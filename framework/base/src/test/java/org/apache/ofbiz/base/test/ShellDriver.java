/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.apache.ofbiz.base.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a shell script from a test, safely, and reports what it did.
 *
 * <p>One implementation for every suite that drives {@code docker/docker-entrypoint.sh} or any other shipped
 * script, because the obvious way to do it is subtly wrong in two ways that only show up on the day something
 * breaks:
 *
 * <ul>
 * <li><strong>Draining before waiting makes a deadline ineffective.</strong> Reading the child's output to its
 * end before calling {@code waitFor(timeout, unit)} means the read is what blocks, and the read has no
 * deadline: a child that writes nothing and never exits hangs the build for as long as the harness is given,
 * and the timeout that was supposed to catch it is only reached once the child has already finished. Here the
 * output is drained on a thread of its own and the wait comes first, so the deadline is the deadline.</li>
 * <li><strong>A child that outruns its deadline has to be killed.</strong> {@code waitFor} returning
 * {@code false} leaves the process running; nothing else in a test JVM will ever reap it, so a wedged shell
 * survives the test, the class and often the build. Here it is destroyed - forcibly - and only then is its
 * output collected, so the failure message still says what the child managed to print.</li>
 * </ul>
 *
 * <p>Interruption is restored rather than swallowed: a harness that clears the interrupt flag makes a test
 * that is being cancelled look as though it passed.
 *
 * <p>Every run replaces, rather than adds to, the {@code OFBIZ_*} part of the environment. A build agent's own
 * environment may well carry {@code OFBIZ_} values, and inheriting one would make a test's outcome depend on
 * where it ran.
 */
public final class ShellDriver {

    /** How long a driven script may take before it is treated as wedged rather than slow. */
    public static final long DEFAULT_TIMEOUT_SECONDS = 120L;

    private ShellDriver() { }

    /**
     * What one run of a script produced.
     *
     * @param exitCode the exit status, or {@code -1} when the run was killed for outrunning its deadline
     * @param output everything the run wrote to stdout and stderr, interleaved
     * @param timedOut whether the run was killed rather than allowed to finish
     */
    public record Run(int exitCode, String output, boolean timedOut) {

        /**
         * Reports whether the run finished successfully.
         *
         * @return true when it exited zero within its deadline
         */
        public boolean succeeded() {
            return !timedOut && exitCode == 0;
        }
    }

    /**
     * Runs a script with the default deadline.
     *
     * @param script the script to run
     * @param workingDirectory the directory to run it in
     * @param environment the {@code OFBIZ_*} and other variables to run it with; every inherited
     *     {@code OFBIZ_} variable is removed first
     * @return what the run produced
     * @throws IOException if the process cannot be started
     */
    public static Run run(Path script, Path workingDirectory, Map<String, String> environment) throws IOException {
        return run(script, workingDirectory, environment, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Runs a script, waits for it, kills it if it outruns its deadline, and collects its output.
     *
     * @param script the script to run
     * @param workingDirectory the directory to run it in
     * @param environment the variables to run it with
     * @param timeoutSeconds how long it may take
     * @return what the run produced
     * @throws IOException if the process cannot be started
     */
    public static Run run(Path script, Path workingDirectory, Map<String, String> environment, long timeoutSeconds)
            throws IOException {
        return run(script, workingDirectory, environment, timeoutSeconds, List.of());
    }

    /**
     * Runs a script with arguments, waits for it, kills it if it outruns its deadline, and collects its
     * output.
     *
     * @param script the script to run
     * @param workingDirectory the directory to run it in
     * @param environment the variables to run it with
     * @param timeoutSeconds how long it may take
     * @param arguments the positional arguments to pass to the script
     * @return what the run produced
     * @throws IOException if the process cannot be started
     */
    public static Run run(Path script, Path workingDirectory, Map<String, String> environment, long timeoutSeconds,
            List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of("bash", script.toString()));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        // Every OFBIZ_ variable is dropped so a value exported into the build's own environment cannot
        // steer a case that did not ask for it; the caller puts back exactly what it wants.
        //
        // SHELLOPTS and BASHOPTS go with them, and for a sharper reason: bash reads SHELLOPTS at start up,
        // so an inherited value containing xtrace switches tracing on before the script under test is even
        // sourced. Since stderr is merged into the stream these tests assert against, that would fill the
        // output with trace lines and make assertions on it fail - or, worse, pass by coincidence. They are
        // read-only inside bash and so cannot be dropped from within the script, which is why it is done here.
        builder.environment().keySet()
                .removeIf(name -> name.startsWith("OFBIZ_") || "SHELLOPTS".equals(name) || "BASHOPTS".equals(name));
        builder.environment().putAll(environment);

        Process process = builder.start();
        StringBuilder collected = new StringBuilder();
        // Drained on its own thread so the wait below is what bounds the run. A daemon thread, so a drain
        // blocked on a child this method has already given up on cannot keep the test JVM alive.
        Thread drain = new Thread(() -> {
            try (InputStream output = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                for (int read = output.read(buffer); read >= 0; read = output.read(buffer)) {
                    synchronized (collected) {
                        collected.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException closed) {
                // The stream closes when the process ends or is destroyed; nothing left to read.
                synchronized (collected) {
                    collected.append("[output stream closed: ").append(closed.getMessage()).append(']');
                }
            }
        }, "shell-driver-drain");
        drain.setDaemon(true);
        drain.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            }
            // Bounded, because a drain blocked in a read the kill has not yet unblocked must not become a
            // second unbounded wait; whatever it has collected by then is what the failure reports.
            drain.join(TimeUnit.SECONDS.toMillis(5L));
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("the shell run was interrupted", interrupted));
        }
        synchronized (collected) {
            return new Run(finished ? process.exitValue() : -1, collected.toString(), !finished);
        }
    }

    /**
     * Writes the entry point as a sourceable library - the whole script with its {@code _main} invocation
     * removed - so that a test can call one of its functions without starting a container.
     *
     * <p>Derived from the shipped file rather than copied into a fixture, which is the point: what is
     * exercised is the script the image runs, so a change to it changes what the test sees.
     *
     * @param workDir the directory to write the library into
     * @param entryPointRelativePath the repository-relative path of the script
     * @return the library to source
     * @throws IOException if the script cannot be read or the library cannot be written
     */
    public static Path sourceableLibrary(Path workDir, String entryPointRelativePath) throws IOException {
        Path library = workDir.resolve("entrypoint-library.sh");
        if (Files.exists(library)) {
            return library;
        }
        List<String> sourced = new ArrayList<>();
        for (String line : Files.readAllLines(repositoryRoot().resolve(entryPointRelativePath),
                StandardCharsets.UTF_8)) {
            if (!"_main \"$@\"".equals(line)) {
                sourced.add(line);
            }
        }
        Files.write(library, sourced, StandardCharsets.UTF_8);
        return library;
    }

    /**
     * Writes a driver script that sources the entry point library and then runs the supplied body.
     *
     * @param workDir the directory to write the driver into
     * @param library the library to source
     * @param body the shell to run after sourcing
     * @return the driver to run
     * @throws IOException if the driver cannot be written
     */
    public static Path driver(Path workDir, Path library, String body) throws IOException {
        Path driver = Files.createTempFile(workDir, "driver-", ".sh");
        Files.writeString(driver, "#!/usr/bin/env bash\n. " + quote(library) + "\n" + body + "\n",
                StandardCharsets.UTF_8);
        return driver;
    }

    /**
     * Quotes a path for a shell, so a temporary directory holding a space or a quote cannot break a driver.
     *
     * @param path the path to quote
     * @return the quoted path
     */
    public static String quote(Path path) {
        return "'" + path.toString().replace("'", "'\\''") + "'";
    }

    /**
     * Reports whether a POSIX shell can be run at all, so a suite can say so rather than fail obscurely.
     *
     * @return true when {@code bash} can be executed
     */
    public static boolean isBashAvailable() {
        try {
            ProcessBuilder builder = new ProcessBuilder("bash", "-c", "exit 0");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            // The probe's own output is closed rather than read: the point is only whether a shell can be
            // started at all, and leaving the pipe open would leak a file descriptor per call. Named and
            // used, rather than opened and ignored, so this does not compile to a warning.
            InputStream output = process.getInputStream();
            try {
                boolean finished = process.waitFor(30L, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return false;
                }
                return process.exitValue() == 0;
            } finally {
                output.close();
            }
        } catch (IOException unavailable) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Locates the repository root by walking up from the working directory, so a suite runs from any module.
     *
     * @return the repository root
     */
    public static Path repositoryRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("dependencies.gradle"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("could not locate the repository root from "
                    + Paths.get("").toAbsolutePath());
        }
        return candidate;
    }

    /**
     * Builds a mutable environment map, so a caller can supply the common part and then break one value.
     *
     * @param pairs alternating names and values
     * @return the environment
     */
    public static Map<String, String> environment(String... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("an environment is built from name and value pairs");
        }
        Map<String, String> environment = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            environment.put(pairs[index], pairs[index + 1]);
        }
        return environment;
    }
}

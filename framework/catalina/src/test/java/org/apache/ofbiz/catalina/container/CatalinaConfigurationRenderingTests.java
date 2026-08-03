/*
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
 */
package org.apache.ofbiz.catalina.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.apache.ofbiz.base.test.ShellDriver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Executable contract of the container's load-balancer renderer: the step that turns
 * {@code OFBIZ_JVM_ROUTE}, {@code OFBIZ_SSL_ACCELERATOR_PORT},
 * {@code OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS} and {@code OFBIZ_ENABLE_AJP_PORT} into the engine
 * declarations of {@code framework/catalina/ofbiz-component.xml} at container start.
 *
 * <p>{@link CatalinaContainerDescriptorTests} beside this class establishes what the committed descriptor
 * DECLARES and what {@link org.apache.ofbiz.base.container.ContainerConfig} resolves those declarations to. What
 * neither it nor any other test established is that the entry point actually produces those declarations from an
 * environment - and that is where every failure mode of this renderer lives, because the descriptor is edited in
 * place on a writable layer:
 *
 * <ul>
 * <li>the descriptor declares two near-identical containers, and the substitutions take the FIRST match in the
 * file. Rewriting the test container instead of the served one has no symptom until traffic arrives;</li>
 * <li>{@code ssl-accelerator-port} decides whether a forwarded plain-HTTP request is treated as
 * <em>secure</em>. A value that survives validation but names a port the serving container does not listen on
 * installs a valve that marks nothing, and every absolute URL silently reverts to {@code http://};</li>
 * <li>because the file is edited rather than regenerated, a variable that is REMOVED must actually restore the
 * committed default. If it does not, each of these settings is a one-way door: an instance keeps a jvm-route,
 * an accelerator port or a cross-subdomain valve that the deployment no longer asks for.</li>
 * </ul>
 *
 * <p>So each case below runs the real {@code render_catalina_configuration} from the shipped
 * {@code docker/docker-entrypoint.sh} - sourced as a library with its {@code _main "$@"} invocation removed -
 * against a private copy of the real descriptor, and then parses the result. The parse is structural: containers
 * are selected by {@code name} plus {@code loaders} among the {@code <container>} children of the root, never by
 * a line number or a substring scan that could match the wrong one.
 *
 * <p>The one thing the driver supplies is {@code CATALINA_COMPONENT_DESCRIPTOR}, which the script otherwise
 * fixes at the container's absolute {@code /ofbiz/framework/catalina/ofbiz-component.xml}. It is the script's
 * own named seam for the file it edits, and pointing it at a copy is what keeps these cases from editing the
 * repository they are asserting about.
 *
 * @see CatalinaContainerDescriptorTests
 */
public final class CatalinaConfigurationRenderingTests {

    /** The shipped script under test. */
    private static final String ENTRY_POINT = "docker/docker-entrypoint.sh";

    /** The descriptor the renderer edits in place. */
    private static final String DESCRIPTOR = "framework/catalina/ofbiz-component.xml";

    /** The schema the rendered descriptor must still satisfy. */
    private static final String COMPONENT_SCHEMA = "framework/base/dtd/ofbiz-component.xsd";

    private static final String PRODUCTION_CONTAINER = "catalina-container";
    private static final String TEST_CONTAINER = "catalina-container-test";
    private static final String ENGINE_PROPERTY = "default-server";
    private static final String JVM_ROUTE = "jvm-route";
    private static final String SSL_ACCELERATOR_PORT = "ssl-accelerator-port";
    private static final String CROSS_SUBDOMAIN_SESSIONS = "enable-cross-subdomain-sessions";
    private static final String AJP_CONNECTOR = "ajp-connector";

    /** The committed defaults every withdrawal must restore, in the order the renderer applies them. */
    private static final Map<String, String> COMMITTED_DEFAULTS = Map.of(
            JVM_ROUTE, "jvm1",
            SSL_ACCELERATOR_PORT, "",
            CROSS_SUBDOMAIN_SESSIONS, "false");

    @BeforeAll
    public static void requireAShell() {
        assumeTrue(ShellDriver.isBashAvailable(), "a POSIX shell is required to drive the container entry point");
    }

    /**
     * The whole of MJ-07's positive half: non-default values for all three settings reach the production
     * engine's declarations, and the value the renderer normalised is the value that is declared.
     *
     * <p>{@code YES} rather than {@code true} for the boolean on purpose. The renderer accepts
     * {@code yes}/{@code no} and {@code 1}/{@code 0} in any letter case and normalises them before anything is
     * written, and {@code CatalinaContainer} understands only {@code true} and {@code false} - so what has to be
     * asserted is the normalised form in the file, not the form that was supplied.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the renderer cannot be driven
     */
    @Test
    public void nonDefaultValuesReachTheProductionEngineNormalisedAsTheContainerUnderstandsThem(
            @TempDir Path tempDir) throws Exception {
        Path descriptor = prepareDescriptor(tempDir);

        RendererRun run = render(tempDir, descriptor, ShellDriver.environment(
                "OFBIZ_JVM_ROUTE", "instance-7.eu-west-1_a",
                "OFBIZ_SSL_ACCELERATOR_PORT", "8080",
                "OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS", "YES"));

        assertTrue(run.succeeded(), "a valid load-balancer configuration must render. Output:\n" + run.output());
        Element root = parse(descriptor);
        assertEquals("instance-7.eu-west-1_a", enginePropertyValue(root, PRODUCTION_CONTAINER, JVM_ROUTE),
                "the sticky-session route the balancer routes on must be the one that was supplied");
        assertEquals("8080", enginePropertyValue(root, PRODUCTION_CONTAINER, SSL_ACCELERATOR_PORT),
                "the accelerator port must be the local port forwarded traffic arrives on");
        assertEquals("true", enginePropertyValue(root, PRODUCTION_CONTAINER, CROSS_SUBDOMAIN_SESSIONS),
                "[YES] must be normalised to [true] before it is written: CatalinaContainer parses this value"
                        + " and understands only true and false");
    }

    /**
     * Only the production container is rewritten. The descriptor declares two containers whose property trees
     * are near identical, the substitutions take the first match in the file, and the test container is the one
     * {@code gradlew testIntegration} loads - so rewriting it would change the behaviour of the integration tier
     * while leaving the served instance on its committed defaults, with no symptom until traffic arrived.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the renderer cannot be driven
     */
    @Test
    public void onlyTheProductionContainerIsRewrittenAndTheTestContainerKeepsItsCommittedValues(
            @TempDir Path tempDir) throws Exception {
        Path descriptor = prepareDescriptor(tempDir);
        Element committed = parse(repositoryRoot().resolve(DESCRIPTOR));
        String committedTestContainer = serialisedContainer(committed, TEST_CONTAINER);

        RendererRun run = render(tempDir, descriptor, ShellDriver.environment(
                "OFBIZ_JVM_ROUTE", "production-only",
                "OFBIZ_SSL_ACCELERATOR_PORT", "8080",
                "OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS", "true",
                "OFBIZ_ENABLE_AJP_PORT", "true"));

        assertTrue(run.succeeded(), "the render must succeed. Output:\n" + run.output());
        Element root = parse(descriptor);
        assertEquals("production-only", enginePropertyValue(root, PRODUCTION_CONTAINER, JVM_ROUTE),
                "the production container is the one that must change");
        assertEquals(committedTestContainer, serialisedContainer(root, TEST_CONTAINER),
                "the '" + TEST_CONTAINER + "' block must be identical to the committed one after a render: it is"
                        + " the container the integration tier loads, and every substitution here is positional");
        assertEquals("jvm1", enginePropertyValue(root, TEST_CONTAINER, JVM_ROUTE),
                "the test container declares jvm-route too, and the renderer must not have taken that match");
    }

    /**
     * Withdrawal, which is the property that makes these settings something other than one-way doors.
     *
     * <p>The descriptor lives on the writable layer, so a conditional rewrite would leave the last value it ever
     * wrote in place forever: an operator who set an accelerator port once and then removed it would keep an
     * instance that marks forwarded requests secure, and one who gave an instance a distinct jvm-route would keep
     * that route after the variable was withdrawn - so a replacement instance would restart with the identity of
     * the instance it replaced. Reconstructing all three from the committed defaults on every start is what makes
     * removing a variable mean what it says, and the assertion is byte-level because that is the only way to
     * establish that nothing at all was left behind.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if either render cannot be driven
     */
    @Test
    public void withdrawingEveryVariableRestoresTheCommittedDescriptorExactly(@TempDir Path tempDir)
            throws Exception {
        Path descriptor = prepareDescriptor(tempDir);
        String committed = Files.readString(repositoryRoot().resolve(DESCRIPTOR), StandardCharsets.UTF_8);

        RendererRun configured = render(tempDir, descriptor, ShellDriver.environment(
                "OFBIZ_JVM_ROUTE", "instance-7",
                "OFBIZ_SSL_ACCELERATOR_PORT", "8009",
                "OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS", "true",
                "OFBIZ_ENABLE_AJP_PORT", "true"));
        assertTrue(configured.succeeded(), "the configuring render must succeed. Output:\n" + configured.output());
        assertFalse(Files.readString(descriptor, StandardCharsets.UTF_8).equals(committed),
                "the configuring render must have changed the descriptor, or the restoration below asserts"
                        + " nothing at all");

        RendererRun withdrawn = render(tempDir, descriptor, ShellDriver.environment());

        assertTrue(withdrawn.succeeded(), "withdrawing every variable must succeed. Output:\n"
                + withdrawn.output());
        assertEquals(committed, Files.readString(descriptor, StandardCharsets.UTF_8),
                "a start with no load-balancer variable must leave the descriptor byte for byte as committed:"
                        + " anything left behind is a setting the deployment no longer asks for and cannot"
                        + " remove");
    }

    /**
     * Each setting is restored independently. Without this, the wholesale restoration above could pass while a
     * single variable that was removed on its own kept its previous value - which is the shape the mistake
     * actually takes, because operators change one variable at a time.
     *
     * @param variable the variable to withdraw on its own
     * @param property the declaration it must restore
     * @param configured a valid non-default value for it
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if either render cannot be driven
     */
    @ParameterizedTest(name = "withdrawing {0} restores {1}")
    @CsvSource({
        "OFBIZ_JVM_ROUTE,jvm-route,instance-7",
        "OFBIZ_SSL_ACCELERATOR_PORT,ssl-accelerator-port,8080",
        "OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS,enable-cross-subdomain-sessions,true",
    })
    public void withdrawingOneVariableOnItsOwnRestoresItsCommittedDefault(String variable, String property,
            String configured, @TempDir Path tempDir) throws Exception {
        Path descriptor = prepareDescriptor(tempDir);

        assertTrue(render(tempDir, descriptor, ShellDriver.environment(variable, configured)).succeeded(),
                "setting " + variable + " must succeed");
        assertEquals(configured, enginePropertyValue(parse(descriptor), PRODUCTION_CONTAINER, property),
                property + " must first hold the configured value, or the withdrawal below proves nothing");

        assertTrue(render(tempDir, descriptor, ShellDriver.environment()).succeeded(),
                "withdrawing " + variable + " must succeed");

        assertEquals(COMMITTED_DEFAULTS.get(property),
                enginePropertyValue(parse(descriptor), PRODUCTION_CONTAINER, property),
                "removing " + variable + " must restore the committed " + property + ", not leave the value the"
                        + " previous start wrote");
    }

    /**
     * Rotation. A new value replaces the previous one rather than being added beside it, and the count of
     * declarations in the whole document does not move - a second declaration would be honoured by
     * {@code ContainerConfig} according to parse order rather than intent, and would grow on every restart.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if any render cannot be driven
     */
    @Test
    public void rotatingEveryValueReplacesItInPlaceWithoutAccumulatingDeclarations(@TempDir Path tempDir)
            throws Exception {
        Path descriptor = prepareDescriptor(tempDir);
        Map<String, Integer> committedCounts = declarationCounts(parse(repositoryRoot().resolve(DESCRIPTOR)));

        for (String route : List.of("instance-1", "instance-2", "instance-3")) {
            RendererRun run = render(tempDir, descriptor, ShellDriver.environment(
                    "OFBIZ_JVM_ROUTE", route,
                    "OFBIZ_SSL_ACCELERATOR_PORT", "8080",
                    "OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS", "true"));

            assertTrue(run.succeeded(), "rotating to [" + route + "] must succeed. Output:\n" + run.output());
            Element root = parse(descriptor);
            assertEquals(route, enginePropertyValue(root, PRODUCTION_CONTAINER, JVM_ROUTE),
                    "the descriptor must hold the newest route, not an earlier one");
            assertEquals(committedCounts, declarationCounts(root),
                    "rotating must not change how many times each property is declared: an accumulated"
                            + " declaration would grow the descriptor on every restart and its value would be"
                            + " selected by parse order");
        }
    }

    /**
     * An invalid value is refused before anything is written, and the descriptor is left exactly as it was.
     *
     * <p>The reason the refusal has to happen here rather than in the container is that every one of these
     * reaches Java as a string in a configuration file. {@code CatalinaContainer} parses the accelerator port
     * with {@code Integer.valueOf}, so an unparseable value surfaces as a {@code NumberFormatException} from
     * deep inside container loading; a jvm-route carrying a character a cookie value cannot hold corrupts the
     * session id it is appended to; and an unparseable boolean is simply read as false. Each becomes one legible
     * refusal naming the variable instead.
     *
     * @param variable the variable to break
     * @param value the invalid value
     * @param diagnostic the words the refusal must carry
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the renderer cannot be driven
     */
    @ParameterizedTest(name = "{0}={1} is refused")
    @CsvSource({
        // A jvm-route is appended to the session id, so it must survive being part of a cookie value and of a
        // balancer's routing table: letters, digits, '.', '_' and '-' only.
        "OFBIZ_JVM_ROUTE,with/slash,only letters",
        "OFBIZ_JVM_ROUTE,with:colon,only letters",
        "OFBIZ_JVM_ROUTE,with space,only letters",
        "OFBIZ_JVM_ROUTE,route#1,only letters",
        "OFBIZ_JVM_ROUTE,route\"quote,only letters",
        "OFBIZ_JVM_ROUTE,route<angle>,only letters",
        // The accelerator port: not an integer, out of range, and written in a form that is not plain decimal.
        "OFBIZ_SSL_ACCELERATOR_PORT,abc,plain decimal integer",
        "OFBIZ_SSL_ACCELERATOR_PORT,8443x,plain decimal integer",
        "OFBIZ_SSL_ACCELERATOR_PORT,-1,plain decimal integer",
        "OFBIZ_SSL_ACCELERATOR_PORT,8080.0,plain decimal integer",
        "OFBIZ_SSL_ACCELERATOR_PORT,0x1F90,plain decimal integer",
        "OFBIZ_SSL_ACCELERATOR_PORT,08080,without a leading zero",
        "OFBIZ_SSL_ACCELERATOR_PORT,0,must be between 1 and 65535",
        "OFBIZ_SSL_ACCELERATOR_PORT,65536,must be between 1 and 65535",
        "OFBIZ_SSL_ACCELERATOR_PORT,99999,must be between 1 and 65535",
        // A boolean the container cannot read as one.
        "OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS,maybe,must be a boolean",
        "OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS,2,must be a boolean",
        "OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS,on,must be a boolean",
        "OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS,t,must be a boolean",
    })
    public void anInvalidValueIsRefusedAndLeavesTheDescriptorExactlyAsItWas(String variable, String value,
            String diagnostic, @TempDir Path tempDir) throws Exception {
        Path descriptor = prepareDescriptor(tempDir);
        String before = Files.readString(descriptor, StandardCharsets.UTF_8);

        RendererRun run = render(tempDir, descriptor, ShellDriver.environment(variable, value));

        assertFalse(run.succeeded(), variable + "=[" + value + "] must be refused. Output:\n" + run.output());
        assertTrue(run.output().contains(variable), "the refusal must name the variable so the operator can find"
                + " it. Output:\n" + run.output());
        assertTrue(run.output().contains(diagnostic), "the refusal must say what was wrong, and must contain ["
                + diagnostic + "]. Output:\n" + run.output());
        assertEquals(before, Files.readString(descriptor, StandardCharsets.UTF_8),
                "a refused value must leave no residual edit in the descriptor: this file decides whether"
                        + " forwarded requests are treated as secure, and a half applied edit would be the"
                        + " configuration the next start serves on");
    }

    /**
     * The accelerator port must name a port the SERVING container listens on.
     *
     * <p>{@code SslAcceleratorValve} marks a request secure purely because it arrived on this local port, so a
     * value naming a port nothing receives traffic on installs a valve that marks nothing - the setting looks
     * validated and does nothing, and every absolute URL silently reverts to {@code http://}. {@code 8010} is
     * the sharpest case: it is a real, declared port, but it belongs to the TEST container's AJP connector
     * alone, so a check that searched the whole descriptor would accept it.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the renderer cannot be driven
     */
    @Test
    public void aPortDeclaredOnlyByTheTestContainerIsRefusedAndTheServingPortsAreNamed(@TempDir Path tempDir)
            throws Exception {
        Path descriptor = prepareDescriptor(tempDir);

        RendererRun run = render(tempDir, descriptor,
                ShellDriver.environment("OFBIZ_SSL_ACCELERATOR_PORT", "8010"));

        assertFalse(run.succeeded(), "8010 is declared only by the test container's AJP connector, so the serving"
                + " instance receives nothing on it. Output:\n" + run.output());
        // Worded against the container block the renderer actually consults rather than against connectors in
        // general: the descriptor declares two containers, and naming the serving one in the refusal is what
        // tells an operator why a port the file plainly contains was still rejected.
        assertTrue(run.output().contains("matches no port the 'name=\"catalina-container\"' block of"),
                "the refusal must say that the port matches no port the serving container listens on. Output:\n"
                        + run.output());
        for (String declared : List.of("8009", "8080", "8443")) {
            assertTrue(run.output().contains(declared), "the refusal must list the ports the serving container"
                    + " does declare, and is missing " + declared + ". Output:\n" + run.output());
        }
        assertFalse(run.output().contains("8010,"), "the refusal must not list the test container's port among"
                + " the ports it would have accepted. Output:\n" + run.output());
    }

    /**
     * Every port the serving container really declares is accepted, which is the other half of the check above:
     * without it, the refusal could be implemented as "only the http connector's port" and would then reject a
     * deployment that forwards to the AJP or the HTTPS connector.
     *
     * @param port a port the production container declares
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the renderer cannot be driven
     */
    @ParameterizedTest(name = "the serving port {0} is accepted")
    @CsvSource({"8009", "8080", "8443"})
    public void everyPortTheServingContainerDeclaresIsAccepted(String port, @TempDir Path tempDir)
            throws Exception {
        Path descriptor = prepareDescriptor(tempDir);

        RendererRun run = render(tempDir, descriptor, ShellDriver.environment("OFBIZ_SSL_ACCELERATOR_PORT", port));

        assertTrue(run.succeeded(), "port " + port + " is declared by the serving container and must be accepted."
                + " Output:\n" + run.output());
        assertEquals(port, enginePropertyValue(parse(descriptor), PRODUCTION_CONTAINER, SSL_ACCELERATOR_PORT),
                "the accepted port must be the one declared");
    }

    /**
     * The AJP bind address is inserted into the production connector only, is inserted at most once however many
     * times the container restarts, and is removed again when the variable is withdrawn.
     *
     * <p>All three properties failed in the unscoped edit this replaced: the anchor matches the AJP connector of
     * BOTH containers, nothing checked whether the line was already there - so every restart appended another
     * copy to both - and removing the variable left the connector bound to every interface for the life of the
     * instance.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if any render cannot be driven
     */
    @Test
    public void theAjpBindAddressIsScopedIdempotentAndRemovable(@TempDir Path tempDir) throws Exception {
        Path descriptor = prepareDescriptor(tempDir);
        String committed = Files.readString(repositoryRoot().resolve(DESCRIPTOR), StandardCharsets.UTF_8);

        for (int restart = 1; restart <= 3; restart++) {
            RendererRun run = render(tempDir, descriptor, ShellDriver.environment("OFBIZ_ENABLE_AJP_PORT", "true"));

            assertTrue(run.succeeded(), "restart " + restart + " must succeed. Output:\n" + run.output());
            Element root = parse(descriptor);
            assertEquals(List.of("0.0.0.0"), connectorAddresses(root, PRODUCTION_CONTAINER),
                    "after restart " + restart + " the production AJP connector must carry exactly one bind"
                            + " address: a second copy would be appended on every restart until the connector"
                            + " held a list of duplicates");
            assertEquals(List.of(), connectorAddresses(root, TEST_CONTAINER),
                    "the test container's AJP connector must gain no bind address: the anchor matches it too");
        }

        RendererRun withdrawn = render(tempDir, descriptor, ShellDriver.environment());

        assertTrue(withdrawn.succeeded(), "withdrawing the AJP port must succeed. Output:\n" + withdrawn.output());
        assertEquals(committed, Files.readString(descriptor, StandardCharsets.UTF_8),
                "withdrawing OFBIZ_ENABLE_AJP_PORT must remove the inserted line: leaving it would keep the AJP"
                        + " connector bound to every interface for the life of the instance");
    }

    /**
     * A descriptor whose two containers have been reordered is refused rather than edited.
     *
     * <p>Every edit here identifies the production container as "the first match in the file". If the blocks
     * were ever reordered, those edits would start rewriting the test container and leave the served one on its
     * committed defaults - a failure with no symptom until traffic arrived. It is cheaper to refuse, and the
     * refusal is what makes the positional assumption safe to rely on.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the renderer cannot be driven
     */
    @Test
    public void aDescriptorWhoseContainersAreReorderedIsRefusedRatherThanEditedInTheWrongPlace(
            @TempDir Path tempDir) throws Exception {
        Path descriptor = prepareDescriptor(tempDir);
        List<String> lines = new ArrayList<>(Files.readAllLines(descriptor, StandardCharsets.UTF_8));
        List<String> reordered = new ArrayList<>();
        // The two container elements are swapped as whole blocks, so what is refused is the ORDER and not a
        // malformed file: a parse of the result below would succeed just as well as a parse of the original.
        int production = indexOfLineContaining(lines, "<container name=\"" + PRODUCTION_CONTAINER + "\"");
        int test = indexOfLineContaining(lines, "<container name=\"" + TEST_CONTAINER + "\"");
        int endOfProduction = indexOfClosingContainer(lines, production);
        int endOfTest = indexOfClosingContainer(lines, test);
        reordered.addAll(lines.subList(0, production));
        reordered.addAll(lines.subList(test, endOfTest + 1));
        reordered.addAll(lines.subList(production, endOfProduction + 1));
        reordered.addAll(lines.subList(endOfTest + 1, lines.size()));
        Files.write(descriptor, reordered, StandardCharsets.UTF_8);
        String before = Files.readString(descriptor, StandardCharsets.UTF_8);

        RendererRun run = render(tempDir, descriptor, ShellDriver.environment("OFBIZ_JVM_ROUTE", "instance-7"));

        assertFalse(run.succeeded(), "a reordered descriptor must be refused: the edits take the first match in"
                + " the file and would rewrite the test container. Output:\n" + run.output());
        assertTrue(run.output().contains("before the"), "the refusal must say which container was found first."
                + " Output:\n" + run.output());
        assertEquals(before, Files.readString(descriptor, StandardCharsets.UTF_8),
                "a refused descriptor must not be edited at all");
    }

    /**
     * A descriptor that declares the production container twice is refused, for the same positional reason - and
     * because the second declaration is the one an operator would not be looking at.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the renderer cannot be driven
     */
    @Test
    public void aDescriptorThatDeclaresTheProductionContainerTwiceIsRefused(@TempDir Path tempDir)
            throws Exception {
        Path descriptor = prepareDescriptor(tempDir);
        List<String> lines = new ArrayList<>(Files.readAllLines(descriptor, StandardCharsets.UTF_8));
        int production = indexOfLineContaining(lines, "<container name=\"" + PRODUCTION_CONTAINER + "\"");
        int endOfProduction = indexOfClosingContainer(lines, production);
        lines.addAll(endOfProduction + 1, new ArrayList<>(lines.subList(production, endOfProduction + 1)));
        Files.write(descriptor, lines, StandardCharsets.UTF_8);

        RendererRun run = render(tempDir, descriptor, ShellDriver.environment("OFBIZ_JVM_ROUTE", "instance-7"));

        assertFalse(run.succeeded(), "a duplicated production container must be refused. Output:\n"
                + run.output());
        assertTrue(run.output().contains("2 times"), "the refusal must report how many declarations were found."
                + " Output:\n" + run.output());
    }

    /**
     * A renamed or reformatted anchor is refused rather than silently doing nothing. The renderer substitutes a
     * single-line {@code <property name="..." value="..."/>}; if the declaration were ever split across lines or
     * renamed, the substitution would match nothing and the setting an operator supplied would be discarded.
     *
     * @param property the declaration to remove from the descriptor
     * @param variable the variable whose value would then be discarded
     * @param value a valid value for it
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the renderer cannot be driven
     */
    @ParameterizedTest(name = "a missing {0} anchor is refused")
    @CsvSource({
        "jvm-route,OFBIZ_JVM_ROUTE,instance-7",
        "ssl-accelerator-port,OFBIZ_SSL_ACCELERATOR_PORT,8080",
        "enable-cross-subdomain-sessions,OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS,true",
    })
    public void aMissingAnchorIsRefusedRatherThanDiscardingTheSuppliedValue(String property, String variable,
            String value, @TempDir Path tempDir) throws Exception {
        Path descriptor = prepareDescriptor(tempDir);
        List<String> kept = new ArrayList<>();
        for (String line : Files.readAllLines(descriptor, StandardCharsets.UTF_8)) {
            if (!line.contains("<property name=\"" + property + "\" value=")) {
                kept.add(line);
            }
        }
        Files.write(descriptor, kept, StandardCharsets.UTF_8);

        RendererRun run = render(tempDir, descriptor, ShellDriver.environment(variable, value));

        assertFalse(run.succeeded(), "a descriptor with no " + property + " anchor must be refused: the"
                + " substitution would match nothing and " + variable + " would be discarded. Output:\n"
                + run.output());
        assertTrue(run.output().contains(property), "the refusal must name the property whose anchor is missing."
                + " Output:\n" + run.output());
    }

    /**
     * A descriptor with no AJP connector anchor is refused. The bind address is inserted after a named line, so
     * a line that has been removed or reformatted would make the insertion match nothing - and
     * {@code OFBIZ_ENABLE_AJP_PORT} would then silently leave the connector bound to every interface, which is
     * the opposite of what supplying it asks for.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the renderer cannot be driven
     */
    @Test
    public void aDescriptorWithNoAjpConnectorAnchorIsRefusedRatherThanSilentlyInsertingNothing(
            @TempDir Path tempDir) throws Exception {
        Path descriptor = prepareDescriptor(tempDir);
        Files.writeString(descriptor, Files.readString(descriptor, StandardCharsets.UTF_8)
                .replace("<property name=\"ajp-connector\" value=\"connector\">",
                        "<property name=\"ajp-listener\" value=\"connector\">"), StandardCharsets.UTF_8);

        RendererRun run = render(tempDir, descriptor, ShellDriver.environment("OFBIZ_ENABLE_AJP_PORT", "true"));

        assertFalse(run.succeeded(), "a missing AJP anchor must be refused. Output:\n" + run.output());
        assertTrue(run.output().contains("does not declare the AJP connector anchor"),
                "the refusal must name the anchor it could not find. Output:\n" + run.output());
    }

    /**
     * A production AJP connector already carrying more than one bind address is refused rather than left as it
     * is. That is exactly the state the unscoped edit this replaced produced - one appended copy per restart -
     * so a volume written by an older image can already be in it, and Tomcat would bind to whichever of the
     * duplicates the container parser happened to keep.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the renderer cannot be driven
     */
    @Test
    public void aProductionConnectorAlreadyCarryingDuplicateBindAddressesIsRefused(@TempDir Path tempDir)
            throws Exception {
        Path descriptor = prepareDescriptor(tempDir);
        Files.writeString(descriptor, Files.readString(descriptor, StandardCharsets.UTF_8).replaceFirst(
                Pattern.quote("<property name=\"ajp-connector\" value=\"connector\">"),
                Matcher.quoteReplacement(
                        "<property name=\"ajp-connector\" value=\"connector\">\n"
                        + "            <property name=\"address\" value=\"0.0.0.0\"/>\n"
                        + "            <property name=\"address\" value=\"127.0.0.1\"/>")),
                StandardCharsets.UTF_8);

        RendererRun run = render(tempDir, descriptor, ShellDriver.environment("OFBIZ_ENABLE_AJP_PORT", "true"));

        assertFalse(run.succeeded(), "duplicate bind addresses must be refused. Output:\n" + run.output());
        assertTrue(run.output().contains("declares its bind address"), "the refusal must say what it found."
                + " Output:\n" + run.output());
    }

    /**
     * Withdrawing the AJP port from a descriptor carrying duplicate bind addresses is refused too, because the
     * removal takes one line and one only - so a survivor would keep the connector bound to every interface
     * while the environment says it should not be. The post-removal read-back is what catches that.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the renderer cannot be driven
     */
    @Test
    public void withdrawingTheAjpPortIsRefusedWhenARemovedBindAddressWouldSurvive(@TempDir Path tempDir)
            throws Exception {
        Path descriptor = prepareDescriptor(tempDir);
        Files.writeString(descriptor, Files.readString(descriptor, StandardCharsets.UTF_8).replaceFirst(
                Pattern.quote("<property name=\"ajp-connector\" value=\"connector\">"),
                Matcher.quoteReplacement(
                        "<property name=\"ajp-connector\" value=\"connector\">\n"
                        + "            <property name=\"address\" value=\"0.0.0.0\"/>\n"
                        + "            <property name=\"address\" value=\"0.0.0.0\"/>")),
                StandardCharsets.UTF_8);

        RendererRun run = render(tempDir, descriptor, ShellDriver.environment());

        assertFalse(run.succeeded(), "a surviving bind address must be refused. Output:\n" + run.output());
        assertTrue(run.output().contains("still declares a bind address"), "the refusal must say that a bind"
                + " address survived the removal. Output:\n" + run.output());
    }

    /**
     * A descriptor whose production container cannot be isolated is refused before any edit is attempted.
     *
     * <p>The isolation is what every port comparison depends on: the accelerator port is compared against the
     * connectors of the production block ALONE, because the test container declares a port the serving instance
     * receives nothing on. A start tag or closing tag that has been reformatted makes that isolation
     * unreliable, and an unreliable isolation means the accelerator valve could be installed against a port
     * nothing listens on - a setting that looks validated and does nothing.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the renderer cannot be driven
     */
    @Test
    public void aProductionContainerThatCannotBeIsolatedIsRefusedBeforeAnyEditIsAttempted(@TempDir Path tempDir)
            throws Exception {
        Path descriptor = prepareDescriptor(tempDir);
        // Both closing tags removed, so the isolating range runs to the end of the file and closes zero times.
        // The document is no longer well formed, which is the point: the refusal has to come from the shell
        // before it edits anything, because nothing downstream would report it as a configuration error.
        Files.writeString(descriptor, Files.readString(descriptor, StandardCharsets.UTF_8)
                .replace("</container>", "<!-- container end -->"), StandardCharsets.UTF_8);
        String before = Files.readString(descriptor, StandardCharsets.UTF_8);

        RendererRun run = render(tempDir, descriptor, ShellDriver.environment("OFBIZ_JVM_ROUTE", "instance-7"));

        assertFalse(run.succeeded(), "a production container that cannot be isolated must be refused. Output:\n"
                + run.output());
        assertTrue(run.output().contains("does not close exactly once"), "the refusal must say why the isolation"
                + " failed. Output:\n" + run.output());
        assertEquals(before, Files.readString(descriptor, StandardCharsets.UTF_8),
                "the refusal must come before any edit");
    }

    /**
     * A production block that swallows the test container is refused, which is the failure the isolation pattern
     * cannot see for itself.
     *
     * <p>The range pattern cannot match {@code catalina-container-test} today, because it includes the closing
     * quote. If a future rename ever made it match, the TEST container's ports would be accepted as the serving
     * container's - and {@code 8010}, which only the test container declares, would install an accelerator
     * valve against a port that receives no traffic. The check that the isolated element does not contain the
     * test container is what makes that impossible rather than merely unlikely.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the renderer cannot be driven
     */
    @Test
    public void aProductionBlockThatSwallowsTheTestContainerIsRefused(@TempDir Path tempDir) throws Exception {
        Path descriptor = prepareDescriptor(tempDir);
        // The test container's own marker text placed inside the production block, without adding a second
        // production container: the isolation therefore succeeds structurally and contains a block it must not.
        Files.writeString(descriptor, Files.readString(descriptor, StandardCharsets.UTF_8).replaceFirst(
                Pattern.quote("<property name=\"jvm-route\" value=\"jvm1\"/>"),
                Matcher.quoteReplacement(
                        "<property name=\"jvm-route\" value=\"jvm1\"/>\n"
                        + "            <!-- see name=\"catalina-container-test\" below -->")),
                StandardCharsets.UTF_8);

        RendererRun run = render(tempDir, descriptor, ShellDriver.environment("OFBIZ_JVM_ROUTE", "instance-7"));

        assertFalse(run.succeeded(), "a production block containing the test container must be refused."
                + " Output:\n" + run.output());
        assertTrue(run.output().contains("contains the '" + "name=\"" + TEST_CONTAINER + "\"" + "' block"),
                "the refusal must name the block it found inside the isolated element. Output:\n" + run.output());
    }

    /**
     * The rendered descriptor is still a valid component descriptor. The renderer writes operator-supplied text
     * into an XML attribute, and a value carrying {@code &}, {@code <} or a quote would produce a file the
     * container cannot parse at all - which would take the instance down after the configuration step had
     * already reported success.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws Exception if the renderer cannot be driven or the schema cannot be read
     */
    @Test
    public void theRenderedDescriptorStillValidatesAgainstTheComponentSchema(@TempDir Path tempDir)
            throws Exception {
        Path descriptor = prepareDescriptor(tempDir);

        RendererRun run = render(tempDir, descriptor, ShellDriver.environment(
                "OFBIZ_JVM_ROUTE", "instance-7.eu-west-1_a",
                "OFBIZ_SSL_ACCELERATOR_PORT", "8080",
                "OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS", "true",
                "OFBIZ_ENABLE_AJP_PORT", "true"));

        assertTrue(run.succeeded(), "the render must succeed. Output:\n" + run.output());
        SchemaFactory schemas = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Schema schema = schemas.newSchema(repositoryRoot().resolve(COMPONENT_SCHEMA).toFile());
        Validator validator = schema.newValidator();
        validator.validate(new StreamSource(descriptor.toFile()));
    }

    /**
     * The rewrite's own read-back is defence in depth with no reachable trigger, and this says so rather than
     * leaving its absence from the cases above to be inferred.
     *
     * <p>{@code rewrite_catalina_property} substitutes the first single-line declaration and then reads the first
     * one back, so the two always agree unless {@code sed --in-place} silently fails to write - which no
     * environment a test can construct produces: an unwritable descriptor makes {@code sed} report an error and
     * end the script through {@code set -e} before the read-back is reached. The guard is kept because this file
     * decides whether forwarded requests are treated as secure, and a silently unapplied edit would leave the
     * previous value serving. What can be established is that it is still wired into every rewrite rather than
     * defined and bypassed - the failure that would turn defence in depth into no defence at all.
     *
     * @throws IOException if the script cannot be read
     */
    @Test
    public void everyLoadBalancerPropertyIsWrittenThroughTheRewriteThatReadsItsOwnResultBack()
            throws IOException {
        List<String> executable = new ArrayList<>();
        for (String line : Files.readAllLines(repositoryRoot().resolve(ENTRY_POINT), StandardCharsets.UTF_8)) {
            if (!line.stripLeading().startsWith("#")) {
                executable.add(line);
            }
        }

        for (String property : List.of(JVM_ROUTE, SSL_ACCELERATOR_PORT, CROSS_SUBDOMAIN_SESSIONS)) {
            assertTrue(executable.stream().anyMatch(line ->
                    line.contains("rewrite_catalina_property " + property + " ")), property + " must be written"
                    + " through rewrite_catalina_property, which reads its own result back: a direct sed would"
                    + " leave a silently unapplied edit serving the previous value");
        }
        assertTrue(executable.stream().anyMatch(line -> line.contains("did not take effect")),
                "the read-back that refuses an unapplied rewrite must still be present in the script");
        assertEquals(0L, executable.stream()
                .filter(line -> line.contains("sed --in-place"))
                .filter(line -> line.contains("CATALINA") || line.contains("$descriptor"))
                .filter(line -> !line.contains("$anchor") && !line.contains("$addressAnchor"))
                .count(), "no load-balancer property may be written into the descriptor by a bare sed: only the"
                        + " AJP bind address is edited outside rewrite_catalina_property, and it has a read-back"
                        + " of its own");
    }

    /**
     * Parses the descriptor the way the tests beside this class do, so both read the same document model.
     *
     * @param descriptor the file to parse
     * @return the document element
     * @throws Exception if the file cannot be parsed
     */
    private static Element parse(Path descriptor) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Document document = factory.newDocumentBuilder().parse(descriptor.toFile());
        Element root = document.getDocumentElement();
        assertEquals("ofbiz-component", root.getLocalName(), "descriptor root element");
        assertNull(root.getNamespaceURI(), "the component descriptor grammar has no target namespace");
        return root;
    }

    /**
     * Reads one engine property of one container, selected structurally.
     *
     * @param root the parsed descriptor
     * @param container the container name
     * @param property the engine property name
     * @return its declared value
     */
    private static String enginePropertyValue(Element root, String container, String property) {
        Element engine = propertyChild(containerElement(root, container), ENGINE_PROPERTY);
        Element declaration = propertyChild(engine, property);
        assertTrue(declaration.hasAttribute("value"), property + " must carry a value attribute");
        return declaration.getAttribute("value");
    }

    /**
     * Counts, for the whole document, how many times each rewritten property is declared.
     *
     * @param root the parsed descriptor
     * @return the counts, keyed by property name
     */
    private static Map<String, Integer> declarationCounts(Element root) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String property : List.of(JVM_ROUTE, SSL_ACCELERATOR_PORT, CROSS_SUBDOMAIN_SESSIONS)) {
            int found = 0;
            NodeList all = root.getElementsByTagName("property");
            for (int index = 0; index < all.getLength(); index++) {
                if (property.equals(((Element) all.item(index)).getAttribute("name"))) {
                    found++;
                }
            }
            counts.put(property, found);
        }
        return counts;
    }

    /**
     * Lists the bind addresses declared by one container's AJP connector, in document order.
     *
     * @param root the parsed descriptor
     * @param container the container name
     * @return the declared addresses, empty when none is declared
     */
    private static List<String> connectorAddresses(Element root, String container) {
        Element connector = propertyChild(containerElement(root, container), AJP_CONNECTOR);
        List<String> addresses = new ArrayList<>();
        for (Element child : childElements(connector)) {
            if ("address".equals(child.getAttribute("name"))) {
                addresses.add(child.getAttribute("value"));
            }
        }
        return addresses;
    }

    /**
     * Renders one container element back to a comparable string, so a whole block can be asserted unchanged.
     *
     * @param root the parsed descriptor
     * @param container the container name
     * @return a stable textual rendering of the element's structure and attributes
     */
    private static String serialisedContainer(Element root, String container) {
        StringBuilder rendered = new StringBuilder();
        appendElement(containerElement(root, container), rendered, 0);
        return rendered.toString();
    }

    /**
     * Appends an element and its descendants to a builder in a stable, comment-free and whitespace-free form.
     *
     * <p>Comments and text nodes are deliberately excluded: what has to be compared is the CONFIGURATION the
     * container reads, and a reflowed comment is not a configuration change.
     *
     * @param element the element to render
     * @param rendered the builder to append to
     * @param depth the current nesting depth
     */
    private static void appendElement(Element element, StringBuilder rendered, int depth) {
        rendered.append("  ".repeat(depth)).append('<').append(element.getLocalName());
        List<String> attributes = new ArrayList<>();
        for (int index = 0; index < element.getAttributes().getLength(); index++) {
            Node attribute = element.getAttributes().item(index);
            attributes.add(attribute.getNodeName() + "=\"" + attribute.getNodeValue() + '"');
        }
        attributes.sort(String::compareTo);
        attributes.forEach(attribute -> rendered.append(' ').append(attribute));
        rendered.append(">\n");
        for (Element child : childElements(element)) {
            appendElement(child, rendered, depth + 1);
        }
    }

    /**
     * Finds one {@code <container>} child of the root by name.
     *
     * @param root the parsed descriptor
     * @param container the container name
     * @return the element
     */
    private static Element containerElement(Element root, String container) {
        for (Element child : childElements(root)) {
            if ("container".equals(child.getLocalName()) && container.equals(child.getAttribute("name"))) {
                return child;
            }
        }
        throw new IllegalStateException("the descriptor declares no container named " + container);
    }

    /**
     * Finds one {@code <property>} child by name, and asserts there is exactly one.
     *
     * @param parent the element to search
     * @param name the property name
     * @return the element
     */
    private static Element propertyChild(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        for (Element child : childElements(parent)) {
            if ("property".equals(child.getLocalName()) && name.equals(child.getAttribute("name"))) {
                found.add(child);
            }
        }
        assertEquals(1, found.size(), "exactly one <property name=\"" + name + "\"> is expected under <"
                + parent.getLocalName() + " name=\"" + parent.getAttribute("name") + "\">");
        return found.get(0);
    }

    /**
     * Lists the element children of a node, skipping text and comments.
     *
     * @param parent the element to list
     * @return its element children in document order
     */
    private static List<Element> childElements(Element parent) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index).getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) nodes.item(index));
            }
        }
        return children;
    }

    /**
     * Finds the first line containing a fragment.
     *
     * @param lines the file
     * @param fragment the text to find
     * @return the index of the line
     */
    private static int indexOfLineContaining(List<String> lines, String fragment) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).contains(fragment)) {
                return index;
            }
        }
        throw new IllegalStateException("no line contains " + fragment);
    }

    /**
     * Finds the {@code </container>} that closes the block opened at a line.
     *
     * @param lines the file
     * @param opening the index of the opening line
     * @return the index of the closing line
     */
    private static int indexOfClosingContainer(List<String> lines, int opening) {
        for (int index = opening; index < lines.size(); index++) {
            if (lines.get(index).contains("</container>")) {
                return index;
            }
        }
        throw new IllegalStateException("the container opened at line " + (opening + 1) + " is never closed");
    }

    /**
     * Copies the real descriptor into a per-test directory, so the renderer edits a copy and not the repository.
     *
     * @param base the directory to copy into
     * @return the copy
     * @throws IOException if the copy cannot be made
     */
    private static Path prepareDescriptor(Path base) throws IOException {
        Path copy = Files.createDirectories(base.resolve("catalina")).resolve("ofbiz-component.xml");
        Files.copy(repositoryRoot().resolve(DESCRIPTOR), copy);
        return copy;
    }

    /**
     * Runs the shipped {@code render_catalina_configuration} as a black box against a descriptor copy.
     *
     * @param workDir a per-test directory for the generated driver scripts
     * @param descriptor the descriptor to render into
     * @param environment the {@code OFBIZ_*} variables to supply
     * @return the exit code and the combined output
     * @throws IOException if the script cannot be driven
     */
    private static RendererRun render(Path workDir, Path descriptor, Map<String, String> environment)
            throws IOException {
        Path library = ShellDriver.sourceableLibrary(workDir, ENTRY_POINT);
        Path driver = ShellDriver.driver(workDir, library,
                "CATALINA_COMPONENT_DESCRIPTOR=" + ShellDriver.quote(descriptor) + "\n"
                        + "render_catalina_configuration\n");
        ShellDriver.Run run = ShellDriver.run(driver, workDir, new LinkedHashMap<>(environment));
        assertFalse(run.timedOut(), "the catalina renderer did not terminate, output was:\n" + run.output());
        return new RendererRun(run.exitCode(), run.output());
    }

    private static Path repositoryRoot() {
        return ShellDriver.repositoryRoot();
    }

    /** What one black-box execution of the shipped catalina renderer produced. */
    private record RendererRun(int exitCode, String output) {
        boolean succeeded() {
            return exitCode == 0;
        }
    }
}

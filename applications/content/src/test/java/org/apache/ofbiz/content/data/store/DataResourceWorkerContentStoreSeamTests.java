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
package org.apache.ofbiz.content.data.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.content.data.DataResourceWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Asserts that the content storage providers are reached from production code, and that reaching them carries
 * content in both directions without changing anything for a deployment that has not configured one.
 *
 * <p>This is the expectation that makes the whole storage package worth having. A provider nothing calls is not
 * a provider: it compiles, it passes its own tests, and not one byte of real content ever travels through it.
 * Every case below therefore enters through a <strong>public {@link DataResourceWorker} method</strong> - the
 * same entry points the content services and the render pipeline use - and asserts on what arrived at the far
 * side of the provider rather than on what the provider says about itself.
 *
 * <p>Three configurations are exercised, because the requirement has parts that pull in opposite directions:
 * <ul>
 * <li><strong>configured object storage</strong>, where a full write, read, re-read and removal round trip has
 *     to be reachable through unchanged method signatures. The provider is the production
 *     {@link S3ContentStore} with only its client boundary replaced by {@link InMemoryS3Client}, so the code
 *     under test is the deployed code and no network, credential or endpoint takes part;</li>
 * <li><strong>configured filesystem storage</strong>, where the provider and the local working copy can be
 *     literally the same file and reconciling one with the other would rewrite content on every read;</li>
 * <li><strong>configured database storage</strong>, the committed default, where every one of these same entry
 *     points has to behave exactly as it did before the seam existed - same resolution, same failure, same
 *     message, and not so much as a rewritten file.</li>
 * </ul>
 *
 * <p>{@code ofbiz.home} is pointed at a throwaway directory for the duration of each test, because the worker
 * resolves {@code OFBIZ_FILE} locations against it and the deployment's own tree must not be written to. Keys
 * sit under {@code runtime/} because that is one of the subtrees the shipped
 * {@code content.data.ofbiz.file.allowed.paths} permits, so the deployment's allow list is exercised as
 * configured rather than widened to accommodate the test.
 */
public final class DataResourceWorkerContentStoreSeamTests {

    private static final String OFBIZ_HOME = "ofbiz.home";
    private static final String PROVIDER_PROPERTY = "content.store.provider";
    private static final String BUCKET = "ofbiz-content";
    private static final String TYPE_OFBIZ_FILE = "OFBIZ_FILE";
    private static final String TYPE_OFBIZ_FILE_BIN = "OFBIZ_FILE_BIN";
    private static final String STAGING_PREFIX = ".ofbiz-content-store-";
    /** A location shaped the way {@code DataResource.objectInfo} carries deployment-relative content. */
    private static final String OBJECT_INFO = "runtime/uploads/1700000000000/statement.pdf";
    private static final byte[] STORED = "the content the store already held".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OVERWRITTEN = "the content a service wrote through the File".getBytes(StandardCharsets.UTF_8);

    /** A throwaway deployment home; JUnit creates it per test and removes it recursively afterwards. */
    @TempDir
    private Path deploymentHome;

    private String previousOfbizHome;
    private String previousProvider;
    private InMemoryS3Client client;

    @BeforeEach
    public void isolateTheDeploymentAndTheConfiguration() {
        previousOfbizHome = System.getProperty(OFBIZ_HOME);
        System.setProperty(OFBIZ_HOME, deploymentHome.toAbsolutePath().toString());
        previousProvider = storeProperties().getProperty(PROVIDER_PROPERTY);
        ContentStoreFactory.clearCache();
    }

    @AfterEach
    public void restoreTheDeploymentAndTheConfiguration() {
        // The resolution is cached for the life of the JVM and the properties object is shared, so both are put
        // back rather than left as this class found it convenient - otherwise every later test in the same JVM
        // would inherit an object store that no longer exists.
        ContentStoreFactory.clearCache();
        if (previousProvider == null) {
            storeProperties().remove(PROVIDER_PROPERTY);
        } else {
            storeProperties().setProperty(PROVIDER_PROPERTY, previousProvider);
        }
        if (previousOfbizHome == null) {
            System.clearProperty(OFBIZ_HOME);
        } else {
            System.setProperty(OFBIZ_HOME, previousOfbizHome);
        }
    }

    @Test
    public void aResourceHeldOnlyByTheObjectStoreIsResolvedAndMaterialisedForALocalCaller() throws Exception {
        configureObjectStorage();
        client.seed(OBJECT_INFO, STORED);
        File local = deploymentHome.resolve(OBJECT_INFO).toFile();
        assertFalse(local.exists(), "the test must begin with nothing on the local disk");

        File resolved = DataResourceWorker.getContentFile(TYPE_OFBIZ_FILE_BIN, OBJECT_INFO, null);

        // An instance that has never seen this resource can serve it, which is the entire point of moving
        // content off the local disk: any instance behind the load balancer can answer for it.
        assertNotNull(resolved, "the worker must resolve a resource the store holds");
        assertEquals(local.getAbsolutePath(), resolved.getAbsolutePath(), "the resolved path must be the usual local one");
        assertTrue(resolved.exists(), "the content must have been materialised for a caller holding a File");
        assertArrayEquals(STORED, Files.readAllBytes(resolved.toPath()), "the materialised content");
        assertEquals(List.of(local.getName()), namesIn(local.toPath().getParent()),
                "a materialisation must leave no staging artefact behind");
    }

    @Test
    public void contentHeldByNeitherSideIsStillReportedAbsentInExactlyTheOldWay() throws Exception {
        configureObjectStorage();

        FileNotFoundException absent = assertThrows(FileNotFoundException.class, () ->
                DataResourceWorker.getContentFile(TYPE_OFBIZ_FILE_BIN, OBJECT_INFO, null),
                "content that is nowhere must still be reported as absent");

        // The message is unchanged, because callers log it and one of them puts it into a service error.
        // Widening where content may live must not change what "it is not there" looks like.
        assertTrue(absent.getMessage().startsWith("No file found: "), "the absence message: " + absent.getMessage());
        assertEquals(1, client.headObjectCalls(), "the store must have been consulted before absence was declared");
    }

    @Test
    public void aWriteMadeThroughTheResolvedFileIsCarriedIntoTheObjectStore() throws Exception {
        configureObjectStorage();
        client.seed(OBJECT_INFO, STORED);

        // Exactly what the binary-file content services do: resolve the file through the worker, then write to
        // it with a plain stream. Those services are business-logic implementations and are out of scope for
        // modification, so the seam has to notice the write by itself and carry it onwards.
        File resolved = DataResourceWorker.getContentFile(TYPE_OFBIZ_FILE_BIN, OBJECT_INFO, null);
        writeLocally(resolved, OVERWRITTEN);

        File afterWrite = DataResourceWorker.getContentFile(TYPE_OFBIZ_FILE_BIN, OBJECT_INFO, null);

        assertArrayEquals(OVERWRITTEN, Files.readAllBytes(afterWrite.toPath()), "the local copy must keep the newer content");
        assertArrayEquals(OVERWRITTEN, client.stored(OBJECT_INFO),
                "the locally written content must have been published to the store");
    }

    @Test
    public void repeatedResolutionsCostNoUploadAndEachOneServesWhatTheStoreHolds() throws Exception {
        configureObjectStorage();
        client.seed(OBJECT_INFO, STORED);
        DataResourceWorker.getContentFile(TYPE_OFBIZ_FILE_BIN, OBJECT_INFO, null);
        int gets = client.getCalls();
        int puts = client.putCalls();

        DataResourceWorker.getContentFile(TYPE_OFBIZ_FILE_BIN, OBJECT_INFO, null);
        File third = DataResourceWorker.getContentFile(TYPE_OFBIZ_FILE_BIN, OBJECT_INFO, null);

        // A read must never cost an upload: that is what would turn every render of a page carrying an uploaded
        // image into a write, and it is the direction that can actually lose content.
        assertEquals(puts, client.putCalls(), "a repeat resolution must not write the object");

        // Reading it again, on the other hand, is exactly what is required. A local copy trusted because it was
        // fetched earlier is a copy that goes stale as soon as another instance writes, and nothing invalidates
        // it - so each resolution asks the store, once, and serves what the store holds.
        assertEquals(gets + 2, client.getCalls(), "each resolution reads the object exactly once");
        assertArrayEquals(STORED, Files.readAllBytes(third.toPath()), "and serves what the store holds");
    }

    @Test
    public void theExplicitPublishAndRemoveSeamMovesContentAndThenRemovesIt() throws Exception {
        configureObjectStorage();
        File source = deploymentHome.resolve(OBJECT_INFO).toFile();
        writeLocally(source, STORED);

        assertTrue(DataResourceWorker.storeContentFile(TYPE_OFBIZ_FILE, OBJECT_INFO, null, source),
                "publishing must report that the store acted");
        assertArrayEquals(STORED, client.stored(OBJECT_INFO), "the published content");

        assertTrue(DataResourceWorker.removeContentFile(TYPE_OFBIZ_FILE, OBJECT_INFO, null),
                "removal must report that the store acted");
        assertFalse(client.holds(OBJECT_INFO), "the object must be gone");

        // Idempotent, so replayed clean-up is safe: a removal that has already happened is not a failure.
        assertTrue(DataResourceWorker.removeContentFile(TYPE_OFBIZ_FILE, OBJECT_INFO, null),
                "a repeated removal must still succeed");
        assertEquals(2, client.deleteCalls(), "both removals must have reached the store");
    }

    @Test
    public void aRenderReadsStraightOutOfTheObjectStoreWithoutMaterialisingAnything() throws Exception {
        configureObjectStorage();
        String text = "rendered straight out of the object store";
        client.seed(OBJECT_INFO, text.getBytes(StandardCharsets.UTF_8));
        StringBuilder rendered = new StringBuilder();

        DataResourceWorker.renderFile(TYPE_OFBIZ_FILE, OBJECT_INFO, null, rendered);

        assertEquals(text, rendered.toString(), "the rendered content");
        assertEquals(1, client.getCalls(), "the render must have streamed the object exactly once");
        assertFalse(deploymentHome.resolve(OBJECT_INFO).toFile().exists(),
                "a render streams and must not leave a local copy behind");
    }

    @Test
    public void anObjectStoreUploadIsStagedOnRealLocalDiskAndNotSentToAKeyPrefix() throws Exception {
        configureObjectStorage();

        // The provider itself must decline. A bucket has no writable local path, so an object-store provider
        // that answered here would be answering with something the frozen callers cannot use: they open a
        // FileOutputStream inside whatever this returns, and DataServices.createFileMethod additionally
        // requires the LOCAL_FILE form to be absolute. A key prefix satisfies neither.
        assertNull(ContentStoreFactory.resolveUploadPath(null, false),
                "the object-store provider must offer no upload location of its own");
        assertNull(ContentStoreFactory.resolveUploadPath(null, true),
                "not in the absolute form either - a key prefix is not a writable local path");

        String relative = DataResourceWorker.getDataResourceContentUploadPath(false);
        String absolute = DataResourceWorker.getDataResourceContentUploadPath(true);

        // What the caller gets instead is a real, existing, writable directory inside the deployment, which is
        // the only thing an upload can actually be written into. Every property the frozen callers depend on is
        // asserted, so this cannot pass on a prefix-shaped answer: the relative form is deployment relative,
        // the absolute form is absolute and inside the deployment, and both exist before the caller writes.
        assertTrue(relative.startsWith("/runtime/uploads/"), "the deployment-relative form: " + relative);
        assertTrue(Files.isDirectory(deploymentHome.resolve(relative.substring(1))),
                "the staging directory must exist ready for the upload: " + relative);
        assertTrue(Path.of(absolute).isAbsolute(), "the absolute form must be absolute: " + absolute);
        assertTrue(absolute.startsWith(deploymentHome.toAbsolutePath().toString().replace('\\', '/')),
                "the staging location must sit inside the deployment: " + absolute);
        assertTrue(Files.isDirectory(Path.of(absolute)), "the absolute form must exist too: " + absolute);
        assertFalse(relative.contains("content/uploads"),
                "the location must not be the provider's key prefix: " + relative);

        // Each allocation is its own directory, which is what makes publishing everything in one of them safe
        // while other uploads are in flight.
        assertNotEquals(relative, absolute.substring(absolute.length() - relative.length()),
                "two allocations must not share a directory: " + relative + " / " + absolute);

        // And what is written there reaches the bucket under the flat key the read side resolves from the
        // persisted objectInfo, with the staging segment spliced out, so the object stays addressable after the
        // staging directory is gone.
        String objectInfo = relative.substring(1) + "/10000.png";
        writeLocally(deploymentHome.resolve(objectInfo).toFile(), STORED);
        assertTrue(ContentStoreFactory.publishContentFile(TYPE_OFBIZ_FILE_BIN, objectInfo, null),
                "publishing the staged upload must report that the store acted");
        assertArrayEquals(STORED, client.stored("runtime/uploads/10000.png"),
                "the staged upload must arrive in the bucket under the flat key, with the staging segment gone");
    }

    @Test
    public void aFilesystemUploadLocationIsTheProvidersOwnAndIsCreatedReadyForUse() throws Exception {
        configureProvider("filesystem");

        String relative = DataResourceWorker.getDataResourceContentUploadPath(false);
        String absolute = DataResourceWorker.getDataResourceContentUploadPath(true);

        assertEquals(ContentStoreFactory.resolveUploadPath(null, false), relative,
                "the relative upload location must be the provider's");
        assertEquals(ContentStoreFactory.resolveUploadPath(null, true), absolute, "and so must the absolute one");
        assertTrue(relative.startsWith("/runtime/uploads/"), "the deployment-relative form: " + relative);
        assertTrue(absolute.startsWith(deploymentHome.toAbsolutePath().toString().replace('\\', '/')),
                "the location must sit inside the deployment: " + absolute);
        assertTrue(absolute.endsWith(relative), "both forms must name one directory: " + absolute + " / " + relative);
        assertTrue(Files.isDirectory(Path.of(absolute)), "the provider must have created the location it reported");
    }

    @Test
    public void aLocationTheSeamMustNotAcceptIsRefusedBeforeTheStoreIsTouched() throws Exception {
        configureObjectStorage();
        File source = deploymentHome.resolve(OBJECT_INFO).toFile();
        writeLocally(source, STORED);

        // A traversal segment would let one resource address another resource's object, so it is refused
        // outright rather than normalised away and hoped about.
        assertThrows(GeneralException.class, () ->
                DataResourceWorker.storeContentFile(TYPE_OFBIZ_FILE, "runtime/../../etc/shadow", null, source),
                "a traversal segment must be refused");
        // A type whose content lives in the database has no storage key at all, so asking for one is a
        // programming error rather than something to guess at.
        assertThrows(GeneralException.class, () ->
                DataResourceWorker.storeContentFile("ELECTRONIC_TEXT", OBJECT_INFO, null, source),
                "a type that is not file backed must be refused");
        assertThrows(GeneralException.class, () ->
                DataResourceWorker.removeContentFile(TYPE_OFBIZ_FILE, "", null),
                "an empty location must be refused");
        assertEquals(0, client.putCalls(), "nothing may reach the store for a location it must not accept");
        assertEquals(0, client.deleteCalls(), "and nothing may be removed from it either");
    }

    @Test
    public void aLeadingSeparatorAddressesTheSameObjectAsTheRelativeFormDoes() throws Exception {
        configureObjectStorage();
        File source = deploymentHome.resolve(OBJECT_INFO).toFile();
        writeLocally(source, STORED);

        // DataResource.objectInfo is persisted in both forms - the upload path helper hands back a leading
        // separator while the allow-list checks build a relative path - so both have to address one object.
        // If they did not, content written under one form would be invisible under the other.
        DataResourceWorker.storeContentFile(TYPE_OFBIZ_FILE, "/" + OBJECT_INFO, null, source);

        assertArrayEquals(STORED, client.stored(OBJECT_INFO),
                "a leading separator must be trimmed rather than becoming an empty first key segment");
        assertTrue(DataResourceWorker.removeContentFile(TYPE_OFBIZ_FILE, OBJECT_INFO, null), "the relative form must reach it");
        assertFalse(client.holds(OBJECT_INFO), "and must remove the very same object");
    }

    @Test
    public void configuredDatabaseModeResolvesExactlyAsItDidBeforeTheSeamExisted() throws Exception {
        configureProvider("database");
        File local = deploymentHome.resolve(OBJECT_INFO).toFile();

        FileNotFoundException absent = assertThrows(FileNotFoundException.class, () ->
                DataResourceWorker.getContentFile(TYPE_OFBIZ_FILE_BIN, OBJECT_INFO, null),
                "an absent local file must still be reported as absent");
        assertTrue(absent.getMessage().startsWith("No file found: "), "the absence message: " + absent.getMessage());

        writeLocally(local, STORED);
        long lengthBefore = local.length();
        long modifiedBefore = local.lastModified();

        File resolved = DataResourceWorker.getContentFile(TYPE_OFBIZ_FILE_BIN, OBJECT_INFO, null);

        assertEquals(local.getAbsolutePath(), resolved.getAbsolutePath(), "the resolved path must be unchanged");
        assertArrayEquals(STORED, Files.readAllBytes(resolved.toPath()), "the content must be unchanged");
        // Untouched rather than merely equivalent: in the committed default the seam must not so much as
        // rewrite the file, because nothing about this deployment asked for object storage.
        assertEquals(lengthBefore, local.length(), "the local file's length must not have been touched");
        assertEquals(modifiedBefore, local.lastModified(), "nor its modification time");
        assertEquals(List.of(local.getName()), namesIn(local.toPath().getParent()), "nor may anything be left beside it");
        assertFalse(DataResourceWorker.storeContentFile(TYPE_OFBIZ_FILE, OBJECT_INFO, null, local),
                "publishing must report that no store is configured");
        assertFalse(DataResourceWorker.removeContentFile(TYPE_OFBIZ_FILE, OBJECT_INFO, null),
                "removal must report that no store is configured");
    }

    @Test
    public void configuredDatabaseModeComputesTheUploadLocationItselfExactlyAsBefore() throws Exception {
        configureProvider("database");
        // The upload root is created up front because the legacy computation creates only one directory level
        // and so relies on the deployment already having this one - which every deployment does, and which the
        // seam neither changes nor is allowed to start compensating for.
        Files.createDirectories(deploymentHome.resolve("runtime/uploads"));

        String relative = DataResourceWorker.getDataResourceContentUploadPath(false);
        String absolute = DataResourceWorker.getDataResourceContentUploadPath(true);

        assertNull(ContentStoreFactory.resolveUploadPath(null, false), "database mode must supply no provider location");
        assertTrue(relative.startsWith("/runtime/uploads/"), "the legacy relative form: " + relative);
        assertTrue(absolute.endsWith(relative), "both forms must name one directory: " + absolute + " / " + relative);
        assertTrue(Files.isDirectory(Path.of(absolute)), "the legacy computation must still create the directory");
    }

    @Test
    public void aLocalProviderBackingTheVerySameFileIsNeverReconciledWithItself() throws Exception {
        configureProvider("filesystem");
        File local = deploymentHome.resolve(OBJECT_INFO).toFile();
        writeLocally(local, STORED);
        long modifiedBefore = local.lastModified();

        File resolved = DataResourceWorker.getContentFile(TYPE_OFBIZ_FILE_BIN, OBJECT_INFO, null);

        // With a filesystem provider a deployment-relative location resolves to the same file through both
        // routes. Copying it over itself would rewrite it on every read, churn the modification time and cost
        // a full copy for no gain - and a size comparison would not have caught it, because a stale copy of
        // the same size is exactly the case that matters.
        assertEquals(local.getAbsolutePath(), resolved.getAbsolutePath(), "the resolved path");
        assertArrayEquals(STORED, Files.readAllBytes(resolved.toPath()), "the content must be intact");
        assertEquals(modifiedBefore, local.lastModified(), "the file must not have been rewritten over itself");
        assertEquals(List.of(local.getName()), namesIn(local.toPath().getParent()), "and nothing may be staged beside it");
    }

    /**
     * Seats the production object-storage provider with its client boundary replaced by a local fake, and tells
     * the configuration that object storage is what is selected, so the worker resolves it exactly as a
     * deployment would.
     */
    private void configureObjectStorage() {
        client = new InMemoryS3Client(BUCKET);
        storeProperties().setProperty(PROVIDER_PROPERTY, "s3");
        ContentStoreFactory.installForTesting("s3", new S3ContentStore(client, BUCKET));
    }

    /**
     * Selects a provider by configuration alone, so the factory builds it the way a deployment does.
     *
     * @param provider the value {@code content.store.provider} is to carry
     */
    private void configureProvider(String provider) {
        storeProperties().setProperty(PROVIDER_PROPERTY, provider);
        ContentStoreFactory.clearCache();
    }

    /**
     * @return the live, cached {@code content} properties that the worker, the factory and the providers all read
     */
    private static Properties storeProperties() {
        Properties properties = UtilProperties.getProperties("content");
        assertNotNull(properties, "content.properties must be reachable on the test classpath");
        return properties;
    }

    /**
     * Writes content to a local path the way a content service does - a plain stream onto a plain file.
     *
     * <p>The modification time is then advanced explicitly, because a stat-based staleness check cannot see two
     * writes that fall inside the filesystem's modification-time granularity, and some filesystems carry only
     * whole seconds. Advancing it keeps the expectation about the seam rather than about the clock.
     *
     * @param file the file to write
     * @param content the bytes to write
     * @throws IOException if the write fails
     */
    private static void writeLocally(File file, byte[] content) throws IOException {
        Files.createDirectories(file.toPath().getParent());
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content);
        }
        assertTrue(file.setLastModified(System.currentTimeMillis() + 2000L), "the modification time must be settable");
    }

    /**
     * The sorted names directly inside a directory, with staging artefacts reported as failures.
     *
     * @param directory the directory to list
     * @return every entry name, sorted, so the comparison is order-independent
     * @throws IOException if the directory cannot be read
     */
    private static List<String> namesIn(Path directory) throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                names.add(entry.getFileName().toString());
            }
        }
        names.sort(String::compareTo);
        for (String name : names) {
            assertFalse(name.startsWith(STAGING_PREFIX), "a staging artefact was left behind: " + name);
        }
        return names;
    }
}

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.test.ShellDriver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives the object-store validation of the shipped {@code docker/docker-entrypoint.sh} and asserts what it
 * refuses and what it accepts.
 *
 * <p>The Java provider and the container entry point both judge the object-store endpoint, and they have to
 * agree: the entry point refuses a value before the JVM starts, and {@code S3ContentStore} refuses it again
 * for a value written straight into {@code content.properties}. A rule that lived in only one of them would be
 * bypassed by whichever path skipped it, so the vectors below are deliberately the same vectors
 * {@code ContentStoreFactoryTest} puts through the Java side.
 *
 * <p>What is exercised is the shipped script itself, sourced as a library with its {@code _main} invocation
 * removed, so a change to the script changes what these tests see. Nothing is started, nothing is
 * containerised and no network is used: the endpoint refusals below are decided from the address the value
 * denotes, which is exactly why they can be asserted offline.
 */
public final class ContentStoreEntryPointTests {

    /** The shipped script under test. */
    private static final String ENTRY_POINT = "docker/docker-entrypoint.sh";

    /** The variable the endpoint arrives in. */
    private static final String ENDPOINT_VARIABLE = "OFBIZ_S3_ENDPOINT";

    /** The variable the bucket arrives in. */
    private static final String BUCKET_VARIABLE = "OFBIZ_S3_BUCKET";

    /** The variable the region arrives in. */
    private static final String REGION_VARIABLE = "OFBIZ_S3_REGION";

    /** The pristine committed resource the render derives from. */
    private static final String CONTENT_PROPERTIES_SOURCE = "applications/content/config/content.properties";

    /** The words the endpoint refusal must carry, so an incidental refusal cannot pass for this one. */
    private static final String METADATA_REFUSAL = "cloud instance metadata address";

    @BeforeAll
    public static void requireAShell() {
        assumeTrue(ShellDriver.isBashAvailable(), "a POSIX shell is required to drive the container entry point");
    }

    /**
     * Every spelling of a cloud instance metadata address is refused, not just the dotted quad.
     *
     * <p>{@code 169.254.169.254} can be written as one decimal number, as one hexadecimal number, in octal, in
     * the two- and three-part short forms and in every mixture of those, and the C resolver the AWS SDK's HTTP
     * client ends up using accepts all of them as the same address. A textual comparison against the dotted
     * quad therefore defends against nothing at all: {@code 2852039166} shares not one character with it.
     *
     * @param endpoint the endpoint to refuse
     * @throws IOException if the script cannot be driven
     */
    @ParameterizedTest(name = "{0} is refused as a metadata address")
    @ValueSource(strings = {
        // The spellings everybody quotes.
        "http://169.254.169.254/",
        "https://169.254.169.254:443/latest/meta-data",
        "http://169.254.170.2/v2/credentials",
        "http://[fd00:ec2::254]/latest/meta-data",
        // One decimal number, and one hexadecimal number.
        "http://2852039166/",
        "http://0xA9FEA9FE/",
        // Per-octet hexadecimal and octal.
        "http://0xa9.0xfe.0xa9.0xfe/",
        "http://0251.0376.0251.0376/",
        // The three-part short form, and a mixture of bases in one literal.
        "http://169.254.43518/",
        "http://0251.254.0xa9fe/",
        // The ECS task metadata endpoint as one decimal number.
        "http://2852039682/",
        // Alternate IPv6 spellings of the same metadata address, including upper case and padded groups.
        "http://[fd00:0ec2::0254]/",
        "http://[FD00:EC2::254]/",
        "http://[fd00:ec2:0:0:0:0:0:254]/",
        // Link-local IPv6, which is where a metadata service lives and where nothing routable does.
        "http://[fe80::1]/",
        // The IPv4-mapped and IPv4-compatible IPv6 forms of the metadata address.
        "http://[::ffff:169.254.169.254]/",
        "http://[0:0:0:0:0:ffff:a9fe:a9fe]/",
        "http://[::169.254.169.254]/",
        // The metadata host names, refused whether or not they resolve.
        "http://metadata.google.internal/computeMetadata/v1/",
        "http://instance-data/latest/meta-data/",
    })
    public void everySpellingOfAMetadataAddressIsRefusedByTheEntryPoint(String endpoint) throws IOException {
        ShellDriver.Run run = requireObjectStoreEndpoint(endpoint);

        assertFalse(run.succeeded(), "[" + endpoint + "] must be refused, output was:\n" + run.output());
        assertTrue(run.output().contains(METADATA_REFUSAL), "[" + endpoint + "] must be refused for naming a"
                + " metadata address rather than for an incidental reason, output was:\n" + run.output());
        assertFalse(run.output().contains(endpoint), "the refusal must not quote the endpoint back, because an"
                + " endpoint may carry a credential, output was:\n" + run.output());
    }

    /**
     * An ordinary address written in an alternate numeric form is still accepted.
     *
     * <p>The other half of the matrix, and the half that would break every real deployment if the
     * canonicalisation were too eager. {@code 2130706433} is {@code 127.0.0.1}; {@code 169.253.169.254} and
     * {@code 169.255.169.254} are the addresses immediately outside the link-local range.
     *
     * @param endpoint the endpoint to accept
     * @throws IOException if the script cannot be driven
     */
    @ParameterizedTest(name = "{0} is accepted")
    @ValueSource(strings = {
        "http://127.0.0.1:9000/",
        "http://2130706433:9000",
        "http://0x7f000001:9000",
        "https://10.0.0.7:9000",
        "http://[::1]:9000",
        "http://169.253.169.254/",
        "http://169.255.169.254/",
        "https://s3.eu-west-1.amazonaws.com",
        "https://minio.example.internal:9000",
    })
    public void anOrdinaryEndpointIsAcceptedHoweverItIsWritten(String endpoint) throws IOException {
        ShellDriver.Run run = requireObjectStoreEndpoint(endpoint);

        assertTrue(run.succeeded(), "[" + endpoint + "] must be accepted: it is not a metadata address, and"
                + " refusing it would refuse a real deployment. Output was:\n" + run.output());
    }

    /**
     * The two layers agree on the metadata host names they refuse.
     *
     * <p>Asserted by reading both files rather than by running either, because the failure this catches is a
     * name added to one list and not the other - which no single-layer test can see. A value that reaches
     * {@code content.properties} without passing through the entry point is judged by the Java list alone, and
     * one that reaches the entry point is judged by the shell list alone, so a name missing from either is a
     * bypass through that path.
     *
     * @throws IOException if either file cannot be read
     */
    @Test
    public void theShellAndTheJavaProviderRefuseTheSameMetadataNames() throws IOException {
        String shell = java.nio.file.Files.readString(ShellDriver.repositoryRoot().resolve(ENTRY_POINT));
        String provider = java.nio.file.Files.readString(ShellDriver.repositoryRoot()
                .resolve("applications/content/src/main/java/org/apache/ofbiz/content/data/store/"
                        + "S3ContentStore.java"));

        for (String metadataName : List.of("metadata.google.internal", "metadata.goog", "instance-data",
                "instance-data.ec2.internal")) {
            assertTrue(shell.contains(metadataName), ENTRY_POINT + " must refuse the metadata host name ["
                    + metadataName + "]");
            assertTrue(provider.contains(metadataName), "S3ContentStore must refuse the metadata host name ["
                    + metadataName + "]");
        }
        for (String rule : List.of("169.254.", "fd00:ec2")) {
            assertTrue(shell.contains(rule), ENTRY_POINT + " must carry the [" + rule + "] rule");
            assertTrue(provider.contains(rule), "S3ContentStore must carry the [" + rule + "] rule");
        }
    }

    /*
     * Fail-fast matrix: the object-store settings whose refusal branches nothing executed
     *
     * Every case runs the real validator and asserts the exact diagnostic. The reason each has to be refused
     * at start up rather than left to the provider is that the provider discovers the problem at the FIRST
     * CONTENT READ - minutes or days after the deployment reported itself healthy - and reports it as an SDK
     * exception from inside a content request, which names neither the variable nor the mistake.
     */

    /**
     * A bucket name the object store cannot address is refused, with the rule it broke named.
     *
     * <p>The rules are the object-store naming rules rather than this script's invention, and each exists for a
     * reason that only shows up later: an upper-case letter or an underscore cannot be addressed
     * virtual-host-style at all; adjacent {@code .} and {@code -} produce a name that cannot be covered by a
     * wildcard TLS certificate, so the connection fails only once TLS is in use; and a name shaped like an IPv4
     * address is reserved, because it is indistinguishable from the endpoint's own host.
     *
     * @param bucket the name to refuse
     * @param diagnostic the rule the refusal must name
     * @throws IOException if the script cannot be driven
     */
    @ParameterizedTest(name = "bucket [{0}] is refused")
    @CsvSource({
        "'',must be set when",
        "ab,must be between",
        "a,must be between",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa,must be between",
        "MyBucket,only lower case letters",
        "my_bucket,only lower case letters",
        "my bucket,only lower case letters",
        "-abc,must begin and end with",
        "abc-,must begin and end with",
        ".abc,must begin and end with",
        "abc.,must begin and end with",
        "a..b,must not contain",
        "a.-b,must not contain",
        "a-.b,must not contain",
        "192.168.0.1,must not be formatted as an IPv4 address",
        "10.0.0.255,must not be formatted as an IPv4 address",
        // The rule is the SHAPE - four dot separated groups of digits - and not "a valid IPv4 address", so a
        // value no resolver would accept as an address is refused too. That is deliberate: what a store
        // cannot tell apart from a host in a virtual-host-style URL is the shape, not the validity.
        "1922.168.0.1,must not be formatted as an IPv4 address",
        "0.0.0.0,must not be formatted as an IPv4 address",
    })
    public void aBucketNameTheObjectStoreCannotAddressIsRefusedWithTheRuleItBrokeNamed(String bucket,
            String diagnostic) throws IOException {
        ShellDriver.Run run = requireObjectStore("require_object_store_bucket", BUCKET_VARIABLE, bucket, "dev");

        assertFalse(run.succeeded(), "bucket [" + bucket + "] must be refused, output was:\n" + run.output());
        assertTrue(run.output().contains(BUCKET_VARIABLE), "the refusal must name the variable, output was:\n"
                + run.output());
        assertTrue(run.output().contains(diagnostic), "the refusal must contain [" + diagnostic
                + "], output was:\n" + run.output());
    }

    /**
     * Every bucket name the object store does allow is accepted. Without this half the rules above could be
     * arbitrarily strict and would refuse real deployments: {@code a.b.c} is a legal dotted name, three
     * characters is the legal minimum, and a name that merely begins with digits is not an IPv4 address.
     *
     * @param bucket a legal bucket name
     * @throws IOException if the script cannot be driven
     */
    @ParameterizedTest(name = "bucket [{0}] is accepted")
    @ValueSource(strings = {"abc", "my-bucket", "a.b.c", "ofbiz-content", "192.168.0", "192.168.0.1.2",
        "0123456789", "content.example-1",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    public void everyBucketNameTheObjectStoreAllowsIsAccepted(String bucket) throws IOException {
        ShellDriver.Run run = requireObjectStore("require_object_store_bucket", BUCKET_VARIABLE, bucket, "dev");

        assertTrue(run.succeeded(), "bucket [" + bucket + "] is legal and refusing it would refuse a real"
                + " deployment, output was:\n" + run.output());
    }

    /**
     * A region identifier outside the shape a region identifier has is refused.
     *
     * <p>The region is a signing input, not just a routing hint: the SDK derives the request signature from it,
     * so a value in the wrong case or carrying an underscore produces a signature the store rejects with an
     * authentication error that names neither the region nor the variable.
     *
     * @param region the value to refuse
     * @param diagnostic the rule the refusal must name
     * @throws IOException if the script cannot be driven
     */
    @ParameterizedTest(name = "region [{0}] is refused")
    @CsvSource({
        "'',must be set when",
        "a,must be between",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa,must be between",
        "US-East-1,only lower case letters",
        "us_east_1,only lower case letters",
        "us east 1,only lower case letters",
        "us.east.1,only lower case letters",
        "-us,must begin and end with",
        "us-,must begin and end with",
    })
    public void aRegionIdentifierOutsideTheShapeOfOneIsRefused(String region, String diagnostic)
            throws IOException {
        ShellDriver.Run run = requireObjectStore("require_object_store_region", REGION_VARIABLE, region, "dev");

        assertFalse(run.succeeded(), "region [" + region + "] must be refused, output was:\n" + run.output());
        assertTrue(run.output().contains(REGION_VARIABLE), "the refusal must name the variable, output was:\n"
                + run.output());
        assertTrue(run.output().contains(diagnostic), "the refusal must contain [" + diagnostic
                + "], output was:\n" + run.output());
    }

    /**
     * Every real region identifier is accepted, including the {@code us-east-1} that S3-compatible stores with
     * no regions of their own conventionally take, and the two-character minimum.
     *
     * @param region a legal region identifier
     * @throws IOException if the script cannot be driven
     */
    @ParameterizedTest(name = "region [{0}] is accepted")
    @ValueSource(strings = {"us-east-1", "eu-west-1", "ap-southeast-2", "us-gov-west-1", "cn-north-1", "a1",
        "local", "0"})
    public void everyRealRegionIdentifierIsAccepted(String region) throws IOException {
        ShellDriver.Run run = requireObjectStore("require_object_store_region", REGION_VARIABLE, region, "dev");

        boolean tooShort = region.length() < 2;
        assertEquals(!tooShort, run.succeeded(), "region [" + region + "], output was:\n" + run.output());
    }

    /**
     * An endpoint that is not a bare scheme-host-port URI is refused, for the reason each of these breaks a
     * different consumer.
     *
     * <p>These are the endpoint refusals that have nothing to do with the metadata service: user information
     * before the host means a credential is being carried in a variable that is logged and rendered; a query
     * string or fragment cannot survive being combined with an object key; and a host outside the character set
     * a URI authority allows produces an SDK exception at the first request rather than at start up. The
     * unterminated and malformed IPv6 cases matter because {@code java.net.URI} and the shell disagree about
     * them unless the shell refuses them outright.
     *
     * @param endpoint the value to refuse
     * @param diagnostic the rule the refusal must name
     * @throws IOException if the script cannot be driven
     */
    @ParameterizedTest(name = "endpoint [{0}] is refused")
    @CsvSource({
        "'https://a b.example',must not contain whitespace",
        "'https://s3.example?x=1',must not carry a query string or a fragment",
        "'https://s3.example#fragment',must not carry a query string or a fragment",
        "'ftp://s3.example',must use the http or https scheme",
        "'file:///tmp/store',must use the http or https scheme",
        "'HTTPS://s3.example',must use the http or https scheme",
        "'s3.example:9000',must be an absolute http:// or https:// URI",
        "'//s3.example:9000',must be an absolute http:// or https:// URI",
        "'https://',has no host",
        "'http://',has no host",
        "'https://user:secret@s3.example',must not carry user information before the host",
        "'https://token@s3.example',must not carry user information before the host",
        "'https://[fd00::1:9000',has an unterminated IPv6 literal",
        "'https://[not-ipv6]:9000',does not contain a valid bracketed IPv6 literal",
        "'https://s3_example.internal',must name a host as letters",
        // The port is validated as a bounded integer of its own, so a non-numeric or out-of-range port is
        // reported as a port rather than as part of the host.
        "'https://s3.example:port',the port component",
        "'https://s3.example:0',the port component",
        "'https://s3.example:65536',the port component",
        "'https://s3.example:09000',the port component",
    })
    public void anEndpointThatIsNotABareSchemeHostPortUriIsRefused(String endpoint, String diagnostic)
            throws IOException {
        ShellDriver.Run run = requireObjectStoreEndpoint(endpoint);

        assertFalse(run.succeeded(), "endpoint [" + endpoint + "] must be refused, output was:\n" + run.output());
        assertTrue(run.output().contains(diagnostic), "the refusal must contain [" + diagnostic
                + "], output was:\n" + run.output());
    }

    /**
     * An endpoint carrying a base path is accepted, by this layer and by the Java provider alike.
     *
     * <p>Asserted rather than assumed, because a path is the one part of a URI that this validator deliberately
     * does NOT refuse: an S3 gateway reached through a reverse proxy is legitimately addressed at a base path,
     * and {@code S3Client.endpointOverride} carries it. {@code S3ContentStore.validatedEndpoint} refuses user
     * information, a query and a fragment and says nothing about the path, so refusing one here would make the
     * two layers disagree and a configuration written straight into {@code content.properties} would work while
     * the same configuration supplied through the environment would not.
     *
     * @param endpoint an endpoint with a base path
     * @throws IOException if the script cannot be driven
     */
    @ParameterizedTest(name = "endpoint [{0}] is accepted with its base path")
    @ValueSource(strings = {"https://s3.example.internal/gateway", "https://s3.example.internal:9000/s3",
        "http://s3.example.internal/", "https://s3.example.internal/a/b/c"})
    public void anEndpointCarryingABasePathIsAcceptedByThisLayerJustAsTheProviderAcceptsIt(String endpoint)
            throws IOException {
        ShellDriver.Run run = requireObjectStoreEndpoint(endpoint);

        assertTrue(run.succeeded(), "[" + endpoint + "] must be accepted: a gateway behind a reverse proxy is"
                + " addressed at a base path, and S3ContentStore accepts one too. Output was:\n" + run.output());
    }

    /**
     * A plaintext endpoint is refused in the deployed profile and only warned about in the development one.
     *
     * <p>Both halves are the contract. Every object write carries the store credential and every read returns
     * content that may not be public, so a plaintext endpoint exposes both to anything on the network path -
     * which the deployed profile does not accept. A developer running a local MinIO over http must still be
     * able to work, so there it is reported rather than refused.
     *
     * @throws IOException if the script cannot be driven
     */
    @Test
    public void aPlaintextEndpointIsRefusedInProductionAndOnlyWarnedAboutInDevelopment() throws IOException {
        String endpoint = "http://minio.example.internal:9000";

        ShellDriver.Run development = requireObjectStore("require_object_store_endpoint", ENDPOINT_VARIABLE,
                endpoint, "dev");
        assertTrue(development.succeeded(), "a local plaintext store must remain usable in development, output"
                + " was:\n" + development.output());

        ShellDriver.Run production = requireObjectStore("require_object_store_endpoint", ENDPOINT_VARIABLE,
                endpoint, "prod");
        assertFalse(production.succeeded(), "a plaintext endpoint must be refused in the deployed profile,"
                + " output was:\n" + production.output());
        assertTrue(production.output().contains("must use https:// when OFBIZ_PROFILE=prod"),
                "the refusal must say which profile refuses it, output was:\n" + production.output());
        assertTrue(production.output().contains("carries the store credential"), "the refusal must say what a"
                + " plaintext endpoint exposes, output was:\n" + production.output());
    }

    /**
     * Object-store settings supplied without selecting the object store are refused in the deployed profile and
     * warned about in the development one.
     *
     * <p>This is a cross-field contradiction that every value passes on its own, and which half is the mistake
     * cannot be guessed: the operator either forgot to set the provider or forgot to remove the settings. The
     * deployed profile refuses it because the alternative is content being written to a backend other than the
     * one the manifest plainly describes, with nothing reporting the difference. The development profile renders
     * the values - they are inert - and says so.
     *
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws IOException if the script cannot be driven
     */
    @Test
    public void objectStoreSettingsWithoutTheObjectStoreAreRefusedInProductionAndWarnedAboutInDevelopment(
            @TempDir Path tempDir) throws IOException {
        Map<String, String> contradiction = ShellDriver.environment(
                "OFBIZ_CONTENT_STORE_PROVIDER", "database",
                "OFBIZ_S3_BUCKET", "ofbiz-content-fixture",
                "OFBIZ_S3_ENDPOINT", "https://objects.example.internal:9000");

        ShellDriver.Run development = renderContentStore(tempDir, contradiction, "dev");
        assertTrue(development.succeeded(), "the development profile must render the inert values, output was:\n"
                + development.output());
        assertTrue(development.output().contains("so these object-store settings have no effect"),
                "the development profile must say the settings are inert, output was:\n" + development.output());
        assertTrue(development.output().contains("OFBIZ_PROFILE=prod refuses this combination"),
                "the warning must say that the deployed profile does not accept it, output was:\n"
                        + development.output());

        ShellDriver.Run production = renderContentStore(tempDir, contradiction, "prod");
        assertFalse(production.succeeded(), "the deployed profile must refuse the contradiction, output was:\n"
                + production.output());
        assertTrue(production.output().contains("They would have no effect"), "the refusal must say the settings"
                + " are ineffective, output was:\n" + production.output());
        for (String named : List.of("OFBIZ_CONTENT_STORE_PROVIDER", "OFBIZ_S3_BUCKET", "OFBIZ_S3_ENDPOINT")) {
            assertTrue(production.output().contains(named), "the refusal must name " + named + ", output was:\n"
                    + production.output());
        }
    }

    /**
     * Selecting the object store without the two settings it cannot work without is refused at start up rather
     * than at the first content read, and the refusal says so.
     *
     * @param missing the variable to leave out
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws IOException if the script cannot be driven
     */
    @ParameterizedTest(name = "s3 without {0} is refused")
    @ValueSource(strings = {"OFBIZ_S3_BUCKET", "OFBIZ_S3_REGION"})
    public void selectingTheObjectStoreWithoutASettingItCannotWorkWithoutIsRefused(String missing,
            @TempDir Path tempDir) throws IOException {
        Map<String, String> environment = ShellDriver.environment(
                "OFBIZ_CONTENT_STORE_PROVIDER", "s3",
                "OFBIZ_S3_BUCKET", "ofbiz-content-fixture",
                "OFBIZ_S3_REGION", "us-east-1");
        environment.remove(missing);

        ShellDriver.Run run = renderContentStore(tempDir, environment, "dev");

        assertFalse(run.succeeded(), "s3 without " + missing + " must be refused, output was:\n" + run.output());
        assertTrue(run.output().contains(missing + " is required when OFBIZ_CONTENT_STORE_PROVIDER=s3"),
                "the refusal must name the variable and the backend that requires it, output was:\n"
                        + run.output());
    }

    /**
     * The addressing-style switch is parsed as a boolean by both layers that read it.
     *
     * <p>It has a meaningful FALSE - Amazon S3 requires virtual-host-style addressing and most S3-compatible
     * stores require path-style - so it cannot be tested for non-emptiness, and an unparseable value must be
     * reported rather than silently resolved to one of the two. Both the resolver and the render are driven,
     * because they read it independently and a rule enforced in only one of them is bypassed through the other.
     *
     * @param supplied the value to supply
     * @param accepted whether it must be accepted
     * @param tempDir a per-test temporary directory, injected by JUnit
     * @throws IOException if the script cannot be driven
     */
    @ParameterizedTest(name = "path style [{0}] accepted: {1}")
    @CsvSource({
        "true,true", "false,true", "YES,true", "no,true", "1,true", "0,true",
        "on,false", "enabled,false", "2,false", "path,false",
    })
    public void theAddressingStyleSwitchIsParsedAsABooleanByBothLayersThatReadIt(String supplied,
            boolean accepted, @TempDir Path tempDir) throws IOException {
        Map<String, String> environment = ShellDriver.environment(
                "OFBIZ_CONTENT_STORE_PROVIDER", "s3",
                "OFBIZ_S3_BUCKET", "ofbiz-content-fixture",
                "OFBIZ_S3_REGION", "us-east-1",
                "OFBIZ_S3_PATH_STYLE", supplied);

        for (String entryPointFunction : List.of("resolve_content_store_configuration",
                "render_content_store_configuration")) {
            ShellDriver.Run run = runContentStoreStep(tempDir, entryPointFunction, environment, "dev");

            assertEquals(accepted, run.succeeded(), entryPointFunction + " with OFBIZ_S3_PATH_STYLE=["
                    + supplied + "], output was:\n" + run.output());
            if (!accepted) {
                assertTrue(run.output().contains("OFBIZ_S3_PATH_STYLE must be a boolean"),
                        entryPointFunction + " must name the variable and say a boolean is required, output"
                                + " was:\n" + run.output());
            }
        }
    }

    /**
     * Drives one of the shipped object-store validators with one value and one profile.
     *
     * @param validator the shell function to call
     * @param variable the variable name to report it under
     * @param value the value to validate
     * @param profile the profile to run in
     * @return what the run produced
     * @throws IOException if the script cannot be driven
     */
    private ShellDriver.Run requireObjectStore(String validator, String variable, String value, String profile)
            throws IOException {
        Path library = ShellDriver.sourceableLibrary(sandbox, ENTRY_POINT);
        Path driver = ShellDriver.driver(sandbox, library, validator + " " + variable + " \"$"
                + "OFBIZ_VALUE_UNDER_TEST\"\necho ACCEPTED");
        Map<String, String> environment = ShellDriver.environment("OFBIZ_VALUE_UNDER_TEST", value,
                "OFBIZ_PROFILE", profile);
        return ShellDriver.run(driver, sandbox, environment);
    }

    /**
     * Runs one step of the shipped content-store configuration against a sandbox holding the committed resource.
     *
     * @param workDir a per-test directory for the generated driver scripts
     * @param step the shell function to call
     * @param environment the {@code OFBIZ_*} variables to supply
     * @param profile the profile to run in
     * @return what the run produced
     * @throws IOException if the script cannot be driven
     */
    private static ShellDriver.Run runContentStoreStep(Path workDir, String step,
            Map<String, String> environment, String profile) throws IOException {
        Path home = Files.createDirectories(Files.createTempDirectory(workDir, "ofbiz"));
        Path source = home.resolve(CONTENT_PROPERTIES_SOURCE);
        Files.createDirectories(source.getParent());
        Files.copy(ShellDriver.repositoryRoot().resolve(CONTENT_PROPERTIES_SOURCE), source);
        Path library = ShellDriver.sourceableLibrary(workDir, ENTRY_POINT);
        Path driver = ShellDriver.driver(workDir, library, "cd " + ShellDriver.quote(home) + " || exit 1\n"
                + step + "\n");
        Map<String, String> variables = new LinkedHashMap<>(environment);
        variables.put("OFBIZ_PROFILE", profile);
        return ShellDriver.run(driver, home, variables);
    }

    /**
     * Runs the shipped content-store render against a sandbox holding the committed resource.
     *
     * @param workDir a per-test directory for the generated driver scripts
     * @param environment the {@code OFBIZ_*} variables to supply
     * @param profile the profile to run in
     * @return what the run produced
     * @throws IOException if the script cannot be driven
     */
    private static ShellDriver.Run renderContentStore(Path workDir, Map<String, String> environment,
            String profile) throws IOException {
        return runContentStoreStep(workDir, "render_content_store_configuration", environment, profile);
    }

    /**
     * Drives the shipped {@code require_object_store_endpoint} with one value.
     *
     * @param endpoint the value to validate
     * @return what the run produced
     * @throws IOException if the script cannot be driven
     */
    private ShellDriver.Run requireObjectStoreEndpoint(String endpoint) throws IOException {
        Path workDir = sandbox;
        Path library = ShellDriver.sourceableLibrary(workDir, ENTRY_POINT);
        // OFBIZ_PROFILE is set because the endpoint validation refuses a plaintext endpoint under prod, and
        // this suite is about the address rather than about the scheme.
        Path driver = ShellDriver.driver(workDir, library,
                "OFBIZ_PROFILE=dev\nrequire_object_store_endpoint " + ENDPOINT_VARIABLE + " \"$"
                        + "OFBIZ_S3_ENDPOINT_UNDER_TEST\"\necho ACCEPTED");
        Map<String, String> environment = ShellDriver.environment("OFBIZ_S3_ENDPOINT_UNDER_TEST", endpoint);
        return ShellDriver.run(driver, workDir, environment);
    }

    /** A directory of this class's own, so the sourced library is written once and cleaned up by JUnit. */
    @TempDir
    private Path sandbox;
}

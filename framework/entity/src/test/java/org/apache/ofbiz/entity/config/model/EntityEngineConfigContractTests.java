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
package org.apache.ofbiz.entity.config.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.apache.ofbiz.entity.GenericEntityConfException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;

/**
 * Configuration contract of the committed {@code framework/entity/config/entityengine.xml}.
 *
 * <p>This file is the single most load-bearing configuration artefact of the AWS-readiness work: it decides
 * which database every delegator talks to, whether a booting instance issues DDL, and whether entity caches
 * are invalidated across a fleet. The committed state is the DEVELOPMENT profile - a bare checkout must boot
 * on embedded H2 with no environment variables and no secrets - while the DEPLOYED profile is produced by the
 * container entry point rendering {@code docker/templates/postgres-entityengine.xml} over this file. That makes
 * every value here a contract in two directions at once: it is what a developer gets with zero configuration,
 * and it is the substitution shape the entry point rewrites.
 *
 * <p>Everything below reads the AUTHORITATIVE file from the repository, by path, and compares COMPLETE
 * structured maps rather than probing one value at a time - so an accidental addition, removal or reorder is
 * caught just as reliably as a changed value. Nothing is copied into a test fixture, and nothing is resolved
 * through the classpath, where a shadowing {@code entityengine.xml} could mask a regression in the real file.
 *
 * <p>Where a value's MEANING matters as much as its text, the real DOM element is additionally handed to the
 * production parser ({@link Datasource} and {@link DelegatorElement}, whose package-private constructors this
 * test can reach because it lives in their package). That is what proves claims such as "an absent
 * {@code check-on-start} defaults to true, so the literal {@code false} is required" and "no extra attribute is
 * needed for the cache-clear plumbing" - the two places where reading the XML alone would be misleading.
 * Neither constructor touches static state, so no global configuration is initialised and nothing leaks between
 * tests; in particular {@code EntityConfig.getInstance()} is deliberately NOT used, because it builds an eager
 * static singleton off the classpath.
 *
 * <p>No test here opens a database connection or needs a credential of any kind. One test does drive the
 * production credential resolver, but only to prove that it FAILS: the managed datasources commit no password,
 * and the lookup they name is deliberately absent from {@code passwords.properties}, so resolving it throws
 * instead of handing back a value that is published in this repository.
 */
public final class EntityEngineConfigContractTests {

    private static final String ENTITY_ENGINE_XML = "framework/entity/config/entityengine.xml";
    private static final String ENTITY_CONFIG_SCHEMA = "framework/entity/dtd/entity-config.xsd";
    private static final String FIELD_TYPE_DIRECTORY = "framework/entity/fieldtype";
    private static final String DEPENDENCY_MANIFEST = "dependencies.gradle";
    private static final String POSTGRES_TEMPLATE = "docker/templates/postgres-entityengine.xml";
    private static final String PASSWORDS_PROPERTIES = "framework/base/config/passwords.properties";

    private static final String OFBIZ_GROUP = "org.apache.ofbiz";
    private static final String OLAP_GROUP = "org.apache.ofbiz.olap";
    private static final String TENANT_GROUP = "org.apache.ofbiz.tenant";

    private static final String H2_DRIVER = "org.h2.Driver";
    private static final String POSTGRES_DRIVER = "org.postgresql.Driver";
    private static final String POSTGRES_COORDINATE = "org.postgresql:postgresql:42.7.13";
    private static final String H2_COORDINATE = "com.h2database:h2:2.4.240";
    private static final String RUNTIME_ONLY = "runtimeOnly";

    /** The only TLS mode that both encrypts the connection and authenticates the server it is talking to. */
    private static final String REQUIRED_SSL_MODE = "sslmode=verify-full";

    /** Placeholder the entry point substitutes with the resolved TLS query string of the managed datasources. */
    private static final String SSL_PARAMS_TOKEN = "@SSL_PARAMS@";

    /** The render template's placeholder for the database port, validated as an integer by the entry point. */
    private static final String PORT_TOKEN = "@PORT@";
    private static final String SCHEMA_DDL_TOKEN = "@SCHEMA_DDL@";
    private static final String CACHE_CLEAR_TOKEN = "@DISTRIBUTED_CACHE_CLEAR@";
    private static final String POOL_MIN_TOKEN = "@DB_POOL_MIN@";
    private static final String POOL_MAX_TOKEN = "@DB_POOL_MAX@";

    /** Reported for an attribute that is absent, so absence is distinguishable from an empty value. */
    private static final String ABSENT = "(absent)";

    /** Any at-sign delimited render placeholder. */
    private static final Pattern PLACEHOLDER = Pattern.compile("@[A-Z_0-9]+@");

    /**
     * Every placeholder of the render template, with a value of the same shape the entry point substitutes:
     * validated host, port and database names, a TLS query string, the normalised boolean of an init-mode start
     * up, and the default pool bounds. Nothing here is a credential of any system.
     */
    private static final Map<String, String> TEMPLATE_SUBSTITUTIONS = Map.ofEntries(
            Map.entry("@HOST@", "db.example.internal"),
            Map.entry(PORT_TOKEN, "5432"),
            Map.entry(SSL_PARAMS_TOKEN, "?" + REQUIRED_SSL_MODE),
            Map.entry("@OFBIZ_DB@", "ofbizmaindb"),
            Map.entry("@OFBIZ_USERNAME@", "ofbizmain"),
            Map.entry("@OFBIZ_PASSWORD@", "rendered-ofbiz-password"),
            Map.entry("@OLAP_DB@", "ofbizolapdb"),
            Map.entry("@OLAP_USERNAME@", "ofbizolap"),
            Map.entry("@OLAP_PASSWORD@", "rendered-olap-password"),
            Map.entry("@TENANT_DB@", "ofbiztenantdb"),
            Map.entry("@TENANT_USERNAME@", "ofbiztenant"),
            Map.entry("@TENANT_PASSWORD@", "rendered-tenant-password"),
            Map.entry(SCHEMA_DDL_TOKEN, "true"),
            Map.entry(CACHE_CLEAR_TOKEN, "true"),
            Map.entry(POOL_MIN_TOKEN, "2"),
            Map.entry(POOL_MAX_TOKEN, "250"));

    private static final String JDBC_PASSWORD = "jdbc-password";
    private static final String JDBC_PASSWORD_LOOKUP = "jdbc-password-lookup";

    private static final String CHECK_ON_START = "check-on-start";
    private static final String ADD_MISSING_ON_START = "add-missing-on-start";
    private static final String FIELD_TYPE_NAME = "field-type-name";
    private static final String CACHE_CLEAR_ENABLED = "distributed-cache-clear-enabled";
    private static final String ENTITY_ECA_ENABLED = "entity-eca-enabled";

    /** The default cache-clear implementation, supplied by {@link DelegatorElement} rather than by an attribute. */
    private static final String CACHE_CLEAR_CLASS = "org.apache.ofbiz.entityext.cache.EntityCacheServices";

    /** Every {@code <datasource>} name, in document order. Removing one would break database portability. */
    private static final List<String> EXPECTED_DATASOURCE_NAMES = List.of(
            "localh2", "localh2odbc", "localh2olap", "localh2tenant",
            "localmysql", "localmysqlolap", "localmysqltenant", "odbcmysql",
            "localpostgres", "localpostgresolap", "localpostgrestenant",
            "localoracle", "localoracledd", "localsybase", "localsapdb", "localfirebird",
            "localmssql", "localp6spy", "localadvantage", "localhsql", "localdaffodil", "localaxion", "DB2");

    /** The embedded datasources the committed development profile resolves to. */
    private static final List<String> H2_DATASOURCES = List.of("localh2", "localh2olap", "localh2tenant");

    /** The managed-RDBMS datasources the deployed profile repoints the default delegators to. */
    private static final List<String> POSTGRES_DATASOURCES = List.of("localpostgres", "localpostgresolap", "localpostgrestenant");

    /** Abstract field-type name to per-dialect mapping file. An immutable interface: 12 dialects, no more, no fewer. */
    private static final Map<String, String> EXPECTED_FIELD_TYPES = Map.ofEntries(
            Map.entry("h2", "fieldtypeh2.xml"),
            Map.entry("mysql", "fieldtypemysql.xml"),
            Map.entry("postgres", "fieldtypepostgres.xml"),
            Map.entry("oracle", "fieldtypeoracle.xml"),
            Map.entry("sapdb", "fieldtypesapdb.xml"),
            Map.entry("sybase", "fieldtypesybase.xml"),
            Map.entry("firebird", "fieldtypefirebird.xml"),
            Map.entry("mssql", "fieldtypemssql.xml"),
            Map.entry("advantage", "fieldtypeadvantage.xml"),
            Map.entry("hsql", "fieldtypehsql.xml"),
            Map.entry("daffodil", "fieldtypedaffodil.xml"),
            Map.entry("axion", "fieldtypeaxion.xml"));

    /** Matches {@code implementation 'a:b:c'} and {@code implementation('a:b:c') {} } alike. */
    private static final Pattern DEPENDENCY_DECLARATION = Pattern.compile("^\\s*(\\w+)\\s*\\(?\\s*'([^']+)'");

    /** A {@code jdbc-uri} attribute of the render template, capturing the URI itself. */
    private static final Pattern TEMPLATE_JDBC_URI = Pattern.compile("jdbc-uri=\"(jdbc:postgresql:[^\"]*)\"");

    /** The configuration is parsed once per test so that element identities can be compared. */
    private Element configRoot;

    @BeforeEach
    public void parseEntityEngineConfig() throws Exception {
        Path config = repositoryRoot().resolve(ENTITY_ENGINE_XML);
        assertTrue(Files.isRegularFile(config), "missing authoritative configuration " + config);
        configRoot = parseXml(Files.readString(config));
        // Like every OFBiz component descriptor this file carries no XML namespace, only an
        // xsi:noNamespaceSchemaLocation hint, so the parser is namespace aware and lookups use getLocalName().
        assertEquals("entity-config", configRoot.getLocalName(), "configuration root element");
        assertNull(configRoot.getNamespaceURI(), "the entity-config grammar has no target namespace");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Delegators: the immutable names, all nine group maps, and the cache attributes
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void exactlyTheThreeImmutableDelegatorsAreDeclared() {
        // Delegator names are a frozen interface: code, component descriptors and testdefs name them directly.
        assertEquals(List.of("default", "default-no-eca", "test"), new ArrayList<>(delegatorsByName().keySet()),
                "the <delegator> children of the configuration, in document order");
    }

    @Test
    public void everyDelegatorMapsAllThreeEntityGroupsToEmbeddedH2() {
        Map<String, Map<String, String>> groupMaps = new LinkedHashMap<>();
        for (Map.Entry<String, Element> delegator : delegatorsByName().entrySet()) {
            Map<String, String> byGroup = new LinkedHashMap<>();
            for (Element groupMap : childElements(delegator.getValue(), "group-map")) {
                assertNull(byGroup.put(groupMap.getAttribute("group-name"), groupMap.getAttribute("datasource-name")),
                        "duplicate group-map in delegator " + delegator.getKey());
            }
            groupMaps.put(delegator.getKey(), byGroup);
        }

        // One assertion for the complete 3x3 matrix. The committed file is the DEVELOPMENT profile, so all nine
        // resolve to embedded H2: that is what lets a bare checkout boot with no configuration at all. The
        // deployed profile repoints only default and default-no-eca; "test" stays here on H2 in BOTH profiles so
        // gradlew loadAll and gradlew testIntegration never need a managed database.
        Map<String, String> h2Groups = Map.of(
                OFBIZ_GROUP, "localh2",
                OLAP_GROUP, "localh2olap",
                TENANT_GROUP, "localh2tenant");
        assertEquals(Map.of("default", h2Groups, "default-no-eca", h2Groups, "test", h2Groups), groupMaps,
                "delegator -> entity group -> datasource, for every declared combination");
    }

    @Test
    public void delegatorCacheAndEcaAttributesAreExactlyTheCommittedLiterals() {
        Map<String, Map<String, String>> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, Element> delegator : delegatorsByName().entrySet()) {
            attributes.put(delegator.getKey(), Map.of(
                    CACHE_CLEAR_ENABLED, delegator.getValue().getAttribute(CACHE_CLEAR_ENABLED),
                    ENTITY_ECA_ENABLED, delegator.getValue().getAttribute(ENTITY_ECA_ENABLED)));
        }

        // Raw attribute text, so that silently DROPPING an attribute is distinguishable from changing its value.
        // The two default delegators state distributed-cache-clear-enabled="false" explicitly - that literal is
        // the anchor the entry point rewrites for OFBIZ_DISTRIBUTED_CACHE_CLEAR, so it must not be deleted even
        // though "false" also happens to be the parser's default.
        assertEquals(Map.of(
                "default", Map.of(CACHE_CLEAR_ENABLED, "false", ENTITY_ECA_ENABLED, ""),
                "default-no-eca", Map.of(CACHE_CLEAR_ENABLED, "false", ENTITY_ECA_ENABLED, "false"),
                "test", Map.of(CACHE_CLEAR_ENABLED, "", ENTITY_ECA_ENABLED, "")),
                attributes, "literal cache-clear and entity-eca attributes per delegator");
    }

    @Test
    public void productionParserResolvesEveryDelegatorToSingleNodeCachingWithTheDefaultPlumbing() throws Exception {
        for (Map.Entry<String, Element> entry : delegatorsByName().entrySet()) {
            String name = entry.getKey();
            DelegatorElement delegator = new DelegatorElement(entry.getValue());

            assertEquals(name, delegator.getName(), "parsed delegator name");
            assertEquals("localh2", delegator.getGroupDataSource(OFBIZ_GROUP), name + " -> " + OFBIZ_GROUP);
            assertEquals("localh2olap", delegator.getGroupDataSource(OLAP_GROUP), name + " -> " + OLAP_GROUP);
            assertEquals("localh2tenant", delegator.getGroupDataSource(TENANT_GROUP), name + " -> " + TENANT_GROUP);
            assertEquals(OFBIZ_GROUP, delegator.getDefaultGroupName(), name + " default entity group");

            // Single-node cache behaviour out of the box, for every delegator including "test".
            assertFalse(delegator.getDistributedCacheClearEnabled(),
                    name + " must keep today's single-node cache behaviour when unconfigured");
            // ...and when it IS enabled, no further attribute is needed: the parser supplies OFBiz's existing
            // implementation and user. This is exactly the claim the comment on the flag makes, so it is asserted
            // rather than trusted - if either default ever moved, enabling the flag would silently do nothing.
            assertEquals(CACHE_CLEAR_CLASS, delegator.getDistributedCacheClearClassName(),
                    name + " must inherit OFBiz's existing DistributedCacheClear implementation");
            assertEquals("system", delegator.getDistributedCacheClearUserLoginId(),
                    name + " must inherit the default cache-clear user");
        }

        // Entity ECAs stay on everywhere except the delegator whose whole purpose is to disable them.
        assertTrue(new DelegatorElement(delegatorsByName().get("default")).getEntityEcaEnabled(), "default keeps entity ECAs");
        assertTrue(new DelegatorElement(delegatorsByName().get("test")).getEntityEcaEnabled(), "test keeps entity ECAs");
        assertFalse(new DelegatorElement(delegatorsByName().get("default-no-eca")).getEntityEcaEnabled(),
                "default-no-eca must keep entity ECAs disabled");
    }

    @Test
    public void noDelegatorReferencesAManagedDatabaseSoOrdinaryTestsNeedNoCredentials() {
        List<String> referenced = new ArrayList<>();
        for (Element delegator : delegatorsByName().values()) {
            for (Element groupMap : childElements(delegator, "group-map")) {
                referenced.add(groupMap.getAttribute("datasource-name"));
            }
        }

        // The whole point of the development profile: nothing in the committed file points at a server-hosted
        // database, so no unit test, no integration test and no gradlew task can require a PostgreSQL host,
        // username or password. If a group-map ever regressed to localpostgres*, every build machine would
        // suddenly need credentials - this is the assertion that catches it.
        for (String managed : POSTGRES_DATASOURCES) {
            assertFalse(referenced.contains(managed),
                    "no committed delegator may reference the managed datasource " + managed);
        }
        for (String datasource : referenced) {
            Element inlineJdbc = inlineJdbcOf(datasource);
            assertEquals(H2_DRIVER, inlineJdbc.getAttribute("jdbc-driver"), datasource + " must stay on the embedded driver");
            assertTrue(inlineJdbc.getAttribute("jdbc-uri").startsWith("jdbc:h2:file:"),
                    datasource + " must be a local file database, reachable with no host, port or network");
        }
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Datasources: the whole inventory, and the two DDL tuples that decide who may issue DDL
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void theDatasourceInventoryIsUnchanged() {
        // Database portability is preserved through the DEFINITIONS, not through bundled drivers: MySQL, Oracle,
        // Sybase, SAP DB, Firebird, MSSQL and the rest stay selectable even though only H2 and PostgreSQL drivers
        // ship. Promoting PostgreSQL to the deployed default must therefore not have removed a single definition.
        assertEquals(EXPECTED_DATASOURCE_NAMES, new ArrayList<>(datasourcesByName().keySet()),
                "the <datasource> children of the configuration, in document order");
    }

    @Test
    public void embeddedH2DatasourcesKeepDdlEnabledForZeroConfigurationDevelopment() {
        Map<String, Map<String, String>> tuples = new LinkedHashMap<>();
        for (String name : H2_DATASOURCES) {
            Element datasource = datasourcesByName().get(name);
            assertNotNull(datasource, "missing datasource " + name);
            tuples.put(name, Map.of(
                    FIELD_TYPE_NAME, datasource.getAttribute(FIELD_TYPE_NAME),
                    CHECK_ON_START, datasource.getAttribute(CHECK_ON_START),
                    ADD_MISSING_ON_START, datasource.getAttribute(ADD_MISSING_ON_START),
                    "jdbc-driver", inlineJdbcOf(name).getAttribute("jdbc-driver")));
        }

        // H2 deliberately keeps startup DDL ON: that is what makes a bare checkout self-initialising. It is safe
        // precisely because H2 here is a single-node local file database that is never part of a served fleet.
        Map<String, String> ddlEnabled = Map.of(
                FIELD_TYPE_NAME, "h2", CHECK_ON_START, "true", ADD_MISSING_ON_START, "true", "jdbc-driver", H2_DRIVER);
        assertEquals(Map.of("localh2", ddlEnabled, "localh2olap", ddlEnabled, "localh2tenant", ddlEnabled), tuples,
                "embedded H2 dialect and DDL tuple per datasource");
    }

    @Test
    public void managedPostgresDatasourcesDeclareTheDriverTheExplicitPortAndDisabledDdl() {
        Map<String, Map<String, String>> tuples = new LinkedHashMap<>();
        for (String name : POSTGRES_DATASOURCES) {
            Element datasource = datasourcesByName().get(name);
            assertNotNull(datasource, "missing datasource " + name);
            Element inlineJdbc = inlineJdbcOf(name);
            tuples.put(name, Map.of(
                    FIELD_TYPE_NAME, datasource.getAttribute(FIELD_TYPE_NAME),
                    CHECK_ON_START, datasource.getAttribute(CHECK_ON_START),
                    ADD_MISSING_ON_START, datasource.getAttribute(ADD_MISSING_ON_START),
                    "jdbc-driver", inlineJdbc.getAttribute("jdbc-driver"),
                    "jdbc-uri", inlineJdbc.getAttribute("jdbc-uri")));
        }

        // Both DDL flags are literally false, which is what makes the serving fleet DDL-free: a running instance
        // needs no DDL privilege and instances cannot race each other on schema changes. The entry point renders
        // both as true only for the one-shot OFBIZ_SCHEMA_INIT execution.
        // Each URI carries the EXPLICIT :5432 default port, a functional no-op for existing users that gives the
        // template an unambiguous host:port shape to substitute (jdbc:postgresql://@HOST@:@PORT@/@DB@), and pins
        // sslmode=verify-full - see managedPostgresDatasourcesRequireVerifiedTls for why that literal matters.
        assertEquals(Map.of(
                "localpostgres", postgresTuple("jdbc:postgresql://127.0.0.1:5432/ofbiz?" + REQUIRED_SSL_MODE),
                "localpostgresolap", postgresTuple("jdbc:postgresql://127.0.0.1:5432/ofbizolap?" + REQUIRED_SSL_MODE),
                "localpostgrestenant", postgresTuple("jdbc:postgresql://127.0.0.1:5432/ofbiztenant?" + REQUIRED_SSL_MODE)),
                tuples, "managed PostgreSQL dialect, DDL tuple, driver and URI per datasource");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Managed credentials: nothing authenticable is committed, and the datasource fails closed
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void managedPostgresDatasourcesCommitNoPasswordAndNameALookupInstead() {
        Map<String, Map<String, String>> credentials = new LinkedHashMap<>();
        for (String name : POSTGRES_DATASOURCES) {
            Element inlineJdbc = inlineJdbcOf(name);
            credentials.put(name, Map.of(
                    "jdbc-username", inlineJdbc.getAttribute("jdbc-username"),
                    JDBC_PASSWORD, inlineJdbc.getAttribute(JDBC_PASSWORD),
                    JDBC_PASSWORD_LOOKUP, inlineJdbc.getAttribute(JDBC_PASSWORD_LOOKUP),
                    "has-password-attribute", String.valueOf(inlineJdbc.hasAttribute(JDBC_PASSWORD))));
        }

        // No password is committed for any managed datasource. jdbc-password is OPTIONAL in entity-config.xsd
        // while jdbc-username is REQUIRED, so the development username placeholder stays - a username on its own
        // cannot authenticate - and the password is replaced by a lookup named after the datasource. The raw
        // attribute is compared as well as its value, so re-adding jdbc-password="" would also fail here.
        assertEquals(Map.of(
                "localpostgres", credentialTuple("localpostgres"),
                "localpostgresolap", credentialTuple("localpostgresolap"),
                "localpostgrestenant", credentialTuple("localpostgrestenant")),
                credentials, "committed credential attributes per managed datasource");
    }

    @Test
    public void theManagedPasswordLookupIsUnresolvableSoTheDatasourceFailsClosed() throws Exception {
        Path passwords = repositoryRoot().resolve(PASSWORDS_PROPERTIES);
        assertTrue(Files.isRegularFile(passwords), "missing " + PASSWORDS_PROPERTIES);
        List<String> committedKeys = new ArrayList<>();
        for (String line : Files.readAllLines(passwords)) {
            if (!line.startsWith("#") && line.contains("=")) {
                committedKeys.add(line.substring(0, line.indexOf('=')).trim());
            }
        }

        for (String name : POSTGRES_DATASOURCES) {
            // The lookup is deliberately NOT satisfied by the committed passwords.properties, which only carries
            // the four embedded H2 entries. Committing an entry here would put the password back in the source
            // tree under a different name.
            assertFalse(committedKeys.contains("jdbc-password." + name),
                    PASSWORDS_PROPERTIES + " must not commit a password for the managed datasource " + name);

            // And this is what the production resolver does with that: EntityConfig.getJdbcPassword falls through
            // the empty jdbc-password to the lookup, finds no such property, and throws - naming the property an
            // operator has to supply. Before this change it returned the literal "ofbiz" from the file instead,
            // so a managed database provisioned with the documented default was reachable with no configuration
            // at all. Nothing here opens a connection; only the credential resolution is exercised.
            InlineJdbc inlineJdbc = new Datasource(datasourcesByName().get(name)).getInlineJdbc();
            assertEquals("", inlineJdbc.getJdbcPassword(), name + " must commit no password");
            assertEquals(name, inlineJdbc.getJdbcPasswordLookup(), name + " must name its lookup");
            Executable resolvePassword = () -> EntityConfig.getJdbcPassword(inlineJdbc);
            GenericEntityConfException failure = assertThrows(GenericEntityConfException.class, resolvePassword,
                    name + " must fail closed instead of resolving a password");
            assertTrue(failure.getMessage().contains("jdbc-password." + name),
                    "the failure must name the missing property, was: " + failure.getMessage());
        }
    }

    @Test
    public void managedPostgresDatasourcesRequireVerifiedTls() {
        for (String name : POSTGRES_DATASOURCES) {
            String uri = inlineJdbcOf(name).getAttribute("jdbc-uri");

            // verify-full is the only mode that both encrypts AND authenticates the server: it checks the
            // certificate chain and the hostname. Every weaker mode is a downgrade an attacker on the network can
            // exploit - pgJDBC's own default, prefer, silently falls back to an UNENCRYPTED connection when the
            // server declines TLS, and require encrypts without checking who answered. Asserting on the committed
            // URI is what stops the parameter being "tidied away" as noise.
            assertTrue(uri.contains("?" + REQUIRED_SSL_MODE),
                    name + " must pin " + REQUIRED_SSL_MODE + ", was: " + uri);
            for (String weaker : List.of("sslmode=disable", "sslmode=allow", "sslmode=prefer", "sslmode=require")) {
                assertFalse(uri.contains(weaker), name + " must not use " + weaker);
            }
        }
    }

    @Test
    public void theRenderTemplateCarriesTheTlsPlaceholderOnEveryManagedUri() throws Exception {
        Path template = repositoryRoot().resolve(POSTGRES_TEMPLATE);
        assertTrue(Files.isRegularFile(template), "missing render template " + POSTGRES_TEMPLATE);
        List<String> managedUris = new ArrayList<>();
        for (String line : Files.readAllLines(template)) {
            Matcher matcher = TEMPLATE_JDBC_URI.matcher(line);
            if (matcher.find()) {
                managedUris.add(matcher.group(1));
            }
        }

        // The other half of the transport-security contract. The committed file above states the mode literally;
        // the deployed profile receives it through this placeholder, which the entry point substitutes with the
        // resolved sslmode (and any sslrootcert). A template that lost the placeholder would render URIs with no
        // TLS parameters at all, so the shape is pinned here and re-checked at runtime on the rendered file.
        //
        // The port placeholder is pinned in the same assertion. Every structural component of the rendered URI has
        // to arrive through a placeholder the entry point validates; a port folded into @HOST@ would be the one
        // component that reached the connection string unchecked.
        assertEquals(List.of(
                "jdbc:postgresql://@HOST@:" + PORT_TOKEN + "/@OFBIZ_DB@" + SSL_PARAMS_TOKEN,
                "jdbc:postgresql://@HOST@:" + PORT_TOKEN + "/@OLAP_DB@" + SSL_PARAMS_TOKEN,
                "jdbc:postgresql://@HOST@:" + PORT_TOKEN + "/@TENANT_DB@" + SSL_PARAMS_TOKEN),
                managedUris, "every managed URI in " + POSTGRES_TEMPLATE + " must carry " + PORT_TOKEN
                        + " and end with " + SSL_PARAMS_TOKEN);
    }

    @Test
    public void theLiteralFalseIsRequiredBecauseCheckOnStartDefaultsToTrue() throws Exception {
        for (String name : POSTGRES_DATASOURCES) {
            Element element = datasourcesByName().get(name);

            // What the production parser actually resolves the committed attributes to.
            Datasource parsed = new Datasource(element);
            assertEquals(name, parsed.getName(), "parsed datasource name");
            assertEquals("postgres", parsed.getFieldTypeName(), name + " dialect");
            assertFalse(parsed.getCheckOnStart(), name + " must issue no startup schema check");
            assertFalse(parsed.getAddMissingOnStart(), name + " must add no missing schema at startup");
            assertEquals(POSTGRES_DRIVER, parsed.getInlineJdbc().getJdbcDriver(), name + " JDBC driver");

            // ...and why the literal matters. Datasource resolves check-on-start as !"false".equals(attribute), so
            // DELETING the attribute silently re-enables startup DDL, while add-missing-on-start resolves as
            // "true".equals(attribute) and defaults the other way. That asymmetry is the trap this asserts against:
            // tidying the "redundant" false away would hand the whole fleet DDL behaviour back.
            Element withoutFlags = (Element) element.cloneNode(true);
            withoutFlags.removeAttribute(CHECK_ON_START);
            withoutFlags.removeAttribute(ADD_MISSING_ON_START);
            Datasource defaulted = new Datasource(withoutFlags);
            assertTrue(defaulted.getCheckOnStart(),
                    "an absent " + CHECK_ON_START + " defaults to TRUE, so " + name + " must state false explicitly");
            assertFalse(defaulted.getAddMissingOnStart(), "an absent " + ADD_MISSING_ON_START + " defaults to false");
        }
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * The deployed-profile render template: the same guarantees, expressed as placeholders
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void theRenderTemplateGatesStartupDdlThroughOneSharedPlaceholderPerManagedDatasource() throws Exception {
        Map<String, Map<String, String>> flags = new LinkedHashMap<>();
        for (Map.Entry<String, Element> entry : childrenByName(templateRoot(), "datasource").entrySet()) {
            flags.put(entry.getKey(), Map.of(
                    CHECK_ON_START, entry.getValue().getAttribute(CHECK_ON_START),
                    ADD_MISSING_ON_START, entry.getValue().getAttribute(ADD_MISSING_ON_START)));
        }

        // Objective 4, the deployed half. Both flags of all three managed datasources carry the SAME placeholder,
        // so init mode and run mode cannot disagree with each other: the entry point substitutes the normalised
        // OFBIZ_SCHEMA_INIT once and every managed datasource moves together. A hardcoded "true" here would hand
        // startup DDL back to every serving instance, which is precisely what this refactor removed.
        Map<String, String> gated = Map.of(CHECK_ON_START, SCHEMA_DDL_TOKEN, ADD_MISSING_ON_START, SCHEMA_DDL_TOKEN);
        // The embedded datasources are NOT gated: they back the test delegator, which must self-initialise.
        Map<String, String> alwaysOn = Map.of(CHECK_ON_START, "true", ADD_MISSING_ON_START, "true");
        assertEquals(Map.of(
                "localpostgres", gated,
                "localpostgresolap", gated,
                "localpostgrestenant", gated,
                "localh2", alwaysOn,
                "localh2olap", alwaysOn,
                "localh2tenant", alwaysOn),
                flags, "startup-DDL attributes per datasource in " + POSTGRES_TEMPLATE);
    }

    @Test
    public void theRenderTemplateGatesCacheClearOnTheTwoDefaultDelegatorsAndNeverOnTest() throws Exception {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, Element> entry : childrenByName(templateRoot(), "delegator").entrySet()) {
            attributes.put(entry.getKey(), entry.getValue().hasAttribute(CACHE_CLEAR_ENABLED)
                    ? entry.getValue().getAttribute(CACHE_CLEAR_ENABLED)
                    : ABSENT);
        }

        // Objective 5, the deployed half. "test" is a single-JVM integration-test delegator: giving it the flag
        // would have integration runs publish invalidation messages onto a fleet's topic, so the attribute is
        // absent rather than present-and-false, and the entry point refuses a configuration that adds it.
        assertEquals(Map.of(
                "default", CACHE_CLEAR_TOKEN,
                "default-no-eca", CACHE_CLEAR_TOKEN,
                "test", ABSENT),
                attributes, "cache-clear attribute per delegator in " + POSTGRES_TEMPLATE);
    }

    @Test
    public void theRenderTemplateKeepsTheTestDelegatorOnTheEmbeddedDatasourcesItDefinesItself() throws Exception {
        Element template = templateRoot();
        Map<String, Element> delegators = childrenByName(template, "delegator");
        assertEquals(List.of("default", "default-no-eca", "test"), new ArrayList<>(delegators.keySet()),
                "the deployed profile must declare the same three immutable delegator names");

        // The production parser resolves the mapping, not a text match: this is the assertion that would fail if
        // the template ever repointed "test" at the managed datasources again. It used to do exactly that, which
        // meant running gradlew testIntegration inside a container wrote ext-test data into the production
        // database - integration tests must never need a managed database, a credential or a network.
        DelegatorElement test = new DelegatorElement(delegators.get("test"));
        assertEquals("localh2", test.getGroupDataSource(OFBIZ_GROUP), "test -> " + OFBIZ_GROUP);
        assertEquals("localh2olap", test.getGroupDataSource(OLAP_GROUP), "test -> " + OLAP_GROUP);
        assertEquals("localh2tenant", test.getGroupDataSource(TENANT_GROUP), "test -> " + TENANT_GROUP);
        assertFalse(test.getDistributedCacheClearEnabled(), "test must never take part in cache invalidation");

        // A mapping is only worth anything if the target is defined. The rendered file REPLACES the committed one
        // on the classpath, so the template has to carry the embedded definitions and the h2 field-type itself,
        // otherwise the test delegator would resolve to a datasource that does not exist.
        Map<String, Element> datasources = childrenByName(template, "datasource");
        for (String name : H2_DATASOURCES) {
            Element datasource = datasources.get(name);
            assertNotNull(datasource, POSTGRES_TEMPLATE + " must define the embedded datasource " + name);
            assertEquals("h2", datasource.getAttribute(FIELD_TYPE_NAME), name + " dialect");
            Element inlineJdbc = soleChild(datasource, "inline-jdbc");
            assertEquals(H2_DRIVER, inlineJdbc.getAttribute("jdbc-driver"), name + " must stay on the embedded driver");
            assertTrue(inlineJdbc.getAttribute("jdbc-uri").startsWith("jdbc:h2:file:"),
                    name + " must be a local file database, reachable with no host, port or network");
            // No placeholder may reach an embedded definition: it has to work in a container that was given a
            // managed database and in one that was not.
            for (String attribute : List.of("jdbc-uri", "jdbc-username", "pool-minsize", "pool-maxsize")) {
                assertFalse(PLACEHOLDER.matcher(inlineJdbc.getAttribute(attribute)).find(),
                        name + " " + attribute + " must carry no placeholder");
            }
        }
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        for (Map.Entry<String, Element> entry : childrenByName(template, "field-type").entrySet()) {
            fieldTypes.put(entry.getKey(), entry.getValue().getAttribute("location"));
        }
        assertEquals(Map.of("postgres", "fieldtypepostgres.xml", "h2", "fieldtypeh2.xml"), fieldTypes,
                "the deployed profile needs exactly the two dialects it maps datasources to");
    }

    @Test
    public void theRenderTemplateSizesEveryManagedPoolThroughAPlaceholderAndLeavesTheEmbeddedOnesAlone() throws Exception {
        Map<String, Element> datasources = childrenByName(templateRoot(), "datasource");
        Map<String, Map<String, String>> pools = new LinkedHashMap<>();
        for (Map.Entry<String, Element> entry : datasources.entrySet()) {
            Element inlineJdbc = soleChild(entry.getValue(), "inline-jdbc");
            pools.put(entry.getKey(), Map.of(
                    "pool-minsize", inlineJdbc.getAttribute("pool-minsize"),
                    "pool-maxsize", inlineJdbc.getAttribute("pool-maxsize")));
        }

        // The committed 2 and 250 are what OFBIZ_DB_POOL_MIN and OFBIZ_DB_POOL_MAX default to, so an operator who
        // sets neither gets exactly today's pool. The maximum matters when scaling out: it is per instance and per
        // datasource, and PostgreSQL's own max_connections defaults to 100, so a fleet needs to be able to lower it.
        Map<String, String> parameterised = Map.of("pool-minsize", POOL_MIN_TOKEN, "pool-maxsize", POOL_MAX_TOKEN);
        Map<String, String> committed = Map.of("pool-minsize", "2", "pool-maxsize", "250");
        assertEquals(Map.of(
                "localpostgres", parameterised,
                "localpostgresolap", parameterised,
                "localpostgrestenant", parameterised,
                "localh2", committed,
                "localh2olap", committed,
                "localh2tenant", committed),
                pools, "pool bounds per datasource in " + POSTGRES_TEMPLATE);
    }

    @Test
    public void theRenderTemplateNamesNoSecretPlaceholderOutsideAJdbcPasswordAttribute() throws Exception {
        Path template = repositoryRoot().resolve(POSTGRES_TEMPLATE);
        List<String> offendingLines = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(template)) {
            lineNumber++;
            if (line.contains("_PASSWORD@") && !line.contains(JDBC_PASSWORD + "=\"")) {
                offendingLines.add(lineNumber + ": " + line.trim());
            }
        }

        // sed rewrites a comment as readily as an attribute. A password placeholder named in prose - a token
        // inventory in the file header, say - is therefore substituted THERE as well, and the rendered
        // configuration repeats all three database passwords in clear text outside the attributes meant to hold
        // them. The entry point refuses to render a template that does this; asserting it here means the mistake
        // is caught at build time rather than by the container. Documentation names the environment variables.
        assertEquals(List.of(), offendingLines,
                POSTGRES_TEMPLATE + " may only name a password placeholder on a " + JDBC_PASSWORD + " attribute");
    }

    @Test
    public void theRenderTemplateSubstitutesToASchemaValidConfigurationWithNoPlaceholderLeftBehind() throws Exception {
        // Substituting with the same shape of values the entry point uses, then validating, proves the template is
        // not merely well-formed but still a legal entity-config once rendered - the state OFBiz actually reads.
        String rendered = Files.readString(repositoryRoot().resolve(POSTGRES_TEMPLATE));
        for (Map.Entry<String, String> substitution : TEMPLATE_SUBSTITUTIONS.entrySet()) {
            rendered = rendered.replace(substitution.getKey(), substitution.getValue());
        }

        Element root = parseXml(rendered);
        // Every placeholder the template carries must be in the map above, which is what turns "someone added a
        // token" into a failing test rather than a literal at-sign in a production connection string. Attribute
        // values are scanned rather than raw text, because the placeholder NAMES appear legitimately in comments.
        List<String> unsubstituted = new ArrayList<>();
        collectPlaceholders(root, unsubstituted);
        assertEquals(List.of(), unsubstituted, "attribute values still carrying a placeholder after substitution");

        assertEquals(List.of(), validateAgainstEntityConfigSchema(rendered), "schema validation problems");

        // And the values really landed where they were meant to: the mode flags on the managed datasources only.
        Map<String, Element> datasources = childrenByName(root, "datasource");
        for (String name : POSTGRES_DATASOURCES) {
            Datasource parsed = new Datasource(datasources.get(name));
            assertTrue(parsed.getCheckOnStart(), name + " must check the schema when init mode is substituted");
            assertTrue(parsed.getAddMissingOnStart(), name + " must add the missing schema in init mode");
            assertEquals(2, parsed.getInlineJdbc().getPoolMinsize(), name + " pool minimum");
            assertEquals(250, parsed.getInlineJdbc().getPoolMaxsize(), name + " pool maximum");
        }
        for (String name : H2_DATASOURCES) {
            assertTrue(new Datasource(datasources.get(name)).getCheckOnStart(),
                    name + " must keep self-initialising for the test delegator");
        }
        assertTrue(new DelegatorElement(childrenByName(root, "delegator").get("default")).getDistributedCacheClearEnabled(),
                "the substituted cache-clear value must reach the default delegator");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Per-dialect field-type mappings: an immutable interface
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void allTwelveDialectFieldTypeMappingsAreDeclaredAndPresentOnDisk() {
        Map<String, String> declared = new LinkedHashMap<>();
        for (Element fieldType : childElements(configRoot, "field-type")) {
            assertEquals("fieldfile", fieldType.getAttribute("loader"), "field-type loader");
            assertNull(declared.put(fieldType.getAttribute("name"), fieldType.getAttribute("location")),
                    "duplicate field-type name " + fieldType.getAttribute("name"));
        }

        // The abstract-field-type grammar and its per-dialect mapping files are frozen by the plan, so the
        // declarations and the files on disk are both compared as complete sets - a dropped dialect and an
        // orphaned file are equally regressions.
        assertEquals(EXPECTED_FIELD_TYPES, declared, "field-type name -> per-dialect mapping file");

        Path directory = repositoryRoot().resolve(FIELD_TYPE_DIRECTORY);
        List<String> onDisk = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.map(path -> path.getFileName().toString()).sorted().forEach(onDisk::add);
        } catch (IOException e) {
            throw new AssertionError("could not list " + directory, e);
        }
        assertEquals(EXPECTED_FIELD_TYPES.values().stream().sorted().toList(), onDisk,
                "every declared mapping file must exist, and no other file may live in " + FIELD_TYPE_DIRECTORY);
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Schema validity
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void configurationRemainsValidAgainstTheEntityConfigSchema() throws Exception {
        String config = Files.readString(repositoryRoot().resolve(ENTITY_ENGINE_XML));

        // jdbc-username is a REQUIRED attribute of inline-jdbc while jdbc-password is OPTIONAL, which is exactly
        // why the managed definitions could drop the committed password and keep the username placeholder. Schema
        // validity is what proves that asymmetry is still being honoured after the externalization work.
        assertEquals(List.of(), validateAgainstEntityConfigSchema(config), "schema validation problems");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * The JDBC driver behind those datasources must actually be supplied
     * ---------------------------------------------------------------------------------------------
     */

    @Test
    public void thePostgresJdbcDriverIsSuppliedOnTheClasspathWithoutAnyConnectionAttempt() throws Exception {
        // initialize=false: the class is resolved but its static initialiser never runs, so nothing registers with
        // DriverManager and no socket, endpoint or credential is involved. This is purely "is the driver there".
        Class<?> driver = Class.forName(POSTGRES_DRIVER, false, getClass().getClassLoader());

        assertTrue(java.sql.Driver.class.isAssignableFrom(driver), POSTGRES_DRIVER + " must implement java.sql.Driver");
        // Tie the manifest back to the configuration: the class that must exist is exactly the one the datasources
        // name. A driver swap in dependencies.gradle without the matching config edit fails here.
        for (String name : POSTGRES_DATASOURCES) {
            assertEquals(driver.getName(), inlineJdbcOf(name).getAttribute("jdbc-driver"),
                    name + " must name the driver that is actually on the classpath");
        }
    }

    @Test
    public void bothJdbcDriversAreDeclaredExactlyOnceAsRuntimeOnlyDependencies() {
        Map<String, List<String>> declarations = dependencyDeclarations();

        // runtimeOnly is the correct scope for a JDBC driver: it is loaded reflectively by name, so no component
        // compiles against it, and putting it on the compile classpath would invite a direct import. The scope is
        // asserted, not just the presence, because a mis-scoped driver still "works" locally and fails in the image.
        assertEquals(List.of(RUNTIME_ONLY), declarations.get(POSTGRES_COORDINATE),
                POSTGRES_COORDINATE + " must be declared exactly once, as " + RUNTIME_ONLY);
        // The H2 driver is untouched by this work: the development profile and the test delegator still need it.
        assertEquals(List.of(RUNTIME_ONLY), declarations.get(H2_COORDINATE),
                H2_COORDINATE + " must remain declared exactly once, as " + RUNTIME_ONLY);
        // Only ONE version of each driver may be declared, under any scope.
        assertEquals(List.of(POSTGRES_COORDINATE), coordinatesFor(declarations, "org.postgresql:postgresql"),
                "exactly one PostgreSQL driver version may be declared");
        assertEquals(List.of(H2_COORDINATE), coordinatesFor(declarations, "com.h2database:h2"),
                "exactly one H2 driver version may be declared");
    }

    /*
     * ---------------------------------------------------------------------------------------------
     * Helpers
     * ---------------------------------------------------------------------------------------------
     */

    private static Map<String, String> postgresTuple(String jdbcUri) {
        return Map.of(
                FIELD_TYPE_NAME, "postgres",
                CHECK_ON_START, "false",
                ADD_MISSING_ON_START, "false",
                "jdbc-driver", POSTGRES_DRIVER,
                "jdbc-uri", jdbcUri);
    }

    /** The credential attributes every managed datasource must carry: a username, no password, and a lookup. */
    private static Map<String, String> credentialTuple(String datasourceName) {
        return Map.of(
                "jdbc-username", "ofbiz",
                JDBC_PASSWORD, "",
                JDBC_PASSWORD_LOOKUP, datasourceName,
                "has-password-attribute", "false");
    }

    /** The {@code <delegator>} children of the root, keyed by name, in document order. */
    private Map<String, Element> delegatorsByName() {
        return childrenByName(configRoot, "delegator");
    }

    /** The {@code <datasource>} children of the root, keyed by name, in document order. */
    private Map<String, Element> datasourcesByName() {
        return childrenByName(configRoot, "datasource");
    }

    private static Map<String, Element> childrenByName(Element root, String localName) {
        Map<String, Element> byName = new LinkedHashMap<>();
        for (Element child : childElements(root, localName)) {
            String name = child.getAttribute("name");
            assertNull(byName.put(name, child), "duplicate <" + localName + "> named " + name);
        }
        return byName;
    }

    /**
     * The parsed root of the deployed-profile render template. It is read from the repository by path, exactly
     * like the committed configuration, and it parses as ordinary XML because every placeholder sits inside an
     * attribute value.
     */
    private static Element templateRoot() throws Exception {
        Path template = repositoryRoot().resolve(POSTGRES_TEMPLATE);
        assertTrue(Files.isRegularFile(template), "missing render template " + POSTGRES_TEMPLATE);
        Element root = parseXml(Files.readString(template));
        assertEquals("entity-config", root.getLocalName(), "render template root element");
        return root;
    }

    /** Parses a document with external entity resolution switched off. */
    private static Element parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory.newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)))
                .getDocumentElement();
    }

    /** The single child of a parent with the given local name. */
    private static Element soleChild(Element parent, String localName) {
        List<Element> children = childElements(parent, localName);
        assertEquals(1, children.size(),
                parent.getAttribute("name") + " must have exactly one <" + localName + "> child");
        return children.get(0);
    }

    /** Records {@code element/@attribute} for every attribute value still carrying a render placeholder. */
    private static void collectPlaceholders(Element element, List<String> found) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            if (PLACEHOLDER.matcher(attribute.getNodeValue()).find()) {
                found.add(element.getLocalName() + "[" + element.getAttribute("name") + "]/@"
                        + attribute.getNodeName() + "=" + attribute.getNodeValue());
            }
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                collectPlaceholders((Element) child, found);
            }
        }
    }

    /**
     * Validates a document against the LOCAL {@code entity-config.xsd}, returning the problems found. The
     * {@code xsi:noNamespaceSchemaLocation} hint these files carry points at ofbiz.apache.org, so the local
     * grammar is supplied explicitly and external access is switched off on top of that, keeping validation
     * hermetic and offline.
     */
    private static List<String> validateAgainstEntityConfigSchema(String xml) throws Exception {
        Path schemaFile = repositoryRoot().resolve(ENTITY_CONFIG_SCHEMA);
        assertTrue(Files.isRegularFile(schemaFile), "missing local schema " + schemaFile);
        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        schemaFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        forbidExternalAccess(schemaFactory);
        Validator validator = schemaFactory.newSchema(schemaFile.toFile()).newValidator();
        forbidExternalAccess(validator);
        List<String> problems = new ArrayList<>();
        validator.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException e) {
                problems.add("warning: " + e.getMessage());
            }

            @Override
            public void error(SAXParseException e) {
                problems.add("error: " + e.getMessage());
            }

            @Override
            public void fatalError(SAXParseException e) {
                problems.add("fatal: " + e.getMessage());
            }
        });
        validator.validate(new StreamSource(new StringReader(xml)));
        return problems;
    }

    /** The single {@code <inline-jdbc>} child of a named datasource. */
    private Element inlineJdbcOf(String datasourceName) {
        Element datasource = datasourcesByName().get(datasourceName);
        assertNotNull(datasource, "no datasource named " + datasourceName);
        List<Element> inlineJdbc = childElements(datasource, "inline-jdbc");
        assertEquals(1, inlineJdbc.size(), datasourceName + " must have exactly one <inline-jdbc> child");
        return inlineJdbc.get(0);
    }

    private static List<Element> childElements(Element parent, String localName) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(node.getLocalName())) {
                children.add((Element) node);
            }
        }
        return children;
    }

    /** Every dependency declaration in the manifest, as coordinate to the list of scopes it is declared under. */
    private static Map<String, List<String>> dependencyDeclarations() {
        Map<String, List<String>> byCoordinate = new LinkedHashMap<>();
        Path manifest = repositoryRoot().resolve(DEPENDENCY_MANIFEST);
        assertTrue(Files.isRegularFile(manifest), "missing authoritative manifest " + manifest);
        List<String> lines;
        try {
            lines = Files.readAllLines(manifest);
        } catch (IOException e) {
            throw new AssertionError("could not read " + manifest, e);
        }
        for (String line : lines) {
            Matcher matcher = DEPENDENCY_DECLARATION.matcher(line);
            if (matcher.find()) {
                byCoordinate.computeIfAbsent(matcher.group(2), key -> new ArrayList<>()).add(matcher.group(1));
            }
        }
        return byCoordinate;
    }

    private static List<String> coordinatesFor(Map<String, List<String>> declarations, String groupAndModule) {
        return declarations.keySet().stream().filter(coordinate -> coordinate.startsWith(groupAndModule + ":")).sorted().toList();
    }

    /**
     * Forbids the loading of anything external, so validation can never turn into a web request for the
     * {@code https://ofbiz.apache.org} schema hint this file carries. Both the JDK and the Xerces build on the
     * classpath may provide the {@code SchemaFactory}, and they do not agree on which of these JAXP properties
     * they recognise, so an unsupported one is tolerated: the explicit local grammar already keeps validation
     * self-contained, and this is belt and braces on top.
     */
    private static void forbidExternalAccess(SchemaFactory factory) {
        for (String property : List.of(XMLConstants.ACCESS_EXTERNAL_DTD, XMLConstants.ACCESS_EXTERNAL_SCHEMA)) {
            try {
                factory.setProperty(property, "");
            } catch (SAXNotRecognizedException | SAXNotSupportedException unsupported) {
                continue;
            }
        }
    }

    /** @see #forbidExternalAccess(SchemaFactory) */
    private static void forbidExternalAccess(Validator validator) {
        for (String property : List.of(XMLConstants.ACCESS_EXTERNAL_DTD, XMLConstants.ACCESS_EXTERNAL_SCHEMA)) {
            try {
                validator.setProperty(property, "");
            } catch (SAXNotRecognizedException | SAXNotSupportedException unsupported) {
                continue;
            }
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve(DEPENDENCY_MANIFEST))) {
            candidate = candidate.getParent();
        }
        assertNotNull(candidate, "could not locate the repository root from " + Paths.get("").toAbsolutePath());
        return candidate;
    }
}

#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
###############################################################################

###############################################################################
# OFBiz initialisation script for use as the entry point in a docker container.
#
# Validates the deployment configuration, renders it into /ofbiz/config, performs the one-off data
# initialisation of a new container, and then executes the command given as arguments to the script.
#
# Execution flow, in the order _main takes it:
#   1. Resolve the deployment profile, then validate every setting and secret that was supplied. A
#      misconfiguration is refused here, before anything is written.
#   2. The before-config-applied hooks.
#   3. Render the configuration overrides: the entity datasource and its startup-DDL flags, the security
#      and start properties, the catalina descriptor, the cache-invalidation transport and the object
#      store. /ofbiz/config takes class path precedence over the copies inside the distribution, and
#      every override that carries a secret is written atomically with mode 0600.
#   4. The after-config-applied hooks.
#   5. With OFBIZ_SCHEMA_INIT=true, apply the entity-model DDL and exit 0 without serving traffic.
#   6. Otherwise load seed or demo data and create the administrative user - the before-data-load,
#      additional-data and after-data-load hooks included - unless the recorded container state already
#      accounts for that work or OFBIZ_SKIP_INIT asks for it to be skipped.
#   7. Remove every container variable from the environment and exec the command.
#
# The configuration is resolved, validated and rendered on EVERY start, OFBIZ_SKIP_INIT included:
# /ofbiz/config is a volume that outlives the container, so a setting that is withdrawn has to stop
# applying and a stale override has to be corrected or removed. Only the data initialisation of step 6 is
# skippable, and a file this script did not write is inspected and left alone.
#
# Hooks are executable scripts with the .sh extension in /docker-entrypoint-hooks/<stage>.d, where
# <stage> is one of before-config-applied, after-config-applied, before-data-load, additional-data or
# after-data-load. A hook sees no secret unless OFBIZ_HOOK_SECRET_ALLOWLIST names it.
#
# Environment contract. Every name this script reads is listed below, grouped by what it configures.
# DOCKER.adoc is the reference for the accepted values, the defaults, the validation rules and the
# deployment procedure of each one. A name that is not supplied leaves the behaviour the committed
# configuration already has, so a container started with no environment at all boots on the embedded H2
# database with database content storage and a single-node cache.
#
# Container control. OFBIZ_SKIP_DB_DRIVER_DOWNLOAD is obsolete: it is accepted for compatibility with
# existing deployment manifests and ignored, because the PostgreSQL JDBC driver ships with the
# distribution.
# OFBIZ_PROFILE
# OFBIZ_SKIP_INIT
# OFBIZ_SCHEMA_INIT
# OFBIZ_DATA_LOAD
# OFBIZ_DISABLE_COMPONENTS
# OFBIZ_HOOK_SECRET_ALLOWLIST
# OFBIZ_TRACE
# OFBIZ_SKIP_DB_DRIVER_DOWNLOAD
#
# Deployment secrets. Required in the prod profile and generated per container in dev. Each is written
# ONLY into /ofbiz/config, atomically and with mode 0600, and is removed from the environment before
# OFBiz is executed.
# OFBIZ_ADMIN_KEY
# OFBIZ_LOGIN_SECRET_KEY
# OFBIZ_JWT_TOKEN_KEY
#
# Administrative user. The admin user holds every OFBiz permission, so the password is a credential
# rather than a convenience setting: it is required in the prod profile, where a weak or published value
# is refused.
# OFBIZ_ADMIN_USER
# OFBIZ_ADMIN_PASSWORD
#
# Managed database. OFBIZ_POSTGRES_HOST is the only setting that selects it, so unset every
# OFBIZ_POSTGRES_ name to use the embedded H2 database instead. No name in this group may be supplied
# with an empty value: an empty value is what an unresolved secret reference or an unrendered template
# leaves behind, so it is refused by name rather than replaced by a default.
# OFBIZ_POSTGRES_HOST
# OFBIZ_POSTGRES_PORT
# OFBIZ_POSTGRES_OFBIZ_DB
# OFBIZ_POSTGRES_OFBIZ_USER
# OFBIZ_POSTGRES_OFBIZ_PASSWORD
# OFBIZ_POSTGRES_OLAP_DB
# OFBIZ_POSTGRES_OLAP_USER
# OFBIZ_POSTGRES_OLAP_PASSWORD
# OFBIZ_POSTGRES_TENANT_DB
# OFBIZ_POSTGRES_TENANT_USER
# OFBIZ_POSTGRES_TENANT_PASSWORD
# OFBIZ_POSTGRES_SSLMODE
# OFBIZ_POSTGRES_SSLROOTCERT
# OFBIZ_POSTGRES_CONNECT_TIMEOUT
# OFBIZ_POSTGRES_SOCKET_TIMEOUT
# OFBIZ_POSTGRES_LOGIN_TIMEOUT
# OFBIZ_POSTGRES_CANCEL_TIMEOUT
# OFBIZ_POSTGRES_QUERY_TIMEOUT
# OFBIZ_POSTGRES_TCP_KEEPALIVE
# OFBIZ_DB_POOL_MIN
# OFBIZ_DB_POOL_MAX
# OFBIZ_DB_POOL_WAIT
# OFBIZ_DB_POOL_TEST_ON_BORROW
# OFBIZ_DB_FLEET_SIZE
# OFBIZ_DB_MAX_CONNECTIONS
#
# Schema-initialisation identity. Read only by an OFBIZ_SCHEMA_INIT=true run, so that the roles the
# fleet serves with need no privilege to create, alter or drop anything. Setting check-on-start and
# add-missing-on-start to false stops a serving instance from issuing DDL; it does not take the ability
# away. These six variables are what actually remove the privilege from the serving path.
# OFBIZ_POSTGRES_OFBIZ_INIT_USER
# OFBIZ_POSTGRES_OFBIZ_INIT_PASSWORD
# OFBIZ_POSTGRES_OLAP_INIT_USER
# OFBIZ_POSTGRES_OLAP_INIT_PASSWORD
# OFBIZ_POSTGRES_TENANT_INIT_USER
# OFBIZ_POSTGRES_TENANT_INIT_PASSWORD
#
# Cross-instance entity cache invalidation. OFBIZ_DISTRIBUTED_CACHE_CLEAR requires a transport in every
# profile, because the services that carry the invalidations are dispatched over JMS and a dispatch with
# no active serviceMessenger rolls back the entity write that triggered it. Either let the OFBIZ_JMS_
# names below render config/serviceengine.xml and config/jndi.properties, or supply
# config/serviceengine.xml directly.
# OFBIZ_DISTRIBUTED_CACHE_CLEAR
# OFBIZ_JMS_INITIAL_CONTEXT_FACTORY
# OFBIZ_JMS_PROVIDER_URL
# OFBIZ_JMS_CONNECTION_FACTORY_JNDI_NAME
# OFBIZ_JMS_TOPIC_JNDI_NAME
# OFBIZ_JMS_TOPIC_PHYSICAL_NAME
# OFBIZ_JMS_USERNAME
# OFBIZ_JMS_PASSWORD
# OFBIZ_JMS_CONNECT_TIMEOUT
#
# Load balancer and reverse proxy. Each of these restores the committed value when it is not supplied,
# so withdrawing one from a restarted container really does withdraw the setting rather than leave the
# previous start's value in place.
# OFBIZ_HOST
# OFBIZ_CONTENT_URL_PREFIX
# OFBIZ_ENABLE_AJP_PORT
# OFBIZ_JVM_ROUTE
# OFBIZ_SSL_ACCELERATOR_PORT
# OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS
#
# Object storage. OFBIZ_CONTENT_STORE_PROVIDER selects which backend stores DataResource content and
# uploaded files - database, filesystem or s3. This and the six OFBIZ_S3_* variables below are rendered
# into a mode 0600 config/content.properties, which the generated start script resolves ahead of the copy
# inside ofbiz.jar, and all seven are then removed from the environment before the OFBiz JVM is exec'd.
# Supplying none of the group writes no override at all, which leaves the committed database storage in
# force.
# OFBIZ_CONTENT_STORE_PROVIDER
# OFBIZ_S3_BUCKET
# OFBIZ_S3_REGION
# OFBIZ_S3_ENDPOINT
# OFBIZ_S3_ACCESS_KEY_ID
# OFBIZ_S3_SECRET_ACCESS_KEY
# OFBIZ_S3_PATH_STYLE
###############################################################################
# Shell tracing is OPT-IN and is never active while a secret is being handled.
#
# Bash echoes every expanded command to stderr, which in a container is the retained log stream, and this
# script injects secrets into the OFBiz configuration - so tracing happens only when the operator asks
# for it. Two ways of asking are recognised and are equivalent from here on: the ambient shell flag, read
# from '$-' ('bash -x docker-entrypoint.sh', or SHELLOPTS=xtrace inherited at start up), and OFBIZ_TRACE,
# which any non-empty value turns on for the whole of this script. They are combined rather than chosen
# between, because the environment switch can only turn tracing ON, so an operator who already ran the
# entry point under 'bash -x' keeps their trace either way.
#
# A region that merely asks WHETHER a secret was supplied is a secret-handling region too, because bash
# expands a command's arguments before echoing them. Every presence check, census and comparison over a
# name in SECRET_ENVIRONMENT_VARIABLES therefore runs inside a hide_secrets region, and restore_trace
# resumes whichever trace was in force once the region ends. Output produced by a sourced hook, by the
# OFBiz JVM or by any other process is outside that scope.
case $- in
  *x*) TRACE_ENABLED="true" ;;
  *) TRACE_ENABLED="false" ;;
esac
if [ -n "$OFBIZ_TRACE" ]; then
  TRACE_ENABLED="true"
  set -x
fi
set -e

###############################################################################
# Signal and exit handling.
#
# SIGTERM and SIGINT are TERMINATION requests, so the handler below ends this process instead of
# returning to whatever it interrupted. A handler that merely ran a shutdown helper and returned left
# the script running: bash resumes the interrupted command's caller once a trap handler returns, so
# initialisation carried on - loading data, rendering configuration and finally exec'ing the server -
# after the orchestrator had already asked the container to stop. The handler therefore forwards the
# signal to the exact initialisation child it is waiting on, removes any secret-bearing temporary
# file, and exits with the conventional 128+signal status so the orchestrator sees a signal death
# rather than a spurious success.
#
# The handler only ever runs DURING initialisation. The last thing this script does is 'exec' the
# server command, which replaces this shell - traps and all - so from that point the JVM receives
# SIGTERM directly as PID 1 and owns its own shutdown, which is exactly the intended arrangement.
#
# The EXIT trap is a cleanup of last resort for the mode 0600 temporary files that hold secret
# material while a configuration file is rendered. They are removed on every normal path, but an
# external command that fails under 'set -e' exits without passing through that code, so the trap
# guarantees no secret is left behind in /tmp. It removes only the exact files this script registered.
#
# All three traps are ARMED at the very bottom of this file, immediately before _main is called, once
# every function they invoke has been defined. Arming them here instead would leave a window in which
# a signal invoked a handler that did not exist yet, and bash would then simply carry on; until they
# are armed the default disposition applies, which for SIGTERM and SIGINT is to terminate the shell -
# the correct outcome, since no work has been done at that point.

# Process id of the initialisation child - always an OFBiz data-load JVM - that this script is
# currently waiting on, or empty when none is running. The termination handler forwards the signal to
# exactly this process rather than to the whole process group, so an unrelated process that happens to
# share the container's namespace is never signalled.
ACTIVE_CHILD_PID=""

CONTAINER_STATE_DIR="/ofbiz/runtime/container_state"
CONTAINER_DATA_LOADED="$CONTAINER_STATE_DIR/data_loaded"
CONTAINER_ADMIN_LOADED="$CONTAINER_STATE_DIR/admin_loaded"
CONTAINER_CONFIG_APPLIED="$CONTAINER_STATE_DIR/config_applied"
CONTAINER_DB_CONFIG_APPLIED="$CONTAINER_STATE_DIR/db_config_applied"

# Encoding version of the container state markers. Every marker written by this script is a two line
# file - a descriptive body and the SHA-256 of that body - rather than an empty file. The version is the
# first field of the body so that a marker written by an older image
# is recognised as foreign and its work redone, instead of being read with the wrong field meanings.
CONTAINER_MARKER_FORMAT=1

# Encoding version of the desired-state records - the database configuration record and the schema-init
# receipt. It is the FIRST LINE of both, and both readers compare that line against this value before
# they believe a word of what follows, so a record written by an older image is read as "no comparable
# record" and rewritten instead of being compared field by field with fields that did not mean the same
# thing. Declared once because the writer and the two readers have to move together: a bump applied to
# the writer alone would make every start report a target change that never happened, and one applied to
# a reader alone would discard a record that was perfectly good.
DESIRED_STATE_RECORD_VERSION='version=2'

# Random per-state-directory value mixed into the admin marker's payload digest.
#
# The admin marker has to change when the admin user name or password changes, which means its payload
# is derived from a credential. The salt is what stops the stored digest from being an offline
# dictionary check against the admin password for anyone who later obtains the marker without the
# volume it belongs to. Created on first use, mode 0600.
CONTAINER_ADMIN_MARKER_SALT="$CONTAINER_STATE_DIR/admin_marker_salt"

# Set to true by load_data once the data load that applies the entity-model schema has actually run to
# completion. The one-shot schema-init mode refuses to report success unless it is true, so a marker,
# a skipped stage or an early return can never produce an exit status that says "the schema is ready"
# when nothing created it.
SCHEMA_APPLYING_LOAD_RAN="false"

# Admin marker payload meaning "the administrator in this database is the account the demo data set
# ships with", as opposed to a credential this script applied.
#
# OFBIZ_DATA_LOAD=demo loads an admin user that is part of the demo data, so the marker written after
# that load must describe THAT account and not whatever OFBIZ_ADMIN_PASSWORD says. Recording it as a
# fixed token rather than as a digest is deliberate: the demo account's password is published in this
# repository, so there is nothing to protect, and a token needs no salt - which is what lets the demo
# image write this marker at build time without baking a salt into a public image layer.
#
# The practical effect is that load_admin_user still runs after a demo load and applies the requested
# credential, because this payload can never equal the digest of a real credential. Before this change
# the demo branch simply touched the marker, so a supplied OFBIZ_ADMIN_PASSWORD was silently discarded
# whenever demo data was loaded and the account kept the published demo password.
ADMIN_MARKER_DEMO_PAYLOAD='admin=demo-data'

# Record of the last schema initialisation this container completed, holding the same versioned
# non-secret fingerprint as db_config_applied so the record is mode and database specific.
#
# It is deliberately NOT a gate. Schema initialisation never consults it to decide whether to apply
# DDL, because the whole point of the init job is to apply DDL: the entity model's startup DDL is
# additive - it creates missing tables and columns and never drops - so re-applying it is safe, while
# skipping it because some marker happened to exist on a reused volume would exit 0 having created
# nothing. It exists so the operator can see WHICH database this container last initialised, which is
# the difference between "already done" and "you have just pointed the init job at a different
# database", and so an init run is recorded separately from the generic data_loaded state.
CONTAINER_SCHEMA_INITIALISED="$CONTAINER_STATE_DIR/schema_initialised"

# Identifies THIS execution of the entry point. The schema-init receipt records it, and the exit check
# requires the receipt to carry exactly this value, so a receipt left by an earlier run - or by a run
# against a different database - can never be mistaken for evidence that this run did the work. The PID
# is unique among live processes and the nanosecond clock separates successive containers that reuse it.
SCHEMA_INIT_RUN_TOKEN="$$-$(date --utc +%Y%m%dT%H%M%S.%N)"

# Messages the entity engine writes while applying and then verifying the startup DDL, used by
# initialise_schema to decide whether the schema was really applied - completely, not just partially.
#
# Reading the engine's log is not the first choice, but the loader's exit status is not evidence: a
# '--load-data readers=none' execution has no reader to take a row from, so it reports "Finished the data
# load with 0 rows changed" - a count of the rows the READERS loaded, which is why it stays 0 even though
# the loader upserts the framework's own Component metadata before it resolves any reader - and exits 0
# EVEN WHEN the database check that runs before it failed outright. Verified
# against a live PostgreSQL with one wrong password - DatabaseUtil logged that it could not connect and
# aborted, the JVM still exited 0, and not one table existed afterwards. Since the only reason this mode
# exists is that its exit status means "the schema is ready", these signatures are what make that true.
#
# Every literal below comes from GenericDelegator or DatabaseUtil, and every one is pinned to its
# emitting source by a build time test, so a rewording upstream fails the build instead of reaching
# production.
#
# WHY THE ABSENCE OF THESE IS STILL NOT ENOUGH ON ITS OWN, and why initialise_schema runs a second
# pass: DatabaseUtil logs and CONTINUES on a failed DDL statement, so a run in which one table could
# not be created reports every other table it did create and still returns normally. Log inspection
# alone therefore has to enumerate every way the engine can report a failure, and an enumeration is
# only ever as complete as the last time somebody checked it. The verification pass replaces that
# reasoning with a direct question to the database itself - "is anything still missing?" - which is why
# the residual signatures below exist and why they are the last word on completeness.

# The per entity group line GenericDelegator logs immediately BEFORE it hands the group to
# DatabaseUtil.checkDb, once for every group whose datasource has check-on-start enabled. The
# add-missing-on-start value of that datasource is concatenated onto it, so the same message
# distinguishes the two passes: the applying pass renders true, the verifying pass renders false.
# Counting these lines is what makes the second pass a completeness PROOF rather than a spot check -
# the verifying pass has to visit exactly as many entity groups as the applying pass did, so a
# verification that silently checked fewer groups than it applied cannot be mistaken for a clean one.
# Both are logged at info level, which is the shipped default; a container that suppresses info logging
# fails closed here - it refuses to record success rather than claiming it.
SCHEMA_INIT_DDL_SIGNATURE='Doing database check as requested in entityengine.xml with addMissing=true'
SCHEMA_INIT_VERIFY_SIGNATURE='Doing database check as requested in entityengine.xml with addMissing=false'

# The check ran and then gave up. Every one of these leaves DatabaseUtil.checkDb without having looked
# at a single entity - the first two abandon the connection or the helper outright, the third loses the
# metadata handle, and the last three are the explicit 'aborting.' returns after a failed catalogue
# read - so any of them means nothing was created, however the loader itself exited.
#
# 'No connection available for helper named [' is the fourth. GenericDelegator raises it when a helper
# has no usable connection at all, so the check never starts for that helper's entity group. It is
# thrown rather than logged by DatabaseUtil, but it reaches the captured loader log just the same, and
# a run in which it appears created nothing for that group.
SCHEMA_INIT_ABORT_SIGNATURES=(
  'Unable to establish a connection with the database'
  'Cannot run checkDb on a legacy database connection'
  'Unable to get database meta data'
  'No connection available for helper named ['
  'Could not get table name information from the database, aborting.'
  'Could not get column information from the database, aborting.'
  'Could not get schema name the database, aborting.'
)

# The check ran to the end but an individual DDL statement failed. This is the silent failure the
# explicit check below exists for: DatabaseUtil logs each of these at error level and then carries on
# with the next entity, so the pass finishes, the loader exits 0, no abort message is ever printed, and
# the database is left with some tables and not others. The first two are the ones that matter for a
# fresh managed database; the four index and foreign key forms only fire when a deployment enables
# check-fks-on-start, check-fk-indices-on-start or check-indices-on-start (all default to false in
# entity-config.xsd) or when the post-creation index helpers fail for a table that was just added.
SCHEMA_INIT_DDL_FAILURE_SIGNATURES=(
  'Could not create table ['
  'Could not add column ['
  'Could not create declared indices for entity ['
  'Could not create foreign key indices for entity ['
  'Could not create foreign key '
  'Could not create foreign key index '
  'Could not create index '
)

# Something is STILL missing. DatabaseUtil emits both of these before it decides whether to create
# anything, so they appear in the applying pass as a matter of course and mean nothing there. In the
# verifying pass, where add-missing-on-start is false and no DDL can be issued, either one is a direct
# statement from the database catalogue that the applying pass did not finish the job.
#
# Deliberately limited to tables and columns. The engine's foreign key and index residuals - "No
# Foreign Key Constraint [x] found" and "No Index [x] found" - are NOT completeness evidence: they are
# governed by the three check-*-on-start flags that ship disabled, and DatabaseUtil's own source
# records that the foreign key comparison "ISN'T working for Postgres or MySQL", so treating them as
# failures would make a correctly initialised PostgreSQL database look incomplete. Table and column
# presence, by contrast, is read straight from the catalogue through getTableNames and getColumnInfo,
# which is exactly what the fleet needs to be true before it serves a request.
SCHEMA_INIT_RESIDUAL_SIGNATURES=(
  '] has no table in the database'
  '] is missing its corresponding '
)

# Secrets generated by this script when OFBIZ_PROFILE=dev and the operator supplied none. Storing
# them alongside the other container state keeps a generated key stable for the life of the container
# - matching the stability the old build-time key generation provided - while keeping it out of the
# source tree and out of every image layer. Nothing is ever written here in the prod profile.
CONTAINER_GENERATED_SECRETS_DIR="$CONTAINER_STATE_DIR/generated_secrets"

# Directory that takes class path precedence over the bundled jars. Kept for operator supplied jars,
# but a JDBC driver placed here would shadow the bundled one, which is checked for on start up.
LIB_EXTRA_DIR="/ofbiz/lib-extra"

# The catalina component descriptor. Unlike the property files this script renders into /ofbiz/config,
# OFBiz locates component descriptors at their fixed path in the source tree, so the class path
# override does not apply and this file has to be edited where it lies. Named once here because four
# separate edits target it: the connector address insertion and the three Objective 5 rewrites.
CATALINA_COMPONENT_DESCRIPTOR="/ofbiz/framework/catalina/ofbiz-component.xml"

# The values the committed descriptor declares for the three load-balancer properties.
#
# They are held here, and written back whenever the corresponding variable is absent, because this
# descriptor is edited IN PLACE rather than rendered into /ofbiz/config from a pristine source. After the
# first container start the committed value is gone from the running instance, so unless the default is
# reconstructed from a value known to this script, "remove the variable" means nothing: a TLS accelerator
# port or a cross-subdomain session valve enabled once would stay enabled for the life of the instance.
# framework/catalina's CatalinaContainerDescriptorTests asserts these same three values on the committed
# descriptor, so the two cannot drift apart unnoticed.
# The jvm-route default is a VALUE rather than nothing on purpose. ContainerConfig treats an empty
# property as absent, so restoring the committed value is what actually removes an operator's override
# - writing an empty string would leave the property declared and the attribute unset, which is a
# third state the descriptor never has in the repository.
CATALINA_DEFAULT_JVM_ROUTE="jvm1"
CATALINA_DEFAULT_SSL_ACCELERATOR_PORT=""
CATALINA_DEFAULT_CROSS_SUBDOMAIN_SESSIONS="false"

# The two container blocks the descriptor declares. Every edit below is confined to the production one:
# the descriptor carries a second, nearly identical "catalina-container-test" block used by
# testIntegration, whose AJP connector listens on its own port, and an edit that is not scoped reaches
# both - rewriting a container that is not part of the served fleet and, for the connector address,
# inserting a duplicate line on every retry. The closing quote is part of each pattern, which is what
# keeps 'name="catalina-container"' from also matching 'name="catalina-container-test"'.
CATALINA_PRODUCTION_CONTAINER='name="catalina-container"'
CATALINA_TEST_CONTAINER='name="catalina-container-test"'
# The connector whose bind address is inserted for OFBIZ_ENABLE_AJP_PORT, and the line inserted after it.
CATALINA_AJP_CONNECTOR_ANCHOR='<property name="ajp-connector" value="connector">'
CATALINA_CONNECTOR_ADDRESS_ANCHOR='<property name="address" value='

# The entity engine configuration: the pristine committed copy, the deployed-profile template rendered
# from it, and the generated override. /ofbiz/config precedes ofbiz.jar on the runtime class path, so
# whichever of the two sources is rendered into ENTITY_ENGINE_OVERRIDE is the configuration OFBiz reads.
# Both sources are used, and which one depends only on whether a managed database is configured:
# OFBIZ_POSTGRES_HOST selects the template, and the committed file is the source for the embedded
# profile, where OFBIZ_DISTRIBUTED_CACHE_CLEAR is the only setting that has anything to substitute.
ENTITY_ENGINE_SOURCE="framework/entity/config/entityengine.xml"
ENTITY_ENGINE_TEMPLATE="templates/postgres-entityengine.xml"
ENTITY_ENGINE_OVERRIDE="config/entityengine.xml"

# The delegators whose cache-clear flag OFBIZ_DISTRIBUTED_CACHE_CLEAR applies to. "test" is excluded on
# purpose: it is the single-JVM integration-test delegator, so it must neither publish nor consume
# invalidation messages, and it carries no distributed-cache-clear-enabled attribute to substitute.
CACHE_CLEAR_DELEGATORS=(default default-no-eca)

# Every managed-database variable OTHER than the OFBIZ_POSTGRES_HOST that selects the mode.
#
# Each of these describes a PostgreSQL connection and has no meaning without one: a database name, a
# user, a password, a TLS setting, a network deadline or an initialisation identity. Supplying any of
# them is therefore evidence that a managed database was intended, which is what
# require_consistent_database_selection needs in order to tell "no managed database was asked for" apart
# from "a managed database was asked for and the host is missing". The two are indistinguishable from
# OFBIZ_POSTGRES_HOST alone, and the second silently started the embedded database on a fleet configured
# for PostgreSQL.
#
# OFBIZ_DB_POOL_*, OFBIZ_DB_FLEET_SIZE and OFBIZ_DB_MAX_CONNECTIONS are deliberately absent. They size
# and audit a pool rather than describe a connection, ofbiz_setup_env defaults the pool bounds on every
# start, and a container that sets only a pool size has expressed no intent about which database it
# talks to.
MANAGED_DATABASE_VARIABLES=(
  OFBIZ_POSTGRES_PORT
  OFBIZ_POSTGRES_OFBIZ_DB
  OFBIZ_POSTGRES_OFBIZ_USER
  OFBIZ_POSTGRES_OFBIZ_PASSWORD
  OFBIZ_POSTGRES_OLAP_DB
  OFBIZ_POSTGRES_OLAP_USER
  OFBIZ_POSTGRES_OLAP_PASSWORD
  OFBIZ_POSTGRES_TENANT_DB
  OFBIZ_POSTGRES_TENANT_USER
  OFBIZ_POSTGRES_TENANT_PASSWORD
  OFBIZ_POSTGRES_SSLMODE
  OFBIZ_POSTGRES_SSLROOTCERT
  OFBIZ_POSTGRES_CONNECT_TIMEOUT
  OFBIZ_POSTGRES_SOCKET_TIMEOUT
  OFBIZ_POSTGRES_LOGIN_TIMEOUT
  OFBIZ_POSTGRES_CANCEL_TIMEOUT
  OFBIZ_POSTGRES_QUERY_TIMEOUT
  OFBIZ_POSTGRES_TCP_KEEPALIVE
  OFBIZ_POSTGRES_OFBIZ_INIT_USER
  OFBIZ_POSTGRES_OFBIZ_INIT_PASSWORD
  OFBIZ_POSTGRES_OLAP_INIT_USER
  OFBIZ_POSTGRES_OLAP_INIT_PASSWORD
  OFBIZ_POSTGRES_TENANT_INIT_USER
  OFBIZ_POSTGRES_TENANT_INIT_PASSWORD
)

# The service engine configuration, in the CLASSPATH PRECEDENCE order the service engine resolves it:
# serviceengine.xml is read as a FLAT class path resource (ResourceLoader.readXmlDocument goes through
# UtilURL.fromResource), and the generated start script puts /ofbiz/config FIRST on the class path, so a
# copy at the first path below shadows the one shipped in the distribution. The order matters: the
# transport pre-flight has to inspect whichever file OFBiz will actually read.
SERVICE_ENGINE_CANDIDATES=(
  "config/serviceengine.xml"
  "framework/service/config/serviceengine.xml"
)

# The cache-invalidation transport: the pristine committed service engine configuration and the override
# rendered from it. The shipped file declares the jms-service that carries entity cache invalidations
# INSIDE AN XML COMMENT, so the transport is inert until something activates it - and activating it is
# what turns OFBIZ_DISTRIBUTED_CACHE_CLEAR from a claim into a working fleet.
#
# The committed source is never modified; the override is generated from it on every start. That is
# required rather than merely tidy: the file is shared by the whole distribution, and a JMS provider is
# deployment configuration rather than something that belongs in a source tree.
SERVICE_ENGINE_SOURCE="framework/service/config/serviceengine.xml"
SERVICE_ENGINE_OVERRIDE="${SERVICE_ENGINE_CANDIDATES[0]}"

# The JNDI server definitions: the pristine committed file and the override rendered from it. This is
# where the broker's client factory and provider URL are declared, and declaring them HERE rather than
# in jndi.properties is the entire point of the pair.
#
# JNDIContextFactory.getInitialContext(name) looks the name up in jndiservers.xml. When the named server
# carries a context-provider-url it builds a Hashtable holding INITIAL_CONTEXT_FACTORY, PROVIDER_URL,
# URL_PKG_PREFIXES and the security pair and calls new InitialContext(h) - a context scoped to that one
# server. When it does not - the case for the shipped <jndi-server name="default"/>, whose entire purpose
# is to carry no parameters - it calls the bare new InitialContext(), and the JDK then resolves
# java.naming.factory.initial from the jndi.properties files on the class path, merged
# FIRST-ENTRY-WINS with /ofbiz/config first.
#
# The bare constructor is JVM-wide, and three other places in OFBiz reach it: CatalinaContainer builds
# Tomcat's global naming context from it and treats a failure as fatal, RmiServiceContainer rebinds and
# looks up the RMI dispatcher through it, and entityengine.xml's user-transaction-jndi and
# transaction-manager-jndi both name the "default" server. Pointing that shared default at a message
# broker would repurpose all of them, so this script never touches it: it renders a DEDICATED
# jndi-server instead and leaves jndi.properties' standard keys exactly as shipped.
#
# JNDIConfigUtil reads jndiservers.xml as a FLAT class path resource and stores one entry per
# jndi-server element, so a copy in /ofbiz/config REPLACES the committed file wholesale rather than
# adding to it. The override is therefore rendered FROM the committed file, so that every server it
# ships - default, localjndi, OpenJMS, localorion, localweblogic - survives.
JNDI_SERVERS_SOURCE="framework/base/config/jndiservers.xml"
JNDI_SERVERS_OVERRIDE="config/jndiservers.xml"

# The JNDI client settings file. Its standard keys are deliberately NOT rendered - see above - but the
# destination mapping the broker's factory needs has nowhere else to live: 'topic.<lookup name>',
# 'connectionFactoryNames' and 'connectionFactory.<name>' are not jndiservers.xml attributes, and the
# JDK merges the whole of every jndi.properties on the class path into the environment of both
# InitialContext constructors, the per-server one included. So this file is rendered APPEND-ONLY: the
# three non-standard lines are added, and java.naming.factory.initial and java.naming.provider.url keep
# their shipped rmi://127.0.0.1:1099 values, which a broker's factory ignores.
JNDI_PROPERTIES_SOURCE="framework/base/config/jndi.properties"
JNDI_PROPERTIES_OVERRIDE="config/jndi.properties"

# Where the JNDI settings are read from, in the order the JVM reads them. Both lists are used to work
# out which broker a configured transport actually resolves to, so that the startup reachability probe
# contacts the broker OFBiz will contact rather than one this script assumed.
JNDI_PROPERTIES_CANDIDATES=(
  "$JNDI_PROPERTIES_OVERRIDE"
  "$JNDI_PROPERTIES_SOURCE"
)
JNDI_SERVERS_CANDIDATES=(
  "$JNDI_SERVERS_OVERRIDE"
  "$JNDI_SERVERS_SOURCE"
)

# Class path searched when checking that the JMS client the transport names is actually present. It is
# the part of the generated start script's class path that can hold a broker client: lib holds the jars
# dependencies.gradle bundles, and lib-extra is the volume an operator drops their provider's client jar
# into. Java expands the 'dir/*' form itself, so the entries must stay quoted wherever this is used.
TRANSPORT_CLASS_PATH="lib/*:lib-extra/*"

# Marker written into every file this script generates under /ofbiz/config for the transport, and the
# reason it exists. /ofbiz/config is a declared VOLUME, so a render performed by an earlier start
# outlives the container: withdrawing the transport variables has to REMOVE the override, or the
# instance keeps publishing invalidations to a broker nobody configured any more. But an operator is
# equally entitled to author config/serviceengine.xml by hand - that has always been the supported way
# to supply a JMS provider - and such a file must never be deleted. The marker is what distinguishes the
# two: only a file carrying it is ever removed.
RENDERED_TRANSPORT_MARKER='GENERATED BY docker-entrypoint.sh - DO NOT EDIT'

# The jms-service name the entityext cache-clear services are wired to. All five distributed cache
# services in framework/entityext/servicedef/services.xml are declared engine="jms"
# location="serviceMessenger", so the element this script renders MUST carry exactly that name. It is not
# configurable, because changing it would disconnect the transport from the services that use it.
JMS_SERVICE_NAME='serviceMessenger'

# The jndi-server the rendered jms-service names, and the shipped name it deliberately is NOT.
#
# The commented example in the distribution names "default", and that is precisely what must not be
# reused here: <jndi-server name="default"/> carries no parameters, so JNDIContextFactory falls back to
# the bare InitialContext constructor and the broker's settings would have to go into jndi.properties,
# where they would become the JVM-wide default for CatalinaContainer's global naming context, for
# RmiServiceContainer's rebind and lookup, and for the "default" server that entityengine.xml's
# user-transaction-jndi and transaction-manager-jndi both name. A dedicated server carrying its own
# context-provider-url takes the per-server new InitialContext(h) branch instead and reaches none of
# them. The shipped name is still needed, as the fallback for an operator-authored server element that
# omits the attribute, because that is what OFBiz would understand such an element to mean.
JNDI_SERVER_NAME='ofbizCacheTransport'
JNDI_SERVER_DEFAULT_NAME='default'

# The JNDI lookup names and the physical topic the rendered transport uses, and the defaults that match
# the shipped commented example so an operator who supplies only a factory class and a provider URL gets
# a working topic. The lookup names are what OFBiz asks JNDI for; the physical name is what the broker
# calls the destination, published to JNDI as 'topic.<lookup name>=<physical name>' - the mapping
# convention ActiveMQ Classic, ActiveMQ Artemis and Qpid JMS all implement.
JMS_CONNECTION_FACTORY_JNDI_DEFAULT='jms/TopicConnectionFactory'
JMS_TOPIC_JNDI_DEFAULT='jms/OFBTopic'
JMS_TOPIC_PHYSICAL_DEFAULT='OFBTopic'

# Length bounds for the transport values. None of them is a security boundary - the grammar checks are -
# but every one of these values is written into a configuration file and quoted back in log lines, so a
# runaway value is refused with the variable named rather than producing an unreadable diagnostic. The
# class-name bound is generous enough for any real package depth; the URL bound accommodates a failover
# list while still refusing a whole broker inventory pasted into one variable.
JMS_JNDI_NAME_MAX_LENGTH=200
JMS_CLASS_NAME_MAX_LENGTH=512
JMS_PROVIDER_URL_MAX_LENGTH=2000

# Deadline for the startup broker reachability probe, in seconds, and the range accepted. The probe is a
# TCP connect and nothing more, so a healthy broker answers in milliseconds; the default is short enough
# that an unreachable one is reported promptly and long enough to cross a loaded network. The upper bound
# is a sanity limit: a value beyond it would make a container that cannot reach its broker look like a
# container that is merely slow to start, which is the confusion this probe exists to remove.
JMS_CONNECT_TIMEOUT_DEFAULT=5
JMS_CONNECT_TIMEOUT_MIN=1
JMS_CONNECT_TIMEOUT_MAX=120

# Largest number of distinct broker endpoints the reachability probe will contact. A provider URL may
# name several - 'failover:(tcp://a:61616,tcp://b:61616)' - and each costs at most the deadline above, so
# the total is bounded by construction. A URL naming more than this is a configuration error rather than
# a topology.
JMS_ENDPOINT_PROBE_LIMIT=8

# Bounds of the managed DBCP connection pools, and the defaults the committed datasource definitions
# carry. The upper limit is a sanity bound rather than a database limit: pool-maxsize is a per-instance
# figure, so behind a load balancer the fleet can open instances * pool-maxsize connections, and a value
# in the tens of thousands is always a mistake rather than a deployment decision.
DB_POOL_MIN_DEFAULT=2
DB_POOL_MAX_DEFAULT=250
DB_POOL_SIZE_LIMIT=10000

# How long a thread waits for a connection when the managed pool is exhausted, and the range accepted.
# The attribute this renders, pool-sleeptime, becomes GenericObjectPoolConfig.setMaxWaitMillis, and
# InlineJdbc defaults it to 300000 when the attribute is absent - five minutes, longer than any
# load-balancer health-check timeout, so an exhausted pool looked exactly like a hung instance. The
# default below is short enough that the failure is reported rather than waited out and long enough to
# absorb an ordinary burst; the lower bound keeps a value so short that a healthy burst fails out of
# reach, and the upper bound is the engine's own default, which is the longest wait that was ever in
# force here.
DB_POOL_WAIT_DEFAULT=20000
DB_POOL_WAIT_MIN=1000
DB_POOL_WAIT_MAX=300000

# Fleet sizing inputs. Neither reaches the rendered configuration: they exist so that the PER-INSTANCE
# pool maximum can be checked against the capacity the whole fleet competes for. The reserve is
# subtracted from the stated max_connections before the comparison, for the slots PostgreSQL keeps for
# superusers, for the one-shot schema-init execution and for operational tooling - a fleet that fills
# the server to exactly max_connections leaves an operator no way in to diagnose it.
DB_FLEET_SIZE_DEFAULT=1
DB_FLEET_SIZE_LIMIT=1000
DB_MAX_CONNECTIONS_LIMIT=100000
DB_CONNECTION_RESERVE=10

# The pgJDBC network deadlines, their defaults and the ranges accepted. Every one of these is in
# SECONDS, which is the unit pgJDBC uses for all of them.
#
# The two that matter most are the two the driver leaves at 0, meaning NO LIMIT: socketTimeout and
# loginTimeout. Without them a thread that reaches a database which has stopped answering blocks on the
# socket for as long as the kernel keeps the connection open. The socket default is deliberately
# generous - it bounds a single socket read, not a query, so it must not be tight enough to abort a
# statement that is simply slow - while still being finite, which is what lets a readiness probe's count
# eventually return and a pooled connection to a failed-over server be discovered.
#
# The query timeout is off by default and is rendered only when it is set: it applies to EVERY statement,
# so a value here aborts legitimately long work such as a demo data load or a large report, and imposing
# that on an unchanged application is a deployment decision rather than this image's to make.
POSTGRES_CONNECT_TIMEOUT_DEFAULT=10
POSTGRES_CONNECT_TIMEOUT_MIN=1
POSTGRES_CONNECT_TIMEOUT_MAX=300
POSTGRES_SOCKET_TIMEOUT_DEFAULT=60
POSTGRES_SOCKET_TIMEOUT_MIN=5
POSTGRES_SOCKET_TIMEOUT_MAX=86400
POSTGRES_LOGIN_TIMEOUT_DEFAULT=30
POSTGRES_LOGIN_TIMEOUT_MIN=1
POSTGRES_LOGIN_TIMEOUT_MAX=600
POSTGRES_CANCEL_TIMEOUT_DEFAULT=10
POSTGRES_CANCEL_TIMEOUT_MIN=1
POSTGRES_CANCEL_TIMEOUT_MAX=300
POSTGRES_QUERY_TIMEOUT_DEFAULT=0
POSTGRES_QUERY_TIMEOUT_MIN=0
POSTGRES_QUERY_TIMEOUT_MAX=86400

# TCP keep-alive is enabled by default, against the driver's own default of false. A pooled connection
# that idles across a load balancer, NAT gateway or firewall which silently drops its state is otherwise
# only discovered when a thread borrows it and blocks - and behind a proxy that reaps idle flows every
# few minutes that is the ordinary case, not an unusual one.
POSTGRES_TCP_KEEPALIVE_DEFAULT=true

# Connections are validated while they sit idle in the pool rather than as they are handed out. Both
# settings only have any effect at all when a validation query is configured, which the template does
# unconditionally; without one DBCP performs no validation whichever of these booleans is set.
#
# Validating during the pool's own eviction sweep costs a request nothing, and it is what turns a
# connection killed by a failover or an idle-flow reaper into a discarded pool entry instead of an error
# handed to a caller. Validating on every borrow adds a round trip to every single database access, so
# it stays opt-in for deployments whose network drops connections faster than the eviction interval.
DB_POOL_TEST_ON_BORROW_DEFAULT=false

# The content component's configuration: the pristine committed copy and the generated override.
#
# content.properties is read as a FLAT class path resource - UtilProperties resolves the "content"
# resource through the context class loader, whose parent is the system loader - and the generated start
# script puts /ofbiz/config FIRST on the class path, ahead of the component's own
# <classpath type="dir" location="config"/> entry. So a copy written to the override path below shadows
# the committed one, exactly as config/security.properties shadows the security component's copy.
#
# /ofbiz/config is a declared VOLUME, so a render performed by an earlier start of this container
# survives a restart. That is what makes the override removal in render_content_store_configuration
# necessary rather than merely tidy: without it, withdrawing OFBIZ_CONTENT_STORE_PROVIDER would leave
# the previous start's object-store configuration silently in force.
# The committed source already declares every content.store.* property with the backward-compatible
# database/blank/false values, so it is a complete render source: no property ever has to be appended,
# only substituted.
CONTENT_PROPERTIES_SOURCE="applications/content/config/content.properties"
CONTENT_PROPERTIES_OVERRIDE="config/content.properties"

# The storage backends ContentStoreFactory recognises, and the one an unconfigured deployment gets.
# ContentStoreFactory compares the configured value case-insensitively after trimming it, and an
# unrecognised value is logged as a warning and then treated as the database default rather than
# refused - a mis-spelled provider must never stop a fleet member from starting. This script mirrors
# that contract exactly: a value outside this set is reported here as a warning and rendered as
# 'database', so the entry point and the factory reach the same decision from the same input.
# The set therefore serves as the vocabulary the warning quotes, not as an admission gate.
CONTENT_STORE_PROVIDERS=(database filesystem s3)
CONTENT_STORE_DEFAULT_PROVIDER='database'

# Bounds of the object-store identifiers, taken from the stores' own naming rules rather than invented
# here. A bucket name must be 3 to 63 characters of lower case letters, digits, '.' and '-', beginning
# and ending with a letter or digit; a region identifier is lower case letters, digits and '-'. Both are
# checked so that a typo is reported at start up instead of surfacing later as a failed content read.
# The bucket bounds are the stores' own. The region bounds are this script's: S3ContentStore requires
# only that a region be non-blank and no store publishes a maximum, so the bound here is a shape check
# that catches an empty or obviously wrong value - an endpoint or a whole URL pasted into the region
# variable - while leaving room for an identifier a private store may legitimately use. 32 characters is
# that room with a wide margin: the longest identifier any store publishes is well under 20.
#
# 32 is also the bound this image has always enforced. render_content_store_configuration used to apply
# its own inline expression, '^[a-z0-9][a-z0-9-]{0,30}[a-z0-9]$', which is exactly 2 to 32 characters,
# and it runs on every start that selects the object store - so 32 was the effective maximum even while
# this constant said 64. The two rules are now one, expressed here, with the accepted set unchanged and
# the diagnostic corrected: a value that is too long is reported as too long instead of as containing a
# character that is not allowed.
#
# These four constants are the single source of both rules. require_object_store_bucket and
# require_object_store_region are the only validators that apply them, and both the early resolver and
# the render delegate to those rather than restating the bounds.
S3_BUCKET_MIN_LENGTH=3
S3_BUCKET_MAX_LENGTH=63
S3_REGION_MIN_LENGTH=2
S3_REGION_MAX_LENGTH=32

# Pristine start.properties shipped with the distribution, and the package qualified override that
# Config.java resolves ahead of it through the class loader. The override MUST keep the
# org/apache/ofbiz/base/start/ path: a flat config/start.properties does not shadow the shipped file.
START_PROPERTIES_SOURCE="framework/start/src/main/resources/org/apache/ofbiz/base/start/start.properties"
ADMIN_KEY_OVERRIDE="config/org/apache/ofbiz/base/start/start.properties"

# The security configuration: the copy shipped in the source tree and the /ofbiz/config override
# rendered from it. Unlike start.properties this one is a FLAT class path resource, so the override
# needs no package directory. Named here because two places need the pair - the renderer that writes
# the override, and the OFBIZ_SKIP_INIT pre-flight that has to establish whether an override with
# usable keys already exists when this run is not going to write one.
SECURITY_PROPERTIES_SOURCE="framework/security/config/security.properties"
SECURITY_PROPERTIES_OVERRIDE="config/security.properties"

# The webapp url configuration carrying the content url prefix. Flat on the class path for the same
# reason, and rendered on every start so that withdrawing OFBIZ_CONTENT_URL_PREFIX restores the committed
# blank value instead of leaving the previous prefix in an override that outlives the container.
URL_PROPERTIES_SOURCE="framework/webapp/config/url.properties"
URL_PROPERTIES_OVERRIDE="config/url.properties"

# Minimum length of the login and JWT signing keys. JWTManager.getJWTKey rejects anything shorter
# than 64 characters, because OFBiz signs with HMAC512 and that needs a 512 bit key.
SIGNING_KEY_MIN_LENGTH=64

# Minimum length of the admin shared secret, and the characters it must not contain.
# AdminServerContainer reads the shutdown request as 'key:command' and compares everything before the
# FIRST ':' with the configured key, so a key containing ':' can never match and would make a clean
# shutdown impossible.
ADMIN_KEY_MIN_LENGTH=16
ADMIN_KEY_FORBIDDEN=':'

# Distinct characters a secret must contain. The shortest value accepted anywhere here is 16
# characters, and a value drawn at random from any alphabet of 16 or more symbols contains at least
# this many distinct characters with overwhelming probability, so a value that does not is a pattern
# rather than a secret: 'aaaaaaaaaaaaaaaa' and 'abababababababab' are as guessable as the 'NA' code
# default. Counting distinct characters is a deliberately coarse entropy proxy - it is cheap, it
# never rejects a randomly generated value, and it catches the hand typed placeholders that in
# practice reach production.
MIN_DISTINCT_CHARACTERS=8

# Minimum length of a managed database password in the prod profile, and the passwords that are
# refused in EVERY profile.
#
# The array below is the authoritative list: the per-group values this repository publishes - in this
# script's own former defaults and in the committed localpostgres* datasource definitions - together
# with the two passwords a PostgreSQL installation is most often left with. None of them may
# authenticate anything again, so a database still provisioned with one is discovered at start up
# instead of being reachable by anyone who has read the source tree. Rotate any database that used
# them.
# The length and entropy floor is only applied in the prod profile: a local development database with
# a short password is not a production risk, and refusing it would push developers towards disabling
# the check altogether.
#
# Only the MANAGED database passwords are governed here. framework/base/config/passwords.properties
# still ships the embedded H2 development passwords in the source tree; that is RECORDED rather than
# repaired, because those values only ever unlock a container-local H2 file that is not part of a
# load-balanced fleet, and that file is not one this change is permitted to modify.
DATABASE_PASSWORD_MIN_LENGTH=16
RETIRED_DATABASE_PASSWORDS=(ofbiz ofbizolap ofbiztenant postgres password)

# The complete TLS mode vocabulary of the PostgreSQL JDBC driver. Every one of these remains available
# in the dev profile, so a developer can still point the container at a database with no TLS at all.
POSTGRES_SSL_MODES=(disable allow prefer require verify-ca verify-full)

# The mode the prod profile requires - the ONE mode that authenticates the server it is actually
# talking to. The weaker four are unusable for a managed database reached over a network: 'disable' and
# 'allow' can transmit the password and every entity row in clear text, 'prefer' - the driver's own
# default - silently falls back to an unencrypted connection when the server declines TLS, and 'require'
# encrypts without checking who is on the other end of the connection.
# 'verify-ca' is refused in prod as well, which is stricter than it first appears to need to be: it
# checks only that the certificate chains to a trusted root and does NOT check that the certificate
# belongs to the host that was asked for. A managed cloud database service issues certificates from a
# shared CA to every tenant, so under verify-ca ANY other tenant's endpoint satisfies the check and can
# impersonate the database - precisely the attack a verified connection is supposed to prevent. It stays
# available in dev, where the full vocabulary above applies.
POSTGRES_SSL_PROD_MODES=(verify-full)
POSTGRES_SSL_DEFAULT_MODE='verify-full'

OFBIZ_PROFILES=(dev prod)

# Admin password rules for the prod profile. The admin user holds every OFBiz permission, so the
# floors match those applied to a database password, and the values published in this repository - in
# DOCKER.adoc, in the demo data and in this script's own former default - are refused outright.
ADMIN_PASSWORD_MIN_LENGTH=16
ADMIN_PASSWORD_DEV_DEFAULT='ofbiz'
RETIRED_ADMIN_PASSWORDS=(ofbiz admin password ofbizdemo)

# Every environment variable that carries secret material. Used in two places: to remove the values
# from the environment of an initialisation hook (a hook may enable shell tracing, as the shipped
# example does, and a traced command line would publish them to the container log), and as the
# authoritative list of what an operator may re-admit through OFBIZ_HOOK_SECRET_ALLOWLIST.
SECRET_ENVIRONMENT_VARIABLES=(
  OFBIZ_ADMIN_PASSWORD
  OFBIZ_ADMIN_KEY
  OFBIZ_LOGIN_SECRET_KEY
  OFBIZ_JWT_TOKEN_KEY
  OFBIZ_POSTGRES_OFBIZ_PASSWORD
  OFBIZ_POSTGRES_OLAP_PASSWORD
  OFBIZ_POSTGRES_TENANT_PASSWORD
  OFBIZ_POSTGRES_OFBIZ_INIT_PASSWORD
  OFBIZ_POSTGRES_OLAP_INIT_PASSWORD
  OFBIZ_POSTGRES_TENANT_INIT_PASSWORD
  OFBIZ_S3_ACCESS_KEY_ID
  OFBIZ_S3_SECRET_ACCESS_KEY
  OFBIZ_JMS_PASSWORD
)

# Variables removed from the environment of every initialisation child this script starts.
#
# The secrets and database passwords are injected into the rendered configuration, which is where the
# JVM reads them from; no child process needs them in its environment. Leaving them there would
# publish each one through /proc/<child pid>/environ for the whole life of a data-load JVM - minutes,
# on a demo load - to anything able to read it, and would hand a copy to every process that JVM in
# turn starts and to any crash handler or diagnostic that dumps the environment. The unset block in
# _main removes them before the serving process is exec'd, but that happens AFTER the initialisation
# children have already run, so the children are given a sanitised environment of their own instead.
#
# Nothing here is consulted by bin/ofbiz: the database credentials reach it through
# config/entityengine.xml, the signing keys through config/security.properties, and the admin shared
# secret through the package qualified start.properties override, the object-store credentials through
# config/content.properties and the broker password through config/serviceengine.xml. The admin
# password is only ever used by this shell, which hashes it and passes the hash in a mode 0600 file.
CHILD_SANITISED_VARIABLES=(
  OFBIZ_ADMIN_KEY
  OFBIZ_ADMIN_PASSWORD
  OFBIZ_LOGIN_SECRET_KEY
  OFBIZ_JWT_TOKEN_KEY
  OFBIZ_POSTGRES_OFBIZ_PASSWORD
  OFBIZ_POSTGRES_OLAP_PASSWORD
  OFBIZ_POSTGRES_TENANT_PASSWORD
  OFBIZ_POSTGRES_OFBIZ_INIT_PASSWORD
  OFBIZ_POSTGRES_OLAP_INIT_PASSWORD
  OFBIZ_POSTGRES_TENANT_INIT_PASSWORD
  OFBIZ_S3_ACCESS_KEY_ID
  OFBIZ_S3_SECRET_ACCESS_KEY
  OFBIZ_JMS_PASSWORD
)

# The three entity groups that have a managed datasource, as the infix of their environment variable
# names, and the datasource each one configures. The two arrays are parallel and index together;
# iterating over them keeps the serving identity and the schema-init identity in step, so a group added
# here gains both without either being forgotten.
POSTGRES_GROUPS=(OFBIZ OLAP TENANT)
MANAGED_DATASOURCE_NAMES=(localpostgres localpostgresolap localpostgrestenant)

# The six variables that carry the schema-initialisation database identity.
#
# Turning off the startup DDL flags stops a serving instance from ISSUING DDL, but it does not remove
# the ability: if the fleet authenticates as a role that owns the schema, a single SQL-injection or
# credential leak still buys DROP TABLE. The privilege has to be somewhere, so it is put where the
# process that needs it is short-lived, unreachable and runs once - the OFBIZ_SCHEMA_INIT=true job -
# and taken away from the long-lived processes that face the network.
#
# All six are supplied together or not at all. In init mode every managed datasource carries
# check-on-start="true", so all three groups issue DDL and all three need the privileged role; a partial
# set would silently leave one group's schema owned by its serving role. See DOCKER.adoc for the exact
# grants each of the two roles needs.
POSTGRES_INIT_IDENTITY_VARIABLES=(
  OFBIZ_POSTGRES_OFBIZ_INIT_USER
  OFBIZ_POSTGRES_OFBIZ_INIT_PASSWORD
  OFBIZ_POSTGRES_OLAP_INIT_USER
  OFBIZ_POSTGRES_OLAP_INIT_PASSWORD
  OFBIZ_POSTGRES_TENANT_INIT_USER
  OFBIZ_POSTGRES_TENANT_INIT_PASSWORD
)

# The seven variables that carry the object-store configuration, and the property each one is rendered
# into. Parallel arrays, indexed together, so a variable cannot be added to one without the other.
#
# These are exactly the seven variables Objective 3 advertises, and advertising one without reading it
# is the failure this pairing exists to prevent: a deployment that supplied a bucket and a secret access
# key would get database storage anyway, while both values sat in the container's environment - readable
# through /proc by anything sharing the PID namespace, and inherited by every hook and child process -
# for the life of the instance.
#
# The set is closed at seven because these are the seven settings a deployment can only supply from
# outside: which backend to use, where the store is and what to authenticate to it as. A variable
# rendered into a property no provider reads would be configuration this image promises to honour and
# then silently ignores, so nothing is rendered that the Java side does not consume.
#
# The Java side reads more content.store.* properties than these seven - the read bound, the local
# fallback, the object-key prefix and the s3 deadlines and retry cap - and deliberately no variable
# carries them. Each has a committed default every deployment can run on, none is a secret, and each
# is overridable per instance through a SystemProperty row without a restart, so adding a variable
# for it would only add a way for the two layers to disagree. They are documented in
# applications/content/config/content.properties beside the values themselves.
CONTENT_STORE_VARIABLES=(
  OFBIZ_CONTENT_STORE_PROVIDER
  OFBIZ_S3_BUCKET
  OFBIZ_S3_REGION
  OFBIZ_S3_ENDPOINT
  OFBIZ_S3_ACCESS_KEY_ID
  OFBIZ_S3_SECRET_ACCESS_KEY
  OFBIZ_S3_PATH_STYLE
)
CONTENT_STORE_PROPERTIES=(
  content.store.provider
  content.store.s3.bucket
  content.store.s3.region
  content.store.s3.endpoint
  content.store.s3.access.key.id
  content.store.s3.secret.access.key
  content.store.s3.path.style
)

# Link-local addresses that answer with cloud instance credentials: the EC2/GCE/Azure instance metadata
# service, its IPv6 form, and the ECS task metadata endpoint. An object-store endpoint pointing at one
# of these is refused in every profile, with no override.
#
# The reason it is unconditional is the direction of the attack. The endpoint is where the provider
# sends requests that carry the deployment's own credentials, and an operator has no legitimate reason
# to send them to the metadata service; but an attacker who can influence one environment variable has
# every reason to, because the response is a set of role credentials for the whole instance. There is no
# development scenario that needs this, so the refusal is absolute and no variable can relax it.
#
# S3ContentStore carries the same list and applies the same refusal, so the endpoint is rejected whether
# it arrives through this entry point or is written straight into content.properties.
INSTANCE_METADATA_HOSTS=(
  169.254.169.254
  '[fd00:ec2::254]'
  fd00:ec2::254
  169.254.170.2
  metadata.google.internal
)

# Every variable whose ONLY effect is the configuration rendering and the initialisation this script
# performs. None of them is read by the OFBiz JVM: each one reaches the application by being written
# into a file here, or not at all. OFBIZ_SKIP_INIT suppresses that work, so a value supplied alongside
# it has no path to the running instance, and the list is what lets the pre-flight below say so by
# name.
#
# It is also the list withdraw_container_configuration withdraws, which is why membership is decided by
# one question and not by convenience: does this variable reach the application by being rendered into a
# file? The variables that do not - the ones that steer this script itself, or that it only validates
# against - are named in CONTAINER_CONTROL_VARIABLES instead, and are withdrawn just the same. Between
# the two arrays every OFBIZ_ name this script consumes is accounted for exactly once.
RUNTIME_APPLIED_VARIABLES=(
  OFBIZ_ADMIN_KEY
  OFBIZ_LOGIN_SECRET_KEY
  OFBIZ_JWT_TOKEN_KEY
  OFBIZ_ADMIN_USER
  OFBIZ_ADMIN_PASSWORD
  OFBIZ_DATA_LOAD
  OFBIZ_HOST
  OFBIZ_CONTENT_URL_PREFIX
  OFBIZ_ENABLE_AJP_PORT
  OFBIZ_DISABLE_COMPONENTS
  OFBIZ_JVM_ROUTE
  OFBIZ_SSL_ACCELERATOR_PORT
  OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS
  OFBIZ_POSTGRES_HOST
  OFBIZ_POSTGRES_PORT
  OFBIZ_POSTGRES_OFBIZ_DB
  OFBIZ_POSTGRES_OFBIZ_USER
  OFBIZ_POSTGRES_OFBIZ_PASSWORD
  OFBIZ_POSTGRES_OLAP_DB
  OFBIZ_POSTGRES_OLAP_USER
  OFBIZ_POSTGRES_OLAP_PASSWORD
  OFBIZ_POSTGRES_TENANT_DB
  OFBIZ_POSTGRES_TENANT_USER
  OFBIZ_POSTGRES_TENANT_PASSWORD
  OFBIZ_POSTGRES_SSLMODE
  OFBIZ_POSTGRES_SSLROOTCERT
  OFBIZ_POSTGRES_CONNECT_TIMEOUT
  OFBIZ_POSTGRES_SOCKET_TIMEOUT
  OFBIZ_POSTGRES_LOGIN_TIMEOUT
  OFBIZ_POSTGRES_CANCEL_TIMEOUT
  OFBIZ_POSTGRES_QUERY_TIMEOUT
  OFBIZ_POSTGRES_TCP_KEEPALIVE
  OFBIZ_DB_POOL_MIN
  OFBIZ_DB_POOL_MAX
  OFBIZ_DB_POOL_WAIT
  OFBIZ_DB_POOL_TEST_ON_BORROW
  OFBIZ_DISTRIBUTED_CACHE_CLEAR
  OFBIZ_JMS_INITIAL_CONTEXT_FACTORY
  OFBIZ_JMS_PROVIDER_URL
  OFBIZ_JMS_CONNECTION_FACTORY_JNDI_NAME
  OFBIZ_JMS_TOPIC_JNDI_NAME
  OFBIZ_JMS_TOPIC_PHYSICAL_NAME
  OFBIZ_JMS_USERNAME
  OFBIZ_JMS_PASSWORD
  OFBIZ_JMS_CONNECT_TIMEOUT
  OFBIZ_CONTENT_STORE_PROVIDER
  OFBIZ_S3_BUCKET
  OFBIZ_S3_REGION
  OFBIZ_S3_ENDPOINT
  OFBIZ_S3_ACCESS_KEY_ID
  OFBIZ_S3_SECRET_ACCESS_KEY
  OFBIZ_S3_PATH_STYLE
  OFBIZ_POSTGRES_OFBIZ_INIT_USER
  OFBIZ_POSTGRES_OFBIZ_INIT_PASSWORD
  OFBIZ_POSTGRES_OLAP_INIT_USER
  OFBIZ_POSTGRES_OLAP_INIT_PASSWORD
  OFBIZ_POSTGRES_TENANT_INIT_USER
  OFBIZ_POSTGRES_TENANT_INIT_PASSWORD
)

# The variables this script consumes WITHOUT rendering them into anything.
#
# Three kinds, and none of them reaches the application through a file:
#  - OFBIZ_TRACE, OFBIZ_SKIP_INIT, OFBIZ_SCHEMA_INIT and OFBIZ_PROFILE steer this script: what it
#    traces, whether it initialises, whether it applies DDL, and how strictly it validates.
#  - OFBIZ_DB_FLEET_SIZE and OFBIZ_DB_MAX_CONNECTIONS are capacity assertions it CHECKS the rendered
#    pool sizes against. Neither is substituted into any file, which is why they belong here rather than
#    in the inventory above.
#  - OFBIZ_HOOK_SECRET_ALLOWLIST names which secrets an initialisation hook may see, and
#    OFBIZ_SKIP_DB_DRIVER_DOWNLOAD is an accepted no-op kept so an older deployment's environment does
#    not become an error.
#
# They are withdrawn from the served JVM's environment for the same reason as the rendered ones: after
# this script has consumed them, a value still visible in /proc/<pid>/environ describes a decision that
# has already been taken and can only mislead whoever reads it next.
CONTAINER_CONTROL_VARIABLES=(
  OFBIZ_TRACE
  OFBIZ_PROFILE
  OFBIZ_SKIP_INIT
  OFBIZ_SCHEMA_INIT
  OFBIZ_HOOK_SECRET_ALLOWLIST
  OFBIZ_DB_FLEET_SIZE
  OFBIZ_DB_MAX_CONNECTIONS
  OFBIZ_SKIP_DB_DRIVER_DOWNLOAD
)

# The names the operator actually supplied, recorded before any default is applied. ofbiz_setup_env
# gives several of the variables above a value, so testing them after it has run cannot distinguish an
# operator's setting from this script's own default; the snapshot is taken first so the pre-flight
# reports only what was really passed in.
SUPPLIED_VARIABLES=()

# Whether that snapshot has been taken. An empty SUPPLIED_VARIABLES is ambiguous on its own - it is both
# "the operator supplied nothing" and "nothing has looked yet" - and the two must not be confused,
# because one means render the committed defaults and the other means the question cannot be answered
# from the array at all.
SUPPLIED_VARIABLES_RECORDED="false"

# The names the operator supplied as an EMPTY value, recorded in the same snapshot and kept apart from
# the array above.
#
# "Set to nothing" and "not set at all" are different instructions and the difference is not recoverable
# later: ofbiz_setup_env resolves both with the '${VAR:-default}' form, so the moment it has run a blank
# OFBIZ_POSTGRES_OFBIZ_DB is indistinguishable from an absent one - both read as the literal default
# 'ofbiz'. An orchestrator that renders a variable from a secret or a config map that turned out to be
# empty therefore produced a container that silently connected somewhere else, and the only symptom was
# the driver's own 'database "ofbiz" does not exist' once the entity engine started. A value that was
# supplied and is empty is a templating accident, never a request for this script's default, so it is
# recorded here and reported by name.
SUPPLIED_BLANK_VARIABLES=()

# The variables OFBIZ_SKIP_INIT genuinely makes ineffective. They configure the work it skips - the
# administrative user and the data load - and nothing else does. Every other runtime-applied variable
# is still rendered on that path, so reporting the whole supplied set as ignored would be false.
SKIP_INIT_INEFFECTIVE_VARIABLES=(OFBIZ_ADMIN_USER OFBIZ_ADMIN_PASSWORD OFBIZ_DATA_LOAD)

# Nesting depth of the secret-handling regions currently open. Secret-handling functions call one
# another - a renderer validates a secret, which in turn validates its shape - so the pair below is
# reference counted. Without the counter the inner function's restore_trace would switch tracing back
# on while its caller was still holding a secret, which is exactly the leak this is meant to prevent.
SECRET_REGION_DEPTH=0

###############################################################################
# Report whether the OPERATOR supplied a variable, as opposed to this script defaulting it.
#
# ofbiz_setup_env gives several runtime-applied variables a value, so an emptiness test after it has run
# cannot tell an operator's setting from this script's own default. That distinction decides real
# behaviour: OFBIZ_CONTENT_STORE_PROVIDER is defaulted to 'database' on every start, so a test of the
# environment reports every container as having configured object storage, including one that configured
# none.
#
# When the snapshot has not been taken, the environment IS the operator's input, because no default has
# been applied yet - that is how the renderers are driven one at a time. The question is the same either
# way, "did this value come from outside this script?", and only the evidence available to answer it
# differs.
# $1 - variable name
variable_was_supplied() {
  local variableName="$1"
  if [ "$SUPPLIED_VARIABLES_RECORDED" = "true" ]; then
    # Names only. Nothing is expanded, so this comparison is safe to make with tracing on even when the
    # name is that of a credential.
    printf '%s\n' "${SUPPLIED_VARIABLES[@]}" | grep --quiet --line-regexp --fixed-strings "$variableName"
  else
    [ -n "${!variableName:-}" ]
  fi
}

###############################################################################
# Report whether the OPERATOR supplied a variable AS AN EMPTY VALUE.
#
# The counterpart of variable_was_supplied, and the question ofbiz_setup_env destroys: '${VAR:-default}'
# treats a blank value and an absent one identically, so after it has run nothing can tell them apart.
# The snapshot separates them, which is what lets a blank value be rejected by name instead of quietly
# becoming this script's default.
#
# When the snapshot has not been taken the environment is still the operator's input, so the question is
# answered directly with the '+' form, which distinguishes "declared" from "non-empty". Under 'set -x'
# neither branch expands a value, so both remain safe for a credential's name.
# $1 - variable name
variable_was_supplied_blank() {
  local variableName="$1"
  if [ "$SUPPLIED_VARIABLES_RECORDED" = "true" ]; then
    if [ "${#SUPPLIED_BLANK_VARIABLES[@]}" -eq 0 ]; then
      return 1
    fi
    printf '%s\n' "${SUPPLIED_BLANK_VARIABLES[@]}" \
      | grep --quiet --line-regexp --fixed-strings "$variableName"
  else
    [ -n "${!variableName+set}" ] && [ -z "${!variableName}" ]
  fi
}

###############################################################################
# Suspend shell tracing for the duration of a secret-handling region.
# Always pair with restore_trace. Safe to call when tracing is already disabled
# and safe to nest.
hide_secrets() {
  set +x
  SECRET_REGION_DEPTH=$((SECRET_REGION_DEPTH + 1))
}

###############################################################################
# Leave a secret-handling region, resuming shell tracing only once the outermost
# region has closed and only if the shell was already tracing when this script
# started.
restore_trace() {
  if [ "$SECRET_REGION_DEPTH" -gt 0 ]; then
    SECRET_REGION_DEPTH=$((SECRET_REGION_DEPTH - 1))
  fi
  if [ "$SECRET_REGION_DEPTH" -eq 0 ] && [ "$TRACE_ENABLED" = "true" ]; then
    set -x
  fi
}

###############################################################################
# Move every supplied secret out of the EXPORTED environment, keeping its value readable by this shell.
#
# Called as the very first statement of _main, before anything forks. Everything that follows creates
# child processes - the renderers run sed, grep, awk and xsltproc, the initialisation runs a JVM for
# minutes at a time, and a hook is operator code that is executed or sourced - and every one of them
# inherits the exported environment. A secret still exported at that point is published through
# /proc/<child pid>/environ for as long as the child lives, is copied again by anything that child
# starts, and lands in a heap dump, an hs_err file or any diagnostic that prints the environment.
# Removing it first is what makes it unobservable to them, rather than observable and then withdrawn.
#
# 'export -n' removes ONLY the export attribute: the value survives as an ordinary shell variable, so
# every resolver, validator and renderer below still reads exactly what the operator supplied and no
# consumer has to be rewritten to look somewhere else. Plain 'unset' would take the value away from
# this script as well, and copying each value into a second variable would leave the secret in two
# places instead of one.
#
# The list is SECRET_ENVIRONMENT_VARIABLES, which is also what run_hook_scrubbed removes around a hook
# and what OFBIZ_HOOK_SECRET_ALLOWLIST is validated against, so the policy is stated once. The restore
# in run_hook_scrubbed reads each variable's export attribute before it removes it and puts back what
# it found, so a variable demoted here is restored demoted.
#
# TWO residuals this cannot close, both deliberate:
#   * this process's OWN original environment, which the kernel captured before the script ran and
#     which /proc/1/environ keeps until the process is replaced. The exec of the serving command at the
#     end of _main is what finally replaces it, and withdraw_container_configuration, called immediately
#     before that exec, is what keeps the value out of the environment the serving JVM is given;
#   * the rendered configuration files, which have to hold the values to be usable, and which are
#     written atomically with mode 0600 into /ofbiz/config for that reason.
capture_secret_environment() {
  hide_secrets

  local secretName
  for secretName in "${SECRET_ENVIRONMENT_VARIABLES[@]}"; do
    if [ -n "${!secretName+set}" ]; then
      # shellcheck disable=SC2163  # the indirection is the point: the loop demotes the variable NAMED by
      # this variable, one per iteration, rather than a variable called secretName.
      export -n "$secretName"
    fi
  done

  restore_trace
}

###############################################################################
# Temporary files that hold secret material while a configuration file is being
# rendered. They are registered here so that an aborted render cannot leave secret
# material behind on disk; config_fatal removes every registered file before exiting.
SECRET_TEMP_FILES=()

###############################################################################
# Record a temporary file that holds secret material.
# $1 - path
register_secret_temp_file() {
  SECRET_TEMP_FILES+=("$1")
}

###############################################################################
# Forget a temporary file without removing it, for the one case where the file has legitimately stopped
# existing under its temporary name: render_config_from moves its temporary onto the destination, so the
# path is gone and the secret material now lives in the published configuration, which is not ours to
# delete. Leaving the entry registered would be harmless for rm --force, but it would make the registry
# a misleading record of what is actually on disk during cleanup.
# $1 - path
unregister_secret_temp_file() {
  local remaining=()
  local registered
  for registered in "${SECRET_TEMP_FILES[@]+"${SECRET_TEMP_FILES[@]}"}"; do
    if [ "$registered" != "$1" ]; then
      remaining+=("$registered")
    fi
  done
  SECRET_TEMP_FILES=("${remaining[@]+"${remaining[@]}"}")
}

###############################################################################
# Remove every registered temporary file holding secret material and forget them.
discard_secret_temp_files() {
  if [ "${#SECRET_TEMP_FILES[@]}" -gt 0 ]; then
    rm --force "${SECRET_TEMP_FILES[@]}"
    SECRET_TEMP_FILES=()
  fi
}

###############################################################################
# Abort start up with a message on stderr.
# The message must only ever name variables, never quote their values, so that a
# validation failure cannot itself leak the secret it rejected.
# $1 - message
config_fatal() {
  hide_secrets
  discard_secret_temp_files
  printf 'ERROR: %s\n' "$1" >&2
  exit 1
}

###############################################################################
# Value validation and grammar-specific encoding helpers.
#
# Every value injected into a configuration file arrives from the container environment and is
# therefore untrusted input. Substituting it unchecked lets a malformed or hostile value escape the
# value it is meant to occupy and rewrite unrelated configuration - by terminating a sed s-command,
# closing an XML attribute, or exploiting the java.util.Properties line-continuation - or smuggle an
# extra line into the file. Each value is therefore validated for shape first and then encoded for
# the exact grammar of its destination.
###############################################################################

###############################################################################
# Reject a value that cannot be represented safely as a single-line configuration
# value: NUL, carriage return, line feed, tab or any other ASCII control character.
# The newline is the dangerous case - it would append an attacker controlled line
# to a properties file or terminate an XML attribute's line.
# $1 - variable name (named in the error message)
# $2 - value (never printed)
reject_unsafe_value() {
  case "$2" in
  *[[:cntrl:]]*)
    config_fatal "$1 contains a control character (newline, carriage return, tab or NUL). Supply a single-line value."
    ;;
  esac
}

###############################################################################
# Reject a value that java.util.Properties would not read back as it was written.
#
# The shell validates the value it was given; the application reads the value the property FILE
# yields, and those two differ when the value starts with whitespace. Properties.load discards every
# space, tab and form feed between the '=' and the first non-blank character, so '   ' followed by 61
# characters passes a 64 character minimum here and arrives as a 61 character key there - shorter, and
# different, from the value that was checked. For a signing key that means JWTManager rejecting it at
# the first token; for the admin key it means a shared secret nobody can reproduce; for the content URL
# prefix it means every generated content URL pointing at a different origin.
#
# The refused set is exactly the set Properties.load discards, form feed included. Form feed is also a
# control character, so every caller that runs reject_unsafe_value or secret_is_usable first already
# refuses it - but this function is the one that owns the Properties round-trip guarantee, so it does
# not depend on the call order to keep it.
#
# Rejecting the value is preferred over escaping it (Properties accepts '\ ' for a leading space)
# because a secret whose first characters are invisible is a configuration mistake in its own right:
# it cannot be typed back reliably and it is almost always an accident of shell quoting.
# $1 - variable name (named in the error message)
# $2 - value (never printed)
reject_leading_whitespace() {
  case "$2" in
  [[:blank:]]* | $'\f'*)
    config_fatal "$1 must not start with a space, a tab or a form feed. java.util.Properties discards those leading characters, so the value the application would read is not the value that was validated. Remove the leading whitespace - it is almost always a shell quoting accident."
    ;;
  esac
}

###############################################################################
# Reject a value containing a '@TOKEN@' template sentinel.
#
# The template renderers apply their substitutions as a sequence of independent sed s-commands over
# one file, so text a value inserts is still visible to every LATER command. A value that itself
# contains a sentinel therefore gets that sentinel expanded in place: an OFBIZ_POSTGRES_OFBIZ_PASSWORD
# of '@TENANT_PASSWORD@' is written into the ofbiz datasource first and then rewritten by the tenant
# pass, so the tenant password ends up in the ofbiz datasource - one credential spliced into another
# field, in a file the operator believes contains only what they supplied.
#
# The sentinel grammar is deliberately narrow - '@', then one or more upper case letters, digits or
# underscores, then '@' - so an ordinary value containing '@', such as an email address or a base64
# secret, is unaffected.
# $1 - variable name (named in the error message)
# $2 - value (never printed)
reject_template_sentinel() {
  if printf '%s' "$2" | grep --quiet --extended-regexp '@[A-Z0-9_]+@'; then
    config_fatal "$1 contains a '@TOKEN@' configuration template sentinel. A later substitution pass would expand it and splice an unrelated value - possibly another credential - into this field. Remove the '@WORD@' text; a bare '@' is fine."
  fi
}

###############################################################################
# Require a value to be one of an explicit set of allowed tokens.
# $1 - variable name, $2 - value, $3.. - allowed tokens
require_enum() {
  local name="$1"
  local value="$2"
  shift 2
  local allowed
  for allowed; do
    if [ "$value" = "$allowed" ]; then
      return 0
    fi
  done
  config_fatal "$name has an unsupported value. Allowed values: $*"
}

###############################################################################
# Resolve the deployment profile, defaulting to development and reporting that it did.
#
# The profile makes the single most consequential security decision this script takes - whether an
# absent secret aborts the start or is replaced by a generated value, whether a weak or published
# credential is accepted, whether a non-verifying database TLS mode is allowed - so an unset value must
# never be settled SILENTLY. It is settled LOUDLY instead: an unset or empty variable resolves to 'dev',
# which generates per-container key material, accepts the published demo admin password and boots on the
# embedded H2 database, and a NOTICE naming the assumed profile is written to stderr on every such start.
# That keeps the container's zero-configuration contract - 'docker run ofbiz-docker' with no environment
# at all still boots and serves, which is what the local and demo paths rely on - without the silence.
#
# 'prod' is never assumed; it has to be asked for, and every strength, presence and TLS rule keyed on it
# applies only when it is. A value that is present but is not one of the two profiles - including a
# whitespace-only value, which is a templating accident rather than an absent setting - remains fatal.
# Guessing which profile such a value meant is exactly the mistake this function exists to prevent.
#
# Called first from _main, before any other work and on every path including the one that skips data
# initialisation, so no rendering, secret resolution or credential check can ever run against an
# unresolved profile.
require_profile() {
  if [ -z "${OFBIZ_PROFILE:-}" ]; then
    OFBIZ_PROFILE='dev'
    printf '%s\n' "NOTICE: OFBIZ_PROFILE was not set, so the '$OFBIZ_PROFILE' profile is assumed: any secret that is not supplied is generated for this container, the published demo admin password is accepted, and the container boots on the embedded database. That is the local and demo behaviour. Set OFBIZ_PROFILE=prod for any deployment that serves real traffic - it requires every secret to be supplied through the environment and refuses a weak or published value." >&2
  fi
  require_enum OFBIZ_PROFILE "$OFBIZ_PROFILE" "${OFBIZ_PROFILES[@]}"
}

###############################################################################
# Require a plain decimal integer within an inclusive range. Leading zeros are
# rejected because the shell's arithmetic comparison would read them as octal.
# $1 - variable name, $2 - value, $3 - minimum, $4 - maximum
require_integer_range() {
  local name="$1"
  local value="$2"
  local min="$3"
  local max="$4"
  case "$value" in
  '' | *[!0-9]*)
    config_fatal "$name must be a plain decimal integer between $min and $max."
    ;;
  0?*)
    config_fatal "$name must be a plain decimal integer without a leading zero."
    ;;
  esac
  if [ "$value" -lt "$min" ] || [ "$value" -gt "$max" ]; then
    config_fatal "$name must be between $min and $max."
  fi
}

###############################################################################
# Normalise a boolean to the literal 'true' or 'false' on stdout so the value
# written into a configuration file is always one of the two tokens the OFBiz
# parsers recognise. Anything that is not clearly a boolean is fatal.
#
# WHAT IS ACCEPTED, exactly: 'true', 'yes' and '1' for true, 'false', 'no' and '0' for false, in any
# letter case. Anything else - including a value that is nearly a boolean, such as 'y', 'on' or
# 'TRUE!' - is a misconfiguration and is refused rather than resolved by guessing.
#
# WHAT IS EMITTED is narrower than what is accepted, and that is the point: the OFBiz parsers on the
# receiving side recognise nothing but the two literals (Datasource.java compares against
# "false"/"true", DelegatorElement.java uses "true".equalsIgnoreCase,
# UtilProperties.getPropertyAsBoolean accepts only the two words), so every value that reaches a
# rendered file passes through here and leaves as 'true' or 'false'. An accepted synonym therefore
# never becomes a second spelling in a configuration file; it exists only in the environment the
# operator wrote.
#
# The two canonical spellings are what the documentation recommends - the header block above, DOCKER.adoc
# and the descriptors all say 'true|false' - and they are what every caller's refusal message names,
# because an operator correcting a value should be told the spelling to use rather than the full list of
# ones that would have worked. The message below names the synonyms as well, and both messages are
# printed: this function's config_fatal ends only the command substitution it runs in, so the caller's
# own message follows it.
# $1 - variable name, $2 - value
require_boolean() {
  case "$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')" in
  true | yes | 1)
    printf 'true'
    ;;
  false | no | 0)
    printf 'false'
    ;;
  *)
    config_fatal "$1 must be a boolean: true or false ('yes'/'no' and '1'/'0' are accepted as synonyms, in any letter case, and are normalised to true or false before anything is written)."
    ;;
  esac
}

###############################################################################
# Require a value that is safe to embed in the authority component of a JDBC URI.
#
# The rendered value lands directly after 'jdbc:postgresql://', so a value containing '/', '?', '#',
# '@' or whitespace could close the authority early and point the connection at a different server,
# graft on driver connection parameters, or comment out the rest of the URI. This function rejects URI
# delimiters and control characters, requires bracket shape for a colon-containing host, and constrains
# the accepted characters: it is injection and shape validation, not address validation, so a value it
# accepts may still fail to resolve.
#
# A port embedded in the host is refused. The port has its own placeholder and its own range check, so
# a value smuggled in here would be the one component of the URI that reached the connection string
# without being validated - and 'host:5432/other?loggerLevel=OFF' is exactly the kind of value that
# check exists to catch. An IPv6 literal must be bracketed, which is what the URI grammar requires
# anyway to keep its colons distinguishable from a port separator.
#
# The value is matched against TWO MUTUALLY EXCLUSIVE SHAPES rather than against a single character
# allow list, because an allow list that contains '[', ']' and ':' also accepts 'db[primary',
# 'db]primary', '[]' and '[x]y' - all of which reach the URI as written. Which shape applies is decided
# first, by whether the value starts with '[', and the value then has to satisfy that shape completely.
# $1 - variable name, $2 - value
require_jdbc_host() {
  reject_unsafe_value "$1" "$2"

  local name="$1"
  local value="$2"

  if [ -z "$value" ]; then
    config_fatal "$name must not be empty."
  fi

  case "$value" in
  '['*)
    # Bracketed shape: the brackets must enclose the whole value. The inner text must be non-empty and
    # must contain no further '[' or ']', which is what rejects '[x]y' and '[a][b]'.
    local inner
    case "$value" in
    *']') inner=${value#'['}; inner=${inner%']'} ;;
    *) config_fatal "$name starts with '[' so it must be a complete bracketed IPv6 literal, closed with ']'." ;;
    esac
    case "$inner" in
    '') config_fatal "$name must not be an empty pair of brackets. Use a bracketed IPv6 literal such as '[fd00::1]'." ;;
    *'['* | *']'*) config_fatal "$name must contain exactly one pair of brackets, around an IPv6 literal such as '[fd00::1]'." ;;
    esac

    # An optional RFC 4007 zone identifier is split off before the rest is checked, because the two
    # halves take different accepted character sets: a zone is an interface name such as 'eth0' or
    # 'en0', whose letters are not hexadecimal, so one character class for both would either refuse a
    # legitimate zone name or admit a non-hexadecimal character into the address part.
    local zone=''
    case "$inner" in
    *%*)
      zone=${inner#*%}
      inner=${inner%%"%"*}
      case "$zone" in
      '') config_fatal "$name ends with '%' but names no zone identifier after it. Write the interface, as in '[fe80::1%eth0]', or omit the '%'." ;;
      *%*) config_fatal "$name contains more than one '%'. An IPv6 literal carries at most one zone identifier." ;;
      *[!A-Za-z0-9._-]*) config_fatal "$name has a zone identifier containing a character that is not valid in an interface name." ;;
      esac
      ;;
    esac

    # At least one ':' inside the brackets is what distinguishes the IPv6 literal shape from a name
    # someone bracketed by accident.
    case "$inner" in
    '') config_fatal "$name names a zone identifier but no address before the '%'. Write the full literal, as in '[fe80::1%eth0]'." ;;
    *:*) ;;
    *) config_fatal "$name is bracketed but contains no ':', so it is not an IPv6 literal. Use an unbracketed host name or IPv4 address instead." ;;
    esac

    # The characters accepted inside the brackets: hexadecimal digits, ':' group separators and '.' for
    # an IPv4-mapped suffix. Nothing else - not '-', not '_', not a second '%'. This constrains the
    # alphabet rather than validating the address.
    case "$inner" in
    *[!0-9A-Fa-f:.]*)
      config_fatal "$name contains a character that is not valid inside a bracketed IPv6 literal."
      ;;
    esac
    ;;
  *)
    # Unbracketed shape: a DNS host name or an IPv4 literal. The accepted characters are letters,
    # digits, '.', '_' and '-', so a stray '[' or ']' is refused here rather than tolerated as "a
    # permitted character", and a ':' cannot appear at all, which is what refuses an embedded port. The
    # shape rules are modelled on DNS - no leading or trailing '-' or '.', and no empty label - and
    # like the bracketed branch they constrain the alphabet and the shape rather than validating the
    # address.
    #
    # '_' is accepted although the DNS host name rules do not allow it, because a container name is a
    # host name here: Docker Compose accepts a service name containing '_' and its embedded resolver
    # answers for it, so 'ofbiz_db' is a working value that this check must not take away.
    case "$value" in
    *[!A-Za-z0-9._-]*)
      case "$value" in
      *:*)
        config_fatal "$name must not contain a port. Set the port in OFBIZ_POSTGRES_PORT, and bracket an IPv6 literal as '[fd00::1]'."
        ;;
      *']'*)
        config_fatal "$name contains ']' but does not start with '['. A bracketed IPv6 literal must be fully bracketed, as '[fd00::1]'."
        ;;
      *)
        config_fatal "$name must be a host name, an IPv4 address, or a bracketed IPv6 literal. It contains a character that is not valid in a JDBC URI authority."
        ;;
      esac
      ;;
    esac
    case "$value" in
    -* | *-) config_fatal "$name must not begin or end with '-'." ;;
    .* | *.) config_fatal "$name must not begin or end with '.'." ;;
    *..*) config_fatal "$name must not contain an empty label ('..')." ;;
    *-.* | *.-*) config_fatal "$name must not contain a label that begins or ends with '-'." ;;
    esac
    ;;
  esac
}

###############################################################################
# Require a value that is safe to embed as the database component of a JDBC URI.
#
# The rendered value is the single path segment after the authority, so '/' would introduce a second
# segment, '?' would start a connection parameter list and '#' would truncate the URI. PostgreSQL
# database names are restricted here to the identifier characters, which covers every name the
# engine's own tooling creates.
# $1 - variable name, $2 - value
require_jdbc_database() {
  reject_unsafe_value "$1" "$2"
  case "$2" in
  '')
    config_fatal "$1 must not be empty."
    ;;
  *[!A-Za-z0-9._-]*)
    config_fatal "$1 must contain only letters, digits, '.', '_' or '-'. It contains a character that is not valid in a JDBC URI path."
    ;;
  esac
}

###############################################################################
# Resolve and validate the password of the OFBiz admin user.
#
# The admin user holds every OFBiz permission, so this is a credential and not a convenience setting.
# A value published in this repository must not be able to become a production admin password, in
# either of the two ways that can happen without a deployment seeing it: a manifest that never sets
# the variable at all and so inherits a default, and a manifest copied from the demo instructions
# that sets it explicitly.
#
# dev  keeps the published default, because that is what makes the demo image usable unconfigured.
# prod requires an explicitly supplied value, applies the same length and entropy floor as a database
#      password, and refuses every value published in this repository even when it is supplied
#      explicitly - an inherited demo value is exactly the case that has to be caught.
#
# Tracing is suspended for the whole function and the value is never printed, so a rejected password
# cannot reach the container log.
require_admin_password() {
  hide_secrets

  # The admin password configures work that OFBIZ_SKIP_INIT skips. When the data initialisation is not
  # going to run, load_admin_user never executes and no account is created, so demanding a password
  # would refuse to start a perfectly valid production container whose administrative user another
  # container already provisioned. Resolved locally rather than from RESOLVED_SKIP_INIT alone, because
  # ofbiz_setup_env can legitimately be called before resolve_skip_init has run; resolve_skip_init
  # remains the only place the value is VALIDATED.
  local skippingInitialisation="${RESOLVED_SKIP_INIT:-}"
  if [ -z "$skippingInitialisation" ]; then
    case "$(printf '%s' "${OFBIZ_SKIP_INIT:-}" | tr '[:upper:]' '[:lower:]')" in
    true | yes | 1) skippingInitialisation="true" ;;
    *) skippingInitialisation="false" ;;
    esac
  fi
  if [ "$skippingInitialisation" = "true" ]; then
    restore_trace
    return 0
  fi

  if [ -z "${OFBIZ_ADMIN_PASSWORD:-}" ]; then
    if [ "$OFBIZ_PROFILE" = 'prod' ]; then
      config_fatal "OFBIZ_ADMIN_PASSWORD must be supplied when OFBIZ_PROFILE=prod. There is no default: the admin user holds every OFBiz permission, and the value this script used to fall back to is published in this repository."
    fi
    OFBIZ_ADMIN_PASSWORD="$ADMIN_PASSWORD_DEV_DEFAULT"
  fi

  reject_unsafe_value OFBIZ_ADMIN_PASSWORD "$OFBIZ_ADMIN_PASSWORD"

  if [ "$OFBIZ_PROFILE" = 'prod' ]; then
    local retired
    for retired in "${RETIRED_ADMIN_PASSWORDS[@]}"; do
      if [ "$OFBIZ_ADMIN_PASSWORD" = "$retired" ]; then
        config_fatal "OFBIZ_ADMIN_PASSWORD is one of the demo passwords published in this repository, which OFBIZ_PROFILE=prod does not accept. Set a private password and rotate any deployment that used it."
      fi
    done
    if ! secret_is_usable "$OFBIZ_ADMIN_PASSWORD" "$ADMIN_PASSWORD_MIN_LENGTH" ''; then
      config_fatal "OFBIZ_ADMIN_PASSWORD $SECRET_REJECTION_REASON. OFBIZ_PROFILE=prod requires a private, high entropy password for the fully privileged admin user."
    fi
  fi

  restore_trace
}

###############################################################################
# Require a managed database password that can actually protect the database.
#
# There is deliberately no default. A missing password is a fatal misconfiguration, and the published
# values named by RETIRED_DATABASE_PASSWORDS are refused in every profile so that a database still
# provisioned with one of them cannot be reached. In the prod profile the value must additionally
# clear the shared length and entropy floor.
#
# Tracing is suspended for the whole function and the value is never printed - not in an error
# message, not in a comparison - so a rejected password cannot end up in the container log.
# $1 - variable name, $2 - value (never printed)
require_database_password() {
  hide_secrets
  local name="$1"
  local value="$2"

  reject_unsafe_value "$name" "$value"

  if [ -z "$value" ]; then
    config_fatal "$name must be set when OFBIZ_POSTGRES_HOST is set. There is no default database password."
  fi

  local retired
  for retired in "${RETIRED_DATABASE_PASSWORDS[@]}"; do
    if [ "$value" = "$retired" ]; then
      config_fatal "$name is one of the published default passwords that are no longer accepted. Set a private password and rotate the database."
    fi
  done

  if [ "$OFBIZ_PROFILE" = 'prod' ] && ! secret_is_usable "$value" "$DATABASE_PASSWORD_MIN_LENGTH" ''; then
    config_fatal "$name $SECRET_REJECTION_REASON. OFBIZ_PROFILE=prod requires a private, high entropy database password."
  fi

  restore_trace
}


###############################################################################
# The TLS query string resolved by the most recent require_postgres_ssl_parameters call.
#
# Published in a global rather than on stdout for the same reason as RESOLVED_SECRET: a command
# substitution would run the resolver in a subshell, where config_fatal's 'exit' ends only that
# subshell and start up would carry on with an empty - and therefore unencrypted - URI, silently
# defeating the fail-fast.
RESOLVED_POSTGRES_SSL_PARAMETERS=""

###############################################################################
# Resolve the query string that carries the TLS settings of the three managed datasources into
# RESOLVED_POSTGRES_SSL_PARAMETERS.
#
# The whole query string is produced as a single token so that the rendered URI is well formed whether
# or not a trusted root path is configured; the alternative - conditionally appending a second token -
# would need the template to carry a '?' or '&' that is wrong in the other case.
# The mode is validated against the driver's vocabulary, and a non-verifying mode is fatal in the prod
# profile, so the deployed profile cannot be downgraded to plaintext by an environment variable.
require_postgres_ssl_parameters() {
  # Defaulted here as well as in ofbiz_setup_env, on purpose. Whether the connection is verified must
  # not depend on the order in which these functions happen to be called, and an empty value must
  # never be able to mean "render no TLS parameters at all".
  OFBIZ_POSTGRES_SSLMODE=${OFBIZ_POSTGRES_SSLMODE:-$POSTGRES_SSL_DEFAULT_MODE}

  require_enum OFBIZ_POSTGRES_SSLMODE "$OFBIZ_POSTGRES_SSLMODE" "${POSTGRES_SSL_MODES[@]}"

  if [ "$OFBIZ_PROFILE" = 'prod' ]; then
    # 'verify-ca' is refused here too, although it is nominally a verifying mode. It authenticates the
    # certificate but NOT the
    # identity behind it: pgJDBC skips the hostname check, so any server presenting a certificate from
    # the same certification authority is trusted - and a managed cloud database service issues every
    # tenant a certificate from one shared CA. An attacker who can influence DNS or routing therefore
    # only needs a certificate of their own from that CA to receive the datasource credentials and
    # every row that follows. Only verify-full closes that, so the deployed profile requires it.
    local accepted
    local isAccepted='false'
    for accepted in "${POSTGRES_SSL_PROD_MODES[@]}"; do
      if [ "$OFBIZ_POSTGRES_SSLMODE" = "$accepted" ]; then
        isAccepted='true'
      fi
    done
    if [ "$isAccepted" != 'true' ]; then
      config_fatal "OFBIZ_POSTGRES_SSLMODE=$OFBIZ_POSTGRES_SSLMODE does not authenticate the identity of the database server, which OFBIZ_PROFILE=prod does not allow. Use one of: ${POSTGRES_SSL_PROD_MODES[*]}. verify-ca is no longer accepted in prod because it omits the hostname check, so any host with a certificate from the same certification authority is trusted."
    fi
  fi

  RESOLVED_POSTGRES_SSL_PARAMETERS="?sslmode=$OFBIZ_POSTGRES_SSLMODE"

  if [ -n "${OFBIZ_POSTGRES_SSLROOTCERT:-}" ]; then
    reject_unsafe_value OFBIZ_POSTGRES_SSLROOTCERT "$OFBIZ_POSTGRES_SSLROOTCERT"
    # The value lands in the URI query string, where '&' would start another connection parameter,
    # '?' or '#' would truncate the URI and whitespace would break the attribute, so only the
    # characters a POSIX path needs are accepted.
    case "$OFBIZ_POSTGRES_SSLROOTCERT" in
    *[!A-Za-z0-9._/-]*)
      config_fatal "OFBIZ_POSTGRES_SSLROOTCERT must be a plain file path: letters, digits, '/', '.', '_' and '-' only."
      ;;
    esac
    RESOLVED_POSTGRES_SSL_PARAMETERS="$RESOLVED_POSTGRES_SSL_PARAMETERS&sslrootcert=$OFBIZ_POSTGRES_SSLROOTCERT"
  fi
}

###############################################################################
# The network-deadline query string resolved by the most recent require_postgres_jdbc_parameters call.
#
# A global for the same reason as RESOLVED_POSTGRES_SSL_PARAMETERS: read through a command substitution
# the resolver would run in a subshell, config_fatal's 'exit' would end only that subshell, and a
# rejected value would leave this empty - which renders URIs with no deadlines at all, exactly the
# state the resolver exists to prevent.
RESOLVED_POSTGRES_JDBC_PARAMETERS=""

# The normalised TCP keepalive flag that went into that query string. A global rather than a local
# because it is one of the values the desired-state fingerprint records, and the fingerprint has to
# record what was RENDERED: require_boolean accepts 'yes' and '1' as well as 'true', so recording the
# raw variable would make two starts that produced byte identical configurations look like a change of
# target.
RESOLVED_POSTGRES_TCP_KEEPALIVE=""

# The normalised connection-validation flag of the three managed pools, kept for the same reason.
RESOLVED_DB_POOL_TEST_ON_BORROW=""

###############################################################################
# Resolve the pgJDBC network deadlines of the three managed datasources into
# RESOLVED_POSTGRES_JDBC_PARAMETERS.
#
# Without these the driver leaves socketTimeout and loginTimeout at 0, meaning no limit, so any thread
# that reaches a database which has stopped answering - a failed-over primary, a dropped NAT mapping, a
# security group closed underneath an established connection - blocks on the socket for as long as the
# kernel keeps it open. That is minutes at best and unbounded at worst, and it is not confined to
# request threads: the same block strands the start-up entity check and the readiness probe's count,
# which is what makes an instance that is merely waiting indistinguishable from one that is wedged.
#
# The string is produced as one token, appended AFTER @SSL_PARAMS@, which always renders non-empty and
# always begins with '?'. Every parameter here therefore starts with '&' and the rendered URI is well
# formed no matter which optional parameters are present. That ordering is also what keeps
# require_rendered_transport_security's check intact - it matches sslmode followed by either '&' or the
# closing quote - so the deadlines can never displace the TLS mode from the URI.
#
# Every value is validated against an explicit range before it is rendered. A malformed deadline is
# refused rather than defaulted, because pgJDBC ignores a connection parameter it cannot parse: a
# typo would produce a URI that looks bounded and behaves exactly like the unbounded one.
require_postgres_jdbc_parameters() {
  # Defaulted here as well as in ofbiz_setup_env, on purpose, and for the same reason the TLS mode is:
  # whether a socket read can block forever must not depend on the order in which these functions are
  # called, and an empty value must never be able to mean "render no deadline at all".
  OFBIZ_POSTGRES_CONNECT_TIMEOUT=${OFBIZ_POSTGRES_CONNECT_TIMEOUT:-$POSTGRES_CONNECT_TIMEOUT_DEFAULT}
  OFBIZ_POSTGRES_SOCKET_TIMEOUT=${OFBIZ_POSTGRES_SOCKET_TIMEOUT:-$POSTGRES_SOCKET_TIMEOUT_DEFAULT}
  OFBIZ_POSTGRES_LOGIN_TIMEOUT=${OFBIZ_POSTGRES_LOGIN_TIMEOUT:-$POSTGRES_LOGIN_TIMEOUT_DEFAULT}
  OFBIZ_POSTGRES_CANCEL_TIMEOUT=${OFBIZ_POSTGRES_CANCEL_TIMEOUT:-$POSTGRES_CANCEL_TIMEOUT_DEFAULT}
  OFBIZ_POSTGRES_QUERY_TIMEOUT=${OFBIZ_POSTGRES_QUERY_TIMEOUT:-$POSTGRES_QUERY_TIMEOUT_DEFAULT}
  OFBIZ_POSTGRES_TCP_KEEPALIVE=${OFBIZ_POSTGRES_TCP_KEEPALIVE:-$POSTGRES_TCP_KEEPALIVE_DEFAULT}

  require_integer_range OFBIZ_POSTGRES_CONNECT_TIMEOUT "$OFBIZ_POSTGRES_CONNECT_TIMEOUT" \
    "$POSTGRES_CONNECT_TIMEOUT_MIN" "$POSTGRES_CONNECT_TIMEOUT_MAX"
  require_integer_range OFBIZ_POSTGRES_SOCKET_TIMEOUT "$OFBIZ_POSTGRES_SOCKET_TIMEOUT" \
    "$POSTGRES_SOCKET_TIMEOUT_MIN" "$POSTGRES_SOCKET_TIMEOUT_MAX"
  require_integer_range OFBIZ_POSTGRES_LOGIN_TIMEOUT "$OFBIZ_POSTGRES_LOGIN_TIMEOUT" \
    "$POSTGRES_LOGIN_TIMEOUT_MIN" "$POSTGRES_LOGIN_TIMEOUT_MAX"
  require_integer_range OFBIZ_POSTGRES_CANCEL_TIMEOUT "$OFBIZ_POSTGRES_CANCEL_TIMEOUT" \
    "$POSTGRES_CANCEL_TIMEOUT_MIN" "$POSTGRES_CANCEL_TIMEOUT_MAX"
  require_integer_range OFBIZ_POSTGRES_QUERY_TIMEOUT "$OFBIZ_POSTGRES_QUERY_TIMEOUT" \
    "$POSTGRES_QUERY_TIMEOUT_MIN" "$POSTGRES_QUERY_TIMEOUT_MAX"

  # The status is checked explicitly because require_boolean normalises onto stdout and its
  # config_fatal ends only the command substitution's subshell; an unchecked failure would leave this
  # empty and render 'tcpKeepAlive=', which pgJDBC rejects at connection time rather than at start up.
  RESOLVED_POSTGRES_TCP_KEEPALIVE=$(require_boolean OFBIZ_POSTGRES_TCP_KEEPALIVE \
    "$OFBIZ_POSTGRES_TCP_KEEPALIVE") \
    || config_fatal "OFBIZ_POSTGRES_TCP_KEEPALIVE must be a boolean: true or false."

  # A connect deadline longer than the socket deadline cannot take effect: pgJDBC applies socketTimeout
  # to the reads of the start-up handshake as well, so the shorter of the two ends the attempt. Refused
  # rather than silently reconciled, because the value an operator set would otherwise not be the one
  # in force.
  if [ "$OFBIZ_POSTGRES_CONNECT_TIMEOUT" -gt "$OFBIZ_POSTGRES_SOCKET_TIMEOUT" ]; then
    config_fatal "OFBIZ_POSTGRES_CONNECT_TIMEOUT=$OFBIZ_POSTGRES_CONNECT_TIMEOUT exceeds OFBIZ_POSTGRES_SOCKET_TIMEOUT=$OFBIZ_POSTGRES_SOCKET_TIMEOUT, so the socket deadline would end the connection attempt first and the connect deadline would never apply. Raise the socket deadline or lower the connect deadline."
  fi

  RESOLVED_POSTGRES_JDBC_PARAMETERS="&connectTimeout=$OFBIZ_POSTGRES_CONNECT_TIMEOUT"
  RESOLVED_POSTGRES_JDBC_PARAMETERS="$RESOLVED_POSTGRES_JDBC_PARAMETERS&socketTimeout=$OFBIZ_POSTGRES_SOCKET_TIMEOUT"
  RESOLVED_POSTGRES_JDBC_PARAMETERS="$RESOLVED_POSTGRES_JDBC_PARAMETERS&loginTimeout=$OFBIZ_POSTGRES_LOGIN_TIMEOUT"
  RESOLVED_POSTGRES_JDBC_PARAMETERS="$RESOLVED_POSTGRES_JDBC_PARAMETERS&cancelSignalTimeout=$OFBIZ_POSTGRES_CANCEL_TIMEOUT"
  RESOLVED_POSTGRES_JDBC_PARAMETERS="$RESOLVED_POSTGRES_JDBC_PARAMETERS&tcpKeepAlive=$RESOLVED_POSTGRES_TCP_KEEPALIVE"

  # Rendered only when it is set. queryTimeout applies to EVERY statement the driver executes, so any
  # value here also aborts work that is legitimately long - a demo data load, a large report, the
  # one-shot schema init - and imposing that on an unchanged application would break functional parity.
  # Zero is pgJDBC's own "no statement deadline", so omitting the parameter and rendering zero are
  # equivalent to the driver; the parameter is omitted so that the rendered URI states only the
  # deadlines that are actually in force.
  if [ "$OFBIZ_POSTGRES_QUERY_TIMEOUT" -gt 0 ]; then
    RESOLVED_POSTGRES_JDBC_PARAMETERS="$RESOLVED_POSTGRES_JDBC_PARAMETERS&queryTimeout=$OFBIZ_POSTGRES_QUERY_TIMEOUT"
  fi
}

###############################################################################
# Verify that every PostgreSQL URI in a rendered entity configuration really carries the resolved
# sslmode.
#
# This is the fail-closed half of the transport-security requirement. The mode reaches the rendered
# file through the @SSL_PARAMS@ placeholder, so a template that predates that placeholder - an
# operator supplied copy, or one restored from an older image - would render URIs with no sslmode at
# all and the driver would silently negotiate down to an unencrypted connection. Checking the rendered
# artifact rather than trusting the substitution turns that into a start-up failure.
# grep is used in counting mode only; no URI is ever printed.
# $1 - rendered file, $2 - the sslmode that must be present on every managed URI
require_rendered_transport_security() {
  local renderedFile="$1"
  local expectedMode="$2"
  local uriCount
  local secureCount

  uriCount=$(grep --count 'jdbc-uri="jdbc:postgresql:' "$renderedFile") || uriCount=0
  secureCount=$(grep --count --extended-regexp \
    "jdbc-uri=\"jdbc:postgresql:[^\"]*[?&]sslmode=$expectedMode(&|\")" "$renderedFile") || secureCount=0

  # The rejected render is deleted before aborting. It was written atomically, so it is a complete and
  # loadable file; leaving it in /ofbiz/config, which takes class path precedence, would mean an
  # operator who restarted the container with the check somehow bypassed would be served the
  # unverified configuration this call exists to refuse.
  if [ "$uriCount" -eq 0 ]; then
    discard_untrustworthy_render "$renderedFile"
    config_fatal "Rendered $renderedFile declares no PostgreSQL datasource URI. The template it was rendered from is not the expected one."
  fi
  if [ "$uriCount" -ne "$secureCount" ]; then
    discard_untrustworthy_render "$renderedFile"
    config_fatal "Rendered $renderedFile has $uriCount PostgreSQL URIs but only $secureCount carry sslmode=$expectedMode. The template is missing the @SSL_PARAMS@ placeholder, so the connection would not be encrypted."
  fi
}

###############################################################################
# Verify that every PostgreSQL URI in a rendered entity configuration really carries a socket deadline,
# and that every managed pool really carries a borrow wait.
#
# The fail-closed half of the network-deadline requirement, and the same argument as the transport check
# above: both settings reach the rendered file through placeholders, so a template that predates them -
# an operator supplied copy, or one restored from an older image - renders URIs with no socketTimeout and
# pools with no pool-sleeptime, and the DEFAULTS THAT THEN APPLY ARE THE UNBOUNDED ONES. pgJDBC treats an
# absent socketTimeout as no limit at all, and InlineJdbc reads an absent pool-sleeptime as 300000 ms. In
# both cases the configuration is perfectly valid and OFBiz starts normally, which is exactly why this is
# checked on the artifact rather than trusted to the substitution.
#
# socketTimeout is the parameter checked because it is the one that bounds a read on an established
# connection, and therefore the one that decides whether a thread already inside the driver can be
# stranded. The others narrow specific windows; this is the one whose absence is unbounded.
#
# grep is used in counting mode only; no URI is ever printed, so no credential in a rendered file can
# reach the log through this check.
# $1 - rendered file
require_rendered_jdbc_deadlines() {
  local renderedFile="$1"
  local uriCount
  local boundedCount
  local poolCount
  local waitCount

  uriCount=$(grep --count 'jdbc-uri="jdbc:postgresql:' "$renderedFile") || uriCount=0
  # The optional 'amp;' is not sloppiness. The parameter separator is an '&', which cannot appear
  # literally inside an XML attribute, so the rendered file carries it escaped as '&amp;' - and the
  # parser hands the driver a plain '&' when it reads the attribute back. The pattern therefore has to
  # accept the separator in the form the FILE holds it, while still matching a hand-written template
  # that uses a raw '&', which is why the entity is optional rather than required.
  boundedCount=$(grep --count --extended-regexp \
    'jdbc-uri="jdbc:postgresql:[^"]*[?&](amp;)?socketTimeout=[0-9]+(&|")' "$renderedFile") || boundedCount=0
  # Every pool is counted, not only the managed ones. The borrow wait is a property of the pool rather
  # than of the URI, the embedded datasources declare one of their own, and requiring all of them to
  # carry a numeric value is both the stronger check and the one that cannot be satisfied by a
  # half-substituted template.
  poolCount=$(grep --count '<inline-jdbc' "$renderedFile") || poolCount=0
  waitCount=$(grep --count --extended-regexp 'pool-sleeptime="[0-9]+"' "$renderedFile") || waitCount=0

  # The rejected render is deleted before aborting, for the same reason the transport check deletes it:
  # it is a complete, loadable file in /ofbiz/config, which takes class path precedence, so leaving it
  # behind would mean a later start with this check bypassed would be served the unbounded configuration
  # this call exists to refuse.
  if [ "$uriCount" -eq 0 ]; then
    discard_untrustworthy_render "$renderedFile"
    config_fatal "Rendered $renderedFile declares no PostgreSQL datasource URI. The template it was rendered from is not the expected one."
  fi
  if [ "$uriCount" -ne "$boundedCount" ]; then
    discard_untrustworthy_render "$renderedFile"
    config_fatal "Rendered $renderedFile has $uriCount PostgreSQL URIs but only $boundedCount carry a socketTimeout. The template is missing the @JDBC_PARAMS@ placeholder, so a read on a database that stopped answering would block without limit."
  fi
  if [ "$poolCount" -eq 0 ] || [ "$poolCount" -ne "$waitCount" ]; then
    discard_untrustworthy_render "$renderedFile"
    config_fatal "Rendered $renderedFile declares $poolCount connection pools but only $waitCount of them state a numeric pool-sleeptime. The template is missing the @DB_POOL_WAIT@ placeholder, so a thread waiting for a connection from an exhausted pool would wait the engine's 300000 ms default."
  fi
}

###############################################################################
# Refuse a render template that names a placeholder anywhere it must not be substituted: a password
# placeholder outside the jdbc-password attribute it is meant to fill, or ANY placeholder inside an XML
# comment.
#
# sed rewrites comments exactly as readily as attributes, so a placeholder mentioned in prose - in a
# token inventory, a worked example, a maintenance note - is substituted there too, and the rendered
# configuration then repeats the database passwords in clear text outside the one attribute that is
# supposed to hold each of them. Every additional copy is a place a support bundle, a configuration diff
# or a mounted volume can leak them from.
#
# The second check generalises the first to every placeholder, for a reason that is not about secrecy at
# all: a substituted value can contain '--' or '>', and either one inside a comment produces a document
# that is no longer well formed - a double hyphen is illegal in XML comment text, and a '>' after one
# closes the comment early and turns the rest of the prose into markup. So a placeholder named in a
# comment is refused whatever it holds, and the templates describe placeholders in prose by the
# attribute they occupy instead of naming them.
#
# Both are checked BEFORE rendering and read only the template, so no secret is involved: the
# placeholder names are public and the checks are purely structural. They are deliberately refusals
# rather than warnings, because the failure mode they prevent is silent - the rendered file is perfectly
# valid and OFBiz starts normally.
# $1 - template path
require_template_secret_placement() {
  local template="$1"
  local offendingLines
  local commentedLines

  offendingLines=$(grep --line-number '_PASSWORD@' "$template" | grep --invert-match 'jdbc-password="' | cut --delimiter=: --fields=1 | tr '\n' ' ') || offendingLines=''
  if [ -n "$offendingLines" ]; then
    config_fatal "$template names a password placeholder outside a jdbc-password attribute, on line(s): $offendingLines. Rendering it would copy the database passwords into those lines. Remove the placeholder from the prose."
  fi

  # Comment state is tracked ACROSS lines, because these comments span many of them and a placeholder
  # named on a continuation line is as exposed as one named on the opening line. Only the text INSIDE a
  # comment is accumulated, so a placeholder in an attribute on the same line is not reported here.
  commentedLines=$(awk '
    BEGIN { inComment = 0 }
    {
      rest = $0
      commented = ""
      while (length(rest) > 0) {
        if (inComment == 0) {
          opening = index(rest, "<!--")
          if (opening == 0) {
            rest = ""
          } else {
            rest = substr(rest, opening + 4)
            inComment = 1
          }
        } else {
          closing = index(rest, "-->")
          if (closing == 0) {
            commented = commented rest
            rest = ""
          } else {
            commented = commented substr(rest, 1, closing - 1)
            rest = substr(rest, closing + 3)
            inComment = 0
          }
        }
      }
      if (match(commented, /@[A-Z0-9_]+@/)) {
        print NR
      }
    }
  ' "$template" | tr '\n' ' ') || commentedLines=''
  if [ -n "$commentedLines" ]; then
    config_fatal "$template names an at-sign delimited placeholder inside an XML comment, on line(s): $commentedLines. The render substitutes comment text as readily as attribute text, so the value would be written there too - republishing a credential outside the attribute meant to hold it, and producing a malformed document whenever the value contains '--' or '>'. Describe the placeholder in prose by the attribute it occupies instead of naming it."
  fi
}

###############################################################################
# Refuse a rendered artifact in which any at-sign delimited placeholder survived substitution.
#
# This is the generic completeness half of the rendering contract, and it is deliberately not a list of
# known placeholder names. The per-setting checks that follow verify that the values this script MEANT
# to substitute really took effect; they cannot see a placeholder the script does not know about. A
# template that is newer than this script - an operator maintained copy, one restored from a later
# image, or one carrying a placeholder added to the template but not yet to the writer above - would
# therefore pass every specific check and still install a configuration containing a literal
# '@SOMETHING@'. The Entity Engine does not treat that as a placeholder: it is simply part of the
# attribute value, so '@HOST@' becomes a host name that is looked up and fails to resolve, and an
# unsubstituted boolean is not the literal "false", which for check-on-start means startup DDL is
# switched back ON for the whole fleet. Refusing the render is the only safe outcome.
#
# The rejected artifact is deleted first, for the same reason as in the checks below: it was written
# atomically, so it is a complete and loadable file, and /ofbiz/config precedes ofbiz.jar on the class
# path. Leaving it there would serve the configuration this call exists to refuse.
#
# Only placeholder NAMES are reported, never surrounding content, because that content includes
# credentials. Names are matched with the same [A-Z0-9_] grammar the template uses.
# $1 - rendered file
require_no_residual_placeholders() {
  local renderedFile="$1"
  local residualNames

  residualNames=$(grep --only-matching --extended-regexp '@[A-Z0-9_]+@' "$renderedFile" \
    | sort --unique | tr '\n' ' ') || residualNames=''

  if [ -n "$residualNames" ]; then
    discard_untrustworthy_render "$renderedFile"
    config_fatal "Rendered $renderedFile still contains unsubstituted placeholder(s): ${residualNames% }. Every placeholder in the template must have a substitution in this script; the template and this entry point are out of step."
  fi
}

###############################################################################
# Verify that the rendered entity configuration carries the intended startup-DDL mode on every managed
# datasource, and that the flags are the literals the Entity Engine actually recognises.
#
# This is the executable half of Objective 4's claim that a serving instance issues no DDL. Both flags
# are resolved from literal strings rather than parsed as booleans: Datasource.java reads check-on-start
# as !"false".equals(value) and add-missing-on-start as "true".equals(value). So an unsubstituted
# placeholder, a missing attribute or a value such as "0" or "FALSE" would leave the schema check ON
# while looking disabled in the file. Requiring the exact literal on the exact attribute is what
# distinguishes those cases. It also fails when the two flags disagree, because the two supported modes
# are "apply the entity model additively" and "issue no DDL at all" - a datasource that checks the schema
# but may not add to it just logs differences on every boot of every instance.
#
# Every managed datasource is looked up BY NAME and its own two attributes are read, through the same
# datasource_attributes helper require_serving_mode_ddl_safety uses, so what is asserted is the posture of
# each named datasource rather than a property of the file as a whole. Counting the literals across the
# whole file and comparing the totals against the number of PostgreSQL datasources would agree with the
# per-datasource truth only while no other element carried either attribute: an attribute that MOVED -
# lost from a managed datasource and gained by an embedded one, which is what a mis-edited template
# produces - would leave both totals correct and the deployed profile wrong. It would also need the
# embedded H2 definitions discounted from the totals in init mode, because they carry the same literal
# on purpose; naming the datasources removes that arithmetic along with the assumption behind it.
# $1 - rendered file, $2 - the mode that must be present: true for init mode, false for run mode
require_rendered_schema_ddl_mode() {
  local renderedFile="$1"
  local expectedMode="$2"
  local datasourceName
  local attributes

  for datasourceName in "${MANAGED_DATASOURCE_NAMES[@]}"; do
    attributes=$(datasource_attributes "$renderedFile" "$datasourceName")
    if [ -z "$attributes" ]; then
      discard_untrustworthy_render "$renderedFile"
      config_fatal "Rendered $renderedFile does not declare the managed datasource '$datasourceName'. The template it was rendered from is not the expected one, so the startup DDL posture of the deployed profile cannot be established."
    fi

    case "$attributes" in
    *"check-on-start=\"$expectedMode\""*) ;;
    *)
      discard_untrustworthy_render "$renderedFile"
      config_fatal "Rendered $renderedFile does not give the managed datasource '$datasourceName' check-on-start=\"$expectedMode\". The engine reads that attribute as !\"false\".equals(value), so anything other than the exact literal \"false\" leaves the startup schema check enabled. Either the @CHECK_ON_START@ placeholder is missing from that datasource in the template or its substitution did not produce the requested mode."
      ;;
    esac

    case "$attributes" in
    *"add-missing-on-start=\"$expectedMode\""*) ;;
    *)
      discard_untrustworthy_render "$renderedFile"
      config_fatal "Rendered $renderedFile does not give the managed datasource '$datasourceName' add-missing-on-start=\"$expectedMode\". The engine reads that attribute as \"true\".equals(value), so only the exact literal \"true\" issues CREATE and ALTER statements and only the exact literal \"false\" states that it must not. Either the @ADD_MISSING_ON_START@ placeholder is missing from that datasource in the template or its substitution did not produce the requested mode."
      ;;
    esac
  done
}

###############################################################################
# Print the start tag of the named <datasource> element in the named file, with its attributes joined
# onto one line, or nothing when the file does not declare it.
#
# A datasource start tag spans many lines in every one of these files, so the attributes cannot be
# matched line by line. The element is identified by name="<exact name>" INCLUDING both quotes, which is
# what keeps name="localpostgres" from also matching name="localpostgresolap".
# $1 - file to inspect, $2 - datasource name
datasource_attributes() {
  if [ ! -f "$1" ]; then
    return 0
  fi
  awk -v target="name=\"$2\"" '
    !inside && index($0, "<datasource") && index($0, target) { inside = 1 }
    inside {
      block = block " " $0
      if (index($0, ">")) { print block; exit }
    }
  ' "$1"
}

###############################################################################
# Refuse to serve from an entity configuration whose managed datasources would issue startup DDL.
#
# This is the guarantee AAP 0.6.4 rests on: the serving fleet performs no startup DDL and therefore
# needs no DDL privilege. render_database_configuration enforces it strictly on every file THIS script
# renders, but that is not sufficient on its own, because /ofbiz/config is a declared volume in the
# Dockerfile and the file OFBiz actually reads may have been left there by an earlier run - including by
# this script's own init mode, which renders both flags true on purpose - or provisioned by other means,
# which is exactly what OFBIZ_SKIP_INIT is documented for. So the posture is checked on EVERY serving
# path, against whichever file is authoritative, no matter who wrote it.
#
# The two flags are read the way the engine reads them, from Datasource.java:96-97:
#   checkOnStart      = !"false".equals(attribute)   - ABSENT MEANS ENABLED
#   addMissingOnStart =  "true".equals(attribute)    - absent means disabled
# and GenericDelegator:285-290 consults the second only when the first is enabled, while every
# DDL-emitting branch of DatabaseUtil.checkDb is guarded by it. That gives two distinct outcomes rather
# than one, and they are reported differently on purpose:
#
#   check-on-start="false"                        -> no DDL and no startup schema read. Correct.
#   otherwise, add-missing-on-start="true"        -> the instance WILL issue CREATE/ALTER. Refused.
#   otherwise                                     -> no DDL, but a full schema read on every boot.
#                                                    Reported, not refused: it is a cost and a metadata
#                                                    privilege, not the hazard this check exists for,
#                                                    and refusing would break the documented
#                                                    externally-provisioned path over something safe.
#
# Init mode is exempt by definition - it is the one execution that is supposed to apply DDL.
require_serving_mode_ddl_safety() {
  if [ "${RESOLVED_SCHEMA_INIT:-false}" = "true" ]; then
    return 0
  fi

  # Whichever file is authoritative for the JVM that is about to start: the override when one exists,
  # because /ofbiz/config precedes ofbiz.jar on the class path, and the committed copy otherwise.
  local authoritative="$ENTITY_ENGINE_SOURCE"
  local origin="committed configuration"
  if [ -f "$ENTITY_ENGINE_OVERRIDE" ]; then
    authoritative="$ENTITY_ENGINE_OVERRIDE"
    origin="rendered override"
  fi

  local datasourceName attributes
  for datasourceName in "${MANAGED_DATASOURCE_NAMES[@]}"; do
    attributes=$(datasource_attributes "$authoritative" "$datasourceName")
    if [ -z "$attributes" ]; then
      # Not declared in this file. The embedded profile's committed configuration declares all three, so
      # this only happens for a hand written file that serves from datasources of its own naming, whose
      # DDL posture this script cannot reason about and does not guess at.
      continue
    fi

    case "$attributes" in
    *'check-on-start="false"'*)
      continue
      ;;
    esac

    case "$attributes" in
    *'add-missing-on-start="true"'*)
      config_fatal "$authoritative ($origin) leaves startup DDL enabled on the '$datasourceName' datasource: check-on-start is not \"false\" and add-missing-on-start is \"true\". A serving instance would issue CREATE and ALTER statements against the managed database, which is what OFBIZ_SCHEMA_INIT=true exists to do exactly once. Re-render this configuration by supplying the OFBIZ_POSTGRES_* variables, or delete $ENTITY_ENGINE_OVERRIDE so the committed configuration applies."
      ;;
    esac

    printf '%s\n' "WARNING: $authoritative ($origin) does not set check-on-start=\"false\" on the '$datasourceName' datasource. No DDL is issued because add-missing-on-start is not \"true\", but the engine reads the whole schema on every boot, which needs metadata privileges and delays start up. AAP run mode expects both flags to be \"false\"." >&2
  done
}

###############################################################################
# Verify that a rendered entity configuration carries the intended cache-clear flag on exactly the two
# default delegators, and that the test delegator was left alone.
#
# DelegatorElement resolves the attribute as "true".equalsIgnoreCase(value), so - as with the DDL flags
# - an unsubstituted placeholder or an unrecognised spelling reads as "not true" and silently keeps
# single-node caching, which behind a load balancer means stale reads rather than a visible failure.
# The test delegator is checked from the other direction: it must carry NO such attribute, because a
# single-JVM integration-test delegator that joined the invalidation topic would publish test writes to
# the serving instances.
# $1 - rendered file, $2 - the value that must be present on the two default delegators
require_rendered_cache_clear_mode() {
  local renderedFile="$1"
  local expectedValue="$2"
  local delegatorName
  local declaration

  for delegatorName in "${CACHE_CLEAR_DELEGATORS[@]}"; do
    declaration=$(grep --only-matching \
      "<delegator name=\"$delegatorName\"[^>]*distributed-cache-clear-enabled=\"[^\"]*\"" "$renderedFile") || declaration=''
    case "$declaration" in
    *"distributed-cache-clear-enabled=\"$expectedValue\"") ;;
    '')
      discard_untrustworthy_render "$renderedFile"
      config_fatal "Rendered $renderedFile does not declare distributed-cache-clear-enabled on the '$delegatorName' delegator. The anchor the entry point substitutes has been removed or reformatted."
      ;;
    *)
      discard_untrustworthy_render "$renderedFile"
      config_fatal "Rendered $renderedFile does not carry distributed-cache-clear-enabled=\"$expectedValue\" on the '$delegatorName' delegator, so cross-instance cache invalidation is not in the requested state."
      ;;
    esac
  done

  if grep --quiet '<delegator name="test"[^>]*distributed-cache-clear-enabled=' "$renderedFile"; then
    discard_untrustworthy_render "$renderedFile"
    config_fatal "Rendered $renderedFile enables distributed cache clear on the 'test' delegator. That delegator is single-JVM only and must never join the invalidation topic."
  fi
}

###############################################################################
# Verify that the rendered configuration keeps the 'test' delegator on the embedded H2 datasources.
#
# Repointing the test delegator at the managed datasources along with the other two would aim a
# delegator whose readers include ext-test - and which integration tests write to and delete from
# freely - at the production database.
# Integration tests must never need a managed database, a credential or a network, so the mapping is
# checked on the rendered artifact: it is a template edit away from regressing, and the regression is
# invisible until the suite has already written to the wrong database.
# $1 - rendered file
require_rendered_test_delegator_isolation() {
  local renderedFile="$1"
  local mappings

  mappings=$(sed --quiet '/<delegator name="test"/,/<\/delegator>/p' "$renderedFile" |
    grep --only-matching 'datasource-name="[^"]*"' | sed 's,.*=",,; s,",,' | sort | tr '\n' ' ')
  if [ "$mappings" != "localh2 localh2olap localh2tenant " ]; then
    discard_untrustworthy_render "$renderedFile"
    config_fatal "Rendered $renderedFile maps the 'test' delegator to [$mappings] instead of the embedded H2 datasources. Integration tests must never be pointed at a managed database."
  fi
}

###############################################################################
# Verify that the rendered configuration authenticates as the identity that was resolved, and - when the
# two are separate - that the other identity's role names are nowhere in it.
#
# This is the executable half of the least-privilege claim. The identity reaches the rendered file
# through the three @*_USERNAME@ placeholders, so a template that predates one of them, or a
# substitution that silently did not match, would leave a serving instance connecting as the schema
# owner, or an init run connecting as a role with no DDL grant. Neither is visible in the file at a
# glance and neither fails until much later - the first as a privilege that should not exist, the second
# as a permission-denied error part way through creating the schema.
#
# The assertion is an EXACT equality against the resolved role, per managed datasource, so it fails in
# both directions: a serving instance rendered with the privileged role and an init run rendered with the
# unprivileged one are equally refused. Only role names are compared, never passwords, so every value in
# this function and in its failure messages is safe to print.
# $1 - rendered file
require_rendered_database_identity() {
  local renderedFile="$1"
  local index group datasourceName resolvedUserVariable expectedUser actualUser leftoverLines

  for index in "${!POSTGRES_GROUPS[@]}"; do
    group="${POSTGRES_GROUPS[$index]}"
    datasourceName="${MANAGED_DATASOURCE_NAMES[$index]}"
    resolvedUserVariable="RESOLVED_POSTGRES_${group}_USER"
    # Compared in its XML-escaped form, which is what the attribute actually holds. A role name may
    # legitimately contain '&' or '<'; comparing the raw value would reject the very escaping that makes
    # such a name safe to render.
    expectedUser=$(xml_escape_value "${!resolvedUserVariable}")

    # Scoped to the one datasource element. A whole-file match would also see the embedded H2
    # definitions, which carry a fixed jdbc-username of their own. The trailing space makes a datasource
    # that declares the attribute twice fail here rather than compare equal to its first value.
    actualUser=$(sed --quiet "/<datasource name=\"$datasourceName\"/,/<\/datasource>/p" "$renderedFile" |
      grep --only-matching 'jdbc-username="[^"]*"' | sed 's,.*=",,; s,",,' | tr '\n' ' ') || actualUser=''
    if [ "$actualUser" != "$expectedUser " ]; then
      discard_untrustworthy_render "$renderedFile"
      config_fatal "Rendered $renderedFile makes the '$datasourceName' datasource authenticate as [${actualUser% }] instead of the resolved $RESOLVED_DATABASE_IDENTITY role [$expectedUser]. Either the template is missing that datasource's username placeholder or it declares more than one, so the connection would not use the identity this run resolved."
    fi
  done

  # Nothing may be left holding an unsubstituted identity placeholder. A datasource an operator added to
  # their own copy of the template would otherwise try to authenticate as the literal '@OFBIZ_USERNAME@',
  # which fails at the first query rather than at start up, and a leftover password placeholder is a
  # placeholder standing where a credential was supposed to be.
  leftoverLines=$(grep --line-number --extended-regexp '@(OFBIZ|OLAP|TENANT)_(USERNAME|PASSWORD)@' \
    "$renderedFile" | cut --delimiter=: --fields=1 | tr '\n' ' ') || leftoverLines=''
  if [ -n "$leftoverLines" ]; then
    discard_untrustworthy_render "$renderedFile"
    config_fatal "Rendered $renderedFile still contains an unsubstituted database identity placeholder on line(s): $leftoverLines. Those datasources would try to authenticate as the placeholder text itself."
  fi
}

###############################################################################
# Description of why the last secret_is_usable call rejected its value. Never
# contains the value itself, so it is safe to print.
SECRET_REJECTION_REASON=""

###############################################################################
# Decide, without aborting, whether a secret meets the requirements of a shared
# secret: not empty, not the publicly known 'NA' fallback, at least the required
# minimum length, free of control characters, free of any character the consuming
# wire format cannot carry, and not simply one character repeated.
#
# Returns 0 when the value is usable. Otherwise returns 1 and leaves an explanation
# in SECRET_REJECTION_REASON. Tracing is suspended so the value is never echoed.
# $1 - value (never printed), $2 - minimum length, $3 - forbidden characters (may be empty)
secret_is_usable() {
  hide_secrets
  local value="$1"
  local minLength="$2"
  local forbidden="$3"
  SECRET_REJECTION_REASON=""

  if [ -z "$value" ]; then
    SECRET_REJECTION_REASON="must not be empty"
  elif [ "$value" = "NA" ]; then
    SECRET_REJECTION_REASON="must not be the publicly known placeholder value shipped as the code default"
  elif [ "${#value}" -lt "$minLength" ]; then
    SECRET_REJECTION_REASON="must be at least $minLength characters long"
  else
    case "$value" in
    *[[:cntrl:]]*)
      SECRET_REJECTION_REASON="must not contain a control character (newline, carriage return, tab or NUL)"
      ;;
    esac
  fi

  if [ -z "$SECRET_REJECTION_REASON" ] && [ -n "$forbidden" ]; then
    case "$value" in
    *["$forbidden"]*)
      SECRET_REJECTION_REASON="must not contain any of these characters: $forbidden"
      ;;
    esac
  fi

  if [ -z "$SECRET_REJECTION_REASON" ] \
    && [ "$(printf '%s' "$value" | fold --width=1 | sort --unique | wc --lines)" -lt "$MIN_DISTINCT_CHARACTERS" ]; then
    SECRET_REJECTION_REASON="has insufficient entropy - it uses fewer than $MIN_DISTINCT_CHARACTERS distinct characters"
  fi

  restore_trace
  [ -z "$SECRET_REJECTION_REASON" ]
}

###############################################################################
# Abort start up when a secret supplied through the environment would leave the
# deployment effectively unauthenticated. The rejection reason is reported, the
# rejected value never is.
# Tracing is suspended for the whole function so the value is never echoed.
# $1 - variable name, $2 - value, $3 - minimum length, $4 - forbidden characters (may be empty)
validate_secret_strength() {
  hide_secrets
  if ! secret_is_usable "$2" "$3" "$4"; then
    config_fatal "$1 $SECRET_REJECTION_REASON. Supply a random, high entropy value."
  fi
  restore_trace
}

###############################################################################
# Length and alphabet of a generated secret, checked after generation.
#
# 48 random bytes encode to exactly 64 base64 characters with no padding, matching the 512 bit key
# length that OFBiz requires for its HMAC512 signatures. Anything shorter is not a key.
GENERATED_SECRET_LENGTH=64

###############################################################################
# Generate a cryptographically random 64 character base64 secret in RESOLVED_GENERATED_SECRET, or
# abort the start up.
#
# FAILS CLOSED. The generation is a pipeline, and 'pipefail' cannot be enabled for this script as a
# whole - other pipelines here rely on the default behaviour of ignoring an upstream command
# terminated by SIGPIPE when a downstream 'head' exits - so the exit status of 'head' alone would
# report success even when /dev/urandom could not be read. Both stage statuses are therefore captured
# from PIPESTATUS and the result is then verified to be EXACTLY 64 base64 characters. Without that
# check a truncated or empty read would be written to the key store and used to sign JWTs and to name
# EntityKeyStore entries, which is a silently weak or absent key (CWE-330 / CWE-252) rather than a
# visible failure.
#
# The result is published in a global rather than on stdout because a command substitution runs the
# function in a subshell, where config_fatal's 'exit' would end only that subshell and the start up
# would continue with an unchecked value.
RESOLVED_GENERATED_SECRET=""
generate_secret() {
  hide_secrets
  RESOLVED_GENERATED_SECRET=""

  local generated
  # 'pipefail' is enabled inside the command substitution's subshell only, so a failure in either
  # stage propagates as the substitution's exit status. It cannot be checked with PIPESTATUS here:
  # an assignment from a command substitution is a simple command, so PIPESTATUS would describe the
  # assignment (a single element) rather than the pipeline that ran inside the subshell. Enabling
  # pipefail globally is not an option because other pipelines in this script rely on SIGPIPE.
  # 48 random bytes encode to exactly 64 base64 characters with no padding, and both stages read to
  # end-of-file, so neither is ever terminated by SIGPIPE.
  if ! generated=$(
    set -o pipefail
    head --bytes=48 /dev/urandom | basenc --base64 --wrap=0
  ); then
    config_fatal "Failed to generate a random secret: the /dev/urandom read or the base64 encoding failed."
  fi

  # Length and alphabet are both checked: a short read is a weak key, and a character outside the
  # base64 alphabet means the encoder did not produce what this function claims to produce.
  if [ "${#generated}" -ne "$GENERATED_SECRET_LENGTH" ]; then
    config_fatal "Failed to generate a random secret: expected exactly $GENERATED_SECRET_LENGTH characters but produced ${#generated}."
  fi
  case "$generated" in
  *[!A-Za-z0-9+/]*)
    config_fatal "Failed to generate a random secret: the generated value is not $GENERATED_SECRET_LENGTH base64 characters."
    ;;
  esac

  RESOLVED_GENERATED_SECRET="$generated"
  restore_trace
}

###############################################################################
# Length and alphabet of the random salt prefixed to the admin password hash.
#
# OFBiz reads the salt back out of the '$SHA$<salt>$<hash>' encoding, so it must contain none of the
# separators, which is what restricting it to letters and digits guarantees.
ADMIN_PASSWORD_SALT_LENGTH=16

###############################################################################
# Generate the random salt for the admin password hash in RESOLVED_PASSWORD_SALT, or abort.
#
# Fails closed for the same reason as generate_secret: the salt comes out of a pipeline whose first
# stage reads /dev/urandom, so both stage statuses are captured and the result is verified to be
# EXACTLY 16 alphanumeric characters before it is folded into the stored password hash. A short or
# empty salt would be accepted silently by the loader and would weaken every stored credential.
RESOLVED_PASSWORD_SALT=""
generate_password_salt() {
  hide_secrets
  RESOLVED_PASSWORD_SALT=""

  local entropy
  local generated
  # The read is bounded first and filtered second, which is the reverse of the historical
  # 'tr </dev/urandom | head' pipeline. That ordering matters: with 'head' downstream, 'tr' is killed
  # by SIGPIPE as soon as 'head' has its bytes, so a genuine failure of the entropy source is
  # indistinguishable from normal termination. Reading a bounded 1024 bytes from the device and
  # letting 'tr' run to end-of-file means both stages exit 0 on success, so 'pipefail' inside the
  # substitution's subshell reliably reports a real failure. 1024 random bytes yield roughly 248
  # alphanumeric characters, far more than required, and the length check below still fails closed.
  if ! entropy=$(
    set -o pipefail
    head --bytes=1024 /dev/urandom | tr --delete --complement A-Za-z0-9
  ); then
    config_fatal "Failed to generate the admin password salt: the /dev/urandom read or the character filter failed."
  fi
  generated="${entropy:0:$ADMIN_PASSWORD_SALT_LENGTH}"

  if [ "${#generated}" -ne "$ADMIN_PASSWORD_SALT_LENGTH" ]; then
    config_fatal "Failed to generate the admin password salt: expected exactly $ADMIN_PASSWORD_SALT_LENGTH characters but produced ${#generated}."
  fi
  case "$generated" in
  *[!A-Za-z0-9]*)
    config_fatal "Failed to generate the admin password salt: the generated value is not $ADMIN_PASSWORD_SALT_LENGTH alphanumeric characters."
    ;;
  esac

  RESOLVED_PASSWORD_SALT="$generated"
  restore_trace
}

###############################################################################
# The secret resolved by the most recent resolve_secret call.
#
# resolve_secret publishes its result in a global rather than on stdout on purpose. A command
# substitution runs the function in a subshell, where config_fatal's 'exit' would end only that
# subshell; start up would then continue with an empty secret and the fail-fast would be silently
# defeated. Keeping the resolution in the current shell keeps both the abort and the tracing
# suppression effective.
RESOLVED_SECRET=""

###############################################################################
# Resolve a deployment secret from the environment, honouring OFBIZ_PROFILE.
#
# Supplied  - the value is validated and used as is.
# Absent, OFBIZ_PROFILE=prod - start up aborts. A production deployment that silently falls back to
#            an empty value, or to a placeholder published in the source tree, is indistinguishable
#            from having no secret at all, so this fails closed.
# Absent, OFBIZ_PROFILE=dev  - a fresh cryptographically random value is generated for the lifetime
#            of this container. This keeps the zero-configuration local and demo experience without
#            generating keys into the image at build time: the value exists only in this container's
#            memory and in its mode 0600 rendered configuration, never in the source tree, the
#            distribution or an image layer.
#
# $1 - variable name, $2 - value, $3 - minimum length, $4 - forbidden characters (may be empty)
resolve_secret() {
  hide_secrets
  local name="$1"
  local value="$2"
  local minLength="$3"
  local forbidden="$4"

  if [ -n "$value" ]; then
    validate_secret_strength "$name" "$value" "$minLength" "$forbidden"
    RESOLVED_SECRET="$value"
    # The supplied value REPLACES any generated value this container was using, so the generated one is
    # destroyed rather than left on the volume. Keeping it meant a later start that simply omitted the
    # variable - a templating slip, a rolled-back manifest - silently revived a key the operator
    # believed they had rotated away from, and did so without a single message.
    discard_generated_secret "$name"
  elif [ "$OFBIZ_PROFILE" = "prod" ]; then
    config_fatal "$name must be supplied through the environment when OFBIZ_PROFILE=prod."
  else
    resolve_generated_secret "$name" "$minLength" "$forbidden"
  fi

  restore_trace
}

###############################################################################
# Destroy the generated value stored for a secret, if there is one.
#
# Reported, because a generated key names EntityKeyStore entries and signs JWTs: an operator moving
# from a generated key to a supplied one needs to know that the previous key is gone and that anything
# encrypted or signed with it can no longer be read or verified.
# $1 - variable name
discard_generated_secret() {
  local store="$CONTAINER_GENERATED_SECRETS_DIR/$1"
  if [ -e "$store" ]; then
    rm --force "$store"
    printf '%s\n' "$1 was supplied through the environment: discarded the previously generated per-container value for it. Data encrypted with the discarded value, and tokens signed with it, are no longer usable."
  fi
}

###############################################################################
# Destroy every generated secret this container may have stored.
#
# Called when OFBIZ_PROFILE=prod, where a generated value is never legitimate: every secret must be
# supplied, so anything left in the store is material from an earlier dev run of the same volume. It is
# removed rather than merely ignored, so that a volume promoted from dev to prod carries no key an
# accidental relapse to dev could resurrect.
discard_all_generated_secrets() {
  if [ -d "$CONTAINER_GENERATED_SECRETS_DIR" ]; then
    rm --recursive --force "$CONTAINER_GENERATED_SECRETS_DIR"
    printf '%s\n' "OFBIZ_PROFILE=prod: discarded the generated secret store left in the runtime volume. Every secret must be supplied through the environment in this profile."
  fi
}

###############################################################################
# Produce a generated secret in RESOLVED_SECRET for the dev profile.
#
# The value generated on an earlier start of this container is reused, so a generated key is stable
# for the life of the container's runtime volume. That matters for functional parity: these keys name
# EntityKeyStore entries and sign JWTs, so a value that changed on every restart would orphan
# previously encrypted data and invalidate every issued token. A stored value that is missing or no
# longer usable - a truncated or hand-edited file - is replaced rather than allowed to fail the start.
# $1 - variable name, $2 - minimum length, $3 - forbidden characters (may be empty)
resolve_generated_secret() {
  hide_secrets
  local name="$1"
  local minLength="$2"
  local forbidden="$3"
  local store="$CONTAINER_GENERATED_SECRETS_DIR/$name"

  # Defence in depth. resolve_secret already aborts before reaching here in the prod profile; refusing
  # again means no future call path can turn a deployed instance into one running on generated - or on
  # previously stored - key material.
  if [ "$OFBIZ_PROFILE" = "prod" ]; then
    config_fatal "$name must be supplied through the environment when OFBIZ_PROFILE=prod. Generated and stored per-container secrets are never used in this profile."
  fi

  RESOLVED_SECRET=""
  if [ -r "$store" ]; then
    RESOLVED_SECRET=$(cat "$store")
    if ! secret_is_usable "$RESOLVED_SECRET" "$minLength" "$forbidden"; then
      RESOLVED_SECRET=""
    fi
  fi

  if [ -z "$RESOLVED_SECRET" ]; then
    # Called rather than substituted: generate_secret validates its own output and aborts through
    # config_fatal, whose 'exit' would only end a command-substitution subshell.
    generate_secret
    RESOLVED_SECRET="$RESOLVED_GENERATED_SECRET"
    RESOLVED_GENERATED_SECRET=""
    mkdir --parents "$CONTAINER_GENERATED_SECRETS_DIR"
    chmod 700 "$CONTAINER_GENERATED_SECRETS_DIR"
    local temporary
    temporary=$(mktemp "$store.XXXXXXXX")
    # Tracked before anything is written to it, so a signal arriving between the creation and the
    # rename cannot leave a staging file holding the generated secret behind. Once the rename below
    # has happened the tracked path no longer exists and the cleanup is a no-op.
    register_secret_temp_file "$temporary"
    chmod 600 "$temporary"
    printf '%s' "$RESOLVED_SECRET" >"$temporary"
    mv --force "$temporary" "$store"
    # Reports that a value was generated, never the value itself.
    printf '%s\n' "OFBIZ_PROFILE=dev and $name was not supplied: generated a random per-container value."
  fi

  restore_trace
}

###############################################################################
# Escape a value for use as the replacement text of a sed 's|..|..|' command.
# Backslash, ampersand and the '|' delimiter are the only characters sed treats
# specially in a replacement, so escaping exactly those makes any value literal.
# Every sed expression added by this script uses '|' as its delimiter because a
# value may legitimately contain '/' (a JDBC URI) or '#' (a base64 secret).
# $1 - value
sed_escape_replacement() {
  printf '%s' "$1" | sed --expression='s,[\\&|],\\&,g'
}

###############################################################################
# Escape a value for use inside a double quoted XML attribute. '&' must be escaped
# first, otherwise the ampersands introduced by the later rules would be escaped again.
# $1 - value
xml_escape_value() {
  printf '%s' "$1" | sed \
    --expression='s,&,\&amp;,g' \
    --expression='s,<,\&lt;,g' \
    --expression='s,>,\&gt;,g' \
    --expression='s,",\&quot;,g' \
    --expression="s,',\\&apos;,g"
}

###############################################################################
# Escape a value for use as a java.util.Properties value.
#
# Two escapes are required, and both have a matching decoder in
# docker/send_ofbiz_stop_signal.sh, which reads these files back to build the shutdown request:
#
#   - A literal backslash is doubled. Properties interprets backslash escapes and treats a trailing
#     backslash as a line continuation, so an unescaped backslash would either vanish or swallow the
#     next line.
#   - A LEADING blank (space or tab) is escaped as '\<blank>'. Properties skips the run of whitespace
#     between the '=' and the value, so an unescaped leading blank is DISCARDED: the server would hold
#     a key with the blank stripped while the shutdown client - which decodes '\<blank>' back to the
#     blank - would send the value with it, and AdminServerContainer's String.equals comparison would
#     reject every shutdown request. Escaping is done rather than rejecting the value because these
#     secrets are operator supplied and a leading blank is legal in all of them.
#
# The backslash rule is applied FIRST and the leading-blank rule SECOND, so that the backslash the
# second rule introduces is not itself doubled; the decoder mirrors that by removing the leading-blank
# escape before collapsing doubled backslashes.
#
# Escaping exactly ONE leading blank is enough, and that is deliberate rather than an oversight: once
# the first blank is escaped the parser is past the separator, so every character after it - blanks
# included - is already part of the value.
#
# The concrete symptom this prevents is worth naming, because it has no diagnostic of its own: with a
# value silently altered by java.util.Properties, 'ofbiz --shutdown' compares two DIFFERENT strings
# and fails, reporting nothing more than a refused shutdown.
# $1 - value
properties_escape_value() {
  printf '%s' "$1" | sed \
    --expression='s,\\,\\\\,g' \
    --expression='s,^\([[:blank:]]\),\\\1,'
}

###############################################################################
# Emit, on stdout, a property NAME turned into a basic regular expression that matches only itself.
#
# Property names here are full of dots, and an unescaped dot in a basic regular expression matches any
# character. 'content.store.s3.bucket' would therefore also match 'contentXstoreXs3Xbucket', and - much
# more to the point - 'content.store.provider' used as a pattern matches nothing surprising while
# 'content.store.s3.access.key.id' overlaps names that differ from it only in punctuation. Escaping the
# dots keeps a substitution and its later read-back addressing exactly one property.
# $1 - property name
properties_key_pattern() {
  printf '%s' "$1" | sed --expression='s,\.,\\.,g'
}

###############################################################################
# Emit, on stdout, one sed substitution for a '@TOKEN@' placeholder in a
# configuration template. The value is XML escaped because it lands inside a double
# quoted XML attribute, then escaped for the sed replacement grammar. printf is a
# shell builtin, so the value never reaches the process table.
#
# The value is first refused if it contains a sentinel of its own. Every substitution written here ends
# up in ONE sed program that is applied as an ordered sequence, so text this value inserts is still
# visible to every later s-command: a value containing '@TENANT_PASSWORD@' would have the tenant
# password spliced into this field by the later tenant pass. reject_template_sentinel closes that
# without changing the renderer, and without restricting any value that merely contains '@'.
# $1 - token, including the surrounding '@'
# $2 - value
# $3 - variable name the value came from, named in a rejection message
write_xml_token_substitution() {
  reject_template_sentinel "${3:-$1}" "$2"
  printf 's|%s|%s|g\n' "$1" "$(sed_escape_replacement "$(xml_escape_value "$2")")"
}

###############################################################################
# Render a generated configuration file by transforming a pristine source file with
# sed, atomically and with restrictive permissions.
#
# sed writes into a temporary file created with mode 0600 in the destination directory, and the file
# is moved into place only after sed has reported success. That gives three properties the deployment
# depends on: a file that may hold a secret is never even briefly readable by another user; a failed
# or partial transformation leaves the previous good file untouched instead of installing a truncated
# one; and OFBiz never observes a half written configuration file. Because the source is always the
# pristine copy in the OFBiz source tree rather than the previous render, re-running this on a later
# container start is idempotent, which is what lets a rotated secret take effect on a restart.
#
# A pipeline is deliberately NOT used here. In 'sed ... | write' the writer cannot tell that sed
# failed, so it would install whatever partial output it received; and 'pipefail' cannot be enabled
# for this script as a whole, because other pipelines here rely on the default behaviour of ignoring
# an upstream command terminated by SIGPIPE when a downstream 'head' exits.
#
# $1 - destination path
# $2 - source path
# $3.. - sed arguments. Use '--file=' for any transformation whose replacement text contains a
#        secret, so that it is not visible in the process table.
render_config_from() {
  local destination="$1"
  local source="$2"
  shift 2
  local temporary
  local directory
  directory=$(dirname "$destination")

  # WRITABILITY, ESTABLISHED BEFORE ANYTHING IS WRITTEN. /ofbiz/config has to be writable on every start,
  # including a start with OFBIZ_SKIP_INIT: that flag skips the data initialization and nothing else, so
  # the configuration is still rendered - which is exactly the expectation an operator who mounts the
  # volume read-only does not have. Left unguarded, mkdir and mktemp abort the script through 'set -e'
  # with their own message ("mktemp: failed to create file via template ...: Read-only file system") and
  # no ERROR line anywhere, so a log filtered for this script's failures shows nothing at all and the one
  # thing that has to be said - which directory, and why it must be writable - is never said. Their
  # messages are suppressed and replaced with that, once, naming the directory and the remedy.
  if ! mkdir --parents "$directory" 2>/dev/null; then
    config_fatal "Cannot create the configuration directory $directory, which is where $destination has to be rendered. The container renders its configuration into /ofbiz/config on every start - OFBIZ_SKIP_INIT skips the data initialization and nothing else - so that directory must be writable by the container user. Mount the volume without the read-only flag, and make sure it is writable by uid 1000."
  fi
  if ! temporary=$(mktemp "$destination.XXXXXXXX" 2>/dev/null); then
    config_fatal "Cannot write to the configuration directory $directory, so $destination cannot be rendered from $source. The most likely cause is that /ofbiz/config is mounted read-only. The container renders its configuration there on every start - OFBIZ_SKIP_INIT skips the data initialization and nothing else - so the volume must be writable by the container user. Mount it without the read-only flag, and make sure it is writable by uid 1000."
  fi
  # The staging file is created beside the destination, which is on a persistent volume, and the
  # rendered output routinely contains the JWT signing key, the admin shared secret or a database
  # password. It is therefore tracked before sed writes to it so that a signal delivered mid-render
  # cannot leave a readable copy behind; after the rename the tracked path is gone and removing it
  # is a no-op.
  #
  # Registered ONCE, and before the chmod rather than after it, because the window the registration
  # closes opens with mktemp: the tracked path is what config_fatal and the signal handler remove, and
  # a second registration of the same path would add nothing to that.
  register_secret_temp_file "$temporary"
  chmod 600 "$temporary"
  if ! sed "$@" "$source" >"$temporary"; then
    rm --force "$temporary"
    config_fatal "Failed to render $destination from $source."
  fi

  # Generic residual-placeholder guard, applied to EVERY rendered configuration before it is published.
  #
  # This is the last line of defence, and for the entity configuration it is the only one. OFBiz does
  # NOT reject a malformed entityengine.xml: EntityConfig reads it through
  # UtilXml.readXmlDocument(url, true, true), whose LocalErrorHandler logs validation errors without
  # throwing and only when a local DTD was resolved, and localdtds.properties resolves none for this
  # file. A surviving placeholder therefore reaches Datasource.java as an attribute value, where
  # check-on-start is parsed as !"false".equals(value) - so an unsubstituted placeholder reads as TRUE
  # and SILENTLY re-enables startup DDL on every instance of the fleet, which is precisely what the
  # gated schema initialization exists to prevent. Failing the start up here converts that silent,
  # fleet-wide regression into one legible error naming the file and the placeholders.
  #
  # The grammar is deliberately generic - any at-sign delimited run of upper-case letters, digits and
  # underscores - rather than a list of the placeholders this script knows about, so a template that
  # carries a placeholder this script has never heard of is refused just as firmly as one whose
  # substitution was dropped by a future edit. Every file rendered here is checked, which is why none of
  # the rendered sources writes a placeholder out in prose.
  local residualTokens
  residualTokens=$(grep --only-matching --extended-regexp '@[A-Z0-9_]+@' "$temporary" | sort --unique \
    | tr '\n' ' ') || residualTokens=''
  if [ -n "$residualTokens" ]; then
    # Deleted rather than left in place: the file is complete and loadable, and /ofbiz/config takes
    # class path precedence, so leaving it behind would serve exactly the configuration being refused.
    rm --force "$temporary"
    config_fatal "Rendered $destination still contains unsubstituted placeholder(s): $residualTokens. The template $source expects a substitution this entry point does not perform, so the rendered configuration would be interpreted with those placeholders as literal attribute values."
  fi

  # Report a replacement that discards content, and only such a replacement.
  #
  # /ofbiz/config is a declared volume, so the destination may already hold a file this container did not
  # write: an operator's hand edit, or the render of an earlier start with different variables. cmp
  # decides, so the notice appears only when the bytes really differ and an ordinary restart stays quiet.
  # Nothing from either file is printed - both routinely contain a database password, the JWT signing key
  # or the admin shared secret - so the notice carries the path and the reason and nothing else.
  # Scoped to the overrides under config/, because only there does "the file already held something else"
  # mean an operator's edit was discarded; a render whose destination is a source-tree file replaces its
  # own previous output by design on every start.
  case "$destination" in
  config/*)
    if [ -f "$destination" ] && ! cmp --quiet "$destination" "$temporary"; then
      printf '%s\n' "NOTICE: $destination already existed with different content and has been replaced by the render of $source. Any edit made directly to $destination is discarded on every start: the render is always derived from the pristine source, so configure the deployment through the documented environment variables, or bind-mount a template over $source. See DOCKER.adoc." >&2
    fi
    ;;
  esac

  mv --force "$temporary" "$destination"
  unregister_secret_temp_file "$temporary"
}

###############################################################################
# Refuse to start when a JDBC driver jar has been left in /ofbiz/lib-extra.
#
# The generated start script puts lib-extra BEFORE lib on the class path, so a jar in lib-extra takes
# precedence over the driver bundled with the distribution. Earlier versions of this script
# downloaded postgresql-42.5.4.jar into lib-extra on every container start; that download has been
# removed because the driver is now bundled by dependencies.gradle at a current, patched version. A
# jar left behind in the persistent lib-extra volume by an older image would silently downgrade the
# driver, so this fails closed rather than loading it in preference to the bundled one.
guard_against_stale_jdbc_drivers() {
  # OFBIZ_SKIP_DB_DRIVER_DOWNLOAD suppressed the retired download. It is still accepted so that an
  # existing deployment manifest keeps working unchanged, but it has nothing to suppress, and
  # saying so once is better than leaving an operator to conclude from silence that it still applies.
  # The variable never holds a secret, so reporting that it was set is safe.
  if [ -n "$OFBIZ_SKIP_DB_DRIVER_DOWNLOAD" ]; then
    printf '%s\n' \
      "NOTICE: OFBIZ_SKIP_DB_DRIVER_DOWNLOAD is obsolete and has no effect. The PostgreSQL JDBC driver is bundled with this distribution and no driver is downloaded at runtime."
  fi

  local staleDrivers
  staleDrivers=$(find "$LIB_EXTRA_DIR" -maxdepth 1 -type f -name 'postgresql-*.jar' -printf '%f ' 2>/dev/null || true)
  if [ -n "$staleDrivers" ]; then
    printf 'ERROR: %s\n' \
      "Refusing to start: $LIB_EXTRA_DIR contains a PostgreSQL JDBC driver ($staleDrivers)." >&2
    printf 'ERROR: %s\n' \
      "lib-extra takes class path precedence, so this would shadow the driver bundled with this" >&2
    printf 'ERROR: %s\n' \
      "distribution. Delete the jar from the lib-extra volume; no runtime download is needed." >&2
    exit 1
  fi
}

###############################################################################
# Normalise OFBIZ_SKIP_INIT into a strict boolean, before anything reads it.
#
# Two separate concerns are handled here.
#
# The first is the parsing. Testing the flag with '[ -z "$OFBIZ_SKIP_INIT" ]' would make ANY non-empty
# value skip - including 'false', 'no' and '0', so an operator who wrote OFBIZ_SKIP_INIT=false to say
# "do initialise" would silently get the exact opposite of what the value says. require_boolean
# accepts only recognisable booleans and refuses everything else, so a typo is reported instead of
# being interpreted as consent. Its config_fatal runs inside a command substitution and therefore only
# ends the subshell, so the status is checked explicitly: without that check an invalid value would
# leave the variable empty, which reads as "not true" and would quietly select a behaviour the
# operator never asked for.
#
# The second is the SCOPE, which is applied by _main and documented here because this is where the flag
# is given its meaning. The flag now skips the DATA initialisation only - the seed/demo load, the admin
# user load and the data-load hooks. It no longer skips profile validation, secret resolution or any
# configuration rendering. Skipping those together with the data load would leave a restarted container
# - the normal case for this flag - serving traffic on whatever happened to be left in
# /ofbiz/config from an earlier image, or on the committed placeholder configuration with no key
# material at all, and did so without validating a single credential. The rendering is idempotent and
# always derives from the pristine source, so running it on this path is both safe and the only way a
# rotated secret can take effect on restart.
resolve_skip_init() {
  RESOLVED_SKIP_INIT=$(require_boolean OFBIZ_SKIP_INIT "${OFBIZ_SKIP_INIT:-false}") \
    || config_fatal "OFBIZ_SKIP_INIT must be a boolean: true, yes or 1 to skip the data initialisation, false, no or 0 to perform it."
}

###############################################################################
# Normalise the two entity-engine mode flags, before anything reads them.
#
# Called from _main ahead of any work, for two reasons. The schema-init decision changes what the whole
# container does - whether it serves traffic at all - so it must be settled and validated before any
# work is performed, not discovered halfway through rendering. And an unparseable value has to be
# refused even when the data initialisation is skipped, because a deployment that meant to request init
# mode and mistyped the value must not be handed a silently serving instance instead.
#
# require_boolean normalises onto stdout and calls config_fatal on anything that is not clearly a
# boolean. Its exit inside a command substitution only ends the subshell, so the status is checked
# explicitly here: without that, an invalid value would leave the captured variable empty, which reads
# as "not true" and would quietly select the permissive setting.
resolve_entity_engine_flags() {
  RESOLVED_SCHEMA_INIT=$(require_boolean OFBIZ_SCHEMA_INIT "${OFBIZ_SCHEMA_INIT:-false}") \
    || config_fatal "OFBIZ_SCHEMA_INIT must be a boolean: true or false."
  RESOLVED_DISTRIBUTED_CACHE_CLEAR=$(require_boolean OFBIZ_DISTRIBUTED_CACHE_CLEAR \
    "${OFBIZ_DISTRIBUTED_CACHE_CLEAR:-false}") \
    || config_fatal "OFBIZ_DISTRIBUTED_CACHE_CLEAR must be a boolean: true or false."

  # Refused rather than tolerated. The Entity Engine has no standalone DDL command - the schema is
  # applied when the data loader creates a delegator - so the data load IS the schema application.
  # Skipping it in init mode would exit 0 without having created a single table, and an operator reading
  # that exit status would conclude the schema was ready. resolve_skip_init has already run, so the
  # value compared here is the parsed boolean and not the mere presence of the variable.
  if [ "$RESOLVED_SCHEMA_INIT" = "true" ] && [ "$RESOLVED_SKIP_INIT" = "true" ]; then
    config_fatal "OFBIZ_SCHEMA_INIT=true cannot be combined with OFBIZ_SKIP_INIT=true: the data load is what applies the entity-model schema, so skipping it would exit successfully without creating a single table. Set OFBIZ_SKIP_INIT=false for the init run."
  fi

  # Checked here, with the flag, and therefore also when the data initialisation is skipped: an instance
  # started with the flag on and no transport rolls back entity writes whether or not this container is
  # the one that loaded the data. require_profile has already run, so the profile it consults is
  # resolved and validated.
  validate_distributed_cache_transport
}

###############################################################################
# Print the contents of the XML file named by $1 with every comment BODY removed, or nothing when the
# file does not exist. A comment can span lines, so this is a small state machine rather than a line-wise
# substitution.
#
# Everything in this script that inspects OFBiz XML configuration reads it through here, because the
# distribution ships most optional configuration commented out. Matching the raw text would find the
# shipped EXAMPLE and conclude that a feature is active when it is inert - which is exactly the mistake
# the cache-invalidation transport invites, since the only jms-service OFBiz ships is inside a comment.
# $1 - file to read
strip_xml_comments() {
  if [ ! -f "$1" ]; then
    return 0
  fi

  awk '
    {
      line = $0
      while (length(line) > 0) {
        if (inComment) {
          i = index(line, "-->")
          if (i == 0) { line = ""; break }
          line = substr(line, i + 3)
          inComment = 0
        } else {
          i = index(line, "<!--")
          if (i == 0) break
          printf "%s", substr(line, 1, i - 1)
          line = substr(line, i + 4)
          inComment = 1
        }
      }
      print line
    }
  ' "$1"
}

###############################################################################
# Print the number of ACTIVE jms-service elements named serviceMessenger in the file named by $1, or 0
# when the file does not exist.
# $1 - file to inspect
count_active_service_messenger() {
  if [ ! -f "$1" ]; then
    echo 0
    return 0
  fi

  strip_xml_comments "$1" \
    | grep --count --extended-regexp "<jms-service[^>]*name=\"$JMS_SERVICE_NAME\"" || true
}

###############################################################################
# Print the whole ACTIVE jms-service element named serviceMessenger from the file named by $1 as a single
# line, or nothing when the file declares no such element. Newlines are collapsed first because the
# element's attributes are conventionally spread over several lines - the shipped example spreads its
# server attributes over six - so any attribute lookup has to see the start tag as one string.
# $1 - file to inspect
active_service_messenger_element() {
  strip_xml_comments "$1" | tr '\n' ' ' | awk -v name="$JMS_SERVICE_NAME" '
    {
      pattern = "<jms-service[^>]*name=\"" name "\""
      if (match($0, pattern)) {
        rest = substr($0, RSTART)
        end = index(rest, "</jms-service>")
        if (end > 0) {
          print substr(rest, 1, end + 13)
        } else {
          print rest
        }
      }
    }
  '
}

###############################################################################
# Print the value of the XML attribute named $2 in the tag text $1, or nothing when the tag does not
# carry it. The attribute name must be preceded by whitespace, so a lookup of 'name' cannot match inside
# 'jndi-server-name' and a lookup of 'jndi-name' cannot match inside it either. Only the first
# occurrence is printed, so a malformed tag repeating an attribute cannot produce a multi-line value.
# $1 - tag text, $2 - attribute name
xml_attribute_value() {
  # The grep is wrapped so that "no such attribute" - its exit status 1 - is a normal empty result rather
  # than a pipeline failure. It has to hold even under 'pipefail', because this is read by callers that
  # legitimately ask whether an OPTIONAL attribute is present.
  printf '%s' "$1" \
    | { grep --only-matching --extended-regexp "[[:space:]]$2=\"[^\"]*\"" || true; } \
    | head --lines=1 \
    | sed --expression="s|^[[:space:]]*$2=\"||" --expression='s|"$||'
}

###############################################################################
# Print the first path in $@ that names a readable regular file, or nothing.
first_existing_file() {
  local candidate
  for candidate; do
    if [ -f "$candidate" ]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
}

###############################################################################
# Print the value of attribute $2 on the jndi-server named $1, reading the rendered override before the
# committed definitions. Nothing is printed when the server is not declared or does not carry the
# attribute - the normal case for the 'default' server, whose entire purpose is to carry no parameters so
# that JNDIContextFactory falls back to the bare InitialContext constructor and the settings come from
# jndi.properties instead.
# $1 - jndi-server name, $2 - attribute name
jndi_server_attribute() {
  local file
  file=$(first_existing_file "${JNDI_SERVERS_CANDIDATES[@]}")
  if [ -z "$file" ]; then
    return 0
  fi

  local tag
  tag=$(jndi_server_element "$file" "$1")
  if [ -n "$tag" ]; then
    xml_attribute_value "$tag" "$2"
  fi
}

###############################################################################
# Print the ACTIVE <jndi-server> start tag named $2 from the file named by $1 as a single line, or
# nothing when the file does not exist or declares no such server. Newlines are collapsed first because
# the shipped elements spread their attributes over as many as six lines, so any attribute lookup has to
# see the start tag as one string.
# $1 - file to inspect, $2 - jndi-server name
jndi_server_element() {
  if [ ! -f "$1" ]; then
    return 0
  fi

  # Both greps are wrapped so that "no such element" - exit status 1 - is a normal empty result rather
  # than a pipeline failure, which has to hold even under 'pipefail'.
  strip_xml_comments "$1" | tr '\n' ' ' \
    | { grep --only-matching --extended-regexp '<jndi-server[^>]*>' || true; } \
    | { grep --extended-regexp "[[:space:]]name=\"$2\"" || true; } \
    | head --lines=1
}

###############################################################################
# Print the number of ACTIVE <jndi-server> elements in the file named by $1, or 0 when the file does not
# exist. Used to prove that rendering the override added exactly one server and dropped none of the ones
# the distribution ships, which matters because JNDIConfigUtil reads jndiservers.xml as a flat class path
# resource: the override REPLACES the committed file rather than adding to it.
# $1 - file to inspect
count_jndi_servers() {
  if [ ! -f "$1" ]; then
    echo 0
    return 0
  fi

  strip_xml_comments "$1" | tr '\n' ' ' \
    | { grep --only-matching --extended-regexp '<jndi-server[^>]*>' || true; } \
    | wc --lines
}

###############################################################################
# Print the value of property $1 from the first jndi.properties on the resolution path, or nothing.
#
# The JDK merges every jndi.properties on the class path FIRST-ENTRY-WINS, and the generated start script
# puts /ofbiz/config first, so reading the candidates in this order reads what the JVM will read. Note
# that $1 is used as an extended regular expression, so a '.' in a property name must be escaped by the
# caller if an exact match matters.
# $1 - property name
jndi_properties_value() {
  local candidate
  local declaration
  for candidate in "${JNDI_PROPERTIES_CANDIDATES[@]}"; do
    if [ ! -f "$candidate" ]; then
      continue
    fi
    declaration=$(grep --extended-regexp "^[[:space:]]*$1=" "$candidate" | head --lines=1) || true
    if [ -n "$declaration" ]; then
      printf '%s' "${declaration#*=}"
      return 0
    fi
  done
}

###############################################################################
# Where the active transport was found, and the broker client class and provider URL it resolves
# through. Published as globals rather than returned, because a fail-fast resolver cannot report through
# a command substitution: config_fatal's exit would only end the subshell.
RESOLVED_TRANSPORT_CONFIGURATION_FILE=""
RESOLVED_TRANSPORT_SERVER_TAG=""
RESOLVED_TRANSPORT_FACTORY_CLASS=""
RESOLVED_TRANSPORT_PROVIDER_URL=""

###############################################################################
# Work out, exactly as JNDIContextFactory does, which broker client class and which provider URL the
# active serviceMessenger transport resolves through, and publish them in the RESOLVED_TRANSPORT_*
# globals. Returns non-zero, having published nothing, when no active transport is declared at all.
#
# The indirection is worth following because probing the wrong broker is worse than not probing: the
# jms-service names a jndi-server, jndiservers.xml may give that server an explicit
# context-provider-url and initial-context-factory - which is exactly what this script renders - and only
# when it does not, as with the parameterless 'default' server OFBiz ships, do the settings come from
# jndi.properties. Reading them in that order is what makes the reachability probe below evidence about
# the broker OFBiz will actually contact rather than about a host this script guessed.
resolve_authoritative_transport_settings() {
  RESOLVED_TRANSPORT_CONFIGURATION_FILE=""
  RESOLVED_TRANSPORT_SERVER_TAG=""
  RESOLVED_TRANSPORT_FACTORY_CLASS=""
  RESOLVED_TRANSPORT_PROVIDER_URL=""

  local candidate
  local element=""
  for candidate in "${SERVICE_ENGINE_CANDIDATES[@]}"; do
    element=$(active_service_messenger_element "$candidate")
    if [ -n "$element" ]; then
      RESOLVED_TRANSPORT_CONFIGURATION_FILE="$candidate"
      break
    fi
  done
  if [ -z "$RESOLVED_TRANSPORT_CONFIGURATION_FILE" ]; then
    return 1
  fi

  RESOLVED_TRANSPORT_SERVER_TAG=$(printf '%s' "$element" \
    | grep --only-matching --extended-regexp '<server[^>]*>' | head --lines=1) || true

  # The server name reaches a grep expression below, and it comes out of a file rather than from a
  # validated variable, so anything that is not a plain name falls back instead of being interpolated.
  # The fallback is the SHIPPED name, not the dedicated one this script renders: an element missing the
  # attribute is not this script's own work, and 'default' is what OFBiz would understand it to mean - a
  # server carrying no parameters, whose settings therefore come from jndi.properties.
  local serverName
  serverName=$(xml_attribute_value "$RESOLVED_TRANSPORT_SERVER_TAG" 'jndi-server-name')
  case "$serverName" in
  '' | *[!A-Za-z0-9._-]*)
    serverName="$JNDI_SERVER_DEFAULT_NAME"
    ;;
  esac

  RESOLVED_TRANSPORT_FACTORY_CLASS=$(jndi_server_attribute "$serverName" 'initial-context-factory')
  RESOLVED_TRANSPORT_PROVIDER_URL=$(jndi_server_attribute "$serverName" 'context-provider-url')
  if [ -z "$RESOLVED_TRANSPORT_FACTORY_CLASS" ]; then
    RESOLVED_TRANSPORT_FACTORY_CLASS=$(jndi_properties_value 'java\.naming\.factory\.initial')
  fi
  if [ -z "$RESOLVED_TRANSPORT_PROVIDER_URL" ]; then
    RESOLVED_TRANSPORT_PROVIDER_URL=$(jndi_properties_value 'java\.naming\.provider\.url')
  fi
}

###############################################################################
# Refuse a transport whose server element does not subscribe as well as publish.
#
# listen="true" is what makes JmsListenerFactory create a subscriber for the topic at start up. Without
# it the instance PUBLISHES its own invalidations and never RECEIVES anyone else's, so its caches drift
# away from the fleet's while every other instance stays correct - the single worst outcome available
# here, because it is invisible: nothing fails, the data is simply wrong on one instance. It is also the
# state the readiness probe reports as not-ready, so an instance in it would be held out of service
# indefinitely rather than repaired.
require_transport_listener_enabled() {
  if [ "$(xml_attribute_value "$RESOLVED_TRANSPORT_SERVER_TAG" 'listen')" != "true" ]; then
    config_fatal "The active jms-service named $JMS_SERVICE_NAME in $RESOLVED_TRANSPORT_CONFIGURATION_FILE does not declare listen=\"true\" on its server element, so this instance would publish cache invalidations without subscribing to the ones other instances publish, and its caches would silently drift. Add listen=\"true\", or let this script render the transport by setting OFBIZ_JMS_INITIAL_CONTEXT_FACTORY and OFBIZ_JMS_PROVIDER_URL. See DOCKER.adoc."
  fi
}

###############################################################################
# Refuse a transport whose JMS client class is not on the class path.
#
# dependencies.gradle bundles only the JMS API (geronimo-jms_1.1_spec); a provider's client jar is
# deployment specific, so the operator supplies it in the lib-extra volume. Without it the named factory
# class cannot be loaded and JNDIContextFactory raises NoInitialContextException at the first
# invalidation, on the path that rolls back the caller's transaction. Checking the class here converts
# that into a start up refusal naming the missing class.
#
# javap is used because it resolves a class from a wildcard class path without executing anything. If
# the image has been slimmed to a JRE it will not be present, in which case this reports that the
# evidence could not be gathered rather than inventing a pass.
require_transport_client_available() {
  local class="$RESOLVED_TRANSPORT_FACTORY_CLASS"
  if [ -z "$class" ]; then
    config_fatal "The active jms-service named $JMS_SERVICE_NAME in $RESOLVED_TRANSPORT_CONFIGURATION_FILE resolves through the jndi-server it names, but neither that server nor any jndi.properties on the class path declares an initial context factory, so there is nothing to connect to the broker with. Set OFBIZ_JMS_INITIAL_CONTEXT_FACTORY to have this script render a dedicated jndi-server, or add initial-context-factory to the jndi-server that element names in $JNDI_SERVERS_OVERRIDE. Declaring java.naming.factory.initial in jndi.properties would be found too, but it replaces the JVM-wide default that Tomcat's global naming context and the RMI service container are built from, so scope it to the jndi-server instead. See DOCKER.adoc."
  fi

  if ! command -v javap >/dev/null 2>&1; then
    printf '%s\n' "WARNING: cannot confirm that the JMS client class [$class] named by the cache-invalidation transport is on the class path, because javap is not present in this image. A missing client jar will not be detected until the first cache invalidation. Place the provider's client jar in lib-extra." >&2
    return 0
  fi

  if javap -classpath "$TRANSPORT_CLASS_PATH" "$class" >/dev/null 2>&1; then
    printf '%s\n' "Cache-invalidation transport: JMS client class [$class] resolved on the class path."
    return 0
  fi

  config_fatal "The cache-invalidation transport names the initial context factory [$class], but that class is not on the class path ($TRANSPORT_CLASS_PATH). OFBiz bundles only the JMS API, so the provider's client jar must be placed in the lib-extra volume. Without it every cache invalidation fails and rolls back the entity write that triggered it. See DOCKER.adoc."
}

###############################################################################
# Print one 'host port' line for every distinct TCP endpoint named in the provider URL $1, in the order
# they appear. A URL may name several - 'failover:(tcp://a:61616,tcp://b:61616)' - and one that names an
# in-JVM or discovery transport names none at all.
# $1 - provider URL
transport_endpoints() {
  # 'no endpoints' is a legitimate answer here - an in-JVM or discovery transport names none - so the
  # grep's exit status 1 must not become a pipeline failure, including under 'pipefail'.
  printf '%s' "$1" \
    | { grep --only-matching --extended-regexp '://(\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9._-]+):[0-9]+' || true; } \
    | awk '{
        endpoint = substr($0, 4)
        port = endpoint
        sub(/^.*:/, "", port)
        host = endpoint
        sub(/:[0-9]+$/, "", host)
        gsub(/^\[|\]$/, "", host)
        if (!seen[host " " port]++) {
          print host, port
        }
      }'
}

###############################################################################
# Attempt one bounded TCP connection to $1:$2, giving up after $3 seconds. Returns 0 when the connection
# was established, 124 when the deadline expired, and any other non-zero status when the connection was
# refused or the name could not be resolved.
#
# bash's /dev/tcp redirection is used because it is built into the shell: the runtime image contains no
# netcat, and adding one to satisfy a health probe would be a change to the base image. The host and port
# are passed as positional parameters rather than interpolated into the -c string, so a value that
# somehow reached here unvalidated still cannot become shell syntax. timeout provides the bound: a
# blackholed address otherwise hangs for the kernel's whole SYN retry budget, which is minutes.
# $1 - host, $2 - port, $3 - deadline in seconds
probe_tcp_endpoint() {
  # The single quotes are deliberate and are the point of the construction: the host and the port must be
  # expanded by the INNER shell, from its positional parameters, not interpolated into the script text by
  # this one.
  # shellcheck disable=SC2016
  timeout "$3" bash -c 'exec 3<>/dev/tcp/"$0"/"$1"' "$1" "$2" 2>/dev/null
}

###############################################################################
# Require startup evidence that the broker the transport names can actually be reached, and refuse to
# start when it cannot.
#
# This is the check that separates a configured transport from a working one. A named but unreachable
# broker fails at exactly the same place as a missing jms-service: the first cache invalidation raises,
# ServiceDispatcher rolls back the caller's transaction, and the bulk operation that triggered it aborts.
# Detecting it here, once, costs one TCP connection per endpoint and converts an intermittent data-loss
# failure into a refusal that names the endpoint.
#
# One reachable endpoint is enough: a failover URL exists precisely so that the fleet survives losing
# one broker, so demanding all of them would refuse a healthy topology. The total cost is bounded by
# construction - at most JMS_ENDPOINT_PROBE_LIMIT endpoints, each at most the deadline - and a URL naming
# more endpoints than that is refused as a configuration error rather than probed.
require_transport_reachable() {
  local url="$RESOLVED_TRANSPORT_PROVIDER_URL"
  local deadline="$RESOLVED_JMS_CONNECT_TIMEOUT"

  if [ -z "$url" ]; then
    config_fatal "The active jms-service named $JMS_SERVICE_NAME in $RESOLVED_TRANSPORT_CONFIGURATION_FILE resolves through the jndi-server it names, but neither that server nor any jndi.properties on the class path declares a provider URL, so there is no broker to connect to. Set OFBIZ_JMS_PROVIDER_URL to have this script render a dedicated jndi-server, or add context-provider-url to the jndi-server that element names in $JNDI_SERVERS_OVERRIDE. Declaring java.naming.provider.url in jndi.properties would be found too, but it replaces the JVM-wide default that Tomcat's global naming context and the RMI service container are built from, so scope it to the jndi-server instead. See DOCKER.adoc."
  fi

  local endpoints
  endpoints=$(transport_endpoints "$url")
  if [ -z "$endpoints" ]; then
    printf '%s\n' "WARNING: the cache-invalidation transport's provider URL names no host and port, so start up cannot confirm that a broker is reachable. An in-JVM or discovery transport is not a cache-coherent fleet unless every instance really does reach the same broker. Verify it independently." >&2
    return 0
  fi

  local count
  count=$(printf '%s\n' "$endpoints" | wc --lines)
  if [ "$count" -gt "$JMS_ENDPOINT_PROBE_LIMIT" ]; then
    config_fatal "The cache-invalidation transport's provider URL names $count distinct endpoints, more than the $JMS_ENDPOINT_PROBE_LIMIT this script will probe. That is a configuration error rather than a topology: reduce the list, or point the instances at a broker cluster's own address."
  fi

  local host
  local port
  local unreachable=()
  while read -r host port; do
    if [ -z "$host" ]; then
      continue
    fi
    if probe_tcp_endpoint "$host" "$port" "$deadline"; then
      printf '%s\n' "Cache-invalidation transport: broker endpoint [$host:$port] accepted a TCP connection within ${deadline}s, so this instance can reach it."
      return 0
    fi
    unreachable+=("$host:$port")
  done <<<"$endpoints"

  config_fatal "The cache-invalidation transport names the broker endpoint(s) ${unreachable[*]}, and none of them accepted a TCP connection within ${deadline}s from this instance. Every instance of the fleet must reach the broker: an unreachable one makes each cache invalidation roll back the entity write that triggered it. Check the URL, the broker, and the network path, raise OFBIZ_JMS_CONNECT_TIMEOUT if the path is merely slow, or set OFBIZ_DISTRIBUTED_CACHE_CLEAR=false to run this instance on a local cache. See DOCKER.adoc."
}

###############################################################################
# Refuse to enable distributed cache invalidation without a working message transport.
#
# The delegator flag does not provide one. All five distributed cache services in
# framework/entityext/servicedef/services.xml are declared engine="jms" location="serviceMessenger",
# yet the only jms-service of that name, in framework/service/config/serviceengine.xml, is commented
# out by default. With the flag on and no transport, getJmsServiceByName returns null and
# JmsServiceEngine.run dereferences it, throwing a NullPointerException inside
# ServiceDispatcher.runAsync, which catches Throwable and rolls back the CALLER'S transaction before
# re-throwing. EntityCacheServices only logs the failure afterwards, so it cannot prevent that
# rollback: the entity write that triggered the invalidation is rolled back too, a bulk operation such
# as the seed data load aborts, and the start up dies a long way from the cause.
#
# Four things are therefore required, in the order in which they fail earliest and most cheaply: an
# active jms-service; a server element that DECLARES it subscribes as well as publishes; a client class
# that can actually be loaded; and a broker endpoint that answers a TCP connection. Each failure is
# fatal in EVERY profile. A development-profile warning here would rest on the idea that a developer
# might want the flag on without a broker, but there is no such state: the flag with no
# transport is not a degraded fleet, it is an instance that rolls back its own writes, and a warning in
# a start up log is not a defence against that. A developer who wants a local cache sets the flag to
# false, which is also the default.
#
# What these four do not establish. They are the failures that can be detected from configuration and
# from the network before the JVM starts, and they are necessary conditions only. No JNDI context is
# created here, nothing is looked up in one, no credentials are authenticated, no subscriber or
# publisher is constructed and nothing is published, so this function cannot and does not establish
# that an invalidation reaches another instance. It also cannot: dependencies.gradle bundles only the
# JMS API, and the provider's client library is deployment specific and mounted by the operator, so
# there is no client here to perform a lookup with - which is precisely why the loadability of that
# class is checked with javap instead. Coherence is established by observing an invalidation arrive at
# a second instance, and DOCKER.adoc carries that procedure. Nothing printed by this function may be
# phrased so that an operator reading the start up log could mistake it for that proof.
validate_distributed_cache_transport() {
  if [ "$RESOLVED_DISTRIBUTED_CACHE_CLEAR" != "true" ]; then
    return 0
  fi

  # Resolved rather than merely counted, because the four checks that follow all have to interrogate the
  # SAME configuration: whichever file is authoritative on this start - the override this script rendered
  # or one the operator supplied - is what publishes RESOLVED_TRANSPORT_CONFIGURATION_FILE and the
  # settings the listener, client and reachability checks read.
  if ! resolve_authoritative_transport_settings; then
    config_fatal "OFBIZ_DISTRIBUTED_CACHE_CLEAR=true but no active jms-service named $JMS_SERVICE_NAME was found in ${SERVICE_ENGINE_CANDIDATES[*]}, so no transport carries cache invalidations. This is not a cache-coherent fleet, and the first entity write that triggers an invalidation would be rolled back. Set OFBIZ_JMS_INITIAL_CONTEXT_FACTORY and OFBIZ_JMS_PROVIDER_URL to have this script render the transport, supply $SERVICE_ENGINE_OVERRIDE yourself, or set OFBIZ_DISTRIBUTED_CACHE_CLEAR=false to run this instance on a local cache. See DOCKER.adoc."
  fi

  require_transport_listener_enabled
  require_transport_client_available
  require_transport_reachable

  printf '%s\n' "OFBIZ_DISTRIBUTED_CACHE_CLEAR=true and an active jms-service named $JMS_SERVICE_NAME was found in $RESOLVED_TRANSPORT_CONFIGURATION_FILE. It declares listen=\"true\", names a client class that resolves on the class path, and names a broker endpoint that accepted a TCP connection. These are the transport's necessary preconditions, checked from the configuration and the network; they are not evidence that an invalidation reaches another instance, which this start up does not and cannot test - no JNDI lookup, authentication, subscription or publication is performed. Every instance of the fleet must use the same physical topic, and propagation must be confirmed against a second instance. See DOCKER.adoc."
}

###############################################################################
# Validate and apply defaults to any environment variables used by this script.
# See script header for environment variable descriptions.
#
# Tracing is suspended for the whole function. Most of the defaulting below expands a password into
# a variable assignment, and a traced assignment echoes the expanded value to stderr - the container
# log - so the credentials would be published simply by resolving their defaults.
ofbiz_setup_env() {
  hide_secrets

  # OFBIZ_PROFILE is NOT resolved here. It decides whether an absent secret is fatal or is replaced by
  # a generated value, so it must be settled before any code that consults it can run - including the
  # code on the path that skips data initialisation. require_profile, called as the first statement of
  # _main, owns it: it defaults an unset value to 'dev' and reports that it did, and it refuses a value
  # that is neither profile. See require_profile for why the default is announced rather than silent.

  case "$OFBIZ_DATA_LOAD" in
  none | seed | demo) ;;
  *)
    OFBIZ_DATA_LOAD="seed"
    ;;
  esac

  OFBIZ_ADMIN_USER=${OFBIZ_ADMIN_USER:-admin}

  # The admin password is a credential, so it is resolved by a function that can refuse a value rather
  # than by a parameter default that can only supply one. In dev it still becomes the published demo
  # password; in prod it must be supplied, private and strong.
  require_admin_password

  # Database names and user names are identifiers, not credentials, so they keep their long standing
  # defaults. The three passwords deliberately have NONE: defaulting them to these same published
  # identifiers would let a deployment that forgot to supply one silently authenticate with a value
  # anybody could read out of this repository. require_database_password refuses an absent password -
  # and refuses the retired defaults outright - but only when PostgreSQL is
  # actually configured, so the embedded H2 path is unaffected.
  OFBIZ_POSTGRES_PORT=${OFBIZ_POSTGRES_PORT:-5432}
  OFBIZ_POSTGRES_OFBIZ_DB=${OFBIZ_POSTGRES_OFBIZ_DB:-ofbiz}
  OFBIZ_POSTGRES_OFBIZ_USER=${OFBIZ_POSTGRES_OFBIZ_USER:-ofbiz}

  OFBIZ_POSTGRES_OLAP_DB=${OFBIZ_POSTGRES_OLAP_DB:-ofbizolap}
  OFBIZ_POSTGRES_OLAP_USER=${OFBIZ_POSTGRES_OLAP_USER:-ofbizolap}

  OFBIZ_POSTGRES_TENANT_DB=${OFBIZ_POSTGRES_TENANT_DB:-ofbiztenant}
  OFBIZ_POSTGRES_TENANT_USER=${OFBIZ_POSTGRES_TENANT_USER:-ofbiztenant}

  # Defaulted to the values the committed datasource definitions already carry, so an operator who sets
  # neither gets exactly that pool. Range checked in
  # render_database_configuration, which is the only place they are used.
  OFBIZ_DB_POOL_MIN=${OFBIZ_DB_POOL_MIN:-$DB_POOL_MIN_DEFAULT}
  OFBIZ_DB_POOL_MAX=${OFBIZ_DB_POOL_MAX:-$DB_POOL_MAX_DEFAULT}

  # Defaulted to the values the committed content.properties already carries, so rendering that file
  # with nothing configured reproduces it exactly and the database storage path stays in effect.
  #
  # These two belong HERE, in the function _main always runs first, and not next to the database
  # rendering: content storage is selected on every start, whatever database the instance uses, whereas
  # render_database_configuration runs only for a managed datasource. Defaulting them there left a
  # zero-configuration container - the embedded H2 run this image has always supported - reaching
  # render_content_store_configuration with an unset provider and refusing to start.
  #
  # The bucket, region, endpoint and the two credentials get NO default on purpose: blank is
  # what makes UtilProperties self-default and, for the credentials, what selects the AWS default
  # credential provider chain, so a value must never be invented for them here.
  OFBIZ_CONTENT_STORE_PROVIDER=${OFBIZ_CONTENT_STORE_PROVIDER:-database}
  OFBIZ_S3_PATH_STYLE=${OFBIZ_S3_PATH_STYLE:-false}

  # The borrow wait and the idle-validation policy of the managed pools. The wait is defaulted to a value
  # short enough to be reported rather than waited out, in place of the five minute wait the engine
  # applies when the attribute is absent. Both are range checked and normalised in
  # render_database_configuration, which is the only place they are used.
  OFBIZ_DB_POOL_WAIT=${OFBIZ_DB_POOL_WAIT:-$DB_POOL_WAIT_DEFAULT}
  OFBIZ_DB_POOL_TEST_ON_BORROW=${OFBIZ_DB_POOL_TEST_ON_BORROW:-$DB_POOL_TEST_ON_BORROW_DEFAULT}

  # Fleet sizing inputs. Neither is rendered anywhere; they exist so the per-instance pool maximum can be
  # checked against the capacity the whole fleet shares. The fleet defaults to a single instance, which is
  # what an unconfigured container is, and the capacity deliberately has NO default: a guessed
  # max_connections would make the check report a conclusion about a number nobody stated.
  OFBIZ_DB_FLEET_SIZE=${OFBIZ_DB_FLEET_SIZE:-$DB_FLEET_SIZE_DEFAULT}

  # The pgJDBC network deadlines. Defaulted here and again in require_postgres_jdbc_parameters, which is
  # where they are range checked; unlike the pool settings these have driver defaults of "no limit", so
  # the value that matters is the one this script supplies rather than the one the driver would.
  OFBIZ_POSTGRES_CONNECT_TIMEOUT=${OFBIZ_POSTGRES_CONNECT_TIMEOUT:-$POSTGRES_CONNECT_TIMEOUT_DEFAULT}
  OFBIZ_POSTGRES_SOCKET_TIMEOUT=${OFBIZ_POSTGRES_SOCKET_TIMEOUT:-$POSTGRES_SOCKET_TIMEOUT_DEFAULT}
  OFBIZ_POSTGRES_LOGIN_TIMEOUT=${OFBIZ_POSTGRES_LOGIN_TIMEOUT:-$POSTGRES_LOGIN_TIMEOUT_DEFAULT}
  OFBIZ_POSTGRES_CANCEL_TIMEOUT=${OFBIZ_POSTGRES_CANCEL_TIMEOUT:-$POSTGRES_CANCEL_TIMEOUT_DEFAULT}
  OFBIZ_POSTGRES_QUERY_TIMEOUT=${OFBIZ_POSTGRES_QUERY_TIMEOUT:-$POSTGRES_QUERY_TIMEOUT_DEFAULT}
  OFBIZ_POSTGRES_TCP_KEEPALIVE=${OFBIZ_POSTGRES_TCP_KEEPALIVE:-$POSTGRES_TCP_KEEPALIVE_DEFAULT}

  OFBIZ_DISABLE_COMPONENTS=${OFBIZ_DISABLE_COMPONENTS-plugins/birt/ofbiz-component.xml}

  # Validated here rather than when the first hook runs, so a misspelled variable name is reported
  # before any work is done instead of leaving a hook silently without the value it asked for.
  require_hook_secret_allowlist

  restore_trace
}

###############################################################################
# CONTAINER STATE MARKERS
#
# A marker records that a one-off initialisation step has already been done, so a restart does not redo
# it. Until now a marker was an EMPTY file and the only question asked of it was whether it existed,
# which made it trivially forgeable: anything running as the ofbiz user - a mounted volume prepared
# elsewhere, a hook, a stale volume from a completely different database - could create
# /ofbiz/runtime/container_state/data_loaded and the container would then skip the seed load and the
# admin user load, and in schema-init mode exit 0 having created no schema at all. An operator reading
# that exit status would conclude the database was ready.
#
# A marker is now a two line file:
#   line 1  <format>|<marker name>|<deployment fingerprint>|<payload>
#   line 2  SHA-256 of line 1
# and it is believed only when all of the following hold: the file exists, has exactly two lines, its
# recorded digest matches its body, and its body matches the body this run would write - same format
# version, same marker name, same deployment fingerprint, same payload. Anything else - absent, empty,
# truncated, edited, written by an older image, or belonging to a different database - is treated as
# ABSENT, which means the work is done again. Redoing the work is always the safe direction: every step
# behind a marker is idempotent (the entity loader stores rows by primary key, the component disabling
# is a value-setting XSLT transform, the AJP edit checks first), whereas skipping it is not.
#
# What the digest does and does not achieve is worth stating plainly. It cannot stop a process that
# already runs as the ofbiz user and can read this script from computing a valid marker - no local
# secret would help, since that process could read the secret too. What it does achieve is that a
# marker is only ever believed for the exact deployment it was written for, so the realistic cases -
# a reused volume, a partially written state directory, a hand-edited or half-copied marker, an image
# upgrade that changed the meaning of a marker - all fail closed. The forgery case is closed
# differently, by not trusting markers where it matters: schema-init mode ignores them entirely and
# verifies that the schema-applying load really ran before it reports success.

###############################################################################
# SHA-256 of standard input, as lower case hex with no trailing file name.
sha256_of_stdin() {
  sha256sum | cut --delimiter=' ' --fields=1
}

###############################################################################
# Path of the in-progress sibling of a marker.
# $1 - marker path
marker_inprogress_path() {
  printf '%s.inprogress' "$1"
}

###############################################################################
# Fingerprint of the data store this container's state belongs to.
#
# Deliberately built from the STRUCTURAL identity only - which server, which port, which three
# databases and which three serving roles - and never from a password or from a mode flag. Passwords are
# excluded because rotating one must not invalidate the record of a load that did happen; the mode flags
# are excluded because the init execution and the serving instances address the same database and must
# agree about what has been loaded into it.
#
# With no managed database configured the fingerprint is over a fixed marker for the embedded H2 store,
# so the zero-configuration local path is stable and the demo image's pre-loaded state stays valid.
deployment_fingerprint() {
  printf '%s\n' \
    "store=${OFBIZ_POSTGRES_HOST:-embedded-h2}" \
    "port=${OFBIZ_POSTGRES_PORT:-}" \
    "ofbiz-db=${OFBIZ_POSTGRES_OFBIZ_DB:-}" \
    "ofbiz-user=${OFBIZ_POSTGRES_OFBIZ_USER:-}" \
    "olap-db=${OFBIZ_POSTGRES_OLAP_DB:-}" \
    "olap-user=${OFBIZ_POSTGRES_OLAP_USER:-}" \
    "tenant-db=${OFBIZ_POSTGRES_TENANT_DB:-}" \
    "tenant-user=${OFBIZ_POSTGRES_TENANT_USER:-}" \
    | sha256_of_stdin
}

###############################################################################
# The body this run would write for a marker.
# $1 - marker path, $2 - payload (opaque; never printed by any caller)
marker_body() {
  printf '%s|%s|%s|%s' "$CONTAINER_MARKER_FORMAT" "$(basename "$1")" "$(deployment_fingerprint)" "$2"
}

###############################################################################
# Write a marker atomically, with mode 0600.
# $1 - marker path, $2 - payload
write_container_marker() {
  local body digest temporary
  body=$(marker_body "$1" "$2")
  digest=$(printf '%s' "$body" | sha256_of_stdin)
  mkdir --parents "$(dirname "$1")"
  temporary=$(mktemp "$1.XXXXXXXX")
  chmod 600 "$temporary"
  printf '%s\n%s\n' "$body" "$digest" >"$temporary"
  mv --force "$temporary" "$1"
}

###############################################################################
# Declare that the work a marker records is about to start.
#
# The previous marker is REMOVED first and an in-progress sibling created, so a container killed part
# way through - the ordinary case for a long seed load - leaves state that says "this was attempted and
# did not finish" rather than state that still says "this is done". The next start then redoes it.
# $1 - marker path
begin_container_marker() {
  local inprogress
  inprogress=$(marker_inprogress_path "$1")
  mkdir --parents "$(dirname "$1")"
  rm --force "$1"
  : >"$inprogress"
  chmod 600 "$inprogress"
}

###############################################################################
# Declare that the work a marker records finished successfully.
# $1 - marker path, $2 - payload
complete_container_marker() {
  write_container_marker "$1" "$2"
  rm --force "$(marker_inprogress_path "$1")"
}

###############################################################################
# Whether the work a marker records has demonstrably been done for THIS deployment.
#
# Returns success only for a marker this run would have written itself. Every other outcome returns
# failure, and the ones that indicate something went wrong - as opposed to simply never having run -
# say so on stdout, because silently redoing a seed load is confusing and silently redoing it every
# start would be a bug worth seeing. The payload is never printed: for the admin marker it is derived
# from the admin credential.
# $1 - marker path, $2 - payload
container_marker_is_complete() {
  local markerName body digest lineCount
  markerName=$(basename "$1")

  if [ -f "$(marker_inprogress_path "$1")" ]; then
    printf '%s\n' "Container state: '$markerName' was attempted but did not finish, so that work is being performed again."
    return 1
  fi

  if [ ! -f "$1" ]; then
    return 1
  fi

  lineCount=$(wc --lines <"$1")
  if [ "$lineCount" != "2" ]; then
    printf '%s\n' "Container state: '$markerName' is not a marker this version writes (it has $lineCount lines, not 2), so it is being ignored and that work performed again."
    return 1
  fi

  body=$(sed --quiet '1p' "$1")
  digest=$(sed --quiet '2p' "$1")
  if [ "$digest" != "$(printf '%s' "$body" | sha256_of_stdin)" ]; then
    printf '%s\n' "Container state: '$markerName' does not match its own checksum, so it has been altered or was only partly written. It is being ignored and that work performed again."
    return 1
  fi

  if [ "$body" != "$(marker_body "$1" "$2")" ]; then
    printf '%s\n' "Container state: '$markerName' was written for a different deployment or for different settings, so it says nothing about this one. It is being ignored and that work performed again."
    return 1
  fi

  return 0
}

###############################################################################
# The salt mixed into the admin marker payload, created on first use.
container_admin_marker_salt() {
  if [ ! -f "$CONTAINER_ADMIN_MARKER_SALT" ]; then
    local temporary
    mkdir --parents "$CONTAINER_STATE_DIR"
    temporary=$(mktemp "$CONTAINER_ADMIN_MARKER_SALT.XXXXXXXX")
    chmod 600 "$temporary"
    tr --delete --complement A-Za-z0-9 </dev/urandom | head --bytes=32 >"$temporary"
    mv --force "$temporary" "$CONTAINER_ADMIN_MARKER_SALT"
  fi
  cat "$CONTAINER_ADMIN_MARKER_SALT"
}

###############################################################################
# Payload that binds the admin marker to the administrator credential this run would load.
#
# Rotating the user name or the password changes the payload, so the marker written for the previous
# credential no longer matches and the admin load runs again - which is the only way a rotated admin
# password can actually reach the database. Before this, the marker's mere existence skipped the load,
# so a rotated password could never take effect and nothing said it had been ignored.
#
# The two values are joined by a newline, which neither can contain (reject_unsafe_value refuses
# control characters), so no pair of values can collide into the same payload.
#
# Tracing is suspended and neither value is printed. The digest is returned on stdout and must never be
# logged by a caller.
admin_marker_payload() {
  hide_secrets
  local payload
  payload=$(printf 'admin=%s\n%s\n%s\n' \
    "$(container_admin_marker_salt)" "$OFBIZ_ADMIN_USER" "$OFBIZ_ADMIN_PASSWORD" | sha256_of_stdin)
  restore_trace
  printf 'admin-sha256=%s' "$payload"
}

###############################################################################
# Image build time only. Record the demo data load that the image build has just performed.
#
# The demo image loads the whole demo data set into the embedded H2 database during 'docker build' so
# that a container from it is immediately usable, and then has to tell the entry point not to load it
# again. Touching three empty files cannot express that, now that a marker is a checksummed record of a
# specific deployment, and it carries a second fault: a bare 'data_loaded' would suppress the load when
# the demo image is pointed at an external PostgreSQL server as well, leaving the container serving an
# empty database. Writing a real marker here binds
# the record to the embedded H2 store, so the demo image starts instantly on its own baked database and
# correctly loads a managed database it is given instead.
#
# Deliberately its own entry point rather than a build-time run of _main: _main resolves the deployment
# secrets, and in the dev profile it GENERATES the ones the operator did not supply. Running that during
# 'docker build' would bake generated key material into a public image layer, which is exactly what the
# secret externalisation work removed. Nothing here reads, resolves or generates a secret - the admin
# marker records the demo data set's own published account as a fixed token, so not even a salt is
# created - and a managed database is refused, because at build time there is none to have loaded.
write_initial_container_state() {
  if [ -n "${OFBIZ_POSTGRES_HOST:-}" ]; then
    config_fatal "--write-initial-container-state records a data load performed into the image's own embedded database at build time, so it cannot be used with OFBIZ_POSTGRES_HOST set. A managed database is loaded by a normal container start, or by an OFBIZ_SCHEMA_INIT=true run."
  fi

  complete_container_marker "$CONTAINER_DATA_LOADED" "load=demo"
  complete_container_marker "$CONTAINER_ADMIN_LOADED" "$ADMIN_MARKER_DEMO_PAYLOAD"

  printf '%s\n' "Recorded the build-time demo data load in $CONTAINER_STATE_DIR. A container started from this image will skip the data load while it uses the embedded database baked into the image, and will perform it if it is pointed at a managed database instead."
}

###############################################################################
# Create the runtime container state directory used to track which initialisation
# steps have been run for the container.
# This directory should be hosted on a volume that persists for the life of the container.
create_ofbiz_runtime_directories() {
  if [ ! -d "$CONTAINER_STATE_DIR" ]; then
    mkdir --parents "$CONTAINER_STATE_DIR"
  fi

  # Done here because this is the first point at which the state directory is known to exist and the
  # profile has been validated, and BEFORE any secret is resolved, so no code path can read a stored
  # generated value in the prod profile. A volume that was previously used for a dev container still
  # holds the generated keys from that run; in prod every secret must be supplied, so that material is
  # destroyed rather than left where an accidental relapse to dev would revive it.
  if [ "$OFBIZ_PROFILE" = 'prod' ]; then
    discard_all_generated_secrets
  fi
}

###############################################################################
# Validate OFBIZ_HOOK_SECRET_ALLOWLIST against the set of variables that are actually scrubbed.
#
# A name that is not scrubbed cannot be re-admitted, so listing one means the operator has misspelled
# a variable and the hook they were trying to supply will silently receive nothing. Refusing the list
# reports that at start up instead.
require_hook_secret_allowlist() {
  local -a allowlist=()
  IFS=', ' read -r -a allowlist <<<"${OFBIZ_HOOK_SECRET_ALLOWLIST:-}"

  local requested known matched
  for requested in "${allowlist[@]}"; do
    matched='false'
    for known in "${SECRET_ENVIRONMENT_VARIABLES[@]}"; do
      if [ "$requested" = "$known" ]; then
        matched='true'
        break
      fi
    done
    if [ "$matched" != 'true' ]; then
      config_fatal "OFBIZ_HOOK_SECRET_ALLOWLIST names '$requested', which is not one of the secret variables this script removes from a hook's environment (${SECRET_ENVIRONMENT_VARIABLES[*]}). Correct the name; as written, the hook would receive nothing."
    fi
  done
}

###############################################################################
# Decide whether a secret variable has been explicitly re-admitted to a hook's environment.
# $1 - variable name
hook_secret_is_allowed() {
  local -a allowlist=()
  IFS=', ' read -r -a allowlist <<<"${OFBIZ_HOOK_SECRET_ALLOWLIST:-}"

  local allowed
  for allowed in "${allowlist[@]}"; do
    if [ "$allowed" = "$1" ]; then
      return 0
    fi
  done
  return 1
}

###############################################################################
# Run one initialisation hook with the deployment secrets removed from its environment and with shell
# tracing suspended for the whole of its execution.
#
# Two distinct leaks are closed.
#
# Tracing. A hook is arbitrary operator-supplied code that this script cannot vet, and it runs while
# every secret is still exported. With OFBIZ_TRACE set, an executable hook inherits nothing traced -
# tracing is not exported - but a SOURCED hook runs in THIS shell, so its own commands are traced by
# this shell's 'set -x' and any expansion of a secret variable inside it is written verbatim to stderr,
# which in a container is the retained log stream. hide_secrets closes that for the hook body.
#
# Trace leakage OUT of the hook. A hook may enable tracing itself - the example hook shipped in
# docker/examples/postgres-demo/after-config-applied.d/applySolrConfig.sh literally runs 'set -x' - and
# because it is sourced, that setting survives its return and would then trace this script's own secret
# handling from the point the hook finished. 'set +x' immediately after the hook returns, before a
# single secret is restored, makes a hook unable to switch tracing on for the rest of the start up.
# restore_trace afterwards re-establishes only what the operator asked for - through OFBIZ_TRACE or
# through the shell's own xtrace flag - and nothing the hook asked for on its behalf.
#
# Environment. The secrets are unset before the hook runs and restored, with their original exported
# status, after it returns. This covers the executable case too: a child process inherits the
# environment, and 'set -x' inside a hook script - or a crash dump, or a diagnostic that prints the
# environment - would publish every value. An operator whose hook genuinely needs one of these values
# re-admits it by name through OFBIZ_HOOK_SECRET_ALLOWLIST, so the exposure is explicit and auditable
# rather than the default.
#
# The hook's exit status is preserved and returned, so a failing hook still aborts the start up under
# 'set -e' exactly as it did before - but only after the environment and the trace state have been put
# back.
# $1 - path to the hook, $2 - 'execute' or 'source'
#
# The locals below carry an 'ofbizHook' prefix on purpose. A sourced hook executes IN this function's
# scope, so an assignment inside it lands on any local of the same name; the saved values and the exit
# status must survive the hook, so they are given names a hook is not going to reuse by accident.
run_hook_scrubbed() {
  local ofbizHookPath="$1"
  local ofbizHookMode="$2"

  hide_secrets

  local -A ofbizHookSavedValues=()
  local -A ofbizHookSavedExported=()
  local -a ofbizHookGranted=()
  local ofbizHookName ofbizHookDeclaration ofbizHookAttributes
  for ofbizHookName in "${SECRET_ENVIRONMENT_VARIABLES[@]}"; do
    if hook_secret_is_allowed "$ofbizHookName"; then
      # AN ALLOWLISTED NAME IS LEFT IN PLACE - BUT 'IN PLACE' IS NOT 'IN THE HOOK'S ENVIRONMENT'.
      # capture_secret_environment demotes every secret with 'export -n' before the first fork, which
      # keeps the value in this shell and takes it out of the environment block that children inherit. A
      # SOURCED hook runs in this shell and therefore still sees it, but an EXECUTED hook is a child and
      # sees nothing - so the allowlist was a silent no-op for exactly the hook kind whose environment it
      # names, promising an explicit, auditable grant and delivering an empty variable. The export
      # attribute is restored here and removed again the moment the hook returns, so the grant is real,
      # lasts no longer than the hook, and never reaches the JVM exec'd at the end of the start up. It is
      # applied in both modes so that the two hook kinds behave identically, including for a command a
      # sourced hook itself spawns, which is what the pre-scrub behaviour gave either kind.
      if [ -n "${!ofbizHookName+set}" ]; then
        # '${name?}' is the indirect-export form: it expands to the variable NAME and fails if the loop
        # variable were somehow unset, which is also what tells a linter the indirection is deliberate.
        export "${ofbizHookName?}"
        ofbizHookGranted+=("$ofbizHookName")
      fi
      continue
    fi
    if [ -n "${!ofbizHookName+set}" ]; then
      ofbizHookSavedValues["$ofbizHookName"]="${!ofbizHookName}"
      ofbizHookSavedExported["$ofbizHookName"]='false'
      # Read the attribute flags of 'declare -p NAME', which prints 'declare <flags> NAME="value"'. Only
      # the flags word is inspected: testing the whole declaration for an 'x' would misread any value
      # that happens to contain the letter, and these values are passwords.
      ofbizHookDeclaration=$(declare -p "$ofbizHookName" 2>/dev/null || true)
      ofbizHookDeclaration=${ofbizHookDeclaration#declare }
      ofbizHookAttributes=${ofbizHookDeclaration%% *}
      case "$ofbizHookAttributes" in
      *x*) ofbizHookSavedExported["$ofbizHookName"]='true' ;;
      esac
      unset "$ofbizHookName"
    fi
  done

  local ofbizHookStatus=0
  if [ "$ofbizHookMode" = 'execute' ]; then
    "$ofbizHookPath" || ofbizHookStatus=$?
  else
    # shellcheck source=/dev/null
    . "$ofbizHookPath" || ofbizHookStatus=$?
  fi

  # Before anything is restored: a sourced hook that enabled tracing must not have it in force while
  # the secrets below come back into the environment.
  set +x

  for ofbizHookName in "${!ofbizHookSavedValues[@]}"; do
    # declare -g assigns to the global scope, which is where these variables live; the value comes from
    # an expansion, so it is never re-parsed. -x reinstates the export attribute the variable had.
    if [ "${ofbizHookSavedExported[$ofbizHookName]}" = 'true' ]; then
      declare -gx "$ofbizHookName=${ofbizHookSavedValues[$ofbizHookName]}"
    else
      declare -g "$ofbizHookName=${ofbizHookSavedValues[$ofbizHookName]}"
    fi
  done

  # The grant ends with the hook that was granted it. 'export -n' keeps the value and removes only the
  # export attribute, which is precisely the state capture_secret_environment left the variable in, so a
  # later hook with a different allowlist - and the exec'd JVM - are unaffected by this one.
  for ofbizHookName in "${ofbizHookGranted[@]}"; do
    export -n "${ofbizHookName?}"
  done

  restore_trace
  return "$ofbizHookStatus"
}

###############################################################################
# Execute the shell scripts at the paths passed to this function.
# Args:
# 1:  Name of the hook stage being executed. Used for logging.
# 2+: Variable number of paths to the shell scripts to be executed.
#     Only scripts with the .sh extension are executed.
#     Scripts will be sourced if they are not executable.
#
# Every hook runs through run_hook_scrubbed, which suspends tracing and removes the deployment secrets
# from the environment for the duration of the hook. See that function for why.
run_init_hooks() {
  local hookStage="$1"
  shift
  local filePath
  for filePath; do
    case "$filePath" in
    *.sh)
      if [ -x "$filePath" ]; then
        printf '%s: running %s\n' "$hookStage" "$filePath"
        run_hook_scrubbed "$filePath" execute
      else
        printf '%s: sourcing %s\n' "$hookStage" "$filePath"
        run_hook_scrubbed "$filePath" source
      fi
      ;;
    *)
      printf '%s: Not a script. Ignoring %s\n' "$hookStage" "$filePath"
      ;;
    esac
  done
}

###############################################################################
# Applying the entity-model schema, and confirming from the loader log and the database that this run
# applied it, both live in initialise_schema and require_schema_init_completed further down.
#
# Three responsibilities that could plausibly sit in separate helpers here are deliberately not split
# out, because each would be weaker than where it actually lives:
#
#   * invoking the loader with 'readers=none' is what initialise_schema does, and it does more with it -
#     the child is started through run_initialisation_child so a container stop is forwarded to it, its
#     log is examined for the DDL statements that were actually applied, and the result is then verified
#     against the database with the startup DDL turned off.
#   * a run-unique receipt token written into the schema_initialised marker would be a weaker form of the
#     same guarantee than SCHEMA_APPLYING_LOAD_RAN, which is set by the very lines that execute the
#     loader and cannot be supplied from outside the process at all, whereas a token in a file on the
#     runtime volume can be.
#   * the marker itself is written by write_state_record, in the same non-secret fingerprint format as
#     every other container state marker, so that a later run can report whether it is repeating the
#     same target or has been pointed at a different one. Reading its first line as a token would read
#     the format version instead.
#
# A per-helper positive postcondition IS kept below: the entity groups the 'default' delegator maps, and
# the datasource each one is mapped to, are derived from the configuration that will actually be read,
# and the loader's log has to show the delegator initialising them through those datasources before the
# schema can be called applied. That is a different question from the one the verification pass asks,
# and it catches a failure the exit status cannot: a per-helper error the delegator swallows into a
# warning.

###############################################################################
# Set to true by initialise_schema once the entity-model DDL has actually been applied. _main refuses
# to report a successful initialisation while this is false, so an init run that did no work exits
# non-zero instead of telling an orchestrator the schema is ready.
SCHEMA_INIT_APPLIED="false"

###############################################################################
# True only while a SUCCESSFUL schema-initialisation run hands the configuration volume back in serving
# mode, which is the one moment at which this script renders a serving configuration from an environment
# that legitimately holds a schema-initialisation database identity.
#
# restore_serving_mode_after_schema_init lowers RESOLVED_SCHEMA_INIT for the duration of that re-render,
# because the DDL mode and the rendered identity are both taken from that flag. resolve_database_identities
# reads the same flag to decide whether an init credential on this container is legitimate, so the lowered
# flag also made it judge the init job itself as "a serving instance holding a DDL credential" and abort -
# after the schema had already been applied and verified, which failed the job while its work had
# succeeded and left an orchestrator unable to release the fleet. Naming the epilogue explicitly keeps
# that refusal exactly as strict for every real serving start: it is scoped to this one internal
# re-render, is set only by the function performing it, and cannot be supplied from outside the process.
SCHEMA_INIT_RESTORING_SERVING_MODE="false"

###############################################################################
# The entity groups the 'default' delegator maps, and the datasource (helper) each one is mapped to, as
# one tab-separated 'group-name<TAB>datasource-name' pair per line.
#
# Derived from the configuration that will actually be read rather than hardcoded, so that the
# postcondition covers the managed localpostgres* helpers in a configured deployment and the embedded
# localh2* helpers in an unconfigured one, and keeps covering the right set if a group-map is ever
# repointed. /ofbiz/config takes class path precedence, so a rendered override is preferred over the
# committed file exactly as the Entity Engine prefers it.
#
# The delegator block is isolated first: 'default' is a prefix of 'default-no-eca', so a file-wide scan
# for group-map lines would pick up that delegator's mappings as well.
#
# The GROUP is emitted next to the helper rather than the helper alone, because the loader reports the
# two TOGETHER and the postcondition below needs the pair: given only a helper name it cannot tell a
# group the delegator never reached - which is legitimate, see schema_init_helper_verdict - from a group
# it reached through some other datasource, which is not. Either attribute order is read, because the
# Entity Engine reads the attributes of a group-map and not the order they are written in.
effective_default_delegator_group_maps() {
  local configFile="$ENTITY_ENGINE_SOURCE"
  if [ -f "$ENTITY_ENGINE_OVERRIDE" ]; then
    configFile="$ENTITY_ENGINE_OVERRIDE"
  fi
  sed --quiet '/<delegator name="default"[^-]/,/<\/delegator>/p' "$configFile" \
    | awk '/<group-map/ {
        group = ""
        helper = ""
        if (match($0, /group-name="[^"]*"/)) {
          group = substr($0, RSTART + 12, RLENGTH - 13)
        }
        if (match($0, /datasource-name="[^"]*"/)) {
          helper = substr($0, RSTART + 17, RLENGTH - 18)
        }
        if (group != "" && helper != "") {
          printf "%s\t%s\n", group, helper
        }
      }' \
    | sort --unique
}

###############################################################################
# Whether a captured schema-init log proves the entity groups the 'default' delegator maps were
# initialised through the datasources it maps them to.
#
# Prints the failure message when it does not and nothing when it does, so the caller can decide the
# verdict while the log still exists and report it once the log has been removed - the same shape as
# every other schema-init verdict here, and the reason there is exactly one removal of the log.
#
# WHAT THE ENGINE ACTUALLY REPORTS, which is what this postcondition can and cannot require.
# GenericDelegator.initializeOneGenericHelper is called once per group returned by
# ModelGroupReader.getGroupNames, which is the delegator's own default group plus the groups declared by
# the entitygroup.xml of the components that were LOADED - NOT the groups the delegator maps. For each
# such group it logs either
#   Delegator "default" initializing helper "<helper>" for entity group "<group>".
# or, when the delegator has no group-map for it,
#   Delegator "default" NOT initializing helper for entity group "<group>" because ...
# A group that IS mapped but that no loaded component declares an entity in produces NEITHER line,
# because the engine never considers it. org.apache.ofbiz.olap is exactly that case: plugins/bi is the
# only component in either repository that declares it, so a deployment without that plugin
# legitimately initialises two of the three mapped groups and has no third database object to create.
# Requiring one line per mapped datasource would fail that valid initialisation, so what is required is
# derived from what the delegator is observed to have DONE with each mapped group:
#   * initialised through the datasource the configuration maps it to - PROVEN;
#   * refused, the NOT line - FAILS, because the configuration maps that group and the delegator built
#     from that configuration disagreed, so the group's tables were never checked;
#   * initialised through a DIFFERENT datasource - FAILS, because the DDL for that group was then issued
#     against a database other than the one this execution's configuration declares;
#   * not mentioned at all - SKIPPED: no loaded entity belongs to it, so there is nothing to create.
#
# Two fail-closed guards remain, because a postcondition that cannot fail proves nothing. An empty
# mapping means the configuration could not be read at all, and nothing PROVEN means the log shows no
# initialisation of any mapped group - on the one execution that is allowed to issue DDL, both are
# refusals rather than passes.
#
# The closing quote of both names is part of the patterns on purpose: 'localpostgres' is a prefix of
# 'localpostgresolap' and 'org.apache.ofbiz' of 'org.apache.ofbiz.olap', so without it a run in which
# only the first group was ever initialised would satisfy the check for all three.
#
# Only the REFUSAL is matched against the delegator's own name. A group initialised through the mapped
# datasource has had its tables checked whichever delegator did it - 'default-no-eca' maps the same
# three groups to the same three datasources - whereas a refusal is only evidence about the delegator
# that refused, and refusing to associate a group is normal for a delegator that does not map it.
# $1 - the captured loader log
schema_init_helper_verdict() {
  local loaderLog="$1"
  local groupMaps
  local group
  local helper
  local observed
  local proven=0
  local failure=''

  groupMaps=$(effective_default_delegator_group_maps)
  if [ -z "$groupMaps" ]; then
    printf '%s' "OFBIZ_SCHEMA_INIT=true but the entity groups the 'default' delegator maps, and the datasources it maps them to, could not be determined from $ENTITY_ENGINE_OVERRIDE or $ENTITY_ENGINE_SOURCE, so it cannot be shown that the schema was applied for any of them."
    return 0
  fi

  while IFS=$'\t' read -r group helper; do
    if [ -z "$group" ] || [ -z "$helper" ]; then
      continue
    fi
    if grep --quiet --fixed-strings "initializing helper \"$helper\" for entity group \"$group\"" "$loaderLog"; then
      proven=$((proven + 1))
      continue
    fi
    if grep --quiet --fixed-strings "Delegator \"default\" NOT initializing helper for entity group \"$group\"" "$loaderLog"; then
      failure="OFBIZ_SCHEMA_INIT=true and the configuration maps the entity group '$group' to the datasource '$helper', but the delegator reports that group is not associated with it, so the tables of that group were never checked and cannot be assumed to exist. The group-maps of the 'default' delegator in the configuration this execution read are not the ones the engine built its delegator from."
      break
    fi
    observed=$(grep --only-matching "initializing helper \"[^\"]*\" for entity group \"$group\"" "$loaderLog" \
      | sed --quiet 's|.*initializing helper "\([^"]*\)".*|\1|p' \
      | sort --unique \
      | tr '\n' ' ')
    if [ -n "$observed" ]; then
      failure="OFBIZ_SCHEMA_INIT=true and the configuration maps the entity group '$group' to the datasource '$helper', but the delegator initialised that group through '${observed% }' instead, so the DDL for it was issued against a different database than this configuration declares. Do not start the fleet: the tables of that group are missing from the database this execution was pointed at."
      break
    fi
  done <<EOF
$groupMaps
EOF

  if [ -n "$failure" ]; then
    printf '%s' "$failure"
    return 0
  fi

  if [ "$proven" -eq 0 ]; then
    printf '%s' "OFBIZ_SCHEMA_INIT=true but the loader's log shows the delegator initialising none of the entity groups the 'default' delegator maps, so nothing in it shows the schema was applied for any of them. The entity engine reports a per-helper failure as a warning and does not change the loader's exit status, which is why it is checked here."
    return 0
  fi
}

###############################################################################
# Write the receipt for a completed schema initialisation.
#
# Two facts in one atomically written record: the versioned non-secret fingerprint of the target that
# was initialised, which is what lets a later run report whether it is repeating the same target or has
# been pointed at a different one, and the token of the run that wrote it, which is what stops a
# receipt left on a reused state volume from being read as evidence for a later run. The token is the
# LAST line rather than the first, because the first line is the format version every container state
# record starts with and the readers of this file compare it.
# $1 - the desired-state fingerprint of the target that was initialised
record_schema_init_receipt() {
  write_state_record "$CONTAINER_SCHEMA_INITIALISED" "$1
run-token=$SCHEMA_INIT_RUN_TOKEN"
}

###############################################################################
# The run token a schema-init receipt records, on stdout, or nothing when it records none.
#
# A record written before the token was introduced, or one truncated by an interrupted write, yields
# the empty string - which never equals this run's token, so the evidence gate refuses rather than
# accepting a receipt it cannot attribute.
# $1 - path of the receipt
schema_init_recorded_token() {
  sed --quiet 's/^run-token=//p' "$1" 2>/dev/null | tail --lines=1
}

###############################################################################
# If required, load data into OFBiz.
#
# Two things decide whether the load runs.
#
# In SCHEMA-INIT mode the marker is not consulted at all. The Entity Engine has no standalone DDL
# command - the schema is applied when the data loader creates a delegator - so this load IS the schema
# application, and a marker that skipped it would produce a job that exits 0 having created nothing.
# A pre-created or stale marker therefore cannot suppress the one piece of work the mode exists for, and
# SCHEMA_APPLYING_LOAD_RAN records that the load really happened for _main to verify before it reports
# success.
#
# Otherwise the marker decides, but only when it is a marker this run would itself have written for this
# deployment: see the CONTAINER STATE MARKERS block for what that means and why an untrusted marker
# leads to the work being redone rather than skipped.
load_data() {
  local dataLoadPayload="load=$OFBIZ_DATA_LOAD"
  local schemaInit="${RESOLVED_SCHEMA_INIT:-false}"

  # The marker may only suppress the load OUTSIDE schema-init mode, so the mode is tested FIRST and
  # short-circuits before the marker is even read. In init mode the load IS the schema application,
  # and the marker lives on a volume that survives being repointed at a fresh managed database, so a
  # marker must never be able to skip the one piece of work the mode exists for.
  if [ "$schemaInit" != "true" ] && container_marker_is_complete "$CONTAINER_DATA_LOADED" "$dataLoadPayload"; then
    return 0
  elif [ "$schemaInit" = "true" ]; then
    printf '%s\n' "OFBIZ_SCHEMA_INIT=true: the entity-model schema is applied by the data load, so the load runs regardless of any container state marker."
  fi

  begin_container_marker "$CONTAINER_DATA_LOADED"

  run_init_hooks before-data-load /docker-entrypoint-hooks/before-data-load.d/*

  case "$OFBIZ_DATA_LOAD" in
  none)
    # No business data to load - but in schema-init mode there is still something to DO. The Entity
    # Engine has no standalone DDL command: the schema is applied when a delegator is created, which the
    # data loader does before it reads anything. 'readers=none' names a reader that no component
    # declares, so EntityDataLoader resolves it to an empty URL list and no reader data is loaded, while
    # the delegator - and therefore the check-on-start/add-missing-on-start DDL - still happens.
    #
    # It is not, however, a run that writes nothing: EntityDataLoadContainer upserts one Component row
    # per loaded component - name and root location - before it resolves any reader, so the identity this
    # execution connects as needs INSERT and UPDATE on the entity group that holds Component, which is
    # org.apache.ofbiz.tenant, and not DDL rights alone. That write is what "0 rows changed" does not
    # count, and a failure in it is logged by the engine as an error and does not fail the load.
    if [ "${RESOLVED_SCHEMA_INIT:-false}" = "true" ]; then
      run_initialisation_child /ofbiz/bin/ofbiz --load-data readers=none
      SCHEMA_APPLYING_LOAD_RAN="true"
    fi
    ;;

  seed)
    run_initialisation_child /ofbiz/bin/ofbiz --load-data readers=seed,seed-initial
    SCHEMA_APPLYING_LOAD_RAN="true"
    ;;

  demo)
    run_initialisation_child /ofbiz/bin/ofbiz --load-data
    SCHEMA_APPLYING_LOAD_RAN="true"
    # The demo data brings its own admin user, so the marker records exactly that - the demo account,
    # not a credential this script applied. Because that payload can never equal the digest of a real
    # credential, load_admin_user still runs and applies whatever OFBIZ_ADMIN_PASSWORD asks for, and the
    # marker then converges on the digest of the applied credential. The previous code touched the
    # marker unconditionally, so a supplied admin password was silently discarded whenever demo data was
    # loaded and the account kept the password published in this repository.
    complete_container_marker "$CONTAINER_ADMIN_LOADED" "$ADMIN_MARKER_DEMO_PAYLOAD"
    ;;
  esac

  # Load any additional data files provided. The directory is tested with a quoted capture of a
  # 'find ... -print -quit' so the result cannot be word split or glob expanded: the previous
  # unquoted '[ -z $(find ...) ]' expanded to zero or several words and made '[' itself fail on a
  # path containing a space (ShellCheck SC2046). The sense of the test is also corrected - the old
  # form ran the load when the directory was EMPTY.
  local additionalDataEntry
  additionalDataEntry=$(find /docker-entrypoint-hooks/additional-data.d/ -mindepth 1 -print -quit 2>/dev/null || true)
  if [ -n "$additionalDataEntry" ]; then
    run_initialisation_child /ofbiz/bin/ofbiz --load-data dir=/docker-entrypoint-hooks/additional-data.d
  fi

  # Committed only after the after-data-load hooks have SUCCEEDED, for the same reason as
  # config_applied: completing the marker first meant a failing hook was never retried, because the
  # next start saw the marker and skipped the whole block. 'set -e' aborts the start up on a failing
  # hook, so the retry re-runs the load - which the Entity Engine data loader applies as upserts - and
  # then the hook.
  run_init_hooks after-data-load /docker-entrypoint-hooks/after-data-load.d/*

  complete_container_marker "$CONTAINER_DATA_LOADED" "$dataLoadPayload"
}

###############################################################################
# How many times a schema-init log carries one of the two database-check lines.
#
# Separated out because the count is the invariant that links the two passes, and because grep's exit
# status is 1 for "no matches", which 'set -e' would otherwise treat as a failure of this script.
#
# $1 the log file to read
# $2 the check line to count, one of the two SCHEMA_INIT_*_SIGNATURE constants
schema_init_check_line_count() {
  local count
  count=$(grep --count --fixed-strings "$2" "$1") || count=0
  printf '%s' "$count"
}

###############################################################################
# The first of a list of signatures that appears in a log, or nothing if none of them does.
#
# Reports the signature it matched rather than a boolean so the caller can name the exact engine message
# in its error output: an operator reading "the init log reports 'Could not create table ['" can act on
# it, whereas "the schema is incomplete" only tells them to start guessing.
#
# $1 the log file to read
# $@ the signatures to look for, in the order they should be reported
schema_init_first_signature_in() {
  local log="$1"
  shift
  local signature

  for signature in "$@"; do
    if grep --quiet --fixed-strings "$signature" "$log"; then
      printf '%s' "$signature"
      return 0
    fi
  done
}

###############################################################################
# The verdict on the APPLYING pass: an explanation of why it failed, or nothing if it succeeded.
#
# A pure function of its arguments and the log file - it reads, it prints, and it changes nothing - so
# it can be, and is, exercised against synthetic logs by the build's schema-init gating tests. That
# matters more here than anywhere else in this script, because the one case it has to catch is the one
# that cannot be produced on demand: a database that accepted some of the DDL and rejected the rest.
#
# $1 the exit status of the data-load child
# $2 the log file the child wrote
schema_init_apply_verdict() {
  local status="$1"
  local log="$2"
  local signature

  if [ "$status" -ne 0 ]; then
    printf '%s' "Schema initialisation failed: '/ofbiz/bin/ofbiz --load-data readers=none' exited $status. The schema has NOT been applied - do not start the fleet. The data-load log above states the cause."
    return 0
  fi

  # Positive evidence that the DDL path actually ran with the flags this mode renders. Without it a
  # change that rendered the run-mode flags into an init execution would pass every other check here
  # and still create nothing.
  if ! grep --quiet --fixed-strings "$SCHEMA_INIT_DDL_SIGNATURE" "$log"; then
    printf '%s' "Schema initialisation did not run the startup database check, so no DDL was issued. The rendered $ENTITY_ENGINE_OVERRIDE must declare check-on-start=\"true\" and add-missing-on-start=\"true\" for this execution. The schema has NOT been applied - do not start the fleet."
    return 0
  fi

  # Negative evidence, part one: the check ran and then gave up before looking at any entity.
  signature=$(schema_init_first_signature_in "$log" "${SCHEMA_INIT_ABORT_SIGNATURES[@]}")
  if [ -n "$signature" ]; then
    printf '%s' "Schema initialisation reached the database check but it aborted: the data-load log above reports '$signature'. No schema was applied - do not start the fleet. Verify OFBIZ_POSTGRES_HOST, OFBIZ_POSTGRES_PORT, the three database names, the three user names and the three passwords, and that each user may create objects in its own database."
    return 0
  fi

  # Negative evidence, part two: the check ran to the end but an individual statement failed and was
  # logged and skipped, which leaves a PARTIAL schema behind an exit status of 0.
  signature=$(schema_init_first_signature_in "$log" "${SCHEMA_INIT_DDL_FAILURE_SIGNATURES[@]}")
  if [ -n "$signature" ]; then
    printf '%s' "Schema initialisation issued the DDL but at least one statement failed: the data-load log above reports '$signature'. The engine logs such a failure and continues, so the schema is PARTIAL - do not start the fleet. Fix the cause, then re-run this init job; the DDL is additive, so re-running it is safe and will complete what is missing."
    return 0
  fi
}

###############################################################################
# The verdict on the VERIFYING pass: an explanation of why it failed, or nothing if it succeeded.
#
# Pure, for the same reason and in the same way as schema_init_apply_verdict.
#
# The order of the checks is the order in which a wrong answer would be most misleading. The count is
# compared BEFORE the residual signatures are looked for, because a pass that checked fewer entity
# groups than it should have would report no residuals for the groups it skipped, and "no residuals" is
# precisely what this function otherwise treats as success.
#
# $1 the exit status of the verification child
# $2 the log file the child wrote
# $3 how many database-check lines the applying pass produced
schema_init_verification_verdict() {
  local status="$1"
  local log="$2"
  local expectedChecks="$3"
  local signature
  local observedChecks

  if [ "$status" -ne 0 ]; then
    printf '%s' "Schema verification failed: the verification pass of '/ofbiz/bin/ofbiz --load-data readers=none' exited $status. The schema has been applied but NOT verified - do not start the fleet. The log above states the cause."
    return 0
  fi

  signature=$(schema_init_first_signature_in "$log" "${SCHEMA_INIT_ABORT_SIGNATURES[@]}")
  if [ -n "$signature" ]; then
    printf '%s' "Schema verification reached the database check but it aborted: the log above reports '$signature'. The schema has been applied but NOT verified - do not start the fleet."
    return 0
  fi

  # Guarded rather than assumed. The caller derives this from the applying pass, which cannot have
  # succeeded with zero check lines, so a zero here would mean the two passes were wired together
  # incorrectly - and a zero-versus-zero comparison would silently pass.
  if [ "$expectedChecks" -lt 1 ]; then
    printf '%s' "Schema verification could not run: the applying pass reported $expectedChecks database checks, so there is nothing to verify against. The schema has NOT been verified - do not start the fleet."
    return 0
  fi

  # Compared against the applying pass, never against a fixed number. It is tempting to require one
  # check per managed datasource - there are three - but the engine checks an entity GROUP, and only
  # those groups to which the loaded components actually assign an entity. Observed against a live
  # PostgreSQL 16: the delegator initialised "localpostgres" for org.apache.ofbiz and
  # "localpostgrestenant" for org.apache.ofbiz.tenant and never touched org.apache.ofbiz.olap, whose
  # entities live in a plugin this image does not load, so a correct initialisation logged TWO lines and
  # a hard-coded three would have failed every one of them. The groups are also initialised
  # concurrently, on different OFBiz-batch threads, so their order carries no meaning either. The count
  # the applying pass produced is the only trustworthy expectation.
  observedChecks=$(schema_init_check_line_count "$log" "$SCHEMA_INIT_VERIFY_SIGNATURE")
  if [ "$observedChecks" -ne "$expectedChecks" ]; then
    printf '%s' "Schema verification checked $observedChecks entity groups but the applying pass applied DDL to $expectedChecks. Every group that was written to has to be re-read, so this pass proves nothing about the difference. The schema has NOT been verified - do not start the fleet."
    return 0
  fi

  signature=$(schema_init_first_signature_in "$log" "${SCHEMA_INIT_RESIDUAL_SIGNATURES[@]}")
  if [ -n "$signature" ]; then
    printf '%s' "Schema verification found the schema INCOMPLETE: with the startup DDL disabled the engine still reports '$signature' in the log above, so the applying pass did not create everything the entity model declares. Do not start the fleet. The log names every entity and field concerned; fix the cause - most often a database user without rights to create every object - and re-run this init job, which is additive and safe to repeat."
    return 0
  fi
}

###############################################################################
# Restore the entity engine override that verify_schema_completeness displaced.
#
# $1 the tracked backup of the override, or an empty string if there was no override to begin with, in
#    which case the verification artifact is removed so the class path falls back to ofbiz.jar exactly
#    as it did before this function ran
restore_entity_engine_override() {
  local backup="$1"

  if [ -n "$backup" ]; then
    # 'mv' rather than 'cp': it is atomic, it preserves the mode 0600 the backup was created with, and
    # it consumes the tracked path so that the EXIT handler has nothing left to remove.
    mv --force "$backup" "$ENTITY_ENGINE_OVERRIDE"
  else
    rm --force "$ENTITY_ENGINE_OVERRIDE"
  fi
}

###############################################################################
# Re-read the database with the startup DDL disabled and prove nothing is still missing.
#
# This is the second half of Objective 4's gate, and it is what makes the init job's exit status mean
# what the fleet roll-out depends on it meaning. The applying pass can only ever report what the engine
# chose to log; this pass asks the database itself. It runs the identical '--load-data readers=none'
# invocation against a configuration in which every add-missing-on-start is false, so DatabaseUtil
# compares the entity model against the live catalogue and issues no DDL at all - it cannot repair what
# it finds, only report it. A clean pass is therefore a statement about the database, not about a log.
#
# The cost is a second JVM start up, which for a one-shot init job that gates a fleet roll-out is the
# right trade: the alternative is a fleet started against a half-created schema.
#
# The verdict is published through a global instead of stdout on purpose. The child's output has to
# reach the container log, and a function whose result is captured in a command substitution cannot
# print anything else.
#
# $1 how many database-check lines the applying pass produced
SCHEMA_VERIFICATION_FAILURE=""
verify_schema_completeness() {
  local expectedChecks="$1"
  local basis="$ENTITY_ENGINE_SOURCE"
  local backup=''
  local verificationLog
  local verificationStatus=0

  SCHEMA_VERIFICATION_FAILURE=""

  # The pass has to verify the very configuration the applying pass used, whatever produced it: the
  # managed render, the embedded cache-clear render, or - when neither applies and the committed
  # configuration inside ofbiz.jar is in effect - the identical copy of that file the distribution ships
  # on disk. Taking a copy first keeps the basis immutable while the render writes over the override,
  # and gives restore_entity_engine_override something to put back.
  if [ -f "$ENTITY_ENGINE_OVERRIDE" ]; then
    backup=$(mktemp "$ENTITY_ENGINE_OVERRIDE.XXXXXXXX")
    # The override routinely holds all three database passwords, so the copy is tracked before anything
    # is written into it and created mode 0600, exactly as render_config_from does for its own staging
    # file: a signal delivered mid-verification must not leave a readable copy behind.
    register_secret_temp_file "$backup"
    chmod 600 "$backup"
    cat "$ENTITY_ENGINE_OVERRIDE" >"$backup"
    basis="$backup"
  fi

  # Only add-missing-on-start is rewritten. check-on-start is deliberately left exactly as the applying
  # pass had it, because that is what keeps the two passes comparable: forcing it true everywhere would
  # make this pass visit datasources the applying pass never touched, and the count invariant in
  # schema_init_verification_verdict would then be comparing two different sets of entity groups. Every
  # datasource the active delegator uses necessarily already carries check-on-start="true" - the
  # applying pass could not have produced its database-check line otherwise.
  render_config_from "$ENTITY_ENGINE_OVERRIDE" "$basis" \
    --expression='s|add-missing-on-start="true"|add-missing-on-start="false"|g'

  # Structural proof, before the child runs, that this pass can neither create anything nor skip the
  # check. Checked on the rendered artifact rather than on the substitution for the same reason the
  # managed render is: it is the file the engine will actually read.
  if grep --quiet 'add-missing-on-start="true"' "$ENTITY_ENGINE_OVERRIDE"; then
    SCHEMA_VERIFICATION_FAILURE="The schema verification configuration still enables add-missing-on-start, so the pass would create objects instead of reporting them and could not prove anything. The schema has NOT been verified - do not start the fleet."
  elif ! grep --quiet 'check-on-start="true"' "$ENTITY_ENGINE_OVERRIDE"; then
    SCHEMA_VERIFICATION_FAILURE="The schema verification configuration enables check-on-start on no datasource, so the pass would read nothing. The schema has NOT been verified - do not start the fleet."
  else
    printf '%s\n' "Verifying the applied schema. This pass re-reads the database catalogue with the startup DDL disabled: it creates nothing, and it fails if the entity model declares any table or column the database does not have."

    # Redirected to a file and replayed for the same reasons as the applying pass: a pipeline would
    # hide the child's exit status behind tee's, and a subshell would put ACTIVE_CHILD_PID out of the
    # termination handler's reach. Mode 0600 because the engine echoes its datasource configuration.
    verificationLog=$(mktemp)
    chmod 600 "$verificationLog"
    run_initialisation_child /ofbiz/bin/ofbiz --load-data readers=none >"$verificationLog" 2>&1 \
      || verificationStatus=$?
    cat "$verificationLog"
    SCHEMA_VERIFICATION_FAILURE=$(schema_init_verification_verdict "$verificationStatus" "$verificationLog" "$expectedChecks")
    rm --force "$verificationLog"
  fi

  # Unconditional, and before the caller acts on the verdict: the displaced override must go back
  # whether this pass passed, failed or found the configuration unusable, so that no execution path can
  # leave the DDL-disabled verification artifact installed as the instance's configuration.
  restore_entity_engine_override "$backup"
}

###############################################################################
# Apply the entity model's schema to the configured database, then return so that _main can exit.
#
# This is the one-shot init job of Objective 4, and it is deliberately a branch of its own rather than a
# flag threaded through the ordinary data load. Attaching it to load_data coupled it to that function's
# data_loaded marker, with two consequences that both defeated the objective:
#
#   * On a REUSED state volume the marker already existed, so load_data returned immediately, no DDL was
#     issued, and _main still printed "schema initialisation complete" and exited 0. An operator - or a
#     deployment pipeline gating the fleet roll-out on that exit status - concluded the schema was ready
#     when nothing at all had run. Nothing here consults a marker, so that outcome is now unreachable.
#   * On a FRESH volume the default OFBIZ_DATA_LOAD=seed took the seed branch instead, so the init job
#     loaded seed data and created an administrative user. A schema initialiser must create structure,
#     not populate a database or provision a login.
#
# How the DDL is applied: the Entity Engine has no standalone DDL command. The schema is created when a
# delegator is instantiated, from the check-on-start / add-missing-on-start flags that
# render_database_configuration has just rendered as true for this execution only. The data loader
# instantiates a delegator before it reads anything, and 'readers=none' names a reader that no component
# declares, so EntityDataLoader resolves it to an empty URL list: no reader data is loaded, while the
# delegator - and therefore the DDL - still happens. This is why the initialiser is a --load-data
# invocation that loads no business data, and why the reader name must not be changed to one that exists.
# The run is not free of DML: the loader upserts one Component row per loaded component before it
# resolves a reader, which the engine's "0 rows changed" report does not count.
#
# The DDL is additive: it creates missing tables and columns and never drops. Re-running the init job is
# therefore safe, which is what makes it correct to apply it unconditionally instead of guessing from
# state whether it is needed.
#
# TWO PASSES, and the second one is not optional. The applying pass runs with the startup DDL enabled and
# is judged from its exit status and its log; the verifying pass runs the identical invocation against a
# configuration in which add-missing-on-start is false, so the engine compares the entity model against
# the live catalogue and can only report, never repair. Only when the verifying pass reports nothing
# missing is this target recorded as initialised.
#
# The reason is that the applying pass cannot be trusted on its own. DatabaseUtil logs a DDL statement
# that failed and then continues with the next entity, so a run in which one table could not be created
# finishes, exits 0, prints no abort message, and leaves a database with some tables and not others.
# Judging that run by its log means enumerating every message the engine might emit - an enumeration
# that is only as complete as the last person to audit it.
# The verifying pass is not an enumeration: it asks the database what is missing.
initialise_schema() {
  local fingerprint
  local previous=''

  fingerprint=$(database_desired_state_fingerprint "$(resolve_desired_database_mode)")

  if [ -s "$CONTAINER_SCHEMA_INITIALISED" ] \
    && [ "$(head --lines=1 "$CONTAINER_SCHEMA_INITIALISED")" = "$DESIRED_STATE_RECORD_VERSION" ]; then
    # The run token is stripped before the comparison. It is unique to the execution that wrote it, so
    # leaving it in would make every record differ from the fingerprint being applied and turn "already
    # initialised this database" into "pointed at a different one" on every repeat run.
    previous=$(sed '/^run-token=/d' "$CONTAINER_SCHEMA_INITIALISED")
  fi

  # Reported, never acted on. The distinction the operator needs is between "this container has already
  # initialised this same database" and "this container previously initialised a DIFFERENT one", because
  # the second is usually a mistake in the environment the init job was given - and it is invisible if
  # the only output is a success message.
  if [ -n "$previous" ] && [ "$previous" = "$fingerprint" ]; then
    printf '%s\n' "This container has already initialised this database once. Re-applying the schema, which is additive and never drops anything, so the operation is safe to repeat."
  elif [ -n "$previous" ]; then
    printf '%s\n' "This container previously initialised a different target. Changed since that run: $(changed_fingerprint_fields "$previous" "$fingerprint"). Verify the environment if that was not intended."
  fi

  # An explicitly supplied data-load selection is ignored by design, and saying so is the point: a
  # supplied variable that is silently dropped looks exactly like one that was honoured. The init job
  # never loads data, so 'OFBIZ_SCHEMA_INIT=true OFBIZ_DATA_LOAD=demo' must not quietly become a demo
  # data load, and must not quietly appear to be one either.
  case "$OFBIZ_DATA_LOAD" in
  none) ;;
  *)
    printf '%s\n' "WARNING: OFBIZ_SCHEMA_INIT=true applies the schema only. OFBIZ_DATA_LOAD=$OFBIZ_DATA_LOAD is IGNORED for this execution, and no administrative user is created. Load data with a separate start that does not set OFBIZ_SCHEMA_INIT." >&2
    ;;
  esac

  printf '%s\n' "Applying the entity model schema. This execution has the startup DDL enabled; the serving fleet does not."

  # The child's output is captured as well as shown, because its EXIT STATUS ALONE IS NOT SUFFICIENT.
  # A data load with no reader reports "Finished the data load with 0 rows changed" and exits 0 even
  # when the startup database check that precedes it failed outright - a wrong password, an unreachable
  # host or a user without rights produces 'Unable to establish a connection with the database' followed
  # by 'Could not get table name information from the database, aborting.', and the loader still exits 0
  # because it had nothing to load. Observed exactly that against a live PostgreSQL with a bad password:
  # not one table was created and the container reported success. Since the entire value of this mode is
  # that its exit status means "the schema is ready", the log the engine produced has to be examined -
  # and then, because a log can only report what the engine chose to write, checked against the database.
  #
  # Redirected to a file rather than piped, deliberately: a pipeline would make the shell see tee's
  # status instead of the child's, and running the child in a subshell to restore pipefail would put
  # ACTIVE_CHILD_PID out of the termination handler's reach, which is what lets a stop signal reach the
  # data-load JVM. The output is replayed immediately afterwards, so it still reaches the container log.
  #
  # The log lives in the container's own /tmp, not in the state volume, so that a termination signal
  # during the load cannot leave a stray file behind for the next container that mounts the volume.
  local loaderLog
  loaderLog=$(mktemp)
  chmod 600 "$loaderLog"
  local loaderStatus=0
  run_initialisation_child /ofbiz/bin/ofbiz --load-data readers=none >"$loaderLog" 2>&1 || loaderStatus=$?
  cat "$loaderLog"

  # Every verdict is decided while the log still exists and reported after it has been removed, so that
  # there is exactly one removal and no path can abort with the file still on disk. The count is taken
  # here for the same reason: it is the applying pass's contribution to the verification that follows,
  # and it cannot be recovered once the log is gone.
  local appliedChecks
  appliedChecks=$(schema_init_check_line_count "$loaderLog" "$SCHEMA_INIT_DDL_SIGNATURE")
  local loaderFailure
  loaderFailure=$(schema_init_apply_verdict "$loaderStatus" "$loaderLog")
  # The PER-GROUP positive postcondition, decided here for the same reason as the two above: it needs
  # the log. What it establishes is ROUTING - that every entity group the configuration maps, and that
  # this deployment's loaded components put an entity in, was reached by the delegator through the
  # datasource the configuration maps it to. Nothing else here can show that: the loader exits 0 whether
  # a group went to the wrong database or was dropped from the delegator altogether, and the verification
  # pass that follows asks its question through the same delegator, so it would ask it of the wrong
  # database just as willingly. Whether each group's DDL then SUCCEEDED is a different question, and one
  # this line cannot answer - GenericDelegator logs the initialisation before it enters the try block
  # whose 'catch (GenericEntityException e) { Debug.logWarning(...) }' swallows a failed checkDataSource.
  # That failure is caught by the DDL failure signatures above and, conclusively, by the verification
  # pass below.
  local helperFailure
  helperFailure=$(schema_init_helper_verdict "$loaderLog")
  rm --force "$loaderLog"
  if [ -n "$loaderFailure" ]; then
    config_fatal "$loaderFailure"
  fi
  if [ -n "$helperFailure" ]; then
    config_fatal "$helperFailure"
  fi

  # THE APPLYING PASS PASSING IS NOT THE SAME AS THE SCHEMA BEING COMPLETE, which is the whole reason
  # this second pass exists. Everything decided above was decided by reading what the engine chose to
  # log, and DatabaseUtil logs a failed DDL statement and then moves on to the next entity - so an
  # enumeration of failure messages can only ever be as complete as the last time somebody audited it.
  # The verification pass replaces that with a question to the database: with the startup DDL disabled,
  # does the engine still report anything missing? Only a clean answer to that question is allowed to
  # record this target as initialised.
  verify_schema_completeness "$appliedChecks"
  if [ -n "$SCHEMA_VERIFICATION_FAILURE" ]; then
    config_fatal "$SCHEMA_VERIFICATION_FAILURE"
  fi

  # The one-shot mode's final self-check reads this. The schema was applied by a child that ran to
  # completion and the result was then verified against the database, so _main is allowed to report
  # success; require_schema_init_completed refuses to exit 0 without it.
  SCHEMA_APPLYING_LOAD_RAN="true"

  # A second, independent record of the same fact, read by the init-mode exit epilogue in _main rather
  # than by the evidence gate. Two variables rather than one because they are consulted at different
  # points by different checks, and neither can be set from outside this process.
  SCHEMA_INIT_APPLIED="true"

  # Written only now - after the schema has been applied AND independently verified - and atomically,
  # so an interrupted, failed or incomplete initialisation leaves no record claiming the schema of this
  # target was applied. The record carries this run's token as well as the fingerprint, so a receipt a
  # previous container left on the same state volume cannot be read as evidence for this run.
  record_schema_init_receipt "$fingerprint"

  printf '%s\n' "Schema applied and verified against the database. Recorded in $CONTAINER_SCHEMA_INITIALISED."
}


###############################################################################
# Create and load the password hash for the admin user.
#
# The marker is bound to the credential this run would load, so changing the admin user name or the
# admin password makes the previous marker stop matching and the load run again. Without that, a
# rotated admin password could never reach the database: the marker existed, so the load was skipped,
# and the account kept the old password with no indication that anything had been ignored.
#
# The load itself is idempotent - it stores one UserLogin row by primary key - so redoing it whenever
# there is any doubt about the marker costs one loader run and never corrupts anything.
load_admin_user() {
  local adminPayload
  adminPayload=$(admin_marker_payload)

  if container_marker_is_complete "$CONTAINER_ADMIN_LOADED" "$adminPayload"; then
    return 0
  fi

  # ADVISORY ONLY, and deliberately so: nothing below this block changes, and neither does the exit
  # status. The administrative user cannot be created without seed data -
  # framework/resources/templates/AdminUserLoginData.xml puts the new login in SecurityGroup 'SUPER',
  # which is seed data - so on a database that has never been seeded the loader child aborts on a
  # referential integrity violation naming USER_SECGRP_GRP and the start exits non-zero. This prints the
  # explanation before that happens.
  #
  # Whether the database really lacks the seed data cannot be decided here - answering it would mean
  # querying the database, a JVM start this script has no reason to spend - so the message is worded as
  # the conditional it is and names the database this container is actually pointed at, which is what
  # tells the two cases apart: a fresh embedded database is certainly empty, whereas a managed one may
  # already have been seeded by the one-shot schema-init job or by another instance of the fleet.
  #
  # Suppressed as soon as there is evidence that the seed data exists: SCHEMA_APPLYING_LOAD_RAN for a
  # load performed by THIS run, and the admin_loaded marker for every earlier start on this state volume,
  # which exists only after an administrative-user load COMPLETED and therefore only on a seeded
  # database. The data_loaded marker cannot serve as that evidence, because load_data completes it with
  # 'load=none' on this very path before this function is reached.
  if [ "$OFBIZ_DATA_LOAD" = 'none' ] && [ "$SCHEMA_APPLYING_LOAD_RAN" != 'true' ] && [ ! -f "$CONTAINER_ADMIN_LOADED" ]; then
    local advisoryDatabase
    case "$(resolve_desired_database_mode)" in
    managed)
      advisoryDatabase="The managed database at $OFBIZ_POSTGRES_HOST may already have been seeded - by the one-shot OFBIZ_SCHEMA_INIT job followed by a seed load, by another instance of the fleet, or by a restore - and if it has then the load below simply succeeds and this warning can be disregarded."
      ;;
    *)
      advisoryDatabase="No managed database is configured, so this container uses the embedded database on the same runtime volume as that marker: on a fresh volume it is certainly empty, and this start will fail."
      ;;
    esac
    printf '%s\n' "WARNING: OFBIZ_DATA_LOAD=none, so this start loaded no data, and no earlier start on this state volume completed an administrative-user load either ($CONTAINER_ADMIN_LOADED is absent), so nothing here shows that the database has ever been seeded. The administrative user is loaded next and it needs the seed data to be there already: the login is placed in SecurityGroup 'SUPER', which is seed data, so against a database that has never been seeded the loader fails with a referential integrity violation on USER_SECGRP_GRP and this start exits non-zero. $advisoryDatabase There are two supported ways to avoid the failure. Set OFBIZ_DATA_LOAD=seed so that this start loads the seed data itself. Or set OFBIZ_SKIP_INIT=true when the database was populated elsewhere, which skips both the data load and this administrative user. See DOCKER.adoc." >&2
  fi

  begin_container_marker "$CONTAINER_ADMIN_LOADED"

  # The admin password, its salt and the resulting hash are secrets. Tracing is suspended for the
  # whole block so no trace line can publish them to the container log, the data file and the sed
  # program are created with mode 0600, and every secret is passed through a shell builtin or a
  # file rather than as a command argument so none of them appears in the process table.
  hide_secrets

  reject_unsafe_value OFBIZ_ADMIN_USER "$OFBIZ_ADMIN_USER"
  reject_unsafe_value OFBIZ_ADMIN_PASSWORD "$OFBIZ_ADMIN_PASSWORD"

  TMPFILE=$(mktemp)
  chmod 600 "$TMPFILE"
  register_secret_temp_file "$TMPFILE"
  SED_SCRIPT=$(mktemp)
  chmod 600 "$SED_SCRIPT"
  register_secret_temp_file "$SED_SCRIPT"

  # Concatenate a random salt and the admin password. The salt is generated by a function that
  # verifies both pipeline stage statuses and the exact length and alphabet of its output, so a
  # failed or truncated /dev/urandom read aborts the start up instead of producing a weak hash.
  generate_password_salt
  SALT="$RESOLVED_PASSWORD_SALT"
  RESOLVED_PASSWORD_SALT=""
  SALT_AND_PASSWORD="${SALT}${OFBIZ_ADMIN_PASSWORD}"

  # Take a SHA-1 hash of the combined salt and password and strip off any additional output form the sha1sum utility.
  # '%s' is used as the printf format so a password containing '%' or a backslash is hashed
  # literally instead of being reinterpreted as a format directive.
  SHA1SUM_ASCII_HEX=$(printf '%s' "$SALT_AND_PASSWORD" | sha1sum | cut --delimiter=' ' --fields=1 --zero-terminated | tr --delete '\000')

  # Convert the ASCII Hex representation of the hash to raw bytes by inserting escape sequences and running
  # through the printf command. Encode the result as URL base 64 and remove padding.
  # This printf deliberately uses its argument as the format string: the argument is the generated
  # '\xNN' escape sequence, which is exactly what needs to be interpreted.
  SHA1SUM_ESCAPED_STRING=$(printf '%s' "$SHA1SUM_ASCII_HEX" | sed -e 's/\(..\)\.\?/\\x\1/g')
  SHA1SUM_BASE64=$(printf "$SHA1SUM_ESCAPED_STRING" | basenc --base64url --wrap=0 | tr --delete '=')

  # Concatenate the hash type, salt and hash as the encoded password value.
  ENCODED_PASSWORD_HASH="\$SHA\$${SALT}\$${SHA1SUM_BASE64}"

  # Populate the login data template. The sed program is written to a mode 0600 file rather than
  # passed on the command line, so neither the user name nor the password hash appears in the
  # process table. Both values are XML escaped, because they land inside a double quoted XML
  # attribute, and then escaped for the sed replacement grammar, so neither can alter the program.
  # The 'currentPassword=".*"' pattern is deliberately greedy, exactly as before, so that the
  # template's trailing requirePasswordChange attribute is replaced along with the password.
  {
    printf 's|@userLoginId@|%s|g\n' "$(sed_escape_replacement "$(xml_escape_value "$OFBIZ_ADMIN_USER")")"
    printf 's|currentPassword=".*"|currentPassword="%s"|g\n' "$(sed_escape_replacement "$(xml_escape_value "$ENCODED_PASSWORD_HASH")")"
  } >"$SED_SCRIPT"
  sed --file="$SED_SCRIPT" framework/resources/templates/AdminUserLoginData.xml >"$TMPFILE"
  rm --force "$SED_SCRIPT"

  restore_trace

  run_initialisation_child /ofbiz/bin/ofbiz --load-data "file=$TMPFILE"

  # Removes both the populated data file and the already deleted sed program, and forgets them so a
  # later validation failure does not try to remove them again.
  discard_secret_temp_files

  complete_container_marker "$CONTAINER_ADMIN_LOADED" "$adminPayload"
}

###############################################################################
# Modify the given ofbiz-component configuration XML file to set the root
# component's 'enabled' attribute to false.
# $1 - Path to the XML file to be modified.
disable_component() {
  XML_FILE="/ofbiz/$1"
  if [ -f "$XML_FILE" ]; then
    TMPFILE=$(mktemp)
    xsltproc /ofbiz/disable-component.xslt "$XML_FILE" > "$TMPFILE"
    # Presence-checked: the transformed document is compared with the original and the file is replaced
    # only when the transform actually changed something. The edit was always idempotent in effect -
    # the stylesheet SETS enabled="false" rather than inserting anything, so applying it twice yields
    # the same document - but the one-shot block this runs in is now retried when an
    # after-config-applied hook fails, and a rewrite that reports itself as a change every time makes
    # the retry indistinguishable from a first run in the log and needlessly rewrites a file OFBiz may
    # already have open.
    if cmp --silent "$TMPFILE" "$XML_FILE"; then
      rm --force "$TMPFILE"
      printf 'Component already disabled, leaving it unchanged: %s\n' "$XML_FILE"
      return 0
    fi
    mv "$TMPFILE" "$XML_FILE"
  else
    echo "Cannot find ofbiz-component configuration file. Not disabling component: $XML_FILE"
  fi
}

###############################################################################
# Modify the given ofbiz-component configuration XML files to set their root
# components' 'enabled' attribute to false.
#
# The list is split with 'read -a' into a quoted array rather than by leaving a variable unquoted in
# 'set -- $list'. Unquoted expansion applies word splitting AND pathname expansion, so a path
# containing a space produced two component paths out of one, and a path containing '*', '?' or '['
# was replaced by whatever files happened to match it in /ofbiz - which silently disabled components
# the operator never named, or lost the entry altogether when nothing matched. Splitting on ',' alone
# and disabling globbing for the split keeps every element exactly as it was supplied.
# $1 - Comma separated list of paths to configuration XML files to be modified.
disable_components() {
  local commaSeparatedPaths="$1"

  # Remove spaces after commas
  commaSeparatedPaths="${commaSeparatedPaths//, /,}"

  if [ -z "$commaSeparatedPaths" ]; then
    return 0
  fi

  # Split on commas only. 'set -f' suppresses pathname expansion for the duration of the split, and
  # the elements are read into an array so each one stays a single literal word.
  local componentPaths=()
  local globbingWasEnabled='false'
  case "$-" in
  *f*) ;;
  *) globbingWasEnabled='true' ;;
  esac
  set -f
  IFS=',' read -r -a componentPaths <<<"$commaSeparatedPaths"
  if [ "$globbingWasEnabled" = 'true' ]; then
    set +f
  fi

  local componentPath
  for componentPath in "${componentPaths[@]}"; do
    if [ -n "$componentPath" ]; then
      disable_component "$componentPath"
    fi
  done
}

###############################################################################
# Render security.properties into /ofbiz/config with the runtime signing keys, and the allowed
# host header, substituted.
#
# The login and JWT signing keys used to be generated into the source tree while the distribution
# was being built, which baked a live signing key into the distribution tarball and into a layer of
# every published image, where anyone able to pull the image could read it. The build no longer
# generates them; they are resolved from the environment on each start and written only here, into a
# mode 0600 file on a container-local volume.
#
# The sed program is written to a mode 0600 temporary file rather than passed on the command line, so
# a key never appears in the process table, and tracing is suspended for the whole function so it
# never appears in the container log either. The secrets are resolved BEFORE the sed program is
# opened for writing, because resolve_secret reports a generated value on stdout and that report must
# reach the container log rather than the sed program.
#
# All the substitutions share a single render: render_config_from always starts from the pristine
# source file, so a second render would discard the first one's substitutions.
render_security_configuration() {
  hide_secrets

  local loginKey jwtKey

  # Pre-provisioned key material. On the OFBIZ_SKIP_INIT path the deployment is allowed to have
  # provisioned the signing keys into /ofbiz/config on an earlier start, or from another container, and
  # to supply neither variable to this one. The declared values are then read back and re-rendered
  # unchanged, which keeps this renderer unconditional - OFBIZ_HOST and the rest of the file are still
  # reconstructed from the pristine source - instead of resolve_secret refusing to start a prod
  # container over a secret that is already present and in use.
  # require_preprovisioned_runtime_configuration is what proves the values really are usable; this only
  # decides where they come from.
  if [ "${RESOLVED_SKIP_INIT:-}" = "true" ] && [ -z "$OFBIZ_LOGIN_SECRET_KEY" ] \
    && [ -z "$OFBIZ_JWT_TOKEN_KEY" ] \
    && config_declares_property "$SECURITY_PROPERTIES_OVERRIDE" 'login\.secret_key_string' \
    && config_declares_property "$SECURITY_PROPERTIES_OVERRIDE" 'security\.token\.key'; then
    printf '%s\n' "OFBIZ_SKIP_INIT is set and no signing key was supplied: keeping the keys already provisioned in $SECURITY_PROPERTIES_OVERRIDE."
    loginKey=$(declared_property_value "$SECURITY_PROPERTIES_OVERRIDE" 'login\.secret_key_string')
    jwtKey=$(declared_property_value "$SECURITY_PROPERTIES_OVERRIDE" 'security\.token\.key')
  else
    resolve_secret OFBIZ_LOGIN_SECRET_KEY "$OFBIZ_LOGIN_SECRET_KEY" "$SIGNING_KEY_MIN_LENGTH" ''
    loginKey="$RESOLVED_SECRET"
    resolve_secret OFBIZ_JWT_TOKEN_KEY "$OFBIZ_JWT_TOKEN_KEY" "$SIGNING_KEY_MIN_LENGTH" ''
    jwtKey="$RESOLVED_SECRET"
    RESOLVED_SECRET=""
  fi

  # Refused before rendering: java.util.Properties discards leading blanks, so a key that begins with
  # one arrives at the application shorter than the value validated above - a 64 character check passed
  # by a 61 character key.
  reject_leading_whitespace OFBIZ_LOGIN_SECRET_KEY "$loginKey"
  reject_leading_whitespace OFBIZ_JWT_TOKEN_KEY "$jwtKey"

  # Two keys with two different jobs. login.secret_key_string names an EntityKeyStore entry that
  # encrypts the temporary password of the forgot-password flow; security.token.key is the HMAC512 key
  # that signs and verifies JSON Web Tokens. Reusing one value for both makes anyone who can obtain a
  # token-signing key - the value that travels with every issued token and is handled by far more code -
  # able to decrypt stored password material as well, so the reuse is refused rather than merely
  # discouraged. Generated values are drawn independently and never collide.
  if [ "$loginKey" = "$jwtKey" ]; then
    config_fatal "OFBIZ_LOGIN_SECRET_KEY and OFBIZ_JWT_TOKEN_KEY must be different values. They protect different things - stored forgot-password material and JWT signatures - and sharing one value means a compromise of the token key is also a compromise of the stored material."
  fi

  local sedScript
  sedScript=$(mktemp)
  chmod 600 "$sedScript"
  register_secret_temp_file "$sedScript"
  {
    if [ -n "$OFBIZ_HOST" ]; then
      reject_unsafe_value OFBIZ_HOST "$OFBIZ_HOST"
      reject_leading_whitespace OFBIZ_HOST "$OFBIZ_HOST"
      printf 's|^host-headers-allowed=.*|host-headers-allowed=%s|\n' \
        "$(sed_escape_replacement "$(properties_escape_value "$OFBIZ_HOST")")"
    fi
    printf 's|^login\\.secret_key_string=.*|login.secret_key_string=%s|\n' \
      "$(sed_escape_replacement "$(properties_escape_value "$loginKey")")"
    printf 's|^security\\.token\\.key=.*|security.token.key=%s|\n' \
      "$(sed_escape_replacement "$(properties_escape_value "$jwtKey")")"
  } >"$sedScript"

  render_config_from "$SECURITY_PROPERTIES_OVERRIDE" "$SECURITY_PROPERTIES_SOURCE" \
    --file="$sedScript"
  discard_secret_temp_files

  # Fails closed if the blank anchors are ever removed from the source file, because a silently
  # unsubstituted key leaves JWT creation throwing and forgot-password decryption broken.
  require_rendered_declaration "$SECURITY_PROPERTIES_OVERRIDE" 'login\.secret_key_string' \
    "$SECURITY_PROPERTIES_SOURCE"
  require_rendered_declaration "$SECURITY_PROPERTIES_OVERRIDE" 'security\.token\.key' \
    "$SECURITY_PROPERTIES_SOURCE"

  # And fails closed if the rendered line would not READ BACK as the key that was validated, which is
  # the half a presence check cannot see.
  require_rendered_property_value config/security.properties 'login\.secret_key_string' \
    OFBIZ_LOGIN_SECRET_KEY "$loginKey"
  require_rendered_property_value config/security.properties 'security\.token\.key' \
    OFBIZ_JWT_TOKEN_KEY "$jwtKey"
  if [ -n "$OFBIZ_HOST" ]; then
    require_rendered_property_value config/security.properties 'host-headers-allowed' \
      OFBIZ_HOST "$OFBIZ_HOST"
  fi

  restore_trace
}

###############################################################################
# Render the package qualified start.properties override into /ofbiz/config with the admin
# shared secret substituted.
#
# Config.java reads this file through the class loader as
# org/apache/ofbiz/base/start/start.properties, and the generated start script puts /ofbiz/config
# FIRST on the class path, so the copy written here shadows the one shipped in the distribution. A
# flat config/start.properties is NOT an override and is deliberately not written.
#
# Writing the key into a mode 0600 file is the whole point of this function: passing it as
# -Dofbiz.admin.key would publish it in the process table and in /proc/<pid>/cmdline to every process
# sharing the container's PID namespace, and to anything that captures the command line.
#
# The shipped file declares the key as a commented anchor so that no credential lives in the source
# tree; the substitution below uncomments it and gives it the resolved value. The '#*' in the anchor
# matches both that commented form and the active form a previous start of the same container left
# behind, and the shipped file declares the property exactly once, so each render produces exactly one
# active declaration whether it is the first or a rotation.
#
# BOTH destinations are written, from the one resolved value:
#
#   1. ADMIN_KEY_OVERRIDE - the package qualified copy under /ofbiz/config, which is what the JVM
#      reads. This is the functional injection.
#   2. START_PROPERTIES_SOURCE - the copy in the source tree, which is what docker/send_ofbiz_stop_signal.sh
#      reads. That helper resolves start.properties from a candidate list in class path precedence
#      order and takes the FIRST READABLE one, so it normally finds (1); it falls back to (2) when (1)
#      is unreadable to it, and it is also what a "docker exec" shutdown finds when /ofbiz/config is
#      mounted in a way the calling context cannot read. Writing only (1) leaves that fallback holding
#      the commented anchor, which the helper reports as "the property is not declared" and answers with
#      Config.java's "NA" default - a value AdminServerContainer compares with String.equals and always
#      rejects, so "docker stop" would degrade from a clean shutdown to a SIGKILL after the timeout.
#      Graceful shutdown is existing behaviour, so preserving it is a functional-parity requirement.
#
# Both writes go through render_config_from, so each is staged in a tracked mode 0600 file beside its
# destination and moved into place with a single rename: no reader ever sees a half written file, and a
# signal delivered mid-render cannot leave a readable copy of the key behind. Both results are then
# read back and verified, because a silently unsubstituted anchor in EITHER file reintroduces exactly
# the shutdown failure described above.
#
# PERSISTENCE RISK, stated explicitly. Destination (2) is in the container's writable layer rather than
# in a declared volume, so it does not outlive the container - but for as long as the container exists
# the admin shared secret is present in a file that "docker diff" reports as changed, that "docker cp"
# can read, and that "docker commit" would capture into a new image layer. Committing or exporting a
# running OFBiz container therefore publishes the key, and any image built that way must be treated as
# compromised. The file is left mode 0600 (render_config_from stages with that mode and the rename
# preserves it) so that only the runtime user can read it, which is the strongest guarantee available
# without giving up the graceful shutdown the write exists to preserve.
render_admin_key_configuration() {
  hide_secrets

  local adminKey

  # Pre-provisioned admin shared secret, for the reason set out on render_security_configuration.
  if [ "${RESOLVED_SKIP_INIT:-}" = "true" ] && [ -z "$OFBIZ_ADMIN_KEY" ] \
    && config_declares_property "$ADMIN_KEY_OVERRIDE" 'ofbiz\.admin\.key'; then
    printf '%s\n' "OFBIZ_SKIP_INIT is set and OFBIZ_ADMIN_KEY was not supplied: keeping the shared secret already provisioned in $ADMIN_KEY_OVERRIDE."
    adminKey=$(declared_property_value "$ADMIN_KEY_OVERRIDE" 'ofbiz\.admin\.key')
  else
    resolve_secret OFBIZ_ADMIN_KEY "$OFBIZ_ADMIN_KEY" "$ADMIN_KEY_MIN_LENGTH" "$ADMIN_KEY_FORBIDDEN"
    adminKey="$RESOLVED_SECRET"
    RESOLVED_SECRET=""
  fi

  # Config.java reads this through java.util.Properties, which discards leading blanks: a key beginning
  # with a space would be truncated on the way in, and AdminServerContainer would then compare requests
  # against a shared secret the operator cannot reproduce - no shutdown would ever succeed.
  reject_leading_whitespace OFBIZ_ADMIN_KEY "$adminKey"

  # One sed program, used for both destinations, so the two files cannot end up carrying different
  # values: the escaping is performed once and the same bytes are substituted into each.
  local sedScript
  sedScript=$(mktemp)
  chmod 600 "$sedScript"
  register_secret_temp_file "$sedScript"
  printf 's|^#*ofbiz\\.admin\\.key=.*|ofbiz.admin.key=%s|\n' \
    "$(sed_escape_replacement "$(properties_escape_value "$adminKey")")" >"$sedScript"

  render_config_from "$ADMIN_KEY_OVERRIDE" "$START_PROPERTIES_SOURCE" --file="$sedScript"

  # Rendered from itself: render_config_from reads the source, stages the result in a separate file and
  # renames it over the destination, so a source that is also the destination is read completely before
  # it is replaced. Done after (1) so that the override is always rendered from the shipped anchor.
  render_config_from "$START_PROPERTIES_SOURCE" "$START_PROPERTIES_SOURCE" --file="$sedScript"

  discard_secret_temp_files

  require_rendered_declaration "$ADMIN_KEY_OVERRIDE" 'ofbiz\.admin\.key' "$START_PROPERTIES_SOURCE"
  require_rendered_property_value "$ADMIN_KEY_OVERRIDE" 'ofbiz\.admin\.key' OFBIZ_ADMIN_KEY "$adminKey"
  # The source-tree copy is verified too. It is the file bin/ofbiz reads when a shutdown is
  # requested, so a failed substitution there would leave the container unable to stop gracefully.
  # Unlike the override it is never discarded on failure - it is the anchor the next start renders
  # from, and it is a distribution file rather than something this script owns.
  require_rendered_declaration "$START_PROPERTIES_SOURCE" 'ofbiz\.admin\.key' "$START_PROPERTIES_SOURCE"
  require_rendered_property_value "$START_PROPERTIES_SOURCE" 'ofbiz\.admin\.key' OFBIZ_ADMIN_KEY "$adminKey"

  restore_trace
}

###############################################################################
# Write one anchored sed expression that replaces the value of a property line, to the sed program on
# stdout. The property name is already a basic regular expression with its dots escaped; the value is
# escaped first for the properties grammar and then for the sed replacement grammar, in that order,
# because the properties escaping introduces backslashes that the sed escaping must then protect.
# Kept out of the callers so that a value is never interpolated into a traced command line.
# $1 - property name as a basic regular expression, $2 - raw value
write_property_substitution() {
  # The replacement half must be the LITERAL property name, so the backslashes that make the search
  # half a regular expression are stripped with a parameter expansion. Deriving the two halves from one
  # argument is deliberate: written out separately they could drift, and a replacement naming a
  # different property than the one matched would rename the property rather than set its value.
  local plainName="${1//\\/}"
  printf 's|^%s=.*|%s=%s|\n' "$1" "$plainName" \
    "$(sed_escape_replacement "$(properties_escape_value "$2")")"
}

###############################################################################
# Remove a rendered configuration file that one of the checks below has just declared untrustworthy -
# but only when it is one of this script's own rendered OVERRIDES.
#
# For an override the removal is part of the check rather than tidiness, for the reason set out on
# require_rendered_property_value: every override this script writes lands under config/, which takes
# class path precedence over the distribution, so an artefact that failed validation must not be left
# behind on a persistent volume for a later start to read in preference to the committed defaults.
#
# A file OUTSIDE config/ is deliberately left alone. The only one verified here is the committed
# start.properties, which is rendered from itself so that bin/ofbiz can authenticate a shutdown request
# against the same shared secret the server was started with. That file is also the anchor every one of
# these renders is built from: deleting it would destroy the anchor, so the next start would abort on a
# missing source file instead of on the real problem, and recovering would mean restoring a distribution
# file rather than correcting the environment. Nothing reads the rejected value in the meantime, because
# every caller invokes config_fatal immediately afterwards and the start never proceeds.
# $1 - rendered file
discard_untrustworthy_render() {
  case "$1" in
    # rm's own stderr is discarded and its status ignored on purpose. Every caller invokes config_fatal
    # immediately afterwards, and that message - which names the file, the check that failed and the
    # consequence - is the one the operator has to see. 'set -e' is in force, so an rm that cannot unlink
    # the file (a read-only config mount, a directory owned by another uid) would end the script HERE,
    # replacing that message with a bare, unprefixed "rm: cannot remove ...: Permission denied". The start
    # still fails either way; this keeps it failing with the diagnostic that was written for it.
    config/*) rm --force "$1" 2>/dev/null || true ;;
  esac
}

###############################################################################
# Refuse a verification whose subject has VANISHED, and name the one thing that realistically does it.
#
# Every check below reads a file this start has just rendered, so its absence is not a configuration
# error - it is a concurrent one. Two containers started at the same moment against the same
# /ofbiz/config volume render the same paths, and a start that rejects its own render deletes that
# override, which removes the other start's file in between its own check and its own read.
#
# Unguarded, both failure modes are undiagnosable: 'sed' has no file to read and aborts the script
# through 'set -e' with its own message and no ERROR line at all, while 'grep' returns 2, which the
# callers treat as "the property is not declared" and reports an anchor missing from a distribution file
# that is perfectly intact. Both are replaced by this one message, which names the file, the cause and
# the remedy.
# $1 - the rendered file a verification is about to read
require_rendered_file_present() {
  if [ ! -f "$1" ]; then
    config_fatal "$1 disappeared between being rendered and being verified. Its content is not the problem: the most likely cause is a second container starting at the same time against the same /ofbiz/config volume, because a start that rejects its own render deletes that override - and with a shared volume it deletes this one. Start one container at a time against a shared configuration volume, or give each instance its own; then start again."
  fi
}

###############################################################################
# Verify that a rendered configuration file really declares a property, so that a source file whose
# anchor has been renamed or deleted cannot silently produce a configuration with the property
# missing - which for these secrets means falling back to a published default or to no key at all.
# grep is used in quiet mode, so the value is never echoed.
#
# A rejected override is DISCARDED before aborting, for the reason set out on
# require_rendered_property_value: an override this function has just declared untrustworthy must not
# be left behind in /ofbiz/config, which takes class path precedence over the distribution. A verified
# source-tree file is left in place - see discard_untrustworthy_render.
# $1 - rendered file, $2 - property name as a basic regular expression, $3 - source file
require_rendered_declaration() {
  require_rendered_file_present "$1"
  if ! grep --quiet "^$2=." "$1"; then
    discard_untrustworthy_render "$1"
    config_fatal "Rendered $1 does not declare a value for the property matched by '$2'. The anchor for it is missing from $3."
  fi
}

###############################################################################
# Verify that a rendered property file yields EXACTLY the value that was intended.
#
# require_rendered_declaration proves a value is present; this proves it is the right one. Validation
# happens on the shell's copy of a value, but the application reads whatever java.util.Properties makes
# of the rendered line, and the two can differ: Properties discards blanks between the '=' and the first
# non-blank character, interprets backslash escapes, and takes the LAST declaration of a duplicated key.
# A value that survives validation but arrives shortened - or that is shadowed by a leftover duplicate
# anchor - is precisely the failure the length and entropy checks exist to prevent, and it is invisible
# until the first JWT or the first admin request fails.
#
# The comparison reproduces Properties' reading for the encoding this script actually emits: the text
# after the first '=' with each doubled backslash collapsed back to one. Leading whitespace is refused
# before rendering (reject_leading_whitespace) and control characters are refused everywhere, so no
# other Properties transformation can apply. Exactly one declaration is required, so a duplicate cannot
# decide the effective value.
#
# A rejected override is DISCARDED before aborting, and that removal is part of the check rather than
# tidiness. The file was written atomically from a pristine source, so it is complete and loadable, and
# it sits in /ofbiz/config, which takes class path precedence over the distribution - the very property
# that makes an override work. Two of the renderers that call this function (the object-store settings
# and the content URL prefix) render nothing at all when their variables are unset, so a rejected
# artefact left on a persistent /ofbiz/config volume would be picked up verbatim by the next start that
# supplies no configuration: the deployment would then run on a value this function has already refused,
# and on a credential the operator believes was never accepted. Removing it means the only outcomes are
# a verified override or the committed defaults. The removal is scoped to config/ by
# discard_untrustworthy_render, so verifying the committed start.properties here cannot delete it.
#
# Tracing is suspended and neither the intended nor the rendered value is ever printed.
# $1 - rendered file
# $2 - property name as a basic regular expression
# $3 - variable name the value came from, named in a failure message
# $4 - intended value (never printed)
require_rendered_property_value() {
  hide_secrets
  local renderedFile="$1"
  local property="$2"
  local name="$3"
  local intended="$4"
  local declarations rendered

  # Presence and readability are established before the count is taken, and they are kept apart from each
  # other and from a wrong number of declarations, because all three are different mistakes with different
  # fixes and the message has to say which one happened. A file that is ABSENT is a concurrent start, not a
  # configuration error - require_rendered_file_present owns that diagnostic and is checked first, because
  # '-r' is false for a missing file too and would otherwise report a permission problem for it. A file
  # that is present but UNREADABLE is a permission or mount problem with the config directory.
  # Both have to be settled before the count, because 'grep --count' prints nothing at all when it cannot
  # open the file and the '|| true' that keeps 'set -e' from ending the script here also swallows that
  # distinction: the count interpolated into the message below was then EMPTY, producing "declares the
  # property matched by 'x'  times", which names no number and does not say that the file could not be
  # read. The count is defaulted to 0 as well, so that the message can never again be published with a
  # hole in it.
  require_rendered_file_present "$renderedFile"
  if [ ! -r "$renderedFile" ]; then
    discard_untrustworthy_render "$renderedFile"
    config_fatal "Rendered $renderedFile cannot be read back, so the value supplied in $name cannot be verified. The file was written by this start up, so this is a permission or mount problem with the config directory rather than a configuration error."
  fi

  declarations=$(grep --count "^$property=" "$renderedFile" || true)
  declarations=${declarations:-0}
  if [ "$declarations" != "1" ]; then
    discard_untrustworthy_render "$renderedFile"
    config_fatal "Rendered $renderedFile declares the property matched by '$property' $declarations times; exactly one declaration is required, because java.util.Properties would silently use the last one."
  fi

  # Guarded as well as pre-checked, because the check above and this read are two separate operations and
  # a concurrent start can delete the file in between them. sed's own failure would otherwise end the
  # script with exit status 2 and no ERROR line, which is the one outcome an operator cannot act on.
  if ! rendered=$(sed --quiet "s|^$property=||p" "$renderedFile" 2>/dev/null); then
    require_rendered_file_present "$renderedFile"
    config_fatal "Could not read back the rendered $renderedFile to verify the value supplied in $name."
  fi
  rendered=$(printf '%s' "$rendered" | sed 's,\\\\,\\,g')
  if [ "$rendered" != "$intended" ]; then
    discard_untrustworthy_render "$renderedFile"
    # Worded for every value this function verifies, not only the secrets: it also checks the object-store
    # bucket, region, endpoint, addressing style and key prefix, and the content URL prefix. The risk
    # common to all of them is that the application would run on a value other than the one that was
    # validated. Neither value is printed, here or anywhere else in this function.
    config_fatal "Rendered $renderedFile does not read back the value supplied in $name. java.util.Properties would give the application a different value from the one that was validated, so the deployment would run on a value this start up never checked - a wrong secret, or a wrong storage or URL setting, depending on which variable this is."
  fi
  restore_trace
}

###############################################################################
# Verify that a rendered configuration file DECLARES a property, whose value may legitimately be empty.
#
# The companion of require_rendered_declaration, for the properties whose blank value is meaningful
# rather than a failure: the S3 bucket, region, endpoint and credentials are all blank in an
# unconfigured container, and UtilProperties self-defaults a blank value, which for the credentials is
# what selects the AWS default provider chain. What must still be impossible is the property going
# MISSING - if the anchor were renamed in the source, the substitution would silently do nothing and
# the rendered override would shadow the shipped file with the property absent altogether.
# grep is used in quiet mode, so no value is ever echoed.
# $1 - rendered file, $2 - property name as a basic regular expression, $3 - source file
require_rendered_property() {
  require_rendered_file_present "$1"
  if ! grep --quiet "^$2=" "$1"; then
    config_fatal "Rendered $1 does not declare the property matched by '$2' at all. The anchor for it has been renamed or removed from $3, so any value supplied for it was discarded."
  fi
}

###############################################################################
# Rewrite one self-contained <property name="NAME" value="..."/> line of the catalina component
# descriptor, in the FIRST container block only, and verify the result.
#
# The catalina descriptor is not a property file: OFBiz locates component descriptors at their fixed
# path in the source tree, so the /ofbiz/config class path override that carries the other rendered
# files does not apply here and the value has to be substituted in place. Two consequences are handled
# explicitly. First, the substitution replaces an attribute value rather than inserting a line, so it
# is idempotent and safe to run on every container start, which is what lets a changed setting take
# effect on restart. Second, the descriptor declares a second, identical property inside the
# "catalina-container-test" container further down the file; the 0,/re/ address restricts the edit to
# the first match so the test container is never touched.
# $1 - property name, $2 - already validated value
rewrite_catalina_property() {
  local name="$1"
  local value="$2"
  local descriptor="$CATALINA_COMPONENT_DESCRIPTOR"
  local escapedValue
  escapedValue=$(sed_escape_replacement "$(xml_escape_value "$value")")

  if ! grep --quiet "<property name=\"$name\" value=\"[^\"]*\"/>" "$descriptor"; then
    config_fatal "$descriptor does not declare the single-line property '$name'. The anchor the entry point substitutes has been removed or reformatted."
  fi
  sed --in-place \
    "0,\|<property name=\"$name\" value=\"[^\"]*\"/>| s|<property name=\"$name\" value=\"[^\"]*\"/>|<property name=\"$name\" value=\"$escapedValue\"/>|" \
    "$descriptor"

  # Read back rather than trust the substitution: this file decides whether requests are treated as
  # secure, so a silently failed edit must stop the start up instead of leaving the previous value.
  local firstValue
  firstValue=$(grep --only-matching "<property name=\"$name\" value=\"[^\"]*\"/>" "$descriptor" | head -1 |
    sed "s|<property name=\"$name\" value=\"\\(.*\\)\"/>|\\1|")
  if [ "$firstValue" != "$(xml_escape_value "$value")" ]; then
    config_fatal "Rewriting '$name' in $descriptor did not take effect: it still reads '$firstValue'."
  fi
}

###############################################################################
# Apply the Objective 5 load-balancer settings to the catalina component descriptor.
#
# Every value is validated before it reaches the descriptor, and an invalid one aborts the start up
# here with a message naming the variable. That matters most for the accelerator port:
# CatalinaContainer parses it with Integer.valueOf, so an unparseable value would otherwise surface as
# a NumberFormatException from deep inside container loading, and a port matching no connector would
# install a valve that marks nothing secure. Both are turned into an immediate, explained refusal.
#
# The accelerator port is additionally cross-checked against the connector ports declared in the
# descriptor. SslAcceleratorValve marks a request secure purely because it arrived on this local port,
# so the value must name a port this instance actually listens on - the plain http (or ajp) connector
# that receives the proxy's forwarded traffic. The network restriction that makes that port
# trustworthy cannot be asserted from here; it is documented at the property itself and in DOCKER.adoc.
#
# All three properties are rewritten on EVERY start, whether or not their variable is set, using the
# committed default when it is not. That is what makes the descriptor a function of the environment
# rather than of its own previous contents.
#
# Skipping the rewrite when a variable is absent looked harmless but was not, because this file is
# edited in place in the OFBiz source tree rather than rendered into /ofbiz/config from a pristine
# copy. Once a start had written a value there was no pristine copy left, so on any later start that
# reused the same writable layer - a restart of the container, or a redeploy that keeps it - an unset
# variable meant "keep the value the previous start wrote". Unsetting OFBIZ_SSL_ACCELERATOR_PORT would
# leave the valve installed and still marking plain HTTP requests secure; unsetting OFBIZ_JVM_ROUTE
# would leave a stale route in every session id. Both are silent, and the second one is precisely the
# kind of per-instance value that must not survive into a differently configured instance.
#
# Rewriting unconditionally is safe because every rewrite replaces an attribute VALUE rather than
# inserting anything: applying it twice produces the same file, and the read-back in
# rewrite_catalina_property proves each one took effect. The 0,/re/ address keeps every edit inside the
# first container block, so the "catalina-container-test" block further down the file - which declares
# its own jvm-route - is left byte for byte as committed.
render_catalina_configuration() {
  require_single_production_container

  # All three are rewritten on EVERY start, with the committed default supplied when the variable is
  # absent, rather than being rewritten only when a value is present.
  #
  # Rewriting conditionally is what made these settings one-way doors. The descriptor lives on the
  # writable layer, so an operator who set OFBIZ_SSL_ACCELERATOR_PORT once and then removed it kept an
  # instance that marks forwarded requests secure; one who enabled cross-subdomain sessions kept the
  # valve that forces a session for every request; and one who gave an instance a distinct jvm-route kept
  # that route after the variable was withdrawn, so a replacement instance restarted with the identity of
  # the old one. Reconstructing all three every start makes removing a variable mean what it says.
  local jvmRoute="${OFBIZ_JVM_ROUTE:-$CATALINA_DEFAULT_JVM_ROUTE}"
  local acceleratorPort="${OFBIZ_SSL_ACCELERATOR_PORT:-$CATALINA_DEFAULT_SSL_ACCELERATOR_PORT}"
  local crossSubdomainSessions="${OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS:-$CATALINA_DEFAULT_CROSS_SUBDOMAIN_SESSIONS}"

  reject_unsafe_value OFBIZ_JVM_ROUTE "$jvmRoute"
  # Tomcat appends this to the session id, so it has to survive being part of a cookie value and of
  # a load balancer's routing table: letters, digits, '.', '_' and '-' only.
  case "$jvmRoute" in
  *[!A-Za-z0-9._-]*)
    config_fatal "OFBIZ_JVM_ROUTE must contain only letters, digits, '.', '_' and '-'. It is appended to the session id."
    ;;
  esac
  rewrite_catalina_property jvm-route "$jvmRoute"

  # The empty default is not validated - it is the committed value, and it is what keeps the
  # SslAcceleratorValve out of the pipeline. Only a supplied value is checked.
  if [ -n "$acceleratorPort" ]; then
    require_integer_range OFBIZ_SSL_ACCELERATOR_PORT "$acceleratorPort" 1 65535
    # Compared against the connectors of the PRODUCTION container alone. Searching the whole descriptor
    # accepted 8010, which is declared only by the catalina-container-test AJP connector: the serving
    # container has nothing on that port, so SslAcceleratorValve's request.getLocalPort() comparison
    # would never match and every forwarded request would be treated as plain http - the value looked
    # validated and did nothing.
    if ! catalina_production_block | grep --quiet "<property name=\"port\" value=\"$acceleratorPort\"/>"; then
      # The ports the serving container DOES declare are named in the refusal. Without them the
      # message states only that the value matched nothing, which leaves the operator to find the
      # descriptor and work out what would have been accepted.
      local declaredPorts
      declaredPorts=$(catalina_production_block \
        | sed --quiet 's|.*<property name="port" value="\([0-9]\{1,\}\)"/>.*|\1|p' \
        | sort --unique | tr '\n' ' ')
      config_fatal "OFBIZ_SSL_ACCELERATOR_PORT=$acceleratorPort matches no connector port declared by the '$CATALINA_PRODUCTION_CONTAINER' block of $CATALINA_COMPONENT_DESCRIPTOR, which declares: $declaredPorts. It must be the LOCAL port this instance receives the proxy's forwarded traffic on, not the load balancer's public HTTPS port, and not a port declared only by the test container."
    fi
  fi
  rewrite_catalina_property ssl-accelerator-port "$acceleratorPort"

  # require_boolean normalises onto stdout, so it has to be read through a command substitution -
  # and config_fatal's exit inside a substitution only ends the subshell. The status is therefore
  # checked explicitly: without this an unparseable value would abort the subshell, leave the
  # captured value empty, and enable-cross-subdomain-sessions would be rewritten to nothing.
  local normalisedBoolean
  normalisedBoolean=$(require_boolean OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS \
    "$crossSubdomainSessions") \
    || config_fatal "OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS must be a boolean: true or false."
  rewrite_catalina_property enable-cross-subdomain-sessions "$normalisedBoolean"

  render_ajp_connector_address
}

###############################################################################
# Print the production catalina container block, and only it, on stdout.
#
# The range runs from the production container's opening tag to the first '</container>' after it, which
# is that block's own closing tag. Used for every check that must not see the test container.
catalina_production_block() {
  sed --quiet "/$CATALINA_PRODUCTION_CONTAINER/,/<\/container>/p" "$CATALINA_COMPONENT_DESCRIPTOR"
}

###############################################################################
# Refuse to edit a descriptor whose shape the scoped edits below do not hold for.
#
# Every edit here is positional: it relies on the production container appearing exactly once and BEFORE
# the test container, because 'first match in the file' is what identifies it. If the two blocks were ever
# reordered or duplicated, those edits would silently start rewriting the test container and leave the
# served one untouched - a failure with no symptom until traffic arrived. It is cheaper to refuse.
require_single_production_container() {
  local descriptor="$CATALINA_COMPONENT_DESCRIPTOR"
  local productionCount testCount
  productionCount=$(grep --count "$CATALINA_PRODUCTION_CONTAINER" "$descriptor" || true)
  testCount=$(grep --count "$CATALINA_TEST_CONTAINER" "$descriptor" || true)
  # Defaulted because grep prints nothing when it cannot open the descriptor: an empty count would be
  # interpolated into the message below as a hole, and the '-gt' comparison on the test count would abort
  # with a bare 'integer expression expected' instead of naming the file.
  productionCount=${productionCount:-0}
  testCount=${testCount:-0}

  if [ "$productionCount" != 1 ]; then
    config_fatal "$descriptor declares the '$CATALINA_PRODUCTION_CONTAINER' container $productionCount times; exactly one is required, because the edits applied here identify it by position."
  fi
  if [ "$testCount" -gt 0 ] \
    && [ "$(grep --line-number "$CATALINA_PRODUCTION_CONTAINER" "$descriptor" | cut --fields=1 --delimiter=:)" \
      -gt "$(grep --line-number "$CATALINA_TEST_CONTAINER" "$descriptor" | head -1 | cut --fields=1 --delimiter=:)" ]; then
    config_fatal "$descriptor declares the '$CATALINA_TEST_CONTAINER' container before the '$CATALINA_PRODUCTION_CONTAINER' one. The edits applied here take the first match in the file and would rewrite the test container instead."
  fi

  # The isolated block must be exactly ONE container element, and must not be the test one. The range
  # pattern cannot match 'catalina-container-test' today because it includes the closing quote, but if
  # a future rename ever made it match, the ports of the test container would be accepted as the
  # serving container's and the accelerator valve would be installed against a port nothing receives
  # traffic on - a setting that looks validated and does nothing.
  local isolated
  isolated=$(catalina_production_block)
  if [ "$(printf '%s\n' "$isolated" | grep --count '</container>')" -ne 1 ]; then
    config_fatal "Could not isolate the '$CATALINA_PRODUCTION_CONTAINER' element in $descriptor: it does not close exactly once. Its start tag or its closing tag has been reformatted, so the connector ports of the serving container cannot be determined."
  fi
  if printf '%s\n' "$isolated" | grep --quiet --fixed-strings "$CATALINA_TEST_CONTAINER"; then
    config_fatal "The element isolated as the '$CATALINA_PRODUCTION_CONTAINER' in $descriptor contains the '$CATALINA_TEST_CONTAINER' block, so the connector ports of the serving container cannot be determined."
  fi
}

###############################################################################
# Give the AJP connector of the PRODUCTION container an explicit bind address when
# OFBIZ_ENABLE_AJP_PORT is set, and take it away again when it is not.
#
# Two defects are fixed here relative to the unscoped 'sed -i /anchor/ a ...' this replaces.
#
# TARGETING. The anchor matches the AJP connector of BOTH container blocks, so the address was inserted
# into the test container too - and because nothing checked whether it was already there, every restart
# of the container appended another copy to both, growing the descriptor until the connector held a list
# of duplicate address properties. The edit is now confined to the first matching connector and skipped
# when an address property is already present, so it is idempotent.
#
# ROTATION. Removing OFBIZ_ENABLE_AJP_PORT must not leave the inserted line in place, keeping the AJP
# connector bound to 0.0.0.0 - every interface - for the life of the instance. The line is removed when
# the variable is absent, restoring the committed state in which the address is only a comment and
# Tomcat applies its own default.
render_ajp_connector_address() {
  local descriptor="$CATALINA_COMPONENT_DESCRIPTOR"
  local anchor="$CATALINA_AJP_CONNECTOR_ANCHOR"
  local addressAnchor="$CATALINA_CONNECTOR_ADDRESS_ANCHOR"

  if ! grep --quiet --fixed-strings "$anchor" "$descriptor"; then
    config_fatal "$descriptor does not declare the AJP connector anchor '$anchor'. The line the bind address is inserted after has been removed or reformatted."
  fi

  # Only the production connector's own lines are examined, so an address property belonging to the http
  # or https connector in the same block cannot be mistaken for this one.
  #
  # The pattern is anchored at the start of the line, after the indentation, and that anchoring is
  # load bearing rather than cosmetic: the committed descriptor carries this declaration as the COMMENT
  # '<!--<property name="address" value=""/>-->', which contains the property text as a substring. An
  # unanchored search matches it, so a pristine descriptor looks as though the address were already
  # present - the insertion is skipped, and withdrawing the variable then tries to remove a line that is
  # only a comment. Requiring '<property' to be the first non-blank text on the line separates an active
  # declaration from the commented one.
  local addressPresent="false"
  if catalina_ajp_connector_block | grep --quiet "^[[:blank:]]*$addressAnchor"; then
    addressPresent="true"
  fi

  if [ -n "$OFBIZ_ENABLE_AJP_PORT" ]; then
    if [ "$addressPresent" = "true" ]; then
      # Already applied by an earlier start of this same container. Re-inserting would duplicate it.
      return 0
    fi
    # '0,/re/{/re/...}' restricts the append to the FIRST line matching the anchor: the outer range ends
    # at that line, and the inner address applies the command only to the line that matches. A bare
    # '0,/re/ a text' would append after every line of the range instead, and an unaddressed '/re/ a text'
    # after every match in the file - which is what reached the test container.
    sed --in-place "0,\|$anchor|{\|$anchor| a\\
            <property name=\"address\" value=\"0.0.0.0\"/>
}" "$descriptor"
  else
    if [ "$addressPresent" = "false" ]; then
      return 0
    fi
    # Symmetric removal, scoped the same way: the first active address property at or after the AJP
    # connector anchor. The committed descriptor carries the declaration only as a comment, which this
    # pattern does not match, so the comment survives and the file returns to its shipped shape.
    sed --in-place "\|$anchor|,\|^[[:blank:]]*$addressAnchor|{\|^[[:blank:]]*$addressAnchor|d
}" "$descriptor"
  fi

  # Read back rather than trust the edit: this line decides which interfaces the AJP connector answers
  # on, and AJP is an unauthenticated protocol, so a silently duplicated or surviving line must stop the
  # start up instead of being served.
  local addressCount
  addressCount=$(catalina_ajp_connector_block | grep --count "^[[:blank:]]*$addressAnchor" || true)
  # Defaulted for the same reason as the container counts above: an unreadable descriptor would otherwise
  # put an empty string where the messages below state a number.
  addressCount=${addressCount:-0}
  if [ -n "$OFBIZ_ENABLE_AJP_PORT" ] && [ "$addressCount" != 1 ]; then
    config_fatal "The AJP connector of the '$CATALINA_PRODUCTION_CONTAINER' block in $descriptor declares its bind address $addressCount times after applying OFBIZ_ENABLE_AJP_PORT; exactly one is required."
  fi
  if [ -z "$OFBIZ_ENABLE_AJP_PORT" ] && [ "$addressCount" != 0 ]; then
    config_fatal "The AJP connector of the '$CATALINA_PRODUCTION_CONTAINER' block in $descriptor still declares a bind address although OFBIZ_ENABLE_AJP_PORT is not set."
  fi
}

###############################################################################
# Print the AJP connector element of the production container, and only it, on stdout.
#
# Bounded on both sides so that the http and https connectors declared after it in the same block - which
# carry their own commented-out address lines - are never examined. The range starts at the AJP connector
# anchor and ends at the '</property>' that closes it.
catalina_ajp_connector_block() {
  catalina_production_block \
    | sed --quiet "\|$CATALINA_AJP_CONNECTOR_ANCHOR|,\|^[[:blank:]]*</property>|p"
}

###############################################################################
# Render the generated OFBiz property files into /ofbiz/config.
#
# Generated property files are placed in /ofbiz/config so they appear earlier in the classpath and
# override the build-time copies of the properties in ofbiz.jar.
#
# This function runs on EVERY container start, not once behind a marker file. Each rendered file is
# derived from the pristine copy in the source tree rather than from the previous render, so the
# operation is idempotent, and re-running it is what allows a rotated secret or a changed setting to
# take effect when the container is restarted. Every substituted value is validated for shape and
# encoded for its destination grammar first, and every file is written atomically with mode 0600.
render_runtime_configuration() {
  render_security_configuration
  render_admin_key_configuration
  render_content_url_configuration
  render_content_store_configuration
}

###############################################################################
# Resolve OFBIZ_CONTENT_STORE_PROVIDER to the backend that will actually be used.
#
# This is the shell half of one decision that ContentStoreFactory.resolve makes on the Java side, and
# the two must agree exactly, because this script renders the value the factory then reads. The factory
# trims the value, folds it to lower case, and - for anything it still does not recognise - logs a
# warning naming the value and falls back to the database default. It does NOT throw, and it does not
# poison its cache with the refusal, because a mis-spelled provider must never stop a fleet member from
# starting: an instance that cannot boot serves nothing, whereas one that boots on database storage
# serves every request correctly and says in its log that the object store was not selected.
#
# So this function mirrors that contract instead of refusing: trim, fold case, accept the three known
# tokens, and otherwise warn and answer 'database'. Refusing here would reintroduce exactly the failure
# mode the factory was changed to avoid, one layer earlier - the container would exit before the JVM
# ever started, and the deployment would see a crash-looping task instead of a warning.
#
# The warning goes to stderr and the resolved token to stdout, so a caller can capture the decision in
# a command substitution without the diagnostic contaminating it.
# $1 - the supplied value; prints the resolved provider token
resolve_content_store_provider() {
  local value="$1"

  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  value=$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]')

  if [ -z "$value" ]; then
    printf '%s' "$CONTENT_STORE_DEFAULT_PROVIDER"
    return 0
  fi

  local candidate
  for candidate in "${CONTENT_STORE_PROVIDERS[@]}"; do
    if [ "$value" = "$candidate" ]; then
      printf '%s' "$value"
      return 0
    fi
  done

  printf '%s\n' "WARNING: OFBIZ_CONTENT_STORE_PROVIDER is '$value', which is not a storage backend this image recognises. Content will be stored by the '$CONTENT_STORE_DEFAULT_PROVIDER' backend instead. Set it to one of: ${CONTENT_STORE_PROVIDERS[*]}." >&2
  printf '%s' "$CONTENT_STORE_DEFAULT_PROVIDER"
}


###############################################################################
# Render the object-store configuration into config/content.properties.
#
# The seven OFBIZ_CONTENT_STORE_PROVIDER / OFBIZ_S3_* variables are what Objective 3 advertises, and
# until now nothing consumed them: ContentStoreFactory and S3ContentStore read these properties of the
# 'content' resource, this script rendered none of them, and it removed none of the variables from the
# environment either. The effect was worse than an unimplemented feature. A deployment that supplied a
# bucket, an endpoint and a secret access key silently kept database storage - so the objective was not
# met and nothing said so - while the secret access key stayed in the container's environment for the
# life of the instance, readable through /proc/<pid>/environ by anything sharing the PID namespace and
# inherited by every hook and child process.
#
# The render works exactly like config/security.properties: the whole file is rendered from the pristine
# committed copy in the OFBiz source tree into /ofbiz/config, which the generated start script puts
# FIRST on the class path, so UtilProperties resolves content.properties to this copy. Rendering the
# whole file matters - a properties override in OFBiz is resolved per resource, not merged per key, so a
# fragment holding only these keys would lose baseUrl, the upload path prefix and everything else.
#
# The credential is written through a --file= sed script so that it never appears in the process table,
# and render_config_from creates the destination with mode 0600 before anything is written into it.
#
# Nothing is rendered when the OPERATOR supplied no object-store variable. The distinction between that
# and "no object-store variable currently holds a value" is the whole of it: ofbiz_setup_env defaults
# OFBIZ_CONTENT_STORE_PROVIDER on every start, so the second question is answered "configured" by every
# container that ever runs, including one that configured nothing. The census therefore asks
# variable_was_supplied, and an unconfigured checkout keeps the committed content.properties -
# content.store.provider=database, every S3 key blank - with no override file in play at all.
render_content_store_configuration() {
  # Opened before the census, not after it: CONTENT_STORE_VARIABLES includes the two credential names,
  # and 'set -x' echoes a test with its arguments already expanded, so the emptiness test that
  # variable_was_supplied falls back on would publish OFBIZ_S3_SECRET_ACCESS_KEY under OFBIZ_TRACE even
  # though the loop only ever looks at whether the value is empty.
  hide_secrets

  local configured="false"
  local variable
  for variable in "${CONTENT_STORE_VARIABLES[@]}"; do
    if variable_was_supplied "$variable"; then
      configured="true"
      break
    fi
  done
  if [ "$configured" = "false" ]; then
    restore_trace
    if [ -e "$CONTENT_PROPERTIES_OVERRIDE" ]; then
      # Status and stderr both discarded, for the reason given at the identical construction in
      # remove_entity_engine_override: under 'set -e' an rm that cannot unlink the file ends the script on
      # the rm, printing a bare unprefixed "rm: cannot remove ..." instead of the ERROR-prefixed message
      # written for this case. The existence check immediately below owns the diagnostic and the failure.
      rm --force "$CONTENT_PROPERTIES_OVERRIDE" 2>/dev/null || true
      if [ -e "$CONTENT_PROPERTIES_OVERRIDE" ]; then
        config_fatal "No object-store variable is set but $CONTENT_PROPERTIES_OVERRIDE could not be removed. It would keep shadowing the committed content.properties with a storage backend this deployment no longer asks for."
      fi
      printf '%s\n' "No object-store variable is set: removed the stale $CONTENT_PROPERTIES_OVERRIDE so the committed content.properties applies again."
    fi
    return 0
  fi

  local provider="${OFBIZ_CONTENT_STORE_PROVIDER:-database}"
  reject_unsafe_value OFBIZ_CONTENT_STORE_PROVIDER "$provider"
  provider=$(resolve_content_store_provider "$provider")

  local bucket="$OFBIZ_S3_BUCKET"
  local region="$OFBIZ_S3_REGION"
  local endpoint="$OFBIZ_S3_ENDPOINT"
  local accessKeyId="$OFBIZ_S3_ACCESS_KEY_ID"
  local secretAccessKey="$OFBIZ_S3_SECRET_ACCESS_KEY"
  local pathStyle

  # An object-store setting supplied without selecting the object store is a contradiction, and which
  # half is the mistake cannot be guessed from here. The deployed profile refuses it, because the
  # alternative is content going somewhere other than where the manifest plainly asks for it and nothing
  # reporting the difference. The development profile renders the values - they are inert - and says so.
  if [ "$provider" != "s3" ]; then
    #
    # OFBIZ_S3_PATH_STYLE is deliberately NOT censused. ofbiz_setup_env gives it the committed default on
    # every start - it has to, because render_content_store_configuration substitutes it into
    # content.properties whatever the backend - so from here it is always non-empty and its presence says
    # nothing about what the operator supplied. Including it made a zero-configuration container report
    # its own default as a contradiction, and refused to start it outright in the prod profile: exactly
    # the database-storage default this refactor is required to leave working. Only the settings that have
    # no default, and therefore can only be there because a deployment asked for them, are counted.
    local suppliedS3=""
    for variable in OFBIZ_S3_BUCKET OFBIZ_S3_REGION OFBIZ_S3_ENDPOINT OFBIZ_S3_ACCESS_KEY_ID \
      OFBIZ_S3_SECRET_ACCESS_KEY; do
      if [ -n "${!variable}" ]; then
        suppliedS3="$suppliedS3 $variable"
      fi
    done
    if [ -n "$suppliedS3" ]; then
      if [ "$OFBIZ_PROFILE" = "prod" ]; then
        config_fatal "OFBIZ_CONTENT_STORE_PROVIDER is '$provider', but these object-store settings were also supplied:${suppliedS3}. They would have no effect, so content would be stored by the '$provider' backend and not in the object store. Set OFBIZ_CONTENT_STORE_PROVIDER=s3, or remove the settings."
      fi
      printf '%s\n' "WARNING: OFBIZ_CONTENT_STORE_PROVIDER is '$provider', so these object-store settings have no effect:${suppliedS3}. OFBIZ_PROFILE=prod refuses this combination." >&2
    fi
  fi

  if [ "$provider" = "s3" ]; then
    if [ -z "$bucket" ]; then
      config_fatal "OFBIZ_S3_BUCKET is required when OFBIZ_CONTENT_STORE_PROVIDER=s3. The provider has no bucket to read an object from, and would fail at the first content read rather than at start up."
    fi
    if [ -z "$region" ]; then
      config_fatal "OFBIZ_S3_REGION is required when OFBIZ_CONTENT_STORE_PROVIDER=s3. Supply the store's region identifier - S3-compatible stores that have no regions of their own conventionally accept us-east-1."
    fi
    reject_unsafe_value OFBIZ_S3_BUCKET "$bucket"
    reject_leading_whitespace OFBIZ_S3_BUCKET "$bucket"
    require_object_store_bucket OFBIZ_S3_BUCKET "$bucket"

    reject_leading_whitespace OFBIZ_S3_REGION "$region"
    # Delegated to the same validator resolve_content_store_configuration uses, exactly as the bucket
    # above delegates to require_object_store_bucket and the endpoint below to require_object_store_endpoint.
    # One validator owns the whole rule - the accepted character set and the accepted length, each
    # rejection reported as the kind of mistake it is - so the two layers cannot drift apart.
    require_object_store_region OFBIZ_S3_REGION "$region"

    if [ -n "$endpoint" ]; then
      reject_unsafe_value OFBIZ_S3_ENDPOINT "$endpoint"
      reject_leading_whitespace OFBIZ_S3_ENDPOINT "$endpoint"
      require_object_store_endpoint OFBIZ_S3_ENDPOINT "$endpoint"
      # Reported from the caller rather than the validator, which is reached twice per start: one
      # endpoint would otherwise produce two identical warnings and read as a fault in the script.
      case "$endpoint" in
      http://*)
        printf '%s\n' "WARNING: OFBIZ_S3_ENDPOINT uses http://, so object-store traffic and its credentials are not encrypted. Accepted because OFBIZ_PROFILE is not prod; OFBIZ_PROFILE=prod refuses it." >&2
        ;;
      esac
    fi

    # Which identity the deployment authenticates as follows from the credential pair, and the pair is
    # all or nothing. Accepting one half would let a typo in one variable name, or a secret whose
    # injection silently failed, move the deployment from the credential the operator supplied to
    # whatever ambient instance role happened to be available - a different identity, with different
    # permissions, and no error at all. So exactly one is refused, both are accepted as the static
    # credential, and neither is accepted as the ambient AWS credential chain. S3ContentStore applies
    # the same rule to the rendered properties, so the two layers cannot disagree.
    require_object_store_credential_pair "$accessKeyId" "$secretAccessKey"
    if [ -n "$accessKeyId" ]; then
      reject_unsafe_value OFBIZ_S3_ACCESS_KEY_ID "$accessKeyId"
      reject_leading_whitespace OFBIZ_S3_ACCESS_KEY_ID "$accessKeyId"
      reject_unsafe_value OFBIZ_S3_SECRET_ACCESS_KEY "$secretAccessKey"
      reject_leading_whitespace OFBIZ_S3_SECRET_ACCESS_KEY "$secretAccessKey"
    fi
  fi

  pathStyle=$(require_boolean OFBIZ_S3_PATH_STYLE "${OFBIZ_S3_PATH_STYLE:-false}") \
    || config_fatal "OFBIZ_S3_PATH_STYLE must be a boolean: true or false. It selects path-style addressing, which most S3-compatible stores require and Amazon S3 does not."

  # One sed script, written to a mode 0600 temporary file, for the same reason the security.properties
  # render uses one: --expression= would put the secret access key in this script's own command line.
  #
  # A value cannot create a property line of its own here. Each expression is anchored on '^key=' and
  # substitutes only the remainder of that one line, so the only way to introduce a second declaration
  # would be to embed a newline - which reject_unsafe_value refuses for every value above.
  local sedScript
  sedScript=$(mktemp)
  chmod 600 "$sedScript"
  register_secret_temp_file "$sedScript"
  {
    local index
    for index in "${!CONTENT_STORE_PROPERTIES[@]}"; do
      local property="${CONTENT_STORE_PROPERTIES[$index]}"
      local value
      case "$property" in
      content.store.provider) value="$provider" ;;
      content.store.s3.bucket) value="$bucket" ;;
      content.store.s3.region) value="$region" ;;
      content.store.s3.endpoint) value="$endpoint" ;;
      content.store.s3.access.key.id) value="$accessKeyId" ;;
      content.store.s3.secret.access.key) value="$secretAccessKey" ;;
      content.store.s3.path.style) value="$pathStyle" ;;
      esac
      printf 's|^%s=.*|%s=%s|\n' "$(properties_key_pattern "$property")" "$property" \
        "$(sed_escape_replacement "$(properties_escape_value "$value")")"
    done
  } >"$sedScript"

  render_config_from "$CONTENT_PROPERTIES_OVERRIDE" "$CONTENT_PROPERTIES_SOURCE" --file="$sedScript"
  discard_secret_temp_files

  # Read the rendered file back, property by property, for the reason require_rendered_property_value
  # exists: the shell validated its own copy of each value, and what the application acts on is whatever
  # java.util.Properties makes of the rendered line. A renamed or duplicated anchor in the committed
  # source would leave a value unsubstituted or shadowed, and the only symptom would be content quietly
  # going to the wrong backend - or, for the provider itself, an exception at the first content read.
  local index
  for index in "${!CONTENT_STORE_PROPERTIES[@]}"; do
    local property="${CONTENT_STORE_PROPERTIES[$index]}"
    local variableName="${CONTENT_STORE_VARIABLES[$index]}"
    local expected
    case "$property" in
    content.store.provider) expected="$provider" ;;
    content.store.s3.bucket) expected="$bucket" ;;
    content.store.s3.region) expected="$region" ;;
    content.store.s3.endpoint) expected="$endpoint" ;;
    content.store.s3.access.key.id) expected="$accessKeyId" ;;
    content.store.s3.secret.access.key) expected="$secretAccessKey" ;;
    content.store.s3.path.style) expected="$pathStyle" ;;
    esac
    # A non-blank value must be declared with something after the '=': that is the half that catches a
    # renamed anchor, which would otherwise leave the property absent from the render entirely.
    if [ -n "$expected" ]; then
      require_rendered_declaration "$CONTENT_PROPERTIES_OVERRIDE" "$(properties_key_pattern "$property")" \
        "$CONTENT_PROPERTIES_SOURCE"
    else
      # Blank is a MEANINGFUL value for these properties - it is what makes UtilProperties self-default
      # and, for the two credentials, what selects the AWS default provider chain - so the requirement is
      # that the declaration is PRESENT, not that it is non-empty. Without this a renamed anchor would
      # leave the property missing from the override altogether and the read-back below would still pass,
      # because a property that is not there reads back as the empty string.
      require_rendered_property "$CONTENT_PROPERTIES_OVERRIDE" "$(properties_key_pattern "$property")" \
        "$CONTENT_PROPERTIES_SOURCE"
    fi
    require_rendered_property_value "$CONTENT_PROPERTIES_OVERRIDE" "$(properties_key_pattern "$property")" \
      "$variableName" "$expected"
  done

  require_rendered_content_store_declarations "$provider"

  # Defence in depth between two independently written validators: resolve_content_store_configuration
  # checked the environment early, before the database was touched, and this function checked it again
  # while substituting it. They agree on the backend or the start is refused, because a disagreement
  # about the backend is a disagreement about where durable content is written. Empty means the resolver
  # did not run, which is how this render is driven in isolation.
  if [ -n "${RESOLVED_CONTENT_STORE_PROVIDER:-}" ] && [ "$RESOLVED_CONTENT_STORE_PROVIDER" != "$provider" ]; then
    discard_untrustworthy_render "$CONTENT_PROPERTIES_OVERRIDE"
    config_fatal "The object-store configuration resolved at start up selected the $RESOLVED_CONTENT_STORE_PROVIDER storage backend, but the render selected $provider. Two validations of the same environment disagree, so which backend content would be written to is not established."
  fi

  # Reports what the instance will do, never what it was given: the provider, whether an endpoint
  # override is in force, the addressing style and which credential source was selected. The bucket,
  # the region, the endpoint and both credentials are withheld - the endpoint because it may carry a
  # credential, the bucket and region because they describe the deployment's storage topology and a
  # log line is the wrong place to publish it.
  if [ "$provider" = "s3" ]; then
    printf '%s\n' "Content storage provider: s3 (rendered into config/content.properties) with endpoint-override [$([ -n "$endpoint" ] && printf 'true' || printf 'false')], path-style [$pathStyle], credentials [$([ -n "$accessKeyId" ] && printf 'configured-properties' || printf 'aws-default-provider-chain')]. The bucket must already exist and be writable; this script neither creates nor probes it."
  else
    printf '%s\n' "Content storage provider: $provider (rendered into config/content.properties). No object store is contacted."
  fi
  restore_trace
}

###############################################################################
# Render the content URL prefix into config/url.properties. Objective 5 support.
#
# Not a secret, but validated and read back exactly like one. Both prefixes are the origin OFBiz puts
# in front of every generated content URL, so a value that java.util.Properties parses into something
# other than what was supplied sends every image, style sheet and download reference to the wrong
# origin - and nothing else in the container notices, because each individual page still renders.
#
# WITHDRAWING THE VARIABLE HAS TO UNDO THE CONFIGURATION IT SET, which is why the absent case is not a
# no-op. /ofbiz/config is a declared volume that outlives the container and takes class path precedence
# over url.properties in ofbiz.jar, so an operator who pointed content at a CDN once and then removed
# the variable would otherwise keep an override that still emitted the old prefix on every rendered
# page, with no way to win the committed blank value back. The override is therefore REMOVED when no
# prefix is supplied, which leaves the shipped file - and only the shipped file - in effect.
#
# Left as its own function so it can be driven, and asserted on, without the two secret renderers.
render_content_url_configuration() {
  local urlPrefix="${OFBIZ_CONTENT_URL_PREFIX:-}"

  if [ -z "$urlPrefix" ]; then
    if [ -e "$URL_PROPERTIES_OVERRIDE" ]; then
      # Status and stderr discarded so the existence check below owns the diagnostic, exactly as in
      # remove_entity_engine_override and the content.properties removal above.
      rm --force "$URL_PROPERTIES_OVERRIDE" 2>/dev/null || true
      if [ -e "$URL_PROPERTIES_OVERRIDE" ]; then
        config_fatal "OFBIZ_CONTENT_URL_PREFIX is not set but $URL_PROPERTIES_OVERRIDE could not be removed. It would keep shadowing the shipped url.properties with a prefix this deployment no longer asks for."
      fi
      printf '%s\n' "OFBIZ_CONTENT_URL_PREFIX is not set: removed the stale $URL_PROPERTIES_OVERRIDE so the shipped url.properties applies again."
    fi
    return 0
  fi

  reject_unsafe_value OFBIZ_CONTENT_URL_PREFIX "$urlPrefix"
  reject_leading_whitespace OFBIZ_CONTENT_URL_PREFIX "$urlPrefix"

  local sedScript
  sedScript=$(mktemp)
  chmod 600 "$sedScript"
  register_secret_temp_file "$sedScript"
  {
    write_property_substitution 'content\.url\.prefix\.secure' "$urlPrefix"
    write_property_substitution 'content\.url\.prefix\.standard' "$urlPrefix"
  } >"$sedScript"

  render_config_from "$URL_PROPERTIES_OVERRIDE" "$URL_PROPERTIES_SOURCE" --file="$sedScript"
  discard_secret_temp_files

  # Read both rendered prefixes back for the same reason the secrets are read back: the anchor could
  # have been renamed in the source file, or duplicated, and either would leave the prefix decided by
  # something other than the supplied value.
  require_rendered_declaration "$URL_PROPERTIES_OVERRIDE" 'content\.url\.prefix\.secure' \
    "$URL_PROPERTIES_SOURCE"
  require_rendered_declaration "$URL_PROPERTIES_OVERRIDE" 'content\.url\.prefix\.standard' \
    "$URL_PROPERTIES_SOURCE"
  require_rendered_property_value "$URL_PROPERTIES_OVERRIDE" 'content\.url\.prefix\.secure' \
    OFBIZ_CONTENT_URL_PREFIX "$urlPrefix"
  require_rendered_property_value "$URL_PROPERTIES_OVERRIDE" 'content\.url\.prefix\.standard' \
    OFBIZ_CONTENT_URL_PREFIX "$urlPrefix"
}

###############################################################################
# OBJECT STORAGE (Objective 3): bridging the deployment environment to content.properties.
#
# The content storage providers read their configuration exclusively through UtilProperties, from the
# "content" resource, and perform NO environment lookup of their own - deliberately, so that the
# provider code has one configuration source and is testable without an environment. This section is
# therefore the only thing that can turn a deployment environment into an active object store, and
# without it OFBIZ_CONTENT_STORE_PROVIDER and the OFBIZ_S3_* variables would have no effect at all.
#
# Every rule enforced below is the rule the Java side already enforces, so the two can never disagree:
# the recognised provider names are ContentStoreFactory's, the required bucket and region are
# S3ContentStore.requireClient/buildClient's, the refusal of a one-sided credential pair is
# S3ContentStore.requireCredentialPair's, and the endpoint rules are
# S3ContentStore.validatedEndpoint's. The difference is only WHEN the refusal happens: here it happens
# at container start up, with the variable named, instead of on the first content read a page attempts.
###############################################################################

###############################################################################
# The most recent trim_configuration_value result.
#
# A global rather than stdout, and pure parameter expansion rather than a pipeline, so that a value
# which may be a credential never reaches an external command's argument vector.
#
# Trimming matters because UtilProperties.getPropertyValue trims every value it reads: without it the
# grammar checks below would refuse a value that OFBiz would then have accepted, and a trailing space
# is exactly the artefact a YAML deployment manifest introduces most easily.
TRIMMED_VALUE=""

###############################################################################
# Strip leading and trailing whitespace from a value, publishing it in TRIMMED_VALUE.
# $1 - value (never printed)
trim_configuration_value() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  TRIMMED_VALUE="$value"
}

###############################################################################
# Require a value that is a valid object-store bucket name.
#
# The S3 naming rules are enforced here rather than left to the store, because the store reports a
# malformed bucket name as a failure on the first request - by which time a page is already trying to
# render content to a user - whereas this reports it at start up with the variable named. The rules are the
# published ones: 3 to 63 characters of lower case letters, digits, '.' and '-', beginning and ending
# with a letter or a digit. Consecutive dots and dot-hyphen pairs are refused as well, because a bucket
# name containing them cannot be used with virtual-host-style addressing or TLS at all.
# $1 - variable name, $2 - value
require_object_store_bucket() {
  local name="$1"
  local value="$2"

  reject_unsafe_value "$name" "$value"
  if [ -z "$value" ]; then
    config_fatal "$name must be set when OFBIZ_CONTENT_STORE_PROVIDER=s3. There is no default bucket."
  fi
  if [ "${#value}" -lt "$S3_BUCKET_MIN_LENGTH" ] || [ "${#value}" -gt "$S3_BUCKET_MAX_LENGTH" ]; then
    config_fatal "$name must be between $S3_BUCKET_MIN_LENGTH and $S3_BUCKET_MAX_LENGTH characters long, which is what an object-store bucket name allows."
  fi
  case "$value" in
  *[!a-z0-9.-]*)
    config_fatal "$name must contain only lower case letters, digits, '.' and '-', which is what an object-store bucket name allows."
    ;;
  [!a-z0-9]* | *[!a-z0-9])
    config_fatal "$name must begin and end with a lower case letter or a digit."
    ;;
  *..* | *.-* | *-.*)
    config_fatal "$name must not contain '..', '.-' or '-.'. Such a bucket name cannot be addressed virtual-host-style or over TLS."
    ;;
  esac
  # S3-compatible stores reserve the IPv4 shape, because a bucket named like an address cannot be told
  # apart from a host in a virtual-host-style URL. Checked with grep rather than a case pattern because
  # the rule is "four groups of digits separated by dots", which a glob cannot express.
  if printf '%s' "$value" | grep --quiet --extended-regexp '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'; then
    config_fatal "$name must not be formatted as an IPv4 address; S3-compatible stores reserve that shape."
  fi
}

###############################################################################
# Require a value that is a valid object-store region identifier.
#
# Region identifiers are lower case letters, digits and '-' - 'us-east-1', 'eu-west-2', 'cn-north-1'.
# An S3-compatible store that has no notion of regions still needs one supplied, and 'us-east-1' is the
# value such stores conventionally accept, so this is validated for shape rather than against a list of
# known AWS regions: a list would go stale and would refuse a perfectly good private store.
# $1 - variable name, $2 - value
require_object_store_region() {
  local name="$1"
  local value="$2"

  reject_unsafe_value "$name" "$value"
  if [ -z "$value" ]; then
    config_fatal "$name must be set when OFBIZ_CONTENT_STORE_PROVIDER=s3. There is no default region; use us-east-1 for a store that has no regions."
  fi
  # The length is checked before the character set, and the message says so, because a value that is too
  # long is usually a different mistake from one containing a character that is not allowed - a whole URL
  # or an endpoint pasted into the region variable, rather than a typo - and a message about which
  # characters are permitted is actively misleading when every character in the value is permitted. The
  # observed length is reported for the same reason require_object_store_bucket reports it: it turns
  # "not accepted" into a fact the operator can act on without counting.
  if [ "${#value}" -lt "$S3_REGION_MIN_LENGTH" ] || [ "${#value}" -gt "$S3_REGION_MAX_LENGTH" ]; then
    config_fatal "$name must be between $S3_REGION_MIN_LENGTH and $S3_REGION_MAX_LENGTH characters long. Its length is ${#value}. A region identifier is short - us-east-1 is 9 characters - so an over-long value is usually an endpoint or a whole URL supplied to the wrong variable."
  fi
  case "$value" in
  *[!a-z0-9-]*)
    config_fatal "$name must contain only lower case letters, digits and '-', which is the shape of a region identifier such as us-east-1."
    ;;
  -* | *-)
    config_fatal "$name must begin and end with a lower case letter or a digit."
    ;;
  esac
}

###############################################################################
# Require an object-store endpoint that is safe to send the deployment's own credentials to.
#
# ONE validator, and every stage that accepts an endpoint calls THIS one. A second validator holding a
# second rule set would mean the rules an endpoint must satisfy depend on which stage happened to look
# at it, and the stage that runs on every path is the one whose omissions matter most: it decides
# whether this deployment's credentials can be sent over a plaintext connection, or to a link-local
# metadata address that would answer with the whole instance's role credentials.
#
# It is called from both the early resolve and the later render on purpose. The resolve checks the
# environment before the database is touched; the render checks it again while substituting it, so a
# value that changes in between is still caught. Defence in depth in TIME is worth having - two
# different rule sets is not, which is why there is one function rather than one per stage.
#
# Each paragraph below is one class of refusal. Every one of them is enforced on BOTH call paths,
# because both call the same function.
#
# The URI must be a plain absolute http or https URI with a host, no user information and no query or
# fragment. Anything else either fails deep inside the SDK's URI parsing, with a message that names
# neither the variable nor the file, or - in the case of embedded user information - puts a credential
# in a value that is logged as ordinary configuration.
#
# The authority must be well formed: a terminated IPv6 literal if it is bracketed, a port within
# 1-65535 if one is given, and a host written only with the characters a host is written with. This
# mirrors S3ContentStore.validatedEndpoint, so a value this script accepts is one the provider accepts.
#
# A metadata-service address is refused outright, in every profile. See INSTANCE_METADATA_HOSTS.
#
# A plaintext endpoint is refused outright in the deployed profile. Every object PUT carries the store
# credential and every GET returns content that may not be public, so an http endpoint exposes both to
# anything on the path. The development profile accepts it, because a local MinIO container normally has
# no certificate; the profile is the whole of that decision, so no separate escape variable exists to be
# set inconsistently with it. Saying so on stderr is the CALLER's job, in the one place that reports the
# effective configuration, because this function is reached twice per start and a warning emitted here
# would be printed twice for a single endpoint.
#
# The value is NEVER printed, in an error message or in the log, precisely because the case this exists
# to catch is an endpoint with a credential embedded in it. Every rejection therefore names the variable
# and describes the rule instead of echoing what was supplied.
# $1 - variable name, $2 - value (never printed)
require_object_store_endpoint() {
  local name="$1"
  local value="$2"

  reject_unsafe_value "$name" "$value"

  # A space survives reject_unsafe_value - it is not a control character - and would make the value
  # unparseable as a URI, so it is refused here with the rest of the shape rather than reaching the
  # SDK as a request that fails at the first content read.
  case "$value" in
  *[[:space:]]*)
    config_fatal "$name must not contain whitespace."
    ;;
  esac

  # S3ContentStore.validatedEndpoint refuses a query string or a fragment outright rather than
  # ignoring it, so the same value is refused here. Neither is part of an object-store endpoint;
  # silently dropping the part the SDK will not use would hide the mistake until the first request.
  case "$value" in
  *'?'* | *'#'*)
    config_fatal "$name must not carry a query string or a fragment. Supply only the scheme, host and optional port."
    ;;
  esac

  local scheme
  case "$value" in
  http://*) scheme='http' ;;
  https://*) scheme='https' ;;
  *://*)
    config_fatal "$name must use the http or https scheme."
    ;;
  *)
    config_fatal "$name must be an absolute http:// or https:// URI, for example https://s3.example.internal:9000. A bare host name is not an endpoint the SDK can use."
    ;;
  esac

  # The authority is everything between '://' and the first '/', '?' or '#'.
  local authority="${value#*://}"
  authority="${authority%%/*}"
  authority="${authority%%\?*}"
  authority="${authority%%#*}"

  case "$authority" in
  '')
    config_fatal "$name has no host. Supply the object store's host name, for example https://s3.example.internal:9000."
    ;;
  *@*)
    config_fatal "$name must not carry user information before the host. Credentials belong in OFBIZ_S3_ACCESS_KEY_ID and OFBIZ_S3_SECRET_ACCESS_KEY, which are rendered into a mode 0600 file, not in an endpoint that is treated as ordinary configuration."
    ;;
  esac

  # Split the optional port off the host. A bracketed IPv6 literal keeps its colons inside the
  # brackets, so it has to be recognised before the ordinary 'host:port' form.
  local host port
  case "$authority" in
  '['*']')
    host="$authority"
    port=''
    ;;
  '['*']:'*)
    host="${authority%:*}"
    port="${authority##*:}"
    ;;
  '['*)
    config_fatal "$name has an unterminated IPv6 literal. Bracket it as https://[fd00::1]:9000."
    ;;
  *:*)
    host="${authority%%:*}"
    port="${authority#*:}"
    ;;
  *)
    host="$authority"
    port=''
    ;;
  esac

  if [ -n "$port" ]; then
    require_integer_range "the port component of $name" "$port" 1 65535
  fi

  case "$host" in
  '['*']')
    # Inside the brackets only the characters an IPv6 literal is written with, including the ':' the
    # brackets exist to disambiguate and the '.' of an IPv4-mapped form.
    local literal="${host#\[}"
    literal="${literal%\]}"
    case "$literal" in
    '' | *[!A-Fa-f0-9:.]*)
      config_fatal "$name does not contain a valid bracketed IPv6 literal."
      ;;
    esac
    ;;
  '' | *[!A-Za-z0-9.-]*)
    config_fatal "$name must name a host as letters, digits, '.' and '-', or a bracketed IPv6 literal."
    ;;
  esac

  # Lower cased, and with the brackets of an IPv6 literal removed, so that both comparisons below treat
  # https://[FD00:EC2::254]/ and https://fd00:ec2::254/ as the same host. The Java provider normalises
  # the same way, because java.net.URI.getHost() keeps the brackets.
  host=$(printf '%s' "$host" | tr '[:upper:]' '[:lower:]')
  case "$host" in
  \[*\])
    host="${host#\[}"
    host="${host%\]}"
    ;;
  esac

  local metadataHost normalisedMetadataHost
  for metadataHost in "${INSTANCE_METADATA_HOSTS[@]}"; do
    normalisedMetadataHost="${metadataHost#\[}"
    normalisedMetadataHost="${normalisedMetadataHost%\]}"
    if [ "$host" = "$normalisedMetadataHost" ]; then
      config_fatal "$name points at the cloud instance metadata service ($metadataHost). The object-store provider sends this deployment's own credentials to that address, so this is refused in every profile and has no override."
    fi
  done

  if [ "$scheme" = 'http' ] && [ "${OFBIZ_PROFILE:-}" = "prod" ]; then
    config_fatal "$name must use https:// when OFBIZ_PROFILE=prod. Every object write carries the store credential and every read returns content that may not be public, so a plaintext endpoint exposes both to anything on the network path. Use https, or run this deployment with OFBIZ_PROFILE=dev if it really is a local development store."
  fi
}

###############################################################################
# The resolved object-store configuration.
#
# Globals rather than stdout, for the reason resolve_secret documents: a command substitution runs the
# function in a subshell, where config_fatal's 'exit' would end only that subshell and start up would
# continue with an unvalidated - or empty - configuration, silently defeating the fail-fast.
#
# RESOLVED_CONTENT_STORE_PROVIDER is deliberately empty when nothing was configured, which is NOT the
# same as 'database': empty means "leave the committed content.properties alone", whereas 'database'
# means "render an override that selects database storage". Both end up using database storage, but only
# the first leaves an unconfigured container reading the committed file byte for byte.
RESOLVED_CONTENT_STORE_PROVIDER=""
RESOLVED_S3_BUCKET=""
RESOLVED_S3_REGION=""
RESOLVED_S3_ENDPOINT=""
RESOLVED_S3_ACCESS_KEY_ID=""
RESOLVED_S3_SECRET_ACCESS_KEY=""
RESOLVED_S3_PATH_STYLE=""
RESOLVED_S3_CREDENTIAL_SOURCE=""

###############################################################################
# Resolve and validate the object-store configuration from the environment.
#
# Called unconditionally from _main, ahead of the OFBIZ_SKIP_INIT branch, for the same two reasons the
# schema-init flag is: where durable content lands decides whether an instance is replaceable at all, so
# a mistyped provider or an endpoint carrying a credential must be refused on every path rather than
# silently degraded; and the stale-override correction in render_content_store_configuration has to run
# on the skip-init path too, because /ofbiz/config is a volume that outlives the container.
#
# Almost nothing here reads OFBIZ_PROFILE, even though it is available: require_profile is the FIRST
# call in _main and aborts when the profile is unset or unrecognised, so by the time this runs the
# profile is resolved. Once the object store IS selected, the rules below fail closed in BOTH profiles,
# deliberately, because the alternative - accepting an incomplete object-store configuration and
# degrading to database storage - would write content to a different backend from the one the deployment
# asked for, and content written to the wrong backend is not something a later restart can put right.
# Selecting it is the one decision that does not fail closed: an unrecognised provider token is reported
# and treated as the database default, matching ContentStoreFactory, because a value the Java side would
# have warned about must not stop the container before the JVM starts. The one rule that distinguishes
# the profiles is the refusal of a plaintext endpoint in prod, which require_object_store_endpoint
# applies on this path as well as on the render, and it can only do that because the profile is already
# known here.
#
# Tracing is suspended for the whole function so a secret access key supplied through the environment is
# never echoed.
resolve_content_store_configuration() {
  hide_secrets

  RESOLVED_CONTENT_STORE_PROVIDER=""
  RESOLVED_S3_BUCKET=""
  RESOLVED_S3_REGION=""
  RESOLVED_S3_ENDPOINT=""
  RESOLVED_S3_ACCESS_KEY_ID=""
  RESOLVED_S3_SECRET_ACCESS_KEY=""
  RESOLVED_S3_PATH_STYLE=""
  RESOLVED_S3_CREDENTIAL_SOURCE=""

  trim_configuration_value "${OFBIZ_CONTENT_STORE_PROVIDER-}"
  local provider="$TRIMMED_VALUE"
  if [ -z "$provider" ]; then
    # Not configured at all. Any object-store variable that was supplied is reported, because supplying
    # a bucket and a credential and then forgetting the provider is a configuration that looks complete
    # and stores nothing in the object store.
    warn_about_inactive_object_store_settings "not set, so $CONTENT_STORE_DEFAULT_PROVIDER storage applies"
    restore_trace
    return 0
  fi

  reject_unsafe_value OFBIZ_CONTENT_STORE_PROVIDER "$provider"
  # Resolved through the SAME function the render uses, which is what lets the two results be compared
  # as plain strings after the render. Folding case and warning-with-fallback both live in that one
  # function, so this path and the render path cannot drift into disagreeing about a value - the failure
  # an earlier revision had, where this resolver accepted 'S3' case-insensitively and the render then
  # refused it, making the operator's first diagnostic name OFBIZ_S3_BUCKET for a run that could never
  # have started.
  provider=$(resolve_content_store_provider "$provider")
  RESOLVED_CONTENT_STORE_PROVIDER="$provider"

  if [ "$provider" != 's3' ]; then
    warn_about_inactive_object_store_settings "$provider"
    restore_trace
    return 0
  fi

  trim_configuration_value "${OFBIZ_S3_BUCKET-}"
  RESOLVED_S3_BUCKET="$TRIMMED_VALUE"
  require_object_store_bucket OFBIZ_S3_BUCKET "$RESOLVED_S3_BUCKET"

  trim_configuration_value "${OFBIZ_S3_REGION-}"
  RESOLVED_S3_REGION="$TRIMMED_VALUE"
  require_object_store_region OFBIZ_S3_REGION "$RESOLVED_S3_REGION"

  # The status of the boolean is checked explicitly: require_boolean reports on stdout and its
  # config_fatal ends only the command substitution's subshell, so an unchecked call would leave the
  # value empty and render path-style="" for UtilProperties to read as false.
  trim_configuration_value "${OFBIZ_S3_PATH_STYLE:-false}"
  # shellcheck disable=SC2034  # recorded for the start-up trace; the render re-derives it from the environment
  RESOLVED_S3_PATH_STYLE=$(require_boolean OFBIZ_S3_PATH_STYLE "$TRIMMED_VALUE") \
    || config_fatal "OFBIZ_S3_PATH_STYLE must be a boolean: true or false."

  trim_configuration_value "${OFBIZ_S3_ENDPOINT-}"
  RESOLVED_S3_ENDPOINT="$TRIMMED_VALUE"
  if [ -n "$RESOLVED_S3_ENDPOINT" ]; then
    require_object_store_endpoint OFBIZ_S3_ENDPOINT "$RESOLVED_S3_ENDPOINT"
  fi

  trim_configuration_value "${OFBIZ_S3_ACCESS_KEY_ID-}"
  RESOLVED_S3_ACCESS_KEY_ID="$TRIMMED_VALUE"
  trim_configuration_value "${OFBIZ_S3_SECRET_ACCESS_KEY-}"
  RESOLVED_S3_SECRET_ACCESS_KEY="$TRIMMED_VALUE"
  TRIMMED_VALUE=""
  require_object_store_credential_pair "$RESOLVED_S3_ACCESS_KEY_ID" "$RESOLVED_S3_SECRET_ACCESS_KEY"

  # shellcheck disable=SC2034  # recorded for the start-up trace; the render reports the mode it was given
  if [ -n "$RESOLVED_S3_ACCESS_KEY_ID" ]; then
    RESOLVED_S3_CREDENTIAL_SOURCE='configured-properties'
  else
    RESOLVED_S3_CREDENTIAL_SOURCE='aws-default-provider-chain'
  fi

  restore_trace
}

###############################################################################
# Refuse a one-sided static credential pair.
#
# Compares only emptiness, so neither value is ever printed or even substituted into a message, and
# 'set -x' is suppressed for the duration because a test is echoed with its arguments expanded.
#
# Falling back to the AWS default credential provider chain when only one of the two is supplied would
# authenticate as whatever ambient instance, container or profile identity happens to be available -
# a different principal, with potentially different permissions on potentially different data - so a
# half-configured pair is refused instead. This is the same rule S3ContentStore.requireCredentialPair
# applies; enforcing it here means the deployment is refused at start up rather than at the first
# content read.
#
# Takes the pair as arguments so that the render and the resolve path share one owner of the rule; the
# caller decides which values to hand over and what to record about the outcome.
# $1 - access key id (never printed), $2 - secret access key (never printed)
require_object_store_credential_pair() {
  hide_secrets

  local accessKeyId="$1"
  local secretAccessKey="$2"

  if [ -n "$accessKeyId" ] && [ -z "$secretAccessKey" ]; then
    config_fatal "OFBIZ_S3_ACCESS_KEY_ID is set but OFBIZ_S3_SECRET_ACCESS_KEY is not. Supply both to use static credentials, or neither to use the AWS default credential provider chain."
  fi
  if [ -z "$accessKeyId" ] && [ -n "$secretAccessKey" ]; then
    config_fatal "OFBIZ_S3_SECRET_ACCESS_KEY is set but OFBIZ_S3_ACCESS_KEY_ID is not. Supply both to use static credentials, or neither to use the AWS default credential provider chain."
  fi

  restore_trace
}

###############################################################################
# Report object-store settings that were supplied but will have no effect.
#
# Supplying a bucket, a region and a credential and then leaving the provider unset - or leaving it at
# filesystem - produces a deployment that looks fully configured for object storage and stores nothing
# there. That is exactly the failure this whole section exists to prevent, so it is reported rather than
# passed over. Only the NAMES of the variables are listed; no value is printed, because one of them is a
# credential and another may carry one.
# $1 - the provider that is in force, for the message
warn_about_inactive_object_store_settings() {
  local inForce="$1"
  local supplied=''
  local variableName

  # OFBIZ_S3_PATH_STYLE is deliberately absent from this list: ofbiz_setup_env defaults it on every
  # start, so it is always set and its presence is not evidence of operator intent. Only the settings
  # that have no default can distinguish "supplied" from "defaulted".
  for variableName in OFBIZ_S3_BUCKET OFBIZ_S3_REGION OFBIZ_S3_ENDPOINT OFBIZ_S3_ACCESS_KEY_ID \
    OFBIZ_S3_SECRET_ACCESS_KEY; do
    if [ -n "${!variableName-}" ]; then
      supplied="$supplied $variableName"
    fi
  done

  if [ -n "$supplied" ]; then
    printf '%s\n' "WARNING: the object store is not in use - OFBIZ_CONTENT_STORE_PROVIDER is $inForce - but these variables were supplied and will have no effect:$supplied. Set OFBIZ_CONTENT_STORE_PROVIDER=s3 to activate the object store." >&2
  fi
}

###############################################################################
# Re-verify the backend selection in the rendered content.properties, independently of the render loop.
# $1 - the storage provider this render substituted
#
# The render loop already reads every property back one by one, so this is deliberately belt and braces
# rather than the only guard. It is here because the backend is not just another property: every other
# one changes how content is stored, and this one changes WHERE, which is the only error a later restart
# cannot put right. So it is asserted a second time, by a different means - counting the declarations
# rather than parsing the value - and asserted directly against the provider that was validated.
#
# Exactly one declaration is required because java.util.Properties honours the LAST of several, so a
# source that gained a second declaration would leave the effective backend decided by a line this
# script never validated.
#
# A rejected render is DELETED before the abort. The only backend a file this script cannot vouch for
# can be trusted to describe is the committed database default, so removing it returns the instance to
# that default rather than leaving a file in force that may name an object store.
#
# grep is used in quiet or counting mode throughout, so no value is echoed.
require_rendered_content_store_declarations() {
  local provider="$1"

  local declarations
  declarations=$(grep --count '^content\.store\.provider=' "$CONTENT_PROPERTIES_OVERRIDE" || true)
  # grep prints nothing when it cannot open the file, and the '|| true' above cannot tell that apart from
  # a genuine zero, so the count is defaulted rather than interpolated into the message as an empty string.
  declarations=${declarations:-0}
  if [ "$declarations" != '1' ]; then
    discard_untrustworthy_render "$CONTENT_PROPERTIES_OVERRIDE"
    config_fatal "Rendered $CONTENT_PROPERTIES_OVERRIDE declares content.store.provider $declarations times instead of once. java.util.Properties would honour the last one, so the selected storage backend would not be the one this script validated."
  fi
  if ! grep --quiet "^content\.store\.provider=$provider\$" "$CONTENT_PROPERTIES_OVERRIDE"; then
    discard_untrustworthy_render "$CONTENT_PROPERTIES_OVERRIDE"
    config_fatal "Rendered $CONTENT_PROPERTIES_OVERRIDE does not select the $provider storage provider. The substitution did not take effect, so content would be stored somewhere other than where this deployment asked for it."
  fi
}

###############################################################################
# The cache-invalidation transport this script will render, resolved from the OFBIZ_JMS_* variables.
#
# RESOLVED_JMS_MANAGED is the switch the rest of the section reads: 'true' means this script owns
# config/serviceengine.xml and config/jndi.properties on this start, 'false' means the operator does.
# The connect timeout is resolved either way, because the start up reachability probe applies to an
# operator-supplied transport just as much as to a rendered one.
RESOLVED_JMS_MANAGED="false"
RESOLVED_JMS_INITIAL_CONTEXT_FACTORY=""
RESOLVED_JMS_PROVIDER_URL=""
RESOLVED_JMS_CONNECTION_FACTORY_JNDI_NAME=""
RESOLVED_JMS_TOPIC_JNDI_NAME=""
RESOLVED_JMS_TOPIC_PHYSICAL_NAME=""
RESOLVED_JMS_USERNAME=""
RESOLVED_JMS_PASSWORD=""
RESOLVED_JMS_CREDENTIAL_SOURCE=""
RESOLVED_JMS_CONNECT_TIMEOUT=""

###############################################################################
# Require a value that is a fully qualified Java class name.
#
# The value is loaded by JNDI as an InitialContextFactory, and it is also written into a properties file
# where an unexpected character - '=' or ':' most obviously - would split the declaration and change
# which property is being set. Only what a Java binary name can contain is accepted: letters, digits,
# '_', '$' for a nested class, and '.' as the package separator.
# $1 - variable name, $2 - value
require_java_class_name() {
  local name="$1"
  local value="$2"

  reject_unsafe_value "$name" "$value"
  if [ "${#value}" -gt "$JMS_CLASS_NAME_MAX_LENGTH" ]; then
    config_fatal "$name is longer than $JMS_CLASS_NAME_MAX_LENGTH characters, which is not a Java class name."
  fi
  case "$value" in
  *[!A-Za-z0-9_.$]*)
    config_fatal "$name must be a fully qualified Java class name: letters, digits, '_', '\$' and '.' only."
    ;;
  .* | *. | *..*)
    config_fatal "$name must be a fully qualified Java class name: it cannot begin or end with '.' or contain '..'."
    ;;
  esac
}

###############################################################################
# Require a value that is usable as a JMS provider URL.
#
# The shape is checked rather than the scheme, because every broker family spells its transport
# differently - 'tcp://', 'amqp://', 'failover:(tcp://a,tcp://b)', 'vm://' - and a list of accepted
# schemes would refuse a perfectly good provider. What IS enforced is that the value cannot carry a
# credential and cannot escape the places it is written to:
#
#  - '@' is refused outright. In every URL grammar it introduces user information, so a URL containing
#    one puts a password into a properties file, into this container's log, and into any diagnostic an
#    operator pastes elsewhere. The broker credentials belong in OFBIZ_JMS_USERNAME and
#    OFBIZ_JMS_PASSWORD, which are written into a 0600 file and never printed.
#  - whitespace is refused because no URL contains it, and a properties value containing it would be
#    read back with the tail silently attached.
#  - the XML metacharacters and the backslash are refused rather than escaped. They cannot appear in a
#    real provider URL, so accepting them would only widen what has to be reasoned about.
# $1 - variable name, $2 - value
require_jms_provider_url() {
  local name="$1"
  local value="$2"

  reject_unsafe_value "$name" "$value"
  if [ "${#value}" -gt "$JMS_PROVIDER_URL_MAX_LENGTH" ]; then
    config_fatal "$name is longer than $JMS_PROVIDER_URL_MAX_LENGTH characters. Point the instances at a broker cluster's own address rather than listing every member."
  fi
  case "$value" in
  *@*)
    config_fatal "$name must not contain '@'. Broker credentials embedded in the URL would be written into a properties file and printed in diagnostics; supply them through OFBIZ_JMS_USERNAME and OFBIZ_JMS_PASSWORD instead."
    ;;
  *[[:space:]]*)
    config_fatal "$name must not contain whitespace."
    ;;
  *'"'* | *"'"* | *'<'* | *'>'* | *"\\"*)
    config_fatal "$name must not contain a quote, an angle bracket or a backslash. A JMS provider URL contains none of them."
    ;;
  esac
  case "$value" in
  *://*) ;;
  *)
    config_fatal "$name must be a JMS provider URL naming a transport, such as tcp://broker:61616 or failover:(tcp://a:61616,tcp://b:61616)."
    ;;
  esac
}

###############################################################################
# Require a value that is usable both as a JNDI lookup name and as a properties key.
#
# A JNDI name reaches two grammars at once: it is written into an XML attribute, and the topic name is
# written as the KEY of a 'topic.<name>=<physical name>' declaration. A key containing '=', ':' or
# whitespace would be split by java.util.Properties, and one containing '#' or '!' would be read as a
# comment, so the accepted set is restricted to what both grammars carry unambiguously: letters, digits,
# '.', '_', '-' and the '/' that JNDI names conventionally use as a separator.
# $1 - variable name, $2 - value
require_jndi_name() {
  local name="$1"
  local value="$2"

  reject_unsafe_value "$name" "$value"
  if [ -z "$value" ]; then
    config_fatal "$name must not be empty."
  fi
  if [ "${#value}" -gt "$JMS_JNDI_NAME_MAX_LENGTH" ]; then
    config_fatal "$name is longer than $JMS_JNDI_NAME_MAX_LENGTH characters, which is not a JNDI name."
  fi
  case "$value" in
  *[!A-Za-z0-9._/-]*)
    config_fatal "$name must contain only letters, digits, '.', '_', '-' and '/'. It is used as a JNDI lookup name and as a properties key, and any other character would split the declaration."
    ;;
  /* | */)
    config_fatal "$name must not begin or end with '/'."
    ;;
  esac
}

###############################################################################
# Refuse a half-configured broker credential pair.
#
# Reads the resolved globals rather than taking arguments, so the secret is not passed around, and
# compares only emptiness, so neither value is ever printed. A broker that requires authentication and
# is given a user name with no password rejects the connection at the first invalidation - on the code
# path that rolls back the caller's transaction - and a password with no user name is simply ignored,
# which silently connects as the anonymous principal. Neither is a state worth entering, so both are
# refused here with the variable named.
require_jms_credential_pair() {
  hide_secrets

  if [ -n "$RESOLVED_JMS_USERNAME" ] && [ -z "$RESOLVED_JMS_PASSWORD" ]; then
    config_fatal "OFBIZ_JMS_USERNAME is set but OFBIZ_JMS_PASSWORD is not. Supply both to authenticate to the broker, or neither to connect anonymously."
  fi
  if [ -z "$RESOLVED_JMS_USERNAME" ] && [ -n "$RESOLVED_JMS_PASSWORD" ]; then
    config_fatal "OFBIZ_JMS_PASSWORD is set but OFBIZ_JMS_USERNAME is not. Supply both to authenticate to the broker, or neither to connect anonymously."
  fi

  if [ -n "$RESOLVED_JMS_USERNAME" ]; then
    RESOLVED_JMS_CREDENTIAL_SOURCE='configured'
  else
    RESOLVED_JMS_CREDENTIAL_SOURCE='anonymous'
  fi

  restore_trace
}

###############################################################################
# Report transport settings that were supplied but will have no effect.
#
# Supplying a provider URL, a topic and a credential and then leaving the factory class unset produces a
# deployment that looks fully configured for a shared broker and renders nothing, so it is reported
# rather than passed over. Only the NAMES of the variables are listed; one of them is a password.
warn_about_inactive_transport_settings() {
  local supplied=''
  local variableName

  for variableName in OFBIZ_JMS_PROVIDER_URL OFBIZ_JMS_CONNECTION_FACTORY_JNDI_NAME \
    OFBIZ_JMS_TOPIC_JNDI_NAME OFBIZ_JMS_TOPIC_PHYSICAL_NAME OFBIZ_JMS_USERNAME OFBIZ_JMS_PASSWORD; do
    if [ -n "${!variableName-}" ]; then
      supplied="$supplied $variableName"
    fi
  done

  if [ -n "$supplied" ]; then
    printf '%s\n' "WARNING: the cache-invalidation transport is not being rendered - OFBIZ_JMS_INITIAL_CONTEXT_FACTORY is not set - but these variables were supplied and will have no effect:$supplied. Set OFBIZ_JMS_INITIAL_CONTEXT_FACTORY to the broker client's initial context factory class to render the transport." >&2
  fi
}

###############################################################################
# Resolve and validate every cache-invalidation transport variable.
#
# Tracing is suspended for the whole function because the broker password is assigned to a variable
# here, and a traced assignment echoes the expanded value to the container log.
resolve_cache_transport_configuration() {
  hide_secrets

  RESOLVED_JMS_MANAGED="false"
  RESOLVED_JMS_INITIAL_CONTEXT_FACTORY=""
  RESOLVED_JMS_PROVIDER_URL=""
  RESOLVED_JMS_CONNECTION_FACTORY_JNDI_NAME=""
  RESOLVED_JMS_TOPIC_JNDI_NAME=""
  RESOLVED_JMS_TOPIC_PHYSICAL_NAME=""
  RESOLVED_JMS_USERNAME=""
  RESOLVED_JMS_PASSWORD=""
  RESOLVED_JMS_CREDENTIAL_SOURCE='anonymous'

  # Resolved unconditionally: the reachability probe bounds an operator-supplied transport too.
  RESOLVED_JMS_CONNECT_TIMEOUT="${OFBIZ_JMS_CONNECT_TIMEOUT:-$JMS_CONNECT_TIMEOUT_DEFAULT}"
  require_integer_range OFBIZ_JMS_CONNECT_TIMEOUT "$RESOLVED_JMS_CONNECT_TIMEOUT" \
    "$JMS_CONNECT_TIMEOUT_MIN" "$JMS_CONNECT_TIMEOUT_MAX"

  trim_configuration_value "${OFBIZ_JMS_INITIAL_CONTEXT_FACTORY:-}"
  local factory="$TRIMMED_VALUE"
  if [ -z "$factory" ]; then
    warn_about_inactive_transport_settings
    restore_trace
    return 0
  fi
  require_java_class_name OFBIZ_JMS_INITIAL_CONTEXT_FACTORY "$factory"
  RESOLVED_JMS_INITIAL_CONTEXT_FACTORY="$factory"

  trim_configuration_value "${OFBIZ_JMS_PROVIDER_URL:-}"
  local url="$TRIMMED_VALUE"
  if [ -z "$url" ]; then
    config_fatal "OFBIZ_JMS_INITIAL_CONTEXT_FACTORY is set but OFBIZ_JMS_PROVIDER_URL is not. A factory class with no provider URL has no broker to connect to. Supply both, or unset both to leave the transport alone."
  fi
  require_jms_provider_url OFBIZ_JMS_PROVIDER_URL "$url"
  RESOLVED_JMS_PROVIDER_URL="$url"

  trim_configuration_value "${OFBIZ_JMS_CONNECTION_FACTORY_JNDI_NAME:-}"
  RESOLVED_JMS_CONNECTION_FACTORY_JNDI_NAME="${TRIMMED_VALUE:-$JMS_CONNECTION_FACTORY_JNDI_DEFAULT}"
  require_jndi_name OFBIZ_JMS_CONNECTION_FACTORY_JNDI_NAME "$RESOLVED_JMS_CONNECTION_FACTORY_JNDI_NAME"

  trim_configuration_value "${OFBIZ_JMS_TOPIC_JNDI_NAME:-}"
  RESOLVED_JMS_TOPIC_JNDI_NAME="${TRIMMED_VALUE:-$JMS_TOPIC_JNDI_DEFAULT}"
  require_jndi_name OFBIZ_JMS_TOPIC_JNDI_NAME "$RESOLVED_JMS_TOPIC_JNDI_NAME"

  trim_configuration_value "${OFBIZ_JMS_TOPIC_PHYSICAL_NAME:-}"
  RESOLVED_JMS_TOPIC_PHYSICAL_NAME="${TRIMMED_VALUE:-$JMS_TOPIC_PHYSICAL_DEFAULT}"
  require_jndi_name OFBIZ_JMS_TOPIC_PHYSICAL_NAME "$RESOLVED_JMS_TOPIC_PHYSICAL_NAME"

  # Not trimmed. A credential's leading or trailing space is part of the credential, and silently
  # removing it would authenticate with a different secret than the one the deployment supplied.
  RESOLVED_JMS_USERNAME="${OFBIZ_JMS_USERNAME:-}"
  RESOLVED_JMS_PASSWORD="${OFBIZ_JMS_PASSWORD:-}"
  reject_unsafe_value OFBIZ_JMS_USERNAME "$RESOLVED_JMS_USERNAME"
  reject_unsafe_value OFBIZ_JMS_PASSWORD "$RESOLVED_JMS_PASSWORD"
  require_jms_credential_pair

  RESOLVED_JMS_MANAGED="true"
  restore_trace
}

###############################################################################
# Remove a transport override generated by an earlier start of this container.
#
# /ofbiz/config is a declared VOLUME, so withdrawing the transport variables is not enough on its own:
# the override an earlier start rendered survives, and the instance keeps subscribing and publishing to a
# broker nobody configures any more. Only a file carrying RENDERED_TRANSPORT_MARKER is removed, because
# authoring config/serviceengine.xml by hand has always been the supported way to supply a JMS provider
# and such a file must never be deleted by this script.
#
# The marker is matched against the RAW file. In serviceengine.xml and jndiservers.xml it lives inside an
# XML comment, which strip_xml_comments would remove, so the comment-aware readers used elsewhere in this
# script would not see it.
remove_generated_transport_overrides() {
  local file
  for file in "$SERVICE_ENGINE_OVERRIDE" "$JNDI_SERVERS_OVERRIDE" "$JNDI_PROPERTIES_OVERRIDE"; do
    if [ -f "$file" ] && grep --quiet --fixed-strings "$RENDERED_TRANSPORT_MARKER" "$file"; then
      rm --force "$file"
      printf '%s\n' "Removed the generated cache-invalidation transport override $file, because OFBIZ_JMS_INITIAL_CONTEXT_FACTORY is not set on this start and /ofbiz/config is a persistent volume."
    fi
  done
}

###############################################################################
# Refuse to render over configuration this script did not generate.
#
# An operator-authored config/serviceengine.xml or config/jndiservers.xml and the OFBIZ_JMS_* variables
# are two sources of truth for the same setting, and silently preferring either one is worse than
# refusing: rendering over the file destroys deliberate configuration, and skipping the render leaves
# variables that were set with every expectation of taking effect doing nothing at all.
require_no_unmanaged_transport_override() {
  local file
  for file in "$SERVICE_ENGINE_OVERRIDE" "$JNDI_SERVERS_OVERRIDE" "$JNDI_PROPERTIES_OVERRIDE"; do
    if [ -f "$file" ] && ! grep --quiet --fixed-strings "$RENDERED_TRANSPORT_MARKER" "$file"; then
      config_fatal "OFBIZ_JMS_INITIAL_CONTEXT_FACTORY is set, which asks this script to render the cache-invalidation transport, but $file already exists and was not generated by this script. Either remove that file and configure the transport through the OFBIZ_JMS_* variables, or unset OFBIZ_JMS_INITIAL_CONTEXT_FACTORY and keep managing the file yourself. See DOCKER.adoc."
    fi
  done
}

###############################################################################
# Refuse to render from a source whose substitution anchors have moved.
#
# All three renders below are anchored on text in a committed OFBiz file rather than on a '@TOKEN@'
# template, because those files are shared by the whole distribution and cannot carry deployment tokens.
# An anchor that has been reformatted upstream would make sed substitute nothing and produce a file that
# is valid, looks rendered, and configures no transport at all - so its presence is verified first and its
# absence is fatal rather than silent.
require_transport_source_anchors() {
  local count
  count=$(grep --count --fixed-strings '</service-engine>' "$SERVICE_ENGINE_SOURCE") || true
  if [ "$count" != "1" ]; then
    config_fatal "$SERVICE_ENGINE_SOURCE contains $count occurrences of '</service-engine>' but exactly one is required: it is the anchor the jms-service element is inserted before."
  fi

  count=$(grep --count --fixed-strings '</jndi-config>' "$JNDI_SERVERS_SOURCE") || true
  if [ "$count" != "1" ]; then
    config_fatal "$JNDI_SERVERS_SOURCE contains $count occurrences of '</jndi-config>' but exactly one is required: it is the anchor the dedicated jndi-server element is inserted before."
  fi

  count=$(grep --count --extended-regexp "^[[:space:]]*<jndi-server[[:space:]]+name=\"$JNDI_SERVER_NAME\"" "$JNDI_SERVERS_SOURCE") || true
  if [ "$count" != "0" ]; then
    config_fatal "$JNDI_SERVERS_SOURCE already declares a jndi-server named $JNDI_SERVER_NAME, so the element this script inserts would be a duplicate and JNDIConfigUtil would keep whichever it read first. The rendered server needs a name the distribution does not use."
  fi

  # java.naming.provider.url is the line the destination mapping is appended AFTER, and
  # java.naming.factory.initial is the line the render must leave untouched. Exactly one of each is
  # required either way: a duplicate would make "left unchanged" unverifiable, and a missing one would
  # mean the JVM-wide default no longer comes from this file, which is the assumption the whole
  # per-server design rests on.
  local property
  for property in 'java\.naming\.factory\.initial' 'java\.naming\.provider\.url'; do
    count=$(grep --count --extended-regexp "^$property=" "$JNDI_PROPERTIES_SOURCE") || true
    if [ "$count" != "1" ]; then
      config_fatal "$JNDI_PROPERTIES_SOURCE contains $count declarations of $property but exactly one is required: one of them is the anchor the destination mapping is appended after, and both must survive the render unchanged."
    fi
  done
}

###############################################################################
# Render the cache-invalidation transport into /ofbiz/config.
#
# Three files are written, because that is what OFBiz reads.
#
# config/serviceengine.xml activates the jms-service the five distributed cache services in
# framework/entityext/servicedef/services.xml are wired to, and points it at a DEDICATED jndi-server.
#
# config/jndiservers.xml declares that server, carrying the broker's context-provider-url and
# initial-context-factory. This is what confines the change to the transport: JNDIContextFactory then
# takes its per-server new InitialContext(h) branch, so the bare new InitialContext() that
# CatalinaContainer, RmiServiceContainer and the shipped 'default' server continue to use keeps
# resolving to the RMI registry the distribution configures.
#
# config/jndi.properties adds only the destination mapping the broker's factory needs - the JNDI topic
# name to physical topic mapping, and the connection-factory registration - and is APPEND-ONLY: the two
# standard java.naming.* keys keep their shipped values, because they are that JVM-wide default.
#
# All three are generated from the pristine committed files on every start, never from the previous
# render, so a changed broker or a rotated password takes effect on a restart.
#
# No committed source is modified. framework/service/config/serviceengine.xml keeps shipping its
# commented example, which stays useful as documentation, and each generated element is inserted before
# its file's closing tag - the position the schemas require, since jms-service is the last member of the
# service-engine sequence and jndi-server is the only member of jndi-config's.
#
# render_config_from writes through a 0600 temporary file and moves it into place, so the broker password
# is never briefly world readable and a failed substitution cannot install a truncated file. The sed
# program is passed with --file rather than --expression wherever it carries that password, which would
# otherwise be visible in the process table.
render_cache_transport_configuration() {
  hide_secrets

  if [ "$RESOLVED_JMS_MANAGED" != "true" ]; then
    remove_generated_transport_overrides
    restore_trace
    return 0
  fi

  require_no_unmanaged_transport_override
  require_transport_source_anchors

  local indent='        '
  local element
  element="<!-- $RENDERED_TRANSPORT_MARKER -->"
  element="$element\\n$indent<jms-service name=\"$JMS_SERVICE_NAME\" send-mode=\"all\">"
  element="$element\\n$indent    <server jndi-server-name=\"$JNDI_SERVER_NAME\""
  element="$element\\n$indent            jndi-name=\"$(xml_escape_value "$RESOLVED_JMS_CONNECTION_FACTORY_JNDI_NAME")\""
  element="$element\\n$indent            topic-queue=\"$(xml_escape_value "$RESOLVED_JMS_TOPIC_JNDI_NAME")\""
  element="$element\\n$indent            type=\"topic\""
  if [ "$RESOLVED_JMS_CREDENTIAL_SOURCE" = 'configured' ]; then
    element="$element\\n$indent            username=\"$(sed_escape_replacement "$(xml_escape_value "$RESOLVED_JMS_USERNAME")")\""
    element="$element\\n$indent            password=\"$(sed_escape_replacement "$(xml_escape_value "$RESOLVED_JMS_PASSWORD")")\""
  fi
  # listen="true" is not configurable. An instance that publishes without subscribing drifts silently.
  element="$element\\n$indent            listen=\"true\"/>"
  element="$element\\n$indent</jms-service>"
  element="$element\\n    </service-engine>"

  local sedScript
  sedScript=$(mktemp)
  chmod 600 "$sedScript"
  register_secret_temp_file "$sedScript"
  printf 's|</service-engine>|%s|\n' "$element" >"$sedScript"
  render_config_from "$SERVICE_ENGINE_OVERRIDE" "$SERVICE_ENGINE_SOURCE" --file="$sedScript"
  rm --force "$sedScript"

  # The dedicated jndi-server. Only the two attributes that scope the context are written: the JMS
  # credentials stay on the jms-service's server element, where JmsTopicListener passes them to
  # createTopicConnection, because security-principal and security-credentials here would authenticate
  # the DIRECTORY lookup instead, which is a different thing and is not what was configured.
  local server
  server="\\n    <!-- $RENDERED_TRANSPORT_MARKER -->"
  server="$server\\n    <jndi-server name=\"$JNDI_SERVER_NAME\""
  server="$server\\n            context-provider-url=\"$(sed_escape_replacement "$(xml_escape_value "$RESOLVED_JMS_PROVIDER_URL")")\""
  server="$server\\n            initial-context-factory=\"$(sed_escape_replacement "$(xml_escape_value "$RESOLVED_JMS_INITIAL_CONTEXT_FACTORY")")\"/>"
  server="$server\\n</jndi-config>"
  render_config_from "$JNDI_SERVERS_OVERRIDE" "$JNDI_SERVERS_SOURCE" \
    --expression="s|</jndi-config>|$server|"

  local url
  local topicKey
  local topicValue
  local connectionFactoryNames
  url=$(sed_escape_replacement "$(properties_escape_value "$RESOLVED_JMS_PROVIDER_URL")")
  topicKey=$(sed_escape_replacement "$RESOLVED_JMS_TOPIC_JNDI_NAME")
  topicValue=$(sed_escape_replacement "$RESOLVED_JMS_TOPIC_PHYSICAL_NAME")
  connectionFactoryNames=$(sed_escape_replacement "$RESOLVED_JMS_CONNECTION_FACTORY_JNDI_NAME")

  # The destination mapping and the connection-factory registration are appended AFTER the shipped
  # provider URL line - '&' re-emits the matched line unchanged - so they land among the active
  # declarations rather than after the commented tail, and neither standard key is rewritten. Both
  # registration forms are written: 'connectionFactoryNames' is what ActiveMQ Classic reads,
  # 'connectionFactory.<name>' is what Artemis reads, and a factory that understands neither ignores
  # both - which is what makes the same rendered file work against a range of compatible brokers instead
  # of one product. The URL is repeated in the registration on purpose: that value is read by the
  # broker's own factory, whereas java.naming.provider.url is read by the JDK for the bare context.
  render_config_from "$JNDI_PROPERTIES_OVERRIDE" "$JNDI_PROPERTIES_SOURCE" \
    --expression="s|^java\\.naming\\.provider\\.url=.*|&\\n\\n# $RENDERED_TRANSPORT_MARKER\\ntopic.$topicKey=$topicValue\\nconnectionFactoryNames=$connectionFactoryNames\\nconnectionFactory.$connectionFactoryNames=$url|"

  require_rendered_transport_declarations

  printf '%s\n' "Rendered the cache-invalidation transport into $SERVICE_ENGINE_OVERRIDE, $JNDI_SERVERS_OVERRIDE and $JNDI_PROPERTIES_OVERRIDE: jndi-server [$JNDI_SERVER_NAME], connection factory [$RESOLVED_JMS_CONNECTION_FACTORY_JNDI_NAME], topic [$RESOLVED_JMS_TOPIC_JNDI_NAME] mapped to physical topic [$RESOLVED_JMS_TOPIC_PHYSICAL_NAME], credentials [$RESOLVED_JMS_CREDENTIAL_SOURCE]. The JVM-wide JNDI defaults in $JNDI_PROPERTIES_OVERRIDE are left as shipped. Every instance of the fleet must use the same physical topic, and the broker client jar must be present in lib-extra."

  restore_trace
}

###############################################################################
# Verify that the rendered transport says what it was meant to say.
#
# The checks that would leave a DANGEROUS file behind run first and delete the render before aborting: a
# serviceengine.xml carrying the wrong topic or a second jms-service is worse than none at all, because
# /ofbiz/config is a persistent volume and the next start would find it, treat it as this script's own
# work, and re-use it. The remaining checks only confirm that a declaration is present.
require_rendered_transport_declarations() {
  local count
  count=$(count_active_service_messenger "$SERVICE_ENGINE_OVERRIDE")
  if [ "$count" != "1" ]; then
    discard_untrustworthy_render "$SERVICE_ENGINE_OVERRIDE"
    config_fatal "The rendered $SERVICE_ENGINE_OVERRIDE declares $count active jms-service elements named $JMS_SERVICE_NAME but exactly one is required. The render has been discarded."
  fi

  local element
  local serverTag
  element=$(active_service_messenger_element "$SERVICE_ENGINE_OVERRIDE")
  serverTag=$(printf '%s' "$element" \
    | grep --only-matching --extended-regexp '<server[^>]*>' | head --lines=1) || true

  local attribute
  local expected
  local actual
  for attribute in 'jndi-server-name' 'jndi-name' 'topic-queue' 'type' 'listen'; do
    case "$attribute" in
    'jndi-server-name') expected="$JNDI_SERVER_NAME" ;;
    'jndi-name') expected="$RESOLVED_JMS_CONNECTION_FACTORY_JNDI_NAME" ;;
    'topic-queue') expected="$RESOLVED_JMS_TOPIC_JNDI_NAME" ;;
    'type') expected='topic' ;;
    *) expected='true' ;;
    esac
    actual=$(xml_attribute_value "$serverTag" "$attribute")
    if [ "$actual" != "$expected" ]; then
      discard_untrustworthy_render "$SERVICE_ENGINE_OVERRIDE"
      config_fatal "The rendered $SERVICE_ENGINE_OVERRIDE declares $attribute=\"$actual\" on its server element but \"$expected\" was required. The render has been discarded."
    fi
  done

  if ! grep --quiet --fixed-strings "$RENDERED_TRANSPORT_MARKER" "$SERVICE_ENGINE_OVERRIDE"; then
    discard_untrustworthy_render "$SERVICE_ENGINE_OVERRIDE"
    config_fatal "The rendered $SERVICE_ENGINE_OVERRIDE does not carry the generated-file marker, so a later start could not tell it apart from configuration authored by an operator and would refuse to replace it. The render has been discarded."
  fi

  if [ "$RESOLVED_JMS_CREDENTIAL_SOURCE" = 'configured' ] \
    && ! grep --quiet --extended-regexp '[[:space:]]password="' "$SERVICE_ENGINE_OVERRIDE"; then
    discard_untrustworthy_render "$SERVICE_ENGINE_OVERRIDE"
    config_fatal "OFBIZ_JMS_USERNAME and OFBIZ_JMS_PASSWORD were supplied but the rendered $SERVICE_ENGINE_OVERRIDE carries no password attribute, so the instance would connect to the broker anonymously. The render has been discarded."
  fi

  require_rendered_jndi_server

  # The two standard keys are the JVM-wide JNDI default, so proving they were not rewritten is the whole
  # point of an append-only render and is checked before anything else in this file.
  require_rendered_declaration_unchanged "$JNDI_PROPERTIES_OVERRIDE" 'java\.naming\.factory\.initial' \
    "$JNDI_PROPERTIES_SOURCE"
  require_rendered_declaration_unchanged "$JNDI_PROPERTIES_OVERRIDE" 'java\.naming\.provider\.url' \
    "$JNDI_PROPERTIES_SOURCE"

  require_rendered_declaration "$JNDI_PROPERTIES_OVERRIDE" 'connectionFactoryNames' \
    "$JNDI_PROPERTIES_SOURCE"
  require_rendered_declaration "$JNDI_PROPERTIES_OVERRIDE" \
    "topic\\.${RESOLVED_JMS_TOPIC_JNDI_NAME//./\\.}" "$JNDI_PROPERTIES_SOURCE"
  if ! grep --quiet --fixed-strings "$RENDERED_TRANSPORT_MARKER" "$JNDI_PROPERTIES_OVERRIDE"; then
    config_fatal "The rendered $JNDI_PROPERTIES_OVERRIDE does not carry the generated-file marker, so a later start could not tell it apart from configuration authored by an operator."
  fi
}

###############################################################################
# Verify that the rendered jndi-server definitions scope the broker to the dedicated server and leave
# every server the distribution ships intact.
#
# All of these leave a DANGEROUS file behind if they are not enforced, so each deletes the render before
# aborting. A jndiservers.xml that names the wrong broker is worse than none, because /ofbiz/config is a
# persistent volume and the next start would treat it as this script's own work; and one that has LOST
# the shipped servers is worse still, because it replaces the committed file wholesale, so a missing
# 'default' entry would break the bare-context fallback and every JNDI transaction-factory deployment
# with it.
require_rendered_jndi_server() {
  local tag
  tag=$(jndi_server_element "$JNDI_SERVERS_OVERRIDE" "$JNDI_SERVER_NAME")
  if [ -z "$tag" ]; then
    discard_untrustworthy_render "$JNDI_SERVERS_OVERRIDE"
    config_fatal "The rendered $JNDI_SERVERS_OVERRIDE declares no active jndi-server named $JNDI_SERVER_NAME, so the jms-service would name a server that does not exist and JNDIContextFactory would refuse to build a context for it. The render has been discarded."
  fi

  # The expected value is XML escaped before the comparison, because that is the form it was written in
  # and a provider URL may legitimately carry the '&' of a query string.
  #
  # For context-provider-url this is more than a value check. Its PRESENCE is what makes
  # JNDIContextFactory take the per-server new InitialContext(h) branch instead of the JVM-wide bare one,
  # and an absent or empty attribute would silently fall back to that bare context and resolve the broker
  # from jndi.properties - the behaviour this whole arrangement exists to avoid. Because
  # require_jms_provider_url has already established that the configured URL is non-empty, requiring the
  # rendered attribute to equal it establishes that it is present and non-empty too.
  local attribute
  local expected
  local actual
  for attribute in 'initial-context-factory' 'context-provider-url'; do
    case "$attribute" in
    'initial-context-factory') expected=$(xml_escape_value "$RESOLVED_JMS_INITIAL_CONTEXT_FACTORY") ;;
    *) expected=$(xml_escape_value "$RESOLVED_JMS_PROVIDER_URL") ;;
    esac
    actual=$(xml_attribute_value "$tag" "$attribute")
    if [ "$actual" != "$expected" ]; then
      discard_untrustworthy_render "$JNDI_SERVERS_OVERRIDE"
      config_fatal "The rendered $JNDI_SERVERS_OVERRIDE declares $attribute=\"$actual\" on jndi-server $JNDI_SERVER_NAME but \"$expected\" was required. The render has been discarded."
    fi
  done

  local rendered
  local original
  rendered=$(count_jndi_servers "$JNDI_SERVERS_OVERRIDE")
  original=$(count_jndi_servers "$JNDI_SERVERS_SOURCE")
  if [ "$rendered" != "$((original + 1))" ]; then
    discard_untrustworthy_render "$JNDI_SERVERS_OVERRIDE"
    config_fatal "The rendered $JNDI_SERVERS_OVERRIDE declares $rendered active jndi-server elements but $((original + 1)) were required - the $original in $JNDI_SERVERS_SOURCE plus the one this script adds. The override replaces the committed file rather than adding to it, so a server lost here is lost from the deployment. The render has been discarded."
  fi

  local defaultTag
  defaultTag=$(jndi_server_element "$JNDI_SERVERS_OVERRIDE" "$JNDI_SERVER_DEFAULT_NAME")
  if [ -z "$defaultTag" ] || [ -n "$(xml_attribute_value "$defaultTag" 'context-provider-url')" ]; then
    discard_untrustworthy_render "$JNDI_SERVERS_OVERRIDE"
    config_fatal "The rendered $JNDI_SERVERS_OVERRIDE must keep declaring the jndi-server named $JNDI_SERVER_DEFAULT_NAME with no context-provider-url, because entityengine.xml's user-transaction-jndi and transaction-manager-jndi both name it and it is the parameterless entry that falls back to the JVM-wide context. The render has been discarded."
  fi

  if ! grep --quiet --fixed-strings "$RENDERED_TRANSPORT_MARKER" "$JNDI_SERVERS_OVERRIDE"; then
    discard_untrustworthy_render "$JNDI_SERVERS_OVERRIDE"
    config_fatal "The rendered $JNDI_SERVERS_OVERRIDE does not carry the generated-file marker, so a later start could not tell it apart from configuration authored by an operator and would refuse to replace it. The render has been discarded."
  fi
}

###############################################################################
# Verify that an append-only render left a declaration exactly as the committed source declares it.
#
# This is what makes "append-only" a checked property rather than an intention. The two standard
# java.naming.* keys in jndi.properties are the JVM-wide JNDI default: every bare new InitialContext() in
# the process resolves through them, including the one CatalinaContainer builds Tomcat's global naming
# context from - and treats a failure as fatal - and the ones RmiServiceContainer rebinds and looks up
# through. Rewriting them to point at a message broker repurposes all of that, silently when the broker's
# client happens to load. So a render that changed either one is deleted rather than installed.
# $1 - rendered file, $2 - property name as an extended regular expression, $3 - source file
require_rendered_declaration_unchanged() {
  local rendered
  local original
  rendered=$(grep --extended-regexp "^$2=" "$1" | head --lines=1) || true
  original=$(grep --extended-regexp "^$2=" "$3" | head --lines=1) || true
  if [ -z "$original" ] || [ "$rendered" != "$original" ]; then
    discard_untrustworthy_render "$1"
    config_fatal "The rendered $1 does not declare the property matched by '$2' exactly as $3 does. That declaration is the JVM-wide JNDI default, which Tomcat's global naming context and the RMI service container are both built from, so the transport must be scoped to its own jndi-server rather than written over it. The render has been discarded."
  fi
}

###############################################################################
# Apply any configuration changes required.
#
# The changes are split by idempotency. The edits below that rewrite a file in the OFBiz source tree
# in place - the AJP connector insertion and the component disabling - cannot be repeated safely, so
# they stay behind the config_applied marker file and run exactly once for the life of the container.
# The generated files under /ofbiz/config are re-rendered on every start by
# render_runtime_configuration, so changed settings and rotated secrets are picked up on restart.
# The ordering of the one-shot steps, the hooks and the rendering is unchanged from before the split.
apply_configuration() {
  local firstRun="false"

  # The payload is empty on purpose. This marker records that the two source-tree edits below were made,
  # and nothing else: binding it to the settings that drive them would make a changed setting trigger a
  # redo, and neither edit can be un-made on an existing container. Its value is integrity - an absent,
  # empty, truncated, hand-edited or foreign-deployment marker is not believed, and the edits are made
  # again. That redo is safe because both edits are now idempotent: the AJP insertion checks first
  # whether the address property is already there, and disable_components is a value-setting XSLT
  # transform.
  if ! container_marker_is_complete "$CONTAINER_CONFIG_APPLIED" ""; then
    firstRun="true"
    run_init_hooks before-config-applied /docker-entrypoint-hooks/before-config-applied.d/*
    begin_container_marker "$CONTAINER_CONFIG_APPLIED"
  fi

  # Every catalina descriptor edit, including the AJP connector bind address, is applied here rather than
  # behind the marker file. All of them are presence-checked and scoped to the production container,
  # which makes them idempotent, so running them on every start costs nothing and is what lets a changed
  # or withdrawn setting take effect on a restart. Applying the address insertion once behind the marker
  # was also unsound in its own right: the marker is written before the after-config-applied hooks run, so
  # a container whose hook failed retried the insertion with the marker already present on the volume.
  render_catalina_configuration

  render_runtime_configuration

  if [ "$firstRun" = "true" ]; then
    if [ -n "$OFBIZ_DISABLE_COMPONENTS" ]; then
      disable_components "$OFBIZ_DISABLE_COMPONENTS"
    fi

    # The marker is committed only after the after-config-applied hooks have SUCCEEDED. Touching it
    # first - which is what this did - suppressed its own recovery: a hook that failed left the marker
    # behind, so the next start took the "already applied" path and never ran the hook again, and the
    # operator's only way out was to delete a file inside the state volume. 'set -e' aborts the start up
    # on a failing hook, so with the marker written last a retry re-runs the whole one-shot block. That
    # is safe because every step in it is idempotent: disable_component compares the transformed
    # document with the original and leaves the file alone when it is already disabled, and the catalina
    # edits are presence-checked and scoped to the serving container.
    run_init_hooks after-config-applied /docker-entrypoint-hooks/after-config-applied.d/*
    complete_container_marker "$CONTAINER_CONFIG_APPLIED" ""
  fi
}

###############################################################################
# Decide which database identity the rendered configuration authenticates as, and refuse the
# combinations that would hand DDL power to a serving instance.
#
# Objective 4 turns off check-on-start and add-missing-on-start for the fleet, which stops a serving
# instance from ISSUING startup DDL. On its own that is a control on behaviour, not on privilege: if
# every instance authenticates as the role that owns the schema, then one SQL-injection defect, one
# leaked connection string or one compromised container is still enough to DROP a table. The privilege
# has to live somewhere, so it lives with the process that genuinely needs it and nowhere else - the
# one-shot OFBIZ_SCHEMA_INIT=true job, which is short-lived, listens on nothing and exits - while the
# long-lived processes that face the network authenticate as roles that can only read and write rows.
#
# The rules, in the order they are applied:
#
#   * The six init variables are supplied together or not at all. In init mode all three managed
#     datasources carry check-on-start="true", so all three groups issue DDL; a partial set would leave
#     one group's schema owned by its serving role, which is the very thing being prevented.
#   * An init role must differ from EVERY serving role, not merely from the one in its own group.
#     Sharing a name across groups would give one serving role DDL over another group's schema.
#   * An init password must differ from every serving password. Distinct role names with a shared
#     password are not separated at all: whoever learns the serving password can authenticate as the
#     privileged role, which is precisely the escalation this exists to prevent.
#   * In the prod profile the init identity is REQUIRED for an init run and REFUSED for a serving run.
#     Required, because a prod init run that authenticated as the serving role would prove that role
#     holds DDL. Refused, because a DDL credential present in a serving instance's environment is
#     readable through the orchestrator's configuration and /proc, so it is still a credential the fleet
#     holds even though this script removes it before exec. Keep it in a separate env file used only by
#     the init job - DOCKER.adoc shows the split.
#   * In the dev profile both are optional: an unconfigured local container must still be able to
#     create its own schema with a single role, so the init run falls back to the serving identity with
#     a warning rather than refusing to start.
#
# Sets RESOLVED_POSTGRES_<GROUP>_USER and RESOLVED_POSTGRES_<GROUP>_PASSWORD for the three groups, which
# are what render_database_configuration substitutes, and RESOLVED_DATABASE_IDENTITY for the read-back.
# Tracing is suspended for the whole function: every comparison below expands a password.
resolve_database_identities() {
  hide_secrets

  local variableName
  local suppliedCount=0
  for variableName in "${POSTGRES_INIT_IDENTITY_VARIABLES[@]}"; do
    if [ -n "${!variableName:-}" ]; then
      suppliedCount=$((suppliedCount + 1))
    fi
  done
  local expectedCount=${#POSTGRES_INIT_IDENTITY_VARIABLES[@]}

  if [ "$suppliedCount" -ne 0 ] && [ "$suppliedCount" -ne "$expectedCount" ]; then
    config_fatal "The schema-initialisation database identity is incomplete: $suppliedCount of $expectedCount variables are set. Supply all of ${POSTGRES_INIT_IDENTITY_VARIABLES[*]} or none of them - in init mode every managed datasource issues DDL, so all three groups need the privileged role. See DOCKER.adoc."
  fi

  if [ "$suppliedCount" -eq 0 ]; then
    if [ "$RESOLVED_SCHEMA_INIT" = "true" ] && [ "$OFBIZ_PROFILE" = 'prod' ]; then
      config_fatal "OFBIZ_SCHEMA_INIT=true with OFBIZ_PROFILE=prod requires a separate schema-initialisation database identity: ${POSTGRES_INIT_IDENTITY_VARIABLES[*]}. Without it this run would create the schema as the same role the fleet serves with, which means every serving instance keeps the privilege to drop it. See DOCKER.adoc for the roles and grants to create."
    fi
    if [ "$RESOLVED_SCHEMA_INIT" = "true" ]; then
      printf '%s\n' "WARNING: OFBIZ_SCHEMA_INIT=true with no schema-initialisation database identity, so the schema is being created by the same roles the fleet serves with. Those roles therefore hold DDL privileges over their own schema. Acceptable for a local database; OFBIZ_PROFILE=prod refuses it. See DOCKER.adoc." >&2
    fi
    resolve_serving_database_identity
    restore_trace
    return 0
  fi

  # Every init value is validated to the same standard as its serving counterpart, in both modes, so a
  # typo in the shared configuration is reported by whichever container starts first rather than only by
  # the init job.
  local group initUser initPassword
  for group in "${POSTGRES_GROUPS[@]}"; do
    initUser="OFBIZ_POSTGRES_${group}_INIT_USER"
    initPassword="OFBIZ_POSTGRES_${group}_INIT_PASSWORD"
    reject_unsafe_value "$initUser" "${!initUser}"
    require_database_password "$initPassword" "${!initPassword}"
  done

  require_separated_database_identities

  if [ "$RESOLVED_SCHEMA_INIT" != "true" ]; then
    # The successful init job's own epilogue, not a serving instance. It has just applied the schema with
    # this identity and is re-rendering the configuration volume in serving mode so that whatever starts
    # next from that volume reads a DDL-disabled configuration authenticating as the serving roles. The
    # container still exits without serving traffic, so neither the refusal below nor its development
    # profile warning applies - both are about a LONG-LIVED process holding a DDL credential. Handled
    # first, and by a flag only restore_serving_mode_after_schema_init sets, so that the rules for every
    # real serving start are reached unchanged.
    if [ "$SCHEMA_INIT_RESTORING_SERVING_MODE" = "true" ]; then
      resolve_serving_database_identity
      restore_trace
      return 0
    fi
    if [ "$OFBIZ_PROFILE" = 'prod' ]; then
      config_fatal "A schema-initialisation database identity is present but OFBIZ_SCHEMA_INIT is not true, so this is a serving instance holding a credential that can alter the schema. OFBIZ_PROFILE=prod refuses that: an environment variable is readable through the orchestrator's configuration and /proc even though this script removes it before starting OFBiz. Keep ${POSTGRES_INIT_IDENTITY_VARIABLES[*]} in an env file that only the OFBIZ_SCHEMA_INIT=true job is given. See DOCKER.adoc."
    fi
    printf '%s\n' "WARNING: a schema-initialisation database identity is present on an instance that is not performing schema initialisation. It is ignored and removed from the environment before OFBiz starts, but a DDL credential should not be given to a serving instance at all. OFBIZ_PROFILE=prod refuses it. See DOCKER.adoc." >&2
    resolve_serving_database_identity
    restore_trace
    return 0
  fi

  RESOLVED_DATABASE_IDENTITY='schema-init'
  for group in "${POSTGRES_GROUPS[@]}"; do
    initUser="OFBIZ_POSTGRES_${group}_INIT_USER"
    initPassword="OFBIZ_POSTGRES_${group}_INIT_PASSWORD"
    printf -v "RESOLVED_POSTGRES_${group}_USER" '%s' "${!initUser}"
    printf -v "RESOLVED_POSTGRES_${group}_PASSWORD" '%s' "${!initPassword}"
  done

  printf '%s\n' "OFBIZ_SCHEMA_INIT=true: the entity configuration is being rendered with the schema-initialisation database identity, so the DDL is applied by the privileged roles and the serving roles need no schema privileges."
  restore_trace
}

###############################################################################
# Point the resolved identity at the serving roles.
#
# The default for every run. Called by resolve_database_identities, which owns the decision; kept
# separate only so that decision reads as one branch per outcome.
resolve_serving_database_identity() {
  RESOLVED_DATABASE_IDENTITY='serving'
  local group servingUser servingPassword
  for group in "${POSTGRES_GROUPS[@]}"; do
    servingUser="OFBIZ_POSTGRES_${group}_USER"
    servingPassword="OFBIZ_POSTGRES_${group}_PASSWORD"
    printf -v "RESOLVED_POSTGRES_${group}_USER" '%s' "${!servingUser}"
    printf -v "RESOLVED_POSTGRES_${group}_PASSWORD" '%s' "${!servingPassword}"
  done
}

###############################################################################
# Refuse a schema-initialisation identity that is not actually separate from the serving identity.
#
# Each init role name is compared with all three serving role names, and each init password with all
# three serving passwords, because either kind of overlap collapses the separation: a shared name gives
# a serving role DDL over some group's schema, and a shared password lets anyone holding the serving
# credential authenticate as the privileged role. Only names appear in the failure messages.
#
# Tracing is suspended: the loop expands passwords. Called from inside resolve_database_identities,
# which has already suspended it - the pair is reference counted, so this is safe and keeps the function
# correct if it is ever called from anywhere else.
require_separated_database_identities() {
  hide_secrets
  local initGroup servingGroup initUser initPassword servingUser servingPassword

  for initGroup in "${POSTGRES_GROUPS[@]}"; do
    initUser="OFBIZ_POSTGRES_${initGroup}_INIT_USER"
    initPassword="OFBIZ_POSTGRES_${initGroup}_INIT_PASSWORD"
    for servingGroup in "${POSTGRES_GROUPS[@]}"; do
      servingUser="OFBIZ_POSTGRES_${servingGroup}_USER"
      servingPassword="OFBIZ_POSTGRES_${servingGroup}_PASSWORD"

      if [ "${!initUser}" = "${!servingUser}" ]; then
        config_fatal "$initUser names the same database role as $servingUser. The schema-initialisation identity exists so that no serving role holds DDL privileges, so it has to be a different role from every serving role. See DOCKER.adoc."
      fi
      if [ "${!initPassword}" = "${!servingPassword}" ]; then
        config_fatal "$initPassword is the same value as $servingPassword. Distinct role names sharing a password are not separated: anyone who learns the serving password can authenticate as the role that can alter the schema. Give the initialisation role its own password."
      fi
    done
  done

  restore_trace
}

###############################################################################
# Render the managed PostgreSQL entity engine configuration from its template.
#
# Every substituted value is either operator-supplied or defaulted by ofbiz_setup_env, and each one
# lands in a double quoted XML attribute, so it is checked for control characters, XML escaped and then
# escaped for the sed replacement grammar. The sed program is written to a mode 0600 temporary file instead of being passed on the
# command line, so a database password never appears in the process table, and tracing is suspended
# for the whole function so it never appears in the container log either. The result is written
# atomically with mode 0600.
#
# A missing or published database password is refused rather than defaulted, because a managed
# database is reachable over the network.
#
# The rendered file is then re-read and verified rather than trusted: no placeholder may survive
# unsubstituted, the startup DDL mode and the cache invalidation mode must be the ones that were
# requested, and the "test" delegator must still resolve to embedded H2.
render_database_configuration() {
  hide_secrets

  # The host, the port and the three database names become structural components of a JDBC URI, so they
  # are validated against the grammar of the component they occupy rather than merely checked for
  # control characters. The host, the port and the database name are the only environment supplied
  # components of the rendered URI, so validating those three covers all of it. The user names and
  # passwords occupy XML attributes of their own, never the URI, so for those a single-line check plus
  # XML escaping is the complete requirement.
  require_jdbc_host OFBIZ_POSTGRES_HOST "$OFBIZ_POSTGRES_HOST"
  # Defaulted here as well as in ofbiz_setup_env, on purpose: an empty value must never be able to
  # render as 'jdbc:postgresql://host:/db', and whether the URI has a usable port must not depend on
  # the order in which these functions happen to be called.
  OFBIZ_POSTGRES_PORT=${OFBIZ_POSTGRES_PORT:-5432}
  require_integer_range OFBIZ_POSTGRES_PORT "$OFBIZ_POSTGRES_PORT" 1 65535
  require_jdbc_database OFBIZ_POSTGRES_OFBIZ_DB "$OFBIZ_POSTGRES_OFBIZ_DB"
  require_jdbc_database OFBIZ_POSTGRES_OLAP_DB "$OFBIZ_POSTGRES_OLAP_DB"
  require_jdbc_database OFBIZ_POSTGRES_TENANT_DB "$OFBIZ_POSTGRES_TENANT_DB"

  local variableName
  for variableName in OFBIZ_POSTGRES_OFBIZ_USER OFBIZ_POSTGRES_OLAP_USER OFBIZ_POSTGRES_TENANT_USER; do
    reject_unsafe_value "$variableName" "${!variableName}"
  done

  # Each password must be supplied and must not be one of the published values named by
  # RETIRED_DATABASE_PASSWORDS. There is no way to opt out: a managed database is reachable over the
  # network, so a deployment with a guessable database password has no database password.
  for variableName in OFBIZ_POSTGRES_OFBIZ_PASSWORD OFBIZ_POSTGRES_OLAP_PASSWORD \
    OFBIZ_POSTGRES_TENANT_PASSWORD; do
    require_database_password "$variableName" "${!variableName}"
  done

  # Transport security for the three managed URIs. Resolved here, next to the credentials it protects,
  # because the passwords validated above travel over exactly this connection: without a verified TLS
  # session pgJDBC's own default silently negotiates down to plaintext and publishes them, and every
  # entity row after them, on the wire between the fleet and the managed database. Any non-verifying
  # mode is refused outright in the prod profile.
  require_postgres_ssl_parameters

  # Resolves and validates the network deadlines shared by the three managed URIs. Without this call the
  # template's @JDBC_PARAMS@ placeholder would render as the literal token and every managed connection
  # would fail, so it sits alongside the TLS resolver rather than behind any condition.
  require_postgres_jdbc_parameters

  # Defaulted here as well as in ofbiz_setup_env, and for the same reason as the port above: whether the
  # rendered pool has usable bounds must not depend on the order in which these functions are called,
  # and an empty value would render pool-minsize="" for DBCPConnectionFactory to parse as an integer.
  OFBIZ_DB_POOL_MIN=${OFBIZ_DB_POOL_MIN:-$DB_POOL_MIN_DEFAULT}
  OFBIZ_DB_POOL_MAX=${OFBIZ_DB_POOL_MAX:-$DB_POOL_MAX_DEFAULT}
  require_integer_range OFBIZ_DB_POOL_MIN "$OFBIZ_DB_POOL_MIN" 1 "$DB_POOL_SIZE_LIMIT"
  require_integer_range OFBIZ_DB_POOL_MAX "$OFBIZ_DB_POOL_MAX" 1 "$DB_POOL_SIZE_LIMIT"
  if [ "$OFBIZ_DB_POOL_MIN" -gt "$OFBIZ_DB_POOL_MAX" ]; then
    config_fatal "OFBIZ_DB_POOL_MIN ($OFBIZ_DB_POOL_MIN) must not exceed OFBIZ_DB_POOL_MAX ($OFBIZ_DB_POOL_MAX)."
  fi

  # How long a thread waits for a connection when the pool is exhausted. Rendered explicitly rather than
  # left absent, because InlineJdbc reads an absent pool-sleeptime as 300000 - five minutes, which is
  # longer than any load-balancer health-check timeout, so an exhausted pool would otherwise present as
  # a hung instance and be reported as a timeout rather than as pool exhaustion.
  OFBIZ_DB_POOL_WAIT=${OFBIZ_DB_POOL_WAIT:-$DB_POOL_WAIT_DEFAULT}
  require_integer_range OFBIZ_DB_POOL_WAIT "$OFBIZ_DB_POOL_WAIT" "$DB_POOL_WAIT_MIN" "$DB_POOL_WAIT_MAX"

  # The status is checked explicitly because require_boolean normalises onto stdout and its config_fatal
  # ends only the subshell; unchecked, a rejected value would render test-on-borrow="" and the entity
  # config parser would refuse the whole file at start up.
  OFBIZ_DB_POOL_TEST_ON_BORROW=${OFBIZ_DB_POOL_TEST_ON_BORROW:-$DB_POOL_TEST_ON_BORROW_DEFAULT}
  RESOLVED_DB_POOL_TEST_ON_BORROW=$(require_boolean OFBIZ_DB_POOL_TEST_ON_BORROW \
    "$OFBIZ_DB_POOL_TEST_ON_BORROW") \
    || config_fatal "OFBIZ_DB_POOL_TEST_ON_BORROW must be a boolean: true or false."

  # Size the per-instance pool against the capacity the whole fleet competes for. Neither of these
  # variables reaches the rendered file: a pool maximum is a per-instance number, but the resource it
  # consumes is shared, so N instances each allowed OFBIZ_DB_POOL_MAX connections can exhaust the
  # server's max_connections between them while every single instance is within its own configured
  # limit. The failure that produces is not pool exhaustion, which the wait above bounds - it is the
  # server refusing new connections, which strands whichever instances lost the race and cannot be
  # recovered by restarting them, because a restarting instance re-opens pool-minsize connections
  # immediately and simply takes the slots from its neighbours.
  OFBIZ_DB_FLEET_SIZE=${OFBIZ_DB_FLEET_SIZE:-$DB_FLEET_SIZE_DEFAULT}
  require_integer_range OFBIZ_DB_FLEET_SIZE "$OFBIZ_DB_FLEET_SIZE" 1 "$DB_FLEET_SIZE_LIMIT"

  if [ -n "$OFBIZ_DB_MAX_CONNECTIONS" ]; then
    require_integer_range OFBIZ_DB_MAX_CONNECTIONS "$OFBIZ_DB_MAX_CONNECTIONS" 1 "$DB_MAX_CONNECTIONS_LIMIT"

    # The reserve is withheld from the fleet's share: PostgreSQL keeps superuser_reserved_connections
    # back, the one-shot schema-init execution needs its own connections, and an operator diagnosing a
    # saturated database needs to be able to connect at all. A fleet sized to exactly max_connections
    # leaves none of that.
    local usableConnections=$((OFBIZ_DB_MAX_CONNECTIONS - DB_CONNECTION_RESERVE))
    # Each instance opens one pool per entity group, and all three managed groups point at the same
    # server, so an instance's worst case is three full pools rather than one.
    local fleetDemand=$((OFBIZ_DB_FLEET_SIZE * OFBIZ_DB_POOL_MAX * 3))
    if [ "$usableConnections" -lt 1 ]; then
      config_fatal "OFBIZ_DB_MAX_CONNECTIONS=$OFBIZ_DB_MAX_CONNECTIONS leaves nothing once the $DB_CONNECTION_RESERVE connection operational reserve is withheld. State the database's real max_connections."
    fi
    if [ "$fleetDemand" -gt "$usableConnections" ]; then
      config_fatal "The fleet can demand more database connections than the server allows: OFBIZ_DB_FLEET_SIZE=$OFBIZ_DB_FLEET_SIZE instances x OFBIZ_DB_POOL_MAX=$OFBIZ_DB_POOL_MAX x 3 entity groups = $fleetDemand, but OFBIZ_DB_MAX_CONNECTIONS=$OFBIZ_DB_MAX_CONNECTIONS leaves only $usableConnections after the $DB_CONNECTION_RESERVE connection operational reserve. Lower OFBIZ_DB_POOL_MAX to at most $((usableConnections / (OFBIZ_DB_FLEET_SIZE * 3))), reduce OFBIZ_DB_FLEET_SIZE, or raise the database's max_connections."
    fi
  elif [ "$OFBIZ_PROFILE" = 'prod' ] && [ "$OFBIZ_DB_FLEET_SIZE" -gt 1 ]; then
    # Refused rather than warned in the deployed profile. A multi-instance fleet is precisely the
    # configuration in which per-instance pool bounds stop being sufficient, so starting one without
    # stating the capacity they have to fit inside means the sizing has not been checked at all.
    config_fatal "OFBIZ_PROFILE=prod with OFBIZ_DB_FLEET_SIZE=$OFBIZ_DB_FLEET_SIZE requires OFBIZ_DB_MAX_CONNECTIONS so the per-instance OFBIZ_DB_POOL_MAX can be checked against the capacity the fleet shares. Set it to the database's max_connections."
  else
    # Reported, not refused. A single instance in a development profile cannot oversubscribe anything
    # this script can reason about, but the arithmetic is still worth stating so that the number is
    # known before the deployment becomes a fleet.
    printf '%s\n' "NOTICE: database connection capacity was not stated (OFBIZ_DB_MAX_CONNECTIONS is unset), so the fleet's demand of OFBIZ_DB_FLEET_SIZE=$OFBIZ_DB_FLEET_SIZE x OFBIZ_DB_POOL_MAX=$OFBIZ_DB_POOL_MAX x 3 entity groups = $((OFBIZ_DB_FLEET_SIZE * OFBIZ_DB_POOL_MAX * 3)) connections has not been checked against the server's max_connections. Set OFBIZ_DB_MAX_CONNECTIONS to have it verified." >&2
  fi

  # Both mode flags are normally resolved by resolve_entity_engine_flags before anything runs. They are
  # defaulted defensively here too so that this function renders the safe mode - no startup DDL, no
  # cross-instance invalidation - even if it is ever reached without that call.
  RESOLVED_SCHEMA_INIT=${RESOLVED_SCHEMA_INIT:-false}
  RESOLVED_DISTRIBUTED_CACHE_CLEAR=${RESOLVED_DISTRIBUTED_CACHE_CLEAR:-false}

  # After the mode flags, because which identity is rendered depends on the mode, and after the serving
  # credentials have been validated, because the separation rules compare against them.
  resolve_database_identities

  # Names the variables the resolved identity came from, so a rejected value is reported as the variable
  # the operator actually set rather than as its serving counterpart.
  local identitySuffix=''
  if [ "$RESOLVED_DATABASE_IDENTITY" = 'schema-init' ]; then
    identitySuffix='_INIT'
  fi

  # Structural check on the template, before a single secret is written anywhere: a password placeholder
  # that appears in a comment would be substituted there too and republish the password in clear text.
  require_template_secret_placement "$ENTITY_ENGINE_TEMPLATE"

  local sedScript
  sedScript=$(mktemp)
  chmod 600 "$sedScript"
  register_secret_temp_file "$sedScript"
  {
    write_xml_token_substitution '@HOST@' "$OFBIZ_POSTGRES_HOST" OFBIZ_POSTGRES_HOST
    write_xml_token_substitution '@PORT@' "$OFBIZ_POSTGRES_PORT" OFBIZ_POSTGRES_PORT
    write_xml_token_substitution '@OFBIZ_DB@' "$OFBIZ_POSTGRES_OFBIZ_DB" OFBIZ_POSTGRES_OFBIZ_DB
    write_xml_token_substitution '@OFBIZ_USERNAME@' "$RESOLVED_POSTGRES_OFBIZ_USER" \
      "OFBIZ_POSTGRES_OFBIZ${identitySuffix}_USER"
    write_xml_token_substitution '@OFBIZ_PASSWORD@' "$RESOLVED_POSTGRES_OFBIZ_PASSWORD" \
      "OFBIZ_POSTGRES_OFBIZ${identitySuffix}_PASSWORD"
    write_xml_token_substitution '@OLAP_DB@' "$OFBIZ_POSTGRES_OLAP_DB" OFBIZ_POSTGRES_OLAP_DB
    write_xml_token_substitution '@OLAP_USERNAME@' "$RESOLVED_POSTGRES_OLAP_USER" \
      "OFBIZ_POSTGRES_OLAP${identitySuffix}_USER"
    write_xml_token_substitution '@OLAP_PASSWORD@' "$RESOLVED_POSTGRES_OLAP_PASSWORD" \
      "OFBIZ_POSTGRES_OLAP${identitySuffix}_PASSWORD"
    write_xml_token_substitution '@TENANT_DB@' "$OFBIZ_POSTGRES_TENANT_DB" OFBIZ_POSTGRES_TENANT_DB
    write_xml_token_substitution '@TENANT_USERNAME@' "$RESOLVED_POSTGRES_TENANT_USER" \
      "OFBIZ_POSTGRES_TENANT${identitySuffix}_USER"
    write_xml_token_substitution '@TENANT_PASSWORD@' "$RESOLVED_POSTGRES_TENANT_PASSWORD" \
      "OFBIZ_POSTGRES_TENANT${identitySuffix}_PASSWORD"
    write_xml_token_substitution '@SSL_PARAMS@' "$RESOLVED_POSTGRES_SSL_PARAMETERS" OFBIZ_POSTGRES_SSLMODE
    # The network deadlines share one placeholder because they share one query string. Left
    # unsubstituted the template would render the literal token into every managed jdbc-uri and no
    # connection would be made at all, so this sits alongside the TLS parameters unconditionally.
    write_xml_token_substitution '@JDBC_PARAMS@' "$RESOLVED_POSTGRES_JDBC_PARAMETERS" \
      OFBIZ_POSTGRES_CONNECT_TIMEOUT
    write_xml_token_substitution '@DB_POOL_MIN@' "$OFBIZ_DB_POOL_MIN" OFBIZ_DB_POOL_MIN
    write_xml_token_substitution '@DB_POOL_MAX@' "$OFBIZ_DB_POOL_MAX" OFBIZ_DB_POOL_MAX
    write_xml_token_substitution '@DB_POOL_WAIT@' "$OFBIZ_DB_POOL_WAIT" OFBIZ_DB_POOL_WAIT
    write_xml_token_substitution '@DB_POOL_TEST_ON_BORROW@' "$RESOLVED_DB_POOL_TEST_ON_BORROW" \
      OFBIZ_DB_POOL_TEST_ON_BORROW
    # Two tokens rather than one, because Datasource.java parses the two attributes by DIFFERENT
    # rules: check-on-start is read as !"false".equals(value), so anything that is not the literal
    # false - including an unsubstituted placeholder - ENABLES it, while add-missing-on-start is read
    # as "true".equals(value) and so resolves to false unless the value is exactly true. Both receive
    # the same resolved mode here; the two names exist so the template states each attribute's value
    # explicitly instead of relying on one literal meaning the same thing under two parse rules, which
    # is also what EntityEngineConfigContractTests pins.
    write_xml_token_substitution '@CHECK_ON_START@' "$RESOLVED_SCHEMA_INIT" OFBIZ_SCHEMA_INIT
    write_xml_token_substitution '@ADD_MISSING_ON_START@' "$RESOLVED_SCHEMA_INIT" OFBIZ_SCHEMA_INIT
    write_xml_token_substitution '@DISTRIBUTED_CACHE_CLEAR@' "$RESOLVED_DISTRIBUTED_CACHE_CLEAR" OFBIZ_DISTRIBUTED_CACHE_CLEAR
  } >"$sedScript"

  render_config_from "$ENTITY_ENGINE_OVERRIDE" "$ENTITY_ENGINE_TEMPLATE" --file="$sedScript"
  discard_secret_temp_files

  # All checked on the rendered artifact rather than on the substitution, so a template that is out of
  # step with this script fails the start up instead of silently producing unencrypted connections,
  # unbounded network waits, a fleet that issues DDL on every boot, stale caches behind the load
  # balancer, or a "test" delegator pointing at the managed database.
  #
  # The generic completeness guard runs first: it catches any placeholder this script does not know
  # about, which no amount of per-setting checking can see. The specific checks that follow then confirm
  # that the values it DID substitute carry the requested modes.
  require_no_residual_placeholders "$ENTITY_ENGINE_OVERRIDE"
  require_rendered_transport_security "$ENTITY_ENGINE_OVERRIDE" "$OFBIZ_POSTGRES_SSLMODE"
  require_rendered_jdbc_deadlines "$ENTITY_ENGINE_OVERRIDE"
  require_rendered_schema_ddl_mode "$ENTITY_ENGINE_OVERRIDE" "$RESOLVED_SCHEMA_INIT"
  require_rendered_cache_clear_mode "$ENTITY_ENGINE_OVERRIDE" "$RESOLVED_DISTRIBUTED_CACHE_CLEAR"
  require_rendered_test_delegator_isolation "$ENTITY_ENGINE_OVERRIDE"
  require_rendered_database_identity "$ENTITY_ENGINE_OVERRIDE"

  restore_trace
}

###############################################################################
# Render the embedded-profile entity engine configuration when cross-instance cache invalidation is
# requested without a managed database.
#
# OFBIZ_DISTRIBUTED_CACHE_CLEAR has to work in both profiles, so when no PostgreSQL host is configured
# the committed entityengine.xml is rendered into /ofbiz/config with the flag rewritten on the two
# default delegators. This is the only setting the embedded profile has to substitute, which is why the
# render is skipped entirely when the flag is false: an unconfigured container then reads the committed
# file exactly as before, and /ofbiz/config gains no entityengine.xml at all.
#
# The substitution is anchored on the delegator name and bounded by [^>] so that it can only ever touch
# the attribute inside that one start tag. The "test" delegator is untouched both because it is not in
# the list and because it declares no such attribute to match.
render_embedded_cache_clear_configuration() {
  local delegatorName
  local sedArguments=()

  for delegatorName in "${CACHE_CLEAR_DELEGATORS[@]}"; do
    if ! grep --quiet \
      "<delegator name=\"$delegatorName\"[^>]*distributed-cache-clear-enabled=\"[^\"]*\"" "$ENTITY_ENGINE_SOURCE"; then
      config_fatal "$ENTITY_ENGINE_SOURCE does not declare distributed-cache-clear-enabled on the '$delegatorName' delegator. The anchor the entry point substitutes has been removed or reformatted."
    fi
    sedArguments+=("--expression=s|\(<delegator name=\"$delegatorName\"[^>]*\)distributed-cache-clear-enabled=\"[^\"]*\"|\1distributed-cache-clear-enabled=\"$RESOLVED_DISTRIBUTED_CACHE_CLEAR\"|")
  done

  render_config_from "$ENTITY_ENGINE_OVERRIDE" "$ENTITY_ENGINE_SOURCE" "${sedArguments[@]}"
  # The committed configuration contains no placeholders at all, so any at-sign delimited name in the
  # result would mean this render read a template instead of the committed file. Guarded identically to
  # the managed path so that neither render can install an artifact with a literal placeholder in it.
  require_no_residual_placeholders "$ENTITY_ENGINE_OVERRIDE"
  require_rendered_cache_clear_mode "$ENTITY_ENGINE_OVERRIDE" "$RESOLVED_DISTRIBUTED_CACHE_CLEAR"
  require_rendered_test_delegator_isolation "$ENTITY_ENGINE_OVERRIDE"
}

###############################################################################
# Resolve which entity engine configuration this container start is asking for.
#
# Exactly one of three modes, decided from the environment alone and never from what a previous start
# happened to leave on the volume:
#   managed   - a PostgreSQL host is configured, so the deployed-profile template is rendered.
#   embedded  - no managed database, but cross-instance cache invalidation is requested, so the
#               committed configuration is rendered with that one attribute rewritten.
#   committed - neither applies, so the committed configuration in ofbiz.jar is used verbatim and
#               /ofbiz/config must contain NO entityengine.xml at all.
resolve_desired_database_mode() {
  if [ -n "$OFBIZ_POSTGRES_HOST" ]; then
    printf 'managed'
  elif [ "${RESOLVED_DISTRIBUTED_CACHE_CLEAR:-false}" = "true" ]; then
    printf 'embedded'
  else
    printf 'committed'
  fi
}

###############################################################################
# Remove a previously rendered entity engine override so the committed configuration takes effect.
#
# /ofbiz/config precedes ofbiz.jar on the class path, and /ofbiz/config lives on a volume that outlives
# any single container, so an override written by an earlier start is authoritative until something
# deletes it. An operator who removed OFBIZ_POSTGRES_HOST - decommissioning a managed database, or moving
# a container back to embedded H2 for a local reproduction - would otherwise keep running against the OLD
# rendered file: the old host,
# the old user names, the credentials of a database that may since have been rotated or destroyed, and,
# if that file happened to be rendered by an init-mode start, check-on-start and add-missing-on-start
# still both true, so every instance would resume issuing startup DDL.
#
# unlink is atomic, so there is no window in which a partially removed file could be read. Absence is
# then confirmed, because silently continuing with the override still in place is the exact failure
# this function exists to prevent.
remove_entity_engine_override() {
  if [ ! -e "$ENTITY_ENGINE_OVERRIDE" ]; then
    return 0
  fi

  printf '%s\n' "No managed database and no cross-instance cache invalidation are configured: removing the entity engine override left by an earlier start so the committed configuration takes effect."
  # rm's own diagnostic is discarded, and its status is explicitly ignored, so that the failure is
  # reported by the existence check below and by nothing else: with 'set -e' in force an unguarded rm
  # that cannot unlink the file - a read-only mount, or a config directory owned by another uid - would
  # end the script on the rm itself, before the config_fatal written for exactly this case is reached.
  # The start still fails either way, fail-closed, because the check below still sees the file.
  rm --force "$ENTITY_ENGINE_OVERRIDE" 2>/dev/null || true

  if [ -e "$ENTITY_ENGINE_OVERRIDE" ]; then
    config_fatal "Could not remove the stale $ENTITY_ENGINE_OVERRIDE. It precedes ofbiz.jar on the class path, so starting would use its database configuration instead of the committed one. Check the permissions of the mounted config directory."
  fi
}

###############################################################################
# The complete non-secret description of the entity engine configuration this start is applying.
#
# Every field that changes the rendered artifact is included, so two starts that produce byte
# identical configurations produce identical fingerprints and any difference is detectable. No secret
# is included: not a database password, not a generated key. A rotated password therefore does NOT
# change the fingerprint, which is correct - the configuration is re-rendered unconditionally on every
# start, so a rotated password takes effect regardless of what this record says.
#
# MAINTENANCE RULE, and the reason the fields below are enumerated rather than summarised: the promise
# in the paragraph above is only kept while every placeholder render_database_configuration substitutes
# has a field here. The complete mapping is
#   @HOST@ @PORT@                            -> postgres-host, postgres-port
#   @OFBIZ_DB@ @OLAP_DB@ @TENANT_DB@         -> ofbiz-database, olap-database, tenant-database
#   @OFBIZ_USERNAME@ @OLAP_USERNAME@ @TENANT_USERNAME@
#                                            -> ofbiz-username, olap-username, tenant-username
#   @SSL_PARAMS@                             -> postgres-sslmode, postgres-sslrootcert
#   @JDBC_PARAMS@                            -> connect-timeout, socket-timeout, login-timeout,
#                                               cancel-timeout, query-timeout, tcp-keepalive
#   @DB_POOL_MIN@ @DB_POOL_MAX@ @DB_POOL_WAIT@ @DB_POOL_TEST_ON_BORROW@
#                                            -> pool-minsize, pool-maxsize, pool-wait,
#                                               pool-test-on-borrow
#   @CHECK_ON_START@ @ADD_MISSING_ON_START@  -> schema-init (both render from it)
#   @DISTRIBUTED_CACHE_CLEAR@                -> distributed-cache-clear
#   @OFBIZ_PASSWORD@ @OLAP_PASSWORD@ @TENANT_PASSWORD@
#                                            -> deliberately absent; they are the secrets
# A new placeholder therefore needs a new field, and the fingerprint-coverage test refuses the omission
# by perturbing each render input in turn and requiring the fingerprint to move.
#
# The RESOLVED usernames are recorded, not the serving ones, because they are what is substituted: an
# initialisation run renders the privileged identity, so recording OFBIZ_POSTGRES_*_USER would give two
# starts with genuinely different rendered artifacts the same fingerprint. database-identity is recorded
# alongside them so the record says WHY those names are the ones present.
#
# The normalised booleans are recorded for the mirror-image reason: 'yes' and 'true' both render as
# 'true', so recording the raw variable would report a change of target that never happened.
#
# The version prefix is what makes the record self-describing. Earlier images wrote the bare
# PostgreSQL host name into this file, and the Dockerfile's demo stage creates it EMPTY; both are read
# back as "unknown", which re-renders and rewrites the record rather than failing, so an existing
# volume upgrades into this format silently. The same mechanism is what makes a version bump safe: a
# record written before these fields existed is not comparable with one written after, and is
# discarded rather than compared field by field.
# $1 - the resolved database mode
database_desired_state_fingerprint() {
  local desiredMode="$1"

  printf '%s\n' "$DESIRED_STATE_RECORD_VERSION"
  # require_profile has already resolved OFBIZ_PROFILE to exactly 'dev' or 'prod' - it defaults an unset
  # value to 'dev' and reports that it did - so this is always set by the time the fingerprint is taken.
  # Reading it directly rather than defaulting again keeps the record from ever attributing a start to a
  # profile it did not run under.
  printf 'profile=%s\n' "$OFBIZ_PROFILE"
  printf 'database-mode=%s\n' "$desiredMode"
  printf 'schema-init=%s\n' "${RESOLVED_SCHEMA_INIT:-false}"
  printf 'distributed-cache-clear=%s\n' "${RESOLVED_DISTRIBUTED_CACHE_CLEAR:-false}"

  if [ "$desiredMode" = 'managed' ]; then
    printf 'postgres-host=%s\n' "$OFBIZ_POSTGRES_HOST"
    printf 'postgres-port=%s\n' "${OFBIZ_POSTGRES_PORT:-5432}"
    printf 'database-identity=%s\n' "${RESOLVED_DATABASE_IDENTITY:-serving}"
    printf 'ofbiz-database=%s\n' "$OFBIZ_POSTGRES_OFBIZ_DB"
    printf 'ofbiz-username=%s\n' "${RESOLVED_POSTGRES_OFBIZ_USER:-$OFBIZ_POSTGRES_OFBIZ_USER}"
    printf 'olap-database=%s\n' "$OFBIZ_POSTGRES_OLAP_DB"
    printf 'olap-username=%s\n' "${RESOLVED_POSTGRES_OLAP_USER:-$OFBIZ_POSTGRES_OLAP_USER}"
    printf 'tenant-database=%s\n' "$OFBIZ_POSTGRES_TENANT_DB"
    printf 'tenant-username=%s\n' "${RESOLVED_POSTGRES_TENANT_USER:-$OFBIZ_POSTGRES_TENANT_USER}"
    printf 'postgres-sslmode=%s\n' "${OFBIZ_POSTGRES_SSLMODE:-$POSTGRES_SSL_DEFAULT_MODE}"
    printf 'postgres-sslrootcert=%s\n' "${OFBIZ_POSTGRES_SSLROOTCERT:-}"
    printf 'connect-timeout=%s\n' "${OFBIZ_POSTGRES_CONNECT_TIMEOUT:-$POSTGRES_CONNECT_TIMEOUT_DEFAULT}"
    printf 'socket-timeout=%s\n' "${OFBIZ_POSTGRES_SOCKET_TIMEOUT:-$POSTGRES_SOCKET_TIMEOUT_DEFAULT}"
    printf 'login-timeout=%s\n' "${OFBIZ_POSTGRES_LOGIN_TIMEOUT:-$POSTGRES_LOGIN_TIMEOUT_DEFAULT}"
    printf 'cancel-timeout=%s\n' "${OFBIZ_POSTGRES_CANCEL_TIMEOUT:-$POSTGRES_CANCEL_TIMEOUT_DEFAULT}"
    printf 'query-timeout=%s\n' "${OFBIZ_POSTGRES_QUERY_TIMEOUT:-$POSTGRES_QUERY_TIMEOUT_DEFAULT}"
    printf 'tcp-keepalive=%s\n' \
      "${RESOLVED_POSTGRES_TCP_KEEPALIVE:-${OFBIZ_POSTGRES_TCP_KEEPALIVE:-$POSTGRES_TCP_KEEPALIVE_DEFAULT}}"
    printf 'pool-minsize=%s\n' "${OFBIZ_DB_POOL_MIN:-$DB_POOL_MIN_DEFAULT}"
    printf 'pool-maxsize=%s\n' "${OFBIZ_DB_POOL_MAX:-$DB_POOL_MAX_DEFAULT}"
    printf 'pool-wait=%s\n' "${OFBIZ_DB_POOL_WAIT:-$DB_POOL_WAIT_DEFAULT}"
    printf 'pool-test-on-borrow=%s\n' \
      "${RESOLVED_DB_POOL_TEST_ON_BORROW:-${OFBIZ_DB_POOL_TEST_ON_BORROW:-$DB_POOL_TEST_ON_BORROW_DEFAULT}}"
  fi
}

###############################################################################
# Report how the applied configuration differs from the one recorded by the previous start, and record
# the new one.
#
# The record is READ, not merely written. A marker whose contents nothing ever consults documents
# nothing and cannot be trusted, which is why the previous single-line host marker was of no use in
# diagnosing a container that had silently kept an older configuration.
#
# The comparison never decides whether to render - rendering is unconditional, because that is what
# makes a rotated credential take effect - so a corrupt, empty or legacy record can only cost a log
# line, never correctness. Only field NAMES are listed for a changed record; the values are all
# non-secret, but the names alone identify what moved without turning the container log into an
# inventory of the deployment.
#
# Written atomically after the configuration has been applied, so a failed render leaves the previous
# record intact rather than claiming a state that was never reached.
# $1 - the resolved database mode
record_database_desired_state() {
  local desiredMode="$1"
  local fingerprint
  local previous=''
  local changedFields=''
  local previousSchemaMode=''
  local desiredSchemaMode="${RESOLVED_SCHEMA_INIT:-false}"

  fingerprint=$(database_desired_state_fingerprint "$desiredMode")

  if [ -s "$CONTAINER_DB_CONFIG_APPLIED" ] \
    && [ "$(head --lines=1 "$CONTAINER_DB_CONFIG_APPLIED")" = "$DESIRED_STATE_RECORD_VERSION" ]; then
    previous=$(cat "$CONTAINER_DB_CONFIG_APPLIED")
    previousSchemaMode=$(printf '%s\n' "$previous" | sed --quiet 's/^schema-init=//p' | tail --lines=1)
  fi

  if [ -z "$previous" ]; then
    printf '%s\n' "Recording the database configuration state of this container in $CONTAINER_DB_CONFIG_APPLIED. No comparable record from an earlier start was present."
  elif [ "$previous" != "$fingerprint" ]; then
    changedFields=$(changed_fingerprint_fields "$previous" "$fingerprint")
    printf '%s\n' "The database configuration of this container differs from the previous start. Changed: $changedFields."
  fi

  # The DDL posture is reported on its own, and directionally, because it is the one recorded field
  # whose change alters what this start DOES rather than merely what it connects to: it decides whether
  # the managed datasources are rendered with the startup DDL enabled. The load-skipping markers are
  # deliberately left alone: they are assertions about a database, and a mode change against the same
  # host does not make them false. Nothing is printed on a first start, because there is no previous
  # mode to have changed from and a report of a change that did not happen is worse than silence.
  #
  # THE TWO DIRECTIONS ARE NOT SYMMETRIC, and neither message may imply that they are.
  #  - Into init mode: a start that asked for OFBIZ_SCHEMA_INIT=true on a state volume a serving
  #    instance had been using. This is the only direction an operator triggers.
  #  - Out of init mode: reached TWICE for one init job in the normal case, and the first time is inside
  #    that job. restore_serving_mode_after_schema_init calls configure_database again, with the mode
  #    forced to false, once the initialisation has succeeded - so the transition is reported while
  #    OFBIZ_SCHEMA_INIT is still true in the environment and the container is still the init job. It is
  #    reported again on a serving start only when the volume was left in init mode by an initialisation
  #    that never reached that point. Saying "is now a serving instance" would therefore be wrong in the
  #    normal case and would send an operator looking for a redeploy that did not happen.
  if [ -n "$previousSchemaMode" ] && [ "$previousSchemaMode" != "$desiredSchemaMode" ]; then
    if [ "$desiredSchemaMode" = "true" ]; then
      printf '%s\n' "Schema mode changed: this state volume previously ran with startup DDL disabled and is now an initialisation job (OFBIZ_SCHEMA_INIT=true), so the managed datasources have been re-rendered with the startup DDL enabled for this execution only."
    else
      printf '%s\n' "Schema mode changed: the managed datasources of this state volume previously rendered with startup DDL enabled for an initialisation job and have now been re-rendered with it disabled. A successful initialisation performs this itself before exiting; on a serving start it instead means the volume was left in init mode by an initialisation that did not get that far."
    fi
  fi

  write_state_record "$CONTAINER_DB_CONFIG_APPLIED" "$fingerprint"
}

###############################################################################
# The names of the fingerprint fields that differ between two records.
#
# Only field NAMES are produced. Every field is non-secret, but the names alone identify what moved
# without turning the container log into an inventory of the deployment. comm needs sorted input and a
# fingerprint is emitted in a fixed order, so both sides are sorted here rather than relying on that
# order; a field present on one side only therefore still shows up.
# $1 - the previously recorded fingerprint
# $2 - the fingerprint being applied now
changed_fingerprint_fields() {
  local previous="$1"
  local current="$2"
  local changedFields=''

  changedFields=$(comm -3 <(printf '%s\n' "$previous" | sort) <(printf '%s\n' "$current" | sort) \
    | tr --delete '\t' | cut --delimiter='=' --fields=1 | sort --unique | tr '\n' ' ') || changedFields=''

  printf '%s' "${changedFields% }"
}

###############################################################################
# Write one container state record atomically.
#
# The temporary file is created in the state directory itself so the rename cannot cross a file system,
# which is what makes it atomic: a reader either sees the whole previous record or the whole new one,
# never a truncated one, and an interrupted write leaves the previous record intact rather than
# claiming a state that was never reached.
# $1 - path of the record
# $2 - contents of the record, without a trailing newline
write_state_record() {
  local record="$1"
  local contents="$2"
  local temporary

  temporary=$(mktemp "$record.XXXXXXXX")
  printf '%s\n' "$contents" >"$temporary"
  mv --force "$temporary" "$record"
}

###############################################################################
# Refuse a database configuration that contradicts itself, before anything is rendered from it.
#
# resolve_desired_database_mode decides between the managed database and the embedded one from
# OFBIZ_POSTGRES_HOST alone, which is the documented trigger and stays the trigger. What that single
# question cannot express is the difference between the two ways of not setting it:
#
#   - a container that configured no database at all, which is the zero-configuration local and demo
#     start, and must go on booting on the embedded H2 database with no message of any kind;
#   - a container that configured a managed database and is MISSING THE HOST, which is a deployment
#     whose database name, user, password and TLS settings were all supplied and all silently ignored.
#
# Three rules, in the order a misconfiguration is most likely to be understood:
#
#   1. Any managed-database variable that was SUPPLIED AS AN EMPTY VALUE is fatal, named individually.
#      This includes OFBIZ_POSTGRES_HOST itself, so 'OFBIZ_POSTGRES_HOST=' can never be read as "use the
#      embedded database": an empty value is what an unresolved secret reference or an unrendered
#      template leaves behind, never an instruction, and ofbiz_setup_env's '${VAR:-default}' resolution
#      would otherwise replace the blank with a published default - 'ofbiz', 'ofbizolap', 'ofbiztenant'.
#   2. Managed-database variables supplied WITHOUT a host are fatal in the prod profile. A deployment
#      that serves real traffic may not silently fall back to a single-instance embedded database, and
#      the fail-fast posture the profile already applies to the required secrets is the same posture
#      here.
#   3. The same combination in the dev profile is a WARNING that names every variable that will be
#      ignored, and the start continues on the embedded database. Refusing it would break the local
#      workflow of leaving a database block in a compose file with the host commented out, which is a
#      legitimate thing to do while developing - but it must not be silent.
#
# Only NAMES are ever printed, never values, because six of these variables are credentials. The
# names come from the snapshot record_supplied_variables took before any default was applied, so a
# value this script defaulted is never mistaken for one the operator supplied - which is what makes rule
# 2 safe to apply to a variable such as OFBIZ_POSTGRES_PORT that also has a default.
require_consistent_database_selection() {
  local variableName
  local blank=''
  local supplied=''
  local hostSupplied='false'

  # Tracing is suspended for the whole of the questioning, and every answer is reduced to a NAME before it
  # is restored. Until record_supplied_variables has taken its snapshot - which is the state this function
  # is in when it is driven directly by a test - variable_was_supplied and variable_was_supplied_blank
  # answer by expanding the variable itself, and six of the names below hold a database password. A traced
  # shell echoes the expanded arguments of every command it runs, so asking the question under 'set -x'
  # would publish those passwords to the container log. Deciding afterwards, from the accumulated names
  # alone, keeps the branch and the message traceable without any value ever being expanded while tracing.
  hide_secrets
  for variableName in OFBIZ_POSTGRES_HOST "${MANAGED_DATABASE_VARIABLES[@]}"; do
    if variable_was_supplied_blank "$variableName"; then
      blank="$blank $variableName"
    fi
  done
  if variable_was_supplied OFBIZ_POSTGRES_HOST; then
    hostSupplied='true'
  fi
  for variableName in "${MANAGED_DATABASE_VARIABLES[@]}"; do
    if variable_was_supplied "$variableName"; then
      supplied="$supplied $variableName"
    fi
  done
  restore_trace

  if [ -n "$blank" ]; then
    config_fatal "These database variables were supplied with an empty value:$blank. An empty value is not the same as an unset one - it is what an unresolved secret reference or an unrendered template leaves behind - so it is refused rather than replaced by a default that would point this container at a different database. Supply a value for each name listed, or remove the variable entirely to leave the setting to its default. Unset every OFBIZ_POSTGRES_ variable to use the embedded database. See DOCKER.adoc."
  fi

  if [ "$hostSupplied" = 'true' ]; then
    return 0
  fi

  if [ -z "$supplied" ]; then
    return 0
  fi

  if [ "$OFBIZ_PROFILE" = 'prod' ]; then
    config_fatal "OFBIZ_POSTGRES_HOST is not set, but these database variables were supplied:$supplied. OFBIZ_POSTGRES_HOST is what selects the managed database, so without it every one of those settings is ignored and this container would serve traffic from an embedded H2 database on its own volume - invisible to every other instance and destroyed with this one. Set OFBIZ_POSTGRES_HOST to the database host, or unset the variables listed to use the embedded database deliberately. See DOCKER.adoc."
  fi

  printf '%s\n' "WARNING: OFBIZ_POSTGRES_HOST is not set, so no managed database is configured and this container will use the embedded H2 database on its own runtime volume. These variables were supplied and will have no effect:$supplied. Set OFBIZ_POSTGRES_HOST to use the managed database. This combination is refused outright in the prod profile, because an instance that serves real traffic must not fall back to an embedded database." >&2
}

###############################################################################
# Render the OFBiz database configuration.
#
# The configuration is reconstructed on every container start rather than once behind a marker file, so
# a rotated database password, a changed host or a changed DDL mode takes effect on restart. It is
# idempotent because it always renders from the pristine committed file or template, never from the
# previous render. Nothing here is gated on the marker: the marker is written AFTER the render as a
# record of what was applied, never consulted to decide whether to render. That ordering is deliberate -
# key material and database settings must be able to change on a restart, so no marker may ever be
# allowed to suppress a render.
#
# CRITICAL: all three modes act. Rendering only when something is configured is not enough, because
# the serving configuration is the FILE ON THE VOLUME, not the environment: whatever an earlier start
# wrote stays authoritative until this function replaces or removes it. The committed mode therefore
# deletes the override instead of doing nothing, which is what makes "unset the variable" actually
# undo the configuration it set.
#
# No JDBC driver is downloaded here. The PostgreSQL driver is bundled with the distribution, and
# guard_against_stale_jdbc_drivers refuses to start if an old downloaded copy is still present in the
# lib-extra volume, where it would take class path precedence over the bundled one.
configure_database() {
  local desiredMode

  # Establish that the database configuration says ONE thing before any of it is acted on. See
  # require_consistent_database_selection: OFBIZ_POSTGRES_HOST alone cannot tell a container that wants
  # the embedded database apart from one that wants PostgreSQL and is missing the host.
  require_consistent_database_selection

  desiredMode=$(resolve_desired_database_mode)

  case "$desiredMode" in
  managed)
    render_database_configuration
    ;;
  embedded)
    # No managed database, but cross-instance cache invalidation was asked for, so the committed
    # configuration is rendered with just that one attribute rewritten.
    render_embedded_cache_clear_configuration
    ;;
  committed)
    # Nothing to substitute, so the committed configuration inside ofbiz.jar is used verbatim - which
    # requires actively removing any override an earlier start left behind.
    remove_entity_engine_override
    ;;
  *)
    config_fatal "Unrecognised database mode '$desiredMode'."
    ;;
  esac

  record_database_desired_state "$desiredMode"

  if [ "${RESOLVED_SCHEMA_INIT:-false}" = "true" ] && [ "$desiredMode" != 'managed' ]; then
    printf '%s\n' \
      "OFBIZ_SCHEMA_INIT=true with no OFBIZ_POSTGRES_HOST: the startup DDL flags apply to the managed datasources only. The embedded H2 database always creates its own schema, so this run will initialise H2 and then exit without serving traffic."
  fi
}

###############################################################################
# Refuse to report success for a schema-initialisation run that did not apply the schema.
#
# The exit status of the init job is the only thing an orchestrator has to decide whether the fleet may
# start, so it must mean "the entity-model schema has been applied to this database" and nothing weaker.
# Four independent facts are required before it is allowed to be zero:
#
#   1. The schema-applying loader invocation really ran IN THIS PROCESS. SCHEMA_APPLYING_LOAD_RAN is set
#      only on the lines that execute /ofbiz/bin/ofbiz --load-data, so it cannot be inherited from the
#      environment, read from a volume, or set by a hook - it is a fact about this run. Creating a
#      delegator is what applies the DDL, and 'set -e' means the invocation either succeeded or the
#      script has already died, so a true value here is proof the DDL was issued against the database the
#      rendered configuration names.
#   2. A receipt exists at all. initialise_schema writes it only after the DDL child succeeded AND the
#      completeness verification came back clean, and it writes it atomically, so its absence means the
#      initialisation did not reach its end.
#   3. That receipt is not empty, which separates "never written" from "written but truncated".
#   4. The receipt carries THIS RUN'S token. The record lives on a state volume that outlives the
#      container, and a volume reused against a fresh managed database carries whatever the previous
#      container left on it, so a receipt from an earlier run - or from a run against a different
#      database - must not be readable as evidence that this run did the work. The token is compared,
#      never merely the file's existence, and it is generated once per execution of this script.
#
# Together these close the case the reviewers raised: a marker pre-created on a mounted volume can no
# longer buy a zero exit status from a job that created nothing, because init mode does not consult the
# marker to decide whether to work and does consult reality before it claims to have finished.
require_schema_init_completed() {
  if [ "$SCHEMA_APPLYING_LOAD_RAN" != "true" ]; then
    config_fatal "OFBIZ_SCHEMA_INIT=true but no schema-applying data load ran, so no schema was created. Refusing to exit successfully: an orchestrator would read that as a ready database. This is a defect in the entry point rather than a configuration error - report it."
  fi

  if [ ! -f "$CONTAINER_SCHEMA_INITIALISED" ]; then
    config_fatal "OFBIZ_SCHEMA_INIT=true completed no schema initialisation: $CONTAINER_SCHEMA_INITIALISED was never written. Exiting successfully would tell the orchestrator that the schema is ready when nothing was applied."
  fi

  # The init job loads no data, so the record it must leave behind is the schema one, not the data-load
  # marker: initialise_schema writes it only after the DDL child succeeded AND the completeness
  # verification came back clean, and it writes it atomically. An empty record therefore means the
  # initialisation did not reach the end.
  if [ ! -s "$CONTAINER_SCHEMA_INITIALISED" ]; then
    config_fatal "OFBIZ_SCHEMA_INIT=true and the schema application ran, but the container state record for it was not written, so the initialisation did not reach the end. Refusing to exit successfully. Check the output above for the failure, then re-run the initialisation."
  fi

  local recordedToken
  recordedToken=$(schema_init_recorded_token "$CONTAINER_SCHEMA_INITIALISED")
  if [ "$recordedToken" != "$SCHEMA_INIT_RUN_TOKEN" ]; then
    config_fatal "OFBIZ_SCHEMA_INIT=true found a schema-init receipt from a different run in $CONTAINER_SCHEMA_INITIALISED. This run applied no schema, so exiting successfully would release the fleet against a database this run never touched."
  fi
}

###############################################################################
# Hand the configuration volume back in serving mode once initialisation has succeeded.
#
# Init mode renders config/entityengine.xml with both startup DDL flags true, and /ofbiz/config is a
# declared volume in the Dockerfile, so that file outlives the container that wrote it. Whatever starts
# next from the same volume would read a DDL-enabled configuration - which is precisely the state a
# serving instance must never be in. Rather than leave the correction to the next start, the override is
# put back into serving mode here, in the one place where the credentials the re-render needs are still
# held as this shell's variables and the outcome can still fail the init job.
#
# configure_database is reused rather than reimplemented, so the same dispatch applies: the managed
# template when the environment configures a database, the committed configuration when only
# distributed cache clear is asked for, and nothing at all otherwise. The requested mode is restored
# afterwards because _main still has to decide, from that same flag, whether to exit instead of serving.
restore_serving_mode_after_schema_init() {
  local requestedMode="$RESOLVED_SCHEMA_INIT"

  # Lowering the mode flag is what makes the re-render write the serving DDL mode, but it also makes
  # resolve_database_identities see an init credential on what looks like a serving instance. This states
  # that the two are the same execution, so that check applies its serving rules to the RENDER without
  # applying its refusal to the container. Cleared immediately afterwards, on every path out of the
  # re-render: config_fatal terminates the script, so there is no path that leaves it set and continues.
  SCHEMA_INIT_RESTORING_SERVING_MODE="true"
  RESOLVED_SCHEMA_INIT="false"
  configure_database
  require_serving_mode_ddl_safety
  RESOLVED_SCHEMA_INIT="$requestedMode"
  SCHEMA_INIT_RESTORING_SERVING_MODE="false"

  if [ -f "$ENTITY_ENGINE_OVERRIDE" ]; then
    printf '%s\n' "OFBIZ_SCHEMA_INIT=true: re-rendered $ENTITY_ENGINE_OVERRIDE with startup DDL disabled, so the configuration volume this job leaves behind is safe for a serving instance to read."
  fi
}

###############################################################################
# Report whether the mounted configuration, rather than the environment, supplies a required secret.
#
# Only ever true on the OFBIZ_SKIP_INIT path, which is the one case where this container may not be the
# one that was given the secrets: the deployment is allowed to have provisioned the key material into
# /ofbiz/config on an earlier start, or from another container, and to supply neither variable to this
# one. DOCKER.adoc documents that shape as supported, and the renderers implement it.
#
# The conditions below are deliberately the SAME ones the renderers apply, so this gate can never accept
# a shape the renderer would then refuse (or the reverse):
#  - render_admin_key_configuration keeps a provisioned ofbiz.admin.key when OFBIZ_ADMIN_KEY is empty and
#    the admin key override declares the property;
#  - render_security_configuration keeps the provisioned signing keys only when BOTH variables are empty
#    and BOTH properties are declared - both or neither - because a half-provisioned pair would otherwise
#    leave one key kept and the other freshly generated, and a generated login key cannot decrypt
#    material the provisioned one encrypted.
# Presence is all that is established here. Whether what the file declares is actually usable - long
# enough, not the published 'NA' default - is proven by require_preprovisioned_runtime_configuration
# after the rendering, which is also where it is reported per property.
#
# Tracing is suspended for the whole function, and the result is returned as an exit status through a
# single exit point, for the reason set out on validate_required_secrets: each '[ -z "$OFBIZ_..." ]' is
# expanded before it runs, so under 'set -x' the test would echo the value it was only meant to measure.
# $1 - the environment variable a required secret would normally arrive in
preprovisioned_declaration_supplies() {
  hide_secrets
  local supplied=1

  if [ "${RESOLVED_SKIP_INIT:-}" = 'true' ]; then
    case "$1" in
      OFBIZ_ADMIN_KEY)
        if [ -z "$OFBIZ_ADMIN_KEY" ] \
          && config_declares_property "$ADMIN_KEY_OVERRIDE" 'ofbiz\.admin\.key'; then
          supplied=0
        fi
        ;;
      OFBIZ_LOGIN_SECRET_KEY | OFBIZ_JWT_TOKEN_KEY)
        if [ -z "$OFBIZ_LOGIN_SECRET_KEY" ] && [ -z "$OFBIZ_JWT_TOKEN_KEY" ] \
          && config_declares_property "$SECURITY_PROPERTIES_OVERRIDE" 'login\.secret_key_string' \
          && config_declares_property "$SECURITY_PROPERTIES_OVERRIDE" 'security\.token\.key'; then
          supplied=0
        fi
        ;;
    esac
  fi

  restore_trace
  return "$supplied"
}

###############################################################################
# Report EVERY required secret that has not been supplied, in one message, before any of them is used.
#
# What is required depends on the profile and on whether a managed database is configured:
#  - the three signing and admin secrets are required in the prod profile only; the dev profile
#    generates them, which is what keeps an unconfigured local container working;
#  - the three managed database passwords are required in BOTH profiles whenever OFBIZ_POSTGRES_HOST is
#    set, because they have no defaults left to fall back to.
#
# Required is not the same as "in the environment". On the OFBIZ_SKIP_INIT path a deployment secret may
# have been provisioned into /ofbiz/config instead, and the renderers accept it from there, so this gate
# accepts it from there too - see preprovisioned_declaration_supplies, which holds the conditions both
# sides share. The managed database passwords have no such second source: they are substituted into the
# entity engine configuration by configure_database and are only ever read from the environment.
#
# Only presence is reported in aggregate: a value that was supplied but is too weak is refused one at a
# time by validate_secret_strength, because that message describes the value and must stay attached to
# the single variable it describes.
#
# The presence tests run inside a secret-handling region. The test that decides whether a variable is
# empty still READS it, and 'set -x' echoes each command with its arguments ALREADY expanded, so
# '[ -z "${!name}" ]' would publish the secret itself to stderr. Tracing is suspended for the tests and
# resumed before the report, which names variables only.
validate_required_secrets() {
  local missing=''
  local name

  hide_secrets
  if [ "$OFBIZ_PROFILE" = 'prod' ]; then
    for name in OFBIZ_ADMIN_KEY OFBIZ_LOGIN_SECRET_KEY OFBIZ_JWT_TOKEN_KEY; do
      if [ -n "${!name}" ]; then
        continue
      fi
      if preprovisioned_declaration_supplies "$name"; then
        continue
      fi
      missing="$missing $name"
    done
  fi

  if [ -n "$OFBIZ_POSTGRES_HOST" ]; then
    for name in OFBIZ_POSTGRES_OFBIZ_PASSWORD OFBIZ_POSTGRES_OLAP_PASSWORD \
      OFBIZ_POSTGRES_TENANT_PASSWORD; do
      if [ -z "${!name}" ]; then
        missing="$missing $name"
      fi
    done
  fi
  restore_trace

  if [ -n "$missing" ]; then
    # The remedy differs by path, so the message does too: without OFBIZ_SKIP_INIT the environment is the
    # only source there is, while with it the mounted configuration is a second one for the three
    # deployment secrets - and naming a source that does not apply is as unhelpful as naming none.
    local remedy='Supply every one of them through its environment variable and start again.'
    if [ "${RESOLVED_SKIP_INIT:-}" = 'true' ]; then
      remedy="Supply every one of them through its environment variable, or - because OFBIZ_SKIP_INIT is set - let the configuration this container mounts declare it: ofbiz.admin.key in $ADMIN_KEY_OVERRIDE for OFBIZ_ADMIN_KEY, and BOTH login.secret_key_string AND security.token.key in $SECURITY_PROPERTIES_OVERRIDE for OFBIZ_LOGIN_SECRET_KEY and OFBIZ_JWT_TOKEN_KEY, which are accepted from the file only together so that a half-provisioned pair cannot leave one key kept and the other generated. The managed database passwords are never read from the configuration and must always come from the environment. See DOCKER.adoc."
    fi
    config_fatal "The following required secret(s) were not supplied:$missing. OFBIZ_PROFILE=$OFBIZ_PROFILE requires every one of them. $remedy"
  fi
}

###############################################################################
# The fail-closed path for a container started with OFBIZ_SKIP_INIT.
#
# With no secret in the environment the renderers keep the value the mounted config/ override already
# declares instead of substituting a new one, and a value that is kept never passes through
# resolve_secret. Skipping the DATA INITIALIZATION is legitimate; skipping the VALIDATION is not. Each
# required secret is therefore accepted from either source - the environment, or the externally
# provisioned configuration file mounted in its place - and the start up is refused only when neither
# supplies it. config/ takes class path precedence over the copies inside ofbiz.jar, so that override is
# where a provisioned value has to be declared. Only the presence of a non-empty declaration is tested;
# no value is read out of the file or printed.
#
# The tests run inside a secret-handling region for the same reason as in validate_required_secrets: each
# '[ -z "$OFBIZ_..." ]' below is expanded before it runs, so under OFBIZ_TRACE - or an inherited
# SHELLOPTS=xtrace - the test itself would echo the value it was only meant to measure. grep runs in quiet
# mode and is pointed at a file rather than handed a value, so it publishes nothing either way, and the
# report below names variables only.
validate_externally_provisioned_secrets() {
  local missing=''

  hide_secrets
  # config/ takes class path precedence over the copies inside ofbiz.jar, which is where an externally
  # provisioned configuration is mounted, so that is where a supplied value has to be declared.
  if [ -z "$OFBIZ_ADMIN_KEY" ] \
    && ! grep --quiet '^ofbiz\.admin\.key=.' "$ADMIN_KEY_OVERRIDE" 2>/dev/null; then
    missing="$missing OFBIZ_ADMIN_KEY(or ofbiz.admin.key in $ADMIN_KEY_OVERRIDE)"
  fi
  if [ -z "$OFBIZ_LOGIN_SECRET_KEY" ] \
    && ! grep --quiet '^login\.secret_key_string=.' "$SECURITY_PROPERTIES_OVERRIDE" 2>/dev/null; then
    missing="$missing OFBIZ_LOGIN_SECRET_KEY(or login.secret_key_string in $SECURITY_PROPERTIES_OVERRIDE)"
  fi
  if [ -z "$OFBIZ_JWT_TOKEN_KEY" ] \
    && ! grep --quiet '^security\.token\.key=.' "$SECURITY_PROPERTIES_OVERRIDE" 2>/dev/null; then
    missing="$missing OFBIZ_JWT_TOKEN_KEY(or security.token.key in $SECURITY_PROPERTIES_OVERRIDE)"
  fi
  restore_trace

  if [ -n "$missing" ]; then
    config_fatal "OFBIZ_SKIP_INIT was set with OFBIZ_PROFILE=prod, but the following required secret(s) are supplied by neither the environment nor the provisioned configuration:$missing. Refusing to serve traffic with an unconfigured secret."
  fi
}

###############################################################################
# Send a shutdown signal to OFBiz
shutdown_ofbiz() {
  /ofbiz/send_ofbiz_stop_signal.sh
}

###############################################################################
# Handle SIGTERM/SIGINT received while this script is still initialising.
#
# Ends the process. See the trap installation near the top of this file for why returning from the
# handler is not an option.
#
# The signal is forwarded to the initialisation child this script is waiting on, if there is one, and
# the child is reaped before exiting, so a data-load JVM is asked to stop rather than being orphaned
# or killed abruptly. When no child is running the documented shutdown helper is invoked instead -
# docker/send_ofbiz_stop_signal.sh states that this script calls it from its termination trap - and
# its failure is tolerated, because during initialisation there is no listening admin port for it to
# reach (bin/ofbiz --load-data resolves load-data.properties, which declares no ofbiz.admin.port).
#
# The handler disarms itself first so a second signal cannot restart it half way through.
# $1 - signal name without the SIG prefix, $2 - signal number
handle_termination_signal() {
  local signalName="$1"
  local signalNumber="$2"

  trap '' SIGTERM SIGINT

  printf '%s\n' "Received SIG$signalName during initialisation. Stopping without starting OFBiz." >&2

  if [ -n "$ACTIVE_CHILD_PID" ] && kill -0 "$ACTIVE_CHILD_PID" 2>/dev/null; then
    kill -s "$signalName" "$ACTIVE_CHILD_PID" 2>/dev/null || true
    wait "$ACTIVE_CHILD_PID" 2>/dev/null || true
  else
    shutdown_ofbiz || true
  fi

  discard_secret_temp_files
  exit $((128 + signalNumber))
}

###############################################################################
# Run one initialisation child - always an OFBiz data-load JVM - so that it can be signalled.
#
# The child is started in the background and waited for, which is what gives the termination handler
# a concrete process id to forward a signal to; running it in the foreground would leave the handler
# with nothing to address and the JVM would keep working after the container had been asked to stop.
# The child's exit status is returned unchanged, so 'set -e' still aborts the start up when a data
# load fails.
#
# Every child is launched through 'env -u' so that no injected secret or database password appears in
# its environment. This function is the single place any initialisation JVM is started from, which is
# what makes the guarantee complete: adding a call here cannot forget to sanitise, and the sanitising
# happens before the first child process exists rather than in the unset block that runs later.
# 'env -u NAME' is a no-op for a variable that is not set, so the list is applied unconditionally.
# $@ - the command and arguments to run
run_initialisation_child() {
  local status=0

  # The child is launched in a SUBSHELL that unsets the variables itself rather than through 'env -u'.
  #
  # 'env' replaces the process image, so it can only ever start an external program: it cannot see a
  # shell function, and this entry point is exercised with /ofbiz/bin/ofbiz substituted by one. A
  # subshell keeps the sanitising guarantee exactly as strong - the unsets happen inside the subshell,
  # before the command runs, and cannot disturb this shell's own environment - while still allowing a
  # function to stand in for the loader. An external command is exec'd from the subshell, so
  # ACTIVE_CHILD_PID stays the pid of the JVM itself and a forwarded stop signal reaches the process
  # doing the work instead of an intermediate shell.
  (
    for sanitisedVariable in "${CHILD_SANITISED_VARIABLES[@]}"; do
      unset "$sanitisedVariable"
    done
    childKind=$(type -t "$1" 2>/dev/null || true)
    if [ "$childKind" = "function" ]; then
      "$@"
    else
      exec "$@"
    fi
  ) &
  ACTIVE_CHILD_PID=$!
  wait "$ACTIVE_CHILD_PID" || status=$?
  ACTIVE_CHILD_PID=""

  return "$status"
}

###############################################################################
# Record which of the runtime-applied variables the operator supplied.
#
# Must be called before ofbiz_setup_env, because that function applies defaults and from then on a
# variable being non-empty says nothing about where the value came from.
#
# TRACING IS SUSPENDED FOR THE WHOLE LOOP, because asking whether a variable is empty is a
# secret-handling operation even though the answer is only a name. Every name in
# SECRET_ENVIRONMENT_VARIABLES is also in RUNTIME_APPLIED_VARIABLES - the admin key and password, the
# two signing keys, the six database passwords, the object store's two credentials and the broker
# password - and '[ -n "${!variableName:-}" ]' expands the VALUE before the test runs. Under 'set -x' bash echoes the
# expanded command, so with OFBIZ_TRACE enabled this loop published every supplied secret to the
# container log as '+ [ -n <the secret> ]'. The names it appends are safe to print and are printed
# later by the OFBIZ_SKIP_INIT advisory; the values must never be, so the suspension covers the entire
# loop rather than a single expansion. hide_secrets/restore_trace are reference counted through
# SECRET_REGION_DEPTH, so this remains correct if a future caller has already suspended tracing.
record_supplied_variables() {
  local variableName

  hide_secrets
  for variableName in "${RUNTIME_APPLIED_VARIABLES[@]}"; do
    if [ -n "${!variableName:-}" ]; then
      SUPPLIED_VARIABLES+=("$variableName")
    # A name that is DECLARED and empty is recorded separately rather than dropped. The '+' form asks
    # only whether the name exists, so this is the one point in the start up at which "set to nothing"
    # is still distinguishable from "not set": ofbiz_setup_env runs next and resolves both to the same
    # default. See SUPPLIED_BLANK_VARIABLES.
    elif [ -n "${!variableName+set}" ]; then
      SUPPLIED_BLANK_VARIABLES+=("$variableName")
    fi
  done
  SUPPLIED_VARIABLES_RECORDED="true"
  restore_trace
}

###############################################################################
# Report whether a configuration file already declares a property with a non-empty value.
#
# Used to establish what a pre-provisioned configuration contains without ever reading a secret into a
# variable: grep runs in quiet mode and the trailing '.' of the pattern requires at least one character
# after the '=', so a declared-but-blank property correctly counts as absent. That distinction matters
# because java.util.Properties returns a declared empty value instead of falling back to a default, so
# a blank key is not "unset" to OFBiz - it is an empty key, which is worse than none.
# $1 - file, $2 - property name as a basic regular expression
config_declares_property() {
  if [ ! -f "$1" ]; then
    return 1
  fi
  if grep --quiet "^$2=." "$1"; then
    return 0
  fi
  return 1
}

###############################################################################
# Read back the value a configuration file declares for a property.
#
# Used only on the OFBIZ_SKIP_INIT path, where a signing key or the admin shared secret may have been
# provisioned into /ofbiz/config by an earlier start or by another container rather than supplied in
# the environment. The value is a secret, so tracing is suspended for the whole function and the result
# is written to stdout for the caller to capture into a local variable rather than into a global.
#
# The extraction matches the anchored 'key=' form this script renders and strips a trailing carriage
# return, mirroring what java.util.Properties would hand the application. The last declaration wins,
# which is also how java.util.Properties resolves a duplicated key.
# $1 - file, $2 - property name as a basic regular expression
declared_property_value() {
  hide_secrets
  local value

  # Both guards exist because the file was proven to declare this property by a SEPARATE earlier call to
  # config_declares_property, and a concurrent start on a shared /ofbiz/config volume can delete it in
  # between (see require_rendered_file_present). sed's own stderr is suppressed for the same reason it is
  # replaced there: unread, it is the only thing an operator would see.
  #
  # An empty result is the second half of that race and has to be refused too, not returned. It cannot
  # happen otherwise - config_declares_property requires a character after the '=' - and returning it
  # would hand a renderer an empty signing key, which java.util.Properties delivers to the application as
  # a declared empty value rather than an absent one.
  #
  # config_fatal runs here inside the caller's command substitution, so its exit ends this subshell; the
  # callers are plain assignments, which take that status and abort the start through 'set -e' after the
  # ERROR has been printed. No caller may capture this value in a context that discards the status.
  require_rendered_file_present "$1"
  value=$(sed --quiet "s|^$2=||p" "$1" 2>/dev/null | tail --lines=1)
  value=${value%%$'\r'*}
  if [ -z "$value" ]; then
    config_fatal "$1 no longer declares a value for the property matched by '$2', although it did when this start checked a moment ago. The most likely cause is a second container starting at the same time against the same /ofbiz/config volume. Start one container at a time against a shared configuration volume, or give each instance its own; then start again."
  fi
  printf '%s' "$value"
  restore_trace
}

###############################################################################
# Decide whether a property already declared in a pre-provisioned file holds a USABLE secret.
#
# Presence is not usability. config_declares_property above answers only "is there at least one
# character after the '='", which accepts 'ofbiz.admin.key=NA' - the published code default this whole
# mechanism exists to reject - as well as a one character login key or a short JWT key that
# JWTManager.getJWTKey will refuse at the first sign in. The rendering path applies
# secret_is_usable to every secret it injects, so the pre-flight for the path that skips that
# rendering has to apply exactly the same test to the value the operator provisioned instead. The
# minimum length and forbidden characters are passed in by the caller from the same constants the
# rendering path uses, so the two can never drift apart.
#
# The value is read and tested with tracing suspended and is never printed, returned or assigned to
# anything outside this function: only the name of the property, the file and the environment variable
# that would normally have supplied it ever reach the log. The explanation is published in
# SECRET_REJECTION_REASON, which by construction describes the value without containing it.
#
# WHAT IS EXTRACTED IS WHAT java.util.Properties WOULD DELIVER, in three respects, because validating
# anything else would either reject a file OFBiz reads perfectly well or accept one it does not:
#
#   * The LAST declaration wins. Properties.load puts each key as it reads the file, so a later
#     duplicate overwrites an earlier one; testing the first would test a value OFBiz will not use.
#   * A carriage return ends the value. Properties treats CR, LF and CRLF alike as line terminators, so
#     a file saved with Windows line endings yields a clean value to OFBiz. Keeping the CR would fail
#     such a file on the control-character rule for a formatting artefact OFBiz never sees.
#   * Blanks between the '=' and the value are not part of it. Properties skips them, so keeping them
#     would let 'key=            abc' satisfy a minimum length that the three-character value OFBiz
#     actually loads does not.
#
# EVERY SEPARATOR Properties HONOURS IS MATCHED, not just the '=' this script renders, and that is a
# security requirement rather than tidiness. Properties accepts '=', ':' or plain whitespace, with
# optional blanks around it and before the key. A file declaring 'ofbiz.admin.key=<a real secret>' and
# then 'ofbiz.admin.key:NA' resolves to NA in OFBiz - verified against java.util.Properties - so an
# extraction that only understood '=' would read the real secret, pronounce the file fit and let the
# instance start on the published default. Matching every shape means a later declaration can never
# hide behind an earlier one.
#
# config_declares_property above still defines PRESENCE as the 'name=value' shape this script renders.
# The asymmetry is deliberate and fails closed in both directions: a secret declared only with ':' is
# reported as absent - the operator is told exactly what to declare - while a bad value declared with
# ':' is caught here.
#
# One limitation, and it also fails closed: a value written across Properties continuation lines is
# judged on its first physical line, so it is likelier to be rejected than accepted. Nothing this
# script renders is ever continued.
#
# Returns 0 when the declared value is usable, 1 otherwise.
# $1 - file, $2 - property name as a regular expression, $3 - minimum length,
# $4 - forbidden characters (may be empty)
preprovisioned_secret_is_usable() {
  hide_secrets
  local file="$1"
  local property="$2"
  local minLength="$3"
  local forbidden="$4"
  # Local, so the value ceases to exist when this function returns.
  local value
  local usable=1

  # The separator group must match at least one character, so that a longer key sharing this one's
  # prefix - 'ofbiz.admin.keyring' against 'ofbiz.admin.key' - cannot be mistaken for it.
  #
  # '#' delimits this expression rather than the '|' used everywhere else in this script, precisely
  # because the pattern itself contains a '|' alternation, which would otherwise end the s command. It
  # is safe here for the reason '|' is safe there: the delimiter only has to avoid the pattern and the
  # replacement, and this pattern is built solely from a script-internal property-name regex while the
  # replacement is empty. No provisioned value is ever part of either.
  value=$(sed --regexp-extended --quiet \
    "s#^[[:blank:]]*$property([[:blank:]]*[=:]|[[:blank:]])[[:blank:]]*##p" "$file" | tail --lines=1)
  value=${value%%$'\r'*}
  if secret_is_usable "$value" "$minLength" "$forbidden"; then
    usable=0
  fi

  restore_trace
  return "$usable"
}

###############################################################################
# Pre-flight for OFBIZ_SKIP_INIT.
#
# The flag skips the DATA initialization only: no data is loaded and no admin user is created, and the
# configuration is still rendered. What it changes here is WHERE the key material comes from - with it
# set the renderers keep whatever the mounted configuration already declares instead of resolving a value
# out of the environment, and a kept value bypasses resolve_secret and the strength tests an environment
# value has to pass.
#
# The prod invariant is therefore placed on the RESULT: the override files the renderers KEPT rather than
# rewrote must ALREADY declare a usable value for each required secret. Only the /ofbiz/config overrides
# count. They are the copies that take class path precedence and the ones an operator provisions on the
# declared volume, whereas the source-tree copies declare no deployment key material at all.
#
# In the dev profile nothing is required, so a developer can still start a pre-provisioned container
# with no secrets configured.
#
# Every failure is collected before reporting, and the message names the property, the file that must
# declare it and the variable that would normally have supplied it - never a value.
require_preprovisioned_runtime_configuration() {
  local missing=()
  local unusable=()

  if [ "$OFBIZ_PROFILE" = "prod" ]; then
    # Three inputs, each checked twice: is the property declared at all, and is what it declares
    # actually a secret. The second question is the one that matters, because the value that satisfies
    # the first can be the published 'NA' default.
    if ! config_declares_property "$ADMIN_KEY_OVERRIDE" 'ofbiz\.admin\.key'; then
      missing+=("ofbiz.admin.key in $ADMIN_KEY_OVERRIDE, normally injected from OFBIZ_ADMIN_KEY")
    elif ! preprovisioned_secret_is_usable "$ADMIN_KEY_OVERRIDE" 'ofbiz\.admin\.key' \
      "$ADMIN_KEY_MIN_LENGTH" "$ADMIN_KEY_FORBIDDEN"; then
      unusable+=("ofbiz.admin.key in $ADMIN_KEY_OVERRIDE $SECRET_REJECTION_REASON (normally injected from OFBIZ_ADMIN_KEY)")
    fi
    if ! config_declares_property "$SECURITY_PROPERTIES_OVERRIDE" 'login\.secret_key_string'; then
      missing+=("login.secret_key_string in $SECURITY_PROPERTIES_OVERRIDE, normally injected from OFBIZ_LOGIN_SECRET_KEY")
    elif ! preprovisioned_secret_is_usable "$SECURITY_PROPERTIES_OVERRIDE" 'login\.secret_key_string' \
      "$SIGNING_KEY_MIN_LENGTH" ''; then
      unusable+=("login.secret_key_string in $SECURITY_PROPERTIES_OVERRIDE $SECRET_REJECTION_REASON (normally injected from OFBIZ_LOGIN_SECRET_KEY)")
    fi
    if ! config_declares_property "$SECURITY_PROPERTIES_OVERRIDE" 'security\.token\.key'; then
      missing+=("security.token.key in $SECURITY_PROPERTIES_OVERRIDE, normally injected from OFBIZ_JWT_TOKEN_KEY")
    elif ! preprovisioned_secret_is_usable "$SECURITY_PROPERTIES_OVERRIDE" 'security\.token\.key' \
      "$SIGNING_KEY_MIN_LENGTH" ''; then
      unusable+=("security.token.key in $SECURITY_PROPERTIES_OVERRIDE $SECRET_REJECTION_REASON (normally injected from OFBIZ_JWT_TOKEN_KEY)")
    fi
  fi

  # Reported separately, because the two failures call for different actions: an absent property has to
  # be provisioned, whereas a declared one has to be REPLACED - and an operator told only "requires a
  # pre-provisioned ofbiz.admin.key" about a file that visibly contains one has been told nothing.
  local entry
  for entry in "${missing[@]}"; do
    printf '%s\n' "ERROR: OFBIZ_PROFILE=prod with OFBIZ_SKIP_INIT requires a pre-provisioned $entry." >&2
  done
  for entry in "${unusable[@]}"; do
    printf '%s\n' "ERROR: OFBIZ_PROFILE=prod with OFBIZ_SKIP_INIT found a pre-provisioned $entry." >&2
  done

  if [ $((${#missing[@]} + ${#unusable[@]})) -gt 0 ]; then
    config_fatal "OFBIZ_SKIP_INIT means this container may not be the one that was given the secrets, so the mounted configuration has to declare a usable value for each of them already. The requirements are the same ones the rendering path enforces: at least $ADMIN_KEY_MIN_LENGTH characters for the admin key and $SIGNING_KEY_MIN_LENGTH for the signing keys, no control characters, no ':' in the admin key, at least $MIN_DISTINCT_CHARACTERS distinct characters, and never the 'NA' code default. Correct the files listed above, or unset OFBIZ_SKIP_INIT and supply the secrets through the environment. See DOCKER.adoc."
  fi

  # Advisory, not an error: the combination is legitimate, and refusing it would break a deployment
  # that passes one environment block to every container and skips initialisation on all but the
  # first. What must not happen is that it stays invisible - a supplied setting that was silently
  # dropped looks exactly like one that was applied, right up to the point where something rejects it.
  #
  # Reported by NAME and restricted to the variables the flag really makes ineffective. Everything else
  # in RUNTIME_APPLIED_VARIABLES is still rendered on this path, so naming the whole supplied set as
  # ignored would be the opposite of accurate.
  local ineffective=()
  local candidate
  for candidate in "${SKIP_INIT_INEFFECTIVE_VARIABLES[@]}"; do
    if printf '%s\n' "${SUPPLIED_VARIABLES[@]}" | grep --quiet --line-regexp --fixed-strings "$candidate"; then
      ineffective+=("$candidate")
    fi
  done
  if [ ${#ineffective[@]} -gt 0 ]; then
    printf '%s\n' "WARNING: OFBIZ_SKIP_INIT is set, so this container performs no data initialisation. The following supplied variables configure only the work that was skipped and therefore have NO effect on this instance: ${ineffective[*]}. The configuration and the deployment secrets have still been rendered." >&2
  fi
}

###############################################################################
# Remove every container configuration variable from the environment the OFBiz JVM inherits.
#
# Driven from RUNTIME_APPLIED_VARIABLES and CONTAINER_CONTROL_VARIABLES rather than from a hand written
# list of unset statements, because the two have to agree and a hand written list is exactly what drifts:
# OFBIZ_DISABLE_COMPONENTS and OFBIZ_POSTGRES_HOST were once missed by it, and were therefore the only
# container settings still visible to the served JVM after this script had already consumed them and
# written the result into the rendered configuration. Neither is a secret, but a stale value that no
# longer describes what the instance is actually running is a debugging trap. Withdrawing what the two
# arrays name makes the agreement a property of the code instead of an intention: a variable this script
# consumes is in one of them, so it is withdrawn, and a variable that is in neither is not consumed here
# at all.
#
# THE SECRETS, first of all - every name SECRET_ENVIRONMENT_VARIABLES declares. The admin key and
# password, the two signing keys, the six database passwords including the schema-initialisation ones,
# the object store's two credentials and the broker password have all been written into mode 0600 files
# that the application reads. An environment variable is readable
# through /proc/<pid>/environ, and any child process, crash handler or diagnostic dump that reports the
# environment would otherwise republish them. The schema-initialisation identity matters most: it is the
# one credential in the deployment that can alter the schema, and the process about to be exec'd is the
# one that must never be able to.
#
# THE REST GO TOO, for a different reason: nothing downstream may be able to read one of these and reach
# a different conclusion about what this instance is doing than the rendered configuration states. The
# entity engine, the service engine and the content-store providers all read their configuration from
# the class path only, never the environment, so removing the names changes no behaviour - it only
# removes the possibility of a stale answer. The database host is withdrawn with the rest of the
# datasource for that reason: it is the value that tells a reader of /proc/<pid>/environ exactly which
# managed database this fleet talks to.
#
# SIX AWS_ PREFIXED NAMES ARE DELIBERATELY NOT TOUCHED: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY,
# AWS_WEB_IDENTITY_TOKEN_FILE, AWS_REGION, AWS_MAX_ATTEMPTS and AWS_RETRY_MODE all belong to the SDK's
# own default credential and retry chains - which is how an instance profile, an ECS task role or an EKS
# service account supplies short lived credentials - and they are resolved inside the JVM. Removing them
# would break the recommended production configuration.
#
# No value is ever expanded here, only names, so this needs no secret-hiding region: a traced run prints
# the name being withdrawn and never what it held.
withdraw_container_configuration() {
  local variableName

  for variableName in "${RUNTIME_APPLIED_VARIABLES[@]}" "${CONTAINER_CONTROL_VARIABLES[@]}"; do
    unset "$variableName"
  done
}

_main() {
  # FIRST, ahead of everything - including the sub-command dispatch below, which itself runs mkdir and
  # touch. Every step after this point forks, and a child inherits the exported environment, so the
  # secrets have to stop being exported before the first fork rather than before the last one. Nothing
  # is resolved or validated here and no value is read, so this is safe to precede even the build-time
  # sub-command; see capture_secret_environment.
  capture_secret_environment

  # Build-time bookkeeping, handled next because it must NOT resolve a profile or a
  # secret: see write_initial_container_state. It writes two container state markers and exits, so it
  # can never start a server, and it refuses to run against a managed database.
  #
  # Written as a 'case' rather than an 'if' deliberately. This is a dispatch on the sub-command, not a
  # branch in the start-up flow, and the start-up flow's first conditional is the one that decides
  # whether the data initialisation runs - which every setup step, ofbiz_setup_env included, must
  # precede. Keeping the two shapes distinct is what makes that ordering checkable.
  case "${1:-}" in
  --write-initial-container-state)
    write_initial_container_state
    exit 0
    ;;
  esac

  # FIRST, before any other work at all. The profile decides whether a missing, weak or published
  # credential aborts the start, so nothing that consults it may run until it has been resolved and
  # validated. An unset value resolves to the development profile and says so on stderr, which keeps the
  # zero-configuration start working without settling that decision silently; a value that is neither
  # profile is refused here.
  require_profile

  # Checked unconditionally, because a driver jar left in lib-extra takes class path precedence over the
  # bundled driver whether or not the data initialisation is skipped.
  guard_against_stale_jdbc_drivers

  # Also unconditional: both of these decide what the container does rather than merely how it does it -
  # whether the data initialisation runs, and whether the container serves traffic at all - so they are
  # parsed and validated before any work, not discovered part way through it.
  resolve_skip_init

  # Recorded before any default is applied, so the advisory on the skip path can name what the operator
  # actually supplied rather than what this script computed.
  record_supplied_variables

  # Everything from here to the data load runs on EVERY start, including the one that skips the data
  # initialisation. All of it is idempotent - each rendered file is derived from the pristine source, not
  # from the previous render - and all of it is security relevant: this is where the secrets are
  # resolved and written into the mode 0600 overrides in /ofbiz/config, where the datasource credentials
  # and TLS mode are validated, and where the load balancer settings are applied. Skipping it, as
  # OFBIZ_SKIP_INIT asks for, would leave a restarted instance serving on stale or absent key material.
  ofbiz_setup_env

  # After ofbiz_setup_env, because OFBIZ_PROFILE must carry its resolved value before the requirement
  # can be decided from it, and before the first secret is used, so the whole missing set is reported in
  # one message. See validate_required_secrets for what is required in each profile.
  validate_required_secrets

  # Before resolve_entity_engine_flags, not after, and unconditional for the same two reasons the
  # object-store resolution below is. Ordering: resolve_entity_engine_flags validates that distributed
  # cache invalidation has a transport, and this is what renders one, so rendering has to happen first or
  # the validation would judge the previous start's configuration. Unconditional: /ofbiz/config is a
  # volume that outlives the container, so this is also what REMOVES a transport override left by an
  # earlier start once the operator withdraws the variables - an instance still subscribing to a broker
  # nobody configures any more is exactly the kind of state a stateless fleet must not be able to
  # inherit. It renders nothing, and removes nothing it did not write, when no transport is configured.
  resolve_cache_transport_configuration
  render_cache_transport_configuration

  # The schema-init flag decides whether this container serves traffic at all, so it is validated before
  # any work is done rather than discovered part way through it. It is also where the cache-invalidation
  # transport rendered above is checked for a subscriber, a loadable client and a reachable broker.
  resolve_entity_engine_flags

  # Resolved here, ahead of every render, and on every path. Where durable content is written decides
  # whether this instance is replaceable at all, so a mistyped provider, an endpoint carrying a
  # credential or a half-configured credential pair is refused before any work is done rather than
  # silently degraded to database storage. The render itself is part of apply_configuration below, which
  # also runs on every path - including the one that skips the data initialisation - because
  # /ofbiz/config is a volume that outlives the container and a withdrawn setting has to stop applying.
  resolve_content_store_configuration

  create_ofbiz_runtime_directories
  configure_database

  # The last word on DDL safety, and the reason configure_database is not sufficient on its own: this
  # checks the file OFBiz will actually read, whoever wrote it and whether or not anything was rendered.
  # A config/entityengine.xml left on the volume by an earlier OFBIZ_SCHEMA_INIT=true run has BOTH DDL
  # flags true, so without this a serving instance could inherit it and issue CREATE and ALTER statements
  # against the managed database. Exempt in init mode, which is the one execution meant to apply DDL.
  require_serving_mode_ddl_safety

  apply_configuration

  # OFBIZ_SKIP_INIT skips exactly this: the one-off population of a database that another container has
  # already loaded. Nothing above depends on it.
  if [ "$RESOLVED_SKIP_INIT" = "true" ]; then
    printf '%s\n' "OFBIZ_SKIP_INIT=true: skipping the data load and the admin user load. The configuration and the deployment secrets have still been resolved and rendered."
    # The requirement is placed on the RESULT - the files the instance will actually read - rather than
    # on the environment, because on this path the key material may legitimately have been provisioned
    # by an earlier start or by another container rather than supplied to this one.
    require_preprovisioned_runtime_configuration
    # A value the renderers KEPT never passes through resolve_secret, so nothing above has tested it;
    # this is what validates it, and only in the prod profile, so a developer's skip-init container keeps
    # working. OFBIZ_PROFILE is read without a ':-dev' fallback because require_profile has already
    # resolved it to exactly 'dev' or 'prod'.
    if [ "$OFBIZ_PROFILE" = 'prod' ]; then
      validate_externally_provisioned_secrets
    fi
  elif [ "$RESOLVED_SCHEMA_INIT" = "true" ]; then
    # The one-shot schema-init job. It shares the configuration rendering above with the serving path -
    # the schema has to be applied to exactly the database, with exactly the credentials, the fleet will
    # use - but it takes NEITHER load_data nor load_admin_user. Those are the generic data-load and
    # administrative-user flows, gated on their own markers, and running them here is what made the init
    # job load seed data, provision a login, and - on a state volume from an earlier start - report
    # success without having applied anything. initialise_schema replaces both: it applies the DDL in a
    # child whose log is examined, and then verifies the result against the database with the startup
    # DDL turned off.
    initialise_schema
  else
    load_data
    load_admin_user
  fi

  # Before the unset block, because both of these read OFBIZ_DATA_LOAD and the datasource identity the
  # record is bound to. An init run that gets this far without having applied the schema must fail rather
  # than exit 0, and the configuration volume must be handed back in serving mode, so both happen while
  # the evidence and the database credentials are still held as this shell's variables.
  if [ "$RESOLVED_SCHEMA_INIT" = "true" ]; then
    require_schema_init_completed
    restore_serving_mode_after_schema_init
  fi

  withdraw_container_configuration

  # Schema initialisation is a one-shot job, not a way to start a server. Reaching this point in init mode
  # means initialise_schema ran the DDL child and it succeeded - it aborts the script otherwise, and it is
  # the only thing the init branch does - so the work is genuinely finished: exit successfully instead of
  # exec'ing the serving command. That is what makes the mode safe to grant DDL privileges to, since it
  # never becomes a long-lived process, and it is what lets an orchestrator run it as an init container or
  # a job that must complete before the fleet starts. Every serving instance runs with the flag unset and
  # therefore issues no DDL at all.
  #
  # The claim of success has already been verified against what actually happened by
  # require_schema_init_completed above; this exit only reports it. The epilogue below is a second,
  # independent backstop on the same fact: an orchestrator treats exit 0 from an init container as
  # "the schema is ready" and starts the fleet on the strength of it, so claiming success without
  # having applied any DDL is worse than failing - the fleet would come up against an empty database
  # and every request would fail on a missing table. Nothing should be able to reach this point in
  # init mode with SCHEMA_INIT_APPLIED still false, so it is a fail-closed backstop rather than an
  # expected path, and it exits non-zero so the job is reported as failed and can be retried.
  if [ "$RESOLVED_SCHEMA_INIT" = "true" ]; then
    if [ "$SCHEMA_INIT_APPLIED" != "true" ]; then
      printf 'ERROR: %s\n' \
        "OFBIZ_SCHEMA_INIT=true but the schema initialisation step did not run, so no DDL was applied." >&2
      printf 'ERROR: %s\n' \
        "Refusing to report success: an orchestrator would start the fleet against an uninitialised database." >&2
      exit 1
    fi
    printf '%s\n' "OFBIZ_SCHEMA_INIT=true: schema initialisation complete. Exiting without serving traffic; start the fleet with OFBIZ_SCHEMA_INIT unset."
    exit 0
  fi

  # Continue loading OFBiz.
  exec "$@"
}

# Armed here, and not next to the explanation near the top of this file, so that every function the
# handlers call is already defined. 'exec' in _main replaces this shell, so these traps cover the
# initialisation phase only and the server process then owns its own shutdown as PID 1.
trap 'handle_termination_signal TERM 15' SIGTERM
trap 'handle_termination_signal INT 2' SIGINT
trap discard_secret_temp_files EXIT

_main "$@"

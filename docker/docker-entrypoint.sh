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
# Triggers the loading of data and configuration of various OFBiz properties before
# executing the command given as arguments to the script.
#
#
# Behaviour controlled by environment variables:
#
# OFBIZ_SKIP_INIT
# Any non-empty value will cause this script to skip any initialisation steps. That includes
# rendering the configuration, so none of the secrets below are injected and OFBiz starts with
# whatever configuration already exists in /ofbiz/config. Use it only when that configuration has
# been provisioned by other means.
# Default: <empty>
#
# OFBIZ_TRACE
# Enable verbose shell tracing (set -x) of this script for troubleshooting.
# Tracing is DISABLED by default. This script handles secrets - the admin password, the admin shared
# key, the login/JWT signing keys and the database passwords - and shell tracing writes every
# expanded command, including those secret values, to stderr, which in a container is the log stream
# that is retained and commonly shipped to a log aggregator. Even when tracing is explicitly enabled
# it is force-disabled around every region that handles a secret, so a trace can never publish a
# secret value.
# Enabled when this environment variable contains a non-empty value.
# Default: <empty> (tracing disabled)
#
# OFBIZ_PROFILE
# Selects how strictly the deployment secrets below are enforced.
# - dev:  a secret that was not supplied is replaced by a cryptographically random value generated
#         for this container and kept in /ofbiz/runtime/container_state/generated_secrets so that it
#         stays stable across restarts. This keeps an unconfigured local or demo container working
#         with no configuration at all, which is how it behaved when the keys were generated into the
#         image at build time.
# - prod: a secret that was not supplied is a fatal misconfiguration and the container refuses to
#         start. A deployment that silently ran on a generated or published value would be
#         indistinguishable from one with no secret at all.
# Default: dev
#
# OFBIZ_ADMIN_USER
# The username of the OFBIZ admin user.
# Default: admin
#
# OFBIZ_ADMIN_PASSWORD
# The password of the OFBIZ admin user.
# Default: ofbiz
#
# OFBIZ_ADMIN_KEY
# The shared secret that authorises administrative requests on the OFBiz admin port, written to the
# ofbiz.admin.key property of the package qualified start.properties override in /ofbiz/config.
# Deliberately NOT passed as -Dofbiz.admin.key: a JVM system property is visible in the process table
# and in /proc/<pid>/cmdline to every process in the container's PID namespace.
# Rejected when it is shorter than 16 characters, is the publicly known 'NA' code default, uses fewer
# than 8 distinct characters, contains a control character, or contains ':' - AdminServerContainer compares
# everything before the FIRST ':' of a request with this key, so a key containing ':' could never
# match and a clean shutdown would be impossible.
# Required when OFBIZ_PROFILE=prod.
# Default: <empty> (generated per container when OFBIZ_PROFILE=dev)
#
# OFBIZ_LOGIN_SECRET_KEY
# Value of the login.secret_key_string property in security.properties, which names the EntityKeyStore
# entry used to encrypt and decrypt the temporary password in the forgot-password flow.
# Must be at least 64 characters.
# Required when OFBIZ_PROFILE=prod.
# Default: <empty> (generated per container when OFBIZ_PROFILE=dev)
#
# OFBIZ_JWT_TOKEN_KEY
# Value of the security.token.key property in security.properties, the key OFBiz signs and verifies
# JSON Web Tokens with. JWTManager rejects a key shorter than 64 characters outright, because the
# signature algorithm is HMAC512 and that needs a 512 bit key.
# Required when OFBIZ_PROFILE=prod.
# Default: <empty> (generated per container when OFBIZ_PROFILE=dev)
#
# The three secrets above are written ONLY into /ofbiz/config, atomically and with mode 0600, and are
# removed from the environment before OFBiz is executed. They are never written to the source tree,
# never included in the distribution, and never present in an image layer.
#
# OFBIZ_DATA_LOAD
# Determine what type of data loading is required.
# Default: seed
# Values:
# - none: No data loading is performed.
# - seed: Seed data is loaded.
# - demo: Demo data is loaded.
#
# OFBIZ_HOST
# Specify the hostname used to access OFBiz.
# Used to populate the host-headers-allowed property in framework/security/config/security.properties.
# Default: default value of host-headers-allowed from framework/security/config/security.properties.
#
# OFBIZ_CONTENT_URL_PREFIX
# Used to set the content.url.prefix.secure and content.url.prefix.standard properties in
# framework/webapp/config/url.properties.
# Default: <empty>
#
# OFBIZ_ENABLE_AJP_PORT
# Enable the AJP (Apache JServe Protocol) port to allow communication with OFBiz via a reverse proxy.
# Enabled when this environment variable contains a non-empty value.
# Default value: <empty>
#
# OFBIZ_SKIP_DB_DRIVER_DOWNLOAD
# OBSOLETE. Accepted for backwards compatibility with existing deployment manifests but ignored.
# The PostgreSQL JDBC driver is now bundled with the distribution (see dependencies.gradle), so this
# script never downloads a driver at runtime. The former download was removed deliberately: the
# generated start script places /ofbiz/lib-extra BEFORE /ofbiz/lib on the class path, so a jar
# downloaded into lib-extra silently shadowed - and could downgrade - the bundled driver. A driver
# left in the lib-extra volume by an older image is now treated as a fatal misconfiguration rather
# than being loaded in preference to the bundled one.
# Default: <empty>
#
# OFBIZ_POSTGRES_HOST
# Sets the name of the PostgreSQL database host.
# If OFBIZ_POSTGRES_HOST is non-empty, then the following OFBIZ_POSTGRES_* environment variables are used to configure
# access to PostgreSQL databases.
# OFBIZ_POSTGRES_OFBIZ_DB           Default: ofbiz
# OFBIZ_POSTGRES_OFBIZ_USER         Default: ofbiz
# OFBIZ_POSTGRES_OLAP_DB            Default: ofbizolap
# OFBIZ_POSTGRES_OLAP_USER          Default: ofbizolap
# OFBIZ_POSTGRES_TENANT_DB          Default: ofbiztenant
# OFBIZ_POSTGRES_TENANT_USER        Default: ofbiztenant
# OFBIZ_POSTGRES_HOST itself must be the host only. A port embedded in it, as in
# 'db.example.internal:5432', is refused, because a port that arrives inside the host string bypasses
# the range check below and lands in the URI unvalidated. Supply the port in OFBIZ_POSTGRES_PORT.
# An IPv6 literal has to be bracketed, as in '[fd00::1]', which is what the JDBC URI grammar requires.
#
# OFBIZ_POSTGRES_PORT
# The TCP port of the PostgreSQL host, rendered as the port component of all three managed JDBC URIs.
# Validated as a plain decimal integer between 1 and 65535, so a value that is not a port cannot reach
# the URI: an unchecked value here would be substituted straight after 'host:' and could otherwise
# graft arbitrary text onto the connection string.
# Default: 5432
#
# OFBIZ_POSTGRES_OFBIZ_PASSWORD
# OFBIZ_POSTGRES_OLAP_PASSWORD
# OFBIZ_POSTGRES_TENANT_PASSWORD
# The passwords of the three managed database users. REQUIRED whenever OFBIZ_POSTGRES_HOST is set:
# these have NO default. They used to default to 'ofbiz', 'ofbizolap' and 'ofbiztenant', which are
# published in this repository, so an operator who forgot to supply one got a database reachable by
# anyone who had read the source tree. Those three values are now refused outright, in every profile,
# together with 'postgres' and 'password'; rotate any database that was provisioned with them.
# In the prod profile a password must additionally be at least 16 characters long and use at least
# 8 distinct characters. The dev profile only requires a value that is not one of the refused ones,
# so a local database with a short password still works.
# Default: none - start up fails, naming the missing variable.
#
# OFBIZ_POSTGRES_SSLMODE
# The pgJDBC sslmode used by all three managed datasources.
# Defaults to verify-full, which encrypts the connection, checks that the server certificate chains to
# a trusted root AND checks that the hostname matches that certificate. The alternatives are weaker in
# ways that matter: 'prefer' - the driver's own default - silently falls back to an UNENCRYPTED
# connection when the server declines TLS, 'allow' and 'disable' transmit the password and all entity
# data in clear text, and 'require' encrypts without authenticating the peer, so a man in the middle
# can present any certificate. Only verify-ca and verify-full are accepted when OFBIZ_PROFILE=prod.
# verify-full needs a trusted root, and pgJDBC does NOT consult the JVM trust store by default: it
# reads sslrootcert, which defaults to ${user.home}/.postgresql/root.crt. Either point
# OFBIZ_POSTGRES_SSLROOTCERT at the CA bundle, or import the CA into the JVM trust store and add
# sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory.
# Values: disable, allow, prefer, require, verify-ca, verify-full
# Default: verify-full
#
# OFBIZ_POSTGRES_SSLROOTCERT
# Absolute path, inside the container, of the PEM or DER encoded root certificate that the server
# certificate must chain to. Rendered as the sslrootcert connection parameter of all three managed
# datasources. Mount the certificate read-only; it is not a secret, but a deployment that cannot read
# it cannot verify the server.
# Default: <empty> (pgJDBC falls back to ${user.home}/.postgresql/root.crt)
#
# OFBIZ_DB_POOL_MIN
# OFBIZ_DB_POOL_MAX
# Minimum and maximum size of the DBCP connection pool of each managed datasource, rendered as
# pool-minsize and pool-maxsize. Both are validated as plain decimal integers between 1 and 10000, and
# the minimum must not exceed the maximum, because an unchecked value here lands in an XML attribute
# that DBCPConnectionFactory parses with Integer.parseInt.
# These are PER-INSTANCE bounds, which is what makes them a deployment concern behind a load balancer:
# a fleet of n instances can open up to n * OFBIZ_DB_POOL_MAX connections against the managed server,
# so the product has to stay within its max_connections. The committed default of 250 was chosen for a
# single node; scaling out without lowering it is the usual way a fleet exhausts the database.
# Default: 2 and 250, the values the committed datasource definitions carry.
#
# OFBIZ_SCHEMA_INIT
# Selects between the one-shot schema initialisation mode and the normal serving run mode.
#
# false (the default) - RUN MODE. The managed datasources render with check-on-start="false" and
# add-missing-on-start="false", so a serving instance issues NO startup DDL: the fleet needs no DDL
# privilege on the managed database, and instances cannot race one another on schema changes while
# scaling out. The rendered configuration is re-read after substitution to confirm this.
#
# true - INIT MODE. The managed datasources render with both flags true, the container performs its
# normal initialisation - which creates the delegator, and creating a delegator is what applies the
# entity-model DDL - and then EXITS 0 WITHOUT SERVING TRAFFIC instead of starting OFBiz. That is the
# one-shot init job: run it once against a new or upgraded database, then run the fleet with the
# variable unset. The DDL is additive: it creates missing tables and columns and never drops. Schema
# truth remains the entity model, so no migration framework, schema-version table or hand-written DDL
# is involved. With OFBIZ_DATA_LOAD=none the schema is still created, through a data load that reads
# no data at all.
# Because init mode must actually initialise something, combining it with OFBIZ_SKIP_INIT is refused
# rather than silently exiting 0 without having touched the database.
# The embedded H2 datasources are deliberately untouched by this flag: they keep startup DDL enabled
# so a bare checkout is self-initialising, which is safe because H2 here is a single-node local file
# database that is never part of a served fleet.
# Values: true, false
# Default: false
#
# OFBIZ_DISTRIBUTED_CACHE_CLEAR
# Enables cross-instance entity cache invalidation by rendering distributed-cache-clear-enabled="true"
# on the default and default-no-eca delegators. The "test" delegator is never touched: it is a
# single-JVM integration-test delegator and must neither publish nor consume invalidation messages.
# Needed as soon as more than one instance serves the same database, because otherwise an entity
# updated on one instance stays stale in the other instances' caches until they expire.
# Applied in both profiles - through the render template when OFBIZ_POSTGRES_HOST is set, and by
# rendering the committed entityengine.xml into /ofbiz/config otherwise.
# The transport is OFBiz's existing plumbing, unchanged: the DistributedCacheClear interface and its
# default implementation org.apache.ofbiz.entityext.cache.EntityCacheServices, running as the "system"
# user. Both are parser defaults, so no further attribute is required. The JMS topic that carries the
# messages - <jms-service name="serviceMessenger"> in framework/service/config/serviceengine.xml - is
# COMMENTED OUT by default, and the transport is a HARD PREREQUISITE rather than an optional extra:
# with the flag on and no active serviceMessenger, the first entity write that triggers an
# invalidation has its transaction rolled back inside ServiceDispatcher.runAsync, which fails a bulk
# operation such as the seed data load and takes the boot down with it. An operator must therefore
# configure a JMS provider before enabling this. validate_distributed_cache_transport below checks
# for the transport up front - fatal in the prod profile, a warning in dev - so the flag can never be
# mistaken for fleet coherence.
# Values: true, false
# Default: false (today's single-node cache behaviour)
#
# OFBIZ_JVM_ROUTE
# Per-instance sticky-session route id, written to the catalina descriptor's jvm-route property.
# Behind a load balancer that uses sticky sessions, give every instance a DISTINCT value. Tomcat
# appends it to the session id, so only letters, digits, '.', '_' and '-' are accepted. Unset leaves
# the committed default in place; an empty value is treated as absent by ContainerConfig, which would
# mean no route at all, so it is never written as empty.
# Default: <empty> (the descriptor keeps jvm1)
#
# OFBIZ_SSL_ACCELERATOR_PORT
# The LOCAL port on which this instance receives plain HTTP forwarded by a TLS-terminating proxy -
# the port of the http (or ajp) connector, NOT the load balancer's public HTTPS port. Setting it
# installs SslAcceleratorValve, which marks every request arriving on that port secure.
# Validated as a plain integer between 1 and 65535 AND cross-checked against the connector ports
# declared in framework/catalina/ofbiz-component.xml: an unparseable value would otherwise abort
# container loading with a NumberFormatException, and a port matching no connector would install a
# valve that marks nothing secure.
# TRUST BOUNDARY: the valve grants TLS semantics on the strength of the local port alone. It verifies
# no source address and no forwarded header, so enable it only when that port is unreachable except
# through the proxy - bound to a private interface and restricted by a security group, firewall rule
# or private subnet - and only when the proxy itself strips and re-sets client supplied
# X-Forwarded-* and Forwarded headers. See the property's own comment in the descriptor.
# Default: <empty> (valve not installed)
#
# OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS
# Shares the session cookie across sub-domains by installing CrossSubdomainSessionValve. Normalised
# to the literal true or false; anything that is not clearly a boolean is refused. That valve calls
# request.getSession(true), so it forces a session to exist for every request - enable it only when
# instances really are served under multiple sub-domains of one parent domain.
# Values: true, false
# Default: <empty> (the descriptor keeps false)
#
# OFBIZ_DISABLE_COMPONENTS
# Prevents loading of ofbiz-components.
# Contains a comma separated list of relative paths from the ofbiz sources directory to the ofbiz-component.xml files
# that should be prevented from loading.
# Default: plugins/birt/ofbiz-component.xml
#
# Hooks are executed at the various stages of the initialisation process by executing scripts in the following
# directories. Scripts must be executable and have the .sh extension:
#
# /docker-entrypoint-hooks/before-config-applied.d
# Executed before any changes are applied to the OFBiz configuration files.
#
# /docker-entrypoint-hooks/after-config-applied.d
# Executed after any changes are applied to the OFBiz configuration files.
#
# /docker-entrypoint-hooks/before-data-load.d
# Executed before any data loading is about to be performed. Only executed if data loading is required.
# Example usage would be to alter the data to be loaded.
#
# /docker-entrypoint-hooks/additional-data.d
# Any data files (.xml files) in this directory are loaded after seed/demo data.
#
# /docker-entrypoint-hooks/after-data-load.d
# Executed after any data loading has been performed. Only executed if data loading was required.
#
###############################################################################
# Shell tracing is OPT-IN and is never active while a secret is being handled.
#
# This script injects secrets (the admin password, the admin shared key, the login/JWT signing keys
# and the database passwords) into the OFBiz configuration. Bash tracing echoes every expanded
# command to stderr, which in a container is the log stream, so an unconditional 'set -x' publishes
# those secrets to the container log. Tracing therefore defaults to off and, when the operator
# explicitly enables it with OFBIZ_TRACE, it is suspended around every secret-handling region by the
# hide_secrets/restore_trace pair below.
OFBIZ_TRACE_ENABLED="false"
if [ -n "$OFBIZ_TRACE" ]; then
  OFBIZ_TRACE_ENABLED="true"
  set -x
fi
set -e

trap shutdown_ofbiz SIGTERM SIGINT

CONTAINER_STATE_DIR="/ofbiz/runtime/container_state"
CONTAINER_DATA_LOADED="$CONTAINER_STATE_DIR/data_loaded"
CONTAINER_ADMIN_LOADED="$CONTAINER_STATE_DIR/admin_loaded"
CONTAINER_CONFIG_APPLIED="$CONTAINER_STATE_DIR/config_applied"
CONTAINER_DB_CONFIG_APPLIED="$CONTAINER_STATE_DIR/db_config_applied"

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
# override does not apply and this file has to be edited where it lies. Named once here because three
# separate edits target it: the connector address insertion and the two Objective 5 rewrites.
CATALINA_COMPONENT_DESCRIPTOR="/ofbiz/framework/catalina/ofbiz-component.xml"

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

# The service engine configuration, in the CLASSPATH PRECEDENCE order the service engine resolves it:
# serviceengine.xml is read as a FLAT class path resource, so a copy in /ofbiz/config shadows the one
# shipped in the distribution. These two files are only ever READ here - the transport pre-flight below
# looks for the jms-service that carries cache invalidations and this script never rewrites either of
# them, because a JMS provider is deployment-specific configuration an operator supplies.
SERVICE_ENGINE_CANDIDATES=(
  "config/serviceengine.xml"
  "framework/service/config/serviceengine.xml"
)

# Bounds of the managed DBCP connection pools, and the defaults the committed datasource definitions
# carry. The upper limit is a sanity bound rather than a database limit: pool-maxsize is a per-instance
# figure, so behind a load balancer the fleet can open instances * pool-maxsize connections, and a value
# in the tens of thousands is always a mistake rather than a deployment decision.
DB_POOL_MIN_DEFAULT=2
DB_POOL_MAX_DEFAULT=250
DB_POOL_SIZE_LIMIT=10000

# Pristine start.properties shipped with the distribution, and the package qualified override that
# Config.java resolves ahead of it through the class loader. The override MUST keep the
# org/apache/ofbiz/base/start/ path: a flat config/start.properties does not shadow the shipped file.
START_PROPERTIES_SOURCE="framework/start/src/main/resources/org/apache/ofbiz/base/start/start.properties"
ADMIN_KEY_OVERRIDE="config/org/apache/ofbiz/base/start/start.properties"

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
# 'ofbiz', 'ofbizolap' and 'ofbiztenant' are the values this script used to default each database
# password to, and 'ofbiz' was also the password the committed localpostgres* datasource definitions
# carried, so all three are published in this repository and must never authenticate anything again.
# They are refused together with the two passwords a PostgreSQL installation is most often left with,
# so a database still provisioned with one of them is discovered at start up instead of being
# reachable by anyone who has read the source tree. Rotate any database that used them.
# The length and entropy floor is only applied in the prod profile: a local development database with
# a short password is not a production risk, and refusing it would push developers towards disabling
# the check altogether.
DATABASE_PASSWORD_MIN_LENGTH=16
RETIRED_DATABASE_PASSWORDS=(ofbiz ofbizolap ofbiztenant postgres password)

# The TLS modes the PostgreSQL JDBC driver understands, and the subset that authenticates the server.
# Only the verifying modes are accepted in the prod profile: 'disable' and 'allow' can transmit the
# password and every entity row in clear text, 'prefer' - the driver's own default - silently falls
# back to an unencrypted connection when the server declines TLS, and 'require' encrypts without
# checking who is on the other end of the connection.
POSTGRES_SSL_MODES=(disable allow prefer require verify-ca verify-full)
POSTGRES_SSL_VERIFYING_MODES=(verify-ca verify-full)
POSTGRES_SSL_DEFAULT_MODE='verify-full'

# Nesting depth of the secret-handling regions currently open. Secret-handling functions call one
# another - a renderer validates a secret, which in turn validates its shape - so the pair below is
# reference counted. Without the counter the inner function's restore_trace would switch tracing back
# on while its caller was still holding a secret, which is exactly the leak this is meant to prevent.
OFBIZ_TRACE_DEPTH=0

###############################################################################
# Suspend shell tracing for the duration of a secret-handling region.
# Always pair with restore_trace. Safe to call when tracing is already disabled
# and safe to nest.
hide_secrets() {
  set +x
  OFBIZ_TRACE_DEPTH=$((OFBIZ_TRACE_DEPTH + 1))
}

###############################################################################
# Leave a secret-handling region, resuming shell tracing only once the outermost
# region has closed and only if the operator explicitly asked for tracing by
# setting OFBIZ_TRACE.
restore_trace() {
  if [ "$OFBIZ_TRACE_DEPTH" -gt 0 ]; then
    OFBIZ_TRACE_DEPTH=$((OFBIZ_TRACE_DEPTH - 1))
  fi
  if [ "$OFBIZ_TRACE_DEPTH" -eq 0 ] && [ "$OFBIZ_TRACE_ENABLED" = "true" ]; then
    set -x
  fi
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
    config_fatal "$1 must be a boolean: true or false."
    ;;
  esac
}

###############################################################################
# Require a value that is safe to embed in the authority component of a JDBC URI.
#
# The rendered value lands directly after 'jdbc:postgresql://', so a value containing '/', '?', '#',
# '@' or whitespace could close the authority early and point the connection at a different server,
# graft on driver connection parameters, or comment out the rest of the URI. Only the characters a
# DNS host name, an IPv4 literal or a bracketed IPv6 literal can contain are accepted.
#
# A port embedded in the host is refused. The port has its own placeholder and its own range check, so
# a value smuggled in here would be the one component of the URI that reached the connection string
# without being validated - and 'host:5432/other?sslmode=disable' is exactly the kind of value that
# check exists to catch. An IPv6 literal must be bracketed, which is what the URI grammar requires
# anyway to keep its colons distinguishable from a port separator.
# $1 - variable name, $2 - value
require_jdbc_host() {
  reject_unsafe_value "$1" "$2"
  case "$2" in
  '')
    config_fatal "$1 must not be empty."
    ;;
  # The allow list is ']', letters, digits, '.', '_', ':', '[' and '-'. In a shell bracket
  # expression ']' must be the first member to be read literally and '-' must be the last,
  # so the order below is deliberate and must not be "tidied" into alphabetical order.
  *[!]A-Za-z0-9._:[-]*)
    config_fatal "$1 must be a host name, an IP address, or a bracketed IPv6 literal. It contains a character that is not valid in a JDBC URI authority."
    ;;
  esac
  # A bracketed literal is the only form in which a colon is legitimate, and only inside the brackets.
  case "$2" in
  '['*']') ;;
  *:*)
    config_fatal "$1 must not contain a port. Set the port in OFBIZ_POSTGRES_PORT, and bracket an IPv6 literal as '[fd00::1]'."
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
# Require a managed database password that can actually protect the database.
#
# There is deliberately no default. A missing password is a fatal misconfiguration, and the published
# values this script used to fall back to are refused in every profile so that a database still
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
    local verifying
    local isVerifying='false'
    for verifying in "${POSTGRES_SSL_VERIFYING_MODES[@]}"; do
      if [ "$OFBIZ_POSTGRES_SSLMODE" = "$verifying" ]; then
        isVerifying='true'
      fi
    done
    if [ "$isVerifying" != 'true' ]; then
      config_fatal "OFBIZ_POSTGRES_SSLMODE=$OFBIZ_POSTGRES_SSLMODE does not authenticate the database server, which OFBIZ_PROFILE=prod does not allow. Use one of: ${POSTGRES_SSL_VERIFYING_MODES[*]}."
    fi
  fi

  RESOLVED_POSTGRES_SSL_PARAMETERS="?sslmode=$OFBIZ_POSTGRES_SSLMODE"

  if [ -n "$OFBIZ_POSTGRES_SSLROOTCERT" ]; then
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
    rm --force "$renderedFile"
    config_fatal "Rendered $renderedFile declares no PostgreSQL datasource URI. The template it was rendered from is not the expected one."
  fi
  if [ "$uriCount" -ne "$secureCount" ]; then
    rm --force "$renderedFile"
    config_fatal "Rendered $renderedFile has $uriCount PostgreSQL URIs but only $secureCount carry sslmode=$expectedMode. The template is missing the @SSL_PARAMS@ placeholder, so the connection would not be encrypted."
  fi
}

###############################################################################
# Refuse a render template in which a password placeholder appears anywhere other than on the
# jdbc-password attribute it is meant to fill.
#
# sed rewrites comments exactly as readily as attributes, so a placeholder mentioned in prose - in a
# token inventory, a worked example, a maintenance note - is substituted there too, and the rendered
# configuration then repeats the database passwords in clear text outside the one attribute that is
# supposed to hold each of them. Every additional copy is a place a support bundle, a configuration diff
# or a mounted volume can leak them from.
#
# This is checked BEFORE rendering and reads only the template, so no secret is involved: the placeholder
# names are public and the check is purely structural. It is deliberately a refusal rather than a warning,
# because the failure mode it prevents is silent - the rendered file is perfectly valid and OFBiz starts
# normally.
# $1 - template path
require_template_secret_placement() {
  local template="$1"
  local offendingLines

  offendingLines=$(grep --line-number '_PASSWORD@' "$template" | grep --invert-match 'jdbc-password="' | cut --delimiter=: --fields=1 | tr '\n' ' ') || offendingLines=''
  if [ -n "$offendingLines" ]; then
    config_fatal "$template names a password placeholder outside a jdbc-password attribute, on line(s): $offendingLines. Rendering it would copy the database passwords into those lines. Remove the placeholder from the prose."
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
# while looking disabled in the file. Counting the exact literals is what distinguishes those cases.
# It also fails when the two flags disagree, because the two supported modes are "apply the entity model
# additively" and "issue no DDL at all" - a datasource that checks the schema but may not add to it just
# logs differences on every boot of every instance.
# $1 - rendered file, $2 - the mode that must be present: true for init mode, false for run mode
require_rendered_schema_ddl_mode() {
  local renderedFile="$1"
  local expectedMode="$2"
  local managedCount
  local checkCount
  local addMissingCount

  # Only the managed datasources are counted. The embedded H2 definitions in the same file keep both
  # flags true on purpose, so a whole-file count would always disagree.
  managedCount=$(grep --count 'field-type-name="postgres"' "$renderedFile") || managedCount=0
  checkCount=$(grep --count "check-on-start=\"$expectedMode\"" "$renderedFile") || checkCount=0
  addMissingCount=$(grep --count "add-missing-on-start=\"$expectedMode\"" "$renderedFile") || addMissingCount=0
  if [ "$expectedMode" = "true" ]; then
    # In init mode the H2 definitions carry the same literal, so they have to be discounted.
    local embeddedCount
    embeddedCount=$(grep --count 'field-type-name="h2"' "$renderedFile") || embeddedCount=0
    checkCount=$((checkCount - embeddedCount))
    addMissingCount=$((addMissingCount - embeddedCount))
  fi

  if [ "$managedCount" -eq 0 ]; then
    rm --force "$renderedFile"
    config_fatal "Rendered $renderedFile declares no managed PostgreSQL datasource. The template it was rendered from is not the expected one."
  fi
  if [ "$checkCount" -ne "$managedCount" ] || [ "$addMissingCount" -ne "$managedCount" ]; then
    rm --force "$renderedFile"
    config_fatal "Rendered $renderedFile has $managedCount managed datasources but $checkCount with check-on-start=\"$expectedMode\" and $addMissingCount with add-missing-on-start=\"$expectedMode\". The template is missing the @SCHEMA_DDL@ placeholder, so the startup DDL mode is not the one that was requested."
  fi
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
      rm --force "$renderedFile"
      config_fatal "Rendered $renderedFile does not declare distributed-cache-clear-enabled on the '$delegatorName' delegator. The anchor the entry point substitutes has been removed or reformatted."
      ;;
    *)
      rm --force "$renderedFile"
      config_fatal "Rendered $renderedFile does not carry distributed-cache-clear-enabled=\"$expectedValue\" on the '$delegatorName' delegator, so cross-instance cache invalidation is not in the requested state."
      ;;
    esac
  done

  if grep --quiet '<delegator name="test"[^>]*distributed-cache-clear-enabled=' "$renderedFile"; then
    rm --force "$renderedFile"
    config_fatal "Rendered $renderedFile enables distributed cache clear on the 'test' delegator. That delegator is single-JVM only and must never join the invalidation topic."
  fi
}

###############################################################################
# Verify that the rendered configuration keeps the 'test' delegator on the embedded H2 datasources.
#
# The test delegator used to be repointed at the managed datasources along with the other two, which
# meant that running the integration suite inside a container aimed a delegator whose readers include
# ext-test - and which integration tests write to and delete from freely - at the production database.
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
    rm --force "$renderedFile"
    config_fatal "Rendered $renderedFile maps the 'test' delegator to [$mappings] instead of the embedded H2 datasources. Integration tests must never be pointed at a managed database."
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
# Generate a cryptographically random 64 character base64 secret on stdout.
# 48 random bytes encode to exactly 64 base64 characters with no padding, matching
# the 512 bit key length that OFBiz requires for its HMAC512 signatures.
generate_secret() {
  head --bytes=48 /dev/urandom | basenc --base64 --wrap=0
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
#            of this container. This preserves the zero-configuration local and demo experience that
#            previously came from generating keys into the image at build time; the difference is that
#            the value now exists only in this container's memory and in its mode 0600 rendered
#            configuration, never in the source tree, the distribution or an image layer.
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
  elif [ "$OFBIZ_PROFILE" = "prod" ]; then
    config_fatal "$name must be supplied through the environment when OFBIZ_PROFILE=prod."
  else
    resolve_generated_secret "$name" "$minLength" "$forbidden"
  fi

  restore_trace
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

  RESOLVED_SECRET=""
  if [ -r "$store" ]; then
    RESOLVED_SECRET=$(cat "$store")
    if ! secret_is_usable "$RESOLVED_SECRET" "$minLength" "$forbidden"; then
      RESOLVED_SECRET=""
    fi
  fi

  if [ -z "$RESOLVED_SECRET" ]; then
    RESOLVED_SECRET=$(generate_secret)
    mkdir --parents "$CONTAINER_GENERATED_SECRETS_DIR"
    chmod 700 "$CONTAINER_GENERATED_SECRETS_DIR"
    local temporary
    temporary=$(mktemp "$store.XXXXXXXX")
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
# Escape a value for use as a java.util.Properties value. Properties interprets
# backslash escapes and treats a trailing backslash as a line continuation, so a
# literal backslash must be doubled.
# $1 - value
properties_escape_value() {
  printf '%s' "$1" | sed --expression='s,\\,\\\\,g'
}

###############################################################################
# Emit, on stdout, one sed substitution for a '@TOKEN@' placeholder in a
# configuration template. The value is XML escaped because it lands inside a double
# quoted XML attribute, then escaped for the sed replacement grammar. printf is a
# shell builtin, so the value never reaches the process table.
# $1 - token, including the surrounding '@'
# $2 - value
write_xml_token_substitution() {
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
  mkdir --parents "$(dirname "$destination")"
  temporary=$(mktemp "$destination.XXXXXXXX")
  chmod 600 "$temporary"
  if ! sed "$@" "$source" >"$temporary"; then
    rm --force "$temporary"
    config_fatal "Failed to render $destination from $source."
  fi
  mv --force "$temporary" "$destination"
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
# Normalise the two entity-engine mode flags, before anything reads them.
#
# Called from _main ahead of the OFBIZ_SKIP_INIT branch, for two reasons. The schema-init decision
# changes what the whole container does - whether it serves traffic at all - so it must be settled and
# validated before any work is performed, not discovered halfway through rendering. And an unparseable
# value has to be refused even on a path that skips initialisation, because a deployment that meant to
# request init mode and mistyped the value must not be handed a silently serving instance instead.
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

  # Refused rather than tolerated. OFBIZ_SKIP_INIT skips the configuration rendering and the data load,
  # which is everything init mode consists of, so the combination would exit 0 without having created a
  # single table - and an operator reading that exit status would conclude the schema was ready.
  if [ "$RESOLVED_SCHEMA_INIT" = "true" ] && [ -n "$OFBIZ_SKIP_INIT" ]; then
    config_fatal "OFBIZ_SCHEMA_INIT=true cannot be combined with OFBIZ_SKIP_INIT: skipping initialisation would exit successfully without applying any schema. Unset one of them."
  fi

  # Checked here, with the flag, and therefore also when OFBIZ_SKIP_INIT is set: an instance started
  # with the flag on and no transport rolls back entity writes whether or not this script rendered its
  # configuration. The profile has not been validated yet - ofbiz_setup_env does that - so the check
  # reads it defensively and an unrecognised value is still refused there.
  validate_distributed_cache_transport
}

###############################################################################
# Print the number of ACTIVE jms-service elements named serviceMessenger in the file named by $1, or 0
# when the file does not exist. XML comment bodies are removed before matching, and a comment can span
# lines, so the element that framework/service/config/serviceengine.xml ships INSIDE a comment
# correctly counts as zero - which is the whole point of the check.
# $1 - file to inspect
count_active_service_messenger() {
  if [ ! -f "$1" ]; then
    echo 0
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
  ' "$1" | grep --count --extended-regexp '<jms-service[^>]*name="serviceMessenger"' || true
}

###############################################################################
# Refuse to enable distributed cache invalidation without a message transport.
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
# Reporting it here reports the real problem, up front, in terms of the variable that caused it. Only
# the PRESENCE of the configuration can be checked - a broker that is named but unreachable fails the
# same way - so the message says to check reachability as well.
validate_distributed_cache_transport() {
  if [ "$RESOLVED_DISTRIBUTED_CACHE_CLEAR" != "true" ]; then
    return 0
  fi

  local candidate
  for candidate in "${SERVICE_ENGINE_CANDIDATES[@]}"; do
    if [ "$(count_active_service_messenger "$candidate")" -gt 0 ]; then
      printf '%s\n' "OFBIZ_DISTRIBUTED_CACHE_CLEAR=true and an active jms-service named serviceMessenger was found in $candidate, so entity cache invalidations have a transport to travel on. Check that the broker it names is reachable from every instance."
      return 0
    fi
  done

  if [ "${OFBIZ_PROFILE:-dev}" = "prod" ]; then
    config_fatal "OFBIZ_DISTRIBUTED_CACHE_CLEAR=true but no active jms-service named serviceMessenger was found in ${SERVICE_ENGINE_CANDIDATES[*]}, so no transport carries cache invalidations. Supply a serviceengine.xml that declares one at ${SERVICE_ENGINE_CANDIDATES[0]}, or set OFBIZ_DISTRIBUTED_CACHE_CLEAR=false to run this instance on a local cache. Starting without the transport would roll back the first entity write that triggers an invalidation and fail the data load. See DOCKER.adoc."
  fi

  printf '%s\n' "WARNING: OFBIZ_DISTRIBUTED_CACHE_CLEAR is true but no active jms-service named serviceMessenger was found in ${SERVICE_ENGINE_CANDIDATES[*]}, so no transport carries cache invalidations. This is NOT a cache-coherent fleet, and the first entity write that triggers an invalidation will be rolled back. Supply the transport, or set OFBIZ_DISTRIBUTED_CACHE_CLEAR=false. OFBIZ_PROFILE=prod refuses to start in this state." >&2
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

  # Resolved before anything that depends on it. The profile decides whether an absent secret is a
  # fatal misconfiguration or is replaced by a generated per-container value, so an unrecognised
  # spelling must not be allowed to fall back to the permissive setting by accident.
  OFBIZ_PROFILE=${OFBIZ_PROFILE:-dev}
  require_enum OFBIZ_PROFILE "$OFBIZ_PROFILE" dev prod

  case "$OFBIZ_DATA_LOAD" in
  none | seed | demo) ;;
  *)
    OFBIZ_DATA_LOAD="seed"
    ;;
  esac

  OFBIZ_ADMIN_USER=${OFBIZ_ADMIN_USER:-admin}

  OFBIZ_ADMIN_PASSWORD=${OFBIZ_ADMIN_PASSWORD:-ofbiz}

  # Database names and user names are identifiers, not credentials, so they keep their long standing
  # defaults. The three passwords deliberately have NONE: they used to default to these same
  # published identifiers, which meant a deployment that forgot to supply one silently authenticated
  # with a value anybody could read out of this repository. require_database_password now refuses an
  # absent password - and refuses the retired defaults outright - but only when PostgreSQL is
  # actually configured, so the embedded H2 path is unaffected.
  OFBIZ_POSTGRES_PORT=${OFBIZ_POSTGRES_PORT:-5432}
  OFBIZ_POSTGRES_OFBIZ_DB=${OFBIZ_POSTGRES_OFBIZ_DB:-ofbiz}
  OFBIZ_POSTGRES_OFBIZ_USER=${OFBIZ_POSTGRES_OFBIZ_USER:-ofbiz}

  OFBIZ_POSTGRES_OLAP_DB=${OFBIZ_POSTGRES_OLAP_DB:-ofbizolap}
  OFBIZ_POSTGRES_OLAP_USER=${OFBIZ_POSTGRES_OLAP_USER:-ofbizolap}

  OFBIZ_POSTGRES_TENANT_DB=${OFBIZ_POSTGRES_TENANT_DB:-ofbiztenant}
  OFBIZ_POSTGRES_TENANT_USER=${OFBIZ_POSTGRES_TENANT_USER:-ofbiztenant}

  # Verifying TLS by default. A datasource that is reachable over the network must authenticate the
  # server it sends its credentials to, and the driver's own default would quietly accept an
  # unencrypted connection instead. Validated in require_postgres_ssl_parameters, which is only
  # reached when PostgreSQL is configured, so a typo cannot stop an H2 container from starting.
  OFBIZ_POSTGRES_SSLMODE=${OFBIZ_POSTGRES_SSLMODE:-$POSTGRES_SSL_DEFAULT_MODE}

  # Defaulted to the values the committed datasource definitions already carry, so an operator who sets
  # neither gets exactly the pool the template used to hardcode. Range checked in
  # render_database_configuration, which is the only place they are used.
  OFBIZ_DB_POOL_MIN=${OFBIZ_DB_POOL_MIN:-$DB_POOL_MIN_DEFAULT}
  OFBIZ_DB_POOL_MAX=${OFBIZ_DB_POOL_MAX:-$DB_POOL_MAX_DEFAULT}

  OFBIZ_DISABLE_COMPONENTS=${OFBIZ_DISABLE_COMPONENTS-plugins/birt/ofbiz-component.xml}

  restore_trace
}

###############################################################################
# Create the runtime container state directory used to track which initialisation
# steps have been run for the container.
# This directory should be hosted on a volume that persists for the life of the container.
create_ofbiz_runtime_directories() {
  if [ ! -d "$CONTAINER_STATE_DIR" ]; then
    mkdir --parents "$CONTAINER_STATE_DIR"
  fi
}

###############################################################################
# Execute the shell scripts at the paths passed to this function.
# Args:
# 1:  Name of the hook stage being executed. Used for logging.
# 2+: Variable number of paths to the shell scripts to be executed.
#     Only scripts with the .sh extension are executed.
#     Scripts will be sourced if they are not executable.
run_init_hooks() {
  local hookStage="$1"
  shift
  local filePath
  for filePath; do
    case "$filePath" in
    *.sh)
      if [ -x "$filePath" ]; then
        printf '%s: running %s\n' "$hookStage" "$filePath"
        "$filePath"
      else
        printf '%s: sourcing %s\n' "$hookStage" "$filePath"
        . "$filePath"
      fi
      ;;
    *)
      printf '%s: Not a script. Ignoring %s\n' "$hookStage" "$filePath"
      ;;
    esac
  done
}

###############################################################################
# If required, load data into OFBiz.
load_data() {
  if [ ! -f "$CONTAINER_DATA_LOADED" ]; then
    run_init_hooks before-data-load /docker-entrypoint-hooks/before-data-load.d/*

    case "$OFBIZ_DATA_LOAD" in
    none)
      # Nothing to load - but in schema-init mode there is still something to DO. The Entity Engine has
      # no standalone DDL command: the schema is applied when a delegator is created, which the data
      # loader does before it reads anything. 'readers=none' names a reader that no component declares,
      # so EntityDataLoader resolves it to an empty URL list and not one row is loaded, while the
      # delegator - and therefore the check-on-start/add-missing-on-start DDL - still happens.
      if [ "${RESOLVED_SCHEMA_INIT:-false}" = "true" ]; then
        /ofbiz/bin/ofbiz --load-data readers=none
      fi
      ;;

    seed)
      /ofbiz/bin/ofbiz --load-data readers=seed,seed-initial
      ;;

    demo)
      /ofbiz/bin/ofbiz --load-data
      # Demo data includes the admin user so indicate that the user is already loaded.
      touch "$CONTAINER_ADMIN_LOADED"
      ;;
    esac

    # Load any additional data files provided.
    if [ -z $(find /docker-entrypoint-hooks/additional-data.d/ -prune -empty) ]; then
      /ofbiz/bin/ofbiz --load-data dir=/docker-entrypoint-hooks/additional-data.d
    fi

    touch "$CONTAINER_DATA_LOADED"

    run_init_hooks after-data-load /docker-entrypoint-hooks/after-data-load.d/*
  fi
}

###############################################################################
# Create and load the password hash for the admin user.
load_admin_user() {
  if [ ! -f "$CONTAINER_ADMIN_LOADED" ]; then
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

    # Concatenate a random salt and the admin password.
    SALT=$(tr --delete --complement A-Za-z0-9 </dev/urandom | head --bytes=16)
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

    # Load data from the populated template.
    /ofbiz/bin/ofbiz --load-data "file=$TMPFILE"

    # Removes both the populated data file and the already deleted sed program, and forgets them so a
    # later validation failure does not try to remove them again.
    discard_secret_temp_files

    touch "$CONTAINER_ADMIN_LOADED"
  fi
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
    mv "$TMPFILE" "$XML_FILE"
  else
    echo "Cannot find ofbiz-component configuration file. Not disabling component: $XML_FILE"
  fi
}

###############################################################################
# Modify the given ofbiz-component configuration XML files to set their root
# components' 'enabled' attribute to false.
# $1 - Comma separated list of paths to configuration XML files to be modified.
disable_components() {
  COMMA_SEPARATED_PATHS=$1

  # Remove spaces after commas
  COMMA_SEPARATED_PATHS="${COMMA_SEPARATED_PATHS//, /,}"

  [ -z "$COMMA_SEPARATED_PATHS" ] && return 0

  # Split on commas into $1 $2 ...
  oldIFS=$IFS
  IFS=,
  set -- $COMMA_SEPARATED_PATHS    # <- no quotes, use -- to avoid option parsing
  IFS=$oldIFS

  # Iterate through the split arguments
  while [ -n "$1" ]; do
    disable_component "$1"
    shift
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
  resolve_secret OFBIZ_LOGIN_SECRET_KEY "$OFBIZ_LOGIN_SECRET_KEY" "$SIGNING_KEY_MIN_LENGTH" ''
  loginKey="$RESOLVED_SECRET"
  resolve_secret OFBIZ_JWT_TOKEN_KEY "$OFBIZ_JWT_TOKEN_KEY" "$SIGNING_KEY_MIN_LENGTH" ''
  jwtKey="$RESOLVED_SECRET"
  RESOLVED_SECRET=""

  local sedScript
  sedScript=$(mktemp)
  chmod 600 "$sedScript"
  register_secret_temp_file "$sedScript"
  {
    if [ -n "$OFBIZ_HOST" ]; then
      reject_unsafe_value OFBIZ_HOST "$OFBIZ_HOST"
      printf 's|^host-headers-allowed=.*|host-headers-allowed=%s|\n' \
        "$(sed_escape_replacement "$(properties_escape_value "$OFBIZ_HOST")")"
    fi
    printf 's|^login\\.secret_key_string=.*|login.secret_key_string=%s|\n' \
      "$(sed_escape_replacement "$(properties_escape_value "$loginKey")")"
    printf 's|^security\\.token\\.key=.*|security.token.key=%s|\n' \
      "$(sed_escape_replacement "$(properties_escape_value "$jwtKey")")"
  } >"$sedScript"

  render_config_from config/security.properties framework/security/config/security.properties \
    --file="$sedScript"
  discard_secret_temp_files

  # Fails closed if the blank anchors are ever removed from the source file, because a silently
  # unsubstituted key leaves JWT creation throwing and forgot-password decryption broken.
  require_rendered_declaration config/security.properties 'login\.secret_key_string' \
    framework/security/config/security.properties
  require_rendered_declaration config/security.properties 'security\.token\.key' \
    framework/security/config/security.properties

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
# tree; the substitution below uncomments it and gives it the resolved value. Rendering always starts
# from that pristine file, so exactly one active declaration exists in the result.
render_admin_key_configuration() {
  hide_secrets

  resolve_secret OFBIZ_ADMIN_KEY "$OFBIZ_ADMIN_KEY" "$ADMIN_KEY_MIN_LENGTH" "$ADMIN_KEY_FORBIDDEN"
  local adminKey="$RESOLVED_SECRET"
  RESOLVED_SECRET=""

  local sedScript
  sedScript=$(mktemp)
  chmod 600 "$sedScript"
  register_secret_temp_file "$sedScript"
  printf 's|^#*ofbiz\\.admin\\.key=.*|ofbiz.admin.key=%s|\n' \
    "$(sed_escape_replacement "$(properties_escape_value "$adminKey")")" >"$sedScript"

  render_config_from "$ADMIN_KEY_OVERRIDE" "$START_PROPERTIES_SOURCE" --file="$sedScript"
  discard_secret_temp_files

  require_rendered_declaration "$ADMIN_KEY_OVERRIDE" 'ofbiz\.admin\.key' "$START_PROPERTIES_SOURCE"

  restore_trace
}

###############################################################################
# Verify that a rendered configuration file really declares a property, so that a source file whose
# anchor has been renamed or deleted cannot silently produce a configuration with the property
# missing - which for these secrets means falling back to a published default or to no key at all.
# grep is used in quiet mode, so the value is never echoed.
# $1 - rendered file, $2 - property name as a basic regular expression, $3 - source file
require_rendered_declaration() {
  if ! grep --quiet "^$2=." "$1"; then
    config_fatal "Rendered $1 does not declare a value for the property matched by '$2'. The anchor for it is missing from $3."
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
render_catalina_configuration() {
  local descriptor="$CATALINA_COMPONENT_DESCRIPTOR"

  if [ -n "$OFBIZ_JVM_ROUTE" ]; then
    reject_unsafe_value OFBIZ_JVM_ROUTE "$OFBIZ_JVM_ROUTE"
    # Tomcat appends this to the session id, so it has to survive being part of a cookie value and of
    # a load balancer's routing table: letters, digits, '.', '_' and '-' only.
    case "$OFBIZ_JVM_ROUTE" in
    *[!A-Za-z0-9._-]*)
      config_fatal "OFBIZ_JVM_ROUTE must contain only letters, digits, '.', '_' and '-'. It is appended to the session id."
      ;;
    esac
    rewrite_catalina_property jvm-route "$OFBIZ_JVM_ROUTE"
  fi

  if [ -n "$OFBIZ_SSL_ACCELERATOR_PORT" ]; then
    require_integer_range OFBIZ_SSL_ACCELERATOR_PORT "$OFBIZ_SSL_ACCELERATOR_PORT" 1 65535
    if ! grep --quiet "<property name=\"port\" value=\"$OFBIZ_SSL_ACCELERATOR_PORT\"/>" "$descriptor"; then
      config_fatal "OFBIZ_SSL_ACCELERATOR_PORT=$OFBIZ_SSL_ACCELERATOR_PORT matches no connector port declared in $descriptor. It must be the LOCAL port this instance receives the proxy's forwarded traffic on, not the load balancer's public HTTPS port."
    fi
    rewrite_catalina_property ssl-accelerator-port "$OFBIZ_SSL_ACCELERATOR_PORT"
  fi

  if [ -n "$OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS" ]; then
    # require_boolean normalises onto stdout, so it has to be read through a command substitution -
    # and config_fatal's exit inside a substitution only ends the subshell. The status is therefore
    # checked explicitly: without this an unparseable value would abort the subshell, leave the
    # captured value empty, and enable_cross_subdomain_sessions would be rewritten to nothing.
    local normalisedBoolean
    normalisedBoolean=$(require_boolean OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS \
      "$OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS") \
      || config_fatal "OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS must be a boolean: true or false."
    rewrite_catalina_property enable-cross-subdomain-sessions "$normalisedBoolean"
  fi
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

  if [ -n "$OFBIZ_CONTENT_URL_PREFIX" ]; then
    reject_unsafe_value OFBIZ_CONTENT_URL_PREFIX "$OFBIZ_CONTENT_URL_PREFIX"
    local escapedUrlPrefix
    escapedUrlPrefix=$(sed_escape_replacement "$(properties_escape_value "$OFBIZ_CONTENT_URL_PREFIX")")
    render_config_from config/url.properties framework/webapp/config/url.properties \
      --expression="s|^content.url.prefix.secure=.*|content.url.prefix.secure=${escapedUrlPrefix}|" \
      --expression="s|^content.url.prefix.standard=.*|content.url.prefix.standard=${escapedUrlPrefix}|"
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

  if [ ! -f "$CONTAINER_CONFIG_APPLIED" ]; then
    firstRun="true"
    run_init_hooks before-config-applied /docker-entrypoint-hooks/before-config-applied.d/*

    if [ -n "$OFBIZ_ENABLE_AJP_PORT" ]; then
      # Configure tomcat to listen for AJP connections on all interfaces within the container.
      sed --in-place \
        '/<property name="ajp-connector" value="connector">/ a <property name="address" value="0.0.0.0"/>' \
        "$CATALINA_COMPONENT_DESCRIPTOR"
    fi
  fi

  # Value-replacing substitutions on the catalina descriptor, so they are idempotent and run on every
  # start alongside the rendered property files rather than once behind the marker file.
  render_catalina_configuration

  render_runtime_configuration

  if [ "$firstRun" = "true" ]; then
    if [ -n "$OFBIZ_DISABLE_COMPONENTS" ]; then
      disable_components "$OFBIZ_DISABLE_COMPONENTS"
    fi

    touch "$CONTAINER_CONFIG_APPLIED"
    run_init_hooks after-config-applied /docker-entrypoint-hooks/after-config-applied.d/*
  fi
}

###############################################################################
# Render the managed PostgreSQL entity engine configuration from its template.
#
# Every value comes from the environment and is substituted into a double quoted XML attribute, so
# each one is checked for control characters, XML escaped and then escaped for the sed replacement
# grammar. The sed program is written to a mode 0600 temporary file instead of being passed on the
# command line, so a database password never appears in the process table, and tracing is suspended
# for the whole function so it never appears in the container log either. The result is written
# atomically with mode 0600.
#
# Two things are refused rather than defaulted, because a managed database is reachable over the
# network and both used to be silently acceptable: a missing or published database password, and a
# connection that is not verified end to end. The rendered file is then re-read to confirm that every
# PostgreSQL URI in it really carries the resolved sslmode.
render_database_configuration() {
  hide_secrets

  # The host, the port and the three database names become structural components of a JDBC URI, so they
  # are validated against the grammar of the component they occupy rather than merely checked for
  # control characters. Together with the sslmode query string resolved below, that covers every part
  # of the rendered URI. The user names and passwords occupy XML attributes of their own, never the
  # URI, so for those a single-line check plus XML escaping is the complete requirement.
  require_jdbc_host OFBIZ_POSTGRES_HOST "$OFBIZ_POSTGRES_HOST"
  # Defaulted here as well as in ofbiz_setup_env, for the same reason as the sslmode below: an empty
  # value must never be able to render as 'jdbc:postgresql://host:/db', and whether the URI has a
  # usable port must not depend on the order in which these functions happen to be called.
  OFBIZ_POSTGRES_PORT=${OFBIZ_POSTGRES_PORT:-5432}
  require_integer_range OFBIZ_POSTGRES_PORT "$OFBIZ_POSTGRES_PORT" 1 65535
  require_jdbc_database OFBIZ_POSTGRES_OFBIZ_DB "$OFBIZ_POSTGRES_OFBIZ_DB"
  require_jdbc_database OFBIZ_POSTGRES_OLAP_DB "$OFBIZ_POSTGRES_OLAP_DB"
  require_jdbc_database OFBIZ_POSTGRES_TENANT_DB "$OFBIZ_POSTGRES_TENANT_DB"

  local variableName
  for variableName in OFBIZ_POSTGRES_OFBIZ_USER OFBIZ_POSTGRES_OLAP_USER OFBIZ_POSTGRES_TENANT_USER; do
    reject_unsafe_value "$variableName" "${!variableName}"
  done

  # Each password must be supplied and must not be one of the published defaults this script used to
  # fall back to. There is no way to opt out: a managed database is reachable over the network, so a
  # deployment with a guessable database password is a deployment with no database password.
  for variableName in OFBIZ_POSTGRES_OFBIZ_PASSWORD OFBIZ_POSTGRES_OLAP_PASSWORD \
    OFBIZ_POSTGRES_TENANT_PASSWORD; do
    require_database_password "$variableName" "${!variableName}"
  done

  # Resolves and validates the TLS query string shared by the three managed URIs, and refuses any
  # non-verifying mode in the prod profile.
  require_postgres_ssl_parameters

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

  # Both mode flags are normally resolved by resolve_entity_engine_flags before anything runs. They are
  # defaulted defensively here too so that this function renders the safe mode - no startup DDL, no
  # cross-instance invalidation - even if it is ever reached without that call.
  RESOLVED_SCHEMA_INIT=${RESOLVED_SCHEMA_INIT:-false}
  RESOLVED_DISTRIBUTED_CACHE_CLEAR=${RESOLVED_DISTRIBUTED_CACHE_CLEAR:-false}

  # Structural check on the template, before a single secret is written anywhere: a password placeholder
  # that appears in a comment would be substituted there too and republish the password in clear text.
  require_template_secret_placement "$ENTITY_ENGINE_TEMPLATE"

  local sedScript
  sedScript=$(mktemp)
  chmod 600 "$sedScript"
  register_secret_temp_file "$sedScript"
  {
    write_xml_token_substitution '@HOST@' "$OFBIZ_POSTGRES_HOST"
    write_xml_token_substitution '@PORT@' "$OFBIZ_POSTGRES_PORT"
    write_xml_token_substitution '@OFBIZ_DB@' "$OFBIZ_POSTGRES_OFBIZ_DB"
    write_xml_token_substitution '@OFBIZ_USERNAME@' "$OFBIZ_POSTGRES_OFBIZ_USER"
    write_xml_token_substitution '@OFBIZ_PASSWORD@' "$OFBIZ_POSTGRES_OFBIZ_PASSWORD"
    write_xml_token_substitution '@OLAP_DB@' "$OFBIZ_POSTGRES_OLAP_DB"
    write_xml_token_substitution '@OLAP_USERNAME@' "$OFBIZ_POSTGRES_OLAP_USER"
    write_xml_token_substitution '@OLAP_PASSWORD@' "$OFBIZ_POSTGRES_OLAP_PASSWORD"
    write_xml_token_substitution '@TENANT_DB@' "$OFBIZ_POSTGRES_TENANT_DB"
    write_xml_token_substitution '@TENANT_USERNAME@' "$OFBIZ_POSTGRES_TENANT_USER"
    write_xml_token_substitution '@TENANT_PASSWORD@' "$OFBIZ_POSTGRES_TENANT_PASSWORD"
    write_xml_token_substitution '@SSL_PARAMS@' "$RESOLVED_POSTGRES_SSL_PARAMETERS"
    write_xml_token_substitution '@DB_POOL_MIN@' "$OFBIZ_DB_POOL_MIN"
    write_xml_token_substitution '@DB_POOL_MAX@' "$OFBIZ_DB_POOL_MAX"
    write_xml_token_substitution '@SCHEMA_DDL@' "$RESOLVED_SCHEMA_INIT"
    write_xml_token_substitution '@DISTRIBUTED_CACHE_CLEAR@' "$RESOLVED_DISTRIBUTED_CACHE_CLEAR"
  } >"$sedScript"

  render_config_from "$ENTITY_ENGINE_OVERRIDE" "$ENTITY_ENGINE_TEMPLATE" --file="$sedScript"
  discard_secret_temp_files

  # All three checked on the rendered artifact rather than on the substitution, so a template that
  # predates one of these placeholders fails the start up instead of silently producing unencrypted
  # connections, a fleet that issues DDL on every boot, or stale caches behind the load balancer.
  require_rendered_transport_security "$ENTITY_ENGINE_OVERRIDE" "$OFBIZ_POSTGRES_SSLMODE"
  require_rendered_schema_ddl_mode "$ENTITY_ENGINE_OVERRIDE" "$RESOLVED_SCHEMA_INIT"
  require_rendered_cache_clear_mode "$ENTITY_ENGINE_OVERRIDE" "$RESOLVED_DISTRIBUTED_CACHE_CLEAR"
  require_rendered_test_delegator_isolation "$ENTITY_ENGINE_OVERRIDE"

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
  require_rendered_cache_clear_mode "$ENTITY_ENGINE_OVERRIDE" "$RESOLVED_DISTRIBUTED_CACHE_CLEAR"
  require_rendered_test_delegator_isolation "$ENTITY_ENGINE_OVERRIDE"
}

###############################################################################
# Set up the connection to the OFBiz database.
#
# The rendering runs on every container start rather than once behind a marker file, so a rotated
# database password or a changed host takes effect when the container is restarted; it is idempotent
# because it always renders from the pristine template. The marker file is still written, and now
# records the host the configuration was rendered for, so the state directory keeps documenting what
# was applied.
#
# No JDBC driver is downloaded here. The PostgreSQL driver is bundled with the distribution, and
# guard_against_stale_jdbc_drivers refuses to start if an old downloaded copy is still present in the
# lib-extra volume, where it would take class path precedence over the bundled one.
configure_database() {
  if [ -n "$OFBIZ_POSTGRES_HOST" ]; then
    render_database_configuration
    printf '%s\n' "$OFBIZ_POSTGRES_HOST" >"$CONTAINER_DB_CONFIG_APPLIED"
  elif [ "${RESOLVED_DISTRIBUTED_CACHE_CLEAR:-false}" = "true" ]; then
    # No managed database, but cross-instance cache invalidation was asked for, so the committed
    # configuration is rendered with just that one attribute rewritten. Deliberately the only case in
    # which the embedded profile generates an entityengine.xml override at all: with the flag at its
    # default an unconfigured container keeps reading the committed file, byte for byte, as before.
    render_embedded_cache_clear_configuration
  fi

  if [ "${RESOLVED_SCHEMA_INIT:-false}" = "true" ] && [ -z "$OFBIZ_POSTGRES_HOST" ]; then
    printf '%s\n' \
      "OFBIZ_SCHEMA_INIT=true with no OFBIZ_POSTGRES_HOST: the startup DDL flags apply to the managed datasources only. The embedded H2 database always creates its own schema, so this run will initialise H2 and then exit without serving traffic."
  fi
}

###############################################################################
# Send a shutdown signal to OFBiz
shutdown_ofbiz() {
  /ofbiz/send_ofbiz_stop_signal.sh
}

_main() {
  # Checked unconditionally, and before anything else, because a driver jar left in lib-extra takes
  # class path precedence over the bundled driver whether or not initialisation is skipped.
  guard_against_stale_jdbc_drivers

  # Also unconditional: the schema-init flag decides whether this container serves traffic at all, so it
  # is validated before any work is done rather than discovered part way through it.
  resolve_entity_engine_flags

  if [ -z "$OFBIZ_SKIP_INIT" ]; then
    ofbiz_setup_env
    create_ofbiz_runtime_directories
    configure_database
    apply_configuration
    load_data
    load_admin_user
  fi

  unset OFBIZ_TRACE
  unset OFBIZ_SKIP_INIT
  unset OFBIZ_PROFILE
  unset OFBIZ_ADMIN_USER
  unset OFBIZ_ADMIN_PASSWORD
  # The injected secrets have been written to the mode 0600 rendered configuration, so they are
  # removed from the environment the OFBiz JVM inherits. An environment variable is readable through
  # /proc/<pid>/environ, and any child process, crash handler or diagnostic dump that reports the
  # environment would otherwise republish them.
  unset OFBIZ_ADMIN_KEY
  unset OFBIZ_LOGIN_SECRET_KEY
  unset OFBIZ_JWT_TOKEN_KEY
  unset OFBIZ_DATA_LOAD
  unset OFBIZ_ENABLE_AJP_PORT
  unset OFBIZ_JVM_ROUTE
  unset OFBIZ_SSL_ACCELERATOR_PORT
  unset OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS
  unset OFBIZ_HOST
  unset OFBIZ_CONTENT_URL_PREFIX
  unset OFBIZ_POSTGRES_PORT
  unset OFBIZ_POSTGRES_OFBIZ_DB
  unset OFBIZ_POSTGRES_OFBIZ_USER
  unset OFBIZ_POSTGRES_OFBIZ_PASSWORD
  unset OFBIZ_POSTGRES_OLAP_DB
  unset OFBIZ_POSTGRES_OLAP_USER
  unset OFBIZ_POSTGRES_OLAP_PASSWORD
  unset OFBIZ_POSTGRES_TENANT_DB
  unset OFBIZ_POSTGRES_TENANT_USER
  unset OFBIZ_POSTGRES_TENANT_PASSWORD
  unset OFBIZ_POSTGRES_SSLMODE
  unset OFBIZ_POSTGRES_SSLROOTCERT
  unset OFBIZ_DB_POOL_MIN
  unset OFBIZ_DB_POOL_MAX
  unset OFBIZ_SCHEMA_INIT
  unset OFBIZ_DISTRIBUTED_CACHE_CLEAR

  # Schema initialisation is a one-shot job, not a way to start a server. The configuration was rendered
  # with startup DDL enabled and the data load above created the delegator, which is what applied the
  # entity-model DDL, so the work is finished: exit successfully instead of exec'ing the serving command.
  # That is what makes the mode safe to grant DDL privileges to - it never becomes a long-lived process -
  # and it is what lets an orchestrator run it as an init container or a job that must complete before
  # the fleet starts. Every serving instance runs with the flag unset and therefore issues no DDL at all.
  if [ "$RESOLVED_SCHEMA_INIT" = "true" ]; then
    printf '%s\n' "OFBIZ_SCHEMA_INIT=true: schema initialisation complete. Exiting without serving traffic; start the fleet with OFBIZ_SCHEMA_INIT unset."
    exit 0
  fi

  # Continue loading OFBiz.
  exec "$@"
}

_main "$@"

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
# Configuration is applied by rendering a property file or an XML template into
# /ofbiz/config, which precedes ofbiz.jar on the class path, so a rendered value wins over the
# one the image ships. Every value rendered here - secrets included - is read from the environment
# at container start; the packaged copies under /ofbiz/framework and /ofbiz/applications are
# read-only sources and are never written to.
#
# DOCKER.adoc is the reference for the environment variables this script reads, their accepted
# values and their defaults. What each one has to satisfy before it can be applied is expressed by
# ofbiz_setup_env below, which refuses a value this script could not render.
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
set -x
set -e

trap shutdown_ofbiz SIGTERM SIGINT

# The container's OFBiz directory, which is also this script's working directory. A variable rather than
# a literal so that the functions below resolve every path against one root, which lets this script be
# SOURCED into a test harness with a throw-away root instead of being executed. In an image it is always
# /ofbiz, it is INTERNAL to this script and its tests rather than a deployment setting, it is documented
# as such in DOCKER.adoc, and it is unset before the serving command is executed.
OFBIZ_CONTAINER_ROOT="${OFBIZ_CONTAINER_ROOT:-/ofbiz}"

# Per-container state that records what has been done to the DATABASE. It lives under /ofbiz/runtime
# because the demo image bakes empty markers there, and because it describes the database rather than the
# configuration. /ofbiz/runtime as a whole must NOT be shared between instances - only runtime/uploads
# may be, and only with the filesystem content provider; DOCKER.adoc says so.
CONTAINER_STATE_DIR="$OFBIZ_CONTAINER_ROOT/runtime/container_state"
CONTAINER_DATA_LOADED="$CONTAINER_STATE_DIR/data_loaded"
CONTAINER_ADMIN_LOADED="$CONTAINER_STATE_DIR/admin_loaded"
CONTAINER_DB_CONFIG_APPLIED="$CONTAINER_STATE_DIR/db_config_applied"
# The overrides this script has rendered into the config directory. It lives WITH the files it governs,
# inside /ofbiz/config, rather than in the runtime volume: the two are separate volumes with separate
# lifetimes, and a ledger that outlived its artefacts - or artefacts that outlived their ledger - is how
# a recreated container ends up unable to withdraw a previous container's live signing key. Only a file
# listed here is ever removed again, so an override an operator mounted themselves is never touched.
CONTAINER_CONFIG_STATE_DIR="$OFBIZ_CONTAINER_ROOT/config/.ofbiz_container_state"
CONTAINER_MANAGED_OVERRIDES="$CONTAINER_CONFIG_STATE_DIR/managed_overrides"

# The files this script reads from and renders to. The shipped copies under /ofbiz/framework and
# /ofbiz/applications are the sources; the rendered copies under /ofbiz/config are what the JVM reads.
SECURITY_PROPERTIES_SOURCE="framework/security/config/security.properties"
CONTENT_PROPERTIES_SOURCE="applications/content/config/content.properties"
URL_PROPERTIES_SOURCE="framework/webapp/config/url.properties"
START_PROPERTIES_SOURCE="framework/start/src/main/resources/org/apache/ofbiz/base/start/start.properties"
START_PROPERTIES_OVERRIDE="config/org/apache/ofbiz/base/start/start.properties"
CATALINA_COMPONENT="framework/catalina/ofbiz-component.xml"
SERVICE_ENGINE_SOURCE="framework/service/config/serviceengine.xml"
SERVICE_ENGINE_OVERRIDE="config/serviceengine.xml"
JNDI_SERVERS_SOURCE="framework/base/config/jndiservers.xml"
JNDI_SERVERS_OVERRIDE="config/jndiservers.xml"
JNDI_SERVER_NAME="ofbizjms"
ENTITY_ENGINE_TEMPLATE="templates/postgres-entityengine.xml"
ENTITY_ENGINE_OVERRIDE="config/entityengine.xml"

###############################################################################
# Report a configuration fault and stop the container.
# A fault is fatal rather than a warning: a container that carries on with a setting it could not apply
# would serve traffic in a configuration the operator did not ask for.
# $1 - the message
config_fatal() {
  echo "ERROR: $1" >&2
  exit 1
}

###############################################################################
# Refuse a value that cannot be represented in the files this script renders.
# Only what genuinely cannot be represented is refused - a line break, a tab or any other control
# character - because everything else, including & | \ < > " and ', is ESCAPED for its destination by
# the functions below rather than rejected. A managed-database password or a signing key must be free to
# contain those characters.
# $1 - variable name, for the message. $2 - the value.
require_single_line() {
  local controls nonascii
  controls=$(printf '%s' "$2" | LC_ALL=C tr --delete --complement '[:cntrl:]' | wc --bytes)
  if [ "$controls" -ne 0 ]; then
    config_fatal "$1 must not contain a control character. A line break, a tab or any other control character cannot be represented in the property and XML files this script renders."
  fi
  nonascii=$(printf '%s' "$2" | LC_ALL=C tr --delete '\000-\177' | wc --bytes)
  if [ "$nonascii" -ne 0 ]; then
    config_fatal "$1 must contain ASCII characters only. This is a separate restriction from the control-character one above: a Java .properties file is decoded as ISO-8859-1, so a UTF-8 value rendered into one would be read back as different characters - silently, and for a secret that means a value nothing can reproduce. Choose an ASCII value."
  fi
}

###############################################################################
# Refuse a value that is NOT the value it appears to be once it has been rendered.
#
# A Java .properties value has its LEADING whitespace stripped when the file is read back, so a variable
# holding nothing but spaces or tabs renders a property that loads as the EMPTY STRING - and every
# presence and length check upstream of the render is satisfied by it, because the shell counts those
# characters. That is how a whitespace-only signing key passed a 64-character floor and then produced a
# zero-length key, and how a whitespace-only object-store credential pair rendered as empty and let the
# AWS SDK fall back to whatever ambient credentials the host offers. The same reasoning applies to a
# trailing blank, which the reader KEEPS: the value the deployment holds would then differ from the value
# the operator supplied by an invisible character, which for a secret means a value nothing can reproduce.
#
# So a value is required to be MEANINGFUL: not blank once normalised, and carrying no leading or trailing
# whitespace. An empty value is left to the caller, which decides whether an unset variable is allowed;
# this refuses only a value that was supplied and cannot survive the round trip. It never echoes the
# value, so it is safe to call with tracing off, on a secret.
# $1 - variable name, for the message. $2 - the value.
require_meaningful_value() {
  if [ -z "$2" ]; then
    return 0
  fi
  local squeezed
  squeezed=$(printf '%s' "$2" | LC_ALL=C tr --delete '[:space:]')
  if [ -z "$squeezed" ]; then
    config_fatal "$1 holds nothing but whitespace. A Java properties reader strips a value's leading whitespace, so this would be rendered and then read back as an EMPTY value - passing every presence and length check on the way - and the deployment would run with no value at all. Supply a real value, or leave the variable unset."
  fi
  case "$2" in
  [[:space:]]* | *[[:space:]])
    config_fatal "$1 begins or ends with whitespace. A Java properties reader strips the leading whitespace and keeps the trailing whitespace, so the value the deployment would use is not the value supplied here - and for a secret that is a value nothing can reproduce. Remove the surrounding whitespace."
    ;;
  esac
}

###############################################################################
# Refuse a value that is not a TCP port.
#
# One validator for every port this script handles, so no port is held to a weaker rule than another:
# the PostgreSQL port accepted 65536 - which is not a port at all - while the accelerator port next to
# it enforced the upper bound inline.
# $1 - variable name, for the message. $2 - the value.
require_tcp_port() {
  require_positive_integer "$1" "$2"
  if [ "$2" -gt 65535 ]; then
    config_fatal "$1=$2 is not a TCP port: it must be between 1 and 65535."
  fi
}

###############################################################################
# Refuse a value that is not an absolute http or https origin.
#
# Used for the two settings that name a remote origin - the content URL prefix rendered into
# url.properties and the object-store endpoint the S3 client is built from. Both were previously checked
# only for their scheme prefix, which accepted forms that are not an origin at all: "https://?x=1" (no
# host), "https://user:secret@host" (credentials, which are then rendered into a world-readable
# configuration file and emitted into browser content URLs), a fragment, or a port that is not a number.
# Each of those either fails much later, in the JVM or in a browser, or silently publishes a credential.
#
# A path is ALLOWED - a content prefix may legitimately name a directory on a CDN - but a query string,
# a fragment and userinfo are refused, and the host and port are validated. The value is echoed in the
# messages because neither of these two settings is a secret; the userinfo case is the exception and
# names no value at all, precisely because the value would carry a password.
# $1 - variable name, for the message. $2 - the value.
require_absolute_origin() {
  local name="$1" value="$2" rest authority hostport host port
  case "$value" in
  http://?* | https://?*) ;;
  *)
    config_fatal "$name=$value must be an absolute origin beginning http:// or https://, for example https://content.example.com."
    ;;
  esac
  rest="${value#*://}"
  case "$rest" in
  *'?'*)
    config_fatal "$name=$value must not carry a query string: it names an origin, and the parameters would be prefixed onto every path built from it."
    ;;
  *'#'*)
    config_fatal "$name=$value must not carry a fragment: it names an origin, and a fragment is never sent to a server."
    ;;
  esac
  authority="${rest%%/*}"
  case "$authority" in
  *'@'*)
    config_fatal "$name must not carry credentials in its authority (the 'user:password@host' form). This value is rendered into a configuration file and, for a content prefix, into every content URL a browser receives, so the credential would be published. Give the host alone."
    ;;
  esac
  if [ -z "$authority" ]; then
    config_fatal "$name=$value names no host. Give an absolute origin such as https://content.example.com."
  fi
  hostport="$authority"
  case "$hostport" in
  '['*)
    # An IPv6 literal, which RFC 3986 requires to be bracketed. The brackets are what separate the
    # address from the optional port, so they are stripped before either is validated.
    host="${hostport#\[}"
    host="${host%%\]*}"
    port="${hostport#*\]}"
    port="${port#:}"
    case "$host" in
    '' | *[!0-9A-Fa-f:.]*)
      config_fatal "$name=$value carries a bracketed authority that is not an IPv6 address."
      ;;
    esac
    ;;
  *:*)
    host="${hostport%%:*}"
    port="${hostport#*:}"
    ;;
  *)
    host="$hostport"
    port=""
    ;;
  esac
  case "$hostport" in
  '['*) ;;
  *)
    # A registered name or an IPv4 address: letters, digits, hyphens and dots, each label starting and
    # ending with a letter or a digit. Anything else is either not a host or is a spelling a browser and
    # the JDK would resolve differently from one another.
    case "$host" in
    '' | *[!A-Za-z0-9.-]* | -* | *- | .* | *. | *..*)
      config_fatal "$name=$value does not name a valid host. Use letters, digits and hyphens in dot-separated labels, or a bracketed IPv6 literal."
      ;;
    esac
    ;;
  esac
  if [ -n "$port" ]; then
    require_tcp_port "$name port" "$port"
  fi
}

###############################################################################
# Escape a value for use as a java.util.Properties VALUE, where a backslash starts an escape sequence.
# Nothing else is special in a property value: ':' and '=' only separate a key from its value, and '#'
# and '!' only start a comment at the beginning of a line.
# $1 - the value. Writes the escaped value to stdout.
properties_value() {
  printf '%s' "$1" | LC_ALL=C sed --expression='s@\\@\\\\@g'
}

###############################################################################
# Escape a value for use inside a double-quoted XML ATTRIBUTE.
# $1 - the value. Writes the escaped value to stdout.
xml_attribute_value() {
  printf '%s' "$1" | LC_ALL=C sed \
    --expression='s@&@\&amp;@g' \
    --expression='s@<@\&lt;@g' \
    --expression='s@>@\&gt;@g' \
    --expression='s@"@\&quot;@g'
}

###############################################################################
# Escape an already destination-encoded value for use as the REPLACEMENT text of a sed s-program: a
# backslash and an ampersand are special in replacement text, and a delimiter would end the replacement
# early. Every delimiter this script uses is escaped - '|', '@' and '/' - rather than only the ones a
# particular call site happens to use, so a value cannot depend on which delimiter its call site chose.
# An escaped delimiter is read back as the character itself whatever the delimiter in force is.
# Always applied LAST, after properties_value or xml_attribute_value.
# $1 - the value. Writes the escaped value to stdout.
sed_replacement() {
  printf '%s' "$1" | LC_ALL=C sed \
    --expression='s@\\@\\\\@g' \
    --expression='s@&@\\\&@g' \
    --expression='s@|@\\|@g' \
    --expression='s@/@\\/@g' \
    --expression='s@\x40@\\\x40@g'
}

###############################################################################
property_substitution() {
  sed_replacement "$(properties_value "$1")"
}

###############################################################################
xml_substitution() {
  sed_replacement "$(xml_attribute_value "$1")"
}

###############################################################################
# The digest of the configuration a marker file records.
#
# A marker records WHAT was applied rather than merely THAT something was, so a persisted volume - or a
# marker baked into the demo image - cannot silently carry a previous configuration forward. A marker
# written by an older image, or baked into the demo image, holds no digest and therefore matches nothing.
# Configuration itself is no longer marker-gated at all (see apply_configuration): the markers that remain
# describe the DATABASE, where the question "has this already been done" cannot be answered by re-doing it.
# What reaches a marker file is the digest alone, so no plaintext setting is persisted there.
# $1 - "data", "admin", or the startup-DDL mode that was rendered for the database marker.
configuration_digest() {
  { set +x; } 2>/dev/null
  local payload
  case "$1" in
  data | admin)
    # Only the IDENTITY of the database decides whether it has already been populated. A rotated
    # password, a resized pool, a changed TLS mode or a flipped cache-clear flag leave the rows exactly
    # where they are, so they must not invalidate these markers and re-run a data load against a
    # database that is already loaded. Pointing the container at a different host, port, database or
    # user does invalidate them, because that is a different database.
    payload="db-identity/1|$1|$OFBIZ_POSTGRES_HOST|$OFBIZ_POSTGRES_PORT|$OFBIZ_POSTGRES_OFBIZ_DB|$OFBIZ_POSTGRES_OFBIZ_USER|$OFBIZ_POSTGRES_OLAP_DB|$OFBIZ_POSTGRES_OLAP_USER|$OFBIZ_POSTGRES_TENANT_DB|$OFBIZ_POSTGRES_TENANT_USER"
    ;;
  *)
    # NO CREDENTIAL MATERIAL, deliberately. A digest is not a one-way function of a SECRET when the
    # secret is the only unknown in the pre-image: every other field here is either a container setting
    # an operator already knows or is visible in "docker inspect", so a pre-image containing the three
    # database passwords is a persisted, offline-guessable commitment to them - and this file survives on
    # the runtime volume long after the container that wrote it. Passwords are usually far below the
    # entropy that makes such a commitment safe, so they are simply not in it.
    #
    # Nothing is lost. This marker is WRITE-ONLY: it is written here and nowhere read (the data and admin
    # markers, which ARE read, use the credential-free db-identity payload above). What it exists to
    # record is which configuration a container last applied and, in "$1", the startup-DDL mode it was
    # rendered with - which is what makes an interrupted schema init visible to the next start - and a
    # rotated password changes neither. Salting instead would mean persisting a salt beside the digest on
    # the same volume, which is more moving parts for no real gain.
    payload="db/2|$1|$OFBIZ_POSTGRES_HOST|$OFBIZ_POSTGRES_PORT|$OFBIZ_POSTGRES_SSLMODE|$OFBIZ_POSTGRES_SSLROOTCERT|$OFBIZ_DB_POOL_MIN|$OFBIZ_DB_POOL_MAX|$OFBIZ_DISTRIBUTED_CACHE_CLEAR|$OFBIZ_POSTGRES_OFBIZ_DB|$OFBIZ_POSTGRES_OFBIZ_USER|$OFBIZ_POSTGRES_OLAP_DB|$OFBIZ_POSTGRES_OLAP_USER|$OFBIZ_POSTGRES_TENANT_DB|$OFBIZ_POSTGRES_TENANT_USER"
    ;;
  esac
  local digest
  digest=$(printf '%s' "$payload" | sha256sum | cut --delimiter=' ' --fields=1)
  # The digest is emitted BEFORE tracing is restored. Restoring it first traces the printf that emits it -
  # "+ printf %s <digest>" - which put the digest into the container's stderr, and so into "docker logs" and
  # every collector behind it, on every start. Tracing is restored afterwards for callers that invoke this
  # without a command substitution; inside one it makes no difference either way, because a subshell cannot
  # change the caller's tracing state.
  printf '%s' "$digest"
  set -x
}

###############################################################################
# Write a marker, creating its directory first.
#
# The image creates runtime/container_state at build time, but a bind mount of an empty host directory over
# /ofbiz/runtime hides it, and then the redirection below would fail with "No such file or directory" and
# take the whole start-up down with it under "set -e" - after the configuration had already been rendered.
# Creating the directory costs nothing and makes an empty bind mount behave like a fresh named volume.
#
# Tracing is suppressed for the whole function, and the marker is written 0600. The digest is not a secret
# now that no credential is in its pre-image, but a marker is container STATE on a shared, persisted volume:
# tracing it repeats it into "docker logs" and into every log collector reading that stream (CWE-532), and
# world-readable state on a volume that other containers may mount is state anything can read and, with a
# writable mount, forge - a forged data marker skips the data load. Neither is anything to leave to a
# default umask. The explicit chmod matters as much as the umask: a marker left behind 0644 by an older
# image is corrected the next time it is written, rather than staying open forever.
# It takes the digest KIND rather than a digest, and computes the digest itself, so that no digest value is
# ever an ARGUMENT on a traced command line. Suppressing tracing inside a function cannot help with that:
# bash prints the expanded invocation before the function body runs, so a digest passed in has already been
# printed by then. This is why the marker digest appeared repeatedly in "docker logs".
# $1 - the marker path, $2 - the digest kind: "data", "admin", or the rendered startup-DDL mode
mark_applied() {
  { set +x; } 2>/dev/null
  local digest
  digest=$(configuration_digest "$2")
  { set +x; } 2>/dev/null
  mkdir --parents "$(dirname "$1")"
  (
    umask 077
    printf '%s' "$digest" >"$1"
  )
  chmod 600 "$1"
  set -x
}

###############################################################################
# Report whether a data marker already covers the database this container is configured for.
#
# A marker this script wrote holds a digest of the database coordinates. A marker with NO digest was baked
# into the demo image, which loaded that data into the EMBEDDED database it also ships, so it counts only
# while no external database is configured. Pointing the demo image at a managed PostgreSQL server must
# not be able to skip loading data into that server.
# Takes the digest KIND, not a digest, for the same reason mark_applied does: a digest passed as an argument
# is printed by the caller's own trace before this function can suppress anything.
# $1 - the marker path, $2 - the digest kind: "data" or "admin"
data_marker_covers() {
  { set +x; } 2>/dev/null
  local expected
  expected=$(configuration_digest "$2")
  { set +x; } 2>/dev/null
  if [ ! -f "$1" ]; then
    set -x
    return 1
  fi
  local held
  # The FIRST LINE only. The admin marker carries a second line - the credential commitment
  # record_admin_credential writes, which is what lets a rotated admin password be noticed - and reading
  # the whole file would compare the digest against digest+commitment and never match again. Reading one
  # line is also correct for a marker written by an older image, which has no newline at all, and for the
  # empty marker the demo image bakes in.
  held=$(head --lines=1 "$1")
  if [ "$held" = "$expected" ]; then
    set -x
    return 0
  fi
  if [ -z "$held" ] && [ -z "$OFBIZ_POSTGRES_HOST" ]; then
    set -x
    return 0
  fi
  set -x
  return 1
}

###############################################################################
# Record that this script rendered the given override, so that a later start can withdraw it again.
#
# The directory is created here rather than relied on: create_ofbiz_runtime_directories makes it, and
# _main does run that first, but a ledger append that depends on another function having run would fail
# under set -e AFTER the configuration had been rendered - leaving a container whose overrides are in
# place and unrecorded, which is exactly the state that can never be withdrawn. One mkdir removes the
# ordering dependency altogether.
# $1 - the override path
own_override() {
  mkdir --parents "$CONTAINER_CONFIG_STATE_DIR"
  if ! grep --quiet --line-regexp --fixed-strings "$1" "$CONTAINER_MANAGED_OVERRIDES" 2>/dev/null; then
    echo "$1" >>"$CONTAINER_MANAGED_OVERRIDES"
  fi
}

###############################################################################
# Remove an override this script rendered on an earlier start, now that the environment no longer asks
# for it. This is what lets a persisted config volume go back to the shipped defaults - blank signing
# keys, database content storage, embedded H2 - instead of silently keeping the previous container's
# values. A file this script never rendered, such as one an operator mounted, is left alone.
# $1 - the override path
disown_override() {
  local path="$1"
  if grep --quiet --line-regexp --fixed-strings "$path" "$CONTAINER_MANAGED_OVERRIDES" 2>/dev/null; then
    rm --force "$path"
    sed --in-place "\\@^${path}\$@d" "$CONTAINER_MANAGED_OVERRIDES"
    echo "Removed the managed override $path, which this environment no longer asks for"
  fi
}

###############################################################################
require_enum() {
  local name="$1" value="$2"
  shift 2
  local accepted
  for accepted; do
    if [ "$value" = "$accepted" ]; then
      return 0
    fi
  done
  config_fatal "$name=$value is not one of: $*"
}

###############################################################################
require_positive_integer() {
  case "$2" in
  '' | *[!0-9]*) config_fatal "$1=$2 must be a positive integer" ;;
  # Every leading-zero spelling is refused, "0" and "00" included, rather than only some of them: the
  # values these guard become a TCP port or a pool size, and a rendered "007" is not what the operator
  # wrote even where it happens to parse.
  0*) config_fatal "$1=$2 must be a positive integer written without a leading zero" ;;
  esac
}

###############################################################################
# Refuse a value that would change the STRUCTURE of the JDBC URI it is rendered into.
#
# The host and the database name are rendered into "jdbc:postgresql://host:port/database?parameters".
# XML escaping protects the FILE; it does nothing about the URI, so a database name carrying
# "?sslmode=disable&x=1" renders a URI whose FIRST query string is the attacker's - and pgJDBC honours the
# first - which silently downgrades a connection this deployment validated as verify-full to plaintext.
# Rejected rather than percent-encoded, because no legitimate host or database name contains any of these.
# $1 - variable name, for the message. $2 - the value. $3 - "database" to also refuse ':'.
require_uri_component() {
  require_single_line "$1" "$2"
  case "$2" in
  *'?'* | *'&'* | *'/'* | *[\\]* | *'#'* | *' '* | *'@'*)
    config_fatal "$1 must not contain '?', '&', '/', '\\', '#', '@' or a space: it is rendered into the database connection URI, where any of those can add or replace a connection parameter. A value carrying '?sslmode=disable' would downgrade a connection this deployment requires TLS for."
    ;;
  esac
  if [ "$3" = "database" ]; then
    case "$2" in
    *':'*)
      config_fatal "$1 must not contain ':': it is rendered into the database connection URI, where ':' separates the host from the port."
      ;;
    esac
  fi
}

###############################################################################
# Report whether a JMS transport is actually configured for the distributedClear* services.
#
# The jms-service named serviceMessenger is shipped COMMENTED OUT in
# framework/service/config/serviceengine.xml, so switching
# distributed cache clear on without supplying a transport would leave every invalidation undeliverable -
# and, because the distributedClear* services inherit use-transaction, would mark the caller's
# transaction rollback-only. An operator supplies a transport by mounting a serviceengine.xml into the
# config directory, which precedes ofbiz.jar on the class path, with the jms-service uncommented and the
# broker's client library in lib-extra. DOCKER.adoc carries the procedure.
#
# XML comments are stripped before the search, so a jms-service that is only present inside a comment -
# as the shipped one is - does not count as configured.
jms_transport_configured() {
  local candidate
  for candidate in "config/serviceengine.xml" "$SERVICE_ENGINE_SOURCE"; do
    if [ ! -f "$candidate" ]; then
      continue
    fi
    if awk '
      {
        line = $0
        while (length(line) > 0) {
          if (incomment) {
            at = index(line, "-->")
            if (at == 0) { line = "" } else { incomment = 0; line = substr(line, at + 3) }
          } else {
            at = index(line, "<!--")
            if (at == 0) { print line; line = "" }
            else { print substr(line, 1, at - 1); incomment = 1; line = substr(line, at + 4) }
          }
        }
      }' "$candidate" | grep --quiet '<jms-service[^>]*serviceMessenger'; then
      return 0
    fi
  done
  return 1
}

###############################################################################
ofbiz_setup_env() {
  OFBIZ_PROFILE=${OFBIZ_PROFILE:-dev}
  require_enum OFBIZ_PROFILE "$OFBIZ_PROFILE" dev prod

  case "$OFBIZ_DATA_LOAD" in
  none | seed | demo) ;;
  *)
    OFBIZ_DATA_LOAD="seed"
    ;;
  esac

  # Whether this container was GIVEN an admin credential is recorded before either half of it is
  # defaulted, because that is what decides whether a reused database's admin login is left as the data
  # load created it or is brought in line with what the environment asks for. See
  # admin_provisioning_needed. Not a deployment setting - it is internal to this script, and _main unsets
  # it before the serving command runs.
  adminCredentialSupplied="${OFBIZ_ADMIN_USER+yes}${OFBIZ_ADMIN_PASSWORD+yes}"

  OFBIZ_ADMIN_USER=${OFBIZ_ADMIN_USER:-admin}
  # Validated as well as escaped. It is substituted into the admin-user data template, and the template is
  # a SINGLE-LINE sed program, so a value able to introduce a ';' or a '#' could end one expression and
  # comment out the next - which is the expression that sets the password, leaving a SUPER admin with the
  # password the template ships. The escaping below closes that on its own; this allow-list means the
  # value never has to be trusted to have been escaped correctly.
  case "$OFBIZ_ADMIN_USER" in
  '' | *[!A-Za-z0-9._@-]*)
    config_fatal "OFBIZ_ADMIN_USER=$OFBIZ_ADMIN_USER must be 1 to 250 characters from A-Z, a-z, 0-9, '.', '_', '@' and '-'. It becomes a userLoginId, which OFBiz stores as a 250-character identifier."
    ;;
  esac
  if [ "${#OFBIZ_ADMIN_USER}" -gt 250 ]; then
    config_fatal "OFBIZ_ADMIN_USER is longer than the 250 characters a userLoginId holds."
  fi

  OFBIZ_HOST=${OFBIZ_HOST:-}
  OFBIZ_CONTENT_URL_PREFIX=${OFBIZ_CONTENT_URL_PREFIX:-}
  OFBIZ_ENABLE_AJP_PORT=${OFBIZ_ENABLE_AJP_PORT:-}
  require_single_line OFBIZ_HOST "$OFBIZ_HOST"
  require_single_line OFBIZ_CONTENT_URL_PREFIX "$OFBIZ_CONTENT_URL_PREFIX"
  # It is rendered into content.url.prefix.secure and content.url.prefix.standard, which OFBiz prefixes
  # onto content URLs, so it has to be an ABSOLUTE origin. A relative or malformed value produces links
  # that resolve against whatever host served the page, which is how a content URL ends up pointing
  # somewhere the deployment did not choose.
  #
  # TRAILING SLASHES ARE REMOVED FIRST, because OFBiz supplies the separator itself and a prefix that ends
  # in one therefore emits a DOUBLE slash in every content URL the deployment generates. The renderers that
  # consume these two properties append a location that already begins with "/" - so "https://cdn.example/"
  # turns every "/images/logo.png" into "https://cdn.example//images/logo.png". Measured on one back-office
  # page with such a prefix: 40 asset URLs carried the double slash. Most origins normalise "//" and still
  # answer, which is exactly why it is worth removing rather than leaving to chance: it survives casual
  # testing and then breaks whatever is stricter - an object-store or CDN origin that reads "//path" as an
  # empty first path segment, a cache keyed on the raw URL, or a Content-Security-Policy path allow-list.
  # Normalised rather than refused: a trailing slash is a natural way to write an origin, it names the same
  # origin, and there is exactly one correct rendering of it. Every trailing slash is removed, not just one,
  # and the validation below then runs on the normalised value - so a value that is nothing but separators
  # is refused by the absolute-origin check rather than rendered as an empty prefix, which would silently
  # restore the relative URLs the committed url.properties emits. Trailing blanks need no handling here:
  # any whitespace at all is refused a few lines further down.
  if [ -n "$OFBIZ_CONTENT_URL_PREFIX" ]; then
    contentUrlPrefixSupplied="$OFBIZ_CONTENT_URL_PREFIX"
    while [ "${OFBIZ_CONTENT_URL_PREFIX%/}" != "$OFBIZ_CONTENT_URL_PREFIX" ]; do
      OFBIZ_CONTENT_URL_PREFIX="${OFBIZ_CONTENT_URL_PREFIX%/}"
    done
    if [ "$OFBIZ_CONTENT_URL_PREFIX" != "$contentUrlPrefixSupplied" ]; then
      echo "OFBIZ_CONTENT_URL_PREFIX ended with '/'; the trailing separators were removed before rendering. OFBiz appends the separator itself, so a prefix ending in '/' emits a double slash in every content URL of every page. The prefix names the same origin either way." >&2
    fi
    unset contentUrlPrefixSupplied
    case "$OFBIZ_CONTENT_URL_PREFIX" in
    *[[:space:]]* | *'"'* | *"'"*)
      config_fatal "OFBIZ_CONTENT_URL_PREFIX must not contain whitespace or a quote character."
      ;;
    esac
    # The full origin grammar rather than the scheme prefix alone. DOCKER.adoc promises that a value
    # carrying credentials, a query string, a fragment, a malformed port or no host at all is refused, and
    # before this it was not: all of those were accepted and rendered, and the credential form was written
    # into config/url.properties and would have been emitted into every content URL a browser receives.
    require_absolute_origin OFBIZ_CONTENT_URL_PREFIX "$OFBIZ_CONTENT_URL_PREFIX"
    case "$OFBIZ_CONTENT_URL_PREFIX" in
    http://*)
      if [ "$OFBIZ_PROFILE" = "prod" ]; then
        config_fatal "OFBIZ_CONTENT_URL_PREFIX=$OFBIZ_CONTENT_URL_PREFIX is plain http, which is refused in the prod profile: every content URL built from it would be fetched unencrypted. Use an https origin."
      fi
      ;;
    esac
  fi

  OFBIZ_POSTGRES_PORT=${OFBIZ_POSTGRES_PORT:-5432}
  # The bounded validator, the same one the accelerator port uses: 65536 is not a TCP port, and it was
  # accepted here while being refused a few dozen lines below.
  require_tcp_port OFBIZ_POSTGRES_PORT "$OFBIZ_POSTGRES_PORT"

  # Tracing off while the database credentials are defaulted, so that a supplied password is not echoed
  # into the container log by "set -x" and from there into "docker logs" and any log collector (CWE-532).
  { set +x; } 2>/dev/null
  OFBIZ_POSTGRES_OFBIZ_DB=${OFBIZ_POSTGRES_OFBIZ_DB:-ofbiz}
  OFBIZ_POSTGRES_OFBIZ_USER=${OFBIZ_POSTGRES_OFBIZ_USER:-ofbiz}
  OFBIZ_POSTGRES_OFBIZ_PASSWORD=${OFBIZ_POSTGRES_OFBIZ_PASSWORD:-ofbiz}

  OFBIZ_POSTGRES_OLAP_DB=${OFBIZ_POSTGRES_OLAP_DB:-ofbizolap}
  OFBIZ_POSTGRES_OLAP_USER=${OFBIZ_POSTGRES_OLAP_USER:-ofbizolap}
  OFBIZ_POSTGRES_OLAP_PASSWORD=${OFBIZ_POSTGRES_OLAP_PASSWORD:-ofbizolap}

  OFBIZ_POSTGRES_TENANT_DB=${OFBIZ_POSTGRES_TENANT_DB:-ofbiztenant}
  OFBIZ_POSTGRES_TENANT_USER=${OFBIZ_POSTGRES_TENANT_USER:-ofbiztenant}
  OFBIZ_POSTGRES_TENANT_PASSWORD=${OFBIZ_POSTGRES_TENANT_PASSWORD:-ofbiztenant}
  # Every one of these reaches the rendered XML through a sed substitution, so a value carrying a control
  # character has to be refused here rather than corrupt the file. The host and the three database names
  # reach the connection URI as well, where a value carrying '?' or '&' could add or replace a connection
  # parameter, so they are held to the stricter rule. None of these checks ever echoes a value, so it is
  # safe to run them on the passwords with tracing off.
  require_uri_component OFBIZ_POSTGRES_HOST "${OFBIZ_POSTGRES_HOST:-}"
  require_uri_component OFBIZ_POSTGRES_OFBIZ_DB "$OFBIZ_POSTGRES_OFBIZ_DB" database
  require_single_line OFBIZ_POSTGRES_OFBIZ_USER "$OFBIZ_POSTGRES_OFBIZ_USER"
  require_single_line OFBIZ_POSTGRES_OFBIZ_PASSWORD "$OFBIZ_POSTGRES_OFBIZ_PASSWORD"
  require_uri_component OFBIZ_POSTGRES_OLAP_DB "$OFBIZ_POSTGRES_OLAP_DB" database
  require_single_line OFBIZ_POSTGRES_OLAP_USER "$OFBIZ_POSTGRES_OLAP_USER"
  require_single_line OFBIZ_POSTGRES_OLAP_PASSWORD "$OFBIZ_POSTGRES_OLAP_PASSWORD"
  require_uri_component OFBIZ_POSTGRES_TENANT_DB "$OFBIZ_POSTGRES_TENANT_DB" database
  require_single_line OFBIZ_POSTGRES_TENANT_USER "$OFBIZ_POSTGRES_TENANT_USER"
  require_single_line OFBIZ_POSTGRES_TENANT_PASSWORD "$OFBIZ_POSTGRES_TENANT_PASSWORD"
  # Every one of the nine also has to survive the round trip through the file it is rendered into: a
  # whitespace-only or whitespace-padded database password renders a property that loads as a DIFFERENT
  # value, and the connection then fails for a reason nothing in the configuration shows.
  local coordinate
  for coordinate in OFBIZ_POSTGRES_OFBIZ_DB OFBIZ_POSTGRES_OFBIZ_USER OFBIZ_POSTGRES_OFBIZ_PASSWORD \
    OFBIZ_POSTGRES_OLAP_DB OFBIZ_POSTGRES_OLAP_USER OFBIZ_POSTGRES_OLAP_PASSWORD \
    OFBIZ_POSTGRES_TENANT_DB OFBIZ_POSTGRES_TENANT_USER OFBIZ_POSTGRES_TENANT_PASSWORD; do
    require_meaningful_value "$coordinate" "${!coordinate}"
  done
  refuse_published_credential OFBIZ_POSTGRES_OFBIZ_PASSWORD "$OFBIZ_POSTGRES_OFBIZ_PASSWORD"
  refuse_published_credential OFBIZ_POSTGRES_OLAP_PASSWORD "$OFBIZ_POSTGRES_OLAP_PASSWORD"
  refuse_published_credential OFBIZ_POSTGRES_TENANT_PASSWORD "$OFBIZ_POSTGRES_TENANT_PASSWORD"
  # A managed database in production must be given real passwords. The three defaults above exist so that
  # a throw-away local PostgreSQL works with no configuration; they are in this file, in DOCKER.adoc's
  # default column and in every clone, so in the prod profile the literal default is refused by NAME - the
  # comparison is against the known literal, not a strength test - and a supplied password has a length
  # floor. Nothing here echoes a value.
  if [ "$OFBIZ_PROFILE" = "prod" ] && [ -n "${OFBIZ_POSTGRES_HOST:-}" ]; then
    local missingDb="" group
    [ "$OFBIZ_POSTGRES_OFBIZ_PASSWORD" != "ofbiz" ] || missingDb="$missingDb OFBIZ_POSTGRES_OFBIZ_PASSWORD"
    [ "$OFBIZ_POSTGRES_OLAP_PASSWORD" != "ofbizolap" ] || missingDb="$missingDb OFBIZ_POSTGRES_OLAP_PASSWORD"
    [ "$OFBIZ_POSTGRES_TENANT_PASSWORD" != "ofbiztenant" ] || missingDb="$missingDb OFBIZ_POSTGRES_TENANT_PASSWORD"
    if [ -n "$missingDb" ]; then
      config_fatal "OFBIZ_PROFILE=prod with a managed database requires every database password to be supplied. These still hold the default this repository ships, which is public:$missingDb. See DOCKER.adoc."
    fi
    for group in OFBIZ_POSTGRES_OFBIZ_PASSWORD OFBIZ_POSTGRES_OLAP_PASSWORD OFBIZ_POSTGRES_TENANT_PASSWORD; do
      require_minimum_length "$group" "${!group}" 12 "a database password is a deployment secret, and a short one is guessable at the rate a database will accept attempts."
    done
  fi
  set -x

  OFBIZ_DB_POOL_MIN=${OFBIZ_DB_POOL_MIN:-2}
  OFBIZ_DB_POOL_MAX=${OFBIZ_DB_POOL_MAX:-250}
  require_positive_integer OFBIZ_DB_POOL_MIN "$OFBIZ_DB_POOL_MIN"
  require_positive_integer OFBIZ_DB_POOL_MAX "$OFBIZ_DB_POOL_MAX"
  if [ "$OFBIZ_DB_POOL_MIN" -gt "$OFBIZ_DB_POOL_MAX" ]; then
    config_fatal "OFBIZ_DB_POOL_MIN=$OFBIZ_DB_POOL_MIN is greater than OFBIZ_DB_POOL_MAX=$OFBIZ_DB_POOL_MAX"
  fi

  # Transport security for the managed database. pgJDBC's own default, sslmode=prefer, encrypts when the
  # server offers TLS and falls back to PLAINTEXT when it does not, so the prod profile defaults to
  # verify-full - which also authenticates the server - and requires the CA that signs its certificate,
  # because pgJDBC does not consult the JVM trust store. A development profile keeps prefer so that a
  # throw-away local PostgreSQL with no certificate still works.
  if [ "$OFBIZ_PROFILE" = "prod" ]; then
    OFBIZ_POSTGRES_SSLMODE=${OFBIZ_POSTGRES_SSLMODE:-verify-full}
  else
    OFBIZ_POSTGRES_SSLMODE=${OFBIZ_POSTGRES_SSLMODE:-prefer}
  fi
  require_enum OFBIZ_POSTGRES_SSLMODE "$OFBIZ_POSTGRES_SSLMODE" disable allow prefer require verify-ca verify-full
  OFBIZ_POSTGRES_SSLROOTCERT=${OFBIZ_POSTGRES_SSLROOTCERT:-}
  require_single_line OFBIZ_POSTGRES_SSLROOTCERT "$OFBIZ_POSTGRES_SSLROOTCERT"
  if [ -n "${OFBIZ_POSTGRES_HOST:-}" ]; then
    case "$OFBIZ_POSTGRES_SSLMODE" in
    verify-ca | verify-full)
      if [ -z "$OFBIZ_POSTGRES_SSLROOTCERT" ]; then
        config_fatal "OFBIZ_POSTGRES_SSLMODE=$OFBIZ_POSTGRES_SSLMODE requires OFBIZ_POSTGRES_SSLROOTCERT to name the PEM file holding the certificate authority that signed the database server's certificate. The PostgreSQL JDBC driver does not consult the JVM trust store, so verification cannot succeed without it. Mount the CA into the container and point this variable at it, or choose a weaker OFBIZ_POSTGRES_SSLMODE deliberately. See DOCKER.adoc."
      fi
      if [ ! -r "$OFBIZ_POSTGRES_SSLROOTCERT" ]; then
        config_fatal "OFBIZ_POSTGRES_SSLROOTCERT=$OFBIZ_POSTGRES_SSLROOTCERT is not a readable file inside this container. Mount the certificate authority PEM file at that path."
      fi
      ;;
    *)
      if [ "$OFBIZ_PROFILE" = "prod" ]; then
        echo "WARNING: OFBIZ_POSTGRES_SSLMODE=$OFBIZ_POSTGRES_SSLMODE was chosen for a prod deployment. Only verify-ca and verify-full authenticate the database server; disable, allow and prefer can complete the connection in plaintext. See DOCKER.adoc." >&2
      fi
      ;;
    esac
  fi

  OFBIZ_SCHEMA_INIT=${OFBIZ_SCHEMA_INIT:-false}
  require_enum OFBIZ_SCHEMA_INIT "$OFBIZ_SCHEMA_INIT" true false
  if [ "$OFBIZ_SCHEMA_INIT" = "true" ] && [ -z "$OFBIZ_POSTGRES_HOST" ]; then
    config_fatal "OFBIZ_SCHEMA_INIT=true requires OFBIZ_POSTGRES_HOST. The embedded H2 database creates its own schema on every start, so it needs no initialisation run."
  fi

  OFBIZ_DISTRIBUTED_CACHE_CLEAR=${OFBIZ_DISTRIBUTED_CACHE_CLEAR:-false}
  require_enum OFBIZ_DISTRIBUTED_CACHE_CLEAR "$OFBIZ_DISTRIBUTED_CACHE_CLEAR" true false

  # The message transport that carries a cache invalidation to the other instances. Supplying
  # OFBIZ_JMS_PROVIDER_URL is what asks this script to CONFIGURE one: it renders the serviceMessenger
  # jms-service - which OFBiz ships commented out - and the JNDI server it names, into the config
  # directory, which precedes ofbiz.jar on the class path. Nothing is rendered when it is unset, and an
  # operator who prefers to mount their own descriptors is still free to.
  # TRACING OFF FOR THE WHOLE JMS BLOCK, not only for the password. A broker URL is a place operators put
  # a credential - "tcp://user:secret@broker:61616", or a "?password=" parameter - and every validation
  # call below takes its value as an ARGUMENT, which "set -x" prints before the function can suppress
  # anything. The URL was therefore repeated into "docker logs" on every start. Credentials in the URL are
  # refused a few lines further down, but the refusal must not be the first thing that publishes them, so
  # the value is never traced at all. The username is covered too: it is half of a credential.
  { set +x; } 2>/dev/null
  OFBIZ_JMS_PROVIDER_URL=${OFBIZ_JMS_PROVIDER_URL:-}
  OFBIZ_JMS_INITIAL_CONTEXT_FACTORY=${OFBIZ_JMS_INITIAL_CONTEXT_FACTORY:-}
  OFBIZ_JMS_TOPIC_CONNECTION_FACTORY=${OFBIZ_JMS_TOPIC_CONNECTION_FACTORY:-TopicConnectionFactory}
  OFBIZ_JMS_TOPIC=${OFBIZ_JMS_TOPIC:-OFBTopic}
  OFBIZ_JMS_USERNAME=${OFBIZ_JMS_USERNAME:-}
  require_single_line OFBIZ_JMS_PROVIDER_URL "$OFBIZ_JMS_PROVIDER_URL"
  require_single_line OFBIZ_JMS_INITIAL_CONTEXT_FACTORY "$OFBIZ_JMS_INITIAL_CONTEXT_FACTORY"
  require_single_line OFBIZ_JMS_TOPIC_CONNECTION_FACTORY "$OFBIZ_JMS_TOPIC_CONNECTION_FACTORY"
  require_single_line OFBIZ_JMS_TOPIC "$OFBIZ_JMS_TOPIC"
  require_single_line OFBIZ_JMS_USERNAME "$OFBIZ_JMS_USERNAME"
  require_meaningful_value OFBIZ_JMS_PROVIDER_URL "$OFBIZ_JMS_PROVIDER_URL"
  require_meaningful_value OFBIZ_JMS_INITIAL_CONTEXT_FACTORY "$OFBIZ_JMS_INITIAL_CONTEXT_FACTORY"
  require_meaningful_value OFBIZ_JMS_TOPIC_CONNECTION_FACTORY "$OFBIZ_JMS_TOPIC_CONNECTION_FACTORY"
  require_meaningful_value OFBIZ_JMS_TOPIC "$OFBIZ_JMS_TOPIC"
  require_meaningful_value OFBIZ_JMS_USERNAME "$OFBIZ_JMS_USERNAME"
  # A credential in the URL is REFUSED rather than carried. The URL is rendered into
  # config/jndiservers.xml as an attribute and is logged by the JNDI provider itself when it reports a
  # connection, so a credential inside it is a credential in a file and in the log - which is exactly what
  # the dedicated OFBIZ_JMS_USERNAME and OFBIZ_JMS_PASSWORD fields exist to avoid. The message names no
  # value, because the value is the credential.
  case "$OFBIZ_JMS_PROVIDER_URL" in
  *"://"*"@"*)
    config_fatal "OFBIZ_JMS_PROVIDER_URL carries credentials in its authority (the 'user:password@host' form). The URL is rendered into a configuration file and is reported by the JNDI provider in its own log lines, so the credential would be published. Give the broker URL alone and supply OFBIZ_JMS_USERNAME and OFBIZ_JMS_PASSWORD, which are rendered as separate attributes of the JMS server descriptor."
    ;;
  esac
  case "$OFBIZ_JMS_PROVIDER_URL" in
  *[Pp][Aa][Ss][Ss][Ww][Oo][Rr][Dd]=* | *[Ss][Ee][Cc][Rr][Ee][Tt]=* | *[Tt][Oo][Kk][Ee][Nn]=*)
    config_fatal "OFBIZ_JMS_PROVIDER_URL carries what looks like a credential parameter (a 'password=', 'secret=' or 'token=' query parameter). The URL is rendered into a configuration file and reported in provider log lines, so it must not hold a secret. Supply OFBIZ_JMS_USERNAME and OFBIZ_JMS_PASSWORD instead."
    ;;
  esac
  OFBIZ_JMS_PASSWORD=${OFBIZ_JMS_PASSWORD:-}
  require_single_line OFBIZ_JMS_PASSWORD "$OFBIZ_JMS_PASSWORD"
  require_meaningful_value OFBIZ_JMS_PASSWORD "$OFBIZ_JMS_PASSWORD"
  refuse_published_credential OFBIZ_JMS_PASSWORD "$OFBIZ_JMS_PASSWORD"
  # The broker credential is a PAIR, exactly as the object-store credential is. The rendered descriptor
  # carries both attributes or neither, so a password supplied without a username would be silently
  # DISCARDED and the broker connection would then fail authentication for a reason nothing in the
  # configuration shows. Refuse the half-supplied pair instead of dropping the value.
  if [ -n "$OFBIZ_JMS_PASSWORD" ] && [ -z "$OFBIZ_JMS_USERNAME" ]; then
    set -x
    config_fatal "OFBIZ_JMS_PASSWORD is set but OFBIZ_JMS_USERNAME is empty. The JMS credential is rendered as a pair, so the password would be discarded and the broker connection would fail to authenticate. Supply both, or neither and let the broker accept an anonymous connection."
  fi
  if [ -n "$OFBIZ_JMS_USERNAME" ] && [ -z "$OFBIZ_JMS_PASSWORD" ]; then
    set -x
    config_fatal "OFBIZ_JMS_USERNAME is set but OFBIZ_JMS_PASSWORD is empty. Supply both, or neither and let the broker accept an anonymous connection."
  fi
  set -x
  if [ -n "$OFBIZ_JMS_PROVIDER_URL" ]; then
    if [ -z "$OFBIZ_JMS_INITIAL_CONTEXT_FACTORY" ]; then
      config_fatal "OFBIZ_JMS_PROVIDER_URL requires OFBIZ_JMS_INITIAL_CONTEXT_FACTORY, the JNDI initial-context-factory class of your broker's client library - for example org.apache.activemq.jndi.ActiveMQInitialContextFactory. OFBiz reaches the topic through JNDI, so it cannot be derived from the URL. See DOCKER.adoc."
    fi
    if [ -z "$OFBIZ_JMS_TOPIC_CONNECTION_FACTORY" ] || [ -z "$OFBIZ_JMS_TOPIC" ]; then
      config_fatal "OFBIZ_JMS_TOPIC_CONNECTION_FACTORY and OFBIZ_JMS_TOPIC must not be empty: they are the JNDI names of the connection factory and of the topic that carries cache invalidations."
    fi
    if [ "$OFBIZ_DISTRIBUTED_CACHE_CLEAR" != "true" ]; then
      echo "WARNING: OFBIZ_JMS_PROVIDER_URL is set but OFBIZ_DISTRIBUTED_CACHE_CLEAR is not true, so the transport is configured and nothing uses it. Set OFBIZ_DISTRIBUTED_CACHE_CLEAR=true to make the instances invalidate each other's entity caches."
    fi
    # The broker's client library is NOT bundled - it is provider specific and OFBiz ships no JMS client -
    # so it has to be mounted. An empty lib-extra with a transport configured is a certain
    # misconfiguration: the initial-context-factory class could not possibly be loaded.
    if [ -d "$OFBIZ_CONTAINER_ROOT/lib-extra" ] \
      && [ -z "$(find "$OFBIZ_CONTAINER_ROOT/lib-extra" -maxdepth 1 -name '*.jar' -print -quit)" ]; then
      config_fatal "OFBIZ_JMS_PROVIDER_URL is set but $OFBIZ_CONTAINER_ROOT/lib-extra holds no jar. This image bundles NO JMS client library, because the library is specific to the broker, so $OFBIZ_JMS_INITIAL_CONTEXT_FACTORY cannot be loaded and every cache invalidation would fail. Mount your broker's JMS client library into /ofbiz/lib-extra, which precedes ofbiz.jar on the class path. See DOCKER.adoc."
    fi
  fi

  # The order is ENFORCED: the flag cannot be switched on without a transport to carry the invalidations,
  # because the distributedClear* services are declared engine="jms" location="serviceMessenger" and
  # inherit use-transaction, so an undeliverable invalidation marks the caller's transaction rollback-only
  # and loses the entity write that triggered it.
  if [ "$OFBIZ_DISTRIBUTED_CACHE_CLEAR" = "true" ] && [ -z "$OFBIZ_JMS_PROVIDER_URL" ] \
    && ! jms_transport_configured; then
    config_fatal "OFBIZ_DISTRIBUTED_CACHE_CLEAR=true requires a message transport, and none is configured. The distributedClear* services are declared engine=\"jms\" location=\"serviceMessenger\", and the jms-service of that name is shipped commented out in $SERVICE_ENGINE_SOURCE, so with the flag on and no transport every cache invalidation would be undeliverable and would mark the caller's transaction rollback-only. Either set OFBIZ_JMS_PROVIDER_URL and OFBIZ_JMS_INITIAL_CONTEXT_FACTORY and mount your broker's JMS client library in /ofbiz/lib-extra - this script then renders the serviceMessenger jms-service and its JNDI server for you - or mount your own serviceengine.xml into the config directory with that jms-service uncommented. See DOCKER.adoc."
  fi

  # Whether the route was SUPPLIED is recorded before the default is applied, because that is the signal
  # the advisory below reads and the default would erase it.
  jvmRouteSupplied=${OFBIZ_JVM_ROUTE+yes}
  OFBIZ_JVM_ROUTE=${OFBIZ_JVM_ROUTE-jvm1}
  require_single_line OFBIZ_JVM_ROUTE "$OFBIZ_JVM_ROUTE"
  # AN ALLOW-LIST, because this value ends up inside a COOKIE, not only inside XML. Tomcat appends it to
  # every session id as ".<route>" and writes that as the JSESSIONID cookie value, and RFC 6265 cookie-octets
  # exclude a space, a comma, a semicolon, a backslash and a double quote - so Tomcat's cookie processor
  # rejects them at RUN TIME, inside the request, long after start-up has succeeded. That is what a route
  # containing an ASCII space did: the container started, /webtools/health/live answered 200 for as long as it
  # was asked, and every request that created a session answered HTTP 500 "An invalid character [32] was
  # present in the Cookie value" - a target that stayed in service and could serve nothing. XML escaping
  # cannot help: the value was rendered perfectly correctly and was still not a cookie value.
  # Letters, digits, '.', '_' and '-' only, which is what a route needs (it names an instance) and what every
  # balancer that parses a sticky-session suffix accepts. Length is bounded too: the route is appended to
  # every session id, and Tomcat's own session-id length plus a long suffix is not worth discovering in a
  # header limit somewhere. An EMPTY value stays legal - ContainerConfig treats it as absent, which is how a
  # deployment says "no route at all".
  if [ -n "$OFBIZ_JVM_ROUTE" ]; then
    case "$OFBIZ_JVM_ROUTE" in
    *[!A-Za-z0-9._-]*)
      config_fatal "OFBIZ_JVM_ROUTE=$OFBIZ_JVM_ROUTE must contain only letters, digits, '.', '_' and '-'. Tomcat appends the route to every session id and writes it into the JSESSIONID cookie, where RFC 6265 forbids a space, a comma, a semicolon, a backslash and a quote: such a route starts and answers health probes, and then fails every request that creates a session with 'An invalid character was present in the Cookie value'. Name the instance, for example jvm1 or app-2."
      ;;
    esac
    if [ "${#OFBIZ_JVM_ROUTE}" -gt 64 ]; then
      config_fatal "OFBIZ_JVM_ROUTE is longer than 64 characters. It is appended to every session id and therefore to every session cookie; keep it to a short instance name."
    fi
  fi

  # A FLEET RUNNING ON SINGLE-NODE CACHING IS INCOHERENT, and nothing said so at start up. The
  # invalidation flag defaults to false and that default stays: an unconfigured container has to boot with
  # no broker, and switching invalidation on without a transport makes every entity write that triggers one
  # roll back. So this warns instead.
  # WHAT IT COSTS, CONCRETELY: each instance holds its own entity caches, so an update applied through one
  # is not seen by another until that entry ages out - a peer answers from the value it cached earlier. It
  # is not confined to screens. Cache-backed metadata decides response HEADERS too, so a content download
  # can advertise the file name a peer had cached rather than the one just uploaded: correct bytes
  # described by stale metadata, which no page refresh reveals.
  # WHY THE ROUTE IS THE SIGNAL: a container cannot know its fleet size - it has no view of its siblings
  # and no variable states the replica count. OFBIZ_JVM_ROUTE has no purpose on a single node; it exists to
  # give each instance a distinct session-id suffix so a balancer can route sticky sessions to the instance
  # that owns them. An operator who SETS it has said this container runs behind a balancer alongside
  # others, which is the closest thing to a declaration of fleet membership the environment carries. The
  # shipped default is not that declaration, which is why the supplied-ness is what is tested.
  if [ -n "$jvmRouteSupplied" ] && [ "$OFBIZ_DISTRIBUTED_CACHE_CLEAR" != "true" ]; then
    echo "WARNING: OFBIZ_JVM_ROUTE is set, which says this instance runs behind a load balancer alongside others, but OFBIZ_DISTRIBUTED_CACHE_CLEAR is not true - so this instance keeps its entity caches to itself. In a fleet that is not a coherent deployment: an update applied through one instance is not seen by the others until the cached entry ages out, so a peer serves the value it cached earlier, including cache-backed metadata that decides response headers such as the file name a content download advertises, which no page refresh corrects. Set OFBIZ_DISTRIBUTED_CACHE_CLEAR=true on EVERY instance and supply the transport with the OFBIZ_JMS_* variables. Leave it unset only for a single-instance deployment, where there is no peer to be stale. See DOCKER.adoc." >&2
  fi
  unset jvmRouteSupplied

  OFBIZ_SSL_ACCELERATOR_PORT=${OFBIZ_SSL_ACCELERATOR_PORT:-}
  if [ -n "$OFBIZ_SSL_ACCELERATOR_PORT" ]; then
    require_tcp_port OFBIZ_SSL_ACCELERATOR_PORT "$OFBIZ_SSL_ACCELERATOR_PORT"
    # It is the LOCAL port this instance receives forwarded traffic on, never the public HTTPS port of
    # the load balancer, so it has to be one of the connector ports this image actually listens on. A
    # value matching no connector would install a valve that marks nothing secure, and would look
    # configured while doing nothing.
    local declaredPorts
    declaredPorts=$(sed --quiet 's|.*<property name="port" value="\([0-9]\{1,\}\)".*|\1|p' "$CATALINA_COMPONENT" \
      | sort --unique | tr '\n' ' ')
    case " $declaredPorts " in
    *" $OFBIZ_SSL_ACCELERATOR_PORT "*) ;;
    *)
      config_fatal "OFBIZ_SSL_ACCELERATOR_PORT=$OFBIZ_SSL_ACCELERATOR_PORT matches no connector port declared in $CATALINA_COMPONENT (declared: $declaredPorts). Set it to the LOCAL port on which this instance receives plain HTTP forwarded by the TLS-terminating proxy - 8080 out of the box - not to the public HTTPS port of the load balancer."
      ;;
    esac
  fi

  OFBIZ_AJP_BIND_ADDRESS=${OFBIZ_AJP_BIND_ADDRESS:-}
  require_single_line OFBIZ_AJP_BIND_ADDRESS "$OFBIZ_AJP_BIND_ADDRESS"
  if [ -n "$OFBIZ_ENABLE_AJP_PORT" ]; then
    # The AJP connector ships with secretRequired=false and no secret, so it is UNAUTHENTICATED:
    # whoever can open a socket to it speaks AJP and can set the request attributes OFBiz reads. Tomcat
    # itself therefore defaults it to localhost only. Enabling it without saying where to bind would
    # publish it on every interface of the container, so the bind address is required and every wildcard
    # spelling is refused.
    if [ -z "$OFBIZ_AJP_BIND_ADDRESS" ]; then
      config_fatal "OFBIZ_ENABLE_AJP_PORT requires OFBIZ_AJP_BIND_ADDRESS. The AJP connector is unauthenticated (secretRequired=false with no secret), so it must be bound to an explicit private address reachable only by the reverse proxy. See DOCKER.adoc."
    fi
    case "$OFBIZ_AJP_BIND_ADDRESS" in
    0.0.0.0 | :: | "[::]" | "*" | 0.0.0.0/0)
      config_fatal "OFBIZ_AJP_BIND_ADDRESS=$OFBIZ_AJP_BIND_ADDRESS is a wildcard, which would publish the unauthenticated AJP connector on every interface of this container. Give the address of one private interface the reverse proxy reaches."
      ;;
    esac
  fi

  OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS=${OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS:-false}
  require_enum OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS "$OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS" true false

  # The domain the session cookie is issued for. It is what makes cross-subdomain sessions work, and it is
  # required with them rather than derived, because one webapp has ONE session-cookie configuration shared by
  # every client: a domain taken from whichever host happened to arrive first would then be sent to every
  # other client too, and RFC 6265 requires a client under a different parent domain to DISCARD that cookie -
  # a silent session outage. The deployment states the parent domain instead. A leading dot is accepted and
  # removed, because it is the spelling operators are used to and the one RFC 6265 refuses.
  OFBIZ_COOKIE_DOMAIN=${OFBIZ_COOKIE_DOMAIN:-}
  if [ -n "$OFBIZ_COOKIE_DOMAIN" ]; then
    require_single_line OFBIZ_COOKIE_DOMAIN "$OFBIZ_COOKIE_DOMAIN"
    OFBIZ_COOKIE_DOMAIN="${OFBIZ_COOKIE_DOMAIN#.}"
    # Refused rather than escaped: this value becomes a cookie attribute, and everything a domain may not
    # contain - a scheme, a port, a path, a wildcard, a space - would either be rejected by Tomcat's cookie
    # processor at run time, inside the webapp where nothing contains it, or widen the cookie past what was
    # asked for.
    case "$OFBIZ_COOKIE_DOMAIN" in
    *[!A-Za-z0-9.-]* | -* | *- | .* | *. | *..*)
      config_fatal "OFBIZ_COOKIE_DOMAIN=$OFBIZ_COOKIE_DOMAIN is not a domain a cookie may name. RFC 6265 allows letters, digits and hyphens in dot-separated labels, each starting and ending with a letter or a digit: give the parent domain alone, such as example.com, with no scheme, port, path or wildcard."
      ;;
    esac
  fi
  if [ "$OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS" = "true" ] && [ -z "$OFBIZ_COOKIE_DOMAIN" ]; then
    config_fatal "OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS=true also needs OFBIZ_COOKIE_DOMAIN, the parent domain the session cookie is issued for - for instance example.com when this fleet is served as shop.example.com and admin.example.com. Without it there is no domain to share the cookie across and the setting would do nothing at all, so it is refused here rather than starting an instance whose sessions stay host-only while its configuration says otherwise."
  fi

  # ONE provider-name contract, normalised HERE so that this script and ContentStoreFactory cannot
  # disagree about it. The factory trims its property value and lower-cases it (ContentStoreFactory
  # resolve()), so "Database" and " s3 " are the same provider to the JVM - and this script used to refuse
  # both before the JVM ever saw them, which made a documented, tested, working value fail at container
  # start. The normalisation is applied first and the CANONICAL value is what gets rendered.
  # An UNRECOGNISED value is still refused here rather than normalised away. The factory falls back to
  # database storage for one, because a hand-mounted content.properties must not stop content working; a
  # container, on the other hand, was given the value by its deployment, and starting an instance on
  # database storage when the deployment asked for s3 is a silent data-placement change. Failing at start
  # is the whole point of the profile's fail-fast. DOCKER.adoc states both halves of this contract.
  OFBIZ_CONTENT_STORE_PROVIDER=${OFBIZ_CONTENT_STORE_PROVIDER:-database}
  OFBIZ_CONTENT_STORE_PROVIDER=$(printf '%s' "$OFBIZ_CONTENT_STORE_PROVIDER" \
    | LC_ALL=C tr --delete '[:space:]' | LC_ALL=C tr '[:upper:]' '[:lower:]')
  OFBIZ_CONTENT_STORE_PROVIDER=${OFBIZ_CONTENT_STORE_PROVIDER:-database}
  require_enum OFBIZ_CONTENT_STORE_PROVIDER "$OFBIZ_CONTENT_STORE_PROVIDER" database filesystem s3

  OFBIZ_S3_BUCKET=${OFBIZ_S3_BUCKET:-}
  OFBIZ_S3_REGION=${OFBIZ_S3_REGION:-}
  OFBIZ_S3_ENDPOINT=${OFBIZ_S3_ENDPOINT:-}
  OFBIZ_S3_PATH_STYLE=${OFBIZ_S3_PATH_STYLE:-false}
  OFBIZ_S3_ENCRYPTION=${OFBIZ_S3_ENCRYPTION:-none}
  OFBIZ_S3_KMS_KEY_ID=${OFBIZ_S3_KMS_KEY_ID:-}
  require_enum OFBIZ_S3_PATH_STYLE "$OFBIZ_S3_PATH_STYLE" true false
  require_enum OFBIZ_S3_ENCRYPTION "$OFBIZ_S3_ENCRYPTION" none sse-s3 sse-kms
  require_single_line OFBIZ_S3_BUCKET "$OFBIZ_S3_BUCKET"
  require_single_line OFBIZ_S3_REGION "$OFBIZ_S3_REGION"
  require_single_line OFBIZ_S3_ENDPOINT "$OFBIZ_S3_ENDPOINT"
  require_single_line OFBIZ_S3_KMS_KEY_ID "$OFBIZ_S3_KMS_KEY_ID"
  # A whitespace-only bucket, region or endpoint used to pass every check here and then render a property
  # that the JVM reads back as EMPTY - an s3 deployment that reported itself configured and had no bucket.
  require_meaningful_value OFBIZ_S3_BUCKET "$OFBIZ_S3_BUCKET"
  require_meaningful_value OFBIZ_S3_REGION "$OFBIZ_S3_REGION"
  require_meaningful_value OFBIZ_S3_ENDPOINT "$OFBIZ_S3_ENDPOINT"
  require_meaningful_value OFBIZ_S3_KMS_KEY_ID "$OFBIZ_S3_KMS_KEY_ID"
  if [ "$OFBIZ_CONTENT_STORE_PROVIDER" = "s3" ]; then
    if [ -z "$OFBIZ_S3_BUCKET" ] || [ -z "$OFBIZ_S3_REGION" ]; then
      config_fatal "OFBIZ_CONTENT_STORE_PROVIDER=s3 requires OFBIZ_S3_BUCKET and OFBIZ_S3_REGION."
    fi
  fi
  # VALIDATED AT CONTAINER START, not on the first content operation. S3ContentStore refuses an endpoint
  # that is not an absolute http or https URI, but it is constructed lazily by the first file-backed
  # content read or write - so a deployment given "not-a-uri" started, passed its readiness probe and
  # then failed for the first user who touched content. The same grammar is applied here, before the JVM
  # runs, so a malformed endpoint stops the container instead.
  if [ -n "$OFBIZ_S3_ENDPOINT" ]; then
    require_absolute_origin OFBIZ_S3_ENDPOINT "$OFBIZ_S3_ENDPOINT"
  fi
  # Refused here as well as in the provider, so a deployment that asked for KMS encryption without naming
  # a key is told at container start rather than on the first content operation.
  if [ "$OFBIZ_S3_ENCRYPTION" = "sse-kms" ] && [ -z "$OFBIZ_S3_KMS_KEY_ID" ]; then
    config_fatal "OFBIZ_S3_ENCRYPTION=sse-kms requires OFBIZ_S3_KMS_KEY_ID to name the key that encrypts stored content."
  fi
  # A plain http endpoint exposes the object content and the authorization metadata of every request -
  # the access-key identifier and the request signature among it - to anyone on the path, so it is
  # refused in production.
  if [ "$OFBIZ_PROFILE" = "prod" ]; then
    case "$OFBIZ_S3_ENDPOINT" in
    http://*)
      config_fatal "OFBIZ_S3_ENDPOINT=$OFBIZ_S3_ENDPOINT is plain http, which is refused in the prod profile: the object content and the request authorization metadata, including the access-key identifier and signature, would cross the network unencrypted. Use an https endpoint, or run with OFBIZ_PROFILE=dev if the store is genuinely reached over a network you control end to end."
      ;;
    esac
  fi

  OFBIZ_DISABLE_COMPONENTS=${OFBIZ_DISABLE_COMPONENTS-plugins/birt/ofbiz-component.xml}

  resolve_deployment_secrets
}

###############################################################################
# Refuse a secret supplied as a JVM system property on the command line.
#
# A -D property reaches /proc/<pid>/cmdline, which every process on the host can read, and it reaches
# "ps" output and any process collector - so a secret passed that way is readable by anything that can
# look at the process table (CWE-214). Every secret this script handles has an environment variable, and
# every one of them is rendered into a file readable by the ofbiz user alone, so there is never a reason
# to pass one on the command line. Checked for the arguments this container was given AND for the two
# variables the launcher appends to the JVM command line itself.
# $@ - the command this container will execute
refuse_secret_command_line() {
  # TRACING OFF FIRST, AND NOTHING TRACED HOLDS THE SECRET. This function's whole purpose is to refuse a
  # secret that must never be seen, so it may not be the thing that publishes it: with tracing on, the
  # assignment below printed "+ local haystack=... -Dsecurity.token.key=<secret> ..." into the container
  # log, and the refusal that followed was too late. Tracing is left OFF on the way out; _main turns it
  # back on once the inspection is done, which is also why this is the FIRST thing _main does.
  { set +x; } 2>/dev/null
  local names='ofbiz.admin.key login.secret_key_string security.token.key content.store.s3.access.key.id content.store.s3.secret.access.key'
  local haystack="$* ${JAVA_OPTS:-} ${OFBIZ_OPTS:-}"
  local name
  for name in $names; do
    case "$haystack" in
    *"-D$name="*)
      config_fatal "The system property $name must not be set on the command line or in JAVA_OPTS/OFBIZ_OPTS: it would put a secret into /proc/<pid>/cmdline and into the process table, where any process on the host can read it. Supply it through its environment variable instead; DOCKER.adoc lists them."
      ;;
    esac
  done
  unset haystack
}

###############################################################################
# Resolve the four deployment secrets, failing fast in the prod profile.
#
# Tracing is disabled for the whole function - and re-enabled on the way out - so that no secret is
# echoed into the container log, which "docker logs" and any log collector would then hold (CWE-532).
resolve_deployment_secrets() {
  { set +x; } 2>/dev/null

  if [ "$OFBIZ_PROFILE" = "prod" ]; then
    local missing=""
    [ -n "${OFBIZ_ADMIN_PASSWORD:-}" ] || missing="$missing OFBIZ_ADMIN_PASSWORD"
    [ -n "${OFBIZ_ADMIN_KEY:-}" ] || missing="$missing OFBIZ_ADMIN_KEY"
    [ -n "${OFBIZ_LOGIN_SECRET_KEY:-}" ] || missing="$missing OFBIZ_LOGIN_SECRET_KEY"
    [ -n "${OFBIZ_JWT_TOKEN_KEY:-}" ] || missing="$missing OFBIZ_JWT_TOKEN_KEY"
    if [ -n "$missing" ]; then
      config_fatal "OFBIZ_PROFILE=prod requires every deployment secret to be supplied. Missing:$missing. See DOCKER.adoc."
    fi
  fi

  OFBIZ_ADMIN_PASSWORD=${OFBIZ_ADMIN_PASSWORD:-ofbiz}
  require_single_line OFBIZ_ADMIN_PASSWORD "$OFBIZ_ADMIN_PASSWORD"
  # Before the length floor, deliberately: a floor counts characters, and whitespace has characters. A
  # 64-space value satisfied every floor in this function and then rendered a property that Java reads
  # back as empty, so the deployment ran with no secret while reporting that it had one.
  require_meaningful_value OFBIZ_ADMIN_PASSWORD "$OFBIZ_ADMIN_PASSWORD"
  refuse_published_credential OFBIZ_ADMIN_PASSWORD "$OFBIZ_ADMIN_PASSWORD"
  if [ "$OFBIZ_PROFILE" = "prod" ]; then
    # A floor as well as a presence check. The presence check above proves a value was supplied; it says
    # nothing about the value, and prod accepted a one-character password until this floor existed. The
    # admin account is a SUPER login, so its password is the most valuable secret in the deployment.
    require_minimum_length OFBIZ_ADMIN_PASSWORD "$OFBIZ_ADMIN_PASSWORD" 12 \
      "it is the password of a SUPER admin login that can reach every webapp and every service."
  fi

  # Generated rather than left empty in the dev profile: with no key at all the AdminClient cannot
  # authenticate, so "ofbiz --shutdown" and the SIGTERM handler below could not stop the container. The
  # value lives for the life of the container and is never written to the log.
  if [ -z "${OFBIZ_ADMIN_KEY:-}" ]; then
    OFBIZ_ADMIN_KEY=$(tr --delete --complement A-Za-z0-9 </dev/urandom | head --bytes=32)
    echo "OFBIZ_ADMIN_KEY was not supplied, so a random per-container admin key was generated."
  fi
  case "$OFBIZ_ADMIN_KEY" in
  *:*)
    config_fatal "OFBIZ_ADMIN_KEY must not contain ':'. AdminServerContainer reads a request as key:command and compares everything before the first ':' with the key, so a key containing one can never match."
    ;;
  esac
  require_single_line OFBIZ_ADMIN_KEY "$OFBIZ_ADMIN_KEY"
  require_meaningful_value OFBIZ_ADMIN_KEY "$OFBIZ_ADMIN_KEY"
  refuse_published_credential OFBIZ_ADMIN_KEY "$OFBIZ_ADMIN_KEY"
  # 16 characters, which is what the generated per-container key already has 32 of. The key authenticates
  # a request that can shut the instance down, and it is checked with no rate limit at all.
  require_minimum_length OFBIZ_ADMIN_KEY "$OFBIZ_ADMIN_KEY" 16 \
    "it authenticates a request that can stop this instance, and AdminServerContainer rate-limits nothing."

  OFBIZ_LOGIN_SECRET_KEY=${OFBIZ_LOGIN_SECRET_KEY:-}
  OFBIZ_JWT_TOKEN_KEY=${OFBIZ_JWT_TOKEN_KEY:-}
  require_single_line OFBIZ_LOGIN_SECRET_KEY "$OFBIZ_LOGIN_SECRET_KEY"
  require_single_line OFBIZ_JWT_TOKEN_KEY "$OFBIZ_JWT_TOKEN_KEY"
  require_meaningful_value OFBIZ_LOGIN_SECRET_KEY "$OFBIZ_LOGIN_SECRET_KEY"
  require_meaningful_value OFBIZ_JWT_TOKEN_KEY "$OFBIZ_JWT_TOKEN_KEY"
  refuse_published_credential OFBIZ_LOGIN_SECRET_KEY "$OFBIZ_LOGIN_SECRET_KEY"
  refuse_published_credential OFBIZ_JWT_TOKEN_KEY "$OFBIZ_JWT_TOKEN_KEY"
  # GENERATED IN THE DEV PROFILE, exactly as OFBIZ_ADMIN_KEY above is, and for the same kind of reason:
  # both properties are committed BLANK so that no key is ever packaged, and with them blank the features
  # that need them do not merely stay idle - they FAIL. JWTManager refuses a key shorter than 512 bits, so
  # the authenticated REST API answered "POST /rest/auth/token" with HTTP 500 in the zero-configuration H2
  # development mode the documentation offers, while the UI login worked; a developer trying the API had no
  # way to know a secret was the reason. A per-container random key restores that path with no key in the
  # source tree, in the image or in the log.
  # It is RUN-TIME ONLY and PER CONTAINER: a token or a reset link minted by this container is not valid
  # for the next one, and would not be valid on a second instance - which is why it is confined to the dev
  # profile. The prod profile has already refused to start above unless both keys were supplied, so a
  # deployment behind a load balancer still has to give every instance the same explicit key.
  if [ "$OFBIZ_PROFILE" != "prod" ]; then
    if [ -z "$OFBIZ_LOGIN_SECRET_KEY" ]; then
      OFBIZ_LOGIN_SECRET_KEY=$(tr --delete --complement A-Za-z0-9 </dev/urandom | head --bytes=64)
      echo "OFBIZ_LOGIN_SECRET_KEY was not supplied, so a random per-container forgot-password key was generated for this development run. A password-reset link it signs is not valid for any other container. Supply OFBIZ_LOGIN_SECRET_KEY for anything beyond development; the prod profile requires it."
    fi
    if [ -z "$OFBIZ_JWT_TOKEN_KEY" ]; then
      OFBIZ_JWT_TOKEN_KEY=$(tr --delete --complement A-Za-z0-9 </dev/urandom | head --bytes=64)
      echo "OFBIZ_JWT_TOKEN_KEY was not supplied, so a random per-container JWT signing key was generated for this development run. Tokens it signs are not valid for any other container, and the authenticated REST API would have been unusable without it. Supply OFBIZ_JWT_TOKEN_KEY for anything beyond development; the prod profile requires it."
    fi
  fi
  if [ -n "$OFBIZ_JWT_TOKEN_KEY" ] && [ ${#OFBIZ_JWT_TOKEN_KEY} -lt 64 ]; then
    config_fatal "OFBIZ_JWT_TOKEN_KEY must be at least 64 characters: JWTManager rejects a shorter key."
  fi
  # The same contract as the JWT key, and for the same reason: security.properties records that both keys
  # must be 512 bits - 64 characters - because HMAC512 creates the token (OFBIZ-12724). A shorter
  # forgot-password key would be accepted here and then produce tokens weaker than the algorithm assumes.
  if [ -n "$OFBIZ_LOGIN_SECRET_KEY" ] && [ ${#OFBIZ_LOGIN_SECRET_KEY} -lt 64 ]; then
    config_fatal "OFBIZ_LOGIN_SECRET_KEY must be at least 64 characters: it keys HMAC512, which assumes a 512-bit key (see the login.secret_key_string comment in framework/security/config/security.properties)."
  fi

  OFBIZ_S3_ACCESS_KEY_ID=${OFBIZ_S3_ACCESS_KEY_ID:-}
  OFBIZ_S3_SECRET_ACCESS_KEY=${OFBIZ_S3_SECRET_ACCESS_KEY:-}
  require_single_line OFBIZ_S3_ACCESS_KEY_ID "$OFBIZ_S3_ACCESS_KEY_ID"
  require_single_line OFBIZ_S3_SECRET_ACCESS_KEY "$OFBIZ_S3_SECRET_ACCESS_KEY"
  # THE PAIR CHECK BELOW IS ONLY AS GOOD AS THIS ONE. A whitespace-only access key satisfies "-n" here and
  # renders a property Java reads back as empty, so the provider saw an EMPTY pair and used the AWS SDK's
  # default credential chain - whatever ambient role or profile the host offers - for a deployment that
  # believed it had supplied explicit credentials. Refusing the value is what closes that fallback.
  require_meaningful_value OFBIZ_S3_ACCESS_KEY_ID "$OFBIZ_S3_ACCESS_KEY_ID"
  require_meaningful_value OFBIZ_S3_SECRET_ACCESS_KEY "$OFBIZ_S3_SECRET_ACCESS_KEY"
  refuse_published_credential OFBIZ_S3_SECRET_ACCESS_KEY "$OFBIZ_S3_SECRET_ACCESS_KEY"
  if { [ -n "$OFBIZ_S3_ACCESS_KEY_ID" ] && [ -z "$OFBIZ_S3_SECRET_ACCESS_KEY" ]; } \
    || { [ -z "$OFBIZ_S3_ACCESS_KEY_ID" ] && [ -n "$OFBIZ_S3_SECRET_ACCESS_KEY" ]; }; then
    config_fatal "OFBIZ_S3_ACCESS_KEY_ID and OFBIZ_S3_SECRET_ACCESS_KEY are supplied together, or neither is supplied and the AWS SDK's default credential chain is used."
  fi

  set -x
}

###############################################################################
# Credentials this repository PUBLISHES, under docker/examples/postgres-demo. They are in every clone,
# every fork and every mirror of it, so a deployment using one has no secret at all - and an example is
# exactly what gets copied into a first deployment and then forgotten. Refused outright, in both profiles.
PUBLISHED_CREDENTIALS='Ab6SqDD2YM2lmEsvao- P7TFUtQHSuvha8gSxMME 4oXET73QGriblUejjbvR 20wganpfDASBtBXY7GQ6'

###############################################################################
# Refuse a value that is one of the credentials this repository publishes.
#
# It does NOT touch the shell's trace setting, exactly as require_single_line does not: a helper that
# turned tracing back on would re-enable it inside a caller that had deliberately turned it off, and every
# secret handled after that point would reach the container log (CWE-532). CALL IT WITH TRACING OFF. Its
# own message names the variable and never the value.
# $1 - variable name, for the message. $2 - the value.
refuse_published_credential() {
  local published
  for published in $PUBLISHED_CREDENTIALS; do
    if [ "$2" = "$published" ]; then
      config_fatal "$1 is one of the example credentials published in this repository under docker/examples/postgres-demo, so it is public. Generate a new value; the examples exist to show the shape of a deployment, not to be deployed."
    fi
  done
}

###############################################################################
# Refuse a secret shorter than a floor. Never echoes the value; call it with tracing off, as above.
# $1 - variable name. $2 - the value. $3 - the minimum length. $4 - why.
require_minimum_length() {
  if [ "${#2}" -lt "$3" ]; then
    config_fatal "$1 must be at least $3 characters: $4"
  fi
}

###############################################################################
# Create the runtime container state directory used to track which initialisation
# steps have been run for the container.
# This directory should be hosted on a volume that persists for the life of the container.
create_ofbiz_runtime_directories() {
  if [ ! -d "$CONTAINER_STATE_DIR" ]; then
    mkdir --parents "$CONTAINER_STATE_DIR"
  fi
  # The configuration ledger lives beside the overrides it governs, in the config volume, so the two
  # cannot be given different lifetimes by a volume change.
  if [ ! -d "$CONTAINER_CONFIG_STATE_DIR" ]; then
    mkdir --parents "$CONTAINER_CONFIG_STATE_DIR"
  fi
}

###############################################################################
# Prepare the directory multipart request bodies are staged in, and clear anything an EARLIER process
# left in it.
#
# Two problems this addresses, both of them state an instance is not supposed to keep. The bodies staged
# here are user content in transit: until an upload has been stored, its staging file must be no more
# readable than the content it becomes, so the directory is owner-only. And a process that was killed
# mid-request - an OOM, a SIGKILL, a rescheduled container reusing a writable layer or a mounted
# volume - cannot delete what it staged, so those files are left for the next start to remove. The
# application itself now deletes its own staging files at the end of each request, so anything found
# here at startup belongs to no live request by definition.
#
# Deliberately narrow, because it deletes files: only the upload staging directory, only regular files
# whose names carry the staging prefix the upload library uses, never recursing, and never following a
# symbolic link. Anything else in the directory is left alone.
prepare_upload_staging_directory() {
  local staging="$OFBIZ_CONTAINER_ROOT/runtime/tmp"
  if [ ! -d "$staging" ]; then
    mkdir --parents "$staging"
  fi
  chmod 700 "$staging" || echo "WARNING: the upload staging directory $staging could not be made owner-only, so the bodies of uploads in progress may be readable by other accounts in this container."
  local abandoned
  abandoned=$(find "$staging" -maxdepth 1 -type f -name 'upload_*.tmp' -print 2>/dev/null | wc --lines)
  if [ "$abandoned" -gt 0 ]; then
    find "$staging" -maxdepth 1 -type f -name 'upload_*.tmp' -delete 2>/dev/null || true
    echo "Removed $abandoned abandoned multipart upload staging file(s) from $staging. They were left by a process that did not finish its request - this container start owns none of them."
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
load_data() {
  if ! data_marker_covers "$CONTAINER_DATA_LOADED" data; then
    run_init_hooks before-data-load /docker-entrypoint-hooks/before-data-load.d/*

    case "$OFBIZ_DATA_LOAD" in
    none) ;;

    seed)
      "$OFBIZ_CONTAINER_ROOT"/bin/ofbiz --load-data readers=seed,seed-initial
      ;;

    demo)
      "$OFBIZ_CONTAINER_ROOT"/bin/ofbiz --load-data
      mark_applied "$CONTAINER_ADMIN_LOADED" admin
      ;;
    esac

    if [ -z "$(find /docker-entrypoint-hooks/additional-data.d/ -prune -empty)" ]; then
      "$OFBIZ_CONTAINER_ROOT"/bin/ofbiz --load-data dir=/docker-entrypoint-hooks/additional-data.d
    fi

    mark_applied "$CONTAINER_DATA_LOADED" data

    run_init_hooks after-data-load /docker-entrypoint-hooks/after-data-load.d/*
  fi
}

###############################################################################
# The salted hash of the admin password this container would provision, in the form the UserLogin row
# holds it: "<salt>|<sha1 hex of salt+password>". Never echoes the password.
# $1 - the salt to use.
admin_credential_hash() {
  { set +x; } 2>/dev/null
  printf '%s' "$1$OFBIZ_ADMIN_PASSWORD" | sha1sum | cut --delimiter=' ' --fields=1
}

###############################################################################
# Record WHICH admin credential this container provisioned, alongside the database-identity digest.
#
# WHY THIS EXISTS. The admin marker records the identity of the database, deliberately and only that, so
# that a rotated database password or a resized pool cannot invalidate it and re-run a load against a
# database that is already populated. But it made the admin credential itself invisible: restarting on the
# same database with a NEW OFBIZ_ADMIN_PASSWORD matched the marker, skipped the user load, and left the
# OLD password working while the operator believed it had been rotated - and a demo-data start marked the
# admin loaded before load_admin_user ran at all, so a supplied user name or password was ignored outright.
#
# WHAT IS PERSISTED, AND WHY IT IS SAFE. A salt and the SHA-1 of salt+password: EXACTLY the value OFBiz
# stores in UserLogin.currentPassword for this account. It is written into the same 0600 marker, on the
# container-private runtime volume, and anyone who can read that file can also read config/entityengine.xml
# (also 0600) on the same instance and ask the database for the very same hash - so it discloses nothing
# the deployment does not already hold. That is what makes it different from putting a credential in the
# configuration digest, which is a commitment to a secret that is NOT otherwise stored here.
# The salt is stored rather than derived so that the SAME password verifies as unchanged on the next start.
record_admin_credential() {
  { set +x; } 2>/dev/null
  local salt hash
  salt=$(tr --delete --complement A-Za-z0-9 </dev/urandom | head --bytes=16)
  hash=$(admin_credential_hash "$salt")
  # The marker's first line is the digest data_marker_covers reads; this is appended as a second line.
  printf '\n%s\n' "admin-credential/1|$OFBIZ_ADMIN_USER|$salt|$hash" >>"$CONTAINER_ADMIN_LOADED"
  chmod 600 "$CONTAINER_ADMIN_LOADED"
  set -x
}

###############################################################################
# Report whether the admin user has to be created or updated on this start.
#
# Three answers, in order:
#  - the marker does not cover this database at all: provision, exactly as before.
#  - it covers it and NO admin credential was supplied to this container: skip, exactly as before. An
#    operator who named neither a user nor a password has not asked for a particular credential, so the
#    account the data load created is left alone and no start pays for a data load it does not need.
#  - it covers it and a credential WAS supplied: provision only when that credential is not already the
#    active one, which the commitment above is what makes answerable. A missing commitment - an older
#    marker, or the one a demo data load wrote - counts as "not proven", so the supplied credential is
#    applied rather than assumed.
admin_provisioning_needed() {
  if ! data_marker_covers "$CONTAINER_ADMIN_LOADED" admin; then
    return 0
  fi
  if [ -z "${adminCredentialSupplied:-}" ]; then
    return 1
  fi
  { set +x; } 2>/dev/null
  local record recordedUser recordedSalt recordedHash
  record=$(grep --max-count=1 '^admin-credential/1|' "$CONTAINER_ADMIN_LOADED" 2>/dev/null || true)
  if [ -z "$record" ]; then
    set -x
    echo "OFBIZ_ADMIN_USER or OFBIZ_ADMIN_PASSWORD was supplied, and this database's admin marker records no credential this container can compare against, so the admin login is (re)provisioned with the supplied values rather than left as whatever loaded it."
    return 0
  fi
  recordedUser=${record#admin-credential/1|}
  recordedSalt=${recordedUser#*|}
  recordedUser=${recordedUser%%|*}
  recordedHash=${recordedSalt#*|}
  recordedSalt=${recordedSalt%%|*}
  if [ "$recordedUser" = "$OFBIZ_ADMIN_USER" ] && [ "$recordedHash" = "$(admin_credential_hash "$recordedSalt")" ]; then
    { set +x; } 2>/dev/null
    set -x
    return 1
  fi
  set -x
  echo "The supplied admin credential differs from the one this container last provisioned for this database, so the admin login is updated. The previous password stops working once this start completes."
  return 0
}

###############################################################################
load_admin_user() {
  if admin_provisioning_needed; then
    { set +x; } 2>/dev/null
    TMPFILE=$(mktemp)

    SALT=$(tr --delete --complement A-Za-z0-9 </dev/urandom | head --bytes=16)
    SALT_AND_PASSWORD="${SALT}${OFBIZ_ADMIN_PASSWORD}"

    # printf '%s' rather than printf "$value": the password is DATA, and passing it as the format string
    # makes printf interpret it - "A%sB" hashes as "AB", "p\tq" gains a tab - so the hash would not be the
    # hash of the password the operator supplied and the admin could not log in with it.
    SHA1SUM_ASCII_HEX=$(printf '%s' "$SALT_AND_PASSWORD" | sha1sum | cut --delimiter=' ' --fields=1 --zero-terminated | tr --delete '\000')

    SHA1SUM_ESCAPED_STRING=$(printf '%s' "$SHA1SUM_ASCII_HEX" | sed -e 's/\(..\)\.\?/\\x\1/g')
    # This one IS a format string on purpose: it holds \xNN escapes that printf has to interpret to
    # produce the raw digest bytes that are then base64url encoded. It is derived from a hex digest, so it
    # can only ever contain [0-9a-f\x].
    # shellcheck disable=SC2059
    SHA1SUM_BASE64=$(printf "$SHA1SUM_ESCAPED_STRING" | basenc --base64url --wrap=0 | tr --delete '=')

    ENCODED_PASSWORD_HASH="\$SHA\$${SALT}\$${SHA1SUM_BASE64}"

    # '|' as the delimiter, as every other substitution in this script uses, and both values escaped for
    # it. With '/' the user name would have had to be trusted not to contain one.
    sed "s|@userLoginId@|$(xml_substitution "$OFBIZ_ADMIN_USER")|g; s|currentPassword=\".*\"|currentPassword=\"$(sed_replacement "$ENCODED_PASSWORD_HASH")\"|g;" framework/resources/templates/AdminUserLoginData.xml >"$TMPFILE"
    set -x

    "$OFBIZ_CONTAINER_ROOT"/bin/ofbiz --load-data "file=$TMPFILE"

    rm "$TMPFILE"

    mark_applied "$CONTAINER_ADMIN_LOADED" admin
    # Written AFTER the load, so a load that failed under set -e leaves no record claiming the credential
    # is active. The next start then provisions again rather than trusting a commitment nothing applied.
    record_admin_credential
  fi
}

###############################################################################
# Modify the given ofbiz-component configuration XML file to set the root
# component's 'enabled' attribute to false.
# $1 - Path to the XML file to be modified.
disable_component() {
  XML_FILE="$OFBIZ_CONTAINER_ROOT/$1"
  if [ -f "$XML_FILE" ]; then
    TMPFILE=$(mktemp)
    xsltproc "$OFBIZ_CONTAINER_ROOT"/disable-component.xslt "$XML_FILE" > "$TMPFILE"
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

  COMMA_SEPARATED_PATHS="${COMMA_SEPARATED_PATHS//, /,}"

  [ -z "$COMMA_SEPARATED_PATHS" ] && return 0

  oldIFS=$IFS
  IFS=,
  set -- $COMMA_SEPARATED_PATHS    # <- no quotes, use -- to avoid option parsing
  IFS=$oldIFS

  while [ -n "$1" ]; do
    disable_component "$1"
    shift
  done
}

###############################################################################
# Render the two deployment signing keys into config/security.properties, together with the allowed host
# headers. One rendering rather than two, because both write the same file and the second would otherwise
# discard the first.
#
# Tracing is disabled for the whole function so that no key reaches the container log.
apply_security_properties() {
  { set +x; } 2>/dev/null
  local expressions=()

  if [ -n "$OFBIZ_HOST" ]; then
    expressions+=(--expression="s|^host-headers-allowed=.*|host-headers-allowed=$(property_substitution "$OFBIZ_HOST")|")
  fi
  if [ -n "$OFBIZ_LOGIN_SECRET_KEY" ]; then
    expressions+=(--expression="s|^login.secret_key_string=.*|login.secret_key_string=$(property_substitution "$OFBIZ_LOGIN_SECRET_KEY")|")
  fi
  if [ -n "$OFBIZ_JWT_TOKEN_KEY" ]; then
    expressions+=(--expression="s|^security.token.key=.*|security.token.key=$(property_substitution "$OFBIZ_JWT_TOKEN_KEY")|")
  fi

  if [ ${#expressions[@]} -eq 0 ]; then
    set -x
    # Nothing to inject. An override this script wrote on an earlier start is REMOVED rather than left
    # behind, so withdrawing a signing key really does go back to the shipped blank anchors instead of
    # leaving the previous container's key active on a persisted config volume.
    disown_override "config/security.properties"
    return 0
  fi

  sed "${expressions[@]}" "$SECURITY_PROPERTIES_SOURCE" >config/security.properties
  # Readable by the ofbiz user alone: the rendered file holds live signing keys and the config volume may
  # be shared with other containers or inspected on the host.
  chmod 600 config/security.properties
  set -x
  own_override "config/security.properties"
  echo "Rendered config/security.properties"
}

###############################################################################
# Render the admin key into the package-qualified class path override.
#
# ONE destination. The launcher puts config/ ahead of the packaged copy on the class path, so this is the
# file the JVM reads, and docker/send_ofbiz_stop_signal.sh searches the same two paths in the same order,
# so it is also the file that authenticates an admin request the helper sends.
# The helper is reached in two situations, and neither is the ordinary shutdown of a running server. The
# SIGTERM/SIGINT trap at the top of this script calls it while THIS script is still the container's
# process - during data loading, admin-user creation or schema initialisation - so that work in progress
# ends cleanly. Once "exec" has replaced this script with the serving command the JVM is the container's
# process and receives the signal itself, which OFBiz handles through its own JVM shutdown hook, with no
# admin request and no key involved. The other situation is an operator invoking "ofbiz --shutdown",
# "ofbiz --status" or the helper directly.
# The copy shipped inside the image is left exactly as it was built - a secret is never written into it.
#
# Tracing is disabled for the whole function so that the key does not reach the container log.
apply_admin_key() {
  { set +x; } 2>/dev/null
  local program
  program="s|^#\{0,1\}ofbiz.admin.key=.*|ofbiz.admin.key=$(property_substitution "$OFBIZ_ADMIN_KEY")|"

  mkdir --parents "$(dirname "$START_PROPERTIES_OVERRIDE")"
  sed "$program" "$START_PROPERTIES_SOURCE" >"$START_PROPERTIES_OVERRIDE"
  chmod 600 "$START_PROPERTIES_OVERRIDE"
  set -x
  own_override "$START_PROPERTIES_OVERRIDE"
  echo "Rendered the admin key into $START_PROPERTIES_OVERRIDE"
}

###############################################################################
# Render the content storage configuration into config/content.properties.
#
# Tracing is disabled for the whole function so that the object store's secret access key does not reach
# the container log.
apply_content_store() {
  if [ "$OFBIZ_CONTENT_STORE_PROVIDER" = "database" ] && [ -z "$OFBIZ_S3_BUCKET" ] && [ -z "$OFBIZ_S3_REGION" ] \
    && [ -z "$OFBIZ_S3_ENDPOINT" ] && [ -z "$OFBIZ_S3_ACCESS_KEY_ID" ] && [ -z "$OFBIZ_S3_SECRET_ACCESS_KEY" ] \
    && [ "$OFBIZ_S3_PATH_STYLE" = "false" ] && [ "$OFBIZ_S3_ENCRYPTION" = "none" ] \
    && [ -z "$OFBIZ_S3_KMS_KEY_ID" ]; then
    # The shipped content.properties already says exactly this. An override this script wrote on an
    # earlier start is REMOVED rather than left behind, so going back to database storage really does go
    # back to it instead of leaving the previous provider and its credentials active on a persisted
    # config volume.
    disown_override "config/content.properties"
    return 0
  fi
  { set +x; } 2>/dev/null
  sed \
    --expression="s|^content.store.provider=.*|content.store.provider=$(property_substitution "$OFBIZ_CONTENT_STORE_PROVIDER")|" \
    --expression="s|^content.store.s3.bucket=.*|content.store.s3.bucket=$(property_substitution "$OFBIZ_S3_BUCKET")|" \
    --expression="s|^content.store.s3.region=.*|content.store.s3.region=$(property_substitution "$OFBIZ_S3_REGION")|" \
    --expression="s|^content.store.s3.endpoint=.*|content.store.s3.endpoint=$(property_substitution "$OFBIZ_S3_ENDPOINT")|" \
    --expression="s|^content.store.s3.access.key.id=.*|content.store.s3.access.key.id=$(property_substitution "$OFBIZ_S3_ACCESS_KEY_ID")|" \
    --expression="s|^content.store.s3.secret.access.key=.*|content.store.s3.secret.access.key=$(property_substitution "$OFBIZ_S3_SECRET_ACCESS_KEY")|" \
    --expression="s|^content.store.s3.path.style=.*|content.store.s3.path.style=$(property_substitution "$OFBIZ_S3_PATH_STYLE")|" \
    --expression="s|^content.store.s3.encryption=.*|content.store.s3.encryption=$(property_substitution "$OFBIZ_S3_ENCRYPTION")|" \
    --expression="s|^content.store.s3.kms.key.id=.*|content.store.s3.kms.key.id=$(property_substitution "$OFBIZ_S3_KMS_KEY_ID")|" \
    "$CONTENT_PROPERTIES_SOURCE" >config/content.properties
  chmod 600 config/content.properties
  set -x
  own_override "config/content.properties"
  echo "Rendered config/content.properties with the [$OFBIZ_CONTENT_STORE_PROVIDER] content store provider"
}

###############################################################################
# Render the JMS transport that carries distributed cache invalidation.
#
# TWO overrides, both into the config directory, because OFBiz reaches a JMS topic through JNDI and the
# two halves of that live in two files:
#   config/serviceengine.xml - the jms-service named serviceMessenger, which the distributedClear*
#                              services in framework/entityext/servicedef/services.xml are declared
#                              against. The packaged file ships it COMMENTED OUT; this renders a live one.
#   config/jndiservers.xml   - the jndi-server that jms-service names, carrying the broker's provider URL
#                              and its initial-context-factory.
# Both are INSERTED immediately before the closing element of the packaged file rather than by replacing
# its commented example, so the render does not depend on that comment's exact text. Both packaged files
# are read-only sources and are never written to; both are resolved from the class path, which puts
# /ofbiz/config ahead of ofbiz.jar, so the rendered copies are the ones OFBiz reads.
#
# What this does NOT ship is the broker or its client library: OFBiz bundles no JMS client, and the
# library is specific to the provider, so it has to be mounted into /ofbiz/lib-extra. That requirement is
# checked in ofbiz_setup_env and stated in DOCKER.adoc.
#
# Tracing is disabled for the whole function so that the broker password does not reach the container log.
render_jms_transport() {
  { set +x; } 2>/dev/null
  local jndiServer connectionFactory topic credentials
  jndiServer=$(xml_substitution "$JNDI_SERVER_NAME")
  connectionFactory=$(xml_substitution "$OFBIZ_JMS_TOPIC_CONNECTION_FACTORY")
  topic=$(xml_substitution "$OFBIZ_JMS_TOPIC")
  credentials=""
  if [ -n "$OFBIZ_JMS_USERNAME" ]; then
    credentials=" username=\"$(xml_substitution "$OFBIZ_JMS_USERNAME")\" password=\"$(xml_substitution "$OFBIZ_JMS_PASSWORD")\""
  fi

  # listen="true": this instance SUBSCRIBES to the topic as well as publishing to it, which is what makes
  # it act on the invalidations the other instances send - without it a fleet would send invalidations and
  # ignore them. No client-id is set, deliberately: one shared by every instance of a fleet would collide,
  # and a non-durable topic subscriber needs none.
  sed \
    --expression="s|^\( *\)</service-engine>|\1    <jms-service name=\"serviceMessenger\" send-mode=\"all\">\n\1        <server jndi-server-name=\"$jndiServer\" jndi-name=\"$connectionFactory\" topic-queue=\"$topic\" type=\"topic\"$credentials listen=\"true\"/>\n\1    </jms-service>\n\1</service-engine>|" \
    "$SERVICE_ENGINE_SOURCE" >"$SERVICE_ENGINE_OVERRIDE"
  # Readable by the ofbiz user alone: with a broker username and password supplied, this file holds them.
  chmod 600 "$SERVICE_ENGINE_OVERRIDE"

  sed \
    --expression="s|^\( *\)</jndi-config>|\1<jndi-server name=\"$jndiServer\" context-provider-url=\"$(xml_substitution "$OFBIZ_JMS_PROVIDER_URL")\" initial-context-factory=\"$(xml_substitution "$OFBIZ_JMS_INITIAL_CONTEXT_FACTORY")\"/>\n\1</jndi-config>|" \
    "$JNDI_SERVERS_SOURCE" >"$JNDI_SERVERS_OVERRIDE"
  # Readable by the ofbiz user alone, exactly as the service engine override above is. This file holds the
  # broker's provider URL, which identifies the message bus of the whole fleet, and the config volume may
  # be shared with another container or inspected on the host; DOCKER.adoc says both rendered JMS
  # descriptors are owner-readable, and until this chmod existed only one of them was.
  chmod 600 "$JNDI_SERVERS_OVERRIDE"
  set -x
  own_override "$SERVICE_ENGINE_OVERRIDE"
  own_override "$JNDI_SERVERS_OVERRIDE"

  # Read back. A jms-service that did not land, or landed twice, would leave every invalidation
  # undeliverable while the configuration looked complete, so the start is refused instead. Two
  # occurrences are expected in the rendered service engine: the shipped commented example, and this one.
  local services jndiServers
  services=$(grep --count '<jms-service name="serviceMessenger"' "$SERVICE_ENGINE_OVERRIDE" || true)
  jndiServers=$(grep --count "<jndi-server name=\"$JNDI_SERVER_NAME\"" "$JNDI_SERVERS_OVERRIDE" || true)
  if [ "$services" -ne 2 ] || [ "$jndiServers" -ne 1 ]; then
    config_fatal "The JMS transport was not rendered as expected (serviceMessenger occurrences=$services, expected 2 - the shipped commented example plus the one rendered here - and $JNDI_SERVER_NAME occurrences=$jndiServers, expected 1). Refusing to start rather than run with cache invalidation that cannot be delivered."
  fi
  if ! jms_transport_configured; then
    config_fatal "The rendered $SERVICE_ENGINE_OVERRIDE holds no UNCOMMENTED jms-service named serviceMessenger. Refusing to start rather than run with cache invalidation that cannot be delivered."
  fi
  echo "Rendered $SERVICE_ENGINE_OVERRIDE and $JNDI_SERVERS_OVERRIDE: entity-cache invalidations are carried by the topic $OFBIZ_JMS_TOPIC reached through $OFBIZ_JMS_PROVIDER_URL. The broker's own JMS client library must be present in /ofbiz/lib-extra."
}

###############################################################################
# Apply the load-balancer settings to the Tomcat container descriptor, and bind the AJP connector.
#
# Patched in place rather than rendered into config/, because ofbiz-component.xml is a component
# descriptor read from its component directory rather than a property resource resolved on the class path.
#
# Only the PRODUCTION container is touched. The descriptor declares a second container,
# catalina-container-test, which the test loader uses, and a global substitution would rewrite its
# jvm-route too - changing what gradlew testIntegration runs with. Every substitution below is therefore
# bounded to the first match, and the result is read back and asserted.
apply_load_balancer_settings() {
  local route sslPort crossSubdomain
  route=$(xml_substitution "$OFBIZ_JVM_ROUTE")
  sslPort=$(xml_substitution "$OFBIZ_SSL_ACCELERATOR_PORT")
  crossSubdomain=$(xml_substitution "$OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS")

  sed --in-place \
    --expression="0,\\@<property name=\"jvm-route\"@ s@<property name=\"jvm-route\" value=\"[^\"]*\"/>@<property name=\"jvm-route\" value=\"$route\"/>@" \
    --expression="s@<property name=\"ssl-accelerator-port\" value=\"[^\"]*\"/>@<property name=\"ssl-accelerator-port\" value=\"$sslPort\"/>@" \
    --expression="s@<property name=\"enable-cross-subdomain-sessions\" value=\"[^\"]*\"/>@<property name=\"enable-cross-subdomain-sessions\" value=\"$crossSubdomain\"/>@" \
    "$CATALINA_COMPONENT"

  # Read back: exactly two jvm-route properties must remain, the first must hold the configured value and
  # the second - the test loader's - must still hold the value the descriptor ships with.
  local routeCount productionRoute testRoute
  routeCount=$(grep --count '<property name="jvm-route"' "$CATALINA_COMPONENT")
  productionRoute=$(grep '<property name="jvm-route"' "$CATALINA_COMPONENT" | sed --quiet '1p' \
    | sed 's|.*value="\([^"]*\)".*|\1|')
  testRoute=$(grep '<property name="jvm-route"' "$CATALINA_COMPONENT" | sed --quiet '2p' \
    | sed 's|.*value="\([^"]*\)".*|\1|')
  if [ "$routeCount" -ne 2 ] || [ "$productionRoute" != "$route" ] || [ "$testRoute" != "jvm1" ]; then
    config_fatal "The jvm-route substitution did not change exactly the production route in $CATALINA_COMPONENT (found $routeCount routes, production=[$productionRoute], test=[$testRoute]). Refusing to start rather than serve with a route the operator did not ask for."
  fi

  # Withdraw any AJP bind address inserted on an earlier start, so that unsetting OFBIZ_ENABLE_AJP_PORT
  # restores Tomcat's localhost-only default on a persisted image layer. The descriptor declares no
  # address property of its own, so this only ever removes an inserted one.
  sed --in-place '\@^[[:space:]]*<property name="address" value="[^"]*"/>$@d' "$CATALINA_COMPONENT"
  if [ -n "$OFBIZ_ENABLE_AJP_PORT" ]; then
    local bindAddress
    bindAddress=$(xml_substitution "$OFBIZ_AJP_BIND_ADDRESS")
    sed --in-place \
      --expression="0,\\@<property name=\"ajp-connector\" value=\"connector\">@ s@<property name=\"ajp-connector\" value=\"connector\">@&\n            <property name=\"address\" value=\"$bindAddress\"/>@" \
      "$CATALINA_COMPONENT"
    local addressCount
    addressCount=$(grep --count '^[[:space:]]*<property name="address" value="' "$CATALINA_COMPONENT" || true)
    if [ "$addressCount" -ne 1 ]; then
      config_fatal "The AJP bind address was not inserted exactly once into $CATALINA_COMPONENT (found $addressCount). Refusing to start rather than publish an unauthenticated connector on an address the operator did not choose."
    fi
    echo "The AJP connector is bound to $OFBIZ_AJP_BIND_ADDRESS. It is unauthenticated (secretRequired=false), so that address must be reachable only by the reverse proxy; restrict it in the network layer."
  fi

  if [ -n "$OFBIZ_SSL_ACCELERATOR_PORT" ]; then
    echo "WARNING: every request arriving on the LOCAL port $OFBIZ_SSL_ACCELERATOR_PORT will be treated as secure. SslAcceleratorValve verifies nothing about the sender, so that port must be reachable only through the TLS-terminating proxy; restrict it in the network layer. See DOCKER.adoc."
  fi
}

###############################################################################
# Apply any configuration changes required.
# Changed property files need to be placed in the config directory so they appear earlier in the
# classpath and override the build-time copies of the properties in ofbiz.jar.
#
# UNCONDITIONAL, deliberately, and NOT gated by a marker. Configuration is rendered from the packaged
# sources on every start, so what the JVM reads is always what this container's environment asks for.
# Marker-gating it could not be made safe: the files it governs do not all live in one volume - the
# property overrides are in /ofbiz/config, the Tomcat descriptor is patched in place under /ofbiz/framework
# which is NOT a volume and is therefore pristine from the image on every recreation - so a marker in
# either volume can outlive or be outlived by the artefacts it vouches for, and a "production" instance
# would then come up without its load-balancer settings, without its injected secrets and on the embedded
# database, reporting healthy the whole time. Every step below is idempotent: each renders from a packaged
# source that is never written to, and the in-place descriptor patch reads its own result back and refuses
# to start if it is not what was asked for.
apply_configuration() {
  run_init_hooks before-config-applied /docker-entrypoint-hooks/before-config-applied.d/*

  apply_security_properties
  apply_admin_key
  apply_content_store
  apply_load_balancer_settings

  if [ -n "$OFBIZ_JMS_PROVIDER_URL" ]; then
    render_jms_transport
  else
    # Withdrawn rather than left behind, so removing the transport from the environment really does go
    # back to single-node caching instead of leaving the previous container's broker configured.
    disown_override "$SERVICE_ENGINE_OVERRIDE"
    disown_override "$JNDI_SERVERS_OVERRIDE"
  fi

  # One override for both settings this file carries, so neither write can undo the other.
  if [ -n "$OFBIZ_CONTENT_URL_PREFIX" ] || [ -n "$OFBIZ_COOKIE_DOMAIN" ]; then
    sed \
      --expression="s|^content.url.prefix.secure=.*|content.url.prefix.secure=$(property_substitution "$OFBIZ_CONTENT_URL_PREFIX")|" \
      --expression="s|^content.url.prefix.standard=.*|content.url.prefix.standard=$(property_substitution "$OFBIZ_CONTENT_URL_PREFIX")|" \
      --expression="s|^cookie.domain=.*|cookie.domain=$(property_substitution "$OFBIZ_COOKIE_DOMAIN")|" \
      "$URL_PROPERTIES_SOURCE" >config/url.properties
    # Readable by the ofbiz user alone, like every other file this script renders. It is not a secret
    # store, but it is deployment configuration on a volume that may be shared or inspected, and treating
    # one rendered file differently from the rest is how a value nobody expected to be readable ends up
    # readable.
    chmod 600 config/url.properties
    own_override "config/url.properties"
  else
    disown_override "config/url.properties"
  fi

  if [ -n "$OFBIZ_DISABLE_COMPONENTS" ]; then
    disable_components "$OFBIZ_DISABLE_COMPONENTS"
  fi

  run_init_hooks after-config-applied /docker-entrypoint-hooks/after-config-applied.d/*
}

###############################################################################
# Render the PostgreSQL entity engine configuration.
# $1 - the check-on-start value, $2 - the add-missing-on-start value.
#
# Tracing is disabled for the whole function so that the three database passwords do not reach the
# container log.
render_entity_engine() {
  { set +x; } 2>/dev/null
  local checkOnStart="$1" addMissingOnStart="$2"

  require_single_line OFBIZ_POSTGRES_HOST "$OFBIZ_POSTGRES_HOST"
  local name
  for name in OFBIZ_POSTGRES_OFBIZ_DB OFBIZ_POSTGRES_OFBIZ_USER OFBIZ_POSTGRES_OFBIZ_PASSWORD \
    OFBIZ_POSTGRES_OLAP_DB OFBIZ_POSTGRES_OLAP_USER OFBIZ_POSTGRES_OLAP_PASSWORD \
    OFBIZ_POSTGRES_TENANT_DB OFBIZ_POSTGRES_TENANT_USER OFBIZ_POSTGRES_TENANT_PASSWORD; do
    require_single_line "$name" "${!name}"
  done

  # The TLS parameters are INSERTED into every PostgreSQL jdbc-uri rather than carried as a token, so the
  # template keeps exactly the token census it declares. "?connectTimeout=" appears only in the
  # PostgreSQL URIs - the embedded H2 ones separate their parameters with ';' - so this reaches those and
  # nothing else. sslrootcert is appended only when one was given, because pgJDBC treats an empty value
  # as a path.
  local sslParameters
  sslParameters="sslmode=$(xml_attribute_value "$OFBIZ_POSTGRES_SSLMODE")&amp;"
  if [ -n "$OFBIZ_POSTGRES_SSLROOTCERT" ]; then
    sslParameters="${sslParameters}sslrootcert=$(xml_attribute_value "$OFBIZ_POSTGRES_SSLROOTCERT")&amp;"
  fi

  sed \
    --expression="s|@HOST@|$(xml_substitution "$OFBIZ_POSTGRES_HOST")|g" \
    --expression="s|@PORT@|$(xml_substitution "$OFBIZ_POSTGRES_PORT")|g" \
    --expression="s|@OFBIZ_DB@|$(xml_substitution "$OFBIZ_POSTGRES_OFBIZ_DB")|g" \
    --expression="s|@OFBIZ_USERNAME@|$(xml_substitution "$OFBIZ_POSTGRES_OFBIZ_USER")|g" \
    --expression="s|@OFBIZ_PASSWORD@|$(xml_substitution "$OFBIZ_POSTGRES_OFBIZ_PASSWORD")|g" \
    --expression="s|@OLAP_DB@|$(xml_substitution "$OFBIZ_POSTGRES_OLAP_DB")|g" \
    --expression="s|@OLAP_USERNAME@|$(xml_substitution "$OFBIZ_POSTGRES_OLAP_USER")|g" \
    --expression="s|@OLAP_PASSWORD@|$(xml_substitution "$OFBIZ_POSTGRES_OLAP_PASSWORD")|g" \
    --expression="s|@TENANT_DB@|$(xml_substitution "$OFBIZ_POSTGRES_TENANT_DB")|g" \
    --expression="s|@TENANT_USERNAME@|$(xml_substitution "$OFBIZ_POSTGRES_TENANT_USER")|g" \
    --expression="s|@TENANT_PASSWORD@|$(xml_substitution "$OFBIZ_POSTGRES_TENANT_PASSWORD")|g" \
    --expression="s|@CHECK_ON_START@|$checkOnStart|g" \
    --expression="s|@ADD_MISSING_ON_START@|$addMissingOnStart|g" \
    --expression="s|@DISTRIBUTED_CACHE_CLEAR@|$OFBIZ_DISTRIBUTED_CACHE_CLEAR|g" \
    --expression="s|@DB_POOL_MIN@|$OFBIZ_DB_POOL_MIN|g" \
    --expression="s|@DB_POOL_MAX@|$OFBIZ_DB_POOL_MAX|g" \
    --expression="s|?connectTimeout=|?$(sed_replacement "$sslParameters")connectTimeout=|g" \
    "$ENTITY_ENGINE_TEMPLATE" >"$ENTITY_ENGINE_OVERRIDE"
  chmod 600 "$ENTITY_ENGINE_OVERRIDE"
  set -x
  own_override "$ENTITY_ENGINE_OVERRIDE"

  # A token that survived would be used verbatim as a host name, a password or a boolean, so the start is
  # refused instead. The check names no value, only the token that is still there.
  local survivors
  survivors=$(grep --only-matching '@[A-Z_]\{2,\}@' "$ENTITY_ENGINE_OVERRIDE" | sort --unique | tr '\n' ' ') || survivors=""
  if [ -n "$survivors" ]; then
    config_fatal "Rendered $ENTITY_ENGINE_OVERRIDE still contains unsubstituted tokens: $survivors. The template and this script have diverged."
  fi

  # The marker records the mode that was just rendered. An interrupted initialisation therefore leaves a
  # marker a serving start cannot match, and that start re-renders with startup DDL disabled.
  mark_applied "$CONTAINER_DB_CONFIG_APPLIED" "$checkOnStart $addMissingOnStart"
  echo "Rendered $ENTITY_ENGINE_OVERRIDE with startup DDL check-on-start=$checkOnStart add-missing-on-start=$addMissingOnStart"
}

###############################################################################
# Set up the connection to the OFBiz database.
#
# Nothing is rendered when OFBIZ_POSTGRES_HOST is empty, so an unconfigured container boots on the
# embedded H2 database exactly as it always has - and an entity-engine override this script wrote on an
# earlier start is removed, so withdrawing the database environment really does go back to H2.
#
# UNCONDITIONAL, like apply_configuration and for the same reason. A serving start ALWAYS renders the run
# mode, so an initialisation that was interrupted after enabling start-up DDL cannot leave a configuration
# a serving instance would inherit - there is no marker to match and therefore no way to skip the render.
# The marker is still written, as a RECORD of the mode that was applied for an operator to read, but
# nothing depends on it any more.
configure_database() {
  if [ -n "$OFBIZ_POSTGRES_HOST" ]; then
    render_entity_engine false false
  else
    disown_override "$ENTITY_ENGINE_OVERRIDE"
    mark_applied "$CONTAINER_DB_CONFIG_APPLIED" "false false"
  fi
}

###############################################################################
# Perform the one-shot schema initialisation and exit, when it was asked for.
#
# The schema is created by the Entity Engine itself from the entity model - no migration framework, no
# hand-written DDL - by starting OFBiz once with startup DDL enabled and loading nothing. The
# configuration is then rewritten with startup DDL disabled, so that this container's own config volume
# cannot be reused by a serving instance with DDL still enabled.
run_schema_init() {
  if [ "$OFBIZ_SCHEMA_INIT" != "true" ]; then
    return 0
  fi

  echo "OFBIZ_SCHEMA_INIT=true: creating the database schema from the entity model, then exiting."
  local report started groups loaderStatus failures

  # Whatever ends this process next - the initialisation failing, set -e, or a signal - the run mode is
  # put back first. Without this an interrupted initialisation would leave config/entityengine.xml with
  # startup DDL ENABLED on a persisted volume, and a serving start would then issue DDL and need DDL
  # privileges. The marker render_entity_engine writes is the second line of defence: it records the mode,
  # so even a kill that outruns this trap leaves a marker no serving start can match.
  trap 'render_entity_engine false false' EXIT
  render_entity_engine true true

  # THE OUTCOME IS AGGREGATED, NOT INFERRED FROM THE EXIT STATUS.
  #
  # "bin/ofbiz --load-data" returns 0 whenever the JVM itself completed, and the Entity Engine's start-up
  # schema check is deliberately non-fatal: GenericDelegator logs a warning and carries on when a group's
  # datasource cannot be checked, and DatabaseUtil LOGS "Unable to establish a connection with the
  # database", "Could not get table name information ... aborting" and "Could not create table [...]"
  # rather than raising. A one-shot job that reports only that status therefore printed a success banner
  # and exited 0 after creating NOTHING - measured twice: valid main and OLAP credentials with a wrong
  # tenant password produced 852/7/0 tables and 51 authentication failures, and a DML-only role on three
  # empty databases produced 0/0/0 tables and thousands of permission denials. An orchestrator reads that
  # as a completed migration and rolls a fleet out onto a schema that is absent or half-built.
  #
  # So the run is judged on three things, all of which must hold:
  #   1. the loader's own exit status, taken from PIPESTATUS so that "tee" cannot mask it;
  #   2. no line matching a definitive datasource or DDL failure - each string below is emitted by
  #      DatabaseUtil, GenericDelegator or the data loader itself and means the operation did not happen;
  #   3. every entity group the rendered configuration points at PostgreSQL was actually checked. The
  #      count comes from the rendered file rather than a literal, so adding or removing a group-map
  #      cannot silently lower the bar.
  # The output is teed rather than swallowed, so "docker logs" still carries the whole init.
  report=$(mktemp)
  set +e
  "$OFBIZ_CONTAINER_ROOT"/bin/ofbiz --load-data readers=none 2>&1 | tee "$report"
  loaderStatus=${PIPESTATUS[0]}
  set -e

  groups=$(awk '
    /<delegator name="default"[ >]/ { inside = 1 }
    inside && /datasource-name="localpostgres/ { found++ }
    inside && /<\/delegator>/ { inside = 0 }
    END { print found + 0 }' "$ENTITY_ENGINE_OVERRIDE")
  started=$(grep --count 'Doing database check as requested in entityengine.xml' "$report" || true)
  failures=$(grep --extended-regexp --count \
    'Unable to establish a connection with the database|SQL Error obtaining JDBC connection|Could not get table name information from the database|Could not get column information from the database|Could not create table \[|Could not add column \[|Could not create primary key|Error loading data file|permission denied for|password authentication failed|no pg_hba\.conf entry' \
    "$report" || true)

  # The run mode is restored BEFORE the verdict, so a failed initialisation cannot leave startup DDL
  # enabled in the config volume for a serving start to inherit - exactly as the EXIT trap would.
  render_entity_engine false false
  trap - EXIT

  if [ "$loaderStatus" -ne 0 ] || [ "$failures" -ne 0 ] || [ "$started" -ne "$groups" ]; then
    echo "ERROR: schema initialisation FAILED. The loader exited $loaderStatus, $failures datasource or DDL failure line(s) were logged, and $started of $groups configured entity group(s) were checked. Startup DDL has been switched back off in the rendered configuration. Do NOT start serving instances against this database: its schema is absent or incomplete. The first failure lines were:" >&2
    grep --extended-regexp --max-count=10 \
      'Unable to establish a connection with the database|SQL Error obtaining JDBC connection|Could not get table name information from the database|Could not get column information from the database|Could not create table \[|Could not add column \[|Could not create primary key|Error loading data file|permission denied for|password authentication failed|no pg_hba\.conf entry' \
      "$report" >&2 || true
    rm --force "$report"
    exit 1
  fi
  rm --force "$report"
  echo "Schema initialisation is complete: all $groups configured entity group(s) were checked and no datasource or DDL failure was logged. Start the serving instances now; they issue no DDL and need no DDL privilege."
  exit 0
}

###############################################################################
shutdown_ofbiz() {
  "$OFBIZ_CONTAINER_ROOT"/send_ofbiz_stop_signal.sh
}

_main() {
  # The deployment configuration is resolved and VALIDATED unconditionally, BEFORE OFBIZ_SKIP_INIT is
  # honoured. Skip-init exists to skip the mutating steps - rendering, data loading, creating the admin
  # user - on a container whose volumes already carry them. It must not be able to skip the fail-fast that
  # refuses to start a prod deployment with a missing secret, nor the validation that refuses an unusable
  # port, address, TLS mode or cache-clear configuration.
  # THE COMMAND LINE IS INSPECTED FIRST, WITH TRACING OFF. This script is invoked with tracing already
  # disabled (see the bottom of the file), because bash prints a function's expanded invocation - here
  # "+ _main <every argument>" - BEFORE the function body runs, so any secret passed as an argument was
  # already in the container log by the time the refusal below could reject it. refuse_secret_command_line
  # leaves tracing off; it is turned back on immediately afterwards, so everything from the environment
  # resolution onwards is traced exactly as before, minus the secrets each of those functions suppresses
  # for itself.
  refuse_secret_command_line "$@"
  set -x
  ofbiz_setup_env
  # Outside the OFBIZ_SKIP_INIT branch deliberately: this neither renders configuration nor loads data,
  # it only makes the multipart staging directory owner-only and removes staging files an earlier process
  # was killed before it could delete. A skip-init container is exactly the one that reuses a volume, so
  # it is the one that most needs the sweep.
  prepare_upload_staging_directory

  if [ -z "$OFBIZ_SKIP_INIT" ]; then
    create_ofbiz_runtime_directories
    configure_database
    apply_configuration
    run_schema_init
    load_data
    load_admin_user
  else
    echo "OFBIZ_SKIP_INIT is set: the deployment configuration was validated but nothing was rendered and no data was loaded. This container starts with whatever its volumes already carry, so anything supplied through them - config/entityengine.xml, config/security.properties, the admin key - must already be correct for this environment."
  fi

  unset OFBIZ_SKIP_INIT
  unset OFBIZ_PROFILE
  unset OFBIZ_ADMIN_USER
  unset OFBIZ_ADMIN_PASSWORD
  unset OFBIZ_ADMIN_KEY
  unset OFBIZ_LOGIN_SECRET_KEY
  unset OFBIZ_JWT_TOKEN_KEY
  unset OFBIZ_DATA_LOAD
  unset OFBIZ_ENABLE_AJP_PORT
  unset OFBIZ_AJP_BIND_ADDRESS
  unset OFBIZ_HOST
  unset OFBIZ_CONTENT_URL_PREFIX
  unset OFBIZ_POSTGRES_PORT
  unset OFBIZ_POSTGRES_SSLMODE
  unset OFBIZ_POSTGRES_SSLROOTCERT
  unset OFBIZ_POSTGRES_OFBIZ_DB
  unset OFBIZ_POSTGRES_OFBIZ_USER
  unset OFBIZ_POSTGRES_OFBIZ_PASSWORD
  unset OFBIZ_POSTGRES_OLAP_DB
  unset OFBIZ_POSTGRES_OLAP_USER
  unset OFBIZ_POSTGRES_OLAP_PASSWORD
  unset OFBIZ_POSTGRES_TENANT_DB
  unset OFBIZ_POSTGRES_TENANT_USER
  unset OFBIZ_POSTGRES_TENANT_PASSWORD
  unset OFBIZ_DB_POOL_MIN
  unset OFBIZ_DB_POOL_MAX
  unset OFBIZ_SCHEMA_INIT
  unset OFBIZ_DISTRIBUTED_CACHE_CLEAR
  unset OFBIZ_JMS_PROVIDER_URL
  unset OFBIZ_JMS_INITIAL_CONTEXT_FACTORY
  unset OFBIZ_JMS_TOPIC_CONNECTION_FACTORY
  unset OFBIZ_JMS_TOPIC
  unset OFBIZ_JMS_USERNAME
  unset OFBIZ_JMS_PASSWORD
  unset OFBIZ_JVM_ROUTE
  unset OFBIZ_SSL_ACCELERATOR_PORT
  unset OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS
  unset OFBIZ_COOKIE_DOMAIN
  unset OFBIZ_CONTENT_STORE_PROVIDER
  unset OFBIZ_S3_BUCKET
  unset OFBIZ_S3_REGION
  unset OFBIZ_S3_ENDPOINT
  unset OFBIZ_S3_ACCESS_KEY_ID
  unset OFBIZ_S3_SECRET_ACCESS_KEY
  unset OFBIZ_S3_PATH_STYLE
  unset OFBIZ_S3_ENCRYPTION
  unset OFBIZ_S3_KMS_KEY_ID
  # Not secrets, but not the JVM's business either: these are read by this script alone, and two of
  # them - OFBIZ_CONTAINER_ROOT and adminCredentialSupplied - are internal to it and its tests.
  unset OFBIZ_POSTGRES_HOST
  unset OFBIZ_DISABLE_COMPONENTS
  unset OFBIZ_CONTAINER_ROOT
  unset adminCredentialSupplied

  exec "$@"
}

# Only run when this script is EXECUTED. When it is SOURCED the functions above are defined and nothing
# is run, so an individual step can be invoked on its own.
#
# Tracing is disabled for the INVOCATION ITSELF. bash prints the expanded call before the function body
# runs, so "_main $@" traced the container's whole command line - and a secret an operator passed as
# "-Dsecurity.token.key=..." was therefore printed into "docker logs" before refuse_secret_command_line
# could refuse it. _main turns tracing back on as soon as that inspection is done.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  { set +x; } 2>/dev/null
  _main "$@"
fi

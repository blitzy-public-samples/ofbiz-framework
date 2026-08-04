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
# a literal so that the functions below resolve every path against one root that can be overridden when
# they are sourced instead of executed. In an image it is always /ofbiz.
OFBIZ_CONTAINER_ROOT="${OFBIZ_CONTAINER_ROOT:-/ofbiz}"

CONTAINER_STATE_DIR="$OFBIZ_CONTAINER_ROOT/runtime/container_state"
CONTAINER_DATA_LOADED="$CONTAINER_STATE_DIR/data_loaded"
CONTAINER_ADMIN_LOADED="$CONTAINER_STATE_DIR/admin_loaded"
CONTAINER_CONFIG_APPLIED="$CONTAINER_STATE_DIR/config_applied"
CONTAINER_DB_CONFIG_APPLIED="$CONTAINER_STATE_DIR/db_config_applied"
# The overrides this script has rendered into the config directory. Only a file listed here is ever
# removed again, so an override an operator mounted themselves is never touched.
CONTAINER_MANAGED_OVERRIDES="$CONTAINER_STATE_DIR/managed_overrides"

# The files this script reads from and renders to. The shipped copies under /ofbiz/framework and
# /ofbiz/applications are the sources; the rendered copies under /ofbiz/config are what the JVM reads.
SECURITY_PROPERTIES_SOURCE="framework/security/config/security.properties"
CONTENT_PROPERTIES_SOURCE="applications/content/config/content.properties"
URL_PROPERTIES_SOURCE="framework/webapp/config/url.properties"
START_PROPERTIES_SOURCE="framework/start/src/main/resources/org/apache/ofbiz/base/start/start.properties"
START_PROPERTIES_OVERRIDE="config/org/apache/ofbiz/base/start/start.properties"
CATALINA_COMPONENT="framework/catalina/ofbiz-component.xml"
SERVICE_ENGINE_SOURCE="framework/service/config/serviceengine.xml"
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
  local unprintable
  unprintable=$(printf '%s' "$2" | LC_ALL=C tr --delete '[:print:]' | wc --bytes)
  if [ "$unprintable" -ne 0 ]; then
    config_fatal "$1 must contain printable characters only. A line break, a tab or another control character cannot be represented in the property and XML files this script renders."
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
# Escape an already destination-encoded value for use as the REPLACEMENT text of a sed s|...|...| or
# s@...@...@ program: a backslash and an ampersand are special there, and so are both delimiters this
# script uses. Always applied LAST, after properties_value or xml_attribute_value.
# $1 - the value. Writes the escaped value to stdout.
sed_replacement() {
  printf '%s' "$1" | LC_ALL=C sed \
    --expression='s@\\@\\\\@g' \
    --expression='s@&@\\\&@g' \
    --expression='s@|@\\|@g'
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
# The digest of the configuration this container would render now, for a marker file.
# A marker records WHAT was applied rather than merely THAT something was, so a persisted runtime volume
# can be reused after an environment change - or after an interrupted schema initialisation - without
# silently carrying the previous configuration forward. A marker written by an older image, or baked into
# the demo image, holds no digest and therefore matches nothing, so the configuration is applied.
# Secrets take part in the digest so that rotating one re-applies the configuration; what reaches the
# marker file is the digest alone, so the plaintext settings are not persisted there.
# $1 - "config", or the startup-DDL mode that was rendered for the database marker.
configuration_digest() {
  { set +x; } 2>/dev/null
  local payload
  case "$1" in
  config)
    payload="config/1|$OFBIZ_PROFILE|$OFBIZ_HOST|$OFBIZ_CONTENT_URL_PREFIX|$OFBIZ_ENABLE_AJP_PORT|$OFBIZ_AJP_BIND_ADDRESS|$OFBIZ_JVM_ROUTE|$OFBIZ_SSL_ACCELERATOR_PORT|$OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS|$OFBIZ_DISABLE_COMPONENTS|$OFBIZ_CONTENT_STORE_PROVIDER|$OFBIZ_S3_BUCKET|$OFBIZ_S3_REGION|$OFBIZ_S3_ENDPOINT|$OFBIZ_S3_PATH_STYLE|$OFBIZ_S3_ACCESS_KEY_ID|$OFBIZ_S3_SECRET_ACCESS_KEY|$OFBIZ_ADMIN_KEY|$OFBIZ_LOGIN_SECRET_KEY|$OFBIZ_JWT_TOKEN_KEY"
    ;;
  data | admin)
    # Only the IDENTITY of the database decides whether it has already been populated. A rotated
    # password, a resized pool, a changed TLS mode or a flipped cache-clear flag leave the rows exactly
    # where they are, so they must not invalidate these markers and re-run a data load against a
    # database that is already loaded. Pointing the container at a different host, port, database or
    # user does invalidate them, because that is a different database.
    payload="db-identity/1|$1|$OFBIZ_POSTGRES_HOST|$OFBIZ_POSTGRES_PORT|$OFBIZ_POSTGRES_OFBIZ_DB|$OFBIZ_POSTGRES_OFBIZ_USER|$OFBIZ_POSTGRES_OLAP_DB|$OFBIZ_POSTGRES_OLAP_USER|$OFBIZ_POSTGRES_TENANT_DB|$OFBIZ_POSTGRES_TENANT_USER"
    ;;
  *)
    payload="db/1|$1|$OFBIZ_POSTGRES_HOST|$OFBIZ_POSTGRES_PORT|$OFBIZ_POSTGRES_SSLMODE|$OFBIZ_POSTGRES_SSLROOTCERT|$OFBIZ_DB_POOL_MIN|$OFBIZ_DB_POOL_MAX|$OFBIZ_DISTRIBUTED_CACHE_CLEAR|$OFBIZ_POSTGRES_OFBIZ_DB|$OFBIZ_POSTGRES_OFBIZ_USER|$OFBIZ_POSTGRES_OFBIZ_PASSWORD|$OFBIZ_POSTGRES_OLAP_DB|$OFBIZ_POSTGRES_OLAP_USER|$OFBIZ_POSTGRES_OLAP_PASSWORD|$OFBIZ_POSTGRES_TENANT_DB|$OFBIZ_POSTGRES_TENANT_USER|$OFBIZ_POSTGRES_TENANT_PASSWORD"
    ;;
  esac
  local digest
  digest=$(printf '%s' "$payload" | sha256sum | cut --delimiter=' ' --fields=1)
  set -x
  printf '%s' "$digest"
}

###############################################################################
marker_matches() {
  [ -f "$1" ] && [ "$(cat "$1")" = "$2" ]
}

###############################################################################
mark_applied() {
  printf '%s' "$2" >"$1"
}

###############################################################################
# Report whether a data marker already covers the database this container is configured for.
#
# A marker this script wrote holds a digest of the database coordinates. A marker with NO digest was baked
# into the demo image, which loaded that data into the EMBEDDED database it also ships, so it counts only
# while no external database is configured. Pointing the demo image at a managed PostgreSQL server must
# not be able to skip loading data into that server.
# $1 - the marker path, $2 - the digest it must hold
data_marker_covers() {
  if [ ! -f "$1" ]; then
    return 1
  fi
  local held
  held=$(cat "$1")
  if [ "$held" = "$2" ]; then
    return 0
  fi
  if [ -z "$held" ] && [ -z "$OFBIZ_POSTGRES_HOST" ]; then
    return 0
  fi
  return 1
}

###############################################################################
own_override() {
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
  0*[!0] | 0) config_fatal "$1=$2 must be a positive integer" ;;
  esac
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

  OFBIZ_ADMIN_USER=${OFBIZ_ADMIN_USER:-admin}

  OFBIZ_HOST=${OFBIZ_HOST:-}
  OFBIZ_CONTENT_URL_PREFIX=${OFBIZ_CONTENT_URL_PREFIX:-}
  OFBIZ_ENABLE_AJP_PORT=${OFBIZ_ENABLE_AJP_PORT:-}
  require_single_line OFBIZ_HOST "$OFBIZ_HOST"
  require_single_line OFBIZ_CONTENT_URL_PREFIX "$OFBIZ_CONTENT_URL_PREFIX"

  OFBIZ_POSTGRES_PORT=${OFBIZ_POSTGRES_PORT:-5432}
  require_positive_integer OFBIZ_POSTGRES_PORT "$OFBIZ_POSTGRES_PORT"

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
  # Every one of these reaches the rendered XML through a sed substitution, so a value carrying a line
  # break or another control character has to be refused here rather than corrupt the file. The check
  # never echoes the value, so it is safe to run on the passwords with tracing off.
  require_single_line OFBIZ_POSTGRES_HOST "${OFBIZ_POSTGRES_HOST:-}"
  require_single_line OFBIZ_POSTGRES_OFBIZ_DB "$OFBIZ_POSTGRES_OFBIZ_DB"
  require_single_line OFBIZ_POSTGRES_OFBIZ_USER "$OFBIZ_POSTGRES_OFBIZ_USER"
  require_single_line OFBIZ_POSTGRES_OFBIZ_PASSWORD "$OFBIZ_POSTGRES_OFBIZ_PASSWORD"
  require_single_line OFBIZ_POSTGRES_OLAP_DB "$OFBIZ_POSTGRES_OLAP_DB"
  require_single_line OFBIZ_POSTGRES_OLAP_USER "$OFBIZ_POSTGRES_OLAP_USER"
  require_single_line OFBIZ_POSTGRES_OLAP_PASSWORD "$OFBIZ_POSTGRES_OLAP_PASSWORD"
  require_single_line OFBIZ_POSTGRES_TENANT_DB "$OFBIZ_POSTGRES_TENANT_DB"
  require_single_line OFBIZ_POSTGRES_TENANT_USER "$OFBIZ_POSTGRES_TENANT_USER"
  require_single_line OFBIZ_POSTGRES_TENANT_PASSWORD "$OFBIZ_POSTGRES_TENANT_PASSWORD"
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
  if [ "$OFBIZ_DISTRIBUTED_CACHE_CLEAR" = "true" ] && ! jms_transport_configured; then
    config_fatal "OFBIZ_DISTRIBUTED_CACHE_CLEAR=true requires a message transport, and none is configured. The distributedClear* services are declared engine=\"jms\" location=\"serviceMessenger\", and the jms-service of that name is shipped commented out in $SERVICE_ENGINE_SOURCE, so with the flag on and no transport every cache invalidation would be undeliverable and would mark the caller's transaction rollback-only. Mount a serviceengine.xml into the config directory with that jms-service uncommented and configured for your broker, put the broker's JMS client library in lib-extra, and start again. See DOCKER.adoc."
  fi

  OFBIZ_JVM_ROUTE=${OFBIZ_JVM_ROUTE-jvm1}
  require_single_line OFBIZ_JVM_ROUTE "$OFBIZ_JVM_ROUTE"

  OFBIZ_SSL_ACCELERATOR_PORT=${OFBIZ_SSL_ACCELERATOR_PORT:-}
  if [ -n "$OFBIZ_SSL_ACCELERATOR_PORT" ]; then
    require_positive_integer OFBIZ_SSL_ACCELERATOR_PORT "$OFBIZ_SSL_ACCELERATOR_PORT"
    if [ "$OFBIZ_SSL_ACCELERATOR_PORT" -gt 65535 ]; then
      config_fatal "OFBIZ_SSL_ACCELERATOR_PORT=$OFBIZ_SSL_ACCELERATOR_PORT is not a TCP port: it must be between 1 and 65535."
    fi
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

  OFBIZ_CONTENT_STORE_PROVIDER=${OFBIZ_CONTENT_STORE_PROVIDER:-database}
  require_enum OFBIZ_CONTENT_STORE_PROVIDER "$OFBIZ_CONTENT_STORE_PROVIDER" database filesystem s3

  OFBIZ_S3_BUCKET=${OFBIZ_S3_BUCKET:-}
  OFBIZ_S3_REGION=${OFBIZ_S3_REGION:-}
  OFBIZ_S3_ENDPOINT=${OFBIZ_S3_ENDPOINT:-}
  OFBIZ_S3_PATH_STYLE=${OFBIZ_S3_PATH_STYLE:-false}
  require_enum OFBIZ_S3_PATH_STYLE "$OFBIZ_S3_PATH_STYLE" true false
  require_single_line OFBIZ_S3_BUCKET "$OFBIZ_S3_BUCKET"
  require_single_line OFBIZ_S3_REGION "$OFBIZ_S3_REGION"
  require_single_line OFBIZ_S3_ENDPOINT "$OFBIZ_S3_ENDPOINT"
  if [ "$OFBIZ_CONTENT_STORE_PROVIDER" = "s3" ]; then
    if [ -z "$OFBIZ_S3_BUCKET" ] || [ -z "$OFBIZ_S3_REGION" ]; then
      config_fatal "OFBIZ_CONTENT_STORE_PROVIDER=s3 requires OFBIZ_S3_BUCKET and OFBIZ_S3_REGION."
    fi
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

  OFBIZ_LOGIN_SECRET_KEY=${OFBIZ_LOGIN_SECRET_KEY:-}
  OFBIZ_JWT_TOKEN_KEY=${OFBIZ_JWT_TOKEN_KEY:-}
  require_single_line OFBIZ_LOGIN_SECRET_KEY "$OFBIZ_LOGIN_SECRET_KEY"
  require_single_line OFBIZ_JWT_TOKEN_KEY "$OFBIZ_JWT_TOKEN_KEY"
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
  if { [ -n "$OFBIZ_S3_ACCESS_KEY_ID" ] && [ -z "$OFBIZ_S3_SECRET_ACCESS_KEY" ]; } \
    || { [ -z "$OFBIZ_S3_ACCESS_KEY_ID" ] && [ -n "$OFBIZ_S3_SECRET_ACCESS_KEY" ]; }; then
    config_fatal "OFBIZ_S3_ACCESS_KEY_ID and OFBIZ_S3_SECRET_ACCESS_KEY are supplied together, or neither is supplied and the AWS SDK's default credential chain is used."
  fi

  set -x
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
load_data() {
  local dataDigest adminDigest
  dataDigest=$(configuration_digest data)
  adminDigest=$(configuration_digest admin)
  if ! data_marker_covers "$CONTAINER_DATA_LOADED" "$dataDigest"; then
    run_init_hooks before-data-load /docker-entrypoint-hooks/before-data-load.d/*

    case "$OFBIZ_DATA_LOAD" in
    none) ;;

    seed)
      "$OFBIZ_CONTAINER_ROOT"/bin/ofbiz --load-data readers=seed,seed-initial
      ;;

    demo)
      "$OFBIZ_CONTAINER_ROOT"/bin/ofbiz --load-data
      mark_applied "$CONTAINER_ADMIN_LOADED" "$adminDigest"
      ;;
    esac

    if [ -z "$(find /docker-entrypoint-hooks/additional-data.d/ -prune -empty)" ]; then
      "$OFBIZ_CONTAINER_ROOT"/bin/ofbiz --load-data dir=/docker-entrypoint-hooks/additional-data.d
    fi

    mark_applied "$CONTAINER_DATA_LOADED" "$dataDigest"

    run_init_hooks after-data-load /docker-entrypoint-hooks/after-data-load.d/*
  fi
}

###############################################################################
load_admin_user() {
  local adminDigest
  adminDigest=$(configuration_digest admin)
  if ! data_marker_covers "$CONTAINER_ADMIN_LOADED" "$adminDigest"; then
    { set +x; } 2>/dev/null
    TMPFILE=$(mktemp)

    SALT=$(tr --delete --complement A-Za-z0-9 </dev/urandom | head --bytes=16)
    SALT_AND_PASSWORD="${SALT}${OFBIZ_ADMIN_PASSWORD}"

    SHA1SUM_ASCII_HEX=$(printf "$SALT_AND_PASSWORD" | sha1sum | cut --delimiter=' ' --fields=1 --zero-terminated | tr --delete '\000')

    SHA1SUM_ESCAPED_STRING=$(printf "$SHA1SUM_ASCII_HEX" | sed -e 's/\(..\)\.\?/\\x\1/g')
    SHA1SUM_BASE64=$(printf "$SHA1SUM_ESCAPED_STRING" | basenc --base64url --wrap=0 | tr --delete '=')

    ENCODED_PASSWORD_HASH="\$SHA\$${SALT}\$${SHA1SUM_BASE64}"

    sed "s/@userLoginId@/$(xml_substitution "$OFBIZ_ADMIN_USER")/g; s/currentPassword=\".*\"/currentPassword=\"$(sed_replacement "$ENCODED_PASSWORD_HASH")\"/g;" framework/resources/templates/AdminUserLoginData.xml >"$TMPFILE"
    set -x

    "$OFBIZ_CONTAINER_ROOT"/bin/ofbiz --load-data "file=$TMPFILE"

    rm "$TMPFILE"

    mark_applied "$CONTAINER_ADMIN_LOADED" "$adminDigest"
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
# so it is also the file that authenticates the shutdown request the container's SIGTERM handler sends.
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
    && [ "$OFBIZ_S3_PATH_STYLE" = "false" ]; then
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
    "$CONTENT_PROPERTIES_SOURCE" >config/content.properties
  chmod 600 config/content.properties
  set -x
  own_override "config/content.properties"
  echo "Rendered config/content.properties with the [$OFBIZ_CONTENT_STORE_PROVIDER] content store provider"
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
# The marker records the DIGEST of the configuration that was applied, not merely that something was, so
# a persisted runtime volume - or a marker baked into the demo image - cannot suppress a configuration
# the environment now asks for.
apply_configuration() {
  local digest
  digest=$(configuration_digest config)
  if marker_matches "$CONTAINER_CONFIG_APPLIED" "$digest"; then
    return 0
  fi

  run_init_hooks before-config-applied /docker-entrypoint-hooks/before-config-applied.d/*

  apply_security_properties
  apply_admin_key
  apply_content_store
  apply_load_balancer_settings

  if [ -n "$OFBIZ_CONTENT_URL_PREFIX" ]; then
    sed \
      --expression="s|^content.url.prefix.secure=.*|content.url.prefix.secure=$(property_substitution "$OFBIZ_CONTENT_URL_PREFIX")|" \
      --expression="s|^content.url.prefix.standard=.*|content.url.prefix.standard=$(property_substitution "$OFBIZ_CONTENT_URL_PREFIX")|" \
      "$URL_PROPERTIES_SOURCE" >config/url.properties
    own_override "config/url.properties"
  else
    disown_override "config/url.properties"
  fi

  if [ -n "$OFBIZ_DISABLE_COMPONENTS" ]; then
    disable_components "$OFBIZ_DISABLE_COMPONENTS"
  fi

  mark_applied "$CONTAINER_CONFIG_APPLIED" "$digest"
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
  mark_applied "$CONTAINER_DB_CONFIG_APPLIED" "$(configuration_digest "$checkOnStart $addMissingOnStart")"
  echo "Rendered $ENTITY_ENGINE_OVERRIDE with startup DDL check-on-start=$checkOnStart add-missing-on-start=$addMissingOnStart"
}

###############################################################################
# Set up the connection to the OFBiz database.
#
# Nothing is rendered when OFBIZ_POSTGRES_HOST is empty, so an unconfigured container boots on the
# embedded H2 database exactly as it always has - and an entity-engine override this script wrote on an
# earlier start is removed, so withdrawing the database environment really does go back to H2.
#
# The marker holds the digest of the database configuration INCLUDING the startup-DDL mode that was
# rendered, so it can only be matched by a start that wants exactly what is already on disk. A marker
# left by an interrupted schema initialisation records DDL enabled and therefore never matches a serving
# start, which re-renders with DDL disabled before serving anything.
configure_database() {
  local digest
  digest=$(configuration_digest "false false")
  if marker_matches "$CONTAINER_DB_CONFIG_APPLIED" "$digest"; then
    return 0
  fi

  if [ -n "$OFBIZ_POSTGRES_HOST" ]; then
    render_entity_engine false false
  else
    disown_override "$ENTITY_ENGINE_OVERRIDE"
    mark_applied "$CONTAINER_DB_CONFIG_APPLIED" "$digest"
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
  # Whatever ends this process next - the initialisation failing, set -e, or a signal - the run mode is
  # put back first. Without this an interrupted initialisation would leave config/entityengine.xml with
  # startup DDL ENABLED on a persisted volume, and a serving start would then issue DDL and need DDL
  # privileges. The marker render_entity_engine writes is the second line of defence: it records the mode,
  # so even a kill that outruns this trap leaves a marker no serving start can match.
  trap 'render_entity_engine false false' EXIT
  render_entity_engine true true
  "$OFBIZ_CONTAINER_ROOT"/bin/ofbiz --load-data readers=none
  render_entity_engine false false
  trap - EXIT
  echo "Schema initialisation is complete. Start the serving instances now; they issue no DDL and need no DDL privilege."
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
  ofbiz_setup_env

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
  unset OFBIZ_JVM_ROUTE
  unset OFBIZ_SSL_ACCELERATOR_PORT
  unset OFBIZ_ENABLE_CROSS_SUBDOMAIN_SESSIONS
  unset OFBIZ_CONTENT_STORE_PROVIDER
  unset OFBIZ_S3_BUCKET
  unset OFBIZ_S3_REGION
  unset OFBIZ_S3_ENDPOINT
  unset OFBIZ_S3_ACCESS_KEY_ID
  unset OFBIZ_S3_SECRET_ACCESS_KEY
  unset OFBIZ_S3_PATH_STYLE

  exec "$@"
}

# Only run when this script is EXECUTED. When it is SOURCED the functions above are defined and nothing
# is run, so an individual step can be invoked on its own.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  _main "$@"
fi

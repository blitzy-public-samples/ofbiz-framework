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
# Asks a running OFBiz to shut down, authenticating with the admin key.
#
# THE ADMIN KEY IS A SECRET AND IS NEVER PRINTED. It used to be safe to echo, because the image shipped
# a published literal key; it no longer is, because the key is now supplied from OFBIZ_ADMIN_KEY at
# container start. Anything this script writes to stdout or stderr is what "docker logs" and every log
# collector behind it keeps, so only the FILE the key came from is reported, never the value (CWE-532).

set -e

# Never trace this script: an "-x" trace would print the key it reads.
{ set +x; } 2>/dev/null

# The container's OFBiz directory. A variable rather than a literal for the same reason as in
# docker-entrypoint.sh; in an image it is always /ofbiz.
OFBIZ_CONTAINER_ROOT="${OFBIZ_CONTAINER_ROOT:-/ofbiz}"

# Where the admin properties are looked for, in PRECEDENCE ORDER, which is the launcher's own class path
# order: config/ precedes the packaged copy, so the rendered override wins here exactly as it does in the
# JVM. docker-entrypoint.sh renders the key into the first of these; the second is the copy shipped in the
# image, which carries the admin port and, in an image built without the entry point, no key at all.
ADMIN_PROPERTIES_FILES=(
  "$OFBIZ_CONTAINER_ROOT/config/org/apache/ofbiz/base/start/start.properties"
  "$OFBIZ_CONTAINER_ROOT/framework/start/src/main/resources/org/apache/ofbiz/base/start/start.properties"
)

###############################################################################
# Print the value of one property, or nothing when the file does not carry it.
# Assumes the single-line, no-spaces-around-'=', no-trailing-comment form start.properties uses.
# Args:
# 1: path of the properties file
# 2: property name
property_value() {
  sed --quiet "s|^$2=||p" "$1" | head --lines=1
}

echo "Getting admin port and key..."

OFBIZ_ADMIN_PORT=""
OFBIZ_ADMIN_KEY=""
adminKeySource=""
for propertiesFile in "${ADMIN_PROPERTIES_FILES[@]}"; do
  if [ ! -r "$propertiesFile" ]; then
    continue
  fi
  if [ -z "$OFBIZ_ADMIN_PORT" ]; then
    OFBIZ_ADMIN_PORT=$(property_value "$propertiesFile" "ofbiz.admin.port")
  fi
  if [ -z "$OFBIZ_ADMIN_KEY" ]; then
    candidateKey=$(property_value "$propertiesFile" "ofbiz.admin.key")
    # "NA" is the value Config.java substitutes when the property is absent, so it means "no key" here
    # too and must not be sent as one.
    if [ -n "$candidateKey" ] && [ "$candidateKey" != "NA" ]; then
      OFBIZ_ADMIN_KEY=$candidateKey
      adminKeySource=$propertiesFile
    fi
  fi
done

if [ -z "$OFBIZ_ADMIN_PORT" ] || [ "$OFBIZ_ADMIN_PORT" = "0" ]; then
  echo "ERROR: no ofbiz.admin.port is configured, so the admin listener is disabled and cannot be asked to shut down. Looked in: ${ADMIN_PROPERTIES_FILES[*]}" >&2
  exit 1
fi
if [ -z "$OFBIZ_ADMIN_KEY" ]; then
  echo "ERROR: no ofbiz.admin.key is configured, so a shutdown request cannot be authenticated. Supply OFBIZ_ADMIN_KEY and let docker-entrypoint.sh render it. Looked in: ${ADMIN_PROPERTIES_FILES[*]}" >&2
  exit 1
fi

echo "Admin port: $OFBIZ_ADMIN_PORT"
echo "Admin key: read from $adminKeySource"

echo "Sending shutdown signal..."
# The request is written to curl's stdin rather than passed as an argument, so the key never appears in
# this container's process table.
printf '%s:SHUTDOWN\n' "$OFBIZ_ADMIN_KEY" | curl --silent --show-error "telnet://localhost:$OFBIZ_ADMIN_PORT"
echo "Done"

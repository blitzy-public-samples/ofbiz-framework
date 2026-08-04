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

set -e

# WHERE THE ADMIN KEY LIVES, AND WHY THIS SCRIPT READS TWO FILES.
#
# ofbiz.admin.key is a SECRET. The shipped start.properties therefore declares it COMMENTED OUT - no
# credential resides in the source tree or in an image layer - and docker-entrypoint.sh injects the value
# at container start by writing the package qualified override
# config/org/apache/ofbiz/base/start/start.properties, which the launcher's class path resolves ahead of
# the packaged copy. Reading only the shipped file, which is what this script used to do, therefore found
# no key at all and sent ':SHUTDOWN', which AdminServerContainer refuses: the container could not be
# stopped through its own helper.
#
# So the override is read FIRST and the shipped file is the fallback, which is exactly the precedence the
# JVM itself applies. The port is resolved the same way, because the override is a whole rendered copy of
# the file and carries its own ofbiz.admin.port line.
#
# THE KEY IS NEVER PRINTED. It used to be echoed to stdout, which put the shared secret into the
# container log, into `docker logs`, and into any log aggregator collecting it - a secret that grants
# shutdown of the instance (CWE-532). Only whether it was found is reported.
ADMIN_KEY_OVERRIDE="/ofbiz/config/org/apache/ofbiz/base/start/start.properties"
START_PROPERTIES="/ofbiz/framework/start/src/main/resources/org/apache/ofbiz/base/start/start.properties"

# Read a property value from the first file that declares it with a value.
# Assumes all properties are single line, with no spaces around the '=' sign and no trailing comment.
# $1 - property name, $2.. - files to read, in precedence order
getPropertyValue() {
    propertyName="$1"
    shift
    for propertiesFile in "$@"; do
        if [ -r "$propertiesFile" ]; then
            propertyValue=$(sed --quiet "s|^${propertyName}=||p" "$propertiesFile" | head --lines=1)
            if [ -n "$propertyValue" ]; then
                printf '%s' "$propertyValue"
                return 0
            fi
        fi
    done
    return 0
}

echo "Getting admin port and key..."

OFBIZ_ADMIN_PORT=$(getPropertyValue "ofbiz.admin.port" "$ADMIN_KEY_OVERRIDE" "$START_PROPERTIES")
if [ -z "$OFBIZ_ADMIN_PORT" ]; then
    echo "ERROR: ofbiz.admin.port is declared in neither $ADMIN_KEY_OVERRIDE nor $START_PROPERTIES." >&2
    exit 1
fi
echo "Admin port: $OFBIZ_ADMIN_PORT"

OFBIZ_ADMIN_KEY=$(getPropertyValue "ofbiz.admin.key" "$ADMIN_KEY_OVERRIDE" "$START_PROPERTIES")
if [ -z "$OFBIZ_ADMIN_KEY" ]; then
    echo "ERROR: ofbiz.admin.key is declared in neither $ADMIN_KEY_OVERRIDE nor $START_PROPERTIES, so no" >&2
    echo "       shutdown request can be authenticated. In a container the key is written to the override by" >&2
    echo "       docker-entrypoint.sh from OFBIZ_ADMIN_KEY; supply that variable, or stop the container with" >&2
    echo "       'docker stop', which the entry point forwards to the JVM as SIGTERM." >&2
    exit 1
fi
echo "Admin key: found (not printed)"

echo "Sending shutdown signal..."
printf '%s\n' "$OFBIZ_ADMIN_KEY:SHUTDOWN" | curl "telnet://localhost:$OFBIZ_ADMIN_PORT"
echo "Done"

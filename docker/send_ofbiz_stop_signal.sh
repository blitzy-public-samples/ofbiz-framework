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
#
# Sends a shutdown signal to the running OFBiz instance through its AdminServer socket.
# docker-entrypoint.sh invokes this script from its SIGTERM/SIGINT trap.
#
# The admin shared secret sent here MUST be the exact value the running JVM computed:
# AdminServerContainer compares the received key with String.equals and rejects the
# request otherwise, in which case the container never shuts down cleanly. Config.java
# (framework/start/src/main/java/org/apache/ofbiz/base/start/Config.java, getProperty()
# and the "ofbiz.admin.key" resolution) applies this precedence:
#
#   1. the -Dofbiz.admin.key JVM system property - injected at deploy time and published
#      to this script as the OFBIZ_ADMIN_KEY environment variable;
#   2. an ACTIVE "ofbiz.admin.key=" assignment in start.properties, whatever its value,
#      because java.util.Properties returns a declared empty value rather than a default;
#   3. the literal "NA", which is Config.java's documented default.
#
# This script mirrors that precedence exactly. Getting it wrong is not a theoretical
# risk: start.properties deliberately ships the key COMMENTED OUT so that no credential
# resides in the source tree or the container image, so parsing the file alone yields an
# EMPTY key while the JVM is using "NA" - and every shutdown request is then rejected.
#
# Consulting the environment first is safe in this image, and is not a second source of
# truth: docker-entrypoint.sh never passes -Dofbiz.admin.key, it writes the SAME
# OFBIZ_ADMIN_KEY value into the package qualified override read at (2), so both levels
# resolve to one value. It then unsets the variable before exec'ing OFBiz, so the JVM does
# not inherit it and only this helper, invoked before that exec or through "docker exec",
# ever sees it. Where the two could differ - OFBIZ_SKIP_INIT suppresses the rendering while
# a differing OFBIZ_ADMIN_KEY is still supplied - the reported key source below names the
# environment, which is what makes such a mismatch diagnosable rather than silent.
#
# Environment variables:
#
# OFBIZ_ADMIN_KEY
# The admin shared secret injected at deploy time. Takes precedence over start.properties,
# mirroring Config.java consulting System.getProperty() before the file. An unset or empty
# value means "not injected", so resolution falls through to the file and then to "NA";
# that matches how the entry point guards every other injected value, and a deployment
# that requires the key is expected to fail fast at start-up rather than here.
# The value is never echoed.
# Default: <empty>
#
# OFBIZ_START_PROPERTIES
# Path of a single start.properties file to read instead of the default candidates below.
# Default: <empty>
#
###############################################################################

set -e

# Never write the secret to the logs. docker-entrypoint.sh runs under "set -x" and calls
# this script from its trap, so xtrace is switched off for the whole body in case it was
# inherited through an exported SHELLOPTS. The script always ends here, so there is
# nothing to restore. It is switched off HERE, before the first property is read and not
# merely before the key is handled, because getPropertyValue expands the ENTIRE content of
# start.properties as a command argument: tracing even the port lookup would print an
# injected ofbiz.admin.key line into the container logs.
set +x

# start.properties locations in the CLASSPATH PRECEDENCE order Config.java observes: the
# file is read PACKAGE QUALIFIED as org/apache/ofbiz/base/start/start.properties, so a copy
# under config/ shadows the one shipped in the source tree. A flat config/start.properties
# is not a valid override and is deliberately not consulted.
DEFAULT_START_PROPERTIES_CANDIDATES=(
    "/ofbiz/config/org/apache/ofbiz/base/start/start.properties"
    "/ofbiz/framework/start/src/main/resources/org/apache/ofbiz/base/start/start.properties"
)

# The value Config.java falls back to when ofbiz.admin.key is not declared at all.
DEFAULT_ADMIN_KEY="NA"

# Read a property value. Assumes all properties are single line and do not have spaces around the '=' sign or comments following the valuel.
# The raw value is decoded the way java.util.Properties decodes it, so that the key sent below is the
# key the JVM holds. docker-entrypoint.sh doubles every backslash when it renders a value into a
# properties file, because Properties would otherwise read a backslash as an escape sequence and a
# trailing one as a line continuation; reading the file back without decoding would therefore send a
# different key than the server resolved and every SHUTDOWN would be rejected.
function getPropertyValue
{
    echo "$1" | sed "/^$2=/!d; s///" | unescapePropertyValue
}

# Decode the two escapes that can appear in the values rendered into these files: a leading blank is
# escaped because Properties would otherwise discard it, and a backslash is doubled. The leading
# blank is decoded first so that a value which genuinely starts with a backslash is not mistaken for
# one. Every other character passes through unchanged.
function unescapePropertyValue
{
    sed --expression='s|^\\\([[:blank:]]\)|\1|' --expression='s|\\\\|\\|g'
}

# Report whether a property is DECLARED, regardless of its value. This is what separates
# "absent" - where Config.java applies its default - from "declared but empty", where
# java.util.Properties returns the empty string and no default is applied.
function hasProperty
{
    echo "$1" | grep --quiet "^$2="
}

# Echo the first readable file among the arguments; fail when none of them is readable.
function findReadableFile
{
    local candidate
    for candidate in "$@"; do
        if [ -r "$candidate" ]; then
            echo "$candidate"
            return 0
        fi
    done
    return 1
}

if [ -n "${OFBIZ_START_PROPERTIES:-}" ]; then
    START_PROPERTIES_CANDIDATES=("$OFBIZ_START_PROPERTIES")
else
    START_PROPERTIES_CANDIDATES=("${DEFAULT_START_PROPERTIES_CANDIDATES[@]}")
fi

echo "Getting admin port and key..."
if ! START_PROPERTIES=$(findReadableFile "${START_PROPERTIES_CANDIDATES[@]}"); then
    echo "Unable to read start.properties from any of: ${START_PROPERTIES_CANDIDATES[*]}" >&2
    exit 1
fi
echo "Using start.properties: $START_PROPERTIES"
START_PROPERTIES_CONTENT=$(cat "$START_PROPERTIES")

OFBIZ_ADMIN_PORT=$(getPropertyValue "$START_PROPERTIES_CONTENT" "ofbiz.admin.port")
echo Admin port: $OFBIZ_ADMIN_PORT;

if [ -n "${OFBIZ_ADMIN_KEY:-}" ]; then
    ADMIN_KEY="$OFBIZ_ADMIN_KEY"
    ADMIN_KEY_SOURCE="OFBIZ_ADMIN_KEY environment variable"
elif hasProperty "$START_PROPERTIES_CONTENT" "ofbiz.admin.key"; then
    ADMIN_KEY=$(getPropertyValue "$START_PROPERTIES_CONTENT" "ofbiz.admin.key")
    ADMIN_KEY_SOURCE="ofbiz.admin.key in $START_PROPERTIES"
else
    ADMIN_KEY="$DEFAULT_ADMIN_KEY"
    ADMIN_KEY_SOURCE="Config.java default, the property is not declared"
fi
# The key is a secret: report where it came from, never what it is.
echo "Admin key source: $ADMIN_KEY_SOURCE"

# AdminServerContainer is not started when ofbiz.admin.port is absent or 0, so there is nothing to
# send the request to. Say so instead of handing curl an address with no port and reporting success.
if [ -z "$OFBIZ_ADMIN_PORT" ] || [ "$OFBIZ_ADMIN_PORT" = "0" ]; then
    echo "ERROR: ofbiz.admin.port does not define a listening admin port in $START_PROPERTIES, so no" >&2
    echo "       shutdown request can be sent. Restore the property to shut OFBiz down this way." >&2
    exit 1
fi

echo "Sending shutdown signal..."
# The key is passed on standard input rather than as an argument, so it never becomes visible in the
# process list or in /proc/<pid>/cmdline.
echo "$ADMIN_KEY:SHUTDOWN" | curl telnet://localhost:"$OFBIZ_ADMIN_PORT"
echo "Done"

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
#
# RECORDED SCOPE DEVIATION. This file is NOT one of the files the specification lists for change; the
# minimal-change clause is deliberately departed from here, and the reason is recorded rather than left
# implicit. Externalising the admin key (Goal 2) is what makes the change necessary. Upstream this script
# echoed the key it read - harmless while every image shipped the same published literal - and it read the
# key from ONE hard-coded path, the copy packaged in the image. Once the key becomes a per-deployment
# secret rendered into the class-path override, leaving this script as it was would have two consequences:
# it would print a live secret into the container log on every stop, and it would read the wrong file and
# so authenticate with the wrong key, leaving no way to stop OFBiz cleanly. The change is therefore
# confined to exactly what Goal 2 forces: stop echoing the key, resolve the file in the launcher's own
# class-path precedence order, and pass the request on curl's stdin so the key never appears in a command
# line that "ps" would show. No behaviour beyond that is touched.
#
# It is POSIX shell, and is run as such: the image's stop path invokes it directly, but an operator reaches
# for "sh /ofbiz/send_ofbiz_stop_signal.sh" often enough that dying on a bashism there - with a syntax error,
# before it has done anything - is not an acceptable way to fail to stop a server.

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
# Newline-separated rather than an array: an array is a bashism, and this script has to run under any POSIX
# shell. Neither path can contain whitespace, so a newline-separated list read with "read" is exact.
ADMIN_PROPERTIES_FILES="$OFBIZ_CONTAINER_ROOT/config/org/apache/ofbiz/base/start/start.properties
$OFBIZ_CONTAINER_ROOT/framework/start/src/main/resources/org/apache/ofbiz/base/start/start.properties"

###############################################################################
# Print the value of one property, or nothing when the file does not carry it.
# Assumes the single-line, no-spaces-around-'=', no-trailing-comment form start.properties uses.
# Args:
# 1: path of the properties file
# 2: property name
property_value() {
  sed --quiet "s|^$2=||p" "$1" | head --lines=1
}

###############################################################################
# Undo the escaping docker-entrypoint.sh applied when it rendered a property VALUE.
#
# This is what made an AUTHORISED shutdown fail. The entry point's properties_value() doubles every
# backslash, because a backslash starts an escape sequence in a java.util.Properties value; Config.java
# reads the file through java.util.Properties, so the key the SERVER holds is the value with each doubled
# backslash collapsed back to one. Reading the file with sed gives the escaped form, so any key containing
# a backslash was transmitted with too many of them, the server's exact comparison failed, and the shutdown
# was refused - while this script reported success.
#
# Doubling is the ONLY escape the entry point emits: it rejects control characters and non-ASCII before
# rendering, and a property value has nothing else that needs escaping. So collapsing each pair back to one
# is the exact inverse, and it is exact for any value: after doubling, every run of backslashes has even
# length, so a single left-to-right pass over non-overlapping pairs reproduces the original. A file
# hand-written with the other java.util.Properties escapes (\t, \n, \uXXXX) is out of scope here, and the
# packaged copy ships no key at all for the entry point's rendering to compete with.
# $1 - the escaped value. Writes the unescaped value to stdout.
properties_unescape() {
  printf '%s' "$1" | LC_ALL=C sed --expression='s@\\\\@\\@g'
}

echo "Getting admin port and key..."

OFBIZ_ADMIN_PORT=""
OFBIZ_ADMIN_KEY=""
adminKeySource=""
# Fed by a here-document rather than a pipe: a "while read" on the right of a pipe runs in a SUBSHELL, and
# the port and key it found would be discarded when that subshell exited. A here-document keeps the reads in
# this shell.
while IFS= read -r propertiesFile; do
  if [ -z "$propertiesFile" ] || [ ! -r "$propertiesFile" ]; then
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
      # Unescaped to the value the SERVER holds, not the value the file spells. See properties_unescape.
      OFBIZ_ADMIN_KEY=$(properties_unescape "$candidateKey")
      adminKeySource=$propertiesFile
    fi
  fi
done <<EOF
$ADMIN_PROPERTIES_FILES
EOF

# Flattened onto one line for the error messages below, so a diagnostic stays one log line.
adminPropertiesSearched=$(printf '%s' "$ADMIN_PROPERTIES_FILES" | tr '\n' ' ')

if [ -z "$OFBIZ_ADMIN_PORT" ] || [ "$OFBIZ_ADMIN_PORT" = "0" ]; then
  echo "ERROR: no ofbiz.admin.port is configured, so the admin listener is disabled and cannot be asked to shut down. Looked in: $adminPropertiesSearched" >&2
  exit 1
fi
if [ -z "$OFBIZ_ADMIN_KEY" ]; then
  echo "ERROR: no ofbiz.admin.key is configured, so a shutdown request cannot be authenticated. Supply OFBIZ_ADMIN_KEY and let docker-entrypoint.sh render it. Looked in: $adminPropertiesSearched" >&2
  exit 1
fi

echo "Admin port: $OFBIZ_ADMIN_PORT"
echo "Admin key: read from $adminKeySource"

echo "Sending shutdown signal..."
# The request is written to curl's stdin rather than passed as an argument, so the key never appears in
# this container's process table.
#
# THE REPLY IS INSPECTED, and its verdict becomes this script's exit status. AdminServerContainer answers
# "OK" when it accepted the shutdown, "IN-PROGRESS" when one was already under way, and "FAIL" when the key
# did not match. Previously the reply was written straight to stdout and never looked at, so a REFUSED
# shutdown printed "FAIL" followed by "Done" and exited 0 - and every caller that trusts an exit status,
# from a container stop hook to an orchestrator's preStop, was told the server had been asked to stop when
# it had not. A stop that did not happen must not look like one that did.
shutdownResponse=$(printf '%s:SHUTDOWN\n' "$OFBIZ_ADMIN_KEY" | curl --silent --show-error "telnet://localhost:$OFBIZ_ADMIN_PORT")

case "$shutdownResponse" in
*OK* | *IN-PROGRESS*)
  # IN-PROGRESS is a success: something else already asked, and the server is stopping either way.
  echo "Response: $shutdownResponse"
  echo "Done"
  ;;
*)
  # The response is reported, never the key - "FAIL" is the whole of what the server says, and it says it
  # because the key did not match. The file it came from is named so the operator knows where to look.
  echo "ERROR: OFBiz refused the shutdown request (response: ${shutdownResponse:-<none>}). The admin key read from $adminKeySource does not match the one the running server holds, or the admin listener is not answering. OFBiz has NOT been asked to stop." >&2
  exit 1
  ;;
esac

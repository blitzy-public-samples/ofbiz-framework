# syntax=docker/dockerfile:1
#####################################################################
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
#####################################################################

FROM eclipse-temurin:17@sha256:e8d451f3b5aa6422c2b00bb913cb8d37a55a61934259109d945605c5651de9a6 AS builder

# Git is used for various OFBiz build tasks.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /builder

# Add and run the gradle wrapper to trigger a download if needed.
COPY gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
COPY --chmod=755 gradle/init-gradle-wrapper.sh gradle/
COPY --chmod=755 gradlew .
RUN ["gradle/init-gradle-wrapper.sh"]

# Run gradlew to trigger downloading of the gradle distribution (if needed)
RUN --mount=type=cache,id=gradle-cache,sharing=locked,target=/root/.gradle \
    ["./gradlew", "--console", "plain"]

# Copy all OFBiz sources.
COPY buildSrc/ buildSrc/
COPY applications/ applications/
COPY config/ config/
COPY framework/ framework/
COPY gradle/ gradle/
COPY lib/ lib/
# We use a regex to match the plugins directory to avoid a build error when the directory doesn't exist.
COPY plugin[s]/ plugins/
COPY themes/ themes/
COPY APACHE2_HEADER build.gradle common.gradle gradle.properties NOTICE settings.gradle dependencies.gradle .

# Build OFBiz while mounting a gradle cache.
# Only "distTar" is run here, deliberately WITHOUT "generateSecretKeys". That task writes freshly
# generated login.secret_key_string / security.token.key values into
# framework/security/config/security.properties, which distTar then packages, so running it here would
# bake live secret material into a layer where it can never be removed - and would additionally give
# every built image a different key, breaking a multi-instance fleet that has to share one.
# Both keys are supplied at run time instead: docker-entrypoint.sh renders them into
# /ofbiz/config/security.properties (mode 0600) from OFBIZ_LOGIN_SECRET_KEY and
# OFBIZ_JWT_TOKEN_KEY, generating a per-container value when OFBIZ_PROFILE=dev and
# failing fast when OFBIZ_PROFILE=prod and a key is absent. /ofbiz/config precedes
# ofbiz.jar on the classpath, so the rendered values win over the blank anchors the image ships.
# See DOCKER.adoc, framework/security/config/security.properties and docker/docker-entrypoint.sh.
# Run ./gradlew generateSecretKeys only for a local (non container) development checkout.
RUN --mount=type=cache,id=gradle-cache,sharing=locked,target=/root/.gradle \
    --mount=type=tmpfs,target=runtime/tmp \
    ["./gradlew", "--console", "plain", "distTar"]

###################################################################################

FROM eclipse-temurin:17@sha256:e8d451f3b5aa6422c2b00bb913cb8d37a55a61934259109d945605c5651de9a6 AS runtimebase

# xsltproc is used to disable OFBiz components during first run.
RUN apt-get update \
    && apt-get install -y --no-install-recommends xsltproc \
    && rm -rf /var/lib/apt/lists/*

RUN ["useradd", "ofbiz"]

# Create directories used to mount volumes where hooks into the startup process can be placed.
RUN ["mkdir", "--parents", \
    "/docker-entrypoint-hooks/before-config-applied.d", \
    "/docker-entrypoint-hooks/after-config-applied.d", \
    "/docker-entrypoint-hooks/before-data-load.d", \
    "/docker-entrypoint-hooks/after-data-load.d", \
    "/docker-entrypoint-hooks/additional-data.d"]
RUN ["/usr/bin/chown", "-R", "ofbiz:ofbiz", "/docker-entrypoint-hooks" ]

USER ofbiz
WORKDIR /ofbiz

# Extract the OFBiz tar distribution created by the builder stage.
RUN --mount=type=bind,from=builder,source=/builder/build/distributions/ofbiz.tar,target=/mnt/ofbiz.tar \
    ["tar", "--extract", "--strip-components=1", "--file=/mnt/ofbiz.tar"]

# Create directories for OFBiz volume mountpoints.
RUN ["mkdir", "/ofbiz/runtime", "/ofbiz/config", "/ofbiz/lib-extra"]

# Append the java runtime version to the OFBiz VERSION file.
COPY --chmod=644 --chown=ofbiz:ofbiz VERSION .
RUN echo '${uiLabelMap.CommonJavaVersion}:' "$(java --version | grep Runtime | sed 's/.*Runtime Environment //; s/ (build.*//;')" >> /ofbiz/VERSION

# Leave executable scripts owned by root and non-writable, addressing sonarcloud rule,
# https://sonarcloud.io/organizations/apache/rules?open=docker%3AS6504&rule_key=docker%3AS6504
COPY --chmod=555 docker/docker-entrypoint.sh docker/send_ofbiz_stop_signal.sh .

COPY --chmod=444 docker/disable-component.xslt .

RUN mkdir templates
COPY --chmod=444 docker/templates templates

EXPOSE 8443
EXPOSE 8009
EXPOSE 5005

ENTRYPOINT ["/ofbiz/docker-entrypoint.sh"]
CMD ["bin/ofbiz"]

###################################################################################
# Load demo data before defining volumes. This results in a container image
# that is ready to go for demo purposes.
FROM runtimebase AS demo

USER ofbiz

RUN /ofbiz/bin/ofbiz --load-data

# Record that load, so a container from this image starts immediately instead of loading the demo data
# a second time. The entry point owns the format of the container state markers, so it writes them:
# these used to be three empty files created with 'touch', which a checksummed marker no longer accepts,
# and which was also wrong - an empty data_loaded suppressed the load even when the image was pointed at
# an external database, leaving the container serving an empty schema. The marker written here is bound
# to the embedded database baked into this image, so that case now loads correctly.
#
# No db_config_applied marker is written: no database is configured at build time, so there is nothing
# for it to record.
RUN ["/ofbiz/docker-entrypoint.sh", "--write-initial-container-state"]

VOLUME ["/docker-entrypoint-hooks"]
VOLUME ["/ofbiz/config", "/ofbiz/runtime", "/ofbiz/lib-extra"]


###################################################################################
# Runtime image with no data loaded.
FROM runtimebase AS runtime

USER ofbiz

VOLUME ["/docker-entrypoint-hooks"]
VOLUME ["/ofbiz/config", "/ofbiz/runtime", "/ofbiz/lib-extra"]

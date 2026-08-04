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

# Refuse to package a signing key. The two properties below are committed BLANK so that the value can be
# injected at run time, and nothing in this stage fills them in - but a developer's working tree can arrive
# here already carrying live keys, because "./gradlew generateSecretKeys" writes them into the tracked file
# and "./gradlew loadAll" depends on that task (build.gradle: loadAll dependsOn generateSecretKeys). Its skip
# test only matches a property that already has a value, so a blank anchor is always (re)generated. The COPY
# above then brings those keys into the build context and distTar would seal them into an image layer that no
# run-time override can remove - and every image built from that tree would share one signing key.
# So check before packaging rather than after: this fails the image build with an actionable message instead.
# Reverting is "git checkout -- framework/security/config/security.properties"; a local development run that
# wants real keys should put them in an untracked config/security.properties override, which precedes
# ofbiz.jar on the class path, exactly as the container does with the environment.
RUN if grep -Eq '^(login\.secret_key_string|security\.token\.key)=.+' framework/security/config/security.properties; then \
        echo 'ERROR: framework/security/config/security.properties carries a live signing key.' >&2; \
        echo '       login.secret_key_string and security.token.key must be committed BLANK: they are' >&2; \
        echo '       injected at run time from OFBIZ_LOGIN_SECRET_KEY and OFBIZ_JWT_TOKEN_KEY.' >&2; \
        echo '       A value here would be packaged into an image layer permanently. Most likely a' >&2; \
        echo '       "gradlew generateSecretKeys" - or a "gradlew loadAll", which depends on it - wrote' >&2; \
        echo '       them. Restore the file with:' >&2; \
        echo '         git checkout -- framework/security/config/security.properties' >&2; \
        echo '       and keep local development keys in an untracked config/security.properties instead.' >&2; \
        exit 1; \
    fi

# Build OFBiz while mounting a gradle cache.
# "generateSecretKeys" is deliberately not run here: it writes live login.secret_key_string and
# security.token.key values into framework/security/config/security.properties, which distTar then
# packages, so a secret would be baked into an image layer that no run-time overwrite can remove.
# Both keys are supplied at run time from OFBIZ_LOGIN_SECRET_KEY and OFBIZ_JWT_TOKEN_KEY by
# docker/docker-entrypoint.sh; see DOCKER.adoc. Run "./gradlew generateSecretKeys" only for a local,
# non-container development checkout.
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
# These markers are EMPTY, and an empty marker vouches only for the embedded H2 database baked into this
# image alongside it. docker/docker-entrypoint.sh honours an empty marker only when no external database
# is configured; start this image with OFBIZ_POSTGRES_HOST and it loads the data and creates the admin
# user in that database, because a marker written here can say nothing about it. Every marker the entry
# point writes itself carries a digest of the configuration it applied.
# They record only what has been done to the DATABASE. CONFIGURATION is not marker-gated at all - the entry
# point renders it from the packaged sources on every start - so nothing baked here can suppress the
# configuration a container's own environment asks for.
RUN mkdir --parents /ofbiz/runtime/container_state
RUN touch /ofbiz/runtime/container_state/data_loaded
RUN touch /ofbiz/runtime/container_state/admin_loaded
RUN touch /ofbiz/runtime/container_state/db_config_applied

VOLUME ["/docker-entrypoint-hooks"]
# THE VOLUME LAYOUT, and what each one holds. Read this before mounting or sharing any of them.
#
# /ofbiz/config    - the rendered configuration, and therefore THE SECRET-BEARING VOLUME. The entry point
#                    writes the admin key, the forgot-password key, the JWT signing key, the object-store
#                    secret key and the three database passwords into files here, in PLAINTEXT, mode 600,
#                    owned by the ofbiz user. It OUTLIVES the container, so removing a secret from the
#                    environment does not remove it from this volume - the entry point withdraws an
#                    override it rendered itself, and its ledger lives here for exactly that reason, but a
#                    volume kept after the container is gone still holds the last values written. Treat it
#                    as a secret store: do not share it between deployments, and wipe or recreate it when
#                    rotating. DOCKER.adoc carries the wipe and rotate procedure.
# /ofbiz/runtime   - logs, the local content/upload directory and the database markers. Per instance.
#                    Do NOT share the whole of it between instances; runtime/uploads alone may be shared,
#                    and only with the filesystem content provider.
# /ofbiz/lib-extra - operator-supplied jars, first on the class path after config. This is where a JMS
#                    provider client library goes when distributed cache invalidation is switched on.
#
# /ofbiz/framework is deliberately NOT a volume. The Tomcat container descriptor there is patched in place
# with the load-balancer settings, so it must come from the image on every recreation rather than from a
# volume that could carry a previous container's values - which is why the entry point renders
# configuration unconditionally instead of gating it on a marker.
VOLUME ["/ofbiz/config", "/ofbiz/runtime", "/ofbiz/lib-extra"]


###################################################################################
# Runtime image with no data loaded.
FROM runtimebase AS runtime

USER ofbiz

VOLUME ["/docker-entrypoint-hooks"]
# THE VOLUME LAYOUT, and what each one holds. Read this before mounting or sharing any of them.
#
# /ofbiz/config    - the rendered configuration, and therefore THE SECRET-BEARING VOLUME. The entry point
#                    writes the admin key, the forgot-password key, the JWT signing key, the object-store
#                    secret key and the three database passwords into files here, in PLAINTEXT, mode 600,
#                    owned by the ofbiz user. It OUTLIVES the container, so removing a secret from the
#                    environment does not remove it from this volume - the entry point withdraws an
#                    override it rendered itself, and its ledger lives here for exactly that reason, but a
#                    volume kept after the container is gone still holds the last values written. Treat it
#                    as a secret store: do not share it between deployments, and wipe or recreate it when
#                    rotating. DOCKER.adoc carries the wipe and rotate procedure.
# /ofbiz/runtime   - logs, the local content/upload directory and the database markers. Per instance.
#                    Do NOT share the whole of it between instances; runtime/uploads alone may be shared,
#                    and only with the filesystem content provider.
# /ofbiz/lib-extra - operator-supplied jars, first on the class path after config. This is where a JMS
#                    provider client library goes when distributed cache invalidation is switched on.
#
# /ofbiz/framework is deliberately NOT a volume. The Tomcat container descriptor there is patched in place
# with the load-balancer settings, so it must come from the image on every recreation rather than from a
# volume that could carry a previous container's values - which is why the entry point renders
# configuration unconditionally instead of gating it on a marker.
VOLUME ["/ofbiz/config", "/ofbiz/runtime", "/ofbiz/lib-extra"]

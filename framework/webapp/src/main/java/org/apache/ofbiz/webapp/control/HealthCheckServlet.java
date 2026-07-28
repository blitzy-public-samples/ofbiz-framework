/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.apache.ofbiz.webapp.control;

import java.io.IOException;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.webapp.WebAppUtil;

/**
 * HealthCheckServlet.java - Liveness and readiness probes for load-balancer target-group checks.
 *
 * <p>Two machine-readable sub-paths are served, intended to be mapped by a webapp deployment
 * descriptor as {@code /health/live} and {@code /health/ready}. Registered in the webtools webapp
 * they are reachable as {@code /webtools/health/live} and {@code /webtools/health/ready}.
 *
 * <p>Status-code contract:
 *
 * <ul>
 * <li>{@code /health/live} (liveness) - always {@code 200 OK} once the servlet container is up.
 *     No database access and no delegator lookup are performed, so it still answers while the
 *     datasource is unavailable. A load balancer uses it only to decide whether an instance has to
 *     be restarted or replaced.</li>
 * <li>{@code /health/ready} (readiness) - {@code 200 OK} when the {@code SequenceValueItem} entity
 *     can be counted and the count is non-zero, otherwise {@code 503 SERVICE_UNAVAILABLE}. A load
 *     balancer uses it to decide whether to route traffic to an instance.</li>
 * <li>Any other sub-path - {@code 404 NOT_FOUND}. A mis-configured probe has to fail visibly
 *     instead of reporting false health.</li>
 * </ul>
 *
 * <p>Each response body is a small fixed JSON document ({@code application/json}, UTF-8) that never
 * carries diagnostic detail; stack traces, SQL, connection strings and credentials go to the OFBiz
 * log only. Responses are marked non-cacheable so that no intermediary can serve a stale verdict.
 *
 * <p>The endpoints are deliberately unauthenticated and session-free, because a load-balancer
 * target group polls them continuously: no login, no permission check, no session access and no
 * service-engine invocation take place here. The servlet keeps no mutable state, so it is safe to
 * serve concurrently, and it is strictly read-only.
 *
 * <p>The class is inert until a webapp deployment descriptor maps it. The {@code webapp} component
 * declares no webapp of its own, so simply adding this class changes no existing behaviour.
 */
@SuppressWarnings("serial")
public class HealthCheckServlet extends HttpServlet {

    private static final String MODULE = HealthCheckServlet.class.getName();

    // Sub-path suffixes selecting each probe. Suffix matching keeps the class independent of the
    // context path and of whether an exact or a prefix url-pattern is used in the descriptor.
    private static final String LIVE_SUFFIX = "/live";
    private static final String READY_SUFFIX = "/ready";

    // Framework-tier entity of the default "org.apache.ofbiz" group. It is present in every
    // deployment regardless of which application components are loaded, which is why the readiness
    // probe counts it rather than any application entity.
    private static final String READINESS_ENTITY = "SequenceValueItem";

    // Fixed response bodies. Hand-built literals only: no JSON library is pulled in, and no
    // internal detail can ever leak into a body that has no variable part.
    private static final String BODY_LIVE_UP = "{\"status\":\"UP\"}";
    private static final String BODY_UNKNOWN = "{\"status\":\"DOWN\"}";
    private static final String BODY_READY_UP = "{\"status\":\"UP\",\"database\":\"UP\"}";
    private static final String BODY_READY_DOWN = "{\"status\":\"DOWN\",\"database\":\"DOWN\"}";

    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String CHARACTER_ENCODING = "UTF-8";
    private static final String CACHE_CONTROL_HEADER = "Cache-Control";
    private static final String CACHE_CONTROL_VALUE = "no-cache, no-store, must-revalidate";

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleProbe(request, response);
    }

    @Override
    protected void doHead(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Load balancers commonly probe with HEAD. The container discards the body of a HEAD
        // response by itself, so the very same handler runs and the status code and headers are
        // identical to the GET case; the body is deliberately not special-cased.
        handleProbe(request, response);
    }

    /*
     * Routes the request to the liveness or the readiness handler.
     *
     * NOTE - pre-existing behaviour, deliberately not changed here: in the webtools webapp
     * ControlFilter.doFilter (line 171) and ContextFilter.doFilter (lines 102-103) already call
     * getSession() unconditionally for every request - including the long-standing /ping.txt - so a
     * JSESSIONID is created upstream whatever this servlet does. Fixing that is out of scope; the
     * requirement honoured here is that this servlet's own code never touches the session.
     */
    private void handleProbe(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = resolveProbePath(request);
        if (path.endsWith(LIVE_SUFFIX)) {
            // Liveness: reaching this line is the whole assertion - no I/O beyond the response.
            writeResponse(response, HttpServletResponse.SC_OK, BODY_LIVE_UP);
        } else if (path.endsWith(READY_SUFFIX)) {
            if (isDatabaseReachable()) {
                writeResponse(response, HttpServletResponse.SC_OK, BODY_READY_UP);
            } else {
                writeResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, BODY_READY_DOWN);
            }
        } else {
            // An unmapped sub-path fails visibly instead of reporting a false 200, which would let
            // a load balancer keep a broken instance in service.
            writeResponse(response, HttpServletResponse.SC_NOT_FOUND, BODY_UNKNOWN);
        }
    }

    /*
     * Resolves the requested path within the webapp as servletPath followed by pathInfo.
     *
     * An exact url-pattern such as /health/live reports the whole mapped path through
     * getServletPath() and leaves getPathInfo() null, whereas a prefix pattern such as /health/*
     * splits the same path across the two. Concatenating them therefore recognises the probe
     * sub-path under either mapping style, without this class having to know which one the
     * deployment descriptor uses. Both accessors are null-tolerant here, so a container that
     * reports neither simply yields an empty path, which is treated as an unknown sub-path.
     */
    private static String resolveProbePath(HttpServletRequest request) {
        StringBuilder path = new StringBuilder();
        String servletPath = request.getServletPath();
        if (servletPath != null) {
            path.append(servletPath);
        }
        String pathInfo = request.getPathInfo();
        if (pathInfo != null) {
            path.append(pathInfo);
        }
        return path.toString();
    }

    /*
     * Mirrors the check performed by the "ping" service (CommonServices.ping in framework/common,
     * lines 479-501): count SequenceValueItem and treat both a failure and a zero count as "not
     * ready", exactly as ping reports CommonPingDatasourceCannotConnect and
     * CommonPingDatasourceInvalidCount. The check itself is used rather than the service, so no
     * dispatcher, service engine or localisation is dragged into what has to stay a cheap probe.
     *
     * The probe is strictly read-only: no DDL, no writes, no cache mutation and no explicit
     * transaction management, which is what allows a serving instance to run without DDL
     * privileges. The delegator is resolved per request rather than in init(), so a datasource that
     * only becomes reachable later flips readiness to 200 - and one that later fails flips it to
     * 503 - with no restart.
     */
    private boolean isDatabaseReachable() {
        Delegator delegator = WebAppUtil.getDelegator(getServletContext());
        if (delegator == null) {
            // WebAppUtil.getDelegator only logs and returns null when the delegator factory fails,
            // so a null result has to be handled here rather than assumed away.
            Debug.logError("Readiness probe failed: no delegator is available for this webapp", MODULE);
            return false;
        }
        try {
            return EntityQuery.use(delegator).from(READINESS_ENTITY).queryCount() != 0L;
        } catch (GenericEntityException e) {
            // Message only - never the Throwable overload and never a stack trace - exactly as
            // CommonServices.ping does on line 491.
            Debug.logError(e.getMessage(), MODULE);
            return false;
        } catch (RuntimeException e) {
            // Defensive: an unchecked failure such as an exhausted connection pool degrades to 503
            // instead of escaping and letting the container render an error page. The exception
            // class is logged because unchecked exceptions frequently carry a null message.
            Debug.logError("Readiness probe failed: " + e.getClass().getName() + ": " + e.getMessage(), MODULE);
            return false;
        }
    }

    /*
     * Writes the probe verdict.
     *
     * setStatus is used deliberately in place of sendError: sendError hands the response to the
     * container's error-page machinery, which would replace this compact JSON document with an HTML
     * page and would also be intercepted by any error-page element added to a deployment descriptor
     * later. The character encoding and content type are set before the writer is obtained, as the
     * servlet contract requires.
     */
    private static void writeResponse(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(CONTENT_TYPE_JSON);
        response.setCharacterEncoding(CHARACTER_ENCODING);
        response.setHeader(CACHE_CONTROL_HEADER, CACHE_CONTROL_VALUE);
        response.getWriter().print(body);
    }
}

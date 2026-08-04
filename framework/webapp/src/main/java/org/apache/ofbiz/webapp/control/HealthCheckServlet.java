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
 * Liveness and readiness endpoints for a load balancer's target-group health check.
 *
 * <p>Two paths are served, and each answers a different question:
 *
 * <ul>
 *   <li>{@code /health/live} - is this JVM answering HTTP at all? Always 200 with
 *       {@code {"status":"UP"}}. A load balancer uses it to decide whether to restart the instance, so
 *       it deliberately depends on nothing outside the servlet container: an instance whose database is
 *       briefly unreachable is not a broken instance.</li>
 *   <li>{@code /health/ready} - can this instance serve a request that touches the database? 200 with
 *       {@code {"status":"UP","database":"UP"}} when a query against the base delegator succeeds, and
 *       503 with {@code {"status":"DOWN","database":"DOWN"}} when it does not. A load balancer uses it
 *       to decide whether to send traffic, so an instance that cannot reach its datasource is taken out
 *       of rotation rather than restarted.</li>
 * </ul>
 *
 * <p>The readiness query mirrors the {@code ping} service in
 * {@code org.apache.ofbiz.common.CommonServices}: it counts rows in {@code SequenceValueItem}, a seed
 * entity every deployment has, so the check exercises the connection pool, the JDBC driver, the
 * datasource credentials and the schema without touching business data or writing anything.
 *
 * <p>The servlet holds no state: the delegator is resolved from the servlet context on every request, so
 * a probe reports what the instance can do NOW rather than what it could do when the servlet was first
 * loaded. It never calls {@code getSession()} and never authenticates: it is mapped outside
 * {@code /control/*}, so it carries no base permission, and its two paths are listed in
 * {@code ControlFilter}'s {@code allowedPaths} so a probe reaches it anonymously.
 *
 * <p>Registered from a webapp's {@code web.xml} with one {@code servlet} element and the two exact
 * {@code url-pattern} values. Any other path that reaches this class - only possible through an internal
 * dispatch, since the mappings are exact - is answered 404 rather than a false 200, so a misconfigured
 * probe cannot keep a broken instance in service.
 */
public final class HealthCheckServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final String MODULE = HealthCheckServlet.class.getName();

    /** The liveness path, relative to the webapp's context path. */
    private static final String PROBE_LIVE = "/health/live";

    /** The readiness path, relative to the webapp's context path. */
    private static final String PROBE_READY = "/health/ready";

    /** The seed entity the readiness query counts; see the {@code ping} service. */
    private static final String READINESS_ENTITY = "SequenceValueItem";

    private static final String BODY_LIVE_UP = "{\"status\":\"UP\"}";
    private static final String BODY_READY_UP = "{\"status\":\"UP\",\"database\":\"UP\"}";
    private static final String BODY_READY_DOWN = "{\"status\":\"DOWN\",\"database\":\"DOWN\"}";

    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String CHARACTER_ENCODING = "UTF-8";
    private static final String CACHE_CONTROL_HEADER = "Cache-Control";
    private static final String CACHE_CONTROL_VALUE = "no-store";

    /**
     * Answers a probe sent with GET.
     *
     * @param request the probe request
     * @param response the response to write
     * @throws IOException if the response cannot be written
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleProbe(request, response);
    }

    /**
     * Answers a probe sent with HEAD, with exactly the status and headers GET would answer.
     *
     * <p>Written here rather than left to the default implementation, which wraps the response in a
     * body-swallowing decorator and calls {@code doGet} only to compute a Content-Length - work a health
     * probe has no use for. The container suppresses the body of a HEAD response itself.
     *
     * @param request the probe request
     * @param response the response to write
     * @throws IOException if the response cannot be written
     */
    @Override
    protected void doHead(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleProbe(request, response);
    }

    /**
     * Serves one probe: routes on the requested path and writes the verdict.
     *
     * @param request the probe request
     * @param response the response to write
     * @throws IOException if the response cannot be written
     */
    private void handleProbe(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = pathWithinWebapp(request);
        if (PROBE_LIVE.equals(path)) {
            writeResponse(response, HttpServletResponse.SC_OK, BODY_LIVE_UP);
        } else if (PROBE_READY.equals(path)) {
            if (isDatabaseReachable()) {
                writeResponse(response, HttpServletResponse.SC_OK, BODY_READY_UP);
            } else {
                writeResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, BODY_READY_DOWN);
            }
        } else {
            // Not a probe path. Answered without a body, and never with a 200, so a mistyped probe URL
            // cannot report health this class did not establish.
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setHeader(CACHE_CONTROL_HEADER, CACHE_CONTROL_VALUE);
        }
    }

    /**
     * Reports whether this instance can reach its database.
     *
     * <p>The delegator is resolved per request from the servlet context, and a null one - the Entity
     * Engine could not build it - is itself a not-ready verdict. Both a checked
     * {@link GenericEntityException} and an unchecked failure from the pool or the driver are reported
     * as not ready: a readiness probe answers a question, so it never propagates.
     *
     * @return true when the readiness query succeeded and found the seed entity populated
     */
    private boolean isDatabaseReachable() {
        Delegator delegator = WebAppUtil.getDelegator(getServletContext());
        if (delegator == null) {
            Debug.logError("Readiness probe found no delegator for this webapp", MODULE);
            return false;
        }
        try {
            // Only the message is logged: a stack trace on every failed probe of every instance would
            // flood the log while a database is unreachable, which is exactly when it must stay readable.
            return EntityQuery.use(delegator).from(READINESS_ENTITY).queryCount() > 0;
        } catch (GenericEntityException e) {
            Debug.logError(e.getMessage(), MODULE);
            return false;
        } catch (RuntimeException e) {
            Debug.logError(e.getMessage(), MODULE);
            return false;
        }
    }

    /**
     * Returns the requested path with the context path removed.
     *
     * <p>Assembled from the container's own decoded, normalised parts rather than from the raw URI, so
     * that a percent-escaped or dot-segment spelling cannot be mistaken for a probe path. With the exact
     * mappings this class is registered under, the whole path is in the servlet path and there is no
     * path info; an internal dispatch can produce either.
     *
     * @param request the probe request
     * @return the path within the webapp, never null
     */
    private static String pathWithinWebapp(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        return (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
    }

    /**
     * Writes one probe response: status, JSON body, and headers that stop anything caching a verdict.
     *
     * <p>{@code setStatus} rather than {@code sendError}, because {@code sendError} hands the response to
     * the container's error page machinery, which would replace this body with an HTML error page and
     * could run an error-page dispatch on a path that requires the database this probe just reported
     * unreachable.
     *
     * @param response the response to write
     * @param status the HTTP status to report
     * @param body the JSON body to write
     * @throws IOException if the response cannot be written
     */
    private static void writeResponse(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(CONTENT_TYPE_JSON);
        response.setCharacterEncoding(CHARACTER_ENCODING);
        response.setHeader(CACHE_CONTROL_HEADER, CACHE_CONTROL_VALUE);
        response.getWriter().write(body);
    }
}

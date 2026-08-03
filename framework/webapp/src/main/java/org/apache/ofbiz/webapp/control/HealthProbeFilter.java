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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import org.apache.ofbiz.base.util.Debug;

/**
 * HealthProbeFilter.java - Routes a load-balancer probe to {@link HealthCheckServlet} without the rest
 * of the webapp's filter chain.
 *
 * <p>This filter exists for one reason: a probe must cost the instance nothing and must leave nothing
 * behind. Every OFBiz webapp maps {@code ControlFilter}, {@code CacheFilter}, {@code ContextFilter} and
 * {@code SameSiteFilter} to {@code /*}, and the first two of those call {@code getSession()}
 * unconditionally - {@code ControlFilter} before it even consults its allow-list. A probe answered
 * behind that chain therefore creates an {@code HttpSession}, and the container emits a session cookie
 * for it, on every probe. A target group probes each instance every few seconds and follows no cookie,
 * so those sessions accumulate until they expire on their own: they are pure overhead on the one
 * request path that has to stay cheap, and the cookie invites a load balancer with cookie-based
 * stickiness to pin traffic using an identifier minted by a health check. Neither is acceptable in a
 * fleet that is supposed to be stateless.
 *
 * <p><strong>How the bypass works.</strong> This filter is mapped first, and only on the two exact
 * probe paths. For a probe it forwards to the health servlet through
 * {@link jakarta.servlet.ServletContext#getNamedDispatcher} and returns without calling the chain, so
 * none of the filters after it runs. A <em>named</em> dispatch is used rather than a path dispatch for
 * two reasons: the servlet spec applies only filters mapped by {@code servlet-name} to a named
 * dispatch, so a path-mapped filter cannot creep back into the probe path, and a named dispatch does
 * not re-run url-pattern matching, so it cannot dispatch back into this filter however the webapp is
 * mounted. The request's {@code servletPath} and {@code pathInfo} are also left untouched by a named
 * dispatch, which is what lets the servlet resolve the probe path exactly as it would for a direct
 * request.
 *
 * <p>For anything that is not a probe this filter calls the chain and changes nothing. Its mapping is
 * two exact paths, so in a correctly configured webapp it is never entered for anything else; the
 * check below is what makes that a property of this class rather than a property of one descriptor.
 *
 * <p><strong>Why this also removes the anonymous prefix grant.</strong> Reaching the health servlet
 * through the ordinary chain requires an entry in {@code ControlFilter}'s {@code allowedPaths}, which
 * is matched with {@code startsWith}. A single {@code /health} entry is the only concise way to express
 * that, and it grants anonymous chain access to every {@code /health*} spelling -
 * {@code /healthz/live}, {@code /health-internal}, {@code /health/live/anything} - none of which is a
 * probe. Because this filter answers probes before {@code ControlFilter} is reached, that entry is not
 * needed at all: the grant is removed rather than narrowed, and a near miss is treated exactly like any
 * other unauthenticated request to a protected webapp.
 *
 * <p><strong>What it deliberately does not do.</strong> It performs no authentication, no permission
 * check, no session access, no parameter parsing and no body parsing, and it writes nothing to the
 * response. Skipping {@code ControlFilter}'s URL and parameter rejection is safe here and only here,
 * because the two paths are matched exactly, the container has already normalised and decoded the URI
 * and stripped path parameters, and the servlet reached through this filter reads no request parameter,
 * no body and no session - it writes a fixed JSON document decided by a database count. Nothing this
 * filter skips has an input to act on.
 *
 * <p><strong>Failure behaviour is to fall through, not to fail.</strong> If the named dispatcher is
 * absent - the webapp mapped this filter but not the servlet - the request continues down the ordinary
 * chain, so the container answers it as it would have without this filter. A misconfiguration
 * degrades to the previous behaviour instead of turning a probe into a 500, which on an
 * unauthenticated path would also mean an HTML error page.
 *
 * @see HealthCheckServlet
 */
public class HealthProbeFilter implements Filter {

    private static final String MODULE = HealthProbeFilter.class.getName();

    /**
     * The {@code servlet-name} of the health servlet, as the descriptor that registers it declares it.
     *
     * <p>A named dispatch needs the registered name, and the name is part of the contract between this
     * filter and the descriptor: a webapp that registers the servlet under another name has to say so,
     * which is what the {@code probeServletName} init-param below is for.
     */
    static final String DEFAULT_PROBE_SERVLET_NAME = "HealthCheckServlet";

    /** The init-param a webapp uses when it registers the health servlet under a different name. */
    static final String SERVLET_NAME_PARAMETER = "probeServletName";

    private String probeServletName = DEFAULT_PROBE_SERVLET_NAME;

    @Override
    public void init(jakarta.servlet.FilterConfig config) {
        String configured = config.getInitParameter(SERVLET_NAME_PARAMETER);
        if (configured != null && !configured.isBlank()) {
            probeServletName = configured.trim();
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest http && HealthCheckServlet.isProbePath(pathWithinWebapp(http))) {
            RequestDispatcher probe = http.getServletContext().getNamedDispatcher(probeServletName);
            if (probe != null) {
                probe.forward(request, response);
                return;
            }
            // Reported once per occurrence rather than suppressed: it means the descriptor maps this
            // filter without registering the servlet it exists to reach, which is worth fixing even
            // though the request below is still answered.
            Debug.logWarning("No servlet named [" + probeServletName + "] is registered in this webapp, so the"
                    + " health probe was left to the ordinary filter chain. Register the health servlet, or set"
                    + " the " + SERVLET_NAME_PARAMETER + " init-param to the name it is registered under.",
                    MODULE);
        }
        chain.doFilter(request, response);
    }

    /**
     * Returns the requested path with the webapp's context path removed.
     *
     * <p>{@code servletPath} plus {@code pathInfo} rather than {@code requestURI}, so the result is
     * independent of the context path the webapp is mounted at and needs no string surgery to compare -
     * the same reason the servlet resolves its own path that way. Both accessors are null-tolerant
     * here, so a container that reports neither yields an empty path, which is not a probe path.
     *
     * @param request the request to describe
     * @return the path within the webapp, never null
     */
    private static String pathWithinWebapp(HttpServletRequest request) {
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
}

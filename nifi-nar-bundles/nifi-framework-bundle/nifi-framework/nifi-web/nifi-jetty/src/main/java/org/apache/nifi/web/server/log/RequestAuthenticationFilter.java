/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.web.server.log;

import jakarta.servlet.*;
import org.apache.nifi.web.security.log.AuthenticationUserAttribute;
import org.eclipse.jetty.security.DefaultUserIdentity;
import org.eclipse.jetty.security.UserAuthentication;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.UserIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;

import java.io.IOException;
import java.security.Principal;

/**
 * Request Authentication Filter sets Jetty Request Authentication using Spring Security Authentication as Principal
 */
public class RequestAuthenticationFilter implements Filter {
    private static final Subject DEFAULT_SUBJECT = new Subject();

    private static final String[] DEFAULT_ROLES = new String[]{};

    private static final String METHOD = "FILTER";

    private static final Logger logger = LoggerFactory.getLogger(RequestAuthenticationFilter.class);

    /**
     * Read Authentication username from request attribute and set Jetty Authentication when found
     *
     * @param httpServletRequest  HTTP Servlet Request
     * @param httpServletResponse HTTP Servlet Response
     * @param filterChain         Filter Chain
     * @throws ServletException Thrown on FilterChain.doFilter()
     * @throws IOException      Thrown on FilterChain.doFilter()
     */

    @Override
    public void doFilter(final ServletRequest httpServletRequest, final ServletResponse httpServletResponse, final FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(httpServletRequest, httpServletResponse);

        if (httpServletRequest instanceof Request) {
            final Request request = (Request) httpServletRequest;
            final Object usernameAttribute = httpServletRequest.getAttribute(AuthenticationUserAttribute.USERNAME.getName());
            if (usernameAttribute == null) {
                logger.debug("Username not found Remote Address [{}]", httpServletRequest.getRemoteAddr());
            } else {
                final String username = usernameAttribute.toString();
                final Principal principal = new UserPrincipal(username);
                final UserIdentity userIdentity = new DefaultUserIdentity(DEFAULT_SUBJECT, principal, DEFAULT_ROLES);
                final UserAuthentication authentication = new UserAuthentication(METHOD, userIdentity);
                request.setAuthentication(authentication);
            }
        }
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        Filter.super.init(filterConfig);
    }

    @Override
    public void destroy() {
        Filter.super.destroy();
    }
}

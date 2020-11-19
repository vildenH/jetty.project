//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.server;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketUpgradeHandler extends HandlerWrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketUpgradeHandler.class);

    private final WebSocketMapping mappings;
    private final Configuration.ConfigurationCustomizer customizer = new Configuration.ConfigurationCustomizer();

    public WebSocketUpgradeHandler()
    {
        this(new WebSocketComponents());
    }

    public WebSocketUpgradeHandler(WebSocketComponents components)
    {
        this.mappings = new WebSocketMapping(components);
    }

    public void addMapping(String pathSpec, WebSocketNegotiator negotiator)
    {
        mappings.addMapping(new ServletPathSpec(pathSpec), negotiator);
    }

    public void addMapping(PathSpec pathSpec, WebSocketNegotiator negotiator)
    {
        mappings.addMapping(pathSpec, negotiator);
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (mappings.upgrade(request, response, customizer))
            return;

        if (!baseRequest.isHandled())
            super.handle(target, baseRequest, request, response);
    }
}

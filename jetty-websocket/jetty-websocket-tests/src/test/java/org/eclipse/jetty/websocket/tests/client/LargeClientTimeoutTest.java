//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.tests.client;

import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.IdleTimeout;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.io.AbstractWebSocketConnection;
import org.eclipse.jetty.websocket.server.NativeWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
import org.eclipse.jetty.websocket.tests.EchoSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LargeClientTimeoutTest
{
    private Server server;
    private WebSocketClient client;

    @BeforeEach
    public void start() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);

        NativeWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
            container.addMapping("/", (req, res) -> new EchoSocket()));
        contextHandler.addFilter(WebSocketUpgradeFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        server.start();

        client = new WebSocketClient();
        client.start();
    }

    @AfterEach
    public void stop()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @Test
    public void testMaxIdleTimeoutTwoHour() throws Exception
    {
        long timeout = Duration.ofHours(2).toMillis();
        client.setMaxIdleTimeout(timeout);
        Future<Session> connect = client.connect(new SimpleSocket(), WSURI.toWebsocket(server.getURI()));

        try (Session clientSession = connect.get(5, TimeUnit.SECONDS))
        {
            assertEquals(timeout, clientSession.getIdleTimeout(), "Client specified idle timeout");
            WebSocketSession wsSession = (WebSocketSession)clientSession;
            LogicalConnection connection = wsSession.getConnection();
            AbstractWebSocketConnection wsConnection = (AbstractWebSocketConnection)connection;
            EndPoint clientEndpoint = wsConnection.getEndPoint();
            IdleTimeout idleTimeout = (IdleTimeout)clientEndpoint;
            assertEquals(timeout, idleTimeout.getIdleTimeout(), "EndPoint current idle timeout");
        }
    }

    @Test
    public void testAnnotatedIdleTimeoutTwoHour() throws Exception
    {
        Future<Session> connect = client.connect(new AnnotatedSimpleSocket(), WSURI.toWebsocket(server.getURI()));

        try (Session clientSession = connect.get(5, TimeUnit.SECONDS))
        {
            assertEquals(7200000, clientSession.getIdleTimeout(), "Client specified idle timeout");
            WebSocketSession wsSession = (WebSocketSession)clientSession;
            LogicalConnection connection = wsSession.getConnection();
            AbstractWebSocketConnection wsConnection = (AbstractWebSocketConnection)connection;
            EndPoint clientEndpoint = wsConnection.getEndPoint();
            IdleTimeout idleTimeout = (IdleTimeout)clientEndpoint;
            assertEquals(7200000, idleTimeout.getIdleTimeout(), "EndPoint current idle timeout");
        }
    }

    @Test
    public void testAfterOpenIdleTimeoutTwoHour() throws Exception
    {
        Future<Session> connect = client.connect(new AfterOpenSocket(), WSURI.toWebsocket(server.getURI()));

        try (Session clientSession = connect.get(5, TimeUnit.SECONDS))
        {
            assertEquals(7200000, clientSession.getIdleTimeout(), "Client specified idle timeout");
            WebSocketSession wsSession = (WebSocketSession)clientSession;
            LogicalConnection connection = wsSession.getConnection();
            AbstractWebSocketConnection wsConnection = (AbstractWebSocketConnection)connection;
            EndPoint clientEndpoint = wsConnection.getEndPoint();
            IdleTimeout idleTimeout = (IdleTimeout)clientEndpoint;
            assertEquals(7200000, idleTimeout.getIdleTimeout(), "EndPoint current idle timeout");
        }
    }

    @WebSocket
    public static class SimpleSocket
    {
    }

    @WebSocket(maxIdleTime = 7200000)
    public static class AnnotatedSimpleSocket
    {
    }

    @WebSocket
    public static class AfterOpenSocket
    {
        @OnWebSocketConnect
        public void onOpen(Session session)
        {
            session.setIdleTimeout(7200000);
        }
    }
}

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

package org.eclipse.jetty.websocket.tests.server;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.EnumSet;
import java.util.Properties;
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
import org.eclipse.jetty.websocket.tests.EventSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LargeServerTimeoutTest
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
            {
                container.getPolicy().setIdleTimeout(88088);
                container.addMapping("/simple", (req, res) -> new SimpleSocket());
                container.addMapping("/annotated", (req, res) -> new AnnotatedSimpleSocket());
                container.addMapping("/afteropen", (req, res) -> new AfterOpenSocket());
            }
        );
        contextHandler.addFilter(WebSocketUpgradeFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        server.start();

        client = new WebSocketClient();
        client.setMaxIdleTimeout(6000);
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @Test
    public void testMaxIdleTimeoutTwoHour() throws Exception
    {
        EventSocket clientSocket = new EventSocket();
        URI endpointURI = server.getURI().resolve("/simple");
        Future<Session> connect = client.connect(clientSocket, WSURI.toWebsocket(endpointURI));

        try (Session ignored = connect.get(5, TimeUnit.SECONDS))
        {
            assertTrue(clientSocket.openLatch.await(5, TimeUnit.SECONDS), "Client Socket not opened?");
            String msg = clientSocket.textMessages.poll(5, TimeUnit.SECONDS);
            assertNotNull(msg, "Expected msg from remote with server idleTimeout values");
            Properties props = new Properties();
            props.load(new StringReader(msg));
            assertEquals("88088", props.getProperty("session.idleTimeout"));
            assertEquals("88088", props.getProperty("endpoint.idleTimeout"));
        }
    }

    @Test
    public void testAnnotatedIdleTimeoutTwoHour() throws Exception
    {
        EventSocket clientSocket = new EventSocket();
        URI endpointURI = server.getURI().resolve("/annotated");
        Future<Session> connect = client.connect(clientSocket, WSURI.toWebsocket(endpointURI));

        try (Session ignored = connect.get(5, TimeUnit.SECONDS))
        {
            assertTrue(clientSocket.openLatch.await(5, TimeUnit.SECONDS), "Client Socket not opened?");
            String msg = clientSocket.textMessages.poll(5, TimeUnit.SECONDS);
            assertNotNull(msg, "Expected msg from remote with server idleTimeout values");
            Properties props = new Properties();
            props.load(new StringReader(msg));
            assertEquals("7200000", props.getProperty("session.idleTimeout"));
            assertEquals("7200000", props.getProperty("endpoint.idleTimeout"));
        }
    }

    @Test
    public void testAfterOpenIdleTimeoutTwoHour() throws Exception
    {
        EventSocket clientSocket = new EventSocket();
        URI endpointURI = server.getURI().resolve("/afteropen");
        Future<Session> connect = client.connect(clientSocket, WSURI.toWebsocket(endpointURI));

        try (Session ignored = connect.get(5, TimeUnit.SECONDS))
        {
            assertTrue(clientSocket.openLatch.await(5, TimeUnit.SECONDS), "Client Socket not opened?");
            String msg = clientSocket.textMessages.poll(5, TimeUnit.SECONDS);
            assertNotNull(msg, "Expected msg from remote with server idleTimeout values");
            Properties props = new Properties();
            props.load(new StringReader(msg));
            assertEquals("7200000", props.getProperty("session.idleTimeout"));
            assertEquals("7200000", props.getProperty("endpoint.idleTimeout"));
        }
    }

    @WebSocket
    public static class SimpleSocket
    {
        @OnWebSocketConnect
        public void onOpen(Session session) throws IOException
        {
            WebSocketSession wsSession = (WebSocketSession)session;
            LogicalConnection connection = wsSession.getConnection();
            AbstractWebSocketConnection wsConnection = (AbstractWebSocketConnection)connection;
            EndPoint endPoint = wsConnection.getEndPoint();
            IdleTimeout idleTimeout = (IdleTimeout)endPoint;

            StringBuilder msg = new StringBuilder();
            msg.append("session.idleTimeout=").append(session.getIdleTimeout());
            msg.append("\nendpoint.idleTimeout=").append(idleTimeout.getIdleTimeout());

            session.getRemote().sendString(msg.toString());
        }
    }

    @WebSocket(maxIdleTime = 7200000)
    public static class AnnotatedSimpleSocket
    {
        @OnWebSocketConnect
        public void onOpen(Session session) throws IOException
        {
            WebSocketSession wsSession = (WebSocketSession)session;
            LogicalConnection connection = wsSession.getConnection();
            AbstractWebSocketConnection wsConnection = (AbstractWebSocketConnection)connection;
            EndPoint endPoint = wsConnection.getEndPoint();
            IdleTimeout idleTimeout = (IdleTimeout)endPoint;

            StringBuilder msg = new StringBuilder();
            msg.append("session.idleTimeout=").append(session.getIdleTimeout());
            msg.append("\nendpoint.idleTimeout=").append(idleTimeout.getIdleTimeout());

            session.getRemote().sendString(msg.toString());
        }
    }

    @WebSocket
    public static class AfterOpenSocket
    {
        @OnWebSocketConnect
        public void onOpen(Session session) throws IOException
        {
            session.setIdleTimeout(7200000);
            WebSocketSession wsSession = (WebSocketSession)session;
            LogicalConnection connection = wsSession.getConnection();
            AbstractWebSocketConnection wsConnection = (AbstractWebSocketConnection)connection;
            EndPoint endPoint = wsConnection.getEndPoint();
            IdleTimeout idleTimeout = (IdleTimeout)endPoint;

            StringBuilder msg = new StringBuilder();
            msg.append("session.idleTimeout=").append(session.getIdleTimeout());
            msg.append("\nendpoint.idleTimeout=").append(idleTimeout.getIdleTimeout());

            session.getRemote().sendString(msg.toString());
        }
    }
}

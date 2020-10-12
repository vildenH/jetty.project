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

package org.eclipse.jetty.server.ssl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.LeakTrackingByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.HttpServerTestBase;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

/**
 * HttpServer Tester.
 */
public class SelectChannelServerSslTest extends HttpServerTestBase
{
    private static final Logger LOG = LoggerFactory.getLogger(SelectChannelServerSslTest.class);
    private SSLContext _sslContext;

    public SelectChannelServerSslTest()
    {
        _scheme = "https";
    }

    @BeforeEach
    public void init() throws Exception
    {
        String keystorePath = MavenTestingUtils.getTestResourcePath("keystore.p12").toString();
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystorePath);
        sslContextFactory.setKeyStorePassword("storepwd");
        ByteBufferPool pool = new LeakTrackingByteBufferPool(new MappedByteBufferPool.Tagged());

        HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory();
        ServerConnector connector = new ServerConnector(_server, null, null, pool, 1, 1, AbstractConnectionFactory.getFactories(sslContextFactory, httpConnectionFactory));
        SecureRequestCustomizer secureRequestCustomer = new SecureRequestCustomizer();
        secureRequestCustomer.setSslSessionAttribute("SSL_SESSION");
        httpConnectionFactory.getHttpConfiguration().addCustomizer(secureRequestCustomer);

        startServer(connector);

        KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream stream = sslContextFactory.getKeyStoreResource().getInputStream())
        {
            keystore.load(stream, "storepwd".toCharArray());
        }
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keystore);
        _sslContext = SSLContext.getInstance("TLS");
        _sslContext.init(null, trustManagerFactory.getTrustManagers(), null);

        try
        {
            // Client configuration in case we use HttpsURLConnection.
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, SslContextFactory.TRUST_ALL_CERTS, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected Socket newSocket(String host, int port) throws Exception
    {
        Socket socket = _sslContext.getSocketFactory().createSocket(host, port);
        socket.setSoTimeout(10000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    @Override
    @DisabledOnOs(WINDOWS) // Don't run on Windows (buggy JVM)
    public void testFullMethod() throws Exception
    {
        try
        {
            super.testFullMethod();
        }
        catch (SocketException e)
        {
            // TODO This needs to be investigated #2244
            LOG.warn("Close overtook 400 response", e);
        }
        catch (SSLException e)
        {
            // TODO This needs to be investigated #2244
            if (e.getCause() instanceof SocketException)
                LOG.warn("Close overtook 400 response", e);
            else
                throw e;
        }
    }

    @Override
    @DisabledOnOs(WINDOWS) // Don't run on Windows (buggy JVM)
    public void testFullURI() throws Exception
    {
        try
        {
            super.testFullURI();
        }
        catch (SocketException e)
        {
            LOG.warn("Close overtook 400 response", e);
        }
    }

    @Override
    public void testFullHeader() throws Exception
    {
        super.testFullHeader();
    }

    @Override
    public void testBlockingWhileReadingRequestContent() throws Exception
    {
        super.testBlockingWhileReadingRequestContent();
    }

    @Override
    public void testBlockingWhileWritingResponseContent() throws Exception
    {
        super.testBlockingWhileWritingResponseContent();
    }

    @Test
    public void testRequest2FixedFragments() throws Exception
    {
        configureServer(new EchoHandler());

        byte[] bytes = REQUEST2.getBytes();
        int[] points = new int[]{74, 325};

        // Sort the list
        Arrays.sort(points);

        URI uri = _server.getURI();
        Socket client = newSocket(uri.getHost(), uri.getPort());
        try
        {
            OutputStream os = client.getOutputStream();

            int last = 0;

            // Write out the fragments
            for (int j = 0; j < points.length; ++j)
            {
                int point = points[j];
                os.write(bytes, last, point - last);
                last = point;
                os.flush();
                Thread.sleep(PAUSE);
            }

            // Write the last fragment
            os.write(bytes, last, bytes.length - last);
            os.flush();
            Thread.sleep(PAUSE);

            // Read the response
            String response = readResponse(client);

            // Check the response
            assertEquals(RESPONSE2, response);
        }
        finally
        {
            client.close();
        }
    }

    @Override
    @Test
    @Disabled("Override and ignore this test as SSLSocket.shutdownOutput() is not supported, " +
        "but shutdownOutput() is needed by the test.")
    public void testInterruptedRequest()
    {
    }

    @Override
    @Disabled
    public void testAvailable() throws Exception
    {
    }

    @Test
    public void testSecureRequestCustomizer() throws Exception
    {
        configureServer(new SecureRequestHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            String request = "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
            os.write(request.getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // Read the response.
            String response = readResponse(client);

            assertThat(response, containsString("HTTP/1.1 200 OK"));
            assertThat(response, containsString("Hello world"));
            assertThat(response, containsString("scheme='https'"));
            assertThat(response, containsString("isSecure='true'"));
            assertThat(response, containsString("X509Certificate='null'"));

            Matcher matcher = Pattern.compile("cipher_suite='([^']*)'").matcher(response);
            matcher.find();
            assertThat(matcher.group(1), Matchers.allOf(not(is(emptyOrNullString()))), not(is("null")));

            matcher = Pattern.compile("key_size='([^']*)'").matcher(response);
            matcher.find();
            assertThat(matcher.group(1), Matchers.allOf(not(is(emptyOrNullString())), not(is("null"))));

            matcher = Pattern.compile("ssl_session_id='([^']*)'").matcher(response);
            matcher.find();
            assertThat(matcher.group(1), Matchers.allOf(not(is(emptyOrNullString())), not(is("null"))));

            matcher = Pattern.compile("ssl_session='([^']*)'").matcher(response);
            matcher.find();
            assertThat(matcher.group(1), Matchers.allOf(not(is(emptyOrNullString())), not(is("null"))));
        }
    }

    public static class SecureRequestHandler extends AbstractHandler
    {

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);
            response.getOutputStream().println("Hello world");
            response.getOutputStream().println("scheme='" + request.getScheme() + "'");
            response.getOutputStream().println("isSecure='" + request.isSecure() + "'");
            response.getOutputStream().println("X509Certificate='" + request.getAttribute("jakarta.servlet.request.X509Certificate") + "'");
            response.getOutputStream().println("cipher_suite='" + request.getAttribute("jakarta.servlet.request.cipher_suite") + "'");
            response.getOutputStream().println("key_size='" + request.getAttribute("jakarta.servlet.request.key_size") + "'");
            response.getOutputStream().println("ssl_session_id='" + request.getAttribute("jakarta.servlet.request.ssl_session_id") + "'");
            SSLSession sslSession = (SSLSession)request.getAttribute("SSL_SESSION");
            response.getOutputStream().println("ssl_session='" + sslSession + "'");
        }
    }
}

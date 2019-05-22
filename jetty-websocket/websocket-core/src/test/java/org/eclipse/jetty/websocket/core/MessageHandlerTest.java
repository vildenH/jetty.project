//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.core.FrameHandler.CoreSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.util.BufferUtil.toBuffer;
import static org.eclipse.jetty.util.Callback.NOOP;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MessageHandlerTest
{
    // Testing with 4 byte UTF8 character "\uD842\uDF9F"
    static String fourByteUtf8String = "\uD842\uDF9F";
    static byte[] fourByteUtf8Bytes = fourByteUtf8String.getBytes(StandardCharsets.UTF_8);
    static byte[] nonUtf8Bytes = { 0x7F, (byte)0xFF, (byte)0xFF };

    boolean demanding;
    int demand;
    CoreSession coreSession;
    List<String> textMessages = new ArrayList<>();
    List<ByteBuffer> binaryMessages = new ArrayList<>();
    List<Callback> callbacks = new ArrayList<>();
    List<Frame> frames = new ArrayList<>();
    MessageHandler handler;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        demanding = false;
        demand = 0;

        coreSession = new CoreSession.Empty()
        {
            private ByteBufferPool byteBufferPool = new MappedByteBufferPool();

            @Override
            public void sendFrame(Frame frame, Callback callback, boolean batch)
            {
                frames.add(frame);
                callback.succeeded();
            }

            @Override
            public ByteBufferPool getByteBufferPool()
            {
                return byteBufferPool;
            }

            @Override
            public void demand(long n)
            {
                demand += n;
            }
        };

        handler = new MessageHandler()
        {
            @Override
            protected void onText(String message, Callback callback)
            {
                textMessages.add(message);
                callbacks.add(callback);
            }

            @Override
            protected void onBinary(ByteBuffer message, Callback callback)
            {
                binaryMessages.add(message);
                callbacks.add(callback);
            }

            @Override
            public boolean isDemanding()
            {
                return demanding;
            }
        };

        handler.onOpen(coreSession, NOOP);
    }

    @Test
    public void testPingPongFrames()
    {
        FutureCallback callback;

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.PING, true, "test"), callback);
        assertThat(callback.isDone(), is(true));

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.PONG, true, "test"), callback);
        assertThat(callback.isDone(), is(true));

        assertThat(textMessages.size(), is(0));
        assertThat(frames.size(), is(0));
    }

    @Test
    public void testOneFrameText()
    {
        FutureCallback callback;

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.TEXT, true, "test"), callback);
        assertThat(callback.isDone(), is(false));
        assertThat(textMessages.size(), is(1));
        assertThat(textMessages.get(0), is("test"));
        assertThat(callbacks.size(), is(1));
        callbacks.get(0).succeeded();
        assertThat(callback.isDone(), is(true));
        assertDoesNotThrow(() -> callback.get());

        assertThat(frames.size(), is(0));
    }

    @Test
    public void testManyFrameText()
    {
        FutureCallback callback;

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.TEXT, false, "Hello"), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(textMessages.size(), is(0));
        assertThat(callbacks.size(), is(0));

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.CONTINUATION, false, " "), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(textMessages.size(), is(0));
        assertThat(callbacks.size(), is(0));

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.CONTINUATION, true, "World"), callback);
        assertThat(callback.isDone(), is(false));
        assertThat(textMessages.size(), is(1));
        assertThat(textMessages.get(0), is("Hello World"));
        assertThat(callbacks.size(), is(1));
        callbacks.get(0).succeeded();
        assertThat(callback.isDone(), is(true));
        FutureCallback finalCallback = callback;
        assertDoesNotThrow(() -> finalCallback.get());

        assertThat(frames.size(), is(0));
    }

    @Test
    public void testSplitUtf8Message()
    {
        FutureCallback callback;

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.TEXT, false, BufferUtil.toBuffer(fourByteUtf8Bytes, 0, 2)), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(textMessages.size(), is(0));
        assertThat(callbacks.size(), is(0));

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.CONTINUATION, false, BufferUtil.toBuffer(fourByteUtf8Bytes, 2, 1)), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(textMessages.size(), is(0));
        assertThat(callbacks.size(), is(0));

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.CONTINUATION, true, BufferUtil.toBuffer(fourByteUtf8Bytes, 3, 1)), callback);
        assertThat(callback.isDone(), is(false));
        assertThat(textMessages.size(), is(1));
        assertThat(textMessages.get(0), is(fourByteUtf8String));
        assertThat(callbacks.size(), is(1));
        callbacks.get(0).succeeded();
        assertThat(callback.isDone(), is(true));
        FutureCallback finalCallback = callback;
        assertDoesNotThrow(() -> finalCallback.get());

        assertThat(frames.size(), is(0));
    }

    @Test
    public void testIncompleteUtf8Message()
    {
        FutureCallback callback;

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.TEXT, false, BufferUtil.toBuffer(fourByteUtf8Bytes, 0, 2)), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(textMessages.size(), is(0));
        assertThat(callbacks.size(), is(0));

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.CONTINUATION, true, BufferUtil.toBuffer(fourByteUtf8Bytes, 2, 1)), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(textMessages.size(), is(0));
        assertThat(callbacks.size(), is(0));

        FutureCallback finalCallback = callback;
        Exception e = assertThrows(ExecutionException.class, () -> finalCallback.get());
        assertThat(e.getCause(), instanceOf(BadPayloadException.class));

        assertThat(frames.size(), is(0));
    }

    @Test
    public void testBadUtf8Message()
    {
        FutureCallback callback;

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.TEXT, false, BufferUtil.toBuffer(nonUtf8Bytes)), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(textMessages.size(), is(0));
        assertThat(callbacks.size(), is(0));

        FutureCallback finalCallback = callback;
        Exception e = assertThrows(ExecutionException.class, () -> finalCallback.get());
        assertThat(e.getCause(), instanceOf(BadPayloadException.class));

        assertThat(frames.size(), is(0));
    }

    @Test
    public void testSplitBadUtf8Message()
    {
        FutureCallback callback;

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.TEXT, false, BufferUtil.toBuffer(nonUtf8Bytes, 0, 1)), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(textMessages.size(), is(0));
        assertThat(callbacks.size(), is(0));

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.CONTINUATION, true, BufferUtil.toBuffer(nonUtf8Bytes, 1, nonUtf8Bytes.length - 1)), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(textMessages.size(), is(0));
        assertThat(callbacks.size(), is(0));

        FutureCallback finalCallback = callback;
        Exception e = assertThrows(ExecutionException.class, () -> finalCallback.get());
        assertThat(e.getCause(), instanceOf(BadPayloadException.class));

        assertThat(frames.size(), is(0));
    }

    @Test
    public void testDemanding()
    {
        demanding = true;
        FutureCallback callback;

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.PING, true, "test"), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(demand, is(1));

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.TEXT, true, "test"), callback);
        assertThat(callback.isDone(), is(false));
        assertThat(textMessages.size(), is(1));
        assertThat(textMessages.get(0), is("test"));
        assertThat(callbacks.size(), is(1));
        callbacks.get(0).succeeded();
        assertThat(demand, is(1));

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.TEXT, false, "Hello"), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(textMessages.size(), is(1));
        assertThat(callbacks.size(), is(1));
        assertThat(demand, is(2));

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.CONTINUATION, false, " "), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(textMessages.size(), is(1));
        assertThat(callbacks.size(), is(1));
        assertThat(demand, is(3));

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.CONTINUATION, true, "World"), callback);
        assertThat(callback.isDone(), is(false));
        assertThat(textMessages.size(), is(2));
        assertThat(textMessages.get(1), is("Hello World"));
        assertThat(callbacks.size(), is(2));
        callbacks.get(1).succeeded();
        assertThat(callback.isDone(), is(true));
        FutureCallback finalCallback = callback;
        assertDoesNotThrow(() -> finalCallback.get());
        assertThat(demand, is(3));

        assertThat(frames.size(), is(0));
    }

    @Test
    public void testTextNotTooLarge()
    {
        FutureCallback callback;

        handler.setMaxTextMessageSize(4);

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.TEXT, true, "test"), callback);
        assertThat(callback.isDone(), is(false));
        assertThat(textMessages.size(), is(1));
        assertThat(textMessages.get(0), is("test"));
        assertThat(callbacks.size(), is(1));
        callbacks.get(0).succeeded();
        assertThat(callback.isDone(), is(true));
        assertDoesNotThrow(() -> callback.get());
    }

    @Test
    public void testTextTooLarge() throws Exception
    {
        FutureCallback callback;

        handler.setMaxTextMessageSize(4);
        handler.onOpen(coreSession, NOOP);

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.TEXT, true, "Testing"), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(textMessages.size(), is(0));
        assertThat(callbacks.size(), is(0));

        FutureCallback finalCallback = callback;
        Exception e = assertThrows(ExecutionException.class, () -> finalCallback.get());
        assertThat(e.getCause(), instanceOf(MessageTooLargeException.class));
    }

    @Test
    public void testSplitTextTooLarge() throws Exception
    {
        FutureCallback callback;

        handler.setMaxTextMessageSize(4);
        handler.onOpen(coreSession, NOOP);

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.TEXT, false, "123"), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(textMessages.size(), is(0));
        assertThat(callbacks.size(), is(0));
        FutureCallback finalCallback = callback;
        assertDoesNotThrow(() -> finalCallback.get());

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.CONTINUATION, false, "456"), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(textMessages.size(), is(0));
        assertThat(callbacks.size(), is(0));

        FutureCallback finalCallback1 = callback;
        Exception e = assertThrows(ExecutionException.class, () -> finalCallback1.get());
        assertThat(e.getCause(), instanceOf(MessageTooLargeException.class));
    }

    @Test
    public void testLargeBytesSmallCharsTooLarge()
    {
        FutureCallback callback;

        handler.setMaxTextMessageSize(4);

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.TEXT, false, BufferUtil.toBuffer(fourByteUtf8Bytes)), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(textMessages.size(), is(0));
        assertThat(callbacks.size(), is(0));
        FutureCallback finalCallback = callback;
        assertDoesNotThrow(() -> finalCallback.get());

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.TEXT, true, BufferUtil.toBuffer(fourByteUtf8Bytes)), callback);
        assertThat(callback.isDone(), is(false));
        assertThat(textMessages.size(), is(1));
        assertThat(textMessages.get(0), is(fourByteUtf8String + fourByteUtf8String));
        assertThat(callbacks.size(), is(1));
        callbacks.get(0).succeeded();
        assertThat(callback.isDone(), is(true));
        FutureCallback finalCallback1 = callback;
        assertDoesNotThrow(() -> finalCallback1.get());

    }

    @Test
    public void testSendText()
    {
        handler.sendText("Hello", NOOP, false);
        Frame frame = frames.get(0);
        assertThat(frame.getOpCode(), is(OpCode.TEXT));
        assertThat(frame.isFin(), is(true));
        assertThat(frame.getPayloadAsUTF8(), is("Hello"));
    }

    @Test
    public void testSendSplitText()
    {
        handler.sendText(NOOP, false, "Hello", " ", "World");
        Frame frame = frames.get(0);
        assertThat(frame.getOpCode(), is(OpCode.TEXT));
        assertThat(frame.isFin(), is(false));
        assertThat(frame.getPayloadAsUTF8(), is("Hello"));

        frame = frames.get(1);
        assertThat(frame.getOpCode(), is(OpCode.CONTINUATION));
        assertThat(frame.isFin(), is(false));
        assertThat(frame.getPayloadAsUTF8(), is(" "));

        frame = frames.get(2);
        assertThat(frame.getOpCode(), is(OpCode.CONTINUATION));
        assertThat(frame.isFin(), is(true));
        assertThat(frame.getPayloadAsUTF8(), is("World"));
    }

    @Test
    public void testOneFrameBinary()
    {
        FutureCallback callback;

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.BINARY, true, "test"), callback);
        assertThat(callback.isDone(), is(false));
        assertThat(binaryMessages.size(), is(1));
        assertThat(BufferUtil.toString(binaryMessages.get(0)), is("test"));
        assertThat(callbacks.size(), is(1));
        callbacks.get(0).succeeded();
        assertThat(callback.isDone(), is(true));
        assertDoesNotThrow(() -> callback.get());

        assertThat(frames.size(), is(0));
    }

    @Test
    public void testManyFrameBinary()
    {
        FutureCallback callback;

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.BINARY, false, "Hello"), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(binaryMessages.size(), is(0));
        assertThat(callbacks.size(), is(0));

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.CONTINUATION, false, " "), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(binaryMessages.size(), is(0));
        assertThat(callbacks.size(), is(0));

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.CONTINUATION, true, "World"), callback);
        assertThat(callback.isDone(), is(false));
        assertThat(binaryMessages.size(), is(1));
        assertThat(BufferUtil.toString(binaryMessages.get(0)), is("Hello World"));
        assertThat(callbacks.size(), is(1));
        callbacks.get(0).succeeded();
        assertThat(callback.isDone(), is(true));
        FutureCallback finalCallback = callback;
        assertDoesNotThrow(() -> finalCallback.get());

        assertThat(frames.size(), is(0));
    }

    @Test
    public void testDemandingBinary()
    {
        demanding = true;
        FutureCallback callback;

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.PING, true, "test"), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(demand, is(1));

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.BINARY, true, "test"), callback);
        assertThat(callback.isDone(), is(false));
        assertThat(binaryMessages.size(), is(1));
        assertThat(BufferUtil.toString(binaryMessages.get(0)), is("test"));
        assertThat(callbacks.size(), is(1));
        callbacks.get(0).succeeded();
        assertThat(demand, is(1));

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.BINARY, false, "Hello"), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(binaryMessages.size(), is(1));
        assertThat(callbacks.size(), is(1));
        assertThat(demand, is(2));

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.CONTINUATION, false, " "), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(binaryMessages.size(), is(1));
        assertThat(callbacks.size(), is(1));
        assertThat(demand, is(3));

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.CONTINUATION, true, "World"), callback);
        assertThat(callback.isDone(), is(false));
        assertThat(binaryMessages.size(), is(2));
        assertThat(BufferUtil.toString(binaryMessages.get(1)), is("Hello World"));
        assertThat(callbacks.size(), is(2));
        callbacks.get(1).succeeded();
        assertThat(callback.isDone(), is(true));
        FutureCallback finalCallback = callback;
        assertDoesNotThrow(() -> finalCallback.get());
        assertThat(demand, is(3));

        assertThat(frames.size(), is(0));
    }

    @Test
    public void testBinaryNotTooLarge()
    {
        FutureCallback callback;

        handler.setMaxBinaryMessageSize(4);

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.BINARY, true, "test"), callback);
        assertThat(callback.isDone(), is(false));
        assertThat(binaryMessages.size(), is(1));
        assertThat(BufferUtil.toString(binaryMessages.get(0)), is("test"));
        assertThat(callbacks.size(), is(1));
        callbacks.get(0).succeeded();
        assertThat(callback.isDone(), is(true));
        assertDoesNotThrow(() -> callback.get());
    }

    @Test
    public void testBinaryTooLarge() throws Exception
    {
        FutureCallback callback;

        handler.setMaxBinaryMessageSize(4);
        handler.onOpen(coreSession, NOOP);

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.BINARY, true, "Testing"), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(binaryMessages.size(), is(0));
        assertThat(callbacks.size(), is(0));

        FutureCallback finalCallback = callback;
        Throwable e = assertThrows(ExecutionException.class, () -> finalCallback.get());
        assertThat(e.getCause(), instanceOf(MessageTooLargeException.class));
    }

    @Test
    public void testSplitBinaryTooLarge() throws Exception
    {
        FutureCallback callback;

        handler.setMaxBinaryMessageSize(4);
        handler.onOpen(coreSession, NOOP);

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.BINARY, false, "123"), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(binaryMessages.size(), is(0));
        assertThat(callbacks.size(), is(0));
        FutureCallback finalCallback = callback;
        assertDoesNotThrow(() -> finalCallback.get());

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.CONTINUATION, false, "456"), callback);
        assertThat(callback.isDone(), is(true));
        assertThat(binaryMessages.size(), is(0));
        assertThat(callbacks.size(), is(0));

        FutureCallback finalCallback1 = callback;
        Exception e = assertThrows(ExecutionException.class, () -> finalCallback1.get());
        assertThat(e.getCause(), instanceOf(MessageTooLargeException.class));
    }

    @Test
    public void testSendBinary()
    {
        handler.sendBinary(toBuffer("Hello"), NOOP, false);
        Frame frame = frames.get(0);
        assertThat(frame.getOpCode(), is(OpCode.BINARY));
        assertThat(frame.isFin(), is(true));
        assertThat(frame.getPayloadAsUTF8(), is("Hello"));
    }

    @Test
    public void testSendSplitBinary()
    {
        handler.sendBinary(NOOP, false, toBuffer("Hello"), toBuffer(" "), toBuffer("World"));
        Frame frame = frames.get(0);
        assertThat(frame.getOpCode(), is(OpCode.BINARY));
        assertThat(frame.isFin(), is(false));
        assertThat(frame.getPayloadAsUTF8(), is("Hello"));

        frame = frames.get(1);
        assertThat(frame.getOpCode(), is(OpCode.CONTINUATION));
        assertThat(frame.isFin(), is(false));
        assertThat(frame.getPayloadAsUTF8(), is(" "));

        frame = frames.get(2);
        assertThat(frame.getOpCode(), is(OpCode.CONTINUATION));
        assertThat(frame.isFin(), is(true));
        assertThat(frame.getPayloadAsUTF8(), is("World"));
    }

    @Test
    public void testTextNotImplemented() throws Exception
    {
        handler = new MessageHandler()
        {
            @Override
            protected void onBinary(ByteBuffer message, Callback callback)
            {
                binaryMessages.add(message);
                callbacks.add(callback);
            }
        };

        handler.onOpen(coreSession, NOOP);

        FutureCallback callback;

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.TEXT, true, "test"), callback);
        assertThat(callback.isDone(), is(true));

        Exception e = assertThrows(ExecutionException.class, () -> callback.get());
        assertThat(e.getCause(), instanceOf(BadPayloadException.class));

        assertThat(textMessages.size(), is(0));
        assertThat(frames.size(), is(0));
    }

    @Test
    public void testBinaryNotImplemented() throws Exception
    {
        handler = new MessageHandler()
        {
            @Override
            protected void onText(String message, Callback callback)
            {
                textMessages.add(message);
                callbacks.add(callback);
            }
        };

        handler.onOpen(coreSession, NOOP);

        FutureCallback callback;

        callback = new FutureCallback();
        handler.onFrame(new Frame(OpCode.BINARY, true, "test"), callback);
        assertThat(callback.isDone(), is(true));

        Exception e = assertThrows(ExecutionException.class, () -> callback.get());
        assertThat(e.getCause(), instanceOf(BadPayloadException.class));

        assertThat(textMessages.size(), is(0));
        assertThat(frames.size(), is(0));
    }
}
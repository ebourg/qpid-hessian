/**
 * Copyright 2010 Emmanuel Bourg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.qpid.contrib.hessian;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import com.caucho.hessian.client.HessianRuntimeException;
import com.caucho.hessian.io.AbstractHessianInput;
import com.caucho.hessian.io.AbstractHessianOutput;
import com.caucho.hessian.io.HessianProtocolException;
import com.caucho.services.server.AbstractSkeleton;
import org.apache.qpid.transport.Connection;
import org.apache.qpid.transport.DeliveryProperties;
import org.apache.qpid.transport.Header;
import org.apache.qpid.transport.MessageAcceptMode;
import org.apache.qpid.transport.MessageAcquireMode;
import org.apache.qpid.transport.MessageCreditUnit;
import org.apache.qpid.transport.MessageProperties;
import org.apache.qpid.transport.MessageTransfer;
import org.apache.qpid.transport.Option;
import org.apache.qpid.transport.QueueQueryResult;
import org.apache.qpid.transport.ReplyTo;
import org.apache.qpid.transport.Session;
import org.apache.qpid.transport.SessionException;
import org.apache.qpid.transport.SessionListener;

/**
 * Proxy implementation for Hessian clients. Applications will generally
 * use {@link AMQPHessianProxyFactory} to create proxy clients.
 * 
 * @author Emmanuel Bourg
 * @author Scott Ferguson
 */
public class AMQPHessianProxy implements InvocationHandler
{
    private AMQPHessianProxyFactory _factory;

    AMQPHessianProxy(AMQPHessianProxyFactory factory)
    {
        _factory = factory;
    }

    /**
     * Handles the object invocation.
     *
     * @param proxy  the proxy object to invoke
     * @param method the method to call
     * @param args   the arguments to the proxy object
     */
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
    {
        String methodName = method.getName();
        Class[] params = method.getParameterTypes();

        // equals and hashCode are special cased
        if (methodName.equals("equals") && params.length == 1 && params[0].equals(Object.class))
        {
            Object value = args[0];
            if (value == null || !Proxy.isProxyClass(value.getClass()))
            {
                return Boolean.FALSE;
            }

            AMQPHessianProxy handler = (AMQPHessianProxy) Proxy.getInvocationHandler(value);

            return _factory.equals(handler._factory);
        }
        else if (methodName.equals("hashCode") && params.length == 0)
        {
            return _factory.hashCode();
        }
        else if (methodName.equals("toString") && params.length == 0)
        {
            return "[HessianProxy " + proxy.getClass() + "]";
        }

        Session session = openSession();

        try
        {
            Future<MessageTransfer> response = sendRequest(session, method, args);
            
            MessageTransfer message = _factory.getReadTimeout() > 0 ? response.get(_factory.getReadTimeout(), TimeUnit.MILLISECONDS) : response.get();
            MessageProperties props = message.getHeader().get(MessageProperties.class);
            boolean compressed = "deflate".equals(props.getContentEncoding());
            
            AbstractHessianInput in;
            
            InputStream is = new ByteArrayInputStream(message.getBodyBytes());
            if (compressed) {
                is = new InflaterInputStream(is, new Inflater(true));
            }
            
            int code = is.read();

            if (code == 'H')
            {
                int major = is.read();
                int minor = is.read();

                in = _factory.getHessian2Input(is);

                return in.readReply(method.getReturnType());
            }
            else if (code == 'r')
            {
                int major = is.read();
                int minor = is.read();

                in = _factory.getHessianInput(is);

                in.startReplyBody();

                Object value = in.readObject(method.getReturnType());

                in.completeReply();

                return value;
            }
            else
            {
                throw new HessianProtocolException("'" + (char) code + "' is an unknown code");
            }
        }
        catch (HessianProtocolException e)
        {
            throw new HessianRuntimeException(e);
        }
        finally
        {
            session.close();
            session.getConnection().close();
        }
    }

    private Session openSession() throws IOException
    {
        Connection conn = _factory.openConnection();

        Session session = conn.createSession(0);
        session.setAutoSync(true);

        return session;
    }

    /**
     * Check if the specified queue exists.
     * 
     * @param session
     * @param name
     */
    private boolean checkQueue(Session session, String name)
    {
        org.apache.qpid.transport.Future<QueueQueryResult> future = session.queueQuery(name);
        QueueQueryResult result = future.get();
        return result.hasQueue();
    }

    private Future<MessageTransfer> sendRequest(Session session, Method method, Object[] args) throws IOException
    {
        // check if the request queue exists
        String requestQueue = getRequestQueue(method.getDeclaringClass());
        org.apache.qpid.transport.Future<QueueQueryResult> future = session.queueQuery(requestQueue);
        QueueQueryResult result = future.get();

        if (!checkQueue(session, getRequestQueue(method.getDeclaringClass())))
        {
            throw new HessianRuntimeException("Service queue not found: " + requestQueue);
        }
        
        // create the temporary queue for the response
        String replyQueue = "temp." + UUID.randomUUID();
        createQueue(session, replyQueue);

        byte[] payload = createRequestBody(method, args);

        DeliveryProperties deliveryProps = new DeliveryProperties();
        deliveryProps.setRoutingKey(requestQueue);

        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setReplyTo(new ReplyTo("amq.direct", replyQueue));
        messageProperties.setContentType("x-application/hessian");
        if (_factory.isCompressed())
        {
            messageProperties.setContentEncoding("deflate");
        }

        ResponseListener listener = new ResponseListener();
        session.setSessionListener(listener);
        
        session.messageTransfer("amq.direct", MessageAcceptMode.NONE, MessageAcquireMode.PRE_ACQUIRED, new Header(deliveryProps, messageProperties), payload);
        session.sync();

        return listener.getResponse();
    }

    /**
     * Return the name of the request queue for the service.
     */
    private String getRequestQueue(Class cls)
    {
        String requestQueue = cls.getSimpleName();
        if (_factory.getQueuePrefix() != null)
        {
            requestQueue = _factory.getQueuePrefix() + "." + requestQueue;
        }
        
        return requestQueue;
    }

    /**
     * Create an exclusive queue.
     * 
     * @param session
     * @param name    the name of the queue
     */
    private void createQueue(Session session, String name)
    {
        session.queueDeclare(name, null, null, Option.EXCLUSIVE, Option.AUTO_DELETE);
        session.exchangeBind(name, "amq.direct", name, null);
        session.messageSubscribe(name, name, MessageAcceptMode.NONE, MessageAcquireMode.PRE_ACQUIRED, null, 0, null);

        // issue credits
        session.messageFlow(name, MessageCreditUnit.BYTE, Session.UNLIMITED_CREDIT);
        session.messageFlow(name, MessageCreditUnit.MESSAGE, Session.UNLIMITED_CREDIT);

        session.sync();
    }

    private static class ResponseListener implements SessionListener
    {
        boolean done = false;

        private CompletableFuture<MessageTransfer> response = new CompletableFuture<>();

        public Future<MessageTransfer> getResponse()
        {
            return response;
        }

        public void opened(Session session) { }
        public void resumed(Session session) { }
        public void exception(Session session, SessionException exception) { }
        public void closed(Session session) { }

        public void message(Session session, MessageTransfer xfr)
        {
            if (!response.isDone())
            {
                session.setSessionListener(null);
                response.complete(xfr);
                done = true;
            }
            
            session.processed(xfr);
        }
    }

    private byte[] createRequestBody(Method method, Object[] args) throws IOException
    {
        String methodName = method.getName();
        
        if (_factory.isOverloadEnabled() && args != null && args.length > 0)
        {
            methodName = AbstractSkeleton.mangleName(method, false);
        }
        
        ByteArrayOutputStream payload = new ByteArrayOutputStream(256);
        OutputStream os;
        if (_factory.isCompressed())
        {
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
            os = new DeflaterOutputStream(payload, deflater);
        }
        else
        {
            os = payload;
        }
        
        AbstractHessianOutput out = _factory.getHessianOutput(os);
        
        out.call(methodName, args);
        if (os instanceof DeflaterOutputStream)
        {
            ((DeflaterOutputStream) os).finish();
        }
        out.flush();
        
        return payload.toByteArray();
    }
}

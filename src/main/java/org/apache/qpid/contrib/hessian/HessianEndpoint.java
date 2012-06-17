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
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import com.caucho.hessian.io.SerializerFactory;
import com.caucho.hessian.server.HessianSkeleton;
import org.apache.qpid.transport.Connection;
import org.apache.qpid.transport.DeliveryProperties;
import org.apache.qpid.transport.Header;
import org.apache.qpid.transport.MessageAcceptMode;
import org.apache.qpid.transport.MessageAcquireMode;
import org.apache.qpid.transport.MessageCreditUnit;
import org.apache.qpid.transport.MessageProperties;
import org.apache.qpid.transport.MessageTransfer;
import org.apache.qpid.transport.Option;
import org.apache.qpid.transport.ReplyTo;
import org.apache.qpid.transport.Session;
import org.apache.qpid.transport.SessionException;
import org.apache.qpid.transport.SessionListener;

/**
 * Endpoint for serving Hessian services.
 * 
 * This class is derived from {@link com.caucho.hessian.server.HessianServlet}. 
 * 
 * @author Emmanuel Bourg
 */
public class HessianEndpoint
{
    private Class serviceAPI;
    private Object serviceImpl;
    private SerializerFactory serializerFactory;

    /** The prefix of the queue created to receive the hessian requests */
    private String queuePrefix;

    /**
     * Creates an hessian endpoint.
     */
    public HessianEndpoint()
    {
        // Initialize the service
        setServiceAPI(findRemoteAPI(getClass()));
        setServiceImpl(this);
    }

    /**
     * Creates an hessian endpoint for the specified service.
     * 
     * @param serviceImpl The remote object to be exposed by the endpoint
     */
    public HessianEndpoint(Object serviceImpl)
    {
        // Initialize the service
        setServiceAPI(findRemoteAPI(serviceImpl.getClass()));
        setServiceImpl(serviceImpl);
    }

    /**
     * Specifies the interface of the service.
     */
    public void setServiceAPI(Class serviceAPI)
    {
        this.serviceAPI = serviceAPI;
    }

    /**
     * Specifies the object implementing the service.
     */
    public void setServiceImpl(Object serviceImpl)
    {
        this.serviceImpl = serviceImpl;
        
    }

    /**
     * Sets the serializer factory.
     */
    public void setSerializerFactory(SerializerFactory factory)
    {
        serializerFactory = factory;
    }

    /**
     * Gets the serializer factory.
     */
    public SerializerFactory getSerializerFactory()
    {
        if (serializerFactory == null)
        {
            serializerFactory = new SerializerFactory();
        }

        return serializerFactory;
    }

    /**
     * Returns the prefix of the queue created to receive the hessian requests.
     */
    public String getQueuePrefix()
    {
        return queuePrefix;
    }

    /**
     * Sets the prefix of the queue created to receive the hessian requests.
     */
    public void setQueuePrefix(String prefix)
    {
        queuePrefix = prefix;
    }

    /**
     * Sets the serializer send collection java type.
     */
    public void setSendCollectionType(boolean sendType)
    {
        getSerializerFactory().setSendCollectionType(sendType);
    }

    private Class findRemoteAPI(Class implClass)
    {
        if (implClass == null)
        {
            return null;
        }
        
        Class[] interfaces = implClass.getInterfaces();

        if (interfaces.length == 1)
        {
            return interfaces[0];
        }

        return findRemoteAPI(implClass.getSuperclass());
    }

    /**
     * Return the name of the request queue for the service.
     * The queue name is based on the class of the API implemented.
     */
    private String getRequestQueue(Class cls)
    {
        String requestQueue = cls.getSimpleName();
        if (queuePrefix != null)
        {
            requestQueue = queuePrefix + "." + requestQueue;
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

    /**
     * Starts the endpoint on the connection specified. A session bound to a
     * dedicated queue is created on the connection and a listener is installed
     * to respond to hessian requests. The endpoint is stopped by closing the
     * session returned.
     * 
     * @param conn The AMQP connection
     * @return the AMQP session handling the requests for the endpoint
     */
    public Session run(Connection conn)
    {
        Session session = conn.createSession(0);
        
        // create the queue receiving the requests 
        createQueue(session, getRequestQueue(serviceAPI));
        
        session.setSessionListener(new SessionListener()
        {
            public void opened(Session session) {}
            public void exception(Session session, SessionException exception) {}
            public void closed(Session session) {}

            public void resumed(final Session session)
            {
                new Thread("Hessian/AMQP Resume Handler [" + getRequestQueue(serviceAPI) + "]")
                {
                    public void run()
                    {
                        // recreate the queue
                        createQueue(session, getRequestQueue(serviceAPI));
                    }
                }.start();
            }

            public void message(final Session session, final MessageTransfer xfr)
            {
                // send the response in a separate thread, otherwise the call to session.messageTransfer() blocks
                new Thread("Hessian/AMQP Responder [" + getRequestQueue(serviceAPI) + "]")
                {
                    public void run()
                    {
                        try
                        {
                            sendReponse(session, xfr);
                        }
                        catch (IOException e)
                        {
                            e.printStackTrace();
                        }
                        finally
                        {
                            session.processed(xfr);
                        }
                    }
                }.start();
            }

            private void sendReponse(Session session, MessageTransfer xfr) throws IOException
            {
                MessageProperties props = xfr.getHeader().get(MessageProperties.class);
                ReplyTo from = props.getReplyTo();
                boolean compressed = "deflate".equals(props.getContentEncoding());

                DeliveryProperties deliveryProps = new DeliveryProperties();
                deliveryProps.setRoutingKey(from.getRoutingKey());

                byte[] response = createResponseBody(xfr.getBodyBytes(), compressed);

                MessageProperties messageProperties = new MessageProperties();
                messageProperties.setContentType("x-application/hessian");
                if (compressed)
                {
                    messageProperties.setContentEncoding("deflate");
                }

                session.messageTransfer("amq.direct", MessageAcceptMode.NONE, MessageAcquireMode.PRE_ACQUIRED, new Header(deliveryProps, messageProperties), response);
                session.sync();
            }
        });
        
        return session;
    }

    /**
     * Execute a request.
     */
    private byte[] createResponseBody(byte[] request, boolean compressed) throws IOException
    {
        try
        {
            InputStream in = new ByteArrayInputStream(request);
            if (compressed)
            {
                in = new InflaterInputStream(new ByteArrayInputStream(request), new Inflater(true));
            }
            
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            OutputStream out;
            if (compressed)
            {
                Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
                out = new DeflaterOutputStream(bout, deflater);
            }
            else
            {
                out = bout;
            }
            
            HessianSkeleton skeleton = new HessianSkeleton(serviceImpl, serviceAPI);
            skeleton.invoke(in, out, getSerializerFactory());
            
            if (out instanceof DeflaterOutputStream)
            {
                ((DeflaterOutputStream) out).finish();
            }
            out.flush();
            out.close();

            return bout.toByteArray();
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Throwable e)
        {
            throw new RuntimeException(e);
        }
    }
}

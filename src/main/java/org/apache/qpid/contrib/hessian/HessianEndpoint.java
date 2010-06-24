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
 * @version $Revision$, $Date$
 */
public class HessianEndpoint
{
    private HessianSkeleton skeleton;
    private SerializerFactory serializerFactory;
    private String queuePrefix;

    public HessianEndpoint()
    {
        // Initialize the service
        skeleton = new HessianSkeleton(this, findRemoteAPI(getClass()));
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

    public String getQueuePrefix()
    {
        return queuePrefix;
    }

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
        Class[] interfaces = implClass.getInterfaces();

        if (interfaces.length == 1)
        {
            return interfaces[0];
        }

        return findRemoteAPI(implClass.getSuperclass());
    }

    /**
     * Creates the queue receiving the requests. The queue name is based on the
     * class of the API implemented.
     * 
     * @param session
     */
    private void createRequestQueue(Session session)
    {
        String requestQueue = findRemoteAPI(getClass()).getSimpleName();
        if (queuePrefix != null)
        {
            requestQueue = queuePrefix + "." + requestQueue;
        }
        
        session.queueDeclare(requestQueue, null, null, Option.EXCLUSIVE, Option.AUTO_DELETE);
        session.exchangeBind(requestQueue, "amq.direct", requestQueue, null);
        session.messageSubscribe(requestQueue, requestQueue, MessageAcceptMode.NONE, MessageAcquireMode.PRE_ACQUIRED, null, 0, null);
        
        // issue credits
        session.messageFlow(requestQueue, MessageCreditUnit.BYTE, Session.UNLIMITED_CREDIT);
        session.messageFlow(requestQueue, MessageCreditUnit.MESSAGE, Session.UNLIMITED_CREDIT);
        
        session.sync();
    }
    
    public void run(Connection conn)
    {
        final Session session = conn.createSession(0);
        
        createRequestQueue(session);
        
        session.setSessionListener(new SessionListener()
        {
            public void opened(Session session) {}
            public void resumed(Session session) {}
            public void exception(Session session, SessionException exception) {}
            public void closed(Session session) {}
            
            public void message(final Session session, final MessageTransfer xfr)
            {
                // send the response in a separate thread, otherwise the call to session.messageTransfer() blocks
                new Thread()
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
                messageProperties.setContentType("application/x-hessian");
                if (compressed)
                {
                    messageProperties.setContentEncoding("deflate");
                }

                session.messageTransfer("amq.direct", MessageAcceptMode.NONE, MessageAcquireMode.PRE_ACQUIRED, new Header(deliveryProps, messageProperties), response);
                session.sync();
            }
        });
    }

    /**
     * Execute a request.
     */
    public byte[] createResponseBody(byte[] request, boolean compressed) throws IOException
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

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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.caucho.hessian.io.AbstractHessianInput;
import com.caucho.hessian.io.AbstractHessianOutput;
import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;
import com.caucho.hessian.io.HessianDebugInputStream;
import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import com.caucho.hessian.io.HessianRemoteResolver;
import com.caucho.hessian.io.SerializerFactory;
import com.caucho.services.client.ServiceProxyFactory;
import org.apache.qpid.transport.Connection;

/**
 * Factory for creating Hessian client stubs. The returned stub will
 * call the remote object for all methods.
 *
 * <pre>
 * HelloHome hello = (HelloHome) factory.create(HelloHome.class, null);
 * </pre>
 *
 * After creation, the stub can be like a regular Java class. Because
 * it makes remote calls, it can throw more exceptions than a Java class.
 * In particular, it may throw protocol exceptions.
 * 
 * This class is derived from {@link com.caucho.hessian.client.HessianProxyFactory}. 
 * 
 * @author Emmanuel Bourg
 * @author Scott Ferguson
 */
public class AMQPHessianProxyFactory /*implements ServiceProxyFactory*/
{
    private SerializerFactory _serializerFactory;
    private HessianRemoteResolver _resolver;

    private String user;
    private String password;
    private String hostname;
    private String virtualhost;
    private int port = 5672;
    
    private String queuePrefix;

    private boolean isOverloadEnabled = false;

    private boolean isHessian2Reply = true;
    private boolean isHessian2Request = true;

    private boolean debug = false;

    private long readTimeout = -1;
    private long connectTimeout = -1;
    
    private boolean compressed;

    /**
     * Creates the new proxy factory.
     */
    public AMQPHessianProxyFactory()
    {
        _resolver = new HessianRemoteResolver()
        {
            public Object lookup(String type, String url) throws IOException
            {
                ClassLoader loader = Thread.currentThread().getContextClassLoader();

                try
                {
                    Class<?> api = Class.forName(type, false, loader);
                    return create(api, url);
                }
                catch (Exception e)
                {
                    throw new IOException(String.valueOf(e));
                }
            }
        };
    }

    /**
     * Sets the user connecting to the AMQP server.
     */
    public void setUser(String user)
    {
        this.user = user;
    }

    /**
     * Sets the password of the user connecting to the AMQP server.
     */
    public void setPassword(String password)
    {
        this.password = password;
    }

    /**
     * Returns the prefix of the queue that receives the hessian requests.
     */
    public String getQueuePrefix()
    {
        return queuePrefix;
    }

    /**
     * Sets the prefix of the queue that receives the hessian requests.
     */
    public void setQueuePrefix(String queuePrefix)
    {
        this.queuePrefix = queuePrefix;
    }

    /**
     * Sets the debug mode.
     */
    public void setDebug(boolean isDebug)
    {
        this.debug = isDebug;
    }

    /**
     * Gets the debug mode.
     */
    public boolean isDebug()
    {
        return debug;
    }

    /**
     * Returns true if overloaded methods are allowed (using mangling)
     */
    public boolean isOverloadEnabled()
    {
        return isOverloadEnabled;
    }

    /**
     * set true if overloaded methods are allowed (using mangling)
     */
    public void setOverloadEnabled(boolean isOverloadEnabled)
    {
        this.isOverloadEnabled = isOverloadEnabled;
    }

    /**
     * Returns the socket timeout on requests in milliseconds.
     */
    public long getReadTimeout()
    {
        return readTimeout;
    }

    /**
     * Sets the socket timeout on requests in milliseconds.
     */
    public void setReadTimeout(long timeout)
    {
        readTimeout = timeout;
    }

    /**
     * Returns the socket timeout on connect in milliseconds.
     */
    public long getConnectTimeout()
    {
        return connectTimeout;
    }

    /**
     * Sets the socket timeout on connect in milliseconds.
     */
    public void setConnectTimeout(long _connecTimeout)
    {
        this.connectTimeout = _connecTimeout;
    }

    /**
     * Indicates if the requests/responses should be compressed.
     */
    public boolean isCompressed()
    {
        return compressed;
    }

    /**
     * Specifies if the requests/responses should be compressed.
     */
    public void setCompressed(boolean compressed) 
    {
        this.compressed = compressed;
    }

    /**
     * True if the proxy can read Hessian 2 responses.
     */
    public void setHessian2Reply(boolean isHessian2)
    {
        isHessian2Reply = isHessian2;
    }

    /**
     * True if the proxy should send Hessian 2 requests.
     */
    public void setHessian2Request(boolean isHessian2)
    {
        isHessian2Request = isHessian2;

        if (isHessian2)
        {
            isHessian2Reply = true;
        }
    }

    /**
     * Returns the remote resolver.
     */
    public HessianRemoteResolver getRemoteResolver()
    {
        return _resolver;
    }

    /**
     * Sets the serializer factory.
     */
    public void setSerializerFactory(SerializerFactory factory)
    {
        _serializerFactory = factory;
    }

    /**
     * Gets the serializer factory.
     */
    public SerializerFactory getSerializerFactory()
    {
        if (_serializerFactory == null)
        {
            _serializerFactory = new SerializerFactory();
        }

        return _serializerFactory;
    }

    /**
     * Creates the URL connection.
     */
    protected Connection openConnection(URL url) throws IOException
    {
        Connection conn = new Connection();
        conn.connect(hostname, port, virtualhost, user, password);

        return conn;
    }

    /**
     * Creates a new proxy with the specified URL.  The returned object
     * is a proxy with the interface specified by api.
     *
     * <pre>
     * String url = "amqp://user:password@localhost:5672/vhost/queue");
     * HelloHome hello = (HelloHome) factory.create(HelloHome.class, url);
     * </pre>
     *
     * @param api the interface the proxy class needs to implement
     * @param urlName the URL where the client object is located.
     * @return a proxy to the object with the specified interface.
     */
    public <T> T create(Class<T> api, String urlName) throws MalformedURLException
    {
        return create(api, urlName, api.getClassLoader());
    }

    /**
     * Creates a new proxy with the specified URL.  The returned object
     * is a proxy with the interface specified by api.
     *
     * <pre>
     * String url = "amqp://user:password@localhost:5672/vhost/queue");
     * HelloHome hello = (HelloHome) factory.create(HelloHome.class, url);
     * </pre>
     *
     * @param api the interface the proxy class needs to implement
     * @param urlName the URL where the client object is located.
     * @return a proxy to the object with the specified interface.
     */
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> api, String urlName, ClassLoader loader) throws MalformedURLException
    {
        try
        {
            URI uri = new URI(urlName);

            hostname = uri.getHost();
            if (uri.getPort() != -1)
            {
                port = uri.getPort();
            }

            String userinfo = uri.getUserInfo();
            if (userinfo != null)
            {
                String[] parts = userinfo.split(":");
                user = parts[0];
                if (parts.length > 0)
                {
                    password = parts[1];
                }
            }

            Pattern pattern = Pattern.compile("/([^/]+)(/(.*))?");
            Matcher matcher = pattern.matcher(uri.getPath());
            if (matcher.matches())
            {
                virtualhost = matcher.group(1);
                if (matcher.groupCount() > 1)
                {
                    queuePrefix = matcher.group(3);
                }
            }
        }
        catch (URISyntaxException e)
        {
            throw (MalformedURLException) new MalformedURLException().initCause(e);
        }
        
        AMQPHessianProxy handler = new AMQPHessianProxy(this);

        return (T) Proxy.newProxyInstance(loader, new Class[]{api}, handler);
    }

    AbstractHessianInput getHessianInput(InputStream is)
    {
        return getHessian2Input(is);
    }

    AbstractHessianInput getHessian1Input(InputStream is)
    {
        AbstractHessianInput in;

        if (debug)
        {
            is = new HessianDebugInputStream(is, new PrintWriter(System.out));
        }

        in = new HessianInput(is);

        in.setRemoteResolver(getRemoteResolver());

        in.setSerializerFactory(getSerializerFactory());

        return in;
    }

    AbstractHessianInput getHessian2Input(InputStream is)
    {
        AbstractHessianInput in;

        if (debug)
        {
            is = new HessianDebugInputStream(is, new PrintWriter(System.out));
        }

        in = new Hessian2Input(is);

        in.setRemoteResolver(getRemoteResolver());

        in.setSerializerFactory(getSerializerFactory());

        return in;
    }

    AbstractHessianOutput getHessianOutput(OutputStream os)
    {
        AbstractHessianOutput out;

        if (isHessian2Request)
        {
            out = new Hessian2Output(os);
        }
        else
        {
            HessianOutput out1 = new HessianOutput(os);
            out = out1;

            if (isHessian2Reply)
            {
                out1.setVersion(2);
            }
        }

        out.setSerializerFactory(getSerializerFactory());

        return out;
    }
}


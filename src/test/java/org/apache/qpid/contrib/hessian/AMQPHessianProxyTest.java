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

import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.TimeoutException;

import com.caucho.hessian.client.HessianRuntimeException;
import org.apache.qpid.contrib.hessian.service.EchoService;
import org.apache.qpid.contrib.hessian.service.EchoServiceEndpoint;
import org.apache.qpid.contrib.hessian.service.FailingService;
import org.apache.qpid.contrib.hessian.service.FailingServiceEndpoint;
import org.apache.qpid.transport.Connection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class AMQPHessianProxyTest
{
    protected final String HOSTNAME = "localhost";
    protected final int PORT = 5672;
    protected final String VIRTUALHOST = "test";
    protected final String USERNAME = "guest";
    protected final String PASSWORD = "guest";

    protected Connection connection;

    @Before
    public void setUp()
    {
        connection = new Connection();
        connection.connect(HOSTNAME, PORT, VIRTUALHOST, USERNAME, PASSWORD);
    }

    @After
    public void tearDown()
    {
        connection.close();
    }

    protected void startEndpoint()
    {
        EchoServiceEndpoint endpoint = new EchoServiceEndpoint();
        endpoint.run(connection);
    }

    @Test
    public void testEcho() throws Exception
    {
        startEndpoint();
        
        AMQPHessianProxyFactory factory = new AMQPHessianProxyFactory();
        factory.setReadTimeout(5000);

        EchoService service = factory.create(EchoService.class, "amqp://" + USERNAME + ":" + PASSWORD + "@" + HOSTNAME + ":" + PORT + "/" + VIRTUALHOST);
        String message = "Hello Hessian!";

        assertEquals(message, service.echo(message));
        assertEquals(message, service.echo(message));
    }

    @Test
    public void testException() throws Exception
    {
        startEndpoint();
        
        AMQPHessianProxyFactory factory = new AMQPHessianProxyFactory();
        factory.setReadTimeout(5000);
        factory.setCompressed(true);

        EchoService service = factory.create(EchoService.class, "amqp://" + USERNAME + ":" + PASSWORD + "@" + HOSTNAME + ":" + PORT + "/" + VIRTUALHOST);
        String message = "Hello Hessian!";

        try
        {
            service.exception(message);
            fail("No exception thrown");
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            assertEquals("Exception message", message, e.getMessage());
        }
    }

    @Test
    public void testResuming() throws Exception
    {
        startEndpoint();
        
        // close and reconnect to the same server
        connection.close();
        connection.connect(connection.getConnectionSettings());
        connection.resume();
        
        AMQPHessianProxyFactory factory = new AMQPHessianProxyFactory();
        factory.setReadTimeout(5000);
        
        EchoService service = factory.create(EchoService.class, "amqp://" + USERNAME + ":" + PASSWORD + "@" + HOSTNAME + ":" + PORT + "/" + VIRTUALHOST);
        String message = "Hello again Hessian!";

        assertEquals(message, service.echo(message));
    }

    @Test
    public void testTimeout() throws Exception
    {
        FailingServiceEndpoint endpoint = new FailingServiceEndpoint();
        endpoint.run(connection);
        
        AMQPHessianProxyFactory factory = new AMQPHessianProxyFactory();
        factory.setReadTimeout(3000);
        
        FailingService service = factory.create(FailingService.class, "amqp://" + USERNAME + ":" + PASSWORD + "@" + HOSTNAME + ":" + PORT + "/" + VIRTUALHOST);
        
        try
        {
            service.timeout(5000);
            fail("UndeclaredThrowableException expected");
        }
        catch (UndeclaredThrowableException e)
        {
            Throwable cause = e.getCause();
            assertTrue(cause instanceof TimeoutException);
        }
    }

    @Test
    public void testSerializationError() throws Exception
    {
        FailingServiceEndpoint endpoint = new FailingServiceEndpoint();
        endpoint.run(connection);
        
        AMQPHessianProxyFactory factory = new AMQPHessianProxyFactory();
        factory.setReadTimeout(5000);
        
        FailingService service = factory.create(FailingService.class, "amqp://" + USERNAME + ":" + PASSWORD + "@" + HOSTNAME + ":" + PORT + "/" + VIRTUALHOST);
        
        try
        {
            service.getNotSerializable();
            fail("IllegalStateException expected");
        }
        catch (IllegalStateException e)
        {
            assertTrue(e.getMessage().contains("must implement java.io.Serializable"));
        }
    }

    @Test
    public void testMissingEndpoint() throws Exception
    {
        AMQPHessianProxyFactory factory = new AMQPHessianProxyFactory();
        factory.setReadTimeout(5000);

        EchoService service = factory.create(EchoService.class, "amqp://" + USERNAME + ":" + PASSWORD + "@" + HOSTNAME + ":" + PORT + "/" + VIRTUALHOST);

        try
        {
            service.echo("Hello Hessian!");
            fail("HessianRuntimeException expected");
        }
        catch (HessianRuntimeException e)
        {
            assertEquals("Service queue not found: EchoService",  e.getMessage());
        }
    }
}

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

import junit.framework.TestCase;
import org.apache.qpid.transport.Connection;

/**
 * @author Emmanuel Bourg
 * @version $Revision$, $Date$
 */
public class AMQPHessianProxyTest extends TestCase
{
    private String HOSTNAME = "localhost";

    private Connection connection;

    protected void setUp() throws Exception
    {
        connection = new Connection();
        connection.connect(HOSTNAME, 5672, "test", "guest", "guest");
    }

    protected void tearDown() throws Exception
    {
        connection.close();
    }

    public void testEcho() throws Exception
    {
        EchoServiceEndpoint endpoint = new EchoServiceEndpoint();
        endpoint.run(connection);

        AMQPHessianProxyFactory factory = new AMQPHessianProxyFactory();
        factory.setReadTimeout(5000);

        EchoService service = (EchoService) factory.create(EchoService.class, "qpid://guest:guest@" + HOSTNAME + "/test");
        String message = "Hello Hessian!";

        assertEquals(message, service.echo(message));
        assertEquals(message, service.echo(message));
    }

    public void testException() throws Exception
    {
        EchoServiceEndpoint endpoint = new EchoServiceEndpoint();
        endpoint.run(connection);

        AMQPHessianProxyFactory factory = new AMQPHessianProxyFactory();
        factory.setReadTimeout(5000);
        factory.setCompressed(true);

        EchoService service = (EchoService) factory.create(EchoService.class, "qpid://guest:guest@" + HOSTNAME + "/test");
        String message = "Hello Hessian!";

        try
        {
            service.exception(message);
            fail("No exception thrown");
        }
        catch (Exception e)
        {
            assertEquals("Exception message", message, e.getMessage());
        }
    }
}

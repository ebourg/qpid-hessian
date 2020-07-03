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

import org.apache.qpid.contrib.hessian.service.EchoService;
import org.apache.qpid.contrib.hessian.service.EchoServiceEndpoint;
import org.apache.qpid.contrib.hessian.service.EchoServiceImpl;
import org.junit.Test;

import static org.junit.Assert.*;

public class HessianEndpointTest extends AMQPHessianProxyTest
{
    protected void startEndpoint()
    {
        HessianEndpoint endpoint = new HessianEndpoint(new EchoServiceImpl());
        endpoint.run(connection);
    }

    private void startEndpointWithPrefix()
    {
        HessianEndpoint endpoint = new HessianEndpoint();
        endpoint.setServiceAPI(EchoService.class);
        endpoint.setServiceImpl(new EchoServiceEndpoint());
        endpoint.setQueuePrefix("foo");
        endpoint.run(connection);
    }

    @Test
    public void testQueuePrefix() throws Exception
    {
        startEndpointWithPrefix();
        
        AMQPHessianProxyFactory factory = new AMQPHessianProxyFactory();
        factory.setReadTimeout(5000);
        factory.setQueuePrefix("foo");
        
        EchoService service = factory.create(EchoService.class, "qpid://guest:guest@" + HOSTNAME + "/test");
        String message = "Hello Hessian!";
        
        assertEquals(message, service.echo(message));
    }

    @Test
    public void testQueuePrefix2() throws Exception
    {
        startEndpointWithPrefix();
        
        AMQPHessianProxyFactory factory = new AMQPHessianProxyFactory();
        factory.setReadTimeout(5000);
        
        EchoService service = factory.create(EchoService.class, "qpid://guest:guest@" + HOSTNAME + "/test/foo");
        String message = "Hello Hessian!";
        
        assertEquals(message, service.echo(message));
    }
}

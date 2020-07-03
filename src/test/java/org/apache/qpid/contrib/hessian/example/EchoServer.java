/**
 * Copyright 2011 Emmanuel Bourg
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

package org.apache.qpid.contrib.hessian.example;

import org.apache.qpid.contrib.hessian.HessianEndpoint;
import org.apache.qpid.contrib.hessian.service.EchoServiceImpl;
import org.apache.qpid.transport.Connection;
import org.apache.qpid.transport.Session;

/**
 * @author Emmanuel Bourg
 */
public class EchoServer {

    public static void main(String[] args) throws Exception {
        Connection connection = new Connection();
        connection.connect("amqp.example.com", 5672, "test", "username", "password");

        HessianEndpoint endpoint = new HessianEndpoint(new EchoServiceImpl());
        Session session = endpoint.run(connection);
        
        Thread.sleep(60000);
        
        session.close();
        connection.close();
    }
}

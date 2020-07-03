Hessian AMQP - Remote Procedure Call over AMQP with Hessian
===========================================================

[![Build Status](https://secure.travis-ci.org/ebourg/qpid-hessian.svg)](https://travis-ci.org/ebourg/qpid-hessian)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Maven Central](https://img.shields.io/maven-central/v/org.apache-extras.qpid/qpid-hessian.svg)](https://search.maven.org/artifact/org.apache-extras.qpid/qpid-hessian)

Hessian AMQP is a component helping the deployment of Hessian services using
an AMQP server as the underlying transport. The principle is almost identical
to Hessian services over HTTP:


1. Create an interface defining the methods exposed:

       public interface EchoService {
           String echo(String message);
       }


2. Implement the interface:

       public class EchoServiceImpl implements EchoService {
       
           public String echo(String message) {
               return message;
           }
       }


3. Deploy an endpoint for the service and attach it to an AMQP connection:

       HessianEndpoint endpoint = new HessianEndpoint(new EchoServiceImpl());
       endpoint.run(connection);


4. On the client side, create a proxy of the interface:

       AMQPHessianProxyFactory factory = new AMQPHessianProxyFactory();
       EchoService service = factory.create(EchoService.class, "amqp://user:pwd@example.com/test");


5. The service is ready to be consumed!

       String echo = service.echo("Hello Hessian!");



### Importing Hessian AMQP into your Maven project

Declare the dependency:

    <dependency>
      <groupId>org.apache-extras.qpid</groupId>
      <artifactId>qpid-hessian</artifactId>
      <version>1.1</version>
    </dependency>


## Changes

#### Version 1.1, released on 2012-07-08

* Added support for SSL with amqps:// URLs
* Endpoints now support session resuming
* Serialization errors are now reported immediately to the client and no longer cause a timeout
* The queue prefix specified on the factory is no longer ignored if the service URL doesn't contain one
* AMQPHessianProxyFactory has been generified

#### Version 1.0, released on 2011-04-30

* Initial release

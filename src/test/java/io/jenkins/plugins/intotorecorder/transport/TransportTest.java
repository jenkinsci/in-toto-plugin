/**
 *
 */
package io.jenkins.plugins.intotorecorder.transport;

import hudson.model.*;
import junit.framework.TestCase;

import java.net.URI;

/**
 *
 * @author Santiago Torres-Arias
 *
 */
public class TransportTest extends TestCase {

	public void setUp() throws Exception {}

	public void testTransportFactory() throws Exception {

        Transport someTransport;
        URI testUrl;

        /* test creating etcd transports */
        testUrl = new URI("etcd://localhost:4000/links/");
        someTransport = Transport.TransportFactory.transportForURI(testUrl);
        assertTrue(someTransport instanceof Etcd);

        /* test creating Jedis transports */
        testUrl = new URI("redis://localhost:4000/links/");
        someTransport = Transport.TransportFactory.transportForURI(testUrl);
        assertTrue(someTransport instanceof Redis);

        /* test creating raw CRUD transports */
        testUrl = new URI("http://localhost:4000/links/");
        someTransport = Transport.TransportFactory.transportForURI(testUrl);
        assertTrue(someTransport instanceof GenericCRUD);

        testUrl = new URI("https://localhost:4000/links/");
        someTransport = Transport.TransportFactory.transportForURI(testUrl);
        assertTrue(someTransport instanceof GenericCRUD);

        /* test creating Grafeas transports */
        testUrl = new URI("grafeas+http://localhost:8080/v1beta1/projects/provider_example/occurrences?noteName=noteName&resourceUri=resourceUri");
        someTransport = Transport.TransportFactory.transportForURI(testUrl);
        assertTrue(someTransport instanceof Grafeas);

	}
}

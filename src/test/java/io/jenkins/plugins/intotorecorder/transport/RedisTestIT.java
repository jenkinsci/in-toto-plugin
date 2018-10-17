/**
 *
 */
package io.jenkins.plugins.intotorecorder.transport;

import hudson.Launcher;
import hudson.model.*;
import hudson.util.RunList;
import junit.framework.TestCase;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;

import static org.junit.Assert.assertTrue;

import redis.clients.jedis.Jedis;

import java.io.PrintStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import io.github.in_toto.models.Link;
import io.github.in_toto.keys.RSAKey;

import java.net.URI;
import java.lang.System;
import java.lang.Integer;

/**
 *
 * @author Santiago Torres-Arias
 *
 */
public class RedisTestIT extends TestCase {

    private final String keyFilepath = "src/test/resources/keys/somekey.pem";

    private Redis transport;
    private RSAKey key;
    private URI uri;
    private int port;

	public void setUp() throws Exception {
        this.port = Integer.parseInt(System.getenv("REDIS_SERVER_PORT"));
        this.uri = new URI("redis://localhost:" + port);
        this.transport = new Redis(uri);
        this.key = RSAKey.read(keyFilepath);
    }

	public void testEtcdStorage() throws Exception {

        Link link = new Link(null, null, "step", null, null, null);
        link.sign(this.key);
        /* get should've gotten a 200 here, otherwise an exception would've
         * been thrown 
         */
        this.transport.submit(link);

        /* To make sure we got the right thing, we'll get the link information
         * as a string and compare it with our local copy. Modulo any encoding
         * weirdness, they should match exactly as we sent it 
         */
        Jedis jedis = new Jedis("localhost", port);
        String result = jedis.get(link.getFullName());
        assertTrue(result + "!=" + link.dumpString(),
                result.equals(link.dumpString()));
	}
}

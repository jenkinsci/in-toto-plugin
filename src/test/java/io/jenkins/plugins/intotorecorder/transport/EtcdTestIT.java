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

import java.io.PrintStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.HttpResponse;

import io.github.in_toto.models.Link;
import io.github.in_toto.keys.RSAKey;

import java.net.URI;
import java.lang.System;

/**
 *
 * @author Santiago Torres-Arias
 *
 */
public class EtcdTestIT extends TestCase {

    private final String keyFilepath = "src/test/resources/keys/somekey.pem";

    private Etcd transport;
    private RSAKey key;
    private URI uri;

	public void setUp() throws Exception {
        String port = System.getenv("ETCD_SERVER_PORT");
        this.uri = new URI("http://localhost:" + port);
        this.transport = new Etcd(uri);
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
        HttpClient client = new DefaultHttpClient();
        HttpGet get = new HttpGet(
            this.uri.toString() + "/v2/keys/" + link.getFullName());

        ResponseHandler<Response> handler = new ResponseHandler<Response>() {
            
            @Override
            public Response handleResponse(final HttpResponse response) 
                throws IOException {

                if (response.getStatusLine().getStatusCode() >= 300) {
                    throw new IOException("Server responded with failing status code");
                }
                if (response.getEntity() == null){
                    throw new IOException("Server's response was invalid");
                }

                Gson gson =  new GsonBuilder().create();
                Reader r = new InputStreamReader(
                        response.getEntity().getContent());
                return gson.fromJson(r, Response.class);
            }
        };
        Response result = client.execute(get, handler);

        System.out.println(result.node.value);
        assertTrue(result.node.value + "!= " + link.dumpString(),
            result.node.value.equals(link.dumpString()));

	}

    private class Response {

        public String action;
        public Node node;

        private class Node {
            String key;
            String value;
        }

    }

}

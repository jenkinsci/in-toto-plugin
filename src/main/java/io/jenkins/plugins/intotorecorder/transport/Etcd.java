/**
 *
 */
package io.jenkins.plugins.intotorecorder.transport;

import java.io.IOException;

import java.net.URI;

// import com.coreos.jetcd.Client;
// import com.coreos.jetcd.data.ByteSequence;

import com.google.api.client.http.javanet.NetHttpTransport;

import io.github.intoto.legacy.models.Link;

import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;



public class Etcd extends Transport {

    URI uri;

    public Etcd(URI uri) {
        this.uri = uri;
    }

    public void submit(Link link) {
        /* FIXME: this is how we *should* handle connectivity but Jetcd is failing
         * I'll defer this to a further checkpoint in the jetcd implementation
        Client client = Client.builder()
                .endpoints(this.uri.toString()).build();
        client.getKVClient().put(
              ByteSequence.fromString(link.getName()),
              ByteSequence.fromString(link.toString())
        );
            WARNING: for now I'll add an unauthenticated curl-like http client
            to perform the operation instead...
            FIXME ^ FIXME ^ FIXME ^
        */
        try {
            HttpRequest request = new NetHttpTransport()
                .createRequestFactory()
                .buildPutRequest(new GenericUrl(this.uri.toString() + 
                            "/v2/keys/" + link.getFullName()),
                    ByteArrayContent.fromString("application/x-www-form-urlencoded",
                        "value=" + link.dumpString()));

            request.execute();
        } catch (IOException e) {
            throw new RuntimeException("Couldn't serialize link: " +
                    link.getFullName() + ".Error was: " + e.toString());
        }
    }
}

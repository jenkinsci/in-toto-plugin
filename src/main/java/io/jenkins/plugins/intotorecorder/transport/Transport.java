/**
 *
 */
package io.jenkins.plugins.intotorecorder.transport;

import java.net.URI;

import io.github.in_toto.models.Link;

public abstract class Transport {

    String uri;

    public abstract void submit(Link link);

    public static class TransportFactory {
        public static Transport transportForURI(URI uri) {

            if (uri == null)
                return null;

            if (uri.getScheme().equals("etcd")) {
                return new Etcd(uri);
            }

            if (uri.getScheme().equals("redis")) {
                return new Redis(uri);
            }

            if (uri.getScheme().equals("http") || uri.getScheme().equals("https")) {
                return new GenericCRUD(uri);
            }

            throw new RuntimeException("This protocol (" + uri.getScheme()
                + ") is not yet supported!");

        }
    }
}

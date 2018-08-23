/**
 *
 */
package io.jenkins.plugins.intotorecorder.transport;

import redis.clients.jedis.Jedis;
import io.in_toto.models.Link;
import java.net.URI;

public class Redis extends Transport {

    private final int REDIS_DEFAULT_PORT = 6379;
    URI uri;
    Jedis jedis;

    public Redis(URI uri) {
        this.uri = uri;
        int port = uri.getPort();
        if (uri.getPort() == - 1)
            port = REDIS_DEFAULT_PORT;

        this.jedis = new Jedis(uri.getHost(), port);
    }

    public void submit(Link link) {
        this.jedis.set(link.getFullName(), link.dumpString());
    }
}

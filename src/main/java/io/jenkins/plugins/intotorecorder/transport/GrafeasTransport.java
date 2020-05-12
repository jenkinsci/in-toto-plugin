/**
 *
 */
package io.jenkins.plugins.intotorecorder.transport;

import io.github.in_toto.models.Link;
import io.github.in_toto.models.Artifact.ArtifactHash;
import io.github.in_toto.keys.Signature;
import java.net.URI;
import org.apache.http.client.utils.URIBuilder;
import java.net.URISyntaxException;
import java.util.*;
import com.google.gson.Gson;

import java.io.IOException;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;


public class GrafeasTransport extends Transport {

    URI uri;
    GrafeasOccurrence occurrence;

    public class GrafeasInTotoMetadata {
        public ArrayList<Signature> signatures = new ArrayList<Signature>();
        public GrafeasInTotoLink signed;

        private class GrafeasInTotoLink {
            // This class exists to represent the Grafeas document format for
            // in-toto links.

            private class Artifact {
                public String resourceUri;
                public Map<String, String> hashes;

                public Artifact(String resourceUri, Map<String, String> hashes) {
                    this.resourceUri = resourceUri;
                    this.hashes = hashes;
                }

            }

            public List<String> command = new ArrayList<String>();
            public List<Artifact> materials = new ArrayList<Artifact>();
            public List<Artifact> products = new ArrayList<Artifact>();
            public Map<String, Map<String, String>> byproducts = new HashMap<String, Map<String, String>>();
            public Map<String, Map<String, String>> environment = new HashMap<String, Map<String, String>>();

            public GrafeasInTotoLink(List<String> command,
                                     Map<String, ArtifactHash> materials,
                                     Map<String, ArtifactHash> products,
                                     Map<String, Object> byproducts,
                                     Map<String, Object> environment) {

                this.command = command;

                for (Map.Entry<String, ArtifactHash> material : materials.entrySet()) {
                    // FIXME: String resourceUri = "file://sha256:" + material.getValue().get("sha256") + ":" + material.getKey();
                    Artifact artifact = new Artifact(material.getKey(), (Map<String, String>)material.getValue());
                    this.materials.add(artifact);
                }

                for (Map.Entry<String, ArtifactHash> product : products.entrySet()) {
                    // FIXME: String resourceUri = "file://sha256:" + product.getValue().get("sha256") + ":" + product.getKey();
                    Artifact artifact = new Artifact(product.getKey(), (Map<String, String>)product.getValue());
                    this.products.add(artifact);
                }

                Map<String, String> stringByproducts = new HashMap<String, String>();
                for (String byproduct : byproducts.keySet()) {
                    stringByproducts.put(byproduct, String.valueOf(byproducts.get(byproduct)));
                }

                Map<String, String> stringEnvironment = new HashMap<String, String>();
                for (String env : environment.keySet()) {
                    stringEnvironment.put(env, String.valueOf(environment.get(env)));
                }

                this.byproducts.put("customValues", stringByproducts);
                this.environment.put("customValues", stringEnvironment);
            }
        }

        public GrafeasInTotoMetadata(Link link) {
            this.signatures = link.getSignatures();
            this.signed = new GrafeasInTotoLink(
                link.getCommand(),
                link.getMaterials(),
                link.getProducts(),
                link.getByproducts(),
                link.getEnvironment()
            );
        }
    }


    public class GrafeasOccurrence {
        public String noteName;
        public Map<String, String> resource = new HashMap<String, String>();
        public String kind = "INTOTO";
        public GrafeasInTotoMetadata intoto;

        public GrafeasOccurrence(String noteName, String resourceUri) {
            this.noteName = noteName;
            this.resource.put("uri", resourceUri);
        }
    }

    private static Map<String, String> getParameterMap(String parameterString) {
        String[] items = parameterString.split("&");
        Map<String, String> parameterMap = new HashMap<String, String>();
        for (String item : items) {
            String[] pair = item.split("=");
            parameterMap.put(pair[0], pair[1]);
        }
        return parameterMap;
    }

    public GrafeasTransport(URI uri) {
        String scheme = uri.getScheme().split("\\+")[1];
        String authority = uri.getAuthority();
        String path = uri.getPath();
        URIBuilder uriBuilder = new URIBuilder();

        try {
            this.uri = uriBuilder
                .setScheme(scheme)
                .setHost(authority)
                .setPath(path)
                .build();
        } catch (URISyntaxException e) {
            throw new RuntimeException("unable to build Grafeas URI: " + e.toString());
        }

        String parameterString = uri.getQuery();

        Map<String, String> parameterMap = GrafeasTransport.getParameterMap(parameterString);

        GrafeasOccurrence occurrence = new GrafeasOccurrence(
            parameterMap.get("noteName"),
            parameterMap.get("resourceUri")
        );

        this.occurrence = occurrence;
    }

    public void submit(Link link) {
        this.occurrence.intoto = new GrafeasInTotoMetadata(link);

        Gson gson = new Gson();
        String jsonString = gson.toJson(this.occurrence);
        GenericUrl url = new GenericUrl(this.uri);

        // FIXME: Shamelessly copied from GenericCRUD.java
        try {
            HttpRequest request = new NetHttpTransport()
                .createRequestFactory()
                .buildPostRequest(url,
                    ByteArrayContent.fromString("application/json",
                        jsonString));
            HttpResponse response = request.execute();
            System.out.println(response.parseAsString());

            /* FIXME: should handle error codes and other situations more appropriately,
             * but this gets the job done for a PoC
             */
        } catch (IOException e) {
            throw new RuntimeException("for URL " + url.toString() +
                " couldn't serialize to HTTP server: " + e.toString());
        }
    }
}

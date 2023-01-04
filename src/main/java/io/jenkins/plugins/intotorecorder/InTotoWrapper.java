/**
 *
 */
package io.jenkins.plugins.intotorecorder;

import io.github.intoto.legacy.keys.Key;
import io.github.intoto.legacy.keys.RSAKey;
import io.github.intoto.legacy.lib.NumericJSONSerializer;
import io.github.intoto.legacy.models.Artifact;
import io.github.intoto.legacy.models.Link;
import io.github.intoto.legacy.models.Artifact.ArtifactHash;
import io.github.intoto.slsa.models.v02.*;
import io.jenkins.plugins.intotorecorder.transport.Transport;

import com.google.common.util.concurrent.Service.Listener;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.RunList;
import hudson.FilePath;
import hudson.EnvVars;
import hudson.FilePath.FileCallable;
import hudson.remoting.VirtualChannel;
import hudson.security.ACL;

import jenkins.MasterToSlaveFileCallable;
import jenkins.model.Jenkins;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;
import org.jenkinsci.Symbol;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.lang.InterruptedException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import jenkins.tasks.SimpleBuildWrapper;
import jenkins.tasks.SimpleBuildWrapper.Context;
import jenkins.tasks.SimpleBuildWrapper.Disposer;

import org.jenkinsci.plugins.plaincredentials.FileCredentials;

/**
 *
 * Jenkins recorder plugin to output signed link metadata for Jenkins pipeline
 * steps.
 *
 * @author SantiagoTorres
 */
public class InTotoWrapper extends SimpleBuildWrapper {

    /**
     * Location of the key to load.
     *
     * If not defined signing will not be performed.
     */
    @DataBoundSetter
    private String keyPath;

    /**
     * CredentialId for the key to load.
     *
     * If not defined signing will not be performed.
     */
    @DataBoundSetter
    private String credentialId;

    /**
     * Name of the step to execute.
     *
     * If not defined, will default to step
     */
    @DataBoundSetter
    private String stepName;

    /**
     * The host URL/URI where to post the in-toto metadata.
     *
     * Protocol information *must* be included.
     */
    @DataBoundSetter
    private String transport;

    /**
     * Link metadata used to record this step
     *
     */
    private Link link;

    /**
     * Loaded key used to sign metadata
     */
    private Key key;

    /**
     * The current working directory (to be recorded as context).
     *
     */
    private FilePath cwd;

    /**
     * PRovenance metadata used to record this step
     *
     */
    private Provenance provenance;

    @DataBoundConstructor
    public InTotoWrapper(String credentialId, String keyPath, String stepName, String transport)
    {

        /* Set a "sensible" step name if not defined */
        if (stepName == null || stepName.length() == 0)
            stepName = "step";
        this.stepName = stepName;

        this.credentialId = credentialId;
        if(credentialId != null && credentialId.length() != 0) {
            try {
                loadKey(new InputStreamReader(getCredentials().getContent(), "UTF-8"));
            } catch (IOException e) {
                throw new RuntimeException("credential with Id '" + credentialId + "' can't be read. ");
            }
        }

        this.keyPath = keyPath;

        /* notice how we can't do the same for the key, as that'd be a security
         * hazard
         *
         * INFO: keys are now loaded on the worker side (as they are assumed to
         * be located there)
         *
         * Will remove this in a future commit...
        this.keyPath = keyPath;
        if (keyPath  != null && keyPath.length() != 0)
            loadKey(keyPath);

        */

        /* The transport property will default to the current CWD, but we can't figure that one
         * just yet
         */
        this.transport = transport;
    }

    @Override
    public void setUp(SimpleBuildWrapper.Context context,
                               Run<?,?> build,
                               FilePath workspace,
                               Launcher launcher,
                               TaskListener listener,
                               EnvVars initialEnvironment)
                        throws IOException,
                               InterruptedException {
        this.cwd = workspace;

        listener.getLogger().println("[in-toto] wrapping step ");
        listener.getLogger().println("[in-toto] using step name: " + this.stepName);
        listener.getLogger().println("[in-toto] transport: " + this.transport);
        if ( credentialId != null && credentialId.length() != 0 && this.key != null ) {
                listener.getLogger().println("[in-toto] Key fetched from credentialId " + this.credentialId);
            } else if (keyPath != null && keyPath.length() != 0) {
                listener.getLogger().println("[in-toto] CredentialId not found, but the keyPath is " + this.keyPath);
            } else {
                throw new RuntimeException("[in-toto] Neither credentialId nor keyPath found for signing key! ");
        }

        this.link = new Link(null, null, this.stepName, null, null, null);
        this.link.setMaterials(InTotoWrapper.collectArtifacts(this.cwd));

        //Setting up Provenance object
        this.provenance = new Provenance();

        //Setting up the builder
        setBuilder(initialEnvironment);

        //setting up materials
        setProvenanceMaterials();

        //setting up Invocation
        BuildInvocation invocation = new BuildInvocation();
        invocation.setEnvironments(initialEnvironment);
        invocation.setConfigSource(initialEnvironment);
        provenance.setInvocation(invocation);

        //Setting up Metadata
        setMetadata(initialEnvironment);

        listener.getLogger().println("[in-toto] Dumping metadata... ");

        context.setDisposer(new PostWrap(this.link, this.provenance, this.key, this.keyPath, this.stepName,
                    this.transport));
    }

    private void setProvenanceMaterials() {

        // HashMap<String, ArtifactHash> materials =new HashMap<String, ArtifactHash>();
        // materials=link.getMaterials();

        Iterator hmIterator = link.getMaterials().entrySet().iterator();
        List<Material> provenanceMaterials = new ArrayList<Material>();

        while (hmIterator.hasNext()) {
            Material material=new Material();
            Map.Entry<String, ArtifactHash> new_Map = (Map.Entry<String, ArtifactHash>)hmIterator.next();
            material.setUri(new_Map.getKey());
            material.setDigest(new_Map.getValue());
            provenanceMaterials.add(material);
        }

        this.provenance.setMaterials(provenanceMaterials);
    }

    private void setBuilder(EnvVars initialEnvironment){

        Builder builder = new Builder();
        builder.setId(initialEnvironment.get("JENKINS_URL")+"@"+initialEnvironment.get("NODE_NAME"));

        this.provenance.setBuilder(builder);
    }

    private class BuildInvocation extends Invocation{
        private Map<String,String> environment;

        private Object parameters;

        public void setEnvironments(EnvVars initialEnvironment){
            environment=initialEnvironment;
            environment.remove("HUDSON_SERVER_COOKIE");
            environment.remove("JENKINS_SERVER_COOKIE");
        }

        public Map<String,String> getEnvironments(){
            return environment;
        }

        public void setConfigSource(EnvVars initialEnvironment){

            ConfigSource configSource=new ConfigSource();
            String git_url=initialEnvironment.get("GIT_URL");
            configSource.setUri(git_url);

            String lastCommit=initialEnvironment.get("GIT_COMMIT");

            Map<String,String> uriDigestMap=new HashMap<String,String>();
            uriDigestMap.put("sha1", lastCommit);
            configSource.setDigest(uriDigestMap);

            configSource.setEntryPoint("Jenkinsfile");

            super.setConfigSource(configSource);
        }
    }

    private void setMetadata(EnvVars initialEnvironment){
        Metadata metadata=new Metadata();

        //Setting up Build Invocation Id
        metadata.setBuildInvocationId(initialEnvironment.get("INVOCATION_ID"));

        //Setting up Build Start Time
        Instant instant = Instant.ofEpochMilli(ZonedDateTime.now().toInstant().toEpochMilli());
        OffsetDateTime offsetTime = OffsetDateTime.ofInstant(instant, ZoneId.systemDefault());
        metadata.setBuildStartedOn(offsetTime);

        /*
         * FIXME: All the completeness parameters are set to false for now.
         * Discuss what completeness means for each field and what would be required to make them true. 
         *
         */

        //Setting up Completeness object.
        Completeness completeness = new Completeness();
        completeness.setEnvironment(false);
        completeness.setMaterials(false);
        completeness.setParameters(false);

        metadata.setReproducible(false);

        metadata.setCompleteness(completeness);
        this.provenance.setMetadata(metadata);
    }


    private void loadKey(Reader reader) {
        this.key = RSAKey.readPemBuffer(reader);
    }

    public String getKeyPath() {
        return this.keyPath;
    }

    public String getStepName() {
        return this.stepName;
    }

    public String getTransport() {
        return this.transport;
    }

    protected final FileCredentials getCredentials() throws IOException {
        FileCredentials fileCredential = CredentialsMatchers.firstOrNull(
            CredentialsProvider.lookupCredentials(
                    FileCredentials.class,
                    Jenkins.get(),
                    ACL.SYSTEM,
                    Collections.<DomainRequirement>emptyList()
            ),
            CredentialsMatchers.withId(this.credentialId)
            );

        if ( fileCredential == null )
            throw new RuntimeException(" Could not find credentials entry with ID '" + credentialId + "' ");

        return fileCredential;
    }

    public static HashMap<String, ArtifactHash> collectArtifacts(FilePath path) {
        HashMap<String, ArtifactHash> result = null;
        Gson gson = new Gson();
        Type stringHashMap = new TypeToken<HashMap<String, ArtifactHash>>(){}.getType();
        try {
            String jsonString = path.act(new ArtifactCollector());
            result = gson.fromJson(jsonString, stringHashMap);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e.toString());
        }
        return result;
    }

    /**
     * Descriptor for {@link InTotoRecorder}. Used as a singleton. The class is
     * marked as public so that it can be accessed from views.
     *
     *
     * See
     * src/main/resources/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension
    @Symbol("in_toto_wrap")
    public static final class DescriptorImpl extends BuildWrapperDescriptor {

        public DescriptorImpl() {
            super(InTotoWrapper.class);
            load();
        }

        @Override
        public String getDisplayName() {
            return "in-toto record wrapper";
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

    }

    /**
     * Class to collect artifact hashes from remote hosts.
     *
     */
    private static final class ArtifactCollector
            extends MasterToSlaveFileCallable<String> {
        private static final long serialVersionUID = 1;

        @Override
        //public HashMap<String, ArtifactHash> invoke(File f, VirtualChannel channel) {
        public String invoke(File f, VirtualChannel channel) {

            HashMap<String, ArtifactHash> result = new HashMap<String, ArtifactHash>();
            recurseAndCollect(f, result);
            Gson gson = new Gson();
            return gson.toJson(result);
        }

        private static void recurseAndCollect(File f, HashMap<String, ArtifactHash> hashmap) {

            if (f.exists() && f.isFile()) {
                Artifact artifact = new Artifact(f.toString());
                hashmap.put(artifact.getURI(), artifact.getArtifactHashes());
            } else if (f.exists() && f.isDirectory()) {
                File[] contents = f.listFiles();
                if ( contents != null ) {
                    for (int i = 0; i < contents.length; i++) {
                        recurseAndCollect(contents[i], hashmap);
                    }
                }
            }
        }
    }

    /**
     *
     * Private class to dump the link metadata to the local workspace of the worker
     */
    private static final class LinkSerializer
            extends MasterToSlaveFileCallable<String> {
        private static final long serialVersionUID = 2;

        String linkData;
        @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("URF_UNREAD_FIELD")
        String keyPath;
        String transportURL;

        private LinkSerializer(String linkData, String keyPath, String transportURL) {
            this.linkData = linkData;
            this.keyPath = keyPath;
            this.transportURL = transportURL;
        }



        @Override
        public String invoke(File f, VirtualChannel channel) {
            Gson gson = new Gson();
            System.out.println(this.linkData);
            Link link = gson.fromJson(this.linkData, Link.class);

            /* if a transport is provided, let the master send the resulting
             * metadata */
            if (transportURL == null || transportURL.length() == 0) {
                try {
                    Writer writer = new OutputStreamWriter(
                        new FileOutputStream(f), "UTF-8");
                    link.dump(writer);
                    writer.close();
                } catch (IOException e) {
                    throw new RuntimeException("Could not instantiate writer: " + e.toString());
                }
            }

            return link.dumpString();
        }
    }

        /**
     *
     * Private class to dump the provenance metadata to the local workspace of the worker
     */
    private static final class ProvenanceSerializer
            extends MasterToSlaveFileCallable<String> {
        private static final long serialVersionUID = 2;

        String ProvenanceData;
        @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("URF_UNREAD_FIELD")
        String keyPath;
        String transportURL;

        private ProvenanceSerializer(String ProvenanceData, String keyPath, String transportURL) {
            this.ProvenanceData = ProvenanceData;
            this.keyPath = keyPath;
            this.transportURL = transportURL;
        }

        @Override
        public String invoke(File f, VirtualChannel channel) {
            Gson gson = new Gson();
            Provenance provenance = gson.fromJson(this.ProvenanceData, Provenance.class);

            /* if a transport is provided, let the master send the resulting
             * metadata */
            if (transportURL == null || transportURL.length() == 0) {
                try {
                    Writer writer = new OutputStreamWriter(
                        new FileOutputStream(f), "UTF-8");
                        writer.write(this.ProvenanceData);
                        writer.flush();
                    writer.close();
                } catch (IOException e) {
                    throw new RuntimeException("Could not instantiate writer: " + e.toString());
                }
            }

            return this.ProvenanceData;
        }
    }


    public static class PostWrap extends Disposer {

        private static final long serialVersionUID = 2;
        @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("SE_TRANSIENT_FIELD_NOT_RESTORE")
        transient Link link;
        @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("SE_TRANSIENT_FIELD_NOT_RESTORE")
        transient Provenance provenance;
        @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("SE_TRANSIENT_FIELD_NOT_RESTORE")
        transient Key key;
        String keyPath;
        String transportURL;
        @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("URF_UNREAD_FIELD")
        String stepName;

        public PostWrap(Link link,Provenance provenance, Key key, String keyPath, String stepName,
                String transportURL) {
            super();

            this.link = link;
            this.provenance=provenance;
            this.key = key;
            this.keyPath = keyPath;
            this.stepName = stepName;

            if (transportURL == null) {
                transportURL = "";
            }
            this.transportURL = transportURL;
        }

        @Override
        public void tearDown(Run<?,?> build,
                                      FilePath workspace,
                                      Launcher launcher,
                                      TaskListener listener)
                               throws IOException,
                                      InterruptedException {

            this.link.setProducts(InTotoWrapper.collectArtifacts(workspace));

            if (this.key != null) {
                this.link.sign(key);
            } else if (this.keyPath != null) {
                RSAKey key = loadKey(this.keyPath);
                this.link.sign(key);
            } else {
                listener.getLogger().println("[in-toto] Warning! no keypath " +
                "specified. Not signing...");
            }

            Instant instant = Instant.ofEpochMilli(ZonedDateTime.now().toInstant().toEpochMilli());
            OffsetDateTime offsetTime = OffsetDateTime.ofInstant(instant, ZoneId.systemDefault());
            provenance.getMetadata().setBuildFinishedOn(offsetTime);

            Transport transport = null;

            try {
                transport = Transport.TransportFactory.transportForURI(
                    new URI(transportURL));
                listener.getLogger().println("[in-toto] Dumping metadata to: " +
                    transport);
                transport.submit(this.link);
            } catch (URISyntaxException | RuntimeException e) {
                listener.getLogger().println("[in-toto] No transport " +
                        "specified (or transport not supported)." +
                        " Dumping metadata to local directory");
                listener.getLogger().println(e);
                dumpLink(workspace);
            }
        }

        /* Private method that will help me publish metadata in a transport
         * agnostic way. Most likely by buffering and sending stuff over the
         * wire once it's serialized to temporary directory
         */
        private String dumpLink(FilePath cwd) {

            Gson gson = new GsonBuilder()
            .serializeNulls()
            // Use custom serializer to enforce non-floating point numbers
            .registerTypeAdapter(Double.class, new NumericJSONSerializer())
            .setPrettyPrinting()
            .create();

            try {
                FilePath linkPath = cwd.child(link.getFullName());
                FilePath provenancePath = cwd.child(this.stepName+"_SLSA_Provenance"+".json");
                provenancePath.act(new ProvenanceSerializer(gson.toJson(provenance), this.keyPath,
                            this.transportURL));
                return linkPath.act(new LinkSerializer(this.link.dumpString(), this.keyPath,
                            this.transportURL));
            } catch(IOException | InterruptedException e) {
                throw new RuntimeException(
                        "Can't create child node for link metadata " +
                        e.toString());
            }
        }

        private RSAKey loadKey(String keyPath) {
            File keyFile = new File(keyPath);

            if (!keyFile.exists()) {
                throw new RuntimeException(" This signing keypath ("
                        + keyPath + ") does not exist!");
            }

            return RSAKey.read(keyPath);
        }
    }
}

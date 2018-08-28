/**
 *
 */
package io.jenkins.plugins.intotorecorder;

import io.jenkins.plugins.intotorecorder.transport.Transport;

import io.in_toto.models.Link;
import io.in_toto.models.Artifact.ArtifactHash;
import io.in_toto.models.Artifact;

import io.in_toto.keys.Key;
import io.in_toto.keys.RSAKey;

import com.google.gson.Gson;
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

import jenkins.MasterToSlaveFileCallable;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

import org.jenkinsci.Symbol;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.lang.InterruptedException;
import java.net.URI;
import java.net.URISyntaxException;

import jenkins.tasks.SimpleBuildWrapper;
import jenkins.tasks.SimpleBuildWrapper.Context;
import jenkins.tasks.SimpleBuildWrapper.Disposer;



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
     * Name of the step to execute.
     *
     * If not defined, will default to step
     */
    @DataBoundSetter
    private String stepName;

    /**
     * The host URL/URI where to post the in-toto metdata.
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

    @DataBoundConstructor
    public InTotoWrapper(String keyPath, String stepName, String transport)
    {

        /* Set a "sensible" step name if not defined */
        if (stepName == null || stepName.length() == 0)
            stepName = "step";
        this.stepName = stepName;

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
        listener.getLogger().println("[in-toto] using step name: " + this.transport);

        this.link = new Link(null, null, this.stepName, null, null, null);
        this.link.setMaterials(InTotoWrapper.collectArtifacts(this.cwd));

        listener.getLogger().println("[in-toto] Dumping metadata..." + this.keyPath);
        context.setDisposer(new PostWrap(this.link, this.keyPath, this.stepName,
                    this.transport));
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
     * Descriptor for {@link inTotoRecorder}. Used as a singleton. The class is
     * marked as public so that it can be accessed from views.
     *
     *
     * See
     * <tt>src/main/resources/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly</tt>
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
                for (int i = 0; i < contents.length; i++) {
                    recurseAndCollect(contents[i], hashmap);
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
        String keyPath;
        String transportURL;

        private LinkSerializer(String linkData, String keyPath, String transportURL) {
            this.linkData = linkData;
            this.keyPath = keyPath;
            this.transportURL = transportURL;
        }

        @Override
        public String invoke(File f, VirtualChannel channel) {

            RSAKey key = loadKey(keyPath);

            Gson gson = new Gson();
            System.out.println(this.linkData);
            Link link = gson.fromJson(this.linkData, Link.class);
            link.sign(key);

            /* if a transport is provided, let the master send the resulting
             * metadata */
            if (transportURL == null) {
                link.dump();
            }

            return link.dumpString();
        }

        private RSAKey loadKey(String keyPath) {
            File keyFile = new File(keyPath);

            if (!keyFile.exists()) {
                throw new RuntimeException("this Signing keypath ("
                        + keyPath + ") does not exist!");
            }

            return RSAKey.read(keyPath);
        }

    }

    public static class PostWrap extends Disposer {

        private static final long serialVersionUID = 2;
        transient Link link;
        transient String keyPath;
        String transportURL;
        String stepName;

        public PostWrap(Link link, String keyPath, String stepName,
                String transportURL) {
            super();

            this.link = link;
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

            Transport transport = null;

            /* we ignore the error as it'll be handled in the next if */
            try {
                transport = Transport.TransportFactory.transportForURI(new URI(transportURL));
            } catch (URISyntaxException | RuntimeException e) {}

            this.link = Link.read(dumpLink(workspace));

            if (transportURL.length() == 0 || transport == null) {
                listener.getLogger().println("[in-toto] No transport specified " +
                        "(or transport not supported)" +
                        " Dumping metadata to worker's directory");
            } else {
                listener.getLogger().println("[in-toto] Dumping metadata to: " + transport);
                transport.submit(this.link);
            }
        }

        /* Private method that will help me publish metadata in a transport
         * agnostic way. Most likely by buffering and sending stuff over the
         * wire once it's serialized to teporary directory
         */
        private String dumpLink(FilePath cwd) {

            try {
                FilePath linkPath = cwd.child(link.getFullName());
                return linkPath.act(new LinkSerializer(this.link.dumpString(), this.keyPath,
                            this.transportURL));
            } catch(IOException | InterruptedException e) {
                throw new RuntimeException(
                        "Can't create child node for link metadata" +
                        e.toString());
            }
        }
    }
}

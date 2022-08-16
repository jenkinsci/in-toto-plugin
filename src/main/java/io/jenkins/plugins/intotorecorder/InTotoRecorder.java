/**
 *
 */
package io.jenkins.plugins.intotorecorder;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.RunList;
import io.github.intoto.legacy.keys.Key;
import io.github.intoto.legacy.keys.RSAKey;
import io.github.intoto.legacy.models.Artifact;
import io.github.intoto.legacy.models.Link;
import io.github.intoto.legacy.models.Artifact.ArtifactHash;
import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.remoting.VirtualChannel;
import hudson.util.ListBoxModel;
import hudson.util.FormValidation;
import hudson.security.ACL;

import jenkins.MasterToSlaveFileCallable;
import jenkins.model.Jenkins;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;

import org.apache.commons.lang.StringUtils;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;

import java.io.BufferedReader;
import java.io.File;
import java.io.Reader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Collections;
import java.lang.InterruptedException;

import org.jenkinsci.plugins.plaincredentials.FileCredentials;

/**
 *
 * Jenkins recorder plugin to output signed link metadata for Jenkins pipeline
 * steps.
 *
 * @author SantiagoTorres
 */
public class InTotoRecorder extends Recorder {

    /**
     * Location of the key to load.
     *
     * If not defined signing will not be performed.
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("URF_UNREAD_FIELD")
    private String keyPath;

    /**
     * CredentialsId for the key to load.
     *
     * If not defined signing will not be performed.
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("URF_UNREAD_FIELD")
    private String credentialId;

    /**
     * Name of the step to execute.
     *
     * If not defined, will default to step
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("URF_UNREAD_FIELD")
    private String stepName;

    /**
     * The host URL/URI where to post the in-toto metadata.
     *
     * Protocol information *must* be included.
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("URF_UNREAD_FIELD")
    private String transport;

    /**
     * Link metadata used to record this step
     *
     */
    private Link link;

    /**
     * Loaded key used to sign metadata
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("UUF_UNUSED_FIELD")
    private Key key;

    /**
     * The current working directory (to be recorded as context).
     *
     */
    private FilePath cwd;

    @DataBoundConstructor
    public InTotoRecorder(String credentialId, String keyPath, String stepName, String transport)
    {

        /* Set a "sensible" step name if not defined */
        if (stepName == null || stepName.length() == 0)
            stepName = "step";
        this.stepName = stepName;

        /* notice how we can't do the same for the key, as that'd be a security
         * hazard */
            
        this.credentialId = credentialId;
        this.keyPath = keyPath;

        if (credentialId != null && credentialId.length() != 0 ) {
            try {
                loadKey(new InputStreamReader(getCredentials().getContent(), "UTF-8"));
            } catch (IOException e) {
                throw new RuntimeException("credentialId '" + credentialId + "' can't be read. ");
            }
        } else if (keyPath != null && keyPath.length() != 0) {
            loadKey(keyPath);
        }

        /* The transport property will default to the current CWD, but we can't figure that one
         * just yet
         */
        this.transport = transport;
    }

    @Override
    public boolean prebuild(AbstractBuild<?,?> build, BuildListener listener)  {

        this.cwd = build.getWorkspace();
        String  cwdStr;
        if (this.cwd != null) {
            cwdStr = this.cwd+"";
        } else {
            throw new RuntimeException("[in-toto] Cannot get the build workspace");
        }

        listener.getLogger().println("[in-toto] Recording state before build " + cwdStr);
        listener.getLogger().println("[in-toto] using step name: " + stepName);

        this.link = new Link(null, null, this.stepName, null, null, null);
        this.link.setMaterials(this.collectArtifacts(this.cwd));
        return true;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {

        this.link.setProducts(this.collectArtifacts(this.cwd));

        if ( this.credentialId != null && this.credentialId.length() != 0 ) {
            listener.getLogger().println("[in-toto] Signing with credentials '"
                    + this.credentialId + "' " + " and keyid: " + this.key.computeKeyId());
            signLink();
        } else if ( this.keyPath != null && this.keyPath.length() != 0 ) {
            listener.getLogger().println("[in-toto] Signing with keyPath '"
                    + this.keyPath + "' " + " and keyid: " + this.key.computeKeyId());
            signLink();
        } else {
            listener.getLogger().println("[in-toto] Warning! no key specified. Not signing...");
        }

        if (this.transport == null || this.transport.length() == 0) {
            listener.getLogger().println("[in-toto] No transport specified (or transport not supported)"
                    + " Dumping metadata to local directory");
        } else {
            listener.getLogger().println("[in-toto] Dumping metadata to: " + transport);
        }
        dumpLink();

        return true;
    }

    /* Private method that will help me publish metadata in a transport agnostic way. Most likely
     * by buffering and sending stuff over the wire once it's serialized to temporary directory
     */
    private void dumpLink() {
        String linkName = this.cwd + "/" + stepName + ".xxxx.link";
        this.link.dump(linkName);
    }

    private void signLink() {
        this.link.sign(this.key);
    }

    private void loadKey(Reader reader) {
        this.key = RSAKey.readPemBuffer(reader);
    }

    private void loadKey(String keyPath) {
        File keyFile = new File(keyPath);

        if (!keyFile.exists()) {
            throw new RuntimeException("this Signing keypath ("
                    + keyPath + ")does not exist!");
        }

        this.key = RSAKey.read(keyPath);
    }

    public String getCredentialId() {
        return this.credentialId;
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
            CredentialsMatchers.withId(credentialId)
            );

        if ( fileCredential == null )
            throw new RuntimeException(" Could not find credentials entry with ID '" + credentialId + "' ");

        return fileCredential;
    }


    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    private HashMap<String, ArtifactHash> collectArtifacts(FilePath path) {
        HashMap<String, ArtifactHash> result = null;
        try {
            result = path.act(new ArtifactCollector());
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
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @SuppressWarnings("rawtypes")
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        /**
         * This human-readable name is used in the configuration screen.
         */
        public String getDisplayName() {return "in-toto provenance plugin";}

        /**
         * populating the credentialId drop-down list
         */
        public ListBoxModel doFillCredentialIdItems(@AncestorInPath Item item, @QueryParameter String credentialId) {

            StandardListBoxModel result = new StandardListBoxModel();
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeCurrentValue(credentialId);
                }
            } else {
                if (!item.hasPermission(Item.EXTENDED_READ)
                    && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                    return result.includeCurrentValue(credentialId);
                }
            }
            return result
                    .includeEmptyValue()
                    .includeAs(ACL.SYSTEM,
                    Jenkins.get(),
                    FileCredentials.class)
                    .includeCurrentValue(credentialId);
        }

        /**
         * validating the credentialId
         */
        public FormValidation doCheckCredentialId(@AncestorInPath Item item, @QueryParameter String value) {
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return FormValidation.ok();
                }
            } else {
                if (!item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                    return FormValidation.ok();
                }
            }
            if (StringUtils.isBlank(value)) {
                return FormValidation.ok();
            }
            return FormValidation.ok();
        }
    }

    public BuildStepMonitor getRequiredMonitorService() {
         return BuildStepMonitor.NONE;
    }

    /**
     * Class to collect artifact hashes from remote hosts.
     *
     */
    private static final class ArtifactCollector
            extends MasterToSlaveFileCallable<HashMap<String, ArtifactHash>> {
        private static final long serialVersionUID = 1;

        @Override
        public HashMap<String, ArtifactHash> invoke(File f, VirtualChannel channel) {

            HashMap<String, ArtifactHash> result = new HashMap<String, ArtifactHash>();
            recurseAndCollect(f, result);
            return result;
        }

        private static void recurseAndCollect(File f, HashMap<String, ArtifactHash> hashmap) {

            if (f.exists() && f.isFile()) {
                Artifact artifact = new Artifact(f.toString());
                hashmap.put(artifact.getURI(), artifact.getArtifactHashes());
            } else if (f.exists() && f.isDirectory()) {
                File[] contents = f.listFiles();
                if (contents != null) {
                for (int i = 0; i < contents.length; i++) {
                    recurseAndCollect(contents[i], hashmap);
                }
                }
            }
        }
    }
}

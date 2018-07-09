/**
 *
 */
package io.jenkins.plugins.in_totorecorder;

import io.in_toto.models.Link;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.RunList;
import hudson.FilePath;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * Builder that discards old build histories according to more detail
 * configurations than the core function.
 * This enables discarding builds by build status or keeping older builds for
 * every N builds / N days
 * or discarding buildswhich has too small or too big logfile size.
 *
 * @author SantiagoTorres 
 */
public class in_totoRecorder extends Recorder {

    /**
     * Location of the key to load.
     *
     * If not defined signing will not be performed.
     */
    private String keyPath;

    /**
     * Name of the step to execute
     *
     * If not defined, will default to step
     */
    private String stepName;

    @DataBoundConstructor
    public in_totoRecorder(String keyPath, String stepName) 
    { 
        this.keyPath = keyPath;
        this.stepName = stepName;
    }

    @Override 
    public boolean prebuild(AbstractBuild<?,?> build, BuildListener listener)  {

        FilePath cwd = build.getWorkspace();
        String  cwdStr = cwd.toString();

        listener.getLogger().println("[in-toto] Recording state before build" + cwdStr);

        Link link = new Link(null, null, this.stepName, null, null, null);

        throw new RuntimeException("Wtf is happening");

        //return true;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
        listener.getLogger().println("[in-toto] Dumping metadata."); 

        throw new RuntimeException("Wtf is happening");
        //return true;
    }

    public String getKeyPath() {
        return this.keyPath;
    }

    public String getStepName() {
        return this.stepName;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * Descriptor for {@link in_totoRecorder}. Used as a singleton. The class is
     * marked as public so that it can be accessed from views.
     * 
     *
     * See
     * <tt>src/main/resources/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @SuppressWarnings("rawtypes")
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {return "in-toto provenance plugin";}
    }

    public BuildStepMonitor getRequiredMonitorService() {
         return BuildStepMonitor.NONE;
    }

}

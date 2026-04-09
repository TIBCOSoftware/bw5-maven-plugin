package com.tibco.bw.maven.plugin.packaging;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Initializes the BW5 build environment.
 * Validates the BW project structure and creates required output directories.
 *
 */
@Mojo(
    name = "initialize",
    defaultPhase = LifecyclePhase.INITIALIZE,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
    threadSafe = false
)
public class InitializeMojo extends AbstractBw5Mojo {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("bw5:initialize skipped.");
            return;
        }

        getLog().info("Initializing BW5 build environment...");
        getLog().info("BW project path: " + bwProjectPath.getAbsolutePath());
        getLog().info("Archive name   : " + archiveName);

        validateBwProjectPath();

        // Create working directories
        mkdirs(bwSourcesDirectory, "bw-src");
        mkdirs(bwLibDirectory, "bw-lib");
        mkdirs(javaSourcesDirectory, "generated-sources/bw-java");
        mkdirs(javaClassesDirectory, "bw-classes");

        getLog().info("BW5 build environment initialized.");
    }

    private void mkdirs(java.io.File dir, String name) throws MojoExecutionException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new MojoExecutionException("Could not create directory: " + dir.getAbsolutePath());
        }
        getLog().debug("Directory ready: " + dir.getAbsolutePath());
    }
}

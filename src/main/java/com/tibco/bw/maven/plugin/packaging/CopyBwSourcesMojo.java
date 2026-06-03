package com.tibco.bw.maven.plugin.packaging;

import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;

/**
 * Copies BW5 project sources to the build working directory ({@code target/bw-src}).
 *
 * <p>This ensures the build process never modifies the original source files.
 * Subsequent goals (alias updates, descriptor generation) operate on the copy.</p>
 *
 */
@Mojo(
    name = "copy-bw-sources",
    defaultPhase = LifecyclePhase.GENERATE_SOURCES,
    requiresDependencyResolution = ResolutionScope.NONE,
    threadSafe = false
)
public class CopyBwSourcesMojo extends AbstractBw5Mojo {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("bw5:copy-bw-sources skipped.");
            return;
        }

        validateBwProjectPath();

        getLog().info("Copying BW sources from: " + bwProjectPath.getAbsolutePath());
        getLog().info("                     to: " + bwSourcesDirectory.getAbsolutePath());

        try {
            if (bwSourcesDirectory.exists()) {
                FileUtils.cleanDirectory(bwSourcesDirectory);
            } else {
                if (!bwSourcesDirectory.mkdirs() && !bwSourcesDirectory.isDirectory()) {
                    throw new MojoExecutionException("Failed to create directory: " + bwSourcesDirectory.getAbsolutePath());
                }
            }
            // Exclude the Maven build directory (target/) from the copy — it lives inside
            // basedir and would otherwise be copied recursively into target/bw-src/target/...
            final File buildDir = bwSourcesDirectory.getParentFile().getCanonicalFile();
            FileFilter excludeBuildDir = f -> {
                try {
                    return !f.getCanonicalFile().equals(buildDir);
                } catch (IOException ex) {
                    return true;
                }
            };
            FileUtils.copyDirectory(bwProjectPath, bwSourcesDirectory, excludeBuildDir);
            getLog().info("BW sources copied successfully.");
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to copy BW sources: " + e.getMessage(), e);
        }
    }
}

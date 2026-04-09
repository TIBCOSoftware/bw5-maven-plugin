package com.tibco.bw.maven.plugin.packaging;

import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Resolves and copies BW5 dependencies (projlibs and JARs) to the build lib directory
 * ({@code target/bw-lib}).
 *
 * <p>Projlib dependencies are resolved from the Maven repository and copied with
 * the convention name {@code artifactId-version.projlib}.</p>
 *
 * <p>JAR dependencies (compile+runtime scope) are copied with the convention name
 * {@code artifactId-version.jar} for reference in TIBCO.xml FileAliases.</p>
 *
 */
@Mojo(
    name = "resolve-dependencies",
    defaultPhase = LifecyclePhase.PROCESS_RESOURCES,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
    threadSafe = false
)
public class ResolveDependenciesMojo extends AbstractBw5Mojo {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("bw5:resolve-dependencies skipped.");
            return;
        }

        bwLibDirectory.mkdirs();

        List<Artifact> projlibs = getProjectlibDependencies();
        List<Artifact> jars = getJarDependencies();

        if (projlibs.isEmpty() && jars.isEmpty()) {
            getLog().info("No BW5 dependencies to resolve.");
            return;
        }

        getLog().info("Resolving BW5 dependencies to: " + bwLibDirectory.getAbsolutePath());

        try {
            for (Artifact projlib : projlibs) {
                String targetName = projlib.getArtifactId() + "-" + projlib.getVersion() + ".projlib";
                File target = new File(bwLibDirectory, targetName);
                FileUtils.copyFile(projlib.getFile(), target);
                getLog().info("  projlib: " + targetName);
            }

            for (Artifact jar : jars) {
                String targetName = jar.getArtifactId() + "-" + jar.getVersion() + ".jar";
                File target = new File(bwLibDirectory, targetName);
                FileUtils.copyFile(jar.getFile(), target);
                getLog().debug("  jar: " + targetName);
            }

            getLog().info("Resolved " + projlibs.size() + " projlib(s) and "
                + jars.size() + " jar(s).");

        } catch (IOException e) {
            throw new MojoExecutionException("Failed to resolve dependencies: " + e.getMessage(), e);
        }
    }
}

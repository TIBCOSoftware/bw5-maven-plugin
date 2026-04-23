package com.tibco.bw.maven.plugin.packaging;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

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
        resolveDependencies();
    }
}

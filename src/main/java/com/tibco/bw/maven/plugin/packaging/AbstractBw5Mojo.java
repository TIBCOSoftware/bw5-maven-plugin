package com.tibco.bw.maven.plugin.packaging;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Base class for all BW5 Maven plugin Mojos.
 * Provides common parameters and utility methods for BW5 project handling.
 */
public abstract class AbstractBw5Mojo extends AbstractMojo {

    /** The Maven project. */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    /**
     * Root directory of the BW5 project sources.
     * This should point to the folder containing the BW Designer project
     * (the folder with .process files, SharedResources/, defaultVars/, etc.).
     * Defaults to ${basedir} (pom.xml lives alongside the BW project files).
     */
    @Parameter(defaultValue = "${basedir}", property = "bw5.bwProjectPath")
    protected File bwProjectPath;

    /**
     * Working directory where BW sources are copied for processing.
     * Defaults to ${project.build.directory}/bw-src.
     */
    @Parameter(defaultValue = "${project.build.directory}/bw-src", readonly = true)
    protected File bwSourcesDirectory;

    /**
     * Directory where projlib/JAR dependencies are resolved.
     * Defaults to ${project.build.directory}/bw-lib.
     */
    @Parameter(defaultValue = "${project.build.directory}/bw-lib", readonly = true)
    protected File bwLibDirectory;

    /**
     * Directory where extracted Java sources from .process files are placed.
     * Defaults to ${project.build.directory}/generated-sources/bw-java.
     */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/bw-java", readonly = true)
    protected File javaSourcesDirectory;

    /**
     * Directory where compiled Java classes are placed.
     * Defaults to ${project.build.directory}/bw-classes.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    protected File javaClassesDirectory;

    /**
     * Name of the Process Archive (PAR) within the EAR.
     * Defaults to ${project.artifactId}.
     * For projects with multiple archive descriptors, specify which one to use.
     */
    @Parameter(defaultValue = "${project.artifactId}", property = "bw5.archiveName")
    protected String archiveName;

    /**
     * Skip execution of this plugin.
     */
    @Parameter(defaultValue = "false", property = "bw5.skip")
    protected boolean skip;

    /**
     * Returns all dependencies of type bw5module (projlibs).
     */
    protected List<Artifact> getProjectlibDependencies() {
        List<Artifact> projlibs = new ArrayList<>();
        Set<Artifact> artifacts = project.getArtifacts();
        if (artifacts != null) {
            for (Artifact artifact : artifacts) {
                if ("projlib".equals(artifact.getType())) {
                    projlibs.add(artifact);
                }
            }
        }
        return projlibs;
    }

    /**
     * Returns all JAR dependencies (compile + runtime scope, excluding projlibs).
     */
    protected List<Artifact> getJarDependencies() {
        List<Artifact> jars = new ArrayList<>();
        Set<Artifact> artifacts = project.getArtifacts();
        if (artifacts != null) {
            for (Artifact artifact : artifacts) {
                String type = artifact.getType();
                String scope = artifact.getScope();
                if ("jar".equals(type)
                        && !Artifact.SCOPE_PROVIDED.equals(scope)
                        && !Artifact.SCOPE_TEST.equals(scope)) {
                    jars.add(artifact);
                }
            }
        }
        return jars;
    }

    /**
     * Validates that the BW project path exists and contains BW project files.
     */
    protected void validateBwProjectPath() throws MojoExecutionException {
        if (!bwProjectPath.exists()) {
            throw new MojoExecutionException(
                "BW project path does not exist: " + bwProjectPath.getAbsolutePath()
                + "\nConfigure <bwProjectPath> in your pom.xml or place your BW project under src/main/bw"
            );
        }
        if (!bwProjectPath.isDirectory()) {
            throw new MojoExecutionException(
                "BW project path is not a directory: " + bwProjectPath.getAbsolutePath()
            );
        }
        // Check that it looks like a BW project (has .process files or .folder files)
        boolean hasBwFiles = containsBwFiles(bwProjectPath);
        if (!hasBwFiles) {
            getLog().warn("BW project path does not appear to contain BW project files (.process, .folder): "
                + bwProjectPath.getAbsolutePath());
        }
    }

    private boolean containsBwFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.isFile()) {
                String name = f.getName();
                if (name.endsWith(".process") || name.endsWith(".folder")) {
                    return true;
                }
            } else if (f.isDirectory()) {
                if (containsBwFiles(f)) return true;
            }
        }
        return false;
    }
}

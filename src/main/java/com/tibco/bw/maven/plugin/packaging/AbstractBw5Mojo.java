package com.tibco.bw.maven.plugin.packaging;

import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Base class for all BW5 Maven plugin Mojos.
 * Provides common parameters and utility methods for BW5 project handling.
 */
public abstract class AbstractBw5Mojo extends AbstractMojo {

    /** The Maven project. */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    /** The Maven session (provides access to CLI -D user properties). */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;

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
     * Returns a merged view of all Maven properties visible to this mojo.
     *
     * <p>POM {@code <properties>} entries are loaded first; CLI {@code -D} flags
     * (session user properties) are overlaid on top, so they take the highest priority.
     * This is the correct source for {@code bw5.global.*} and {@code bw5.project.*}
     * override resolution.</p>
     */
    protected Map<String, String> getAllMavenProperties() {
        Map<String, String> props = new LinkedHashMap<>();
        Properties pomProps = project.getProperties();
        for (String key : pomProps.stringPropertyNames()) {
            props.put(key, pomProps.getProperty(key));
        }
        // CLI -D user properties override POM properties
        Properties userProps = session.getUserProperties();
        for (String key : userProps.stringPropertyNames()) {
            props.put(key, userProps.getProperty(key));
        }
        return props;
    }

    /**
     * Copies all projlib and JAR dependencies to {@code bwLibDirectory} (target/bw-lib).
     * Called by mojos that need dependencies on disk before they can proceed.
     */
    protected void resolveDependencies() throws MojoExecutionException {
        if (!bwLibDirectory.mkdirs() && !bwLibDirectory.isDirectory()) {
            throw new MojoExecutionException("Failed to create directory: " + bwLibDirectory.getAbsolutePath());
        }

        List<Artifact> projlibs = getProjectlibDependencies();
        List<Artifact> jars     = getJarDependencies();

        if (projlibs.isEmpty() && jars.isEmpty()) {
            getLog().info("No BW5 dependencies to resolve.");
            return;
        }

        getLog().info("Resolving BW5 dependencies to: " + bwLibDirectory.getAbsolutePath());
        try {
            for (Artifact projlib : projlibs) {
                String name = projlib.getArtifactId() + "-" + projlib.getVersion() + ".projlib";
                FileUtils.copyFile(projlib.getFile(), new File(bwLibDirectory, name));
                getLog().info("  projlib: " + name);
            }
            for (Artifact jar : jars) {
                String name = jar.getArtifactId() + "-" + jar.getVersion() + ".jar";
                FileUtils.copyFile(jar.getFile(), new File(bwLibDirectory, name));
                getLog().debug("  jar: " + name);
            }
            getLog().info("Resolved " + projlibs.size() + " projlib(s) and " + jars.size() + " jar(s).");
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to resolve dependencies: " + e.getMessage(), e);
        }
    }

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
     * Returns the effective BW source directory every goal should read from: the staged copy
     * ({@code target/bw-src}, produced by {@code copy-bw-sources} and enriched with compiled JCF
     * bytecode / resolved libs) when it exists, otherwise the configured {@code bwProjectPath}.
     *
     * <p>All goals resolve BW sources through this single method so the {@code bwProjectPath}
     * property is honored consistently — whether a goal runs standalone (uses {@code bwProjectPath})
     * or as part of the build lifecycle (uses the processed {@code target/bw-src}).</p>
     */
    protected File getEffectiveSourceDir() {
        return (bwSourcesDirectory != null && bwSourcesDirectory.exists())
            ? bwSourcesDirectory : resolveBwSourceDir();
    }

    /**
     * Resolves the actual BW project source directory (the folder that contains the Designer
     * project — marked by {@code vcrepo.dat}).
     *
     * <p>Normally this is {@code bwProjectPath}. But when the pom lives ABOVE the BW project
     * (e.g. the sources are under {@code src/main/bw} or {@code src/main/tibco/<name>} while the
     * pom is at the module root), {@code bwProjectPath} defaults to the module root, which is NOT
     * the Designer project folder. In that case this auto-detects the nearest descendant that IS a
     * BW project root (contains {@code vcrepo.dat}) so every goal — including launching Designer —
     * operates on the real sources.</p>
     *
     * <p>Safe by construction: when {@code bwProjectPath} itself contains {@code vcrepo.dat} it is
     * returned unchanged (the common case), so auto-detection never overrides an explicit or
     * conventional layout.</p>
     */
    protected File resolveBwSourceDir() {
        if (bwProjectPath == null) return bwProjectPath;
        if (new File(bwProjectPath, "vcrepo.dat").isFile()) return bwProjectPath;
        File detected = findBwProjectRoot(bwProjectPath, 5);
        if (detected != null) {
            getLog().info("Auto-detected BW project root: " + detected.getAbsolutePath()
                + " (pom lives above the BW sources)");
            return detected;
        }
        return bwProjectPath;
    }

    /**
     * Bounded breadth-first search for the shallowest directory under {@code root} that is a BW
     * project root (contains {@code vcrepo.dat}). Skips build output ({@code target}) and hidden
     * directories. Returns {@code null} if none is found within {@code maxDepth} levels.
     */
    private File findBwProjectRoot(File root, int maxDepth) {
        List<File> current = new ArrayList<>();
        current.add(root);
        for (int depth = 0; depth <= maxDepth && !current.isEmpty(); depth++) {
            List<File> next = new ArrayList<>();
            for (File dir : current) {
                File[] children = dir.listFiles();
                if (children == null) continue;
                for (File c : children) {
                    if (!c.isDirectory() || c.getName().startsWith(".")
                            || "target".equals(c.getName())) {
                        continue;
                    }
                    if (new File(c, "vcrepo.dat").isFile()) {
                        return c;
                    }
                    next.add(c);
                }
            }
            current = next;
        }
        return null;
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

    /**
     * Derives the global-variable folder prefix from the relative path of a {@code .substvar} file.
     *
     * <p>BW5 stores substitution variable files under {@code defaultVars/}. Any subdirectory
     * between {@code defaultVars/} and the file itself becomes part of the variable name,
     * matching the way TIBCO Designer and AppManage qualify variable names.
     *
     * <p>Example: {@code defaultVars/Connections/JMS.substvar} → prefix {@code "Connections/"}.
     * A file directly inside {@code defaultVars/} (e.g. {@code defaultVars/default.substvar})
     * returns an empty prefix.
     *
     * @param relativePath path of the {@code .substvar} file relative to the project source root,
     *                     using forward slashes
     * @return folder prefix to prepend to each variable name, or {@code ""} if none
     */
    protected static String computeGvPrefix(String relativePath) {
        String marker = "defaultVars/";
        int idx = relativePath.indexOf(marker);
        if (idx < 0) return "";
        String afterMarker = relativePath.substring(idx + marker.length());
        int lastSlash = afterMarker.lastIndexOf('/');
        if (lastSlash < 0) return "";
        return afterMarker.substring(0, lastSlash + 1);
    }

    /**
     * TIBCO installation root directory (e.g. {@code /opt/tibco} or {@code C:\tibco}).
     * Used by goals that need to locate TIBCO executables (Designer, BW engine, etc.).
     *
     * <p>If not set, the {@code TIBCO_HOME} environment variable is used as a fallback.</p>
     *
     * <p>Example: {@code -Dbw5.tibcoHome=/opt/tibco}</p>
     */
    @Parameter(property = "bw5.tibcoHome")
    protected File tibcoHome;

    /**
     * Returns the effective TIBCO home: {@code bw5.tibcoHome} if set, otherwise the
     * {@code TIBCO_HOME} environment variable, or {@code null} if neither is available.
     */
    protected File resolvedTibcoHome() {
        if (tibcoHome != null) return tibcoHome;
        String envHome = System.getenv("TIBCO_HOME");
        return (envHome != null && !envHome.isEmpty()) ? new File(envHome) : null;
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

package com.tibco.bw.maven.plugin.designer;

import com.tibco.bw.maven.plugin.packaging.AbstractBw5Mojo;
import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Downloads and stages BW5 projlib/JAR dependencies for use with TIBCO Designer.
 *
 * <p>Run this goal once (or whenever dependencies change) before opening the project
 * in TIBCO Designer:</p>
 * <pre>
 *   mvn bw5:designer-setup
 * </pre>
 *
 * <p>What it does:</p>
 * <ol>
 *   <li>Resolves all {@code projlib} and {@code jar} dependencies from the Maven repository.</li>
 *   <li>Copies them to {@code ${bw5.designerLibsDir}} (default: {@code ${project.build.directory}/designer-libs}).
 *       The directory is created if it doesn't exist. Files are only copied if the destination
 *       is missing or has a different size, making the goal idempotent and fast on re-runs.</li>
 *   <li>Updates (or creates) the {@code .designtimelibs} file in the BW project source directory
 *       (auto-detected when the pom lives above the sources, e.g. {@code src/main/tibco/<name>})
 *       with entries in the format {@code N=groupId:artifactId:version:type\=} that TIBCO Designer
 *       understands when the bw-maven-plugin Designer integration is configured.</li>
 * </ol>
 *
 * <p>The default libs cache lives under {@code target/} so git ignores it automatically. If you
 * override {@code bw5.designerLibsDir} to a location outside {@code target/}, the goal adds that
 * directory to {@code .gitignore} since it contains resolved artifacts that should not be committed.</p>
 *
 */
@Mojo(
    name = "designer-setup",
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
    threadSafe = false
)
public class PullMojo extends AbstractBw5Mojo {

    /**
     * Directory where dependencies will be staged for Designer use.
     * Defaults to {@code ${project.build.directory}/designer-libs} — under {@code target/}, so the
     * staged JAR cache is auto-ignored by git, never pollutes the BW source tree (not copied into
     * {@code target/bw-src}), and is regenerated together with the Designer prefs (which also live
     * under {@code target/}) on the next {@code designer-setup} after a {@code clean}. Designer
     * resolves the JARs via the generated prefs (absolute paths), so the location is flexible.
     * Override with {@code -Dbw5.designerLibsDir} for a persistent location outside {@code target/}.
     */
    @Parameter(defaultValue = "${project.build.directory}/designer-libs", property = "bw5.designerLibsDir")
    private File designerLibsDir;

    /**
     * Path to the BW project source where the {@code .designtimelibs} file will be written.
     * Defaults to {@code ${bw5.bwProjectPath}} (same as the build source path).
     */
    @Parameter(property = "bw5.designtimeLibsDir")
    private File designtimeLibsDir;

    /**
     * If true, always overwrites staged files even if they appear to be up-to-date.
     * Defaults to false (idempotent: skips files that are already staged correctly).
     */
    @Parameter(defaultValue = "false", property = "bw5.designerSetup.force")
    private boolean force;

    /**
     * When {@code true}, launches TIBCO Designer after staging the dependencies.
     * Requires {@code bw5.tibcoHome} (or the {@code TIBCO_HOME} environment variable)
     * to be set so the plugin can locate the Designer executable.
     *
     * <pre>mvn bw5:designer-setup -Dbw5.designerSetup.launchDesigner=true</pre>
     */
    @Parameter(defaultValue = "false", property = "bw5.designerSetup.launchDesigner")
    private boolean shouldLaunchDesigner;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("bw5:designer-setup skipped.");
            return;
        }

        // The real BW project folder (with vcrepo.dat) — auto-detected when the pom lives above
        // the sources (e.g. src/main/tibco/<name>). The .designtimelibs file must live INSIDE this
        // folder (Designer reads it from the project it opens). The .designer-libs JAR cache, by
        // contrast, stays at the module root (see designerLibsDir) so it does not pollute the BW
        // source tree.
        File bwSourceDir = resolveBwSourceDir();

        // Resolve effective .designtimelibs target dir
        File libsSourceDir = (designtimeLibsDir != null) ? designtimeLibsDir : bwSourceDir;

        List<Artifact> projlibs = getProjectlibDependencies();
        List<Artifact> jars = getJarDependencies();

        if (projlibs.isEmpty() && jars.isEmpty()) {
            getLog().info("No projlib or JAR dependencies declared — nothing to pull.");
            if (shouldLaunchDesigner) {
                launchDesigner();
            }
            return;
        }

        getLog().info("Pulling BW5 dependencies to: " + designerLibsDir.getAbsolutePath());
        if (!designerLibsDir.mkdirs() && !designerLibsDir.isDirectory()) {
            throw new MojoExecutionException("Failed to create designer libs directory: " + designerLibsDir.getAbsolutePath());
        }

        int copied = 0;
        int skipped = 0;

        // Stage projlibs
        List<StagedDep> stagedProjlibs = new ArrayList<>();
        for (Artifact projlib : projlibs) {
            StagedDep dep = stage(projlib, "projlib");
            stagedProjlibs.add(dep);
            if (dep.wasCopied) copied++; else skipped++;
        }

        // Stage JARs (for Designer classpath / FileAlias use)
        List<StagedDep> stagedJars = new ArrayList<>();
        for (Artifact jar : jars) {
            StagedDep dep = stage(jar, "jar");
            stagedJars.add(dep);
            if (dep.wasCopied) copied++; else skipped++;
        }

        getLog().info("Staged: " + copied + " file(s) copied, " + skipped + " already up-to-date.");

        // Update .designtimelibs in BW project source — projlibs only, Maven coordinate format
        if (libsSourceDir.exists()) {
            updateDesigntimeLibs(libsSourceDir, stagedProjlibs);
        } else {
            getLog().warn(".designtimelibs not written: BW project path does not exist: "
                + libsSourceDir.getAbsolutePath()
                + "\nRun bw5:designer-setup after the project source is in place, or set bw5.designtimeLibsDir.");
        }

        // Generate target/.TIBCO/Designer5.prefs with FileAlias entries for all staged deps
        writeDesigner5Prefs(stagedProjlibs, stagedJars);

        // Generate target/.TIBCO/designer.tra: a copy of the real designer.tra with the staged JARs
        // prepended to CUSTOM_CP_EXT. File Aliases only resolve projlib/resource references; the Java
        // classes used by Java activities (e.g. the *InterfacesJLib jars) must be on the actual JVM
        // classpath, which the TRA launcher builds from CUSTOM_CP_EXT. Designer is then launched with
        // --propFile <this copy>, so the generic installation tra is never modified.
        prepareDesignerTra(stagedJars);

        // Only manage .gitignore when the libs cache is OUTSIDE target/ (an override). The default
        // location is under target/, which git ignores already.
        if (!isUnderBuildDirectory(designerLibsDir)) {
            ensureGitignore();
        }

        // Optionally launch Designer
        if (shouldLaunchDesigner) {
            launchDesigner();
        }
    }

    /**
     * Generates {@code target/.TIBCO/Designer5.prefs} with {@code filealias.pref.N} entries
     * pointing to the staged dependency files.
     *
     * <p>Reads {@code ~/.TIBCO/Designer5.prefs} for existing preferences (palette settings,
     * recent projects, etc.), strips all {@code filealias.pref.*} lines, and re-appends them
     * with the current Maven-resolved paths. The result is written to
     * {@code target/.TIBCO/Designer5.prefs} — the real {@code ~/.TIBCO/} file is never touched,
     * so multiple projects can be set up independently without interfering with each other.</p>
     *
     * <p>To make Designer read this file instead of {@code ~/.TIBCO/Designer5.prefs}, the
     * {@code designer.tra} must contain {@code java.property.user.home=<project>/target},
     * which translates to {@code -Duser.home=<project>/target} on the JVM. See
     * {@link #prepareDesignerTra(File)}.</p>
     */
    /**
     * Escapes a file-system path for inclusion in the value of a Java properties-format entry
     * (Designer5.prefs). Only backslashes need doubling so Windows paths survive the un-escaping
     * Designer performs on read. No-op for POSIX paths (forward slashes). Package-private for tests.
     */
    static String escapePrefsPath(String path) {
        return path == null ? "" : path.replace("\\", "\\\\");
    }

    private void writeDesigner5Prefs(List<StagedDep> stagedProjlibs, List<StagedDep> stagedJars)
            throws MojoExecutionException {

        // Base prefs on the real system file so palette/window settings are preserved
        File systemPrefs = new File(System.getProperty("user.home"), ".TIBCO/Designer5.prefs");
        List<String> lines = new ArrayList<>();
        if (systemPrefs.exists()) {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(java.nio.file.Files.newInputStream(systemPrefs.toPath()), StandardCharsets.ISO_8859_1))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.startsWith("filealias.pref.")) {
                        lines.add(line);
                    }
                }
            } catch (IOException e) {
                getLog().warn("Could not read " + systemPrefs + ": " + e.getMessage()
                    + " — generating prefs from scratch.");
            }
        }

        // Append filealias entries: projlibs first, then JARs.
        // The .prefs is a Java properties-format file, so backslashes in the value are un-escaped
        // when Designer reads it. Windows paths (C:\Users\...\target\...) must therefore have their
        // backslashes doubled, otherwise sequences like "\t"/"\U" are mangled (e.g. "\target" -> TAB)
        // and every path breaks. On POSIX (forward slashes) this is a no-op.
        int idx = 0;
        for (StagedDep dep : stagedProjlibs) {
            Artifact a = dep.artifact;
            lines.add("filealias.pref." + idx + "="
                + a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion() + ":projlib"
                + "\\=" + escapePrefsPath(dep.stagedFile.getAbsolutePath()));
            idx++;
        }
        for (StagedDep dep : stagedJars) {
            Artifact a = dep.artifact;
            lines.add("filealias.pref." + idx + "="
                + a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion() + ":jar"
                + "\\=" + escapePrefsPath(dep.stagedFile.getAbsolutePath()));
            idx++;
        }

        File targetPrefs = new File(project.getBuild().getDirectory(), ".TIBCO/Designer5.prefs");
        File prefsParent = targetPrefs.getParentFile();
        if (!prefsParent.mkdirs() && !prefsParent.isDirectory()) {
            throw new MojoExecutionException("Failed to create directory: " + prefsParent.getAbsolutePath());
        }
        try (Writer w = new OutputStreamWriter(
                java.nio.file.Files.newOutputStream(targetPrefs.toPath()), StandardCharsets.ISO_8859_1)) {
            for (String line : lines) {
                w.write(line);
                w.write("\n");
            }
        } catch (IOException e) {
            throw new MojoExecutionException(
                "Failed to write Designer5.prefs: " + e.getMessage(), e);
        }

        getLog().info("Generated: " + targetPrefs.getAbsolutePath()
            + " (" + idx + " filealias entries)");
        if (!shouldLaunchDesigner) {
            getLog().info("To open Designer with these aliases, run:");
            getLog().info("  JAVA_TOOL_OPTIONS=\"-Duser.home=" + project.getBuild().getDirectory()
                + "\" designer " + bwProjectPath.getAbsolutePath());
        }
    }

    /**
     * Stages a single artifact to the designer libs directory.
     * Skips the copy if the destination file already exists with the same size (unless force=true).
     */
    private StagedDep stage(Artifact artifact, String type) throws MojoExecutionException {
        if (artifact.getFile() == null || !artifact.getFile().exists()) {
            throw new MojoExecutionException(
                "Artifact file not found for " + artifact.getId()
                + ". Run 'mvn dependency:resolve' first.");
        }

        String fileName = artifact.getArtifactId() + "-" + artifact.getVersion() + "." + type;
        File dest = new File(designerLibsDir, fileName);
        File src = artifact.getFile();

        boolean needsCopy = force
            || !dest.exists()
            || dest.length() != src.length();

        if (needsCopy) {
            try {
                FileUtils.copyFile(src, dest);
                getLog().info("  + " + fileName + " (" + humanSize(src.length()) + ")");
                return new StagedDep(artifact, dest, true);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to stage " + fileName + ": " + e.getMessage(), e);
            }
        } else {
            getLog().debug("  = " + fileName + " (up-to-date)");
            return new StagedDep(artifact, dest, false);
        }
    }

    /**
     * Writes or updates the {@code .designtimelibs} file in the BW project source directory.
     *
     * <p>Format used by TIBCO Designer with the bw-maven-plugin Designer integration:</p>
     * <pre>
     * #Design time libraries
     * #Format: #=File Alias=Description
     * #&lt;timestamp&gt;
     * 0=groupId\:artifactId\:version\:projlib\=
     * 1=groupId\:artifactId\:version\:projlib\=
     * </pre>
     *
     * <p>Only projlib entries are written — JAR dependencies are not listed here.
     * Colons are escaped as {@code \:} and each entry ends with {@code \=} as required
     * by the Java properties-like format TIBCO Designer reads.</p>
     */
    /**
     * Returns the version-agnostic {@code groupId:artifactId} key of a
     * {@code groupId:artifactId:version:type} coordinate, or the input unchanged if it has fewer
     * than two colon-separated segments. Package-private for tests.
     */
    static String groupArtifactKey(String coord) {
        if (coord == null) return "";
        int firstColon = coord.indexOf(':');
        if (firstColon < 0) return coord;
        int secondColon = coord.indexOf(':', firstColon + 1);
        return secondColon < 0 ? coord : coord.substring(0, secondColon);
    }

    private void updateDesigntimeLibs(File projectDir, List<StagedDep> stagedProjlibs)
            throws MojoExecutionException {

        File designtimeLibsFile = new File(projectDir, ".designtimelibs");

        // Build the set of Maven coordinates for projlibs managed by this plugin
        LinkedHashSet<String> managedCoords = new LinkedHashSet<>();
        // ...and the version-agnostic groupId:artifactId keys, to drop STALE entries for the same
        // artifact at a different version (e.g. a leftover CommonFramework:4.5.0 when the project
        // now resolves CommonFramework:4.1.0). Such stale coords have no File Alias and make
        // Designer report "invalid file alias / not a .projlib".
        LinkedHashSet<String> managedKeys = new LinkedHashSet<>();
        for (StagedDep dep : stagedProjlibs) {
            Artifact a = dep.artifact;
            managedCoords.add(a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion() + ":projlib");
            managedKeys.add(a.getGroupId() + ":" + a.getArtifactId());
        }

        // Read existing entries; preserve manual ones (valid coord entries not in our managed set)
        List<String> manualCoords = new ArrayList<>();
        if (designtimeLibsFile.exists()) {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(java.nio.file.Files.newInputStream(designtimeLibsFile.toPath()), StandardCharsets.ISO_8859_1))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("#") || line.isEmpty()) continue;
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String value = line.substring(eq + 1);
                    if (value.endsWith("\\=")) value = value.substring(0, value.length() - 2);
                    value = value.replace("\\:", ":");
                    if (!managedCoords.contains(value) && value.contains(":") && !value.startsWith("/")
                            && !managedKeys.contains(groupArtifactKey(value))) {
                        manualCoords.add(value);
                        getLog().debug("Preserving manual .designtimelibs entry: " + value);
                    } else if (managedKeys.contains(groupArtifactKey(value))
                            && !managedCoords.contains(value)) {
                        getLog().info("Dropping stale .designtimelibs entry (superseded version): " + value);
                    }
                }
            } catch (IOException e) {
                getLog().warn("Could not read existing .designtimelibs: " + e.getMessage());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#Design time libraries\n");
        sb.append("#Format: #=File Alias=Description\n");
        sb.append("#").append(new java.util.Date()).append("\n");

        int idx = 0;
        for (String coord : managedCoords) {
            sb.append(idx++).append("=").append(coord.replace(":", "\\:")).append("\\=\n");
        }
        for (String coord : manualCoords) {
            sb.append(idx++).append("=").append(coord.replace(":", "\\:")).append("\\=\n");
        }

        try (Writer w = new OutputStreamWriter(
                java.nio.file.Files.newOutputStream(designtimeLibsFile.toPath()), StandardCharsets.ISO_8859_1)) {
            w.write(sb.toString());
        } catch (IOException e) {
            throw new MojoExecutionException(
                "Failed to write .designtimelibs: " + e.getMessage(), e);
        }

        getLog().info("Updated: " + designtimeLibsFile.getAbsolutePath()
            + " (" + (managedCoords.size() + manualCoords.size()) + " entries)");
    }

    /**
     * Adds {@code .designer-libs/} to the project's {@code .gitignore} if not already present.
     */
    /** True when {@code dir} lives inside the Maven build directory (target/), which git ignores. */
    private boolean isUnderBuildDirectory(File dir) {
        try {
            String build = new File(project.getBuild().getDirectory()).getCanonicalPath()
                + File.separator;
            return (dir.getCanonicalPath() + File.separator).startsWith(build);
        } catch (IOException e) {
            return false;
        }
    }

    private void ensureGitignore() {
        File gitignore = new File(project.getBasedir(), ".gitignore");
        String entry = designerLibsDir.getName() + "/";
        try {
            if (gitignore.exists()) {
                String content = FileUtils.readFileToString(gitignore, StandardCharsets.UTF_8);
                if (!content.contains(entry)) {
                    FileUtils.writeStringToFile(gitignore,
                        content + (content.endsWith("\n") ? "" : "\n") + entry + "\n",
                        StandardCharsets.UTF_8);
                    getLog().info("Added '" + entry + "' to .gitignore");
                }
            } else {
                FileUtils.writeStringToFile(gitignore, entry + "\n", StandardCharsets.UTF_8);
                getLog().info("Created .gitignore with '" + entry + "'");
            }
        } catch (IOException e) {
            getLog().warn("Could not update .gitignore: " + e.getMessage()
                + "\nPlease add '" + entry + "' manually.");
        }
    }

    /**
     * Locates and launches the TIBCO Designer executable as a detached process,
     * opening the BW project directory.
     */
    private void launchDesigner() throws MojoExecutionException {
        File executable = findDesignerExecutable();
        if (executable == null) {
            throw new MojoExecutionException(
                "Cannot launch Designer: executable not found.\n"
                + "Set TIBCO_HOME environment variable or configure <tibcoHome> in the plugin.\n"
                + "Expected location: <tibcoHome>/designer/5.*/bin/designer[.exe]");
        }

        File binDir = executable.getParentFile();
        String targetDir = project.getBuild().getDirectory();
        // Open the actual BW project (the folder with vcrepo.dat), auto-detected when the pom
        // lives above the sources (e.g. src/main/tibco/<name>), not the Maven module root.
        File projectToOpen = resolveBwSourceDir();

        // JAVA_TOOL_OPTIONS is injected into the JVM before any TRA properties are applied.
        // Since designer.tra has no java.property.user.home entry, this sets user.home cleanly,
        // causing Designer to read target/.TIBCO/Designer5.prefs instead of ~/.TIBCO/Designer5.prefs.
        // This works with TIBCO's native embedded JVM launcher (JNI_CreateJavaVM honours it).
        // No TIBCO installation files are modified.
        String javaToolOptions = "-Duser.home=" + targetDir;

        // Use the project-local designer.tra copy (with the staged JARs on CUSTOM_CP_EXT) if present,
        // so Java activity classes resolve at design time. The generic installation tra is untouched.
        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        File traCopy = designerTraFile();
        if (traCopy.isFile()) {
            command.add("--propFile");
            command.add(traCopy.getAbsolutePath());
        }
        command.add(projectToOpen.getAbsolutePath());

        getLog().info("Launching Designer: " + executable.getAbsolutePath());
        getLog().info("Project directory : " + projectToOpen.getAbsolutePath());
        if (traCopy.isFile()) {
            getLog().info("Designer TRA       : " + traCopy.getAbsolutePath() + " (--propFile)");
        }
        getLog().info("JAVA_TOOL_OPTIONS  : " + javaToolOptions);

        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                .directory(binDir)
                .inheritIO();
            pb.environment().put("JAVA_TOOL_OPTIONS", javaToolOptions);
            pb.start();
            getLog().info("Designer launched (detached). You can continue working in the terminal.");
        } catch (IOException e) {
            throw new MojoExecutionException(
                "Failed to launch Designer: " + e.getMessage(), e);
        }
    }

    /**
     * Searches for the Designer executable under {@code tibcoHome} (or {@code TIBCO_HOME}).
     * Scans {@code <tibcoHome>/designer/<version>/bin/} for version directories starting
     * with {@code 5.}, looking for {@code designer} or {@code designer.exe}.
     * Returns {@code null} if nothing is found.
     */
    private File findDesignerExecutable() {
        File home = resolvedTibcoHome();
        if (home == null || !home.isDirectory()) return null;

        File designerRoot = new File(home, "designer");
        if (!designerRoot.isDirectory()) return null;

        // Find the first 5.x version directory
        File[] versionDirs = designerRoot.listFiles(f ->
            f.isDirectory() && Pattern.matches("5\\..*", f.getName()));
        if (versionDirs == null || versionDirs.length == 0) return null;

        // Sort to pick the highest version if multiple installs exist
        Arrays.sort(versionDirs, Comparator.comparing(File::getName).reversed());

        for (File versionDir : versionDirs) {
            File binDir = new File(versionDir, "bin");
            // Unix
            File exec = new File(binDir, "designer");
            if (exec.isFile()) return exec;
            // Windows
            exec = new File(binDir, "designer.exe");
            if (exec.isFile()) return exec;
        }
        return null;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    /** Location of the project-local designer.tra copy: {@code target/.TIBCO/designer.tra}. */
    private File designerTraFile() {
        return new File(project.getBuild().getDirectory(), ".TIBCO/designer.tra");
    }

    /**
     * Generates {@code target/.TIBCO/designer.tra}: a copy of the installed {@code designer.tra} with
     * the staged JAR paths prepended to {@code tibco.env.CUSTOM_CP_EXT}. The TRA launcher builds the
     * design-time Java classpath ({@code -Djava.class.path}) from that variable, so this is what puts
     * the {@code *InterfacesJLib} classes (used by Java activities) on the classpath and clears the
     * {@code BW-JAVA-100017 ClassNotFoundException} validation errors. Designer is launched with
     * {@code --propFile <this copy>}, leaving the generic installation tra untouched.
     *
     * <p>No-op (with a warning) when the installed designer.tra cannot be located (e.g. no TIBCO_HOME
     * on a build machine) or when there are no JARs to add.</p>
     */
    private void prepareDesignerTra(List<StagedDep> stagedJars) throws MojoExecutionException {
        if (stagedJars.isEmpty()) {
            return;
        }
        File executable = findDesignerExecutable();
        if (executable == null) {
            getLog().info("designer.tra not generated: Designer executable not found "
                + "(set TIBCO_HOME or <tibcoHome> to enable classpath injection for validation).");
            return;
        }
        File baseTra = new File(executable.getParentFile(), "designer.tra");
        if (!baseTra.isFile()) {
            getLog().warn("designer.tra not generated: base file not found next to executable: "
                + baseTra.getAbsolutePath());
            return;
        }

        List<String> jarPaths = new ArrayList<>();
        for (StagedDep dep : stagedJars) {
            jarPaths.add(dep.stagedFile.getAbsolutePath());
        }

        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                java.nio.file.Files.newInputStream(baseTra.toPath()), StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read " + baseTra + ": " + e.getMessage(), e);
        }

        List<String> injected = injectClasspath(lines, jarPaths, File.pathSeparator);

        File traCopy = designerTraFile();
        File parent = traCopy.getParentFile();
        if (!parent.mkdirs() && !parent.isDirectory()) {
            throw new MojoExecutionException("Failed to create directory: " + parent.getAbsolutePath());
        }
        try (Writer w = new OutputStreamWriter(
                java.nio.file.Files.newOutputStream(traCopy.toPath()), StandardCharsets.ISO_8859_1)) {
            for (String line : injected) {
                w.write(line);
                w.write("\n");
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write " + traCopy + ": " + e.getMessage(), e);
        }
        getLog().info("Generated: " + traCopy.getAbsolutePath()
            + " (" + jarPaths.size() + " JAR(s) added to CUSTOM_CP_EXT for design-time classpath)");
    }

    /**
     * Prepends {@code jarPaths} to the {@code tibco.env.CUSTOM_CP_EXT} entry of a designer.tra, joined
     * with {@code pathSep}. The entry is space-separated ({@code key<space>value}); the JARs are
     * inserted at the front of the value so they take precedence, and the original value is preserved.
     * If no {@code CUSTOM_CP_EXT} line exists, one is appended. Package-private for tests.
     *
     * <p>The TRA launcher un-escapes backslashes when reading the file (like a Java properties file:
     * {@code \t} becomes a TAB, other {@code \x} drop the backslash), so Windows paths in the injected
     * JARs must have their backslashes doubled or every path breaks. Only the injected paths are
     * escaped; the pre-existing value is preserved verbatim (TIBCO already stores it escaped).</p>
     */
    static List<String> injectClasspath(List<String> traLines, List<String> jarPaths, String pathSep) {
        final String key = "tibco.env.CUSTOM_CP_EXT";
        List<String> escaped = new ArrayList<>(jarPaths.size());
        for (String p : jarPaths) {
            escaped.add(escapePrefsPath(p)); // double backslashes for the TRA parser (no-op on POSIX)
        }
        String prefix = String.join(pathSep, escaped);
        List<String> out = new ArrayList<>(traLines.size() + 1);
        boolean found = false;
        for (String line : traLines) {
            if (!found && line.startsWith(key + " ")) {
                found = true;
                String existing = line.substring((key + " ").length());
                out.add(key + " " + prefix + (existing.isEmpty() ? "" : pathSep + existing));
            } else {
                out.add(line);
            }
        }
        if (!found) {
            out.add(key + " " + prefix);
        }
        return out;
    }

    private static class StagedDep {
        final Artifact artifact;
        final File stagedFile;
        final boolean wasCopied;

        StagedDep(Artifact artifact, File stagedFile, boolean wasCopied) {
            this.artifact = artifact;
            this.stagedFile = stagedFile;
            this.wasCopied = wasCopied;
        }
    }
}

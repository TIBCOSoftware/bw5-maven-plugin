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
 *   <li>Copies them to {@code ${bw5.designerLibsDir}} (default: {@code ${basedir}/.designer-libs}).
 *       The directory is created if it doesn't exist. Files are only copied if the destination
 *       is missing or has a different size, making the goal idempotent and fast on re-runs.</li>
 *   <li>Updates (or creates) the {@code .designtimelibs} file in the BW project source directory
 *       with entries in the format {@code N=groupId:artifactId:version:type\=} that TIBCO Designer
 *       understands when the bw-maven-plugin Designer integration is configured.</li>
 * </ol>
 *
 * <p>Add {@code .designer-libs/} to your {@code .gitignore} since it contains resolved
 * artifacts that should not be committed.</p>
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
     * Defaults to {@code ${basedir}/.designer-libs}.
     * Add this directory to your .gitignore.
     */
    @Parameter(defaultValue = "${basedir}/.designer-libs", property = "bw5.designerLibsDir")
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
    private boolean launchDesigner;

    /**
     * TIBCO installation root directory (e.g. {@code /opt/tibco} or {@code C:\tibco}).
     * Used to locate the Designer executable when {@code bw5.designerSetup.launchDesigner=true}.
     *
     * <p>If not set, the plugin checks the {@code TIBCO_HOME} environment variable.
     * The executable is expected at one of:</p>
     * <ul>
     *   <li>{@code <tibcoHome>/designer/<version>/bin/designer} (Unix)</li>
     *   <li>{@code <tibcoHome>/designer/<version>/bin/designer.exe} (Windows)</li>
     * </ul>
     */
    @Parameter(property = "bw5.tibcoHome")
    private File tibcoHome;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("bw5:designer-setup skipped.");
            return;
        }

        // Resolve effective .designtimelibs target dir
        File libsSourceDir = (designtimeLibsDir != null) ? designtimeLibsDir : bwProjectPath;

        List<Artifact> projlibs = getProjectlibDependencies();
        List<Artifact> jars = getJarDependencies();

        if (projlibs.isEmpty() && jars.isEmpty()) {
            getLog().info("No projlib or JAR dependencies declared — nothing to pull.");
            return;
        }

        getLog().info("Pulling BW5 dependencies to: " + designerLibsDir.getAbsolutePath());
        designerLibsDir.mkdirs();

        int copied = 0;
        int skipped = 0;

        // Stage projlibs
        List<StagedDep> staged = new ArrayList<>();
        for (Artifact projlib : projlibs) {
            StagedDep dep = stage(projlib, "projlib");
            staged.add(dep);
            if (dep.wasCopied) copied++; else skipped++;
        }

        // Stage JARs
        for (Artifact jar : jars) {
            StagedDep dep = stage(jar, "jar");
            staged.add(dep);
            if (dep.wasCopied) copied++; else skipped++;
        }

        getLog().info("Staged: " + copied + " file(s) copied, " + skipped + " already up-to-date.");

        // Update .designtimelibs in BW project source
        if (libsSourceDir.exists()) {
            updateDesigntimeLibs(libsSourceDir, projlibs, jars);
        } else {
            getLog().warn(".designtimelibs not written: BW project path does not exist: "
                + libsSourceDir.getAbsolutePath()
                + "\nRun bw5:designer-setup after the project source is in place, or set bw5.designtimeLibsDir.");
        }

        // Suggest .gitignore entry
        ensureGitignore();

        // Optionally launch Designer
        if (launchDesigner) {
            launchDesigner();
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
     * <p>Format used (compatible with TIBCO Designer + bw-maven-plugin integration):</p>
     * <pre>
     * #Design time libraries
     * #Format: #=File Alias=Description
     * #&lt;timestamp&gt;
     * 0=groupId:artifactId:version:type\=
     * 1=groupId:artifactId:version:type\=
     * </pre>
     *
     * <p>Projlibs are listed first (index 0..N), then JARs continue the numbering.
     * Existing entries not managed by this plugin are preserved.</p>
     */
    private void updateDesigntimeLibs(File projectDir, List<Artifact> projlibs, List<Artifact> jars)
            throws MojoExecutionException {

        File designtimeLibsFile = new File(projectDir, ".designtimelibs");

        // Read existing entries to preserve any manual entries not in our dependency list
        Map<String, String> existingByCoord = readExistingEntries(designtimeLibsFile);

        // Build new entry set: managed entries (all projlib+jar deps from pom)
        // We rebuild the index from scratch to keep it clean
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();

        // Add projlibs first
        for (Artifact projlib : projlibs) {
            String coord = projlib.getGroupId() + ":" + projlib.getArtifactId()
                + ":" + projlib.getVersion() + ":projlib";
            entries.put(coord, coord);
        }

        // Add JARs
        for (Artifact jar : jars) {
            String coord = jar.getGroupId() + ":" + jar.getArtifactId()
                + ":" + jar.getVersion() + ":jar";
            entries.put(coord, coord);
        }

        // Preserve manual entries not in our managed set
        for (Map.Entry<String, String> existing : existingByCoord.entrySet()) {
            if (!entries.containsKey(existing.getKey())) {
                entries.put(existing.getKey(), existing.getValue());
                getLog().debug("Preserving manual .designtimelibs entry: " + existing.getKey());
            }
        }

        // Write the file
        StringBuilder sb = new StringBuilder();
        sb.append("#Design time libraries\n");
        sb.append("#Format: #=File Alias=Description\n");
        sb.append("#").append(new java.util.Date()).append("\n");

        int idx = 0;
        for (String coord : entries.keySet()) {
            // Escape colons in the coordinate as per the format observed in real files
            String escaped = coord.replace(":", "\\:");
            sb.append(idx).append("=").append(escaped).append("\\=\n");
            idx++;
        }

        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(designtimeLibsFile), StandardCharsets.ISO_8859_1)) {
            w.write(sb.toString());
        } catch (IOException e) {
            throw new MojoExecutionException(
                "Failed to write .designtimelibs: " + e.getMessage(), e);
        }

        getLog().info("Updated: " + designtimeLibsFile.getAbsolutePath()
            + " (" + entries.size() + " entries)");
    }

    /**
     * Reads existing {@code .designtimelibs} entries into a map of {@code coord -> coord}.
     * Returns empty map if file doesn't exist.
     */
    private Map<String, String> readExistingEntries(File file) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!file.exists()) return result;

        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) continue;
                // Format: N=coord\=  or N=coord\:with\:colons\=
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String value = line.substring(eq + 1);
                // Remove trailing \=
                if (value.endsWith("\\=")) {
                    value = value.substring(0, value.length() - 2);
                }
                // Unescape colons
                value = value.replace("\\:", ":");
                result.put(value, value);
            }
        } catch (IOException e) {
            getLog().warn("Could not read existing .designtimelibs: " + e.getMessage());
        }
        return result;
    }

    /**
     * Adds {@code .designer-libs/} to the project's {@code .gitignore} if not already present.
     */
    private void ensureGitignore() {
        File gitignore = new File(project.getBasedir(), ".gitignore");
        String entry = ".designer-libs/";
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

        getLog().info("Launching Designer: " + executable.getAbsolutePath());
        getLog().info("Project directory : " + bwProjectPath.getAbsolutePath());

        try {
            new ProcessBuilder(executable.getAbsolutePath(), bwProjectPath.getAbsolutePath())
                .directory(bwProjectPath)
                .inheritIO()
                .start();
            // Detached — we do not wait for Designer to exit
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

    private File resolvedTibcoHome() {
        if (tibcoHome != null) return tibcoHome;
        String envHome = System.getenv("TIBCO_HOME");
        return (envHome != null && !envHome.isEmpty()) ? new File(envHome) : null;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
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

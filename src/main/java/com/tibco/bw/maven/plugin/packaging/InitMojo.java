package com.tibco.bw.maven.plugin.packaging;

import com.tibco.bw.maven.plugin.descriptor.ArchiveDescriptorParser;
import com.tibco.bw.maven.plugin.descriptor.DesignTimeLibsParser;
import com.tibco.bw.maven.plugin.descriptor.LibBuilderParser;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Generates an initial {@code pom.xml} for an existing BW5 project or library.
 *
 * <p>Run this goal once per project, inside the BW project directory, after checkout:
 * it inspects the directory for TIBCO Designer descriptor files to determine the
 * correct Maven packaging type and seed the artifact coordinates.</p>
 *
 * <h3>Detection logic</h3>
 * <ol>
 *   <li>A {@code .archive} file in the project root → {@code bwear} packaging</li>
 *   <li>A {@code .libbuilder} file in the project root or {@code Library/} subdirectory
 *       → {@code projlib} packaging</li>
 *   <li>Neither found → error with a clear message</li>
 * </ol>
 *
 * <h3>Auto-detected defaults</h3>
 * <ul>
 *   <li>{@code artifactId} — EAR/library name from the descriptor, lowercased and
 *       non-alphanumeric characters replaced with hyphens (override with {@code -DartifactId=...})</li>
 *   <li>{@code version} — defaults to {@code 1.0.0-SNAPSHOT} (override with {@code -Dversion=...})</li>
 * </ul>
 *
 * <h3>Dependency stubs</h3>
 * <p>If a {@code .designtimelibs} file is present, the generated {@code pom.xml} includes
 * commented-out {@code &lt;dependency&gt;} stubs — one per projlib path in the file.
 * Uncomment them and fill in the correct {@code groupId} and {@code version} once the
 * libraries have been published to your Maven repository.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 *   # Minimum — groupId is required
 *   mvn com.tibco.bw:bw5-maven-plugin:init -DgroupId=com.example
 *
 *   # Override artifact coordinates
 *   mvn com.tibco.bw:bw5-maven-plugin:init \
 *       -DgroupId=com.example \
 *       -DartifactId=my-service \
 *       -Dversion=2.0.0-SNAPSHOT
 *
 *   # Overwrite an existing pom.xml
 *   mvn com.tibco.bw:bw5-maven-plugin:init -DgroupId=com.example -Dbw5.init.force=true
 * </pre>
 */
@Mojo(
    name = "init",
    requiresProject = false,
    requiresDependencyResolution = ResolutionScope.NONE,
    threadSafe = false
)
public class InitMojo extends AbstractMojo {

    /**
     * Directory containing the BW5 project.
     * Defaults to the current working directory ({@code ${basedir}}).
     * Override when generating a pom for a project in a different directory.
     */
    @Parameter(defaultValue = "${basedir}", property = "bw5.init.projectDir")
    private File projectDir;

    /**
     * Maven {@code groupId} for the generated project.
     * This is the only required parameter — it has no sensible default.
     *
     * <p>Example: {@code com.example}, {@code com.acme.bw5}</p>
     */
    @Parameter(property = "groupId")
    private String groupId;

    /**
     * Maven {@code artifactId} for the generated project.
     * When not specified, the name is derived from the {@code .archive} or {@code .libbuilder}
     * descriptor: lowercased, spaces and special characters replaced with hyphens.
     *
     * <p>Example: {@code my-bw5-app}, {@code framework-common}</p>
     */
    @Parameter(property = "artifactId")
    private String artifactId;

    /**
     * Maven {@code version} for the generated project.
     * Defaults to {@code 1.0.0-SNAPSHOT}.
     */
    @Parameter(property = "version", defaultValue = "1.0.0-SNAPSHOT")
    private String version;

    /**
     * When {@code true}, overwrites an existing {@code pom.xml}.
     * When {@code false} (default), fails with an error if {@code pom.xml} already exists.
     */
    @Parameter(defaultValue = "false", property = "bw5.init.force")
    private boolean force;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (groupId == null || groupId.trim().isEmpty()) {
            throw new MojoExecutionException(
                "groupId is required. Specify it on the command line:\n"
                + "  mvn bw5:init -DgroupId=com.example");
        }

        if (!projectDir.exists() || !projectDir.isDirectory()) {
            throw new MojoExecutionException(
                "Project directory does not exist or is not a directory: "
                + projectDir.getAbsolutePath());
        }

        File pomFile = new File(projectDir, "pom.xml");
        if (pomFile.exists() && !force) {
            throw new MojoExecutionException(
                "pom.xml already exists: " + pomFile.getAbsolutePath() + "\n"
                + "Use -Dbw5.init.force=true to overwrite.");
        }

        DetectionResult detection = detectProject(projectDir);

        String resolvedArtifactId = (artifactId != null && !artifactId.trim().isEmpty())
            ? artifactId.trim()
            : detection.defaultArtifactId;

        String resolvedVersion = (version != null && !version.trim().isEmpty())
            ? version.trim()
            : "1.0.0-SNAPSHOT";

        List<String> designTimePaths = readDesignTimeLibs(projectDir);

        getLog().info("Detected packaging : " + detection.packaging);
        getLog().info("groupId            : " + groupId.trim());
        getLog().info("artifactId         : " + resolvedArtifactId);
        getLog().info("version            : " + resolvedVersion);
        if (!designTimePaths.isEmpty()) {
            getLog().info("Dependency stubs   : " + designTimePaths.size()
                + " entr" + (designTimePaths.size() == 1 ? "y" : "ies")
                + " from .designtimelibs (commented out — fill in Maven coordinates)");
        }

        String pomContent = generatePomXml(groupId.trim(), resolvedArtifactId, resolvedVersion,
            detection.packaging, detection.descriptorFileName, designTimePaths);

        try (Writer w = new OutputStreamWriter(new FileOutputStream(pomFile), StandardCharsets.UTF_8)) {
            w.write(pomContent);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write pom.xml: " + e.getMessage(), e);
        }

        getLog().info("Generated: " + pomFile.getAbsolutePath());
        getLog().info("");
        getLog().info("Next steps:");
        if (!designTimePaths.isEmpty()) {
            getLog().info("  1. Update the commented-out <dependencies> with real Maven coordinates");
            getLog().info("  2. mvn bw5:designer-setup  — stage dependencies for Designer");
            getLog().info("  3. mvn package             — build the " + detection.packaging);
        } else {
            getLog().info("  1. mvn bw5:designer-setup  — stage dependencies for Designer");
            getLog().info("  2. mvn package             — build the " + detection.packaging);
        }
    }

    // -----------------------------------------------------------------------
    //  Detection — package-private for testability
    // -----------------------------------------------------------------------

    static class DetectionResult {
        final String packaging;
        final String defaultArtifactId;
        /** Descriptor filename (no path), may be null when nothing useful was found */
        final String descriptorFileName;

        DetectionResult(String packaging, String defaultArtifactId, String descriptorFileName) {
            this.packaging = packaging;
            this.defaultArtifactId = defaultArtifactId;
            this.descriptorFileName = descriptorFileName;
        }
    }

    /**
     * Scans {@code dir} for a {@code .archive} or {@code .libbuilder} descriptor and
     * returns the detected packaging type, a suggested artifactId, and the descriptor filename.
     *
     * @throws MojoExecutionException if neither descriptor is found
     */
    DetectionResult detectProject(File dir) throws MojoExecutionException {
        // 1. .archive → bwear
        File archiveFile = findFirst(dir, ".archive");
        if (archiveFile != null) {
            String name = readArchiveName(archiveFile);
            if (name == null || name.isEmpty()) {
                name = stripExtension(archiveFile.getName());
            }
            return new DetectionResult("bwear", toArtifactId(name), archiveFile.getName());
        }

        // 2. .libbuilder → projlib (root first, then Library/ subdirectory)
        File libBuilderFile = findFirst(dir, ".libbuilder");
        if (libBuilderFile == null) {
            File libraryDir = new File(dir, "Library");
            if (libraryDir.isDirectory()) {
                libBuilderFile = findFirst(libraryDir, ".libbuilder");
            }
        }
        if (libBuilderFile != null) {
            String name = readLibBuilderName(libBuilderFile);
            if (name == null || name.isEmpty()) {
                name = stripExtension(libBuilderFile.getName());
            }
            return new DetectionResult("projlib", toArtifactId(name), libBuilderFile.getName());
        }

        throw new MojoExecutionException(
            "Cannot detect BW5 project type in: " + dir.getAbsolutePath() + "\n"
            + "Expected a .archive file (for bwear) or a .libbuilder file (for projlib).\n"
            + "Make sure bw5.init.projectDir points to a valid BW5 project directory.");
    }

    private File findFirst(File dir, String extension) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(extension)) {
                return f;
            }
        }
        return null;
    }

    private String readArchiveName(File archiveFile) {
        try {
            return new ArchiveDescriptorParser().parse(archiveFile).earName;
        } catch (Exception e) {
            getLog().warn("Could not parse .archive descriptor: " + e.getMessage()
                + " — using filename as artifactId");
            return null;
        }
    }

    private String readLibBuilderName(File libBuilderFile) {
        try {
            return new LibBuilderParser().parse(libBuilderFile).libraryName;
        } catch (Exception e) {
            getLog().warn("Could not parse .libbuilder descriptor: " + e.getMessage()
                + " — using filename as artifactId");
            return null;
        }
    }

    private List<String> readDesignTimeLibs(File dir) {
        File dtlFile = new File(dir, ".designtimelibs");
        if (!dtlFile.isFile()) return Collections.emptyList();
        try {
            List<String> paths = new DesignTimeLibsParser().parse(dtlFile);
            if (paths.isEmpty()) return Collections.emptyList();
            return paths;
        } catch (IOException e) {
            getLog().warn("Could not read .designtimelibs: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // -----------------------------------------------------------------------
    //  pom.xml generation — package-private static for testability
    // -----------------------------------------------------------------------

    /**
     * Generates the content of the initial {@code pom.xml}.
     *
     * @param groupId            Maven groupId
     * @param artifactId         Maven artifactId
     * @param version            Maven version
     * @param packaging          {@code bwear} or {@code projlib}
     * @param descriptorFileName descriptor filename for the comment hint, or {@code null}
     * @param designTimePaths    projlib paths from {@code .designtimelibs} for commented stubs
     * @return formatted pom.xml content
     */
    static String generatePomXml(String groupId, String artifactId, String version,
            String packaging, String descriptorFileName, List<String> designTimePaths) {

        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        sb.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 ");
        sb.append("http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        sb.append("    <modelVersion>4.0.0</modelVersion>\n");
        sb.append("\n");
        sb.append("    <groupId>").append(groupId).append("</groupId>\n");
        sb.append("    <artifactId>").append(artifactId).append("</artifactId>\n");
        sb.append("    <version>").append(version).append("</version>\n");
        sb.append("    <packaging>").append(packaging).append("</packaging>\n");
        sb.append("\n");
        sb.append("    <name>").append(artifactId).append("</name>\n");

        if (!designTimePaths.isEmpty()) {
            sb.append("\n");
            sb.append("    <!--\n");
            sb.append("        Projlib dependencies detected from .designtimelibs.\n");
            sb.append("        Replace the TODO placeholders with real Maven coordinates,\n");
            sb.append("        then run 'mvn bw5:designer-setup' to stage them for Designer.\n");
            sb.append("    -->\n");
            sb.append("    <!--\n");
            sb.append("    <dependencies>\n");
            for (String path : designTimePaths) {
                String libName = DesignTimeLibsParser.extractLibName(path);
                sb.append("        <dependency>\n");
                sb.append("            <groupId>TODO</groupId>\n");
                sb.append("            <artifactId>").append(libName).append("</artifactId>\n");
                sb.append("            <version>TODO</version>\n");
                sb.append("            <type>projlib</type>\n");
                sb.append("        </dependency>\n");
            }
            sb.append("    </dependencies>\n");
            sb.append("    -->\n");
        }

        sb.append("\n");
        sb.append("    <build>\n");
        sb.append("        <plugins>\n");
        sb.append("            <plugin>\n");
        sb.append("                <groupId>com.tibco.bw</groupId>\n");
        sb.append("                <artifactId>bw5-maven-plugin</artifactId>\n");
        sb.append("                <version>1.0.0-SNAPSHOT</version>\n");
        sb.append("                <extensions>true</extensions>\n");

        if (descriptorFileName != null) {
            sb.append("                <configuration>\n");
            if ("bwear".equals(packaging)) {
                sb.append("                    <!-- .archive descriptor auto-detected."
                    + " Uncomment to pin explicitly: -->\n");
                sb.append("                    <!-- <archiveDescriptorFile>${basedir}/")
                  .append(descriptorFileName).append("</archiveDescriptorFile> -->\n");
            } else {
                sb.append("                    <!-- .libbuilder descriptor auto-detected in Library/."
                    + " Uncomment to pin explicitly: -->\n");
                sb.append("                    <!-- <libBuilderFile>${basedir}/Library/")
                  .append(descriptorFileName).append("</libBuilderFile> -->\n");
            }
            sb.append("                </configuration>\n");
        }

        sb.append("            </plugin>\n");
        sb.append("        </plugins>\n");
        sb.append("    </build>\n");
        sb.append("</project>\n");

        return sb.toString();
    }

    // -----------------------------------------------------------------------
    //  Helpers — package-private for testability
    // -----------------------------------------------------------------------

    /**
     * Converts a BW project name to a valid Maven artifactId.
     * Lowercases the name, replaces any character that is not {@code [a-z0-9-]} with a hyphen,
     * collapses consecutive hyphens, and strips leading/trailing hyphens.
     *
     * <p>Examples: {@code "My App"} → {@code "my-app"},
     * {@code "Framework Common"} → {@code "framework-common"}</p>
     */
    static String toArtifactId(String name) {
        if (name == null || name.trim().isEmpty()) return "my-bw5-project";
        return name.trim()
                   .toLowerCase(Locale.ROOT)
                   .replaceAll("[^a-z0-9-]", "-")
                   .replaceAll("-+", "-")
                   .replaceAll("^-|-$", "");
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}

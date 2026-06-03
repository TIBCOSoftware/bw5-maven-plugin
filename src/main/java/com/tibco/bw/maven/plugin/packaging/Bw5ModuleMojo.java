package com.tibco.bw.maven.plugin.packaging;

import com.tibco.bw.maven.plugin.descriptor.LibBuilderParser;
import org.apache.commons.io.IOUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Assembles a BW5 projlib (.projlib) from the BW project sources.
 *
 * <p>A projlib is a ZIP archive containing BW project files (processes, shared resources,
 * schemas, etc.) that can be used as a dependency by other BW5 projects.</p>
 *
 * <p>When a {@code .libbuilder} descriptor file is present (or configured via
 * {@code bw5.libBuilderFile}), the plugin uses its {@code resources} list to
 * include only the processes and shared resources that TIBCO Designer would include.
 * {@code .folder} and {@code .substvar} files are always included automatically.</p>
 *
 * <p>Without a {@code .libbuilder}, all BW project files are included (except
 * standard exclusions like {@code AESchemas/} and {@code vcrepo.dat}).</p>
 */
@Mojo(
    name = "bw5module",
    defaultPhase = LifecyclePhase.PACKAGE,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
    threadSafe = false
)
public class Bw5ModuleMojo extends AbstractBw5Mojo {

    /**
     * File/directory names always excluded from projlib packaging.
     * These are TIBCO Designer system artefacts and version-control metadata
     * that buildlibrary never includes.
     */
    private static final Set<String> EXCLUDED_NAMES = new HashSet<>(Arrays.asList(
        ".DS_Store", "Thumbs.db", ".git", ".svn", "target",
        // TIBCO Designer system schemas (never shipped in projlibs)
        "AESchemas",
        // Version-control metadata
        "vcrepo.dat",
        // Designer runtime-only files
        ".designtimelibs"
    ));

    /**
     * Optional path to a TIBCO Designer {@code .libbuilder} descriptor file.
     *
     * <p>When provided, the plugin uses its {@code resources} list to include only
     * the files that TIBCO Designer would package — matching {@code buildlibrary}
     * behaviour exactly. Without it, all BW project files are included.</p>
     *
     * <p>The plugin auto-detects a {@code .libbuilder} file inside the
     * {@code Library/} subdirectory of the BW project if this parameter is not set.</p>
     */
    @Parameter(property = "bw5.libBuilderFile")
    private File libBuilderFile;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("bw5:bw5module skipped.");
            return;
        }

        validateBwProjectPath();

        File srcDir = bwSourcesDirectory.exists() ? bwSourcesDirectory : bwProjectPath;
        getLog().info("Assembling BW5 projlib from: " + srcDir.getAbsolutePath());

        try {
            LibBuilderParser.LibBuilderDescriptor descriptor = loadLibBuilderDescriptor(srcDir);

            String projlibFileName = project.getBuild().getFinalName() + ".projlib";
            File projlibFile = new File(project.getBuild().getDirectory(), projlibFileName);

            buildProjlib(projlibFile, srcDir, descriptor);

            getLog().info("Projlib assembled: " + projlibFile.getAbsolutePath()
                + " (" + projlibFile.length() + " bytes)");

            // Attach as the project artifact
            project.getArtifact().setFile(projlibFile);

        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to assemble BW5 projlib: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    //  .libbuilder descriptor loading
    // -----------------------------------------------------------------------

    private LibBuilderParser.LibBuilderDescriptor loadLibBuilderDescriptor(File srcDir) {
        File descriptor = resolveLibBuilderFile(srcDir);
        if (descriptor == null) return null;

        try {
            LibBuilderParser parser = new LibBuilderParser();
            LibBuilderParser.LibBuilderDescriptor result = parser.parse(descriptor);
            getLog().info("Using .libbuilder descriptor: " + descriptor.getName()
                + " (" + result + ")");
            return result;
        } catch (Exception e) {
            getLog().warn("Could not parse .libbuilder descriptor: " + e.getMessage()
                + " — including all BW project files");
            return null;
        }
    }

    /**
     * Resolves the .libbuilder file to use. Checks in order:
     * 1. Explicit {@code bw5.libBuilderFile} parameter
     * 2. Auto-detect: first {@code .libbuilder} file found under {@code <srcDir>/Library/}
     * 3. Auto-detect: first {@code .libbuilder} file found directly under {@code srcDir}
     */
    private File resolveLibBuilderFile(File srcDir) {
        if (libBuilderFile != null) {
            if (!libBuilderFile.exists()) {
                getLog().warn(".libbuilder not found, ignoring: " + libBuilderFile.getAbsolutePath());
                return null;
            }
            return libBuilderFile;
        }

        // Auto-detect: check Library/ subfolder first (standard Designer convention)
        File libraryDir = new File(srcDir, "Library");
        if (libraryDir.isDirectory()) {
            File found = findFirstLibBuilder(libraryDir);
            if (found != null) return found;
        }

        // Auto-detect: check project root
        return findFirstLibBuilder(srcDir);
    }

    private File findFirstLibBuilder(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".libbuilder")) {
                return f;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    //  Projlib assembly
    // -----------------------------------------------------------------------

    private void buildProjlib(File projlibFile, File srcDir,
            LibBuilderParser.LibBuilderDescriptor descriptor) throws Exception {

        // Build the include set if we have a .libbuilder
        Set<String> includedResources = null;
        Set<String> includedDirPrefixes = null;
        if (descriptor != null && descriptor.hasExplicitResourceList()) {
            includedResources = new HashSet<>(descriptor.resources);
            includedDirPrefixes = buildDirPrefixes(includedResources);
        }

        final Set<String> finalResources = includedResources;
        final Set<String> finalDirPrefixes = includedDirPrefixes;

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(projlibFile))) {
            zos.setLevel(Deflater.DEFAULT_COMPRESSION);

            // Add /library.manifest (always first, with leading / matching Designer convention)
            addManifest(zos);

            // Walk and add BW project files
            Path srcPath = srcDir.toPath();
            Files.walkFileTree(srcPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path fileNamePath = dir.getFileName();
                    if (fileNamePath == null) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = fileNamePath.toString();
                    if (EXCLUDED_NAMES.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    // Skip the Library/ folder itself (it only contains the .libbuilder descriptor)
                    Path parent = dir.getParent();
                    if ("Library".equals(name) && parent != null && parent.equals(srcPath)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path fileNamePath = file.getFileName();
                    if (fileNamePath == null) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = fileNamePath.toString();
                    if (EXCLUDED_NAMES.contains(name)) {
                        return FileVisitResult.CONTINUE;
                    }

                    String relativePath = srcPath.relativize(file).toString()
                        .replace(File.separatorChar, '/');

                    if (shouldInclude(relativePath, name, finalResources, finalDirPrefixes)) {
                        addToZip(zos, relativePath, file.toFile());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            // Add compiled Java classes if any
            if (javaClassesDirectory.exists()) {
                Path classesPath = javaClassesDirectory.toPath();
                Files.walkFileTree(classesPath, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        String relative = classesPath.relativize(file).toString()
                            .replace(File.separatorChar, '/');
                        addToZip(zos, "JavaCode/" + relative, file.toFile());
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
    }

    /**
     * Decides whether a file should be included in the projlib.
     *
     * <p>When no .libbuilder is present ({@code includedResources == null}): include everything.</p>
     *
     * <p>When a .libbuilder is present:</p>
     * <ul>
     *   <li>{@code .substvar} files — always included (global variable definitions)</li>
     *   <li>{@code .folder} files — included only if their parent directory contains
     *       at least one resource from the explicit list</li>
     *   <li>All other files — included only if explicitly listed in {@code resources}</li>
     * </ul>
     */
    private boolean shouldInclude(String relativePath, String fileName,
            Set<String> includedResources, Set<String> includedDirPrefixes) {

        if (includedResources == null) {
            // No .libbuilder: include everything (minus EXCLUDED_NAMES already handled)
            return true;
        }

        // .substvar files are always included (contain global variable definitions)
        if (fileName.endsWith(".substvar")) {
            return true;
        }

        // .folder files are included when their containing directory has listed resources
        if (fileName.equals(".folder")) {
            String dir = parentDir(relativePath);
            return dir.isEmpty() ? false : includedDirPrefixes.contains(dir);
        }

        // All other files must be explicitly listed
        return includedResources.contains(relativePath);
    }

    /**
     * Builds the set of directory path prefixes for all listed resources.
     * E.g. resource "Common/ARC/Process.process" → prefixes {"Common", "Common/ARC"}
     */
    private Set<String> buildDirPrefixes(Set<String> resources) {
        Set<String> dirs = new HashSet<>();
        for (String resource : resources) {
            String dir = parentDir(resource);
            while (!dir.isEmpty()) {
                dirs.add(dir);
                dir = parentDir(dir);
            }
        }
        return dirs;
    }

    private String parentDir(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : "";
    }

    // -----------------------------------------------------------------------
    //  ZIP helpers
    // -----------------------------------------------------------------------

    private void addManifest(ZipOutputStream zos) throws IOException {
        String author = System.getProperty("user.name", "unknown");
        String date   = new Date().toString();
        String ver    = project.getVersion();

        String manifestContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<library>\n"
            + "    <version>" + ver + "</version>\n"
            + "    <author>" + author + "</author>\n"
            + "    <date>" + date + "</date>\n"
            + "</library>\n";

        // Leading / matches TIBCO Designer's convention for library.manifest
        ZipEntry entry = new ZipEntry("/library.manifest");
        zos.putNextEntry(entry);
        zos.write(manifestContent.getBytes("UTF-8"));
        zos.closeEntry();
    }

    private void addToZip(ZipOutputStream zos, String entryName, File file) throws IOException {
        entryName = entryName.replace(File.separatorChar, '/');
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(file.lastModified());
        zos.putNextEntry(entry);
        try (InputStream in = new FileInputStream(file)) {
            IOUtils.copy(in, zos);
        }
        zos.closeEntry();
    }
}

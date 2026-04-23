package com.tibco.bw.maven.plugin.compile;

import com.tibco.bw.maven.plugin.packaging.AbstractBw5Mojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Reads compiled bytecode for Java Custom Functions (.javaxpath) and stages it as
 * Maven project properties for consumption by the {@code bwear} packaging goal.
 *
 * <p>For each {@code .javaxpath} file found in the BW project, this goal:</p>
 * <ol>
 *   <li>Parses the XML and reads {@code <ns0:loadedFromLocation>} to derive the
 *       fully-qualified class name (everything after {@code classes/} in the path).</li>
 *   <li>Looks up the corresponding {@code .class} file in
 *       {@code ${project.build.outputDirectory}}.</li>
 *   <li>If found, Base64-encodes the bytes and stores them as a Maven project property:
 *       {@code bw5.jcf.bytecode.<fullyQualifiedClassName>}.</li>
 * </ol>
 *
 * <p>This goal is a no-op (silent) when no {@code .javaxpath} files are present.
 * It never modifies any source file on disk.</p>
 */
@Mojo(
    name = "prepare-jcf-bytecode",
    defaultPhase = LifecyclePhase.PROCESS_CLASSES,
    requiresDependencyResolution = ResolutionScope.NONE,
    threadSafe = false
)
public class PrepareJcfBytecodeMojo extends AbstractBw5Mojo {

    static final Namespace JCF_NS =
        Namespace.getNamespace("http://www.tibco.com/bw/javaxpath/2003");

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().debug("bw5:prepare-jcf-bytecode skipped.");
            return;
        }

        File srcDir = bwSourcesDirectory.exists() ? bwSourcesDirectory : bwProjectPath;
        List<File> javaxpathFiles = findJavaxpathFiles(srcDir);

        if (javaxpathFiles.isEmpty()) {
            return;
        }

        getLog().info("Preparing JCF bytecode for " + javaxpathFiles.size() + " .javaxpath file(s)");

        int injected = 0;
        for (File jxpFile : javaxpathFiles) {
            try {
                SAXBuilder builder = new SAXBuilder();
                Element root = builder.build(jxpFile).getRootElement();

                String className = extractClassNameFromRoot(root);
                if (className == null) {
                    getLog().debug("Cannot determine class name from: " + jxpFile.getName() + " — skipping");
                    continue;
                }

                File classFile = resolveClassFile(root, className);
                if (classFile == null) {
                    getLog().debug("No compiled .class found for " + className + " — skipping");
                    continue;
                }

                byte[] bytes = Files.readAllBytes(classFile.toPath());
                String encoded = Base64.getEncoder().encodeToString(bytes);
                project.getProperties().setProperty("bw5.jcf.bytecode." + className, encoded);
                getLog().info("  [JCF] " + jxpFile.getName() + " → " + className
                    + " (from " + classFile.getAbsolutePath() + ")");
                injected++;
            } catch (Exception e) {
                getLog().warn("Could not prepare JCF bytecode for: " + jxpFile.getName()
                    + " — " + e.getMessage());
            }
        }

        if (injected > 0) {
            getLog().info("JCF bytecode staged for " + injected + " custom function(s)");
        }
    }

    private List<File> findJavaxpathFiles(File dir) {
        List<File> result = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return result;
        for (File f : files) {
            if (f.isDirectory()) {
                result.addAll(findJavaxpathFiles(f));
            } else if (f.getName().endsWith(".javaxpath")) {
                result.add(f);
            }
        }
        return result;
    }

    /**
     * Derives the fully-qualified class name from a pre-parsed root element.
     * Takes the path segment after the last {@code "classes/"} in {@code <ns0:loadedFromLocation>}.
     * Returns {@code null} when the element is absent or the path has no {@code "classes/"} segment.
     */
    static String extractClassNameFromRoot(Element root) {
        Element locationEl = root.getChild("loadedFromLocation", JCF_NS);
        if (locationEl == null) return null;

        String location = locationEl.getTextTrim().replace('\\', '/');
        int idx = location.lastIndexOf("classes/");
        if (idx < 0) return null;

        String relative = location.substring(idx + "classes/".length());
        if (relative.endsWith(".class")) {
            relative = relative.substring(0, relative.length() - ".class".length());
        }
        return relative.replace('/', '.');
    }

    /** Kept for test compatibility — delegates to the root-based variant. */
    static String extractClassName(File javaxpathFile) throws Exception {
        return extractClassNameFromRoot(new SAXBuilder().build(javaxpathFile).getRootElement());
    }

    /**
     * Resolves the {@code .class} file for {@code className} using two strategies:
     * <ol>
     *   <li>Primary — {@code javaClassesDirectory/<fqcn>.class} (classes compiled in this build).</li>
     *   <li>Fallback — the absolute path stored in {@code <ns0:loadedFromLocation>} (external project).</li>
     * </ol>
     * Returns {@code null} if neither location exists.
     */
    private File resolveClassFile(Element root, String className) {
        File fromBuild = new File(javaClassesDirectory,
            className.replace('.', File.separatorChar) + ".class");
        if (fromBuild.exists()) return fromBuild;

        Element locationEl = root.getChild("loadedFromLocation", JCF_NS);
        if (locationEl != null) {
            File fromLocation = new File(locationEl.getTextTrim());
            if (fromLocation.exists()) return fromLocation;
        }
        return null;
    }
}

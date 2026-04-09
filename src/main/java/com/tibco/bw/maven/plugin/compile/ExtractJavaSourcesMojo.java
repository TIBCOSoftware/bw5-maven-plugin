package com.tibco.bw.maven.plugin.compile;

import com.tibco.bw.maven.plugin.packaging.AbstractBw5Mojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts Java source code from BW5 Java Code activities embedded in .process files.
 *
 * <p>In TIBCO BusinessWorks 5, Java Code activities can contain inline Java source code
 * stored within the process XML. This Mojo extracts that code into proper {@code .java}
 * source files under {@code target/generated-sources/bw-java} so that the standard
 * Maven compiler plugin can compile them.</p>
 *
 * <p>The generated sources directory is registered as a compile source root so that
 * {@code maven-compiler-plugin:compile} picks it up automatically.</p>
 *
 * <p>BW5 Java Code activity XML structure:</p>
 * <pre>
 * &lt;pd:activity name="JavaCode"&gt;
 *   &lt;pd:type&gt;com.tibco.plugin.java.JavaActivity&lt;/pd:type&gt;
 *   &lt;config&gt;
 *     &lt;code&gt;
 *       public void myMethod() { ... }
 *     &lt;/code&gt;
 *     &lt;className&gt;com.example.MyClass&lt;/className&gt;
 *     &lt;imports&gt;import java.util.*;&#10;import com.tibco...;&lt;/imports&gt;
 *   &lt;/config&gt;
 * &lt;/pd:activity&gt;
 * </pre>
 *
 */
@Mojo(
    name = "extract-java-sources",
    defaultPhase = LifecyclePhase.GENERATE_SOURCES,
    requiresDependencyResolution = ResolutionScope.NONE,
    threadSafe = false
)
public class ExtractJavaSourcesMojo extends AbstractBw5Mojo {

    private static final Namespace PD_NS =
        Namespace.getNamespace("pd", "http://xmlns.tibco.com/bw/process/2003");

    private static final String JAVA_ACTIVITY_TYPE = "com.tibco.plugin.java.JavaActivity";

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("bw5:extract-java-sources skipped.");
            return;
        }

        File srcDir = bwSourcesDirectory.exists() ? bwSourcesDirectory : bwProjectPath;

        List<File> processFiles = findProcessFiles(srcDir);
        if (processFiles.isEmpty()) {
            getLog().debug("No .process files found, skipping Java source extraction.");
            return;
        }

        List<ExtractedClass> extracted = new ArrayList<>();
        for (File processFile : processFiles) {
            try {
                List<ExtractedClass> classes = extractFromProcess(processFile);
                extracted.addAll(classes);
            } catch (Exception e) {
                getLog().warn("Could not extract Java sources from: " + processFile.getName()
                    + " - " + e.getMessage());
            }
        }

        if (extracted.isEmpty()) {
            getLog().debug("No Java Code activities found in .process files.");
            return;
        }

        javaSourcesDirectory.mkdirs();

        int written = 0;
        for (ExtractedClass cls : extracted) {
            try {
                writeJavaSource(cls);
                written++;
            } catch (IOException e) {
                getLog().warn("Could not write Java source for " + cls.className + ": " + e.getMessage());
            }
        }

        if (written > 0) {
            getLog().info("Extracted " + written + " Java source file(s) to: "
                + javaSourcesDirectory.getAbsolutePath());
            // Register as compile source root so maven-compiler-plugin picks it up
            project.addCompileSourceRoot(javaSourcesDirectory.getAbsolutePath());
        }
    }

    private List<File> findProcessFiles(File dir) {
        List<File> result = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return result;
        for (File f : files) {
            if (f.isDirectory()) {
                result.addAll(findProcessFiles(f));
            } else if (f.getName().endsWith(".process")) {
                result.add(f);
            }
        }
        return result;
    }

    private List<ExtractedClass> extractFromProcess(File processFile) throws Exception {
        List<ExtractedClass> result = new ArrayList<>();
        SAXBuilder builder = new SAXBuilder();
        Document doc = builder.build(processFile);
        Element root = doc.getRootElement();

        for (Element activity : root.getChildren("activity", PD_NS)) {
            String type = getChildText(activity, "type");
            if (!JAVA_ACTIVITY_TYPE.equals(type)) continue;

            Element config = activity.getChild("config");
            if (config == null) continue;

            String code = getDirectChildText(config, "code");
            String className = getDirectChildText(config, "className");
            String imports = getDirectChildText(config, "imports");

            if (code == null || code.isBlank()) continue;
            if (className == null || className.isBlank()) {
                getLog().warn("Java Code activity in " + processFile.getName()
                    + " has no className, skipping.");
                continue;
            }

            result.add(new ExtractedClass(className.trim(), imports, code));
        }

        return result;
    }

    private void writeJavaSource(ExtractedClass cls) throws IOException {
        // Convert fully-qualified class name to file path
        String filePath = cls.className.replace('.', File.separatorChar) + ".java";
        File javaFile = new File(javaSourcesDirectory, filePath);
        javaFile.getParentFile().mkdirs();

        // Determine package from class name
        int lastDot = cls.className.lastIndexOf('.');
        String packageName = lastDot > 0 ? cls.className.substring(0, lastDot) : null;
        String simpleName = lastDot > 0 ? cls.className.substring(lastDot + 1) : cls.className;

        StringBuilder sb = new StringBuilder();
        if (packageName != null) {
            sb.append("package ").append(packageName).append(";\n\n");
        }
        if (cls.imports != null && !cls.imports.isBlank()) {
            sb.append(cls.imports.trim()).append("\n\n");
        }
        // Wrap the code in a class if it looks like method bodies (not a full class)
        if (!cls.code.contains("class " + simpleName)) {
            sb.append("public class ").append(simpleName).append(" {\n\n");
            sb.append(cls.code).append("\n");
            sb.append("}\n");
        } else {
            sb.append(cls.code).append("\n");
        }

        try (Writer w = new OutputStreamWriter(Files.newOutputStream(javaFile.toPath()), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }

        getLog().debug("  extracted: " + cls.className);
    }

    private String getChildText(Element parent, String childLocalName) {
        Element child = parent.getChild(childLocalName, PD_NS);
        return child != null ? child.getTextTrim() : null;
    }

    private String getDirectChildText(Element parent, String childLocalName) {
        Element child = parent.getChild(childLocalName);
        return child != null ? child.getText() : null;
    }

    private static class ExtractedClass {
        final String className;
        final String imports;
        final String code;

        ExtractedClass(String className, String imports, String code) {
            this.className = className;
            this.imports = imports;
            this.code = code;
        }
    }
}

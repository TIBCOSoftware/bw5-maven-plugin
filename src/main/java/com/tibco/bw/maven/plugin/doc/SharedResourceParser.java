package com.tibco.bw.maven.plugin.doc;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Parses BW5 SharedResource XML files from the {@code SharedResources/} directory.
 *
 * <p>BW5 shared resources (JDBC connections, HTTP connections, JMS connections, etc.)
 * are stored as individual XML files. Their extension varies by type but they all
 * share a common structure with {@code &lt;name&gt;}, {@code &lt;type&gt;}, and
 * {@code &lt;config&gt;} elements.</p>
 */
public class SharedResourceParser {

    private static final Logger LOG = Logger.getLogger(SharedResourceParser.class.getName());

    private static final java.util.Set<String> SKIP_NAMES = new java.util.HashSet<>(
        java.util.Arrays.asList(".DS_Store", "Thumbs.db", ".folder", "vcrepo.dat"));

    /**
     * Scans the entire {@code srcDir} tree for shared resource files (extension starts with
     * {@code "shared"}, e.g. {@code .sharedjdbc}, {@code .sharedhttp}) and parses each one.
     *
     * <p>BW5 Designer does not constrain shared resources to a fixed folder name — they can
     * live in any project subdirectory (commonly {@code Connections/} or {@code SharedResources/}).
     * Scanning the whole tree by extension is the only reliable approach.</p>
     */
    public List<SharedResourceModel> parse(File srcDir) {
        List<SharedResourceModel> result = new ArrayList<>();
        collectSRs(srcDir, result);
        return result;
    }

    /**
     * Returns {@code true} when {@code f} looks like a BW5 shared resource file.
     * The extension (text after the last {@code .}) must start with {@code "shared"}.
     * Package-private for testing.
     */
    static boolean isSharedResourceFile(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && name.substring(dot + 1).startsWith("shared");
    }

    private void collectSRs(File dir, List<SharedResourceModel> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) collectSRs(f, result);
            } else if (f.isFile() && isSharedResourceFile(f) && !SKIP_NAMES.contains(f.getName())
                       && !f.getName().startsWith(".")) {
                try {
                    SharedResourceModel sr = parseFile(f);
                    if (sr != null) result.add(sr);
                } catch (Exception e) {
                    LOG.fine("SharedResourceParser: skipping " + f.getName() + ": " + e.getMessage());
                }
            }
        }
    }

    private SharedResourceModel parseFile(File f) throws Exception {
        SAXBuilder builder = new SAXBuilder();
        Document doc = builder.build(f);
        Element root = doc.getRootElement();

        String name = childText(root, "name");
        if (name == null || name.isEmpty()) return null;

        SharedResourceModel sr = new SharedResourceModel();
        sr.name = name;
        int slash = name.lastIndexOf('/');
        sr.displayName = slash >= 0 ? name.substring(slash + 1) : name;
        sr.type = childText(root, "type");
        sr.resourceType = childText(root, "resourceType");

        Element configEl = root.getChild("config");
        if (configEl != null) {
            for (Element child : configEl.getChildren()) {
                String val = child.getTextTrim();
                if (!val.isEmpty()) {
                    sr.config.put(child.getName(), val);
                }
            }
        }
        return sr;
    }

    private String childText(Element parent, String name) {
        Element child = parent.getChild(name);
        return child != null ? child.getTextTrim() : null;
    }
}

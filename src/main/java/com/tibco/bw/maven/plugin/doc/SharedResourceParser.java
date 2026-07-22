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

    /** Friendly type label per shared-resource file extension. */
    private static final java.util.Map<String, String> EXT_TYPE = new java.util.HashMap<>();
    static {
        EXT_TYPE.put("sharedhttp",     "HTTP Connection");
        EXT_TYPE.put("sharedjdbc",     "JDBC Connection");
        EXT_TYPE.put("sharedjmscon",   "JMS Connection");
        EXT_TYPE.put("sharedjmsapp",   "JMS Application Properties");
        EXT_TYPE.put("sharedjndi",     "JNDI Configuration");
        EXT_TYPE.put("sharedvariable", "Shared Variable");
        EXT_TYPE.put("sharednotify",   "Notify Configuration");
        EXT_TYPE.put("sharedparse",    "Data Format (Parse)");
        EXT_TYPE.put("sharedftp",      "FTP Connection");
        EXT_TYPE.put("sharedrv",       "Rendezvous Transport");
        EXT_TYPE.put("sharedae",       "ActiveEnterprise Connection");
        EXT_TYPE.put("sharedch",       "RV/Channel Connection");
        EXT_TYPE.put("sharedtcp",      "TCP Connection");
        EXT_TYPE.put("sharedwss",      "WSS Configuration");
        EXT_TYPE.put("sharedidentity", "Identity");
        EXT_TYPE.put("sharedssl",      "SSL Configuration");
        EXT_TYPE.put("sharedjms",      "JMS Connection");
    }

    /**
     * Derives a human-friendly resource type. Prefers the file extension (most reliable, e.g.
     * {@code .sharedjmsapp} → "JMS Application Properties"), then the {@code <resourceType>}
     * value (e.g. {@code ae.shared.JMSAppPropResource}), then the XML root element name.
     */
    static String friendlyType(String fileName, String resourceType, String rootElement) {
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0) {
                String label = EXT_TYPE.get(fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
                if (label != null) return label;
            }
        }
        if (resourceType != null && !resourceType.isEmpty()) return resourceType;
        return rootElement;
    }

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
                    // parseFile always returns a model (name falls back to the file name);
                    // genuinely unparseable files throw and are skipped below.
                    result.add(parseFile(f));
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
        if (name == null || name.isEmpty()) {
            // Many shared resources (e.g. .sharedhttp / .sharedjdbc / .sharedjmscon) carry no
            // top-level <name> element — the resource name IS the file name (its repository path).
            // Fall back to the file name (minus extension) instead of dropping the resource.
            String fn = f.getName();
            int dot = fn.lastIndexOf('.');
            name = dot > 0 ? fn.substring(0, dot) : fn;
        }

        SharedResourceModel sr = new SharedResourceModel();
        sr.name = name;
        int slash = name.lastIndexOf('/');
        sr.displayName = slash >= 0 ? name.substring(slash + 1) : name;
        sr.resourceType = childText(root, "resourceType");
        sr.type = childText(root, "type");
        if (sr.type == null || sr.type.isEmpty()) {
            // No <type> element. Many shared resources use a generic <BWSharedResource> root and
            // carry the real kind in <resourceType> (e.g. ae.shared.JMSAppPropResource); others
            // (e.g. .sharedhttp) have a type-specific root but no <type>. Derive a friendly label
            // from the file extension first (most reliable), then <resourceType>, then the root tag.
            sr.type = friendlyType(f.getName(), sr.resourceType, root.getName());
        }

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

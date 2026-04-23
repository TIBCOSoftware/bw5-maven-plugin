package com.tibco.bw.maven.plugin.descriptor;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;

import java.beans.XMLDecoder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Parses TIBCO Designer {@code .aliaslib} shared resource files.
 *
 * <p>An {@code .aliaslib} is a Repository XML file that holds a list of Java library
 * aliases used by Java Method activities. The alias list is stored as an
 * HTML-entity-encoded Java XMLEncoder blob (an {@code ArrayList<HashMap<String,Object>>})
 * inside the {@code <FILE_ALIASES_LIST>} element.</p>
 *
 * <p>Each entry in the decoded list has at minimum:</p>
 * <ul>
 *   <li>{@code name} — the JAR filename used as the alias (e.g. {@code commons-lang3-3.12.0.jar})</li>
 *   <li>{@code includeInDeployment} — whether the JAR must be present in the EAR</li>
 *   <li>{@code isClasspathFile} — whether it is on the Java Method classpath</li>
 * </ul>
 */
public class AliasLibParser {

    private static final Namespace REPO_NS =
        Namespace.getNamespace("Repository", "http://www.tibco.com/xmlns/repo/types/2002");
    private static final Namespace REPO_NS_DEFAULT =
        Namespace.getNamespace("http://www.tibco.com/xmlns/repo/types/2002");

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Finds and parses all {@code *.aliaslib} files under {@code rootDir} (recursive).
     *
     * @param rootDir the BW project source directory to scan
     * @return combined list of entries from all {@code .aliaslib} files found
     */
    public List<AliasLibEntry> parseAll(File rootDir) throws Exception {
        List<AliasLibEntry> result = new ArrayList<>();
        if (rootDir == null || !rootDir.isDirectory()) return result;

        try (Stream<Path> walk = Files.walk(rootDir.toPath())) {
            walk.filter(p -> p.toString().endsWith(".aliaslib"))
                .forEach(p -> {
                    try {
                        result.addAll(parse(p.toFile()));
                    } catch (Exception e) {
                        // Log-friendly: surface the filename in the message
                        throw new RuntimeException(
                            "Failed to parse aliaslib: " + p + ": " + e.getMessage(), e);
                    }
                });
        }
        return result;
    }

    /**
     * Parses a single {@code .aliaslib} file and returns its alias entries.
     *
     * @param aliasLibFile the file to parse
     * @return list of entries; empty if {@code FILE_ALIASES_LIST} is absent or empty
     */
    public List<AliasLibEntry> parse(File aliasLibFile) throws Exception {
        SAXBuilder builder = new SAXBuilder();
        Document doc = builder.build(aliasLibFile);
        Element root = doc.getRootElement();

        // <name name="..."> child of root
        Element nameEl = getChildNoNs(root, "name");
        if (nameEl == null) return new ArrayList<>();

        Element fileAliasListEl = getChildNoNs(nameEl, "FILE_ALIASES_LIST");
        if (fileAliasListEl == null) return new ArrayList<>();

        String xmlBlob = fileAliasListEl.getText();
        if (xmlBlob == null || xmlBlob.isBlank()) return new ArrayList<>();

        return decodeBlob(xmlBlob.trim());
    }

    // ── XMLDecoder blob decoding ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<AliasLibEntry> decodeBlob(String xmlBlob) throws IOException {
        List<AliasLibEntry> entries = new ArrayList<>();
        byte[] bytes = xmlBlob.getBytes(StandardCharsets.UTF_8);

        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             XMLDecoder decoder = new XMLDecoder(bais)) {
            Object decoded = decoder.readObject();
            if (!(decoded instanceof List)) return entries;

            List<?> list = (List<?>) decoded;
            for (Object item : list) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> map = (Map<String, Object>) item;

                String name = (String) map.get("name");
                if (name == null || name.isBlank()) continue;

                Boolean includeInDeployment = (Boolean) map.get("includeInDeployment");
                Boolean isClasspathFile = (Boolean) map.get("isClasspathFile");

                AliasLibEntry entry = new AliasLibEntry();
                entry.aliasName = name.trim();
                entry.includeInDeployment = Boolean.TRUE.equals(includeInDeployment);
                entry.isClasspathFile = Boolean.TRUE.equals(isClasspathFile);
                entries.add(entry);
            }
        }
        return entries;
    }

    // ── JDOM helpers ─────────────────────────────────────────────────────────

    private Element getChildNoNs(Element parent, String localName) {
        Element child = parent.getChild(localName);
        if (child != null) return child;
        child = parent.getChild(localName, REPO_NS_DEFAULT);
        if (child != null) return child;
        return parent.getChild(localName, REPO_NS);
    }

    // ── Entry model ───────────────────────────────────────────────────────────

    /**
     * A single alias entry decoded from a {@code .aliaslib} file.
     */
    public static class AliasLibEntry {
        /**
         * The alias name — typically a JAR filename such as
         * {@code commons-lang3-3.12.0.jar}.
         */
        public String aliasName;

        /**
         * Whether the JAR must be bundled in the EAR for deployment.
         * Only entries with {@code includeInDeployment=true} need a matching
         * Maven dependency.
         */
        public boolean includeInDeployment;

        /**
         * Whether the JAR is on the Java Method activity classpath.
         */
        public boolean isClasspathFile;
    }
}

package com.tibco.bw.maven.plugin.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for relative {@code schemaLocation} resolution inside XSD import chains.
 *
 * <p>Uses reflection to call the private {@code followXsdImports()} helper so the
 * test stays focused on this single behaviour without requiring a full Maven project.</p>
 */
public class BwEarMojoXsdFollowTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // -----------------------------------------------------------------------
    //  Reflection handles (computed once for the class)
    // -----------------------------------------------------------------------

    private static final Method FOLLOW_XSD;
    private static final Constructor<?> BW_FILE_CTOR;

    static {
        try {
            FOLLOW_XSD = BwEarMojo.class.getDeclaredMethod(
                "followXsdImports", String.class, Map.class, Set.class);
            FOLLOW_XSD.setAccessible(true);

            Class<?> bwFileClass = null;
            for (Class<?> c : BwEarMojo.class.getDeclaredClasses()) {
                if ("BwFile".equals(c.getSimpleName())) { bwFileClass = c; break; }
            }
            if (bwFileClass == null) throw new NoSuchFieldException("BwFile inner class not found");
            BW_FILE_CTOR = bwFileClass.getDeclaredConstructor(File.class, String.class);
            BW_FILE_CTOR.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Object bwFile(File f, String relativePath) throws Exception {
        return BW_FILE_CTOR.newInstance(f, relativePath);
    }

    private void followXsd(String xsdPath, Map<String, Object> index, Set<String> visited) throws Exception {
        FOLLOW_XSD.invoke(new BwEarMojo(), xsdPath, index, visited);
    }

    private File writeXsd(File dir, String name, String content) throws Exception {
        File f = new File(dir, name);
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    // -----------------------------------------------------------------------
    //  Tests
    // -----------------------------------------------------------------------

    // NOTE: normalizeBwPath() strips the leading "/" so the resource index is keyed
    // without it (e.g. "SharedResources/parent.xsd", not "/SharedResources/parent.xsd").
    // followXsdImports() is always invoked with the normalized (no-leading-slash) path.

    @Test
    public void followsRelativeSchemaLocationImport() throws Exception {
        File dir = tmp.newFolder("rel-xsd");
        File parentXsd = writeXsd(dir, "parent.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">\n" +
            "  <xs:import schemaLocation=\"broker.xsd\"/>\n" +
            "</xs:schema>");
        File brokerXsd = writeXsd(dir, "broker.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>");

        // Keys are normalized (no leading "/"), matching normalizeBwPath() output
        String parentPath = "SharedResources/parent.xsd";
        String brokerPath = "SharedResources/broker.xsd";

        Map<String, Object> index = new LinkedHashMap<>();
        index.put(parentPath, bwFile(parentXsd, "SharedResources/parent.xsd"));
        index.put(brokerPath, bwFile(brokerXsd, "SharedResources/broker.xsd"));

        Set<String> visited = new LinkedHashSet<>();
        visited.add(parentPath);

        followXsd(parentPath, index, visited);

        assertTrue("broker.xsd must be visited via relative schemaLocation import",
            visited.contains(brokerPath));
    }

    @Test
    public void absoluteSchemaLocationStillFollowed() throws Exception {
        File dir = tmp.newFolder("abs-xsd");
        File parentXsd = writeXsd(dir, "parent.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">\n" +
            "  <xs:import schemaLocation=\"/CommonTypes/Types.xsd\"/>\n" +
            "</xs:schema>");
        File typesXsd = writeXsd(dir, "Types.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>");

        String parentPath = "SharedResources/parent.xsd";
        // normalizeBwPath("/CommonTypes/Types.xsd") → "CommonTypes/Types.xsd"
        String typesPath  = "CommonTypes/Types.xsd";

        Map<String, Object> index = new LinkedHashMap<>();
        index.put(parentPath, bwFile(parentXsd, "SharedResources/parent.xsd"));
        index.put(typesPath,  bwFile(typesXsd,  "CommonTypes/Types.xsd"));

        Set<String> visited = new LinkedHashSet<>();
        visited.add(parentPath);

        followXsd(parentPath, index, visited);

        assertTrue("Absolute schemaLocation import must still be followed",
            visited.contains(typesPath));
    }

    @Test
    public void relativeImportTransitiveChain() throws Exception {
        // parent.xsd →(relative) middle.xsd →(relative) leaf.xsd
        File dir = tmp.newFolder("chain-xsd");
        File parentXsd = writeXsd(dir, "parent.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">\n" +
            "  <xs:import schemaLocation=\"middle.xsd\"/>\n" +
            "</xs:schema>");
        File middleXsd = writeXsd(dir, "middle.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">\n" +
            "  <xs:import schemaLocation=\"leaf.xsd\"/>\n" +
            "</xs:schema>");
        File leafXsd = writeXsd(dir, "leaf.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>");

        String parentPath = "SR/parent.xsd";
        String middlePath = "SR/middle.xsd";
        String leafPath   = "SR/leaf.xsd";

        Map<String, Object> index = new LinkedHashMap<>();
        index.put(parentPath, bwFile(parentXsd, "SR/parent.xsd"));
        index.put(middlePath, bwFile(middleXsd, "SR/middle.xsd"));
        index.put(leafPath,   bwFile(leafXsd,   "SR/leaf.xsd"));

        Set<String> visited = new LinkedHashSet<>();
        visited.add(parentPath);

        followXsd(parentPath, index, visited);

        assertTrue("middle.xsd must be visited", visited.contains(middlePath));
        assertTrue("leaf.xsd must be visited transitively", visited.contains(leafPath));
    }

    /**
     * Regression test for Bug 2 — relative {@code schemaLocation} values containing {@code ..}
     * segments must be normalized before index lookup.
     *
     * <p>Example: {@code STATUS_MSG.xsd} at {@code XSD/Status/} includes
     * {@code ../Common/HEADER.xsd}, which resolves to {@code XSD/Status/../Common/HEADER.xsd}.
     * Without normalization that path is not found in the resource index (where the file
     * lives at the canonical {@code XSD/Common/HEADER.xsd}). With normalization the {@code ..}
     * is collapsed and the lookup succeeds.</p>
     */
    @Test
    public void normalizesDotDotSegmentsInRelativeImport() throws Exception {
        // STATUS_MSG.xsd at XSD/Status/ includes ../Common/HEADER.xsd
        // HEADER.xsd is indexed under XSD/Common/HEADER.xsd (canonical path, no ..)
        File statusDir = tmp.newFolder("dot-dot-status");
        File commonDir = tmp.newFolder("dot-dot-common");
        File statusMsg = writeXsd(statusDir, "STATUS_MSG.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">\n" +
            "  <xs:include schemaLocation=\"../Common/HEADER.xsd\"/>\n" +
            "</xs:schema>");
        File header = writeXsd(commonDir, "HEADER.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>");

        String statusPath = "XSD/Status/STATUS_MSG.xsd";
        String headerPath = "XSD/Common/HEADER.xsd";

        Map<String, Object> index = new LinkedHashMap<>();
        index.put(statusPath, bwFile(statusMsg, statusPath));
        index.put(headerPath, bwFile(header, headerPath));

        Set<String> visited = new LinkedHashSet<>();
        visited.add(statusPath);
        followXsd(statusPath, index, visited);

        assertTrue("HEADER.xsd with ../ in schemaLocation must be found after path normalization",
            visited.contains(headerPath));
    }

    @Test
    public void ignoresNonXsdRelativeRefs() throws Exception {
        File dir = tmp.newFolder("non-xsd");
        File parentXsd = writeXsd(dir, "parent.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">\n" +
            "  <xs:import schemaLocation=\"readme.txt\"/>\n" +
            "</xs:schema>");

        String parentPath = "SR/parent.xsd";
        Map<String, Object> index = new LinkedHashMap<>();
        index.put(parentPath, bwFile(parentXsd, "SR/parent.xsd"));

        Set<String> visited = new LinkedHashSet<>();
        visited.add(parentPath);

        followXsd(parentPath, index, visited);

        assertEquals("Non-XSD relative refs must not expand visited set", 1, visited.size());
    }

    /**
     * Regression test for Bug 1 — sharedResources XSDs (alwaysInclude) must have their
     * import chains followed.
     *
     * <p>sharedResources XSDs go to alwaysInclude and bypass the BFS resourceIndex. After
     * the BFS the fix calls followXsdImports for each alwaysInclude XSD with an extended
     * index that covers both alwaysInclude and resourceIndex entries so their imports can
     * be read and resolved. This test verifies that followXsdImports correctly discovers
     * imports from an alwaysInclude XSD when the caller supplies such an extended index.</p>
     */
    @Test
    public void sharedResourceXsdImportsDiscoveredWithExtendedIndex() throws Exception {
        // alwaysInclude XSD: STATUS_MSG.xsd (a sharedResources entry)
        // resourceIndex XSD: BW_STATUS.xsd (imported by STATUS_MSG.xsd, not in sharedResources)
        File dir = tmp.newFolder("shared-res");
        File statusMsg = writeXsd(dir, "STATUS_MSG.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">\n" +
            "  <xs:include schemaLocation=\"BW_STATUS.xsd\"/>\n" +
            "</xs:schema>");
        File bwStatus = writeXsd(dir, "BW_STATUS.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>");

        String statusMsgPath = "XSD/Status/STATUS_MSG.xsd";
        String bwStatusPath  = "XSD/Status/BW_STATUS.xsd";

        // Extended index covers both the alwaysInclude XSD and the resource XSD
        Map<String, Object> extIndex = new LinkedHashMap<>();
        extIndex.put(statusMsgPath, bwFile(statusMsg, statusMsgPath));
        extIndex.put(bwStatusPath,  bwFile(bwStatus,  bwStatusPath));

        Set<String> visited = new LinkedHashSet<>();
        // Simulate calling followXsdImports for an alwaysInclude XSD
        visited.add(statusMsgPath);
        followXsd(statusMsgPath, extIndex, visited);

        assertTrue("BW_STATUS.xsd imported by a sharedResources XSD must be discovered",
            visited.contains(bwStatusPath));
    }

    @Test
    public void ignoresHttpSchemaLocationRefs() throws Exception {
        File dir = tmp.newFolder("http-xsd");
        File parentXsd = writeXsd(dir, "parent.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">\n" +
            "  <xs:import namespace=\"http://example.com/types\" schemaLocation=\"http://example.com/types.xsd\"/>\n" +
            "</xs:schema>");

        String parentPath = "SR/parent.xsd";
        Map<String, Object> index = new LinkedHashMap<>();
        index.put(parentPath, bwFile(parentXsd, "SR/parent.xsd"));

        Set<String> visited = new LinkedHashSet<>();
        visited.add(parentPath);

        followXsd(parentPath, index, visited);

        assertEquals("HTTP schemaLocation refs must not expand visited set", 1, visited.size());
    }
}

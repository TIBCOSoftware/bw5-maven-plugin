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

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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the xsd:import preamble filtering in
 * {@code BwEarMojo.extractBwResourceRefs(File, Map)}.
 *
 * <p>BW process files declare namespace bindings via {@code xsd:import schemaLocation="/..."}
 * at the top of the file. Not all declared schemas are actually used (instantiated as variable
 * or activity types). This class verifies that the plugin only includes schemas whose
 * top-level element names appear in the process body — matching TIBCO Designer behaviour.</p>
 */
public class BwEarMojoProcessXsdFilterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final Method EXTRACT_REFS;
    private static final Constructor<?> BW_FILE_CTOR;

    static {
        try {
            EXTRACT_REFS = BwEarMojo.class.getDeclaredMethod(
                "extractBwResourceRefs", File.class, Map.class);
            EXTRACT_REFS.setAccessible(true);

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

    private Object bwFile(File f, String rel) throws Exception {
        return BW_FILE_CTOR.newInstance(f, rel);
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractRefs(File processFile, Map<String, Object> index) throws Exception {
        return (Set<String>) EXTRACT_REFS.invoke(new BwEarMojo(), processFile, index);
    }

    private File writeFile(File dir, String name, String content) throws Exception {
        File f = new File(dir, name);
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    // -----------------------------------------------------------------------
    //  Tests
    // -----------------------------------------------------------------------

    /**
     * Regression test: a process that declares two schemas via xsd:import but only
     * instantiates one of them must only reference the used schema.
     */
    @Test
    public void includesUsedXsdImportAndExcludesUnused() throws Exception {
        File dir = tmp.newFolder("xsd-filter");

        File usedXsd = writeFile(dir, "UsedSchema.xsd",
            "<?xml version=\"1.0\"?>\n" +
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">\n" +
            "  <xs:element name=\"usedElement\"/>\n" +
            "</xs:schema>");

        File unusedXsd = writeFile(dir, "UnusedSchema.xsd",
            "<?xml version=\"1.0\"?>\n" +
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">\n" +
            "  <xs:element name=\"neverReferencedElement\"/>\n" +
            "  <xs:complexType name=\"neverReferencedType\"/>\n" +
            "</xs:schema>");

        File process = writeFile(dir, "test.process",
            "<?xml version=\"1.0\"?>\n" +
            "<pd:ProcessDefinition\n" +
            "    xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\"\n" +
            "    xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"\n" +
            "    xmlns:ns1=\"http://example.com/used\">\n" +
            "  <xsd:import namespace=\"http://example.com/used\"" +
            "    schemaLocation=\"/Test/UsedSchema.xsd\"/>\n" +
            "  <xsd:import namespace=\"http://example.com/unused\"" +
            "    schemaLocation=\"/Test/UnusedSchema.xsd\"/>\n" +
            "  <pd:name>Test/test.process</pd:name>\n" +
            "  <pd:errorSchemas>\n" +
            "    <Schema0 ref=\"ns1:usedElement\"/>\n" +
            "  </pd:errorSchemas>\n" +
            "</pd:ProcessDefinition>");

        Map<String, Object> index = new LinkedHashMap<>();
        index.put("Test/UsedSchema.xsd",   bwFile(usedXsd,   "Test/UsedSchema.xsd"));
        index.put("Test/UnusedSchema.xsd", bwFile(unusedXsd, "Test/UnusedSchema.xsd"));

        Set<String> refs = extractRefs(process, index);

        assertTrue("UsedSchema.xsd must be included (usedElement is referenced in body)",
            refs.contains("/Test/UsedSchema.xsd"));
        assertFalse("UnusedSchema.xsd must be excluded (no body references to its types)",
            refs.contains("/Test/UnusedSchema.xsd"));
    }

    /**
     * Regression test matching the exact RAMSES BackOffice scenario: two schemas share the
     * same targetNamespace ("pmu"). Only the one whose element names appear in the process
     * body (bwException) must be included; the other (LOG_MSG) must be excluded.
     */
    @Test
    public void distinguishesSchemasWithSharedNamespace() throws Exception {
        File dir = tmp.newFolder("shared-ns");

        File usedXsd = writeFile(dir, "BWException.xsd",
            "<?xml version=\"1.0\"?>\n" +
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"\n" +
            "    targetNamespace=\"pmu\" xmlns=\"pmu\">\n" +
            "  <xs:element name=\"bwException\"/>\n" +
            "</xs:schema>");

        File unusedXsd = writeFile(dir, "LOG_MSG.xsd",
            "<?xml version=\"1.0\"?>\n" +
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"\n" +
            "    targetNamespace=\"pmu\" xmlns=\"pmu\">\n" +
            "  <xs:element name=\"LOG_MSG\"/>\n" +
            "  <xs:element name=\"LOG\"/>\n" +
            "</xs:schema>");

        // Both schemas in preamble with namespace="pmu"; only bwException is used in body.
        File process = writeFile(dir, "main.process",
            "<?xml version=\"1.0\"?>\n" +
            "<pd:ProcessDefinition\n" +
            "    xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\"\n" +
            "    xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"\n" +
            "    xmlns:ns5=\"pmu\">\n" +
            "  <xsd:import namespace=\"pmu\"" +
            "    schemaLocation=\"/LibSV_Ramses/BWException.xsd\"/>\n" +
            "  <xsd:import namespace=\"pmu\"" +
            "    schemaLocation=\"/LibSV/LOG_MSG.xsd\"/>\n" +
            "  <pd:name>Processes/main.process</pd:name>\n" +
            "  <pd:errorSchemas>\n" +
            "    <Schema0 ref=\"ns5:bwException\"/>\n" +
            "  </pd:errorSchemas>\n" +
            "  <fault>localname=bwException namespace=pmu</fault>\n" +
            "</pd:ProcessDefinition>");

        Map<String, Object> index = new LinkedHashMap<>();
        index.put("LibSV_Ramses/BWException.xsd", bwFile(usedXsd,   "LibSV_Ramses/BWException.xsd"));
        index.put("LibSV/LOG_MSG.xsd",            bwFile(unusedXsd, "LibSV/LOG_MSG.xsd"));

        Set<String> refs = extractRefs(process, index);

        assertTrue("BWException.xsd must be included (bwException is referenced in body)",
            refs.contains("/LibSV_Ramses/BWException.xsd"));
        assertFalse("LOG_MSG.xsd must be excluded (no LOG_MSG/LOG references in body)",
            refs.contains("/LibSV/LOG_MSG.xsd"));
    }

    /**
     * Non-XSD schemaLocations in xsd:import (e.g. .sharedparse) are always included
     * since they are structural parser references, not schema type imports.
     */
    @Test
    public void nonXsdSchemaLocationAlwaysIncluded() throws Exception {
        File dir = tmp.newFolder("sharedparse");
        File process = writeFile(dir, "main.process",
            "<?xml version=\"1.0\"?>\n" +
            "<pd:ProcessDefinition\n" +
            "    xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\"\n" +
            "    xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\n" +
            "  <xsd:import namespace=\"http://example.com/parse\"" +
            "    schemaLocation=\"/Resources/ParseData/MyFormat.sharedparse\"/>\n" +
            "  <pd:name>Processes/main.process</pd:name>\n" +
            "</pd:ProcessDefinition>");

        Set<String> refs = extractRefs(process, new LinkedHashMap<>());

        assertTrue(".sharedparse schemaLocation must always be included",
            refs.contains("/Resources/ParseData/MyFormat.sharedparse"));
    }

    /**
     * When an XSD declared in xsd:import is not present in the SAR resource index
     * (e.g. an external schema), it must be included conservatively.
     */
    @Test
    public void missingFromIndexIncludedConservatively() throws Exception {
        File dir = tmp.newFolder("missing-xsd");
        File process = writeFile(dir, "main.process",
            "<?xml version=\"1.0\"?>\n" +
            "<pd:ProcessDefinition\n" +
            "    xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\"\n" +
            "    xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\n" +
            "  <xsd:import namespace=\"http://example.com/ext\"" +
            "    schemaLocation=\"/External/ExternalSchema.xsd\"/>\n" +
            "  <pd:name>Processes/main.process</pd:name>\n" +
            "</pd:ProcessDefinition>");

        // Empty index — ExternalSchema.xsd is not in this SAR
        Set<String> refs = extractRefs(process, new LinkedHashMap<>());

        assertTrue("Schema absent from SAR index must be included conservatively",
            refs.contains("/External/ExternalSchema.xsd"));
    }
}

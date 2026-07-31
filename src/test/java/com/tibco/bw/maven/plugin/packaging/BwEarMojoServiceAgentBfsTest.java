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

import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

/**
 * Regression tests for BFS traversal of promoted {@code .serviceagent} files.
 *
 * <p>A serviceagent declared as a processProperty entry point is promoted from
 * the SAR to the PAR by {@code promoteServiceAgentsFromDescriptor}. However,
 * the {@code opImpl} attributes inside the serviceagent XML reference the actual
 * implementation processes that must also be included in the PAR (multi-PAR mode)
 * and whose transitive SAR references must be discovered. The SAR resources
 * referenced directly by the serviceagent ({@code sharedChannel}, {@code ref},
 * WSDL {@code location}) must also be included in the SAR.</p>
 *
 * <p>Before this fix neither the implementation processes nor the direct SAR
 * resources were discovered, causing Maven EAR contents to diverge from the
 * TIBCO {@code buildear} reference output.</p>
 */
public class BwEarMojoServiceAgentBfsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final Method EXTRACT_REFS;
    private static final Method APPLY_TRANSITIVE;
    private static final Constructor<?> BW_FILE_CTOR;

    static {
        try {
            EXTRACT_REFS = BwEarMojo.class.getDeclaredMethod(
                "extractBwResourceRefs", File.class);
            EXTRACT_REFS.setAccessible(true);

            APPLY_TRANSITIVE = BwEarMojo.class.getDeclaredMethod(
                "applyTransitiveDependencyAnalysis",
                List.class, List.class, List.class, List.class, boolean.class);
            APPLY_TRANSITIVE.setAccessible(true);

            Class<?> bwFileClass = null;
            for (Class<?> c : BwEarMojo.class.getDeclaredClasses()) {
                if ("BwFile".equals(c.getSimpleName())) {
                    bwFileClass = c;
                    break;
                }
            }
            if (bwFileClass == null) {
                throw new NoSuchFieldException("BwFile inner class not found");
            }
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
    private Set<String> extractRefs(File file) throws Exception {
        return (Set<String>) EXTRACT_REFS.invoke(new BwEarMojo(), file);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyTransitive(List parFiles, List sarFiles,
                                  List<String> entryPoints, List<String> sharedRes,
                                  boolean filter) throws Exception {
        try {
            APPLY_TRANSITIVE.invoke(new BwEarMojo(),
                parFiles, sarFiles, entryPoints, sharedRes, filter);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        }
    }

    private File writeFile(File dir, String name, String content) throws Exception {
        File f = new File(dir, name);
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    // -----------------------------------------------------------------------
    //  BUG-2: copybook (.cpy) resource wrapping (buildear parity, encoding-safe)
    // -----------------------------------------------------------------------

    /**
     * Regression (BUG-2): a raw COBOL copybook must be wrapped into its
     * {@code ae.shared.CCBSchemaResource} XML with the fixed metadata block and
     * {@code <version>3.6.0</version>} (current copybook palette version), matching buildear.
     */
    @Test
    public void rawCopybookWrappedIntoResourceXml() throws Exception {
        File dir = tmp.newFolder("cpy-raw");
        File cpy = new File(dir, "AIU0_REP.cpy");
        Files.write(cpy.toPath(), "      01 REC.\r\n         05 F PIC X(10).\r\n"
            .getBytes(StandardCharsets.ISO_8859_1));

        File out = new BwEarMojo().ensureCopybookResource(cpy);
        Element root = new SAXBuilder().build(out).getRootElement();
        assertEquals("BWSharedResource", root.getName());
        assertEquals("AIU0_REP.cpy", root.getChildText("name"));
        assertEquals("ae.shared.CCBSchemaResource", root.getChildText("resourceType"));
        Element config = root.getChild("config");
        assertEquals("3.6.0", config.getChildText("version"));
        assertEquals("COBOL", config.getChildText("copybookType"));
        assertEquals("ieee", config.getChildText("float"));
        assertEquals("true", config.getChildText("floatSet"));
        assertEquals("true", config.getChildText("legacyAlign"));
        assertEquals("1", config.getChildText("metadataVersion"));
        assertEquals("ASCII", config.getChildText("encoding"));
        assertTrue("copybook text preserved", config.getChildText("copybook").contains("01 REC."));
    }

    /**
     * Regression (BUG-2): non-ASCII copybook bytes must be preserved (default ISO-8859-1),
     * NOT replaced with U+FFFD like buildear's UTF-8 read does. This is the deliberate,
     * data-preserving divergence from buildear.
     */
    @Test
    public void rawCopybookNonAsciiPreservedNotCorrupted() throws Exception {
        File dir = tmp.newFolder("cpy-nonascii");
        File cpy = new File(dir, "X.cpy");
        // 0xC1 = 'Á' in ISO-8859-1 (invalid as UTF-8 → buildear would corrupt to U+FFFD)
        Files.write(cpy.toPath(), new byte[] {' ',' ','*',' ',(byte)0xC1,'r','e','a','\r','\n'});

        File out = new BwEarMojo().ensureCopybookResource(cpy);
        String copybook = new SAXBuilder().build(out).getRootElement()
            .getChild("config").getChildText("copybook");
        assertTrue("accented char must be preserved as Á, not U+FFFD",
            copybook.contains("Área"));
        assertFalse("must not contain the U+FFFD replacement char",
            copybook.contains("�"));
    }

    /**
     * Regression (BUG-2): an already-XML copybook resource gets the five metadata elements
     * injected while its stored {@code <version>} is preserved (not overwritten with 3.6.0).
     */
    @Test
    public void xmlCopybookMetadataInjectedVersionPreserved() throws Exception {
        File dir = tmp.newFolder("cpy-xml");
        File cpy = writeFile(dir, "BKAIX011.cpy",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<BWSharedResource>\n"
            + "  <name>BKAIX011.cpy</name>\n"
            + "  <resourceType>ae.shared.CCBSchemaResource</resourceType>\n"
            + "  <config>\n    <version>2.1.0</version>\n    <fixedFormat>true</fixedFormat>\n"
            + "    <encoding>ASCII</encoding>\n    <copybook>01 X.</copybook>\n  </config>\n"
            + "</BWSharedResource>\n");

        File out = new BwEarMojo().ensureCopybookResource(cpy);
        Element config = new SAXBuilder().build(out).getRootElement().getChild("config");
        assertEquals("stored version preserved (not forced to 3.6.0)",
            "2.1.0", config.getChildText("version"));
        assertEquals("COBOL", config.getChildText("copybookType"));
        assertEquals("1", config.getChildText("metadataVersion"));
    }

    // -----------------------------------------------------------------------
    //  Unit test: extractBwResourceRefs picks up opImpl attribute
    // -----------------------------------------------------------------------

    /**
     * Regression: {@code extractBwResourceRefs} on a {@code .serviceagent} file must
     * return paths from {@code opImpl} attributes, {@code sharedChannel} text, {@code ref}
     * text, and WSDL {@code location} attributes.
     */
    @Test
    public void extractRefsFromServiceAgentIncludesOpImpl() throws Exception {
        File dir = tmp.newFolder("sa-unit");
        File sa = writeFile(dir, "GetData.serviceagent",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ServiceAgent xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\"\n"
            + "    xmlns:ns0=\"http://schemas.xmlsoap.org/wsdl/\">\n"
            + "  <opTable>\n"
            + "    <row opName=\"GetData\" opImpl=\"/Svc/Op/GetData.process\"/>\n"
            + "  </opTable>\n"
            + "  <sharedChannel>/Resources/JMS.sharedjmscon</sharedChannel>\n"
            + "  <ref>/Resources/Context.contextresource</ref>\n"
            + "  <ns0:import location=\"/Resources/Service.wsdl\"/>\n"
            + "</pd:ServiceAgent>");

        Set<String> refs = extractRefs(sa);

        assertTrue("opImpl process path must be extracted", refs.contains("/Svc/Op/GetData.process"));
        assertTrue("sharedChannel path must be extracted", refs.contains("/Resources/JMS.sharedjmscon"));
        assertTrue("ref contextresource must be extracted", refs.contains("/Resources/Context.contextresource"));
        assertTrue("WSDL location must be extracted", refs.contains("/Resources/Service.wsdl"));
    }

    // -----------------------------------------------------------------------
    //  Integration test: BFS includes opImpl processes and serviceagent SAR refs
    // -----------------------------------------------------------------------

    /**
     * Regression: in multi-PAR mode ({@code filterParByReachability=true}), an
     * implementation process referenced only via a serviceagent's {@code opImpl}
     * attribute must be included in the PAR, and SAR resources referenced by
     * the serviceagent must be included in the SAR.
     *
     * <p>Before the fix: the BFS seeded only {@code .process} entry points, so the
     * serviceagent entry point was ignored and neither the implementation process
     * nor the serviceagent SAR resources were discovered.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void serviceAgentOpImplProcessAndSarRefsDiscoveredInMultiParMode() throws Exception {
        File dir = tmp.newFolder("sa-bfs");

        // Promoted serviceagent (entry point declared in .archive descriptor)
        File saFile = writeFile(dir, "GetData.serviceagent",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ServiceAgent xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\"\n"
            + "    xmlns:ns0=\"http://schemas.xmlsoap.org/wsdl/\">\n"
            + "  <opTable>\n"
            + "    <row opName=\"GetData\" opImpl=\"/Svc/Op/GetDataImpl.process\"/>\n"
            + "  </opTable>\n"
            + "  <sharedChannel>/Resources/JMS.sharedjmscon</sharedChannel>\n"
            + "  <ref>/Resources/Context.contextresource</ref>\n"
            + "  <ns0:import location=\"/Resources/Service.wsdl\"/>\n"
            + "</pd:ServiceAgent>");

        // Implementation process referenced only via opImpl — no direct .archive entry
        File procFile = writeFile(dir, "GetDataImpl.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/Svc/Op/GetDataImpl</pd:name>\n"
            + "</pd:ProcessDefinition>");

        // SAR resources declared by the serviceagent
        File jmsFile  = writeFile(dir, "JMS.sharedjmscon",   "<connection/>");
        File ctxFile  = writeFile(dir, "Context.contextresource", "<context/>");
        File wsdlFile = writeFile(dir, "Service.wsdl",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://schemas.xmlsoap.org/wsdl/\"/>");

        // parFiles: promoted serviceagent + available implementation process
        List parFiles = new ArrayList();
        parFiles.add(bwFile(saFile,   "Svc/Op/GetData.serviceagent"));
        parFiles.add(bwFile(procFile, "Svc/Op/GetDataImpl.process"));

        // sarFiles: the three resources referenced by the serviceagent
        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(jmsFile,  "Resources/JMS.sharedjmscon"));
        sarFiles.add(bwFile(ctxFile,  "Resources/Context.contextresource"));
        sarFiles.add(bwFile(wsdlFile, "Resources/Service.wsdl"));

        // The archive descriptor declares the serviceagent as entry point, not the impl process
        List<String> entryPoints = Collections.singletonList("/Svc/Op/GetData.serviceagent");
        List<String> sharedRes   = Collections.emptyList();

        // Multi-PAR mode: only reachable processes are kept
        applyTransitive(parFiles, sarFiles, entryPoints, sharedRes, true);

        // Extract file names from the result lists for assertion messages
        Set<String> parNames = fileNames(parFiles);
        Set<String> sarNames = fileNames(sarFiles);

        assertTrue("Promoted serviceagent must remain in PAR",
            parNames.contains("GetData.serviceagent"));
        assertTrue("Implementation process referenced via opImpl must be in PAR",
            parNames.contains("GetDataImpl.process"));
        assertTrue("sharedjmscon declared by serviceagent must be in SAR",
            sarNames.contains("JMS.sharedjmscon"));
        assertTrue("contextresource declared by serviceagent must be in SAR",
            sarNames.contains("Context.contextresource"));
        assertTrue("WSDL declared by serviceagent must be in SAR",
            sarNames.contains("Service.wsdl"));
    }

    /**
     * Regression: relative {@code schemaLocation} and {@code location} attributes inside a WSDL
     * file must be resolved against the WSDL's BW directory and the referenced XSDs must be
     * included in the SAR.
     *
     * <p>Before the fix, {@code followSharedResourceRefs} only called {@code extractBwResourceRefs}
     * which requires a leading {@code /}. Relative refs like {@code ../../Ext/Types.xsd} were
     * silently skipped, causing the XSD files to be missing from the SAR.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void relativeXsdRefsInsideWsdlAreFollowed() throws Exception {
        File dir = tmp.newFolder("wsdl-relative-xsd");

        // WSDL at bw path "Svc/Resources/WSDL/Service.wsdl" references
        // ../../SchemaDefinitions/Types.xsd  →  "Svc/SchemaDefinitions/Types.xsd"
        File wsdlFile = writeFile(dir, "Service.wsdl",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://schemas.xmlsoap.org/wsdl/\"\n"
            + "    xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">\n"
            + "  <import location=\"../../SchemaDefinitions/Types.xsd\""
            + "    namespace=\"http://example.com/types\"/>\n"
            + "  <types>\n"
            + "    <xs:schema>\n"
            + "      <xs:import schemaLocation=\"../../SchemaDefinitions/Inline.xsd\""
            + "        namespace=\"http://example.com/inline\"/>\n"
            + "    </xs:schema>\n"
            + "  </types>\n"
            + "</definitions>");

        File typesXsd  = writeFile(dir, "Types.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>");
        File inlineXsd = writeFile(dir, "Inline.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>");
        // An extra XSD not referenced — must NOT be pulled in
        File unreachableXsd = writeFile(dir, "Unreachable.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>");

        // WSDL is at "Svc/Resources/WSDL/Service.wsdl"
        // Relative refs resolve to "Svc/SchemaDefinitions/Types.xsd" and "Svc/SchemaDefinitions/Inline.xsd"
        List parFiles = new ArrayList();
        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(wsdlFile,      "Svc/Resources/WSDL/Service.wsdl"));
        sarFiles.add(bwFile(typesXsd,      "Svc/SchemaDefinitions/Types.xsd"));
        sarFiles.add(bwFile(inlineXsd,     "Svc/SchemaDefinitions/Inline.xsd"));
        sarFiles.add(bwFile(unreachableXsd, "Svc/SchemaDefinitions/Unreachable.xsd"));

        // Single-PAR mode: no process entry points, but the WSDL is reachable via the SAR
        // We use a minimal process that references the WSDL directly to trigger its traversal
        File procFile = writeFile(dir, "Op.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\"\n"
            + "    xmlns:wsdl=\"http://schemas.xmlsoap.org/wsdl/\">\n"
            + "  <pd:name>/Svc/Op</pd:name>\n"
            + "  <wsdl:import location=\"/Svc/Resources/WSDL/Service.wsdl\"/>\n"
            + "</pd:ProcessDefinition>");
        parFiles.add(bwFile(procFile, "Svc/Op.process"));

        List<String> entryPoints = Collections.singletonList("/Svc/Op.process");
        List<String> sharedRes   = Collections.emptyList();

        applyTransitive(parFiles, sarFiles, entryPoints, sharedRes, true);

        Set<String> sarNames = fileNames(sarFiles);

        assertTrue("Types.xsd reached via relative WSDL location must be in SAR",
            sarNames.contains("Types.xsd"));
        assertTrue("Inline.xsd reached via relative WSDL schemaLocation must be in SAR",
            sarNames.contains("Inline.xsd"));
        assertTrue("Unreachable.xsd not referenced by WSDL must NOT be in SAR",
            !sarNames.contains("Unreachable.xsd"));
    }

    // -----------------------------------------------------------------------
    //  xsd:import always included (no usage-based filter)
    // -----------------------------------------------------------------------

    /**
     * Regression: {@code xsd:import schemaLocation="/path/to/Schema.xsd"} in a process
     * file must always cause the XSD to be included in the SAR, regardless of whether
     * the schema's types are explicitly referenced via {@code type=} attributes.
     *
     * <p>Before this fix, a filter ({@code isXsdUsedInProcess}) discarded XSD imports
     * whose types were only used via {@code xsi:type} (a namespace attribute that
     * {@code e.getAttribute("type")} does not return) or that were structural imports
     * (e.g. SOAP envelope schemas) with no direct type references in the process body.
     * TIBCO Designer/buildear always packages declared {@code xsd:import} schemas.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void xsdImportAlwaysIncludedRegardlessOfTypeUsage() throws Exception {
        File dir = tmp.newFolder("xsd-import-always");

        // Schema whose types are only used via xsi:type — previously filtered out
        File schema = writeFile(dir, "Schema.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"\n"
            + "    targetNamespace=\"urn:Test\" elementFormDefault=\"qualified\">\n"
            + "  <xs:complexType name=\"MyType\">\n"
            + "    <xs:sequence><xs:element name=\"value\" type=\"xs:string\"/></xs:sequence>\n"
            + "  </xs:complexType>\n"
            + "</xs:schema>");

        // Structural-only import (no type refs at all, like SOAP schemas)
        File soapSchema = writeFile(dir, "SOAP11.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"\n"
            + "    targetNamespace=\"http://schemas.xmlsoap.org/soap/envelope/\">\n"
            + "</xs:schema>");

        // Process that imports both XSDs — uses MyType only via xsi:type, no type= ref
        File proc = writeFile(dir, "Op.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition\n"
            + "    xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\"\n"
            + "    xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"\n"
            + "    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
            + "    xmlns:ns1=\"urn:Test\"\n"
            + "    xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">\n"
            + "  <xsd:import namespace=\"urn:Test\" schemaLocation=\"/Svc/Schema.xsd\"/>\n"
            + "  <xsd:import namespace=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " schemaLocation=\"/Svc/SOAP11.xsd\"/>\n"
            + "  <pd:name>/Svc/Op</pd:name>\n"
            + "  <!-- type used only via xsi:type, not as type= attribute -->\n"
            + "  <data xsi:type=\"ns1:MyType\"><value>hello</value></data>\n"
            + "</pd:ProcessDefinition>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(proc, "Svc/Op.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(schema,     "Svc/Schema.xsd"));
        sarFiles.add(bwFile(soapSchema, "Svc/SOAP11.xsd"));

        List<String> entryPoints = Collections.singletonList("/Svc/Op.process");
        List<String> sharedRes   = Collections.emptyList();

        applyTransitive(parFiles, sarFiles, entryPoints, sharedRes, true);

        Set<String> sarNames = fileNames(sarFiles);

        assertTrue("Schema.xsd used only via xsi:type must be in SAR",
            sarNames.contains("Schema.xsd"));
        assertTrue("SOAP11.xsd (structural import, no type refs) must be in SAR",
            sarNames.contains("SOAP11.xsd"));
    }

    // -----------------------------------------------------------------------
    //  Java Global Service Agent promoted to PAR via <JavaGlobalInstance>
    // -----------------------------------------------------------------------

    /**
     * Regression: a {@code .serviceagent} file of type {@code ae.shared.JavaGlobalServiceAgent}
     * that is referenced by a process via a {@code <JavaGlobalInstance>} element must be placed
     * in the PAR, not the SAR.
     *
     * <p>Before this fix, the serviceagent was left in the SAR (because {@code .serviceagent}
     * is in {@code SAR_EXTENSIONS}). TIBCO buildear packages Java Global Service Agents
     * alongside the processes that use them — they are process-archive resources, not
     * shared-archive resources.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void javaGlobalServiceAgentPromotedToParViaJavaGlobalInstance() throws Exception {
        File dir = tmp.newFolder("java-global-sa");

        // Java Global Service Agent — referenced from a process via <JavaGlobalInstance>
        File saFile = writeFile(dir, "Java Stats Transactions.serviceagent",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<BWSharedResource>\n"
            + "  <name>Java Stats Transactions</name>\n"
            + "  <resourceType>ae.shared.JavaGlobalServiceAgent</resourceType>\n"
            + "  <config>\n"
            + "    <class>com.tibco.plugin.java.JavaGlobalServiceAgent</class>\n"
            + "    <ConstructorInfo>\n"
            + "      <className>com.example.Monitor</className>\n"
            + "    </ConstructorInfo>\n"
            + "  </config>\n"
            + "</BWSharedResource>");

        // Process that uses the Java Global via <JavaGlobalInstance>
        File proc = writeFile(dir, "ManageStats.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/Monitor/Processes/Common/ManageStats</pd:name>\n"
            + "  <pd:activity name=\"GetStats\">\n"
            + "    <JavaGlobalInstance>/Monitor/Resources/JAVA/Java Stats Transactions.serviceagent"
            + "</JavaGlobalInstance>\n"
            + "  </pd:activity>\n"
            + "</pd:ProcessDefinition>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(proc, "Monitor/Processes/Common/ManageStats.process"));

        // Serviceagent starts in SAR (.serviceagent is SAR_EXTENSIONS by default)
        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(saFile,
            "Monitor/Resources/JAVA/Java Stats Transactions.serviceagent"));

        List<String> entryPoints =
            Collections.singletonList("/Monitor/Processes/Common/ManageStats.process");
        List<String> sharedRes = Collections.emptyList();

        applyTransitive(parFiles, sarFiles, entryPoints, sharedRes, true);

        Set<String> parNames = fileNames(parFiles);
        Set<String> sarNames = fileNames(sarFiles);

        assertTrue("Java Global serviceagent must be promoted to PAR",
            parNames.contains("Java Stats Transactions.serviceagent"));
        assertTrue("Java Global serviceagent must also remain in SAR (buildear places it in both)",
            sarNames.contains("Java Stats Transactions.serviceagent"));
    }

    /**
     * Regression (Fix #3): a Java Global service agent already promoted to the PAR from the
     * descriptor's {@code processProperty} list (so it starts in {@code parFiles}, NOT in the
     * SAR pool / {@code resourceIndex}) must ALSO be placed in the SAR when a packaged process
     * references it via {@code <JavaGlobalInstance>}.
     *
     * <p>The existing resourceIndex-based loop cannot find such an agent — promotion removed it
     * from the SAR pool. Fix #3b resolves it from the promoted-PAR set instead. buildear packages
     * Java Global service agents in BOTH the PAR and the SAR.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void descriptorPromotedServiceAgentAlsoPlacedInSarViaJavaGlobalInstance() throws Exception {
        File dir = tmp.newFolder("descriptor-promoted-sa");

        File saFile = writeFile(dir, "EMSRuntimeGlobalInstance.serviceagent",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<BWSharedResource>\n"
            + "  <name>EMSRuntimeGlobalInstance</name>\n"
            + "  <resourceType>ae.shared.JavaGlobalServiceAgent</resourceType>\n"
            + "  <config>\n"
            + "    <class>com.tibco.plugin.java.JavaGlobalServiceAgent</class>\n"
            + "  </config>\n"
            + "</BWSharedResource>");

        File proc = writeFile(dir, "Bootstrap.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/App/Bootstrap</pd:name>\n"
            + "  <pd:activity name=\"InitEMS\">\n"
            + "    <JavaGlobalInstance>/App/Resources/EMSRuntimeGlobalInstance.serviceagent"
            + "</JavaGlobalInstance>\n"
            + "  </pd:activity>\n"
            + "</pd:ProcessDefinition>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(proc, "App/Bootstrap.process"));
        // Descriptor-promoted serviceagent starts in the PAR, NOT in the SAR pool.
        parFiles.add(bwFile(saFile, "App/Resources/EMSRuntimeGlobalInstance.serviceagent"));

        List sarFiles = new ArrayList();

        List<String> entryPoints = Collections.singletonList("/App/Bootstrap.process");
        List<String> sharedRes = Collections.emptyList();

        applyTransitive(parFiles, sarFiles, entryPoints, sharedRes, true);

        Set<String> parNames = fileNames(parFiles);
        Set<String> sarNames = fileNames(sarFiles);

        assertTrue("descriptor-promoted serviceagent stays in PAR",
            parNames.contains("EMSRuntimeGlobalInstance.serviceagent"));
        assertTrue("descriptor-promoted serviceagent referenced via JavaGlobalInstance must also be in SAR",
            sarNames.contains("EMSRuntimeGlobalInstance.serviceagent"));
    }

    /**
     * Regression (Fix #3): a serviceagent that is NOT referenced by any packaged process must
     * NOT appear in the SAR merely because a global variable value names its path. buildear
     * places Java Global service agents in the SAR only through {@code <JavaGlobalInstance>}
     * process references, never via a global-variable path.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void unreferencedServiceAgentNotPulledIntoSar() throws Exception {
        File dir = tmp.newFolder("unreferenced-sa");

        File saFile = writeFile(dir, "CacheGlobalInstance.serviceagent",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<BWSharedResource>\n"
            + "  <name>CacheGlobalInstance</name>\n"
            + "  <resourceType>ae.shared.JavaGlobalServiceAgent</resourceType>\n"
            + "  <config><class>x</class></config>\n"
            + "</BWSharedResource>");

        // Process references nothing.
        File proc = writeFile(dir, "Noop.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/App/Noop</pd:name>\n"
            + "</pd:ProcessDefinition>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(proc, "App/Noop.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(saFile, "App/Resources/CacheGlobalInstance.serviceagent"));

        List<String> entryPoints = Collections.singletonList("/App/Noop.process");
        List<String> sharedRes = Collections.emptyList();

        applyTransitive(parFiles, sarFiles, entryPoints, sharedRes, true);

        Set<String> sarNames = fileNames(sarFiles);
        assertFalse("unreferenced serviceagent must not appear in SAR",
            sarNames.contains("CacheGlobalInstance.serviceagent"));
    }

    @SuppressWarnings("rawtypes")
    private Set<String> fileNames(List bwFiles) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        for (Object bwf : bwFiles) {
            File f = (File) bwf.getClass().getDeclaredField("file").get(bwf);
            names.add(f.getName());
        }
        return names;
    }
}

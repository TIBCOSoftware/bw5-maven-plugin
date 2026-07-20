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
import java.util.Locale;

import com.tibco.bw.maven.plugin.descriptor.SubstVarParser;
import com.tibco.bw.maven.plugin.descriptor.TibcoXmlGenerator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

/**
 * Regression tests for adapter definition files (.adb, .adldap) inclusion in the SAR.
 *
 * <p>TIBCO buildear includes an adapter definition file in the SAR when at least one
 * process in the archive references it via {@code ae.aepalette.sharedProperties.adapterService}.
 * If no process references it (pure adapter-only archive), the file stays only in the AAR.</p>
 *
 * <p>Before this fix, {@code collectFiles()} blanket-excluded all {@code .ad*} files (except
 * {@code .adapter}) from the SAR scan, and {@code extractBwResourceRefs} did not strip the
 * {@code #adapterService.X} fragment from such references.  Both bugs together caused
 * {@code .adb}/{@code .adldap} files to be missing from Maven-built SARs even when processes
 * referenced them.</p>
 */
public class BwEarMojoAdapterSarTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final Method EXTRACT_REFS;
    private static final Method APPLY_TRANSITIVE;
    private static final Method COLLECT_FILES;
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

            COLLECT_FILES = BwEarMojo.class.getDeclaredMethod(
                "collectFiles", File.class, File.class, List.class, List.class, List.class);
            COLLECT_FILES.setAccessible(true);

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

    /**
     * Returns minimal valid AESDK adapter instance XML containing {@code <AESDK:instanceId>}.
     * Used by AAR-creation tests that need isAdapterInstanceFile() to return true.
     *
     * @param nsPrefix  namespace prefix for the adapter element, e.g. {@code "SAPAdapter"}
     * @param fragName  value of the {@code name} attribute, e.g. {@code "SAPAdapter"}
     * @param instanceId the instanceId value
     */
    private static String aesdkXml(String nsPrefix, String fragName, String instanceId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\""
            + " xmlns:AESDK=\"http://www.tibco.com/xmlns/aemeta/adapter/2002\">"
            + "<" + nsPrefix + ":adapter xmlns:" + nsPrefix + "=\"http://www.tibco.com/xmlns/adapter/"
            + nsPrefix + "/2002\" name=\"" + fragName + "\">"
            + "<AESDK:instanceId>" + instanceId + "</AESDK:instanceId>"
            + "</" + nsPrefix + ":adapter>"
            + "</Repository:repository>";
    }

    /**
     * Regression (BUG-M1): {@code toSarPaths} must reference an adapter instance file in the
     * EXTERNAL_DEPENDENCIES list by its adapter-type URI {@code "<path>#adapter.<fragName>"}
     * (matching buildear's AEResource.getURI()), while non-adapter resources keep the bare path.
     */
    @Test
    public void toSarPathsAppendsAdapterFragmentToInstanceFile() throws Exception {
        File dir = tmp.newFolder("m1");
        File adr3 = writeFile(dir, "R3AdapterConfiguration.adr3",
                aesdkXml("SAPAdapter", "SAPAdapter", "inst1"));
        File jdbc = writeFile(dir, "JDBC Connection.sharedjdbc", "<x/>");

        Method toSarPaths = BwEarMojo.class.getDeclaredMethod("toSarPaths", List.class);
        toSarPaths.setAccessible(true);
        List<Object> sarFiles = Arrays.asList(
                bwFile(adr3, "R3AdapterConfiguration.adr3"),
                bwFile(jdbc, "JDBC Connection.sharedjdbc"));
        @SuppressWarnings("unchecked")
        List<String> paths = (List<String>) toSarPaths.invoke(new BwEarMojo(), sarFiles);

        assertTrue("adapter instance file must carry the #adapter.<frag> fragment",
                paths.contains("/R3AdapterConfiguration.adr3#adapter.SAPAdapter"));
        assertTrue("non-adapter resource keeps its bare path",
                paths.contains("/JDBC Connection.sharedjdbc"));
        assertFalse("bare adapter path must NOT appear in the BOM",
                paths.contains("/R3AdapterConfiguration.adr3"));
    }

    /**
     * Regression (BUG-M3): a process copied between folders keeps a stale internal
     * {@code <pd:name>}. buildear keys BwBPConfiguration.processDefinitionName and the BOM off the
     * resource LOCATION (ProcessDeploymentData.getPath()), so {@code parseProcesses} must set the
     * metadata name from the file's repository path, NOT the internal {@code <pd:name>}.
     */
    @Test
    public void parseProcessesUsesFileLocationNotStaleInternalName() throws Exception {
        File dir = tmp.newFolder("m3");
        File proc = writeFile(dir, "OnStartup.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">"
            + "<pd:name>BusinessDomains/COMPLEX/DomainResources/Processes/On Startup.process</pd:name>"
            + "<pd:starter name=\"OnStartup\"><pd:type>com.tibco.pe.core.OnStartupEventSource</pd:type></pd:starter>"
            + "</pd:ProcessDefinition>");
        Object bwf = bwFile(proc, "BusinessDomains/MVS/DomainResources/Processes/On Startup.process");

        Method parseProcesses = BwEarMojo.class.getDeclaredMethod("parseProcesses", List.class, File.class);
        parseProcesses.setAccessible(true);
        List<?> metas = (List<?>) parseProcesses.invoke(new BwEarMojo(),
                new java.util.ArrayList<>(Collections.singletonList(bwf)), dir);

        assertEquals(1, metas.size());
        Object meta = metas.get(0);
        String name = (String) meta.getClass().getField("name").get(meta);
        assertEquals("processDefinition name must be the file location, not the stale internal <pd:name>",
                "BusinessDomains/MVS/DomainResources/Processes/On Startup.process", name);
    }

    /**
     * BUG-E: opt-in extra engine properties are emitted into the Adapter SDK Properties block with
     * their {@code java.property.} twin (matching buildear), so e.g. the REST/JSON
     * {@code com.tibco.plugin.restjson.escape.unicodeInText} property can be included.
     */
    @Test
    public void extraEnginePropertiesEmitPropertyAndJavaPropertyTwin() throws Exception {
        List<TibcoXmlGenerator.SdkProperty> props = BwEarMojo.parseExtraEngineProperties(
            Collections.singletonList("com.tibco.plugin.restjson.escape.unicodeInText=true"));

        assertEquals("plain property + java.property twin", 2, props.size());
        TibcoXmlGenerator.SdkProperty plain = props.get(0);
        assertEquals("com.tibco.plugin.restjson.escape.unicodeInText", plain.option);
        assertEquals("true", plain.defaultValue);
        // label==description==name → appendSdkProperties renders "<name> <name>" (buildear format)
        assertEquals(plain.option, plain.label);
        assertEquals(plain.option, plain.description);

        TibcoXmlGenerator.SdkProperty twin = props.get(1);
        assertEquals("java.property.com.tibco.plugin.restjson.escape.unicodeInText", twin.option);
        assertEquals("true", twin.defaultValue);
    }

    @Test
    public void extraEnginePropertiesNoDoubleTwinWhenAlreadyJavaProperty() throws Exception {
        List<TibcoXmlGenerator.SdkProperty> props = BwEarMojo.parseExtraEngineProperties(
            Collections.singletonList("java.property.com.tibco.plugin.restjson.escape.unicodeInText=true"));
        assertEquals("already a java.property → no extra twin", 1, props.size());
        assertEquals("java.property.com.tibco.plugin.restjson.escape.unicodeInText",
            props.get(0).option);
    }

    @Test
    public void extraEnginePropertiesEmptyAndNullSafe() throws Exception {
        assertTrue(BwEarMojo.parseExtraEngineProperties(null).isEmpty());
        assertTrue(BwEarMojo.parseExtraEngineProperties(
            Arrays.asList("", "   ", null)).isEmpty());
    }

    // -----------------------------------------------------------------------
    //  Unit test: extractBwResourceRefs strips #fragment from .adb and .adldap
    // -----------------------------------------------------------------------

    /**
     * Regression: {@code extractBwResourceRefs} on a process file must extract the adapter
     * definition file path from {@code ae.aepalette.sharedProperties.adapterService} elements
     * by stripping the {@code #adapterService.X} fragment.
     */
    @Test
    public void extractRefsStripsFragmentFromAdbAndAdldapReferences() throws Exception {
        File dir = tmp.newFolder("adapter-refs-unit");
        File proc = writeFile(dir, "Process.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/Process Definition</pd:name>\n"
            + "  <pd:activity name=\"ADB Pub\">\n"
            + "    <ae.aepalette.sharedProperties.adapterService>"
            + "/ActiveDatabaseAdapterConfiguration.adb#adapterService.ADBPublisher"
            + "</ae.aepalette.sharedProperties.adapterService>\n"
            + "  </pd:activity>\n"
            + "  <pd:activity name=\"LDAP Op\">\n"
            + "    <ae.aepalette.sharedProperties.adapterService>"
            + "/LDAPAdapterConfiguration.adldap#adapterService.LDAPServer"
            + "</ae.aepalette.sharedProperties.adapterService>\n"
            + "  </pd:activity>\n"
            + "</pd:ProcessDefinition>");

        Set<String> refs = extractRefs(proc);

        assertTrue(".adb reference with #fragment must be extracted",
            refs.contains("/ActiveDatabaseAdapterConfiguration.adb"));
        assertTrue(".adldap reference with #fragment must be extracted",
            refs.contains("/LDAPAdapterConfiguration.adldap"));
    }

    // -----------------------------------------------------------------------
    //  Integration test: .adb in SAR when process references it
    // -----------------------------------------------------------------------

    /**
     * Regression: when a process references an adapter via
     * {@code ae.aepalette.sharedProperties.adapterService}, the adapter definition file
     * ({@code .adb}) must be included in the SAR.
     *
     * <p>This mirrors the {@code adbsample} project where {@code Process Definition.process}
     * references {@code /ActiveDatabaseAdapterConfiguration.adb#adapterService.ADBPublisher}.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void adbIncludedInSarWhenProcessReferencesIt() throws Exception {
        File dir = tmp.newFolder("adb-in-sar");

        File adbFile = writeFile(dir, "ActiveDatabaseAdapterConfiguration.adb",
            "<adapter><name>ActiveDatabaseAdapterConfiguration</name></adapter>");

        File procFile = writeFile(dir, "Process Definition.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/Process Definition</pd:name>\n"
            + "  <pd:activity name=\"ADB Pub\">\n"
            + "    <ae.aepalette.sharedProperties.adapterService>"
            + "/ActiveDatabaseAdapterConfiguration.adb#adapterService.ADBPublisher"
            + "</ae.aepalette.sharedProperties.adapterService>\n"
            + "  </pd:activity>\n"
            + "</pd:ProcessDefinition>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(procFile, "Process Definition.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(adbFile, "ActiveDatabaseAdapterConfiguration.adb"));

        List<String> entryPoints = Collections.singletonList("/Process Definition.process");
        List<String> sharedRes   = Collections.emptyList();

        applyTransitive(parFiles, sarFiles, entryPoints, sharedRes, false);

        assertTrue(".adb file must be in SAR when process references it",
            fileNames(sarFiles).contains("ActiveDatabaseAdapterConfiguration.adb"));
    }

    /**
     * Same as {@link #adbIncludedInSarWhenProcessReferencesIt} but for {@code .adldap} files.
     * Mirrors the {@code adldapsample} project.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void adldapIncludedInSarWhenProcessReferencesIt() throws Exception {
        File dir = tmp.newFolder("adldap-in-sar");

        File adldapFile = writeFile(dir, "LDAPAdapterConfiguration.adldap",
            "<adapter><name>LDAPAdapterConfiguration</name></adapter>");

        File procFile = writeFile(dir, "Process.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/Process</pd:name>\n"
            + "  <pd:activity name=\"LDAP Op\">\n"
            + "    <ae.aepalette.sharedProperties.adapterService>"
            + "/LDAPAdapterConfiguration.adldap#adapterService.LDAPServer"
            + "</ae.aepalette.sharedProperties.adapterService>\n"
            + "  </pd:activity>\n"
            + "</pd:ProcessDefinition>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(procFile, "Process.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(adldapFile, "LDAPAdapterConfiguration.adldap"));

        List<String> entryPoints = Collections.singletonList("/Process.process");
        List<String> sharedRes   = Collections.emptyList();

        applyTransitive(parFiles, sarFiles, entryPoints, sharedRes, false);

        assertTrue(".adldap file must be in SAR when process references it",
            fileNames(sarFiles).contains("LDAPAdapterConfiguration.adldap"));
    }

    // -----------------------------------------------------------------------
    //  Integration test: .adb NOT in SAR when no process references it
    // -----------------------------------------------------------------------

    /**
     * Regression: when no process references the adapter (pure adapter-only archive with no
     * {@code processArchive}), the {@code .adb} file must stay out of the SAR.
     *
     * <p>This mirrors pure adapter archives like {@code EAI_ADB_PUBS} which have only an
     * {@code adapterArchive} and no {@code processArchive} in their {@code .archive} descriptor.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void adbNotInSarWhenNoProcessReferencesIt() throws Exception {
        File dir = tmp.newFolder("adb-not-in-sar");

        File adbFile = writeFile(dir, "ADB_PUBS.adb",
            "<adapter><name>ADB_PUBS</name></adapter>");

        // No processes — pure adapter-only archive
        List parFiles = new ArrayList();

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(adbFile, "ADB_PUBS.adb"));

        List<String> entryPoints = Collections.emptyList();
        List<String> sharedRes   = Collections.emptyList();

        applyTransitive(parFiles, sarFiles, entryPoints, sharedRes, false);

        assertFalse(".adb file must NOT be in SAR when no process references it",
            fileNames(sarFiles).contains("ADB_PUBS.adb"));
    }

    /**
     * Regression (BUG-M3, multi-PAR): each PAR's EXTERNAL_RESOURCE_DEPENDENCY must list only ITS
     * OWN reachable SAR resources, not the union across all PARs. The per-PAR extdep is built from
     * the PAR's own {@code sarFiles} (scoped by reachability), NOT the accumulated combinedSarFiles.
     * This guards that scoping: BFS seeded from PAR-A's process reaches A's schema but not B's.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void multiParResourcesScopedToOwnReachableSet() throws Exception {
        File dir = tmp.newFolder("multipar-scope");
        writeFile(dir, "A.xsd", "<schema/>");
        writeFile(dir, "B.xsd", "<schema/>");
        File procA = writeFile(dir, "ProcA.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/ProcA</pd:name>\n"
            + "  <pd:activity name=\"a\"><ref>/A.xsd</ref></pd:activity>\n"
            + "</pd:ProcessDefinition>");
        File procB = writeFile(dir, "ProcB.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/ProcB</pd:name>\n"
            + "  <pd:activity name=\"b\"><ref>/B.xsd</ref></pd:activity>\n"
            + "</pd:ProcessDefinition>");

        List parFiles = new ArrayList(Arrays.asList(
            bwFile(procA, "ProcA.process"), bwFile(procB, "ProcB.process")));
        List sarFiles = new ArrayList(Arrays.asList(
            bwFile(new File(dir, "A.xsd"), "A.xsd"), bwFile(new File(dir, "B.xsd"), "B.xsd")));

        // Scope to PAR-A's entry point only (filterParByReachability=true, as the multi-PAR path does)
        applyTransitive(parFiles, sarFiles,
            Collections.singletonList("/ProcA.process"), Collections.emptyList(), true);

        Set<String> names = fileNames(sarFiles);
        assertTrue("PAR-A must reach its own A.xsd", names.contains("A.xsd"));
        assertFalse("PAR-A extdep must NOT include PAR-B's exclusive B.xsd", names.contains("B.xsd"));
    }

    // -----------------------------------------------------------------------
    //  Integration test: .adb in sharedResources is always included in SAR
    // -----------------------------------------------------------------------

    /**
     * Regression: a {@code .adb} file explicitly listed in the {@code sharedResources}
     * section of the {@code .archive} descriptor must always be included in the SAR,
     * regardless of process references.
     *
     * <p>This mirrors the {@code TestT111Hugo} project where the {@code .adb} file
     * is listed under {@code sharedResources} of the process archive.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void adbInSharedResourcesAlwaysIncludedInSar() throws Exception {
        File dir = tmp.newFolder("adb-shared-resources");

        File adbFile = writeFile(dir, "AdapterConfig.adb",
            "<adapter><name>AdapterConfig</name></adapter>");

        File procFile = writeFile(dir, "Process.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/Process</pd:name>\n"
            + "</pd:ProcessDefinition>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(procFile, "Process.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(adbFile, "AdapterConfig.adb"));

        List<String> entryPoints = Collections.singletonList("/Process.process");
        // The .archive sharedResources lists the .adb file explicitly
        List<String> sharedRes = Collections.singletonList("/AdapterConfig.adb");

        applyTransitive(parFiles, sarFiles, entryPoints, sharedRes, false);

        assertTrue(".adb listed in sharedResources must always be in SAR",
            fileNames(sarFiles).contains("AdapterConfig.adb"));
    }

    // -----------------------------------------------------------------------
    //  Unit test: extractBwResourceRefs strips self-reference fragment in .adb files
    // -----------------------------------------------------------------------

    /**
     * Regression: any {@code .ad*} adapter definition file can contain internal references
     * of the form {@code /Path/To/Adapter.adb#jmsSession.SomeName} that point to a service
     * defined within the same file.  After Category B fragment stripping these resolve to
     * {@code /Path/To/Adapter.adb} — the adapter file itself.
     *
     * <p>The adapter scanning block in {@code BwEarMojo} must skip paths whose extension
     * starts with {@code .ad} ({@code .adb}, {@code .adldap}, {@code .adsap}, …) so that
     * the adapter definition file does not get self-included in the SAR.  Only a process
     * referencing the file via {@code ae.aepalette.sharedProperties.adapterService} may
     * bring it into the SAR.</p>
     */
    @Test
    public void extractRefsStripsAdapterSelfReferenceFragment() throws Exception {
        File dir = tmp.newFolder("adb-self-ref");
        // .adb with self-reference
        File adbFile = writeFile(dir, "ADB_PUBS.adb",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<adapter>\n"
            + "  <service>"
            + "/BusinessDomains/EAI/DomainResources/Adapters/ADB/ADB_PUBS.adb#jmsSession.JMSQueue"
            + "</service>\n"
            + "</adapter>");
        // .adsap with self-reference
        File adsapFile = writeFile(dir, "SAPAdapter.adsap",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<adapter>\n"
            + "  <service>/SAP/SAPAdapter.adsap#rfcService.SomeRFC</service>\n"
            + "</adapter>");

        Set<String> adbRefs   = extractRefs(adbFile);
        Set<String> adsapRefs = extractRefs(adsapFile);

        // Category B: fragment is stripped, so the self-path is emitted by extractBwResourceRefs
        assertTrue(".adb self-ref must be returned with #fragment stripped",
            adbRefs.contains("/BusinessDomains/EAI/DomainResources/Adapters/ADB/ADB_PUBS.adb"));
        assertTrue(".adsap self-ref must be returned with #fragment stripped",
            adsapRefs.contains("/SAP/SAPAdapter.adsap"));
        // (The adapter scanning block now skips any .ad* extension before adding to
        //  referencedResourcePaths, preventing self-inclusion in the SAR.)
    }

    // -----------------------------------------------------------------------
    //  Fix A: namespace-only xsd:import resolution (broker.xsd pattern)
    // -----------------------------------------------------------------------

    /**
     * Regression: an XSD that imports another XSD using only a namespace attribute
     * (no schemaLocation) must still pull the target XSD into the SAR.
     *
     * <p>This mirrors the broker.xsd pattern seen in EAI_BW_TIBMC / COMPLEX / MVS / WS
     * projects: {@code PGM5_0_Contextos.xsd} contains
     * {@code <xsd:import namespace="http://arquitecturas/soap/2003/4_5/"/>} and
     * {@code broker.xsd} declares that targetNamespace.  Without this fix the
     * transitive import is invisible to the XSD follower and {@code broker.xsd} is
     * missing from the SAR.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void namespaceOnlyXsdImportPullsTargetXsdIntoSar() throws Exception {
        File dir = tmp.newFolder("ns-only-import");

        // Leaf XSD — declares the namespace that is imported by reference-only
        File leafXsd = writeFile(dir, "broker.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"\n"
            + "            targetNamespace=\"http://example.com/broker/types\">\n"
            + "  <xsd:complexType name=\"BrokerMsg\"><xsd:sequence/></xsd:complexType>\n"
            + "</xsd:schema>");

        // Middle XSD — imports leaf by namespace only (no schemaLocation)
        File midXsd = writeFile(dir, "Contextos.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"\n"
            + "            xmlns:b=\"http://example.com/broker/types\"\n"
            + "            targetNamespace=\"http://example.com/contextos\">\n"
            + "  <xsd:import namespace=\"http://example.com/broker/types\"/>\n"
            + "</xsd:schema>");

        // Process imports the middle XSD by absolute schemaLocation
        File proc = writeFile(dir, "Process.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/Process</pd:name>\n"
            + "  <xsd:import xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"\n"
            + "              namespace=\"http://example.com/contextos\"\n"
            + "              schemaLocation=\"/Contextos.xsd\"/>\n"
            + "</pd:ProcessDefinition>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(proc, "Process.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(midXsd,  "Contextos.xsd"));
        sarFiles.add(bwFile(leafXsd, "broker.xsd"));

        List<String> entryPoints = Collections.singletonList("/Process.process");
        List<String> sharedRes   = Collections.emptyList();

        applyTransitive(parFiles, sarFiles, entryPoints, sharedRes, false);

        Set<String> names = fileNames(sarFiles);
        assertTrue("Contextos.xsd must be in SAR (directly referenced)", names.contains("Contextos.xsd"));
        assertTrue("broker.xsd must be in SAR (resolved via namespace-only import)",
            names.contains("broker.xsd"));
    }

    // -----------------------------------------------------------------------
    //  Fix B: always-include .adb file must pull in its aeschema refs
    // -----------------------------------------------------------------------

    /**
     * Regression: a {@code .adb} file listed in the {@code sharedResources} of the
     * {@code .archive} descriptor is moved to {@code alwaysInclude} and bypasses the BFS.
     * Its {@code AESDK:loadUrl} aeschema references (e.g. {@code /AESchemas/ae/ADB/adbmetadata.aeschema})
     * must still be followed transitively so the palette AESchema files end up in the SAR.
     *
     * <p>This mirrors the {@code TestT111Hugo} project where the {@code .adb} in sharedResources
     * caused {@code AESchemas/ae/ADB/adbmetadata.aeschema}, {@code AESchemas/ae/ADB/scalar.aeschema},
     * and {@code AESchemas/ae.aeschema} to be missing from the Maven-built SAR.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void alwaysIncludeAdbPullsAeschemaChainIntoSar() throws Exception {
        File dir = tmp.newFolder("adb-aeschema-chain");

        // Leaf aeschema — the transitively-reachable base types schema
        File aeRoot = writeFile(dir, "ae.aeschema",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">\n"
            + "  <class name=\"string\"/>\n"
            + "</Repository:repository>");

        // Middle aeschema — references the root by relative path (AESchemas/ae.aeschema)
        File adbMeta = writeFile(dir, "adbmetadata.aeschema",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">\n"
            + "  <class name=\"SQL_REQUEST\">\n"
            + "    <attributeType isRef=\"true\">AESchemas/ae.aeschema#scalar.string</attributeType>\n"
            + "  </class>\n"
            + "</Repository:repository>");

        // .adb file — has an AESDK:loadUrl pointing to the middle aeschema
        File adbFile = writeFile(dir, "AdapterConfig.adb",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Repository:repository"
            + " xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\""
            + " xmlns:AESDK=\"http://www.tibco.com/xmlns/aemeta/adapter/2002\">\n"
            + "  <AESDK:loadUrl isRef=\"true\">/AESchemas/ae/ADB/adbmetadata.aeschema</AESDK:loadUrl>\n"
            + "</Repository:repository>");

        // Process — no direct reference to the .adb (it's in sharedResources, not process refs)
        File proc = writeFile(dir, "Process.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/Process</pd:name>\n"
            + "</pd:ProcessDefinition>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(proc, "Process.process"));

        // SAR files use the relative paths that match sharedResourcePaths
        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(adbFile, "AdapterConfig.adb"));
        sarFiles.add(bwFile(adbMeta, "AESchemas/ae/ADB/adbmetadata.aeschema"));
        sarFiles.add(bwFile(aeRoot,  "AESchemas/ae.aeschema"));

        List<String> entryPoints = Collections.singletonList("/Process.process");
        // The .archive sharedResources lists the .adb file
        List<String> sharedRes = Collections.singletonList("/AdapterConfig.adb");

        applyTransitive(parFiles, sarFiles, entryPoints, sharedRes, false);

        Set<String> names = fileNames(sarFiles);
        assertTrue(".adb in sharedResources must be in SAR", names.contains("AdapterConfig.adb"));
        assertTrue("adbmetadata.aeschema must be pulled in via .adb loadUrl ref",
            names.contains("adbmetadata.aeschema"));
        assertTrue("ae.aeschema must be pulled in transitively via adbmetadata.aeschema",
            names.contains("ae.aeschema"));
    }

    // -----------------------------------------------------------------------
    //  Relative WSDL import following
    // -----------------------------------------------------------------------

    /**
     * Regression: when a process references a WSDL by absolute path and that WSDL
     * imports another WSDL and an XSD by relative path, all three must appear in the SAR.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void relativeWsdlImportPullsTransitiveWsdlAndXsdIntoSar() throws Exception {
        File dir = tmp.newFolder("wsdl-relative-import");

        // types.wsdl — imported by the service WSDL via a relative wsdl:import
        File typesWsdl = writeFile(dir, "types.wsdl",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<wsdl:definitions xmlns:wsdl=\"http://schemas.xmlsoap.org/wsdl/\""
            + " targetNamespace=\"http://example.com/types\"/>\n");

        // schema.xsd — imported by the service WSDL via a relative xsd:import in wsdl:types
        File schemaXsd = writeFile(dir, "schema.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\""
            + " targetNamespace=\"http://example.com/schema\"/>\n");

        // ServiceWSDL.wsdl — directly referenced by process; imports types.wsdl + schema.xsd relatively
        File serviceWsdl = writeFile(dir, "ServiceWSDL.wsdl",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<wsdl:definitions xmlns:wsdl=\"http://schemas.xmlsoap.org/wsdl/\""
            + " xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\""
            + " targetNamespace=\"http://example.com/service\">\n"
            + "  <wsdl:import namespace=\"http://example.com/types\" location=\"types.wsdl\"/>\n"
            + "  <wsdl:types><xsd:schema>\n"
            + "    <xsd:import schemaLocation=\"schema.xsd\" namespace=\"http://example.com/schema\"/>\n"
            + "  </xsd:schema></wsdl:types>\n"
            + "</wsdl:definitions>\n");

        // Process references ServiceWSDL.wsdl by absolute BW path
        File proc = writeFile(dir, "Process.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\""
            + " xmlns:wsdl=\"http://schemas.xmlsoap.org/wsdl/\">\n"
            + "  <pd:name>/Process</pd:name>\n"
            + "  <wsdl:import namespace=\"http://example.com/service\""
            + "               location=\"/WSDL/ServiceWSDL.wsdl\"/>\n"
            + "</pd:ProcessDefinition>\n");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(proc, "Process.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(serviceWsdl, "WSDL/ServiceWSDL.wsdl"));
        sarFiles.add(bwFile(typesWsdl,   "WSDL/types.wsdl"));
        sarFiles.add(bwFile(schemaXsd,   "WSDL/schema.xsd"));

        applyTransitive(parFiles, sarFiles, Collections.singletonList("/Process.process"),
                        Collections.emptyList(), false);

        Set<String> names = fileNames(sarFiles);
        assertTrue("ServiceWSDL.wsdl must be in SAR (directly referenced)", names.contains("ServiceWSDL.wsdl"));
        assertTrue("types.wsdl must be in SAR (relative wsdl:import)", names.contains("types.wsdl"));
        assertTrue("schema.xsd must be in SAR (relative xsd:import in wsdl:types)", names.contains("schema.xsd"));
    }

    // -----------------------------------------------------------------------
    //  Case-insensitive path lookup
    // -----------------------------------------------------------------------

    /**
     * Regression: BW5 is a Windows application — path references in process files may use
     * a different case than the actual on-disk filenames (e.g. WSDL vs Wsdl directory).
     * The plugin must find the file despite the case mismatch.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void wsdlIsFoundEvenWhenReferencePathCaseDiffersFromDisk() throws Exception {
        File dir = tmp.newFolder("case-insensitive-path");

        // WSDL file lives in a "Wsdl" directory (mixed case, as on disk/git)
        File wsdlDir = new File(dir, "Wsdl");
        wsdlDir.mkdirs();
        File wsdlFile = writeFile(wsdlDir, "MyService.wsdl",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<wsdl:definitions xmlns:wsdl=\"http://schemas.xmlsoap.org/wsdl/\""
            + " targetNamespace=\"http://example.com/myservice\"/>\n");

        // Process references it via uppercase "WSDL" directory (typical Windows Developer artefact)
        File proc = writeFile(dir, "Process.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\""
            + " xmlns:wsdl=\"http://schemas.xmlsoap.org/wsdl/\">\n"
            + "  <pd:name>/Process</pd:name>\n"
            + "  <wsdl:import namespace=\"http://example.com/myservice\""
            + "               location=\"/ServiceResources/WSDL/MyService.wsdl\"/>\n"
            + "</pd:ProcessDefinition>\n");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(proc, "Process.process"));

        List sarFiles = new ArrayList();
        // BwFile uses the on-disk BW path (lowercase "Wsdl")
        sarFiles.add(bwFile(wsdlFile, "ServiceResources/Wsdl/MyService.wsdl"));

        applyTransitive(parFiles, sarFiles, Collections.singletonList("/Process.process"),
                        Collections.emptyList(), false);

        Set<String> names = fileNames(sarFiles);
        assertTrue("MyService.wsdl must be in SAR despite case mismatch in reference path",
                   names.contains("MyService.wsdl"));
    }

    // -----------------------------------------------------------------------
    //  Unqualified term ref (XMLParseActivity coercion schema)
    // -----------------------------------------------------------------------

    /**
     * Regression: XMLParseActivity and coercion activities reference their output schema via
     * {@code <term ref="ElementName"/>} (no namespace prefix = unqualified element, defined
     * in an XSD with no targetNamespace). This reference was not scanned by
     * extractBwResourceRefs, so the XSD was silently omitted from the SAR.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void unqualifiedTermRefPullsNoNamespaceXsdIntoSar() throws Exception {
        File dir = tmp.newFolder("term-ref-unqualified");

        // XSD with no targetNamespace defining element "MyData"
        File myDataXsd = writeFile(dir, "MyData.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\""
            + " elementFormDefault=\"unqualified\">\n"
            + "  <xs:element name=\"MyData\" type=\"xs:string\"/>\n"
            + "</xs:schema>\n");

        // Process using XMLParseActivity with <term ref="MyData"/> (unqualified)
        File proc = writeFile(dir, "ParseProcess.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/ParseProcess</pd:name>\n"
            + "  <pd:activity name=\"Parse\">\n"
            + "    <pd:type>com.tibco.plugin.xml.XMLParseActivity</pd:type>\n"
            + "    <config>\n"
            + "      <term ref=\"MyData\"/>\n"
            + "    </config>\n"
            + "  </pd:activity>\n"
            + "</pd:ProcessDefinition>\n");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(proc, "ParseProcess.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(myDataXsd, "MyData.xsd"));

        applyTransitive(parFiles, sarFiles,
                        Collections.singletonList("/ParseProcess.process"),
                        Collections.emptyList(), true);

        Set<String> sarNames = fileNames(sarFiles);
        assertTrue("MyData.xsd must be in SAR (referenced via unqualified <term ref=\"MyData\"/>)",
                   sarNames.contains("MyData.xsd"));
    }

    // -----------------------------------------------------------------------
    //  Windows checkout artifact filtering
    // -----------------------------------------------------------------------

    /**
     * Regression: on Linux, a file checked out from Windows VCS may land in the project root
     * with its full Windows path (backslashes) as the literal filename, e.g.
     * {@code BusinessDomains\Svc\Sub\Foo.process}. collectFiles() must skip such files so they
     * never appear in the PAR or SAR.
     *
     * <p>This test is skipped on Windows because the OS does not allow '\' in filenames.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void windowsCheckoutArtifactWithBackslashInNameIsSkipped() throws Exception {
        assumeTrue("Backslash-in-filename test only runs on non-Windows",
                   !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));

        File rootDir = tmp.newFolder("backslash-artifact");

        // Normal process at a proper path
        File subDir = new File(rootDir, "Svc");
        subDir.mkdir();
        writeFile(subDir, "Normal.process",
            "<?xml version=\"1.0\"?><pd:ProcessDefinition"
            + " xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\"/>\n");

        // Checkout artifact: file whose name literally contains backslashes
        File artifact = new File(rootDir, "BusinessDomains\\Svc\\Sub\\Artifact.process");
        Files.write(artifact.toPath(),
            "<?xml version=\"1.0\"?><pd:ProcessDefinition"
                .getBytes(StandardCharsets.UTF_8));

        List parFiles  = new ArrayList();
        List sarFiles  = new ArrayList();
        List metadata  = new ArrayList();
        COLLECT_FILES.invoke(new BwEarMojo(), rootDir, rootDir, parFiles, sarFiles, metadata);

        Set<String> parNames = fileNames(parFiles);
        assertTrue("Normal.process must be collected", parNames.contains("Normal.process"));
        for (String n : parNames) {
            assertFalse("Backslash-artifact must not appear in PAR, but found: " + n,
                        n.contains("\\"));
        }
    }

    // -----------------------------------------------------------------------
    //  REST Binding/process attribute discovery
    // -----------------------------------------------------------------------

    /**
     * Regression: REST starter processes reference their implementation process via a
     * {@code process} XML attribute ({@code <Binding process="/path/RestImpl.process"/>}).
     * This attribute was not scanned by extractBwResourceRefs, so REST implementation
     * processes and their transitive SAR resources were silently omitted from the EAR.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void restBindingProcessAttributePullsImplProcessAndSarIntoEar() throws Exception {
        File dir = tmp.newFolder("rest-binding-process");

        // SAR XSD — referenced by the REST impl process
        File restSchema = writeFile(dir, "RestSchema.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\""
            + " targetNamespace=\"http://example.com/rest\"/>\n");

        // REST implementation process — referenced by starter via Binding/process attribute
        File implProc = writeFile(dir, "RestImpl.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\""
            + " xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\n"
            + "  <pd:name>/RestImpl</pd:name>\n"
            + "  <xsd:import namespace=\"http://example.com/rest\""
            + "              schemaLocation=\"/RestSchema.xsd\"/>\n"
            + "</pd:ProcessDefinition>\n");

        // REST starter process — references impl via <Binding process="...">
        File starterProc = writeFile(dir, "REST_Starter.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/REST_Starter</pd:name>\n"
            + "  <Binding path=\"/api/execute\""
            + "           process=\"/RestImpl.process\"/>\n"
            + "</pd:ProcessDefinition>\n");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(starterProc, "REST_Starter.process"));
        parFiles.add(bwFile(implProc,    "RestImpl.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(restSchema, "RestSchema.xsd"));

        // filterParByReachability=true: only entry-point + reachable processes are kept
        applyTransitive(parFiles, sarFiles,
                        Collections.singletonList("/REST_Starter.process"),
                        Collections.emptyList(), true);

        Set<String> parNames = fileNames(parFiles);
        assertTrue("REST_Starter.process must be in PAR (entry point)", parNames.contains("REST_Starter.process"));
        assertTrue("RestImpl.process must be in PAR (found via Binding process= attribute)",
                   parNames.contains("RestImpl.process"));
        Set<String> sarNames = fileNames(sarFiles);
        assertTrue("RestSchema.xsd must be in SAR (transitively referenced from RestImpl.process)",
                   sarNames.contains("RestSchema.xsd"));
    }

    /**
     * A COBOL CopyBook activity can emit a relative path without a leading '/' and with
     * a double slash, e.g.:
     *   {@code BusinessDomains/WS/Services/WSTBL5/SubProcess/CopyBooks//WSTBL5_REQ_Strings.cpy}
     * The plugin must normalise this to an absolute BW path and pull the file into the SAR.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void relativeCopyBookPathWithDoubleSlashIsNormalisedAndIncludedInSar() throws Exception {
        File dir = tmp.newFolder("copybook-relative");

        // Process referencing the .cpy via a relative path (no leading '/') with double slash
        File proc = writeFile(dir, "MyProcess.process",
              "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/MyProcess</pd:name>\n"
            + "  <ae.palette.cobolpalette.sharedProperties.copybook>"
            +       "CopyBooks//MySchema.cpy"
            + "</ae.palette.cobolpalette.sharedProperties.copybook>\n"
            + "</pd:ProcessDefinition>\n");

        // .cpy file in a CopyBooks/ subdirectory
        File copyBooksDir = new File(dir, "CopyBooks");
        copyBooksDir.mkdirs();
        File cpyFile = new File(copyBooksDir, "MySchema.cpy");
        cpyFile.createNewFile();

        List parFiles = new ArrayList();
        parFiles.add(bwFile(proc, "MyProcess.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(cpyFile, "CopyBooks/MySchema.cpy"));

        applyTransitive(parFiles, sarFiles,
                        Collections.singletonList("/MyProcess.process"),
                        Collections.emptyList(), true);

        Set<String> sarNames = fileNames(sarFiles);
        assertTrue("MySchema.cpy must be in SAR (referenced via relative path with double slash)",
                   sarNames.contains("MySchema.cpy"));
    }

    // -----------------------------------------------------------------------
    //  Fix: unreferenced SAR resources excluded in no-archive-descriptor mode
    // -----------------------------------------------------------------------

    /**
     * Regression: in no-archive-descriptor mode (filterParByReachability=false, empty
     * entry points), aeschema files not referenced by any process must be excluded from
     * the SAR — matching buildear's behaviour for SAP IDocFormatPublishingMode projects
     * where IDOCS.aeschema and structures.aeschema are present on disk but unreferenced.
     *
     * <p>Referenced aeschemas and connections must still be included.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void unreferencedSarResourcesExcludedWhenNoArchiveDescriptor() throws Exception {
        File dir = tmp.newFolder("sap-unreferenced-aeschema");

        // aeschema referenced by the process (must be included)
        File aeRoot = writeFile(dir, "ae.aeschema",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Repository:repository"
            + " xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">\n"
            + "  <class name=\"string\"/>\n"
            + "</Repository:repository>");

        // Unreferenced IDoc aeschemas present in project but unused by processes
        File idocsAeschema = writeFile(dir, "IDOCS.aeschema",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Repository:repository"
            + " xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">\n"
            + "  <sequence name=\"DEBMAS01-E1KNKKM-4x\"/>\n"
            + "</Repository:repository>");
        File structuresAeschema = writeFile(dir, "structures.aeschema",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Repository:repository"
            + " xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">\n"
            + "  <class name=\"E1KNKKM-4x\"/>\n"
            + "</Repository:repository>");

        // Unreferenced JMS connection present in project but not used by any process
        File unusedConn = writeFile(dir, "JMS Application Properties.sharedjmsapp",
            "<sharedapp><name>JMS Application Properties</name></sharedapp>");

        // Referenced SAP connection (must be included)
        File sapConn = writeFile(dir, "SAPConnection.adsap",
            "<adapter><name>SAPConnection</name></adapter>");

        // Process references ae.aeschema and SAPConnection but not the IDoc aeschemas
        File proc = writeFile(dir, "PublishProcess.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/PublishProcess</pd:name>\n"
            + "  <pd:activity name=\"SAP Publish\">\n"
            + "    <ae.aepalette.sharedProperties.adapterService>"
            + "/SAPConnection.adsap#adapterService.SAPPublisher"
            + "</ae.aepalette.sharedProperties.adapterService>\n"
            + "    <xsd:import xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"\n"
            + "                schemaLocation=\"/AESchemas/ae.aeschema\"/>\n"
            + "  </pd:activity>\n"
            + "</pd:ProcessDefinition>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(proc, "PublishProcess.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(aeRoot,             "AESchemas/ae.aeschema"));
        sarFiles.add(bwFile(idocsAeschema,      "AESchemas/ae/700/basic/IDOCS.aeschema"));
        sarFiles.add(bwFile(structuresAeschema, "AESchemas/ae/700/basic/structures.aeschema"));
        sarFiles.add(bwFile(unusedConn,         "SharedResources/JMS Application Properties.sharedjmsapp"));
        sarFiles.add(bwFile(sapConn,            "SAPConnection.adsap"));

        // No archive descriptor: empty entry points, filterParByReachability=false
        applyTransitive(parFiles, sarFiles, Collections.emptyList(), Collections.emptyList(), false);

        Set<String> names = fileNames(sarFiles);
        assertTrue("ae.aeschema must be in SAR (referenced by process)",
            names.contains("ae.aeschema"));
        assertTrue("SAPConnection.adsap must be in SAR (referenced by process)",
            names.contains("SAPConnection.adsap"));
        assertFalse("IDOCS.aeschema must NOT be in SAR (unreferenced by any process)",
            names.contains("IDOCS.aeschema"));
        assertFalse("structures.aeschema must NOT be in SAR (unreferenced by any process)",
            names.contains("structures.aeschema"));
        assertFalse("JMS Application Properties.sharedjmsapp must NOT be in SAR (unreferenced)",
            names.contains("JMS Application Properties.sharedjmsapp"));
    }

    // -----------------------------------------------------------------------
    //  SAP R/3 adapter: .adr3 → .adr3Connections transitive discovery
    // -----------------------------------------------------------------------

    /**
     * Regression: when a process references an SAP R/3 adapter configuration (.adr3), the
     * plugin must also include the connection-pool file (.adr3Connections) referenced from
     * within the .adr3 file.  Unreferenced .adr3 / .adr3TID files must be excluded.
     *
     * <p>Mirrors OutboundIDocWithRemoteTIDManager where Publisher2.adr3 and TIDManager.adr3TID
     * are present on disk but unreferenced by any process, while Publisher1.adr3 and
     * R3Connections.adr3Connections are reachable via the process → Publisher1 → R3Connections
     * reference chain.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void adr3ConnectionsDiscoveredAndUnreferencedAdr3Excluded() throws Exception {
        File dir = tmp.newFolder("adr3-transitive");

        // Publisher1.adr3: referenced by process, contains an objectGroup ref to R3Connections
        File pub1 = writeFile(dir, "Publisher1.adr3",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\"\n"
            + "  xmlns:AESDK=\"http://www.tibco.com/xmlns/aemeta/adapter/2002\">\n"
            + "  <AESDK:instanceId>Publisher1</AESDK:instanceId>\n"
            + "  <AESDK:objectGroup isRef=\"true\">/R3Connections.adr3Connections#connPool.Main</AESDK:objectGroup>\n"
            + "</Repository:repository>");

        // R3Connections.adr3Connections: discovered transitively via Publisher1
        File r3conn = writeFile(dir, "R3Connections.adr3Connections",
            "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">"
            + "<pool name=\"Main\"/></Repository:repository>");

        // Publisher2.adr3: on disk but NOT referenced by any process → must be excluded
        File pub2 = writeFile(dir, "Publisher2.adr3",
            "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">"
            + "<AESDK:instanceId xmlns:AESDK=\"http://www.tibco.com/xmlns/aemeta/adapter/2002\">"
            + "Publisher2</AESDK:instanceId></Repository:repository>");

        // TIDManager.adr3TID: on disk but NOT referenced → must be excluded
        File tidMgr = writeFile(dir, "TIDManager.adr3TID",
            "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">"
            + "<tidmgr/></Repository:repository>");

        // Process references only Publisher1.adr3
        File proc = writeFile(dir, "CREMAS Process.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/CREMAS Process</pd:name>\n"
            + "  <pd:activity name=\"SAPPub\">\n"
            + "    <ae.aepalette.sharedProperties.adapterService>"
            + "/Publisher1.adr3#adapterService.CREMAS01Publisher"
            + "</ae.aepalette.sharedProperties.adapterService>\n"
            + "  </pd:activity>\n"
            + "</pd:ProcessDefinition>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(proc, "CREMAS Process.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(pub1,   "Publisher1.adr3"));
        sarFiles.add(bwFile(r3conn, "R3Connections.adr3Connections"));
        sarFiles.add(bwFile(pub2,   "Publisher2.adr3"));
        sarFiles.add(bwFile(tidMgr, "TIDManager.adr3TID"));

        applyTransitive(parFiles, sarFiles, Collections.emptyList(), Collections.emptyList(), false);

        Set<String> names = fileNames(sarFiles);
        assertTrue("Publisher1.adr3 must be in SAR (referenced by process)",
            names.contains("Publisher1.adr3"));
        assertTrue("R3Connections.adr3Connections must be in SAR (referenced from Publisher1.adr3)",
            names.contains("R3Connections.adr3Connections"));
        assertFalse("Publisher2.adr3 must NOT be in SAR (unreferenced)",
            names.contains("Publisher2.adr3"));
        assertFalse("TIDManager.adr3TID must NOT be in SAR (unreferenced)",
            names.contains("TIDManager.adr3TID"));
    }

    // -----------------------------------------------------------------------
    //  SAP R/3: AESDK:loadUrl in .adr3 files is NOT followed for SAR inclusion
    // -----------------------------------------------------------------------

    /**
     * Regression: {@code AESDK:loadUrl} elements in {@code .adr3} adapter configuration files
     * reference runtime type definitions that the SAP adapter loads from its own plugin JAR, NOT
     * from the project. They must NOT cause aeschema files to be added to the SAR.
     *
     * <p>Mirrors IDocFormatPublishingMode where {@code R3AdapterConfiguration.adr3} has loadUrls
     * for {@code IDOCS/#class}, {@code structures/#class}, and {@code SAPAdapter40/classes/#class},
     * but buildear's reference EAR does NOT include these aeschemas from the loadUrls. Aeschemas
     * reach the SAR only when a process directly imports them or they are transitively reachable
     * from a process-imported aeschema via {@code isRef} references.</p>
     *
     * <p>For .adb/.adsap/.adldap files, {@code AESDK:loadUrl} entries DO reference project
     * aeschemas that must be included — those files are not affected by this rule.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void adr3LoadUrlRefsNotFollowedForAeschemaInclusion() throws Exception {
        File dir = tmp.newFolder("adr3-loadurl-not-followed");

        // .adr3 with AESDK:loadUrl entries for runtime schema loading
        File adr3 = writeFile(dir, "R3AdapterConfiguration.adr3",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\"\n"
            + "  xmlns:AESDK=\"http://www.tibco.com/xmlns/aemeta/adapter/2002\">\n"
            + "  <AESDK:loadUrl>/AESchemas/ae/700/basic/structures/#class</AESDK:loadUrl>\n"
            + "  <AESDK:loadUrl>/AESchemas/ae/700/basic/IDOCS/#class</AESDK:loadUrl>\n"
            + "  <AESDK:loadUrl>/AESchemas/ae/SAPAdapter40/classes/#class</AESDK:loadUrl>\n"
            + "</Repository:repository>");

        // These aeschemas are on disk but must NOT be pulled in via .adr3 loadUrls
        File structures = writeFile(dir, "structures.aeschema",
            "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">"
            + "<class name=\"E1KNKKM-4x\"/></Repository:repository>");
        File idocs = writeFile(dir, "IDOCS.aeschema",
            "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">"
            + "<sequence name=\"DEBMAS01-E1KNKKM-4x\"/></Repository:repository>");
        File classes = writeFile(dir, "classes.aeschema",
            "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">"
            + "<class name=\"RFCClient\"/></Repository:repository>");

        // Process references .adr3 via adapterService — does NOT directly import the aeschemas
        File proc = writeFile(dir, "ReceiveIDoc.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/ReceiveIDoc</pd:name>\n"
            + "  <pd:activity name=\"Receive\">\n"
            + "    <ae.aepalette.sharedProperties.adapterService>"
            + "/R3AdapterConfiguration.adr3#adapterService.IDOCSubscriber"
            + "</ae.aepalette.sharedProperties.adapterService>\n"
            + "  </pd:activity>\n"
            + "</pd:ProcessDefinition>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(proc, "ReceiveIDoc.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(adr3,       "R3AdapterConfiguration.adr3"));
        sarFiles.add(bwFile(structures, "AESchemas/ae/700/basic/structures.aeschema"));
        sarFiles.add(bwFile(idocs,      "AESchemas/ae/700/basic/IDOCS.aeschema"));
        sarFiles.add(bwFile(classes,    "AESchemas/ae/SAPAdapter40/classes.aeschema"));

        applyTransitive(parFiles, sarFiles, Collections.emptyList(), Collections.emptyList(), false);

        Set<String> paths = fileRelPaths(sarFiles);
        assertTrue("R3AdapterConfiguration.adr3 must be in SAR (referenced by process)",
            paths.contains("R3AdapterConfiguration.adr3"));
        assertFalse("structures.aeschema must NOT be in SAR (.adr3 loadUrl refs are skipped)",
            paths.contains("AESchemas/ae/700/basic/structures.aeschema"));
        assertFalse("IDOCS.aeschema must NOT be in SAR (.adr3 loadUrl refs are skipped)",
            paths.contains("AESchemas/ae/700/basic/IDOCS.aeschema"));
        assertFalse("classes.aeschema must NOT be in SAR (.adr3 loadUrl refs are skipped)",
            paths.contains("AESchemas/ae/SAPAdapter40/classes.aeschema"));
    }

    /**
     * Regression: {@code <ApplicationProperties>} inside an AE adapter activity
     * ({@code com.tibco.plugin.ae.*}) configures the adapter's internal JMS transport. The
     * referenced {@code .sharedjmsapp} file must NOT be added to the SAR.
     *
     * <p>Contrasts with standard JMS palette activities ({@code com.tibco.plugin.jms.*}) where
     * {@code <ApplicationProperties>} DOES reference a project shared JMS resource and the file
     * IS correctly included in the SAR.</p>
     *
     * <p>Mirrors IDocFormatPublishingMode where {@code ReceiveIDocFromSAP.process} uses
     * {@code AESubscriberActivity} with {@code <ApplicationProperties>/JMS Application
     * Properties.sharedjmsapp</ApplicationProperties>} — buildear excludes this file from SAR.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void applicationPropertiesInAeActivityNotAddedToSar() throws Exception {
        File dir = tmp.newFolder("ae-app-props");

        // The JMS Application Properties file
        File jmsApp = writeFile(dir, "JMS Application Properties.sharedjmsapp",
            "<sharedapp><name>JMS Application Properties</name></sharedapp>");

        // Process with an AE adapter activity containing <ApplicationProperties>
        File proc = writeFile(dir, "ReceiveIDoc.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/ReceiveIDoc</pd:name>\n"
            + "  <pd:activity name=\"Receive\">\n"
            + "    <pd:type>com.tibco.plugin.ae.AESubscriberActivity</pd:type>\n"
            + "    <config>\n"
            + "      <ae.aepalette.sharedProperties.adapterService>"
            + "/R3AdapterConfiguration.adr3#adapterService.IDOCSubscriber"
            + "</ae.aepalette.sharedProperties.adapterService>\n"
            + "      <ApplicationProperties>/JMS Application Properties.sharedjmsapp</ApplicationProperties>\n"
            + "    </config>\n"
            + "  </pd:activity>\n"
            + "</pd:ProcessDefinition>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(proc, "ReceiveIDoc.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(jmsApp, "JMS Application Properties.sharedjmsapp"));

        applyTransitive(parFiles, sarFiles, Collections.emptyList(), Collections.emptyList(), false);

        assertFalse("JMS Application Properties must NOT be in SAR when referenced from AE adapter activity",
            fileRelPaths(sarFiles).contains("JMS Application Properties.sharedjmsapp"));
    }

    /**
     * Positive case: {@code <ApplicationProperties>} inside a standard JMS palette activity
     * ({@code com.tibco.plugin.jms.*}) references a project shared JMS resource. The file
     * MUST be included in the SAR.
     *
     * <p>Mirrors BookingDetails / EAI_BW_TIBMC projects where JMSQueueSendActivity or
     * JMSTopicPublishActivity use {@code <ApplicationProperties>} to reference
     * {@code SharedResources/Transport/*.sharedjmsapp} files that buildear includes in SAR.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void applicationPropertiesInJmsActivityAddedToSar() throws Exception {
        File dir = tmp.newFolder("jms-app-props");

        File jmsApp = writeFile(dir, "JMSBookingIDProperties.sharedjmsapp",
            "<sharedapp><name>JMSBookingIDProperties</name></sharedapp>");

        File proc = writeFile(dir, "Send.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/Send</pd:name>\n"
            + "  <pd:activity name=\"SendMsg\">\n"
            + "    <pd:type>com.tibco.plugin.jms.JMSQueueSendActivity</pd:type>\n"
            + "    <config>\n"
            + "      <ApplicationProperties>"
            + "/SharedResources/Connections/JMS/JMSBookingIDProperties.sharedjmsapp"
            + "</ApplicationProperties>\n"
            + "    </config>\n"
            + "  </pd:activity>\n"
            + "</pd:ProcessDefinition>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(proc, "Send.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(jmsApp, "SharedResources/Connections/JMS/JMSBookingIDProperties.sharedjmsapp"));

        applyTransitive(parFiles, sarFiles, Collections.emptyList(), Collections.emptyList(), false);

        assertTrue("JMS Application Properties must be in SAR when referenced from JMS palette activity",
            fileRelPaths(sarFiles).contains(
                "SharedResources/Connections/JMS/JMSBookingIDProperties.sharedjmsapp"));
    }

    // -----------------------------------------------------------------------
    //  SAP R/3 adapter: SNC GV injection (loadGlobalVariablesForR3 equivalent)
    // -----------------------------------------------------------------------

    private static final Method INJECT_SAP_SNC_GVARS;

    static {
        try {
            INJECT_SAP_SNC_GVARS = BwEarMojo.class.getDeclaredMethod(
                "injectSapSncGvarsIfNeeded", List.class, List.class);
            INJECT_SAP_SNC_GVARS.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void injectSapSncGvars(List sarFiles, List<SubstVarParser.GlobalVariable> gvars) throws Exception {
        try {
            INJECT_SAP_SNC_GVARS.invoke(new BwEarMojo(), sarFiles, gvars);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        }
    }

    /**
     * Regression: buildear calls R3DependencyLoader.loadGlobalVariablesForR3() which unconditionally
     * registers SncLib, SncMode, SncPartnername, SncQop as empty GVs whenever a SAP adapter instance
     * (.adr3) is present — even when SNC is disabled (useSNC=0 in the connection file).
     * The plugin must inject these 4 GVs when an .adr3 file is in the SAR.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void sapSncGvarsInjectedWhenAdr3FilePresentInSar() throws Exception {
        File dir = tmp.newFolder("sap-snc-gvars");
        File adr3 = writeFile(dir, "R3AdapterConfiguration.adr3", "<adr3/>");

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(adr3, "R3AdapterConfiguration.adr3"));

        List<SubstVarParser.GlobalVariable> gvars = new ArrayList<>();
        injectSapSncGvars(sarFiles, gvars);

        Set<String> names = new LinkedHashSet<>();
        for (SubstVarParser.GlobalVariable v : gvars) names.add(v.name);

        assertTrue("SncLib must be injected when .adr3 is in SAR", names.contains("SncLib"));
        assertTrue("SncMode must be injected when .adr3 is in SAR", names.contains("SncMode"));
        assertTrue("SncPartnername must be injected when .adr3 is in SAR", names.contains("SncPartnername"));
        assertTrue("SncQop must be injected when .adr3 is in SAR", names.contains("SncQop"));

        for (SubstVarParser.GlobalVariable v : gvars) {
            assertEquals("Injected SNC GV must have empty value", "", v.value);
            assertTrue("Injected SNC GV must have requiresConfiguration=true", v.requiresConfiguration);
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void sapSncGvarsNotInjectedWhenNoAdr3InSar() throws Exception {
        File dir = tmp.newFolder("sap-snc-gvars-no-adr3");
        File xsd = writeFile(dir, "Schema.xsd", "<xsd:schema/>");

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(xsd, "Schema.xsd"));

        List<SubstVarParser.GlobalVariable> gvars = new ArrayList<>();
        injectSapSncGvars(sarFiles, gvars);

        assertTrue("No SNC GVs should be injected when no .adr3 in SAR", gvars.isEmpty());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void sapSncGvarsNotDuplicatedWhenAlreadyInSubstvar() throws Exception {
        File dir = tmp.newFolder("sap-snc-gvars-no-dup");
        File adr3 = writeFile(dir, "R3AdapterConfiguration.adr3", "<adr3/>");

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(adr3, "R3AdapterConfiguration.adr3"));

        List<SubstVarParser.GlobalVariable> gvars = new ArrayList<>();
        // Pre-populate with SncMode already defined (e.g. user put it in substvar)
        SubstVarParser.GlobalVariable existing = new SubstVarParser.GlobalVariable();
        existing.name = "SncMode";
        existing.value = "1";
        gvars.add(existing);

        injectSapSncGvars(sarFiles, gvars);

        long sncModeCount = gvars.stream().filter(v -> "SncMode".equals(v.name)).count();
        assertEquals("SncMode must not be duplicated", 1, sncModeCount);
        assertEquals("Pre-existing SncMode value must be preserved", "1",
            gvars.stream().filter(v -> "SncMode".equals(v.name)).findFirst().get().value);
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

    // -----------------------------------------------------------------------
    //  SAP R/3 adapter: auto-AAR creation for .adr3 / .adr3TID files
    // -----------------------------------------------------------------------

    private static final Method BUILD_SAP_AARS;

    static {
        try {
            BUILD_SAP_AARS = BwEarMojo.class.getDeclaredMethod(
                "buildAdapterAarsIfNeeded",
                List.class, List.class, List.class, File.class, List.class,
                com.tibco.bw.maven.plugin.descriptor.ArchiveDescriptorParser.ArchiveDescriptor.class);
            BUILD_SAP_AARS.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void buildSapAars(List allSarFiles, List<File> moduleFiles, File workDir,
                               List<SubstVarParser.GlobalVariable> gvars) throws Exception {
        try {
            BUILD_SAP_AARS.invoke(new BwEarMojo(), allSarFiles, allSarFiles, moduleFiles, workDir, gvars, null);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        }
    }

    /**
     * Regression: buildear creates one AAR per SAP R/3 adapter instance file (.adr3).
     * The AAR must be named after the instance (e.g. Publisher1.aar) and must contain
     * exactly two ZIP entries: TIBCO.xml and /Publisher1.adr3 (leading slash = BW repo path).
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void sapAarCreatedForAdr3File() throws Exception {
        File dir    = tmp.newFolder("sap-aar-adr3");
        File work   = tmp.newFolder("sap-aar-adr3-work");
        File adr3   = writeFile(dir, "Publisher1.adr3",
            aesdkXml("SAPAdapter", "SAPAdapter", "Publisher1"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(adr3, "Publisher1.adr3"));

        List<File> moduleFiles = new ArrayList<>();
        buildSapAars(sarFiles, moduleFiles, work, new ArrayList<>());

        assertEquals("Exactly one AAR must be created for one .adr3 file", 1, moduleFiles.size());
        File aar = moduleFiles.get(0);
        assertEquals("AAR name must match the instance name", "Publisher1.aar", aar.getName());
        assertTrue("AAR file must exist on disk", aar.isFile());

        // Verify AAR contents
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(aar)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zf.entries();
            Set<String> entryNames = new LinkedHashSet<>();
            while (entries.hasMoreElements()) entryNames.add(entries.nextElement().getName());
            assertTrue("AAR must contain TIBCO.xml", entryNames.contains("TIBCO.xml"));
            assertTrue("AAR must contain the adapter file with leading slash BW path",
                       entryNames.contains("/Publisher1.adr3"));
            assertEquals("AAR must contain exactly 2 entries", 2, entryNames.size());
        }
    }

    /**
     * Regression: buildear creates one AAR per SAP R/3 TID manager file (.adr3TID).
     * The AAR must be named after the instance (e.g. TIDManager.aar).
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void sapAarCreatedForAdr3TidFile() throws Exception {
        File dir   = tmp.newFolder("sap-aar-adr3tid");
        File work  = tmp.newFolder("sap-aar-adr3tid-work");
        File tid   = writeFile(dir, "TIDManager.adr3TID",
            aesdkXml("TIDManager", "TIDManager", "TIDManager"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(tid, "TIDManager.adr3TID"));

        List<File> moduleFiles = new ArrayList<>();
        buildSapAars(sarFiles, moduleFiles, work, new ArrayList<>());

        assertEquals("Exactly one AAR must be created for one .adr3TID file", 1, moduleFiles.size());
        File aar = moduleFiles.get(0);
        assertEquals("AAR name must match the TIDManager instance name", "TIDManager.aar", aar.getName());
        assertTrue("TIDManager AAR file must exist on disk", aar.isFile());

        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(aar)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zf.entries();
            Set<String> entryNames = new LinkedHashSet<>();
            while (entries.hasMoreElements()) entryNames.add(entries.nextElement().getName());
            assertTrue("AAR must contain TIBCO.xml", entryNames.contains("TIBCO.xml"));
            assertTrue("AAR must contain the .adr3TID file with leading slash",
                       entryNames.contains("/TIDManager.adr3TID"));
        }
    }

    /**
     * Regression: no AARs should be created when the project has no .adr3 or .adr3TID files.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void noSapAarsCreatedWhenNoAdr3FilesInProject() throws Exception {
        File dir  = tmp.newFolder("sap-aar-none");
        File work = tmp.newFolder("sap-aar-none-work");
        File xsd  = writeFile(dir, "Schema.xsd", "<xsd:schema/>");

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(xsd, "Schema.xsd"));

        List<File> moduleFiles = new ArrayList<>();
        buildSapAars(sarFiles, moduleFiles, work, new ArrayList<>());

        assertTrue("No AARs must be created when no .adr3/.adr3TID files are present",
                   moduleFiles.isEmpty());
    }

    /**
     * Regression: multiple .adr3 files each get their own AAR (one per instance).
     * Mirrors OutboundIDocWithRemoteTIDManager with Publisher1.adr3, Publisher2.adr3,
     * and TIDManager.adr3TID → 3 AARs.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void multipleSapAdapterFilesEachGetOwnAar() throws Exception {
        File dir  = tmp.newFolder("sap-aar-multi");
        File work = tmp.newFolder("sap-aar-multi-work");

        File pub1 = writeFile(dir, "Publisher1.adr3",
            aesdkXml("SAPAdapter", "SAPAdapter", "Publisher1"));
        File pub2 = writeFile(dir, "Publisher2.adr3",
            aesdkXml("SAPAdapter", "SAPAdapter", "Publisher2"));
        File tid  = writeFile(dir, "TIDManager.adr3TID",
            aesdkXml("TIDManager", "TIDManager", "TIDManager"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(pub1, "Publisher1.adr3"));
        sarFiles.add(bwFile(pub2, "Publisher2.adr3"));
        sarFiles.add(bwFile(tid,  "TIDManager.adr3TID"));

        List<File> moduleFiles = new ArrayList<>();
        buildSapAars(sarFiles, moduleFiles, work, new ArrayList<>());

        assertEquals("Three AARs must be created for two .adr3 + one .adr3TID", 3, moduleFiles.size());
        Set<String> aarNames = new LinkedHashSet<>();
        for (File f : moduleFiles) aarNames.add(f.getName());
        assertTrue("Publisher1.aar must be created", aarNames.contains("Publisher1.aar"));
        assertTrue("Publisher2.aar must be created", aarNames.contains("Publisher2.aar"));
        assertTrue("TIDManager.aar must be created", aarNames.contains("TIDManager.aar"));
    }

    // -----------------------------------------------------------------------
    //  Generic AESDK adapter AAR creation (adldap, adfiles, adpsft8, …)
    // -----------------------------------------------------------------------

    @Test
    public void isAdapterInstanceFileTrueWhenContainsInstanceId() throws Exception {
        File dir = tmp.newFolder("is-instance-true");
        File f = writeFile(dir, "Foo.adldap",
            aesdkXml("adldap", "ldap", "MyInstance"));
        assertTrue("File with <AESDK:instanceId> must be an instance file",
            BwEarMojo.isAdapterInstanceFile(f));
    }

    @Test
    public void isAdapterInstanceFileFalseWhenNoInstanceId() throws Exception {
        File dir = tmp.newFolder("is-instance-false");
        File f = writeFile(dir, "Conn.adr3Connections",
            "<Repository:repository xmlns:Repository=\"x\"><pool/></Repository:repository>");
        assertFalse("File without <AESDK:instanceId> must not be an instance file",
            BwEarMojo.isAdapterInstanceFile(f));
    }

    @Test
    public void readAdapterFragNameExtractsNameAttribute() throws Exception {
        File dir = tmp.newFolder("frag-name");
        File ldap = writeFile(dir, "Foo.adldap", aesdkXml("adldap", "ldap", "Foo"));
        File sap  = writeFile(dir, "Bar.adr3", aesdkXml("SAPAdapter", "SAPAdapter", "Bar"));
        assertEquals("adldap fragment name must be 'ldap'",
            "ldap", BwEarMojo.readAdapterFragName(ldap));
        assertEquals("adr3 fragment name must be 'SAPAdapter'",
            "SAPAdapter", BwEarMojo.readAdapterFragName(sap));
    }

    @Test
    public void detectAdapterVersionReturnsNonNullFourPartString() {
        // Returns an installed version or a hardcoded default — always four-part X.Y.Z.W
        for (String name : new String[]{"adr3", "adr3TID", "adldap", "adfiles", "adpsft8", "adunknown"}) {
            String v = BwEarMojo.detectAdapterVersion(name);
            assertNotNull("detectAdapterVersion must not return null for " + name, v);
            assertTrue("Version must contain at least one dot for " + name, v.contains("."));
        }
    }

    @Test
    public void readSdkPropertiesLoadsAdr3FromClasspath() {
        List<TibcoXmlGenerator.SdkProperty> props = BwEarMojo.readSdkProperties("adr3");
        assertFalse("adr3 SDK properties must not be empty", props.isEmpty());
        boolean hasMaxConn = props.stream().anyMatch(p -> "adr3.maxconnections".equals(p.option));
        assertTrue("adr3 SDK properties must include adr3.maxconnections", hasMaxConn);
    }

    @Test
    public void readSdkPropertiesReturnsEmptyForUnknownAdapter() {
        List<TibcoXmlGenerator.SdkProperty> props = BwEarMojo.readSdkProperties("adunknownxxx");
        assertTrue("Unknown adapter must return empty SDK properties", props.isEmpty());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void adldapInstanceFileCreatesAar() throws Exception {
        File dir  = tmp.newFolder("aar-adldap");
        File work = tmp.newFolder("aar-adldap-work");
        File f = writeFile(dir, "MyLdap.adldap",
            aesdkXml("adldap", "ldap", "MyLdap"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(f, "MyLdap.adldap"));

        List<File> moduleFiles = new ArrayList<>();
        buildSapAars(sarFiles, moduleFiles, work, new ArrayList<>());

        assertEquals("One AAR must be created for the .adldap instance file", 1, moduleFiles.size());
        assertEquals("AAR name must match instance name", "MyLdap.aar", moduleFiles.get(0).getName());

        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(moduleFiles.get(0))) {
            Set<String> entries = new LinkedHashSet<>();
            java.util.Enumeration<? extends java.util.zip.ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) entries.add(e.nextElement().getName());
            assertTrue("AAR must contain TIBCO.xml", entries.contains("TIBCO.xml"));
            assertTrue("AAR must contain /MyLdap.adldap", entries.contains("/MyLdap.adldap"));
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void adfilesInstanceFileCreatesAar() throws Exception {
        File dir  = tmp.newFolder("aar-adfiles");
        File work = tmp.newFolder("aar-adfiles-work");
        File f = writeFile(dir, "delimitedReader.adfiles",
            aesdkXml("fa", "FileAdapter", "delimitedReader"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(f, "delimitedReader.adfiles"));

        List<File> moduleFiles = new ArrayList<>();
        buildSapAars(sarFiles, moduleFiles, work, new ArrayList<>());

        assertEquals("One AAR for .adfiles instance", 1, moduleFiles.size());
        assertEquals("delimitedReader.aar", moduleFiles.get(0).getName());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void adr3ConnectionsFileDoesNotCreateAar() throws Exception {
        File dir  = tmp.newFolder("aar-adr3conn");
        File work = tmp.newFolder("aar-adr3conn-work");
        File f = writeFile(dir, "R3Connections.adr3Connections",
            "<Repository:repository xmlns:Repository=\"x\"><pool/></Repository:repository>");

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(f, "R3Connections.adr3Connections"));

        List<File> moduleFiles = new ArrayList<>();
        buildSapAars(sarFiles, moduleFiles, work, new ArrayList<>());

        assertTrue("adr3Connections must not create any AAR", moduleFiles.isEmpty());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void adapterAarTibcoXmlContainsSdkProperties() throws Exception {
        File dir  = tmp.newFolder("aar-nvp");
        File work = tmp.newFolder("aar-nvp-work");
        File f = writeFile(dir, "MyR3.adr3",
            aesdkXml("SAPAdapter", "SAPAdapter", "MyR3"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(f, "MyR3.adr3"));

        List<File> moduleFiles = new ArrayList<>();
        buildSapAars(sarFiles, moduleFiles, work, new ArrayList<>());

        assertEquals(1, moduleFiles.size());
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(moduleFiles.get(0))) {
            java.util.zip.ZipEntry tibco = zf.getEntry("TIBCO.xml");
            assertNotNull("TIBCO.xml must be in AAR", tibco);
            String xml = new String(
                zf.getInputStream(tibco).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue("TIBCO.xml must contain Adapter SDK Properties block",
                xml.contains("<name>Adapter SDK Properties</name>"));
            assertTrue("TIBCO.xml must contain adr3.maxconnections",
                xml.contains("adr3.maxconnections"));
        }
    }

    // -----------------------------------------------------------------------
    //  GV-referenced resources: files stored only as GV values must be in SAR
    // -----------------------------------------------------------------------

    private static final Method ADD_GV_RESOURCES;

    static {
        try {
            ADD_GV_RESOURCES = BwEarMojo.class.getDeclaredMethod(
                "addGvReferencedResources", List.class, List.class, List.class);
            ADD_GV_RESOURCES.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void addGvResources(List<SubstVarParser.GlobalVariable> gvars,
                                List sarFiles, List allSarFiles) throws Exception {
        try {
            ADD_GV_RESOURCES.invoke(new BwEarMojo(), gvars, sarFiles, allSarFiles);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        }
    }

    /**
     * Regression: a WSDL whose BW path is stored only as a GV value (e.g.
     * {@code salesforce.wsdl = /SalesforceResources/partner_27_0.wsdl}) must be added
     * to the SAR even though no process directly imports it.
     *
     * <p>Mirrors SalesforceOpportunityToSAPOrder where partner_27_0.wsdl is referenced
     * via the {@code salesforce.wsdl} GV and otherwise invisible to transitive analysis.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void gvReferencedWsdlAddedToSar() throws Exception {
        File dir = tmp.newFolder("gv-wsdl");
        File wsdlFile = writeFile(dir, "partner_27_0.wsdl",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<wsdl:definitions xmlns:wsdl=\"http://schemas.xmlsoap.org/wsdl/\""
            + " targetNamespace=\"urn:partner.soap.sforce.com\"/>\n");
        File xsdFile = writeFile(dir, "Salesforce_Metadata.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"/>\n");

        // GV: salesforce.wsdl = /SalesforceResources/partner_27_0.wsdl
        SubstVarParser.GlobalVariable gv = new SubstVarParser.GlobalVariable();
        gv.name = "salesforce.wsdl";
        gv.value = "/SalesforceResources/partner_27_0.wsdl";
        gv.type = "String";
        List<SubstVarParser.GlobalVariable> gvars = Collections.singletonList(gv);

        // allSarFiles contains both files
        List allSarFiles = new ArrayList();
        allSarFiles.add(bwFile(wsdlFile, "SalesforceResources/partner_27_0.wsdl"));
        allSarFiles.add(bwFile(xsdFile,  "SalesforceResources/Salesforce_Metadata.xsd"));

        // sarFiles already includes the XSD (referenced directly by process) but NOT the WSDL
        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(xsdFile, "SalesforceResources/Salesforce_Metadata.xsd"));

        addGvResources(gvars, sarFiles, allSarFiles);

        Set<String> names = fileNames(sarFiles);
        assertTrue("partner_27_0.wsdl must be added (GV value points to it)",
            names.contains("partner_27_0.wsdl"));
        assertTrue("Salesforce_Metadata.xsd must still be in SAR",
            names.contains("Salesforce_Metadata.xsd"));
    }

    /**
     * GV values that do NOT look like BW resource paths (no leading slash, or no
     * file extension, or no matching file in the project) must not cause errors or
     * spurious SAR additions.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void gvWithNonPathValueDoesNotAddAnything() throws Exception {
        File dir = tmp.newFolder("gv-non-path");
        File xsdFile = writeFile(dir, "Schema.xsd",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><xsd:schema"
            + " xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"/>\n");

        List<SubstVarParser.GlobalVariable> gvars = new ArrayList<>();
        // hostname GV: no leading slash
        SubstVarParser.GlobalVariable host = new SubstVarParser.GlobalVariable();
        host.name = "server.host"; host.value = "myserver.example.com"; host.type = "String";
        gvars.add(host);
        // path without extension
        SubstVarParser.GlobalVariable noExt = new SubstVarParser.GlobalVariable();
        noExt.name = "log.dir"; noExt.value = "/var/log/bw"; noExt.type = "String";
        gvars.add(noExt);
        // path that doesn't exist in project
        SubstVarParser.GlobalVariable ghost = new SubstVarParser.GlobalVariable();
        ghost.name = "missing.wsdl"; ghost.value = "/Schemas/DoesNotExist.wsdl"; ghost.type = "String";
        gvars.add(ghost);

        List allSarFiles = new ArrayList();
        allSarFiles.add(bwFile(xsdFile, "Schema.xsd"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(xsdFile, "Schema.xsd"));

        addGvResources(gvars, sarFiles, allSarFiles);

        assertEquals("Only the originally included XSD must be in SAR", 1, sarFiles.size());
    }

    // -----------------------------------------------------------------------
    //  Adapter .folder metadata: addAdapterFolderMetadata
    // -----------------------------------------------------------------------

    private static final Method ADD_ADAPTER_FOLDER_METADATA;

    static {
        try {
            ADD_ADAPTER_FOLDER_METADATA = BwEarMojo.class.getDeclaredMethod(
                "addAdapterFolderMetadata", List.class, List.class);
            ADD_ADAPTER_FOLDER_METADATA.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void addAdapterFolderMetadata(List sarFiles, List allSarFiles) throws Exception {
        try {
            ADD_ADAPTER_FOLDER_METADATA.invoke(new BwEarMojo(), sarFiles, allSarFiles);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        }
    }

    /**
     * Regression: SAP R/3 adapter schema directory ({@code AESchemas/ae/SAPAdapter40}) must
     * have its {@code .folder} file added to the SAR when the directory contains SAR resources.
     *
     * <p>Mirrors DynamicLogonExternalCommit / InboundIDocWithInboundBAPI etc. where buildear
     * includes {@code AESchemas/ae/SAPAdapter40/.folder} via
     * {@code R3AdapterInstance.getExportPartners()} → PartnerSleuth chain.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void sapAdapter40FolderAddedWhenDirectoryContainsSarResource() throws Exception {
        File dir = tmp.newFolder("sap-folder-metadata");

        // SAP palette schema present in the SAR
        File classesAe = writeFile(dir, "classes.aeschema",
            "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">"
            + "<class name=\"RFCClient\"/></Repository:repository>");

        // .folder file for the SAPAdapter40 directory
        File folderFile = writeFile(dir, ".folder",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<repository:repository xmlns:repository=\"http://www.tibco.com/xmlns/repo/types/2002\""
            + " xmlns:Designer=\"http://www.tibco.com/xmlns/ae/types/2002/designer\">\n"
            + "  <Designer:folder><Designer:name>SAPAdapter40</Designer:name></Designer:folder>\n"
            + "</repository:repository>");

        // allSarFiles has both
        List allSarFiles = new ArrayList();
        allSarFiles.add(bwFile(classesAe, "AESchemas/ae/SAPAdapter40/classes.aeschema"));
        allSarFiles.add(bwFile(folderFile, "AESchemas/ae/SAPAdapter40/.folder"));

        // sarFiles already has the aeschema but NOT the .folder (the bug scenario)
        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(classesAe, "AESchemas/ae/SAPAdapter40/classes.aeschema"));

        addAdapterFolderMetadata(sarFiles, allSarFiles);

        Set<String> names = fileRelPaths(sarFiles);
        assertTrue("AESchemas/ae/SAPAdapter40/.folder must be added to SAR for SAP adapter",
            names.contains("AESchemas/ae/SAPAdapter40/.folder"));
    }

    /**
     * Regression: LDAP adapter schema directory ({@code AESchemas/ae/adapter/ldap/...}) must
     * have its {@code .folder} file added to the SAR.
     *
     * <p>Mirrors adldapsample where buildear includes the {@code .folder} for each
     * {@code AESchemas/ae/adapter/ldap/<cfg>/<session>} directory via
     * {@code LDAPAdapterInstance.getExportPartners()} → PartnerSleuth chain.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void ldapAdapterFolderAddedForAeAdapterSubtree() throws Exception {
        File dir = tmp.newFolder("ldap-folder-metadata");

        // LDAP session schema file
        File ldapSchema = writeFile(dir, "Classes.aeschema",
            "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">"
            + "<class name=\"LDAPRecord\"/></Repository:repository>");

        // .folder for the LDAP session directory
        File folderFile = writeFile(dir, ".folder",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<repository:repository xmlns:repository=\"http://www.tibco.com/xmlns/repo/types/2002\""
            + " xmlns:Designer=\"http://www.tibco.com/xmlns/ae/types/2002/designer\">\n"
            + "  <Designer:folder><Designer:name>Session1</Designer:name></Designer:folder>\n"
            + "</repository:repository>");

        String schemaRel = "AESchemas/ae/adapter/ldap/LDAPCfg/Session1/Classes.aeschema";
        String folderRel = "AESchemas/ae/adapter/ldap/LDAPCfg/Session1/.folder";

        List allSarFiles = new ArrayList();
        allSarFiles.add(bwFile(ldapSchema, schemaRel));
        allSarFiles.add(bwFile(folderFile,  folderRel));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(ldapSchema, schemaRel));

        addAdapterFolderMetadata(sarFiles, allSarFiles);

        Set<String> paths = fileRelPaths(sarFiles);
        assertTrue("LDAP session .folder must be added to SAR",
            paths.contains(folderRel));
    }

    /**
     * Regression: ADB adapter schema directory ({@code AESchemas/ae/ADB}) must NOT have its
     * {@code .folder} added, because {@code ADBAdapterConfiguration.getExportPartners()} never
     * adds the {@code AESchemas/ae/ADB} folder to the export-partner list.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void adbAdapterFolderNotAddedBecauseAdbIsNotInKnownAdapterSet() throws Exception {
        File dir = tmp.newFolder("adb-no-folder-metadata");

        // ADB palette schema
        File adbMeta = writeFile(dir, "adbmetadata.aeschema",
            "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">"
            + "<class name=\"SQL_REQUEST\"/></Repository:repository>");

        // .folder for the ADB directory (present in allSarFiles but must NOT be added)
        File folderFile = writeFile(dir, ".folder",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<repository:repository xmlns:repository=\"http://www.tibco.com/xmlns/repo/types/2002\""
            + " xmlns:Designer=\"http://www.tibco.com/xmlns/ae/types/2002/designer\">\n"
            + "  <Designer:folder><Designer:name>ADB</Designer:name></Designer:folder>\n"
            + "</repository:repository>");

        String schemaRel = "AESchemas/ae/ADB/adbmetadata.aeschema";
        String folderRel = "AESchemas/ae/ADB/.folder";

        List allSarFiles = new ArrayList();
        allSarFiles.add(bwFile(adbMeta,   schemaRel));
        allSarFiles.add(bwFile(folderFile, folderRel));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(adbMeta, schemaRel));

        addAdapterFolderMetadata(sarFiles, allSarFiles);

        Set<String> paths = fileRelPaths(sarFiles);
        assertFalse("AESchemas/ae/ADB/.folder must NOT be added (ADB is not in known adapter set)",
            paths.contains(folderRel));
    }

    /**
     * Regression: {@code .folder} files already present in {@code sarFiles} before the call
     * must not be duplicated.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void alreadyPresentFolderFileIsNotDuplicated() throws Exception {
        File dir = tmp.newFolder("sap-folder-no-dup");

        File classesAe = writeFile(dir, "classes.aeschema", "<ae/>");
        File folderFile = writeFile(dir, ".folder", "<folder/>");

        List allSarFiles = new ArrayList();
        allSarFiles.add(bwFile(classesAe, "AESchemas/ae/SAPAdapter40/classes.aeschema"));
        allSarFiles.add(bwFile(folderFile, "AESchemas/ae/SAPAdapter40/.folder"));

        // .folder is ALREADY in sarFiles
        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(classesAe, "AESchemas/ae/SAPAdapter40/classes.aeschema"));
        sarFiles.add(bwFile(folderFile, "AESchemas/ae/SAPAdapter40/.folder"));

        int sizeBefore = sarFiles.size();
        addAdapterFolderMetadata(sarFiles, allSarFiles);

        assertEquals("sarFiles size must not grow when .folder already present", sizeBefore, sarFiles.size());
    }

    @SuppressWarnings("rawtypes")
    private Set<String> fileRelPaths(List bwFiles) throws Exception {
        Set<String> paths = new LinkedHashSet<>();
        for (Object bwf : bwFiles) {
            String rel = (String) bwf.getClass().getDeclaredField("relativePath").get(bwf);
            paths.add(rel);
        }
        return paths;
    }

    // -----------------------------------------------------------------------
    //  Directory expansion must not pull in EXCLUDED_EXTENSIONS files (.folder)
    // -----------------------------------------------------------------------

    /**
     * Regression: {@code pd:targetNamespace} values like {@code /EventHandler/Procesos} look
     * like directory references and cause {@code expandDirectoryRefs} to expand the entire
     * folder. Metadata files with excluded extensions (e.g. {@code .folder}) must be skipped
     * during that expansion — only valid SAR resources are allowed in.
     *
     * <p>Mirrors OPL_EventHandler where buildear omits
     * {@code EventHandler/Procesos/.folder} but the plugin was incorrectly including it.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void folderFileNotIncludedViaDirectoryExpansionOfTargetNamespace() throws Exception {
        File dir = tmp.newFolder("dir-expand-no-folder");

        // Process with a pd:targetNamespace that looks like a directory ref
        File proc = writeFile(dir, "Inicializacion EventHandler.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/EventHandler/Procesos/Inicializacion EventHandler</pd:name>\n"
            + "  <pd:targetNamespace>/EventHandler/Procesos</pd:targetNamespace>\n"
            + "  <pd:activity name=\"Call\">\n"
            + "    <pd:call>/EventHandler/Procesos/CargaVariableBD.process</pd:call>\n"
            + "  </pd:activity>\n"
            + "</pd:ProcessDefinition>");

        // Sub-process referenced by the main process
        File subProc = writeFile(dir, "CargaVariableBD.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/EventHandler/Procesos/CargaVariableBD</pd:name>\n"
            + "  <pd:targetNamespace>/EventHandler/Procesos</pd:targetNamespace>\n"
            + "</pd:ProcessDefinition>");

        // .folder file that must NOT end up in the SAR
        File folderFile = writeFile(dir, ".folder",
            "<?xml version=\"1.0\"?><Designer:root xmlns:Designer=\"http://www.tibco.com/xmlns/designer/1.0.0\">"
            + "<Designer:folder><Designer:name>Procesos</Designer:name></Designer:folder></Designer:root>");

        // A legitimate SAR resource under the same directory
        File xsdFile = writeFile(dir, "Schema.xsd", "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(proc,    "EventHandler/Procesos/Inicializacion EventHandler.process"));
        parFiles.add(bwFile(subProc, "EventHandler/Procesos/CargaVariableBD.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(folderFile, "EventHandler/Procesos/.folder"));
        sarFiles.add(bwFile(xsdFile,    "EventHandler/Procesos/Schema.xsd"));

        List<String> entryPoints = Collections.singletonList(
            "/EventHandler/Procesos/Inicializacion EventHandler.process");

        applyTransitive(parFiles, sarFiles, entryPoints, Collections.emptyList(), true);

        Set<String> paths = fileRelPaths(sarFiles);
        assertFalse("EventHandler/Procesos/.folder must NOT be included via directory expansion",
            paths.contains("EventHandler/Procesos/.folder"));
        assertTrue("EventHandler/Procesos/Schema.xsd must be included (valid SAR resource under referenced dir)",
            paths.contains("EventHandler/Procesos/Schema.xsd"));
    }

    /**
     * Regression: {@code buildSapAdapterAarsIfNeeded} was using {@code allSarFiles} (the full
     * unfiltered pool) which caused a spurious AAR to be created for any {@code .adr3} file
     * present on disk even if no process references it.
     *
     * <p>Mirrors SonarSamples where {@code R3AdapterConfiguration.adr3} exists on disk but
     * is not referenced by any process, so buildear produces no AAR for it. The fix passes
     * {@code combinedSarFiles} (post-BFS) to {@code buildSapAdapterAarsIfNeeded} so that only
     * adapter instances reachable from deployed processes receive an AAR.</p>
     *
     * <p>This test verifies that the BFS itself correctly excludes an unreferenced {@code .adr3}
     * from {@code sarFiles}, which is the prerequisite for the fix (the caller passes those
     * filtered sarFiles to {@code buildSapAdapterAarsIfNeeded}).</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void unreferencedAdr3ExcludedFromSarByBfs() throws Exception {
        File dir = tmp.newFolder("unreferenced-adr3");

        // A process that does NOT reference any SAP adapter
        File proc = writeFile(dir, "SonarProcess.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/SonarSamples/SonarProcess</pd:name>\n"
            + "  <pd:targetNamespace>/SonarSamples</pd:targetNamespace>\n"
            + "</pd:ProcessDefinition>");

        // .adr3 present on disk but unreferenced
        File adr3 = writeFile(dir, "R3AdapterConfiguration.adr3", "<adr3/>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(proc, "SonarSamples/SonarProcess.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(adr3, "R3AdapterConfiguration.adr3"));

        List<String> entryPoints = Collections.singletonList("/SonarSamples/SonarProcess.process");

        applyTransitive(parFiles, sarFiles, entryPoints, Collections.emptyList(), true);

        Set<String> paths = fileRelPaths(sarFiles);
        assertFalse("R3AdapterConfiguration.adr3 must NOT be in SAR when unreferenced by any process",
            paths.contains("R3AdapterConfiguration.adr3"));
    }

    // -----------------------------------------------------------------------
    //  Bug #3: adapter-only projects (no .process files) — AESchemas into SAR
    // -----------------------------------------------------------------------

    private static final Method COLLECT_ADAPTER_AESCHEMAS;

    static {
        try {
            COLLECT_ADAPTER_AESCHEMAS = BwEarMojo.class.getDeclaredMethod(
                "collectAdapterAeschemas", List.class, List.class, Set.class);
            COLLECT_ADAPTER_AESCHEMAS.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void collectAdapterAeschemas(List allSarFiles,
            List combinedSarFiles, Set<String> seenSarPaths) throws Exception {
        try {
            COLLECT_ADAPTER_AESCHEMAS.invoke(new BwEarMojo(),
                allSarFiles, combinedSarFiles, seenSarPaths);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        }
    }

    /**
     * Regression (Bug #3): adapter-only projects (no .process files) — AESchemas referenced
     * by adapter instance files must be collected into the SAR.
     *
     * <p>Before the fix, {@code applyTransitiveDependencyAnalysis} seeded from processes
     * (empty for adapter-only projects) and left the SAR empty.  The AESchema scanning
     * block only existed in the multi-archive path, so single-PAR adapter-only projects
     * (like adfiles/BaseRecord) always produced an empty SAR.</p>
     *
     * <p>This test verifies that {@code collectAdapterAeschemas} discovers the AESchema
     * files referenced via {@code AESDK:loadUrl} in a {@code .adfiles} instance file and
     * follows the import chain transitively.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void adapterOnlyProjectAeschemasCollectedIntoSar() throws Exception {
        File dir = tmp.newFolder("adfiles-aeschema-sar");

        // Root AESchema
        File aeSchemaDir = new File(dir, "AESchemas");
        aeSchemaDir.mkdirs();
        File aeRoot = writeFile(aeSchemaDir, "ae.aeschema",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Repository:repository"
            + " xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">\n"
            + "  <class name=\"string\"/>\n"
            + "</Repository:repository>");

        // Operation AESchema — lives in a sub-directory
        File faDir = new File(dir, "AESchemas/ae/FileAdapter");
        faDir.mkdirs();
        File opAeschema = writeFile(faDir, "operation.aeschema",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Repository:repository"
            + " xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">\n"
            + "  <class name=\"ContainerRecord\">\n"
            + "    <attributeType isRef=\"true\">"
            + "AESchemas/ae.aeschema#scalar.string"
            + "</attributeType>\n"
            + "  </class>\n"
            + "</Repository:repository>");

        // .adfiles adapter instance file — has AESDK:instanceId and AESDK:loadUrl refs
        File adfiles = writeFile(dir, "ContainerReader.adfiles",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Repository:repository"
            + " xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\"\n"
            + " xmlns:AESDK=\"http://www.tibco.com/xmlns/aemeta/adapter/2002\"\n"
            + " xmlns:FileAdapter=\"http://www.tibco.com/xmlns/adapter/FileAdapter/2002\">\n"
            + "  <FileAdapter:adapter name=\"FileAdapter\">\n"
            + "    <AESDK:instanceId>ContainerReader</AESDK:instanceId>\n"
            + "    <AESDK:loadUrl isRef=\"true\">/AESchemas/ae.aeschema</AESDK:loadUrl>\n"
            + "    <AESDK:loadUrl isRef=\"true\">/AESchemas/ae/FileAdapter/operation.aeschema</AESDK:loadUrl>\n"
            + "  </FileAdapter:adapter>\n"
            + "</Repository:repository>");

        List allSarFiles = new ArrayList();
        allSarFiles.add(bwFile(adfiles,    "ContainerReader.adfiles"));
        allSarFiles.add(bwFile(aeRoot,     "AESchemas/ae.aeschema"));
        allSarFiles.add(bwFile(opAeschema, "AESchemas/ae/FileAdapter/operation.aeschema"));

        List combinedSarFiles = new ArrayList();
        Set<String> seenSarPaths = new LinkedHashSet<>();

        collectAdapterAeschemas(allSarFiles, combinedSarFiles, seenSarPaths);

        Set<String> sarPaths = fileRelPaths(combinedSarFiles);
        assertTrue("ae.aeschema must be collected from .adfiles loadUrl reference",
            sarPaths.contains("AESchemas/ae.aeschema"));
        assertTrue("operation.aeschema must be collected from .adfiles loadUrl reference",
            sarPaths.contains("AESchemas/ae/FileAdapter/operation.aeschema"));
        assertFalse(".adfiles itself must NOT be added to the SAR (only its AESchema refs)",
            sarPaths.contains("ContainerReader.adfiles"));
    }

    /**
     * Regression (Bug #3): when multiple adapter instance files share some AESchemas,
     * {@code collectAdapterAeschemas} must deduplicate entries in {@code combinedSarFiles}.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void adapterOnlyProjectDeduplicatesSharedAeschemas() throws Exception {
        File dir = tmp.newFolder("adfiles-dedup");

        // Shared root AESchema
        new File(dir, "AESchemas").mkdirs();
        File aeRoot = writeFile(new File(dir, "AESchemas"), "ae.aeschema",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Repository:repository"
            + " xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">\n"
            + "  <class name=\"string\"/>\n"
            + "</Repository:repository>");

        // Two .adfiles instance files that both reference the same ae.aeschema
        String instanceTemplate =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Repository:repository"
            + " xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\"\n"
            + " xmlns:AESDK=\"http://www.tibco.com/xmlns/aemeta/adapter/2002\"\n"
            + " xmlns:FileAdapter=\"http://www.tibco.com/xmlns/adapter/FileAdapter/2002\">\n"
            + "  <FileAdapter:adapter name=\"FileAdapter\">\n"
            + "    <AESDK:instanceId>%s</AESDK:instanceId>\n"
            + "    <AESDK:loadUrl isRef=\"true\">/AESchemas/ae.aeschema</AESDK:loadUrl>\n"
            + "  </FileAdapter:adapter>\n"
            + "</Repository:repository>";

        File reader = writeFile(dir, "ContainerReader.adfiles",
            String.format(instanceTemplate, "ContainerReader"));
        File writer = writeFile(dir, "ContainerWriter.adfiles",
            String.format(instanceTemplate, "ContainerWriter"));

        List allSarFiles = new ArrayList();
        allSarFiles.add(bwFile(reader,  "ContainerReader.adfiles"));
        allSarFiles.add(bwFile(writer,  "ContainerWriter.adfiles"));
        allSarFiles.add(bwFile(aeRoot,  "AESchemas/ae.aeschema"));

        List combinedSarFiles = new ArrayList();
        Set<String> seenSarPaths = new LinkedHashSet<>();

        collectAdapterAeschemas(allSarFiles, combinedSarFiles, seenSarPaths);

        Set<String> sarPaths = fileRelPaths(combinedSarFiles);
        assertTrue("ae.aeschema must be in SAR (referenced by both adapter files)",
            sarPaths.contains("AESchemas/ae.aeschema"));

        long aeCount = combinedSarFiles.stream()
            .filter(bwf -> {
                try {
                    java.lang.reflect.Field rel = bwf.getClass().getDeclaredField("relativePath");
                    rel.setAccessible(true);
                    return "AESchemas/ae.aeschema".equals(rel.get(bwf));
                } catch (Exception e2) { return false; }
            }).count();
        assertEquals("ae.aeschema must appear exactly once in combinedSarFiles (deduped)", 1, aeCount);
    }

    // -----------------------------------------------------------------------
    //  Bug #3b: ae/baseDocument.aeschema must not be excluded from collectFiles
    // -----------------------------------------------------------------------

    /**
     * Regression: {@code AESchemas/ae/baseDocument.aeschema} was incorrectly listed in
     * {@code PLATFORM_AESCHEMA_RELATIVE_PATHS} and therefore skipped by {@code collectFiles}.
     * As a result it never reached {@code allSarFiles} and could not be collected into the SAR
     * even though the adfiles adapter instance files reference it directly via
     * {@code AESDK:loadUrl}, and buildear does package it for adfiles projects.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void baseDocumentAeschemaIsNotExcludedByCollectFiles() throws Exception {
        File rootDir = tmp.newFolder("base-doc-collect");
        File aeSchemaDir = new File(rootDir, "AESchemas/ae");
        aeSchemaDir.mkdirs();

        // corba.aeschema is a true platform file — must be excluded
        writeFile(new File(rootDir, "AESchemas"), "corba.aeschema",
            "<?xml version=\"1.0\"?><ns0:repository"
            + " xmlns:ns0=\"http://www.tibco.com/xmlns/repo/types/2002\"/>");

        // ae/baseDocument.aeschema must NOT be excluded (adapter-specific schema)
        writeFile(aeSchemaDir, "baseDocument.aeschema",
            "<?xml version=\"1.0\"?><ns0:repository"
            + " xmlns:ns0=\"http://www.tibco.com/xmlns/repo/types/2002\"/>");

        List parFiles = new ArrayList();
        List sarFiles = new ArrayList();
        List metadata = new ArrayList();
        COLLECT_FILES.invoke(new BwEarMojo(), rootDir, rootDir, parFiles, sarFiles, metadata);

        Set<String> sarNames = fileRelPaths(sarFiles);
        assertTrue("ae/baseDocument.aeschema must be collected into allSarFiles",
            sarNames.contains("AESchemas/ae/baseDocument.aeschema"));
        assertFalse("corba.aeschema is a platform file and must be excluded from allSarFiles",
            sarNames.contains("AESchemas/corba.aeschema"));
    }

    // -----------------------------------------------------------------------
    //  Bug #4: adapter projects with mixed processes — only starters go in PAR
    // -----------------------------------------------------------------------

    private static final Method COLLECT_STARTER_PATHS;

    static {
        try {
            COLLECT_STARTER_PATHS = BwEarMojo.class.getDeclaredMethod(
                "collectStarterPaths", List.class);
            COLLECT_STARTER_PATHS.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<String> collectStarterPaths(List parFiles) throws Exception {
        try {
            return (List<String>) COLLECT_STARTER_PATHS.invoke(new BwEarMojo(), parFiles);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        }
    }

    private static final String PROCESS_XML_NS =
        "xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\"";

    private String starterProcessXml(String processName) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<pd:ProcessDefinition " + PROCESS_XML_NS + ">"
            + "<pd:name>" + processName + "</pd:name>"
            + "<pd:startName>Adapter Subscriber</pd:startName>"
            + "<pd:starter name=\"Adapter Subscriber\">"
            + "<pd:type>com.tibco.plugin.ae.AESubscriberActivity</pd:type>"
            + "</pd:starter>"
            + "</pd:ProcessDefinition>";
    }

    private String nonStarterProcessXml(String processName) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<pd:ProcessDefinition " + PROCESS_XML_NS + ">"
            + "<pd:name>" + processName + "</pd:name>"
            + "</pd:ProcessDefinition>";
    }

    /**
     * Regression (Bug #4): {@code collectStarterPaths} must return only the paths of
     * processes that contain a {@code <pd:starter>} element.
     * Non-starter processes (e.g. RPC request-reply sub-services in adldap projects)
     * must not appear in the returned list.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void collectStarterPathsReturnsOnlyStarterProcesses() throws Exception {
        File dir = tmp.newFolder("starter-paths");
        File procDir = new File(dir, "Processes");
        procDir.mkdirs();

        File starterFile = writeFile(procDir, "ReceivePub.process",
            starterProcessXml("Processes/ReceivePub.process"));
        File nonStarterFile = writeFile(procDir, "Operations.process",
            nonStarterProcessXml("Processes/Operations.process"));

        List parFiles = new ArrayList();
        parFiles.add(bwFile(starterFile, "Processes/ReceivePub.process"));
        parFiles.add(bwFile(nonStarterFile, "Processes/Operations.process"));

        List<String> starterPaths = collectStarterPaths(parFiles);

        assertEquals("Only one starter process expected", 1, starterPaths.size());
        assertTrue("Starter path must contain ReceivePub.process",
            starterPaths.get(0).contains("ReceivePub.process"));
        for (String p : starterPaths) {
            assertFalse("Operations.process must not be in starter paths",
                p.contains("Operations.process"));
        }
    }

    /**
     * Regression (Bug #4): when no archive descriptor is present and adapter instance files
     * exist, only starter processes end up in the PAR. Non-starter processes that are not
     * transitively called from any starter are excluded — matching buildear behaviour for
     * adldap/adfiles adapter projects.
     *
     * <p>This tests the path used by {@code collectStarterPaths} + {@code applyTransitive}
     * with {@code filterParByReachability=true} and starter paths as entry points.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void adapterProjectNonStarterProcessesExcludedFromPar() throws Exception {
        File dir = tmp.newFolder("adapter-starter-filter");
        File procDir = new File(dir, "Processes");
        procDir.mkdirs();

        File starterFile = writeFile(procDir, "ReceivePub.process",
            starterProcessXml("Processes/ReceivePub.process"));
        File nonStarterFile1 = writeFile(procDir, "Operations.process",
            nonStarterProcessXml("Processes/Operations.process"));
        File nonStarterFile2 = writeFile(procDir, "Search.process",
            nonStarterProcessXml("Processes/Search.process"));

        List parFiles = new ArrayList();
        parFiles.add(bwFile(starterFile, "Processes/ReceivePub.process"));
        parFiles.add(bwFile(nonStarterFile1, "Processes/Operations.process"));
        parFiles.add(bwFile(nonStarterFile2, "Processes/Search.process"));

        List sarFiles = new ArrayList();

        List<String> starterPaths = collectStarterPaths(parFiles);
        applyTransitive(parFiles, sarFiles, starterPaths, Collections.emptyList(), true);

        Set<String> parNames = fileRelPaths(parFiles);
        assertTrue("ReceivePub.process (starter) must be in PAR",
            parNames.contains("Processes/ReceivePub.process"));
        assertFalse("Operations.process (non-starter) must be excluded from PAR",
            parNames.contains("Processes/Operations.process"));
        assertFalse("Search.process (non-starter) must be excluded from PAR",
            parNames.contains("Processes/Search.process"));
    }

    /**
     * Regression (Bug #4): when NO process has a starter element, {@code collectStarterPaths}
     * returns an empty list. The caller then falls back to including all processes, avoiding
     * an accidental empty PAR.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void collectStarterPathsReturnsEmptyWhenNoStarters() throws Exception {
        File dir = tmp.newFolder("no-starters");
        File procDir = new File(dir, "Processes");
        procDir.mkdirs();

        File f1 = writeFile(procDir, "LibA.process",
            nonStarterProcessXml("Processes/LibA.process"));
        File f2 = writeFile(procDir, "LibB.process",
            nonStarterProcessXml("Processes/LibB.process"));

        List parFiles = new ArrayList();
        parFiles.add(bwFile(f1, "Processes/LibA.process"));
        parFiles.add(bwFile(f2, "Processes/LibB.process"));

        List<String> starterPaths = collectStarterPaths(parFiles);

        assertTrue("No starters — result must be empty", starterPaths.isEmpty());
    }

    // -----------------------------------------------------------------------
    //  Fix A + B: adapterSupportsAar whitelist — all known AESDK adapters
    // -----------------------------------------------------------------------

    /**
     * Regression (Fix A/B): {@code adapterSupportsAar} must return {@code true} for every known
     * TIBCO AESDK adapter extension, including those newly added: adas400, adjdexe, adpsft8,
     * adsbl, adtuxedo.  This ensures AARs are generated for all AESDK adapter types.
     */
    @Test
    public void adapterSupportsAarReturnsTrueForAllKnownAesdkAdapters() {
        String[] known = {
            "adb",      // TIBCO Adapter for JDBC
            "adas400",  // TIBCO Adapter for IBM i / AS400
            "adfiles",  // TIBCO Adapter for Files
            "adjdexe",  // TIBCO Adapter for JD Edwards EnterpriseOne
            "adldap",   // TIBCO Adapter for LDAP
            "adpsft8",  // TIBCO Adapter for PeopleSoft 8
            "adr3",     // TIBCO Adapter for SAP R/3
            "adr3tid",  // SAP R/3 TID extension
            "adsbl",    // TIBCO Adapter for Siebel
            "adtuxedo"  // TIBCO Adapter for Tuxedo
        };
        for (String ext : known) {
            assertTrue("adapterSupportsAar must return true for ." + ext,
                BwEarMojo.adapterSupportsAar(ext));
            assertTrue("adapterSupportsAar must be case-insensitive for ." + ext,
                BwEarMojo.adapterSupportsAar(ext.toUpperCase(Locale.ROOT)));
        }
    }

    /**
     * Regression (Fix A/B): non-AESDK adapter types (adswift uses .swf, not an AESDK adapter;
     * adsmartmapper is a BW plugin) and unknown extensions must return {@code false}.
     */
    @Test
    public void adapterSupportsAarReturnsFalseForNonAesdkAndUnknownExtensions() {
        assertFalse("swf (adswift) is not an AESDK adapter — must return false",
            BwEarMojo.adapterSupportsAar("swf"));
        assertFalse("adadb was a wrong entry — correct extension is adb",
            BwEarMojo.adapterSupportsAar("adadb"));
        assertFalse("unknown extension must return false",
            BwEarMojo.adapterSupportsAar("adunknown"));
        assertFalse("empty string must return false",
            BwEarMojo.adapterSupportsAar(""));
    }

    // -----------------------------------------------------------------------
    //  Fix C: empty starter list → empty seeds → BFS produces empty PAR + SAR
    // -----------------------------------------------------------------------

    /**
     * Regression (Fix C): when an adapter project has no starter processes,
     * {@code applyTransitiveDependencyAnalysis} called with empty entry points and
     * {@code filterParByReachability=true} must leave both {@code parFiles} and
     * {@code sarFiles} empty.  This triggers {@code collectAdapterAeschemas} to correctly
     * populate the SAR from the adapter instance files' {@code AESDK:loadUrl} references,
     * without including non-process files in the PAR.
     *
     * <p>Before Fix C the no-starters fallback used {@code filterParByReachability=false},
     * which caused all non-starter processes (e.g. ManualSchema test processes in adfiles
     * projects) to be kept in the PAR.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void fixCEmptySeedsWithFilterTrueProducesEmptyParAndSar() throws Exception {
        File dir = tmp.newFolder("fix-c-empty-seeds");
        File procFile = writeFile(dir, "TestHelper.process",
            nonStarterProcessXml("/TestHelper"));
        File aeschema = writeFile(dir, "schema.aeschema", "<repository/>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(procFile, "TestHelper.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(aeschema, "schema.aeschema"));

        applyTransitive(parFiles, sarFiles, Collections.emptyList(), Collections.emptyList(), true);

        assertTrue("PAR must be empty when seeds are empty and filterParByReachability=true",
            parFiles.isEmpty());
        assertTrue("SAR must be empty when seeds are empty and filterParByReachability=true",
            sarFiles.isEmpty());
    }

    // -----------------------------------------------------------------------
    //  Bug A: AAR Adapter SDK Properties resource resolution
    // -----------------------------------------------------------------------

    /**
     * Regression (Bug A): the JDBC adapter's instance files use the {@code .adb} extension,
     * so the component software name is {@code adb} — but its bundled deployment resource is
     * named {@code adadb.xml}. {@code deploymentResourceBase} must translate {@code adb}
     * to {@code adadb} while leaving every other adapter name unchanged.
     */
    @Test
    public void deploymentResourceBaseMapsAdbToAdadb() {
        assertEquals("adb must map to the adadb deployment resource",
            "adadb", BwEarMojo.deploymentResourceBase("adb"));
        assertEquals("adr3 must be used verbatim",
            "adr3", BwEarMojo.deploymentResourceBase("adr3"));
        assertEquals("adldap must be used verbatim",
            "adldap", BwEarMojo.deploymentResourceBase("adldap"));
    }

    /**
     * Regression (Bug A): {@code readSdkProperties("adb")} must resolve to {@code adadb.xml}
     * and return the adb.* runtime tuning options. Before the fix it looked for a
     * non-existent {@code adb.xml} and returned an empty list, stripping the AAR's
     * Adapter SDK Properties block.
     */
    @Test
    public void readSdkPropertiesResolvesAdbToAdadbResource() {
        List<TibcoXmlGenerator.SdkProperty> adb = BwEarMojo.readSdkProperties("adb");
        assertFalse("adb SDK properties must be loaded from adadb.xml", adb.isEmpty());
        assertTrue("adb SDK properties must include adb.* options",
            adb.stream().anyMatch(p -> p.option != null && p.option.startsWith("adb.")));

        List<TibcoXmlGenerator.SdkProperty> adr3 = BwEarMojo.readSdkProperties("adr3");
        assertFalse("adr3 SDK properties must be loaded", adr3.isEmpty());
        assertTrue("adr3 SDK properties must include adr3.* options",
            adr3.stream().anyMatch(p -> p.option != null && p.option.startsWith("adr3.")));
    }

    /**
     * Regression (Bug A-bis): {@code readSdkProperties} must NOT trim label/description text —
     * buildear preserves significant whitespace. ADB.xml's {@code adb.useBetweenClause} label
     * ends with a trailing space; trimming it produced a single space where buildear emits two.
     */
    @Test
    public void readSdkPropertiesPreservesSignificantWhitespace() {
        TibcoXmlGenerator.SdkProperty p = BwEarMojo.readSdkProperties("adb").stream()
            .filter(x -> "adb.useBetweenClause".equals(x.option))
            .findFirst().orElse(null);
        assertNotNull("adb.useBetweenClause must be present in ADB deployment resource", p);
        assertTrue("trailing space in the label must be preserved (not trimmed): " + "[" + p.label + "]",
            p.label.endsWith(" "));
    }

    // -----------------------------------------------------------------------
    //  Bug D: JDBC checkpoint repositories derived from SAR (including projlibs)
    // -----------------------------------------------------------------------

    /**
     * Regression (Bug D): checkpoint {@code availableSharedResourceName} entries must be
     * derived from the SAR resource list (extension stripped), so JDBC connections that
     * live inside {@code .projlib} dependencies — already collected into the SAR under
     * their BW repository path — are included. A filesystem scan of the project source
     * missed them, dropping every projlib JDBC connection from the checkpoint list.
     */
    @Test
    public void jdbcCheckpointPathsDerivedFromSarIncludingProjlibs() {
        List<String> sarPaths = Arrays.asList(
            "/Resources/JDBC_For_UnitTests/JDBC-AMS-write.sharedjdbc",
            "/Common/_Lib/LibSV_Ramses/Resources/Connection/JDBC/JDBC-AMS-read.sharedjdbc",
            "/IntegrationServices/SharedUtilities/Schemas/Foo.xsd",
            "/IntegrationServices/SharedUtilities/Connections/JMS/Bar.sharedjmscon");

        List<String> checkpoints = BwEarMojo.jdbcCheckpointPaths(sarPaths);

        assertTrue("project JDBC connection must be a checkpoint repository",
            checkpoints.contains("/Resources/JDBC_For_UnitTests/JDBC-AMS-write"));
        assertTrue("projlib JDBC connection must be a checkpoint repository",
            checkpoints.contains("/Common/_Lib/LibSV_Ramses/Resources/Connection/JDBC/JDBC-AMS-read"));
        assertFalse("non-JDBC resources must not appear as checkpoint repositories",
            checkpoints.stream().anyMatch(p -> p.contains("Foo") || p.contains("Bar")));
        assertEquals("only the two .sharedjdbc resources become checkpoints",
            2, checkpoints.size());
    }

    /**
     * Regression (Bug #5): the BW engine SDK properties come from the bundled
     * com/tibco/deployment/bwengine.xml (the same file buildear reads), not a hardcoded
     * list. It must load the standard engine properties, must NOT include
     * java.extended.properties (buildear never emits it), and must ignore the
     * {@code <property>} template documented inside an XML comment.
     */
    @Test
    public void readSdkPropertiesLoadsBwengineWithoutJavaExtended() {
        List<TibcoXmlGenerator.SdkProperty> engine = BwEarMojo.readSdkProperties("bwengine");
        assertFalse("bwengine.xml properties must load", engine.isEmpty());

        java.util.Set<String> opts = new java.util.HashSet<>();
        for (TibcoXmlGenerator.SdkProperty p : engine) opts.add(p.option);

        assertTrue("must include Trace.Task.*", opts.contains("Trace.Task.*"));
        assertTrue("must include bw.log4j.configuration", opts.contains("bw.log4j.configuration"));
        assertFalse("must NOT include java.extended.properties (buildear never emits it)",
            opts.contains("java.extended.properties"));
        assertFalse("must ignore the commented-out <property> template",
            opts.contains("property.name.written.into.the.tra.file"));
    }

    /**
     * Regression (Bug #8): the adapter AAR EXTERNAL_RESOURCE_DEPENDENCY entries must be
     * ordered like buildear (java.util.HashSet iteration order from addExternalResourceBom),
     * NOT alphabetically sorted with the adapter fragment appended last. This asserts the
     * exact order buildear produces for the adbsample ADB adapter.
     */
    @Test
    public void externalDepsUseBuildearHashSetOrder() {
        List<String> schemas = Arrays.asList(
            "/AESchemas/ae/ADB/ActiveDatabaseAdapterConfiguration.aeschema",
            "/AESchemas/ae/ADB/adbmetadata.aeschema",
            "/AESchemas/ae/ADB/scalar.aeschema",
            "/AESchemas/ae.aeschema");
        String frag = "/ActiveDatabaseAdapterConfiguration.adb"
            + "#adapter.ActiveDatabaseAdapterConfiguration";

        List<String> out = BwEarMojo.externalDepsInBuildearOrder(schemas, frag);

        assertEquals(Arrays.asList(
            "/AESchemas/ae.aeschema",
            "/AESchemas/ae/ADB/ActiveDatabaseAdapterConfiguration.aeschema",
            "/AESchemas/ae/ADB/adbmetadata.aeschema",
            "/ActiveDatabaseAdapterConfiguration.adb#adapter.ActiveDatabaseAdapterConfiguration",
            "/AESchemas/ae/ADB/scalar.aeschema"), out);
    }

    /**
     * Regression (MISSING SAR / duplicate AAR): an AESDK adapter instance (.adb, .adr3, …)
     * declared as an {@code <adapterArchive>} must be recognized as AESDK from its
     * adapterReference path, so the archive-descriptor loop skips buildAar and lets
     * buildAdapterAarsIfNeeded own its AAR. Building it in both paths produced a duplicate
     * module entry, failing EAR assembly and leaving the EAR without its SAR. A generic
     * {@code .adapter} archive is NOT AESDK and is still built by buildAar.
     */
    @Test
    public void aesdkAdapterInstanceDetectedFromArchiveReferencePath() {
        assertTrue(".adb adapterReference must be detected as an AESDK instance",
            BwEarMojo.adapterSupportsAar(BwEarMojo.getExtNoDot(
                new File("/ActiveDatabaseAdapterConfiguration.adb"))));
        assertTrue(".adr3 adapterReference must be detected as an AESDK instance",
            BwEarMojo.adapterSupportsAar(BwEarMojo.getExtNoDot(
                new File("/BusinessDomains/EAI/R3AdapterConfiguration.adr3"))));
        assertFalse("generic .adapter must NOT be treated as an AESDK instance",
            BwEarMojo.adapterSupportsAar(BwEarMojo.getExtNoDot(
                new File("/GenericAdapterConfiguration.adapter"))));
    }

    @Test
    public void jdbcCheckpointPathsEmptyWhenNoJdbcResources() {
        assertTrue("no JDBC resources → no checkpoint repositories",
            BwEarMojo.jdbcCheckpointPaths(Arrays.asList("/a/b/C.process", "/x/Y.xsd")).isEmpty());
        assertTrue("null SAR paths → empty checkpoint list",
            BwEarMojo.jdbcCheckpointPaths(null).isEmpty());
    }
}

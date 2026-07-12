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

import static org.junit.Assert.assertFalse;
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
    //  SAP R/3: /#class fragment refs resolve to .aeschema via fallback
    // -----------------------------------------------------------------------

    /**
     * Regression: AESDK loadUrl elements of the form {@code /path/basename/#class} (after
     * #fragment strip: {@code /path/basename} — no extension) must resolve to
     * {@code /path/basename.aeschema} when no files exist under a {@code /path/basename/}
     * directory.
     *
     * <p>Mirrors DynamicLogonExternalCommit where {@code R3AdapterConfiguration.adr3} contains
     * {@code <AESDK:loadUrl>/AESchemas/ae/700/basic/structures/#class</AESDK:loadUrl>} and
     * the real file on disk is {@code AESchemas/ae/700/basic/structures.aeschema}.</p>
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void classFragmentRefResolvesViaAeschemaFallback() throws Exception {
        File dir = tmp.newFolder("class-fragment-fallback");

        // .adr3 with a /#class loadUrl pointing to structures.aeschema (via base-name ref)
        File adr3 = writeFile(dir, "R3AdapterConfiguration.adr3",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\"\n"
            + "  xmlns:AESDK=\"http://www.tibco.com/xmlns/aemeta/adapter/2002\">\n"
            + "  <AESDK:loadUrl>/AESchemas/ae/700/basic/structures/#class</AESDK:loadUrl>\n"
            + "  <AESDK:loadUrl>/AESchemas/ae/SAPAdapter40/classes.aeschema#rpcClass.RFCClient</AESDK:loadUrl>\n"
            + "</Repository:repository>");

        // structures.aeschema: present in project; referenced only via /#class notation
        File structures = writeFile(dir, "structures.aeschema",
            "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">"
            + "<class name=\"E1KNKKM-4x\"/></Repository:repository>");

        // classes.aeschema: referenced via direct path with #fragment (standard case)
        File classes = writeFile(dir, "classes.aeschema",
            "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">"
            + "<class name=\"RFCClient\"/></Repository:repository>");

        // Process references the .adr3 adapter
        File proc = writeFile(dir, "DynamicLogon.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/DynamicLogon</pd:name>\n"
            + "  <pd:activity name=\"RFC\">\n"
            + "    <ae.aepalette.sharedProperties.adapterService>"
            + "/R3AdapterConfiguration.adr3#adapterService.RFCClient"
            + "</ae.aepalette.sharedProperties.adapterService>\n"
            + "  </pd:activity>\n"
            + "</pd:ProcessDefinition>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(proc, "DynamicLogon.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(adr3,       "R3AdapterConfiguration.adr3"));
        sarFiles.add(bwFile(structures, "AESchemas/ae/700/basic/structures.aeschema"));
        sarFiles.add(bwFile(classes,    "AESchemas/ae/SAPAdapter40/classes.aeschema"));

        applyTransitive(parFiles, sarFiles, Collections.emptyList(), Collections.emptyList(), false);

        Set<String> names = fileNames(sarFiles);
        assertTrue("R3AdapterConfiguration.adr3 must be in SAR (referenced by process)",
            names.contains("R3AdapterConfiguration.adr3"));
        assertTrue("classes.aeschema must be in SAR (direct ref from .adr3)",
            names.contains("classes.aeschema"));
        assertTrue("structures.aeschema must be in SAR (via /#class fallback resolution)",
            names.contains("structures.aeschema"));
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
                "buildSapAdapterAarsIfNeeded",
                List.class, List.class, File.class, List.class,
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
            BUILD_SAP_AARS.invoke(new BwEarMojo(), allSarFiles, moduleFiles, workDir, gvars, null);
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
        File adr3   = writeFile(dir, "Publisher1.adr3", "<adr3/>");

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
        File tid   = writeFile(dir, "TIDManager.adr3TID", "<adr3tid/>");

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

        File pub1 = writeFile(dir, "Publisher1.adr3",  "<adr3/>");
        File pub2 = writeFile(dir, "Publisher2.adr3",  "<adr3/>");
        File tid  = writeFile(dir, "TIDManager.adr3TID", "<adr3tid/>");

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
}

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

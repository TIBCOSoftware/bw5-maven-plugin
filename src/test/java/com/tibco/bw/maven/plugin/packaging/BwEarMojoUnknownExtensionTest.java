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
 * Regression tests for third-party palette resources with unknown file extensions.
 *
 * <p>TIBCO BW5 palettes (CopyBook, SmartMapper, JavaSchema, …) reference resource files
 * via absolute BW paths in process XML elements, just like built-in palette types.
 * The extension of those files ({@code .cpy}, {@code .smartmapperermodel},
 * {@code .javaschema}, …) is not registered in {@code SAR_EXTENSIONS} because the
 * plugin cannot know every future palette type in advance.</p>
 *
 * <p>Before this fix, {@code isBwResourcePath} rejected any path whose extension was
 * not in {@code PAR_EXTENSIONS} or {@code SAR_EXTENSIONS}, so the reference was never
 * emitted by {@code extractBwResourceRefs} and the file was missing from the SAR even
 * though it was physically present in the project (collected via the unknown-extension
 * fallback in {@code collectFiles}).</p>
 *
 * <p>After the fix, any {@code /}-absolute path with an unknown extension passes the
 * syntactic check in {@code isBwResourcePath} and is handed to the BFS; if the file
 * exists in the resource index it is included, otherwise it is silently discarded.</p>
 */
public class BwEarMojoUnknownExtensionTest {

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
            if (bwFileClass == null) throw new NoSuchFieldException("BwFile not found");
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
    //  Unit tests: extractBwResourceRefs emits unknown-extension paths
    // -----------------------------------------------------------------------

    /**
     * Regression: {@code extractBwResourceRefs} must emit CopyBook {@code .cpy} paths
     * referenced from {@code ae.palette.cobolpalette.sharedProperties.copybook} elements.
     */
    @Test
    public void extractRefsEmitsCpyPath() throws Exception {
        File dir = tmp.newFolder("cpy-unit");
        File proc = writeFile(dir, "Req_Body.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/Services/Req_Body</pd:name>\n"
            + "  <pd:activity name=\"Parse\">\n"
            + "    <ae.palette.cobolpalette.sharedProperties.copybook>"
            + "/SharedResources/CopyBook/COMPLEX/ORTBD1_REP.cpy"
            + "</ae.palette.cobolpalette.sharedProperties.copybook>\n"
            + "  </pd:activity>\n"
            + "</pd:ProcessDefinition>");

        Set<String> refs = extractRefs(proc);

        assertTrue(".cpy path must be extracted from cobolpalette element",
            refs.contains("/SharedResources/CopyBook/COMPLEX/ORTBD1_REP.cpy"));
    }

    /**
     * Regression: {@code extractBwResourceRefs} must emit SmartMapper
     * {@code .smartmapperermodel} paths from {@code erModelRef} elements.
     */
    @Test
    public void extractRefsEmitsSmartMapperModelPath() throws Exception {
        File dir = tmp.newFolder("sm-unit");
        File proc = writeFile(dir, "CheckLogging.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/Processes/CheckLogging</pd:name>\n"
            + "  <pd:activity name=\"Lookup\">\n"
            + "    <erModelRef>"
            + "/SharedResources/SmartMapper ER Model File Storage VALIDATE.smartmapperermodel"
            + "</erModelRef>\n"
            + "    <fromParticipantRef>"
            + "/SharedResources/SmartMapper ER Model File Storage VALIDATE.smartmapperermodel"
            + "/Relationships/R_TYPE/TYPE/TYPE_INPUT"
            + "</fromParticipantRef>\n"
            + "  </pd:activity>\n"
            + "</pd:ProcessDefinition>");

        Set<String> refs = extractRefs(proc);

        assertTrue(".smartmapperermodel path must be extracted from erModelRef",
            refs.contains("/SharedResources/SmartMapper ER Model File Storage VALIDATE.smartmapperermodel"));
        assertFalse("Sub-path of smartmapperermodel must NOT be emitted as a separate ref",
            refs.stream().anyMatch(r -> r.contains("/Relationships/")));
    }

    /**
     * Regression: an unknown-extension path that does NOT correspond to any file on
     * disk must be silently discarded by the BFS (no error, not included in SAR).
     */
    @Test
    public void extractRefsEmitsUnknownPathButBfsDiscardsIfNoFile() throws Exception {
        File dir = tmp.newFolder("unknown-discard");
        File proc = writeFile(dir, "Op.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/Op</pd:name>\n"
            + "  <pd:activity name=\"A\">\n"
            + "    <someCustomElement>/SharedResources/Missing.unknownext</someCustomElement>\n"
            + "  </pd:activity>\n"
            + "</pd:ProcessDefinition>");

        // extractRefs emits the path...
        Set<String> refs = extractRefs(proc);
        assertTrue("Unknown-extension path must be emitted",
            refs.contains("/SharedResources/Missing.unknownext"));
    }

    // -----------------------------------------------------------------------
    //  Integration tests: BFS includes unknown-extension SAR files
    // -----------------------------------------------------------------------

    /**
     * Regression: a {@code .cpy} CopyBook file referenced from a process via
     * {@code ae.palette.cobolpalette.sharedProperties.copybook} must be included
     * in the SAR after transitive analysis.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void cpyFileIncludedInSarWhenReferencedByProcess() throws Exception {
        File dir = tmp.newFolder("cpy-sar");

        File cpyFile = writeFile(dir, "ORTBD1_REP.cpy",
            "      01 RECORD.\n         05 FIELD PIC X(10).\n");

        File procFile = writeFile(dir, "Req_Body.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/Services/Req_Body</pd:name>\n"
            + "  <pd:activity name=\"Parse\">\n"
            + "    <ae.palette.cobolpalette.sharedProperties.copybook>"
            + "/SharedResources/CopyBook/COMPLEX/ORTBD1_REP.cpy"
            + "</ae.palette.cobolpalette.sharedProperties.copybook>\n"
            + "  </pd:activity>\n"
            + "</pd:ProcessDefinition>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(procFile, "Services/Req_Body.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(cpyFile, "SharedResources/CopyBook/COMPLEX/ORTBD1_REP.cpy"));

        List<String> entryPoints = Collections.singletonList("/Services/Req_Body.process");
        List<String> sharedRes   = Collections.emptyList();

        applyTransitive(parFiles, sarFiles, entryPoints, sharedRes, false);

        assertTrue(".cpy file must be in SAR when referenced by process",
            fileNames(sarFiles).contains("ORTBD1_REP.cpy"));
    }

    /**
     * Regression: a {@code .smartmapperermodel} file referenced from a process via
     * {@code erModelRef} must be included in the SAR.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void smartMapperModelIncludedInSarWhenReferencedByProcess() throws Exception {
        File dir = tmp.newFolder("sm-sar");

        File smFile = writeFile(dir, "SmartMapper ER Model DB Storage.smartmapperermodel",
            "<?xml version=\"1.0\"?><erModel/>");

        File procFile = writeFile(dir, "CheckLogging.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/Processes/CheckLogging</pd:name>\n"
            + "  <pd:activity name=\"Lookup\">\n"
            + "    <erModelRef>"
            + "/SharedResources/SmartMapper ER Model DB Storage.smartmapperermodel"
            + "</erModelRef>\n"
            + "  </pd:activity>\n"
            + "</pd:ProcessDefinition>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(procFile, "Processes/CheckLogging.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(smFile, "SharedResources/SmartMapper ER Model DB Storage.smartmapperermodel"));

        List<String> entryPoints = Collections.singletonList("/Processes/CheckLogging.process");
        List<String> sharedRes   = Collections.emptyList();

        applyTransitive(parFiles, sarFiles, entryPoints, sharedRes, false);

        assertTrue(".smartmapperermodel must be in SAR when referenced by process",
            fileNames(sarFiles).contains("SmartMapper ER Model DB Storage.smartmapperermodel"));
    }

    /**
     * Regression: an unknown-extension file that is NOT referenced by any process
     * must NOT be included in the SAR (BFS correctly discards unreachable files).
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void unknownExtensionFileNotIncludedWhenNotReferenced() throws Exception {
        File dir = tmp.newFolder("unknown-not-ref");

        File cpyFile = writeFile(dir, "Unreferenced.cpy",
            "      01 RECORD.\n         05 FIELD PIC X(10).\n");

        File procFile = writeFile(dir, "Op.process",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "  <pd:name>/Op</pd:name>\n"
            + "</pd:ProcessDefinition>");

        List parFiles = new ArrayList();
        parFiles.add(bwFile(procFile, "Op.process"));

        List sarFiles = new ArrayList();
        sarFiles.add(bwFile(cpyFile, "SharedResources/Unreferenced.cpy"));

        List<String> entryPoints = Collections.singletonList("/Op.process");
        List<String> sharedRes   = Collections.emptyList();

        applyTransitive(parFiles, sarFiles, entryPoints, sharedRes, false);

        assertFalse("Unreferenced .cpy must NOT be in SAR",
            fileNames(sarFiles).contains("Unreferenced.cpy"));
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

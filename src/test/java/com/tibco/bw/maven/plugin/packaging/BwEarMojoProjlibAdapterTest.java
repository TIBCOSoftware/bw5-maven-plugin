package com.tibco.bw.maven.plugin.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Regression tests for adapter definition files that live in a project library.
 *
 * <p>An {@code <adapterArchive>} can point at an adapter kept in a projlib
 * ({@code /MyLib/Adapters/Shared.adb#adapter.Shared}); Designer builds the AAR from it. The
 * projlib contents were already extracted and collected into the resource pool, but the adapter
 * file was only looked up on disk under the project directories, so the build failed with
 * "Adapter file not found for AAR" — and the aeschemas the adapter references were silently
 * left out of the SAR.</p>
 *
 * <p>The pool is built here with the real {@code collectFiles} over an extracted projlib, so the
 * tests also pin down the relative-path format the lookup depends on.</p>
 */
public class BwEarMojoProjlibAdapterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String ADAPTER_PATH = "MyLib/Adapters/Shared.adb";

    private static File write(File root, String relativePath, String content) throws Exception {
        File f = new File(root, relativePath.replace('/', File.separatorChar));
        Files.createDirectories(f.getParentFile().toPath());
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    /** Collects {@code extractDir} the way {@code collectFilesFromProjlibs} does. */
    private static List<BwEarMojo.BwFile> collect(File extractDir) throws Exception {
        Method collectFiles = BwEarMojo.class.getDeclaredMethod(
            "collectFiles", File.class, File.class, List.class, List.class, List.class);
        collectFiles.setAccessible(true);
        List<BwEarMojo.BwFile> parFiles = new ArrayList<>();
        List<BwEarMojo.BwFile> sarFiles = new ArrayList<>();
        List<BwEarMojo.BwFile> metadataFiles = new ArrayList<>();
        collectFiles.invoke(new BwEarMojo(), extractDir, extractDir, parFiles, sarFiles, metadataFiles);
        return sarFiles;
    }

    /** The reported failure: the AAR's adapter only exists inside the projlib. */
    @Test
    public void findsAnAdapterThatOnlyExistsInAProjlib() throws Exception {
        File srcDir = tmp.newFolder("bw-src");
        File projlib = tmp.newFolder("bw-projlib-extracted", "MyLib-1.0.0");
        File adb = write(projlib, ADAPTER_PATH, "<adb/>");

        assertEquals(adb.getCanonicalFile(), BwEarMojo.locateAdapterFile(
            ADAPTER_PATH, srcDir, srcDir, collect(projlib)).getCanonicalFile());
    }

    /** A project file must keep precedence over a library file with the same path. */
    @Test
    public void prefersTheProjectCopyOverTheProjlibCopy() throws Exception {
        File srcDir = tmp.newFolder("bw-src");
        File own = write(srcDir, ADAPTER_PATH, "<project/>");
        File projlib = tmp.newFolder("bw-projlib-extracted", "MyLib-1.0.0");
        write(projlib, ADAPTER_PATH, "<library/>");

        List<BwEarMojo.BwFile> pool = collect(srcDir);
        pool.addAll(collect(projlib));

        assertEquals(own, BwEarMojo.locateAdapterFile(ADAPTER_PATH, srcDir, srcDir, pool));
    }

    /** BW5 paths are case-insensitive; the reference and the file on disk may differ in case. */
    @Test
    public void matchesProjlibPathsCaseInsensitively() throws Exception {
        File srcDir = tmp.newFolder("bw-src");
        File projlib = tmp.newFolder("bw-projlib-extracted", "MyLib-1.0.0");
        File adb = write(projlib, ADAPTER_PATH, "<adb/>");

        assertEquals(adb.getCanonicalFile(), BwEarMojo.locateAdapterFile(
            "mylib/adapters/SHARED.adb", srcDir, srcDir, collect(projlib)).getCanonicalFile());
    }

    @Test
    public void findsAnAdapterUnderTheProjectDirectoryWhenTheSourceCopyLacksIt() throws Exception {
        File srcDir = tmp.newFolder("bw-src");
        File projectDir = tmp.newFolder("project");
        File adb = write(projectDir, ADAPTER_PATH, "<adb/>");

        assertEquals(adb, BwEarMojo.locateAdapterFile(
            ADAPTER_PATH, srcDir, projectDir, new ArrayList<>()));
    }

    // -----------------------------------------------------------------------
    //  Resources an <adapterArchive> adapter needs: the SAR content for it, and (Bug #8) the
    //  AAR's EXTERNAL_RESOURCE_DEPENDENCY, which buildear fills with exactly that set.
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, BwEarMojo.BwFile> index(List<BwEarMojo.BwFile> pool) throws Exception {
        Method buildBwIndex = BwEarMojo.class.getDeclaredMethod("buildBwIndex", List.class);
        buildBwIndex.setAccessible(true);
        return (Map<String, BwEarMojo.BwFile>) buildBwIndex.invoke(new BwEarMojo(), pool);
    }

    private static String adb(String... loadUrls) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\""
            + " xmlns:AESDK=\"http://www.tibco.com/xmlns/aemeta/adapter/2002\">\n");
        for (String url : loadUrls) {
            sb.append("  <AESDK:loadUrl isRef=\"true\">").append(url).append("</AESDK:loadUrl>\n");
        }
        return sb.append("</Repository:repository>\n").toString();
    }

    private File adapterProject() throws Exception {
        File root = tmp.newFolder("bw-projlib-extracted", "MyLib-1.0.0");
        write(root, "AESchemas/ae/ADB/adbmetadata.aeschema", "<schema/>");
        write(root, "AESchemas/ae/ADB/Shared.aeschema", "<schema/>");
        write(root, "AESchemas/ae/ADB/Other.aeschema", "<schema/>");
        return root;
    }

    /** Plain loadUrls: only what the adapter references, nothing else in the directory. */
    @Test
    public void adapterResourcesAreTheSchemasTheAdapterReferences() throws Exception {
        File root = adapterProject();
        File adb = write(root, ADAPTER_PATH, adb(
            "/AESchemas/ae/ADB/adbmetadata.aeschema", "/AESchemas/ae/ADB/Shared.aeschema"));

        List<String> resources = new BwEarMojo().adapterSarResources(adb, index(collect(root)));

        assertEquals(Arrays.asList(
            "AESchemas/ae/ADB/adbmetadata.aeschema", "AESchemas/ae/ADB/Shared.aeschema"), resources);
    }

    /**
     * Fragment loadUrls ({@code X.aeschema#class}) make Designer take every aeschema in the
     * referenced directory — so does the SAR, and so must the AAR's dependency list.
     */
    @Test
    public void fragmentLoadUrlsTakeEverySchemaInTheDirectory() throws Exception {
        File root = adapterProject();
        File adb = write(root, ADAPTER_PATH, adb("/AESchemas/ae/ADB/Shared.aeschema#class"));

        List<String> resources = new BwEarMojo().adapterSarResources(adb, index(collect(root)));

        assertEquals(new HashSet<>(Arrays.asList("AESchemas/ae/ADB/adbmetadata.aeschema",
                "AESchemas/ae/ADB/Shared.aeschema", "AESchemas/ae/ADB/Other.aeschema")),
            new HashSet<>(resources));
    }

    /** A reference that cannot be packaged must not be declared as a dependency either. */
    @Test
    public void adapterResourcesSkipReferencesThatAreNotInTheProject() throws Exception {
        File root = adapterProject();
        File adb = write(root, ADAPTER_PATH, adb(
            "/AESchemas/ae/ADB/Shared.aeschema", "/AESchemas/ae/ADB/Missing.aeschema"));

        assertEquals(Collections.singletonList("AESchemas/ae/ADB/Shared.aeschema"),
            new BwEarMojo().adapterSarResources(adb, index(collect(root))));
    }

    @Test
    public void returnsNullWhenNeitherTheProjectNorAProjlibHasIt() throws Exception {
        File srcDir = tmp.newFolder("bw-src");
        File projlib = tmp.newFolder("bw-projlib-extracted", "MyLib-1.0.0");
        write(projlib, "MyLib/Adapters/Other.adb", "<adb/>");

        assertNull(BwEarMojo.locateAdapterFile(ADAPTER_PATH, srcDir, srcDir, collect(projlib)));
    }
}

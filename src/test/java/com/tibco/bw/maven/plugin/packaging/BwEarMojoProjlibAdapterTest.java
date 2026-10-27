package com.tibco.bw.maven.plugin.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

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

    @Test
    public void returnsNullWhenNeitherTheProjectNorAProjlibHasIt() throws Exception {
        File srcDir = tmp.newFolder("bw-src");
        File projlib = tmp.newFolder("bw-projlib-extracted", "MyLib-1.0.0");
        write(projlib, "MyLib/Adapters/Other.adb", "<adb/>");

        assertNull(BwEarMojo.locateAdapterFile(ADAPTER_PATH, srcDir, srcDir, collect(projlib)));
    }
}

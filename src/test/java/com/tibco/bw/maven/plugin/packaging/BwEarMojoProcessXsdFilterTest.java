package com.tibco.bw.maven.plugin.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@code BwEarMojo} utility methods used during archive discovery.
 */
public class BwEarMojoProcessXsdFilterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // -----------------------------------------------------------------------
    //  DEF-026: auto-discovery of .archive file in bwProjectPath
    // -----------------------------------------------------------------------

    @Test
    public void findArchiveFilesReturnsSingleArchive() throws Exception {
        File dir = tmp.newFolder("single-archive");
        File archive = new File(dir, "MyApp.archive");
        Files.write(archive.toPath(), "<archive/>".getBytes(StandardCharsets.UTF_8));

        List<File> found = BwEarMojo.findArchiveFiles(dir);

        assertEquals("Should find exactly one .archive file", 1, found.size());
        assertEquals("MyApp.archive", found.get(0).getName());
    }

    @Test
    public void findArchiveFilesReturnsEmptyWhenNonePresent() throws Exception {
        File dir = tmp.newFolder("no-archive");
        new File(dir, "some.process").createNewFile();
        new File(dir, "resource.sharedjdbc").createNewFile();

        List<File> found = BwEarMojo.findArchiveFiles(dir);

        assertTrue("No .archive file → empty list (all processes included)", found.isEmpty());
    }

    @Test
    public void findArchiveFilesFindsMultiple() throws Exception {
        File dir = tmp.newFolder("multi-archive");
        new File(dir, "App1.archive").createNewFile();
        new File(dir, "App2.archive").createNewFile();

        List<File> found = BwEarMojo.findArchiveFiles(dir);

        assertEquals("Both .archive files must be returned (caller decides which to use)", 2, found.size());
    }

    @Test
    public void findArchiveFilesIsNotRecursive() throws Exception {
        File dir = tmp.newFolder("nested-archive");
        File subdir = new File(dir, "Deployment");
        subdir.mkdir();
        new File(subdir, "nested.archive").createNewFile();

        List<File> found = BwEarMojo.findArchiveFiles(dir);

        assertTrue(".archive in a subdirectory must not be auto-discovered", found.isEmpty());
    }

    @Test
    public void findArchiveFilesReturnsEmptyForNullDir() {
        assertTrue(BwEarMojo.findArchiveFiles(null).isEmpty());
    }

    @Test
    public void findArchiveFilesReturnsEmptyForNonExistentDir() {
        assertTrue(BwEarMojo.findArchiveFiles(new File("/nonexistent/path")).isEmpty());
    }
}

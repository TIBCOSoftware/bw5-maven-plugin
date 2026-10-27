package com.tibco.bw.maven.plugin.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the {@code .archive} check of {@code bw5:validate}.
 *
 * <p>A process declared in a {@code <processArchive>} may come from a project library — bwear
 * packages those as well. The check used to look for it only under the project directory, so
 * such a process was reported as an ERROR (and failed the build with
 * {@code bw5.validate.failOnError=true}) even though the EAR built fine.</p>
 */
public class ValidateMojoArchiveTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String LIB_PROCESS = "/MyLib/Processes/Shared.process";

    private File archive(File projectDir) throws Exception {
        File f = new File(projectDir, "Deployment/App.archive");
        Files.createDirectories(f.getParentFile().toPath());
        Files.write(f.toPath(), ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">\n"
            + "  <enterpriseArchive>\n"
            + "    <name>App</name>\n"
            + "    <processArchive name=\"App\">\n"
            + "      <processProperty>" + LIB_PROCESS + "</processProperty>\n"
            + "    </processArchive>\n"
            + "  </enterpriseArchive>\n"
            + "</Repository:repository>\n").getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static void projlib(File libDir, String name, String... entries) throws Exception {
        try (OutputStream os = Files.newOutputStream(new File(libDir, name).toPath());
             ZipOutputStream zos = new ZipOutputStream(os)) {
            for (String entry : entries) {
                zos.putNextEntry(new ZipEntry(entry));
                zos.write("<x/>".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
    }

    /**
     * Runs the archive check the way the lifecycle does: the Maven module root is
     * {@code bwProjectPath}, the BW sources are the staged {@code srcDir} underneath it.
     */
    private List<ValidateMojo.Issue> validate(File srcDir, File libDir) throws Exception {
        ValidateMojo mojo = new ValidateMojo();
        mojo.bwProjectPath = tmp.getRoot();
        mojo.bwLibDirectory = libDir;
        Method m = ValidateMojo.class.getDeclaredMethod(
            "validateArchive", List.class, File.class, List.class);
        m.setAccessible(true);
        List<ValidateMojo.Issue> issues = new ArrayList<>();
        m.invoke(mojo, Collections.singletonList(archive(srcDir)), srcDir, issues);
        return issues;
    }

    private static void write(File root, String relativePath) throws Exception {
        File f = new File(root, relativePath.replace('/', File.separatorChar));
        Files.createDirectories(f.getParentFile().toPath());
        Files.write(f.toPath(), "<x/>".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Process paths are BW project paths: they must be resolved against the BW source root, not
     * the Maven module root, or every process of a project whose pom sits above its BW sources
     * is reported missing.
     */
    @Test
    public void resolvesDeclaredProcessesAgainstTheBwSourceRoot() throws Exception {
        File srcDir = tmp.newFolder("target", "bw-src");
        write(srcDir, LIB_PROCESS.substring(1));

        assertTrue(validate(srcDir, tmp.newFolder("bw-lib")).isEmpty());
    }

    /**
     * The staged sources live in {@code target/bw-src}; excluding every path that contains
     * {@code /target/} made validate find nothing at all there.
     */
    @Test
    public void findFilesWorksWhenTheRootItselfIsUnderTarget() throws Exception {
        File srcDir = tmp.newFolder("target", "bw-src");
        write(srcDir, "Deployment/App.archive");

        assertEquals(1, new ValidateMojo().findFiles(srcDir, ".archive").size());
    }

    @Test
    public void findFilesStillSkipsATargetDirectoryInsideTheRoot() throws Exception {
        File srcDir = tmp.newFolder("project");
        write(srcDir, "Deployment/App.archive");
        write(srcDir, "target/copy/Deployment/App.archive");

        assertEquals(1, new ValidateMojo().findFiles(srcDir, ".archive").size());
    }

    @Test
    public void acceptsAProcessThatComesFromAProjlib() throws Exception {
        File projectDir = tmp.newFolder("project");
        File libDir = tmp.newFolder("bw-lib");
        projlib(libDir, "MyLib-1.0.0.projlib", "MyLib/Processes/Shared.process");

        assertTrue(validate(projectDir, libDir).isEmpty());
    }

    @Test
    public void stillReportsAProcessThatIsNowhere() throws Exception {
        File projectDir = tmp.newFolder("project");
        File libDir = tmp.newFolder("bw-lib");
        projlib(libDir, "MyLib-1.0.0.projlib", "MyLib/Processes/Other.process");

        List<ValidateMojo.Issue> issues = validate(projectDir, libDir);
        assertEquals(1, issues.size());
        assertEquals(ValidateMojo.Severity.ERROR, issues.get(0).severity);
        assertTrue(issues.get(0).message, issues.get(0).message.contains(LIB_PROCESS));
    }

    @Test
    public void projlibEntryPathsAreLowerCasedWithoutLeadingSlash() throws Exception {
        File libDir = tmp.newFolder("bw-lib");
        projlib(libDir, "MyLib-1.0.0.projlib", "/MyLib/Processes/Shared.process");

        assertEquals(Collections.singleton("mylib/processes/shared.process"),
            ValidateMojo.projlibEntryPaths(libDir));
    }

    @Test
    public void projlibEntryPathsIsEmptyWithoutAStagingDirectory() {
        assertTrue(ValidateMojo.projlibEntryPaths(new File(tmp.getRoot(), "missing")).isEmpty());
        assertTrue(ValidateMojo.projlibEntryPaths(null).isEmpty());
    }
}

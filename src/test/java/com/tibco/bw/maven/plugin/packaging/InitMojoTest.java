package com.tibco.bw.maven.plugin.packaging;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class InitMojoTest {

    private File tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = createTempDir();
    }

    @After
    public void tearDown() {
        deleteRecursively(tempDir);
    }

    // -----------------------------------------------------------------------
    //  toArtifactId
    // -----------------------------------------------------------------------

    @Test
    public void toArtifactIdLowercasesName() {
        assertEquals("myapp", InitMojo.toArtifactId("MyApp"));
    }

    @Test
    public void toArtifactIdReplacesSpacesWithHyphens() {
        assertEquals("my-app", InitMojo.toArtifactId("My App"));
    }

    @Test
    public void toArtifactIdCollapsesDuplicateHyphens() {
        assertEquals("framework-common", InitMojo.toArtifactId("Framework  Common"));
    }

    @Test
    public void toArtifactIdStripsLeadingTrailingHyphens() {
        assertEquals("mylib", InitMojo.toArtifactId("_MyLib_"));
    }

    @Test
    public void toArtifactIdHandlesNullAndEmpty() {
        assertEquals("my-bw5-project", InitMojo.toArtifactId(null));
        assertEquals("my-bw5-project", InitMojo.toArtifactId(""));
        assertEquals("my-bw5-project", InitMojo.toArtifactId("   "));
    }

    @Test
    public void toArtifactIdPreservesHyphens() {
        assertEquals("my-service-v2", InitMojo.toArtifactId("My Service V2"));
    }

    // -----------------------------------------------------------------------
    //  generatePomXml
    // -----------------------------------------------------------------------

    @Test
    public void generatePomContainsMavenCoordinates() {
        String pom = InitMojo.generatePomXml("com.example", "my-app", "2.0.0-SNAPSHOT",
            "bwear", null, Collections.emptyList());
        assertTrue(pom.contains("<groupId>com.example</groupId>"));
        assertTrue(pom.contains("<artifactId>my-app</artifactId>"));
        assertTrue(pom.contains("<version>2.0.0-SNAPSHOT</version>"));
        assertTrue(pom.contains("<packaging>bwear</packaging>"));
    }

    @Test
    public void generatePomForProjlibHasCorrectPackaging() {
        String pom = InitMojo.generatePomXml("com.example", "my-lib", "1.0.0-SNAPSHOT",
            "projlib", null, Collections.emptyList());
        assertTrue(pom.contains("<packaging>projlib</packaging>"));
    }

    @Test
    public void generatePomIncludesPluginBlock() {
        String pom = InitMojo.generatePomXml("com.example", "my-app", "1.0.0-SNAPSHOT",
            "bwear", null, Collections.emptyList());
        assertTrue(pom.contains("<groupId>com.tibco.bw</groupId>"));
        assertTrue(pom.contains("<artifactId>bw5-maven-plugin</artifactId>"));
        assertTrue(pom.contains("<extensions>true</extensions>"));
    }

    @Test
    public void generatePomContainsCommentedDepsWhenDesignTimeLibsPresent() {
        List<String> libs = Arrays.asList(
            "/opt/tibco/libs/FrameworkCommon.projlib",
            "/opt/tibco/libs/AnotherLib.projlib"
        );
        String pom = InitMojo.generatePomXml("com.example", "my-app", "1.0.0-SNAPSHOT",
            "bwear", null, libs);
        assertTrue(pom.contains("FrameworkCommon"));
        assertTrue(pom.contains("AnotherLib"));
        assertTrue(pom.contains("<type>projlib</type>"));
        assertTrue(pom.contains("TODO"));
        // Dependencies must be in a comment block
        int depsIdx = pom.indexOf("<dependencies>");
        int commentIdx = pom.indexOf("<!--");
        assertTrue("dependencies must be inside a comment", commentIdx < depsIdx);
    }

    @Test
    public void generatePomOmitsDepsWhenNone() {
        String pom = InitMojo.generatePomXml("com.example", "my-app", "1.0.0-SNAPSHOT",
            "bwear", null, Collections.emptyList());
        assertFalse(pom.contains("<dependencies>"));
        assertFalse(pom.contains("TODO"));
    }

    @Test
    public void generatePomIncludesArchiveDescriptorHintForBwear() {
        String pom = InitMojo.generatePomXml("com.example", "my-app", "1.0.0-SNAPSHOT",
            "bwear", "MyApp.archive", Collections.emptyList());
        assertTrue(pom.contains("MyApp.archive"));
        assertTrue(pom.contains("archiveDescriptorFile"));
    }

    @Test
    public void generatePomIncludesLibBuilderHintForProjlib() {
        String pom = InitMojo.generatePomXml("com.example", "my-lib", "1.0.0-SNAPSHOT",
            "projlib", "MyLib.libbuilder", Collections.emptyList());
        assertTrue(pom.contains("MyLib.libbuilder"));
        assertTrue(pom.contains("libBuilderFile"));
    }

    @Test
    public void generatePomIsValidXmlOpening() {
        String pom = InitMojo.generatePomXml("com.example", "my-app", "1.0.0-SNAPSHOT",
            "bwear", null, Collections.emptyList());
        assertTrue(pom.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(pom.contains("</project>"));
    }

    // -----------------------------------------------------------------------
    //  detectProject
    // -----------------------------------------------------------------------

    @Test
    public void detectsBwearFromArchiveFile() throws Exception {
        writeFile(new File(tempDir, "MyApp.archive"), archiveXml("MyApp"));

        InitMojo.DetectionResult r = newMojo().detectProject(tempDir);

        assertEquals("bwear", r.packaging);
        assertEquals("myapp", r.defaultArtifactId);
        assertEquals("MyApp.archive", r.descriptorFileName);
    }

    @Test
    public void detectsProjlibFromLibBuilderAtRoot() throws Exception {
        writeFile(new File(tempDir, "MyLib.libbuilder"), libBuilderXml("MyLibrary"));

        InitMojo.DetectionResult r = newMojo().detectProject(tempDir);

        assertEquals("projlib", r.packaging);
        assertEquals("mylibrary", r.defaultArtifactId);
        assertEquals("MyLib.libbuilder", r.descriptorFileName);
    }

    @Test
    public void detectsProjlibFromLibBuilderInLibrarySubdir() throws Exception {
        File libraryDir = new File(tempDir, "Library");
        libraryDir.mkdir();
        writeFile(new File(libraryDir, "FrameworkCommon.libbuilder"),
            libBuilderXml("FrameworkCommon"));

        InitMojo.DetectionResult r = newMojo().detectProject(tempDir);

        assertEquals("projlib", r.packaging);
        assertEquals("frameworkcommon", r.defaultArtifactId);
        assertEquals("FrameworkCommon.libbuilder", r.descriptorFileName);
    }

    @Test
    public void prefersArchiveOverLibBuilderWhenBothPresent() throws Exception {
        writeFile(new File(tempDir, "MyApp.archive"), archiveXml("MyApp"));
        writeFile(new File(tempDir, "MyLib.libbuilder"), libBuilderXml("MyLib"));

        InitMojo.DetectionResult r = newMojo().detectProject(tempDir);

        assertEquals("bwear", r.packaging);
    }

    @Test(expected = MojoExecutionException.class)
    public void failsWhenNeitherDescriptorFound() throws Exception {
        newMojo().detectProject(tempDir);
    }

    @Test
    public void detectsBwearFromAESchemasFolder() throws Exception {
        new File(tempDir, "AESchemas").mkdir();

        InitMojo.DetectionResult r = newMojo().detectProject(tempDir);

        assertEquals("bwear", r.packaging);
        assertNull(r.descriptorFileName);
    }

    @Test
    public void detectsBwearFromVcrepodat() throws Exception {
        writeFile(new File(tempDir, "vcrepo.dat"), "");

        InitMojo.DetectionResult r = newMojo().detectProject(tempDir);

        assertEquals("bwear", r.packaging);
        assertNull(r.descriptorFileName);
    }

    @Test
    public void detectsArtifactIdFromDirNameWhenNoDescriptor() throws Exception {
        new File(tempDir, "AESchemas").mkdir();

        InitMojo.DetectionResult r = newMojo().detectProject(tempDir);

        assertEquals(InitMojo.toArtifactId(tempDir.getName()), r.defaultArtifactId);
    }

    @Test
    public void usesFilenameAsArtifactIdWhenArchiveNameUnparseable() throws Exception {
        // Write a malformed .archive file
        writeFile(new File(tempDir, "MyApp.archive"), "<not-valid-xml>");

        InitMojo.DetectionResult r = newMojo().detectProject(tempDir);

        assertEquals("bwear", r.packaging);
        assertEquals("myapp", r.defaultArtifactId); // falls back to filename
    }

    // -----------------------------------------------------------------------
    //  execute() integration
    // -----------------------------------------------------------------------

    @Test
    public void executeWritesPomForEarProject() throws Exception {
        writeFile(new File(tempDir, "MyApp.archive"), archiveXml("MyApp"));

        createMojo(tempDir, "com.example", null, null, false).execute();

        File pomFile = new File(tempDir, "pom.xml");
        assertTrue("pom.xml should exist", pomFile.exists());
        String content = readFile(pomFile);
        assertTrue(content.contains("<packaging>bwear</packaging>"));
        assertTrue(content.contains("<groupId>com.example</groupId>"));
        assertTrue(content.contains("<artifactId>myapp</artifactId>"));
    }

    @Test
    public void executeWritesPomForProjlib() throws Exception {
        File lib = new File(tempDir, "Library");
        lib.mkdir();
        writeFile(new File(lib, "FwkCommon.libbuilder"), libBuilderXml("FwkCommon"));

        createMojo(tempDir, "com.example", null, null, false).execute();

        String content = readFile(new File(tempDir, "pom.xml"));
        assertTrue(content.contains("<packaging>projlib</packaging>"));
    }

    @Test
    public void executeUsesExplicitArtifactId() throws Exception {
        writeFile(new File(tempDir, "MyApp.archive"), archiveXml("MyApp"));

        createMojo(tempDir, "com.example", "custom-artifact", null, false).execute();

        String content = readFile(new File(tempDir, "pom.xml"));
        assertTrue(content.contains("<artifactId>custom-artifact</artifactId>"));
    }

    @Test
    public void executeUsesExplicitVersion() throws Exception {
        writeFile(new File(tempDir, "MyApp.archive"), archiveXml("MyApp"));

        createMojo(tempDir, "com.example", null, "3.0.0", false).execute();

        String content = readFile(new File(tempDir, "pom.xml"));
        assertTrue(content.contains("<version>3.0.0</version>"));
    }

    @Test
    public void executeIncludesDesignTimeLibsAsCommentedStubs() throws Exception {
        writeFile(new File(tempDir, "MyApp.archive"), archiveXml("MyApp"));
        writeFile(new File(tempDir, ".designtimelibs"),
            "/opt/tibco/FrameworkCommon.projlib\n/opt/tibco/SharedUtils.projlib\n");

        createMojo(tempDir, "com.example", null, null, false).execute();

        String content = readFile(new File(tempDir, "pom.xml"));
        assertTrue(content.contains("FrameworkCommon"));
        assertTrue(content.contains("SharedUtils"));
        assertTrue(content.contains("TODO"));
    }

    @Test
    public void executeUsesDefaultGroupIdWhenMissing() throws Exception {
        writeFile(new File(tempDir, "MyApp.archive"), archiveXml("MyApp"));
        createMojo(tempDir, null, null, null, false).execute();
        String content = readFile(new File(tempDir, "pom.xml"));
        assertTrue(content.contains("<groupId>com.tibco</groupId>"));
    }

    @Test
    public void executeUsesDefaultGroupIdWhenBlank() throws Exception {
        writeFile(new File(tempDir, "MyApp.archive"), archiveXml("MyApp"));
        createMojo(tempDir, "   ", null, null, false).execute();
        String content = readFile(new File(tempDir, "pom.xml"));
        assertTrue(content.contains("<groupId>com.tibco</groupId>"));
    }

    @Test(expected = MojoExecutionException.class)
    public void executeFailsWhenPomAlreadyExistsAndNoForce() throws Exception {
        writeFile(new File(tempDir, "MyApp.archive"), archiveXml("MyApp"));
        writeFile(new File(tempDir, "pom.xml"), "<project/>");
        createMojo(tempDir, "com.example", null, null, false).execute();
    }

    @Test
    public void executeOverwritesPomWhenForceTrue() throws Exception {
        writeFile(new File(tempDir, "MyApp.archive"), archiveXml("MyApp"));
        writeFile(new File(tempDir, "pom.xml"), "<project/>");

        createMojo(tempDir, "com.example", null, null, true).execute();

        String content = readFile(new File(tempDir, "pom.xml"));
        assertTrue("pom.xml should be overwritten", content.contains("<packaging>bwear</packaging>"));
    }

    @Test(expected = MojoExecutionException.class)
    public void executeFailsWhenProjectDirNotExist() throws Exception {
        File nonExistent = new File(tempDir, "does-not-exist");
        createMojo(nonExistent, "com.example", null, null, false).execute();
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private InitMojo newMojo() {
        InitMojo mojo = new InitMojo();
        mojo.setLog(new SilentLog());
        return mojo;
    }

    private InitMojo createMojo(File dir, String groupId, String artifactId,
            String version, boolean force) throws Exception {
        InitMojo mojo = newMojo();
        setField(mojo, "projectDir", dir);
        setField(mojo, "groupId", groupId);
        setField(mojo, "artifactId", artifactId);
        setField(mojo, "version", version != null ? version : "1.0.0-SNAPSHOT");
        setField(mojo, "force", force);
        return mojo;
    }

    private void setField(Object obj, String name, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(obj, value);
    }

    private void writeFile(File file, String content) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }

    private String readFile(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private File createTempDir() throws IOException {
        File dir = File.createTempFile("bw5init", "");
        dir.delete();
        dir.mkdirs();
        return dir;
    }

    private void deleteRecursively(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        f.delete();
    }

    private static String archiveXml(String earName) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">\n"
            + "    <enterpriseArchive>\n"
            + "        <name>" + earName + "</name>\n"
            + "        <processArchive name=\"" + earName + " Archive\"/>\n"
            + "        <sharedArchive name=\"Shared Archive\"/>\n"
            + "    </enterpriseArchive>\n"
            + "</Repository:repository>\n";
    }

    private static String libBuilderXml(String libName) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">\n"
            + "    <name name=\"" + libName + "\">\n"
            + "        <version>1</version>\n"
            + "        <resources>/Lib/Process1.process</resources>\n"
            + "    </name>\n"
            + "</Repository:repository>\n";
    }

    // -----------------------------------------------------------------------
    //  No-op Log implementation
    // -----------------------------------------------------------------------

    private static class SilentLog implements Log {
        public boolean isDebugEnabled() { return false; }
        public void debug(CharSequence c) {}
        public void debug(CharSequence c, Throwable t) {}
        public void debug(Throwable t) {}
        public boolean isInfoEnabled() { return false; }
        public void info(CharSequence c) {}
        public void info(CharSequence c, Throwable t) {}
        public void info(Throwable t) {}
        public boolean isWarnEnabled() { return false; }
        public void warn(CharSequence c) {}
        public void warn(CharSequence c, Throwable t) {}
        public void warn(Throwable t) {}
        public boolean isErrorEnabled() { return false; }
        public void error(CharSequence c) {}
        public void error(CharSequence c, Throwable t) {}
        public void error(Throwable t) {}
    }
}

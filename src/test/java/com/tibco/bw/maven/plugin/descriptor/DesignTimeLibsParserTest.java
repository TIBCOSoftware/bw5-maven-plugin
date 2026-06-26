package com.tibco.bw.maven.plugin.descriptor;

import org.junit.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public class DesignTimeLibsParserTest {

    // -----------------------------------------------------------------------
    //  parse()
    // -----------------------------------------------------------------------

    @Test
    public void parsesSimplePaths() throws Exception {
        File f = writeTemp("/opt/tibco/FrameworkCommon.projlib\n/opt/tibco/AnotherLib.projlib\n");
        List<String> paths = new DesignTimeLibsParser().parse(f);
        assertEquals(2, paths.size());
        assertEquals("/opt/tibco/FrameworkCommon.projlib", paths.get(0));
        assertEquals("/opt/tibco/AnotherLib.projlib", paths.get(1));
    }

    @Test
    public void parsesNumberedDesignerFormat() throws Exception {
        File f = writeTemp("1=/opt/tibco/FrameworkCommon.projlib\n2=/opt/tibco/AnotherLib.projlib\n");
        List<String> paths = new DesignTimeLibsParser().parse(f);
        assertEquals(2, paths.size());
        assertEquals("/opt/tibco/FrameworkCommon.projlib", paths.get(0));
        assertEquals("/opt/tibco/AnotherLib.projlib", paths.get(1));
    }

    @Test
    public void skipsCommentLines() throws Exception {
        File f = writeTemp("# This is a comment\n/path/to/Lib.projlib\n");
        List<String> paths = new DesignTimeLibsParser().parse(f);
        assertEquals(1, paths.size());
        assertEquals("/path/to/Lib.projlib", paths.get(0));
    }

    @Test
    public void skipsBlankLines() throws Exception {
        File f = writeTemp("\n/path/to/Lib.projlib\n\n");
        List<String> paths = new DesignTimeLibsParser().parse(f);
        assertEquals(1, paths.size());
    }

    @Test
    public void handlesEmptyFile() throws Exception {
        File f = writeTemp("");
        assertTrue(new DesignTimeLibsParser().parse(f).isEmpty());
    }

    @Test
    public void handlesCommentAndBlankOnlyFile() throws Exception {
        File f = writeTemp("# only comments\n\n# another comment\n");
        assertTrue(new DesignTimeLibsParser().parse(f).isEmpty());
    }

    @Test
    public void handlesMixedFormat() throws Exception {
        File f = writeTemp("# header\n1=/opt/tibco/LibA.projlib\n/opt/tibco/LibB.projlib\n");
        List<String> paths = new DesignTimeLibsParser().parse(f);
        assertEquals(2, paths.size());
        assertEquals("/opt/tibco/LibA.projlib", paths.get(0));
        assertEquals("/opt/tibco/LibB.projlib", paths.get(1));
    }

    // -----------------------------------------------------------------------
    //  extractLibName()
    // -----------------------------------------------------------------------

    @Test
    public void extractsNameFromUnixPath() {
        assertEquals("FrameworkCommon",
            DesignTimeLibsParser.extractLibName("/opt/tibco/libs/FrameworkCommon.projlib"));
    }

    @Test
    public void extractsNameFromWindowsPath() {
        assertEquals("AnotherLib",
            DesignTimeLibsParser.extractLibName("C:\\tibco\\libs\\AnotherLib.projlib"));
    }

    @Test
    public void extractsNameFromBareFilename() {
        assertEquals("MyLib", DesignTimeLibsParser.extractLibName("MyLib.projlib"));
    }

    @Test
    public void extractsNameCaseInsensitiveExtension() {
        assertEquals("MyLib", DesignTimeLibsParser.extractLibName("/path/MyLib.PROJLIB"));
    }

    @Test
    public void preservesNameWhenNoExtension() {
        assertEquals("MyLib", DesignTimeLibsParser.extractLibName("/path/MyLib"));
    }

    @Test
    public void extractsArtifactIdFromPluginMavenCoordinate() {
        // Format produced by bw5:designer-setup after parse() strips the "N=" prefix:
        // the raw value is "groupId\:artifactId\:version\:type\="
        assertEquals("framework-common",
            DesignTimeLibsParser.extractLibName("com.example\\:framework-common\\:1.0.0\\:projlib\\="));
    }

    @Test
    public void parsesPluginWrittenMavenCoordinateFormat() throws Exception {
        // File written by bw5:designer-setup
        File f = writeTemp(
            "#Design time libraries\n"
            + "#Format: #=File Alias=Description\n"
            + "0=com.example\\:framework-common\\:1.0.0\\:projlib\\=\n"
            + "1=com.example\\:another-lib\\:2.0.0\\:projlib\\=\n");
        List<String> paths = new DesignTimeLibsParser().parse(f);
        assertEquals(2, paths.size());
        assertEquals("framework-common", DesignTimeLibsParser.extractLibName(paths.get(0)));
        assertEquals("another-lib", DesignTimeLibsParser.extractLibName(paths.get(1)));
    }

    // -----------------------------------------------------------------------
    //  DEF-003: Designer "N=path=description" format — artifactId must not be "="
    // -----------------------------------------------------------------------

    @Test
    public void parsesDesignerNativeFormatWithDescription() throws Exception {
        // TIBCO Designer writes: N=FilePath=HumanReadableAlias
        File f = writeTemp("1=/opt/tibco/libs/FrameworkCommon.projlib=Framework Common\n"
                         + "2=C:\\tibco\\libs\\AnotherLib.projlib=Another Library\n");
        List<String> paths = new DesignTimeLibsParser().parse(f);
        assertEquals(2, paths.size());
        assertEquals("/opt/tibco/libs/FrameworkCommon.projlib", paths.get(0));
        assertEquals("C:\\tibco\\libs\\AnotherLib.projlib", paths.get(1));
    }

    @Test
    public void extractLibNameFromDesignerNativeFormat() throws Exception {
        // After parse() strips N= prefix: "FilePath=Description" — extractLibName must yield just the name
        File f = writeTemp("1=/opt/tibco/libs/FrameworkCommon.projlib=Framework Common\n");
        List<String> paths = new DesignTimeLibsParser().parse(f);
        assertEquals("FrameworkCommon", DesignTimeLibsParser.extractLibName(paths.get(0)));
    }

    @Test
    public void parseDoesNotProduceEqualsAsArtifactId() throws Exception {
        // Regression for DEF-003: a Designer entry with empty path after N= must not yield "="
        File f = writeTemp("1==Some Description\n");
        List<String> paths = new DesignTimeLibsParser().parse(f);
        // The stripped value is "=Some Description" → stripDesignerDescription → ""  → not added
        assertTrue("Entry with empty path must be skipped entirely", paths.isEmpty());
    }

    @Test
    public void stripDesignerDescriptionLeavesPluginMavenCoordIntact() {
        // Maven coordinates written by bw5:designer-setup end with \= (escaped equals) — must survive
        String coord = "com.example\\:framework-common\\:1.0.0\\:projlib\\=";
        assertEquals(coord, DesignTimeLibsParser.stripDesignerDescription(coord));
    }

    @Test
    public void stripDesignerDescriptionStripsUnescapedEquals() {
        assertEquals("/opt/tibco/libs/FrameworkCommon.projlib",
            DesignTimeLibsParser.stripDesignerDescription("/opt/tibco/libs/FrameworkCommon.projlib=Framework Common"));
    }

    @Test
    public void stripDesignerDescriptionHandlesNoEquals() {
        assertEquals("/opt/tibco/libs/FrameworkCommon.projlib",
            DesignTimeLibsParser.stripDesignerDescription("/opt/tibco/libs/FrameworkCommon.projlib"));
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private File writeTemp(String content) throws IOException {
        File f = File.createTempFile("designtimelibs", ".designtimelibs");
        f.deleteOnExit();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(content);
        }
        return f;
    }
}

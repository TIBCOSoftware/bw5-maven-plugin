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

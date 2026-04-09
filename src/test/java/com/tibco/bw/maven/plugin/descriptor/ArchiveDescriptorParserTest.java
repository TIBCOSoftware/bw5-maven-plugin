package com.tibco.bw.maven.plugin.descriptor;

import org.junit.Test;

import java.io.File;
import java.net.URL;

import static org.junit.Assert.*;

public class ArchiveDescriptorParserTest {

    private File resource(String name) throws Exception {
        URL url = getClass().getClassLoader().getResource("descriptor/" + name);
        assertNotNull("Test resource not found: " + name, url);
        return new File(url.toURI());
    }

    @Test
    public void parsesEarName() throws Exception {
        ArchiveDescriptorParser.ArchiveDescriptor d = new ArchiveDescriptorParser().parse(resource("MyApp.archive"));
        assertEquals("MyApp", d.earName);
    }

    @Test
    public void parsesProcessArchiveName() throws Exception {
        ArchiveDescriptorParser.ArchiveDescriptor d = new ArchiveDescriptorParser().parse(resource("MyApp.archive"));
        assertEquals("MyApp Archive", d.processArchiveName);
    }

    @Test
    public void parsesSharedArchiveName() throws Exception {
        ArchiveDescriptorParser.ArchiveDescriptor d = new ArchiveDescriptorParser().parse(resource("MyApp.archive"));
        assertEquals("MyShared Archive", d.sharedArchiveName);
    }

    @Test
    public void parsesProcessPaths() throws Exception {
        ArchiveDescriptorParser.ArchiveDescriptor d = new ArchiveDescriptorParser().parse(resource("MyApp.archive"));
        assertTrue(d.hasExplicitProcessList());
        assertEquals(2, d.processPaths.size());
        assertTrue(d.processPaths.contains("/com/example/Receiver.process"));
        assertTrue(d.processPaths.contains("/com/example/SubProcess.process"));
    }

    @Test
    public void missingProcessPropertyGivesEmptyList() throws Exception {
        File tmp = File.createTempFile("noprocess", ".archive");
        tmp.deleteOnExit();
        try (java.io.PrintWriter pw = new java.io.PrintWriter(tmp)) {
            pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            pw.println("<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">");
            pw.println("  <enterpriseArchive>");
            pw.println("    <name>Empty</name>");
            pw.println("    <processArchive name=\"Process Archive\"/>");
            pw.println("  </enterpriseArchive>");
            pw.println("</Repository:repository>");
        }
        ArchiveDescriptorParser.ArchiveDescriptor d = new ArchiveDescriptorParser().parse(tmp);
        assertFalse(d.hasExplicitProcessList());
        assertTrue(d.processPaths.isEmpty());
    }
}

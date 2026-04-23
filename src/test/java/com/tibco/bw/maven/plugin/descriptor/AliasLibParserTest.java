package com.tibco.bw.maven.plugin.descriptor;

import org.junit.Test;

import java.io.File;
import java.io.PrintWriter;
import java.net.URL;
import java.util.List;

import static org.junit.Assert.*;

public class AliasLibParserTest {

    private File resource(String name) throws Exception {
        URL url = getClass().getClassLoader().getResource("descriptor/" + name);
        assertNotNull("Test resource not found: " + name, url);
        return new File(url.toURI());
    }

    // ── Single entry ──────────────────────────────────────────────────────────

    @Test
    public void parseSingleEntry_count() throws Exception {
        List<AliasLibParser.AliasLibEntry> entries =
            new AliasLibParser().parse(resource("SingleEntry.aliaslib"));
        assertEquals(1, entries.size());
    }

    @Test
    public void parseSingleEntry_aliasName() throws Exception {
        List<AliasLibParser.AliasLibEntry> entries =
            new AliasLibParser().parse(resource("SingleEntry.aliaslib"));
        assertEquals("commons-lang3-3.12.0.jar", entries.get(0).aliasName);
    }

    @Test
    public void parseSingleEntry_includeInDeployment() throws Exception {
        List<AliasLibParser.AliasLibEntry> entries =
            new AliasLibParser().parse(resource("SingleEntry.aliaslib"));
        assertTrue(entries.get(0).includeInDeployment);
    }

    @Test
    public void parseSingleEntry_isClasspathFile() throws Exception {
        List<AliasLibParser.AliasLibEntry> entries =
            new AliasLibParser().parse(resource("SingleEntry.aliaslib"));
        assertTrue(entries.get(0).isClasspathFile);
    }

    // ── Multiple entries ──────────────────────────────────────────────────────

    @Test
    public void parseMultiEntry_count() throws Exception {
        List<AliasLibParser.AliasLibEntry> entries =
            new AliasLibParser().parse(resource("MultiEntry.aliaslib"));
        assertEquals(3, entries.size());
    }

    @Test
    public void parseMultiEntry_names() throws Exception {
        List<AliasLibParser.AliasLibEntry> entries =
            new AliasLibParser().parse(resource("MultiEntry.aliaslib"));
        assertEquals("commons-lang3-3.12.0.jar", entries.get(0).aliasName);
        assertEquals("my-util-2.0.0.jar",        entries.get(1).aliasName);
        assertEquals("tibco-system-1.0.jar",      entries.get(2).aliasName);
    }

    @Test
    public void parseMultiEntry_includeInDeploymentFlags() throws Exception {
        List<AliasLibParser.AliasLibEntry> entries =
            new AliasLibParser().parse(resource("MultiEntry.aliaslib"));
        assertTrue(entries.get(0).includeInDeployment);   // commons-lang3
        assertFalse(entries.get(1).includeInDeployment);  // my-util (false)
        assertTrue(entries.get(2).includeInDeployment);   // tibco-system
    }

    @Test
    public void parseMultiEntry_isClasspathFileFlags() throws Exception {
        List<AliasLibParser.AliasLibEntry> entries =
            new AliasLibParser().parse(resource("MultiEntry.aliaslib"));
        assertTrue(entries.get(0).isClasspathFile);
        assertTrue(entries.get(1).isClasspathFile);
        assertFalse(entries.get(2).isClasspathFile);
    }

    // ── Empty FILE_ALIASES_LIST ───────────────────────────────────────────────

    @Test
    public void parseEmpty_returnsEmptyList() throws Exception {
        List<AliasLibParser.AliasLibEntry> entries =
            new AliasLibParser().parse(resource("Empty.aliaslib"));
        assertNotNull(entries);
        assertTrue(entries.isEmpty());
    }

    // ── Missing FILE_ALIASES_LIST element ─────────────────────────────────────

    @Test
    public void parseMissingFileAliasesList_returnsEmptyList() throws Exception {
        File tmp = File.createTempFile("noaliases", ".aliaslib");
        tmp.deleteOnExit();
        try (PrintWriter pw = new PrintWriter(tmp)) {
            pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            pw.println("<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">");
            pw.println("  <name name=\"NoAliases\">");
            pw.println("  </name>");
            pw.println("</Repository:repository>");
        }
        List<AliasLibParser.AliasLibEntry> entries = new AliasLibParser().parse(tmp);
        assertNotNull(entries);
        assertTrue(entries.isEmpty());
    }

    // ── parseAll: scans directory recursively ─────────────────────────────────

    @Test
    public void parseAll_findsFilesRecursively() throws Exception {
        // Use the descriptor/ resources directory which has SingleEntry, MultiEntry, Empty
        File dir = resource("SingleEntry.aliaslib").getParentFile();
        List<AliasLibParser.AliasLibEntry> entries = new AliasLibParser().parseAll(dir);
        // 1 (Single) + 3 (Multi) + 0 (Empty) = 4
        assertEquals(4, entries.size());
    }

    @Test
    public void parseAll_nonExistentDir_returnsEmpty() throws Exception {
        List<AliasLibParser.AliasLibEntry> entries =
            new AliasLibParser().parseAll(new File("/no/such/dir"));
        assertNotNull(entries);
        assertTrue(entries.isEmpty());
    }
}

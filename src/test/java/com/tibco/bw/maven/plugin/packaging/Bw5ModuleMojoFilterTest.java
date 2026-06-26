package com.tibco.bw.maven.plugin.packaging;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class Bw5ModuleMojoFilterTest {

    // -----------------------------------------------------------------------
    //  parentDir
    // -----------------------------------------------------------------------

    @Test
    public void parentDirReturnsEmptyForRootFile() {
        assertEquals("", Bw5ModuleMojo.LibBuilderFilter.parentDir("file.process"));
    }

    @Test
    public void parentDirReturnsImmediateParent() {
        assertEquals("Common", Bw5ModuleMojo.LibBuilderFilter.parentDir("Common/MyProcess.process"));
    }

    @Test
    public void parentDirReturnsFullPathForNestedFile() {
        assertEquals("Common/ARC", Bw5ModuleMojo.LibBuilderFilter.parentDir("Common/ARC/MyProcess.process"));
    }

    // -----------------------------------------------------------------------
    //  buildDirPrefixes
    // -----------------------------------------------------------------------

    @Test
    public void buildDirPrefixesIncludesRootWhenRootResourceListed() {
        Set<String> resources = new HashSet<>(Collections.singletonList("RootProcess.process"));
        Set<String> prefixes = Bw5ModuleMojo.LibBuilderFilter.buildDirPrefixes(resources);
        assertTrue("Root dir marker '' must be present for root-level resources", prefixes.contains(""));
    }

    @Test
    public void buildDirPrefixesAlwaysIncludesRootEvenForNestedResources() {
        // DEF-012 regression: root "" must always be in the set so the root .folder is included
        Set<String> resources = new HashSet<>(Collections.singletonList("Common/MyProcess.process"));
        Set<String> prefixes = Bw5ModuleMojo.LibBuilderFilter.buildDirPrefixes(resources);
        assertTrue(prefixes.contains("Common"));
        assertTrue("Root '' must always be present (DEF-012 fix)", prefixes.contains(""));
    }

    @Test
    public void buildDirPrefixesIncludesAllAncestorsAndRoot() {
        // DEF-012 regression: root "" included for deeply nested resources
        Set<String> resources = new HashSet<>(Collections.singletonList("A/B/C/file.process"));
        Set<String> prefixes = Bw5ModuleMojo.LibBuilderFilter.buildDirPrefixes(resources);
        assertTrue(prefixes.contains("A/B/C"));
        assertTrue(prefixes.contains("A/B"));
        assertTrue(prefixes.contains("A"));
        assertTrue("Root '' must always be included (DEF-012 fix)", prefixes.contains(""));
    }

    // -----------------------------------------------------------------------
    //  shouldInclude — no .libbuilder (includedResources == null)
    // -----------------------------------------------------------------------

    @Test
    public void shouldIncludeReturnsTrueForEverythingWithNoLibBuilder() {
        assertTrue(Bw5ModuleMojo.LibBuilderFilter.shouldInclude("any/file.process", "file.process", null, null));
        assertTrue(Bw5ModuleMojo.LibBuilderFilter.shouldInclude(".folder", ".folder", null, null));
        assertTrue(Bw5ModuleMojo.LibBuilderFilter.shouldInclude("sub/.folder", ".folder", null, null));
    }

    // -----------------------------------------------------------------------
    //  shouldInclude — .substvar always included
    // -----------------------------------------------------------------------

    @Test
    public void shouldIncludeAlwaysIncludesSubstvar() {
        Set<String> resources = Collections.emptySet();
        Set<String> prefixes = Collections.emptySet();
        assertTrue(Bw5ModuleMojo.LibBuilderFilter.shouldInclude("defaultVars.substvar", "defaultVars.substvar", resources, prefixes));
        assertTrue(Bw5ModuleMojo.LibBuilderFilter.shouldInclude("sub/defaultVars.substvar", "defaultVars.substvar", resources, prefixes));
    }

    // -----------------------------------------------------------------------
    //  shouldInclude — root .folder (the bug case)
    // -----------------------------------------------------------------------

    @Test
    public void shouldIncludeRootFolderWhenRootResourceIsListed() {
        Set<String> resources = new HashSet<>(Collections.singletonList("MyProcess.process"));
        Set<String> prefixes = Bw5ModuleMojo.LibBuilderFilter.buildDirPrefixes(resources);
        assertTrue("Root .folder must be included when root resources are listed",
            Bw5ModuleMojo.LibBuilderFilter.shouldInclude(".folder", ".folder", resources, prefixes));
    }

    @Test
    public void shouldIncludeRootFolderEvenWhenNoRootResourceIsListed() {
        // DEF-012 regression: root .folder must always be included because it carries BW project metadata
        Set<String> resources = new HashSet<>(Collections.singletonList("sub/MyProcess.process"));
        Set<String> prefixes = Bw5ModuleMojo.LibBuilderFilter.buildDirPrefixes(resources);
        assertTrue("Root .folder must be included even when no root-level resource is listed (DEF-012)",
            Bw5ModuleMojo.LibBuilderFilter.shouldInclude(".folder", ".folder", resources, prefixes));
    }

    // -----------------------------------------------------------------------
    //  shouldInclude — nested .folder
    // -----------------------------------------------------------------------

    @Test
    public void shouldIncludeNestedFolderWhenDirectoryHasListedResources() {
        Set<String> resources = new HashSet<>(Collections.singletonList("Common/MyProcess.process"));
        Set<String> prefixes = Bw5ModuleMojo.LibBuilderFilter.buildDirPrefixes(resources);
        assertTrue(Bw5ModuleMojo.LibBuilderFilter.shouldInclude("Common/.folder", ".folder", resources, prefixes));
    }

    @Test
    public void shouldExcludeNestedFolderWhenDirectoryHasNoListedResources() {
        Set<String> resources = new HashSet<>(Collections.singletonList("Other/MyProcess.process"));
        Set<String> prefixes = Bw5ModuleMojo.LibBuilderFilter.buildDirPrefixes(resources);
        assertFalse(Bw5ModuleMojo.LibBuilderFilter.shouldInclude("Common/.folder", ".folder", resources, prefixes));
    }

    // -----------------------------------------------------------------------
    //  shouldInclude — regular files
    // -----------------------------------------------------------------------

    @Test
    public void shouldIncludeListedFileOnly() {
        Set<String> resources = new HashSet<>(Arrays.asList("Common/MyProcess.process", "Other/Another.process"));
        Set<String> prefixes = Bw5ModuleMojo.LibBuilderFilter.buildDirPrefixes(resources);
        assertTrue(Bw5ModuleMojo.LibBuilderFilter.shouldInclude("Common/MyProcess.process", "MyProcess.process", resources, prefixes));
        assertFalse(Bw5ModuleMojo.LibBuilderFilter.shouldInclude("Common/Unlisted.process", "Unlisted.process", resources, prefixes));
    }
}

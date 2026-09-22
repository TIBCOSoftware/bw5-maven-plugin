package com.tibco.bw.maven.plugin.packaging;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Regression tests for DEF-022, DEF-023, and the projlib alias key bug.
 *
 * DEF-022: Background mode startup markers did not include the BW 5.16 format
 *   "BWENGINE-300002 Engine &lt;hostname&gt; started", causing the plugin to always
 *   exhaust the full startupWaitSeconds timeout instead of returning early.
 *   Root causes: missing marker AND a race between startBackgroundLogging and
 *   waitForStartup both reading from the same InputStream independently.
 *
 * DEF-023: registerShutdownHook was called in background mode, killing the engine
 *   when Maven exited and preventing developer use after "mvn bw5:run" returned.
 *   Fix: shutdown hook is registered only in foreground mode.
 */
public class RunBwMojoStartupTest {

    // -----------------------------------------------------------------------
    //  DEF-022: startup marker presence
    // -----------------------------------------------------------------------

    @Test
    public void startMarkersArrayIncludesBw516Format() {
        boolean found = false;
        for (String marker : RunBwMojo.START_MARKERS) {
            if ("BWENGINE-300002".equals(marker)) {
                found = true;
                break;
            }
        }
        assertTrue("START_MARKERS must contain BWENGINE-300002 for BW 5.16 compatibility", found);
    }

    // -----------------------------------------------------------------------
    //  DEF-022: detectStartupMarker logic
    // -----------------------------------------------------------------------

    @Test
    public void detectsMarkerForBw516EngineStart() {
        assertTrue("BW 5.16 startup line must be detected",
            RunBwMojo.detectStartupMarker("BWENGINE-300002 Engine myhost.example.com started"));
    }

    @Test
    public void detectsMarkerForBw516WithDifferentHostname() {
        assertTrue(RunBwMojo.detectStartupMarker("BWENGINE-300002 Engine WIN-ABC123 started"));
    }

    @Test
    public void detectsLegacyEngineInitializedMarker() {
        assertTrue(RunBwMojo.detectStartupMarker("-- Engine Initialized --"));
    }

    @Test
    public void detectsLegacyApplicationStartedMarker() {
        assertTrue(RunBwMojo.detectStartupMarker("[INFO] Application started"));
    }

    @Test
    public void detectsLegacyBusinessWorksStartedMarker() {
        assertTrue(RunBwMojo.detectStartupMarker("BusinessWorks started successfully"));
    }

    @Test
    public void noFalsePositiveForNormalEngineOutput() {
        assertFalse(RunBwMojo.detectStartupMarker("[INFO] Loading resources..."));
        assertFalse(RunBwMojo.detectStartupMarker("Connecting to domain..."));
        assertFalse(RunBwMojo.detectStartupMarker("Loading projlib: my-lib-1.0.0.projlib"));
        assertFalse(RunBwMojo.detectStartupMarker("BWENGINE-300001 Engine initializing"));
    }

    // -----------------------------------------------------------------------
    //  DEF-023: shutdown hook must not be registered in background mode
    // -----------------------------------------------------------------------

    @Test
    public void shutdownHookNotNeededInBackgroundMode() {
        assertFalse(
            "Shutdown hook must NOT be registered in background mode — engine must outlive Maven (PRD §4)",
            RunBwMojo.isShutdownHookNeeded(true));
    }

    @Test
    public void shutdownHookNeededInForegroundMode() {
        assertTrue(
            "Shutdown hook must be registered in foreground mode so Ctrl+C kills the engine",
            RunBwMojo.isShutdownHookNeeded(false));
    }

    // -----------------------------------------------------------------------
    //  Alias key formats
    //  bw5:run points the engine at the project source directory, so RepoLoader reads the
    //  project's .designtimelibs and asks bwengine.properties for each File Alias name declared
    //  there. The plugin cannot guess that name — designer-setup writes a Maven coordinate, an
    //  entry added by hand in Designer is a file path — so it reads them from the file. Guessing
    //  one form failed in both directions with BWENGINE-100088 "Library alias undefined".
    // -----------------------------------------------------------------------

    private static Artifact artifact(String groupId, String artifactId, String version, String type) {
        return new DefaultArtifact(groupId, artifactId, version, "compile", type, null,
            new DefaultArtifactHandler(type));
    }

    @Test
    public void projlibAliasKeyUsesFilenameFormat() {
        assertEquals("tibco.alias.ProjectLibrary2.projlib",
            RunBwMojo.projlibAliasKey("ProjectLibrary2"));
    }

    @Test
    public void projlibAliasKeyDoesNotContainMavenCoordinates() {
        String key = RunBwMojo.projlibAliasKey("MyLib");
        assertFalse("Alias key must not contain ':' (Maven GAV separator)", key.contains(":"));
    }

    @Test
    public void projlibAliasKeyEndsWithDotProjlib() {
        String key = RunBwMojo.projlibAliasKey("FooBar");
        assertTrue("Alias key must end with .projlib", key.endsWith(".projlib"));
    }

    @Test
    public void jarAliasKeyUsesFilenameFormat() {
        assertEquals("tibco.alias.util-1.0.0.jar",
            RunBwMojo.jarAliasKey(artifact("com.example", "util", "1.0.0", "jar")));
    }

    @Test
    public void aliasNameFromDesigntimeEntryUnescapesCoordinate() {
        assertEquals("com.example.plugins:CommonFramework:4.3.0:projlib",
            RunBwMojo.aliasNameFromDesigntimeEntry(
                "com.example.plugins\\:CommonFramework\\:4.3.0\\:projlib\\="));
    }

    @Test
    public void aliasNameFromDesigntimeEntryLeavesPlainPathAlone() {
        assertEquals("/opt/tibco/libs/CommonFramework.projlib",
            RunBwMojo.aliasNameFromDesigntimeEntry("/opt/tibco/libs/CommonFramework.projlib"));
    }

    /**
     * The exact failure Niels Lous reported: designer-setup had recorded the coordinate form in
     * .designtimelibs, the engine asked for that name, and only the filename alias was generated.
     */
    @Test
    public void designtimeAliasEntriesDefinesTheCoordinateNameTheEngineAsksFor() {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("CommonFramework", "/m2/CommonFramework-4.3.0.projlib");

        Map<String, String> aliases = RunBwMojo.designtimeAliasEntries(
            Collections.singletonList("com.example.plugins\\:CommonFramework\\:4.3.0\\:projlib\\="),
            paths);

        assertEquals(Collections.singletonMap(
            "tibco.alias.com.example.plugins:CommonFramework:4.3.0:projlib",
            "/m2/CommonFramework-4.3.0.projlib"), aliases);
    }

    @Test
    public void designtimeAliasEntriesHandlesPathStyleEntries() {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("CommonFramework", "/m2/CommonFramework-4.3.0.projlib");

        Map<String, String> aliases = RunBwMojo.designtimeAliasEntries(
            Collections.singletonList("/home/dev/libs/CommonFramework.projlib"), paths);

        assertEquals("/m2/CommonFramework-4.3.0.projlib",
            aliases.get("tibco.alias./home/dev/libs/CommonFramework.projlib"));
    }

    @Test
    public void designtimeAliasEntriesSkipsLibrariesThatAreNotMavenDependencies() {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("CommonFramework", "/m2/CommonFramework-4.3.0.projlib");

        Map<String, String> aliases = RunBwMojo.designtimeAliasEntries(
            Arrays.asList("com.example\\:CommonFramework\\:4.3.0\\:projlib\\=",
                          "com.example\\:SomeOtherLib\\:1.0.0\\:projlib\\="),
            paths);

        assertEquals("No path is known for SomeOtherLib, so no alias can be defined for it",
            1, aliases.size());
    }

    @Test
    public void designtimeAliasEntriesReturnsEmptyWhenNothingIsDeclared() {
        assertTrue(RunBwMojo.designtimeAliasEntries(
            Collections.<String>emptyList(), Collections.<String, String>emptyMap()).isEmpty());
    }
}

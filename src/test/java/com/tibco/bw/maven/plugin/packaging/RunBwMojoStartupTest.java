package com.tibco.bw.maven.plugin.packaging;

import org.junit.Test;

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
    //  Projlib alias key format
    //  Root cause: key was "tibco.alias.groupId:artifactId:version:projlib" (Maven GAV)
    //  but the BW5 engine looks up libraries as "tibco.alias.artifactId.projlib" (filename).
    // -----------------------------------------------------------------------

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
}

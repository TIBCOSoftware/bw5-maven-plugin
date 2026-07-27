package com.tibco.bw.maven.plugin.designer;

import org.junit.Test;

import java.io.StringReader;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for Designer5.prefs File-Alias path escaping.
 *
 * <p>The .prefs file is a Java properties-format file. On Windows the staged-lib paths contain
 * backslashes (e.g. {@code C:\Users\...\target\designer-libs\x.jar}); if written raw, Designer's
 * properties un-escaping mangles them ({@code \t} -> TAB, {@code \U} -> the backslash is dropped),
 * breaking every File Alias. Backslashes must be doubled so the original path round-trips.</p>
 */
public class PullMojoTest {

    @Test
    public void escapesWindowsBackslashes() {
        String win = "C:\\Users\\Pandeyki\\Downloads\\proj\\target\\designer-libs\\commons-io-2.6.jar";
        assertEquals(
            "C:\\\\Users\\\\Pandeyki\\\\Downloads\\\\proj\\\\target\\\\designer-libs\\\\commons-io-2.6.jar",
            PullMojo.escapePrefsPath(win));
    }

    @Test
    public void posixPathUnchanged() {
        String posix = "/home/user/proj/target/designer-libs/commons-io-2.6.jar";
        assertEquals(posix, PullMojo.escapePrefsPath(posix));
    }

    /** The escaped value must round-trip back to the exact Windows path through Properties.load(). */
    @Test
    public void escapedPathRoundTripsThroughProperties() throws Exception {
        String win = "C:\\Users\\Pandeyki\\Downloads\\proj\\target\\designer-libs\\x.jar";
        String line = "filealias.pref.0=grp:art:1.0:jar\\=" + PullMojo.escapePrefsPath(win);

        Properties p = new Properties();
        p.load(new StringReader(line));
        String value = p.getProperty("filealias.pref.0");

        assertNotNull(value);
        assertTrue("path must be preserved verbatim", value.endsWith("=" + win));
        assertTrue("real backslashes retained", value.contains("\\target\\designer-libs\\"));
        assertFalse("\\t must NOT have become a tab", value.indexOf('\t') >= 0);
    }

    // -----------------------------------------------------------------------
    //  groupArtifactKey — used to drop stale .designtimelibs version duplicates
    // -----------------------------------------------------------------------

    @Test
    public void groupArtifactKeyStripsVersionAndType() {
        assertEquals("com.ceva.eai.generic.plugins:CommonFramework",
            PullMojo.groupArtifactKey("com.ceva.eai.generic.plugins:CommonFramework:4.5.0:projlib"));
        assertEquals("com.ceva.eai.generic.plugins:CommonFramework",
            PullMojo.groupArtifactKey("com.ceva.eai.generic.plugins:CommonFramework:4.1.0:projlib"));
    }

    @Test
    public void groupArtifactKeyIdentifiesStaleDuplicate() {
        // A leftover 4.5.0 entry shares the group:artifact key of the managed 4.1.0 entry,
        // so it is recognised as a stale duplicate and dropped.
        String managed = "com.ceva.eai.generic.plugins:CommonFramework:4.1.0:projlib";
        String stale   = "com.ceva.eai.generic.plugins:CommonFramework:4.5.0:projlib";
        assertEquals(PullMojo.groupArtifactKey(managed), PullMojo.groupArtifactKey(stale));
    }
}

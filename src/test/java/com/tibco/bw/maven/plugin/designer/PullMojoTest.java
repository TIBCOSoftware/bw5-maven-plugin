package com.tibco.bw.maven.plugin.designer;

import org.junit.Test;

import java.io.StringReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
        String win = "C:\\Users\\dev\\Downloads\\proj\\target\\designer-libs\\commons-io-2.6.jar";
        assertEquals(
            "C:\\\\Users\\\\dev\\\\Downloads\\\\proj\\\\target\\\\designer-libs\\\\commons-io-2.6.jar",
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
        String win = "C:\\Users\\dev\\Downloads\\proj\\target\\designer-libs\\x.jar";
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
        assertEquals("com.example.libs:SharedLib",
            PullMojo.groupArtifactKey("com.example.libs:SharedLib:4.5.0:projlib"));
        assertEquals("com.example.libs:SharedLib",
            PullMojo.groupArtifactKey("com.example.libs:SharedLib:4.1.0:projlib"));
    }

    @Test
    public void groupArtifactKeyIdentifiesStaleDuplicate() {
        // A leftover 4.5.0 entry shares the group:artifact key of the managed 4.1.0 entry,
        // so it is recognised as a stale duplicate and dropped.
        String managed = "com.example.libs:SharedLib:4.1.0:projlib";
        String stale   = "com.example.libs:SharedLib:4.5.0:projlib";
        assertEquals(PullMojo.groupArtifactKey(managed), PullMojo.groupArtifactKey(stale));
    }

    // -----------------------------------------------------------------------
    //  injectClasspath — prepend staged JARs to designer.tra CUSTOM_CP_EXT
    //  (the design-time Java classpath the TRA launcher builds -Djava.class.path from)
    // -----------------------------------------------------------------------

    @Test
    public void injectClasspathPrependsToExistingCustomCpExt() {
        List<String> tra = Arrays.asList(
            "tibco.env.TIB_HOME /opt/tibco",
            "tibco.env.CUSTOM_CP_EXT /opt/tibco/bw/5.16/lib:/existing.jar",
            "tibco.class.path.extended %CUSTOM_CP_EXT%:%STD_CP_EXT%");
        List<String> out = PullMojo.injectClasspath(
            tra, Arrays.asList("/libs/InterfacesLib.jar", "/libs/SharedLib.jar"), ":");

        assertEquals(3, out.size());
        assertEquals(
            "tibco.env.CUSTOM_CP_EXT /libs/InterfacesLib.jar:/libs/SharedLib.jar:/opt/tibco/bw/5.16/lib:/existing.jar",
            out.get(1));
        // untouched lines preserved
        assertEquals("tibco.env.TIB_HOME /opt/tibco", out.get(0));
        assertEquals("tibco.class.path.extended %CUSTOM_CP_EXT%:%STD_CP_EXT%", out.get(2));
    }

    @Test
    public void injectClasspathAddsCustomCpExtWhenAbsent() {
        List<String> tra = Collections.singletonList("tibco.env.TIB_HOME /opt/tibco");
        List<String> out = PullMojo.injectClasspath(tra, Arrays.asList("/libs/a.jar"), ":");
        assertEquals(2, out.size());
        assertEquals("tibco.env.CUSTOM_CP_EXT /libs/a.jar", out.get(1));
    }

    @Test
    public void injectClasspathHonorsWindowsPathSeparator() {
        // Injected paths get their backslashes doubled (the TRA parser un-escapes them, like Java
        // properties); the pre-existing value is preserved verbatim (TIBCO already stores it escaped).
        List<String> tra = Collections.singletonList("tibco.env.CUSTOM_CP_EXT C:\\\\tibco\\\\lib");
        List<String> out = PullMojo.injectClasspath(
            tra, Arrays.asList("C:\\libs\\a.jar", "C:\\libs\\b.jar"), ";");
        assertEquals(
            "tibco.env.CUSTOM_CP_EXT C:\\\\libs\\\\a.jar;C:\\\\libs\\\\b.jar;C:\\\\tibco\\\\lib",
            out.get(0));
    }

    /** A backslash sequence like {@code \t} must not survive un-escaped (would become a TAB). */
    @Test
    public void injectClasspathDoublesBackslashesSoTheyRoundTrip() throws Exception {
        List<String> out = PullMojo.injectClasspath(
            Collections.<String>emptyList(),
            Collections.singletonList("C:\\Users\\dev\\target\\designer-libs\\x.jar"),
            ";");
        String value = out.get(0).substring("tibco.env.CUSTOM_CP_EXT ".length());
        assertFalse("no raw single backslash left", value.matches(".*[^\\\\]\\\\[^\\\\].*"));

        // Round-trip through Properties (the un-escaping the TRA launcher performs) yields the path back.
        Properties p = new Properties();
        p.load(new StringReader("cp=" + value));
        assertEquals("C:\\Users\\dev\\target\\designer-libs\\x.jar", p.getProperty("cp"));
    }

    // -----------------------------------------------------------------------
    //  Generated designer.tra: classpath + user.home redirection
    // -----------------------------------------------------------------------

    private static final List<String> DESIGNER_TRA = Arrays.asList(
        "# designer.tra",
        "tibco.env.DESIGNER_HOME /opt/tibco/designer/5.13",
        "tibco.env.CUSTOM_CP_EXT /opt/tibco/designer/5.13/lib",
        "java.property.user.dir %DESIGNER_HOME%");

    /**
     * Without {@code -Duser.home=<target>} Designer reads {@code ~/.TIBCO/Designer5.prefs} and none
     * of the generated File Aliases apply. The mojo passes it through JAVA_TOOL_OPTIONS when it
     * launches Designer itself, but the tra has to carry it too for hand-started Designers.
     */
    @Test
    public void designerTraPointsUserHomeAtTheBuildDirectory() {
        List<String> out = PullMojo.buildDesignerTra(
            DESIGNER_TRA, Collections.<String>emptyList(), "/proj/target", false, ":");

        assertTrue("user.home must be added",
            out.contains("java.property.user.home /proj/target"));
        assertEquals("the entry must blend in with the java.property. style of the file",
            4, out.indexOf("java.property.user.home /proj/target"));
    }

    /** user.dir is opt-in: the installed value (%DESIGNER_HOME%) drives every relative path. */
    @Test
    public void designerTraLeavesUserDirAloneByDefault() {
        List<String> out = PullMojo.buildDesignerTra(
            DESIGNER_TRA, Collections.<String>emptyList(), "/proj/target", false, ":");
        assertTrue("user.dir must keep the installed value",
            out.contains("java.property.user.dir %DESIGNER_HOME%"));
    }

    @Test
    public void designerTraOverridesUserDirWhenOptedIn() {
        List<String> out = PullMojo.buildDesignerTra(
            DESIGNER_TRA, Collections.<String>emptyList(), "/proj/target", true, ":");
        assertTrue(out.contains("java.property.user.dir /proj/target"));
        assertFalse("the entry must be replaced, not duplicated",
            out.contains("java.property.user.dir %DESIGNER_HOME%"));
    }

    /** A projlib-only project stages no JARs but still needs the preferences redirection. */
    @Test
    public void designerTraIsStillUsefulWithoutStagedJars() {
        List<String> out = PullMojo.buildDesignerTra(
            DESIGNER_TRA, Collections.<String>emptyList(), "/proj/target", false, ":");

        assertEquals("only the user.home line may be added", DESIGNER_TRA.size() + 1, out.size());
        assertEquals("the classpath must be left untouched when there is nothing to add",
            "tibco.env.CUSTOM_CP_EXT /opt/tibco/designer/5.13/lib", out.get(2));
    }

    @Test
    public void designerTraCombinesClasspathAndUserHome() {
        List<String> out = PullMojo.buildDesignerTra(
            DESIGNER_TRA, Arrays.asList("/proj/target/designer-libs/x.jar"), "/proj/target",
            false, ":");

        assertEquals("tibco.env.CUSTOM_CP_EXT /proj/target/designer-libs/x.jar"
            + ":/opt/tibco/designer/5.13/lib", out.get(2));
        assertTrue(out.contains("java.property.user.home /proj/target"));
    }

    /** The build directory is a path, so it needs the same escaping as the classpath entries. */
    @Test
    public void designerTraEscapesAWindowsBuildDirectory() {
        List<String> out = PullMojo.buildDesignerTra(
            DESIGNER_TRA, Collections.<String>emptyList(), "C:\\Users\\dev\\proj\\target",
            false, ";");
        assertTrue(out.toString(),
            out.contains("java.property.user.home C:\\\\Users\\\\dev\\\\proj\\\\target"));
    }

    @Test
    public void designerTraDoesNotMutateTheInstalledLines() {
        List<String> base = new java.util.ArrayList<>(DESIGNER_TRA);
        PullMojo.buildDesignerTra(base, Arrays.asList("/x.jar"), "/proj/target", true, ":");
        assertEquals(DESIGNER_TRA, base);
    }
}

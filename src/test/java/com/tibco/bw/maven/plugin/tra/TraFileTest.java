package com.tibco.bw.maven.plugin.tra;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the shared TRA read/modify/write helper.
 *
 * <p>The two files this helper has to handle do not agree on syntax: {@code designer.tra} (5.13)
 * writes every {@code tibco.env.*} entry as {@code key<space>value}, while {@code bwengine.tra}
 * (5.16) writes most of them as {@code key=value} and a few with a space. Reading only one of the
 * two forms means the classpath line is not found, a duplicate is appended, and the TIBCO hotfix /
 * bouncycastle / tibrv entries silently drop off the engine classpath.</p>
 */
public class TraFileTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String ENGINE_CP = "tibco.env.CUSTOM_EXT_PREPEND_CP";
    private static final String DESIGNER_CP = "tibco.env.CUSTOM_CP_EXT";

    // -----------------------------------------------------------------------
    //  separatorIndex — both syntaxes, and prefix-collision safety
    // -----------------------------------------------------------------------

    @Test
    public void separatorIndexFindsSpaceForm() {
        assertEquals(DESIGNER_CP.length(),
            TraFile.separatorIndex("tibco.env.CUSTOM_CP_EXT /opt/tibco", DESIGNER_CP));
    }

    @Test
    public void separatorIndexFindsEqualsForm() {
        assertEquals(ENGINE_CP.length(),
            TraFile.separatorIndex("tibco.env.CUSTOM_EXT_PREPEND_CP=/opt/tibco", ENGINE_CP));
    }

    @Test
    public void separatorIndexRejectsLongerKeyWithSamePrefix() {
        assertEquals(-1,
            TraFile.separatorIndex("tibco.env.CUSTOM_CP_EXT_OLD /opt/tibco", DESIGNER_CP));
    }

    @Test
    public void separatorIndexRejectsUnrelatedAndBareKeyLines() {
        assertEquals(-1,
            TraFile.separatorIndex("tibco.class.path.extended %CUSTOM_CP_EXT%", DESIGNER_CP));
        assertEquals(-1, TraFile.separatorIndex(DESIGNER_CP, DESIGNER_CP));
        assertEquals(-1, TraFile.separatorIndex(null, DESIGNER_CP));
    }

    // -----------------------------------------------------------------------
    //  detectSeparator — an added entry must blend in with the file it came from
    // -----------------------------------------------------------------------

    @Test
    public void detectSeparatorPicksEqualsForBwengineStyleFile() {
        List<String> tra = Arrays.asList(
            "tibco.env.TIB_HOME=/opt/tibco",
            "tibco.env.BW_HOME=/opt/tibco/bw/5.16",
            "tibco.env.CUSTOM_PATH /opt/tibco/hawk/6.3/bin");
        assertEquals("=", TraFile.detectSeparator(tra, "tibco.env."));
    }

    @Test
    public void detectSeparatorPicksSpaceForDesignerStyleFile() {
        List<String> tra = Arrays.asList(
            "tibco.env.TIB_HOME /opt/tibco",
            "tibco.env.DESIGNER_HOME /opt/tibco/designer/5.13");
        assertEquals(" ", TraFile.detectSeparator(tra, "tibco.env."));
    }

    /** No evidence at all: fall back to the space form, the more common TRA style. */
    @Test
    public void detectSeparatorDefaultsToSpaceWithoutEvidence() {
        assertEquals(" ", TraFile.detectSeparator(Collections.<String>emptyList(), "tibco.env."));
        assertEquals(" ",
            TraFile.detectSeparator(Collections.singletonList("# just a comment"), "tibco.env."));
    }

    /** The sample is restricted to the key's own family, so java.property.* is not skewed by env. */
    @Test
    public void detectSeparatorSamplesOnlyTheGivenFamily() {
        List<String> tra = Arrays.asList(
            "tibco.env.A=1",
            "tibco.env.B=2",
            "java.property.user.dir %DESIGNER_HOME%");
        assertEquals("=", TraFile.detectSeparator(tra, "tibco.env."));
        assertEquals(" ", TraFile.detectSeparator(tra, "java.property."));
    }

    // -----------------------------------------------------------------------
    //  injectClasspath — the bwengine.tra "=" case Niels' report exposed
    // -----------------------------------------------------------------------

    @Test
    public void injectClasspathRewritesEqualsFormInPlace() {
        List<String> tra = Arrays.asList(
            "tibco.env.CUSTOM_EXT_PREPEND_CP=/opt/tibco/bw/5.16/hotfix/lib:/opt/tibco/tibrv/8.8/lib",
            "tibco.class.path.extended %CUSTOM_EXT_PREPEND_CP%:%STD_EXT_CP%");
        List<String> out = TraFile.injectClasspath(
            tra, ENGINE_CP, Arrays.asList("/m2/a.jar", "/m2/b.jar"), ":");

        assertEquals("no duplicate line may be appended", 2, out.size());
        assertEquals("tibco.env.CUSTOM_EXT_PREPEND_CP=/m2/a.jar:/m2/b.jar:"
            + "/opt/tibco/bw/5.16/hotfix/lib:/opt/tibco/tibrv/8.8/lib", out.get(0));
        assertEquals("tibco.class.path.extended %CUSTOM_EXT_PREPEND_CP%:%STD_EXT_CP%", out.get(1));
    }

    /** The separator of the rewritten entry is the one it already used, not a normalised one. */
    @Test
    public void injectClasspathPreservesTheExistingSeparator() {
        List<String> spaceForm = TraFile.injectClasspath(
            Collections.singletonList("tibco.env.CUSTOM_EXT_PREPEND_CP /opt/lib"),
            ENGINE_CP, Collections.singletonList("/m2/a.jar"), ":");
        assertEquals("tibco.env.CUSTOM_EXT_PREPEND_CP /m2/a.jar:/opt/lib", spaceForm.get(0));

        List<String> equalsForm = TraFile.injectClasspath(
            Collections.singletonList("tibco.env.CUSTOM_EXT_PREPEND_CP=/opt/lib"),
            ENGINE_CP, Collections.singletonList("/m2/a.jar"), ":");
        assertEquals("tibco.env.CUSTOM_EXT_PREPEND_CP=/m2/a.jar:/opt/lib", equalsForm.get(0));
    }

    @Test
    public void injectClasspathAddsEntryInTheFilesOwnStyleWhenAbsent() {
        List<String> engineStyle = TraFile.injectClasspath(
            Arrays.asList("tibco.env.TIB_HOME=/opt/tibco", "tibco.env.BW_HOME=/opt/tibco/bw"),
            ENGINE_CP, Collections.singletonList("/m2/a.jar"), ":");
        assertEquals("tibco.env.CUSTOM_EXT_PREPEND_CP=/m2/a.jar", engineStyle.get(2));

        List<String> designerStyle = TraFile.injectClasspath(
            Collections.singletonList("tibco.env.TIB_HOME /opt/tibco"),
            DESIGNER_CP, Collections.singletonList("/m2/a.jar"), ":");
        assertEquals("tibco.env.CUSTOM_CP_EXT /m2/a.jar", designerStyle.get(1));
    }

    @Test
    public void injectClasspathRewritesOnlyTheFirstOccurrence() {
        List<String> tra = Arrays.asList(
            "tibco.env.CUSTOM_CP_EXT /first",
            "tibco.env.CUSTOM_CP_EXT /second");
        List<String> out = TraFile.injectClasspath(
            tra, DESIGNER_CP, Collections.singletonList("/m2/a.jar"), ":");
        assertEquals("tibco.env.CUSTOM_CP_EXT /m2/a.jar:/first", out.get(0));
        assertEquals("tibco.env.CUSTOM_CP_EXT /second", out.get(1));
    }

    @Test
    public void injectClasspathHandlesAnEmptyExistingValue() {
        List<String> out = TraFile.injectClasspath(
            Collections.singletonList("tibco.env.CUSTOM_CP_EXT="),
            DESIGNER_CP, Collections.singletonList("/m2/a.jar"), ":");
        assertEquals("tibco.env.CUSTOM_CP_EXT=/m2/a.jar", out.get(0));
    }

    @Test
    public void injectClasspathDoesNotMutateTheInputList() {
        List<String> tra = new ArrayList<>(Collections.singletonList("tibco.env.CUSTOM_CP_EXT /opt"));
        TraFile.injectClasspath(tra, DESIGNER_CP, Collections.singletonList("/m2/a.jar"), ":");
        assertEquals(1, tra.size());
        assertEquals("tibco.env.CUSTOM_CP_EXT /opt", tra.get(0));
    }

    // -----------------------------------------------------------------------
    //  Windows escaping
    // -----------------------------------------------------------------------

    @Test
    public void escapePathDoublesBackslashesAndLeavesPosixAlone() {
        assertEquals("C:\\\\tibco\\\\lib", TraFile.escapePath("C:\\tibco\\lib"));
        assertEquals("/opt/tibco/lib", TraFile.escapePath("/opt/tibco/lib"));
        assertEquals("", TraFile.escapePath(null));
    }

    /** Injected Windows paths must round-trip through the launcher's properties-style un-escaping. */
    @Test
    public void injectedWindowsPathsRoundTripThroughPropertiesUnescaping() throws Exception {
        List<String> out = TraFile.injectClasspath(
            Collections.singletonList("tibco.env.CUSTOM_EXT_PREPEND_CP=C:\\\\tibco\\\\lib"),
            ENGINE_CP, Collections.singletonList("C:\\Users\\dev\\target\\bw-lib"), ";");
        String value = out.get(0).substring((ENGINE_CP + "=").length());

        Properties p = new Properties();
        p.load(new StringReader("cp=" + value));
        assertTrue("injected path must survive un-escaping",
            p.getProperty("cp").startsWith("C:\\Users\\dev\\target\\bw-lib;"));
        assertFalse("\\t must NOT have become a tab", p.getProperty("cp").indexOf('\t') >= 0);
    }

    /** A pre-existing value is already escaped by TIBCO and must be copied through untouched. */
    @Test
    public void injectClasspathPreservesTheExistingValueVerbatim() {
        List<String> out = TraFile.injectClasspath(
            Collections.singletonList("tibco.env.CUSTOM_CP_EXT C:\\\\tibco\\\\lib"),
            DESIGNER_CP, Collections.singletonList("C:\\libs\\a.jar"), ";");
        assertEquals("tibco.env.CUSTOM_CP_EXT C:\\\\libs\\\\a.jar;C:\\\\tibco\\\\lib", out.get(0));
    }

    // -----------------------------------------------------------------------
    //  setProperty
    // -----------------------------------------------------------------------

    @Test
    public void setPropertyReplacesExistingEntryKeepingItsSeparator() {
        List<String> out = TraFile.setProperty(
            Arrays.asList("java.property.user.dir %DESIGNER_HOME%", "tibco.env.TIB_HOME /opt/tibco"),
            "java.property.user.dir", "/proj/target");
        assertEquals(2, out.size());
        assertEquals("java.property.user.dir /proj/target", out.get(0));
        assertEquals("tibco.env.TIB_HOME /opt/tibco", out.get(1));
    }

    @Test
    public void setPropertyAppendsWhenAbsentUsingTheFamilyStyle() {
        List<String> out = TraFile.setProperty(
            Collections.singletonList("java.property.user.dir %DESIGNER_HOME%"),
            "java.property.user.home", "/proj/target");
        assertEquals(2, out.size());
        assertEquals("java.property.user.home /proj/target", out.get(1));
    }

    @Test
    public void setPropertyDoesNotTouchACommentedOutEntry() {
        List<String> out = TraFile.setProperty(
            Collections.singletonList("#java.property.user.home=/old"),
            "java.property.user.home", "/proj/target");
        assertEquals("#java.property.user.home=/old", out.get(0));
        assertEquals("java.property.user.home /proj/target", out.get(1));
    }

    @Test
    public void setPropertyDoesNotMutateTheInputList() {
        List<String> tra = new ArrayList<>(Collections.singletonList("java.property.user.dir /old"));
        TraFile.setProperty(tra, "java.property.user.dir", "/new");
        assertEquals("java.property.user.dir /old", tra.get(0));
    }

    // -----------------------------------------------------------------------
    //  read / write round-trip
    // -----------------------------------------------------------------------

    @Test
    public void readWriteRoundTripPreservesContent() throws Exception {
        List<String> lines = Arrays.asList(
            "# comment",
            "tibco.env.CUSTOM_EXT_PREPEND_CP=/opt/lib",
            "java.property.user.dir %DESIGNER_HOME%",
            "");
        File f = tmp.newFile("bwengine.tra");
        TraFile.write(f, lines);
        assertEquals(lines, TraFile.read(f));
    }
}

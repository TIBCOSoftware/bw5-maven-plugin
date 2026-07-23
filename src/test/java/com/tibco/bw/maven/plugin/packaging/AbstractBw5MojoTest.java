package com.tibco.bw.maven.plugin.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the shared BW source-directory resolution used by every goal, so the
 * {@code bwProjectPath} property is honored consistently across all mojos.
 */
public class AbstractBw5MojoTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Minimal concrete subclass to exercise the protected resolver. */
    private static final class TestMojo extends AbstractBw5Mojo {
        @Override public void execute() { /* no-op */ }
        void setDirs(File projectPath, File sourcesDir) {
            this.bwProjectPath = projectPath;
            this.bwSourcesDirectory = sourcesDir;
        }
    }

    @Test
    public void usesStagedSourcesWhenPresent() throws Exception {
        File project = tmp.newFolder("bwproject");
        File staged = tmp.newFolder("target", "bw-src"); // exists
        TestMojo m = new TestMojo();
        m.setDirs(project, staged);
        assertEquals(staged, m.getEffectiveSourceDir());
    }

    @Test
    public void fallsBackToProjectPathWhenNoStagedSources() throws Exception {
        File project = tmp.newFolder("bwproject2");
        File staged = new File(tmp.getRoot(), "target/bw-src-missing"); // does NOT exist
        TestMojo m = new TestMojo();
        m.setDirs(project, staged);
        assertEquals(project, m.getEffectiveSourceDir());
    }

    @Test
    public void fallsBackToProjectPathWhenStagedDirIsNull() throws Exception {
        File project = tmp.newFolder("bwproject3");
        TestMojo m = new TestMojo();
        m.setDirs(project, null);
        assertEquals(project, m.getEffectiveSourceDir());
    }

    // -----------------------------------------------------------------------
    //  resolveBwSourceDir — auto-detect the BW project root (vcrepo.dat)
    // -----------------------------------------------------------------------

    @Test
    public void usesProjectPathWhenItHasVcrepo() throws Exception {
        File project = tmp.newFolder("proj-with-vcrepo");
        new File(project, "vcrepo.dat").createNewFile();
        TestMojo m = new TestMojo();
        m.setDirs(project, null);
        assertEquals(project, m.resolveBwSourceDir());
    }

    @Test
    public void autoDetectsBwProjectUnderSrcMainTibco() throws Exception {
        // pom at module root; real BW project under src/main/tibco/<name> (has vcrepo.dat)
        File moduleRoot = tmp.newFolder("module");
        File bwProject = new File(moduleRoot, "src/main/tibco/MyApp");
        assertTrue(bwProject.mkdirs());
        new File(bwProject, "vcrepo.dat").createNewFile();
        TestMojo m = new TestMojo();
        m.setDirs(moduleRoot, null);
        assertEquals(bwProject.getCanonicalFile(), m.resolveBwSourceDir().getCanonicalFile());
    }

    @Test
    public void returnsProjectPathWhenNoVcrepoAnywhere() throws Exception {
        File moduleRoot = tmp.newFolder("module-none");
        new File(moduleRoot, "src/main/bw").mkdirs(); // no vcrepo.dat
        TestMojo m = new TestMojo();
        m.setDirs(moduleRoot, null);
        assertEquals(moduleRoot, m.resolveBwSourceDir());
    }
}

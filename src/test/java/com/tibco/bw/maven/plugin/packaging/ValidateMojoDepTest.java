package com.tibco.bw.maven.plugin.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Regression tests for DEF-019 and DEF-020.
 *
 * DEF-019: DEP check must work without Maven framework-level dependency resolution.
 *   Root cause: RequiresDepResolution=COMPILE_PLUS_RUNTIME caused Maven to abort
 *   before the goal ran when a projlib dep was absent. Fix: NONE scope +
 *   local-repo path check via project.getDependencies().
 *
 * DEF-020: bw5:validate must be bound to the verify phase in the bwear lifecycle.
 *   Root cause: components.xml had no <verify> entry for the bwear lifecycle mapping.
 */
public class ValidateMojoDepTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // -----------------------------------------------------------------------
    //  DEF-019: localRepoArtifactPath path computation
    // -----------------------------------------------------------------------

    @Test
    public void localRepoPathBuildsCorrectPathForProjlib() {
        File path = ValidateMojo.localRepoArtifactPath(
            "/home/user/.m2/repository",
            "com.example", "my-lib", "1.0.0", "projlib"
        );
        assertEquals("my-lib-1.0.0.projlib", path.getName());
        String p = path.getPath().replace('\\', '/');
        assertTrue(p.contains("com/example/my-lib/1.0.0/my-lib-1.0.0.projlib"));
    }

    @Test
    public void localRepoPathBuildsCorrectPathForJar() {
        File path = ValidateMojo.localRepoArtifactPath(
            "/repo",
            "org.apache.commons", "commons-lang3", "3.12.0", "jar"
        );
        assertEquals("commons-lang3-3.12.0.jar", path.getName());
        String p = path.getPath().replace('\\', '/');
        assertTrue(p.contains("org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar"));
    }

    @Test
    public void localRepoPathHandlesMultiSegmentGroupId() {
        File path = ValidateMojo.localRepoArtifactPath(
            "/repo",
            "com.tibco.bw.maven", "framework-common", "2.0.0", "projlib"
        );
        String p = path.getPath().replace('\\', '/');
        assertTrue(p.contains("com/tibco/bw/maven/framework-common/2.0.0/framework-common-2.0.0.projlib"));
    }

    // -----------------------------------------------------------------------
    //  DEF-019: validateDependencies reports present and absent artifacts
    // -----------------------------------------------------------------------

    @Test
    public void validateDependenciesReportsMissingProjlibAsDEPError() throws Exception {
        // Simulate a local repo with one projlib present and one absent
        File repoBase = tmp.newFolder("repo");

        // "present-lib" — create the file in the expected local repo location
        File presentDir = new File(repoBase, "com/example/present-lib/1.0.0".replace('/', File.separatorChar));
        presentDir.mkdirs();
        new File(presentDir, "present-lib-1.0.0.projlib").createNewFile();

        // "missing-lib" — intentionally absent from the local repo

        ValidateMojoDepTestHelper mojo = new ValidateMojoDepTestHelper(repoBase.getAbsolutePath());
        mojo.addDependency("com.example", "present-lib", "1.0.0", "projlib", "compile");
        mojo.addDependency("com.example", "missing-lib", "2.0.0", "projlib", "compile");

        List<ValidateMojo.Issue> issues = new ArrayList<>();
        mojo.validateDependencies(issues);

        assertEquals("Expected exactly one DEP issue (missing projlib)", 1, issues.size());
        ValidateMojo.Issue issue = issues.get(0);
        assertEquals("DEP", issue.code);
        assertEquals(ValidateMojo.Severity.ERROR, issue.severity);
        assertTrue("Issue message must name the missing artifact",
            issue.message.contains("missing-lib"));
    }

    @Test
    public void validateDependenciesSkipsTestScopeDependencies() throws Exception {
        File repoBase = tmp.newFolder("repo2");

        ValidateMojoDepTestHelper mojo = new ValidateMojoDepTestHelper(repoBase.getAbsolutePath());
        // test-scope dep that doesn't exist — must not be reported
        mojo.addDependency("com.example", "test-only-lib", "1.0.0", "jar", "test");

        List<ValidateMojo.Issue> issues = new ArrayList<>();
        mojo.validateDependencies(issues);

        assertTrue("test-scope dependencies must not generate DEP issues", issues.isEmpty());
    }

    @Test
    public void validateDependenciesSkipsProvidedScopeDependencies() throws Exception {
        File repoBase = tmp.newFolder("repo3");

        ValidateMojoDepTestHelper mojo = new ValidateMojoDepTestHelper(repoBase.getAbsolutePath());
        mojo.addDependency("com.tibco", "tibco-engine", "5.14.0", "jar", "provided");

        List<ValidateMojo.Issue> issues = new ArrayList<>();
        mojo.validateDependencies(issues);

        assertTrue("provided-scope dependencies must not generate DEP issues", issues.isEmpty());
    }

    // -----------------------------------------------------------------------
    //  DEF-020: bwear lifecycle must bind validate to the verify phase
    // -----------------------------------------------------------------------

    @Test
    public void bwearLifecycleDescriptorBindsValidateToVerifyPhase() throws Exception {
        // Read the components.xml from the classpath (packed into the plugin jar)
        InputStream is = getClass().getClassLoader()
            .getResourceAsStream("META-INF/plexus/components.xml");
        assertNotNull("components.xml must be on classpath", is);

        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            sb.append(new String(buf, 0, n, "UTF-8"));
        }
        is.close();

        String xml = sb.toString();
        assertTrue(
            "bwear lifecycle must bind bw5:validate to the verify phase (DEF-020)",
            xml.contains("<verify>com.tibco.bw:bw5-maven-plugin:validate</verify>")
        );
    }
}

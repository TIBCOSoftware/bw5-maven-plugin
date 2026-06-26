package com.tibco.bw.maven.plugin.descriptor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.*;

public class PropertyMergerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // --- helpers ---

    private List<SubstVarParser.GlobalVariable> oneVar(String name, String value) {
        SubstVarParser.GlobalVariable v = new SubstVarParser.GlobalVariable();
        v.name  = name;
        v.value = value;
        return java.util.Arrays.asList(v);
    }

    private File propsFile(String... pairs) throws IOException {
        Properties p = new Properties();
        for (int i = 0; i < pairs.length; i += 2) {
            p.setProperty(pairs[i], pairs[i + 1]);
        }
        File f = tmp.newFile();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            p.store(fos, null);
        }
        return f;
    }

    // -----------------------------------------------------------------------
    //  DEF-015: bw5.project.* CLI properties must override projectPropertiesFile
    // -----------------------------------------------------------------------

    @Test
    public void cliProjectPropertyOverridesProjectFile() throws Exception {
        // project.properties says Domain=file-value
        File projectFile = propsFile("Domain", "file-value");

        // CLI -Dbw5.project.Domain=cli-value
        Map<String, String> mavenProps = new HashMap<>();
        mavenProps.put(PropertyMerger.PROJECT_PREFIX + "Domain", "cli-value");

        PropertyMerger merger = new PropertyMerger();
        List<SubstVarParser.GlobalVariable> result =
            merger.merge(oneVar("Domain", "default"), null, projectFile, mavenProps);

        assertEquals("CLI bw5.project.* must override projectPropertiesFile (DEF-015)",
            "cli-value", result.get(0).value);
    }

    @Test
    public void cliGlobalPropertyOverridesGlobalFile() throws Exception {
        File globalFile = propsFile("Host", "file-host");

        Map<String, String> mavenProps = new HashMap<>();
        mavenProps.put(PropertyMerger.GLOBAL_PREFIX + "Host", "cli-host");

        PropertyMerger merger = new PropertyMerger();
        List<SubstVarParser.GlobalVariable> result =
            merger.merge(oneVar("Host", "default"), globalFile, null, mavenProps);

        assertEquals("CLI bw5.global.* must override globalPropertiesFile",
            "cli-host", result.get(0).value);
    }

    @Test
    public void projectFileOverridesGlobalFile() throws Exception {
        File globalFile  = propsFile("Domain", "global-value");
        File projectFile = propsFile("Domain", "project-value");

        PropertyMerger merger = new PropertyMerger();
        List<SubstVarParser.GlobalVariable> result =
            merger.merge(oneVar("Domain", "default"), globalFile, projectFile, Collections.emptyMap());

        assertEquals("projectPropertiesFile must override globalPropertiesFile",
            "project-value", result.get(0).value);
    }

    @Test
    public void fiveLevelMergeOrderIsCorrect() throws Exception {
        // All five levels set the same variable; highest (bw5.project.*) must win
        File globalFile  = propsFile("X", "global-file");
        File projectFile = propsFile("X", "project-file");

        Map<String, String> mavenProps = new HashMap<>();
        mavenProps.put(PropertyMerger.GLOBAL_PREFIX  + "X", "global-cli");
        mavenProps.put(PropertyMerger.PROJECT_PREFIX + "X", "project-cli");  // highest priority

        PropertyMerger merger = new PropertyMerger();
        List<SubstVarParser.GlobalVariable> result =
            merger.merge(oneVar("X", "substvar-default"), globalFile, projectFile, mavenProps);

        assertEquals("bw5.project.* CLI must be highest priority in five-level merge (DEF-015)",
            "project-cli", result.get(0).value);
    }

    @Test
    public void substVarDefaultUsedWhenNoOverride() throws Exception {
        PropertyMerger merger = new PropertyMerger();
        List<SubstVarParser.GlobalVariable> result =
            merger.merge(oneVar("Timeout", "30"), null, null, Collections.emptyMap());

        assertEquals("substvar default must survive when no overrides provided",
            "30", result.get(0).value);
    }

    // -----------------------------------------------------------------------
    //  DEF-016: explicitly configured files that are missing must fail
    // -----------------------------------------------------------------------

    @Test(expected = IOException.class)
    public void missingGlobalPropertiesFileThrows() throws Exception {
        File missing = new File(tmp.getRoot(), "does-not-exist-global.properties");
        new PropertyMerger().merge(oneVar("X", "v"), missing, null, Collections.emptyMap());
    }

    @Test(expected = IOException.class)
    public void missingProjectPropertiesFileThrows() throws Exception {
        File missing = new File(tmp.getRoot(), "does-not-exist-project.properties");
        new PropertyMerger().merge(oneVar("X", "v"), null, missing, Collections.emptyMap());
    }

    @Test
    public void nullFilesAreAllowed() throws Exception {
        // Passing null for both files must not throw
        PropertyMerger merger = new PropertyMerger();
        List<SubstVarParser.GlobalVariable> result =
            merger.merge(oneVar("X", "v"), null, null, Collections.emptyMap());
        assertEquals("v", result.get(0).value);
    }
}

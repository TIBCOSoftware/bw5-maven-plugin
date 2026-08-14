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

    // -----------------------------------------------------------------------
    //  serviceSettable flag must survive the merge copy (feeds Runtime Variables)
    // -----------------------------------------------------------------------

    @Test
    public void mergePreservesServiceSettableFlag() throws Exception {
        SubstVarParser.GlobalVariable v = new SubstVarParser.GlobalVariable();
        v.name = "CommonCore/Cache/cacheManagerConfig";
        v.value = "x";
        v.serviceSettable = true;

        PropertyMerger merger = new PropertyMerger();
        List<SubstVarParser.GlobalVariable> result =
            merger.merge(java.util.Arrays.asList(v), null, null, Collections.emptyMap());

        assertTrue("serviceSettable must survive the merge (else Runtime Variables is empty)",
            result.get(0).serviceSettable);
    }

    // -----------------------------------------------------------------------
    //  Service-property override merge (bw[<par>]/... keys)
    // -----------------------------------------------------------------------

    @Test
    public void serviceOverrideFileOverridesGeneratedValue() throws Exception {
        Map<String, String> base = new HashMap<>();
        String key = "bw[MyApp-LB.par]/bindings/binding[]/setting/java/maxHeapSize";
        base.put(key, "256");

        File override = propsFile(key, "512");

        Map<String, String> merged =
            new PropertyMerger().mergeServiceProperties(base, override, Collections.emptyMap());

        assertEquals("service override file must override generated default", "512", merged.get(key));
    }

    @Test
    public void serviceOverrideMavenPropWins() throws Exception {
        Map<String, String> base = new HashMap<>();
        String key = "bw[MyApp-LB.par]/bindings/binding[]/setting/threadCount";
        base.put(key, "8");

        File override = propsFile(key, "16");
        Map<String, String> mavenProps = new HashMap<>();
        mavenProps.put(PropertyMerger.SERVICE_PREFIX + key, "32");

        Map<String, String> merged =
            new PropertyMerger().mergeServiceProperties(base, override, mavenProps);

        assertEquals("bw5.service.* must override the service properties file", "32", merged.get(key));
    }

    @Test
    public void serviceOverrideAddsNewKey() throws Exception {
        Map<String, String> base = new HashMap<>();
        base.put("bw[MyApp-LB.par]/enabled", "true");

        String extra = "bw[MyApp-LB.par]/bindings/binding[]/machine";
        File override = propsFile(extra, "%%host%%");

        Map<String, String> merged =
            new PropertyMerger().mergeServiceProperties(base, override, Collections.emptyMap());

        assertEquals("override may add new keys (extra bindings/machines)", "%%host%%", merged.get(extra));
        assertEquals("existing keys are preserved", "true", merged.get("bw[MyApp-LB.par]/enabled"));
    }

    @Test(expected = IOException.class)
    public void missingServicePropertiesFileThrows() throws Exception {
        File missing = new File(tmp.getRoot(), "does-not-exist-service.properties");
        new PropertyMerger().mergeServiceProperties(
            new HashMap<>(), missing, Collections.emptyMap());
    }

    // -----------------------------------------------------------------------
    //  #11: two-level service-property merge (common + project files)
    // -----------------------------------------------------------------------

    @Test
    public void projectServiceFileOverridesCommonServiceFile() throws Exception {
        String key = "bw[MyApp-LB.par]/bindings/binding[]/setting/java/maxHeapSize";
        Map<String, String> base = new HashMap<>();
        base.put(key, "256");

        File common  = propsFile(key, "512");   // global/common service file
        File project = propsFile(key, "1024");  // per-project service file (higher priority)

        Map<String, String> merged = new PropertyMerger().mergeServiceProperties(
            base, common, project, Collections.emptyMap(), true);

        assertEquals("project service file must override common service file (default precedence)",
            "1024", merged.get(key));
    }

    @Test
    public void commonServiceFileWinsWhenPrecedenceFlipped() throws Exception {
        String key = "bw[MyApp-LB.par]/bindings/binding[]/setting/threadCount";
        Map<String, String> base = new HashMap<>();
        base.put(key, "8");

        File common  = propsFile(key, "64");
        File project = propsFile(key, "16");

        Map<String, String> merged = new PropertyMerger().mergeServiceProperties(
            base, common, project, Collections.emptyMap(), false);

        assertEquals("common service file must win when projectPropertiesWin=false",
            "64", merged.get(key));
    }

    @Test
    public void commonServiceFileFillsKeysAbsentFromProjectFile() throws Exception {
        Map<String, String> base = new HashMap<>();
        base.put("bw[MyApp-LB.par]/enabled", "true");

        String commonKey  = "bw[MyApp-LB.par]/bindings/binding[]/machine";
        String projectKey = "bw[MyApp-LB.par]/bindings/binding[]/setting/threadCount";
        File common  = propsFile(commonKey, "%%host%%");
        File project = propsFile(projectKey, "16");

        Map<String, String> merged = new PropertyMerger().mergeServiceProperties(
            base, common, project, Collections.emptyMap(), true);

        assertEquals("common key present when project file omits it", "%%host%%", merged.get(commonKey));
        assertEquals("project key applied", "16", merged.get(projectKey));
        assertEquals("base key preserved", "true", merged.get("bw[MyApp-LB.par]/enabled"));
    }

    @Test
    public void inlineServicePropertyBeatsBothServiceFiles() throws Exception {
        String key = "bw[MyApp-LB.par]/bindings/binding[]/setting/threadCount";
        Map<String, String> base = new HashMap<>();
        base.put(key, "8");

        File common  = propsFile(key, "64");
        File project = propsFile(key, "16");
        Map<String, String> mavenProps = new HashMap<>();
        mavenProps.put(PropertyMerger.SERVICE_PREFIX + key, "128");

        // Even with global precedence, inline bw5.service.* stays the highest priority.
        Map<String, String> merged = new PropertyMerger().mergeServiceProperties(
            base, common, project, mavenProps, false);

        assertEquals("bw5.service.* must beat both service files regardless of precedence",
            "128", merged.get(key));
    }

    @Test
    public void singleFileServiceOverloadMapsToProjectLevel() throws Exception {
        // The legacy 3-arg overload must behave as a project-level (higher-priority) file.
        String key = "bw[MyApp-LB.par]/enabled";
        Map<String, String> base = new HashMap<>();
        base.put(key, "false");
        File project = propsFile(key, "true");

        Map<String, String> merged =
            new PropertyMerger().mergeServiceProperties(base, project, Collections.emptyMap());

        assertEquals("legacy single-file overload still applies the override", "true", merged.get(key));
    }

    @Test(expected = IOException.class)
    public void missingCommonServicePropertiesFileThrows() throws Exception {
        File missing = new File(tmp.getRoot(), "does-not-exist-common-service.properties");
        new PropertyMerger().mergeServiceProperties(
            new HashMap<>(), missing, null, Collections.emptyMap(), true);
    }

    // -----------------------------------------------------------------------
    //  #11: configurable global-variable precedence (projectPropertiesWin)
    // -----------------------------------------------------------------------

    @Test
    public void globalFileWinsWhenPrecedenceFlipped() throws Exception {
        File globalFile  = propsFile("Domain", "global-value");
        File projectFile = propsFile("Domain", "project-value");

        List<SubstVarParser.GlobalVariable> result = new PropertyMerger().merge(
            oneVar("Domain", "default"), globalFile, projectFile, Collections.emptyMap(), false);

        assertEquals("globalPropertiesFile must win when projectPropertiesWin=false",
            "global-value", result.get(0).value);
    }

    @Test
    public void projectWinsIsTheDefaultOverload() throws Exception {
        File globalFile  = propsFile("Domain", "global-value");
        File projectFile = propsFile("Domain", "project-value");

        // The 4-arg overload must keep the historical project-wins behaviour.
        List<SubstVarParser.GlobalVariable> result = new PropertyMerger().merge(
            oneVar("Domain", "default"), globalFile, projectFile, Collections.emptyMap());

        assertEquals("4-arg merge() must default to project-wins", "project-value",
            result.get(0).value);
    }

    @Test
    public void globalCliBeatsProjectFileWhenGlobalWins() throws Exception {
        // With global precedence, the whole global bundle (file + bw5.global.*) sits on top.
        File globalFile  = propsFile("X", "global-file");
        File projectFile = propsFile("X", "project-file");
        Map<String, String> mavenProps = new HashMap<>();
        mavenProps.put(PropertyMerger.GLOBAL_PREFIX + "X", "global-cli");

        List<SubstVarParser.GlobalVariable> result = new PropertyMerger().merge(
            oneVar("X", "default"), globalFile, projectFile, mavenProps, false);

        assertEquals("bw5.global.* is top of the global bundle, which wins when flipped",
            "global-cli", result.get(0).value);
    }
}

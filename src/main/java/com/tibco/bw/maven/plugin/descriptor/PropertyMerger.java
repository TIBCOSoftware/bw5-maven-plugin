package com.tibco.bw.maven.plugin.descriptor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Merges substitution-variable values from two levels of external override:
 * <ol>
 *   <li><b>Global</b> — shared configuration applied to every BW5 project in a build
 *       (e.g. infrastructure hostnames, credentials shared across all apps).</li>
 *   <li><b>Project</b> — application-specific overrides that take precedence over global
 *       values (e.g. a queue name unique to this application).</li>
 * </ol>
 *
 * <p>Override values can come from two sources for each level:</p>
 * <ul>
 *   <li>A {@code .properties} file on disk (via
 *       {@code bw5.configure.globalPropertiesFile} / {@code bw5.configure.projectPropertiesFile}).</li>
 *   <li>Inline Maven properties prefixed with {@code bw5.global.} or {@code bw5.project.}
 *       (set in {@code <properties>} or passed as {@code -Dbw5.global.VarName=value}).</li>
 * </ul>
 *
 * <p><b>Merge order (lowest → highest priority):</b></p>
 * <ol>
 *   <li>.substvar default values</li>
 *   <li>Global properties file</li>
 *   <li>Maven properties prefixed with {@code bw5.global.}</li>
 *   <li>Project properties file</li>
 *   <li>Maven properties prefixed with {@code bw5.project.}</li>
 * </ol>
 */
public class PropertyMerger {

    /** Maven property prefix for global-level overrides. */
    public static final String GLOBAL_PREFIX  = "bw5.global.";

    /** Maven property prefix for per-project overrides. */
    public static final String PROJECT_PREFIX = "bw5.project.";

    /**
     * Applies global and project overrides to a list of global variables.
     *
     * @param vars              variables parsed from .substvar files (default values)
     * @param globalPropsFile   optional path to a global .properties file (may be null)
     * @param projectPropsFile  optional path to a project .properties file (may be null)
     * @param mavenProperties   all Maven project properties (to extract bw5.global.* and bw5.project.*)
     * @return a new list of GlobalVariable objects with merged values (originals are not mutated)
     */
    public List<SubstVarParser.GlobalVariable> merge(
            List<SubstVarParser.GlobalVariable> vars,
            File   globalPropsFile,
            File   projectPropsFile,
            Map<String, String> mavenProperties) throws IOException {

        // --- Build override maps ---
        Map<String, String> globalOverrides  = new LinkedHashMap<>();
        Map<String, String> projectOverrides = new LinkedHashMap<>();

        // 1. Global properties file
        if (globalPropsFile != null) {
            if (!globalPropsFile.isFile()) {
                throw new IOException("Global properties file not found: " + globalPropsFile.getAbsolutePath());
            }
            globalOverrides.putAll(loadProperties(globalPropsFile));
        }
        // 2. Maven properties with bw5.global. prefix (override file values)
        if (mavenProperties != null) {
            for (Map.Entry<String, String> e : mavenProperties.entrySet()) {
                if (e.getKey().startsWith(GLOBAL_PREFIX)) {
                    globalOverrides.put(e.getKey().substring(GLOBAL_PREFIX.length()), e.getValue());
                }
            }
        }
        // 3. Project properties file
        if (projectPropsFile != null) {
            if (!projectPropsFile.isFile()) {
                throw new IOException("Project properties file not found: " + projectPropsFile.getAbsolutePath());
            }
            projectOverrides.putAll(loadProperties(projectPropsFile));
        }
        // 4. Maven properties with bw5.project. prefix (highest priority)
        if (mavenProperties != null) {
            for (Map.Entry<String, String> e : mavenProperties.entrySet()) {
                if (e.getKey().startsWith(PROJECT_PREFIX)) {
                    projectOverrides.put(e.getKey().substring(PROJECT_PREFIX.length()), e.getValue());
                }
            }
        }

        // --- Apply to variables ---
        List<SubstVarParser.GlobalVariable> result = new ArrayList<>(vars.size());
        for (SubstVarParser.GlobalVariable orig : vars) {
            SubstVarParser.GlobalVariable merged = copy(orig);
            String name = orig.name;
            if (globalOverrides.containsKey(name)) {
                merged.value = globalOverrides.get(name);
            }
            if (projectOverrides.containsKey(name)) {
                merged.value = projectOverrides.get(name);
            }
            result.add(merged);
        }
        return result;
    }

    /**
     * Loads a Java .properties file and returns its entries as a String map.
     * Keys and values are NOT trimmed beyond what Properties does internally.
     */
    public Map<String, String> loadProperties(File file) throws IOException {
        Properties p = new Properties();
        try (InputStream is = Files.newInputStream(file.toPath())) {
            p.load(is);
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : p.stringPropertyNames()) {
            map.put(key, p.getProperty(key));
        }
        return map;
    }

    private SubstVarParser.GlobalVariable copy(SubstVarParser.GlobalVariable src) {
        SubstVarParser.GlobalVariable dst = new SubstVarParser.GlobalVariable();
        dst.name                  = src.name;
        dst.value                 = src.value;
        dst.description           = src.description;
        dst.type                  = src.type;
        dst.requiresConfiguration = src.requiresConfiguration;
        dst.substVarFile          = src.substVarFile;
        return dst;
    }
}

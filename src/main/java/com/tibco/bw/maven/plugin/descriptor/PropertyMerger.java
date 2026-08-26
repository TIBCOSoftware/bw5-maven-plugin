package com.tibco.bw.maven.plugin.descriptor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
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
 * <p><b>Merge order (lowest → highest priority), with the default
 * {@code projectPropertiesWin = true}:</b></p>
 * <ol>
 *   <li>.substvar default values</li>
 *   <li>Global properties file</li>
 *   <li>Maven properties prefixed with {@code bw5.global.}</li>
 *   <li>Project properties file</li>
 *   <li>Maven properties prefixed with {@code bw5.project.}</li>
 * </ol>
 *
 * <p>When {@code projectPropertiesWin = false} the two levels swap: the whole
 * project bundle (project file + {@code bw5.project.*}) is applied first and the
 * whole global bundle (global file + {@code bw5.global.*}) on top, so global
 * values win.</p>
 */
public class PropertyMerger {

    /** Maven property prefix for global-level overrides. */
    public static final String GLOBAL_PREFIX  = "bw5.global.";

    /** Maven property prefix for per-project overrides. */
    public static final String PROJECT_PREFIX = "bw5.project.";

    /** Maven property prefix for service-property overrides ({@code bw[<par>]/...} keys). */
    public static final String SERVICE_PREFIX = "bw5.service.";

    /**
     * Merges a single project-level service-property override file on top of a generated
     * {@code bw[<par>]/...=value} map. Equivalent to
     * {@link #mergeServiceProperties(Map, File, File, Map, boolean)} with no common file and
     * {@code projectPropertiesWin = true}.
     *
     * @param base             generated service-property map (never null)
     * @param servicePropsFile optional project {@code .properties} override file (may be null)
     * @param mavenProperties  all Maven project properties ({@code bw5.service.*} are applied)
     * @return a new map with overrides merged in
     */
    public Map<String, String> mergeServiceProperties(
            Map<String, String> base,
            File servicePropsFile,
            Map<String, String> mavenProperties) throws IOException {
        return mergeServiceProperties(base, null, servicePropsFile, mavenProperties, true);
    }

    /**
     * Merges two levels of service-property override on top of a generated
     * {@code bw[<par>]/...=value} map: a common/global file shared across projects and a
     * per-project file.
     *
     * <p>Override sources, applied in increasing priority when
     * {@code projectPropertiesWin = true} (the default): the generated defaults ({@code base}),
     * the {@code globalServicePropsFile} (if any), the {@code projectServicePropsFile} (if any),
     * then Maven properties prefixed with {@code bw5.service.}. When
     * {@code projectPropertiesWin = false} the two files swap so the common file wins over the
     * project file; the inline {@code bw5.service.*} properties always remain the highest
     * priority. Unlike the global-variable merge, keys are matched verbatim and new keys are
     * added (an admin may introduce extra bindings/machines), not just override existing ones.
     * The input map is not mutated.</p>
     *
     * @param base                    generated service-property map (never null)
     * @param globalServicePropsFile  optional common/global override file (may be null)
     * @param projectServicePropsFile optional per-project override file (may be null)
     * @param mavenProperties         all Maven project properties ({@code bw5.service.*} applied)
     * @param projectPropertiesWin    when {@code true} the project file wins over the common file
     * @return a new map with overrides merged in
     */
    public Map<String, String> mergeServiceProperties(
            Map<String, String> base,
            File globalServicePropsFile,
            File projectServicePropsFile,
            Map<String, String> mavenProperties,
            boolean projectPropertiesWin) throws IOException {

        Map<String, String> result = new LinkedHashMap<>(base);
        Map<String, String> globalOverrides  = loadServiceFile(globalServicePropsFile);
        Map<String, String> projectOverrides = loadServiceFile(projectServicePropsFile);

        if (projectPropertiesWin) {
            result.putAll(globalOverrides);
            result.putAll(projectOverrides);
        } else {
            result.putAll(projectOverrides);
            result.putAll(globalOverrides);
        }
        // Inline bw5.service.* Maven properties are always the most explicit override.
        addPrefixed(result, mavenProperties, SERVICE_PREFIX);
        return result;
    }

    /**
     * Collects the concrete (non-wildcard) service-property keys the user set explicitly through the
     * common file, the project file or {@code bw5.service.*} Maven properties. These keys are
     * protected from wildcard expansion in
     * {@link DeploymentConfigGenerator#resolveServiceBindings(Map, java.util.Set)} — an explicit
     * value always wins over a glob. Keys that themselves contain a {@code *} (wildcard patterns)
     * are excluded.
     *
     * @param globalServicePropsFile  optional common/global override file (may be null)
     * @param projectServicePropsFile optional per-project override file (may be null)
     * @param mavenProperties         all Maven project properties ({@code bw5.service.*} applied)
     * @return the set of explicitly-set concrete keys (never null)
     */
    public java.util.Set<String> collectServiceOverrideKeys(
            File globalServicePropsFile,
            File projectServicePropsFile,
            Map<String, String> mavenProperties) throws IOException {

        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (File f : Arrays.asList(globalServicePropsFile, projectServicePropsFile)) {
            for (String k : loadServiceFile(f).keySet()) {
                if (k.indexOf('*') < 0) keys.add(k);
            }
        }
        if (mavenProperties != null) {
            for (String k : mavenProperties.keySet()) {
                if (k.startsWith(SERVICE_PREFIX)) {
                    String real = k.substring(SERVICE_PREFIX.length());
                    if (real.indexOf('*') < 0) keys.add(real);
                }
            }
        }
        return keys;
    }

    private Map<String, String> loadServiceFile(File file) throws IOException {
        if (file == null) return new LinkedHashMap<>();
        if (!file.isFile()) {
            throw new IOException("Service properties file not found: " + file.getAbsolutePath());
        }
        return loadProperties(file);
    }

    /**
     * Copies every {@code mavenProperties} entry whose key starts with {@code prefix} into
     * {@code target}, stripping the prefix from the key. No-op when {@code mavenProperties} is null.
     */
    private void addPrefixed(Map<String, String> target,
            Map<String, String> mavenProperties, String prefix) {
        if (mavenProperties == null) return;
        for (Map.Entry<String, String> e : mavenProperties.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                target.put(e.getKey().substring(prefix.length()), e.getValue());
            }
        }
    }

    /**
     * Applies global and project overrides to a list of global variables, with the project
     * level winning over the global level. Equivalent to
     * {@link #merge(List, File, File, Map, boolean)} with {@code projectPropertiesWin = true}.
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
        return merge(vars, globalPropsFile, projectPropsFile, mavenProperties, true);
    }

    /**
     * Applies global and project overrides to a list of global variables, letting the caller
     * choose which level wins.
     *
     * @param vars                 variables parsed from .substvar files (default values)
     * @param globalPropsFile      optional path to a global .properties file (may be null)
     * @param projectPropsFile     optional path to a project .properties file (may be null)
     * @param mavenProperties      all Maven project properties (bw5.global.* and bw5.project.*)
     * @param projectPropertiesWin when {@code true} the project level (project file +
     *                             {@code bw5.project.*}) wins over the global level; when
     *                             {@code false} the global level wins
     * @return a new list of GlobalVariable objects with merged values (originals are not mutated)
     */
    public List<SubstVarParser.GlobalVariable> merge(
            List<SubstVarParser.GlobalVariable> vars,
            File   globalPropsFile,
            File   projectPropsFile,
            Map<String, String> mavenProperties,
            boolean projectPropertiesWin) throws IOException {

        // --- Build one override map per level (file value + matching bw5.<level>.* prefix) ---
        Map<String, String> globalOverrides  = new LinkedHashMap<>();
        if (globalPropsFile != null) {
            if (!globalPropsFile.isFile()) {
                throw new IOException("Global properties file not found: " + globalPropsFile.getAbsolutePath());
            }
            globalOverrides.putAll(loadProperties(globalPropsFile));
        }
        addPrefixed(globalOverrides, mavenProperties, GLOBAL_PREFIX);

        Map<String, String> projectOverrides = new LinkedHashMap<>();
        if (projectPropsFile != null) {
            if (!projectPropsFile.isFile()) {
                throw new IOException("Project properties file not found: " + projectPropsFile.getAbsolutePath());
            }
            projectOverrides.putAll(loadProperties(projectPropsFile));
        }
        addPrefixed(projectOverrides, mavenProperties, PROJECT_PREFIX);

        // Apply the lower-priority level first, then the higher-priority one on top.
        List<Map<String, String>> ordered = projectPropertiesWin
                ? Arrays.asList(globalOverrides, projectOverrides)
                : Arrays.asList(projectOverrides, globalOverrides);

        // --- Apply to variables ---
        List<SubstVarParser.GlobalVariable> result = new ArrayList<>(vars.size());
        for (SubstVarParser.GlobalVariable orig : vars) {
            SubstVarParser.GlobalVariable merged = copy(orig);
            for (Map<String, String> overrides : ordered) {
                if (overrides.containsKey(orig.name)) {
                    merged.value = overrides.get(orig.name);
                }
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
        dst.defaultValue          = src.defaultValue;
        dst.description           = src.description;
        dst.type                  = src.type;
        dst.requiresConfiguration = src.requiresConfiguration;
        dst.serviceSettable       = src.serviceSettable;
        dst.substVarFile          = src.substVarFile;
        return dst;
    }
}

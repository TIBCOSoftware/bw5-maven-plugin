package com.tibco.bw.maven.plugin.packaging;

import com.tibco.bw.maven.plugin.descriptor.DeploymentConfigGenerator;
import com.tibco.bw.maven.plugin.descriptor.PropertyMerger;
import com.tibco.bw.maven.plugin.descriptor.SubstVarParser;
import com.tibco.bw.maven.plugin.descriptor.SubstVarWriter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;
import java.util.*;

/**
 * Merges environment-specific property overrides into the BW5 project's
 * substitution variables and regenerates all deployment configuration files.
 *
 * <h3>Two-level override model</h3>
 * <ol>
 *   <li><b>Global level</b> — values shared across <em>all</em> BW5 projects in a
 *       multi-module build (infrastructure hostnames, shared credentials, etc.).
 *       Provided via {@code bw5.deployConfig.globalPropertiesFile} and/or Maven
 *       properties prefixed with {@code bw5.global.}.</li>
 *   <li><b>Project level</b> — values specific to <em>this</em> application
 *       (queue names, service endpoints unique to this app, etc.).
 *       Provided via {@code bw5.deployConfig.projectPropertiesFile} and/or Maven
 *       properties prefixed with {@code bw5.project.}.</li>
 * </ol>
 *
 * <h3>Merge priority (lowest → highest)</h3>
 * <ol>
 *   <li>.substvar default values</li>
 *   <li>Global properties file ({@code bw5.deployConfig.globalPropertiesFile})</li>
 *   <li>Maven properties prefixed {@code bw5.global.*}</li>
 *   <li>Project properties file ({@code bw5.deployConfig.projectPropertiesFile})</li>
 *   <li>Maven properties prefixed {@code bw5.project.*}</li>
 * </ol>
 *
 * <h3>Outputs</h3>
 * <p>After merging, the following files are regenerated under
 * {@code target/} with the merged values:</p>
 * <ul>
 *   <li>{@code <finalName>-deploy.xml} — AppManage XML</li>
 *   <li>{@code <finalName>-deploy.properties} — flat key=value</li>
 *   <li>{@code values.yaml} — Helm override values</li>
 * </ul>
 *
 * <p>Optionally, the merged values can be written back into the source
 * {@code .substvar} files by setting {@code bw5.deployConfig.updateSubstVarFiles=true}.</p>
 *
 * <h3>Example pom.xml</h3>
 * <pre>{@code
 * <plugin>
 *   <groupId>com.tibco.bw</groupId>
 *   <artifactId>bw5-maven-plugin</artifactId>
 *   <configuration>
 *     <globalPropertiesFile>${project.basedir}/../config/global.properties</globalPropertiesFile>
 *     <projectPropertiesFile>${project.basedir}/config/project.properties</projectPropertiesFile>
 *   </configuration>
 * </plugin>
 * }</pre>
 *
 * <p>Or via command line:</p>
 * <pre>
 *   mvn bw5:deploy-config -Dbw5.global.JmsHost=mq.prod.example.com \
 *                     -Dbw5.project.AppQueueName=PROD.ORDER.IN
 * </pre>
 */
@Mojo(
    name = "deploy-config",
    defaultPhase = LifecyclePhase.GENERATE_RESOURCES,
    requiresDependencyResolution = ResolutionScope.NONE,
    threadSafe = false
)
public class ConfigureMojo extends AbstractBw5Mojo {

    /**
     * Path to a Java {@code .properties} file containing <em>global</em> variable
     * overrides, applied to every BW5 project in a multi-module build.
     *
     * <p>Keys must match the global variable names defined in the {@code .substvar}
     * files exactly (e.g. {@code JmsProviderUrl=tcp://mq.prod.example.com:7222}).</p>
     *
     * <p>Values from Maven properties prefixed with {@code bw5.global.} take
     * precedence over values in this file.</p>
     */
    @Parameter(property = "bw5.deployConfig.globalPropertiesFile")
    private File globalPropertiesFile;

    /**
     * Path to a Java {@code .properties} file containing <em>per-project</em>
     * variable overrides, applied only to this Maven module.
     *
     * <p>Takes precedence over {@code globalPropertiesFile}.</p>
     * <p>Values from Maven properties prefixed with {@code bw5.project.} take
     * precedence over values in this file.</p>
     */
    @Parameter(property = "bw5.deployConfig.projectPropertiesFile")
    private File projectPropertiesFile;

    /**
     * When {@code true}, the merged variable values are written back into the
     * source {@code .substvar} files, updating the {@code &lt;value&gt;} element
     * of each variable in-place.
     *
     * <p><b>Warning:</b> this modifies your source files. Only use this in a
     * dedicated environment-configuration step, not during a standard build.</p>
     *
     * <p>Default: {@code false}.</p>
     */
    @Parameter(defaultValue = "false", property = "bw5.deployConfig.updateSubstVarFiles")
    private boolean updateSubstVarFiles;

    /**
     * Generate an AppManage-compatible XML deployment configuration file
     * ({@code <finalName>-deploy.xml}) with the merged values.
     */
    @Parameter(defaultValue = "true", property = "bw5.deployConfig.generateDeployXml")
    private boolean generateDeployXml;

    /**
     * Generate a flat {@code .properties} deployment configuration file
     * ({@code <finalName>-deploy.properties}) with the merged values.
     */
    @Parameter(defaultValue = "true", property = "bw5.deployConfig.generateProperties")
    private boolean generateProperties;

    /**
     * Generate a Helm {@code values.yaml} deployment configuration file
     * with the merged values.
     */
    @Parameter(defaultValue = "true", property = "bw5.deployConfig.generateValuesYaml")
    private boolean generateValuesYaml;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("bw5:deploy-config skipped.");
            return;
        }

        validateBwProjectPath();

        File srcDir = bwSourcesDirectory.exists() ? bwSourcesDirectory : bwProjectPath;
        getLog().info("Configuring BW5 project: " + project.getArtifactId());
        getLog().info("Source dir : " + srcDir.getAbsolutePath());

        logConfiguredSources();

        try {
            // 1. Collect all .substvar files
            List<File> substVarFiles = findSubstVarFiles(srcDir);
            getLog().info("Found " + substVarFiles.size() + " .substvar file(s)");

            // 2. Parse all global variables
            SubstVarParser parser = new SubstVarParser();
            List<SubstVarParser.GlobalVariable> allVars = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (File f : substVarFiles) {
                try {
                    String relativePath = srcDir.toURI().relativize(f.toURI()).getPath();
                    String prefix = computeGvPrefix(relativePath);
                    List<SubstVarParser.GlobalVariable> vars = parser.parse(f);
                    for (SubstVarParser.GlobalVariable v : vars) {
                        v.substVarFile = f.getName();
                        v.name = prefix + v.name;
                        if (seen.add(v.name)) {
                            allVars.add(v);
                        }
                    }
                } catch (Exception e) {
                    getLog().warn("Could not parse " + f.getName() + ": " + e.getMessage());
                }
            }
            getLog().info("Total global variables: " + allVars.size());

            // 3. Merge overrides
            PropertyMerger merger = new PropertyMerger();
            Map<String, String> mavenProps = getAllMavenProperties();
            List<SubstVarParser.GlobalVariable> mergedVars =
                merger.merge(allVars, globalPropertiesFile, projectPropertiesFile, mavenProps);

            logMergeStats(allVars, mergedVars);

            // 4. Optionally write merged values back to .substvar files
            if (updateSubstVarFiles) {
                getLog().info("Writing merged values back to .substvar files...");
                SubstVarWriter writer = new SubstVarWriter();
                for (File f : substVarFiles) {
                    try {
                        writer.updateValues(f, mergedVars);
                        getLog().info("  Updated: " + f.getName());
                    } catch (Exception e) {
                        getLog().warn("  Could not update " + f.getName() + ": " + e.getMessage());
                    }
                }
            }

            // 5. Generate deployment config files with merged values
            generateConfigs(mergedVars);

        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("bw5:deploy-config failed: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------

    private void generateConfigs(List<SubstVarParser.GlobalVariable> vars)
            throws MojoExecutionException {

        if (!generateDeployXml && !generateProperties && !generateValuesYaml) return;

        String finalName  = project.getBuild().getFinalName();
        String appName    = project.getArtifactId();
        String appVersion = project.getVersion();
        File   targetDir  = new File(project.getBuild().getDirectory());
        if (!targetDir.mkdirs() && !targetDir.isDirectory()) {
            throw new MojoExecutionException("Failed to create directory: " + targetDir.getAbsolutePath());
        }

        DeploymentConfigGenerator gen = new DeploymentConfigGenerator();
        try {
            if (generateDeployXml) {
                File out = new File(targetDir, finalName + "-deploy.xml");
                gen.generateDeployXml(out, appName, appVersion, vars);
                getLog().info("Generated deploy XML    : " + out.getName());
            }
            if (generateProperties) {
                File out = new File(targetDir, finalName + "-deploy.properties");
                gen.generateProperties(out, appName, appVersion, vars);
                getLog().info("Generated properties    : " + out.getName());
            }
            if (generateValuesYaml) {
                File out = new File(targetDir, finalName + "-values.yaml");
                gen.generateValuesYaml(out, appName, appVersion, vars);
                getLog().info("Generated values.yaml   : " + out.getName());
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate config files: " + e.getMessage(), e);
        }
    }

    private List<File> findSubstVarFiles(File dir) {
        List<File> result = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return result;
        for (File f : files) {
            if (f.isDirectory() && !f.getName().startsWith(".")) {
                result.addAll(findSubstVarFiles(f));
            } else if (f.isFile() && f.getName().endsWith(".substvar")) {
                result.add(f);
            }
        }
        return result;
    }

    private void logConfiguredSources() {
        if (globalPropertiesFile != null) {
            getLog().info("Global properties  : " + globalPropertiesFile.getAbsolutePath()
                + (globalPropertiesFile.isFile() ? "" : " (not found)"));
        }
        if (projectPropertiesFile != null) {
            getLog().info("Project properties : " + projectPropertiesFile.getAbsolutePath()
                + (projectPropertiesFile.isFile() ? "" : " (not found)"));
        }
        // Count inline Maven property overrides
        long globalCount  = countMavenProps(PropertyMerger.GLOBAL_PREFIX);
        long projectCount = countMavenProps(PropertyMerger.PROJECT_PREFIX);
        if (globalCount > 0)  getLog().info("Inline bw5.global.*  overrides: " + globalCount);
        if (projectCount > 0) getLog().info("Inline bw5.project.* overrides: " + projectCount);
    }

    private long countMavenProps(String prefix) {
        return getAllMavenProperties().keySet().stream()
            .filter(k -> k.startsWith(prefix))
            .count();
    }

    private void logMergeStats(List<SubstVarParser.GlobalVariable> original,
                               List<SubstVarParser.GlobalVariable> merged) {
        int changed = 0;
        Map<String, String> origMap = new LinkedHashMap<>();
        for (SubstVarParser.GlobalVariable v : original) origMap.put(v.name, v.value);
        for (SubstVarParser.GlobalVariable v : merged) {
            String origVal = origMap.get(v.name);
            if (origVal == null ? v.value != null : !origVal.equals(v.value)) {
                changed++;
                getLog().debug("  Override: " + v.name + " = " + v.value
                    + " (was: " + origVal + ")");
            }
        }
        if (changed > 0) {
            getLog().info("Applied overrides to " + changed + " of " + merged.size() + " variable(s)");
        } else {
            getLog().info("No overrides applied (all variables at default values)");
        }
    }

}

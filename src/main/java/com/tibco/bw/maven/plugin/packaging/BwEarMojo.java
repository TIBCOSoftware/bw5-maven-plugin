package com.tibco.bw.maven.plugin.packaging;

import com.tibco.bw.maven.plugin.descriptor.AliasLibParser;
import com.tibco.bw.maven.plugin.descriptor.ArchiveDescriptorParser;
import com.tibco.bw.maven.plugin.descriptor.DeploymentConfigGenerator;
import com.tibco.bw.maven.plugin.descriptor.ManifestBw5Generator;
import com.tibco.bw.maven.plugin.descriptor.ProcessParser;
import com.tibco.bw.maven.plugin.descriptor.PropertyMerger;
import com.tibco.bw.maven.plugin.descriptor.SubstVarParser;
import com.tibco.bw.maven.plugin.descriptor.TibcoXmlGenerator;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProjectHelper;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import javax.inject.Inject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.Objects;
import java.util.zip.*;

/**
 * Assembles the BW5 EAR archive from the BW project sources.
 *
 * <p>The EAR contains:</p>
 * <ul>
 *   <li>{@code TIBCO.xml} — EAR-level deployment descriptor (generated)</li>
 *   <li>{@code <archiveName>.par} — Process Archive containing .process files</li>
 *   <li>{@code Shared Archive.sar} — Shared Archive containing shared resources</li>
 * </ul>
 *
 * <p>No TIBCO tools are required. The EAR is assembled entirely in pure Java.</p>
 *
 */
@Mojo(
    name = "bwear",
    defaultPhase = LifecyclePhase.PACKAGE,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
    threadSafe = false
)
public class BwEarMojo extends AbstractBw5Mojo {

    /**
     * Whether to include the Shared Archive (SAR) in the EAR.
     * The SAR contains shared resources like connections, schemas, and variables.
     * Defaults to true.
     */
    @Parameter(defaultValue = "true", property = "bw5.includeSharedArchive")
    private boolean includeSharedArchive;

    /**
     * Name of the Shared Archive within the EAR.
     * Defaults to "Shared Archive".
     */
    @Parameter(defaultValue = "Shared Archive", property = "bw5.sharedArchiveName")
    private String sharedArchiveName;

    /**
     * Generate an AppManage-compatible XML deployment configuration file
     * ({@code <finalName>-deploy.xml}) alongside the EAR.
     * Set to {@code false} to skip this file.
     */
    @Parameter(defaultValue = "true", property = "bw5.generateDeployXml")
    private boolean generateDeployXml;

    /**
     * Generate a flat {@code .properties} deployment configuration file
     * ({@code <finalName>-deploy.properties}) alongside the EAR.
     * Set to {@code false} to skip this file.
     */
    @Parameter(defaultValue = "true", property = "bw5.generateProperties")
    private boolean generateProperties;

    /**
     * Generate a Helm {@code values.yaml} deployment configuration file
     * alongside the EAR, for BW5 Platform (Kubernetes) deployments.
     * Set to {@code false} to skip this file.
     */
    @Parameter(defaultValue = "true", property = "bw5.generateValuesYaml")
    private boolean generateValuesYaml;

    /**
     * When {@code true}, skips copying projlib/JAR dependencies to {@code target/bw-lib}
     * before assembling the EAR. Useful when {@code bw5:resolve-dependencies} has already
     * been executed earlier in the build (e.g. bound to the {@code process-resources} phase).
     */
    @Parameter(defaultValue = "false", property = "bw5.bwear.skipResolveDependencies")
    private boolean skipResolveDependencies;

    /**
     * When {@code true}, only the EAR is assembled — no deployment configuration
     * files ({@code -deploy.xml}, {@code -deploy.properties}, {@code values.yaml})
     * are generated.
     *
     * <p>Equivalent to setting {@code generateDeployXml=false},
     * {@code generateProperties=false}, and {@code generateValuesYaml=false}
     * in a single flag.</p>
     *
     * <pre>mvn package -Dbw5.earOnly=true</pre>
     */
    @Parameter(defaultValue = "false", property = "bw5.earOnly")
    private boolean earOnly;

    /**
     * Path to a Java {@code .properties} file with <em>global</em> variable
     * overrides applied before generating deployment config files.
     * See {@code bw5:deploy-config} for the full two-level merge model.
     */
    @Parameter(property = "bw5.deployConfig.globalPropertiesFile")
    private File globalPropertiesFile;

    /**
     * Path to a Java {@code .properties} file with <em>per-project</em>
     * variable overrides applied before generating deployment config files.
     * Takes precedence over {@code globalPropertiesFile}.
     */
    @Parameter(property = "bw5.deployConfig.projectPropertiesFile")
    private File projectPropertiesFile;

    /**
     * When {@code true}, the {@code .javaxpath} bytecode already embedded in the source file
     * is used as-is instead of failing when no freshly compiled class is found.
     *
     * <p>Set this to {@code true} for legacy or vendored custom functions whose source
     * is not compiled as part of this Maven build. A WARNING is logged for each affected file.</p>
     *
     * <pre>mvn package -Dbw5.oldJavaCustomFunctions=true</pre>
     */
    @Parameter(defaultValue = "false", property = "bw5.oldJavaCustomFunctions")
    private boolean oldJavaCustomFunctions;

    /**
     * Optional path to a TIBCO Designer {@code .archive} descriptor file.
     *
     * <p>When provided, the plugin reads the descriptor to determine:</p>
     * <ul>
     *   <li>The PAR name (from {@code processArchive/@name}), overriding
     *       {@code "Process Archive.par"} if the descriptor specifies a different name.</li>
     *   <li>The SAR name (from {@code sharedArchive/@name}), overriding
     *       {@code bw5.sharedArchiveName} if the descriptor specifies a name.</li>
     *   <li>Optionally which processes to include in the PAR (when
     *       {@code bw5.archiveDescriptorFilterProcesses=true}).</li>
     * </ul>
     *
     * <p>By default (when {@code bw5.archiveDescriptorFilterProcesses} is {@code false}),
     * all collected {@code .process} files are included — the descriptor is used only
     * for archive naming. This matches {@code buildEAR} behaviour where all processes
     * from projlib dependencies are packaged.</p>
     *
     * <p>Commit the {@code .archive} file alongside your BW project sources to use this
     * feature, for example:</p>
     * <pre>{@code
     * <configuration>
     *   <archiveDescriptorFile>${basedir}/src/main/bw/MyApp.archive</archiveDescriptorFile>
     * </configuration>
     * }</pre>
     */
    @Parameter(property = "bw5.archiveDescriptorFile")
    private File archiveDescriptorFile;

    /**
     * @deprecated No longer used. Transitive dependency analysis is applied automatically
     *             when {@code archiveDescriptorFile} is configured with a processProperty list.
     */
    @Deprecated
    @Parameter(defaultValue = "false", property = "bw5.archiveDescriptorFilterProcesses")
    private boolean archiveDescriptorFilterProcesses;

    /**
     * When {@code true}, the {@code manifest-bw5.json} file is NOT generated and NOT
     * included in the EAR. Defaults to {@code false} (manifest is always generated).
     *
     * <p>The manifest is required by TIBCO BW5 container runtimes (TCI/BWCE). Only set
     * this flag for traditional domain deployments where the manifest is not needed.</p>
     *
     * <pre>mvn package -Dbw5.skipManifest=true</pre>
     */
    @Parameter(defaultValue = "false", property = "bw5.skipManifest")
    private boolean skipManifest;

    /**
     * When {@code true} (the default), TIBCO Designer folder-metadata files ({@code .folder})
     * are included in the SAR alongside the resources they accompany.
     *
     * <p>TIBCO {@code buildear} includes {@code .folder} files that appear under the paths
     * listed in the {@code .archive} descriptor's {@code sharedResources} element. This
     * flag reproduces that behaviour for backwards compatibility.</p>
     *
     * <p>Set to {@code false} to omit all {@code .folder} files from the EAR (produces a
     * slightly smaller, functionally identical archive — {@code .folder} files are pure
     * TIBCO Designer display metadata and are not read by the BW engine at runtime).</p>
     *
     * <pre>mvn package -Dbw5.includeFolderMetadata=false</pre>
     */
    @Parameter(defaultValue = "true", property = "bw5.includeFolderMetadata")
    private boolean includeFolderMetadata;

    private static final Namespace JCF_NS =
        Namespace.getNamespace("http://www.tibco.com/bw/javaxpath/2003");

    /**
     * File extensions that go into the PAR (Process Archive).
     * All other BW files go into the SAR (Shared Archive).
     */
    private static final Set<String> PAR_EXTENSIONS = new HashSet<>(Arrays.asList(
        ".process"
    ));

    /**
     * File extensions for shared BW resources that go into the SAR.
     * Excludes metadata files (.folder, .substvar, .aeschema) and version-control
     * files (.dat) which buildEAR never includes in the SAR.
     */
    private static final Set<String> SAR_EXTENSIONS = new HashSet<>(Arrays.asList(
        // Connection / transport
        ".rvtransport", ".sharedhttp", ".sharedjdbc", ".sharedjmscon", ".sharedjmsapp",
        ".httpProxy", ".sharedpartner",
        // Variables / locks
        ".sharedvariable", ".jobsharedvariable", ".sharedLock",
        // Service / security
        ".serviceagent", ".securityPolicy", ".securityPolicyAssociation",
        ".contextResource",
        // Schema / WSDL
        ".wsdl", ".xsd",
        // Identity / certificates
        ".id", ".cert",
        // Other resources
        ".properties", ".xslt", ".xsl",
        // Java Custom Functions
        ".javaxpath",
        // Companion data files (e.g. DocumentStore.xml alongside .sharedvariable)
        ".xml",
        // TIBCO shared parse schemas
        ".sharedparse"
    ));

    /**
     * File extensions for metadata files that are parsed for configuration but
     * NOT included in any archive.
     */
    private static final Set<String> METADATA_EXTENSIONS = new HashSet<>(Arrays.asList(
        ".substvar"
    ));

    /**
     * File extensions that are always excluded (TIBCO Designer metadata,
     * system schemas, version-control files, generic XML data files, etc.).
     * buildEAR only includes explicitly registered BW shared-resource types.
     */
    private static final Set<String> EXCLUDED_EXTENSIONS = new HashSet<>(Arrays.asList(
        ".folder", ".aeschema", ".dat", ".classpath"
    ));

    /**
     * File/directory names to exclude from packaging.
     * AESchemas is a standard TIBCO Designer system-schema directory.
     * vcrepo.dat is version-control metadata.
     */
    private static final Set<String> EXCLUDED_NAMES = new HashSet<>(Arrays.asList(
        ".DS_Store", "Thumbs.db", ".git", ".svn", "target", "AESchemas", "vcrepo.dat",
        ".designtimelibs", "Deployment", "library.manifest",
        // Deployment-time config and version-info directories present in some projlibs
        // — these are not BW shared resources and buildear never includes them in the EAR
        "config", "VersionInfo",
        // Maven build descriptors — not BW resources
        "pom.xml"
    ));

    @Inject
    private MavenProjectHelper projectHelper;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("bw5:bwear skipped.");
            return;
        }

        validateBwProjectPath();

        if (!skipResolveDependencies) resolveDependencies();

        File srcDir = bwSourcesDirectory.exists() ? bwSourcesDirectory : bwProjectPath;
        getLog().info("Assembling BW5 EAR from: " + srcDir.getAbsolutePath());
        getLog().info("Archive name: " + archiveName);

        try {
            // 0. Optionally load the .archive descriptor
            ArchiveDescriptorParser.ArchiveDescriptor archiveDescriptor = loadArchiveDescriptor();

            // 1. Collect and classify project files
            List<BwFile> parFiles = new ArrayList<>();
            List<BwFile> sarFiles = new ArrayList<>();
            List<BwFile> metadataFiles = new ArrayList<>();
            collectFiles(srcDir, srcDir, parFiles, sarFiles, metadataFiles);

            // Extract and collect files from projlib ZIP dependencies
            collectFilesFromProjlibs(parFiles, sarFiles, metadataFiles);

            // Promote serviceagents listed in processProperty from SAR to PAR
            if (archiveDescriptor != null && archiveDescriptor.hasExplicitProcessList()) {
                promoteServiceAgentsFromDescriptor(parFiles, sarFiles, archiveDescriptor.processPaths);
            }

            // Apply transitive dependency analysis from processProperty entry points.
            // This matches buildear behaviour: only reachable processes and referenced
            // resources are packaged; .javaxpath and sharedResources paths are always included.
            if (archiveDescriptor != null && archiveDescriptor.hasExplicitProcessList()) {
                applyTransitiveDependencyAnalysis(parFiles, sarFiles,
                    archiveDescriptor.processPaths, archiveDescriptor.sharedResourcePaths);
            }

            getLog().info("Process files (PAR): " + parFiles.size());
            getLog().info("Shared resource files (SAR): " + sarFiles.size());

            // 2. Parse process metadata for TIBCO.xml generation
            List<ProcessParser.ProcessMetadata> processMetadata = parseProcesses(parFiles, srcDir);

            // 3. Parse global variables from .substvar metadata files
            List<SubstVarParser.GlobalVariable> globalVars = parseGlobalVars(metadataFiles);
            getLog().info("Global variables: " + globalVars.size());

            // 4. Generate work directory for descriptor generation
            File workDir = new File(project.getBuild().getDirectory(), "bw5-assembly");
            if (!workDir.mkdirs() && !workDir.isDirectory()) {
                throw new MojoExecutionException("Failed to create directory: " + workDir.getAbsolutePath());
            }

            // 5. Determine PAR and SAR names: .archive descriptor takes precedence over defaults
            String parFileName = "Process Archive.par";
            if (archiveDescriptor != null
                    && archiveDescriptor.processArchiveName != null
                    && !archiveDescriptor.processArchiveName.isEmpty()) {
                parFileName = archiveDescriptor.processArchiveName + ".par";
            }
            if (archiveDescriptor != null
                    && archiveDescriptor.sharedArchiveName != null
                    && !archiveDescriptor.sharedArchiveName.isEmpty()) {
                sharedArchiveName = archiveDescriptor.sharedArchiveName;
            }
            File parFile = new File(workDir, parFileName);

            // Build SAR resource paths for EXTERNAL_DEPENDENCIES in PAR TIBCO.xml
            List<String> sarPaths = new ArrayList<>();
            for (BwFile bwf : sarFiles) {
                String path = bwf.relativePath.startsWith("/") ? bwf.relativePath : "/" + bwf.relativePath;
                sarPaths.add(path);
            }

            buildPar(parFile, parFiles, srcDir, processMetadata, sarPaths);
            getLog().info("PAR assembled: " + parFile.getName() + " (" + parFile.length() + " bytes)");

            // 6. Build the SAR (may be empty but always present)
            File sarFile = null;
            if (includeSharedArchive) {
                sarFile = new File(workDir, sharedArchiveName + ".sar");
                buildSar(sarFile, sarFiles, srcDir);
                getLog().info("SAR assembled: " + sarFile.getName() + " (" + sarFile.length() + " bytes)");
            }

            // 7. Generate EAR-level TIBCO.xml
            File earTibcoXml = new File(workDir, "TIBCO.xml");
            TibcoXmlGenerator generator = new TibcoXmlGenerator();
            generator.generateEarDescriptor(
                earTibcoXml,
                archiveName,
                parFileName,
                getProjectlibDependencies(),
                getJarDependencies(),
                globalVars,
                null
            );

            // 8. Cross-reference .aliaslib entries against Maven dependencies
            checkAliasLibs(srcDir, getJarDependencies());

            // 8b. Generate manifest-bw5.json (required by container runtimes)
            File manifestFile = null;
            if (!skipManifest) {
                List<File> sharedHttpFiles = new ArrayList<>();
                for (BwFile bwf : sarFiles) {
                    if (bwf.file.getName().toLowerCase(Locale.ROOT).endsWith(".sharedhttp")) {
                        sharedHttpFiles.add(bwf.file);
                    }
                }
                List<File> processFiles = new ArrayList<>();
                for (BwFile bwf : parFiles) {
                    if (bwf.file.getName().toLowerCase(Locale.ROOT).endsWith(".process")) {
                        processFiles.add(bwf.file);
                    }
                }
                manifestFile = new ManifestBw5Generator().generate(
                    archiveName, project.getVersion(), globalVars, sharedHttpFiles, processFiles, workDir);
                getLog().info("manifest-bw5.json generated.");
            }

            // 9. Assemble the final EAR
            String earFileName = project.getBuild().getFinalName() + ".ear";
            File earFile = new File(project.getBuild().getDirectory(), earFileName);
            buildEar(earFile, earTibcoXml, parFile, sarFile, manifestFile);

            getLog().info("EAR assembled: " + earFile.getAbsolutePath() + " (" + earFile.length() + " bytes)");

            // 9. Attach the EAR as the project artifact
            project.getArtifact().setFile(earFile);

            // 10. Apply property overrides and generate deployment configuration files
            if (!earOnly) {
                List<SubstVarParser.GlobalVariable> configuredVars = applyPropertyOverrides(globalVars);
                generateDeploymentConfigs(configuredVars);
            }

        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to assemble BW5 EAR: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    //  Property override merge
    // -----------------------------------------------------------------------

    private List<SubstVarParser.GlobalVariable> applyPropertyOverrides(
            List<SubstVarParser.GlobalVariable> vars) {
        try {
            PropertyMerger merger = new PropertyMerger();
            Map<String, String> mavenProps = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> e : project.getProperties().entrySet()) {
                if (e.getKey() instanceof String && e.getValue() instanceof String) {
                    mavenProps.put((String) e.getKey(), (String) e.getValue());
                }
            }
            List<SubstVarParser.GlobalVariable> merged =
                merger.merge(vars, globalPropertiesFile, projectPropertiesFile, mavenProps);
            long changed = 0;
            for (int i = 0; i < vars.size(); i++) {
                if (!Objects.equals(vars.get(i).value, merged.get(i).value)) changed++;
            }
            if (changed > 0) getLog().info("Property overrides applied: " + changed + " variable(s) changed");
            return merged;
        } catch (Exception e) {
            getLog().warn("Could not apply property overrides: " + e.getMessage() + " — using .substvar defaults");
            return vars;
        }
    }

    // -----------------------------------------------------------------------
    //  Deployment config generation
    // -----------------------------------------------------------------------

    private void generateDeploymentConfigs(List<SubstVarParser.GlobalVariable> globalVars)
            throws MojoExecutionException {

        if (earOnly || (!generateDeployXml && !generateProperties && !generateValuesYaml)) {
            return;
        }

        String finalName = project.getBuild().getFinalName();
        String appName   = project.getArtifactId();
        String appVersion = project.getVersion();
        File targetDir   = new File(project.getBuild().getDirectory());

        DeploymentConfigGenerator gen = new DeploymentConfigGenerator();

        try {
            if (generateDeployXml) {
                File out = new File(targetDir, finalName + "-deploy.xml");
                gen.generateDeployXml(out, appName, appVersion, globalVars);
                getLog().info("Generated deploy XML : " + out.getName());
            }
            if (generateProperties) {
                File out = new File(targetDir, finalName + "-deploy.properties");
                gen.generateProperties(out, appName, appVersion, globalVars);
                getLog().info("Generated properties : " + out.getName());
            }
            if (generateValuesYaml) {
                File out = new File(targetDir, finalName + "-values.yaml");
                gen.generateValuesYaml(out, appName, appVersion, globalVars);
                getLog().info("Generated values.yaml: " + out.getName());
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate deployment config files: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    //  File collection
    // -----------------------------------------------------------------------

    private void collectFiles(File rootDir, File dir, List<BwFile> parFiles,
                              List<BwFile> sarFiles, List<BwFile> metadataFiles) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            String name = f.getName();
            if (EXCLUDED_NAMES.contains(name)) continue;

            if (f.isDirectory()) {
                collectFiles(rootDir, f, parFiles, sarFiles, metadataFiles);
            } else {
                String ext = getExtension(name);
                if (EXCLUDED_EXTENSIONS.contains(ext)) {
                    // .folder files are TIBCO Designer display metadata; include when the flag
                    // is set so the SAR matches buildear output for sharedResources paths.
                    if (!includeFolderMetadata || !ext.equals(".folder")) continue;
                }

                String relativePath = rootDir.toURI().relativize(f.toURI()).getPath();
                BwFile bwf = new BwFile(f, relativePath);

                if (PAR_EXTENSIONS.contains(ext)) {
                    parFiles.add(bwf);
                } else if (METADATA_EXTENSIONS.contains(ext)) {
                    metadataFiles.add(bwf);
                } else if (SAR_EXTENSIONS.contains(ext)) {
                    sarFiles.add(bwf);
                } else {
                    // Unknown extension: include in SAR for safety
                    getLog().debug("Unknown BW file type, adding to SAR: " + relativePath);
                    sarFiles.add(bwf);
                }
            }
        }
    }

    private void collectFilesFromProjlibs(List<BwFile> parFiles, List<BwFile> sarFiles,
                                          List<BwFile> metadataFiles) throws IOException {
        if (!bwLibDirectory.exists()) return;
        File[] projlibs = bwLibDirectory.listFiles((dir, name) -> name.endsWith(".projlib"));
        if (projlibs == null || projlibs.length == 0) return;

        File extractRoot = new File(project.getBuild().getDirectory(), "bw-projlib-extracted");
        if (!extractRoot.mkdirs() && !extractRoot.isDirectory()) {
            throw new IOException("Failed to create directory: " + extractRoot.getAbsolutePath());
        }

        for (File projlib : projlibs) {
            String baseName = projlib.getName().replaceAll("\\.projlib$", "");
            File extractDir = new File(extractRoot, baseName);
            if (!extractDir.mkdirs() && !extractDir.isDirectory()) {
                throw new IOException("Failed to create directory: " + extractDir.getAbsolutePath());
            }

            getLog().debug("Extracting projlib: " + projlib.getName());
            try (ZipFile zip = new ZipFile(projlib)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    File outFile = new File(extractDir, entry.getName());
                    File parentFile = outFile.getParentFile();
                    if (!parentFile.mkdirs() && !parentFile.isDirectory()) {
                        throw new IOException("Failed to create directory: " + parentFile.getAbsolutePath());
                    }
                    try (InputStream is = zip.getInputStream(entry);
                         OutputStream os = new FileOutputStream(outFile)) {
                        IOUtils.copy(is, os);
                    }
                }
            }

            int beforePar = parFiles.size();
            int beforeSar = sarFiles.size();
            collectFiles(extractDir, extractDir, parFiles, sarFiles, metadataFiles);
            getLog().info("Projlib " + projlib.getName() + ": +"
                + (parFiles.size() - beforePar) + " process(es), +"
                + (sarFiles.size() - beforeSar) + " shared resource(s)");
        }
    }

    // -----------------------------------------------------------------------
    //  Process metadata parsing
    // -----------------------------------------------------------------------

    private List<ProcessParser.ProcessMetadata> parseProcesses(List<BwFile> parFiles, File srcDir) {
        List<ProcessParser.ProcessMetadata> result = new ArrayList<>();
        ProcessParser parser = new ProcessParser();
        for (BwFile bwf : parFiles) {
            try {
                ProcessParser.ProcessMetadata meta = parser.parse(bwf.file);
                if (meta.name == null || meta.name.isEmpty()) {
                    // Use relative path as fallback name
                    meta.name = bwf.relativePath;
                }
                result.add(meta);
            } catch (Exception e) {
                getLog().warn("Could not parse process file: " + bwf.file.getName() + " - " + e.getMessage());
                // Still add with minimal metadata
                ProcessParser.ProcessMetadata meta = new ProcessParser.ProcessMetadata();
                meta.name = bwf.relativePath;
                result.add(meta);
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    //  Global variable parsing
    // -----------------------------------------------------------------------

    private List<SubstVarParser.GlobalVariable> parseGlobalVars(List<BwFile> metadataFiles) {
        List<SubstVarParser.GlobalVariable> result = new ArrayList<>();
        SubstVarParser parser = new SubstVarParser();
        Set<String> seen = new LinkedHashSet<>();

        for (BwFile bwf : metadataFiles) {
            if (!bwf.file.getName().endsWith(".substvar")) continue;
            try {
                String prefix = computeGvPrefix(bwf.relativePath);
                List<SubstVarParser.GlobalVariable> vars = parser.parse(bwf.file);
                for (SubstVarParser.GlobalVariable var : vars) {
                    var.substVarFile = bwf.file.getName();
                    var.name = prefix + var.name;
                    if (seen.add(var.name)) {
                        result.add(var);
                    }
                }
            } catch (Exception e) {
                getLog().warn("Could not parse substvar file: " + bwf.file.getName() + " - " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * Scans {@code srcDir} recursively for {@code .sharedjdbc} files and returns their paths
     * in the form {@code /relative/path/WithoutExtension}, which is the format used by
     * {@code <chk:availableSharedResourceName>} in the PAR-level TIBCO.xml.
     */
    private List<String> scanJdbcResourcePaths(File srcDir) {
        List<String> paths = new ArrayList<>();
        scanJdbcResourcePathsRecursive(srcDir, srcDir, paths);
        java.util.Collections.sort(paths);
        return paths;
    }

    private void scanJdbcResourcePathsRecursive(File rootDir, File dir, List<String> paths) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanJdbcResourcePathsRecursive(rootDir, f, paths);
            } else if (f.getName().endsWith(".sharedjdbc")) {
                String rel = rootDir.toURI().relativize(f.toURI()).getPath();
                // strip extension and add leading slash
                String path = "/" + rel.substring(0, rel.lastIndexOf('.'));
                paths.add(path);
            }
        }
    }

    /**
     * Derives the global-variable folder prefix from a .substvar file's relative path.
     * BW5 stores substvar files in {@code defaultVars/<FolderA>/<FolderB>/defaultVars.substvar};
     * the corresponding TIBCO.xml name prefix is {@code FolderA/FolderB/}.
     */
    // -----------------------------------------------------------------------
    //  Archive descriptor (.archive) support
    // -----------------------------------------------------------------------

    /**
     * Scans all {@code .aliaslib} files under {@code srcDir} and cross-references their
     * entries against the resolved Maven JAR dependencies.
     *
     * <p>For every alias with {@code includeInDeployment=true} that cannot be matched to a
     * Maven dependency by filename ({@code artifactId-version.jar}), a WARNING is logged.
     * The build is not failed — the alias may reference a system JAR intentionally excluded
     * from Maven.</p>
     */
    private void checkAliasLibs(File srcDir, List<Artifact> jarDeps) {
        List<AliasLibParser.AliasLibEntry> entries;
        try {
            entries = new AliasLibParser().parseAll(srcDir);
        } catch (Exception e) {
            getLog().warn("Could not parse .aliaslib files: " + e.getMessage());
            return;
        }

        if (entries.isEmpty()) return;

        // Build a set of expected JAR filenames from Maven deps: artifactId-version.jar
        Set<String> mavenJarFileNames = new HashSet<>();
        for (Artifact jar : jarDeps) {
            mavenJarFileNames.add(jar.getArtifactId() + "-" + jar.getVersion() + ".jar");
        }

        int matched = 0;
        int unmatched = 0;
        for (AliasLibParser.AliasLibEntry entry : entries) {
            if (!entry.includeInDeployment) continue;
            if (mavenJarFileNames.contains(entry.aliasName)) {
                matched++;
            } else {
                unmatched++;
                getLog().warn(".aliaslib entry not found in Maven dependencies: \""
                    + entry.aliasName + "\""
                    + " — add a <dependency> with the matching artifactId/version"
                    + " or this JAR will be missing from the EAR at runtime.");
            }
        }

        if (matched > 0 || unmatched > 0) {
            getLog().info(".aliaslib check: " + matched + " matched, " + unmatched + " unmatched"
                + " (scanned " + entries.size() + " entries in all .aliaslib files)");
        }
    }

    private ArchiveDescriptorParser.ArchiveDescriptor loadArchiveDescriptor()
            throws MojoExecutionException {
        if (archiveDescriptorFile == null) {
            return null;
        }
        if (!archiveDescriptorFile.exists()) {
            throw new MojoExecutionException(
                "Archive descriptor not found: " + archiveDescriptorFile.getAbsolutePath());
        }
        try {
            ArchiveDescriptorParser parser = new ArchiveDescriptorParser();
            ArchiveDescriptorParser.ArchiveDescriptor descriptor = parser.parse(archiveDescriptorFile);
            getLog().info("Using .archive descriptor: " + archiveDescriptorFile.getName()
                + " (" + descriptor + ")");
            return descriptor;
        } catch (Exception e) {
            throw new MojoExecutionException(
                "Failed to parse .archive descriptor '"
                + archiveDescriptorFile.getName() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Moves {@code .serviceagent} files listed in the {@code .archive} descriptor's
     * {@code processProperty} from {@code sarFiles} to {@code parFiles}.
     *
     * <p>TIBCO buildear puts service agents that are declared as process entry points
     * (i.e. listed in processProperty) into the PAR rather than the SAR. This matches
     * the TIBCO Designer archive model where service agents are startup entries for the
     * process archive.</p>
     */
    private void promoteServiceAgentsFromDescriptor(List<BwFile> parFiles, List<BwFile> sarFiles,
                                                    List<String> descriptorPaths) {
        Iterator<BwFile> sarIter = sarFiles.iterator();
        while (sarIter.hasNext()) {
            BwFile bwf = sarIter.next();
            if (!bwf.file.getName().endsWith(".serviceagent")) continue;
            String rel = bwf.relativePath.replace('\\', '/');
            for (String descriptorPath : descriptorPaths) {
                String normalized = descriptorPath.startsWith("/")
                    ? descriptorPath.substring(1) : descriptorPath;
                if (rel.equals(normalized) || rel.endsWith("/" + normalized)
                        || rel.equals(descriptorPath) || rel.endsWith(descriptorPath)) {
                    sarIter.remove();
                    parFiles.add(bwf);
                    getLog().info("Promoting serviceagent to PAR (processProperty): " + rel);
                    break;
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Transitive dependency analysis
    // -----------------------------------------------------------------------

    /** BW5 process XML element names whose text content is a BW resource path. */
    private static final List<String> RESOURCE_REF_ELEMENTS = Arrays.asList(
        "processName",          // subprocess call (com.tibco.pe.core.CallProcessActivity)
        "ConnectionReference",  // JMS / HTTP / partner connections
        "variableConfig",       // shared variables and job shared variables
        "stylesheet",           // XSLT transform references
        "JavaGlobalInstance",   // service agent instances (Cache, EMS, Scheduler…)
        "ParseSharedConfig",    // shared parse schemas
        "JavaSchemaResource"    // Java-defined schema resources (.javaschema)
    );

    /**
     * Replaces {@code parFiles} and {@code sarFiles} in-place with only the processes
     * and resources reachable from the {@code processProperty} entry points via
     * transitive subprocess-call and resource-reference analysis.
     *
     * <p>Always-include sets:</p>
     * <ul>
     *   <li>{@code .javaxpath} (Java Custom Function) files — XPath function calls are
     *       not statically traceable.</li>
     *   <li>Files matching the {@code sharedResources} paths declared in the
     *       {@code .archive} descriptor — buildear includes these unconditionally,
     *       e.g. an entire schema directory or a config deployment tree.</li>
     * </ul>
     *
     * <p>Non-process entries already in {@code parFiles} (e.g. promoted serviceagents)
     * are preserved unconditionally.</p>
     */
    private void applyTransitiveDependencyAnalysis(List<BwFile> parFiles, List<BwFile> sarFiles,
                                                   List<String> entryPoints,
                                                   List<String> sharedResourcePaths)
            throws MojoExecutionException {
        // Separate out promoted service agents — they stay in PAR unconditionally
        List<BwFile> promotedParEntries = new ArrayList<>();
        List<BwFile> processFiles = new ArrayList<>();
        for (BwFile f : parFiles) {
            if (f.file.getName().endsWith(".process")) {
                processFiles.add(f);
            } else {
                promotedParEntries.add(f);
            }
        }

        // Separate always-include SAR files from files subject to transitive filtering:
        //   • .javaxpath — XPath function calls are not statically traceable
        //   • files under sharedResources paths — buildear includes these unconditionally
        List<BwFile> alwaysInclude = new ArrayList<>();
        List<BwFile> otherSarFiles = new ArrayList<>();
        for (BwFile f : sarFiles) {
            if (f.file.getName().endsWith(".javaxpath")
                    || isUnderSharedResourcePath(f, sharedResourcePaths)) {
                alwaysInclude.add(f);
            } else {
                otherSarFiles.add(f);
            }
        }

        // Build path → BwFile indices
        Map<String, BwFile> processIndex = buildBwIndex(processFiles);
        Map<String, BwFile> resourceIndex = buildBwIndex(otherSarFiles);

        // BFS from .process entry points
        Set<String> visitedProcessPaths = new LinkedHashSet<>();
        Set<String> referencedResourcePaths = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>();

        // Validate that every .process declared in the .archive descriptor exists on disk.
        // These are explicit entry points — a missing file is always a build error.
        List<String> missingEntryPoints = new ArrayList<>();
        for (String ep : entryPoints) {
            String norm = normalizeBwPath(ep);
            if (norm.endsWith(".process")) {
                if (!processIndex.containsKey(norm)) {
                    missingEntryPoints.add(ep);
                } else {
                    queue.add(norm);
                }
            }
            // .serviceagent entries already handled by promoteServiceAgentsFromDescriptor
        }
        if (!missingEntryPoints.isEmpty()) {
            StringBuilder msg = new StringBuilder(
                "Process file(s) declared in .archive descriptor not found on disk:");
            for (String missing : missingEntryPoints) {
                msg.append("\n  - ").append(missing);
            }
            throw new MojoExecutionException(msg.toString());
        }

        while (!queue.isEmpty()) {
            String path = queue.poll();
            if (!visitedProcessPaths.add(path)) continue;

            BwFile bwf = processIndex.get(path);
            if (bwf == null) {
                // Transitively discovered reference — may be a dynamic call; skip silently.
                getLog().debug("Transitive: process not found: " + path);
                continue;
            }

            for (String ref : extractBwResourceRefs(bwf.file)) {
                String norm = normalizeBwPath(ref);
                if (norm.endsWith(".process")) {
                    if (!visitedProcessPaths.contains(norm)) queue.add(norm);
                } else if (referencedResourcePaths.add(norm)) {
                    // Add companion .xml for sharedvariable / jobsharedvariable
                    if (norm.endsWith(".sharedvariable") || norm.endsWith(".jobsharedvariable")) {
                        referencedResourcePaths.add(norm.replaceAll("\\.[^.]+$", ".xml"));
                    }
                    // Follow schemaLocation refs declared inside shared-resource files:
                    // .sharedvariable, .jobsharedvariable, and .sharedparse all embed an
                    // <import schemaLocation="..."/> that points to the XSD they're typed by.
                    if (norm.endsWith(".sharedvariable") || norm.endsWith(".jobsharedvariable")
                            || norm.endsWith(".sharedparse")) {
                        followSharedResourceRefs(norm, resourceIndex, referencedResourcePaths);
                    }
                    // Follow XSD imports transitively
                    if (norm.endsWith(".xsd")) {
                        followXsdImports(norm, resourceIndex, referencedResourcePaths);
                    }
                }
            }
        }

        // Rebuild PAR: promoted entries + reachable processes
        List<BwFile> reachableProcesses = new ArrayList<>();
        for (String path : visitedProcessPaths) {
            BwFile f = processIndex.get(path);
            if (f != null) reachableProcesses.add(f);
        }

        // Rebuild SAR: alwaysInclude + transitively reachable resources (deduped)
        Set<String> sarPathsSeen = new LinkedHashSet<>();
        for (BwFile f : alwaysInclude) {
            sarPathsSeen.add(normalizeBwPath(f.relativePath));
        }
        List<BwFile> reachableResources = new ArrayList<>(alwaysInclude);
        for (String path : referencedResourcePaths) {
            BwFile f = resourceIndex.get(path);
            if (f != null && sarPathsSeen.add(normalizeBwPath(f.relativePath))) {
                reachableResources.add(f);
            }
        }

        getLog().info("Transitive analysis: "
            + reachableProcesses.size() + "/" + processFiles.size() + " processes, "
            + reachableResources.size() + "/" + (otherSarFiles.size() + alwaysInclude.size())
            + " resources (always-include: " + alwaysInclude.size() + ")");

        parFiles.clear();
        parFiles.addAll(promotedParEntries);
        parFiles.addAll(reachableProcesses);
        sarFiles.clear();
        sarFiles.addAll(reachableResources);
    }

    /**
     * Returns {@code true} if the file's BW path (its relative path, normalized) starts
     * with any of the {@code sharedResources} directory prefixes declared in the
     * {@code .archive} descriptor.
     */
    private boolean isUnderSharedResourcePath(BwFile f, List<String> sharedResourcePaths) {
        if (sharedResourcePaths == null || sharedResourcePaths.isEmpty()) return false;
        String normPath = normalizeBwPath(f.relativePath);
        for (String srPath : sharedResourcePaths) {
            String normSrPath = normalizeBwPath(srPath);
            if (normPath.startsWith(normSrPath + "/") || normPath.equals(normSrPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Follows {@code schemaLocation} attributes declared inside a shared-resource file
     * ({@code .sharedvariable}, {@code .jobsharedvariable}, or {@code .sharedparse}).
     *
     * <p>These files embed an {@code <import schemaLocation="/..."/>} pointing to the
     * XSD that defines their value type. buildear includes those XSDs in the SAR even
     * if they are not directly referenced by any reachable process.</p>
     */
    private void followSharedResourceRefs(String resourcePath, Map<String, BwFile> resourceIndex,
                                          Set<String> visited) {
        BwFile resourceFile = resourceIndex.get(resourcePath);
        if (resourceFile == null) return;
        for (String ref : extractBwResourceRefs(resourceFile.file)) {
            String norm = normalizeBwPath(ref);
            if (!norm.endsWith(".process") && visited.add(norm)) {
                if (norm.endsWith(".xsd")) {
                    followXsdImports(norm, resourceIndex, visited);
                }
            }
        }
    }

    private Map<String, BwFile> buildBwIndex(List<BwFile> files) {
        Map<String, BwFile> index = new HashMap<>();
        for (BwFile f : files) {
            index.put(normalizeBwPath(f.relativePath), f);
        }
        return index;
    }

    /**
     * Recursively follows {@code schemaLocation} imports in an XSD file, adding
     * every imported XSD path to {@code visited} (prevents cycles).
     */
    private void followXsdImports(String xsdPath, Map<String, BwFile> resourceIndex,
                                  Set<String> visited) {
        BwFile xsdFile = resourceIndex.get(xsdPath);
        if (xsdFile == null) return;
        for (String ref : extractBwResourceRefs(xsdFile.file)) {
            String norm = normalizeBwPath(ref);
            if (norm.endsWith(".xsd") && visited.add(norm)) {
                followXsdImports(norm, resourceIndex, visited);
            }
        }
    }

    /**
     * Parses a BW5 process (or XSD) file and returns all BW resource paths it references.
     *
     * <p>Extracted from:
     * <ul>
     *   <li>Text content of elements named in {@link #RESOURCE_REF_ELEMENTS}</li>
     *   <li>{@code schemaLocation} attributes (XSD imports in process namespaces)</li>
     * </ul>
     * Only paths starting with {@code /} are returned (absolute BW project paths).</p>
     */
    private Set<String> extractBwResourceRefs(File file) {
        Set<String> refs = new LinkedHashSet<>();
        try {
            SAXBuilder builder = new SAXBuilder();
            Document doc = builder.build(file);
            for (Element e : doc.getDescendants(Filters.element())) {
                // Element text references
                if (RESOURCE_REF_ELEMENTS.contains(e.getName())) {
                    String text = e.getTextTrim();
                    if (text.startsWith("/")) refs.add(text);
                }
                // schemaLocation attribute (xsd:import in process + XSD files)
                String schemaLoc = e.getAttributeValue("schemaLocation");
                if (schemaLoc != null && schemaLoc.startsWith("/")) refs.add(schemaLoc);
            }
        } catch (org.jdom2.JDOMException | IOException e) {
            getLog().debug("Could not parse refs from " + file.getName() + ": " + e.getMessage());
        }
        return refs;
    }

    private static String normalizeBwPath(String path) {
        if (path == null) return "";
        String s = path.replace('\\', '/').trim();
        return s.startsWith("/") ? s.substring(1) : s;
    }

    // -----------------------------------------------------------------------
    //  PAR assembly
    // -----------------------------------------------------------------------

    private void buildPar(File parFile, List<BwFile> parFiles, File srcDir,
                          List<ProcessParser.ProcessMetadata> processMetadata,
                          List<String> sarPaths) throws Exception {
        // Generate PAR-level TIBCO.xml into a temp file
        File parTibcoXml = File.createTempFile("par-TIBCO", ".xml");
        parTibcoXml.deleteOnExit();
        new TibcoXmlGenerator().generateParDescriptor(
            parTibcoXml,
            parFile.getName(),
            processMetadata,
            sarPaths,
            scanJdbcResourcePaths(srcDir),
            null
        );

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(parFile))) {
            zos.setLevel(Deflater.DEFAULT_COMPRESSION);

            // Add TIBCO.xml
            addToZip(zos, "TIBCO.xml", parTibcoXml);

            // Add process files preserving directory structure
            Set<String> added = new HashSet<>();
            for (BwFile bwf : parFiles) {
                String entryName = bwf.relativePath.replace(File.separatorChar, '/');
                if (!added.add(entryName)) {
                    getLog().debug("Skipping duplicate PAR entry: " + entryName);
                    continue;
                }
                addToZip(zos, bwf.relativePath, bwf.file);
            }

            // Add compiled Java classes if any
            addClassesToPar(zos);
        }
    }

    private void addClassesToPar(ZipOutputStream zos) throws IOException {
        if (!javaClassesDirectory.exists()) return;
        Path classesPath = javaClassesDirectory.toPath();
        Files.walkFileTree(classesPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relative = classesPath.relativize(file).toString().replace(File.separatorChar, '/');
                addToZip(zos, "JavaCode/" + relative, file.toFile());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // -----------------------------------------------------------------------
    //  SAR assembly
    // -----------------------------------------------------------------------

    private void buildSar(File sarFile, List<BwFile> sarFiles, File srcDir) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(sarFile))) {
            zos.setLevel(Deflater.DEFAULT_COMPRESSION);
            Set<String> added = new HashSet<>();
            for (BwFile bwf : sarFiles) {
                String entryName = bwf.relativePath.replace(File.separatorChar, '/');
                if (!added.add(entryName)) {
                    getLog().debug("Skipping duplicate SAR entry: " + entryName);
                    continue;
                }
                if (bwf.file.getName().endsWith(".javaxpath")) {
                    addJavaxpathToSar(zos, bwf);
                } else {
                    addToZip(zos, bwf.relativePath, bwf.file);
                }
            }
        }
    }

    private void addJavaxpathToSar(ZipOutputStream zos, BwFile bwf) throws Exception {
        String className = extractJcfClassName(bwf.file);
        String encodedBytecode = className != null
            ? project.getProperties().getProperty("bw5.jcf.bytecode." + className)
            : null;

        if (encodedBytecode != null) {
            File tempFile = injectJcfBytecode(bwf.file, encodedBytecode);
            try {
                addToZip(zos, bwf.relativePath, tempFile);
            } finally {
                if (!tempFile.delete()) {
                    getLog().warn("Could not delete temp file: " + tempFile);
                }
            }
        } else if (!oldJavaCustomFunctions) {
            String displayName = className != null ? className : bwf.file.getName();
            throw new MojoExecutionException(
                "No compiled bytecode found for Java Custom Function: " + displayName + ". "
                + "Ensure the class is compiled before packaging, or set "
                + "bw5.oldJavaCustomFunctions=true to use the bytecode already present "
                + "in the .javaxpath file.");
        } else {
            String displayClass = className != null ? className : "(unknown)";
            getLog().warn("[bw5] WARNING: Using pre-existing bytecode in " + bwf.file.getName()
                + " for class " + displayClass + ". Recompile to embed current bytecode.");
            addToZip(zos, bwf.relativePath, bwf.file);
        }
    }

    /**
     * Returns a temp file containing the .javaxpath XML with {@code <ns0:bytecode>} replaced
     * by {@code encodedBytecode}. Caller is responsible for deleting the temp file.
     */
    private File injectJcfBytecode(File javaxpathFile, String encodedBytecode) throws Exception {
        SAXBuilder builder = new SAXBuilder();
        Document doc = builder.build(javaxpathFile);
        Element root = doc.getRootElement();

        Element bytecodeEl = root.getChild("bytecode", JCF_NS);
        if (bytecodeEl == null) {
            bytecodeEl = new Element("bytecode", JCF_NS);
            root.addContent(bytecodeEl);
        }
        bytecodeEl.setText(encodedBytecode);

        File temp = File.createTempFile("bw5-jcf-", ".javaxpath");
        temp.deleteOnExit();
        XMLOutputter xmlOut = new XMLOutputter(Format.getRawFormat().setEncoding("UTF-8"));
        try (Writer w = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            xmlOut.output(doc, w);
        }
        return temp;
    }

    /**
     * Derives the fully-qualified class name from {@code <ns0:loadedFromLocation>} by
     * extracting the path segment after {@code "classes/"}. Returns {@code null} when the
     * element is absent or the path contains no {@code "classes/"} segment.
     */
    private String extractJcfClassName(File javaxpathFile) {
        try {
            SAXBuilder builder = new SAXBuilder();
            Document doc = builder.build(javaxpathFile);
            Element root = doc.getRootElement();
            Element locationEl = root.getChild("loadedFromLocation", JCF_NS);
            if (locationEl == null) return null;

            String location = locationEl.getTextTrim().replace('\\', '/');
            int idx = location.lastIndexOf("classes/");
            if (idx < 0) return null;

            String relative = location.substring(idx + "classes/".length());
            if (relative.endsWith(".class")) {
                relative = relative.substring(0, relative.length() - ".class".length());
            }
            return relative.replace('/', '.');
        } catch (org.jdom2.JDOMException | IOException e) {
            getLog().debug("Could not extract class name from " + javaxpathFile.getName()
                + ": " + e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    //  EAR assembly
    // -----------------------------------------------------------------------

    private void buildEar(File earFile, File tibcoXml, File parFile,
                          File sarFile, File manifestFile) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(earFile))) {
            zos.setLevel(Deflater.DEFAULT_COMPRESSION);

            addToZip(zos, "TIBCO.xml", tibcoXml);
            addToZip(zos, parFile.getName(), parFile);

            if (sarFile != null && sarFile.exists()) {
                addToZip(zos, sharedArchiveName + ".sar", sarFile);
            }

            if (manifestFile != null && manifestFile.exists()) {
                addToZip(zos, "manifest-bw5.json", manifestFile);
            }
        }
    }

    // -----------------------------------------------------------------------
    //  ZIP helpers
    // -----------------------------------------------------------------------

    private void addToZip(ZipOutputStream zos, String entryName, File file) throws IOException {
        // Normalize path separators
        entryName = entryName.replace(File.separatorChar, '/');

        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(file.lastModified());
        zos.putNextEntry(entry);
        try (InputStream in = new FileInputStream(file)) {
            IOUtils.copy(in, zos);
        }
        zos.closeEntry();
    }

    // -----------------------------------------------------------------------
    //  Utilities
    // -----------------------------------------------------------------------

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    /**
     * Simple tuple of a file and its relative path within the BW project.
     */
    private static class BwFile {
        final File file;
        final String relativePath;

        BwFile(File file, String relativePath) {
            this.file = file;
            this.relativePath = relativePath;
        }
    }
}

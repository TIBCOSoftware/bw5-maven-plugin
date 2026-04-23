package com.tibco.bw.maven.plugin.packaging;

import com.tibco.bw.maven.plugin.descriptor.AliasLibParser;
import com.tibco.bw.maven.plugin.descriptor.ArchiveDescriptorParser;
import com.tibco.bw.maven.plugin.descriptor.DeploymentConfigGenerator;
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
     *   <li>Which processes to include in the PAR (explicit list from
     *       {@code processArchive/processProperty}). Processes not listed are excluded.</li>
     *   <li>The PAR name (from {@code processArchive/@name}), overriding
     *       {@code "Process Archive.par"} if the descriptor specifies a different name.</li>
     *   <li>The SAR name (from {@code sharedArchive/@name}), overriding
     *       {@code bw5.sharedArchiveName} if the descriptor specifies a name.</li>
     * </ul>
     *
     * <p>When not set (default), all {@code .process} files in the project are included
     * and archive names use their configured defaults — matching {@code buildEAR}
     * behaviour for projects where the {@code .archive} includes everything.</p>
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
        ".javaxpath"
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
        ".folder", ".aeschema", ".dat", ".classpath", ".xml"
    ));

    /**
     * File/directory names to exclude from packaging.
     * AESchemas is a standard TIBCO Designer system-schema directory.
     * vcrepo.dat is version-control metadata.
     */
    private static final Set<String> EXCLUDED_NAMES = new HashSet<>(Arrays.asList(
        ".DS_Store", "Thumbs.db", ".git", ".svn", "target", "AESchemas", "vcrepo.dat",
        ".designtimelibs"
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

            // Apply .archive process filter if an explicit list is specified
            if (archiveDescriptor != null && archiveDescriptor.hasExplicitProcessList()) {
                parFiles = filterProcessesByDescriptor(parFiles, archiveDescriptor.processPaths, srcDir);
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
            workDir.mkdirs();

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

            // 9. Assemble the final EAR
            String earFileName = project.getBuild().getFinalName() + ".ear";
            File earFile = new File(project.getBuild().getDirectory(), earFileName);
            buildEar(earFile, earTibcoXml, parFile, sarFile);

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
                if (EXCLUDED_EXTENSIONS.contains(ext)) continue;

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
        // Track variable names to avoid duplicates (multiple .substvar files may define same vars)
        Set<String> seen = new LinkedHashSet<>();

        for (BwFile bwf : metadataFiles) {
            if (!bwf.file.getName().endsWith(".substvar")) continue;
            try {
                List<SubstVarParser.GlobalVariable> vars = parser.parse(bwf.file);
                for (SubstVarParser.GlobalVariable var : vars) {
                    var.substVarFile = bwf.file.getName();
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

    private ArchiveDescriptorParser.ArchiveDescriptor loadArchiveDescriptor() {
        if (archiveDescriptorFile == null) {
            return null;
        }
        if (!archiveDescriptorFile.exists()) {
            getLog().warn("Archive descriptor not found, ignoring: " + archiveDescriptorFile.getAbsolutePath());
            return null;
        }
        try {
            ArchiveDescriptorParser parser = new ArchiveDescriptorParser();
            ArchiveDescriptorParser.ArchiveDescriptor descriptor = parser.parse(archiveDescriptorFile);
            getLog().info("Using .archive descriptor: " + archiveDescriptorFile.getName()
                + " (" + descriptor + ")");
            return descriptor;
        } catch (Exception e) {
            getLog().warn("Could not parse .archive descriptor: " + e.getMessage()
                + " — falling back to full directory scan");
            return null;
        }
    }

    /**
     * Filters the collected process files to only those listed in the {@code .archive}
     * descriptor's {@code processProperty}.
     *
     * <p>Process paths in the descriptor may include a project-folder prefix
     * (e.g. {@code /MyApp/Start.process}). The file is matched if the collected
     * relative path ends with the descriptor path (with or without leading slash),
     * or equals it exactly.</p>
     */
    private List<BwFile> filterProcessesByDescriptor(List<BwFile> parFiles,
            List<String> descriptorPaths, File srcDir) {
        List<BwFile> filtered = new ArrayList<>();
        for (String descriptorPath : descriptorPaths) {
            // Normalize: strip leading slash for comparison
            String normalized = descriptorPath.startsWith("/")
                ? descriptorPath.substring(1) : descriptorPath;
            boolean found = false;
            for (BwFile bwf : parFiles) {
                String rel = bwf.relativePath.replace('\\', '/');
                if (rel.equals(normalized) || rel.endsWith("/" + normalized)
                        || rel.equals(descriptorPath) || rel.endsWith(descriptorPath)) {
                    filtered.add(bwf);
                    found = true;
                    break;
                }
            }
            if (!found) {
                getLog().warn(".archive lists process not found in project: " + descriptorPath);
            }
        }
        getLog().info("Process filter from .archive: " + filtered.size()
            + "/" + parFiles.size() + " processes included");
        return filtered;
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
            null
        );

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(parFile))) {
            zos.setLevel(Deflater.DEFAULT_COMPRESSION);

            // Add TIBCO.xml
            addToZip(zos, "TIBCO.xml", parTibcoXml);

            // Add process files preserving directory structure
            for (BwFile bwf : parFiles) {
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
            for (BwFile bwf : sarFiles) {
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
                tempFile.delete();
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
        } catch (Exception e) {
            getLog().debug("Could not extract class name from " + javaxpathFile.getName()
                + ": " + e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    //  EAR assembly
    // -----------------------------------------------------------------------

    private void buildEar(File earFile, File tibcoXml, File parFile, File sarFile) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(earFile))) {
            zos.setLevel(Deflater.DEFAULT_COMPRESSION);

            addToZip(zos, "TIBCO.xml", tibcoXml);
            addToZip(zos, parFile.getName(), parFile);

            if (sarFile != null && sarFile.exists()) {
                addToZip(zos, sharedArchiveName + ".sar", sarFile);
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
        return dot >= 0 ? filename.substring(dot).toLowerCase() : "";
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

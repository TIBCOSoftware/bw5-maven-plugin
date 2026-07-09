package com.tibco.bw.maven.plugin.packaging;

import com.tibco.bw.maven.plugin.descriptor.AliasLibParser;
import com.tibco.bw.maven.plugin.descriptor.ArchiveDescriptorParser;
import com.tibco.bw.maven.plugin.descriptor.DeploymentConfigGenerator;
import com.tibco.bw.maven.plugin.descriptor.ManifestBw5Generator;
import com.tibco.bw.maven.plugin.descriptor.ProcessParser;
import com.tibco.bw.maven.plugin.descriptor.PropertyMerger;
import com.tibco.bw.maven.plugin.descriptor.SubstVarParser;
import com.tibco.bw.maven.plugin.descriptor.TibcoXmlGenerator;
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
import org.jdom2.JDOMException;
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
     * Path to a TIBCO Designer {@code .archive} descriptor file.
     *
     * <p>When provided (or auto-discovered in {@code bwProjectPath}), the plugin reads
     * the descriptor to determine:</p>
     * <ul>
     *   <li>The PAR name (from {@code processArchive/@name}), overriding
     *       {@code "Process Archive.par"} if the descriptor specifies a different name.</li>
     *   <li>The SAR name (from {@code sharedArchive/@name}), overriding
     *       {@code bw5.sharedArchiveName} if the descriptor specifies a name.</li>
     *   <li>Which processes to include in the PAR — only processes reachable by BFS from
     *       the declared {@code processProperty} entry points are packaged, matching
     *       TIBCO Designer {@code buildear} behaviour. Orphan processes (not reachable
     *       from any entry point) are excluded.</li>
     * </ul>
     *
     * <p>If this parameter is not set, the plugin scans the top level of
     * {@code bwProjectPath} for a {@code *.archive} file and uses it automatically.
     * If no {@code .archive} file is found, all {@code .process} files are included.</p>
     *
     * <p>Configure this explicitly only when the {@code .archive} file is not at the
     * project root, for example:</p>
     * <pre>{@code
     * <configuration>
     *   <archiveDescriptorFile>${basedir}/archives/MyApp.archive</archiveDescriptorFile>
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
    @Parameter(defaultValue = "false", property = "bw5.includeFolderMetadata")
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
     * Explicit SAR extensions for resource types that do NOT follow the {@code .shared*} /
     * {@code .jobshared*} naming convention. All {@code .shared*} and {@code .jobshared*}
     * extensions are covered generically by {@link #isSarExtension} — adding a new TIBCO
     * palette connection type (e.g. {@code .sharedsap}) requires no code change.
     *
     * <p>Use {@link #isSarExtension(String)} rather than querying this set directly.</p>
     */
    /**
     * Returns {@code true} if {@code ext} identifies a BW5 shared-archive (SAR) resource type.
     *
     * <p>Two rules apply:</p>
     * <ol>
     *   <li>Any extension starting with {@code .shared} is a SAR type — this covers all
     *       current TIBCO palette connection types ({@code .sharedhttp}, {@code .sharedjdbc},
     *       {@code .sharedjmscon}, {@code .sharedftp}, etc.) and any future ones without
     *       requiring a code change.</li>
     *   <li>Extensions listed in {@link #SAR_EXTENSIONS} (non-{@code .shared*} types that
     *       are nevertheless SAR resources).</li>
     * </ol>
     */
    private static boolean isSarExtension(String ext) {
        if (ext == null || ext.isEmpty()) return false;
        return ext.startsWith(".shared") || SAR_EXTENSIONS.contains(ext);
    }

    /**
     * Explicit SAR extensions for resource types whose names do NOT start with {@code .shared}.
     * All {@code .shared*} extensions are handled generically by {@link #isSarExtension}.
     *
     * <p>Use {@link #isSarExtension(String)} rather than querying this set directly.</p>
     */
    private static final Set<String> SAR_EXTENSIONS = new HashSet<>(Arrays.asList(
        // Transport (not .shared* prefixed)
        ".rvtransport", ".httpproxy",
        // Fixed shared type (only one variant exists)
        ".jobsharedvariable",
        // Service / security
        ".serviceagent", ".securitypolicy", ".securitypolicyassociation",
        ".contextresource",
        // Schema / WSDL
        ".wsdl", ".xsd",
        // AE adapter schemas (adapter projects reference via /AESchemas/...)
        ".aeschema",
        // Identity / certificates
        ".id", ".cert",
        // Other resources
        ".properties", ".xslt", ".xsl",
        // Java Custom Functions
        ".javaxpath",
        // Companion data files (e.g. DocumentStore.xml alongside .sharedvariable)
        ".xml",
        // Adapter configuration descriptors
        ".adapter",
        // Adapter definition files: included in SAR when any process references them via
        // ae.aepalette.sharedProperties.adapterService.  Pure adapter-only archives (no
        // processArchive) leave these files only in the AAR via the adapterDefFiles path.
        ".adb", ".adldap",
        // Java archive library references (referenced by javaArchive element)
        ".aliaslib"
    ));

    /**
     * File extensions for metadata files that are parsed for configuration but
     * NOT included in any archive.
     */
    private static final Set<String> METADATA_EXTENSIONS = new HashSet<>(Arrays.asList(
        ".substvar"
    ));

    /**
     * File extensions that are always excluded regardless of project type.
     * buildEAR only includes explicitly registered BW shared-resource types.
     */
    private static final Set<String> EXCLUDED_EXTENSIONS = new HashSet<>(Arrays.asList(
        ".folder", ".dat", ".classpath",
        // TIBCO Designer archive descriptor — build metadata, not a BW shared resource
        ".archive",
        // Source Control metadata (SourceSafe) — never a BW resource
        ".scc"
    ));

    /**
     * Matches relative aeschema cross-references inside .aeschema files, e.g.
     * {@code AESchemas/ae.aeschema} or {@code AESchemas/ae/ADB/adbmetadata.aeschema}.
     * These have no leading slash and are resolved relative to the project root.
     */
    private static final java.util.regex.Pattern RELATIVE_AESCHEMA_REF =
        java.util.regex.Pattern.compile("\\bAESchemas/[^\\s<>\"'#\\\\]+\\.aeschema");

    /**
     * Per-analysis cache of {@code targetNamespace → bwPath} for all XSD files in the
     * project.  Populated before each {@link #applyTransitiveDependencyAnalysis} call and
     * cleared immediately after so it does not bleed between invocations.
     * Used by {@link #followXsdImports} to resolve namespace-only {@code xsd:import}
     * elements that carry no {@code schemaLocation} attribute.
     */
    private Map<String, String> xsdNsIndex = Collections.emptyMap();

    /**
     * File/directory names to exclude from packaging.
     * vcrepo.dat is version-control metadata.
     *
     * <p>Note: {@code AESchemas} is intentionally NOT excluded here. Adapter projects
     * reference {@code /AESchemas/ae.aeschema} and {@code /AESchemas/ae/BW/AESchema.aeschema}
     * from process XML files. Those references are picked up by the content-based
     * {@link #isBwResourcePath} scanner, so the directory must be reachable for collection.
     * For non-adapter projects no process references these paths, so they are excluded
     * naturally by transitive analysis.</p>
     */
    private static final Set<String> EXCLUDED_NAMES = new HashSet<>(Arrays.asList(
        ".DS_Store", "Thumbs.db", ".git", ".svn", "target", "vcrepo.dat",
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

            // 1. Collect all project files into a shared pool
            List<BwFile> allParFiles = new ArrayList<>();
            List<BwFile> allSarFiles = new ArrayList<>();
            List<BwFile> metadataFiles = new ArrayList<>();
            collectFiles(srcDir, srcDir, allParFiles, allSarFiles, metadataFiles);
            collectFilesFromProjlibs(allParFiles, allSarFiles, metadataFiles);

            // 2. Parse global variables from .substvar metadata files
            List<SubstVarParser.GlobalVariable> globalVars = parseGlobalVars(metadataFiles);
            getLog().info("Global variables: " + globalVars.size());

            // 3. Work directory
            File workDir = new File(project.getBuild().getDirectory(), "bw5-assembly");
            if (!workDir.mkdirs() && !workDir.isDirectory()) {
                throw new MojoExecutionException("Failed to create directory: " + workDir.getAbsolutePath());
            }

            // 4. Override SAR name from descriptor if present
            if (archiveDescriptor != null
                    && archiveDescriptor.sharedArchiveName != null
                    && !archiveDescriptor.sharedArchiveName.isEmpty()) {
                sharedArchiveName = archiveDescriptor.sharedArchiveName;
            }

            // 5. Assemble PAR(s) and AAR(s) — multi-archive or single-PAR
            List<File> moduleFiles = new ArrayList<>();  // all PAR/AAR files for this EAR
            // SAR resources accumulated across all archives (union, deduped by path)
            List<BwFile> combinedSarFiles = new ArrayList<>();
            Set<String> seenSarPaths = new LinkedHashSet<>();

            boolean multiArchiveMode = archiveDescriptor != null
                && (archiveDescriptor.isMultiPar() || archiveDescriptor.hasAdapterArchives());

            if (multiArchiveMode) {
                // ---- Multi-PAR: one PAR per <processArchive> element ----
                for (ArchiveDescriptorParser.ProcessArchiveEntry pa : archiveDescriptor.processArchives) {
                    String parFileName = (pa.name != null && !pa.name.isEmpty())
                        ? pa.name + ".par" : "Process Archive.par";
                    File parFile = new File(workDir, parFileName);

                    List<BwFile> parFiles = new ArrayList<>(allParFiles);
                    List<BwFile> sarFiles = new ArrayList<>(allSarFiles);

                    if (pa.hasExplicitProcessList()) {
                        promoteServiceAgentsFromDescriptor(parFiles, sarFiles, pa.processPaths);
                        // Multi-PAR: filter processes by reachability so each PAR owns its own set
                        applyTransitiveDependencyAnalysis(parFiles, sarFiles,
                            pa.processPaths, archiveDescriptor.sharedResourcePaths, true);
                    }
                    accumulateSarFiles(sarFiles, combinedSarFiles, seenSarPaths);

                    List<String> sarPaths = toSarPaths(combinedSarFiles);
                    List<ProcessParser.ProcessMetadata> meta = parseProcesses(parFiles, srcDir);
                    buildPar(parFile, parFiles, srcDir, meta, sarPaths, globalVars);
                    moduleFiles.add(parFile);
                    getLog().info("PAR assembled: " + parFile.getName()
                        + " (" + parFile.length() + " bytes, " + parFiles.size() + " process(es))");
                }

                // ---- AAR: one AAR per <adapterArchive> element ----
                // Maps adapter definition file → sdkVersion string (to decide schema expansion mode).
                Map<File, String> adapterDefFiles = new LinkedHashMap<>();
                for (ArchiveDescriptorParser.AdapterArchiveEntry aa : archiveDescriptor.adapterArchives) {
                    String aarFileName = (aa.name != null && !aa.name.isEmpty())
                        ? aa.name + ".aar" : "Adapter Archive.aar";
                    File aarFile = new File(workDir, aarFileName);
                    buildAar(aarFile, srcDir, aa, globalVars);
                    moduleFiles.add(aarFile);
                    getLog().info("AAR assembled: " + aarFile.getName() + " (" + aarFile.length() + " bytes)");

                    String adapterFilePath = aa.getAdapterFilePath();
                    if (adapterFilePath != null) {
                        File adapterFile = new File(srcDir, adapterFilePath.replace('/', File.separatorChar));
                        if (!adapterFile.isFile()) {
                            adapterFile = new File(bwProjectPath, adapterFilePath.replace('/', File.separatorChar));
                        }
                        if (adapterFile.isFile()) {
                            if (adapterFilePath.toLowerCase(Locale.ROOT).endsWith(".adapter")) {
                                // TIBCO Designer duplicates each .adapter file in the SAR in addition
                                // to placing it inside its dedicated AAR. Replicate that behaviour.
                                accumulateSarFiles(
                                    Collections.singletonList(new BwFile(adapterFile, adapterFilePath)),
                                    combinedSarFiles, seenSarPaths);
                            } else {
                                // Non-.adapter definition files (.adb, .adldap, etc.): placed in the
                                // AAR here; they additionally reach the SAR via the transitive BFS
                                // when a process references them through adapterService.
                                // Collect them to scan their aeschema references for the SAR.
                                adapterDefFiles.put(adapterFile, aa.sdkVersion);
                            }
                        } else {
                            getLog().warn("Could not locate adapter file for AAR '" + aa.name + "': " + adapterFilePath);
                        }
                    }
                }

                // Collect all aeschemas (and other SAR resources) referenced by adapter definition
                // files (.adb, .adsap, etc.) and add them to the SAR.
                // When a .adb file's <loadUrl> entries use fragment references (e.g.
                // ADB_LOGS.aeschema#class), TIBCO Designer cannot resolve them to individual
                // files and instead includes the entire /AESchemas/ae/<type>/ directory.
                // Adapters whose loadUrls have no fragment (ADB_PUBS, ADB_SM, ADB_EVENTS, etc.)
                // use transitive-only resolution regardless of sdkVersion.
                if (!adapterDefFiles.isEmpty()) {
                    Map<String, BwFile> resourceIndex = buildBwIndex(allSarFiles);
                    Set<String> referencedResourcePaths = new LinkedHashSet<>();
                    Set<String> wholeDirectoryPrefixes = new LinkedHashSet<>();

                    for (Map.Entry<File, String> defEntry : adapterDefFiles.entrySet()) {
                        File defFile = defEntry.getKey();
                        boolean hasFragmentLoadUrls = adapterHasFragmentLoadUrls(defFile);
                        for (String ref : extractBwResourceRefs(defFile)) {
                            String norm = normalizeBwPath(ref);
                            // Adapter definition files (.adb, .adldap, .adsap, …) must only
                            // enter the SAR when a process references them via adapterService.
                            // Within a .ad* config, internal references like /Foo.adb#svc.Bar
                            // are stripped to /Foo.adb by extractBwResourceRefs — skip any
                            // .ad* path here to avoid inadvertent self-inclusion in the SAR.
                            String normExt = getExtension(norm);
                            if (normExt.startsWith(".ad")) continue;
                            if (norm.endsWith(".aeschema") && hasFragmentLoadUrls) {
                                // Fragment loadUrls: remember the directory so we can add all aeschemas in it.
                                int slash = norm.lastIndexOf('/');
                                if (slash > 0) wholeDirectoryPrefixes.add(norm.substring(0, slash + 1));
                            }
                            if (referencedResourcePaths.add(norm)) {
                                if (isSarExtension(getExtension(norm))) {
                                    followSharedResourceRefs(norm, resourceIndex, referencedResourcePaths);
                                }
                                if (norm.endsWith(".aeschema")) {
                                    followAeschemaImports(norm, resourceIndex, referencedResourcePaths);
                                }
                            }
                        }
                    }

                    // Directory expansion for adapters with fragment-based loadUrls.
                    for (String dirPrefix : wholeDirectoryPrefixes) {
                        getLog().info("Fragment loadUrls: expanding all aeschemas under " + dirPrefix);
                        for (Map.Entry<String, BwFile> e : resourceIndex.entrySet()) {
                            if (e.getKey().startsWith(dirPrefix) && e.getKey().endsWith(".aeschema")) {
                                referencedResourcePaths.add(e.getKey());
                            }
                        }
                    }

                    for (String path : referencedResourcePaths) {
                        BwFile f = resourceIndex.get(path);
                        if (f != null) {
                            accumulateSarFiles(Collections.singletonList(f), combinedSarFiles, seenSarPaths);
                        }
                    }
                }

            } else {
                // ---- Single-PAR mode (default or single processArchive) ----
                List<BwFile> parFiles = new ArrayList<>(allParFiles);
                List<BwFile> sarFiles = new ArrayList<>(allSarFiles);

                if (archiveDescriptor != null && archiveDescriptor.hasExplicitProcessList()) {
                    promoteServiceAgentsFromDescriptor(parFiles, sarFiles,
                        archiveDescriptor.getProcessPaths());
                    // Single-PAR: same filtering as multi-PAR — only processes reachable from
                    // the processProperty entry points are included. Processes that exist on disk
                    // but are not reachable from any entry point are excluded, matching buildEAR.
                    applyTransitiveDependencyAnalysis(parFiles, sarFiles,
                        archiveDescriptor.getProcessPaths(), archiveDescriptor.sharedResourcePaths,
                        true);
                }

                String parFileName = "Process Archive.par";
                String descriptorParName = archiveDescriptor != null
                    ? archiveDescriptor.getProcessArchiveName() : null;
                if (descriptorParName != null && !descriptorParName.isEmpty()) {
                    parFileName = descriptorParName + ".par";
                }

                getLog().info("Process files (PAR): " + parFiles.size());
                getLog().info("Shared resource files (SAR): " + sarFiles.size());

                accumulateSarFiles(sarFiles, combinedSarFiles, seenSarPaths);
                List<String> sarPaths = toSarPaths(combinedSarFiles);
                List<ProcessParser.ProcessMetadata> meta = parseProcesses(parFiles, srcDir);
                File parFile = new File(workDir, parFileName);
                buildPar(parFile, parFiles, srcDir, meta, sarPaths, globalVars);
                moduleFiles.add(parFile);
                getLog().info("PAR assembled: " + parFile.getName() + " (" + parFile.length() + " bytes)");
            }

            // 6. Build the SAR (shared across all PARs/AARs)
            File sarFile = null;
            if (includeSharedArchive) {
                sarFile = new File(workDir, sharedArchiveName + ".sar");
                buildSar(sarFile, combinedSarFiles, srcDir);
                getLog().info("SAR assembled: " + sarFile.getName() + " (" + sarFile.length() + " bytes)");
            }

            // 7. Generate EAR-level TIBCO.xml — lists all PAR/AAR modules
            List<String> moduleFileNames = new ArrayList<>();
            for (File mf : moduleFiles) moduleFileNames.add(mf.getName());

            File earTibcoXml = new File(workDir, "TIBCO.xml");
            String effectiveEarName = (archiveDescriptor != null
                    && archiveDescriptor.earName != null
                    && !archiveDescriptor.earName.isEmpty())
                ? archiveDescriptor.earName : archiveName;
            String projectVersion = project.getVersion();
            String majorVersion = projectVersion.contains(".")
                ? projectVersion.substring(0, projectVersion.indexOf('.'))
                : projectVersion;
            TibcoXmlGenerator generator = new TibcoXmlGenerator();
            generator.generateEarDescriptor(
                earTibcoXml,
                effectiveEarName,
                majorVersion,
                moduleFileNames,
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
                for (BwFile bwf : combinedSarFiles) {
                    if (bwf.file.getName().toLowerCase(Locale.ROOT).endsWith(".sharedhttp")) {
                        sharedHttpFiles.add(bwf.file);
                    }
                }
                // Collect all process files across all module files (for manifest)
                List<File> allProcessFiles = new ArrayList<>();
                for (BwFile bwf : allParFiles) {
                    if (bwf.file.getName().toLowerCase(Locale.ROOT).endsWith(".process")) {
                        allProcessFiles.add(bwf.file);
                    }
                }
                manifestFile = new ManifestBw5Generator().generate(
                    effectiveEarName, project.getVersion(), globalVars, sharedHttpFiles, allProcessFiles, workDir);
                getLog().info("manifest-bw5.json generated.");
            }

            // 9. Assemble the final EAR
            String earFileName = project.getBuild().getFinalName() + ".ear";
            File earFile = new File(project.getBuild().getDirectory(), earFileName);
            buildEar(earFile, earTibcoXml, moduleFiles, sarFile, manifestFile);

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
            List<SubstVarParser.GlobalVariable> vars) throws MojoExecutionException {
        try {
            PropertyMerger merger = new PropertyMerger();
            Map<String, String> mavenProps = getAllMavenProperties();
            List<SubstVarParser.GlobalVariable> merged =
                merger.merge(vars, globalPropertiesFile, projectPropertiesFile, mavenProps);
            long changed = 0;
            for (int i = 0; i < vars.size(); i++) {
                if (!Objects.equals(vars.get(i).value, merged.get(i).value)) changed++;
            }
            if (changed > 0) getLog().info("Property overrides applied: " + changed + " variable(s) changed");
            return merged;
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
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
                    if (!includeFolderMetadata || !".folder".equals(ext)) continue;
                }
                // Adapter-related extensions starting with ".ad": only those in SAR_EXTENSIONS
                // (.adapter, .adb, .adldap, …) are eligible for the SAR.  Unknown ".ad*" types
                // (e.g. .adsap, .addr3) belong exclusively in the AAR and are skipped here.
                if (ext.startsWith(".ad") && !isSarExtension(ext)) continue;

                String relativePath = rootDir.toURI().relativize(f.toURI()).getPath();
                BwFile bwf = new BwFile(f, relativePath);

                if (PAR_EXTENSIONS.contains(ext)) {
                    parFiles.add(bwf);
                } else if (METADATA_EXTENSIONS.contains(ext)) {
                    metadataFiles.add(bwf);
                } else if (isSarExtension(ext)) {
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
                         OutputStream os = Files.newOutputStream(outFile.toPath())) {
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

    @SuppressWarnings("PMD.UnusedFormalParameter")
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
        result.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
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
        File descriptorFile = archiveDescriptorFile;
        if (descriptorFile == null) {
            // Auto-discover: scan the project source root for a .archive file.
            // The .archive descriptor is excluded from packaging (EXCLUDED_EXTENSIONS) so it
            // remains in bwProjectPath even after sources are copied to bwSourcesDirectory.
            List<File> found = findArchiveFiles(bwProjectPath);
            if (found.isEmpty()) {
                return null;
            }
            if (found.size() > 1) {
                getLog().warn("Multiple .archive files found in " + bwProjectPath.getName()
                    + "; using first: " + found.get(0).getName()
                    + ". Configure <archiveDescriptorFile> explicitly to suppress this warning.");
            }
            descriptorFile = found.get(0);
            getLog().info("Auto-discovered .archive descriptor: " + descriptorFile.getAbsolutePath());
        }
        if (!descriptorFile.exists()) {
            throw new MojoExecutionException(
                "Archive descriptor not found: " + descriptorFile.getAbsolutePath());
        }
        try {
            ArchiveDescriptorParser parser = new ArchiveDescriptorParser();
            ArchiveDescriptorParser.ArchiveDescriptor descriptor = parser.parse(descriptorFile);
            getLog().info("Using .archive descriptor: " + descriptorFile.getName()
                + " (" + descriptor + ")");
            return descriptor;
        } catch (Exception e) {
            throw new MojoExecutionException(
                "Failed to parse .archive descriptor '"
                + descriptorFile.getName() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Scans the top level of {@code dir} for files with a {@code .archive} extension.
     * Non-recursive — {@code .archive} files in BW5 projects always live at the project root.
     */
    static List<File> findArchiveFiles(File dir) {
        List<File> result = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return result;
        File[] files = dir.listFiles();
        if (files == null) return result;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".archive")) {
                result.add(f);
            }
        }
        return result;
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

    // RESOURCE_REF_ELEMENTS has been removed. Reference detection is now content-based:
    // any element whose text value looks like a BW resource path (starts with "/" and
    // has an extension registered in PAR_EXTENSIONS or SAR_EXTENSIONS) is treated as
    // a resource reference — regardless of the XML element name. This makes reference
    // scanning generic: any new TIBCO palette that stores a resource path in an XML
    // element is covered automatically without code changes. See isBwResourcePath().

    /**
     * Performs transitive dependency analysis, updating {@code parFiles} and {@code sarFiles}
     * in-place.
     *
     * <p><b>PAR content ({@code filterParByReachability}):</b></p>
     * <ul>
     *   <li>{@code false} (single-PAR mode) — ALL process files are kept in the PAR regardless
     *       of reachability. This matches {@code buildEAR} behaviour: the {@code processProperty}
     *       list in the {@code .archive} descriptor drives TIBCO Administrator startup ordering,
     *       not which processes get packaged. Entry points are validated for existence but the BFS
     *       is seeded from ALL processes to discover SAR resources.</li>
     *   <li>{@code true} (multi-PAR mode) — only processes reachable from the declared entry
     *       points are kept. Each PAR owns its own process set.</li>
     * </ul>
     *
     * <p><b>SAR content:</b> always filtered to the transitively reachable resources, plus
     * always-include items ({@code .javaxpath} files and {@code sharedResources} paths).</p>
     *
     * <p>Non-process entries in {@code parFiles} (e.g. promoted serviceagents) are always kept.</p>
     */
    private void applyTransitiveDependencyAnalysis(List<BwFile> parFiles, List<BwFile> sarFiles,
                                                   List<String> entryPoints,
                                                   List<String> sharedResourcePaths,
                                                   boolean filterParByReachability)
            throws MojoExecutionException {
        // Separate promoted service agents (always stay in PAR) from process files
        List<BwFile> promotedParEntries = new ArrayList<>();
        List<BwFile> processFiles = new ArrayList<>();
        for (BwFile f : parFiles) {
            if (f.file.getName().endsWith(".process")) {
                processFiles.add(f);
            } else {
                promotedParEntries.add(f);
            }
        }

        // Separate always-include SAR files from files subject to transitive filtering
        List<BwFile> alwaysInclude = new ArrayList<>();
        List<BwFile> otherSarFiles = new ArrayList<>();
        for (BwFile f : sarFiles) {
            if (f.file.getName().endsWith(".javaxpath")
                    || f.file.getName().endsWith(".sharedjdbc")
                    || isUnderSharedResourcePath(f, sharedResourcePaths)) {
                alwaysInclude.add(f);
            } else {
                otherSarFiles.add(f);
            }
        }

        Map<String, BwFile> processIndex = buildBwIndex(processFiles);
        Map<String, BwFile> resourceIndex = buildBwIndex(otherSarFiles);
        // Build namespace index before BFS so followXsdImports can resolve namespace-only imports
        // during the main traversal. Rebuilt post-BFS with extIndex (alwaysInclude XSDs added).
        this.xsdNsIndex = buildXsdNsIndex(resourceIndex);

        Set<String> visitedProcessPaths = new LinkedHashSet<>();
        Set<String> referencedResourcePaths = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>();

        // Validate that every .process declared in the descriptor exists on disk
        List<String> missingEntryPoints = new ArrayList<>();
        for (String ep : entryPoints) {
            String norm = normalizeBwPath(ep);
            if (norm.endsWith(".process")) {
                if (!processIndex.containsKey(norm)) {
                    missingEntryPoints.add(ep);
                }
            }
            // .serviceagent entries are promoted to PAR by promoteServiceAgentsFromDescriptor
            // and their opImpl processes are seeded into the BFS queue below
        }
        if (!missingEntryPoints.isEmpty()) {
            StringBuilder msg = new StringBuilder(
                "Process file(s) declared in .archive descriptor not found on disk:");
            for (String missing : missingEntryPoints) {
                msg.append("\n  - ").append(missing);
            }
            throw new MojoExecutionException(msg.toString());
        }

        // Seed the BFS:
        // • filterParByReachability=true (multi-PAR): only declared entry points — tightly
        //   scopes each PAR to its assigned processes plus any subprocesses they call.
        // • filterParByReachability=false (single-PAR): ALL process files — matches buildEAR
        //   which packages every process regardless of processProperty membership.
        if (filterParByReachability) {
            for (String ep : entryPoints) {
                String norm = normalizeBwPath(ep);
                if (norm.endsWith(".process")) queue.add(norm);
            }
        } else {
            for (BwFile pf : processFiles) {
                queue.add(normalizeBwPath(pf.relativePath));
            }
        }

        // Traverse promoted service agents: opImpl attrs → process seeds, text/location
        // refs (sharedChannel, contextResource, WSDL) → SAR resources. This step runs
        // before the BFS loop so that implementation processes discovered here are visited
        // by the BFS and can pull in their own transitive SAR dependencies.
        for (BwFile sa : promotedParEntries) {
            if (!sa.file.getName().endsWith(".serviceagent")) continue;
            getLog().debug("Traversing service agent: " + sa.relativePath);
            for (String ref : extractBwResourceRefs(sa.file)) {
                String norm = normalizeBwPath(ref);
                if (norm.endsWith(".process")) {
                    if (!visitedProcessPaths.contains(norm)) {
                        getLog().debug("  serviceagent opImpl → process: " + norm);
                        queue.add(norm);
                    }
                } else if (referencedResourcePaths.add(norm)) {
                    if (norm.endsWith(".sharedvariable") || norm.endsWith(".jobsharedvariable")) {
                        referencedResourcePaths.add(norm.replaceAll("\\.[^.]+$", ".xml"));
                    }
                    if (isSarExtension(getExtension(norm))) {
                        followSharedResourceRefs(norm, resourceIndex, referencedResourcePaths);
                    }
                    if (norm.endsWith(".xsd")) {
                        followXsdImports(norm, resourceIndex, referencedResourcePaths);
                    }
                    if (norm.endsWith(".aeschema")) {
                        followAeschemaImports(norm, resourceIndex, referencedResourcePaths);
                    }
                }
            }
        }

        while (!queue.isEmpty()) {
            String path = queue.poll();
            if (!visitedProcessPaths.add(path)) continue;

            BwFile bwf = processIndex.get(path);
            if (bwf == null) {
                getLog().debug("Transitive: process not found: " + path);
                continue;
            }

            for (String ref : extractBwResourceRefs(bwf.file)) {
                String norm = normalizeBwPath(ref);
                if (norm.endsWith(".process")) {
                    if (!visitedProcessPaths.contains(norm)) queue.add(norm);
                } else if (referencedResourcePaths.add(norm)) {
                    // Add same-base-name companion .xml (e.g. DocumentStore.xml for .sharedvariable).
                    // Kept as belt-and-suspenders for files that don't use initialValueRef.
                    if (norm.endsWith(".sharedvariable") || norm.endsWith(".jobsharedvariable")) {
                        referencedResourcePaths.add(norm.replaceAll("\\.[^.]+$", ".xml"));
                    }
                    // Follow internal references inside ANY SAR-typed shared resource file
                    // (e.g. .sharedhttp → .id/.cert, .sharedvariable → .xsd, etc.).
                    // Using the extension set keeps this generic: any new resource type added
                    // to SAR_EXTENSIONS is automatically traversed without further code changes.
                    if (isSarExtension(getExtension(norm))) {
                        followSharedResourceRefs(norm, resourceIndex, referencedResourcePaths);
                    }
                    // Follow XSD imports transitively
                    if (norm.endsWith(".xsd")) {
                        followXsdImports(norm, resourceIndex, referencedResourcePaths);
                    }
                    // Follow relative aeschema cross-references transitively
                    if (norm.endsWith(".aeschema")) {
                        followAeschemaImports(norm, resourceIndex, referencedResourcePaths);
                    }
                }
            }
        }

        // Follow XSD import chains for sharedResources (alwaysInclude) files.
        // These files bypass the BFS (they go to alwaysInclude, not resourceIndex), so their
        // transitive imports must be discovered separately. Build an extended index that includes
        // alwaysInclude entries so followXsdImports / followSharedResourceRefs can read them.
        Map<String, BwFile> extIndex = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        extIndex.putAll(resourceIndex);
        for (BwFile f : alwaysInclude) extIndex.put(normalizeBwPath(f.relativePath), f);
        // Build targetNamespace → bwPath index for resolving namespace-only xsd:imports.
        this.xsdNsIndex = buildXsdNsIndex(extIndex);
        for (BwFile f : alwaysInclude) {
            String fPath = normalizeBwPath(f.relativePath);
            if (f.file.getName().endsWith(".xsd")) {
                followXsdImports(fPath, extIndex, referencedResourcePaths);
            } else if (isSarExtension(getExtension(fPath))) {
                // Follow transitive refs (aeschema loadUrls, WSDL imports, etc.) from
                // always-include shared resource files (e.g. .adb files whose AESDK:loadUrl
                // points to palette AESchema files that must also be in the SAR).
                followSharedResourceRefs(fPath, extIndex, referencedResourcePaths);
            }
        }
        this.xsdNsIndex = Collections.emptyMap();

        // PAR: all promoted entries + either all processes or only reachable ones
        List<BwFile> parProcesses = filterParByReachability
            ? buildReachableList(visitedProcessPaths, processIndex)
            : processFiles;

        // Expand directory references (e.g. /Certificates → all .cert files inside)
        expandDirectoryRefs(referencedResourcePaths, resourceIndex);

        // SAR: alwaysInclude + transitively reachable resources (deduped).
        // Exception: .serviceagent files discovered via process references (e.g.
        // <JavaGlobalInstance>) belong in PAR — buildear packages Java Globals
        // alongside the processes that use them, not in the shared archive.
        Set<String> sarPathsSeen = new LinkedHashSet<>();
        for (BwFile f : alwaysInclude) sarPathsSeen.add(normalizeBwPath(f.relativePath));
        List<BwFile> reachableResources = new ArrayList<>(alwaysInclude);
        for (String path : referencedResourcePaths) {
            BwFile f = resourceIndex.get(path);
            if (f == null) continue;
            if (f.file.getName().endsWith(".serviceagent")) {
                getLog().info("Promoting serviceagent to PAR (JavaGlobalInstance): " + f.relativePath);
                promotedParEntries.add(f);
                // buildear also places Java Globals in the SAR
                if (sarPathsSeen.add(normalizeBwPath(f.relativePath))) {
                    reachableResources.add(f);
                }
            } else if (sarPathsSeen.add(normalizeBwPath(f.relativePath))) {
                reachableResources.add(f);
            }
        }

        getLog().info("Transitive analysis: "
            + parProcesses.size() + "/" + processFiles.size() + " processes"
            + (filterParByReachability ? " (filtered)" : " (all)")
            + ", " + reachableResources.size() + "/" + (otherSarFiles.size() + alwaysInclude.size())
            + " resources (always-include: " + alwaysInclude.size() + ")");

        parFiles.clear();
        parFiles.addAll(promotedParEntries);
        parFiles.addAll(parProcesses);
        sarFiles.clear();
        sarFiles.addAll(reachableResources);
    }

    private List<BwFile> buildReachableList(Set<String> visitedPaths, Map<String, BwFile> index) {
        List<BwFile> result = new ArrayList<>();
        for (String path : visitedPaths) {
            BwFile f = index.get(path);
            if (f != null) result.add(f);
        }
        return result;
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
     *
     * <p>For {@code .wsdl} files a second pass resolves relative {@code schemaLocation}
     * and {@code location} attributes (e.g. {@code ../../SchemaDefinitions/Ext/Types.xsd})
     * that {@code extractBwResourceRefs} skips because they lack a leading {@code /}.</p>
     */
    private void followSharedResourceRefs(String resourcePath, Map<String, BwFile> resourceIndex,
                                          Set<String> visited) {
        BwFile resourceFile = resourceIndex.get(resourcePath);
        if (resourceFile == null) return;
        // Pass 1: absolute references
        for (String ref : extractBwResourceRefs(resourceFile.file)) {
            String norm = normalizeBwPath(ref);
            if (!norm.endsWith(".process") && visited.add(norm)) {
                if (norm.endsWith(".xsd")) {
                    followXsdImports(norm, resourceIndex, visited);
                }
                if (norm.endsWith(".aeschema")) {
                    followAeschemaImports(norm, resourceIndex, visited);
                }
            }
        }
        // Pass 2: relative location/schemaLocation pointing to XSD files inside WSDL files.
        // WSDL files commonly embed inline XSD types or import external XSDs via relative
        // paths (e.g. ../../SchemaDefinitions/External/Types.xsd). These are invisible to
        // extractBwResourceRefs which requires a leading "/" on every path it returns.
        if (!resourcePath.endsWith(".wsdl")) return;
        String parentBwDir = resourcePath.contains("/")
            ? resourcePath.substring(0, resourcePath.lastIndexOf('/') + 1) : "";
        try {
            Document wsdlDoc = new SAXBuilder().build(resourceFile.file);
            for (Element e : wsdlDoc.getDescendants(Filters.element())) {
                for (String attrName : new String[]{"schemaLocation", "location"}) {
                    String attrVal = e.getAttributeValue(attrName);
                    if (attrVal == null || attrVal.startsWith("/") || attrVal.startsWith("http")) {
                        continue;
                    }
                    boolean isXsd  = attrVal.endsWith(".xsd");
                    boolean isWsdl = attrVal.endsWith(".wsdl");
                    if (!isXsd && !isWsdl) continue;
                    String raw = parentBwDir + attrVal;
                    String resolved = java.nio.file.Paths.get(raw).normalize().toString()
                        .replace(java.io.File.separatorChar, '/');
                    if (visited.add(resolved)) {
                        if (isWsdl) {
                            followSharedResourceRefs(resolved, resourceIndex, visited);
                        } else {
                            followXsdImports(resolved, resourceIndex, visited);
                        }
                    }
                }
            }
        } catch (org.jdom2.JDOMException | IOException ignored) { }
    }

    private Map<String, BwFile> buildBwIndex(List<BwFile> files) {
        // Case-insensitive: BW5 runs on Windows where paths are case-insensitive, but
        // references in .process/.serviceagent files may use different case than on-disk paths.
        Map<String, BwFile> index = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (BwFile f : files) {
            index.put(normalizeBwPath(f.relativePath), f);
        }
        return index;
    }

    /**
     * Scans every XSD file in {@code resourceIndex} and builds a map of
     * {@code targetNamespace → bwPath}.  Used by {@link #followXsdImports} to resolve
     * {@code xsd:import} elements that carry only a {@code namespace} attribute with no
     * {@code schemaLocation} (TIBCO BW5 resolves these via its internal type registry;
     * we replicate that by looking up which project XSD declares the namespace).
     */
    private Map<String, String> buildXsdNsIndex(Map<String, BwFile> resourceIndex) {
        Map<String, String> nsIndex = new HashMap<>();
        for (Map.Entry<String, BwFile> entry : resourceIndex.entrySet()) {
            if (!entry.getKey().endsWith(".xsd")) continue;
            try {
                Document doc = new SAXBuilder().build(entry.getValue().file);
                String ns = doc.getRootElement().getAttributeValue("targetNamespace");
                if (ns != null && !ns.isEmpty()) {
                    nsIndex.putIfAbsent(ns, entry.getKey());
                }
            } catch (org.jdom2.JDOMException | IOException ignored) { }
        }
        return nsIndex;
    }

    /**
     * Recursively follows {@code schemaLocation} imports in an XSD file, adding
     * every imported XSD path to {@code visited} (prevents cycles).
     *
     * <p>Three passes are performed: first the general-purpose scanner collects absolute
     * references (leading {@code /}); then a dedicated XML scan resolves
     * <em>relative</em> {@code schemaLocation} values such as {@code broker.xsd} by
     * prepending the BW directory of the importing XSD; finally, namespace-only imports
     * (no {@code schemaLocation}) are resolved via {@link #xsdNsIndex}.</p>
     */
    private void followXsdImports(String xsdPath, Map<String, BwFile> resourceIndex,
                                  Set<String> visited) {
        BwFile xsdFile = resourceIndex.get(xsdPath);
        if (xsdFile == null) return;
        // Pass 1: absolute references (e.g. schemaLocation="/SharedResources/Types.xsd")
        for (String ref : extractBwResourceRefs(xsdFile.file)) {
            String norm = normalizeBwPath(ref);
            if (norm.endsWith(".xsd") && visited.add(norm)) {
                followXsdImports(norm, resourceIndex, visited);
            }
        }
        // Pass 2 + Pass 3: parse once; resolve relative schemaLocations and namespace-only imports.
        String parentBwDir = xsdPath.contains("/")
            ? xsdPath.substring(0, xsdPath.lastIndexOf('/') + 1) : "/";
        try {
            Document doc = new SAXBuilder().build(xsdFile.file);
            for (Element e : doc.getDescendants(Filters.element())) {
                String schemaLoc = e.getAttributeValue("schemaLocation");
                // Pass 2: relative schemaLocation values (e.g. schemaLocation="broker.xsd").
                // extractBwResourceRefs() requires a leading "/" and skips these; resolve them
                // manually against the parent BW directory of the importing XSD.
                if (schemaLoc != null && !schemaLoc.startsWith("/") && !schemaLoc.startsWith("http")
                        && schemaLoc.endsWith(".xsd")) {
                    // Normalize ".." segments (e.g. "XSD/Status/../Common/HEADER.xsd")
                    String raw = normalizeBwPath(parentBwDir + schemaLoc);
                    String resolved = java.nio.file.Paths.get(raw).normalize().toString()
                        .replace(java.io.File.separatorChar, '/');
                    if (visited.add(resolved)) {
                        followXsdImports(resolved, resourceIndex, visited);
                    }
                }
                // Pass 3: namespace-only imports without schemaLocation.
                // e.g. <xsd:import namespace="http://arquitecturas/soap/2003/4_5/"/>
                // TIBCO BW5 resolves these via its internal type registry; we replicate
                // that lookup using the targetNamespace index built from all project XSDs.
                if (schemaLoc == null && !xsdNsIndex.isEmpty()) {
                    String ns = e.getAttributeValue("namespace");
                    if (ns != null && !ns.isEmpty()) {
                        String resolvedPath = xsdNsIndex.get(ns);
                        if (resolvedPath != null && visited.add(resolvedPath)) {
                            followXsdImports(resolvedPath, resourceIndex, visited);
                        }
                    }
                }
            }
        } catch (org.jdom2.JDOMException | IOException ignored) { }
    }

    /**
     * Recursively follows relative aeschema cross-references inside a {@code .aeschema} file.
     *
     * <p>TIBCO aeschema files reference peer schemas using relative paths such as
     * {@code AESchemas/ae.aeschema} (no leading slash). Each match is prepended with
     * {@code /} and normalised before index lookup, then followed recursively.</p>
     */
    private void followAeschemaImports(String aeschemaPath, Map<String, BwFile> resourceIndex,
                                       Set<String> visited) {
        BwFile f = resourceIndex.get(aeschemaPath);
        if (f == null) return;
        try {
            String content = new String(
                java.nio.file.Files.readAllBytes(f.file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = RELATIVE_AESCHEMA_REF.matcher(content);
            while (m.find()) {
                String norm = normalizeBwPath("/" + m.group());
                if (visited.add(norm)) {
                    followAeschemaImports(norm, resourceIndex, visited);
                }
            }
        } catch (IOException ex) {
            getLog().debug("Could not scan aeschema imports from " + f.file.getName());
        }
    }

    /**
     * Parses a BW5 process or shared-resource file and returns all BW resource paths it references.
     *
     * <p>Uses content-based detection rather than a fixed list of element names: any XML
     * element whose trimmed text value passes {@link #isBwResourcePath} is treated as a
     * resource reference. This covers all current TIBCO palettes and future ones without
     * requiring code changes. {@code schemaLocation} attributes are also scanned.</p>
     */
    private Set<String> extractBwResourceRefs(File file) {
        Set<String> refs = new LinkedHashSet<>();
        try {
            SAXBuilder builder = new SAXBuilder();
            Document doc = builder.build(file);
            for (Element e : doc.getDescendants(Filters.element())) {
                String text = e.getTextTrim();
                if (isBwResourcePath(text)) {
                    refs.add(text);
                } else if (text.contains("#") && !"messageSchemaURI".equals(e.getName())) {
                    // BW resource references that include a fragment — strip it to get the file.
                    // Examples:
                    //   /path/schema.aeschema#class.X  (adapter palette aeMeta / customOutputMeta)
                    //   /path/config.adb#adapterService.X  (process → adapter via adapterService)
                    //   /path/config.adldap#adapterService.X  (LDAP adapter)
                    // messageSchemaURI is excluded: it is a runtime type hint, not a resource import.
                    String stripped = text.substring(0, text.indexOf('#'));
                    if (isBwResourcePath(stripped)) refs.add(stripped);
                }
                String schemaLoc = e.getAttributeValue("schemaLocation");
                if (schemaLoc != null && schemaLoc.startsWith("/")) refs.add(schemaLoc);
                String locationAttr = e.getAttributeValue("location");
                if (locationAttr != null && isBwResourcePath(locationAttr)) refs.add(locationAttr);
                // .serviceagent files declare their implementation process via opImpl attribute
                // (e.g. <row opImpl="/pkg/Op.process"/>). Text-content scanning misses attributes.
                String opImplAttr = e.getAttributeValue("opImpl");
                if (opImplAttr != null && isBwResourcePath(opImplAttr)) refs.add(opImplAttr);
            }
        } catch (org.jdom2.JDOMException | IOException e) {
            getLog().debug("Could not parse refs from " + file.getName() + ": " + e.getMessage());
        }
        return refs;
    }

    /**
     * Returns {@code true} when {@code value} looks like an absolute BW resource path.
     *
     * <p>Three cases are accepted:</p>
     * <ol>
     *   <li><b>Known file reference</b> — path has an extension registered in
     *       {@link #PAR_EXTENSIONS} or via {@link #isSarExtension}. Accepted immediately.</li>
     *   <li><b>Unknown-extension file reference</b> — path has an extension not in any
     *       known set (e.g. {@code .cpy} for CopyBook, {@code .smartmapperermodel} for
     *       SmartMapper, {@code .javaschema} for Java Schema, and any future third-party
     *       palette type). These paths are emitted so the BFS can look them up in the
     *       resource index; if no file matches they are silently discarded. This avoids
     *       the need to hard-code every palette's resource extension in advance.</li>
     *   <li><b>Directory reference</b> — path has no extension at all (no {@code .}).
     *       TIBCO BW5 processes can reference a directory path (e.g. {@code /Certificates})
     *       meaning "include all SAR files under that directory". Resolved by
     *       {@link #expandDirectoryRefs} after the BFS completes.</li>
     * </ol>
     * <p>XPath-like patterns ({@code ::}, {@code [}, {@code (}) are rejected in cases 2
     * and 3 to avoid false positives from mapper and XSLT expressions.</p>
     */
    private boolean isBwResourcePath(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '/') return false;
        // Fragment references (path#something) are handled by the caller's else-if branch
        // which strips the fragment first.  Returning false here ensures we don't emit
        // the full string (with the fragment) as a file path.
        if (value.contains("#")) return false;
        int dot = value.lastIndexOf('.');
        if (dot <= 0) {
            // No extension — could be a directory reference (e.g. /Certificates).
            // Reject XPath operators to avoid picking up mapper expressions.
            return !value.contains("::") && !value.contains("[") && !value.contains("(");
        }
        String ext = value.substring(dot).toLowerCase(Locale.ROOT);
        // If the "extension" contains a slash, the dot is in a middle path segment and
        // the trailing part is a sub-path within a resource (e.g.
        // /Model.smartmapperermodel/Relationships/TYPE/TYPE_INPUT).
        // This is not a file reference; reject it so only /Model.smartmapperermodel
        // (the erModelRef) is emitted.
        if (ext.contains("/")) return false;
        if (PAR_EXTENSIONS.contains(ext) || isSarExtension(ext)) return true;
        // Unknown extension: emit and let the BFS resolve against the resource index.
        // If no file with this path exists on disk the ref is silently dropped.
        // This handles any third-party palette resource type (.cpy, .smartmapperermodel,
        // .javaschema, …) without requiring explicit registration in SAR_EXTENSIONS.
        return !value.contains("::") && !value.contains("[") && !value.contains("(");
    }

    /**
     * Expands directory references in {@code refs} to include all SAR resource files
     * whose normalized path starts with that directory prefix.
     *
     * <p>TIBCO Designer includes all SAR files under a referenced directory path —
     * e.g. {@code /Certificates} expands to all {@code .cert} files inside it.
     * A directory reference is identified by having no file extension in its last segment.</p>
     */
    private void expandDirectoryRefs(Set<String> refs, Map<String, BwFile> resourceIndex) {
        List<String> dirRefs = new ArrayList<>();
        for (String path : refs) {
            int lastSlash = path.lastIndexOf('/');
            String lastName = path.substring(lastSlash + 1);
            if (!lastName.isEmpty() && !lastName.contains(".")) {
                dirRefs.add(path);
            }
        }
        for (String dir : dirRefs) {
            String prefix = dir + "/";
            for (String resourcePath : resourceIndex.keySet()) {
                if (resourcePath.startsWith(prefix)) {
                    if (refs.add(resourcePath)) {
                        getLog().debug("Directory ref expanded: " + dir + " → " + resourcePath);
                    }
                }
            }
        }
    }

    private static String normalizeBwPath(String path) {
        if (path == null) return "";
        String s = path.replace('\\', '/').trim();
        return s.startsWith("/") ? s.substring(1) : s;
    }

    /**
     * Returns true when the adapter definition file contains {@code <loadUrl>} entries
     * that use fragment references (e.g. {@code ADB_LOGS.aeschema#class}).
     * <p>When fragment-based loadUrls are present TIBCO Designer cannot resolve them to
     * individual files and falls back to loading the entire {@code AESchemas/ae/&lt;type&gt;/}
     * directory. Adapters without fragment loadUrls use transitive-only resolution.
     */
    private static boolean adapterHasFragmentLoadUrls(File adapterFile) {
        try {
            String content = new String(java.nio.file.Files.readAllBytes(adapterFile.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("loadUrl[^>]*>[^<]*\\.aeschema#")
                .matcher(content);
            return m.find();
        } catch (java.io.IOException e) {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    //  PAR assembly
    // -----------------------------------------------------------------------

    private void buildPar(File parFile, List<BwFile> parFiles, File srcDir,
                          List<ProcessParser.ProcessMetadata> processMetadata,
                          List<String> sarPaths,
                          List<SubstVarParser.GlobalVariable> globalVars) throws Exception {
        // Generate PAR-level TIBCO.xml into a temp file
        File parTibcoXml = File.createTempFile("par-TIBCO", ".xml");
        parTibcoXml.deleteOnExit();
        new TibcoXmlGenerator().generateParDescriptor(
            parTibcoXml,
            parFile.getName(),
            processMetadata,
            sarPaths,
            scanJdbcResourcePaths(srcDir),
            globalVars,
            null
        );

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(parFile.toPath()))) {
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
    //  AAR assembly
    // -----------------------------------------------------------------------

    /**
     * Assembles a BW5 Adapter Archive ({@code .aar}) from the descriptor's
     * {@code <adapterReference>} element.
     *
     * <p>An AAR contains exactly two entries:</p>
     * <ol>
     *   <li>{@code TIBCO.xml} — adapter-specific descriptor (SDK version, external deps,
     *       RepoConfigUrl)</li>
     *   <li>The {@code .adapter} file at its BW project path (relative to {@code srcDir})</li>
     * </ol>
     */
    private void buildAar(File aarFile, File srcDir,
                          ArchiveDescriptorParser.AdapterArchiveEntry aa,
                          List<SubstVarParser.GlobalVariable> globalVars) throws Exception {
        String aarFileName = aarFile.getName();

        // Locate the .adapter file on disk
        String adapterFilePath = aa.getAdapterFilePath();
        if (adapterFilePath == null || adapterFilePath.isEmpty()) {
            throw new MojoExecutionException(
                "adapterArchive '" + aa.name + "' has no <adapterReference> — cannot assemble AAR");
        }
        File adapterFile = new File(srcDir, adapterFilePath.replace('/', File.separatorChar));
        if (!adapterFile.isFile()) {
            // Try bwSourcesDirectory as root if different from srcDir
            adapterFile = new File(bwProjectPath, adapterFilePath.replace('/', File.separatorChar));
        }
        if (!adapterFile.isFile()) {
            throw new MojoExecutionException(
                "Adapter file not found for AAR '" + aa.name + "': " + adapterFilePath
                + "\nSearched under: " + srcDir.getAbsolutePath());
        }

        // Generate AAR-level TIBCO.xml
        File aarTibcoXml = File.createTempFile("aar-TIBCO-", ".xml");
        aarTibcoXml.deleteOnExit();
        new TibcoXmlGenerator().generateAarDescriptor(
            aarTibcoXml, aarFileName, aa.adapterReference, aa.getSdkVersionFourPart(), globalVars, null);

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(aarFile.toPath()))) {
            zos.setLevel(Deflater.DEFAULT_COMPRESSION);
            addToZip(zos, "TIBCO.xml", aarTibcoXml);
            // Store adapter file with leading slash to match TIBCO Designer's AAR format,
            // where the BW runtime uses the absolute repository path as the entry key.
            String aarEntryPath = adapterFilePath.startsWith("/")
                ? adapterFilePath : "/" + adapterFilePath;
            addToZip(zos, aarEntryPath, adapterFile);
        }
    }

    // -----------------------------------------------------------------------
    //  SAR assembly
    // -----------------------------------------------------------------------

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void buildSar(File sarFile, List<BwFile> sarFiles, File srcDir) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(sarFile.toPath()))) {
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
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(temp.toPath()), StandardCharsets.UTF_8)) {
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

    private void buildEar(File earFile, File tibcoXml, List<File> moduleFiles,
                          File sarFile, File manifestFile) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(earFile.toPath()))) {
            zos.setLevel(Deflater.DEFAULT_COMPRESSION);

            addToZip(zos, "TIBCO.xml", tibcoXml);

            for (File moduleFile : moduleFiles) {
                addToZip(zos, moduleFile.getName(), moduleFile);
            }

            if (sarFile != null && sarFile.exists()) {
                addToZip(zos, sharedArchiveName + ".sar", sarFile);
            }

            if (manifestFile != null && manifestFile.exists()) {
                addToZip(zos, "manifest-bw5.json", manifestFile);
            }
        }
    }

    /** Adds SAR files from {@code source} into {@code accumulated}, deduped by normalized BW path. */
    private void accumulateSarFiles(List<BwFile> source, List<BwFile> accumulated,
                                    Set<String> seen) {
        for (BwFile bwf : source) {
            if (seen.add(normalizeBwPath(bwf.relativePath))) {
                accumulated.add(bwf);
            }
        }
    }

    /** Converts accumulated SAR files to leading-slash BW paths for EXTERNAL_DEPENDENCIES. */
    private List<String> toSarPaths(List<BwFile> sarFiles) {
        List<String> paths = new ArrayList<>();
        for (BwFile bwf : sarFiles) {
            String path = bwf.relativePath.startsWith("/") ? bwf.relativePath : "/" + bwf.relativePath;
            paths.add(path);
        }
        return paths;
    }

    // -----------------------------------------------------------------------
    //  ZIP helpers
    // -----------------------------------------------------------------------

    private void addToZip(ZipOutputStream zos, String entryName, File file) throws IOException {
        // Normalize path separators
        String normalizedName = entryName.replace(File.separatorChar, '/');

        ZipEntry entry = new ZipEntry(normalizedName);
        entry.setTime(file.lastModified());
        zos.putNextEntry(entry);
        try (InputStream in = Files.newInputStream(file.toPath())) {
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

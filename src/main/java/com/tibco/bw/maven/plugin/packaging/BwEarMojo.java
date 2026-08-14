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
     * Charset used to read raw {@code .cpy} (COBOL copybook) source files when wrapping them
     * into their {@code ae.shared.CCBSchemaResource} XML resource.
     *
     * <p>Defaults to {@code ISO-8859-1}, a lossless byte↔char mapping that preserves every
     * source byte (including non-ASCII characters common in copybook comments, e.g. accented
     * text). This deliberately differs from TIBCO {@code buildear}, which reads copybooks as
     * UTF-8 and replaces invalid bytes with U+FFFD — corrupting non-ASCII copybooks. For
     * ASCII-only copybooks (the vast majority) the output is byte-identical to buildear. Set
     * this to {@code UTF-8} (to reproduce buildear exactly) or to the copybook's true charset
     * when it is known.</p>
     */
    @Parameter(defaultValue = "ISO-8859-1", property = "bw5.copybookEncoding")
    private String copybookEncoding;

    /**
     * Archive version stamped into the {@code <version>} of the EAR, every PAR and every AAR —
     * resolved from the {@code .archive} {@code <versionProperty>} (falling back to the POM major
     * version). Set at the start of {@code execute()}; read by the PAR/AAR build helpers.
     */
    private String archiveVersion = "1";

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
     * Generate a flat {@code -services.properties} deployment configuration file alongside the EAR.
     * This is the {@code <services>} section (bindings, processes, runtime/engine properties) of the
     * {@code -deploy.xml} in AppManage {@code key=value} form. Set to {@code false} to skip it.
     */
    @Parameter(defaultValue = "true", property = "bw5.generateServicesProperties")
    private boolean generateServicesProperties;

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
     * Path to a Java {@code .properties} file with <em>per-project service</em> overrides
     * ({@code bw[<par>]/...} keys) merged into the generated {@code -services.properties}
     * before it is written. Also honours {@code bw5.service.*} Maven properties. Lets an
     * environment tune bindings/heap/thread settings without editing generated output.
     *
     * <p>Takes precedence over {@code globalServicePropertiesFile} unless
     * {@code projectPropertiesPrecedence=false}.</p>
     */
    @Parameter(property = "bw5.deployConfig.servicePropertiesFile")
    private File servicePropertiesFile;

    /**
     * Path to a Java {@code .properties} file with <em>common/global service</em> overrides
     * ({@code bw[<par>]/...} keys) shared across projects, merged into the generated
     * {@code -services.properties} at a lower priority than {@code servicePropertiesFile}
     * (unless {@code projectPropertiesPrecedence=false}). Mirrors the two-level model already
     * available for global variables via {@code globalPropertiesFile}/{@code projectPropertiesFile}.
     */
    @Parameter(property = "bw5.deployConfig.globalServicePropertiesFile")
    private File globalServicePropertiesFile;

    /**
     * Controls which override level wins when both a global/common file and a per-project file
     * define the same key, for both global variables and service properties.
     *
     * <p>{@code true} (default): the project-level file wins over the global/common file.
     * {@code false}: the global/common file wins. In both cases inline Maven properties
     * ({@code bw5.project.*}, {@code bw5.global.*}, {@code bw5.service.*}) keep their usual
     * precedence.</p>
     */
    @Parameter(defaultValue = "true", property = "bw5.deployConfig.projectPropertiesPrecedence")
    private boolean projectPropertiesPrecedence;

    /**
     * Value for the application {@code <description>} element of the generated {@code -deploy.xml}
     * (empty by default). For compatibility with the fastconnect plugin the legacy
     * {@code -Ddeploy.description} property is also honoured when this is not set.
     */
    @Parameter(property = "bw5.deploy.description")
    private String deployDescription;

    /**
     * Value for the application {@code <contact>} element of the generated {@code -deploy.xml}
     * (empty by default). For compatibility with the fastconnect plugin the legacy
     * {@code -Ddeploy.contact} property is also honoured when this is not set.
     */
    @Parameter(property = "bw5.deploy.contact")
    private String deployContact;

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

    /**
     * Additional BW engine properties to append to each PAR's <em>Adapter SDK Properties</em>
     * block, on top of the ones read from the bundled {@code com/tibco/deployment/bwengine.xml}.
     *
     * <p>Some TIBCO palettes/hotfixes expose behaviour toggles as engine properties that an
     * administrator adds to the engine's {@code bwengine.xml} — for example the REST/JSON plugin's
     * {@code com.tibco.plugin.restjson.escape.unicodeInText} (defect REST-1803). {@code buildear}
     * reads them from {@code bwengine.xml} and emits them (with the {@code java.property.} twin that
     * turns them into JVM system properties) into every PAR. These are environment/config-driven and
     * not derivable from the project, so they are opt-in here.</p>
     *
     * <p>Each entry is a {@code name=value} pair. For any entry whose name does not already start
     * with {@code java.property.}, the {@code java.property.<name>} twin is emitted automatically
     * (matching buildear), so the property also becomes a {@code -D} JVM system property at runtime.</p>
     *
     * <pre>
     * &lt;extraEngineProperties&gt;
     *   &lt;property&gt;com.tibco.plugin.restjson.escape.unicodeInText=true&lt;/property&gt;
     * &lt;/extraEngineProperties&gt;
     * </pre>
     * <pre>mvn package -Dbw5.extraEngineProperties=com.tibco.plugin.restjson.escape.unicodeInText=true</pre>
     */
    @Parameter(property = "bw5.extraEngineProperties")
    private List<String> extraEngineProperties;

    /**
     * Per-adapter-type overrides for the adapter SDK version stamped into each AAR's
     * {@code minimumComponentSoftwareVersion} / {@code configVersion}.
     *
     * <p>The map key is the adapter <em>type</em> — its component software name / instance-file
     * extension, e.g. {@code adas400}, {@code adr3}, {@code adb}, {@code adldap}. A project may use
     * several adapters of different types (and versions), so the override is keyed per type rather
     * than a single global value.</p>
     *
     * <p>Version precedence (highest first): this override → the {@code .archive} descriptor's
     * {@code <sdkVersion>} for that adapter → the version detected from the local TIBCO install →
     * a built-in default. Values are used verbatim, so give a full 4-part version
     * (e.g. {@code 6.3.0.0}).</p>
     *
     * <pre>
     * &lt;adapterVersions&gt;
     *   &lt;adas400&gt;6.3.0.0&lt;/adas400&gt;
     *   &lt;adr3&gt;7.3.2.0&lt;/adr3&gt;
     * &lt;/adapterVersions&gt;
     * </pre>
     */
    @Parameter
    private Map<String, String> adapterVersions;

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
        // Adapter definition files: included in SAR when any process references them,
        // and also each becomes one AAR via buildAdapterAarsIfNeeded.
        ".adb", ".adadb", ".adas400", ".adfiles", ".adjdexe", ".adldap",
        ".adpsft8", ".adsbl", ".adtuxedo",
        // SAP R/3 adapter configuration files.  An .adr3 file references its connection pool
        // (.adr3Connections) via AESDK:objectGroup elements; adding these to SAR_EXTENSIONS
        // causes followSharedResourceRefs to be called on them, enabling BFS to discover the
        // full .adr3 → .adr3Connections reference chain (otherwise .adr3Connections is
        // silently dropped when transitive analysis is active in no-archive-descriptor mode).
        ".adr3", ".adr3Connections", ".adr3TID",
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
     * Platform-level AESchema files shipped with every TIBCO BW5/TRA installation.
     * These files are always present in project AESchemas directories but are part of
     * the runtime environment — buildEAR never packages them because the BW5 engine
     * already provides them on the target server.
     *
     * <p>Note: {@code AESchemas/ae/baseDocument.aeschema} is intentionally NOT listed here.
     * Despite appearing in every adfiles project source tree, buildear DOES package it in
     * the SAR for adapter projects because it defines the adapter-specific base document
     * type.  Excluding it caused adapter-only SARs to miss this schema.</p>
     */
    private static final Set<String> PLATFORM_AESCHEMA_RELATIVE_PATHS = new HashSet<>(Arrays.asList(
        "AESchemas/corba.aeschema",
        "AESchemas/java.aeschema",
        "AESchemas/sql.aeschema"
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
     * Maps unqualified XSD element name → bwPath for XSD files with no targetNamespace.
     * Used by {@link #extractBwResourceRefs} to resolve {@code <term ref="localName"/>}
     * references in XMLParseActivity/coercion activities (no namespace prefix = unqualified).
     * Built alongside {@link #xsdNsIndex} and cleared after each analysis invocation.
     */
    private Map<String, String> xsdElementIndex = Collections.emptyMap();

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

        File srcDir = getEffectiveSourceDir();
        getLog().info("Assembling BW5 EAR from: " + srcDir.getAbsolutePath());
        getLog().info("Archive name: " + archiveName);

        try {
            // 0. Optionally load the .archive descriptor
            ArchiveDescriptorParser.ArchiveDescriptor archiveDescriptor = loadArchiveDescriptor();

            // Archive version stamped into every EAR/PAR/AAR <version> (matches buildear, which
            // uses the .archive <versionProperty>). Falls back to the POM major version when no
            // descriptor version is available.
            String pomMajor = project.getVersion().contains(".")
                ? project.getVersion().substring(0, project.getVersion().indexOf('.'))
                : project.getVersion();
            archiveVersion = (archiveDescriptor != null && archiveDescriptor.version != null
                    && !archiveDescriptor.version.isEmpty())
                ? archiveDescriptor.version : pomMajor;

            // 1. Collect all project files into a shared pool
            List<BwFile> allParFiles = new ArrayList<>();
            List<BwFile> allSarFiles = new ArrayList<>();
            List<BwFile> metadataFiles = new ArrayList<>();
            collectFiles(srcDir, srcDir, allParFiles, allSarFiles, metadataFiles);
            collectFilesFromProjlibs(allParFiles, allSarFiles, metadataFiles);

            // 2. Parse global variables from .substvar metadata files
            List<SubstVarParser.GlobalVariable> globalVars = parseGlobalVars(metadataFiles);
            // SAP R/3 adapter: loadGlobalVariablesForR3() unconditionally registers SNC GVs
            // when the .adr3 resource is added to the designer document, even when SNC is disabled.
            injectSapSncGvarsIfNeeded(allSarFiles, globalVars);
            // buildear reads the project encoding from vcrepo.dat and uses it as the
            // MessageEncoding GV value (default ISO8859-1 if not found).
            injectMessageEncodingFromVcrepoDat(bwProjectPath, globalVars);
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
            // One service model per PAR — drives the <services> block of the -deploy.xml and the
            // services.properties file (bindings/processes deployment template).
            List<DeploymentConfigGenerator.ServiceModel> serviceModels = new ArrayList<>();
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
                    sarFiles.removeIf(f -> isAdapterInstanceFile(f.file)
                            && !adapterSupportsAar(getExtNoDot(f.file)));

                    if (pa.hasExplicitProcessList()) {
                        promoteServiceAgentsFromDescriptor(parFiles, sarFiles, pa.processPaths);
                        // Multi-PAR: filter processes by reachability so each PAR owns its own set
                        applyTransitiveDependencyAnalysis(parFiles, sarFiles,
                            pa.processPaths, archiveDescriptor.sharedResourcePaths, true);
                    } else {
                        // No BFS: .folder files survive unreferenced. Strip them now;
                        // addAdapterFolderMetadata will add back the adapter-specific ones.
                        sarFiles.removeIf(f -> normalizeBwPath(f.relativePath).endsWith("/.folder"));
                    }
                    addGvReferencedResources(globalVars, sarFiles, allSarFiles);
                    addAdapterFolderMetadata(sarFiles, allSarFiles);
                    accumulateSarFiles(sarFiles, combinedSarFiles, seenSarPaths);

                    // Each PAR's EXTERNAL_RESOURCE_DEPENDENCY lists only ITS OWN reachable SAR
                    // resources (buildear scopes them per-archive via getArchiveDependencies /
                    // getAssignedResources) — NOT the accumulated union across all PARs.
                    // combinedSarFiles is the union used to assemble the shared SAR; the per-PAR
                    // extdep uses this PAR's sarFiles. (Using the union made every PAR list the
                    // other PARs' schemas/wsdls — the BookingDetails/EVENTS "sobra".)
                    List<String> sarPaths = toSarPaths(sarFiles);
                    List<ProcessParser.ProcessMetadata> meta = parseProcesses(parFiles, srcDir);
                    // Descriptor-based PAR: the EXTERNAL_RESOURCE_DEPENDENCY process members are
                    // the declared processProperty entries (getHiddenReferences), not just starters.
                    List<String> declaredProcessPaths = pa.hasExplicitProcessList()
                        ? pa.processPaths : null;
                    buildPar(parFile, parFiles, meta, sarPaths, globalVars, declaredProcessPaths);
                    moduleFiles.add(parFile);
                    serviceModels.add(toServiceModel(parFile.getName(), meta));
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
                // Remove adapter instance files whose extension is not in AAR_ADAPTER_EXTENSIONS
                // (e.g. unknown future adapters or non-AESDK files with a .ad* extension) from
                // sarFiles so they do not trigger BFS traversal or appear in the SAR.
                sarFiles.removeIf(f -> isAdapterInstanceFile(f.file)
                        && !adapterSupportsAar(getExtNoDot(f.file)));

                if (archiveDescriptor != null && archiveDescriptor.hasExplicitProcessList()) {
                    promoteServiceAgentsFromDescriptor(parFiles, sarFiles,
                        archiveDescriptor.getProcessPaths());
                    // Single-PAR: same filtering as multi-PAR — only processes reachable from
                    // the processProperty entry points are included. Processes that exist on disk
                    // but are not reachable from any entry point are excluded, matching buildEAR.
                    applyTransitiveDependencyAnalysis(parFiles, sarFiles,
                        archiveDescriptor.getProcessPaths(), archiveDescriptor.sharedResourcePaths,
                        true);
                } else {
                    // No archive descriptor (or descriptor without an explicit process list).
                    // When adapter instance files are present, buildear seeds the PAR only from
                    // processes that have a <pd:starter> element, then follows sub-process calls
                    // transitively. Non-starter processes not reachable from any starter are
                    // excluded (e.g. RPC request-reply sub-services in adldap/adfiles projects).
                    // For plain BW5 projects (no adapter instances), seed from ALL processes —
                    // buildear packages every process regardless of starter presence.
                    List<String> sharedResPaths = archiveDescriptor != null
                        ? archiveDescriptor.sharedResourcePaths : Collections.emptyList();
                    boolean hasAdapterInstances = allSarFiles.stream()
                        .anyMatch(f -> isAdapterInstanceFile(f.file));
                    if (hasAdapterInstances && !parFiles.isEmpty()) {
                        List<String> starterPaths = collectStarterPaths(parFiles);
                        if (!starterPaths.isEmpty()) {
                            applyTransitiveDependencyAnalysis(parFiles, sarFiles,
                                starterPaths, sharedResPaths, true);
                        } else {
                            // Adapter instances present but NO starter processes (e.g.
                            // adfiles/ManualSchema's single non-starter test process). buildear
                            // excludes the non-starter processes from the PAR — but still emits
                            // an (empty) Process Archive.par because the project has .process
                            // files. BFS with empty seeds + filterParByReachability=true empties
                            // parFiles; collectAdapterAeschemas then fills the SAR, and the empty
                            // PAR is emitted below (see hadProcessFiles).
                            applyTransitiveDependencyAnalysis(parFiles, sarFiles,
                                Collections.emptyList(), sharedResPaths, true);
                        }
                    } else {
                        applyTransitiveDependencyAnalysis(parFiles, sarFiles,
                            Collections.emptyList(), sharedResPaths, false);
                    }
                }

                String parFileName = "Process Archive.par";
                String descriptorParName = archiveDescriptor != null
                    ? archiveDescriptor.getProcessArchiveName() : null;
                if (descriptorParName != null && !descriptorParName.isEmpty()) {
                    parFileName = descriptorParName + ".par";
                }

                addGvReferencedResources(globalVars, sarFiles, allSarFiles);
                addAdapterFolderMetadata(sarFiles, allSarFiles);
                getLog().info("Process files (PAR): " + parFiles.size());
                getLog().info("Shared resource files (SAR): " + sarFiles.size());

                accumulateSarFiles(sarFiles, combinedSarFiles, seenSarPaths);

                // Adapter-only projects (no .process files): BFS seeds from processes and
                // finds nothing, leaving the SAR empty.  Scan every adapter instance file
                // (.adfiles, .adldap, …) directly for AESchema references and add them to
                // the SAR — replicating what the multi-archive path does for explicit
                // <adapterArchive> entries.
                if (parFiles.isEmpty()) {
                    collectAdapterAeschemas(allSarFiles, combinedSarFiles, seenSarPaths);
                }

                // buildear emits a Process Archive.par whenever the project contains any
                // .process files — even if none are runnable (all non-starter), in which case
                // the PAR is empty of processes but still present. A genuinely adapter-only
                // project (no .process files at all) gets no PAR.
                boolean hadProcessFiles = !allParFiles.isEmpty();
                if (!parFiles.isEmpty() || hadProcessFiles) {
                    // An empty PAR (project has .process files but none are packaged) lists no
                    // EXTERNAL_RESOURCE_DEPENDENCY — buildear scopes it to the PAR's processes,
                    // of which there are none. A PAR with processes lists the SAR resources.
                    List<String> sarPaths = parFiles.isEmpty()
                        ? Collections.emptyList() : toSarPaths(combinedSarFiles);
                    List<ProcessParser.ProcessMetadata> meta = parseProcesses(parFiles, srcDir);
                    // Descriptor-based PAR: EXTERNAL_RESOURCE_DEPENDENCY process members are the
                    // declared processProperty entries (getHiddenReferences), not just starters.
                    // Skip for the empty PAR (no processes packaged → no dependency list).
                    List<String> declaredProcessPaths =
                        (!parFiles.isEmpty() && archiveDescriptor != null
                            && archiveDescriptor.hasExplicitProcessList())
                            ? archiveDescriptor.getProcessPaths() : null;
                    File parFile = new File(workDir, parFileName);
                    buildPar(parFile, parFiles, meta, sarPaths, globalVars, declaredProcessPaths);
                    moduleFiles.add(parFile);
                    serviceModels.add(toServiceModel(parFile.getName(), meta));
                    getLog().info("PAR assembled: " + parFile.getName() + " ("
                        + parFile.length() + " bytes, " + parFiles.size() + " process(es))");
                } else {
                    getLog().info("Adapter-only project: no process files, skipping PAR.");
                }
            }

            // 5b. Auto-create AARs for SAP R/3 adapter instance files (.adr3/.adr3TID).
            // When there is no archive descriptor (bare project, no .archive file), buildear
            // scans everything and creates one AAR per .adr3 regardless of whether a process
            // references it — use allSarFiles to replicate that behaviour.
            // When a descriptor exists, buildear respects its scope: unreferenced .adr3 files
            // that are not listed as <adapterArchive> entries are silently ignored — use
            // combinedSarFiles (post-BFS) so only reachable instances get an AAR.
            List<BwFile> aarSourceFiles = (archiveDescriptor == null) ? allSarFiles : combinedSarFiles;
            buildAdapterAarsIfNeeded(aarSourceFiles, combinedSarFiles, moduleFiles, workDir,
                    globalVars, archiveDescriptor);

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
            TibcoXmlGenerator generator = new TibcoXmlGenerator();
            generator.generateEarDescriptor(
                earTibcoXml,
                effectiveEarName,
                archiveVersion,
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
                generateDeploymentConfigs(configuredVars, serviceModels);
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
                merger.merge(vars, globalPropertiesFile, projectPropertiesFile, mavenProps,
                        projectPropertiesPrecedence);
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

    private void generateDeploymentConfigs(List<SubstVarParser.GlobalVariable> globalVars,
            List<DeploymentConfigGenerator.ServiceModel> serviceModels)
            throws MojoExecutionException {

        if (earOnly || (!generateDeployXml && !generateProperties
                && !generateValuesYaml && !generateServicesProperties)) {
            return;
        }

        String finalName = project.getBuild().getFinalName();
        String appName   = project.getArtifactId();
        String appVersion = project.getVersion();
        File targetDir   = new File(project.getBuild().getDirectory());

        DeploymentConfigGenerator gen = new DeploymentConfigGenerator();

        // Build the service-property map once (with overrides merged) so both the <services> block
        // of the -deploy.xml and the -services.properties file reflect the same values.
        Map<String, String> serviceFlat = null;
        if (generateDeployXml || generateServicesProperties) {
            serviceFlat = applyServicePropertyOverrides(
                    gen.servicePropertyMap(globalVars, serviceModels));
        }

        try {
            if (generateDeployXml) {
                File out = new File(targetDir, finalName + "-deploy.xml");
                gen.generateDeployXml(out, appName, appVersion,
                        resolveWithFallback(deployDescription, "deploy.description"),
                        resolveWithFallback(deployContact, "deploy.contact"),
                        globalVars, serviceModels, serviceFlat);
                getLog().info("Generated deploy XML : " + out.getName());
            }
            if (generateProperties) {
                File out = new File(targetDir, finalName + "-deploy.properties");
                gen.generateProperties(out, appName, appVersion, globalVars);
                getLog().info("Generated properties : " + out.getName());
            }
            if (generateServicesProperties) {
                File out = new File(targetDir, finalName + "-services.properties");
                gen.generateServicesProperties(out, appName, appVersion, serviceFlat);
                getLog().info("Generated services   : " + out.getName());
            }
            if (generateValuesYaml) {
                File out = new File(targetDir, finalName + "-values.yaml");
                gen.generateValuesYaml(out, appName, appVersion, globalVars);
                getLog().info("Generated values.yaml: " + out.getName());
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate deployment config files: " + e.getMessage(), e);
        }
    }

    /**
     * Builds the {@link DeploymentConfigGenerator.ServiceModel} for a PAR from its process
     * metadata: one entry per process/serviceagent that has a starter (the entry points
     * AppManage lists under {@code <bwprocesses>}).
     */
    private DeploymentConfigGenerator.ServiceModel toServiceModel(
            String parFileName, List<ProcessParser.ProcessMetadata> meta) {
        List<DeploymentConfigGenerator.ServiceModel.ProcessEntry> entries = new ArrayList<>();
        for (ProcessParser.ProcessMetadata m : meta) {
            if (m.hasStarter) {
                entries.add(new DeploymentConfigGenerator.ServiceModel.ProcessEntry(
                        m.name, m.starterName));
            }
        }
        return new DeploymentConfigGenerator.ServiceModel(parFileName, entries);
    }

    /**
     * Applies {@code servicePropertiesFile} (and {@code bw5.service.*} Maven properties) overrides
     * on top of the generated service-property map, so the {@code services.properties} artifact can
     * be tuned per environment (heap sizes, thread counts, extra machines) and merged into a
     * deployment. Returns the map unchanged when no override source is configured.
     */
    private Map<String, String> applyServicePropertyOverrides(Map<String, String> base)
            throws MojoExecutionException {
        try {
            return new PropertyMerger().mergeServiceProperties(
                    base, globalServicePropertiesFile, servicePropertiesFile,
                    getAllMavenProperties(), projectPropertiesPrecedence);
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } catch (Exception e) {
            getLog().warn("Could not apply service-property overrides: " + e.getMessage()
                    + " — using generated defaults");
            return base;
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
                // Files whose name contains '\' are Windows-path checkout artifacts on Linux
                // (the full Windows path was stored as a single filename). Skip them.
                if (name.contains("\\")) {
                    getLog().warn("Skipping Windows-path checkout artifact: " + f.getAbsolutePath());
                    continue;
                }
                String ext = getExtension(name);
                if (EXCLUDED_EXTENSIONS.contains(ext)) {
                    // .folder files are always collected into the full pool so that
                    // addAdapterFolderMetadata() can selectively add adapter-specific
                    // ones back after BFS.  Non-adapter .folder files are filtered out
                    // by BFS because no resource references them.
                    if (!".folder".equals(ext)) continue;
                }
                String relativePath = rootDir.toURI().relativize(f.toURI()).getPath();
                if (PLATFORM_AESCHEMA_RELATIVE_PATHS.contains(relativePath)) continue;
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
            boolean isServiceAgent =
                bwf.file.getName().toLowerCase(Locale.ROOT).endsWith(".serviceagent");
            try {
                ProcessParser.ProcessMetadata meta;
                if (isServiceAgent) {
                    // Service agents are top-level runnable modules: they need their own
                    // BwBPConfiguration entry keyed by the .serviceagent BW path.
                    meta = parser.parseServiceAgent(bwf.file);
                    meta.name = normalizeBwPath(bwf.relativePath);
                    if (meta.starterName == null || meta.starterName.isEmpty()) {
                        String fn = bwf.file.getName();
                        int dot = fn.lastIndexOf('.');
                        meta.starterName = dot > 0 ? fn.substring(0, dot) : fn;
                    }
                } else {
                    meta = parser.parse(bwf.file);
                    // buildear keys BwBPConfiguration.processDefinitionName (and the BOM entry)
                    // off the resource's REPOSITORY PATH — ProcessDeploymentData.getPath() in
                    // BaseProcessArchive.updateBWBpConfigurations — not the process's internal
                    // <pd:name>. They usually match, but a process copied between folders keeps a
                    // stale internal name (e.g. an MVS process whose <pd:name> still reads
                    // BusinessDomains/COMPLEX/...), so use the actual file location to match buildear.
                    meta.name = normalizeBwPath(bwf.relativePath);
                }
                result.add(meta);
            } catch (Exception e) {
                getLog().warn("Could not parse "
                    + (isServiceAgent ? "service agent" : "process") + " file: "
                    + bwf.file.getName() + " - " + e.getMessage());
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

    private static final List<String> SAP_SNC_GVAR_NAMES = Arrays.asList(
            "SncLib", "SncMode", "SncPartnername", "SncQop");

    private void injectSapSncGvarsIfNeeded(List<BwFile> sarFiles,
                                            List<SubstVarParser.GlobalVariable> globalVars) {
        boolean hasSapAdapter = sarFiles.stream()
                .anyMatch(f -> f.relativePath.toLowerCase(Locale.ROOT).endsWith(".adr3"));
        if (!hasSapAdapter) return;

        Set<String> existingNames = new HashSet<>();
        for (SubstVarParser.GlobalVariable v : globalVars) existingNames.add(v.name);

        for (String gvName : SAP_SNC_GVAR_NAMES) {
            if (existingNames.contains(gvName)) continue;
            SubstVarParser.GlobalVariable gv = new SubstVarParser.GlobalVariable();
            gv.name = gvName;
            gv.value = "";
            gv.type = "String";
            gv.requiresConfiguration = true;
            globalVars.add(gv);
            getLog().debug("SAP SNC GV injected: " + gvName);
        }
        globalVars.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
    }

    /**
     * Reads the project encoding from {@code vcrepo.dat} (TIBCO SourceSafe metadata) and
     * injects it as the {@code MessageEncoding} GV when it is not already present.
     *
     * <p>buildear reads the project encoding from {@code vcrepo.dat} via
     * {@code EnterpriseArchiveBuilderResource} and writes it to the EAR-level TIBCO.xml
     * as the {@code MessageEncoding} global variable. When the vcrepo.dat is absent or has
     * no encoding entry, buildear defaults to {@code ISO8859-1} — which our generator
     * also uses as the fallback in {@code TibcoXmlGenerator.generateEarDescriptor()}.</p>
     *
     * <p>This method only reads {@code vcrepo.dat} and adds the GV; the {@code ISO8859-1}
     * fallback is provided by the generator so there is no duplication.</p>
     */
    private void injectMessageEncodingFromVcrepoDat(File projectDir,
                                                     List<SubstVarParser.GlobalVariable> globalVars) {
        boolean alreadyPresent = globalVars.stream()
                .anyMatch(v -> "MessageEncoding".equals(v.name));
        if (alreadyPresent) return;

        File vcrepodat = new File(projectDir, "vcrepo.dat");
        if (!vcrepodat.isFile()) return;

        try {
            String content = new String(Files.readAllBytes(vcrepodat.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            // <instanceInfoProperty name="encoding" value="UTF-8"/>
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("name=\"encoding\"\\s+value=\"([^\"]+)\"")
                    .matcher(content);
            if (!m.find()) return;

            String encoding = m.group(1).trim();
            if (encoding.isEmpty()) return;

            SubstVarParser.GlobalVariable gv = new SubstVarParser.GlobalVariable();
            gv.name = "MessageEncoding";
            gv.value = encoding;
            gv.type = "String";
            gv.requiresConfiguration = false;
            globalVars.add(gv);
            // Keep the list sorted so MessageEncoding sorts correctly
            globalVars.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            getLog().debug("MessageEncoding read from vcrepo.dat: " + encoding);
        } catch (IOException e) {
            getLog().debug("Could not read vcrepo.dat for MessageEncoding: " + e.getMessage());
        }
    }

    /**
     * Adds to {@code sarFiles} any file from {@code allSarFiles} whose BW path is stored
     * verbatim as a global-variable value (e.g. {@code salesforce.wsdl =
     * /SalesforceResources/partner_27_0.wsdl}).
     *
     * <p>buildear discovers such dependencies by following GV-path references at build time.
     * Without this step, files referenced only via a GV value (not via any XSD/process import)
     * are silently omitted from the SAR.</p>
     */
    private void addGvReferencedResources(
            List<SubstVarParser.GlobalVariable> globalVars,
            List<BwFile> sarFiles,
            List<BwFile> allSarFiles) {
        Map<String, BwFile> allIdx = buildBwIndex(allSarFiles);
        Set<String> included = new HashSet<>();
        for (BwFile f : sarFiles) included.add(normalizeBwPath(f.relativePath));

        for (SubstVarParser.GlobalVariable gv : globalVars) {
            String val = gv.value;
            if (val == null || val.isEmpty() || !val.startsWith("/")) continue;
            // Skip values without a file extension (not a resource path)
            String norm = normalizeBwPath(val);
            if (!norm.contains(".")) continue;
            // A GV whose value points at a .serviceagent must NOT force it into the SAR.
            // buildear places a Java Global service agent in the SAR only when a packaged
            // process references it via <JavaGlobalInstance> (handled in the transitive
            // analysis), never merely because a global variable names its path.
            if (norm.endsWith(".serviceagent")) continue;
            if (included.contains(norm)) continue;
            BwFile f = allIdx.get(norm);
            if (f != null) {
                getLog().debug("GV-path resource added to SAR: " + norm
                        + " (from GV %%" + gv.name + "%%)");
                sarFiles.add(f);
                included.add(norm);
            }
        }
    }

    /**
     * Adapter type-folder names (one level below {@code AESchemas/ae/}) whose {@code .folder}
     * file must be included in the SAR unconditionally.
     *
     * <p>Each listed adapter's palette class overrides {@code getExportPartners()} and explicitly
     * adds the {@code AESchemas/ae/<Name>} {@link com.tibco.ae.designerapi.DesignerFolder} to its
     * export-partner list, which causes buildear to write the corresponding {@code .folder} file
     * into the SAR.  Adapters <em>not</em> listed here (ADB, Files, JD Edwards) never return that
     * folder resource and therefore do not get a {@code .folder} in the SAR.</p>
     */
    private static final Set<String> ADAPTER_TYPE_FOLDER_NAMES;
    static {
        Set<String> s = new HashSet<>();
        s.add("SAPAdapter40");  // SAP R/3  — R3AdapterInstance.getExportPartners()
        s.add("AS400");         // AS400     — AS400AdapterConfiguration.getExportPartners()
        s.add("PeopleSoft8");   // PeopleSoft 8 — PeopleSoftAdapterConfiguration.getExportPartners()
        s.add("siebel");        // Siebel    — SiebelAdapterInstance.getExportPartners()
        s.add("SWIFTAdapter");  // SWIFT     — SwiftActivityResource.getExportPartners()
        s.add("Tuxedo");        // Tuxedo    — TuxedoAdapterConfiguration.getExportPartners()
        ADAPTER_TYPE_FOLDER_NAMES = Collections.unmodifiableSet(s);
    }

    /**
     * Includes {@code .folder} files for adapter-specific schema directories that buildear
     * unconditionally adds to the SAR via each adapter's {@code getExportPartners()} chain.
     *
     * <p>The rule mirrors buildear's behavior: a {@code .folder} file is added for directory D
     * when D directly contains at least one non-{@code .folder} file already in {@code sarFiles}
     * AND the path of D is adapter-specific:
     * <ul>
     *   <li>{@code AESchemas/ae/<KnownAdapterName>} — covers SAP R/3, AS400, PeopleSoft,
     *       Siebel, SWIFT, Tuxedo</li>
     *   <li>Starts with {@code AESchemas/ae/adapter/} — covers LDAP and similar adapters
     *       whose schemas live under the generic adapter sub-tree</li>
     * </ul>
     */
    private void addAdapterFolderMetadata(List<BwFile> sarFiles, List<BwFile> allSarFiles) {
        // Index all .folder files from the full project pool, keyed by their parent directory
        Map<String, BwFile> folderByDir = new HashMap<>();
        for (BwFile f : allSarFiles) {
            String norm = normalizeBwPath(f.relativePath);
            if (norm.endsWith("/.folder")) {
                String dir = norm.substring(0, norm.length() - "/.folder".length());
                folderByDir.put(dir, f);
            }
        }

        // Collect directories that directly contain SAR resources and match adapter paths
        Set<String> included = new HashSet<>();
        for (BwFile f : sarFiles) included.add(normalizeBwPath(f.relativePath));

        Set<String> dirsToInclude = new LinkedHashSet<>();
        for (BwFile f : sarFiles) {
            String norm = normalizeBwPath(f.relativePath);
            if (norm.endsWith("/.folder")) continue;
            int slash = norm.lastIndexOf('/');
            if (slash <= 0) continue;
            String dir = norm.substring(0, slash);
            if (isAdapterTypeFolder(dir)) {
                dirsToInclude.add(dir);
            }
        }

        for (String dir : dirsToInclude) {
            BwFile folderFile = folderByDir.get(dir);
            if (folderFile == null) continue;
            String folderPath = normalizeBwPath(folderFile.relativePath);
            if (!included.contains(folderPath)) {
                getLog().debug("Adapter .folder added to SAR: " + folderPath);
                sarFiles.add(folderFile);
                included.add(folderPath);
            }
        }
    }

    private static boolean isAdapterTypeFolder(String dir) {
        if (!dir.startsWith("AESchemas/ae/")) return false;
        String sub = dir.substring("AESchemas/ae/".length());
        // LDAP and adapters under the generic adapter sub-tree
        if (sub.startsWith("adapter/")) return true;
        // Named adapter type folders (one level below ae/)
        return !sub.contains("/") && ADAPTER_TYPE_FOLDER_NAMES.contains(sub);
    }

    private static final String SHAREDJDBC_EXT = ".sharedjdbc";

    /**
     * Derives the JDBC checkpoint repository paths from the SAR resource list. Every
     * {@code .sharedjdbc} resource in the SAR is a candidate checkpoint repository, listed
     * in the PAR-level TIBCO.xml as {@code <chk:availableSharedResourceName>} with the
     * extension stripped.
     *
     * <p>Deriving from {@code sarPaths} (rather than re-scanning the source tree) ensures
     * JDBC connections that live inside project library ({@code .projlib}) dependencies —
     * whose resources are already collected into the SAR under their BW repository path —
     * are included. A filesystem scan of the project source misses them.</p>
     */
    static List<String> jdbcCheckpointPaths(List<String> sarPaths) {
        List<String> paths = new ArrayList<>();
        if (sarPaths != null) {
            for (String p : sarPaths) {
                if (p.toLowerCase(Locale.ROOT).endsWith(SHAREDJDBC_EXT)) {
                    paths.add(p.substring(0, p.length() - SHAREDJDBC_EXT.length()));
                }
            }
        }
        java.util.Collections.sort(paths);
        return paths;
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
     * Scans {@code dir} recursively for files with a {@code .archive} extension.
     *
     * <p>TIBCO Designer stores the {@code .archive} descriptor under the project's
     * {@code Deployment/} subfolder (not the project root), so a non-recursive scan misses it
     * and the EAR falls back to the default {@code "Process Archive.par"} PAR name. Walking the
     * tree lets auto-discovery find {@code Deployment/<App>.archive} and honour its
     * {@code processArchive/@name}.</p>
     *
     * <p>Build-output and VCS directories ({@code target}, {@code .git}, {@code .svn}) are skipped
     * so a copy under {@code target/bw-src/Deployment/} is never picked up. Results are sorted by
     * absolute path for deterministic selection when more than one descriptor exists.</p>
     */
    static List<File> findArchiveFiles(File dir) {
        List<File> result = new ArrayList<>();
        collectArchiveFiles(dir, result);
        result.sort(Comparator.comparing(File::getAbsolutePath));
        return result;
    }

    private static void collectArchiveFiles(File dir, List<File> result) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                String name = f.getName();
                if ("target".equals(name) || ".git".equals(name) || ".svn".equals(name)) continue;
                collectArchiveFiles(f, result);
            } else if (f.getName().endsWith(".archive")) {
                result.add(f);
            }
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
        // Build element-name index for unqualified term refs (no-namespace XSD schemas).
        this.xsdElementIndex = buildXsdElementIndex(resourceIndex);

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
        this.xsdElementIndex = buildXsdElementIndex(extIndex);
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
        this.xsdElementIndex = Collections.emptyMap();

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

        // A Java Global service agent already promoted to the PAR from the descriptor's
        // processProperty list is ALSO placed in the SAR when a packaged process references
        // it via <JavaGlobalInstance>. Such agents were pulled out of the SAR pool during
        // promotion (they are not in resourceIndex), so the loop above cannot find them —
        // resolve them from the promoted-PAR set instead. buildear packages Java Global
        // service agents in BOTH the PAR and the SAR.
        Map<String, BwFile> promotedIndex = buildBwIndex(promotedParEntries);
        for (String path : referencedResourcePaths) {
            if (!path.endsWith(".serviceagent")) continue;
            if (resourceIndex.containsKey(path)) continue;
            BwFile f = promotedIndex.get(path);
            if (f != null && sarPathsSeen.add(normalizeBwPath(f.relativePath))) {
                getLog().info("Java Global serviceagent also placed in SAR (JavaGlobalInstance): "
                        + f.relativePath);
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
     * Builds a map of top-level XSD element names to BW paths, restricted to XSD files
     * that have no {@code targetNamespace} (i.e. unqualified schemas).
     *
     * <p>Used to resolve {@code <term ref="localName"/>} references (no namespace prefix)
     * in BW5 XMLParseActivity and coercion activities. An unqualified term ref means the
     * element lives in a schema with no targetNamespace; BW Designer locates it by scanning
     * all no-namespace XSDs for a matching top-level element name.</p>
     */
    private Map<String, String> buildXsdElementIndex(Map<String, BwFile> resourceIndex) {
        Map<String, String> elemIndex = new HashMap<>();
        org.jdom2.Namespace XSD_NS = org.jdom2.Namespace.getNamespace(
            "http://www.w3.org/2001/XMLSchema");
        for (Map.Entry<String, BwFile> entry : resourceIndex.entrySet()) {
            if (!entry.getKey().endsWith(".xsd")) continue;
            try {
                Document doc = new SAXBuilder().build(entry.getValue().file);
                org.jdom2.Element root = doc.getRootElement();
                // Only index XSDs with no targetNamespace (unqualified schemas)
                if (root.getAttributeValue("targetNamespace") != null) continue;
                for (org.jdom2.Element child : root.getChildren("element", XSD_NS)) {
                    String name = child.getAttributeValue("name");
                    if (name != null && !name.isEmpty()) {
                        elemIndex.putIfAbsent(name, entry.getKey());
                    }
                }
            } catch (org.jdom2.JDOMException | IOException ignored) { }
        }
        return elemIndex;
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
        String fileNameLower = file.getName().toLowerCase(Locale.ROOT);
        // AESDK:loadUrl elements in .adr3/.adr3TID/.adr3Connections files reference runtime
        // type definitions that the adapter loads from its plugin JAR, not from the project.
        // Skipping them prevents spurious aeschema inclusions (e.g. IDOCS.aeschema for
        // IDocFormatPublishingMode). For .adb/.adsap/.adldap files, loadUrls DO reference
        // project SAR aeschemas (e.g. adbmetadata.aeschema) and must continue to be followed.
        boolean skipLoadUrl = fileNameLower.endsWith(".adr3")
                || fileNameLower.endsWith(".adr3tid")
                || fileNameLower.endsWith(".adr3connections");
        Set<String> refs = new LinkedHashSet<>();
        try {
            SAXBuilder builder = new SAXBuilder();
            Document doc = builder.build(file);
            Namespace pdNs = Namespace.getNamespace("pd", "http://xmlns.tibco.com/bw/process/2003");
            for (Element e : doc.getDescendants(Filters.element())) {
                // Skip AESDK:loadUrl in .adr3 adapter config files — runtime schema loading only.
                if (skipLoadUrl && "loadUrl".equals(e.getName())) continue;
                // <ApplicationProperties> inside an AE adapter activity (com.tibco.plugin.ae.*)
                // configures the adapter's internal JMS transport, not a project shared resource.
                // Standard JMS palette activities (com.tibco.plugin.jms.*) DO reference project
                // .sharedjmsapp resources via the same element — those must still be followed.
                if ("ApplicationProperties".equals(e.getName())) {
                    Element configEl = e.getParentElement();
                    Element activityEl = (configEl != null) ? configEl.getParentElement() : null;
                    if (activityEl != null) {
                        Element typeEl = activityEl.getChild("type", pdNs);
                        String actType = (typeEl != null) ? typeEl.getTextTrim() : "";
                        if (actType.startsWith("com.tibco.plugin.ae.")) continue;
                    }
                }
                String text = toAbsoluteBwRef(e.getTextTrim());
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
                // REST plugin starter processes declare their implementation via Binding/process
                // attribute (e.g. <Binding process="/pkg/RestImpl.process"/>).
                String processAttr = e.getAttributeValue("process");
                if (processAttr != null && isBwResourcePath(processAttr)) refs.add(processAttr);
                // XMLParseActivity / coercion activities declare their output schema via
                // <term ref="ElementName"/> (unqualified, no prefix) or
                // <term ref="pfx:ElementName"/> (qualified, namespace already covered by
                // xs:import schemaLocation in the same process file).
                // For the unqualified case we look up the element name in xsdElementIndex
                // (built from XSD files with no targetNamespace) to get the bwPath.
                if ("term".equals(e.getName())) {
                    String termRef = e.getAttributeValue("ref");
                    if (termRef != null && !termRef.contains(":") && !xsdElementIndex.isEmpty()) {
                        String xsdPath = xsdElementIndex.get(termRef);
                        getLog().debug("term ref=" + termRef + " → xsdPath=" + xsdPath);
                        if (xsdPath != null) refs.add("/" + xsdPath);
                    }
                }
            }
        } catch (org.jdom2.JDOMException | IOException e) {
            getLog().debug("Could not parse refs from " + file.getName() + ": " + e.getMessage());
        }
        return refs;
    }

    /**
     * Converts a raw string from a BW5 process element into a canonical absolute BW ref.
     *
     * <p>BW Designer occasionally emits relative paths (no leading {@code /}) for palette
     * resource references, most notably COBOL CopyBook activities which can produce paths like
     * {@code BusinessDomains/.../CopyBooks//Foo.cpy} — relative and with a double-slash.
     * This method converts such values to an absolute BW path so that {@link #isBwResourcePath}
     * and the resource index lookup can handle them uniformly.</p>
     *
     * <p>A leading {@code /} is prepended only when the value looks like a file path: it must
     * contain a {@code /} separator AND have a file-extension dot in its last segment. This
     * avoids false positives from arbitrary text content that happens to contain dots.</p>
     */
    private static String toAbsoluteBwRef(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        // Collapse double slashes first (works for both absolute and relative)
        String s = raw.contains("//") ? raw.replace("//", "/") : raw;
        if (s.startsWith("/")) return s;
        // For relative paths, prepend '/' only when the value looks like a file path:
        // must contain at least one '/' AND have an extension dot after the last '/'.
        int lastSlash = s.lastIndexOf('/');
        if (lastSlash < 0) return s;                        // single segment, not a path
        int dot = s.lastIndexOf('.');
        if (dot <= lastSlash) return s;                     // no extension in last segment
        return "/" + s;
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
            // Strip trailing slash: AESDK "/#class" refs like "/AESchemas/ae/SAPAdapter40/classes/#class"
            // become "/AESchemas/ae/SAPAdapter40/classes/" after fragment stripping, yielding an empty
            // lastName below without this normalization.
            String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            int lastSlash = p.lastIndexOf('/');
            String lastName = p.substring(lastSlash + 1);
            if (!lastName.isEmpty() && !lastName.contains(".")) {
                dirRefs.add(p);
            }
        }
        for (String dir : dirRefs) {
            String prefix = dir + "/";
            boolean anyFound = false;
            for (String resourcePath : resourceIndex.keySet()) {
                if (resourcePath.startsWith(prefix)) {
                    // pd:targetNamespace values (e.g. "/EventHandler/Procesos") look like
                    // directory refs and expand the whole folder — skip metadata files
                    // (e.g. .folder, .bak) that must only be added via addAdapterFolderMetadata.
                    if (EXCLUDED_EXTENSIONS.contains(getExtension(resourcePath))) continue;
                    if (refs.add(resourcePath)) {
                        getLog().debug("Directory ref expanded: " + dir + " → " + resourcePath);
                    }
                    anyFound = true;
                }
            }
            if (!anyFound) {
                // Fallback: the "directory" path may actually be the base name of an .aeschema
                // file (AESDK /#class fragment refs like /AESchemas/ae/SAPAdapter40/classes/#class
                // strip to /AESchemas/ae/SAPAdapter40/classes — no extension, looks like a dir,
                // but the real file is classes.aeschema in the parent directory).
                String candidate = dir + ".aeschema";
                if (resourceIndex.containsKey(candidate) && refs.add(candidate)) {
                    getLog().debug("No-extension ref resolved as .aeschema: " + dir + " → " + candidate);
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

    private void buildPar(File parFile, List<BwFile> parFiles,
                          List<ProcessParser.ProcessMetadata> processMetadata,
                          List<String> sarPaths,
                          List<SubstVarParser.GlobalVariable> globalVars,
                          List<String> declaredProcessPaths) throws Exception {
        // Generate PAR-level TIBCO.xml into a temp file
        File parTibcoXml = File.createTempFile("par-TIBCO", ".xml");
        parTibcoXml.deleteOnExit();
        // Adapter SDK Properties = bundled bwengine.xml properties + any opt-in extras.
        List<TibcoXmlGenerator.SdkProperty> engineProps =
            new ArrayList<>(readSdkProperties("bwengine"));
        engineProps.addAll(parseExtraEngineProperties(extraEngineProperties));
        new TibcoXmlGenerator().generateParDescriptor(
            parTibcoXml,
            parFile.getName(),
            processMetadata,
            sarPaths,
            jdbcCheckpointPaths(sarPaths),
            globalVars,
            engineProps,
            archiveVersion,
            null,
            declaredProcessPaths
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
    //  AAR assembly — AESDK adapter auto-discovery (all .adXXX instance files)
    // -----------------------------------------------------------------------

    /**
     * Assembles one BW5 Adapter Archive ({@code .aar}) for every AESDK adapter instance
     * file ({@code .adXXX} containing {@code <AESDK:instanceId>}) found among the SAR
     * candidate files that is not already covered by an explicit {@code <adapterArchive>}
     * entry in the archive descriptor.
     *
     * <p>Works without any TIBCO installation: the adapter version is detected from the
     * local install directory if present, or a fallback default is used. SDK properties
     * are read from bundled classpath resources under
     * {@code com/tibco/deployment/{componentSoftwareName}.xml}.</p>
     */
    /**
     * For adapter-only projects (no {@code .process} files), the BFS in
     * {@link #applyTransitiveDependencyAnalysis} seeds from processes and finds nothing,
     * leaving the SAR empty.  This method scans every adapter instance file
     * ({@code .adfiles}, {@code .adldap}, …) in {@code allSarFiles} for BW resource
     * references, follows AESchema import chains transitively, and adds the discovered
     * files to {@code combinedSarFiles}.
     *
     * <p>This replicates the behaviour of the multi-archive path which does the same
     * scan for each explicit {@code <adapterArchive>} entry in an {@code .archive}
     * descriptor.</p>
     */

    /**
     * Returns the normalized BW paths of all process files in {@code parFiles} that contain
     * a {@code <pd:starter>} element. Used to seed BFS when no archive descriptor is present
     * but adapter instance files exist — only independently-startable processes seed the PAR.
     */
    private List<String> collectStarterPaths(List<BwFile> parFiles) {
        List<String> result = new ArrayList<>();
        ProcessParser parser = new ProcessParser();
        for (BwFile pf : parFiles) {
            try {
                if (parser.parse(pf.file).hasStarter) {
                    result.add(normalizeBwPath(pf.relativePath));
                }
            } catch (Exception e) {
                getLog().warn("Cannot check starter in " + pf.file.getName() + ": " + e.getMessage());
                result.add(normalizeBwPath(pf.relativePath));
            }
        }
        return result;
    }

    private void collectAdapterAeschemas(List<BwFile> allSarFiles,
            List<BwFile> combinedSarFiles, Set<String> seenSarPaths) {
        Map<String, BwFile> resourceIndex = buildBwIndex(allSarFiles);
        Set<String> referencedResourcePaths = new LinkedHashSet<>();
        Set<String> wholeDirectoryPrefixes  = new LinkedHashSet<>();

        for (BwFile bwf : allSarFiles) {
            if (!isAdapterInstanceFile(bwf.file)) continue;
            boolean hasFragmentLoadUrls = adapterHasFragmentLoadUrls(bwf.file);
            for (String ref : extractBwResourceRefs(bwf.file)) {
                String norm = normalizeBwPath(ref);
                if (getExtension(norm).startsWith(".ad")) continue;
                if (norm.endsWith(".aeschema") && hasFragmentLoadUrls) {
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

        for (String dirPrefix : wholeDirectoryPrefixes) {
            getLog().info("Fragment loadUrls: expanding all aeschemas under " + dirPrefix);
            for (Map.Entry<String, BwFile> e : resourceIndex.entrySet()) {
                if (e.getKey().startsWith(dirPrefix) && e.getKey().endsWith(".aeschema")) {
                    referencedResourcePaths.add(e.getKey());
                }
            }
        }

        int before = combinedSarFiles.size();
        for (String path : referencedResourcePaths) {
            BwFile f = resourceIndex.get(path);
            if (f != null) {
                accumulateSarFiles(Collections.singletonList(f), combinedSarFiles, seenSarPaths);
            }
        }
        int added = combinedSarFiles.size() - before;
        if (added > 0) {
            getLog().info("Adapter-only: added " + added + " AESchema/SAR resource(s) to SAR.");
        }
    }

    private void buildAdapterAarsIfNeeded(
            List<BwFile> allSarFiles,
            List<BwFile> sarFiles,
            List<File> moduleFiles,
            File workDir,
            List<SubstVarParser.GlobalVariable> globalVars,
            ArchiveDescriptorParser.ArchiveDescriptor descriptor) throws Exception {

        Set<String> explicitPaths = new HashSet<>();
        // Map an adapter instance file (normalized, lower-cased BW path) to the <sdkVersion>
        // declared for it in the descriptor's <adapterArchive> — so the auto-discovered AAR uses
        // the descriptor's version (e.g. adas400 6.3.0.0, adb 7.3.2.0) instead of a
        // detected/default one.
        Map<String, String> explicitVersions = new HashMap<>();
        if (descriptor != null && descriptor.adapterArchives != null) {
            for (ArchiveDescriptorParser.AdapterArchiveEntry aa : descriptor.adapterArchives) {
                String path = aa.getAdapterFilePath();
                if (path != null) {
                    explicitPaths.add(path.toLowerCase(Locale.ROOT));
                    explicitVersions.put(normalizeBwPath(path).toLowerCase(Locale.ROOT),
                            aa.getSdkVersionFourPart());
                }
            }
        }

        // Use the SAR-scoped index for AESchema traversal and .folder lookup so that only
        // files actually included in the SAR appear in each AAR's EXTERNAL_RESOURCE_DEPENDENCY.
        Map<String, BwFile> resourceIndex = buildBwIndex(sarFiles);

        for (BwFile bwf : allSarFiles) {
            String fileName = bwf.file.getName();
            int dot = fileName.lastIndexOf('.');
            if (dot < 0) continue;
            String ext = fileName.substring(dot).toLowerCase(Locale.ROOT);
            if (!ext.startsWith(".ad")) continue;

            if (!isAdapterInstanceFile(bwf.file)) continue;
            if (!adapterSupportsAar(ext.substring(1))) continue;

            String bwPath = "/" + bwf.relativePath.replace(File.separatorChar, '/');
            if (explicitPaths.contains(bwPath.toLowerCase(Locale.ROOT))) continue;

            String instanceName = fileName.substring(0, dot);
            // Preserve original case of extension for componentSoftwareName (e.g. adr3TID)
            String componentSoftwareName = fileName.substring(dot + 1);
            String adapterFragName = readAdapterFragName(bwf.file);
            // Version precedence: pom override → descriptor <sdkVersion> for this adapter →
            // detected/default. The descriptor version (when the adapter is an explicit
            // <adapterArchive>) is authoritative over local-install detection.
            String descriptorVersion =
                explicitVersions.get(normalizeBwPath(bwPath).toLowerCase(Locale.ROOT));
            String baseVersion = (descriptorVersion != null && !descriptorVersion.isEmpty())
                ? descriptorVersion : detectAdapterVersion(componentSoftwareName);
            String adapterVersion = resolveAdapterVersion(componentSoftwareName, baseVersion);
            List<TibcoXmlGenerator.SdkProperty> sdkProps = readSdkProperties(componentSoftwareName);
            String adapterTypeFrag = bwPath + "#adapter." + adapterFragName;
            List<String> externalDeps = computeAdapterExternalDeps(
                    bwf.file, adapterTypeFrag, resourceIndex);

            String aarFileName = instanceName + ".aar";
            File aarFile = new File(workDir, aarFileName);
            buildAdapterAar(aarFile, bwf, bwPath, instanceName,
                            componentSoftwareName, adapterVersion, adapterFragName,
                            sdkProps, externalDeps);
            // An adapter instance that is ALSO an explicit <adapterArchive> was already
            // registered (and possibly written with a placeholder descriptor) by the archive
            // descriptor loop; buildAdapterAar has just overwritten the file with the correct
            // content. Register the module only once to avoid a duplicate entry in the EAR.
            if (!moduleFiles.contains(aarFile)) {
                moduleFiles.add(aarFile);
            }
            getLog().info("Adapter AAR assembled: " + aarFileName
                          + " (" + aarFile.length() + " bytes) [" + componentSoftwareName + "]");
        }
    }

    /**
     * Computes the EXTERNAL_RESOURCE_DEPENDENCY value for an adapter AAR by scanning
     * the adapter instance file for AESchema references and following them transitively.
     * Returns the AESchema paths plus the adapter fragment in {@link java.util.HashSet}
     * iteration order — matching buildear's {@code ArchiveResource.addExternalResourceBom},
     * which joins a {@code HashSet<String>} (so the order is hash-based, not sorted).
     * If the adapter has no AESchema references, returns a single-element list containing
     * just the fragment (as buildear does for unconfigured adapter instances).
     */
    private List<String> computeAdapterExternalDeps(
            File adapterFile, String adapterTypeFrag,
            Map<String, BwFile> resourceIndex) {
        Set<String> visited = new LinkedHashSet<>();
        try {
            String content = new String(
                    Files.readAllBytes(adapterFile.toPath()), StandardCharsets.UTF_8);
            java.util.regex.Matcher m = RELATIVE_AESCHEMA_REF.matcher(content);
            while (m.find()) {
                String norm = normalizeBwPath("/" + m.group());
                if (visited.add(norm)) {
                    followAeschemaImports(norm, resourceIndex, visited);
                }
            }
        } catch (IOException ignored) { }
        // Add .folder files for any directory that contains a referenced AESchema and
        // whose .folder file exists in the SAR.
        for (String path : new ArrayList<>(visited)) {
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash > 0) {
                String folderPath = path.substring(0, lastSlash) + "/.folder";
                if (resourceIndex.containsKey(folderPath)) {
                    visited.add(folderPath);
                }
            }
        }
        // Re-add the leading "/" stripped by normalizeBwPath, then order like buildear.
        List<String> withSlash = new ArrayList<>();
        for (String path : visited) withSlash.add("/" + path);
        return externalDepsInBuildearOrder(withSlash, adapterTypeFrag);
    }

    /**
     * Orders adapter {@code EXTERNAL_RESOURCE_DEPENDENCY} entries the way buildear does.
     * buildear's {@code ArchiveResource.addExternalResourceBom} joins a
     * {@code java.util.HashSet<String>}, so the entry order is the HashSet iteration order —
     * NOT sorted. {@code String.hashCode} and {@code HashMap} bucketing are JVM-stable, so
     * building the same kind of set (default capacity, one add per entry) yields
     * byte-identical ordering to buildear.
     */
    static List<String> externalDepsInBuildearOrder(Collection<String> depsWithSlash,
                                                     String adapterTypeFrag) {
        Set<String> deps = new HashSet<>();
        for (String d : depsWithSlash) deps.add(d);
        if (adapterTypeFrag != null && !adapterTypeFrag.isEmpty()) deps.add(adapterTypeFrag);
        return new ArrayList<>(deps);
    }

    /**
     * Returns {@code true} if the file is an AESDK adapter instance file, detected by
     * the presence of an {@code <AESDK:instanceId>} element in its XML content.
     * Non-instance files (e.g. {@code .adr3Connections} pool) that lack this element
     * return {@code false}.
     */
    static boolean isAdapterInstanceFile(File f) {
        try {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return content.contains("<AESDK:instanceId>");
        } catch (IOException e) {
            return false;
        }
    }

    // Known TIBCO AESDK adapter instance-file extensions. All AESDK adapters support AAR
    // generation. adswift and adsmartmapper(eps) are NOT AESDK adapters (no AESDK:instanceId)
    // and are intentionally absent.
    private static final Set<String> AAR_ADAPTER_EXTENSIONS = new HashSet<>(Arrays.asList(
            "adb",       // TIBCO Adapter for JDBC (adadb)
            "adas400",   // TIBCO Adapter for IBM i / AS400
            "adfiles",   // TIBCO Adapter for Files
            "adjdexe",   // TIBCO Adapter for JD Edwards EnterpriseOne
            "adldap",    // TIBCO Adapter for LDAP
            "adpsft8",   // TIBCO Adapter for PeopleSoft 8
            "adr3",      // TIBCO Adapter for SAP R/3
            "adr3tid",   // SAP R/3 TID extension
            "adsbl",     // TIBCO Adapter for Siebel
            "adtuxedo"   // TIBCO Adapter for Tuxedo
    ));

    static boolean adapterSupportsAar(String extNoDot) {
        return AAR_ADAPTER_EXTENSIONS.contains(extNoDot.toLowerCase(Locale.ROOT));
    }

    static String getExtNoDot(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /**
     * Reads the {@code name} attribute of the adapter element ({@code *:adapter})
     * inside an AESDK adapter instance file. Returns {@code "adapter"} as fallback.
     *
     * <p>Example: {@code <adldap:adapter name="ldap">} returns {@code "ldap"}</p>
     */
    static String readAdapterFragName(File f) {
        try {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<[A-Za-z_][A-Za-z0-9_]*:adapter[^>]+name=\"([^\"]+)\"")
                    .matcher(content);
            if (m.find()) return m.group(1);
        } catch (IOException ignored) { }
        return "adapter";
    }

    /**
     * Detects the installed version of a TIBCO adapter by scanning its standard
     * installation directory. Returns a sensible fallback version if not installed.
     */
    /**
     * Resolves the adapter SDK version for a given adapter type, applying the per-type
     * {@link #adapterVersions} pom override on top of the supplied {@code fallback} (which is the
     * descriptor's {@code <sdkVersion>} or the detected/default version). Returns {@code fallback}
     * when no override is configured for the type.
     */
    private String resolveAdapterVersion(String componentSoftwareName, String fallback) {
        if (adapterVersions != null && componentSoftwareName != null) {
            String override = adapterVersions.get(componentSoftwareName);
            if (override != null && !override.trim().isEmpty()) {
                return override.trim();
            }
        }
        return fallback;
    }

    static String detectAdapterVersion(String componentSoftwareName) {
        for (String base : new String[]{
                "/opt/tibco/adapter/" + componentSoftwareName,
                "C:\\tibco\\adapter\\" + componentSoftwareName}) {
            File root = new File(base);
            if (!root.isDirectory()) continue;
            String[] dirs = root.list();
            if (dirs == null || dirs.length == 0) continue;
            Arrays.sort(dirs);
            String latest = dirs[dirs.length - 1];
            // Only use the directory name when it gives a 3- or 4-part version.
            // A 2-part name (e.g. "6.1") does not encode the patch version, so fall
            // through to the hardcoded defaults which carry the full precise version.
            if (latest.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return latest;
            if (latest.matches("\\d+\\.\\d+\\.\\d+")) return latest + ".0";
        }
        switch (componentSoftwareName) {
            case "adr3":    return "7.3.2.0";
            case "adr3TID": return "7.3.2.0";
            case "adldap":  return "6.1.2.0";
            case "adfiles": return "7.1.1.0";
            case "adb":     // TIBCO Adapter for JDBC — instance files use the .adb extension
            case "adadb":   return "7.1.0.0";
            case "adas400": return "7.1.0.0";
            default:        return "7.0.0.0";
        }
    }

    /**
     * Parses {@code name=value} entries from {@link #extraEngineProperties} into
     * {@link TibcoXmlGenerator.SdkProperty} objects for the <em>Adapter SDK Properties</em> block.
     *
     * <p>For each entry a plain property is produced; when the name does not already start with
     * {@code java.property.}, a {@code java.property.<name>} twin is also produced — matching
     * buildear, which emits both so the property becomes a JVM {@code -D} system property at runtime
     * (e.g. {@code com.tibco.plugin.restjson.escape.unicodeInText}, read via
     * {@code Boolean.getBoolean}). Label and description are both set to the property name, which
     * reproduces buildear's {@code "<name> <name>"} description for bwengine.xml-style properties.</p>
     */
    static List<TibcoXmlGenerator.SdkProperty> parseExtraEngineProperties(List<String> entries) {
        List<TibcoXmlGenerator.SdkProperty> out = new ArrayList<>();
        if (entries == null) return out;
        for (String entry : entries) {
            if (entry == null) continue;
            String e = entry.trim();
            if (e.isEmpty()) continue;
            int eq = e.indexOf('=');
            String name = (eq >= 0 ? e.substring(0, eq) : e).trim();
            String value = eq >= 0 ? e.substring(eq + 1).trim() : "";
            if (name.isEmpty()) continue;
            out.add(new TibcoXmlGenerator.SdkProperty(name, value, name, name, false));
            if (!name.startsWith("java.property.")) {
                String twin = "java.property." + name;
                out.add(new TibcoXmlGenerator.SdkProperty(twin, value, twin, twin, false));
            }
        }
        return out;
    }

    /**
     * Maps an adapter component-software name to the base name of its bundled deployment
     * resource ({@code com/tibco/deployment/{base}.xml}). Most adapters use their name
     * verbatim; the JDBC adapter is an exception — its instance files carry the
     * {@code .adb} extension (component name {@code adb}) but the deployment resource is
     * named {@code adadb.xml}.
     */
    static String deploymentResourceBase(String componentSoftwareName) {
        if ("adb".equals(componentSoftwareName)) return "adadb";
        return componentSoftwareName;
    }

    /**
     * Loads SDK properties for an adapter from the bundled classpath resource
     * {@code com/tibco/deployment/{componentSoftwareName}.xml}.
     * Returns an empty list if the resource is not found.
     */
    static List<TibcoXmlGenerator.SdkProperty> readSdkProperties(String componentSoftwareName) {
        String resource = "com/tibco/deployment/"
                + deploymentResourceBase(componentSoftwareName) + ".xml";
        try (java.io.InputStream in =
                BwEarMojo.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) return Collections.emptyList();
            String xml = new String(org.apache.commons.io.IOUtils.toByteArray(in),
                                    StandardCharsets.UTF_8);
            // Strip XML comments so template blocks (e.g. bwengine.xml documents its
            // <property> schema inside a comment) are not parsed as real properties.
            xml = xml.replaceAll("(?s)<!--.*?-->", "");
            List<TibcoXmlGenerator.SdkProperty> props = new ArrayList<>();
            java.util.regex.Pattern propPat = java.util.regex.Pattern.compile(
                    "<property>(.*?)</property>", java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = propPat.matcher(xml);
            while (m.find()) {
                String block = m.group(1);
                String option = firstGroup(block, "<option>(.*?)</option>");
                String def    = firstGroup(block, "<default>(.*?)</default>");
                String label  = firstGroup(block, "<name>(.*?)</name>");
                String desc   = firstGroup(block, "<description>(.*?)</description>");
                String obf    = firstGroup(block, "<obfuscate>(.*?)</obfuscate>");
                if (option == null) continue;
                boolean isPassword = "yes".equalsIgnoreCase(obf) || "true".equalsIgnoreCase(obf);
                props.add(new TibcoXmlGenerator.SdkProperty(option,
                        def == null ? "" : def, label, desc, isPassword));
            }
            return props;
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private static String firstGroup(String text, String regex) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(regex, java.util.regex.Pattern.DOTALL).matcher(text);
        // Do NOT trim: buildear XML-parses the deployment file and preserves the element's
        // exact text, including significant trailing spaces in labels/descriptions (e.g.
        // "Enable using between clause "). The extracted value is still XML-escaped (regex
        // scan), so decode it — the writer re-escapes on output, and leaving it encoded
        // would double-escape (&amp;quot;).
        return m.find() ? xmlDecode(m.group(1)) : null;
    }

    /** Decodes the five predefined XML entities. {@code &amp;} is decoded last. */
    private static String xmlDecode(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    /** Creates a single AESDK adapter AAR containing the adapter file and its TIBCO.xml. */
    private void buildAdapterAar(
            File aarFile,
            BwFile adapterBwFile,
            String adapterBwPath,
            String instanceName,
            String componentSoftwareName,
            String adapterVersion,
            String adapterFragName,
            List<TibcoXmlGenerator.SdkProperty> sdkProperties,
            List<String> externalDeps) throws Exception {

        String aarFileName = aarFile.getName();
        File aarTibcoXml = File.createTempFile("aar-adapter-TIBCO-", ".xml");
        aarTibcoXml.deleteOnExit();
        new TibcoXmlGenerator().generateAdapterAarDescriptor(
                aarTibcoXml, aarFileName, instanceName, componentSoftwareName,
                adapterVersion, adapterBwPath, adapterFragName, sdkProperties, externalDeps,
                archiveVersion);

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(aarFile.toPath()))) {
            zos.setLevel(Deflater.DEFAULT_COMPRESSION);
            addToZip(zos, "TIBCO.xml", aarTibcoXml);
            addToZip(zos, adapterBwPath, adapterBwFile.file);
        }
    }

    // -----------------------------------------------------------------------
    //  AAR assembly — generic adapter (archive descriptor entry)
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

        // Determine the adapter component software name (e.g. adb, adr3): prefer the
        // descriptor's softwareTypeProperty, else infer from the adapter file extension.
        String componentSoftwareName = (aa.softwareType != null && !aa.softwareType.isEmpty())
            ? aa.softwareType : getExtNoDot(adapterFile);
        // Load the adapter's SDK properties so the AAR carries its Adapter SDK Properties
        // block (buildear emits e.g. the adb.*/adr3.* runtime tuning options).
        List<TibcoXmlGenerator.SdkProperty> sdkProps = readSdkProperties(componentSoftwareName);

        // Generate AAR-level TIBCO.xml
        File aarTibcoXml = File.createTempFile("aar-TIBCO-", ".xml");
        aarTibcoXml.deleteOnExit();
        new TibcoXmlGenerator().generateAarDescriptor(
            aarTibcoXml, aarFileName, aa.adapterReference, componentSoftwareName,
            resolveAdapterVersion(componentSoftwareName, aa.getSdkVersionFourPart()),
            sdkProps, globalVars, archiveVersion, null);

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


    /**
     * Ensures a {@code .cpy} is packaged as its {@code ae.shared.CCBSchemaResource} XML resource,
     * matching what TIBCO {@code buildear} produces.
     *
     * <ul>
     *   <li><b>Raw COBOL source</b> (plain copybook text): wrapped into the full
     *       {@code <BWSharedResource>} XML — {@code <version>3.6.0</version>} (current copybook
     *       palette {@code CBVersion}) plus the fixed metadata block and the copybook text
     *       (XML-escaped, CR→{@code &#xD;}, LF kept). The raw bytes are read with
     *       {@link #copybookEncoding} (default ISO-8859-1) so non-ASCII characters are preserved
     *       — unlike buildear, which reads as UTF-8 and corrupts them.</li>
     *   <li><b>Already-XML</b> copybook resource: the five metadata elements buildear adds
     *       ({@code copybookType}, {@code float}, {@code floatSet}, {@code legacyAlign},
     *       {@code metadataVersion}) are injected if absent; the stored {@code <version>} is
     *       preserved.</li>
     * </ul>
     * Returns the original file on any error (packaged verbatim).
     */
    File ensureCopybookResource(File cpyFile) {
        try {
            byte[] bytes = Files.readAllBytes(cpyFile.toPath());
            String head = new String(bytes, 0, Math.min(bytes.length, 64),
                    StandardCharsets.ISO_8859_1).trim();
            if (head.startsWith("<?xml") || head.startsWith("<BWSharedResource")) {
                return injectCopybookMetadata(cpyFile);
            }
            // Raw copybook → wrap into the CCBSchemaResource XML resource.
            String enc = (copybookEncoding == null || copybookEncoding.isEmpty())
                    ? "ISO-8859-1" : copybookEncoding;
            String text = new String(bytes, java.nio.charset.Charset.forName(enc));
            String xml = buildCopybookResourceXml(cpyFile.getName(), text);
            File tmp = File.createTempFile("bw5-cpy-", ".cpy");
            tmp.deleteOnExit();
            Files.write(tmp.toPath(), xml.getBytes(StandardCharsets.UTF_8));
            return tmp;
        } catch (IOException e) {
            getLog().warn("Could not wrap copybook " + cpyFile.getName()
                + " - " + e.getMessage() + "; packaging verbatim");
            return cpyFile;
        }
    }

    /** Builds the {@code ae.shared.CCBSchemaResource} XML for a raw copybook. */
    private static String buildCopybookResourceXml(String fileName, String copybookText) {
        String cb = escape(copybookText).replace("\r", "&#xD;");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<BWSharedResource>\n"
            + "    <name>" + escape(fileName) + "</name>\n"
            + "    <resourceType>ae.shared.CCBSchemaResource</resourceType>\n"
            + "    <config>\n"
            + "        <version>3.6.0</version>\n"
            + "        <fixedFormat>true</fixedFormat>\n"
            + "        <encoding>ASCII</encoding>\n"
            + "        <copybookType>COBOL</copybookType>\n"
            + "        <float>ieee</float>\n"
            + "        <floatSet>true</floatSet>\n"
            + "        <modified>false</modified>\n"
            + "        <dayMonth>Day/month</dayMonth>\n"
            + "        <dateFormat>YYYYXXXX</dateFormat>\n"
            + "        <legacyAlign>true</legacyAlign>\n"
            + "        <copybook>" + cb + "</copybook>\n"
            + "        <metadataVersion>1</metadataVersion>\n"
            + "        <redefineGroups/>\n"
            + "    </config>\n"
            + "</BWSharedResource>\n";
    }

    /** XML-escapes text content ({@code &}, {@code <}, {@code >}). */
    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Injects the five metadata elements buildear adds to an already-XML copybook resource's
     * {@code <config>} when absent. Returns the original file when nothing changed.
     */
    private File injectCopybookMetadata(File cpyFile) {
        try {
            org.jdom2.Document doc = new org.jdom2.input.SAXBuilder().build(cpyFile);
            org.jdom2.Element config = doc.getRootElement().getChild("config");
            if (config == null) return cpyFile;
            boolean changed = false;
            changed |= addChildIfAbsent(config, "copybookType", "COBOL");
            changed |= addChildIfAbsent(config, "float", "ieee");
            changed |= addChildIfAbsent(config, "floatSet", "true");
            changed |= addChildIfAbsent(config, "legacyAlign", "true");
            changed |= addChildIfAbsent(config, "metadataVersion", "1");
            if (!changed) return cpyFile;
            File tmp = File.createTempFile("bw5-cpy-", ".cpy");
            tmp.deleteOnExit();
            try (java.io.OutputStream os = Files.newOutputStream(tmp.toPath())) {
                new XMLOutputter(Format.getRawFormat().setEncoding("UTF-8")).output(doc, os);
            }
            return tmp;
        } catch (org.jdom2.JDOMException | IOException e) {
            getLog().warn("Could not inject copybook metadata into "
                + cpyFile.getName() + " - " + e.getMessage() + "; packaging verbatim");
            return cpyFile;
        }
    }

    /** Adds {@code <name>value</name>} to {@code parent} iff no direct child {@code name} exists. */
    private static boolean addChildIfAbsent(org.jdom2.Element parent, String name, String value) {
        if (parent.getChild(name) != null) return false;
        parent.addContent(new org.jdom2.Element(name).setText(value));
        return true;
    }

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
                String lname = bwf.file.getName().toLowerCase(Locale.ROOT);
                if (bwf.file.getName().endsWith(".javaxpath")) {
                    addJavaxpathToSar(zos, bwf);
                } else if (lname.endsWith(".cpy")) {
                    addToZip(zos, bwf.relativePath, ensureCopybookResource(bwf.file));
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
            paths.add(toBomResourcePath(bwf));
        }
        return paths;
    }

    /**
     * BW-repository URI of a SAR resource as it appears in a PAR/AAR EXTERNAL_DEPENDENCIES list.
     *
     * <p>buildear references an adapter instance resource in the BOM by its adapter-type URI —
     * {@code "<path>#adapter.<fragmentName>"} (matching {@code AEResource.getURI()} for the
     * adapter) — not the bare file path. The fragment name is the {@code name} attribute of the
     * {@code *:adapter} element inside the instance file (e.g. {@code SAPAdapter}, {@code ldap},
     * {@code ActiveDatabaseAdapterConfiguration}). Only the EXTERNAL_DEPENDENCIES string carries
     * the fragment; the packaged SAR entry itself keeps the bare path.</p>
     */
    private String toBomResourcePath(BwFile bwf) {
        String path = bwf.relativePath.startsWith("/") ? bwf.relativePath : "/" + bwf.relativePath;
        if (adapterSupportsAar(getExtNoDot(bwf.file)) && isAdapterInstanceFile(bwf.file)) {
            return path + "#adapter." + readAdapterFragName(bwf.file);
        }
        return path;
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

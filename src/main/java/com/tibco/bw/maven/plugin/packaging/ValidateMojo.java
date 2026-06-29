package com.tibco.bw.maven.plugin.packaging;

import com.tibco.bw.maven.plugin.descriptor.ArchiveDescriptorParser;
import com.tibco.bw.maven.plugin.descriptor.SubstVarParser;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;

import javax.xml.namespace.NamespaceContext;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Validates the structure and integrity of a TIBCO BusinessWorks 5.x project
 * without requiring TIBCO tools installed.
 *
 * <p>Checks performed:</p>
 * <ul>
 *   <li>XML well-formedness of all .process, .substvar, .archive and .aliaslib files</li>
 *   <li>.archive process list — all declared process paths exist on disk</li>
 *   <li>Process &lt;name&gt; element matches the file's relative path</li>
 *   <li>Duplicate process names across the project</li>
 *   <li>Global variable completeness — %%VAR%% references vs .substvar definitions</li>
 *   <li>Maven dependency resolution — all projlibs and JARs are present</li>
 *   <li>XPath syntax in xsl:* attributes and pd:condition elements, validated
 *       against the bundled BW5 function catalog (bw5-xpath-functions.properties)</li>
 * </ul>
 */
@Mojo(
    name = "validate",
    defaultPhase = LifecyclePhase.VERIFY,
    requiresDependencyResolution = ResolutionScope.NONE,
    threadSafe = false
)
public class ValidateMojo extends AbstractBw5Mojo {

    private static final Namespace PD  = Namespace.getNamespace("pd",  "http://xmlns.tibco.com/bw/process/2003");
    private static final Namespace XSL = Namespace.getNamespace("xsl", "http://www.w3.org/1999/XSL/Transform");
    private static final Pattern   GV_REF = Pattern.compile("%%([^%]+)%%");

    /**
     * Fail the build when validation errors are found.
     * When {@code false} (default), errors are reported as warnings and the build continues.
     * Set to {@code true} in CI to enforce a hard gate.
     */
    @Parameter(defaultValue = "false", property = "bw5.validate.failOnError")
    private boolean failOnError;

    /** Fail the build when warnings are present, not just errors. */
    @Parameter(defaultValue = "false", property = "bw5.validate.failOnWarning")
    private boolean failOnWarning;

    /** Report global variables that are defined in .substvar files but never referenced. */
    @Parameter(defaultValue = "false", property = "bw5.validate.showUnusedGVars")
    private boolean showUnusedGVars;

    /** Skip XPath expression syntax checking (faster, useful for large projects). */
    @Parameter(defaultValue = "false", property = "bw5.validate.skipXPath")
    private boolean skipXPath;

    /** Skip copying projlib/JAR dependencies to target/bw-lib before validation. */
    @Parameter(defaultValue = "false", property = "bw5.validate.skipResolveDependencies")
    private boolean skipResolveDependencies;

    // ── Issue model ──────────────────────────────────────────────────────────

    enum Severity { ERROR, WARNING }

    static class Issue {
        final Severity severity;
        final String   code;
        final String   message;

        Issue(Severity s, String c, String m) { severity = s; code = c; message = m; }
    }

    // ── Entry point ──────────────────────────────────────────────────────────

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) { getLog().info("bw5:validate skipped."); return; }

        if (!skipResolveDependencies) resolveDependencies();

        List<Issue> issues = new ArrayList<>();

        List<File> processFiles  = findFiles(bwProjectPath, ".process");
        List<File> substVarFiles = findFiles(bwProjectPath, ".substvar");
        List<File> aliasLibFiles = findFiles(bwProjectPath, ".aliaslib");
        List<File> archiveFiles  = findFiles(bwProjectPath, ".archive");

        getLog().info("Validating BW5 project: " + bwProjectPath.getAbsolutePath());
        getLog().info("Found " + processFiles.size() + " process(es), "
            + substVarFiles.size() + " substvar(s), " + archiveFiles.size() + " archive(s).");

        validateXmlWellFormedness(processFiles, substVarFiles, aliasLibFiles, archiveFiles, issues);
        validateArchive(archiveFiles, issues);
        validateProcessNames(processFiles, issues);
        validateGlobalVariables(processFiles, substVarFiles, issues);
        validateDependencies(issues);
        if (!skipXPath) validateXPaths(processFiles, issues);

        report(issues);

        long errors   = issues.stream().filter(i -> i.severity == Severity.ERROR).count();
        long warnings = issues.stream().filter(i -> i.severity == Severity.WARNING).count();

        if (errors == 0 && warnings == 0) {
            getLog().info("bw5:validate passed — no issues found.");
        } else if ((failOnError && errors > 0) || (failOnWarning && warnings > 0)) {
            throw new MojoFailureException(
                "bw5:validate: " + errors + " error(s), " + warnings + " warning(s).");
        }
    }

    // ── 1. XML well-formedness ───────────────────────────────────────────────

    private void validateXmlWellFormedness(List<File> processes, List<File> substvars,
                                            List<File> aliaslibs, List<File> archives,
                                            List<Issue> issues) {
        SAXBuilder builder = new SAXBuilder();
        Stream.of(processes, substvars, aliaslibs, archives)
              .flatMap(Collection::stream)
              .forEach(f -> {
                  try { builder.build(f); }
                  catch (Exception e) {
                      issues.add(new Issue(Severity.ERROR, "XML",
                          rel(f) + ": " + rootMessage(e)));
                  }
              });
    }

    // ── 2. Archive descriptor ────────────────────────────────────────────────

    private void validateArchive(List<File> archiveFiles, List<Issue> issues) {
        if (archiveFiles.isEmpty()) {
            issues.add(new Issue(Severity.ERROR, "ARCHIVE",
                "No .archive descriptor found under " + bwProjectPath));
            return;
        }
        ArchiveDescriptorParser parser = new ArchiveDescriptorParser();
        for (File f : archiveFiles) {
            try {
                ArchiveDescriptorParser.ArchiveDescriptor desc = parser.parse(f);
                // Validate every processArchive entry (multi-PAR aware)
                for (ArchiveDescriptorParser.ProcessArchiveEntry pa : desc.processArchives) {
                    for (String path : pa.processPaths) {
                        String relative = path.startsWith("/") ? path.substring(1) : path;
                        if (!new File(bwProjectPath, relative).isFile()) {
                            issues.add(new Issue(Severity.ERROR, "ARCHIVE",
                                rel(f) + " [" + pa.name + "]: declared process not found on disk: " + path));
                        }
                    }
                }
            } catch (Exception ignored) {
                // already caught by XML well-formedness check
            }
        }
    }

    // ── 3. Process names and duplicates ─────────────────────────────────────

    private void validateProcessNames(List<File> processFiles, List<Issue> issues) {
        SAXBuilder builder = new SAXBuilder();
        Map<String, File> seen = new LinkedHashMap<>();

        for (File f : processFiles) {
            String declaredName;
            try {
                Document doc = builder.build(f);
                Element nameEl = doc.getRootElement().getChild("name", PD);
                declaredName = nameEl != null ? nameEl.getTextTrim() : null;
            } catch (JDOMException | IOException e) {
                continue; // already flagged
            }
            if (declaredName == null || declaredName.isEmpty()) continue;

            String relPath  = rel(f).replace(File.separatorChar, '/');
            String normName = declaredName.replace('\\', '/');

            if (!relPath.endsWith(normName)) {
                issues.add(new Issue(Severity.ERROR, "PROCESS_NAME",
                    rel(f) + ": <pd:name> '" + declaredName + "' does not match file path"));
            }

            if (seen.containsKey(normName)) {
                issues.add(new Issue(Severity.ERROR, "DUPLICATE",
                    "Duplicate process name '" + normName + "': "
                    + rel(f) + " and " + rel(seen.get(normName))));
            } else {
                seen.put(normName, f);
            }
        }
    }

    // ── 4. Global variables ──────────────────────────────────────────────────

    void validateGlobalVariables(List<File> processFiles, List<File> substVarFiles,
                                 List<Issue> issues) {
        SubstVarParser parser = new SubstVarParser();
        Set<String> defined = new LinkedHashSet<>();
        for (File f : substVarFiles) {
            try { parser.parse(f).forEach(gv -> defined.add(gv.name)); }
            catch (Exception e) {
                getLog().warn("Skipping substvar file due to parse error: " + rel(f) + ": " + e.getMessage(), e);
            }
        }

        Set<String> referenced = new LinkedHashSet<>();
        for (File f : processFiles) {
            try {
                Matcher m = GV_REF.matcher(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                while (m.find()) referenced.add(m.group(1));
            } catch (IOException ignored) { }
        }

        for (String ref : referenced) {
            if (!defined.contains(ref)) {
                issues.add(new Issue(Severity.WARNING, "GVAR",
                    "Global variable referenced but not defined: %%" + ref + "%%"));
            }
        }

        if (showUnusedGVars) {
            for (String def : defined) {
                if (!referenced.contains(def)) {
                    issues.add(new Issue(Severity.WARNING, "GVAR_UNUSED",
                        "Global variable defined but never referenced: %%" + def + "%%"));
                }
            }
        }
    }

    // ── 5. Maven dependency resolution ──────────────────────────────────────

    /**
     * Checks that all declared projlib and JAR dependencies are present in the local
     * Maven repository.
     *
     * <p>Uses {@code project.getDependencies()} (POM-declared) rather than
     * {@code project.getArtifacts()} (framework-resolved), because this goal runs with
     * {@code requiresDependencyResolution = NONE}: if Maven tried to resolve artifacts
     * before the goal ran and a dep was absent, it would exit with
     * {@code DependencyResolutionException} before the goal could report a DEP issue.
     * Checking the local-repo path directly gives the same result without Maven aborting.</p>
     */
    void validateDependencies(List<Issue> issues) {
        String localRepoBase = session.getLocalRepository().getBasedir();
        for (Dependency dep : project.getDependencies()) {
            String scope = dep.getScope();
            if ("test".equals(scope) || "system".equals(scope) || "provided".equals(scope)) {
                continue;
            }
            String type = (dep.getType() != null && !dep.getType().isEmpty()) ? dep.getType() : "jar";
            if (!"projlib".equals(type) && !"jar".equals(type)) continue;

            File localFile = localRepoArtifactPath(localRepoBase,
                dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), type);
            if (!localFile.isFile()) {
                issues.add(new Issue(Severity.ERROR, "DEP",
                    dep.getGroupId() + ":" + dep.getArtifactId() + ":" + dep.getVersion()
                    + " [" + type + "] not found in local repository"));
            }
        }
    }

    /** Computes the canonical local-repository path for a Maven artifact. Package-private for testing. */
    static File localRepoArtifactPath(String repoBase, String groupId,
                                       String artifactId, String version, String type) {
        String groupPath = groupId.replace('.', File.separatorChar);
        String filename   = artifactId + "-" + version + "." + type;
        return new File(repoBase,
            groupPath + File.separator + artifactId + File.separator + version
            + File.separator + filename);
    }

    // ── 6. XPath syntax ─────────────────────────────────────────────────────

    private void validateXPaths(List<File> processFiles, List<Issue> issues) {
        Map<String, int[]> catalog = loadXPathCatalog();
        XPath engine = buildXPathEngine(catalog);
        SAXBuilder builder = new SAXBuilder();

        for (File f : processFiles) {
            Document doc;
            try { doc = builder.build(f); }
            catch (JDOMException | IOException e) { continue; }
            scanForXPathExpressions(doc.getRootElement(), rel(f), engine, catalog, issues);
        }
    }

    private void scanForXPathExpressions(Element el, String file, XPath engine,
                                          Map<String, int[]> catalog, List<Issue> issues) {
        if (XSL.getURI().equals(el.getNamespaceURI())) {
            String expr = null;
            switch (el.getName()) {
                case "value-of":
                case "for-each":
                case "copy-of":
                case "sort":
                    expr = el.getAttributeValue("select"); break;
                case "if":
                case "when":
                    expr = el.getAttributeValue("test"); break;
                default: break;
            }
            if (expr != null && !expr.isBlank()) {
                XPathChecker.check(expr, activityContext(el), file, engine, catalog, issues);
            }
        }

        if (PD.getURI().equals(el.getNamespaceURI()) && "condition".equals(el.getName())) {
            String text = el.getTextTrim();
            if (!text.isBlank()) {
                XPathChecker.check(text, "transition", file, engine, catalog, issues);
            }
        }

        for (Element child : el.getChildren()) {
            scanForXPathExpressions(child, file, engine, catalog, issues);
        }
    }

    /**
     * Package-private for unit testing: XPath expression validation logic,
     * extracted so it can be tested without a full Mojo lifecycle.
     */
    static final class XPathChecker {

        private XPathChecker() {}

        /**
         * Matches namespace-qualified function calls of the form {@code prefix:localName(},
         * e.g. {@code tib:format-money(} or {@code fn:concat(}.
         *
         * <p>Node-axis steps ({@code ancestor::}, {@code child::}) and element names
         * ({@code ns0:element}) do not end with {@code (} so they are never matched.</p>
         */
        static final Pattern NS_FUNC_CALL = Pattern.compile(
            "[a-zA-Z_][a-zA-Z0-9_]*:[a-zA-Z_][a-zA-Z0-9_-]*\\s*\\("
        );

        /**
         * Validates one XPath expression and appends any issues to {@code issues}.
         *
         * <p>Two-phase check:</p>
         * <ol>
         *   <li>Syntax: {@code engine.compile()} — catches malformed XPath.</li>
         *   <li>Unknown function: scans the expression text for {@code prefix:name(} patterns
         *       and checks each local name against the BW5 function catalog. This is necessary
         *       because {@code XPath.compile()} is lazy and does <em>not</em> invoke the
         *       {@code XPathFunctionResolver} — resolution only happens at evaluation time.</li>
         * </ol>
         */
        static void check(String expr, String ctx, String file,
                           XPath engine, Map<String, int[]> catalog, List<Issue> issues) {
            try {
                engine.compile(expr);
            } catch (XPathExpressionException e) {
                String detail = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                issues.add(new Issue(Severity.WARNING, "XPATH",
                    file + " [" + ctx + "]: XPath syntax error: " + detail
                    + " — expression: " + truncate(expr, 80)));
                return; // malformed — skip function scan
            }

            Matcher m = NS_FUNC_CALL.matcher(expr);
            while (m.find()) {
                String call     = m.group();                              // e.g. "tib:format-money("
                int    colon    = call.indexOf(':');
                String localName = call.substring(colon + 1, call.lastIndexOf('(')).trim();
                if (!catalog.containsKey(localName)) {
                    issues.add(new Issue(Severity.WARNING, "XPATH",
                        file + " [" + ctx + "]: Unknown BW5 function '"
                        + call.substring(0, call.lastIndexOf('(')).trim()
                        + "' — expression: " + truncate(expr, 80)));
                }
            }
        }

        private static String truncate(String s, int max) {
            return s.length() <= max ? s : s.substring(0, max) + "...";
        }
    }

    /** Walks up the element tree to find the nearest named pd:activity or pd:group. */
    private String activityContext(Element el) {
        Element parent = el.getParentElement();
        while (parent != null) {
            String name = parent.getAttributeValue("name");
            if (name != null && !name.isBlank()) return name;
            parent = parent.getParentElement();
        }
        return "unknown";
    }

    // ── Catalog loading + XPath engine ──────────────────────────────────────

    private Map<String, int[]> loadXPathCatalog() {
        Map<String, int[]> catalog = new HashMap<>();
        Properties props = new Properties();
        try (InputStream is = ValidateMojo.class.getResourceAsStream("/bw5-xpath-functions.properties")) {
            if (is != null) {
                props.load(is);
                for (String key : props.stringPropertyNames()) {
                    String[] parts = props.getProperty(key).split(",");
                    if (parts.length == 2) {
                        catalog.put(key.trim(), new int[]{
                            Integer.parseInt(parts[0].trim()),
                            Integer.parseInt(parts[1].trim())
                        });
                    }
                }
                getLog().debug("Loaded " + catalog.size() + " BW5 XPath functions from catalog.");
            } else {
                getLog().warn("bw5-xpath-functions.properties not found on classpath — XPath function validation disabled.");
            }
        } catch (IOException | NumberFormatException e) {
            getLog().warn("Could not load BW5 XPath function catalog: " + e.getMessage());
        }
        return catalog;
    }

    @SuppressWarnings("rawtypes")
    XPath buildXPathEngine(Map<String, int[]> catalog) {
        XPath engine = XPathFactory.newInstance().newXPath();

        // Accept any variable: $activityOutput, $_globalVariables, etc.
        engine.setXPathVariableResolver(var -> "");

        // Accept only catalogued BW5 functions at the declared arity; reject everything else.
        engine.setXPathFunctionResolver((name, arity) -> {
            int[] range = catalog.get(name.getLocalPart());
            if (range == null) return null;                          // unknown function → error
            if (arity < range[0] || arity > range[1]) return null;  // wrong arity → error
            return args -> null;                                     // known + valid → no-op
        });

        // Accept any namespace prefix (ns1:, ns2:, etc. declared per-process)
        engine.setNamespaceContext(new AnyPrefixNamespaceContext());

        return engine;
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private List<File> findFiles(File root, String ext) {
        if (!root.isDirectory()) return Collections.emptyList();
        try (Stream<Path> walk = Files.walk(root.toPath())) {
            return walk
                .filter(p -> p.toString().endsWith(ext))
                .filter(p -> !p.toString().contains(File.separator + "target" + File.separator))
                .map(Path::toFile)
                .sorted()
                .collect(Collectors.toList());
        } catch (Exception e) {
            getLog().warn("Error scanning " + ext + " files: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private String rel(File f) {
        return bwProjectPath.toURI().relativize(f.toURI()).getPath();
    }

    private String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : e.getMessage();
    }

    private void report(List<Issue> issues) {
        if (issues.isEmpty()) return;
        long errors   = issues.stream().filter(i -> i.severity == Severity.ERROR).count();
        long warnings = issues.stream().filter(i -> i.severity == Severity.WARNING).count();
        getLog().info("bw5:validate — " + errors + " error(s), " + warnings + " warning(s):");
        for (Issue issue : issues) {
            String line = "[" + issue.code + "] " + issue.message;
            if (issue.severity == Severity.ERROR) getLog().error(line);
            else                                  getLog().warn(line);
        }
    }

    /** Accepts any namespace prefix by mapping it to a synthetic URN. */
    @SuppressWarnings("rawtypes")
    private static final class AnyPrefixNamespaceContext implements NamespaceContext {
        @Override public String getNamespaceURI(String prefix) { return "urn:" + prefix; }
        @Override public String getPrefix(String uri)          { return null; }
        @Override public Iterator getPrefixes(String uri)      { return Collections.emptyIterator(); }
    }
}

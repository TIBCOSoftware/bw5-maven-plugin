package com.tibco.bw.maven.plugin.doc;

import com.tibco.bw.maven.plugin.descriptor.SubstVarParser;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generates the HTML documentation site for a BW5 project.
 *
 * <p>Produces:</p>
 * <ul>
 *   <li>{@code target/site/bw5/index.html} — project overview with technology map</li>
 *   <li>{@code target/site/bw5/processes/<path>.html} — one page per process</li>
 *   <li>{@code target/site/bw5/bw5-site.css} — stylesheet</li>
 *   <li>{@code target/site/bw5/bw5-site.js} — scripts + embedded process index</li>
 * </ul>
 */
public class SiteHtmlGenerator {

    private final File outputDir;
    private final MavenProject project;
    private final SvgDiagramGenerator svgGen = new SvgDiagramGenerator();

    private static final Pattern GV_PATTERN = Pattern.compile("%%([^%]+)%%");

    // Cross-reference data, populated in generateIndex()
    private List<ProcessDocModel> allProcesses = Collections.emptyList();
    private List<SharedResourceModel> sharedResources;
    private List<SubstVarParser.GlobalVariable> globalVars;
    /** processFileName → model (key = safeFileName(fullName), no extension) */
    private final Map<String, ProcessDocModel> fileToModel = new LinkedHashMap<>();
    /** model.name → HTML file path relative to outputDir root (e.g. "processes/Common_ARC_BA1N_Main.html") */
    private final Map<String, String> nameToHtml = new LinkedHashMap<>();
    /** Shared resource name → HTML file path relative to outputDir root */
    private final Map<String, String> srNameToHtml = new LinkedHashMap<>();
    /** displayName / fullName segment → list of callers (models) */
    private final Map<String, List<ProcessDocModel>> calledByMap = new LinkedHashMap<>();
    /** plugin code → count of processes using it */
    private final Map<String, Integer> pluginProcessCount = new LinkedHashMap<>();
    /** plugin code → set of process display names using it */
    private final Map<String, Set<String>> pluginProcesses = new LinkedHashMap<>();

    // ── Constructor ──────────────────────────────────────────────────────────

    public SiteHtmlGenerator(File outputDir, MavenProject project,
                              List<SharedResourceModel> sharedResources,
                              List<SubstVarParser.GlobalVariable> globalVars) {
        this.outputDir = outputDir;
        this.project = project;
        this.sharedResources = sharedResources != null ? new ArrayList<>(sharedResources) : Collections.emptyList();
        this.globalVars = globalVars != null ? new ArrayList<>(globalVars) : Collections.emptyList();
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void generateIndex(List<ProcessDocModel> processes) throws IOException {
        this.allProcesses = new ArrayList<>(processes);
        mkdirs(outputDir);
        mkdirs(new File(outputDir, "processes"));
        mkdirs(new File(outputDir, "sharedresources"));

        // Build file name map
        for (ProcessDocModel p : processes) {
            String key = processFileName(p);
            fileToModel.put(key, p);
            nameToHtml.put(p.name != null ? p.name : p.displayName, "processes/" + key + ".html");
        }

        // Build SR file name map
        for (SharedResourceModel sr : sharedResources) {
            String key = srFileName(sr);
            srNameToHtml.put(sr.name, "sharedresources/" + key + ".html");
        }

        // Build "called by" reverse index
        for (ProcessDocModel p : processes) {
            for (ProcessDocModel.Activity a : p.allActivities()) {
                if (a.calledProcessPath == null) continue;
                ProcessDocModel target = resolveCalledProcess(a.calledProcessPath);
                if (target != null) {
                    calledByMap
                        .computeIfAbsent(target.name != null ? target.name : target.displayName,
                                         k -> new ArrayList<>())
                        .add(p);
                }
            }
        }

        // Build SR "used by" cross-reference
        for (SharedResourceModel sr : sharedResources) {
            sr.usedBy.clear();
        }
        for (ProcessDocModel p : processes) {
            for (ProcessDocModel.Activity a : p.allActivities()) {
                for (String ref : a.sharedResourceRefs) {
                    for (SharedResourceModel sr : sharedResources) {
                        String srNorm = sr.name.startsWith("/") ? sr.name.substring(1) : sr.name;
                        String refNorm = ref.startsWith("/") ? ref.substring(1) : ref;
                        if (srNorm.equals(refNorm) || sr.displayName.equals(lastSegment(ref))) {
                            String entry = fullDisplayName(p) + " / " + a.name;
                            if (!sr.usedBy.contains(entry)) sr.usedBy.add(entry);
                        }
                    }
                }
            }
        }

        // Build plugin map: scan all activity types, look up in PluginRegistry
        for (ProcessDocModel p : processes) {
            Set<String> pluginsInProcess = new LinkedHashSet<>();
            for (ProcessDocModel.Activity a : p.allActivities()) {
                String plugin = PluginRegistry.getPlugin(a.type);
                if (plugin != null) pluginsInProcess.add(plugin);
            }
            for (String plugin : pluginsInProcess) {
                pluginProcessCount.merge(plugin, 1, Integer::sum);
                pluginProcesses.computeIfAbsent(plugin, k -> new LinkedHashSet<>()).add(p.displayName);
            }
        }

        writeCss();
        writeJs(processes);

        File indexFile = new File(outputDir, "index.html");
        try (Writer w = writer(indexFile)) {
            writeIndexPage(w, processes);
        }
    }

    public void generateProcessPage(ProcessDocModel model) throws IOException {
        mkdirs(new File(outputDir, "processes"));
        String fileName = processFileName(model) + ".html";
        File pageFile = new File(new File(outputDir, "processes"), fileName);
        try (Writer w = writer(pageFile)) {
            writeProcessPage(w, model);
        }
    }

    public void generateSharedResourcePage(SharedResourceModel sr) throws IOException {
        mkdirs(new File(outputDir, "sharedresources"));
        String fileName = srFileName(sr) + ".html";
        File pageFile = new File(new File(outputDir, "sharedresources"), fileName);
        try (Writer w = writer(pageFile)) {
            writeSharedResourcePage(w, sr);
        }
    }

    // ── Index page ───────────────────────────────────────────────────────────

    private void writeIndexPage(Writer w, List<ProcessDocModel> processes) throws IOException {
        writeHtmlHead(w, project.getArtifactId() + " — BW5 Documentation", "bw5-site.css", "bw5-site.js", "", "index.html");

        writeTopbar(w, "index.html");

        w.write("<div class=\"app-body\">\n");
        writeSidebarHtml(w, processes, "index.html");
        w.write("<main class=\"main-content\">\n<div class=\"content-inner\">\n");

        // ── Hero header ──────────────────────────────────────────────────────
        w.write("<div class=\"page-hero\">\n");
        w.write("  <div class=\"hero-icon\">BW</div>\n");
        w.write("  <div class=\"hero-text\">\n");
        w.write("    <h1>" + esc(project.getArtifactId()) + "</h1>\n");
        w.write("    <p class=\"hero-sub\">" + esc(project.getGroupId()) + " &nbsp;·&nbsp; "
            + esc(project.getVersion()) + "</p>\n");
        if (project.getDescription() != null && !project.getDescription().isEmpty()) {
            w.write("    <p class=\"hero-desc\">" + esc(project.getDescription()) + "</p>\n");
        }
        w.write("  </div>\n</div>\n");

        // ── Stats row ────────────────────────────────────────────────────────
        long starterCount = processes.stream().filter(p -> p.starter != null).count();
        long totalActivities = processes.stream().mapToLong(p -> p.activities.size()).sum();
        long totalTransitions = processes.stream().mapToLong(p -> p.transitions.size()).sum();
        long depCount = countDependencies();
        w.write("<div class=\"stats-row\">\n");
        writeStat(w, String.valueOf(processes.size()), "Processes", "📄");
        writeStat(w, String.valueOf(starterCount), "Event Sources", "▶");
        writeStat(w, String.valueOf(totalActivities), "Activities", "⚙");
        writeStat(w, String.valueOf(totalTransitions), "Transitions", "→");
        if (!sharedResources.isEmpty()) {
            writeStat(w, String.valueOf(sharedResources.size()), "Shared Resources", "🔗");
        }
        if (!globalVars.isEmpty()) {
            writeStat(w, String.valueOf(globalVars.size()), "Global Variables", "🔧");
        }
        writeStat(w, String.valueOf(pluginProcessCount.size()), "Plugins", "🔌");
        if (depCount > 0) {
            writeStat(w, String.valueOf(depCount), "Dependencies", "📦");
        }
        w.write("</div>\n");

        // ── Required plugins ─────────────────────────────────────────────────
        if (!pluginProcessCount.isEmpty()) {
            w.write("<section class=\"card\">\n");
            w.write("  <h2>Required Plugins</h2>\n");
            w.write("  <p class=\"section-desc\">Third-party TIBCO add-on plugins detected across all processes.</p>\n");
            w.write("  <div class=\"tech-grid\">\n");

            // Sort by process count desc
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(pluginProcessCount.entrySet());
            sorted.sort((a, b) -> b.getValue() - a.getValue());
            for (Map.Entry<String, Integer> e : sorted) {
                String pluginCode = e.getKey();
                String[] info = PluginRegistry.getPluginInfo(pluginCode);
                String displayName = info[0];
                String emoji      = info[1];
                String desc       = info[2];
                int procCount = pluginProcesses.getOrDefault(pluginCode, Collections.emptySet()).size();
                w.write("    <div class=\"tech-card\">\n");
                w.write("      <div class=\"tech-card-header\">\n");
                w.write("        <span class=\"tech-emoji\">" + emoji + "</span>\n");
                w.write("        <span class=\"tech-name\">" + esc(displayName) + "</span>\n");
                w.write("      </div>\n");
                w.write("      <p class=\"tech-desc\">" + esc(desc) + "</p>\n");
                w.write("      <div class=\"tech-badges\">\n");
                w.write("        <span class=\"tech-badge\">" + procCount + " process" + (procCount != 1 ? "es" : "") + "</span>\n");
                w.write("        <span class=\"tech-badge-code\">" + esc(pluginCode) + "</span>\n");
                w.write("      </div>\n");
                w.write("    </div>\n");
            }
            w.write("  </div>\n</section>\n");
        }

        // ── Shared Resources ──────────────────────────────────────────────────
        if (!sharedResources.isEmpty()) {
            w.write("<section class=\"card\">\n");
            w.write("  <h2>Shared Resources <span class=\"count-badge\">" + sharedResources.size() + "</span></h2>\n");
            w.write("  <p class=\"section-desc\">Connection pools and shared configuration objects used across processes.</p>\n");
            w.write("  <table class=\"data-table\">\n");
            w.write("    <thead><tr><th>Name</th><th>Type</th><th class=\"num\">Used by</th></tr></thead>\n");
            w.write("    <tbody>\n");
            for (SharedResourceModel sr : sharedResources) {
                String href = "sharedresources/" + srFileName(sr) + ".html";
                w.write("    <tr>\n");
                w.write("      <td><a href=\"" + href + "\" class=\"call-link\">" + esc(sr.displayName) + "</a></td>\n");
                w.write("      <td><span class=\"badge badge-type\">" + esc(sr.shortType()) + "</span></td>\n");
                w.write("      <td class=\"num\">" + sr.usedBy.size() + "</td>\n");
                w.write("    </tr>\n");
            }
            w.write("    </tbody>\n  </table>\n</section>\n");
        }

        // ── Global Variables tree ─────────────────────────────────────────────
        if (!globalVars.isEmpty()) {
            w.write("<section class=\"card\">\n");
            w.write("  <div class=\"section-header\">\n");
            w.write("    <h2>Global Variables <span class=\"count-badge\">" + globalVars.size() + "</span></h2>\n");
            w.write("    <input class=\"tree-search\" id=\"gvSearch\" placeholder=\"Filter…\" "
                + "oninput=\"filterTree(this,'gvTree')\" autocomplete=\"off\">\n");
            w.write("  </div>\n");
            w.write("  <p class=\"section-desc\">Substitution variables defined in <code>.substvar</code> files — organised by path.</p>\n");
            w.write("  <div class=\"tree-view\" id=\"gvTree\">\n");
            writeGvTree(w, globalVars);
            w.write("  </div>\n</section>\n");
        }

        // ── Dependencies ─────────────────────────────────────────────────────
        writeDependenciesSection(w);

        // ── Process tree ──────────────────────────────────────────────────────
        w.write("<section class=\"card\">\n");
        w.write("  <div class=\"section-header\">\n");
        w.write("    <h2>Processes <span class=\"count-badge\">" + processes.size() + "</span></h2>\n");
        w.write("    <input class=\"tree-search\" id=\"procSearch\" placeholder=\"Filter…\" "
            + "oninput=\"filterTree(this,'procTree')\" autocomplete=\"off\">\n");
        w.write("  </div>\n");
        w.write("  <div class=\"tree-view\" id=\"procTree\">\n");
        writeProcessTree(w, processes);
        w.write("  </div>\n</section>\n");

        w.write("</div>\n</main>\n</div>\n");
        writeHtmlFoot(w);
    }

    // ── Process page ─────────────────────────────────────────────────────────

    private void writeProcessPage(Writer w, ProcessDocModel model) throws IOException {
        String title = fullDisplayName(model) + " — " + project.getArtifactId();
        writeHtmlHead(w, title, "../bw5-site.css", "../bw5-site.js", "../", "processes/" + processFileName(model) + ".html");

        writeTopbar(w, "../index.html");
        w.write("<div class=\"app-body\">\n");
        writeSidebarHtml(w, allProcesses, "processes/" + processFileName(model) + ".html");
        w.write("<main class=\"main-content\">\n<div class=\"content-inner\">\n");

        // Breadcrumb
        w.write("<nav class=\"breadcrumb\">\n");
        w.write("  <a href=\"../index.html\">" + esc(project.getArtifactId()) + "</a>\n");
        if (!model.folderPath.isEmpty()) {
            for (String seg : model.folderPath.split("/")) {
                w.write("  <span class=\"bc-sep\">›</span> <span class=\"bc-seg\">" + esc(seg) + "</span>\n");
            }
        }
        w.write("  <span class=\"bc-sep\">›</span> <strong>" + esc(model.displayName) + "</strong>\n");
        w.write("</nav>\n");

        // Process hero
        w.write("<div class=\"page-hero page-hero-sm\">\n");
        if (model.starter != null) {
            w.write("  <span class=\"badge badge-starter hero-badge\">" + esc(model.starter.shortType()) + "</span>\n");
        }
        w.write("  <div class=\"hero-text\">\n");
        w.write("    <h1>" + esc(model.displayName) + "</h1>\n");
        w.write("    <p class=\"hero-sub mono\">" + esc(fullDisplayName(model)) + "</p>\n");
        w.write("  </div>\n</div>\n");

        // Prominent description card
        if (model.description != null && !model.description.isEmpty()) {
            w.write("<div class=\"desc-card\">\n");
            w.write("  <div class=\"desc-label\">Description</div>\n");
            w.write("  <div class=\"desc-text\">" + esc(model.description) + "</div>\n");
            w.write("</div>\n");
        }

        // Prev / Next navigation
        writePrevNext(w, model);

        // ── Diagram ───────────────────────────────────────────────────────────
        w.write("<section class=\"card\">\n");
        w.write("  <div class=\"section-header\">\n");
        w.write("    <h2>Process Diagram</h2>\n");
        w.write("    <div class=\"diagram-toolbar\">\n");
        w.write("      <button class=\"btn-icon\" onclick=\"resetZoom(this)\" title=\"Reset zoom\">⊙ Reset</button>\n");
        w.write("    </div>\n  </div>\n");
        w.write("  <div class=\"diagram-wrap\" id=\"diag-" + safeId(model.displayName) + "\">\n");
        w.write(svgGen.generate(model));
        w.write("\n  </div>\n");
        // Legend
        w.write("  <div class=\"diagram-legend\">\n");
        writeLegend(w, "#1565c0", "Event Source");
        writeLegend(w, "#27ae60", "Success");
        writeLegend(w, "#e74c3c", "Error");
        writeLegend(w, "#e67e22", "Conditional");
        writeLegend(w, "#8e44ad", "Otherwise");
        w.write("  </div>\n</section>\n");

        // ── Starter detail ────────────────────────────────────────────────────
        if (model.starter != null) {
            w.write("<section class=\"card\">\n");
            w.write("  <h2>Event Source</h2>\n");
            writeActivityDetailTable(w, model.starter, null);
            w.write("</section>\n");
        }

        // ── Activities ────────────────────────────────────────────────────────
        List<ProcessDocModel.Activity> acts = model.activities.stream()
            .filter(a -> !a.isEnd).collect(Collectors.toList());
        if (!acts.isEmpty()) {
            w.write("<section class=\"card\">\n");
            w.write("  <div class=\"section-header\">\n");
            w.write("    <h2>Activities <span class=\"count-badge\">" + acts.size() + "</span></h2>\n");
            w.write("    <input class=\"table-filter\" placeholder=\"Filter…\" "
                + "oninput=\"filterTable(this,'acts-" + safeId(model.displayName) + "')\">\n");
            w.write("  </div>\n");
            w.write("  <table class=\"data-table\" id=\"acts-" + safeId(model.displayName) + "\">\n");
            w.write("    <thead><tr><th>#</th><th>Name</th><th>Type</th></tr></thead>\n");
            w.write("    <tbody>\n");
            int i = 1;
            for (ProcessDocModel.Activity a : acts) {
                String typeCell;
                if (a.calledProcessPath != null) {
                    // CallProcessActivity: embed link inside the type cell
                    ProcessDocModel target = resolveCalledProcess(a.calledProcessPath);
                    String link;
                    if (target != null) {
                        String href = "../" + nameToHtml.getOrDefault(target.name, "#");
                        link = "(<a class=\"call-link\" href=\"" + href + "\">"
                            + esc(fullDisplayName(target)) + "</a>)";
                    } else {
                        link = "(<span class=\"unresolved\">" + esc(lastSegment(a.calledProcessPath)) + "</span>)";
                    }
                    typeCell = "<span class=\"badge badge-type\">" + esc(a.shortType()) + "</span> " + link;
                } else {
                    typeCell = "<span class=\"badge badge-type\">" + esc(a.shortType()) + "</span>";
                }

                w.write("    <tr>\n");
                w.write("      <td class=\"num\">" + i++ + "</td>\n");
                w.write("      <td class=\"act-name\">" + esc(a.name) + "</td>\n");
                w.write("      <td class=\"type-cell\">" + typeCell + "</td>\n");
                w.write("    </tr>\n");
            }
            w.write("    </tbody>\n  </table>\n</section>\n");
        }

        // ── Transitions ───────────────────────────────────────────────────────
        if (!model.transitions.isEmpty()) {
            // Build name → activity lookup for icon resolution
            Map<String, ProcessDocModel.Activity> actByName = new LinkedHashMap<>();
            for (ProcessDocModel.Activity a : model.allActivities()) {
                if (a.name != null) actByName.put(a.name, a);
            }

            w.write("<section class=\"card\">\n");
            w.write("  <h2>Transitions <span class=\"count-badge\">" + model.transitions.size() + "</span></h2>\n");
            w.write("  <table class=\"data-table tr-visual-table\">\n");
            w.write("    <thead><tr><th>Flow</th><th>Condition</th></tr></thead>\n");
            w.write("    <tbody>\n");
            for (ProcessDocModel.Transition tr : model.transitions) {
                // Only show the XPath expression when one is present; arrow colour already conveys type
                String condCell = (tr.condition != null && !tr.condition.isEmpty())
                    ? "<code class=\"tr-cond-expr\">" + esc(tr.condition) + "</code>"
                    : "";
                w.write("    <tr>\n");
                w.write("      <td class=\"tr-flow-cell\">"
                    + buildTransitionSvg(tr, actByName) + "</td>\n");
                w.write("      <td class=\"tr-cond-cell\">" + condCell + "</td>\n");
                w.write("    </tr>\n");
            }
            w.write("    </tbody>\n  </table>\n</section>\n");
        }

        // ── Data Mappings ─────────────────────────────────────────────────────
        writeDataMappings(w, model);

        // ── Called by ─────────────────────────────────────────────────────────
        String key = model.name != null ? model.name : model.displayName;
        List<ProcessDocModel> callers = calledByMap.get(key);
        if (callers != null && !callers.isEmpty()) {
            w.write("<section class=\"card\">\n");
            w.write("  <h2>Called by</h2>\n");
            w.write("  <p class=\"section-desc\">These processes contain a Call Process activity targeting this process.</p>\n");
            w.write("  <ul class=\"caller-list\">\n");
            for (ProcessDocModel caller : callers) {
                String href = "../" + nameToHtml.getOrDefault(caller.name, "#");
                w.write("    <li><a href=\"" + href + "\">" + esc(fullDisplayName(caller)) + "</a></li>\n");
            }
            w.write("  </ul>\n</section>\n");
        }

        // ── Global Variables Used ─────────────────────────────────────────────
        writeGvUsedSection(w, model);

        // ── Connections Used ──────────────────────────────────────────────────
        writeConnectionsUsedSection(w, model);

        w.write("</div>\n</main>\n</div>\n");
        writeHtmlFoot(w);
    }

    // ── Shared HTML fragments ─────────────────────────────────────────────────

    /** Official TIBCO wordmark SVG (tibco.com), recoloured white for dark topbar. */
    private static final String TIBCO_LOGO_SVG =
        "<svg class=\"tibco-logo\" viewBox=\"0 0 89 25\" fill=\"none\" "
        + "xmlns=\"http://www.w3.org/2000/svg\" aria-label=\"TIBCO\" role=\"img\">\n"
        + "  <g clip-path=\"url(#tbcl)\">\n"
        // T
        + "    <path d=\"M-3.8147e-05 3.04688V5.61855H7.03817V23.1021H9.73685V5.61855H16.7751V3.04688L-3.8147e-05 3.04688Z\" fill=\"white\"/>\n"
        // I
        + "    <path d=\"M22.6872 3.04688H19.9877V23.1021H22.6872V3.04688Z\" fill=\"white\"/>\n"
        // B
        + "    <path d=\"M41.7353 17.4231C41.7353 20.824 39.4539 23.1021 34.2798 23.1021H27.4315V3.04688H34.0841C38.9375 3.04688 41.0898 5.22847 41.0898 8.36987C41.0898 10.4234 39.9004 11.9313 37.6515 12.7663C40.3168 13.44 41.7328 15.1733 41.7328 17.4198M34.3123 5.54952H30.1668V11.839H33.8942C36.7861 11.839 38.3611 10.5556 38.3611 8.59527C38.3611 6.66984 36.9452 5.54619 34.3123 5.54619M34.1191 14.31H30.1668V20.5995H34.5372C37.5257 20.5995 39.0042 19.5399 39.0042 17.4547C39.0042 15.1434 36.9468 14.31 34.1191 14.31Z\" fill=\"white\"/>\n"
        // C
        + "    <path d=\"M59.5416 6.7061C57.9317 5.58041 56.0095 4.98569 54.0443 5.00523C49.6731 5.00523 46.7495 8.02187 46.7495 12.9955C46.7495 17.9692 49.7064 21.1464 54.1084 21.1464C56.1641 21.1128 58.1559 20.4271 59.7956 19.1885L60.2454 21.9166C58.3656 23.0187 56.2233 23.595 54.0434 23.585C48.1622 23.585 43.985 19.5096 43.985 13.1245C43.985 6.73937 48.1996 2.56663 54.0484 2.56663C56.2796 2.55325 58.4747 3.12912 60.4111 4.23589L59.5416 6.7061Z\" fill=\"white\"/>\n"
        // O swoosh (3 paths)
        + "    <path d=\"M76.9589 3.9795C76.659 3.79576 76.3489 3.62915 76.0301 3.48047C73.4048 8.50654 68.6338 14.4858 62.3227 17.8867C62.6751 18.6813 63.1353 19.4238 63.6904 20.0932C69.4169 15.9397 73.9845 10.3927 76.9589 3.9795Z\" fill=\"white\"/>\n"
        + "    <path d=\"M75.7919 3.37496C74.413 2.78907 72.9276 2.49402 71.4291 2.50831C65.5253 2.50831 61.3507 7.05864 61.3507 13.0503C61.347 14.0096 61.451 14.9662 61.6605 15.9023C67.7051 13.4388 73.0741 8.02094 75.7919 3.37496Z\" fill=\"white\"/>\n"
        + "    <path d=\"M77.1862 4.11719C74.6238 10.7732 70.6321 16.7878 65.492 21.738C67.2443 22.9166 69.3175 23.5279 71.4299 23.4887C77.3345 23.4887 81.5083 18.906 81.5083 12.9151C81.5083 8.94448 79.8624 5.86546 77.1904 4.11719\" fill=\"white\"/>\n"
        // ® mark
        + "    <path d=\"M81.5041 3.4566V3.4408C81.5002 2.0104 82.6583 0.847664 84.0908 0.84376C85.5233 0.839855 86.6877 1.99626 86.6916 3.42666V3.4408C86.6959 4.8712 85.5382 6.03431 84.1058 6.03867C82.6733 6.04303 81.5085 4.887 81.5041 3.4566ZM86.3934 3.4408V3.42666C86.4133 2.1604 85.4015 1.11777 84.1334 1.09788C82.8653 1.07799 81.8211 2.08838 81.8012 3.35464C81.8008 3.38336 81.8008 3.41208 81.8015 3.4408V3.4566C81.7821 4.72287 82.7944 5.76507 84.0625 5.78442C85.3306 5.80377 86.3743 4.79294 86.3937 3.52667C86.3941 3.49805 86.394 3.46942 86.3934 3.4408ZM83.0842 2.07179H84.2769C84.86 2.07179 85.2914 2.35457 85.2914 2.90351C85.3064 3.28334 85.0497 3.62051 84.6792 3.70778L85.3805 4.70584H84.77L84.1436 3.79761H83.5914V4.70667H83.085L83.0842 2.07179ZM84.2319 3.38175C84.5743 3.38175 84.7692 3.20293 84.7692 2.95009C84.7692 2.6673 84.5751 2.51842 84.2319 2.51842H83.5906V3.38175H84.2319Z\" fill=\"white\"/>\n"
        + "  </g>\n"
        + "  <defs>\n"
        + "    <clipPath id=\"tbcl\">\n"
        + "      <rect width=\"88.29\" height=\"24.9515\" fill=\"white\" transform=\"translate(-3.8147e-05 0.0234375)\"/>\n"
        + "    </clipPath>\n"
        + "  </defs>\n"
        + "</svg>\n";

    private void writeTopbar(Writer w, String indexHref) throws IOException {
        w.write("<header class=\"topbar\">\n");
        w.write("  <button class=\"sidebar-toggle\" onclick=\"toggleSidebar()\" title=\"Toggle sidebar\">☰</button>\n");
        // TIBCO logo + project name (left side)
        w.write("  <a href=\"" + indexHref + "\" class=\"brand\">\n");
        w.write("    " + TIBCO_LOGO_SVG.replace("\n", "\n    ").trim() + "\n");
        w.write("    <span class=\"brand-sep\"></span>\n");
        w.write("    <span class=\"brand-project\">" + esc(project.getArtifactId()) + "</span>\n");
        w.write("  </a>\n");
        // Global search (centre)
        w.write("  <div class=\"search-wrap\">\n");
        w.write("    <input id=\"globalSearch\" class=\"global-search\" type=\"search\" "
            + "placeholder=\"Search processes… (/)\" autocomplete=\"off\">\n");
        w.write("    <div id=\"searchDrop\" class=\"search-drop\"></div>\n");
        w.write("  </div>\n");
        // Right side: tool ID + dark mode
        w.write("  <div class=\"topbar-end\">\n");
        w.write("    <span class=\"topbar-tool\">bwdoc</span>\n");
        w.write("    <button class=\"btn-icon theme-btn\" onclick=\"toggleTheme()\" title=\"Toggle dark mode\">🌙</button>\n");
        w.write("  </div>\n");
        w.write("</header>\n");
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void writeSidebarHtml(Writer w, List<ProcessDocModel> processes,
                                   String currentPath) throws IOException {
        w.write("<aside class=\"sidebar\" id=\"sidebar\">\n");
        w.write("  <div class=\"sidebar-project\">\n");
        w.write("    <div class=\"sidebar-proj-name\">" + esc(project.getArtifactId()) + "</div>\n");
        w.write("    <div class=\"sidebar-proj-meta\">" + processes.size() + " processes</div>\n");
        w.write("  </div>\n");
        w.write("  <input class=\"sidebar-filter\" id=\"sidebarFilter\" type=\"search\" "
            + "placeholder=\"Filter processes…\">\n");
        w.write("  <nav class=\"process-nav\" id=\"processNav\"></nav>\n");
        w.write("</aside>\n");
    }

    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private void writePrevNext(Writer w, ProcessDocModel model) throws IOException {
        int idx = -1;
        for (int i = 0; i < allProcesses.size(); i++) {
            if (allProcesses.get(i) == model) { idx = i; break; }
        }
        if (idx < 0) return;
        ProcessDocModel prev = idx > 0 ? allProcesses.get(idx - 1) : null;
        ProcessDocModel next = idx < allProcesses.size() - 1 ? allProcesses.get(idx + 1) : null;
        if (prev == null && next == null) return;

        w.write("<div class=\"prev-next\">\n");
        if (prev != null) {
            w.write("  <a class=\"pn-prev\" href=\"" + processFileName(prev) + ".html\">"
                + "← " + esc(fullDisplayName(prev)) + "</a>\n");
        } else {
            w.write("  <span></span>\n");
        }
        if (next != null) {
            w.write("  <a class=\"pn-next\" href=\"" + processFileName(next) + ".html\">"
                + esc(fullDisplayName(next)) + " →</a>\n");
        }
        w.write("</div>\n");
    }

    /**
     * Builds a compact inline SVG showing: [source icon + name] --arrow--> [target icon + name].
     * Arrow colour matches the diagram transition colours.
     */
    private String buildTransitionSvg(ProcessDocModel.Transition tr,
                                       Map<String, ProcessDocModel.Activity> actByName) {
        // SVG layout constants
        final int W = 320;
        final int H = 48;
        final int ICON = 20;
        final int ICON_R = ICON / 2;  // icon size and radius
        // Source zone centre x, Target zone centre x
        final int SRC_X = 36;
        final int TGT_X = W - 36;
        final int CY = 18;  // vertical centre for icon and arrow
        final int NAME_Y = H - 6;  // name label baseline

        String color = transitionSvgColor(tr.conditionType);
        boolean isDash = tr.conditionType != null && "error".equalsIgnoreCase(tr.conditionType);

        ProcessDocModel.Activity srcAct = actByName.get(tr.from);
        ProcessDocModel.Activity tgtAct = actByName.get(tr.to);

        // Arrow runs between icon edges
        int arrowX1 = SRC_X + ICON_R + 4;
        int arrowX2 = TGT_X - ICON_R - 4;
        // Control points for a mild curve
        int cx1 = arrowX1 + (arrowX2 - arrowX1) / 3;
        int cx2 = arrowX2 - (arrowX2 - arrowX1) / 3;
        // Arrowhead tip and base
        int tipX = arrowX2;
        int tipY = CY;
        int ah = 5;  // arrowhead half-height
        int ab = 8;  // arrowhead length

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" "
            + "width=\"%d\" height=\"%d\" style=\"font-family:Arial,sans-serif;vertical-align:middle;\">",
            W, H));

        // Source icon
        sb.append(activityMiniIcon(srcAct, tr.from, SRC_X, CY, ICON));
        // Source name label (truncated)
        sb.append(String.format(
            "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-size=\"9\" fill=\"currentColor\">%s</text>",
            SRC_X, NAME_Y, esc(truncate14(tr.from))));

        // Arrow path (bezier, stops before arrowhead tip)
        sb.append(String.format(
            "<path d=\"M%d,%d C%d,%d %d,%d %d,%d\" fill=\"none\" stroke=\"%s\" stroke-width=\"1.5\"%s/>",
            arrowX1, CY, cx1, CY, cx2, CY, tipX - ab, tipY,
            color, isDash ? " stroke-dasharray=\"4,3\"" : ""));
        // Arrowhead (filled triangle)
        sb.append(String.format(
            "<polygon points=\"%d,%d %d,%d %d,%d\" fill=\"%s\"/>",
            tipX, tipY, tipX - ab, tipY - ah, tipX - ab, tipY + ah, color));

        // Target icon
        sb.append(activityMiniIcon(tgtAct, tr.to, TGT_X, CY, ICON));
        // Target name label
        sb.append(String.format(
            "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-size=\"9\" fill=\"currentColor\">%s</text>",
            TGT_X, NAME_Y, esc(truncate14(tr.to))));

        sb.append("</svg>");
        return sb.toString();
    }

    /** Returns the icon element for a mini transition SVG, or a plain circle for unknown activities. */
    @SuppressWarnings("PMD.UnusedFormalParameter")
    private String activityMiniIcon(ProcessDocModel.Activity act, String fallbackName,
                                     int cx, int cy, int size) {
        if (act == null) {
            // Unknown activity (shouldn't happen, but fallback to a grey circle)
            return String.format(
                "<circle cx=\"%d\" cy=\"%d\" r=\"%d\" fill=\"#ccc\" stroke=\"#999\" stroke-width=\"1\"/>",
                cx, cy, size / 2);
        }
        if (act.isEnd || "ae.process.stopstate".equals(act.resourceType)) {
            return String.format(
                "<circle cx=\"%d\" cy=\"%d\" r=\"%d\" fill=\"#c62828\" stroke=\"#7f0000\" stroke-width=\"1\"/>"
                + "<circle cx=\"%d\" cy=\"%d\" r=\"3\" fill=\"#7f0000\"/>",
                cx, cy, size / 2, cx, cy);
        }
        if ("act-startstate".equals(act.cssClass())) {
            return String.format(
                "<circle cx=\"%d\" cy=\"%d\" r=\"%d\" fill=\"#2e7d32\" stroke=\"#1b5e20\" stroke-width=\"1\"/>"
                + "<circle cx=\"%d\" cy=\"%d\" r=\"3\" fill=\"#a5d6a7\"/>",
                cx, cy, size / 2, cx, cy);
        }
        return ActivityIconRegistry.getImageElement(act.type, act.resourceType, cx, cy, size);
    }

    private String transitionSvgColor(String conditionType) {
        if (conditionType == null) return "#888";
        switch (conditionType.toLowerCase(java.util.Locale.ROOT)) {
            case "error":                return "#e74c3c";
            case "xpath":
            case "successwithcondition": return "#e67e22";
            case "otherwise":            return "#8e44ad";
            case "success":              return "#27ae60";
            default:                     return "#888";
        }
    }

    private String truncate14(String s) {
        if (s == null) return "";
        return s.length() > 14 ? s.substring(0, 13) + "…" : s;
    }

    private void writeLegend(Writer w, String color, String label) throws IOException {
        w.write("    <span class=\"legend-item\">"
            + "<span class=\"legend-dot\" style=\"background:" + color + "\"></span>"
            + esc(label) + "</span>\n");
    }

    private void writeStat(Writer w, String value, String label, String icon) throws IOException {
        w.write("<div class=\"stat-card\">\n");
        w.write("  <div class=\"stat-icon\">" + icon + "</div>\n");
        w.write("  <div class=\"stat-value\">" + value + "</div>\n");
        w.write("  <div class=\"stat-label\">" + label + "</div>\n");
        w.write("</div>\n");
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void writeActivityDetailTable(Writer w, ProcessDocModel.Activity a,
                                           String calledHref) throws IOException {
        w.write("<table class=\"detail-table\">\n");
        w.write("  <tr><th>Name</th><td>" + esc(a.name) + "</td></tr>\n");
        w.write("  <tr><th>Type</th><td><code>" + esc(a.type) + "</code></td></tr>\n");
        if (a.resourceType != null && !a.resourceType.isEmpty()) {
            w.write("  <tr><th>Palette</th><td><span class=\"badge badge-type\">"
                + esc(a.resourceType) + "</span></td></tr>\n");
        }
        if (!a.configEntries.isEmpty()) {
            w.write("  <tr><th>Config</th><td>\n");
            w.write("    <table class=\"config-table\">\n");
            for (Map.Entry<String, String> e : a.configEntries.entrySet()) {
                w.write("      <tr><td class=\"cfg-key\">" + esc(e.getKey()) + "</td>");
                w.write("<td class=\"cfg-val\">" + renderConfigValue(e.getValue()) + "</td></tr>\n");
            }
            w.write("    </table>\n  </td></tr>\n");
        } else if (a.configSummary != null && !a.configSummary.isEmpty()) {
            w.write("  <tr><th>Config</th><td class=\"config-summary\">" + esc(a.configSummary) + "</td></tr>\n");
        }
        w.write("</table>\n");
    }

    /**
     * Renders a config value with highlighting for global variable references (%%...%%)
     * and hyperlinks for shared resource references.
     */
    private String renderConfigValue(String val) {
        if (val == null) return "";
        // Check if it's a shared resource reference
        String valNorm = val.startsWith("/") ? val.substring(1) : val;
        for (SharedResourceModel sr : sharedResources) {
            String srNorm = sr.name.startsWith("/") ? sr.name.substring(1) : sr.name;
            if (srNorm.equals(valNorm)) {
                String href = "../" + srNameToHtml.getOrDefault(sr.name, "#");
                return "<a href=\"" + href + "\" class=\"sr-link\">" + esc(sr.displayName) + "</a>";
            }
        }
        // Highlight global variable references (%%varName%%)
        Matcher m = GV_PATTERN.matcher(val);
        if (m.find()) {
            StringBuffer sb = new StringBuffer();
            m.reset();
            while (m.find()) {
                m.appendReplacement(sb,
                    "<span class=\"gv-ref\" title=\"Global variable\">%%" + esc(m.group(1)) + "%%</span>");
            }
            m.appendTail(sb);
            return sb.toString();
        }
        return esc(val);
    }

    // ── Mapper tree node ─────────────────────────────────────────────────────

    private static class MTreeNode {
        final String label;
        final List<MTreeNode> children = new ArrayList<>();
        /** Mapping indices that terminate at this node (can be multiple for shared paths). */
        final List<Integer> mappingIdxs = new ArrayList<>();
        /** Non-zero for virtual branch nodes (WHEN/OTHERWISE/IF/FOR-EACH); used by JS to align branch items. */
        int branchId;

        MTreeNode(String label) { this.label = label; }

        MTreeNode getOrCreate(String lbl) {
            for (MTreeNode c : children) {
                if (lbl.equals(c.label)) return c;
            }
            MTreeNode n = new MTreeNode(lbl);
            children.add(n);
            return n;
        }

        boolean isLeaf() { return children.isEmpty(); }
    }

    private static class BranchInfo {
        final int branchId;
        final String conditionKind;
        final String condition;
        BranchInfo(int bid, String kind, String cond) {
            branchId = bid; conditionKind = kind; condition = cond;
        }
    }

    private static boolean isSimplePath(String expr) {
        if (expr == null || !expr.startsWith("$")) return false;
        return !expr.contains("(") && !expr.contains("[")
            && !expr.contains(" ")  && !expr.contains("+")
            && !expr.contains("=")  && !expr.contains("!")
            && !expr.contains("|")  && !expr.contains(",");
    }

    /** True when an XPath source expression references a BW5 global variable. */
    private static boolean isGvPath(String expr) {
        return expr != null && expr.startsWith("$_globalVariables/");
    }

    /**
     * Extracts the variable name from a GV source expression, stripping the
     * {@code $_globalVariables/ns:GlobalVariables/} prefix.
     * E.g. {@code $_globalVariables/ns:GlobalVariables/CFG/COMMON/timerSleep} → {@code CFG/COMMON/timerSleep}
     */
    private static String extractGvName(String expr) {
        if (expr == null) return "";
        int idx = expr.indexOf("/ns:GlobalVariables/");
        if (idx >= 0) return expr.substring(idx + "/ns:GlobalVariables/".length());
        // Fallback: strip first two slash-segments ($var / nsPrefix / ...)
        String[] parts = expr.split("/");
        if (parts.length > 2) {
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < parts.length; i++) {
                if (i > 2) sb.append('/');
                sb.append(parts[i]);
            }
            return sb.toString();
        }
        return expr;
    }

    /** Build a prefix-trie from mapping source expressions (simple paths only). */
    private MTreeNode buildSrcTree(List<ProcessDocModel.FieldMapping> mappings) {
        MTreeNode root = new MTreeNode("");
        for (int i = 0; i < mappings.size(); i++) {
            ProcessDocModel.FieldMapping m = mappings.get(i);
            if (m.isLiteral || !isSimplePath(m.sourceExpression)) continue;
            MTreeNode cur = root;
            for (String seg : m.sourceExpression.split("/")) cur = cur.getOrCreate(seg);
            cur.mappingIdxs.add(i);
        }
        return root;
    }

    /**
     * Build a prefix-trie from mapping target paths.
     * For conditional mappings (chooseId > 0), inserts a virtual WHEN/OTHERWISE/IF/FOR-EACH
     * node just above the leaf. Virtual node labels use the prefix "~WHEN:", "~IF:",
     * "~FOR-EACH:", or "~OTHERWISE" so renderMTree renders them as badges.
     * Branch IDs are recorded in {@code branches} for VALUE/EXPRESSION column alignment.
     */
    private MTreeNode buildTgtTree(List<ProcessDocModel.FieldMapping> mappings,
                                   List<BranchInfo> branches) {
        MTreeNode root = new MTreeNode("");
        int[] branchSeq = {0};
        for (int i = 0; i < mappings.size(); i++) {
            ProcessDocModel.FieldMapping m = mappings.get(i);
            String path = m.targetPath != null ? m.targetPath : m.targetField;
            if (path == null || path.isEmpty()) continue;
            String[] segs = path.split("/");
            MTreeNode cur = root;

            if (m.chooseId > 0) {
                // Walk all segments except the last, then insert virtual branch node, then leaf
                for (int s = 0; s < segs.length - 1; s++) cur = cur.getOrCreate(segs[s]);
                String branchLabel;
                if ("when".equals(m.conditionKind)) {
                    branchLabel = "~WHEN:" + (m.condition != null ? m.condition : "");
                } else if ("if".equals(m.conditionKind)) {
                    branchLabel = "~IF:" + (m.condition != null ? m.condition : "");
                } else if ("for-each".equals(m.conditionKind)) {
                    branchLabel = "~FOR-EACH:" + (m.condition != null ? m.condition : "");
                } else {
                    branchLabel = "~OTHERWISE";
                }
                MTreeNode branchNode = cur.getOrCreate(branchLabel);
                if (branchNode.branchId == 0) {
                    branchNode.branchId = ++branchSeq[0];
                    branches.add(new BranchInfo(branchNode.branchId, m.conditionKind, m.condition));
                }
                cur = branchNode;
                cur = cur.getOrCreate(segs[segs.length - 1]);
            } else {
                for (String seg : segs) cur = cur.getOrCreate(seg);
            }
            cur.mappingIdxs.add(i);
        }
        return root;
    }

    /** Render a trie as indented HTML rows; leaf nodes carry data-midxs for JS line drawing. */
    private void renderMTree(Writer w, MTreeNode node, int depth, boolean inGvPath) throws IOException {
        for (MTreeNode child : node.children) {
            boolean isWhenNode      = child.label.startsWith("~WHEN:");
            boolean isIfNode        = child.label.startsWith("~IF:");
            boolean isOtherwiseNode = "~OTHERWISE".equals(child.label);
            boolean isForEachNode   = child.label.startsWith("~FOR-EACH:");
            // GV path: this node is inside a _globalVariables subtree
            boolean isGvNode = inGvPath || "$_globalVariables".equals(child.label);

            if (isWhenNode || isIfNode || isOtherwiseNode || isForEachNode) {
                String cssSuffix = isOtherwiseNode ? "mtree-branch-otherwise"
                    : isForEachNode ? "mtree-branch-foreach"
                    : "mtree-branch-when";
                String css = "mtree-node mtree-branch-hdr " + cssSuffix;
                w.write("<div class=\"" + css + "\""
                    + " data-branch-id=\"" + child.branchId + "\""
                    + " style=\"padding-left:" + (depth * 14 + 6) + "px\">");
                if (isWhenNode)         w.write("<span class=\"map-kw map-kw-when\">WHEN</span>");
                else if (isIfNode)      w.write("<span class=\"map-kw map-kw-if\">IF</span>");
                else if (isForEachNode) w.write("<span class=\"map-kw map-kw-foreach\">FOR-EACH</span>");
                else                    w.write("<span class=\"map-kw map-kw-otherwise\">OTHERWISE</span>");
                w.write("</div>\n");
            } else {
                boolean leaf = child.isLeaf();
                String midxsAttr = "";
                if (!child.mappingIdxs.isEmpty()) {
                    StringBuilder sb2 = new StringBuilder();
                    for (int i = 0; i < child.mappingIdxs.size(); i++) {
                        if (i > 0) sb2.append(',');
                        sb2.append(child.mappingIdxs.get(i));
                    }
                    midxsAttr = " data-midxs=\"" + sb2 + "\"";
                }
                String gvClass = isGvNode ? " mtree-gv" : "";
                w.write("<div class=\"mtree-node" + (leaf ? " mtree-leaf" : "") + gvClass + "\""
                    + midxsAttr
                    + " style=\"padding-left:" + (depth * 14 + 6) + "px\">");
                w.write("<span class=\"mtree-icon\">" + (leaf ? "&#9656;" : "&#9662;") + "</span>");
                // GV leaf: make it a link to the GV definition on the index page
                if (isGvNode && leaf) {
                    String gvAnchor = "../index.html#gv-" + safeId(child.label);
                    w.write("<a href=\"" + gvAnchor + "\" class=\"mtree-gv-link\" title=\"Global variable\">"
                        + esc(child.label) + "</a>");
                } else {
                    w.write("<span class=\"mtree-lbl\">" + esc(child.label) + "</span>");
                }
                w.write("</div>\n");
            }
            renderMTree(w, child, depth + 1, isGvNode);
        }
    }

    // ── Data mapping section ──────────────────────────────────────────────────

    private void writeDataMappings(Writer w, ProcessDocModel model) throws IOException {
        boolean has = (model.starter != null && !model.starter.inputMappings.isEmpty())
            || model.activities.stream().anyMatch(a -> !a.inputMappings.isEmpty());
        if (!has) return;

        w.write("<section class=\"card\">\n");
        w.write("  <h2>Data Mappings</h2>\n");

        int widgetIdx = 0;
        for (ProcessDocModel.Activity a : model.allActivities()) {
            if (a.inputMappings.isEmpty()) continue;
            List<ProcessDocModel.FieldMapping> mappings = a.inputMappings;
            String wid = "mw-" + widgetIdx++;

            List<BranchInfo> branches = new ArrayList<>();
            MTreeNode srcRoot = buildSrcTree(mappings);
            MTreeNode tgtRoot = buildTgtTree(mappings, branches);

            w.write("  <div class=\"mapping-block\">\n");
            w.write("    <div class=\"mapping-act-header\">" + esc(a.name) + "</div>\n");
            w.write("    <div class=\"mapper-3col\" id=\"" + wid + "\">\n");

            // ── Source column ────────────────────────────────────────────────
            w.write("      <div class=\"mapper-col mapper-src\">\n");
            w.write("        <div class=\"mapper-col-head\">Source</div>\n");
            if (srcRoot.children.isEmpty()) {
                w.write("        <div class=\"mtree mtree-empty\">&#8212;</div>\n");
            } else {
                w.write("        <div class=\"mtree\">\n");
                renderMTree(w, srcRoot, 0, false);
                w.write("        </div>\n");
            }
            w.write("      </div>\n");

            // ── Target column ────────────────────────────────────────────────
            w.write("      <div class=\"mapper-col mapper-tgt\">\n");
            w.write("        <div class=\"mapper-col-head\">Target</div>\n");
            w.write("        <div class=\"mtree\">\n");
            renderMTree(w, tgtRoot, 0, false);
            w.write("        </div>\n");
            w.write("      </div>\n");

            // ── Value / Expression column ────────────────────────────────────
            // For choose-block mappings (chooseId > 0): each mapping gets its own
            // cond-item aligned to its individual leaf (condition is already shown
            // in the TARGET tree as a virtual node — don't repeat it here).
            // For simple mappings: group by targetPath as before.
            w.write("      <div class=\"mapper-col mapper-cond\">\n");
            w.write("        <div class=\"mapper-col-head\">Value / Expression</div>\n");

            // Build groups: choose mappings are individual; others grouped by targetPath
            Map<String, List<Integer>> tgtGroups = new LinkedHashMap<>();
            for (int i = 0; i < mappings.size(); i++) {
                ProcessDocModel.FieldMapping m = mappings.get(i);
                String key = m.chooseId > 0
                    ? "~choose:" + i                                      // unique per choose mapping
                    : (m.targetPath != null ? m.targetPath : "#" + i);   // grouped for simple mappings
                tgtGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            }

            for (Map.Entry<String, List<Integer>> grp : tgtGroups.entrySet()) {
                List<Integer> midxs = grp.getValue();
                String midxsStr = midxs.stream().map(Object::toString)
                        .collect(Collectors.joining(","));
                w.write("        <div class=\"mapper-cond-item\" data-midxs=\"" + midxsStr + "\">\n");
                for (int midx : midxs) {
                    ProcessDocModel.FieldMapping m = mappings.get(midx);
                    String kind = m.conditionKind;
                    // Build tooltip from source expression only (conditions shown in branch items)
                    String tip = (m.sourceExpression != null && !m.sourceExpression.isEmpty())
                        ? m.sourceExpression : null;
                    w.write("          <div class=\"cond-alt-row"
                        + (kind != null ? " map-kind-" + kind : "") + "\""
                        + (tip != null ? " title=\"" + esc(tip) + "\"" : "")
                        + ">");
                    // Value / expression (literals + complex XPath)
                    // Condition keywords (WHEN/OTHERWISE/IF/FOR-EACH) are shown in branch items, not here
                    if (m.isLiteral) {
                        w.write("<span class=\"map-literal\" title=\"" + esc(m.sourceExpression) + "\">"
                            + "\"" + esc(m.sourceExpression) + "\"</span>");
                    } else if (!isSimplePath(m.sourceExpression)
                               && m.sourceExpression != null && !m.sourceExpression.isEmpty()) {
                        w.write("<code class=\"map-xpath\" title=\"" + esc(m.sourceExpression) + "\">"
                            + esc(m.sourceExpression) + "</code>");
                    }
                    w.write("</div>\n");
                }
                w.write("        </div>\n");
            }

            // Branch items: one per virtual branch node, absolutely positioned by JS
            // to align with the corresponding WHEN/OTHERWISE/IF/FOR-EACH badge in the TARGET tree
            for (BranchInfo b : branches) {
                w.write("        <div class=\"mapper-branch-item\" data-branch-id=\"" + b.branchId + "\">");
                if ("otherwise".equals(b.conditionKind)) {
                    w.write("<span class=\"map-kw map-kw-otherwise\">OTHERWISE</span>");
                } else if (b.condition != null && !b.condition.isEmpty()) {
                    w.write("<code class=\"map-cond-expr\" title=\"" + esc(b.condition) + "\">"
                        + esc(b.condition) + "</code>");
                }
                w.write("</div>\n");
            }
            w.write("      </div>\n");

            // SVG overlay — lines drawn by JS
            w.write("      <svg class=\"mapper-svg\" xmlns=\"http://www.w3.org/2000/svg\"></svg>\n");

            w.write("    </div>\n"); // mapper-3col
            w.write("  </div>\n");   // mapping-block
        }
        w.write("</section>\n");
    }

    // ── Process tree ──────────────────────────────────────────────────────────

    /** Generic tree node used for both the process and GV directory trees. */
    private static class DirNode {
        final Map<String, DirNode> children = new LinkedHashMap<>();
        final List<ProcessDocModel> processes = new ArrayList<>();
        final List<SubstVarParser.GlobalVariable> vars = new ArrayList<>();
    }

    private void writeProcessTree(Writer w, List<ProcessDocModel> processes) throws IOException {
        // Build folder tree from folderPath
        DirNode root = new DirNode();
        List<ProcessDocModel> sorted = new ArrayList<>(processes);
        sorted.sort(Comparator.comparing(p -> fullDisplayName(p).toLowerCase(java.util.Locale.ROOT)));
        for (ProcessDocModel p : sorted) {
            DirNode node = root;
            if (!p.folderPath.isEmpty()) {
                for (String seg : p.folderPath.split("/")) {
                    node = node.children.computeIfAbsent(seg, k -> new DirNode());
                }
            }
            node.processes.add(p);
        }
        renderProcessDirNode(w, root, 0, true);
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void renderProcessDirNode(Writer w, DirNode node, int depth, boolean isRoot) throws IOException {
        String indent = "  ".repeat(depth + 1);
        // Render sub-folders first
        for (Map.Entry<String, DirNode> e : node.children.entrySet()) {
            int total = countProcessLeaves(e.getValue());
            w.write(indent + "<details class=\"tree-folder\">\n");
            w.write(indent + "  <summary class=\"tree-folder-summary\">"
                + "<span class=\"tree-folder-icon\">📁</span>"
                + "<span class=\"tree-folder-name\">" + esc(e.getKey()) + "</span>"
                + "<span class=\"tree-folder-count\">" + total + "</span>"
                + "</summary>\n");
            renderProcessDirNode(w, e.getValue(), depth + 1, false);
            w.write(indent + "</details>\n");
        }
        // Render process leaves
        for (ProcessDocModel p : node.processes) {
            String href = "processes/" + processFileName(p) + ".html";
            String starter = p.starter != null
                ? "<span class=\"badge badge-starter\">" + esc(p.starter.shortType()) + "</span>" : "";
            w.write(indent + "<div class=\"tree-item tree-item-proc\" data-label=\""
                + esc(fullDisplayName(p).toLowerCase(java.util.Locale.ROOT)) + "\">\n");
            w.write(indent + "  <span class=\"tree-item-icon\">📄</span>\n");
            w.write(indent + "  <a href=\"" + href + "\" class=\"tree-item-link\">" + esc(p.displayName) + "</a>\n");
            w.write(indent + "  " + starter + "\n");
            w.write(indent + "  <span class=\"tree-item-meta\">"
                + p.activities.stream().filter(a -> !a.isEnd).count() + " acts"
                + " · " + p.transitions.size() + " tr"
                + "</span>\n");
            w.write(indent + "</div>\n");
        }
    }

    private int countProcessLeaves(DirNode node) {
        int count = node.processes.size();
        for (DirNode child : node.children.values()) count += countProcessLeaves(child);
        return count;
    }

    // ── Global Variable tree ──────────────────────────────────────────────────

    private void writeGvTree(Writer w, List<SubstVarParser.GlobalVariable> vars) throws IOException {
        // Build folder tree from variable name path (split by /)
        DirNode root = new DirNode();
        List<SubstVarParser.GlobalVariable> sorted = new ArrayList<>(vars);
        sorted.sort(Comparator.comparing(v -> v.name != null ? v.name.toLowerCase(java.util.Locale.ROOT) : ""));
        for (SubstVarParser.GlobalVariable gv : sorted) {
            String name = gv.name != null ? gv.name : "";
            String[] parts = name.split("/");
            DirNode node = root;
            // All segments except the last are folders; the last is the variable name
            for (int i = 0; i < parts.length - 1; i++) {
                String seg = parts[i];
                node = node.children.computeIfAbsent(seg, k -> new DirNode());
            }
            node.vars.add(gv);
        }
        renderGvDirNode(w, root, 0);
    }

    private void renderGvDirNode(Writer w, DirNode node, int depth) throws IOException {
        String indent = "  ".repeat(depth + 1);
        for (Map.Entry<String, DirNode> e : node.children.entrySet()) {
            int total = countGvLeaves(e.getValue());
            w.write(indent + "<details class=\"tree-folder\">\n");
            w.write(indent + "  <summary class=\"tree-folder-summary\">"
                + "<span class=\"tree-folder-icon\">📁</span>"
                + "<span class=\"tree-folder-name\">" + esc(e.getKey()) + "</span>"
                + "<span class=\"tree-folder-count\">" + total + "</span>"
                + "</summary>\n");
            renderGvDirNode(w, e.getValue(), depth + 1);
            w.write(indent + "</details>\n");
        }
        for (SubstVarParser.GlobalVariable gv : node.vars) {
            String[] parts = (gv.name != null ? gv.name : "").split("/");
            String leafName = parts[parts.length - 1];
            boolean isPass = "Password".equalsIgnoreCase(gv.type);
            String typeLabel = gv.type != null && !gv.type.isEmpty() ? gv.type : "String";
            String valDisplay = isPass ? "<em class=\"gv-pass\">[password]</em>"
                : "<span class=\"gv-val\">" + esc(gv.value != null ? gv.value : "") + "</span>";
            w.write(indent + "<div class=\"tree-item tree-item-gv\" id=\"gv-" + safeId(gv.name) + "\""
                + " data-label=\""
                + esc((gv.name != null ? gv.name : "").toLowerCase(java.util.Locale.ROOT)) + "\">\n");
            w.write(indent + "  <span class=\"tree-item-icon\">🔧</span>\n");
            w.write(indent + "  <span class=\"tree-item-name\">" + esc(leafName) + "</span>\n");
            w.write(indent + "  <span class=\"badge badge-type gv-type-badge\">" + esc(typeLabel) + "</span>\n");
            w.write(indent + "  " + valDisplay + "\n");
            w.write(indent + "</div>\n");
        }
    }

    private int countGvLeaves(DirNode node) {
        int count = node.vars.size();
        for (DirNode child : node.children.values()) count += countGvLeaves(child);
        return count;
    }

    // ── Global Variables Used (process page) ──────────────────────────────────

    private void writeGvUsedSection(Writer w, ProcessDocModel model) throws IOException {
        // Collect GV names from: (a) activity configEntries (%%name%%), (b) mapping source expressions
        Map<String, SubstVarParser.GlobalVariable> found = new LinkedHashMap<>();

        // From activity config values
        for (ProcessDocModel.Activity a : model.allActivities()) {
            for (String val : a.configEntries.values()) {
                Matcher m = GV_PATTERN.matcher(val);
                while (m.find()) {
                    String gvName = m.group(1);
                    if (!found.containsKey(gvName)) {
                        // Find the GV definition by matching the last segment of the stored name
                        for (SubstVarParser.GlobalVariable gv : globalVars) {
                            String storedName = gv.name != null ? gv.name : "";
                            String lastSeg = lastSegment(storedName.replace("/", "/"));
                            if (lastSeg.equals(gvName) || storedName.equals(gvName)) {
                                found.put(gvName, gv);
                                break;
                            }
                        }
                        if (!found.containsKey(gvName)) {
                            // No match found — store null sentinel so we still show it
                            found.put(gvName, null);
                        }
                    }
                }
            }
        }

        // From mapping source expressions (XPath GV paths)
        for (ProcessDocModel.Activity a : model.allActivities()) {
            for (ProcessDocModel.FieldMapping fm : a.inputMappings) {
                if (isGvPath(fm.sourceExpression)) {
                    String gvName = extractGvName(fm.sourceExpression);
                    if (!found.containsKey(gvName)) {
                        // Match against stored global var names
                        for (SubstVarParser.GlobalVariable gv : globalVars) {
                            String storedName = gv.name != null ? gv.name : "";
                            if (storedName.equals(gvName) || storedName.endsWith("/" + gvName)
                                    || gvName.endsWith("/" + lastSegment(storedName))) {
                                found.put(gvName, gv);
                                break;
                            }
                        }
                        if (!found.containsKey(gvName)) {
                            found.put(gvName, null);
                        }
                    }
                }
            }
        }

        if (found.isEmpty()) return;

        w.write("<section class=\"card\">\n");
        w.write("  <h2>Global Variables Used <span class=\"count-badge\">" + found.size() + "</span></h2>\n");
        w.write("  <p class=\"section-desc\">Substitution variables referenced in activity configuration or data mappings.</p>\n");
        w.write("  <table class=\"data-table\">\n");
        w.write("    <thead><tr><th>Name</th><th>Default Value</th><th>Type</th></tr></thead>\n");
        w.write("    <tbody>\n");
        for (Map.Entry<String, SubstVarParser.GlobalVariable> e : found.entrySet()) {
            String displayName = e.getKey();
            SubstVarParser.GlobalVariable gv = e.getValue();
            String anchor = "../index.html#gv-" + safeId(gv != null ? gv.name : displayName);
            boolean isPass = gv != null && "Password".equalsIgnoreCase(gv.type);
            String valCell = gv == null ? "<span class=\"gv-pass\">—</span>"
                : isPass ? "<em class=\"gv-pass\">[password]</em>"
                : "<span class=\"gv-val\">" + esc(gv.value != null ? gv.value : "") + "</span>";
            String typeCell = gv != null && gv.type != null && !gv.type.isEmpty()
                ? "<span class=\"badge badge-type\">" + esc(gv.type) + "</span>" : "";
            w.write("    <tr>\n");
            w.write("      <td><a href=\"" + anchor + "\" class=\"call-link gv-anchor-link\">"
                + "<code>" + esc(displayName) + "</code></a></td>\n");
            w.write("      <td>" + valCell + "</td>\n");
            w.write("      <td>" + typeCell + "</td>\n");
            w.write("    </tr>\n");
        }
        w.write("    </tbody>\n  </table>\n</section>\n");
    }

    // ── Connections Used (process page) ───────────────────────────────────────

    private void writeConnectionsUsedSection(Writer w, ProcessDocModel model) throws IOException {
        // Collect unique SR references across all activities
        List<SharedResourceModel> usedSRs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (ProcessDocModel.Activity a : model.allActivities()) {
            for (String ref : a.sharedResourceRefs) {
                if (!seen.add(ref)) continue;
                // Find matching SR
                String refNorm = ref.startsWith("/") ? ref.substring(1) : ref;
                for (SharedResourceModel sr : sharedResources) {
                    String srNorm = sr.name.startsWith("/") ? sr.name.substring(1) : sr.name;
                    if (srNorm.equals(refNorm) || sr.displayName.equals(lastSegment(ref))) {
                        usedSRs.add(sr);
                        break;
                    }
                }
            }
        }

        if (usedSRs.isEmpty()) return;

        w.write("<section class=\"card\">\n");
        w.write("  <h2>Connections Used <span class=\"count-badge\">" + usedSRs.size() + "</span></h2>\n");
        w.write("  <p class=\"section-desc\">Shared resources referenced by activities in this process.</p>\n");
        w.write("  <table class=\"data-table\">\n");
        w.write("    <thead><tr><th>Name</th><th>Type</th></tr></thead>\n");
        w.write("    <tbody>\n");
        for (SharedResourceModel sr : usedSRs) {
            String href = "../" + srNameToHtml.getOrDefault(sr.name, "sharedresources/" + srFileName(sr) + ".html");
            w.write("    <tr>\n");
            w.write("      <td><a href=\"" + href + "\" class=\"call-link\">" + esc(sr.displayName) + "</a></td>\n");
            w.write("      <td><span class=\"badge badge-type\">" + esc(sr.shortType()) + "</span></td>\n");
            w.write("    </tr>\n");
        }
        w.write("    </tbody>\n  </table>\n</section>\n");
    }

    // ── Shared Resource page ──────────────────────────────────────────────────

    private void writeSharedResourcePage(Writer w, SharedResourceModel sr) throws IOException {
        String title = sr.displayName + " — " + project.getArtifactId();
        writeHtmlHead(w, title, "../bw5-site.css", "../bw5-site.js", "../", "sharedresources/" + srFileName(sr) + ".html");
        writeTopbar(w, "../index.html");

        w.write("<div class=\"app-body\">\n");
        writeSidebarHtml(w, allProcesses, "sharedresources/" + srFileName(sr) + ".html");
        w.write("<main class=\"main-content\">\n<div class=\"content-inner\">\n");

        // Breadcrumb
        w.write("<nav class=\"breadcrumb\">\n");
        w.write("  <a href=\"../index.html\">" + esc(project.getArtifactId()) + "</a>\n");
        w.write("  <span class=\"bc-sep\">›</span> <span class=\"bc-seg\">Shared Resources</span>\n");
        w.write("  <span class=\"bc-sep\">›</span> <strong>" + esc(sr.displayName) + "</strong>\n");
        w.write("</nav>\n");

        // Hero
        w.write("<div class=\"page-hero page-hero-sm\">\n");
        w.write("  <div class=\"hero-text\">\n");
        w.write("    <h1>" + esc(sr.displayName) + "</h1>\n");
        w.write("    <p class=\"hero-sub mono\">" + esc(sr.name) + "</p>\n");
        if (sr.type != null && !sr.type.isEmpty()) {
            w.write("    <p class=\"hero-sub\">" + esc(sr.type) + "</p>\n");
        }
        w.write("  </div>\n</div>\n");

        // Config table
        if (!sr.config.isEmpty()) {
            w.write("<section class=\"card\">\n");
            w.write("  <h2>Configuration</h2>\n");
            w.write("  <table class=\"detail-table\">\n");
            for (Map.Entry<String, String> e : sr.config.entrySet()) {
                boolean isPass = e.getKey().toLowerCase(java.util.Locale.ROOT).contains("password")
                    || e.getKey().toLowerCase(java.util.Locale.ROOT).contains("secret");
                w.write("  <tr><th>" + esc(e.getKey()) + "</th><td>");
                if (isPass) {
                    w.write("<em class=\"gv-pass\">[hidden]</em>");
                } else {
                    w.write(renderConfigValue(e.getValue()));
                }
                w.write("</td></tr>\n");
            }
            w.write("  </table>\n</section>\n");
        }

        // Used by
        if (!sr.usedBy.isEmpty()) {
            w.write("<section class=\"card\">\n");
            w.write("  <h2>Used by <span class=\"count-badge\">" + sr.usedBy.size() + "</span></h2>\n");
            w.write("  <p class=\"section-desc\">Activities that reference this shared resource.</p>\n");
            w.write("  <ul class=\"caller-list\">\n");
            for (String entry : sr.usedBy) {
                // entry is "ProcessFullName / ActivityName"
                int sep = entry.lastIndexOf(" / ");
                String procPath = sep > 0 ? entry.substring(0, sep) : entry;
                String actName  = sep > 0 ? entry.substring(sep + 3) : "";
                // Try to find process href
                String href = null;
                for (ProcessDocModel p : allProcesses) {
                    if (fullDisplayName(p).equals(procPath)) {
                        href = "../" + nameToHtml.getOrDefault(p.name, "#");
                        break;
                    }
                }
                if (href != null) {
                    w.write("    <li><a href=\"" + href + "\">" + esc(procPath) + "</a>"
                        + (actName.isEmpty() ? "" : " › <span class=\"bc-seg\">" + esc(actName) + "</span>") + "</li>\n");
                } else {
                    w.write("    <li>" + esc(entry) + "</li>\n");
                }
            }
            w.write("  </ul>\n</section>\n");
        }

        w.write("</div>\n</main>\n</div>\n");
        writeHtmlFoot(w);
    }

    private long countDependencies() {
        List<Dependency> deps = project.getDependencies();
        if (deps == null) return 0;
        return deps.stream()
            .filter(d -> !"test".equals(d.getScope()) && !d.isOptional())
            .count();
    }

    private void writeDependenciesSection(Writer w) throws IOException {
        List<Dependency> deps = project.getDependencies();
        if (deps == null || deps.isEmpty()) return;

        List<Dependency> projlibs = new ArrayList<>();
        List<Dependency> jars = new ArrayList<>();
        List<Dependency> others = new ArrayList<>();
        for (Dependency d : deps) {
            if ("test".equals(d.getScope())) continue;
            switch (d.getType() == null ? "jar" : d.getType()) {
                case "projlib": projlibs.add(d); break;
                case "jar":     jars.add(d);     break;
                default:        others.add(d);   break;
            }
        }
        if (projlibs.isEmpty() && jars.isEmpty() && others.isEmpty()) return;

        w.write("<section class=\"card\">\n");
        w.write("  <h2>Dependencies</h2>\n");

        if (!projlibs.isEmpty()) {
            w.write("  <h3 class=\"dep-subsection\">Projlib Dependencies</h3>\n");
            writeDepTable(w, projlibs, "dep-projlib");
        }
        if (!jars.isEmpty()) {
            w.write("  <h3 class=\"dep-subsection\">JAR Dependencies</h3>\n");
            writeDepTable(w, jars, "dep-jar");
        }
        if (!others.isEmpty()) {
            w.write("  <h3 class=\"dep-subsection\">Other Dependencies</h3>\n");
            writeDepTable(w, others, "dep-other");
        }
        w.write("</section>\n");
    }

    private void writeDepTable(Writer w, List<Dependency> deps, String rowClass) throws IOException {
        w.write("  <table class=\"data-table\">\n");
        w.write("    <thead><tr><th>GroupId</th><th>ArtifactId</th><th>Version</th><th>Scope</th></tr></thead>\n");
        w.write("    <tbody>\n");
        for (Dependency d : deps) {
            String scope = d.getScope() != null ? d.getScope() : "compile";
            w.write("    <tr class=\"" + rowClass + "\">\n");
            w.write("      <td>" + esc(d.getGroupId()) + "</td>\n");
            w.write("      <td><strong>" + esc(d.getArtifactId()) + "</strong></td>\n");
            w.write("      <td>" + esc(d.getVersion()) + "</td>\n");
            w.write("      <td>" + esc(scope) + "</td>\n");
            w.write("    </tr>\n");
        }
        w.write("    </tbody>\n  </table>\n");
    }

    // ── HTML head / foot ─────────────────────────────────────────────────────

    private void writeHtmlHead(Writer w, String title, String cssHref, String jsHref,
                                String base, String current) throws IOException {
        w.write("<!DOCTYPE html>\n<html lang=\"en\" data-theme=\"light\">\n<head>\n");
        w.write("  <meta charset=\"UTF-8\">\n");
        w.write("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        w.write("  <title>" + esc(title) + "</title>\n");
        w.write("  <link rel=\"stylesheet\" href=\"" + cssHref + "\">\n");
        w.write("</head>\n<body>\n");
        // Inject runtime data before the JS file
        w.write("<script>\n");
        w.write("window.BW5_BASE = " + jsonStr(base) + ";\n");
        w.write("window.BW5_CURRENT = " + jsonStr(current) + ";\n");
        w.write("</script>\n");
        w.write("<script src=\"" + jsHref + "\"></script>\n");
    }

    private void writeHtmlFoot(Writer w) throws IOException {
        w.write("<div class=\"site-footer\">Generated by <strong>bw5-maven-plugin</strong> · "
            + new java.util.Date() + "</div>\n");
        w.write("</body>\n</html>\n");
    }

    // ── Asset generation ─────────────────────────────────────────────────────

    private void writeCss() throws IOException {
        File f = new File(outputDir, "bw5-site.css");
        try (Writer w = writer(f)) { w.write(buildCss()); }
    }

    private void writeJs(List<ProcessDocModel> processes) throws IOException {
        File f = new File(outputDir, "bw5-site.js");
        try (Writer w = writer(f)) {
            // Embed dynamic process list first
            w.write("// Generated by bw5-maven-plugin\n");
            w.write("window.BW5_PROCESSES = [\n");
            for (int i = 0; i < processes.size(); i++) {
                ProcessDocModel p = processes.get(i);
                w.write("  {name:" + jsonStr(p.displayName)
                    + ",full:" + jsonStr(fullDisplayName(p))
                    + ",folder:" + jsonStr(p.folderPath)
                    + ",path:" + jsonStr("processes/" + processFileName(p) + ".html") + "}");
                if (i < processes.size() - 1) w.write(",");
                w.write("\n");
            }
            w.write("];\n\n");
            // Static JS code
            w.write(STATIC_JS);
        }
    }

    // ── Cross-reference helpers ───────────────────────────────────────────────

    /** Resolve a called process path (e.g. /Project/Common/Folder/Name) to a model. */
    private ProcessDocModel resolveCalledProcess(String calledPath) {
        if (calledPath == null || calledPath.isEmpty()) return null;
        String norm = calledPath.startsWith("/") ? calledPath.substring(1) : calledPath;
        if (norm.endsWith(".process")) norm = norm.substring(0, norm.length() - 8);
        // Try exact match against model.name (without extension)
        for (ProcessDocModel p : allProcesses) {
            if (p.name == null) continue;
            String pn = p.name.endsWith(".process") ? p.name.substring(0, p.name.length() - 8) : p.name;
            if (pn.equals(norm)) return p;
        }
        // Strip first path component (project name) from calledPath and retry
        int slash = norm.indexOf('/');
        if (slash > 0) {
            String withoutFirst = norm.substring(slash + 1);
            for (ProcessDocModel p : allProcesses) {
                if (p.name == null) continue;
                String pn = p.name.endsWith(".process") ? p.name.substring(0, p.name.length() - 8) : p.name;
                if (pn.equals(withoutFirst) || pn.endsWith("/" + withoutFirst)) return p;
            }
        }
        // Last-resort: match by display name (last segment)
        String lastName = lastSegment(calledPath);
        for (ProcessDocModel p : allProcesses) {
            if (lastName.equals(p.displayName)) return p;
        }
        return null;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /** Full display name: "Common/ARC_BA1N/ARC_BA1N_Main" */
    private String fullDisplayName(ProcessDocModel model) {
        if (model.name == null) return model.displayName;
        String n = model.name;
        return n.endsWith(".process") ? n.substring(0, n.length() - 8) : n;
    }

    /** Safe filename (no extension): "Common_ARC_BA1N_ARC_BA1N_Main" */
    private String processFileName(ProcessDocModel model) {
        return safeFileName(fullDisplayName(model));
    }

    private String lastSegment(String path) {
        if (path == null) return "";
        int s = path.lastIndexOf('/');
        return s >= 0 ? path.substring(s + 1) : path;
    }

    private String safeFileName(String name) {
        return name != null ? name.replaceAll("[^a-zA-Z0-9_\\-]", "_") : "process";
    }

    private String srFileName(SharedResourceModel sr) {
        return safeFileName(sr.name != null ? sr.name : sr.displayName);
    }

    private String safeId(String name) {
        return name != null ? name.replaceAll("[^a-zA-Z0-9]", "_") : "x";
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void mkdirs(File dir) throws IOException {
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Failed to create directory: " + dir);
        }
    }

    private Writer writer(File file) throws IOException {
        mkdirs(file.getParentFile());
        return new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  CSS
    // ═══════════════════════════════════════════════════════════════════════════

    private static String buildCss() {
        return
        // ── Reset & variables ────────────────────────────────────────────────
        "*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }\n"
        + ":root {\n"
        + "  --c-primary:    #0033A0;\n"
        + "  --c-primary-dk: #001F6B;\n"
        + "  --c-accent:     #00B140;\n"
        + "  --c-warn:       #F57C00;\n"
        + "  --c-error:      #D32F2F;\n"
        + "  --c-purple:     #7B1FA2;\n"
        + "  --c-bg:         #F0F2F7;\n"
        + "  --c-surface:    #FFFFFF;\n"
        + "  --c-text:       #1A1A2E;\n"
        + "  --c-text-2:     #4A5568;\n"
        + "  --c-text-3:     #718096;\n"
        + "  --c-border:     #E2E8F0;\n"
        + "  --c-topbar:     #0033A0;\n"
        + "  --c-topbar-t:   #FFFFFF;\n"
        + "  --c-sidebar:    #1E2A4A;\n"
        + "  --c-sidebar-t:  #C8D6F0;\n"
        + "  --c-sidebar-a:  #FFFFFF;\n"
        + "  --c-sidebar-h:  rgba(255,255,255,.08);\n"
        + "  --c-code:       #C62828;\n"
        + "  --radius:       8px;\n"
        + "  --shadow:       0 1px 6px rgba(0,0,0,.08), 0 0 0 1px rgba(0,0,0,.04);\n"
        + "  --topbar-h:     52px;\n"
        + "  --sidebar-w:    268px;\n"
        + "  --font:         -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;\n"
        + "  --mono:         'JetBrains Mono','Fira Code','Consolas',monospace;\n"
        + "}\n"
        + "[data-theme='dark'] {\n"
        + "  --c-bg:        #0D1117;\n"
        + "  --c-surface:   #161B27;\n"
        + "  --c-text:      #E8EDF5;\n"
        + "  --c-text-2:    #9BA3B5;\n"
        + "  --c-text-3:    #6B7280;\n"
        + "  --c-border:    #2D3748;\n"
        + "  --c-code:      #FF7979;\n"
        + "  --c-sidebar:   #0D1117;\n"
        + "  --c-sidebar-t: #C8D6F0;\n"
        + "  --shadow:      0 1px 6px rgba(0,0,0,.4);\n"
        + "}\n"

        // ── Base ─────────────────────────────────────────────────────────────
        + "html, body { height: 100%; font-family: var(--font); color: var(--c-text);"
        +   " background: var(--c-bg); font-size: 14px; line-height: 1.6; }\n"
        + "a { color: var(--c-primary); text-decoration: none; }\n"
        + "a:hover { text-decoration: underline; }\n"
        + "code { background: #F5F5F5; padding: 1px 5px; border-radius: 3px;"
        +   " font-family: var(--mono); font-size: 12px; color: var(--c-code); }\n"
        + "[data-theme='dark'] code { background: #1E2A3A; }\n"

        // ── App shell: topbar + (sidebar | main) ─────────────────────────────
        + ".topbar {\n"
        + "  position: fixed; top: 0; left: 0; right: 0; height: var(--topbar-h);\n"
        + "  background: var(--c-topbar); color: var(--c-topbar-t);\n"
        + "  display: flex; align-items: center; gap: 12px; padding: 0 16px;\n"
        + "  z-index: 200; box-shadow: 0 2px 8px rgba(0,0,50,.3);\n"
        + "}\n"
        + ".app-body {\n"
        + "  display: flex; margin-top: var(--topbar-h); height: calc(100vh - var(--topbar-h));\n"
        + "}\n"
        + ".sidebar {\n"
        + "  width: var(--sidebar-w); flex-shrink: 0;\n"
        + "  background: var(--c-sidebar); color: var(--c-sidebar-t);\n"
        + "  display: flex; flex-direction: column; overflow: hidden;\n"
        + "  position: sticky; top: var(--topbar-h); height: calc(100vh - var(--topbar-h));\n"
        + "  transition: width .2s; border-right: 1px solid rgba(255,255,255,.06);\n"
        + "}\n"
        + ".sidebar.collapsed { width: 0; }\n"
        + ".main-content { flex: 1; overflow-y: auto; }\n"
        + ".content-inner { max-width: 1100px; margin: 0 auto; padding: 28px 24px 60px; }\n"

        // ── Topbar elements ──────────────────────────────────────────────────
        + ".sidebar-toggle { background: none; border: none; color: white; font-size: 20px;"
        +   " cursor: pointer; padding: 6px; border-radius: 4px; line-height: 1; }\n"
        + ".sidebar-toggle:hover { background: rgba(255,255,255,.15); }\n"
        + ".brand { display: flex; align-items: center; gap: 12px; color: white; font-size: 15px;"
        +   " font-weight: 600; white-space: nowrap; }\n"
        + ".brand:hover { text-decoration: none; }\n"
        + ".tibco-logo { height: 20px; width: auto; flex-shrink: 0; display: block; }\n"
        + ".brand-sep { width: 1px; height: 20px; background: rgba(255,255,255,.3); flex-shrink: 0; }\n"
        + ".brand-project { font-size: 14px; font-weight: 600; opacity: .9; }\n"
        + ".search-wrap { flex: 1; max-width: 440px; position: relative; }\n"
        + ".global-search { width: 100%; padding: 7px 14px; border-radius: 6px;"
        +   " border: none; background: rgba(255,255,255,.15); color: white; font-size: 13px;"
        +   " outline: none; }\n"
        + ".global-search::placeholder { color: rgba(255,255,255,.6); }\n"
        + ".global-search:focus { background: rgba(255,255,255,.25); }\n"
        + ".search-drop { position: absolute; top: calc(100% + 4px); left: 0; right: 0;"
        +   " background: var(--c-surface); border-radius: var(--radius);"
        +   " box-shadow: var(--shadow); max-height: 320px; overflow-y: auto;"
        +   " z-index: 300; display: none; }\n"
        + ".search-drop a { display: block; padding: 8px 14px; color: var(--c-text);"
        +   " border-bottom: 1px solid var(--c-border); font-size: 13px; }\n"
        + ".search-drop a:hover { background: var(--c-bg); text-decoration: none; }\n"
        + ".sr-folder { color: var(--c-text-3); font-size: 11px; }\n"
        + ".topbar-end { display: flex; align-items: center; gap: 10px; margin-left: auto; }\n"
        + ".topbar-tool { font-size: 12px; font-family: var(--mono); background: rgba(255,255,255,.15);"
        +   " border: 1px solid rgba(255,255,255,.25); border-radius: 4px; padding: 2px 8px;"
        +   " white-space: nowrap; letter-spacing: .5px; }\n"
        + ".theme-btn { background: none; border: 1px solid rgba(255,255,255,.3);"
        +   " color: white; border-radius: 6px; padding: 4px 8px; cursor: pointer; font-size: 16px; }\n"
        + ".theme-btn:hover { background: rgba(255,255,255,.15); }\n"

        // ── Sidebar ──────────────────────────────────────────────────────────
        + ".sidebar-project { padding: 16px 14px 10px; border-bottom: 1px solid rgba(255,255,255,.08); }\n"
        + ".sidebar-proj-name { font-weight: 700; font-size: 13px; color: white; }\n"
        + ".sidebar-proj-meta { font-size: 11px; opacity: .5; margin-top: 2px; }\n"
        + ".sidebar-filter { margin: 10px 10px 6px; padding: 6px 10px; border-radius: 5px;"
        +   " border: 1px solid rgba(255,255,255,.12); background: rgba(255,255,255,.07);"
        +   " color: var(--c-sidebar-t); font-size: 12px; width: calc(100% - 20px); outline: none; }\n"
        + ".sidebar-filter::placeholder { color: rgba(255,255,255,.35); }\n"
        + ".process-nav { overflow-y: auto; flex: 1; padding-bottom: 24px; }\n"
        + ".folder-item { list-style: none; }\n"
        + ".folder-item details > summary {\n"
        + "  display: flex; align-items: center; gap: 6px; padding: 5px 10px 5px 14px;"
        +   " cursor: pointer; font-size: 12px; color: rgba(255,255,255,.55);"
        +   " letter-spacing: .3px; user-select: none; list-style: none;\n"
        + "}\n"
        + ".folder-item details > summary::-webkit-details-marker { display: none; }\n"
        + ".folder-item details > summary::before { content:'▶'; font-size:9px; opacity:.5; transition:transform .15s; display:inline-block; }\n"
        + ".folder-item details[open] > summary::before { transform:rotate(90deg); }\n"
        + ".folder-item details > summary:hover { color: white; background: var(--c-sidebar-h); }\n"
        + ".folder-icon { font-size: 13px; }\n"
        + ".folder-children { padding-left: 12px; }\n"
        + ".process-nav a.nav-link { display: block; padding: 4px 10px 4px 32px;"
        +   " font-size: 12px; color: var(--c-sidebar-t); overflow: hidden;"
        +   " text-overflow: ellipsis; white-space: nowrap; }\n"
        + ".process-nav a.nav-link:hover { background: var(--c-sidebar-h); color: white;"
        +   " text-decoration: none; }\n"
        + ".process-nav a.nav-link.active { color: white; font-weight: 600;"
        +   " background: rgba(0,177,64,.25); border-left: 3px solid var(--c-accent);"
        +   " padding-left: 29px; }\n"

        // ── Cards / Sections ─────────────────────────────────────────────────
        + ".card { background: var(--c-surface); border-radius: var(--radius);"
        +   " padding: 24px; margin-bottom: 20px; box-shadow: var(--shadow); }\n"
        + ".card h2 { font-size: 17px; color: var(--c-primary); margin-bottom: 14px;"
        +   " padding-bottom: 10px; border-bottom: 2px solid var(--c-border); display: flex;"
        +   " align-items: center; gap: 8px; }\n"
        + ".card h3 { font-size: 14px; color: var(--c-text-2); margin: 14px 0 8px; }\n"
        + ".section-header { display: flex; align-items: center; justify-content: space-between;"
        +   " margin-bottom: 14px; padding-bottom: 10px; border-bottom: 2px solid var(--c-border); }\n"
        + ".section-header h2 { margin-bottom: 0; padding-bottom: 0; border-bottom: none; }\n"
        + ".section-desc { color: var(--c-text-3); font-size: 13px; margin-bottom: 14px; }\n"
        + ".count-badge { background: var(--c-border); color: var(--c-text-2); font-size: 11px;"
        +   " font-weight: 600; border-radius: 10px; padding: 1px 8px; }\n"

        // ── Hero ─────────────────────────────────────────────────────────────
        + ".page-hero { background: linear-gradient(135deg, var(--c-primary) 0%, var(--c-primary-dk) 100%);"
        +   " color: white; border-radius: var(--radius); padding: 28px 32px; margin-bottom: 20px;"
        +   " display: flex; align-items: center; gap: 20px; }\n"
        + ".page-hero-sm { padding: 20px 24px; margin-bottom: 14px; }\n"
        + ".hero-icon { background: rgba(255,255,255,.2); border-radius: 10px; width: 54px;"
        +   " height: 54px; display: flex; align-items: center; justify-content: center;"
        +   " font-size: 18px; font-weight: 900; flex-shrink: 0; }\n"
        + ".hero-badge { margin-bottom: 4px; }\n"
        + ".hero-text h1 { font-size: 24px; font-weight: 700; }\n"
        + ".page-hero-sm .hero-text h1 { font-size: 20px; }\n"
        + ".hero-sub { opacity: .75; font-size: 13px; margin-top: 4px; }\n"
        + ".hero-desc { opacity: .9; margin-top: 8px; font-size: 14px; }\n"
        + ".mono { font-family: var(--mono); font-size: 12px !important; }\n"

        // ── Stats ────────────────────────────────────────────────────────────
        + ".stats-row { display: flex; gap: 14px; margin-bottom: 20px; flex-wrap: wrap; }\n"
        + ".stat-card { background: var(--c-surface); border-radius: var(--radius);"
        +   " padding: 16px 20px; flex: 1; min-width: 100px; box-shadow: var(--shadow);"
        +   " text-align: center; }\n"
        + ".stat-icon { font-size: 20px; margin-bottom: 4px; }\n"
        + ".stat-value { font-size: 28px; font-weight: 700; color: var(--c-primary); line-height: 1; }\n"
        + ".stat-label { font-size: 11px; color: var(--c-text-3); text-transform: uppercase;"
        +   " letter-spacing: .5px; margin-top: 4px; }\n"

        // ── Technology grid ──────────────────────────────────────────────────
        + ".tech-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px,1fr));"
        +   " gap: 12px; }\n"
        + ".tech-card { background: var(--c-bg); border-radius: 6px; padding: 14px;"
        +   " border: 1px solid var(--c-border); }\n"
        + ".tech-card-header { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }\n"
        + ".tech-emoji { font-size: 20px; }\n"
        + ".tech-name { font-weight: 700; font-size: 13px; }\n"
        + ".tech-desc { font-size: 12px; color: var(--c-text-3); margin-bottom: 8px; }\n"
        + ".tech-badges { display: flex; flex-wrap: wrap; gap: 6px; }\n"
        + ".tech-badge { background: var(--c-primary); color: white; border-radius: 10px;"
        +   " padding: 1px 8px; font-size: 11px; }\n"
        + ".tech-badge-code { background: var(--c-bg); color: var(--c-text-3); border: 1px solid var(--c-border);"
        +   " border-radius: 10px; padding: 1px 8px; font-size: 10px; font-family: var(--mono); }\n"

        // ── Tables ───────────────────────────────────────────────────────────
        + ".data-table { width: 100%; border-collapse: collapse; font-size: 13px; }\n"
        + ".data-table th { background: #EEF0FF; color: var(--c-primary); font-weight: 600;"
        +   " padding: 9px 12px; text-align: left; border-bottom: 2px solid var(--c-border);"
        +   " white-space: nowrap; }\n"
        + "[data-theme='dark'] .data-table th { background: #1A2040; }\n"
        + ".data-table td { padding: 8px 12px; border-bottom: 1px solid var(--c-border);"
        +   " vertical-align: top; }\n"
        + ".data-table tbody tr:hover { background: var(--c-bg); }\n"
        + ".data-table .num { text-align: right; color: var(--c-text-3); font-size: 12px; }\n"
        + ".data-table td.act-name { font-weight: 500; }\n"
        + ".config-cell { max-width: 380px; }\n"
        + ".config-summary { color: var(--c-text-2); font-size: 12px; }\n"
        + ".type-cell { white-space: nowrap; }\n"
        + ".call-link { color: var(--c-primary); font-weight: 500; font-size: 12px; }\n"
        + ".call-link:hover { text-decoration: underline; }\n"
        + ".unresolved { color: var(--c-text-3); font-style: italic; font-size: 12px; }\n"
        + ".table-filter { padding: 5px 10px; border: 1px solid var(--c-border); border-radius: 5px;"
        +   " font-size: 12px; background: var(--c-surface); color: var(--c-text); outline: none; }\n"
        + ".table-filter:focus { border-color: var(--c-primary); }\n"

        // ── Badges ───────────────────────────────────────────────────────────
        + ".badge { display: inline-flex; align-items: center; border-radius: 4px;"
        +   " padding: 1px 7px; font-size: 11px; font-weight: 600; white-space: nowrap; }\n"
        + ".badge-starter { background: #E3F0FF; color: var(--c-primary); }\n"
        + ".badge-type { background: #F0F4FF; color: #3730A3; }\n"
        + ".badge-lit { background: #F0FFF4; color: #15803D; }\n"
        + ".badge-cond-sm { background: #FFF7ED; color: #C2410C; }\n"
        + ".cond-always, .cond-success { background: #DCFCE7; color: #15803D; }\n"
        + ".cond-error { background: #FEE2E2; color: var(--c-error); }\n"
        + ".cond-xpath, .cond-successwithcondition { background: #FFF7ED; color: #C2410C; }\n"
        + ".cond-otherwise { background: #F3E8FF; color: #7C3AED; }\n"

        // ── Proc link in table ───────────────────────────────────────────────
        + ".proc-folder { color: var(--c-text-3); font-size: 12px; }\n"
        + ".proc-name { font-weight: 500; }\n"
        + ".proc-link { color: var(--c-text); }\n"
        + ".proc-link:hover { color: var(--c-primary); }\n"

        // ── Diagram ──────────────────────────────────────────────────────────
        + ".diagram-wrap { overflow: hidden; position: relative; background: #FAFBFF;"
        +   " border: 1px solid var(--c-border); border-radius: 6px; cursor: grab; min-height: 120px; }\n"
        + "[data-theme='dark'] .diagram-wrap { background: #10141F; }\n"
        + ".diagram-wrap:active { cursor: grabbing; }\n"
        + ".diagram-toolbar { display: flex; gap: 8px; }\n"
        + ".btn-icon { background: var(--c-bg); border: 1px solid var(--c-border); border-radius: 5px;"
        +   " padding: 4px 10px; font-size: 12px; cursor: pointer; color: var(--c-text-2); }\n"
        + ".btn-icon:hover { background: var(--c-border); }\n"
        + ".diagram-legend { display: flex; flex-wrap: wrap; gap: 14px; margin-top: 10px;"
        +   " font-size: 12px; color: var(--c-text-3); }\n"
        + ".legend-item { display: flex; align-items: center; gap: 5px; }\n"
        + ".legend-dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }\n"

        // ── Mapper widget (3-column: Source | Target | Condition) ────────────
        + ":root { --map-line:#1565c0; --map-lit-c:#6d28d9; }\n"
        + "[data-theme='dark'] { --map-line:#60a5fa; --map-lit-c:#c4b5fd; }\n"
        + ".mapping-block { margin-bottom: 28px; }\n"
        + ".mapping-act-header { font-weight: 700; font-size: 13px; padding: 6px 10px;"
        +   " background: var(--c-bg); border: 1px solid var(--c-border);"
        +   " border-bottom: none; border-radius: 6px 6px 0 0; color: var(--c-text); }\n"
        + ".mapper-3col { display: grid; grid-template-columns: 1fr 1fr 1fr;"
        +   " border: 1px solid var(--c-border); border-radius: 0 0 6px 6px;"
        +   " position: relative; font-size: 12px; overflow: hidden; }\n"
        + ".mapper-col { display: flex; flex-direction: column; }\n"
        + ".mapper-col-head { background: var(--c-bg2,#f1f5f9); padding: 5px 12px;"
        +   " font-weight: 600; font-size: 11px; text-transform: uppercase;"
        +   " letter-spacing: .04em; color: var(--c-text-2);"
        +   " border-bottom: 1px solid var(--c-border); flex-shrink: 0; }\n"
        + ".mapper-src { border-right: 1px solid var(--c-border); overflow: hidden; }\n"
        + ".mapper-tgt { border-right: 1px solid var(--c-border); overflow: hidden; }\n"
        + ".mapper-cond { position: relative; overflow: hidden; }\n"
        // Tree
        + ".mtree { padding: 4px 0; }\n"
        + ".mtree-empty { padding: 8px 12px; color: var(--c-text-2); font-style: italic; }\n"
        + ".mtree-node { display: flex; align-items: center; min-height: 26px;"
        +   " padding-top: 2px; padding-bottom: 2px; white-space: nowrap; }\n"
        + ".mtree-icon { color: var(--c-text-2); font-size: 9px;"
        +   " margin-right: 4px; flex-shrink: 0; }\n"
        + ".mtree-lbl { color: var(--c-text); overflow: hidden; text-overflow: ellipsis; }\n"
        + ".mtree-leaf > .mtree-lbl { font-weight: 700; color: #1565c0; }\n"
        + "[data-theme='dark'] .mtree-leaf > .mtree-lbl { color: #60a5fa; }\n"
        // GV nodes in the mapper source tree
        + ".mtree-gv .mtree-lbl { color: #C2410C; }\n"
        + ".mtree-gv.mtree-leaf .mtree-lbl { color: #C2410C; font-weight: 700; }\n"
        + "[data-theme='dark'] .mtree-gv .mtree-lbl { color: #fb923c; }\n"
        + ".mtree-gv-link { color: #C2410C; font-weight: 700; text-decoration: none; }\n"
        + ".mtree-gv-link:hover { text-decoration: underline; }\n"
        + "[data-theme='dark'] .mtree-gv-link { color: #fb923c; }\n"
        + ".gv-anchor-link { font-size: 13px; }\n"
        // WHEN / OTHERWISE / IF header nodes inside the TARGET tree
        + ".mtree-branch-hdr { gap: 5px; min-height: 24px; padding-top: 3px; padding-bottom: 3px; }\n"
        + ".mtree-branch-when     { border-left: 3px solid #3b82f6; background: #EFF6FF33; }\n"
        + ".mtree-branch-otherwise{ border-left: 3px solid #8b5cf6; background: #F5F3FF33; }\n"
        + ".mtree-branch-foreach  { border-left: 3px solid #22c55e; background: #F0FFF433; }\n"
        + "[data-theme='dark'] .mtree-branch-when      { background: #1e3a5f30; }\n"
        + "[data-theme='dark'] .mtree-branch-otherwise { background: #2e106530; }\n"
        + "[data-theme='dark'] .mtree-branch-foreach   { background: #0D2A1830; }\n"
        // Condition column items — absolutely positioned by JS
        + ".mapper-cond-item { position: absolute; left: 0; right: 0;"
        +   " display: flex; flex-direction: column; overflow: hidden;"
        +   " font-size: 11px; transform: translateY(-50%); }\n"
        // Each conditional alternative row inside the grouped item
        + ".cond-alt-row { display: flex; align-items: center; flex-wrap: nowrap; gap: 3px;"
        +   " padding: 1px 10px; min-height: 22px; overflow: hidden;"
        +   " white-space: nowrap; }\n"
        + ".cond-alt-row:hover { overflow: visible; z-index: 10; background: var(--c-bg);"
        +   " box-shadow: 0 2px 8px rgba(0,0,0,.12); border-radius: 4px; }\n"
        + ".cond-alt-row:hover .map-xpath,"
        +   " .cond-alt-row:hover .map-literal,"
        +   " .cond-alt-row:hover .map-cond-expr"
        +   " { overflow: visible; white-space: normal; word-break: break-all; }\n"
        + ".map-xpath { color: #1565c0; font-family: monospace; overflow: hidden;"
        +   " text-overflow: ellipsis; white-space: nowrap; min-width: 0; }\n"
        + "[data-theme='dark'] .map-xpath { color: #60a5fa; }\n"
        + ".map-literal { color: #6d28d9; font-style: italic; overflow: hidden;"
        +   " text-overflow: ellipsis; white-space: nowrap; min-width: 0; }\n"
        + "[data-theme='dark'] .map-literal { color: #c4b5fd; }\n"
        + ".map-cond-expr { color: #c2410c; font-family: monospace; font-size: 10px;"
        +   " overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }\n"
        + "[data-theme='dark'] .map-cond-expr { color: #fb923c; }\n"
        + ".map-badge { font-size: 9px; padding: 1px 5px; border-radius: 3px; font-weight: 700; }\n"
        + ".map-if { background: #FFF7ED; color: #c2410c; }\n"
        + "[data-theme='dark'] .map-if { background: #431407; color: #fb923c; }\n"
        // Condition kind keywords
        + ".map-kw { font-size: 9px; font-weight: 800; padding: 1px 6px; border-radius: 3px;"
        +   " letter-spacing: .03em; flex-shrink: 0; }\n"
        + ".map-kw-if       { background: #FFF7ED; color: #c2410c; }\n"
        + ".map-kw-when     { background: #EFF6FF; color: #1d4ed8; }\n"
        + ".map-kw-otherwise{ background: #F5F3FF; color: #6d28d9; }\n"
        + ".map-kw-foreach  { background: #F0FFF4; color: #15803d; }\n"
        + "[data-theme='dark'] .map-kw-if        { background: #431407; color: #fb923c; }\n"
        + "[data-theme='dark'] .map-kw-when      { background: #1e3a5f; color: #93c5fd; }\n"
        + "[data-theme='dark'] .map-kw-otherwise { background: #2e1065; color: #c4b5fd; }\n"
        + "[data-theme='dark'] .map-kw-foreach   { background: #0D2A18; color: #4ade80; }\n"
        + ".cond-alt-row.map-kind-if { border-left: 3px solid #f97316; padding-left: 7px; }\n"
        + ".cond-alt-row.map-kind-for-each { border-left: 3px solid #22c55e; padding-left: 7px; }\n"
        // Branch items: condition expressions aligned to virtual branch nodes in TARGET tree
        + ".mapper-branch-item { position: absolute; left: 0; right: 0; display: flex;"
        +   " align-items: center; gap: 4px; padding: 2px 10px; font-size: 11px;"
        +   " transform: translateY(-50%); overflow: hidden; white-space: nowrap; }\n"
        + ".mapper-branch-item:hover { overflow: visible; z-index: 10; background: var(--c-bg);"
        +   " box-shadow: 0 2px 8px rgba(0,0,0,.12); border-radius: 4px; }\n"
        + ".mapper-branch-item:hover .map-cond-expr { overflow: visible; white-space: normal;"
        +   " word-break: break-all; }\n"
        // SVG line overlay
        + ".mapper-svg { position: absolute; top: 0; left: 0; width: 100%; height: 100%;"
        +   " pointer-events: none; z-index: 2; overflow: visible; }\n"
        + ".xpath { color: #166534; }\n"
        + "[data-theme='dark'] .xpath { color: #86EFAC; }\n"
        + ".literal-val { color: #7C3AED; font-style: italic; }\n"

        // ── Detail table ─────────────────────────────────────────────────────
        + ".detail-table { border-collapse: collapse; width: 100%; }\n"
        + ".detail-table th { width: 130px; background: var(--c-bg); padding: 8px 12px;"
        +   " text-align: left; font-weight: 600; border-right: 1px solid var(--c-border);"
        +   " border-bottom: 1px solid var(--c-border); vertical-align: top; }\n"
        + ".detail-table td { padding: 8px 12px; border-bottom: 1px solid var(--c-border); }\n"

        // ── Breadcrumb ───────────────────────────────────────────────────────
        + ".breadcrumb { font-size: 13px; color: var(--c-text-3); margin-bottom: 12px;"
        +   " display: flex; align-items: center; flex-wrap: wrap; gap: 4px; }\n"
        + ".breadcrumb a { color: var(--c-primary); }\n"
        + ".bc-sep { color: var(--c-text-3); }\n"
        + ".bc-seg { color: var(--c-text-2); }\n"

        // ── Prev/Next ────────────────────────────────────────────────────────
        + ".prev-next { display: flex; justify-content: space-between; margin-bottom: 16px; }\n"
        + ".pn-prev, .pn-next { font-size: 12px; color: var(--c-text-3); padding: 5px 10px;"
        +   " border: 1px solid var(--c-border); border-radius: 5px; }\n"
        + ".pn-prev:hover, .pn-next:hover { background: var(--c-border); text-decoration: none; }\n"

        // ── Called-by list ───────────────────────────────────────────────────
        + ".caller-list { list-style: none; display: flex; flex-direction: column; gap: 6px; }\n"
        + ".caller-list a { color: var(--c-primary); font-size: 13px; }\n"

        // ── Transition visual table ──────────────────────────────────────────
        + ".tr-visual-table .tr-flow-cell { padding: 6px 10px; white-space: nowrap; }\n"
        + ".tr-visual-table .tr-cond-cell { padding: 6px 12px; vertical-align: middle; min-width: 120px; }\n"
        + ".tr-cond-expr { font-size: 11px; color: var(--c-text-2); display: block; margin-top: 4px;"
        +   " white-space: normal; word-break: break-word; max-width: 280px; }\n"

        // ── Dependencies ─────────────────────────────────────────────────────
        + ".dep-subsection { font-size: 13px; font-weight: 600; margin: 16px 0 6px;"
        +   " color: var(--c-text-2); text-transform: uppercase; letter-spacing: .04em; }\n"
        + ".dep-subsection:first-of-type { margin-top: 4px; }\n"
        + "tr.dep-projlib td { background: #F0FFF4; }\n"
        + "[data-theme='dark'] tr.dep-projlib td { background: #0D2A18; }\n"
        + "tr.dep-jar td { background: #EFF6FF; }\n"
        + "[data-theme='dark'] tr.dep-jar td { background: #0D1F3C; }\n"
        + "tr.dep-other td { background: #FEFCE8; }\n"
        + "[data-theme='dark'] tr.dep-other td { background: #2A2508; }\n"

        // ── Directory tree (process + GV index) ──────────────────────────────
        + ".tree-view { font-size: 13px; }\n"
        + ".tree-search { padding: 5px 10px; border: 1px solid var(--c-border); border-radius: 5px;"
        +   " font-size: 12px; background: var(--c-surface); color: var(--c-text); outline: none;"
        +   " min-width: 180px; }\n"
        + ".tree-search:focus { border-color: var(--c-primary); }\n"
        + ".tree-folder { margin: 1px 0; }\n"
        + ".tree-folder-summary { display: flex; align-items: center; gap: 6px; padding: 5px 8px;"
        +   " cursor: pointer; border-radius: 5px; user-select: none; list-style: none;"
        +   " color: var(--c-text); font-weight: 600; }\n"
        + ".tree-folder-summary::-webkit-details-marker { display: none; }\n"
        + ".tree-folder-summary:hover { background: var(--c-bg); }\n"
        + ".tree-folder > summary::before { content: none; }\n"
        + ".tree-folder[open] > .tree-folder-summary { color: var(--c-primary); }\n"
        + ".tree-folder-icon { font-size: 15px; flex-shrink: 0; }\n"
        + ".tree-folder-name { flex: 1; }\n"
        + ".tree-folder-count { font-size: 11px; font-weight: 400; color: var(--c-text-3);"
        +   " background: var(--c-border); border-radius: 8px; padding: 1px 7px; }\n"
        + ".tree-folder > details, .tree-folder > .tree-item { margin-left: 20px; }\n"
        // Process leaf
        + ".tree-item { display: flex; align-items: center; gap: 7px; padding: 4px 8px;"
        +   " border-radius: 5px; margin: 1px 0; }\n"
        + ".tree-item:hover { background: var(--c-bg); }\n"
        + ".tree-item-icon { font-size: 14px; flex-shrink: 0; }\n"
        + ".tree-item-link { font-weight: 500; color: var(--c-text); flex: 1; overflow: hidden;"
        +   " text-overflow: ellipsis; white-space: nowrap; }\n"
        + ".tree-item-link:hover { color: var(--c-primary); text-decoration: underline; }\n"
        + ".tree-item-meta { font-size: 11px; color: var(--c-text-3); white-space: nowrap; }\n"
        // GV leaf
        + ".tree-item-name { font-weight: 600; color: var(--c-text); flex-shrink: 0; min-width: 120px; }\n"
        + ".gv-type-badge { flex-shrink: 0; }\n"
        + ".gv-val { color: var(--c-text-2); font-family: var(--mono); font-size: 12px;"
        +   " overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }\n"
        + ".tree-item[hidden] { display: none; }\n"
        + ".tree-folder[data-hidden] { display: none; }\n"

        // ── Config table (activity detail) ───────────────────────────────────
        + ".config-table { border-collapse: collapse; width: 100%; font-size: 12px; }\n"
        + ".config-table .cfg-key { font-weight: 600; color: var(--c-text-2); padding: 3px 10px 3px 0;"
        +   " white-space: nowrap; vertical-align: top; min-width: 120px; }\n"
        + ".config-table .cfg-val { padding: 3px 0; word-break: break-word; }\n"
        + ".config-table tr:not(:last-child) td { border-bottom: 1px solid var(--c-border); }\n"
        + ".gv-ref { background: #FFF7ED; color: #C2410C; border-radius: 3px; padding: 1px 5px;"
        +   " font-family: var(--mono); font-size: 11px; cursor: help; }\n"
        + "[data-theme='dark'] .gv-ref { background: #431407; color: #fb923c; }\n"
        + ".sr-link { color: var(--c-primary); font-weight: 500; }\n"
        + ".sr-link:hover { text-decoration: underline; }\n"

        // ── Global variable list ──────────────────────────────────────────────
        + ".gv-name code { font-size: 12px; }\n"
        + ".gv-file { color: var(--c-text-3); font-size: 12px; font-family: var(--mono); }\n"
        + ".gv-pass { color: var(--c-text-3); font-style: italic; font-size: 12px; }\n"

        // ── Prominent description card ────────────────────────────────────────
        + ".desc-card { background: #F0F7FF; border-left: 4px solid var(--c-primary);"
        +   " border-radius: 0 var(--radius) var(--radius) 0; padding: 14px 20px;"
        +   " margin-bottom: 16px; }\n"
        + "[data-theme='dark'] .desc-card { background: #0D1F3C; }\n"
        + ".desc-label { font-size: 11px; font-weight: 700; text-transform: uppercase;"
        +   " letter-spacing: .05em; color: var(--c-primary); margin-bottom: 6px; }\n"
        + ".desc-text { font-size: 14px; line-height: 1.7; color: var(--c-text); }\n"

        // ── Footer ───────────────────────────────────────────────────────────
        + ".site-footer { text-align: center; padding: 24px; color: var(--c-text-3); font-size: 12px; }\n"

        // ── Responsive ──────────────────────────────────────────────────────
        + "@media (max-width: 800px) {\n"
        + "  .sidebar { position: fixed; top: var(--topbar-h); bottom: 0; left: 0;"
        +   " z-index: 150; transform: translateX(-100%); transition: transform .2s; }\n"
        + "  .sidebar.open { transform: translateX(0); }\n"
        + "  .topbar-project { display: none; }\n"
        + "  .content-inner { padding: 16px; }\n"
        + "}\n"

        // ── Transitions ─────────────────────────────────────────────────────
        + "body, .card, .topbar, .sidebar { transition: background .2s, color .2s; }\n";
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  JavaScript (static — appended after the dynamic process list)
    // ═══════════════════════════════════════════════════════════════════════════

    private static final String STATIC_JS =
        "(function(){\n"
        + "'use strict';\n"

        // ── Sidebar ──────────────────────────────────────────────────────────
        + "function buildNav(){\n"
        + "  var nav=document.getElementById('processNav'); if(!nav) return;\n"
        + "  var base=window.BW5_BASE||'';\n"
        + "  var cur=window.BW5_CURRENT||'';\n"
        + "  var procs=window.BW5_PROCESSES||[];\n"
        + "  // Build a recursive tree: each node = {_procs:[], _children:{name:node}}\n"
        + "  var tree={_procs:[],_children:{}};\n"
        + "  procs.forEach(function(p){\n"
        + "    var parts=p.folder?p.folder.split('/'):[]; var node=tree;\n"
        + "    parts.forEach(function(seg){\n"
        + "      if(!node._children[seg]) node._children[seg]={_procs:[],_children:{}};\n"
        + "      node=node._children[seg];\n"
        + "    });\n"
        + "    node._procs.push(p);\n"
        + "  });\n"
        + "  function hasActive(node){\n"
        + "    for(var i=0;i<node._procs.length;i++){\n"
        + "      var p=node._procs[i]; if(p.path===cur||base+p.path===cur) return true;\n"
        + "    }\n"
        + "    return Object.keys(node._children).some(function(k){ return hasActive(node._children[k]); });\n"
        + "  }\n"
        + "  function renderNode(node,container){\n"
        + "    node._procs.forEach(function(p){ container.appendChild(makeLink(p,base,cur)); });\n"
        + "    Object.keys(node._children).sort().forEach(function(fname){\n"
        + "      var child=node._children[fname];\n"
        + "      var det=document.createElement('details');\n"
        + "      if(hasActive(child)) det.open=true;\n"
        + "      var sum=document.createElement('summary');\n"
        + "      sum.innerHTML='<span class=\"folder-icon\">📁</span>'+escHtml(fname);\n"
        + "      det.appendChild(sum);\n"
        + "      var inner=document.createElement('div'); inner.className='folder-children';\n"
        + "      renderNode(child,inner);\n"
        + "      det.appendChild(inner);\n"
        + "      var li=document.createElement('div'); li.className='folder-item';\n"
        + "      li.appendChild(det); container.appendChild(li);\n"
        + "    });\n"
        + "  }\n"
        + "  renderNode(tree,nav);\n"
        + "}\n"
        + "function makeLink(p,base,cur){\n"
        + "  var a=document.createElement('a');\n"
        + "  a.href=base+p.path; a.className='nav-link';\n"
        + "  if(p.path===cur||base+p.path===cur) a.classList.add('active');\n"
        + "  a.textContent=p.name; a.title=p.full;\n"
        + "  return a;\n"
        + "}\n"

        // ── Sidebar filter ───────────────────────────────────────────────────
        + "function initSidebarFilter(){\n"
        + "  var inp=document.getElementById('sidebarFilter'); if(!inp) return;\n"
        + "  inp.addEventListener('input',function(){\n"
        + "    var q=inp.value.toLowerCase().trim();\n"
        + "    var links=document.querySelectorAll('#processNav .nav-link');\n"
        + "    links.forEach(function(a){\n"
        + "      var show=!q||a.title.toLowerCase().includes(q)||a.textContent.toLowerCase().includes(q);\n"
        + "      a.style.display=show?'':'none';\n"
        + "    });\n"
        + "    document.querySelectorAll('#processNav details').forEach(function(d){\n"
        + "      var hasVisible=[].slice.call(d.querySelectorAll('.nav-link'))\n"
        + "        .some(function(a){ return a.style.display!=='none'; });\n"
        + "      d.style.display=hasVisible?'':'none';\n"
        + "      if(q&&hasVisible) d.open=true;\n"
        + "    });\n"
        + "  });\n"
        + "}\n"

        // ── Global search ─────────────────────────────────────────────────────
        + "function initGlobalSearch(){\n"
        + "  var inp=document.getElementById('globalSearch');\n"
        + "  var drop=document.getElementById('searchDrop');\n"
        + "  if(!inp||!drop) return;\n"
        + "  inp.addEventListener('input',function(){\n"
        + "    var q=inp.value.trim().toLowerCase(); if(!q){drop.style.display='none';return;}\n"
        + "    var base=window.BW5_BASE||'';\n"
        + "    var hits=(window.BW5_PROCESSES||[]).filter(function(p){\n"
        + "      return p.full.toLowerCase().includes(q);\n"
        + "    }).slice(0,10);\n"
        + "    drop.innerHTML=hits.map(function(p){\n"
        + "      var folder=p.folder?'<span class=\"sr-folder\">'+escHtml(p.folder)+'/ </span>':'';\n"
        + "      return '<a href=\"'+base+p.path+'\">'+ folder +escHtml(p.name)+'</a>';\n"
        + "    }).join('');\n"
        + "    drop.style.display=hits.length?'block':'none';\n"
        + "  });\n"
        + "  document.addEventListener('click',function(e){\n"
        + "    if(!drop.contains(e.target)&&e.target!==inp) drop.style.display='none';\n"
        + "  });\n"
        + "  document.addEventListener('keydown',function(e){\n"
        + "    if(e.key==='/'&&document.activeElement.tagName!=='INPUT'&&document.activeElement.tagName!=='TEXTAREA'){\n"
        + "      e.preventDefault(); inp.focus(); inp.select();\n"
        + "    }\n"
        + "    if(e.key==='Escape'){ inp.blur(); drop.style.display='none'; }\n"
        + "  });\n"
        + "}\n"

        // ── SVG diagram zoom/pan ──────────────────────────────────────────────
        + "function initDiagrams(){\n"
        + "  document.querySelectorAll('.diagram-wrap').forEach(function(wrap){\n"
        + "    var svg=wrap.querySelector('svg'); if(!svg) return;\n"
        + "    var g=document.createElementNS('http://www.w3.org/2000/svg','g');\n"
        + "    while(svg.firstChild) g.appendChild(svg.firstChild);\n"
        + "    svg.appendChild(g);\n"
        + "    var sc=1,ox=0,oy=0,drag=false,lx=0,ly=0;\n"
        + "    function apply(){ g.setAttribute('transform','translate('+ox+','+oy+') scale('+sc+')'); }\n"
        + "    svg.addEventListener('wheel',function(e){\n"
        + "      e.preventDefault();\n"
        + "      var r=svg.getBoundingClientRect();\n"
        + "      var mx=e.clientX-r.left, my=e.clientY-r.top;\n"
        + "      var f=e.deltaY<0?1.12:0.89;\n"
        + "      var nsc=Math.min(5,Math.max(0.15,sc*f));\n"
        + "      ox=mx-(mx-ox)*(nsc/sc); oy=my-(my-oy)*(nsc/sc); sc=nsc; apply();\n"
        + "    },{passive:false});\n"
        + "    svg.addEventListener('mousedown',function(e){ drag=true;lx=e.clientX;ly=e.clientY;e.preventDefault(); });\n"
        + "    document.addEventListener('mousemove',function(e){\n"
        + "      if(!drag) return;\n"
        + "      ox+=e.clientX-lx; oy+=e.clientY-ly; lx=e.clientX; ly=e.clientY; apply();\n"
        + "    });\n"
        + "    document.addEventListener('mouseup',function(){ drag=false; });\n"
        + "    wrap._reset=function(){ sc=1;ox=0;oy=0;apply(); };\n"
        + "  });\n"
        + "}\n"
        + "window.resetZoom=function(btn){\n"
        + "  var wrap=btn.closest('.card').querySelector('.diagram-wrap');\n"
        + "  if(wrap&&wrap._reset) wrap._reset();\n"
        + "};\n"

        // ── Sidebar toggle ────────────────────────────────────────────────────
        + "window.toggleSidebar=function(){\n"
        + "  var s=document.getElementById('sidebar');\n"
        + "  if(s) s.classList.toggle('collapsed'); s.classList.toggle('open');\n"
        + "};\n"

        // ── Dark mode ─────────────────────────────────────────────────────────
        + "window.toggleTheme=function(){\n"
        + "  var h=document.documentElement;\n"
        + "  var t=h.getAttribute('data-theme')==='dark'?'light':'dark';\n"
        + "  h.setAttribute('data-theme',t);\n"
        + "  try{localStorage.setItem('bw5-theme',t);}catch(e){}\n"
        + "};\n"
        + "(function(){\n"
        + "  try{\n"
        + "    var t=localStorage.getItem('bw5-theme');\n"
        + "    if(t) document.documentElement.setAttribute('data-theme',t);\n"
        + "  }catch(e){}\n"
        + "})();\n"

        // ── Table filter ──────────────────────────────────────────────────────
        + "window.filterTable=function(inp,tableId){\n"
        + "  var q=inp.value.toLowerCase();\n"
        + "  var tbl=document.getElementById(tableId); if(!tbl) return;\n"
        + "  tbl.querySelectorAll('tbody tr').forEach(function(tr){\n"
        + "    tr.style.display=tr.textContent.toLowerCase().includes(q)?'':'none';\n"
        + "  });\n"
        + "};\n"

        // ── HTML escape ───────────────────────────────────────────────────────
        + "function escHtml(s){\n"
        + "  return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');\n"
        + "}\n"

        // ── Mapper: draw source→target lines and align condition items ────────
        + "function drawMapperLines(){\n"
        + "  document.querySelectorAll('.mapper-3col').forEach(function(widget){\n"
        + "    var svgEl=widget.querySelector('.mapper-svg');\n"
        + "    var condCol=widget.querySelector('.mapper-cond');\n"
        + "    var srcCol=widget.querySelector('.mapper-src');\n"
        + "    var tgtCol=widget.querySelector('.mapper-tgt');\n"
        + "    var wr=widget.getBoundingClientRect();\n"
        + "    if(!svgEl||wr.width===0||!srcCol||!tgtCol) return;\n"
        + "    var srcColR=srcCol.getBoundingClientRect().right;\n"
        // Align branch items (WHEN/OTHERWISE/IF/FOR-EACH condition expressions) with their header badges
        + "    if(condCol){\n"
        + "      widget.querySelectorAll('.mapper-tgt .mtree-branch-hdr').forEach(function(hdr){\n"
        + "        var bid=hdr.getAttribute('data-branch-id'); if(!bid) return;\n"
        + "        var item=condCol.querySelector('.mapper-branch-item[data-branch-id=\"'+bid+'\"]');\n"
        + "        if(!item) return;\n"
        + "        var br=hdr.getBoundingClientRect();\n"
        + "        var cr=condCol.getBoundingClientRect();\n"
        + "        item.style.top=((br.top+br.bottom)/2-cr.top)+'px';\n"
        + "      });\n"
        + "    }\n"
        + "    var paths=[];\n"
        + "    widget.querySelectorAll('.mapper-tgt .mtree-leaf').forEach(function(tgtLeaf){\n"
        + "      var midxsRaw=tgtLeaf.getAttribute('data-midxs'); if(!midxsRaw) return;\n"
        + "      var tr=tgtLeaf.getBoundingClientRect();\n"
        + "      var tgtMidY=(tr.top+tr.bottom)/2;\n"
        + "      var midxArr=midxsRaw.split(',').map(Number);\n"
        + "      if(condCol){\n"
        + "        var condItems=[].slice.call(condCol.querySelectorAll('.mapper-cond-item'));\n"
        + "        for(var ci=0;ci<condItems.length;ci++){\n"
        + "          var ciMidxs=(condItems[ci].getAttribute('data-midxs')||'').split(',').map(Number);\n"
        + "          var hits=midxArr.filter(function(m){return ciMidxs.indexOf(m)>=0;});\n"
        + "          if(hits.length>0){\n"
        + "            var cr=condCol.getBoundingClientRect();\n"
        + "            condItems[ci].style.top=(tgtMidY-cr.top)+'px';\n"
        + "            break;\n"
        + "          }\n"
        + "        }\n"
        + "      }\n"
        + "      midxArr.forEach(function(midx){\n"
        + "        var srcLeaves=[].slice.call(widget.querySelectorAll('.mapper-src .mtree-leaf'));\n"
        + "        var srcLeaf=null;\n"
        + "        for(var i=0;i<srcLeaves.length;i++){\n"
        + "          var sm=(srcLeaves[i].getAttribute('data-midxs')||'').split(',').map(Number);\n"
        + "          if(sm.indexOf(midx)>=0){srcLeaf=srcLeaves[i];break;}\n"
        + "        }\n"
        + "        if(!srcLeaf) return;\n"
        + "        var sr=srcLeaf.getBoundingClientRect();\n"
        + "        var srcLbl=srcLeaf.querySelector('.mtree-lbl');\n"
        + "        var srcLblR=srcLbl?srcLbl.getBoundingClientRect().right:sr.right;\n"
        + "        var x1=Math.round(Math.min(srcLblR,srcColR)-wr.left);\n"
        + "        var y1=Math.round((sr.top+sr.bottom)/2-wr.top);\n"
        + "        var tgtIcon=tgtLeaf.querySelector('.mtree-icon');\n"
        + "        var tgtIconL=tgtIcon?tgtIcon.getBoundingClientRect().left:tr.left;\n"
        + "        var x2=Math.round(tgtIconL-wr.left);\n"
        + "        var y2=Math.round(tgtMidY-wr.top);\n"
        + "        var cx=Math.round((x1+x2)/2);\n"
        + "        paths.push('<path d=\"M'+x1+','+y1+' C'+cx+','+y1+' '+cx+','+y2+' '+x2+','+y2+'\"'"
        + "          +' fill=\"none\" stroke=\"#1565c0\" stroke-width=\"1.5\" opacity=\"0.7\"/>');\n"
        + "        paths.push('<circle cx=\"'+x1+'\" cy=\"'+y1+'\" r=\"3\" fill=\"#1565c0\"/>');\n"
        + "        paths.push('<circle cx=\"'+x2+'\" cy=\"'+y2+'\" r=\"3\" fill=\"#1565c0\"/>');\n"
        + "      });\n"
        + "    });\n"
        + "    svgEl.setAttribute('viewBox','0 0 '+Math.round(wr.width)+' '+Math.round(wr.height));\n"
        + "    svgEl.innerHTML=paths.join('');\n"
        + "  });\n"
        + "}\n"

        // ── Directory tree filter ─────────────────────────────────────────────
        + "window.filterTree=function(inp,treeId){\n"
        + "  var q=inp.value.trim().toLowerCase();\n"
        + "  var tree=document.getElementById(treeId); if(!tree) return;\n"
        + "  // Show/hide leaves\n"
        + "  tree.querySelectorAll('.tree-item').forEach(function(item){\n"
        + "    var lbl=item.getAttribute('data-label')||'';\n"
        + "    if(!q||lbl.includes(q)) item.removeAttribute('hidden');\n"
        + "    else item.setAttribute('hidden','');\n"
        + "  });\n"
        + "  // Show/hide folders: keep folder if it has any visible child\n"
        + "  function updateFolder(folder){\n"
        + "    var hasSub=false;\n"
        + "    folder.querySelectorAll(':scope > .tree-item,:scope > details').forEach(function(child){\n"
        + "      if(child.classList.contains('tree-folder')){\n"
        + "        updateFolder(child);\n"
        + "        if(!child.hasAttribute('data-hidden')) hasSub=true;\n"
        + "      } else if(!child.hasAttribute('hidden')) hasSub=true;\n"
        + "    });\n"
        + "    if(!q){ folder.removeAttribute('data-hidden'); folder.open=true; return; }\n"
        + "    if(hasSub){ folder.removeAttribute('data-hidden'); folder.open=true; }\n"
        + "    else folder.setAttribute('data-hidden','');\n"
        + "  }\n"
        + "  tree.querySelectorAll(':scope > details.tree-folder').forEach(updateFolder);\n"
        + "};\n"

        // ── Init ─────────────────────────────────────────────────────────────
        + "document.addEventListener('DOMContentLoaded',function(){\n"
        + "  buildNav();\n"
        + "  initSidebarFilter();\n"
        + "  initGlobalSearch();\n"
        + "  initDiagrams();\n"
        + "  drawMapperLines();\n"
        + "  window.addEventListener('resize',drawMapperLines);\n"
        + "});\n"
        + "})();\n";
}

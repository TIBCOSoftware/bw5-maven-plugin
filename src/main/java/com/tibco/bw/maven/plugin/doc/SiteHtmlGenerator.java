package com.tibco.bw.maven.plugin.doc;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
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

    // Cross-reference data, populated in generateIndex()
    private List<ProcessDocModel> allProcesses = Collections.emptyList();
    /** processFileName → model (key = safeFileName(fullName), no extension) */
    private final Map<String, ProcessDocModel> fileToModel = new LinkedHashMap<>();
    /** model.name → HTML file path relative to outputDir root (e.g. "processes/Common_ARC_BA1N_Main.html") */
    private final Map<String, String> nameToHtml = new LinkedHashMap<>();
    /** displayName / fullName segment → list of callers (models) */
    private final Map<String, List<ProcessDocModel>> calledByMap = new LinkedHashMap<>();
    /** palette/plugin label → count of activities using it */
    private final Map<String, Integer> paletteActivityCount = new LinkedHashMap<>();
    /** palette/plugin label → set of process names using it */
    private final Map<String, Set<String>> paletteProcesses = new LinkedHashMap<>();

    // ── Palette detection table ──────────────────────────────────────────────
    // Each entry: {prefix, displayLabel, emoji, description}
    private static final String[][] PALETTES = {
        {"httppalette.",           "HTTP",             "🌐", "HTTP event sources, requests and responses"},
        {"ae.activities.JMS",      "JMS",              "📨", "JMS messaging – queues and topics"},
        {"ae.shared.JMS",          "JMS",              "📨", "JMS messaging – queues and topics"},
        {"ae.activities.JDBC",     "JDBC",             "🗄", "Database access via JDBC"},
        {"ae.shared.JDBC",         "JDBC",             "🗄", "Database access via JDBC"},
        {"ae.activities.File",     "File",             "📁", "File system read, write, copy, delete"},
        {"ae.activities.SOAP",     "SOAP / Web Svcs",  "⚙", "SOAP web service invocation and hosting"},
        {"ae.activities.XML",      "XML / XSLT",       "📄", "XML parsing, rendering and transformation"},
        {"ae.activities.JSON",     "JSON",             "📋", "JSON rendering and parsing"},
        {"ae.javapalette.",        "Java",             "☕", "Java method calls and inline Java code"},
        {"ae.activities.Mail",     "Email / SMTP",     "✉", "Email send / receive via SMTP or IMAP"},
        {"ae.activities.TCP",      "TCP",              "🔌", "TCP socket communication"},
        {"ae.shared.TCP",          "TCP",              "🔌", "TCP socket communication"},
        {"ae.activities.FTP",      "FTP",              "📤", "FTP file transfer"},
        {"ae.activities.SFTP",     "SFTP",             "🔒", "Secure FTP file transfer"},
        {"ae.rvpalette.",          "TIBCO RV",         "🔄", "TIBCO Rendezvous publish/subscribe"},
        {"ae.shared.RV",           "TIBCO RV",         "🔄", "TIBCO Rendezvous publish/subscribe"},
        {"ae.aepalette.",          "TIBCO AE / EMS",   "⚡", "TIBCO ActiveEnterprise and EMS"},
        {"ae.eventsources.",       "Business Events",  "📡", "TIBCO BusinessEvents integration"},
        {"plugin.bwmq.",           "IBM MQ",           "🏭", "IBM WebSphere MQ messaging"},
        {"plugin.cicspi.",         "IBM CICS",         "🏦", "IBM CICS Transaction Gateway"},
        {"hl7.",                   "HL7",              "🏥", "HL7 healthcare message processing"},
        {"ae.activities.Salesforce","Salesforce",      "☁", "Salesforce CRM integration"},
        {"ae.shared.Salesforce",   "Salesforce",       "☁", "Salesforce CRM integration"},
        {"ae.activities.MongoDB",  "MongoDB",          "🍃", "MongoDB NoSQL database access"},
        {"ae.shared.MongoDB",      "MongoDB",          "🍃", "MongoDB NoSQL database access"},
        {"ae.activities.Kafka",    "Apache Kafka",     "⚡", "Apache Kafka streaming"},
        {"ae.shared.Kafka",        "Apache Kafka",     "⚡", "Apache Kafka streaming"},
        {"adswift.",               "SWIFT",            "💳", "SWIFT financial messaging"},
        {"netsuite.",              "NetSuite",         "☁", "Oracle NetSuite ERP"},
        {"sharepoint.",            "SharePoint",       "📁", "Microsoft SharePoint"},
        {"ae.activities.oracle",   "Oracle EBS",       "🔶", "Oracle E-Business Suite"},
        {"ae.activities.iProcess", "TIBCO iProcess",  "📊", "TIBCO iProcess Suite integration"},
        {"form.flow.",             "iProcess Forms",  "📝", "TIBCO iProcess Forms"},
        {"ae.shared.iProcess",     "TIBCO iProcess",  "📊", "TIBCO iProcess Suite integration"},
        {"xref.",                  "Cross-Reference", "🔁", "TIBCO Cross-Reference tables"},
        {"firefly.",               "Firefly",          "🔥", "Firefly data integration"},
        {"workday.",               "Workday",          "👔", "Workday HCM/Finance integration"},
        {"twitter.",               "Twitter/X",        "🐦", "Twitter/X social API"},
        {"facebook.",              "Facebook",         "📘", "Facebook Graph API"},
        {"pdfplugin.",             "PDF",              "📑", "PDF generation and parsing"},
        {"wadlresource.",          "REST/WADL",        "🌐", "REST adapter via WADL"},
        {"ae.activities.Rest",     "REST Adapter",     "🌐", "Generic REST adapter"},
        {"ae.shared.Rest",         "REST Adapter",     "🌐", "Generic REST adapter"},
        {"bw.jrmi.",               "Java RMI",         "☕", "Java Remote Method Invocation"},
    };

    // ── Constructor ──────────────────────────────────────────────────────────

    public SiteHtmlGenerator(File outputDir, MavenProject project) {
        this.outputDir = outputDir;
        this.project = project;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void generateIndex(List<ProcessDocModel> processes) throws IOException {
        this.allProcesses = processes;
        outputDir.mkdirs();
        new File(outputDir, "processes").mkdirs();

        // Build file name map
        for (ProcessDocModel p : processes) {
            String key = processFileName(p);
            fileToModel.put(key, p);
            nameToHtml.put(p.name != null ? p.name : p.displayName, "processes/" + key + ".html");
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

        // Build palette/technology map
        for (ProcessDocModel p : processes) {
            Set<String> palettesInProcess = new LinkedHashSet<>();
            for (ProcessDocModel.Activity a : p.allActivities()) {
                String label = detectPalette(a.resourceType);
                if (label != null) palettesInProcess.add(label);
            }
            for (String label : palettesInProcess) {
                paletteActivityCount.merge(label, 1, Integer::sum);
                paletteProcesses.computeIfAbsent(label, k -> new LinkedHashSet<>()).add(p.displayName);
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
        new File(outputDir, "processes").mkdirs();
        String fileName = processFileName(model) + ".html";
        File pageFile = new File(new File(outputDir, "processes"), fileName);
        try (Writer w = writer(pageFile)) {
            writeProcessPage(w, model);
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
        w.write("<div class=\"stats-row\">\n");
        writeStat(w, String.valueOf(processes.size()), "Processes", "📄");
        writeStat(w, String.valueOf(starterCount), "Event Sources", "▶");
        writeStat(w, String.valueOf(totalActivities), "Activities", "⚙");
        writeStat(w, String.valueOf(totalTransitions), "Transitions", "→");
        writeStat(w, String.valueOf(paletteActivityCount.size()), "Technologies", "🔧");
        w.write("</div>\n");

        // ── Technology map ───────────────────────────────────────────────────
        if (!paletteActivityCount.isEmpty()) {
            w.write("<section class=\"card\">\n");
            w.write("  <h2>Technology Map</h2>\n");
            w.write("  <p class=\"section-desc\">Palettes and external adapters detected across all processes.</p>\n");
            w.write("  <div class=\"tech-grid\">\n");

            // Sort by process count desc
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(paletteActivityCount.entrySet());
            sorted.sort((a, b) -> b.getValue() - a.getValue());
            for (Map.Entry<String, Integer> e : sorted) {
                String label = e.getKey();
                int count = e.getValue();
                String[] info = paletteInfo(label);
                String emoji = info[0];
                String desc = info[1];
                int procCount = paletteProcesses.getOrDefault(label, Collections.emptySet()).size();
                w.write("    <div class=\"tech-card\">\n");
                w.write("      <div class=\"tech-card-header\">\n");
                w.write("        <span class=\"tech-emoji\">" + emoji + "</span>\n");
                w.write("        <span class=\"tech-name\">" + esc(label) + "</span>\n");
                w.write("      </div>\n");
                w.write("      <p class=\"tech-desc\">" + esc(desc) + "</p>\n");
                w.write("      <div class=\"tech-badges\">\n");
                w.write("        <span class=\"tech-badge\">" + procCount + " process" + (procCount != 1 ? "es" : "") + "</span>\n");
                w.write("      </div>\n");
                w.write("    </div>\n");
            }
            w.write("  </div>\n</section>\n");
        }

        // ── Process list ─────────────────────────────────────────────────────
        w.write("<section class=\"card\">\n");
        w.write("  <div class=\"section-header\">\n");
        w.write("    <h2>Processes</h2>\n");
        w.write("    <input class=\"table-filter\" id=\"procFilter\" placeholder=\"Filter…\" "
            + "oninput=\"filterTable(this,'procTable')\">\n");
        w.write("  </div>\n");
        w.write("  <table class=\"data-table\" id=\"procTable\">\n");
        w.write("    <thead><tr>\n");
        w.write("      <th>Full Path</th><th>Starter Type</th>"
            + "<th class=\"num\">Acts</th><th class=\"num\">Transitions</th>\n");
        w.write("    </tr></thead>\n<tbody>\n");
        for (ProcessDocModel proc : processes) {
            String href = "processes/" + processFileName(proc) + ".html";
            String fullPath = fullDisplayName(proc);
            String starterType = proc.starter != null
                ? "<span class=\"badge badge-starter\">" + esc(proc.starter.shortType()) + "</span>" : "—";
            w.write("    <tr>\n");
            w.write("      <td><a href=\"" + href + "\" class=\"proc-link\">"
                + "<span class=\"proc-folder\">" + esc(proc.folderPath) + (proc.folderPath.isEmpty() ? "" : " / ") + "</span>"
                + "<span class=\"proc-name\">" + esc(proc.displayName) + "</span></a></td>\n");
            w.write("      <td>" + starterType + "</td>\n");
            w.write("      <td class=\"num\">" + proc.activities.size() + "</td>\n");
            w.write("      <td class=\"num\">" + proc.transitions.size() + "</td>\n");
            w.write("    </tr>\n");
        }
        w.write("  </tbody></table>\n</section>\n");

        // ── Maven dependencies ────────────────────────────────────────────────
        writeDependenciesSection(w);

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
            w.write("    <thead><tr><th>#</th><th>Name</th><th>Type</th><th>Configuration / Link</th></tr></thead>\n");
            w.write("    <tbody>\n");
            int i = 1;
            for (ProcessDocModel.Activity a : acts) {
                String typeLabel = "<span class=\"badge badge-type\">" + esc(a.shortType()) + "</span>";
                String nameCell = esc(a.name);
                String configCell;

                // CallProcessActivity: add a link to the called process
                if (a.calledProcessPath != null) {
                    ProcessDocModel target = resolveCalledProcess(a.calledProcessPath);
                    if (target != null) {
                        String href = "../" + nameToHtml.getOrDefault(target.name, "#");
                        configCell = "<a class=\"call-link\" href=\"" + href + "\">→ "
                            + esc(fullDisplayName(target)) + "</a>";
                    } else {
                        // Unresolved: show path as text
                        configCell = "<span class=\"unresolved\">→ " + esc(lastSegment(a.calledProcessPath)) + "</span>";
                    }
                } else {
                    configCell = "<span class=\"config-summary\">"
                        + esc(a.configSummary != null ? a.configSummary : "") + "</span>";
                }

                w.write("    <tr>\n");
                w.write("      <td class=\"num\">" + i++ + "</td>\n");
                w.write("      <td class=\"act-name\">" + nameCell + "</td>\n");
                w.write("      <td>" + typeLabel + "</td>\n");
                w.write("      <td class=\"config-cell\">" + configCell + "</td>\n");
                w.write("    </tr>\n");
            }
            w.write("    </tbody>\n  </table>\n</section>\n");
        }

        // ── Transitions ───────────────────────────────────────────────────────
        if (!model.transitions.isEmpty()) {
            w.write("<section class=\"card\">\n");
            w.write("  <h2>Transitions <span class=\"count-badge\">" + model.transitions.size() + "</span></h2>\n");
            w.write("  <table class=\"data-table\">\n");
            w.write("    <thead><tr><th>From</th><th>To</th><th>Type</th><th>Condition</th></tr></thead>\n");
            w.write("    <tbody>\n");
            for (ProcessDocModel.Transition tr : model.transitions) {
                String ct = tr.conditionType != null ? tr.conditionType.toLowerCase() : "always";
                w.write("    <tr class=\"tr-" + esc(ct) + "\">\n");
                w.write("      <td class=\"mono\">" + esc(tr.from) + "</td>\n");
                w.write("      <td class=\"mono\">" + esc(tr.to) + "</td>\n");
                w.write("      <td><span class=\"badge cond-" + esc(ct) + "\">"
                    + esc(tr.conditionType != null ? tr.conditionType : "always") + "</span></td>\n");
                w.write("      <td>" + (tr.condition != null
                    ? "<code>" + esc(tr.condition) + "</code>" : "") + "</td>\n");
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

        w.write("</div>\n</main>\n</div>\n");
        writeHtmlFoot(w);
    }

    // ── Shared HTML fragments ─────────────────────────────────────────────────

    private void writeTopbar(Writer w, String indexHref) throws IOException {
        w.write("<header class=\"topbar\">\n");
        w.write("  <button class=\"sidebar-toggle\" onclick=\"toggleSidebar()\" title=\"Toggle sidebar\">☰</button>\n");
        w.write("  <a href=\"" + indexHref + "\" class=\"brand\">\n");
        w.write("    <span class=\"brand-logo\">BW</span>\n");
        w.write("    <span class=\"brand-text\"><strong>5</strong> Documentation</span>\n");
        w.write("  </a>\n");
        w.write("  <div class=\"search-wrap\">\n");
        w.write("    <input id=\"globalSearch\" class=\"global-search\" type=\"search\" "
            + "placeholder=\"Search processes… (/)\" autocomplete=\"off\">\n");
        w.write("    <div id=\"searchDrop\" class=\"search-drop\"></div>\n");
        w.write("  </div>\n");
        w.write("  <div class=\"topbar-end\">\n");
        w.write("    <span class=\"topbar-project\">" + esc(project.getArtifactId()) + "</span>\n");
        w.write("    <button class=\"btn-icon theme-btn\" onclick=\"toggleTheme()\" title=\"Toggle dark mode\">🌙</button>\n");
        w.write("  </div>\n");
        w.write("</header>\n");
    }

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

    private void writeActivityDetailTable(Writer w, ProcessDocModel.Activity a,
                                           String calledHref) throws IOException {
        w.write("<table class=\"detail-table\">\n");
        w.write("  <tr><th>Name</th><td>" + esc(a.name) + "</td></tr>\n");
        w.write("  <tr><th>Type</th><td><code>" + esc(a.type) + "</code></td></tr>\n");
        if (a.resourceType != null && !a.resourceType.isEmpty()) {
            w.write("  <tr><th>Palette</th><td><span class=\"badge badge-type\">"
                + esc(a.resourceType) + "</span></td></tr>\n");
        }
        if (a.configSummary != null && !a.configSummary.isEmpty()) {
            w.write("  <tr><th>Config</th><td class=\"config-summary\">" + esc(a.configSummary) + "</td></tr>\n");
        }
        w.write("</table>\n");
    }

    private void writeDataMappings(Writer w, ProcessDocModel model) throws IOException {
        boolean has = (model.starter != null && !model.starter.inputMappings.isEmpty())
            || model.activities.stream().anyMatch(a -> !a.inputMappings.isEmpty());
        if (!has) return;

        w.write("<section class=\"card\">\n");
        w.write("  <h2>Data Mappings</h2>\n");
        w.write("  <p class=\"section-desc\">XSL input bindings for each activity.</p>\n");
        for (ProcessDocModel.Activity a : model.allActivities()) {
            if (a.inputMappings.isEmpty()) continue;
            w.write("  <div class=\"mapping-block\">\n");
            w.write("    <h3>" + esc(a.name) + "</h3>\n");
            w.write("    <table class=\"mapping-table\">\n");
            w.write("      <thead><tr><th>Target</th><th>Source / Expression</th><th>Flags</th></tr></thead>\n");
            w.write("      <tbody>\n");
            for (ProcessDocModel.FieldMapping m : a.inputMappings) {
                w.write("      <tr>\n");
                w.write("        <td><code class=\"field-target\">"
                    + esc(m.targetPath != null ? m.targetPath : m.targetField) + "</code></td>\n");
                if (m.isLiteral) {
                    w.write("        <td><span class=\"literal-val\">\"" + esc(m.sourceExpression) + "\"</span></td>\n");
                } else {
                    w.write("        <td><code class=\"xpath\">" + esc(m.sourceExpression) + "</code></td>\n");
                }
                w.write("        <td>");
                if (m.isLiteral) w.write("<span class=\"badge badge-lit\">literal</span> ");
                if (m.isConditional) w.write("<span class=\"badge badge-cond-sm\">if</span> ");
                w.write("</td>\n");
                w.write("      </tr>\n");
            }
            w.write("      </tbody>\n    </table>\n  </div>\n");
        }
        w.write("</section>\n");
    }

    private void writeDependenciesSection(Writer w) throws IOException {
        Set<Artifact> artifacts = project.getArtifacts();
        if (artifacts == null || artifacts.isEmpty()) return;
        w.write("<section class=\"card\">\n<h2>Maven Dependencies</h2>\n");
        w.write("<table class=\"data-table\">\n");
        w.write("  <thead><tr><th>GroupId</th><th>ArtifactId</th><th>Version</th><th>Type</th><th>Scope</th></tr></thead>\n");
        w.write("  <tbody>\n");
        for (Artifact a : artifacts) {
            String typeClass = "projlib".equals(a.getType()) ? " class=\"dep-projlib\"" : "";
            w.write("  <tr" + typeClass + ">\n");
            w.write("    <td>" + esc(a.getGroupId()) + "</td>\n");
            w.write("    <td><strong>" + esc(a.getArtifactId()) + "</strong></td>\n");
            w.write("    <td>" + esc(a.getVersion()) + "</td>\n");
            w.write("    <td><span class=\"badge badge-type type-" + esc(a.getType()) + "\">"
                + esc(a.getType()) + "</span></td>\n");
            w.write("    <td>" + esc(a.getScope()) + "</td>\n");
            w.write("  </tr>\n");
        }
        w.write("  </tbody>\n</table>\n</section>\n");
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

    /** Detect which palette/technology label an activity's resourceType belongs to. */
    private String detectPalette(String resourceType) {
        if (resourceType == null) return null;
        for (String[] entry : PALETTES) {
            if (resourceType.startsWith(entry[0])) return entry[1];
        }
        return null;
    }

    private String[] paletteInfo(String label) {
        for (String[] entry : PALETTES) {
            if (label.equals(entry[1])) return new String[]{entry[2], entry[3]};
        }
        return new String[]{"🔧", label};
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

    private Writer writer(File file) throws IOException {
        file.getParentFile().mkdirs();
        return new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
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
        + ".brand { display: flex; align-items: center; gap: 8px; color: white; font-size: 16px;"
        +   " font-weight: 600; white-space: nowrap; }\n"
        + ".brand:hover { text-decoration: none; }\n"
        + ".brand-logo { background: white; color: var(--c-primary); border-radius: 6px;"
        +   " font-weight: 900; font-size: 13px; padding: 3px 6px; letter-spacing: -.5px; }\n"
        + ".brand-text strong { font-size: 17px; }\n"
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
        + ".topbar-project { font-size: 12px; opacity: .7; white-space: nowrap; }\n"
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
        + ".call-link { color: var(--c-primary); font-weight: 600; }\n"
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
        + ".cond-successwithcondition { background: #FFF7ED; color: #C2410C; }\n"
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

        // ── Mappings ─────────────────────────────────────────────────────────
        + ".mapping-block { margin-bottom: 22px; }\n"
        + ".mapping-table { width: 100%; border-collapse: collapse; font-size: 12px; }\n"
        + ".mapping-table th { background: var(--c-bg); padding: 6px 10px; text-align: left;"
        +   " border-bottom: 1px solid var(--c-border); font-weight: 600; }\n"
        + ".mapping-table td { padding: 5px 10px; border-bottom: 1px solid var(--c-border);"
        +   " vertical-align: top; }\n"
        + ".field-target { color: #1D4ED8; }\n"
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

        // ── Dependencies ─────────────────────────────────────────────────────
        + "tr.dep-projlib td { background: #F0FFF4; }\n"
        + "[data-theme='dark'] tr.dep-projlib td { background: #0D2A18; }\n"

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

        // ── Init ─────────────────────────────────────────────────────────────
        + "document.addEventListener('DOMContentLoaded',function(){\n"
        + "  buildNav();\n"
        + "  initSidebarFilter();\n"
        + "  initGlobalSearch();\n"
        + "  initDiagrams();\n"
        + "});\n"
        + "})();\n";
}

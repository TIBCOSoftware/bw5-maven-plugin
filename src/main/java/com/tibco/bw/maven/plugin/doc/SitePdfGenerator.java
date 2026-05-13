package com.tibco.bw.maven.plugin.doc;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer;
import com.tibco.bw.maven.plugin.descriptor.SubstVarParser;
import org.apache.maven.project.MavenProject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generates a self-contained PDF document for a BW5 project.
 *
 * <p>The PDF mirrors the HTML site structure: cover page, table of contents,
 * project overview, one section per process, one section per shared resource.
 * SVG diagrams are embedded inline. Activated by {@code -Dbw5.site.generatePdf=true}.</p>
 */
public class SitePdfGenerator {

    private final MavenProject project;
    private final List<SharedResourceModel> sharedResources;
    private final List<SubstVarParser.GlobalVariable> globalVars;
    private final SvgDiagramGenerator svgGen = new SvgDiagramGenerator();

    private static final Pattern GV_PATTERN = Pattern.compile("%%([^%]+)%%");

    public SitePdfGenerator(MavenProject project,
                             List<SharedResourceModel> sharedResources,
                             List<SubstVarParser.GlobalVariable> globalVars) {
        this.project        = project;
        this.sharedResources = sharedResources != null ? sharedResources : Collections.emptyList();
        this.globalVars      = globalVars      != null ? globalVars      : Collections.emptyList();
    }

    public void generate(File outputFile, List<ProcessDocModel> processes) throws IOException {
        outputFile.getParentFile().mkdirs();
        String xhtml = buildXhtml(processes);
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(outputFile))) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useSVGDrawer(new BatikSVGDrawer());
            builder.withHtmlContent(xhtml, outputFile.getParentFile().toURI().toString());
            builder.toStream(os);
            builder.run();
        } catch (Exception e) {
            throw new IOException("PDF rendering failed: " + e.getMessage(), e);
        }
    }

    // ── Document assembly ─────────────────────────────────────────────────────

    private String buildXhtml(List<ProcessDocModel> processes) {
        StringBuilder sb = new StringBuilder(512 * 1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"en\">\n");
        sb.append("<head>\n");
        sb.append("  <meta charset=\"UTF-8\"/>\n");
        sb.append("  <title>").append(esc(project.getArtifactId())).append(" — BW5 Documentation</title>\n");
        sb.append("  <style>").append(buildPdfCss()).append("</style>\n");
        sb.append("</head>\n<body>\n");

        writeCoverPage(sb);
        writeToc(sb, processes);
        writeOverviewSection(sb, processes);

        for (ProcessDocModel p : processes) {
            writeProcessSection(sb, p, processes);
        }

        for (SharedResourceModel sr : sharedResources) {
            writeSrSection(sb, sr, processes);
        }

        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    // ── Cover ─────────────────────────────────────────────────────────────────

    private void writeCoverPage(StringBuilder sb) {
        sb.append("<div class=\"cover\">\n");
        sb.append("  <div class=\"cover-logo\">BW</div>\n");
        sb.append("  <h1 class=\"cover-title\">").append(esc(project.getArtifactId())).append("</h1>\n");
        sb.append("  <p class=\"cover-coords\">")
          .append(esc(project.getGroupId())).append(" &#160;&#183;&#160;")
          .append(esc(project.getVersion())).append("</p>\n");
        if (project.getDescription() != null && !project.getDescription().isEmpty()) {
            sb.append("  <p class=\"cover-desc\">").append(esc(project.getDescription())).append("</p>\n");
        }
        sb.append("  <p class=\"cover-date\">Generated ").append(esc(new Date().toString())).append("</p>\n");
        sb.append("</div>\n");
    }

    // ── TOC ──────────────────────────────────────────────────────────────────

    private void writeToc(StringBuilder sb, List<ProcessDocModel> processes) {
        sb.append("<div class=\"toc-page\">\n");
        sb.append("  <h1>Table of Contents</h1>\n");
        sb.append("  <ol class=\"toc\">\n");
        sb.append("    <li><a href=\"#overview\">Project Overview</a></li>\n");
        if (!processes.isEmpty()) {
            sb.append("    <li>Processes\n      <ol>\n");
            for (ProcessDocModel p : processes) {
                sb.append("        <li><a href=\"#proc-").append(safeId(fullName(p))).append("\">")
                  .append(esc(fullName(p))).append("</a></li>\n");
            }
            sb.append("      </ol>\n    </li>\n");
        }
        if (!sharedResources.isEmpty()) {
            sb.append("    <li>Shared Resources\n      <ol>\n");
            for (SharedResourceModel sr : sharedResources) {
                sb.append("        <li><a href=\"#sr-").append(safeId(sr.name)).append("\">")
                  .append(esc(sr.displayName)).append("</a></li>\n");
            }
            sb.append("      </ol>\n    </li>\n");
        }
        sb.append("  </ol>\n</div>\n");
    }

    // ── Overview ──────────────────────────────────────────────────────────────

    private void writeOverviewSection(StringBuilder sb, List<ProcessDocModel> processes) {
        sb.append("<div id=\"overview\" class=\"section\">\n");
        sb.append("  <h1>Project Overview</h1>\n");

        long starters    = processes.stream().filter(p -> p.starter != null).count();
        long activities  = processes.stream().mapToLong(p -> p.activities.size()).sum();
        long transitions = processes.stream().mapToLong(p -> p.transitions.size()).sum();

        sb.append("  <table class=\"stats-table\">\n");
        statRow(sb, "Processes",       String.valueOf(processes.size()));
        statRow(sb, "Event Sources",   String.valueOf(starters));
        statRow(sb, "Activities",      String.valueOf(activities));
        statRow(sb, "Transitions",     String.valueOf(transitions));
        if (!sharedResources.isEmpty()) statRow(sb, "Shared Resources", String.valueOf(sharedResources.size()));
        if (!globalVars.isEmpty())       statRow(sb, "Global Variables", String.valueOf(globalVars.size()));
        sb.append("  </table>\n");

        // Process list
        if (!processes.isEmpty()) {
            sb.append("  <h2>Processes</h2>\n");
            sb.append("  <table class=\"data-table\">\n");
            sb.append("    <thead><tr><th>Process</th><th>Event Source</th><th>Activities</th><th>Transitions</th></tr></thead>\n");
            sb.append("    <tbody>\n");
            for (ProcessDocModel p : processes) {
                String starter = p.starter != null ? p.starter.shortType() : "—";
                sb.append("    <tr>\n");
                sb.append("      <td><a href=\"#proc-").append(safeId(fullName(p))).append("\">")
                  .append(esc(fullName(p))).append("</a></td>\n");
                sb.append("      <td><span class=\"badge\">").append(esc(starter)).append("</span></td>\n");
                sb.append("      <td class=\"num\">").append(p.activities.stream().filter(a -> !a.isEnd).count()).append("</td>\n");
                sb.append("      <td class=\"num\">").append(p.transitions.size()).append("</td>\n");
                sb.append("    </tr>\n");
            }
            sb.append("    </tbody>\n  </table>\n");
        }

        // Shared Resources
        if (!sharedResources.isEmpty()) {
            sb.append("  <h2>Shared Resources</h2>\n");
            sb.append("  <table class=\"data-table\">\n");
            sb.append("    <thead><tr><th>Name</th><th>Type</th><th>Used by</th></tr></thead>\n");
            sb.append("    <tbody>\n");
            for (SharedResourceModel sr : sharedResources) {
                sb.append("    <tr><td><a href=\"#sr-").append(safeId(sr.name)).append("\">")
                  .append(esc(sr.displayName)).append("</a></td>");
                sb.append("<td><span class=\"badge\">").append(esc(sr.shortType())).append("</span></td>");
                sb.append("<td class=\"num\">").append(sr.usedBy.size()).append("</td></tr>\n");
            }
            sb.append("    </tbody>\n  </table>\n");
        }

        // Global Variables
        if (!globalVars.isEmpty()) {
            List<SubstVarParser.GlobalVariable> sorted = new ArrayList<>(globalVars);
            sorted.sort(Comparator.comparing(v -> v.name != null ? v.name : ""));
            sb.append("  <h2>Global Variables</h2>\n");
            sb.append("  <table class=\"data-table\">\n");
            sb.append("    <thead><tr><th>Name</th><th>Default Value</th><th>Type</th><th>Source</th></tr></thead>\n");
            sb.append("    <tbody>\n");
            for (SubstVarParser.GlobalVariable gv : sorted) {
                boolean isPass = "Password".equalsIgnoreCase(gv.type);
                String val  = isPass ? "[password]" : (gv.value != null ? gv.value : "");
                String type = gv.type != null && !gv.type.isEmpty() ? gv.type : "String";
                sb.append("    <tr>");
                sb.append("<td><code>").append(esc(gv.name)).append("</code></td>");
                sb.append("<td>").append(isPass ? "<em>[password]</em>" : esc(val)).append("</td>");
                sb.append("<td>").append(esc(type)).append("</td>");
                sb.append("<td class=\"small\">").append(esc(gv.substVarFile != null ? gv.substVarFile : "")).append("</td>");
                sb.append("</tr>\n");
            }
            sb.append("    </tbody>\n  </table>\n");
        }

        sb.append("</div>\n");
    }

    // ── Process section ───────────────────────────────────────────────────────

    private void writeProcessSection(StringBuilder sb, ProcessDocModel model, List<ProcessDocModel> all) {
        sb.append("<div id=\"proc-").append(safeId(fullName(model))).append("\" class=\"section\">\n");

        // Header
        sb.append("  <div class=\"proc-header\">\n");
        if (model.starter != null) {
            sb.append("    <span class=\"badge badge-starter\">").append(esc(model.starter.shortType())).append("</span> ");
        }
        sb.append("    <h1>").append(esc(model.displayName)).append("</h1>\n");
        sb.append("    <p class=\"proc-path\"><code>").append(esc(fullName(model))).append("</code></p>\n");
        sb.append("  </div>\n");

        if (model.description != null && !model.description.isEmpty()) {
            sb.append("  <div class=\"desc-card\">\n");
            sb.append("    <strong>Description:</strong> ").append(esc(model.description)).append("\n");
            sb.append("  </div>\n");
        }

        // SVG Diagram
        String svg = svgGen.generate(model);
        if (svg != null && !svg.isEmpty()) {
            sb.append("  <div class=\"diagram-wrap\">\n");
            sb.append("    <h2>Process Diagram</h2>\n");
            // Wrap in a scaling container — do NOT touch the svg element itself
            // to avoid duplicate 'style' attribute errors in strict XHTML parsing.
            sb.append("    <div class=\"svg-scaler\">").append(svg).append("</div>\n");
            sb.append("  </div>\n");
        }

        // Event Source
        if (model.starter != null) {
            sb.append("  <h2>Event Source</h2>\n");
            sb.append("  <table class=\"detail-table\">\n");
            sb.append("    <tr><th>Name</th><td>").append(esc(model.starter.name)).append("</td></tr>\n");
            sb.append("    <tr><th>Type</th><td><code>").append(esc(model.starter.type)).append("</code></td></tr>\n");
            for (Map.Entry<String, String> e : model.starter.configEntries.entrySet()) {
                sb.append("    <tr><th>").append(esc(e.getKey())).append("</th><td>")
                  .append(esc(e.getValue())).append("</td></tr>\n");
            }
            sb.append("  </table>\n");
        }

        // Activities
        List<ProcessDocModel.Activity> acts = model.activities.stream()
            .filter(a -> !a.isEnd).collect(Collectors.toList());
        if (!acts.isEmpty()) {
            sb.append("  <h2>Activities</h2>\n");
            sb.append("  <table class=\"data-table\">\n");
            sb.append("    <thead><tr><th>#</th><th>Name</th><th>Type</th></tr></thead>\n");
            sb.append("    <tbody>\n");
            int i = 1;
            for (ProcessDocModel.Activity a : acts) {
                sb.append("    <tr>");
                sb.append("<td class=\"num\">").append(i++).append("</td>");
                sb.append("<td>").append(esc(a.name)).append("</td>");
                sb.append("<td><span class=\"badge\">").append(esc(a.shortType())).append("</span>");
                if (a.calledProcessPath != null) {
                    ProcessDocModel target = resolveCalledProcess(a.calledProcessPath, all);
                    if (target != null) {
                        sb.append(" <a href=\"#proc-").append(safeId(fullName(target))).append("\">")
                          .append(esc(fullName(target))).append("</a>");
                    } else {
                        sb.append(" <em>").append(esc(lastSeg(a.calledProcessPath))).append("</em>");
                    }
                }
                sb.append("</td></tr>\n");
            }
            sb.append("    </tbody>\n  </table>\n");
        }

        // Transitions
        if (!model.transitions.isEmpty()) {
            sb.append("  <h2>Transitions</h2>\n");
            sb.append("  <table class=\"data-table\">\n");
            sb.append("    <thead><tr><th>From</th><th>To</th><th>Condition</th></tr></thead>\n");
            sb.append("    <tbody>\n");
            for (ProcessDocModel.Transition tr : model.transitions) {
                String cond = tr.conditionLabel();
                sb.append("    <tr>");
                sb.append("<td>").append(esc(tr.from)).append("</td>");
                sb.append("<td>").append(esc(tr.to)).append("</td>");
                sb.append("<td>").append(cond.isEmpty() ? "always" : "<code>" + esc(cond) + "</code>").append("</td>");
                sb.append("</tr>\n");
            }
            sb.append("    </tbody>\n  </table>\n");
        }

        // Data Mappings (simplified table — no JS widget)
        writeDataMappingsTables(sb, model);

        // Connections used
        writeConnectionsUsed(sb, model);

        // Global variables used
        writeGvUsed(sb, model);

        sb.append("</div>\n");
    }

    private void writeDataMappingsTables(StringBuilder sb, ProcessDocModel model) {
        boolean has = (model.starter != null && !model.starter.inputMappings.isEmpty())
            || model.activities.stream().anyMatch(a -> !a.inputMappings.isEmpty());
        if (!has) return;

        sb.append("  <h2>Data Mappings</h2>\n");
        for (ProcessDocModel.Activity a : model.allActivities()) {
            if (a.inputMappings.isEmpty()) continue;
            sb.append("  <p class=\"mapping-act\">").append(esc(a.name)).append("</p>\n");
            sb.append("  <table class=\"data-table\">\n");
            sb.append("    <thead><tr><th>Target Field</th><th>Source / Value</th></tr></thead>\n");
            sb.append("    <tbody>\n");
            for (ProcessDocModel.FieldMapping m : a.inputMappings) {
                String target = m.targetPath != null ? m.targetPath : (m.targetField != null ? m.targetField : "");
                String source = m.sourceExpression != null ? m.sourceExpression : "";
                sb.append("    <tr>");
                sb.append("<td><code>").append(esc(target)).append("</code></td>");
                sb.append("<td>");
                if (m.isLiteral) {
                    sb.append("<em>\"").append(esc(source)).append("\"</em>");
                } else {
                    sb.append("<code>").append(esc(source)).append("</code>");
                }
                sb.append("</td></tr>\n");
            }
            sb.append("    </tbody>\n  </table>\n");
        }
    }

    private void writeConnectionsUsed(StringBuilder sb, ProcessDocModel model) {
        Set<String> seen = new LinkedHashSet<>();
        List<SharedResourceModel> used = new ArrayList<>();
        for (ProcessDocModel.Activity a : model.allActivities()) {
            for (String ref : a.sharedResourceRefs) {
                if (!seen.add(ref)) continue;
                String refNorm = ref.startsWith("/") ? ref.substring(1) : ref;
                for (SharedResourceModel sr : sharedResources) {
                    String srNorm = sr.name.startsWith("/") ? sr.name.substring(1) : sr.name;
                    if (srNorm.equals(refNorm) || sr.displayName.equals(lastSeg(ref))) {
                        used.add(sr);
                        break;
                    }
                }
            }
        }
        if (used.isEmpty()) return;

        sb.append("  <h2>Connections Used</h2>\n");
        sb.append("  <table class=\"data-table\">\n");
        sb.append("    <thead><tr><th>Name</th><th>Type</th></tr></thead>\n");
        sb.append("    <tbody>\n");
        for (SharedResourceModel sr : used) {
            sb.append("    <tr>");
            sb.append("<td><a href=\"#sr-").append(safeId(sr.name)).append("\">").append(esc(sr.displayName)).append("</a></td>");
            sb.append("<td><span class=\"badge\">").append(esc(sr.shortType())).append("</span></td>");
            sb.append("</tr>\n");
        }
        sb.append("    </tbody>\n  </table>\n");
    }

    private void writeGvUsed(StringBuilder sb, ProcessDocModel model) {
        Map<String, SubstVarParser.GlobalVariable> found = new LinkedHashMap<>();
        for (ProcessDocModel.Activity a : model.allActivities()) {
            for (String val : a.configEntries.values()) {
                Matcher m = GV_PATTERN.matcher(val);
                while (m.find()) {
                    String gvName = m.group(1);
                    if (!found.containsKey(gvName)) {
                        SubstVarParser.GlobalVariable match = findGv(gvName);
                        found.put(gvName, match);
                    }
                }
            }
            for (ProcessDocModel.FieldMapping fm : a.inputMappings) {
                if (fm.sourceExpression != null && fm.sourceExpression.startsWith("$_globalVariables/")) {
                    String gvName = extractGvName(fm.sourceExpression);
                    if (!found.containsKey(gvName)) {
                        found.put(gvName, findGvByPath(gvName));
                    }
                }
            }
        }
        if (found.isEmpty()) return;

        sb.append("  <h2>Global Variables Used</h2>\n");
        sb.append("  <table class=\"data-table\">\n");
        sb.append("    <thead><tr><th>Name</th><th>Default Value</th><th>Type</th></tr></thead>\n");
        sb.append("    <tbody>\n");
        for (Map.Entry<String, SubstVarParser.GlobalVariable> e : found.entrySet()) {
            SubstVarParser.GlobalVariable gv = e.getValue();
            boolean isPass = gv != null && "Password".equalsIgnoreCase(gv.type);
            String val  = gv == null ? "" : isPass ? "[password]" : (gv.value != null ? gv.value : "");
            String type = gv != null && gv.type != null && !gv.type.isEmpty() ? gv.type : "";
            sb.append("    <tr>");
            sb.append("<td><code>").append(esc(e.getKey())).append("</code></td>");
            sb.append("<td>").append(isPass ? "<em>[password]</em>" : esc(val)).append("</td>");
            sb.append("<td>").append(esc(type)).append("</td>");
            sb.append("</tr>\n");
        }
        sb.append("    </tbody>\n  </table>\n");
    }

    // ── Shared Resource section ───────────────────────────────────────────────

    private void writeSrSection(StringBuilder sb, SharedResourceModel sr, List<ProcessDocModel> all) {
        sb.append("<div id=\"sr-").append(safeId(sr.name)).append("\" class=\"section\">\n");
        sb.append("  <h1>").append(esc(sr.displayName)).append("</h1>\n");
        sb.append("  <p class=\"proc-path\"><code>").append(esc(sr.name)).append("</code></p>\n");
        if (sr.type != null && !sr.type.isEmpty()) {
            sb.append("  <p><span class=\"badge\">").append(esc(sr.shortType())).append("</span></p>\n");
        }

        if (!sr.config.isEmpty()) {
            sb.append("  <h2>Configuration</h2>\n");
            sb.append("  <table class=\"detail-table\">\n");
            for (Map.Entry<String, String> e : sr.config.entrySet()) {
                boolean isPass = e.getKey().toLowerCase(java.util.Locale.ROOT).contains("password")
                    || e.getKey().toLowerCase(java.util.Locale.ROOT).contains("secret");
                sb.append("    <tr><th>").append(esc(e.getKey())).append("</th><td>");
                sb.append(isPass ? "<em>[hidden]</em>" : esc(e.getValue())).append("</td></tr>\n");
            }
            sb.append("  </table>\n");
        }

        if (!sr.usedBy.isEmpty()) {
            sb.append("  <h2>Used by</h2>\n");
            sb.append("  <ul class=\"used-by\">\n");
            for (String entry : sr.usedBy) {
                int sep = entry.lastIndexOf(" / ");
                String procPath = sep > 0 ? entry.substring(0, sep) : entry;
                String actName  = sep > 0 ? entry.substring(sep + 3) : "";
                // Try to find the process anchor
                ProcessDocModel target = null;
                for (ProcessDocModel p : all) {
                    if (fullName(p).equals(procPath)) { target = p; break; }
                }
                sb.append("    <li>");
                if (target != null) {
                    sb.append("<a href=\"#proc-").append(safeId(fullName(target))).append("\">")
                      .append(esc(procPath)).append("</a>");
                } else {
                    sb.append(esc(procPath));
                }
                if (!actName.isEmpty()) sb.append(" &#x203A; ").append(esc(actName));
                sb.append("</li>\n");
            }
            sb.append("  </ul>\n");
        }

        sb.append("</div>\n");
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String fullName(ProcessDocModel model) {
        if (model.name == null) return model.displayName;
        return model.name.endsWith(".process")
            ? model.name.substring(0, model.name.length() - 8) : model.name;
    }

    private String lastSeg(String path) {
        if (path == null) return "";
        int s = path.lastIndexOf('/');
        return s >= 0 ? path.substring(s + 1) : path;
    }

    private String safeId(String name) {
        return name != null ? name.replaceAll("[^a-zA-Z0-9]", "_") : "x";
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private ProcessDocModel resolveCalledProcess(String calledPath, List<ProcessDocModel> all) {
        if (calledPath == null || calledPath.isEmpty()) return null;
        String norm = calledPath.startsWith("/") ? calledPath.substring(1) : calledPath;
        if (norm.endsWith(".process")) norm = norm.substring(0, norm.length() - 8);
        for (ProcessDocModel p : all) {
            if (p.name == null) continue;
            String pn = p.name.endsWith(".process") ? p.name.substring(0, p.name.length() - 8) : p.name;
            if (pn.equals(norm) || pn.endsWith("/" + norm)) return p;
        }
        String last = lastSeg(calledPath);
        for (ProcessDocModel p : all) {
            if (last.equals(p.displayName)) return p;
        }
        return null;
    }

    private SubstVarParser.GlobalVariable findGv(String shortName) {
        for (SubstVarParser.GlobalVariable gv : globalVars) {
            String stored = gv.name != null ? gv.name : "";
            String last = lastSeg(stored.replace("/", "/"));
            if (last.equals(shortName) || stored.equals(shortName)) return gv;
        }
        return null;
    }

    private SubstVarParser.GlobalVariable findGvByPath(String gvPath) {
        for (SubstVarParser.GlobalVariable gv : globalVars) {
            String stored = gv.name != null ? gv.name : "";
            if (stored.equals(gvPath) || stored.endsWith("/" + gvPath)) return gv;
        }
        return null;
    }

    private static String extractGvName(String expr) {
        if (expr == null) return "";
        int idx = expr.indexOf("/ns:GlobalVariables/");
        if (idx >= 0) return expr.substring(idx + "/ns:GlobalVariables/".length());
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

    private void statRow(StringBuilder sb, String label, String value) {
        sb.append("    <tr><th>").append(esc(label)).append("</th><td class=\"stat-val\">")
          .append(esc(value)).append("</td></tr>\n");
    }

    // ── PDF CSS ───────────────────────────────────────────────────────────────

    private static String buildPdfCss() {
        return
        "@page { size: A4; margin: 18mm 20mm 18mm 20mm; }\n"
        + "@page :first { margin-top: 0; }\n"
        + "body { font-family: sans-serif; font-size: 9.5pt; color: #1A1A2E;"
        +   " line-height: 1.5; margin: 0; padding: 0; }\n"
        + "a { color: #0033A0; text-decoration: none; }\n"
        + "code { font-family: monospace; font-size: 8.5pt; color: #C62828;"
        +   " background: #F5F5F5; padding: 1px 4px; border-radius: 2px; }\n"
        + "em { color: #718096; }\n"

        // Cover
        + ".cover { page-break-after: always; background: #0033A0; color: white;"
        +   " padding: 60mm 30mm; min-height: 250mm; display: block; }\n"
        + ".cover-logo { font-size: 40pt; font-weight: 900; background: rgba(255,255,255,.2);"
        +   " display: inline-block; border-radius: 8px; padding: 6px 18px;"
        +   " margin-bottom: 20px; }\n"
        + ".cover-title { font-size: 28pt; font-weight: 700; margin: 0 0 8px; color: white; }\n"
        + ".cover-coords { font-size: 12pt; opacity: .8; margin: 0 0 24px; }\n"
        + ".cover-desc { font-size: 11pt; opacity: .9; max-width: 130mm;"
        +   " line-height: 1.7; margin-bottom: 32px; }\n"
        + ".cover-date { font-size: 9pt; opacity: .6; margin-top: 40mm; }\n"

        // TOC
        + ".toc-page { page-break-after: always; padding-top: 10mm; }\n"
        + ".toc-page h1 { font-size: 18pt; color: #0033A0; border-bottom: 2px solid #E2E8F0;"
        +   " padding-bottom: 6px; margin-bottom: 12px; }\n"
        + ".toc { margin: 0; padding-left: 20px; }\n"
        + ".toc li { margin: 4px 0; font-size: 10pt; }\n"
        + ".toc ol { margin: 4px 0; padding-left: 20px; }\n"
        + ".toc a { color: #0033A0; }\n"

        // Sections
        + ".section { page-break-before: always; padding-top: 6mm; }\n"
        + ".section h1 { font-size: 17pt; color: #0033A0; margin-bottom: 4px; }\n"
        + ".section h2 { font-size: 12pt; color: #0033A0; border-bottom: 1px solid #E2E8F0;"
        +   " padding-bottom: 4px; margin: 14px 0 8px; }\n"
        + ".section h1:first-child { border-bottom: 2px solid #0033A0; padding-bottom: 8px; }\n"

        // Process header
        + ".proc-header { margin-bottom: 10px; }\n"
        + ".proc-path { margin: 2px 0 10px; }\n"

        // Description card
        + ".desc-card { background: #F0F7FF; border-left: 4px solid #0033A0;"
        +   " padding: 8px 14px; margin-bottom: 12px; font-size: 9.5pt; border-radius: 0 4px 4px 0; }\n"

        // Diagram
        + ".diagram-wrap { margin: 10px 0; }\n"
        + ".diagram-wrap h2 { font-size: 11pt; color: #555; }\n"
        + ".svg-scaler { max-width: 170mm; overflow: hidden; }\n"
        + ".svg-scaler svg { display: block; }\n"

        // Stats table
        + ".stats-table { border-collapse: collapse; margin-bottom: 14px; }\n"
        + ".stats-table th { font-weight: 600; color: #4A5568; padding: 5px 16px 5px 0;"
        +   " text-align: left; font-size: 9.5pt; }\n"
        + ".stats-table .stat-val { font-size: 13pt; font-weight: 700; color: #0033A0;"
        +   " padding-left: 8px; }\n"

        // Data tables
        + ".data-table { width: 100%; border-collapse: collapse; font-size: 9pt; margin-bottom: 10px; }\n"
        + ".data-table th { background: #EEF0FF; color: #0033A0; font-weight: 600;"
        +   " padding: 6px 10px; text-align: left; border-bottom: 2px solid #C7D2FE;"
        +   " white-space: nowrap; }\n"
        + ".data-table td { padding: 5px 10px; border-bottom: 1px solid #E2E8F0; vertical-align: top; }\n"
        + ".data-table .num { text-align: right; color: #718096; font-size: 8.5pt; }\n"
        + ".data-table .small { font-size: 8pt; color: #718096; }\n"

        // Detail table
        + ".detail-table { border-collapse: collapse; width: 100%; margin-bottom: 10px; }\n"
        + ".detail-table th { width: 110px; background: #F7F7F7; padding: 5px 10px;"
        +   " text-align: left; font-weight: 600; border-right: 1px solid #E2E8F0;"
        +   " border-bottom: 1px solid #E2E8F0; vertical-align: top; font-size: 9pt; }\n"
        + ".detail-table td { padding: 5px 10px; border-bottom: 1px solid #E2E8F0; font-size: 9pt; }\n"

        // Badges
        + ".badge { display: inline-block; background: #EEF0FF; color: #3730A3;"
        +   " border-radius: 3px; padding: 1px 6px; font-size: 8pt; font-weight: 600; }\n"
        + ".badge-starter { background: #E3F0FF; color: #0033A0; }\n"

        // Mapping activity header
        + ".mapping-act { font-weight: 700; font-size: 9.5pt; color: #4A5568;"
        +   " margin: 10px 0 4px; border-top: 1px solid #E2E8F0; padding-top: 8px; }\n"

        // Used-by list
        + ".used-by { margin: 0; padding-left: 16px; }\n"
        + ".used-by li { margin: 3px 0; font-size: 9pt; }\n";
    }
}

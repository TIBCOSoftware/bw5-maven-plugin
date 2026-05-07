package com.tibco.bw.maven.plugin.doc;

import com.tibco.bw.maven.plugin.descriptor.SubstVarParser;
import org.apache.maven.project.MavenProject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Generates Markdown documentation alongside the HTML site.
 *
 * <p>Produces:</p>
 * <ul>
 *   <li>{@code index.md} — project overview with process list, shared resources, global variables</li>
 *   <li>{@code processes/&lt;name&gt;.md} — one file per process</li>
 * </ul>
 *
 * <p>The Markdown is plain CommonMark and renders well in GitHub, GitLab, and
 * documentation portals like Confluence or Backstage.</p>
 */
public class SiteMarkdownGenerator {

    private final File outputDir;
    private final MavenProject project;
    private final List<SharedResourceModel> sharedResources;
    private final List<SubstVarParser.GlobalVariable> globalVars;

    private List<ProcessDocModel> allProcesses = Collections.emptyList();

    public SiteMarkdownGenerator(File outputDir, MavenProject project,
                                  List<SharedResourceModel> sharedResources,
                                  List<SubstVarParser.GlobalVariable> globalVars) {
        this.outputDir = outputDir;
        this.project = project;
        this.sharedResources = sharedResources != null ? sharedResources : Collections.emptyList();
        this.globalVars = globalVars != null ? globalVars : Collections.emptyList();
    }

    public void generateIndex(List<ProcessDocModel> processes) throws IOException {
        this.allProcesses = processes;
        outputDir.mkdirs();
        new File(outputDir, "processes").mkdirs();

        File f = new File(outputDir, "index.md");
        try (Writer w = writer(f)) {
            writeIndexMd(w, processes);
        }
    }

    public void generateProcessPage(ProcessDocModel model) throws IOException {
        new File(outputDir, "processes").mkdirs();
        String fileName = mdFileName(model) + ".md";
        File f = new File(new File(outputDir, "processes"), fileName);
        try (Writer w = writer(f)) {
            writeProcessMd(w, model);
        }
    }

    // ── Index ─────────────────────────────────────────────────────────────────

    private void writeIndexMd(Writer w, List<ProcessDocModel> processes) throws IOException {
        w.write("# " + project.getArtifactId() + "\n\n");
        w.write("> " + project.getGroupId() + " · " + project.getVersion() + "\n\n");

        if (project.getDescription() != null && !project.getDescription().isEmpty()) {
            w.write(project.getDescription() + "\n\n");
        }

        // Stats
        long starterCount = processes.stream().filter(p -> p.starter != null).count();
        long totalActivities = processes.stream().mapToLong(p -> p.activities.size()).sum();
        long totalTransitions = processes.stream().mapToLong(p -> p.transitions.size()).sum();

        w.write("| Metric | Count |\n|--------|-------|\n");
        w.write("| Processes | " + processes.size() + " |\n");
        w.write("| Event Sources | " + starterCount + " |\n");
        w.write("| Activities | " + totalActivities + " |\n");
        w.write("| Transitions | " + totalTransitions + " |\n");
        if (!sharedResources.isEmpty()) w.write("| Shared Resources | " + sharedResources.size() + " |\n");
        if (!globalVars.isEmpty()) w.write("| Global Variables | " + globalVars.size() + " |\n");
        w.write("\n");

        // Process list
        w.write("## Processes\n\n");
        w.write("| Process | Starter | Activities | Transitions |\n");
        w.write("|---------|---------|:----------:|:-----------:|\n");
        for (ProcessDocModel p : processes) {
            String link = "[" + mdEsc(fullDisplayName(p)) + "](processes/" + mdFileName(p) + ".md)";
            String starter = p.starter != null ? "`" + mdEsc(p.starter.shortType()) + "`" : "—";
            w.write("| " + link + " | " + starter + " | " + p.activities.size()
                + " | " + p.transitions.size() + " |\n");
        }
        w.write("\n");

        // Shared Resources
        if (!sharedResources.isEmpty()) {
            w.write("## Shared Resources\n\n");
            w.write("| Name | Type | Used by |\n|------|------|:-------:|\n");
            for (SharedResourceModel sr : sharedResources) {
                w.write("| " + mdEsc(sr.displayName) + " | `" + mdEsc(sr.shortType()) + "` | "
                    + sr.usedBy.size() + " |\n");
            }
            w.write("\n");
        }

        // Global Variables
        if (!globalVars.isEmpty()) {
            List<SubstVarParser.GlobalVariable> sorted = new ArrayList<>(globalVars);
            sorted.sort(Comparator.comparing(v -> v.name != null ? v.name : ""));
            w.write("## Global Variables\n\n");
            w.write("| Name | Default Value | Type | Source |\n|------|--------------|------|--------|\n");
            for (SubstVarParser.GlobalVariable gv : sorted) {
                boolean isPass = "Password".equalsIgnoreCase(gv.type);
                String val = isPass ? "_(password)_" : mdEsc(gv.value != null ? gv.value : "");
                String type = gv.type != null && !gv.type.isEmpty() ? gv.type : "String";
                w.write("| `" + mdEsc(gv.name) + "` | " + val + " | " + mdEsc(type)
                    + " | " + mdEsc(gv.substVarFile != null ? gv.substVarFile : "") + " |\n");
            }
            w.write("\n");
        }

        w.write("---\n_Generated by bw5-maven-plugin · " + new Date() + "_\n");
    }

    // ── Process page ──────────────────────────────────────────────────────────

    private void writeProcessMd(Writer w, ProcessDocModel model) throws IOException {
        w.write("# " + mdEsc(model.displayName) + "\n\n");
        w.write("[← Back to index](../index.md)\n\n");

        // Full path
        w.write("> `" + mdEsc(fullDisplayName(model)) + "`\n\n");

        // Description
        if (model.description != null && !model.description.isEmpty()) {
            w.write("> **Description:** " + mdEsc(model.description) + "\n\n");
        }

        // Event source / starter
        if (model.starter != null) {
            w.write("## Event Source\n\n");
            w.write("| Field | Value |\n|-------|-------|\n");
            w.write("| Name | " + mdEsc(model.starter.name) + " |\n");
            w.write("| Type | `" + mdEsc(model.starter.type) + "` |\n");
            if (model.starter.resourceType != null && !model.starter.resourceType.isEmpty()) {
                w.write("| Palette | `" + mdEsc(model.starter.resourceType) + "` |\n");
            }
            if (!model.starter.configEntries.isEmpty()) {
                w.write("\n**Configuration:**\n\n");
                w.write("| Key | Value |\n|-----|-------|\n");
                for (Map.Entry<String, String> e : model.starter.configEntries.entrySet()) {
                    w.write("| " + mdEsc(e.getKey()) + " | " + mdEsc(e.getValue()) + " |\n");
                }
            }
            w.write("\n");
        }

        // Activities
        List<ProcessDocModel.Activity> acts = new ArrayList<>();
        for (ProcessDocModel.Activity a : model.activities) {
            if (!a.isEnd) acts.add(a);
        }
        if (!acts.isEmpty()) {
            w.write("## Activities\n\n");
            w.write("| # | Name | Type |\n|---|------|------|\n");
            int i = 1;
            for (ProcessDocModel.Activity a : acts) {
                String typeStr = "`" + mdEsc(a.shortType()) + "`";
                if (a.calledProcessPath != null) {
                    // Try to link to the called process
                    for (ProcessDocModel target : allProcesses) {
                        String tpath = target.name != null ? target.name : target.displayName;
                        if (tpath.contains(a.calledProcessPath) || a.calledProcessPath.contains(lastSegment(tpath))) {
                            typeStr += " → [" + mdEsc(fullDisplayName(target)) + "]("
                                + mdFileName(target) + ".md)";
                            break;
                        }
                    }
                }
                w.write("| " + i++ + " | " + mdEsc(a.name) + " | " + typeStr + " |\n");
            }
            w.write("\n");
        }

        // Transitions
        if (!model.transitions.isEmpty()) {
            w.write("## Transitions\n\n");
            w.write("| From | To | Condition |\n|------|----|-----------|\n");
            for (ProcessDocModel.Transition tr : model.transitions) {
                String cond = tr.conditionLabel();
                w.write("| " + mdEsc(tr.from) + " | " + mdEsc(tr.to) + " | "
                    + (cond.isEmpty() ? "always" : mdEsc(cond)) + " |\n");
            }
            w.write("\n");
        }

        w.write("---\n_Generated by bw5-maven-plugin · " + new Date() + "_\n");
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String fullDisplayName(ProcessDocModel model) {
        if (model.name == null) return model.displayName;
        String n = model.name;
        return n.endsWith(".process") ? n.substring(0, n.length() - 8) : n;
    }

    private String mdFileName(ProcessDocModel model) {
        return fullDisplayName(model).replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private String lastSegment(String path) {
        if (path == null) return "";
        int s = path.lastIndexOf('/');
        return s >= 0 ? path.substring(s + 1) : path;
    }

    private String mdEsc(String s) {
        if (s == null) return "";
        // Escape pipe (table delimiter) and backslash
        return s.replace("\\", "\\\\").replace("|", "\\|");
    }

    private Writer writer(File file) throws IOException {
        file.getParentFile().mkdirs();
        return new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
    }
}

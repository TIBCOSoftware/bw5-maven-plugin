package com.tibco.bw.maven.plugin.descriptor;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates deployment configuration files from the global variables collected
 * during EAR assembly.
 *
 * <p>Three formats are supported:</p>
 * <ul>
 *   <li><b>XML</b> ({@code *-deploy.xml}) — AppManage deployment configuration
 *       compatible with TIBCO Administrator ({@code appmanage -setDeployConfig}).</li>
 *   <li><b>Properties</b> ({@code *-deploy.properties}) — flat {@code key=value}
 *       file suitable for BW5 container deployments.</li>
 *   <li><b>values.yaml</b> — Helm values file for BW5 Platform (Kubernetes)
 *       deployments.</li>
 * </ul>
 *
 * <p>In all three formats the global variables are written in <b>alphabetical order</b>
 * by variable name to make file-to-file comparison straightforward.</p>
 */
public class DeploymentConfigGenerator {

    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

    // -----------------------------------------------------------------------
    //  XML — AppManage deployment config
    // -----------------------------------------------------------------------

    /**
     * Generates an AppManage-compatible XML deployment configuration file.
     *
     * <p>The format matches what {@code appmanage -exportConfig} produces and
     * what {@code appmanage -setDeployConfig} consumes.</p>
     *
     * @param outputFile  target file (e.g. {@code target/myapp-1.0.0-deploy.xml})
     * @param appName     application name
     * @param appVersion  application version
     * @param globalVars  global variables parsed from {@code .substvar} files
     */
    public void generateDeployXml(
            File outputFile,
            String appName,
            String appVersion,
            List<SubstVarParser.GlobalVariable> globalVars) throws IOException {

        List<SubstVarParser.GlobalVariable> sorted = sorted(globalVars);
        String date = timestamp();

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!--\n");
        sb.append("    BW5 AppManage Deployment Configuration\n");
        sb.append("    Application : ").append(appName).append("\n");
        sb.append("    Version     : ").append(appVersion).append("\n");
        sb.append("    Generated   : ").append(date).append("\n");
        sb.append("    Tool        : bw5-maven-plugin\n");
        sb.append("-->\n");
        sb.append("<applicationManagement\n");
        sb.append("    xmlns=\"http://www.tibco.com/xmlns/ApplicationManagement\"\n");
        sb.append("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n");
        sb.append("  <application name=\"").append(xmlAttr(appName)).append("\"");
        sb.append(" contact=\"\" description=\"\">\n");

        if (!sorted.isEmpty()) {
            sb.append("    <NVPairs name=\"Global Variables\">\n");
            for (SubstVarParser.GlobalVariable var : sorted) {
                sb.append("      <NameValuePair>\n");
                sb.append("        <name>").append(xmlEscape(var.name)).append("</name>\n");
                sb.append("        <value>").append(xmlEscape(var.value)).append("</value>\n");
                if (var.description != null && !var.description.isEmpty()) {
                    sb.append("        <description>").append(xmlEscape(var.description)).append("</description>\n");
                }
                sb.append("        <type>").append(xmlEscape(nvlType(var.type))).append("</type>\n");
                sb.append("        <requiresConfiguration>").append(var.requiresConfiguration).append("</requiresConfiguration>\n");
                sb.append("        <deploymentSettable>").append(var.requiresConfiguration).append("</deploymentSettable>\n");
                sb.append("      </NameValuePair>\n");
            }
            sb.append("    </NVPairs>\n");
        }

        sb.append("  </application>\n");
        sb.append("</applicationManagement>\n");

        write(outputFile, sb.toString());
    }

    // -----------------------------------------------------------------------
    //  .properties — flat key=value
    // -----------------------------------------------------------------------

    /**
     * Generates a flat {@code .properties} file with all global variables.
     *
     * <p>Variable names are used as-is (including {@code /} path separators).
     * The file is intended for BW5 container deployments where global variables
     * are injected via a properties file or environment variables.</p>
     *
     * @param outputFile  target file (e.g. {@code target/myapp-1.0.0-deploy.properties})
     * @param appName     application name
     * @param appVersion  application version
     * @param globalVars  global variables parsed from {@code .substvar} files
     */
    public void generateProperties(
            File outputFile,
            String appName,
            String appVersion,
            List<SubstVarParser.GlobalVariable> globalVars) throws IOException {

        List<SubstVarParser.GlobalVariable> sorted = sorted(globalVars);
        String date = timestamp();

        StringBuilder sb = new StringBuilder();
        sb.append("# BW5 Deployment Properties\n");
        sb.append("# Application : ").append(appName).append("\n");
        sb.append("# Version     : ").append(appVersion).append("\n");
        sb.append("# Generated   : ").append(date).append("\n");
        sb.append("# Tool        : bw5-maven-plugin\n");
        sb.append("#\n");
        sb.append("# Global variables are listed alphabetically.\n");
        sb.append("# Edit the values on the right-hand side before deployment.\n");
        sb.append("\n");

        for (SubstVarParser.GlobalVariable var : sorted) {
            if (var.description != null && !var.description.isEmpty()) {
                sb.append("# ").append(var.description).append("\n");
            }
            sb.append(propertiesEscape(var.name))
              .append("=")
              .append(propertiesEscape(var.value != null ? var.value : ""))
              .append("\n");
        }

        write(outputFile, sb.toString());
    }

    // -----------------------------------------------------------------------
    //  values.yaml — Helm / BW5 Platform
    // -----------------------------------------------------------------------

    /**
     * Generates a Helm values override file for the {@code dp-bw5ce-app} chart.
     *
     * <p>Only the {@code appProps} and {@code appSecrets} sections are emitted —
     * this file is intended to be passed as {@code -f values.yaml} on top of the
     * chart's own defaults. Non-password variables go into {@code appProps};
     * password-type variables go into {@code appSecrets}. Within each section
     * variables are grouped by their source {@code .substvar} file name and
     * sorted alphabetically.</p>
     *
     * @param outputFile  target file (e.g. {@code target/values.yaml})
     * @param appName     application name
     * @param appVersion  application version
     * @param globalVars  global variables parsed from {@code .substvar} files
     */
    public void generateValuesYaml(
            File outputFile,
            String appName,
            String appVersion,
            List<SubstVarParser.GlobalVariable> globalVars) throws IOException {

        String date = timestamp();

        // Separate into appProps (non-password) and appSecrets (password)
        // and group each by substvar file name, sorted alphabetically within each group.
        Map<String, List<SubstVarParser.GlobalVariable>> props   = groupByFile(globalVars, false);
        Map<String, List<SubstVarParser.GlobalVariable>> secrets = groupByFile(globalVars, true);

        StringBuilder sb = new StringBuilder();
        sb.append("# BW5 Platform Deployment Values — dp-bw5ce-app override\n");
        sb.append("# Application : ").append(appName).append("\n");
        sb.append("# Version     : ").append(appVersion).append("\n");
        sb.append("# Generated   : ").append(date).append("\n");
        sb.append("# Tool        : bw5-maven-plugin\n");
        sb.append("#\n");
        sb.append("# Pass this file to the dp-bw5ce-app Helm chart:\n");
        sb.append("#   helm install ").append(appName.toLowerCase(Locale.ROOT)).append(" dp/dp-bw5ce-app -f ").append(appName).append("-values.yaml\n");
        sb.append("#\n");
        sb.append("# Variables are listed alphabetically within each substvar profile.\n");
        sb.append("# appProps  : non-password global variables\n");
        sb.append("# appSecrets: password global variables (set values before deployment)\n");
        sb.append("\n");

        appendYamlSection(sb, "appProps", props);
        sb.append("\n");
        appendYamlSection(sb, "appSecrets", secrets);

        write(outputFile, sb.toString());
    }

    /**
     * Groups variables by their substvar file name, keeping only password or
     * non-password variables depending on the {@code passwordOnly} flag.
     * Files and variables within each file are sorted alphabetically.
     */
    private Map<String, List<SubstVarParser.GlobalVariable>> groupByFile(
            List<SubstVarParser.GlobalVariable> globalVars, boolean passwordOnly) {

        // Preserve insertion order (files sorted alphabetically)
        Map<String, List<SubstVarParser.GlobalVariable>> grouped = new LinkedHashMap<>();

        List<SubstVarParser.GlobalVariable> filtered = new ArrayList<>();
        for (SubstVarParser.GlobalVariable v : globalVars) {
            if (v.isPassword() == passwordOnly) {
                filtered.add(v);
            }
        }
        // Sort by file name first, then by variable name
        filtered.sort(Comparator
            .comparing((SubstVarParser.GlobalVariable v) -> v.substVarFile != null ? v.substVarFile : "")
            .thenComparing(v -> v.name != null ? v.name : ""));

        for (SubstVarParser.GlobalVariable v : filtered) {
            String file = v.substVarFile != null ? v.substVarFile : "default.substvar";
            grouped.computeIfAbsent(file, k -> new ArrayList<>()).add(v);
        }
        return grouped;
    }

    private void appendYamlSection(StringBuilder sb, String sectionName,
            Map<String, List<SubstVarParser.GlobalVariable>> grouped) {

        sb.append(sectionName).append(":");
        if (grouped.isEmpty()) {
            sb.append(" {}\n");
            return;
        }
        sb.append("\n");
        for (Map.Entry<String, List<SubstVarParser.GlobalVariable>> entry : grouped.entrySet()) {
            sb.append("  ").append(yamlQuoteKey(entry.getKey())).append(":\n");
            for (SubstVarParser.GlobalVariable var : entry.getValue()) {
                if (var.description != null && !var.description.isEmpty()) {
                    sb.append("    # ").append(var.description).append("\n");
                }
                sb.append("    ").append(yamlQuoteKey(bwAppPropsKey(var.name)))
                  .append(": \"").append(yamlEscape(var.value != null ? var.value : "")).append("\"\n");
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Shared helpers
    // -----------------------------------------------------------------------

    /** Returns a copy of the list sorted alphabetically by variable name. */
    private List<SubstVarParser.GlobalVariable> sorted(List<SubstVarParser.GlobalVariable> vars) {
        List<SubstVarParser.GlobalVariable> copy = new ArrayList<>(vars);
        copy.sort(Comparator.comparing(v -> v.name != null ? v.name : ""));
        return copy;
    }

    private String timestamp() {
        return new SimpleDateFormat(DATE_FORMAT, Locale.ROOT).format(new Date());
    }

    private String nvlType(String type) {
        return (type != null && !type.isEmpty()) ? type : "String";
    }

    // XML escaping
    private static String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // XML attribute value escaping (same as xmlEscape but used for attributes)
    private static String xmlAttr(String s) {
        return xmlEscape(s);
    }

    /**
     * Escapes a key or value for use in a Java .properties file.
     * Encodes characters that have special meaning in the format.
     */
    private static String propertiesEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '=':  sb.append("\\=");  break;
                case ':':  sb.append("\\:");  break;
                case '#':  sb.append("\\#");  break;
                case '!':  sb.append("\\!");  break;
                default:   sb.append(c);      break;
            }
        }
        return sb.toString();
    }

    /**
     * Encodes special characters in a BW5 global variable name using the
     * BW Platform {@code appProps} convention, so the key is safe as a YAML
     * map key consumed by the {@code dp-bw5ce-app} Helm chart.
     *
     * <table>
     *   <tr><th>Character</th><th>Encoded as</th></tr>
     *   <tr><td>{@code :}</td><td>{@code __CoLoN__}</td></tr>
     *   <tr><td>{@code /}</td><td>{@code __SlAsH__}</td></tr>
     *   <tr><td>{@code -}</td><td>{@code __DaSh__}</td></tr>
     *   <tr><td>{@code (}</td><td>{@code __LPaReN__}</td></tr>
     *   <tr><td>{@code )}</td><td>{@code __RPaReN__}</td></tr>
     * </table>
     */
    private static String bwAppPropsKey(String name) {
        if (name == null) return "";
        return name
            .replace(":", "__CoLoN__")
            .replace("/", "__SlAsH__")
            .replace("-", "__DaSh__")
            .replace("(", "__LPaReN__")
            .replace(")", "__RPaReN__");
    }

    /**
     * Returns the key as a plain YAML identifier if it contains only safe
     * characters, or wraps it in double quotes otherwise.
     * BW5 variable names like {@code Connections/JMS/Host} are quoted.
     */
    private static String yamlQuoteKey(String name) {
        if (name == null) return "\"\"";
        // Plain key: starts with letter/underscore, contains only [A-Za-z0-9_-.]
        if (name.matches("[A-Za-z_][A-Za-z0-9_.\\-]*")) {
            return name;
        }
        return "\"" + yamlEscape(name) + "\"";
    }

    /** Escapes double-quoted YAML string content. */
    private static String yamlEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static void write(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
        }
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }
}

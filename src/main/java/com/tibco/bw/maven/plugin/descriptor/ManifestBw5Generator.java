package com.tibco.bw.maven.plugin.descriptor;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.util.IteratorIterable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates {@code manifest-bw5.json} for inclusion in the BW5 EAR.
 *
 * <p>The manifest is required by TIBCO BW5 container runtimes (TCI/BWCE) and
 * describes the application name, global variables (appProperties), and HTTP
 * endpoints derived from {@code .sharedhttp} shared resources.</p>
 */
public class ManifestBw5Generator {

    private static final Logger LOG = Logger.getLogger(ManifestBw5Generator.class.getName());

    private static final Namespace HTTP_NS =
        Namespace.getNamespace("www.tibco.com/shared/HTTPConnection");

    private static final Pattern GV_REF = Pattern.compile("%%([^%]+)%%");

    /**
     * Generates {@code manifest-bw5.json} in {@code workDir} and returns the file.
     *
     * @param appName         EAR / application name
     * @param appVersion      Maven project version
     * @param globalVars      parsed global variables from all .substvar files
     * @param sharedHttpFiles {@code .sharedhttp} files from the project (SAR resources)
     * @param processFiles    {@code .process} files used to resolve endpoint names
     * @param workDir         directory where the file will be written
     */
    public File generate(String appName, String appVersion,
                         List<SubstVarParser.GlobalVariable> globalVars,
                         List<File> sharedHttpFiles,
                         List<File> processFiles,
                         File workDir) throws Exception {

        Map<String, String> gvMap = buildGvMap(globalVars);

        List<String[]> properties = buildProperties(globalVars);
        Map<String, String> sharedHttpToProcess = buildSharedHttpToProcessMap(processFiles);
        List<Endpoint> endpoints = buildEndpoints(sharedHttpFiles, gvMap, sharedHttpToProcess);

        String json = renderJson(appName, appVersion, properties, endpoints);

        File out = new File(workDir, "manifest-bw5.json");
        try (Writer w = new OutputStreamWriter(java.nio.file.Files.newOutputStream(out.toPath()), StandardCharsets.UTF_8)) {
            w.write(json);
        }
        return out;
    }

    // -----------------------------------------------------------------------

    private Map<String, String> buildGvMap(List<SubstVarParser.GlobalVariable> globalVars) {
        Map<String, String> map = new LinkedHashMap<>();
        for (SubstVarParser.GlobalVariable gv : globalVars) {
            if (gv.name != null) {
                map.put(gv.name, gv.value != null ? gv.value : "");
            }
        }
        return map;
    }

    /** Returns list of [name, datatype, defaultValue] triples, including synthetic MessageEncoding. */
    private List<String[]> buildProperties(List<SubstVarParser.GlobalVariable> globalVars) {
        List<String[]> list = new ArrayList<>();
        boolean hasMessageEncoding = false;
        for (SubstVarParser.GlobalVariable gv : globalVars) {
            if (gv.name == null || gv.name.isEmpty()) continue;
            String type = (gv.type != null && !gv.type.isEmpty()) ? gv.type : "String";
            String value = (gv.value != null) ? gv.value : "";
            list.add(new String[]{gv.name, type, value});
            if ("MessageEncoding".equals(gv.name)) hasMessageEncoding = true;
        }
        // buildEAR always appends MessageEncoding if not already defined in the substvar
        if (!hasMessageEncoding) {
            list.add(new String[]{"MessageEncoding", "String", "ISO8859-1"});
        }
        return list;
    }

    /**
     * Scans process files for {@code <sharedChannel>} elements and returns a map of
     * sharedhttp filename → process name (filename without {@code .process} extension).
     * When multiple processes reference the same sharedhttp, the first one wins.
     */
    private Map<String, String> buildSharedHttpToProcessMap(List<File> processFiles) {
        Map<String, String> map = new HashMap<>();
        SAXBuilder sax = new SAXBuilder();
        for (File f : processFiles) {
            if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".process")) continue;
            String processName = f.getName().replaceFirst("\\.[^.]+$", "");
            try {
                Document doc = sax.build(f);
                IteratorIterable<Element> channels = doc.getDescendants(Filters.element("sharedChannel"));
                for (Element ch : channels) {
                    String path = ch.getTextTrim();
                    if (path.isEmpty()) continue;
                    // path is like "/HTTP Connection.sharedhttp" — take the filename part
                    String filename = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                    map.putIfAbsent(filename, processName);
                }
            } catch (JDOMException | IOException ignored) {
                // malformed process file — skip silently
            }
        }
        return map;
    }

    /** Returns endpoint descriptors from .sharedhttp files. Health check is always appended last. */
    private List<Endpoint> buildEndpoints(List<File> sharedHttpFiles,
                                          Map<String, String> gvMap,
                                          Map<String, String> sharedHttpToProcess) {
        List<Endpoint> endpoints = new ArrayList<>();
        SAXBuilder sax = new SAXBuilder();

        for (File f : sharedHttpFiles) {
            try {
                Document doc = sax.build(f);
                Element root = doc.getRootElement();
                Element config = root.getChild("config", HTTP_NS);
                if (config == null) config = root.getChild("config");
                if (config == null) continue;

                Element portEl = config.getChild("Port", HTTP_NS);
                if (portEl == null) portEl = config.getChild("Port");
                if (portEl == null) continue;

                String port = resolveGvRef(portEl.getTextTrim(), gvMap);
                if (port.isEmpty()) continue;

                String sharedResourceName = f.getName().replaceFirst("\\.[^.]+$", "");
                String processName = sharedHttpToProcess.getOrDefault(f.getName(), "");
                endpoints.add(new Endpoint(sharedResourceName, port, processName));
            } catch (JDOMException | IOException e) {
                LOG.warning("Skipping malformed sharedhttp file " + f.getName() + ": " + e.getMessage());
            }
        }

        endpoints.add(Endpoint.healthcheck());
        return endpoints;
    }

    private static class Endpoint {
        final String sharedResourceName; // null for healthcheck
        final String port;
        final String name; // process filename (without .process) that references this sharedhttp

        Endpoint(String sharedResourceName, String port, String name) {
            this.sharedResourceName = sharedResourceName;
            this.port = port;
            this.name = name != null ? name : "";
        }

        static Endpoint healthcheck() {
            return new Endpoint(null, "8090", "");
        }

        boolean isHealthcheck() {
            return sharedResourceName == null;
        }
    }

    /** Resolves %%VarName%% GV references to their default value. */
    private String resolveGvRef(String value, Map<String, String> gvMap) {
        if (value == null) return "";
        Matcher m = GV_REF.matcher(value);
        if (!m.matches()) return value;
        String varName = m.group(1);
        String resolved = gvMap.get(varName);
        return (resolved != null) ? resolved : value;
    }

    // -----------------------------------------------------------------------
    //  JSON rendering
    // -----------------------------------------------------------------------

    private String renderJson(String appName, String appVersion,
                              List<String[]> properties, List<Endpoint> endpoints) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"name\": ").append(jstr(appName)).append(",\n");
        sb.append("  \"applicationName\": ").append(jstr(appName)).append(",\n");
        sb.append("  \"applicationVersion\": ").append(jstr(appVersion)).append(",\n");
        sb.append("  \"description\": \"\",\n");
        sb.append("  \"version\": \"1.0\",\n");
        sb.append("  \"type\": \"bw5\",\n");
        sb.append("  \"volumesFrom\": [],\n");

        // appProperties
        sb.append("  \"appProperties\": [\n");
        sb.append("    {\n");
        sb.append("      \"profile\": \"default.substvar\",\n");
        sb.append("      \"properties\": [\n");
        for (int i = 0; i < properties.size(); i++) {
            String[] p = properties.get(i);
            sb.append("        {");
            sb.append("\"name\": ").append(jstr(p[0])).append(", ");
            sb.append("\"datatype\": ").append(jstr(p[1])).append(", ");
            sb.append("\"default\": ").append(jstr(p[2]));
            sb.append("}");
            if (i < properties.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("      ]\n");
        sb.append("    }\n");
        sb.append("  ],\n");

        // endpoints
        sb.append("  \"endpoints\": [\n");
        for (int i = 0; i < endpoints.size(); i++) {
            Endpoint ep = endpoints.get(i);
            sb.append("    {\n");
            sb.append("      \"name\": ").append(jstr(ep.name)).append(",\n");
            sb.append("      \"protocol\": \"http\",\n");
            sb.append("      \"port\": ").append(jstr(ep.port)).append(",\n");
            if (!ep.isHealthcheck()) {
                sb.append("      \"sharedResourceName\": ").append(jstr(ep.sharedResourceName)).append(",\n");
            }
            sb.append("      \"type\": ").append(ep.isHealthcheck() ? "\"httpHealthCheck\"" : "\"public\"").append(",\n");
            sb.append("      \"path\": \"\",\n");
            sb.append("      \"ping\": \"\",\n");
            sb.append("      \"public\": false,\n");
            sb.append("      \"primary\": false,\n");
            sb.append("      \"pingable\": ").append(ep.isHealthcheck()).append("\n");
            sb.append("    }");
            if (i < endpoints.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String jstr(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:   sb.append(c);      break;
            }
        }
        sb.append('"');
        return sb.toString();
    }
}

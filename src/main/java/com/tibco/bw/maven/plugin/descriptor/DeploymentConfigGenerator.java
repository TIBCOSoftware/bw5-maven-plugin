package com.tibco.bw.maven.plugin.descriptor;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    /**
     * Deployment model of one PAR (BW service) — its filename and the entry-point processes
     * (those with a starter) that AppManage lists under {@code <bwprocesses>}. The bindings,
     * runtime variables, engine (Adapter SDK) properties, monitor and fault-tolerance settings
     * are the standard AppManage single-binding export template and are emitted from fixed
     * defaults, so they are not part of this model.
     */
    public static class ServiceModel {
        /** PAR filename inside the EAR, e.g. {@code "MyApp-LB.par"}. */
        public final String parFileName;
        /** Entry-point processes (starters only). */
        public final List<ProcessEntry> processes;

        public ServiceModel(String parFileName, List<ProcessEntry> processes) {
            this.parFileName = parFileName;
            this.processes = processes != null ? processes : new ArrayList<>();
        }

        /**
         * Processes sorted by BW path for deterministic output, de-duplicated by name.
         *
         * <p>A PAR cannot contain the same process/serviceagent path twice (the archive is a zip,
         * which collapses duplicate entries on write). The in-memory discovery list, however, may
         * carry a serviceagent twice when it is both reachable by BFS and promoted from the
         * {@code .archive} descriptor's {@code processProperty} — which would otherwise emit a
         * duplicate {@code <bwprocess>} in the {@code <services>} block. De-duplicating here keeps
         * the first occurrence per name so the rendered XML matches the PAR contents.</p>
         */
        public List<ProcessEntry> sortedProcesses() {
            List<ProcessEntry> copy = new ArrayList<>(processes);
            copy.sort(Comparator.comparing(p -> p.name != null ? p.name : ""));
            List<ProcessEntry> deduped = new ArrayList<>(copy.size());
            Set<String> seenNames = new LinkedHashSet<>();
            for (ProcessEntry p : copy) {
                if (seenNames.add(p.name != null ? p.name : "")) {
                    deduped.add(p);
                }
            }
            return deduped;
        }

        /** One entry-point process: its BW path (no leading slash) and starter name. */
        public static class ProcessEntry {
            public final String name;
            public final String starterName;

            public ProcessEntry(String name, String starterName) {
                this.name = name;
                this.starterName = starterName;
            }
        }
    }

    /**
     * Standard BW engine (Adapter SDK) properties AppManage emits in the {@code <services>}
     * {@code Adapter SDK Properties} block of a fresh export. Deliberately the deployment-level
     * subset (not the full {@code bwengine.xml} SDK list emitted into the PAR TIBCO.xml).
     */
    private static final List<String[]> ADAPTER_SDK_PROPERTIES = Arrays.asList(
        new String[]{"Trace.Task.*", "false"},
        new String[]{"EnableMemorySavingMode", "false"},
        new String[]{"bw.engine.enableJobRecovery", "false"},
        new String[]{"bw.engine.autoCheckpointRestart", "true"},
        new String[]{"bw.engine.jobstats.enable", "false"},
        new String[]{"log.file.encoding", ""},
        new String[]{"bw.engine.emaEnabled", "false"},
        new String[]{"bw.container.service", ""},
        new String[]{"bw.container.service.rmi.port", "9995"},
        new String[]{"bw.platform.services.retreiveresources.Enabled", "false"},
        new String[]{"bw.platform.services.retreiveresources.Hostname", "localhost"},
        new String[]{"bw.platform.services.retreiveresources.Httpport", "8010"},
        new String[]{"bw.platform.services.retreiveresources.defaultEncoding", "ISO8859_1"},
        new String[]{"bw.platform.services.retreiveresources.enableLookups", "false"},
        new String[]{"bw.platform.services.retreiveresources.isSecure", "false"},
        new String[]{"bw.platform.services.retreiveresources.identity", "/Identity_HTTPConnection.id"},
        new String[]{"bw.log4j.configuration", ""}
    );

    /** Fixed {@code <repoInstances>} block (AppManage default, local repo selected). */
    private static final String REPO_INSTANCES_BLOCK =
        "    <repoInstances selected=\"local\">\n"
        + "        <httpRepoInstance>\n"
        + "            <timeout>600</timeout>\n"
        + "            <url></url>\n"
        + "        </httpRepoInstance>\n"
        + "        <rvRepoInstance>\n"
        + "            <timeout>600</timeout>\n"
        + "            <discoveryTimout>10</discoveryTimout>\n"
        + "            <daemon>tcp:7500</daemon>\n"
        + "            <service>7500</service>\n"
        + "            <network></network>\n"
        + "            <regionalSubject></regionalSubject>\n"
        + "            <operationRetry>0</operationRetry>\n"
        + "        </rvRepoInstance>\n"
        + "        <localRepoInstance>\n"
        + "            <encoding>UTF-8</encoding>\n"
        + "        </localRepoInstance>\n"
        + "    </repoInstances>\n";

    /** Fixed {@code <monitor>} block (AppManage default: all events present, actions disabled). */
    private static final String MONITOR_BLOCK = buildMonitorBlock();

    // -----------------------------------------------------------------------
    //  XML — AppManage deployment config
    // -----------------------------------------------------------------------

    /**
     * Generates an AppManage-compatible XML deployment configuration file.
     *
     * <p>The format matches what {@code appmanage -exportConfig} produces and what
     * {@code appmanage -setDeployConfig} consumes: root {@code <application>} (namespace
     * {@code ApplicationManagement}), {@code <description>}/{@code <contact>} as child elements,
     * type-specific Global-Variable tags ({@code NameValuePair} / {@code NameValuePairInteger} /
     * {@code NameValuePairBoolean} / {@code NameValuePairPassword}) with only {@code <name>} and
     * {@code <value>} children, plus the {@code <repoInstances>} and {@code <services>} sections.</p>
     *
     * <p>The {@code <services>} block is generated as a fresh single-binding export template (one
     * {@code <bw>} per PAR): the machine, credentials and heap settings are placeholders/defaults
     * an administrator edits or overrides before deployment.</p>
     *
     * @param outputFile  target file (e.g. {@code target/myapp-1.0.0-deploy.xml})
     * @param appName     application name
     * @param appVersion  application version
     * @param description value for the application {@code <description>} element (may be null/empty)
     * @param contact     value for the application {@code <contact>} element (may be null/empty)
     * @param globalVars  global variables parsed from {@code .substvar} files
     * @param services    per-PAR service models (bindings/processes); may be empty
     * @param serviceProps flat {@code bw[<par>]/...} map (from {@link #servicePropertyMap}, with any
     *                    overrides already merged) that supplies the values for the {@code <services>}
     *                    block — so overrides show up in the XML exactly as in the
     *                    {@code -services.properties}. When {@code null} the generated defaults are used.
     */
    public void generateDeployXml(
            File outputFile,
            String appName,
            String appVersion,
            String description,
            String contact,
            List<SubstVarParser.GlobalVariable> globalVars,
            List<ServiceModel> services,
            Map<String, String> serviceProps) throws IOException {

        List<SubstVarParser.GlobalVariable> sorted = sorted(globalVars);
        List<SubstVarParser.GlobalVariable> runtimeVars = serviceSettable(globalVars);
        // The <services> block renders from the same (possibly overridden) flat map as the
        // services.properties file. Fall back to freshly generated defaults when none is supplied.
        Map<String, String> flat = serviceProps != null
                ? serviceProps : servicePropertyMap(globalVars, services);
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
        sb.append("<application xmlns=\"http://www.tibco.com/xmlns/ApplicationManagement\" name=\"")
          .append(xmlAttr(appName)).append("\">\n");
        sb.append("    <description>").append(xmlEscape(description)).append("</description>\n");
        sb.append("    <contact>").append(xmlEscape(contact)).append("</contact>\n");

        if (!sorted.isEmpty()) {
            sb.append("    <NVPairs name=\"Global Variables\">\n");
            for (SubstVarParser.GlobalVariable var : sorted) {
                String tag = globalVarTag(var.type);
                sb.append("        <").append(tag).append(">\n");
                sb.append("            <name>").append(xmlEscape(var.name)).append("</name>\n");
                sb.append("            <value>").append(xmlEscape(normalizedValue(var))).append("</value>\n");
                sb.append("        </").append(tag).append(">\n");
            }
            sb.append("    </NVPairs>\n");
        }

        sb.append(REPO_INSTANCES_BLOCK);

        if (services != null && !services.isEmpty()) {
            sb.append("    <services>\n");
            for (ServiceModel svc : services) {
                appendServiceBw(sb, svc, runtimeVars, flat);
            }
            sb.append("    </services>\n");
        }

        sb.append("</application>\n");

        write(outputFile, sb.toString());
    }

    /**
     * Appends one {@code <bw name="...par">} service block. Every scalar value is read from the
     * flat {@code bw[<par>]/...} map ({@code flat}) so overrides merged into the
     * {@code -services.properties} appear identically here — the {@code <monitor>} block is the only
     * fixed part. Structure (process list, runtime-variable names) comes from the model.
     */
    private void appendServiceBw(StringBuilder sb, ServiceModel svc,
            List<SubstVarParser.GlobalVariable> runtimeVars, Map<String, String> flat) {
        String par = svc.parFileName;
        String p = "bw[" + par + "]";
        // The binding name is data-driven: after resolveServiceBindings() the flat map carries the
        // deployment binding name supplied by the service overrides (e.g. "MyApp-LB-esb06"); when no
        // override named it, this is the empty AppManage template name.
        String bindingName = resolveBindingNameForPar(flat, p);
        String b = p + "/bindings/binding[" + bindingName + "]";
        sb.append("        <bw name=\"").append(xmlAttr(par)).append("\">\n");
        sb.append("            <enabled>").append(esc(flat, p + "/enabled", "true")).append("</enabled>\n");
        sb.append("            <bindings>\n");
        sb.append("                <binding name=\"").append(xmlAttr(bindingName)).append("\">\n");
        sb.append("                    <machine>").append(esc(flat, b + "/machine", "%%" + par + "-machine%%")).append("</machine>\n");
        sb.append("                    <product>\n");
        sb.append("                        <type>").append(esc(flat, b + "/product/type", "bwengine")).append("</type>\n");
        sb.append("                        <version>").append(esc(flat, b + "/product/version", "")).append("</version>\n");
        sb.append("                        <location>").append(esc(flat, b + "/product/location", "")).append("</location>\n");
        sb.append("                    </product>\n");
        sb.append("                    <description>").append(esc(flat, b + "/description", "")).append("</description>\n");
        sb.append("                    <contact>").append(esc(flat, b + "/contact", "")).append("</contact>\n");
        sb.append("                    <setting>\n");
        sb.append("                        <startOnBoot>").append(esc(flat, b + "/setting/startOnBoot", "false")).append("</startOnBoot>\n");
        sb.append("                        <enableVerbose>").append(esc(flat, b + "/setting/enableVerbose", "false")).append("</enableVerbose>\n");
        sb.append("                        <maxLogFileSize>").append(esc(flat, b + "/setting/maxLogFileSize", "20000")).append("</maxLogFileSize>\n");
        sb.append("                        <maxLogFileCount>").append(esc(flat, b + "/setting/maxLogFileCount", "5")).append("</maxLogFileCount>\n");
        sb.append("                        <threadCount>").append(esc(flat, b + "/setting/threadCount", "8")).append("</threadCount>\n");
        sb.append("                        <NTService>\n");
        sb.append("                            <runAsNT>").append(esc(flat, b + "/setting/NTService/runAsNT", "false")).append("</runAsNT>\n");
        sb.append("                            <startupType>").append(esc(flat, b + "/setting/NTService/startupType", "automatic")).append("</startupType>\n");
        sb.append("                            <loginAs>").append(esc(flat, b + "/setting/NTService/loginAs", "%%" + par + "loginAs%%")).append("</loginAs>\n");
        sb.append("                            <password>").append(esc(flat, b + "/setting/NTService/password", "%%" + par + "password%%")).append("</password>\n");
        sb.append("                        </NTService>\n");
        sb.append("                        <java>\n");
        sb.append("                            <prepandClassPath>").append(esc(flat, b + "/setting/java/prepandClassPath", "")).append("</prepandClassPath>\n");
        sb.append("                            <appendClassPath>").append(esc(flat, b + "/setting/java/appendClassPath", "")).append("</appendClassPath>\n");
        sb.append("                            <initHeapSize>").append(esc(flat, b + "/setting/java/initHeapSize", "32")).append("</initHeapSize>\n");
        sb.append("                            <maxHeapSize>").append(esc(flat, b + "/setting/java/maxHeapSize", "256")).append("</maxHeapSize>\n");
        sb.append("                            <threadStackSize>").append(esc(flat, b + "/setting/java/threadStackSize", "256")).append("</threadStackSize>\n");
        sb.append("                        </java>\n");
        sb.append("                    </setting>\n");
        sb.append("                    <ftWeight>").append(esc(flat, b + "/ftWeight", "1000")).append("</ftWeight>\n");
        sb.append("                    <shutdown>\n");
        sb.append("                        <checkpoint>").append(esc(flat, b + "/shutdown/checkpoint", "false")).append("</checkpoint>\n");
        sb.append("                        <timeout>").append(esc(flat, b + "/shutdown/timeout", "0")).append("</timeout>\n");
        sb.append("                    </shutdown>\n");
        appendServiceVars(sb, "                    ", "INSTANCE_RUNTIME_VARIABLES", runtimeVars,
                flat, b + "/variables/variable[");
        sb.append("                </binding>\n");
        sb.append("            </bindings>\n");
        appendServiceVars(sb, "            ", "Runtime Variables", runtimeVars,
                flat, p + "/variables[Runtime Variables]/variable[");
        appendSdkVars(sb, "            ", flat, p + "/variables[Adapter SDK Properties]/variable[");
        sb.append("            <failureCount>").append(esc(flat, p + "/failureCount", "0")).append("</failureCount>\n");
        sb.append("            <failureInterval>").append(esc(flat, p + "/failureInterval", "0")).append("</failureInterval>\n");
        sb.append(MONITOR_BLOCK);
        sb.append("            <bwprocesses>\n");
        for (ServiceModel.ProcessEntry proc : svc.sortedProcesses()) {
            String bp = p + "/bwprocesses/bwprocess[" + proc.name + "]";
            sb.append("                <bwprocess name=\"").append(xmlAttr(proc.name)).append("\">\n");
            sb.append("                    <starter>").append(esc(flat, bp + "/starter", proc.starterName)).append("</starter>\n");
            sb.append("                    <enabled>").append(esc(flat, bp + "/enabled", "true")).append("</enabled>\n");
            sb.append("                    <maxJob>").append(esc(flat, bp + "/maxJob", "0")).append("</maxJob>\n");
            sb.append("                    <activation>").append(esc(flat, bp + "/activation", "true")).append("</activation>\n");
            sb.append("                    <flowLimit>").append(esc(flat, bp + "/flowLimit", "0")).append("</flowLimit>\n");
            sb.append("                </bwprocess>\n");
        }
        sb.append("            </bwprocesses>\n");
        sb.append("            <isFt>").append(esc(flat, p + "/isFt", "false")).append("</isFt>\n");
        sb.append("            <faultTolerant>\n");
        sb.append("                <hbInterval>").append(esc(flat, p + "/faultTolerant/hbInterval", "10000")).append("</hbInterval>\n");
        sb.append("                <activationInterval>").append(esc(flat, p + "/faultTolerant/activationInterval", "35000")).append("</activationInterval>\n");
        sb.append("                <preparationDelay>").append(esc(flat, p + "/faultTolerant/preparationDelay", "0")).append("</preparationDelay>\n");
        sb.append("            </faultTolerant>\n");
        sb.append("        </bw>\n");
    }

    /** Runtime/instance variables block: one entry per service-settable GV, value read from {@code flat}. */
    private void appendServiceVars(StringBuilder sb, String indent, String blockName,
            List<SubstVarParser.GlobalVariable> runtimeVars, Map<String, String> flat, String keyPrefix) {
        sb.append(indent).append("<NVPairs name=\"").append(xmlAttr(blockName)).append("\">\n");
        for (SubstVarParser.GlobalVariable v : runtimeVars) {
            sb.append(indent).append("    <NameValuePair>\n");
            sb.append(indent).append("        <name>").append(xmlEscape(v.name)).append("</name>\n");
            sb.append(indent).append("        <value>").append(esc(flat, keyPrefix + v.name + "]", normalizedValue(v))).append("</value>\n");
            sb.append(indent).append("    </NameValuePair>\n");
        }
        sb.append(indent).append("</NVPairs>\n");
    }

    /** Adapter SDK Properties block: fixed key set, values read from {@code flat}. */
    private void appendSdkVars(StringBuilder sb, String indent, Map<String, String> flat, String keyPrefix) {
        sb.append(indent).append("<NVPairs name=\"Adapter SDK Properties\">\n");
        for (String[] kv : ADAPTER_SDK_PROPERTIES) {
            sb.append(indent).append("    <NameValuePair>\n");
            sb.append(indent).append("        <name>").append(xmlEscape(kv[0])).append("</name>\n");
            sb.append(indent).append("        <value>").append(esc(flat, keyPrefix + kv[0] + "]", kv[1])).append("</value>\n");
            sb.append(indent).append("    </NameValuePair>\n");
        }
        sb.append(indent).append("</NVPairs>\n");
    }

    /** XML-escaped value from the flat map for {@code key}, or {@code def} when absent. */
    private static String esc(Map<String, String> flat, String key, String def) {
        String v = flat != null ? flat.get(key) : null;
        return xmlEscape(v != null ? v : def);
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
    //  services.properties — bindings / processes (AppManage service config)
    // -----------------------------------------------------------------------

    /**
     * Generates the flat {@code services.properties} file: the {@code <services>} deployment
     * template flattened to {@code bw[<par>]/...=value} keys (bindings, runtime variables,
     * Adapter SDK properties, bwprocesses, fault tolerance). This is the same content as the
     * {@code <services>} block of the {@code -deploy.xml}, in the {@code key=value} form
     * {@code AppManage} reads/writes. Keys are emitted in sorted order for stable output.
     *
     * @param outputFile  target file (e.g. {@code target/myapp-1.0.0-services.properties})
     * @param appName     application name
     * @param appVersion  application version
     * @param flat        flat {@code bw[<par>]/...=value} map (from {@link #servicePropertyMap},
     *                    with any overrides already merged in)
     */
    public void generateServicesProperties(
            File outputFile,
            String appName,
            String appVersion,
            Map<String, String> flat) throws IOException {

        List<String> keys = new ArrayList<>(flat.keySet());
        keys.sort(Comparator.naturalOrder());

        StringBuilder sb = new StringBuilder();
        sb.append("# BW5 Service Deployment Properties (bindings, processes)\n");
        sb.append("# Application : ").append(appName).append("\n");
        sb.append("# Version     : ").append(appVersion).append("\n");
        sb.append("# Generated   : ").append(timestamp()).append("\n");
        sb.append("# Tool        : bw5-maven-plugin\n");
        sb.append("\n");
        for (String key : keys) {
            sb.append(propertiesEscapeKey(key)).append("=")
              .append(propertiesEscape(flat.get(key))).append("\n");
        }
        write(outputFile, sb.toString());
    }

    /**
     * Builds the flat {@code bw[<par>]/...=value} map for the {@code services.properties} file.
     * Exposed (package-private) so overrides can be merged before the file and the
     * {@code -deploy.xml} {@code <services>} block are written.
     */
    public Map<String, String> servicePropertyMap(
            List<SubstVarParser.GlobalVariable> globalVars,
            List<ServiceModel> services) {

        Map<String, String> flat = new LinkedHashMap<>();
        if (services == null) return flat;
        List<String[]> runtimeVars = runtimeVarPairs(serviceSettable(globalVars));

        for (ServiceModel svc : services) {
            String p = "bw[" + svc.parFileName + "]";
            flat.put(p + "/enabled", "true");
            flat.put(p + "/failureCount", "0");
            flat.put(p + "/failureInterval", "0");
            flat.put(p + "/isFt", "false");
            flat.put(p + "/faultTolerant/hbInterval", "10000");
            flat.put(p + "/faultTolerant/activationInterval", "35000");
            flat.put(p + "/faultTolerant/preparationDelay", "0");

            // Single default binding
            String b = p + "/bindings/binding[]";
            flat.put(b + "/machine", "%%" + svc.parFileName + "-machine%%");
            flat.put(b + "/description", "");
            flat.put(b + "/contact", "");
            flat.put(b + "/product/type", "bwengine");
            flat.put(b + "/product/version", "");
            flat.put(b + "/product/location", "");
            flat.put(b + "/ftWeight", "1000");
            flat.put(b + "/setting/startOnBoot", "false");
            flat.put(b + "/setting/enableVerbose", "false");
            flat.put(b + "/setting/maxLogFileSize", "20000");
            flat.put(b + "/setting/maxLogFileCount", "5");
            flat.put(b + "/setting/threadCount", "8");
            flat.put(b + "/setting/NTService/runAsNT", "false");
            flat.put(b + "/setting/NTService/startupType", "automatic");
            flat.put(b + "/setting/NTService/loginAs", "%%" + svc.parFileName + "loginAs%%");
            flat.put(b + "/setting/NTService/password", "%%" + svc.parFileName + "password%%");
            flat.put(b + "/setting/java/prepandClassPath", "");
            flat.put(b + "/setting/java/appendClassPath", "");
            flat.put(b + "/setting/java/initHeapSize", "32");
            flat.put(b + "/setting/java/maxHeapSize", "256");
            flat.put(b + "/setting/java/threadStackSize", "256");
            flat.put(b + "/shutdown/checkpoint", "false");
            flat.put(b + "/shutdown/timeout", "0");
            for (String[] kv : runtimeVars) {
                flat.put(b + "/variables/variable[" + kv[0] + "]", kv[1]);
            }

            for (String[] kv : runtimeVars) {
                flat.put(p + "/variables[Runtime Variables]/variable[" + kv[0] + "]", kv[1]);
            }
            for (String[] kv : ADAPTER_SDK_PROPERTIES) {
                flat.put(p + "/variables[Adapter SDK Properties]/variable[" + kv[0] + "]", kv[1]);
            }
            for (ServiceModel.ProcessEntry proc : svc.sortedProcesses()) {
                String bp = p + "/bwprocesses/bwprocess[" + proc.name + "]";
                flat.put(bp + "/starter", proc.starterName);
                flat.put(bp + "/enabled", "true");
                flat.put(bp + "/maxJob", "0");
                flat.put(bp + "/activation", "true");
                flat.put(bp + "/flowLimit", "0");
            }
        }
        return flat;
    }

    // -----------------------------------------------------------------------
    //  Service-property resolution (named bindings + wildcard expansion)
    // -----------------------------------------------------------------------

    /**
     * Resolves a merged service-property map into the concrete, AppManage-ready form used by both
     * the {@code -services.properties} file and the {@code <services>} block of the
     * {@code -deploy.xml}. Two transformations are applied:
     *
     * <ol>
     *   <li><b>Named-binding reconciliation.</b> The generated template uses an empty-named binding
     *       ({@code bw[<par>]/bindings/binding[]/...}). When an override supplies a named binding
     *       ({@code binding[<par>-<node>]/...}), the template binding is renamed to that name and
     *       all template defaults are carried over, so a single fully-populated
     *       {@code binding[<name>]} results — matching what {@code AppManage} exports. Without the
     *       reconciliation the named override keys and the empty template binding stay disjoint, and
     *       the XML renders only {@code <binding name="">} with default values.</li>
     *   <li><b>Wildcard / glob expansion.</b> {@code AppManage}-style wildcard keys — {@code bw[*]},
     *       {@code binding[*]}, {@code binding[*esbNN]} (suffix match), {@code bwprocess[*]},
     *       {@code variable[*]}, {@code adapter[*]} and a bare wildcard path segment — are
     *       expanded onto every concrete key they match and then dropped. A wildcard never
     *       overwrites a key the user set explicitly (present in {@code explicitOverrideKeys});
     *       explicit values always win over a glob. Wildcards that match nothing concrete are
     *       silently discarded (e.g. {@code adapter[*]} entries when the app has no adapters).</li>
     * </ol>
     *
     * <p>The returned map contains only concrete keys — no {@code *}, no empty-named binding.
     * Passing a map with no named bindings and no wildcards returns it unchanged, so the method is
     * safe (and idempotent) to call even when no overrides are configured.</p>
     *
     * @param merged               flat map after {@link PropertyMerger#mergeServiceProperties}
     *                             (template defaults + explicit overrides + wildcard entries)
     * @param explicitOverrideKeys concrete (non-wildcard) keys the user set explicitly via a service
     *                             override file or a {@code bw5.service.*} property; protected from
     *                             wildcard expansion. May be {@code null}.
     * @return the resolved concrete map (a new {@link LinkedHashMap})
     */
    public Map<String, String> resolveServiceBindings(
            Map<String, String> merged, Set<String> explicitOverrideKeys) {

        Set<String> explicit = explicitOverrideKeys != null ? explicitOverrideKeys : Collections.emptySet();

        // 1. Partition into concrete keys and wildcard patterns.
        Map<String, String> concrete = new LinkedHashMap<>();
        Map<String, String> wildcards = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : merged.entrySet()) {
            if (isWildcardKey(e.getKey())) {
                wildcards.put(e.getKey(), e.getValue());
            } else {
                concrete.put(e.getKey(), e.getValue());
            }
        }

        // 2. Reconcile the empty-named template binding with the override-supplied binding name.
        reconcileBindingNames(concrete);

        // 3. Expand wildcards. Apply least-specific (most stars) first so a more specific glob wins;
        //    never overwrite an explicitly-set key.
        List<String> patterns = new ArrayList<>(wildcards.keySet());
        patterns.sort(Comparator.comparingInt(DeploymentConfigGenerator::wildcardCount).reversed()
                .thenComparing(Comparator.naturalOrder()));
        List<String> concreteKeys = new ArrayList<>(concrete.keySet());
        for (String pattern : patterns) {
            String value = wildcards.get(pattern);
            List<String> patternComps = splitTopLevel(pattern);
            for (String key : concreteKeys) {
                if (explicit.contains(key)) continue;
                if (componentsMatch(patternComps, key)) {
                    concrete.put(key, value);
                }
            }
        }
        return concrete;
    }

    /**
     * Per PAR, renames the empty-named template binding ({@code binding[]}) to the non-empty binding
     * name found among the override keys, carrying over every template default that the override did
     * not already set. Mutates {@code concrete} in place. No-op for a PAR whose bindings are all
     * empty-named (no override named the binding).
     */
    private void reconcileBindingNames(Map<String, String> concrete) {
        // First pass: choose, per PAR token, the binding name to adopt.
        Map<String, String> parToName = new LinkedHashMap<>();
        for (String key : concrete.keySet()) {
            List<String> c = splitTopLevel(key);
            if (isBindingKey(c)) {
                String name = bracketInner(c.get(2));
                if (!name.isEmpty()) {
                    parToName.putIfAbsent(c.get(0), name);
                }
            }
        }
        if (parToName.isEmpty()) return;

        // Second pass: rename binding[] -> binding[<name>], filling only the keys the override omitted.
        Map<String, String> renamed = new LinkedHashMap<>();
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, String> e : concrete.entrySet()) {
            List<String> c = splitTopLevel(e.getKey());
            if (isBindingKey(c) && bracketInner(c.get(2)).isEmpty()) {
                String name = parToName.get(c.get(0));
                if (name != null) {
                    c.set(2, "binding[" + name + "]");
                    String nk = String.join("/", c);
                    if (!concrete.containsKey(nk)) {
                        renamed.put(nk, e.getValue());
                    }
                    toRemove.add(e.getKey());
                }
            }
        }
        concrete.keySet().removeAll(toRemove);
        concrete.putAll(renamed);
    }

    /** Binding name (possibly empty) carried in {@code flat} for {@code par} = {@code "bw[<par>]"}. */
    private static String resolveBindingNameForPar(Map<String, String> flat, String par) {
        if (flat == null) return "";
        String best = "";
        for (String key : flat.keySet()) {
            List<String> c = splitTopLevel(key);
            if (isBindingKey(c) && par.equals(c.get(0))) {
                String name = bracketInner(c.get(2));
                if (!name.isEmpty() && (best.isEmpty() || name.compareTo(best) < 0)) {
                    best = name;
                }
            }
        }
        return best;
    }

    /** True when {@code c} is a {@code bw[..]/bindings/binding[..]/...} key (component form). */
    private static boolean isBindingKey(List<String> c) {
        return c.size() >= 4
                && c.get(0).startsWith("bw[") && c.get(0).endsWith("]")
                && "bindings".equals(c.get(1))
                && c.get(2).startsWith("binding[") && c.get(2).endsWith("]");
    }

    /** Substring between the first {@code [} and the last {@code ]}, or {@code ""} if absent. */
    private static String bracketInner(String token) {
        int lo = token.indexOf('[');
        int hi = token.lastIndexOf(']');
        return (lo >= 0 && hi > lo) ? token.substring(lo + 1, hi) : "";
    }

    /**
     * A key is a wildcard pattern when one of its <em>structural</em> components is a glob
     * (see {@link #isGlobComponent(String)}). A {@code *} inside a {@code variable[...]} name is
     * <em>not</em> a wildcard: real BW engine properties legitimately contain one (e.g.
     * {@code variable[Trace.Task.*]}), so such keys stay concrete and are never expanded/dropped.
     */
    private static boolean isWildcardKey(String key) {
        for (String comp : splitTopLevel(key)) {
            if (isGlobComponent(comp)) return true;
        }
        return false;
    }

    /**
     * True when a single key component is a glob: it contains a {@code *} and is not a
     * {@code variable[...]} leaf (whose {@code *} belongs to the property name, not the pattern).
     * The plural {@code variables[...]} block name carries no {@code *} in practice, so a
     * hypothetical {@code variables[*]} still counts as a glob.
     */
    private static boolean isGlobComponent(String comp) {
        return comp.indexOf('*') >= 0 && !comp.startsWith("variable[");
    }

    private static int wildcardCount(String key) {
        int n = 0;
        for (String comp : splitTopLevel(key)) {
            if (isGlobComponent(comp)) {
                for (int i = 0; i < comp.length(); i++) {
                    if (comp.charAt(i) == '*') n++;
                }
            }
        }
        return n;
    }

    /**
     * Splits a flat key on {@code /} at bracket depth 0 only, so slashes <em>inside</em> a bracketed
     * token (process paths like {@code bwprocess[A/B/C.process]}, variable names) stay in one
     * component.
     */
    private static List<String> splitTopLevel(String key) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (ch == '[') {
                depth++;
            } else if (ch == ']') {
                if (depth > 0) depth--;
            }
            if (ch == '/' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        parts.add(cur.toString());
        return parts;
    }

    /** True when a wildcard pattern (already split into components) matches a concrete key. */
    private static boolean componentsMatch(List<String> patternComps, String key) {
        List<String> keyComps = splitTopLevel(key);
        if (patternComps.size() != keyComps.size()) return false;
        for (int i = 0; i < patternComps.size(); i++) {
            String p = patternComps.get(i);
            String k = keyComps.get(i);
            if (isGlobComponent(p)) {
                if (!globComponentMatches(p, k)) return false;
            } else if (!p.equals(k)) {
                return false;
            }
        }
        return true;
    }

    /** Glob match of a single component, where {@code *} matches any run of characters. */
    private static boolean globComponentMatches(String pattern, String value) {
        StringBuilder rx = new StringBuilder();
        int last = 0;
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.charAt(i) == '*') {
                rx.append(Pattern.quote(pattern.substring(last, i)));
                rx.append(".*");
                last = i + 1;
            }
        }
        rx.append(Pattern.quote(pattern.substring(last)));
        return value.matches(rx.toString());
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

    /** The service-settable global variables (those AppManage lists as Runtime Variables). */
    private List<SubstVarParser.GlobalVariable> serviceSettable(
            List<SubstVarParser.GlobalVariable> vars) {
        List<SubstVarParser.GlobalVariable> result = new ArrayList<>();
        if (vars != null) {
            for (SubstVarParser.GlobalVariable v : vars) {
                if (v.serviceSettable) result.add(v);
            }
        }
        result.sort(Comparator.comparing(v -> v.name != null ? v.name : ""));
        return result;
    }

    /** Runtime-variable name/value pairs for the service blocks. */
    private List<String[]> runtimeVarPairs(List<SubstVarParser.GlobalVariable> serviceVars) {
        List<String[]> pairs = new ArrayList<>();
        for (SubstVarParser.GlobalVariable v : serviceVars) {
            pairs.add(new String[]{v.name, normalizedValue(v)});
        }
        return pairs;
    }

    /**
     * The type-specific Global-Variable element tag used by AppManage:
     * {@code NameValuePairInteger} / {@code NameValuePairBoolean} / {@code NameValuePairPassword},
     * or plain {@code NameValuePair} for strings/unknown types.
     */
    private static String globalVarTag(String type) {
        if (type == null) return "NameValuePair";
        switch (type.toLowerCase(Locale.ROOT)) {
            case "password": return "NameValuePairPassword";
            case "boolean":  return "NameValuePairBoolean";
            case "integer":  return "NameValuePairInteger";
            default:         return "NameValuePair";
        }
    }

    /** Value with boolean {@code 1}/{@code true} normalised to {@code true}/{@code false}. */
    private static String normalizedValue(SubstVarParser.GlobalVariable var) {
        String value = var.value != null ? var.value : "";
        if ("boolean".equalsIgnoreCase(var.type)) {
            return ("1".equals(value) || "true".equalsIgnoreCase(value)) ? "true" : "false";
        }
        return value;
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
     * Escapes a {@code .properties} <em>key</em>. Same as {@link #propertiesEscape} plus spaces
     * (which delimit key from value and so must be escaped inside a key), matching the
     * {@code variables[Adapter\ SDK\ Properties]} form AppManage writes.
     */
    private static String propertiesEscapeKey(String s) {
        return propertiesEscape(s).replace(" ", "\\ ");
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

    /**
     * Builds the fixed {@code <monitor>} block (12-space base indent) emitted inside each
     * {@code <bw>} service — the AppManage default: a single empty rulebase, the four failure
     * events (ANY/FIRST/SECOND/Subsequent), a log event and a suspend-process event, every
     * action present but disabled.
     */
    private static String buildMonitorBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("            <monitor>\n");
        sb.append("                <rulebases>\n");
        sb.append("                    <rulebase>\n");
        sb.append("                        <uri></uri>\n");
        sb.append("                        <data></data>\n");
        sb.append("                    </rulebase>\n");
        sb.append("                </rulebases>\n");
        sb.append("                <events>\n");
        for (String failure : new String[]{"ANY", "FIRST", "SECOND", "Subsequent"}) {
            sb.append("                    <failureEvent>\n");
            sb.append("                        <restart>false</restart>\n");
            sb.append("                        <description></description>\n");
            appendMonitorActions(sb);
            sb.append("                        <failure>").append(failure).append("</failure>\n");
            sb.append("                    </failureEvent>\n");
        }
        sb.append("                    <logEvent>\n");
        sb.append("                        <restart>false</restart>\n");
        sb.append("                        <description></description>\n");
        appendMonitorActions(sb);
        sb.append("                        <match></match>\n");
        sb.append("                    </logEvent>\n");
        sb.append("                    <suspendProcessEvent>\n");
        sb.append("                        <restart>false</restart>\n");
        sb.append("                        <description></description>\n");
        appendMonitorActions(sb);
        sb.append("                    </suspendProcessEvent>\n");
        sb.append("                </events>\n");
        sb.append("            </monitor>\n");
        return sb.toString();
    }

    /** Appends the shared {@code <actions>} block (alert/email/custom, all disabled). */
    private static void appendMonitorActions(StringBuilder sb) {
        sb.append("                        <actions>\n");
        sb.append("                            <alertAction>\n");
        sb.append("                                <performPolicy>Once</performPolicy>\n");
        sb.append("                                <enabled>false</enabled>\n");
        sb.append("                                <level>High</level>\n");
        sb.append("                                <message></message>\n");
        sb.append("                            </alertAction>\n");
        sb.append("                            <emailAction>\n");
        sb.append("                                <performPolicy>Once</performPolicy>\n");
        sb.append("                                <enabled>false</enabled>\n");
        sb.append("                                <message></message>\n");
        sb.append("                                <to></to>\n");
        sb.append("                                <cc></cc>\n");
        sb.append("                                <subject></subject>\n");
        sb.append("                                <sMTPServer></sMTPServer>\n");
        sb.append("                            </emailAction>\n");
        sb.append("                            <customAction>\n");
        sb.append("                                <performPolicy>Once</performPolicy>\n");
        sb.append("                                <enabled>false</enabled>\n");
        sb.append("                                <command></command>\n");
        sb.append("                                <arguments></arguments>\n");
        sb.append("                            </customAction>\n");
        sb.append("                        </actions>\n");
    }
}

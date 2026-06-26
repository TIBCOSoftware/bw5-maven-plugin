package com.tibco.bw.maven.plugin.descriptor;

import org.apache.maven.artifact.Artifact;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Generates TIBCO.xml deployment descriptors for BW5 EAR and PAR archives.
 *
 * <p>This is the core of the plugin — it replaces the need for the {@code appmanage}
 * binary to extract/generate deployment descriptors.</p>
 *
 * <p>Two descriptor types are generated:</p>
 * <ul>
 *   <li><b>EAR-level TIBCO.xml</b>: contains FileAliases (projlib/JAR refs),
 *       Global Variables (from .substvar), and the Modules list (PAR references).</li>
 *   <li><b>PAR-level TIBCO.xml</b>: contains process configurations, engine
 *       compatibility requirements, and fault tolerance settings.</li>
 * </ul>
 */
public class TibcoXmlGenerator {

    private static final String DATE_FORMAT = "M/d/yy, h:mm a";

    // -----------------------------------------------------------------------
    //  EAR-level TIBCO.xml
    // -----------------------------------------------------------------------

    /**
     * Generates the EAR-level TIBCO.xml deployment descriptor.
     *
     * @param outputFile      target file to write
     * @param earName         EAR archive name (from the .archive descriptor {@code <name>} element)
     * @param earVersion      version string written to {@code <version>} (e.g. "1" for pom version 1.0.0-SNAPSHOT)
     * @param moduleFileNames PAR/AAR filenames inside the EAR (e.g. ["OrderService.par", "PaymentService.par"])
     * @param projlibDeps     list of projlib (bw5module) dependencies
     * @param jarDeps         list of JAR dependencies
     * @param globalVars      list of global variables from .substvar files
     * @param owner           owner string (defaults to system user)
     */
    public void generateEarDescriptor(
            File outputFile,
            String earName,
            String earVersion,
            List<String> moduleFileNames,
            List<Artifact> projlibDeps,
            List<Artifact> jarDeps,
            List<SubstVarParser.GlobalVariable> globalVars,
            String owner) throws IOException {

        String date = new SimpleDateFormat(DATE_FORMAT, Locale.ROOT).format(new Date());
        String effectiveOwner = (owner == null || owner.isEmpty())
            ? System.getProperty("user.name", "unknown") : owner;

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<DeploymentDescriptors xmlns=\"http://www.tibco.com/xmlns/dd\">\n");
        sb.append("    <name>").append(escape(earName)).append("</name>\n");
        sb.append("    <version>").append(escape(earVersion)).append("</version>\n");
        sb.append("    <owner>").append(escape(effectiveOwner)).append("</owner>\n");
        sb.append("    <creationDate>").append(date).append("</creationDate>\n");
        sb.append("    <isApplicationArchive>true</isApplicationArchive>\n");

        // RepoInstance factory
        sb.append("    <DeploymentDescriptorFactory>\n");
        sb.append("        <name>{http://www.tibco.com/xmlns/repoinstance}RepoInstance</name>\n");
        sb.append("        <deploymentDescriptorFactoryClassName>com.tibco.dd.repo.RepoInstance</deploymentDescriptorFactoryClassName>\n");
        sb.append("        <deploymentDescriptorXsdFileName>com/tibco/dd/repo/RepoInstance.xsd</deploymentDescriptorXsdFileName>\n");
        sb.append("    </DeploymentDescriptorFactory>\n");
        sb.append("    <repoinstance:RepoInstance xmlns:repoinstance=\"http://www.tibco.com/xmlns/repoinstance\">\n");
        sb.append("        <name>TIBCO BusinessWorks and Adapters Deployment Repository Instance</name>\n");
        sb.append("    </repoinstance:RepoInstance>\n");

        // NameValuePairs factory
        sb.append("    <DeploymentDescriptorFactory>\n");
        sb.append("        <name>{http://www.tibco.com/xmlns/dd}NameValuePairs</name>\n");
        sb.append("        <deploymentDescriptorFactoryClassName>com.tibco.archive.helpers.NameValuePairs</deploymentDescriptorFactoryClassName>\n");
        sb.append("    </DeploymentDescriptorFactory>\n");

        // FileAliases: projlib dependencies
        boolean hasAliases = !projlibDeps.isEmpty() || !jarDeps.isEmpty();
        if (hasAliases) {
            sb.append("    <NameValuePairs>\n");
            sb.append("        <name>FileAliases</name>\n");
            for (Artifact projlib : projlibDeps) {
                String fileName = projlib.getArtifactId() + "-" + projlib.getVersion() + ".projlib";
                appendFileAlias(sb, fileName);
            }
            for (Artifact jar : jarDeps) {
                String fileName = jar.getArtifactId() + "-" + jar.getVersion() + ".jar";
                appendFileAlias(sb, fileName);
            }
            sb.append("    </NameValuePairs>\n");
        }

        // Global Variables from .substvar files, plus the standard MessageEncoding entry
        // that buildEAR always appends to every EAR.
        {
            sb.append("    <NameValuePairs>\n");
            sb.append("        <name>Global Variables</name>\n");
            for (SubstVarParser.GlobalVariable var : globalVars) {
                appendGlobalVar(sb, var);
            }
            // buildEAR always appends MessageEncoding as the last Global Variable
            boolean hasMessageEncoding = globalVars.stream()
                .anyMatch(v -> "MessageEncoding".equals(v.name));
            if (!hasMessageEncoding) {
                sb.append("        <NameValuePair>\n");
                sb.append("            <name>MessageEncoding</name>\n");
                sb.append("            <value>ISO8859-1</value>\n");
                sb.append("            <description>This is the encoding used the EAR.</description>\n");
                sb.append("            <requiresConfiguration>false</requiresConfiguration>\n");
                sb.append("        </NameValuePair>\n");
            }
            sb.append("    </NameValuePairs>\n");
        }

        // Modules: ONE block listing all PAR/AAR filenames (matches TIBCO buildEAR format)
        sb.append("    <DeploymentDescriptorFactory>\n");
        sb.append("        <name>{http://www.tibco.com/xmlns/dd}Modules</name>\n");
        sb.append("        <deploymentDescriptorFactoryClassName>com.tibco.archive.helpers.Modules</deploymentDescriptorFactoryClassName>\n");
        sb.append("    </DeploymentDescriptorFactory>\n");
        sb.append("    <Modules>\n");
        sb.append("        <name>Modules</name>\n");
        for (String moduleFileName : moduleFileNames) {
            sb.append("        <pathName>").append(escape(moduleFileName)).append("</pathName>\n");
        }
        sb.append("    </Modules>\n");

        sb.append("</DeploymentDescriptors>\n");

        write(outputFile, sb.toString());
    }

    // -----------------------------------------------------------------------
    //  AAR-level TIBCO.xml
    // -----------------------------------------------------------------------

    /**
     * Generates the AAR-level TIBCO.xml deployment descriptor.
     *
     * @param outputFile       target file to write
     * @param aarFileName      AAR filename (e.g. "GAC_RPC_JMS_Topic.aar")
     * @param adapterReference raw {@code adapterReference} value from the {@code .archive}
     *                         descriptor (e.g. {@code /path/Foo.adapter#adapter.GenericAdapterConfiguration})
     * @param sdkVersionFour   four-part SDK version (e.g. {@code 5.3.0.0})
     * @param owner            owner string
     */
    public void generateAarDescriptor(
            File outputFile,
            String aarFileName,
            String adapterReference,
            String sdkVersionFour,
            String owner) throws IOException {

        String date = new SimpleDateFormat(DATE_FORMAT, Locale.ROOT).format(new Date());
        String effectiveOwner = (owner == null || owner.isEmpty())
            ? System.getProperty("user.name", "unknown") : owner;

        // Derive paths from adapterReference:
        // /path/to/Foo.adapter#frag  →  adapterFilePath = /path/to/Foo.adapter
        // repoConfigUrl = path/to/Foo (no leading slash, no extension)
        // instanceID = Foo
        String adapterFilePath = adapterReference != null ? adapterReference : "";
        int hash = adapterFilePath.indexOf('#');
        if (hash >= 0) adapterFilePath = adapterFilePath.substring(0, hash);

        String repoConfigUrl = adapterFilePath.startsWith("/")
            ? adapterFilePath.substring(1) : adapterFilePath;
        int lastDot = repoConfigUrl.lastIndexOf('.');
        if (lastDot > 0) repoConfigUrl = repoConfigUrl.substring(0, lastDot);

        String instanceID = repoConfigUrl;
        int lastSlash = instanceID.lastIndexOf('/');
        if (lastSlash >= 0) instanceID = instanceID.substring(lastSlash + 1);

        // EXTERNAL_RESOURCE_DEPENDENCY: AE schemas + the adapter reference
        String extDep = "/AESchemas/ae.aeschema,"
            + (adapterReference != null ? adapterReference : "")
            + ",/AESchemas/ae/BW/AESchema.aeschema";

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<DeploymentDescriptors xmlns=\"http://www.tibco.com/xmlns/dd\">\n");
        sb.append("    <name>").append(escape(aarFileName)).append("</name>\n");
        sb.append("    <version>1</version>\n");
        sb.append("    <owner>").append(escape(effectiveOwner)).append("</owner>\n");
        sb.append("    <creationDate>").append(date).append("</creationDate>\n");

        sb.append("    <DeploymentDescriptorFactory>\n");
        sb.append("        <name>{http://www.tibco.com/xmlns/dd}StartAsOneOf</name>\n");
        sb.append("        <deploymentDescriptorFactoryClassName>com.tibco.archive.helpers.StartAsOneOf</deploymentDescriptorFactoryClassName>\n");
        sb.append("    </DeploymentDescriptorFactory>\n");
        sb.append("    <StartAsOneOf>\n");
        sb.append("        <name>StartAsOneOf</name>\n");
        sb.append("        <ComponentSoftwareReference>\n");
        sb.append("            <componentSoftwareName>adapter</componentSoftwareName>\n");
        sb.append("            <minimumComponentSoftwareVersion>").append(escape(sdkVersionFour)).append("</minimumComponentSoftwareVersion>\n");
        sb.append("            <minimumTRAVersion>5.1.0.0</minimumTRAVersion>\n");
        sb.append("            <configVersion>").append(escape(sdkVersionFour)).append("</configVersion>\n");
        sb.append("            <keyword>Adapter</keyword>\n");
        sb.append("        </ComponentSoftwareReference>\n");
        sb.append("    </StartAsOneOf>\n");

        sb.append("    <DeploymentDescriptorFactory>\n");
        sb.append("        <name>{http://www.tibco.com/xmlns/dd}NameValuePairs</name>\n");
        sb.append("        <deploymentDescriptorFactoryClassName>com.tibco.archive.helpers.NameValuePairs</deploymentDescriptorFactoryClassName>\n");
        sb.append("    </DeploymentDescriptorFactory>\n");
        sb.append("    <NameValuePairs>\n");
        sb.append("        <name>EXTERNAL_DEPENDENCIES</name>\n");
        sb.append("        <NameValuePair>\n");
        sb.append("            <name>EXTERNAL_RESOURCE_DEPENDENCY</name>\n");
        sb.append("            <value>").append(escape(extDep)).append("</value>\n");
        sb.append("            <description>External resource configuration required by the archive.</description>\n");
        sb.append("            <requiresConfiguration>false</requiresConfiguration>\n");
        sb.append("            <disableConfigureAtDeployment>true</disableConfigureAtDeployment>\n");
        sb.append("        </NameValuePair>\n");
        sb.append("    </NameValuePairs>\n");

        sb.append("    <DeploymentDescriptorFactory>\n");
        sb.append("        <name>{http://www.tibco.com/xmlns/configurl}RepoConfigUrl</name>\n");
        sb.append("        <deploymentDescriptorFactoryClassName>com.tibco.dd.repo.RepoConfigUrl</deploymentDescriptorFactoryClassName>\n");
        sb.append("        <deploymentDescriptorXsdFileName>com/tibco/dd/repo/RepoConfigUrl.xsd</deploymentDescriptorXsdFileName>\n");
        sb.append("    </DeploymentDescriptorFactory>\n");
        sb.append("    <configurl:RepoConfigUrl xmlns:configurl=\"http://www.tibco.com/xmlns/configurl\">\n");
        sb.append("        <name>TIBCO Repository Server Configuration URL</name>\n");
        sb.append("        <configurl:repoConfigUrl>").append(escape(repoConfigUrl)).append("</configurl:repoConfigUrl>\n");
        sb.append("        <configurl:instanceID>").append(escape(instanceID)).append("</configurl:instanceID>\n");
        sb.append("    </configurl:RepoConfigUrl>\n");
        sb.append("</DeploymentDescriptors>\n");

        write(outputFile, sb.toString());
    }

    private void appendFileAlias(StringBuilder sb, String fileName) {
        // alias name uses the filename with dots replaced except extension
        // Convention from real EARs: tibco.alias.<filename-with-extension>
        String aliasName = "tibco.alias." + fileName;
        sb.append("        <NameValuePair>\n");
        sb.append("            <name>").append(escape(aliasName)).append("</name>\n");
        sb.append("            <value>").append(escape(fileName)).append("</value>\n");
        sb.append("            <description/>\n");
        sb.append("            <requiresConfiguration>false</requiresConfiguration>\n");
        sb.append("            <disableConfigureAtDeployment>true</disableConfigureAtDeployment>\n");
        sb.append("        </NameValuePair>\n");
    }

    private void appendGlobalVar(StringBuilder sb, SubstVarParser.GlobalVariable var) {
        String tag = getGlobalVarTag(var.type);
        String value = var.value;
        if ("boolean".equalsIgnoreCase(var.type)) {
            if ("1".equals(value) || "true".equalsIgnoreCase(value)) {
                value = "true";
            } else {
                value = "false";
            }
        }
        sb.append("        <").append(tag).append(">\n");
        sb.append("            <name>").append(escape(var.name)).append("</name>\n");
        appendValue(sb, "            ", value);
        if (var.description != null && !var.description.isEmpty()) {
            sb.append("            <description>").append(escape(var.description)).append("</description>\n");
        }
        sb.append("            <requiresConfiguration>").append(var.requiresConfiguration).append("</requiresConfiguration>\n");
        sb.append("        </").append(tag).append(">\n");
    }

    private String getGlobalVarTag(String type) {
        if (type == null) return "NameValuePair";
        switch (type.toLowerCase(Locale.ROOT)) {
            case "password": return "NameValuePairPassword";
            case "boolean":  return "NameValuePairBoolean";
            case "integer":  return "NameValuePairInteger";
            default:         return "NameValuePair";
        }
    }

    // -----------------------------------------------------------------------
    //  PAR-level TIBCO.xml
    // -----------------------------------------------------------------------

    /**
     * Generates the PAR-level TIBCO.xml deployment descriptor.
     *
     * @param outputFile        target file to write
     * @param parFileName       PAR file name (e.g. "Process Archive.par")
     * @param processes         metadata extracted from each .process file in the PAR
     * @param sarPaths          paths of SAR resource files (with leading "/") for EXTERNAL_DEPENDENCIES
     * @param jdbcCheckpointPaths paths of JDBC shared resources (e.g. "/0SharedResource/JDBC/Foo")
     *                          listed as available checkpoint resources in BwCheckpoint
     * @param owner             owner string
     */
    public void generateParDescriptor(
            File outputFile,
            String parFileName,
            List<ProcessParser.ProcessMetadata> processes,
            List<String> sarPaths,
            List<String> jdbcCheckpointPaths,
            String owner) throws IOException {

        String date = new SimpleDateFormat(DATE_FORMAT, Locale.ROOT).format(new Date());
        String effectiveOwner = (owner == null || owner.isEmpty())
            ? System.getProperty("user.name", "unknown") : owner;

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<DeploymentDescriptors xmlns=\"http://www.tibco.com/xmlns/dd\">\n");
        sb.append("    <name>").append(escape(parFileName)).append("</name>\n");
        sb.append("    <version>1</version>\n");
        sb.append("    <owner>").append(escape(effectiveOwner)).append("</owner>\n");
        sb.append("    <creationDate>").append(date).append("</creationDate>\n");

        // StartAsOneOf: engine version requirement
        sb.append("    <DeploymentDescriptorFactory>\n");
        sb.append("        <name>{http://www.tibco.com/xmlns/dd}StartAsOneOf</name>\n");
        sb.append("        <deploymentDescriptorFactoryClassName>com.tibco.archive.helpers.StartAsOneOf</deploymentDescriptorFactoryClassName>\n");
        sb.append("    </DeploymentDescriptorFactory>\n");
        sb.append("    <StartAsOneOf>\n");
        sb.append("        <name>StartAsOneOf</name>\n");
        sb.append("        <ComponentSoftwareReference>\n");
        sb.append("            <componentSoftwareName>bwengine</componentSoftwareName>\n");
        sb.append("            <minimumComponentSoftwareVersion>5.3.0.0</minimumComponentSoftwareVersion>\n");
        sb.append("            <minimumTRAVersion>5.3.0.0</minimumTRAVersion>\n");
        sb.append("            <configVersion>5.3.0.0</configVersion>\n");
        sb.append("        </ComponentSoftwareReference>\n");
        sb.append("    </StartAsOneOf>\n");

        // NameValuePairs factory
        sb.append("    <DeploymentDescriptorFactory>\n");
        sb.append("        <name>{http://www.tibco.com/xmlns/dd}NameValuePairs</name>\n");
        sb.append("        <deploymentDescriptorFactoryClassName>com.tibco.archive.helpers.NameValuePairs</deploymentDescriptorFactoryClassName>\n");
        sb.append("    </DeploymentDescriptorFactory>\n");

        // EXTERNAL_DEPENDENCIES: one NameValuePair with all resources comma-separated.
        // Includes only processes WITH starters, plus all SAR resource files.
        // The block is always emitted (even when empty), matching buildEAR behaviour.
        {
            List<String> allPaths = new ArrayList<>();
            for (ProcessParser.ProcessMetadata proc : processes) {
                if (proc.hasStarter) {
                    allPaths.add(proc.getReferencePath());
                }
            }
            if (sarPaths != null) {
                allPaths.addAll(sarPaths);
            }
            sb.append("    <NameValuePairs>\n");
            sb.append("        <name>EXTERNAL_DEPENDENCIES</name>\n");
            if (!allPaths.isEmpty()) {
                String value = String.join(",", allPaths);
                sb.append("        <NameValuePair>\n");
                sb.append("            <name>EXTERNAL_RESOURCE_DEPENDENCY</name>\n");
                sb.append("            <value>").append(escape(value)).append("</value>\n");
                sb.append("            <description>External resource configuration required by the archive.</description>\n");
                sb.append("            <requiresConfiguration>false</requiresConfiguration>\n");
                sb.append("            <disableConfigureAtDeployment>true</disableConfigureAtDeployment>\n");
                sb.append("        </NameValuePair>\n");
            }
            sb.append("    </NameValuePairs>\n");
        }

        // BwCheckpoint
        sb.append("    <DeploymentDescriptorFactory>\n");
        sb.append("        <name>{http://www.tibco.com/xmlns/checkpoint}BwCheckpoint</name>\n");
        sb.append("        <deploymentDescriptorFactoryClassName>com.tibco.dd.bw.BwCheckpoint</deploymentDescriptorFactoryClassName>\n");
        sb.append("        <deploymentDescriptorXsdFileName>com/tibco/dd/bw/BwCheckpoint.xsd</deploymentDescriptorXsdFileName>\n");
        sb.append("    </DeploymentDescriptorFactory>\n");
        sb.append("    <chk:BwCheckpoint xmlns:chk=\"http://www.tibco.com/xmlns/checkpoint\">\n");
        sb.append("        <name>TIBCO BusinessWorks Checkpoint Data Repository</name>\n");
        if (jdbcCheckpointPaths != null) {
            for (String path : jdbcCheckpointPaths) {
                sb.append("        <chk:availableSharedResourceName>").append(escape(path)).append("</chk:availableSharedResourceName>\n");
            }
        }
        sb.append("        <chk:useSharedResource>false</chk:useSharedResource>\n");
        String parBase = parFileName.contains(".") ? parFileName.substring(0, parFileName.lastIndexOf('.')) : parFileName;
        String tablePrefix = parBase.replace(" ", "_").replace("Archive", "Ar") + "_" + (parBase.hashCode() & 0x7fffffff);
        sb.append("        <!--<chk:tablePrefix>").append(tablePrefix).append("</chk:tablePrefix>-->\n");
        sb.append("    </chk:BwCheckpoint>\n");

        // BwBPConfigurations: one entry per process that has a starter
        sb.append("    <DeploymentDescriptorFactory>\n");
        sb.append("        <name>{http://www.tibco.com/xmlns/pdconfiguration}BwBPConfigurations</name>\n");
        sb.append("        <deploymentDescriptorFactoryClassName>com.tibco.dd.bw.BwBPConfigurations</deploymentDescriptorFactoryClassName>\n");
        sb.append("        <deploymentDescriptorXsdFileName>com/tibco/dd/bw/BwBPConfigurations.xsd</deploymentDescriptorXsdFileName>\n");
        sb.append("    </DeploymentDescriptorFactory>\n");
        sb.append("    <pd:BwBPConfigurations xmlns:pd=\"http://www.tibco.com/xmlns/pdconfiguration\">\n");
        sb.append("        <name>TIBCO BusinessWorks Process Configurations</name>\n");
        for (ProcessParser.ProcessMetadata proc : processes) {
            if (proc.hasStarter) {
                sb.append("        <pd:BwBPConfiguration>\n");
                sb.append("            <pd:processDefinitionName>").append(escape(proc.name)).append("</pd:processDefinitionName>\n");
                sb.append("            <pd:isDynamicCallProcess>false</pd:isDynamicCallProcess>\n");
                sb.append("            <pd:processDefinitionStarterName>").append(escape(proc.starterName)).append("</pd:processDefinitionStarterName>\n");
                sb.append("            <pd:enabled>true</pd:enabled>\n");
                sb.append("            <pd:maxJobs>0</pd:maxJobs>\n");
                sb.append("            <pd:activation>true</pd:activation>\n");
                sb.append("            <!--<pd:flowLimit>0</pd:flowLimit>-->\n");
                sb.append("        </pd:BwBPConfiguration>\n");
            }
        }
        sb.append("    </pd:BwBPConfigurations>\n");

        // SupportsFaultTolerance
        sb.append("    <DeploymentDescriptorFactory>\n");
        sb.append("        <name>{http://www.tibco.com/xmlns/dd}SupportsFaultTolerance</name>\n");
        sb.append("        <deploymentDescriptorFactoryClassName>com.tibco.archive.helpers.SupportsFaultTolerance</deploymentDescriptorFactoryClassName>\n");
        sb.append("    </DeploymentDescriptorFactory>\n");
        sb.append("    <SupportsFaultTolerance>\n");
        sb.append("        <name>SupportsFaultTolerance</name>\n");
        sb.append("    </SupportsFaultTolerance>\n");

        // Adapter SDK Properties (standard BW engine properties)
        sb.append("    <NameValuePairs>\n");
        sb.append("        <name>Adapter SDK Properties</name>\n");
        appendAdapterSdkProperty(sb, "Trace.Task.*", "false",
            "Activity Trace Controls activity invocation trace");
        appendAdapterSdkProperty(sb, "EnableMemorySavingMode", "false",
            "Memory Saving Mode controls process variable memory saving mode");
        appendAdapterSdkProperty(sb, "bw.engine.enableJobRecovery", "false",
            "maintain checkpoints for failed processes enables the preservation of checkpoints for process instances that end with a fault");
        appendAdapterSdkProperty(sb, "bw.engine.autoCheckpointRestart", "true",
            "auto checkpoint restart controls automatic restarting of checkpoints when engine first starts up");
        appendAdapterSdkProperty(sb, "bw.engine.jobstats.enable", "false",
            "maintain job history file controls maintence of job history csv file");
        appendAdapterSdkProperty(sb, "log.file.encoding", "",
            "set log file encoding controls log file encoding");
        appendAdapterSdkProperty(sb, "bw.engine.emaEnabled", "false",
            "Enables Enterprise Management Advisor Interface Enables Enterprise Management Advisor driven process control based on resource state");
        appendAdapterSdkProperty(sb, "bw.container.service", "",
            "BW Service Container Enables BW engine to be hosted within a container");
        appendAdapterSdkProperty(sb, "bw.container.service.rmi.port", "9995",
            "BW Service Container RMI Port Enables Container Communication Port");
        appendAdapterSdkProperty(sb, "bw.platform.services.retreiveresources.Enabled", "false",
            "Built-in Resource Provider Activation Enables Built in Resource Provider");
        appendAdapterSdkProperty(sb, "bw.platform.services.retreiveresources.Hostname", "localhost",
            "Built-in Resource Provider Host Sets Built-in Resource Provider Host Name");
        appendAdapterSdkProperty(sb, "bw.platform.services.retreiveresources.Httpport", "8010",
            "Built-in Resource Provider Http Port Sets Built-in Resource Provider Http Port Number");
        appendAdapterSdkProperty(sb, "bw.platform.services.retreiveresources.defaultEncoding", "ISO8859_1",
            "Built-in Resource Provider Default Encoding Sets Built-in Resource Provider defaultEncoding");
        appendAdapterSdkProperty(sb, "bw.platform.services.retreiveresources.enableLookups", "false",
            "Built-in Resource Provider DNS Lookups Enables Built-in Resource Provider DNS Lookups");
        appendAdapterSdkProperty(sb, "bw.platform.services.retreiveresources.isSecure", "false",
            "Built-in Resource Provider Secure Socket Layer(SSL) support Enables Built-in Resource Provider Secure Socket Layer(SSL) support");
        appendAdapterSdkProperty(sb, "bw.platform.services.retreiveresources.identity", "/Identity_HTTPConnection.id",
            "Built-in Resource Provider Identity  Sets Built-in Resource Provider Identity if Secure Socket Layer(SSL) support is enabled");
        appendAdapterSdkProperty(sb, "bw.log4j.configuration", "",
            "Log4j Configuration File Log4j Configuration file path");
        appendAdapterSdkProperty(sb, "java.extended.properties", "",
            "Java Extended Properties Java Extended Properties");
        sb.append("    </NameValuePairs>\n");

        sb.append("</DeploymentDescriptors>\n");

        write(outputFile, sb.toString());
    }

    private void appendAdapterSdkProperty(StringBuilder sb, String name, String value, String description) {
        sb.append("        <NameValuePair>\n");
        sb.append("            <name>").append(escape(name)).append("</name>\n");
        appendValue(sb, "            ", value);
        sb.append("            <description>").append(escape(description)).append("</description>\n");
        sb.append("            <requiresConfiguration>false</requiresConfiguration>\n");
        sb.append("        </NameValuePair>\n");
    }

    /** Appends {@code <value>text</value>} or {@code <value/>} when the value is empty. */
    private void appendValue(StringBuilder sb, String indent, String value) {
        if (value == null || value.isEmpty()) {
            sb.append(indent).append("<value/>\n");
        } else {
            sb.append(indent).append("<value>").append(escape(value)).append("</value>\n");
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static void write(File file, String content) throws IOException {
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.isDirectory() && !parentDir.mkdirs()) {
            throw new IOException("Failed to create directory: " + parentDir);
        }
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }
}

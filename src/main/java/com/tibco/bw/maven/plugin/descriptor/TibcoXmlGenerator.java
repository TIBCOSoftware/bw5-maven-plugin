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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    //  AAR-level TIBCO.xml — AESDK adapter (auto-discovered .adXXX instance files)
    // -----------------------------------------------------------------------

    /**
     * A single SDK property from a {@code com/tibco/deployment/{adapter}.xml} resource.
     * Each property maps to one {@code <NameValuePair>} (or {@code <NameValuePairPassword>}
     * for obfuscated properties) in the AAR TIBCO.xml Adapter SDK Properties block.
     */
    public static class SdkProperty {
        public final String option;
        public final String defaultValue;
        public final String label;
        public final String description;
        public final boolean isPassword;

        public SdkProperty(String option, String defaultValue,
                           String label, String description, boolean isPassword) {
            this.option = option;
            this.defaultValue = defaultValue;
            this.label = label;
            this.description = description;
            this.isPassword = isPassword;
        }
    }

    /**
     * Generates the AAR-level TIBCO.xml for any AESDK adapter instance file
     * ({@code .adXXX}) that has no explicit archive descriptor entry.
     *
     * <p>buildear creates one AAR per adapter instance file and uses the installed adapter
     * version for {@code componentSoftwareName} and {@code minimumComponentSoftwareVersion}.
     * The {@code adapterFragName} is the {@code name} attribute of the {@code *:adapter}
     * element inside the instance file (e.g. {@code SAPAdapter}, {@code ldap},
     * {@code FileAdapter}).</p>
     *
     * @param outputFile            target file to write
     * @param aarFileName           AAR filename (e.g. {@code R3AdapterConfiguration.aar})
     * @param instanceName          adapter instance ID (filename without extension)
     * @param componentSoftwareName e.g. {@code adr3}, {@code adldap}, {@code adfiles}
     * @param adapterVersion        four-part adapter version (e.g. {@code 7.3.2.0})
     * @param adapterBwPath         absolute BW repository path of the adapter file
     *                              (e.g. {@code /R3AdapterConfiguration.adr3})
     * @param adapterFragName       fragment name for the adapter type reference
     *                              (e.g. {@code SAPAdapter}, {@code ldap})
     * @param sdkProperties         SDK properties from the bundled deployment XML,
     *                              emitted as the Adapter SDK Properties NVP block
     */
    public void generateAdapterAarDescriptor(
            File outputFile,
            String aarFileName,
            String instanceName,
            String componentSoftwareName,
            String adapterVersion,
            String adapterBwPath,
            String adapterFragName,
            List<SdkProperty> sdkProperties,
            List<String> externalDeps,
            String archiveVersion) throws IOException {

        String date = new SimpleDateFormat(DATE_FORMAT, Locale.ROOT).format(new Date());
        String owner = System.getProperty("user.name", "unknown");

        String adapterTypeFrag = adapterBwPath + "#adapter." + adapterFragName;

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<DeploymentDescriptors xmlns=\"http://www.tibco.com/xmlns/dd\">\n");
        sb.append("    <name>").append(escape(aarFileName)).append("</name>\n");
        sb.append("    <version>").append(escape(effectiveVersion(archiveVersion))).append("</version>\n");
        sb.append("    <owner>").append(escape(owner)).append("</owner>\n");
        sb.append("    <creationDate>").append(date).append("</creationDate>\n");

        sb.append("    <DeploymentDescriptorFactory>\n");
        sb.append("        <name>{http://www.tibco.com/xmlns/dd}StartAsOneOf</name>\n");
        sb.append("        <deploymentDescriptorFactoryClassName>com.tibco.archive.helpers.StartAsOneOf</deploymentDescriptorFactoryClassName>\n");
        sb.append("    </DeploymentDescriptorFactory>\n");
        sb.append("    <StartAsOneOf>\n");
        sb.append("        <name>StartAsOneOf</name>\n");
        sb.append("        <ComponentSoftwareReference>\n");
        sb.append("            <componentSoftwareName>").append(escape(componentSoftwareName)).append("</componentSoftwareName>\n");
        sb.append("            <minimumComponentSoftwareVersion>").append(escape(adapterVersion)).append("</minimumComponentSoftwareVersion>\n");
        sb.append("            <minimumTRAVersion>5.1.0.0</minimumTRAVersion>\n");
        sb.append("            <configVersion>").append(escape(adapterVersion)).append("</configVersion>\n");
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
        String depValue = (externalDeps != null && !externalDeps.isEmpty())
                ? String.join(",", externalDeps)
                : adapterTypeFrag;
        sb.append("            <value>").append(escape(depValue)).append("</value>\n");
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
        sb.append("        <configurl:repoConfigUrl>").append(escape(instanceName)).append("</configurl:repoConfigUrl>\n");
        sb.append("        <configurl:instanceID>").append(escape(instanceName)).append("</configurl:instanceID>\n");
        sb.append("    </configurl:RepoConfigUrl>\n");

        appendSdkProperties(sb, sdkProperties);

        sb.append("</DeploymentDescriptors>\n");

        write(outputFile, sb.toString());
    }

    /**
     * Appends the {@code Adapter SDK Properties} NameValuePairs block, one entry per
     * SDK property loaded from the bundled {@code com/tibco/deployment/{adapter}.xml}
     * resource. Password properties use {@code NameValuePairPassword}. No-op when the
     * list is null or empty.
     */
    private void appendSdkProperties(StringBuilder sb, List<SdkProperty> sdkProperties) {
        if (sdkProperties == null || sdkProperties.isEmpty()) return;
        sb.append("    <NameValuePairs>\n");
        sb.append("        <name>Adapter SDK Properties</name>\n");
        for (SdkProperty p : sdkProperties) {
            String tag = p.isPassword ? "NameValuePairPassword" : "NameValuePair";
            sb.append("        <").append(tag).append(">\n");
            sb.append("            <name>").append(escape(p.option)).append("</name>\n");
            String val = (p.defaultValue == null || p.defaultValue.isEmpty())
                    ? null : p.defaultValue;
            if (val == null) {
                sb.append("            <value/>\n");
            } else {
                sb.append("            <value>").append(escape(val)).append("</value>\n");
            }
            // buildear's description rule: when the deployment <description> is empty it
            // emits an empty <description/> (the label alone is NOT used); otherwise it emits
            // "<label> <description>". The <description> element is always present.
            String desc;
            if (p.description == null || p.description.isEmpty()) {
                desc = "";
            } else if (p.label == null || p.label.isEmpty()) {
                desc = p.description;
            } else {
                desc = p.label + " " + p.description;
            }
            if (desc.isEmpty()) {
                sb.append("            <description/>\n");
            } else {
                sb.append("            <description>").append(escape(desc)).append("</description>\n");
            }
            sb.append("            <requiresConfiguration>false</requiresConfiguration>\n");
            sb.append("        </").append(tag).append(">\n");
        }
        sb.append("    </NameValuePairs>\n");
    }

    // -----------------------------------------------------------------------
    //  AAR-level TIBCO.xml — generic adapter (archive descriptor entry)
    // -----------------------------------------------------------------------

    /**
     * Generates the AAR-level TIBCO.xml deployment descriptor.
     *
     * @param outputFile       target file to write
     * @param aarFileName      AAR filename (e.g. "GAC_RPC_JMS_Topic.aar")
     * @param adapterReference raw {@code adapterReference} value from the {@code .archive}
     *                         descriptor (e.g. {@code /path/Foo.adapter#adapter.GenericAdapterConfiguration})
     * @param componentSoftwareName adapter component software name from the descriptor's
     *                         {@code softwareTypeProperty} (e.g. {@code adb}, {@code adr3}).
     *                         Falls back to {@code adapter} when null/empty.
     * @param sdkVersionFour   four-part SDK version (e.g. {@code 5.3.0.0})
     * @param sdkProperties    SDK properties from the bundled deployment XML, emitted as
     *                         the Adapter SDK Properties NVP block (may be null/empty)
     * @param owner            owner string
     */
    public void generateAarDescriptor(
            File outputFile,
            String aarFileName,
            String adapterReference,
            String componentSoftwareName,
            String sdkVersionFour,
            List<SdkProperty> sdkProperties,
            List<SubstVarParser.GlobalVariable> globalVars,
            String archiveVersion,
            String owner) throws IOException {

        String effectiveCsn = (componentSoftwareName == null || componentSoftwareName.isEmpty())
            ? "adapter" : componentSoftwareName;

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
        sb.append("    <version>").append(escape(effectiveVersion(archiveVersion))).append("</version>\n");
        sb.append("    <owner>").append(escape(effectiveOwner)).append("</owner>\n");
        sb.append("    <creationDate>").append(date).append("</creationDate>\n");

        sb.append("    <DeploymentDescriptorFactory>\n");
        sb.append("        <name>{http://www.tibco.com/xmlns/dd}StartAsOneOf</name>\n");
        sb.append("        <deploymentDescriptorFactoryClassName>com.tibco.archive.helpers.StartAsOneOf</deploymentDescriptorFactoryClassName>\n");
        sb.append("    </DeploymentDescriptorFactory>\n");
        sb.append("    <StartAsOneOf>\n");
        sb.append("        <name>StartAsOneOf</name>\n");
        sb.append("        <ComponentSoftwareReference>\n");
        sb.append("            <componentSoftwareName>").append(escape(effectiveCsn)).append("</componentSoftwareName>\n");
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

        // Runtime Variables: buildear's AdapterArchiveResource lists the service-settable
        // global variables here (GVs with <serviceSettable>true</serviceSettable>), not the
        // full set of deployment-settable GVs.
        appendRuntimeVariables(sb, globalVars);

        appendSdkProperties(sb, sdkProperties);

        sb.append("</DeploymentDescriptors>\n");

        write(outputFile, sb.toString());
    }

    /**
     * Appends the {@code Runtime Variables} NameValuePairs block: the service-settable
     * global variables ({@code serviceSettable=true}). buildear emits this identical block
     * in both the PAR and the adapter AAR. {@code requiresConfiguration} on each entry still
     * reflects its {@code deploymentSettable} flag. No-op when no variable is service-settable.
     */
    private void appendRuntimeVariables(StringBuilder sb, List<SubstVarParser.GlobalVariable> globalVars) {
        List<SubstVarParser.GlobalVariable> serviceVars = new ArrayList<>();
        if (globalVars != null) {
            for (SubstVarParser.GlobalVariable v : globalVars) {
                if (v.serviceSettable) serviceVars.add(v);
            }
        }
        if (serviceVars.isEmpty()) return;
        sb.append("    <NameValuePairs>\n");
        sb.append("        <name>Runtime Variables</name>\n");
        for (SubstVarParser.GlobalVariable v : serviceVars) {
            appendGlobalVar(sb, v);
        }
        sb.append("    </NameValuePairs>\n");
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
        } else if ("MessageEncoding".equals(var.name)) {
            // buildear always stamps this fixed description on the MessageEncoding GV,
            // regardless of whether it came from vcrepo.dat, a substvar, or the default.
            sb.append("            <description>This is the encoding used the EAR.</description>\n");
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
     * @param globalVars        all global variables; those with {@code serviceSettable=true}
     *                          are listed in the {@code Runtime Variables} block
     * @param engineProperties  BW engine SDK properties loaded from the bundled
     *                          {@code com/tibco/deployment/bwengine.xml}, emitted verbatim as
     *                          the {@code Adapter SDK Properties} block (matches buildear,
     *                          which reads the same file)
     * @param owner             owner string
     */
    public void generateParDescriptor(
            File outputFile,
            String parFileName,
            List<ProcessParser.ProcessMetadata> processes,
            List<String> sarPaths,
            List<String> jdbcCheckpointPaths,
            List<SubstVarParser.GlobalVariable> globalVars,
            List<SdkProperty> engineProperties,
            String archiveVersion,
            String owner,
            List<String> declaredProcessPaths) throws IOException {

        String date = new SimpleDateFormat(DATE_FORMAT, Locale.ROOT).format(new Date());
        String effectiveOwner = (owner == null || owner.isEmpty())
            ? System.getProperty("user.name", "unknown") : owner;

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<DeploymentDescriptors xmlns=\"http://www.tibco.com/xmlns/dd\">\n");
        sb.append("    <name>").append(escape(parFileName)).append("</name>\n");
        sb.append("    <version>").append(escape(effectiveVersion(archiveVersion))).append("</version>\n");
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
        // The block is always emitted (even when empty), matching buildEAR behaviour.
        //
        // Membership — matches buildear's BaseProcessArchive.calculatedDependencies =
        //   getHiddenReferences()  → every .archive processProperty entry (the DECLARED
        //                            processes/serviceagents, starter or not), plus
        //   getArchiveDependencies() + getAssignedResources() → the non-process resources
        //                            (schemas, copybooks, wsdl, connections, adapters…).
        // So when the archive descriptor is available we list its DECLARED processProperty
        // paths verbatim (reproduces getHiddenReferences exactly — verified byte-for-byte on
        // MVS/COMPLEX/WS), and the SAR resource paths cover the non-process part. Without a
        // descriptor (auto-discovery) there is no declared list, so we approximate the entry
        // points with the starter processes.
        //
        // Order: buildear's ArchiveResource.addExternalResourceBom collects every BOM
        // entry into a single java.util.HashSet<String> and joins its iteration order — NOT
        // sorted, NOT insertion order. String.hashCode / HashMap bucketing are JVM-stable, so
        // building the same kind of set here yields byte-identical ordering for collision-free
        // sets (same fix family as the AAR EXTERNAL_RESOURCE_DEPENDENCY,
        // BwEarMojo.externalDepsInBuildearOrder).
        {
            Set<String> allPaths = new HashSet<>();
            if (declaredProcessPaths != null && !declaredProcessPaths.isEmpty()) {
                for (String p : declaredProcessPaths) {
                    if (p != null && !p.isEmpty()) {
                        allPaths.add(p.startsWith("/") ? p : "/" + p);
                    }
                }
            } else {
                for (ProcessParser.ProcessMetadata proc : processes) {
                    if (proc.hasStarter) {
                        allPaths.add(proc.getReferencePath());
                    }
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

        // Runtime Variables: the service-settable global variables (buildear lists these,
        // not the deployment-settable set). Matches buildear's ProcessDefinition
        // getReferencedServiceLevelVariables(), which — despite its name — returns every
        // global variable with serviceSettable=true (connection deployment parameters,
        // service-level GVs, …), not just those referenced by a single process.
        appendRuntimeVariables(sb, globalVars);

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

        // Adapter SDK Properties: the standard BW engine properties, read from the bundled
        // com/tibco/deployment/bwengine.xml — the same file buildear reads. This avoids
        // hardcoding the list (and, notably, does NOT include java.extended.properties, which
        // buildear never emits).
        appendSdkProperties(sb, engineProperties);

        sb.append("</DeploymentDescriptors>\n");

        write(outputFile, sb.toString());
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

    /** Archive {@code <version>} value, defaulting to {@code 1} when none is supplied. */
    private static String effectiveVersion(String archiveVersion) {
        return (archiveVersion == null || archiveVersion.isEmpty()) ? "1" : archiveVersion;
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

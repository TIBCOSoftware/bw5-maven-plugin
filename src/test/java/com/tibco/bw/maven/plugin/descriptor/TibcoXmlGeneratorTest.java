package com.tibco.bw.maven.plugin.descriptor;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.metadata.ArtifactMetadata;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.OverConstrainedVersionException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;
import org.junit.Test;

import java.io.File;
import java.util.*;

import static org.junit.Assert.*;

public class TibcoXmlGeneratorTest {

    private static final Namespace DD_NS = Namespace.getNamespace("http://www.tibco.com/xmlns/dd");

    // -----------------------------------------------------------------------
    //  EAR descriptor tests
    // -----------------------------------------------------------------------

    @Test
    public void earDescriptorVersion1() throws Exception {
        Document doc = generateEar("MyApp", "Process Archive.par",
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        assertEquals("1", child(doc.getRootElement(), "version").getTextTrim());
    }

    @Test
    public void earDescriptorName() throws Exception {
        Document doc = generateEar("MyApp", "Process Archive.par",
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        assertEquals("MyApp", child(doc.getRootElement(), "name").getTextTrim());
    }

    @Test
    public void earDescriptorModulesPathName() throws Exception {
        Document doc = generateEar("MyApp", "Process Archive.par",
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        Element modules = findNamedBlock(doc, "Modules");
        assertNotNull("Modules block missing", modules);
        assertEquals("Process Archive.par", child(modules, "pathName").getTextTrim());
    }

    @Test
    public void earDescriptorNoFileAliasesWhenNoDeps() throws Exception {
        Document doc = generateEar("MyApp", "Process Archive.par",
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        assertNull("FileAliases should be absent with no deps", findNamedBlock(doc, "FileAliases"));
    }

    @Test
    public void earDescriptorFileAliasForProjlib() throws Exception {
        Artifact projlib = artifact("my-lib", "2.0.0");
        Document doc = generateEar("MyApp", "Process Archive.par",
                Collections.singletonList(projlib), Collections.emptyList(),
                Collections.emptyList());

        Element fileAliases = findNamedBlock(doc, "FileAliases");
        assertNotNull("FileAliases block missing", fileAliases);

        Element nvp = firstNamedNvp(fileAliases, "tibco.alias.my-lib-2.0.0.projlib");
        assertNotNull("projlib alias entry missing", nvp);
        assertEquals("my-lib-2.0.0.projlib", child(nvp, "value").getTextTrim());
        assertEquals("false", child(nvp, "requiresConfiguration").getTextTrim());
        assertEquals("true", child(nvp, "disableConfigureAtDeployment").getTextTrim());
    }

    @Test
    public void earDescriptorFileAliasForJar() throws Exception {
        Artifact jar = artifact("commons-lang3", "3.12.0");
        Document doc = generateEar("MyApp", "Process Archive.par",
                Collections.emptyList(), Collections.singletonList(jar),
                Collections.emptyList());

        Element fileAliases = findNamedBlock(doc, "FileAliases");
        assertNotNull("FileAliases block missing", fileAliases);

        Element nvp = firstNamedNvp(fileAliases, "tibco.alias.commons-lang3-3.12.0.jar");
        assertNotNull("jar alias entry missing", nvp);
        assertEquals("commons-lang3-3.12.0.jar", child(nvp, "value").getTextTrim());
    }

    @Test
    public void earDescriptorMessageEncodingAppended() throws Exception {
        Document doc = generateEar("MyApp", "Process Archive.par",
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        Element globalVars = findNamedBlock(doc, "Global Variables");
        assertNotNull("Global Variables block missing", globalVars);

        Element msgEnc = firstNamedNvp(globalVars, "MessageEncoding");
        assertNotNull("MessageEncoding entry missing", msgEnc);
        assertEquals("ISO8859-1", child(msgEnc, "value").getTextTrim());
        assertEquals("false", child(msgEnc, "requiresConfiguration").getTextTrim());
    }

    @Test
    public void earDescriptorMessageEncodingNotDuplicatedWhenInSubstvar() throws Exception {
        SubstVarParser.GlobalVariable msgEnc = new SubstVarParser.GlobalVariable();
        msgEnc.name = "MessageEncoding";
        msgEnc.value = "UTF-8";
        msgEnc.type = "String";
        msgEnc.requiresConfiguration = false;

        Document doc = generateEar("MyApp", "Process Archive.par",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(msgEnc));

        Element globalVars = findNamedBlock(doc, "Global Variables");
        long count = globalVars.getChildren().stream()
                .filter(e -> "MessageEncoding".equals(childText(e, "name")))
                .count();
        assertEquals("MessageEncoding must appear exactly once", 1, count);
    }

    /**
     * Regression: buildear always stamps the fixed description
     * {@code "This is the encoding used the EAR."} on the MessageEncoding GV, even when the
     * variable is supplied without a description (e.g. injected from vcrepo.dat). Previously
     * the description was emitted only on the default-fallback path, so an injected
     * MessageEncoding came out without it.
     */
    @Test
    public void earDescriptorMessageEncodingCarriesFixedDescription() throws Exception {
        SubstVarParser.GlobalVariable msgEnc = new SubstVarParser.GlobalVariable();
        msgEnc.name = "MessageEncoding";
        msgEnc.value = "ISO8859-1";
        msgEnc.type = "String";
        msgEnc.requiresConfiguration = false;
        // no description set on the incoming GV

        Document doc = generateEar("MyApp", "Process Archive.par",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(msgEnc));

        Element globalVars = findNamedBlock(doc, "Global Variables");
        Element nvp = firstNamedNvp(globalVars, "MessageEncoding");
        assertNotNull("MessageEncoding entry missing", nvp);
        assertEquals("This is the encoding used the EAR.",
                child(nvp, "description").getTextTrim());
    }

    @Test
    public void earDescriptorGlobalVarRequiresConfigurationTrue() throws Exception {
        SubstVarParser.GlobalVariable var = new SubstVarParser.GlobalVariable();
        var.name = "ServerHost";
        var.value = "localhost";
        var.type = "String";
        var.requiresConfiguration = true;

        Document doc = generateEar("MyApp", "Process Archive.par",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(var));

        Element globalVars = findNamedBlock(doc, "Global Variables");
        Element nvp = firstNamedNvp(globalVars, "ServerHost");
        assertNotNull(nvp);
        assertEquals("true", child(nvp, "requiresConfiguration").getTextTrim());
    }

    @Test
    public void earDescriptorPasswordVarUsesPasswordTag() throws Exception {
        SubstVarParser.GlobalVariable var = new SubstVarParser.GlobalVariable();
        var.name = "DbPass";
        var.value = "";
        var.type = "password";
        var.requiresConfiguration = true;

        Document doc = generateEar("MyApp", "Process Archive.par",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(var));

        Element globalVars = findNamedBlock(doc, "Global Variables");
        // Find by tag name NameValuePairPassword
        boolean found = globalVars.getChildren("NameValuePairPassword", DD_NS).stream()
                .anyMatch(e -> "DbPass".equals(childText(e, "name")));
        assertTrue("Password variable should use NameValuePairPassword tag", found);
    }

    // -----------------------------------------------------------------------
    //  PAR descriptor tests
    // -----------------------------------------------------------------------

    private static final Namespace PD_NS =
            Namespace.getNamespace("pd", "http://www.tibco.com/xmlns/pdconfiguration");

    @Test
    public void parDescriptorVersion1() throws Exception {
        Document doc = generatePar("Process Archive.par", Collections.emptyList(), Collections.emptyList());
        assertEquals("1", child(doc.getRootElement(), "version").getTextTrim());
    }

    @Test
    public void parDescriptorExternalDepsAlwaysPresent() throws Exception {
        Document doc = generatePar("Process Archive.par", Collections.emptyList(), Collections.emptyList());
        Element extDeps = findNamedBlock(doc, "EXTERNAL_DEPENDENCIES");
        assertNotNull("EXTERNAL_DEPENDENCIES block must always be present", extDeps);
    }

    @Test
    public void parDescriptorExternalDepsEmptyOmitsInnerNvp() throws Exception {
        // When there are no starter processes and no SAR files, the EXTERNAL_DEPENDENCIES block
        // is still emitted (structural requirement) but with no inner NameValuePair.
        Document doc = generatePar("Process Archive.par", Collections.emptyList(), Collections.emptyList());
        Element extDeps = findNamedBlock(doc, "EXTERNAL_DEPENDENCIES");
        assertNotNull("EXTERNAL_DEPENDENCIES block must always be present", extDeps);
        // No inner NameValuePair should be emitted when there are no resources
        assertNull("No EXTERNAL_RESOURCE_DEPENDENCY NVP when empty",
                firstNamedNvp(extDeps, "EXTERNAL_RESOURCE_DEPENDENCY"));
    }

    @Test
    public void parDescriptorExternalDepsContainsStarterProcessPaths() throws Exception {
        ProcessParser.ProcessMetadata starter = processWithStarter("com/example/Start.process");
        ProcessParser.ProcessMetadata sub = processNoStarter("com/example/Sub.process");

        String value = getExternalDepsValue(generatePar("Process Archive.par",
                Arrays.asList(starter, sub), Collections.emptyList()));

        assertNotNull(value);
        assertTrue("starter process path must be in EXTERNAL_DEPENDENCIES",
                value.contains("/com/example/Start.process"));
        // A sub-process (no starter) is scoped by Designer's resourceDependencyMap in buildear,
        // which we cannot reproduce; we approximate the archive's entry points with starters, so
        // a non-starter sub-process is not blanket-added here.
        assertFalse("non-starter process must NOT be blanket-added to EXTERNAL_DEPENDENCIES",
                value.contains("/com/example/Sub.process"));
    }

    /**
     * Regression (BUG-M2/M3): when the archive descriptor is available, the PAR
     * EXTERNAL_RESOURCE_DEPENDENCY process members are the DECLARED processProperty entries
     * (buildear's getHiddenReferences) — every declared process, starter or not — NOT the
     * starter-filtered set. Verified byte-for-byte against buildear on MVS/COMPLEX/WS.
     */
    @Test
    public void parDescriptorExternalDepsUsesDeclaredProcessPathsWhenGiven() throws Exception {
        // Only "Start" has a starter, but all three are declared in processProperty.
        ProcessParser.ProcessMetadata starter = processWithStarter("Services/A/Start.process");
        ProcessParser.ProcessMetadata sub1 = processNoStarter("Services/A/SubProcess/Rep.process");
        ProcessParser.ProcessMetadata sub2 = processNoStarter("DomainResources/On Startup.process");
        List<String> declared = Arrays.asList(
                "/Services/A/Start.process",
                "/Services/A/SubProcess/Rep.process",
                "/DomainResources/On Startup.process");

        String value = getExternalDepsValue(generateParWithDeclared("MVS_BW_01.par",
                Arrays.asList(starter, sub1, sub2),
                Collections.singletonList("/SharedResources/CopyBook/X.cpy"),
                declared));

        assertNotNull(value);
        Set<String> members = new HashSet<>(Arrays.asList(value.split(",")));
        assertTrue(members.contains("/Services/A/Start.process"));
        assertTrue("non-starter declared process must be listed",
                members.contains("/Services/A/SubProcess/Rep.process"));
        assertTrue("non-starter declared process must be listed",
                members.contains("/DomainResources/On Startup.process"));
        assertTrue("SAR resource must still be listed",
                members.contains("/SharedResources/CopyBook/X.cpy"));
    }

    @Test
    public void parDescriptorExternalDepsContainsSarPaths() throws Exception {
        String value = getExternalDepsValue(generatePar("Process Archive.par",
                Collections.emptyList(),
                Arrays.asList("/HTTPConnection.sharedhttp", "/Books.xsd")));

        assertNotNull(value);
        assertTrue(value.contains("/HTTPConnection.sharedhttp"));
        assertTrue(value.contains("/Books.xsd"));
    }

    @Test
    public void parDescriptorExternalDepsOneSingleBlock() throws Exception {
        // EXTERNAL_DEPENDENCIES must be a single NameValuePairs block, not one per resource
        ProcessParser.ProcessMetadata p1 = processWithStarter("A.process");
        ProcessParser.ProcessMetadata p2 = processWithStarter("B.process");

        Document doc = generatePar("Process Archive.par",
                Arrays.asList(p1, p2), Collections.singletonList("/Conn.sharedhttp"));

        long extDepsCount = doc.getRootElement().getChildren().stream()
                .filter(e -> "EXTERNAL_DEPENDENCIES".equals(childText(e, "name")))
                .count();
        assertEquals("Must have exactly one EXTERNAL_DEPENDENCIES block", 1, extDepsCount);
    }

    /**
     * Regression: buildear's {@code ArchiveResource.addExternalResourceBom} collects every BOM
     * entry (starter processes AND shared resources) into a single {@code java.util.HashSet<String>}
     * and joins its iteration order — NOT sorted, NOT insertion order. The PAR
     * EXTERNAL_RESOURCE_DEPENDENCY must reproduce that HashSet ordering (same fix family as the
     * AAR EXTERNAL_RESOURCE_DEPENDENCY, Bug #8). The chosen strings have a HashSet iteration order
     * that differs from insertion order, so this fails if the code emits insertion/sorted order.
     */
    @Test
    public void parDescriptorExternalDepsUseBuildearHashSetOrder() throws Exception {
        List<ProcessParser.ProcessMetadata> procs = Arrays.asList(
                processWithStarter("Process Definition (1).process"),
                processWithStarter("Process Definition.process"),
                processWithStarter("Process Definition (2).process"));
        List<String> sar = Arrays.asList(
                "/HTTP Connection.sharedhttp", "/HTTP-Connection-1.sharedhttp");

        String value = getExternalDepsValue(generatePar("Process Archive.par", procs, sar));
        assertNotNull(value);

        Set<String> expectedSet = new HashSet<>();
        expectedSet.add("/Process Definition (1).process");
        expectedSet.add("/Process Definition.process");
        expectedSet.add("/Process Definition (2).process");
        expectedSet.add("/HTTP Connection.sharedhttp");
        expectedSet.add("/HTTP-Connection-1.sharedhttp");
        assertEquals(String.join(",", expectedSet), value);
    }

    @Test
    public void parDescriptorBwBPConfigurationForStarterProcess() throws Exception {
        ProcessParser.ProcessMetadata starter = processWithStarter("com/example/Start.process");
        Document doc = generatePar("Process Archive.par",
                Collections.singletonList(starter), Collections.emptyList());

        // BwBPConfigurations is in pd: namespace
        Element bwbp = findBwBPConfigurations(doc);
        assertNotNull("BwBPConfigurations block must be present for starter process", bwbp);

        Element config = bwbp.getChild("BwBPConfiguration", PD_NS);
        assertNotNull("BwBPConfiguration child must exist", config);

        String processName = config.getChildTextTrim("processDefinitionName", PD_NS);
        assertTrue("processDefinitionName must reference the starter process: " + processName,
                processName != null && processName.contains("Start.process"));
    }

    @Test
    public void parDescriptorBwBPConfigurationsAlwaysPresent() throws Exception {
        // BwBPConfigurations is a required structural block — always emitted even with no starters.
        // It simply has no BwBPConfiguration children when no process has a starter.
        ProcessParser.ProcessMetadata sub = processNoStarter("com/example/Sub.process");
        Document doc = generatePar("Process Archive.par",
                Collections.singletonList(sub), Collections.emptyList());

        Element bwbp = findBwBPConfigurations(doc);
        assertNotNull("BwBPConfigurations block must always be present", bwbp);
        assertTrue("No BwBPConfiguration children for non-starter process",
                bwbp.getChildren("BwBPConfiguration", PD_NS).isEmpty());
    }

    // -----------------------------------------------------------------------
    //  Runtime Variables tests (PAR and AAR) — Bug #4 / Bug B
    // -----------------------------------------------------------------------

    /**
     * Regression (Bug #4): the PAR must emit a "Runtime Variables" block of service-settable
     * GVs, never the old "TRA_PROPERTIES_VARIABLES" block. When nothing is service-settable
     * the block is absent — even if variables are deployment-settable.
     */
    @Test
    public void parDescriptorRuntimeVarsAbsentWhenNoServiceSettableVars() throws Exception {
        SubstVarParser.GlobalVariable deployOnly = new SubstVarParser.GlobalVariable();
        deployOnly.name = "ServerHost";
        deployOnly.value = "localhost";
        deployOnly.type = "String";
        deployOnly.requiresConfiguration = true;   // deploymentSettable, but NOT serviceSettable
        deployOnly.serviceSettable = false;

        Document doc = generatePar("Process Archive.par",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(deployOnly));

        assertNull("Runtime Variables must be absent when no var is service-settable",
                findNamedBlock(doc, "Runtime Variables"));
        assertNull("The old TRA_PROPERTIES_VARIABLES block must never be emitted",
                findNamedBlock(doc, "TRA_PROPERTIES_VARIABLES"));
    }

    @Test
    public void parDescriptorRuntimeVarsEmittedForServiceSettableVar() throws Exception {
        SubstVarParser.GlobalVariable conn = new SubstVarParser.GlobalVariable();
        conn.name = "IntegrationServices/SharedUtilities/Connections/JDBC/TIB-JDBC-Connection/Password";
        conn.value = "";
        conn.type = "password";
        conn.requiresConfiguration = true;
        conn.serviceSettable = true;

        Document doc = generatePar("Process Archive.par",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(conn));

        Element rtBlock = findNamedBlock(doc, "Runtime Variables");
        assertNotNull("Runtime Variables must be present for a service-settable var", rtBlock);
        Element nvp = firstNamedNvp(rtBlock,
                "IntegrationServices/SharedUtilities/Connections/JDBC/TIB-JDBC-Connection/Password");
        assertNotNull("connection GV must appear in Runtime Variables", nvp);
        assertEquals("requiresConfiguration reflects deploymentSettable",
                "true", child(nvp, "requiresConfiguration").getTextTrim());
    }

    @Test
    public void parDescriptorRuntimeVarsOnlyIncludesServiceSettableVars() throws Exception {
        SubstVarParser.GlobalVariable svc = new SubstVarParser.GlobalVariable();
        svc.name = "ApiEndpoint";
        svc.value = "";
        svc.type = "String";
        svc.requiresConfiguration = true;
        svc.serviceSettable = true;

        // deployment-settable but not service-settable (e.g. Deployment, DirLedger, queues)
        SubstVarParser.GlobalVariable deploy = new SubstVarParser.GlobalVariable();
        deploy.name = "Deployment";
        deploy.value = "App";
        deploy.type = "String";
        deploy.requiresConfiguration = true;
        deploy.serviceSettable = false;

        Document doc = generatePar("Process Archive.par",
                Collections.emptyList(), Collections.emptyList(),
                Arrays.asList(svc, deploy));

        Element rtBlock = findNamedBlock(doc, "Runtime Variables");
        assertNotNull(rtBlock);
        assertNotNull("ApiEndpoint must be in Runtime Variables", firstNamedNvp(rtBlock, "ApiEndpoint"));
        assertNull("Deployment (not service-settable) must NOT be in Runtime Variables",
                firstNamedNvp(rtBlock, "Deployment"));
    }

    /**
     * Regression (Bug #5): the PAR "Adapter SDK Properties" block is emitted from the
     * supplied engine-property list (loaded from bwengine.xml), not a hardcoded set. The
     * caller-provided properties appear; nothing extra (e.g. java.extended.properties,
     * which buildear never emits) is injected by the generator.
     */
    @Test
    public void parDescriptorEmitsSuppliedEnginePropertiesOnly() throws Exception {
        List<TibcoXmlGenerator.SdkProperty> engine = Arrays.asList(
            new TibcoXmlGenerator.SdkProperty("Trace.Task.*", "false",
                "Activity Trace", "Controls activity invocation trace", false),
            new TibcoXmlGenerator.SdkProperty("bw.log4j.configuration", "",
                "Log4j Configuration File", "Log4j Configuration file path", false));

        File tmp = File.createTempFile("par-tibco", ".xml");
        tmp.deleteOnExit();
        new TibcoXmlGenerator().generateParDescriptor(tmp, "Process Archive.par",
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(), engine, "1", "test-owner", null);
        Document doc = new SAXBuilder().build(tmp);

        Element sdk = findNamedBlock(doc, "Adapter SDK Properties");
        assertNotNull("Adapter SDK Properties block must be present in the PAR", sdk);
        assertNotNull("supplied engine property must appear", firstNamedNvp(sdk, "Trace.Task.*"));
        assertNotNull("supplied engine property must appear", firstNamedNvp(sdk, "bw.log4j.configuration"));
        assertNull("generator must NOT inject java.extended.properties",
                firstNamedNvp(sdk, "java.extended.properties"));
    }

    /**
     * Regression (Bug A-bis): the Adapter SDK Properties description must match buildear's
     * rule exactly: an empty deployment description yields an empty {@code <description/>}
     * (the label is NOT substituted); a non-empty one yields "{@code <label> <description>}"
     * with significant whitespace preserved (labels can carry trailing spaces).
     */
    @Test
    public void aarSdkPropertyDescriptionMatchesBuildearRule() throws Exception {
        List<TibcoXmlGenerator.SdkProperty> sdk = Arrays.asList(
            // empty description → empty <description/>, label ignored
            new TibcoXmlGenerator.SdkProperty("adb.stmtCache", "1",
                "Number of cache statements", "", false),
            // label + description combined
            new TibcoXmlGenerator.SdkProperty("adb.url", "",
                "Url", "The Url configured for the adapter at runtime.", false),
            // trailing space in the label must be preserved (double space before desc)
            new TibcoXmlGenerator.SdkProperty("adb.useBetweenClause", "",
                "Enable using between clause ", "use 'between' clause", false));

        File tmp = File.createTempFile("aar-tibco", ".xml");
        tmp.deleteOnExit();
        new TibcoXmlGenerator().generateAarDescriptor(tmp, "ADB.aar",
            "/X.adb#adapter.X", "adb", "7.3.2.0", sdk, Collections.emptyList(), "1", "o");
        Document doc = new SAXBuilder().build(tmp);
        Element block = findNamedBlock(doc, "Adapter SDK Properties");

        Element stmtCache = firstNamedNvp(block, "adb.stmtCache");
        Element descEl = child(stmtCache, "description");
        assertNotNull("empty-description property must still have a <description> element", descEl);
        assertEquals("empty deployment description → empty output (label NOT used)",
            "", descEl.getText());

        assertEquals("label + space + description",
            "Url The Url configured for the adapter at runtime.",
            child(firstNamedNvp(block, "adb.url"), "description").getText());

        assertEquals("trailing space in label preserved → double space",
            "Enable using between clause  use 'between' clause",
            child(firstNamedNvp(block, "adb.useBetweenClause"), "description").getText());
    }

    /**
     * Regression (Bug B): the adapter AAR must list service-settable GVs in a
     * "Runtime Variables" block — matching buildear's AdapterArchiveResource, which selects
     * variables by their {@code serviceSettable} flag (not {@code deploymentSettable}).
     */
    @Test
    public void aarDescriptorRuntimeVariablesEmittedForServiceSettableVar() throws Exception {
        SubstVarParser.GlobalVariable service = new SubstVarParser.GlobalVariable();
        service.name = "ADB_NAME";
        service.value = "MyAdb";
        service.type = "String";
        service.requiresConfiguration = true;
        service.serviceSettable = true;

        // A deployment-settable-only var must NOT appear in the Runtime Variables block.
        SubstVarParser.GlobalVariable deployOnly = new SubstVarParser.GlobalVariable();
        deployOnly.name = "ADBOpcode";
        deployOnly.value = "";
        deployOnly.type = "String";
        deployOnly.requiresConfiguration = true;
        deployOnly.serviceSettable = false;

        Document doc = generateAar("MyAdapter.aar",
                "/BusinessDomains/EAI/Adapters/MyAdapter.adapter#adapter.MyAdapter",
                java.util.Arrays.asList(service, deployOnly));

        Element rtBlock = findNamedBlock(doc, "Runtime Variables");
        assertNotNull("Runtime Variables block must be present for service-settable vars", rtBlock);
        assertNotNull("ADB_NAME (serviceSettable) must appear in Runtime Variables",
                firstNamedNvp(rtBlock, "ADB_NAME"));
        assertNull("ADBOpcode (not serviceSettable) must NOT appear in Runtime Variables",
                firstNamedNvp(rtBlock, "ADBOpcode"));
        assertNull("AAR must no longer emit the TRA_PROPERTIES_VARIABLES block",
                findNamedBlock(doc, "TRA_PROPERTIES_VARIABLES"));
    }

    /**
     * Regression (BUG-3): the archive version (from the {@code .archive} {@code <versionProperty>})
     * must be stamped into the PAR and AAR {@code <version>} — not hardcoded to 1.
     */
    @Test
    public void parAndAarUseArchiveVersion() throws Exception {
        File parTmp = File.createTempFile("par-tibco", ".xml");
        parTmp.deleteOnExit();
        new TibcoXmlGenerator().generateParDescriptor(parTmp, "Process Archive.par",
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), "7", "o", null);
        assertEquals("PAR <version> must come from the archive version",
            "7", childText(new SAXBuilder().build(parTmp).getRootElement(), "version"));

        File aarTmp = File.createTempFile("aar-tibco", ".xml");
        aarTmp.deleteOnExit();
        new TibcoXmlGenerator().generateAarDescriptor(aarTmp, "X.aar", "/X.adb#adapter.X",
            "adb", "7.3.2.0", null, Collections.emptyList(), "7", "o");
        assertEquals("AAR <version> must come from the archive version",
            "7", childText(new SAXBuilder().build(aarTmp).getRootElement(), "version"));
    }

    @Test
    public void aarDescriptorRuntimeVariablesAbsentWhenNoServiceSettableVars() throws Exception {
        SubstVarParser.GlobalVariable deployOnly = new SubstVarParser.GlobalVariable();
        deployOnly.name = "Deployment";
        deployOnly.value = "App";
        deployOnly.type = "String";
        deployOnly.requiresConfiguration = true;
        deployOnly.serviceSettable = false;

        Document doc = generateAar("MyAdapter.aar",
                "/BusinessDomains/EAI/Adapters/MyAdapter.adapter#adapter.MyAdapter",
                Collections.singletonList(deployOnly));

        assertNull("Runtime Variables must be absent in AAR when no vars are service-settable",
                findNamedBlock(doc, "Runtime Variables"));
    }

    /**
     * Regression (Bug A): the archive-descriptor AAR path must set componentSoftwareName
     * from the descriptor's softwareTypeProperty and emit an Adapter SDK Properties block
     * carrying the supplied SDK properties. Before the fix it hardcoded
     * {@code componentSoftwareName=adapter} and emitted no SDK properties, stripping the
     * AAR TIBCO.xml down to a few KB.
     */
    @Test
    public void aarDescriptorEmitsComponentSoftwareNameAndSdkProperties() throws Exception {
        List<TibcoXmlGenerator.SdkProperty> sdk = new java.util.ArrayList<>();
        sdk.add(new TibcoXmlGenerator.SdkProperty(
                "adb.url", "jdbc:oracle", "Database URL", "The database URL", false));
        sdk.add(new TibcoXmlGenerator.SdkProperty(
                "adb.password", "", "Password", "The database password", true));

        File tmp = File.createTempFile("aar-tibco", ".xml");
        tmp.deleteOnExit();
        new TibcoXmlGenerator().generateAarDescriptor(tmp, "ADB_Config.aar",
                "/Adapters/ADB_Config.adb#adapter.ADB_Config",
                "adb", "7.3.2.0", sdk, Collections.emptyList(), "1", "test-owner");
        Document doc = new SAXBuilder().build(tmp);

        // componentSoftwareName must be the adapter type, not the hardcoded "adapter"
        Element startAsOneOf = child(doc.getRootElement(), "StartAsOneOf");
        Element csr = child(startAsOneOf, "ComponentSoftwareReference");
        assertEquals("componentSoftwareName must come from softwareTypeProperty",
                "adb", childText(csr, "componentSoftwareName"));

        // Adapter SDK Properties block must carry the supplied properties
        Element sdkBlock = findNamedBlock(doc, "Adapter SDK Properties");
        assertNotNull("Adapter SDK Properties block must be present", sdkBlock);
        assertNotNull("adb.url must appear in the SDK block",
                firstNamedNvp(sdkBlock, "adb.url"));
        // password properties must use NameValuePairPassword
        boolean hasPasswordTag = sdkBlock.getChildren().stream()
                .anyMatch(e -> "NameValuePairPassword".equals(e.getName())
                        && "adb.password".equals(childText(e, "name")));
        assertTrue("adb.password must be emitted as NameValuePairPassword", hasPasswordTag);
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private Document generateEar(String earName, String parFileName,
            List<Artifact> projlibs, List<Artifact> jars,
            List<SubstVarParser.GlobalVariable> vars) throws Exception {
        File tmp = File.createTempFile("ear-tibco", ".xml");
        tmp.deleteOnExit();
        new TibcoXmlGenerator().generateEarDescriptor(tmp, earName, "1",
            java.util.Collections.singletonList(parFileName), projlibs, jars, vars, "test-owner");
        return new SAXBuilder().build(tmp);
    }

    private Document generatePar(String parFileName,
            List<ProcessParser.ProcessMetadata> processes,
            List<String> sarPaths) throws Exception {
        return generatePar(parFileName, processes, sarPaths, Collections.emptyList());
    }

    private Document generatePar(String parFileName,
            List<ProcessParser.ProcessMetadata> processes,
            List<String> sarPaths,
            List<SubstVarParser.GlobalVariable> globalVars) throws Exception {
        File tmp = File.createTempFile("par-tibco", ".xml");
        tmp.deleteOnExit();
        new TibcoXmlGenerator().generateParDescriptor(tmp, parFileName, processes, sarPaths,
            Collections.emptyList(), globalVars, Collections.emptyList(), "1", "test-owner", null);
        return new SAXBuilder().build(tmp);
    }

    /** Variant that supplies declared processProperty paths (descriptor-based PAR). */
    private Document generateParWithDeclared(String parFileName,
            List<ProcessParser.ProcessMetadata> processes,
            List<String> sarPaths,
            List<String> declaredProcessPaths) throws Exception {
        File tmp = File.createTempFile("par-tibco", ".xml");
        tmp.deleteOnExit();
        new TibcoXmlGenerator().generateParDescriptor(tmp, parFileName, processes, sarPaths,
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), "1",
            "test-owner", declaredProcessPaths);
        return new SAXBuilder().build(tmp);
    }

    private Document generateAar(String aarFileName, String adapterRef,
            List<SubstVarParser.GlobalVariable> globalVars) throws Exception {
        File tmp = File.createTempFile("aar-tibco", ".xml");
        tmp.deleteOnExit();
        new TibcoXmlGenerator().generateAarDescriptor(tmp, aarFileName, adapterRef,
            "adapter", "7.3.2.0", null, globalVars, "1", "test-owner");
        return new SAXBuilder().build(tmp);
    }

    private Element child(Element parent, String localName) {
        Element e = parent.getChild(localName, DD_NS);
        return e != null ? e : parent.getChild(localName);
    }

    private String childText(Element parent, String localName) {
        Element e = child(parent, localName);
        return e != null ? e.getTextTrim() : null;
    }

    private Element findNamedBlock(Document doc, String blockName) {
        for (Element e : doc.getRootElement().getChildren()) {
            String nameText = childText(e, "name");
            if (blockName.equals(nameText)) return e;
        }
        return null;
    }

    /**
     * Finds the BwBPConfigurations element (uses pd: namespace, not DD_NS).
     */
    private Element findBwBPConfigurations(Document doc) {
        for (Element e : doc.getRootElement().getChildren()) {
            if ("BwBPConfigurations".equals(e.getName())) return e;
        }
        return null;
    }

    /**
     * Gets the value from EXTERNAL_DEPENDENCIES > EXTERNAL_RESOURCE_DEPENDENCY > value.
     * Returns null if the block is not found; returns empty string if value is empty.
     */
    private String getExternalDepsValue(Document doc) {
        Element extDeps = findNamedBlock(doc, "EXTERNAL_DEPENDENCIES");
        if (extDeps == null) return null;
        Element nvp = firstNamedNvp(extDeps, "EXTERNAL_RESOURCE_DEPENDENCY");
        if (nvp == null) return null;
        Element valueEl = child(nvp, "value");
        return valueEl != null ? valueEl.getTextTrim() : null;
    }

    private Element firstNamedNvp(Element block, String nvpName) {
        for (Element child : block.getChildren()) {
            String name = childText(child, "name");
            if (nvpName.equals(name)) return child;
        }
        return null;
    }

    private ProcessParser.ProcessMetadata processWithStarter(String name) {
        ProcessParser.ProcessMetadata m = new ProcessParser.ProcessMetadata();
        m.name = name;
        m.hasStarter = true;
        m.starterName = "Start";
        m.starterType = "com.tibco.plugin.timer.TimerEventSource";
        return m;
    }

    private ProcessParser.ProcessMetadata processNoStarter(String name) {
        ProcessParser.ProcessMetadata m = new ProcessParser.ProcessMetadata();
        m.name = name;
        m.hasStarter = false;
        return m;
    }

    private Artifact artifact(String artifactId, String version) {
        return new StubArtifact(artifactId, version);
    }

    // Minimal Artifact stub — implements only what TibcoXmlGenerator uses
    private static class StubArtifact implements Artifact {
        private final String artifactId;
        private final String version;

        StubArtifact(String artifactId, String version) {
            this.artifactId = artifactId;
            this.version = version;
        }

        @Override public String getArtifactId() { return artifactId; }
        @Override public String getVersion() { return version; }
        @Override public String getGroupId() { return "com.example"; }
        @Override public String getType() { return "jar"; }
        @Override public String getScope() { return "compile"; }
        @Override public String getClassifier() { return null; }
        @Override public boolean hasClassifier() { return false; }
        @Override public File getFile() { return null; }
        @Override public void setFile(File destination) {}
        @Override public String getBaseVersion() { return version; }
        @Override public void setBaseVersion(String baseVersion) {}
        @Override public String getId() { return groupId() + ":" + artifactId + ":" + version; }
        @Override public String getDependencyConflictId() { return groupId() + ":" + artifactId; }
        @Override public void addMetadata(ArtifactMetadata metadata) {}
        @Override public Collection<ArtifactMetadata> getMetadataList() { return Collections.emptyList(); }
        @Override public void setRepository(ArtifactRepository remoteRepository) {}
        @Override public ArtifactRepository getRepository() { return null; }
        @Override public void updateVersion(String version, ArtifactRepository localRepository) {}
        @Override public String getDownloadUrl() { return null; }
        @Override public void setDownloadUrl(String downloadUrl) {}
        @Override public ArtifactFilter getDependencyFilter() { return null; }
        @Override public void setDependencyFilter(ArtifactFilter artifactFilter) {}
        @Override public ArtifactHandler getArtifactHandler() { return null; }
        @Override public List<String> getDependencyTrail() { return Collections.emptyList(); }
        @Override public void setDependencyTrail(List<String> dependencyTrail) {}
        @Override public void setScope(String scope) {}
        @Override public VersionRange getVersionRange() { return null; }
        @Override public void setVersionRange(VersionRange newRange) {}
        @Override public void selectVersion(String version) {}
        @Override public void setGroupId(String groupId) {}
        @Override public void setArtifactId(String artifactId) {}
        @Override public boolean isSnapshot() { return version.endsWith("SNAPSHOT"); }
        @Override public void setResolved(boolean resolved) {}
        @Override public boolean isResolved() { return true; }
        @Override public void setResolvedVersion(String version) {}
        @Override public void setVersion(String version) {}
        @Override public void setArtifactHandler(ArtifactHandler handler) {}
        @Override public boolean isRelease() { return !isSnapshot(); }
        @Override public void setRelease(boolean release) {}
        @Override public List<ArtifactVersion> getAvailableVersions() { return Collections.emptyList(); }
        @Override public void setAvailableVersions(List<ArtifactVersion> versions) {}
        @Override public boolean isOptional() { return false; }
        @Override public boolean isSelectedVersionKnown() { return true; }
        @Override public void setOptional(boolean optional) {}
        @Override public ArtifactVersion getSelectedVersion() throws OverConstrainedVersionException { return null; }
        @Override public int compareTo(Artifact o) { return 0; }

        private String groupId() { return "com.example"; }
    }
}

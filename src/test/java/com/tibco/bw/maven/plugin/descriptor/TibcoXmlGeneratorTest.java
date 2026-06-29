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
        assertFalse("non-starter process must NOT be in EXTERNAL_DEPENDENCIES",
                value.contains("/com/example/Sub.process"));
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
    //  TRA_PROPERTIES_VARIABLES tests (PAR and AAR)
    // -----------------------------------------------------------------------

    @Test
    public void parDescriptorTRAPropertiesVarsAbsentWhenNoRequiredVars() throws Exception {
        SubstVarParser.GlobalVariable optional = new SubstVarParser.GlobalVariable();
        optional.name = "ServerHost";
        optional.value = "localhost";
        optional.type = "String";
        optional.requiresConfiguration = false;

        Document doc = generatePar("Process Archive.par",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(optional));

        assertNull("TRA_PROPERTIES_VARIABLES must be absent when no vars require configuration",
                findNamedBlock(doc, "TRA_PROPERTIES_VARIABLES"));
    }

    @Test
    public void parDescriptorTRAPropertiesVarsEmittedForRequiredVar() throws Exception {
        SubstVarParser.GlobalVariable required = new SubstVarParser.GlobalVariable();
        required.name = "DbPassword";
        required.value = "";
        required.type = "password";
        required.requiresConfiguration = true;

        Document doc = generatePar("Process Archive.par",
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(required));

        Element traBlock = findNamedBlock(doc, "TRA_PROPERTIES_VARIABLES");
        assertNotNull("TRA_PROPERTIES_VARIABLES must be present when a var requiresConfiguration", traBlock);
        Element nvp = firstNamedNvp(traBlock, "DbPassword");
        assertNotNull("DbPassword must appear in TRA_PROPERTIES_VARIABLES", nvp);
        assertEquals("true", child(nvp, "requiresConfiguration").getTextTrim());
    }

    @Test
    public void parDescriptorTRAPropertiesVarsOnlyIncludesRequiredVars() throws Exception {
        SubstVarParser.GlobalVariable req = new SubstVarParser.GlobalVariable();
        req.name = "ApiKey";
        req.value = "";
        req.type = "String";
        req.requiresConfiguration = true;

        SubstVarParser.GlobalVariable opt = new SubstVarParser.GlobalVariable();
        opt.name = "LogLevel";
        opt.value = "INFO";
        opt.type = "String";
        opt.requiresConfiguration = false;

        Document doc = generatePar("Process Archive.par",
                Collections.emptyList(), Collections.emptyList(),
                Arrays.asList(req, opt));

        Element traBlock = findNamedBlock(doc, "TRA_PROPERTIES_VARIABLES");
        assertNotNull(traBlock);
        assertNotNull("ApiKey must be in TRA block", firstNamedNvp(traBlock, "ApiKey"));
        assertNull("LogLevel must NOT be in TRA block (not required)", firstNamedNvp(traBlock, "LogLevel"));
    }

    @Test
    public void aarDescriptorTRAPropertiesVarsEmittedForRequiredVar() throws Exception {
        SubstVarParser.GlobalVariable required = new SubstVarParser.GlobalVariable();
        required.name = "EndpointUrl";
        required.value = "https://example.com";
        required.type = "String";
        required.requiresConfiguration = true;

        Document doc = generateAar("MyAdapter.aar",
                "/BusinessDomains/EAI/Adapters/MyAdapter.adapter#adapter.MyAdapter",
                Collections.singletonList(required));

        Element traBlock = findNamedBlock(doc, "TRA_PROPERTIES_VARIABLES");
        assertNotNull("TRA_PROPERTIES_VARIABLES must be present in AAR for required vars", traBlock);
        assertNotNull("EndpointUrl must appear in AAR TRA block",
                firstNamedNvp(traBlock, "EndpointUrl"));
    }

    @Test
    public void aarDescriptorTRAPropertiesVarsAbsentWhenEmpty() throws Exception {
        Document doc = generateAar("MyAdapter.aar",
                "/BusinessDomains/EAI/Adapters/MyAdapter.adapter#adapter.MyAdapter",
                Collections.emptyList());

        assertNull("TRA_PROPERTIES_VARIABLES must be absent in AAR when no vars require configuration",
                findNamedBlock(doc, "TRA_PROPERTIES_VARIABLES"));
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
            Collections.emptyList(), globalVars, "test-owner");
        return new SAXBuilder().build(tmp);
    }

    private Document generateAar(String aarFileName, String adapterRef,
            List<SubstVarParser.GlobalVariable> globalVars) throws Exception {
        File tmp = File.createTempFile("aar-tibco", ".xml");
        tmp.deleteOnExit();
        new TibcoXmlGenerator().generateAarDescriptor(tmp, aarFileName, adapterRef,
            "7.3.2.0", globalVars, "test-owner");
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

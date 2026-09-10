package com.tibco.bw.maven.plugin.descriptor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link DeploymentConfigGenerator}: the AppManage-native {@code -deploy.xml}
 * (root {@code <application>}, typed Global-Variable tags, {@code <repoInstances>} and
 * {@code <services>}) and the flat {@code services.properties} file.
 */
public class DeploymentConfigGeneratorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // --- helpers ---

    private SubstVarParser.GlobalVariable gv(String name, String value, String type,
            boolean serviceSettable) {
        SubstVarParser.GlobalVariable v = new SubstVarParser.GlobalVariable();
        v.name = name;
        v.value = value;
        v.type = type;
        v.serviceSettable = serviceSettable;
        return v;
    }

    private List<DeploymentConfigGenerator.ServiceModel> oneService() {
        List<DeploymentConfigGenerator.ServiceModel.ProcessEntry> procs = new ArrayList<>();
        procs.add(new DeploymentConfigGenerator.ServiceModel.ProcessEntry(
            "MyApp/Mediation/Receive.process", "Receive_HTTPRequest"));
        procs.add(new DeploymentConfigGenerator.ServiceModel.ProcessEntry(
            "CommonCore/Startup/Echo.process", "onStartup"));
        return Arrays.asList(new DeploymentConfigGenerator.ServiceModel("MyApp-LB.par", procs));
    }

    private String readDeployXml(List<SubstVarParser.GlobalVariable> vars,
            List<DeploymentConfigGenerator.ServiceModel> services) throws Exception {
        File out = tmp.newFile("deploy.xml");
        new DeploymentConfigGenerator().generateDeployXml(out, "MyApp", "1.0.0", "", "", vars, services, null);
        return new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    //  deploy.xml — AppManage native format
    // -----------------------------------------------------------------------

    @Test
    public void deployXmlUsesApplicationRootNotApplicationManagement() throws Exception {
        String xml = readDeployXml(Arrays.asList(gv("A", "1", "String", false)), null);
        assertTrue("root must be <application> with ApplicationManagement namespace",
            xml.contains("<application xmlns=\"http://www.tibco.com/xmlns/ApplicationManagement\" name=\"MyApp\">"));
        assertFalse("must NOT wrap in <applicationManagement>", xml.contains("<applicationManagement"));
        assertTrue("<description> as element", xml.contains("<description></description>"));
        assertTrue("<contact> as element", xml.contains("<contact></contact>"));
    }

    @Test
    public void globalVariablesUseTypedTagsWithoutTypeChildren() throws Exception {
        List<SubstVarParser.GlobalVariable> vars = Arrays.asList(
            gv("Str", "hello", "String", false),
            gv("Num", "42", "Integer", false),
            gv("Flag", "1", "Boolean", false),
            gv("Secret", "s3cr3t", "Password", false));
        String xml = readDeployXml(vars, null);

        assertTrue(xml.contains("<NameValuePairInteger>"));
        assertTrue(xml.contains("<NameValuePairBoolean>"));
        assertTrue(xml.contains("<NameValuePairPassword>"));
        // boolean 1 -> true
        assertTrue("boolean value 1 normalises to true", xml.contains("<value>true</value>"));
        // AppManage format has no per-variable type metadata children
        assertFalse("no <type> child", xml.contains("<type>"));
        assertFalse("no <requiresConfiguration> child", xml.contains("<requiresConfiguration>"));
        assertFalse("no <deploymentSettable> child", xml.contains("<deploymentSettable>"));
    }

    @Test
    public void deployXmlDescriptionAndContactPopulatedAndEscaped() throws Exception {
        File out = tmp.newFile("deploy-dc.xml");
        new DeploymentConfigGenerator().generateDeployXml(out, "MyApp", "1.0.0",
                "develop:1.0.1-SNAPSHOT", "Team <A> & B, team@example.com",
                Arrays.asList(gv("A", "1", "String", false)), null, null);
        String xml = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue(xml.contains("<description>develop:1.0.1-SNAPSHOT</description>"));
        assertTrue("contact value must be XML-escaped",
                xml.contains("<contact>Team &lt;A&gt; &amp; B, team@example.com</contact>"));
    }

    @Test
    public void deployXmlDescriptionAndContactEmptyByDefault() throws Exception {
        // null description/contact render as empty elements (unchanged default behaviour)
        File out = tmp.newFile("deploy-empty.xml");
        new DeploymentConfigGenerator().generateDeployXml(out, "MyApp", "1.0.0", null, null,
                Arrays.asList(gv("A", "1", "String", false)), null, null);
        String xml = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue(xml.contains("<description></description>"));
        assertTrue(xml.contains("<contact></contact>"));
    }

    @Test
    public void deployXmlIncludesRepoInstances() throws Exception {
        String xml = readDeployXml(Arrays.asList(gv("A", "1", "String", false)), null);
        assertTrue(xml.contains("<repoInstances selected=\"local\">"));
        assertTrue(xml.contains("<localRepoInstance>"));
        assertTrue(xml.contains("<encoding>UTF-8</encoding>"));
    }

    @Test
    public void deployXmlIncludesServicesWithBwProcessesAndRuntimeVars() throws Exception {
        List<SubstVarParser.GlobalVariable> vars = Arrays.asList(
            gv("CommonCore/Cache/cacheManagerConfig", "x", "String", true),  // service-settable
            gv("Other", "y", "String", false));
        String xml = readDeployXml(vars, oneService());

        assertTrue(xml.contains("<services>"));
        assertTrue(xml.contains("<bw name=\"MyApp-LB.par\">"));
        assertTrue("machine placeholder derived from PAR name",
            xml.contains("%%MyApp-LB.par-machine%%"));
        assertTrue("bwprocess with starter",
            xml.contains("<bwprocess name=\"MyApp/Mediation/Receive.process\">"));
        assertTrue(xml.contains("<starter>Receive_HTTPRequest</starter>"));
        assertTrue("Runtime Variables lists only service-settable GVs",
            xml.contains("<name>CommonCore/Cache/cacheManagerConfig</name>"));
        assertFalse("non-service-settable GV must not appear in Runtime Variables",
            xml.contains("<NVPairs name=\"Runtime Variables\">\n            <NameValuePair>\n"
                + "                <name>Other</name>"));
        assertTrue("Adapter SDK Properties present", xml.contains("<NVPairs name=\"Adapter SDK Properties\">"));
    }

    // #14 — a deployment (gv.properties) override replaces a GV's value but must NOT leak into the
    // per-service Runtime Variables. AppManage renders the override only in the top-level Global
    // Variables block; the per-service blocks keep the EAR design-time default.
    @Test
    public void deploymentOverrideAppliesToGlobalVariablesButNotPerServiceRuntimeVars()
            throws Exception {
        // cacheManagerConfig: default from the EAR = "CommonCore/Cache/DefaultCache/",
        // overridden at deploy time (gv.properties) to "ehcache.xml".
        SubstVarParser.GlobalVariable cache =
            gv("CommonCore/Cache/cacheManagerConfig", "ehcache.xml", "String", true);
        cache.defaultValue = "CommonCore/Cache/DefaultCache/";
        String xml = readDeployXml(Arrays.asList(cache), oneService());

        // Top-level Global Variables shows the deployment override.
        int globalEnd = xml.indexOf("</NVPairs>");
        String globalBlock = xml.substring(0, globalEnd);
        assertTrue("Global Variables block must show the deployment override",
            globalBlock.contains("<value>ehcache.xml</value>"));

        // Per-service Runtime Variables (binding-level and service-level) show the EAR default.
        String services = xml.substring(xml.indexOf("<services>"));
        assertTrue("per-service Runtime Variables must show the EAR default value",
            services.contains("<value>CommonCore/Cache/DefaultCache/</value>"));
        assertFalse("deployment override must not leak into per-service Runtime Variables",
            services.contains("<value>ehcache.xml</value>"));
    }

    @Test
    public void servicePropertyMapSeedsRuntimeVarsWithDefaultNotOverride() {
        SubstVarParser.GlobalVariable cache =
            gv("CommonCore/Cache/cacheManagerConfig", "ehcache.xml", "String", true);
        cache.defaultValue = "CommonCore/Cache/DefaultCache/";
        Map<String, String> flat = new DeploymentConfigGenerator()
            .servicePropertyMap(Arrays.asList(cache), oneService());

        assertEquals("binding-level runtime var seeded from the EAR default",
            "CommonCore/Cache/DefaultCache/",
            flat.get("bw[MyApp-LB.par]/bindings/binding[]/variables/variable["
                + "CommonCore/Cache/cacheManagerConfig]"));
        assertEquals("service-level runtime var seeded from the EAR default",
            "CommonCore/Cache/DefaultCache/",
            flat.get("bw[MyApp-LB.par]/variables[Runtime Variables]/variable["
                + "CommonCore/Cache/cacheManagerConfig]"));
    }

    @Test
    public void duplicateProcessEntriesRenderOnceInServices() throws Exception {
        // A serviceagent that is both reachable by BFS and promoted from the .archive descriptor's
        // processProperty ends up twice in the in-memory discovery list. The PAR zip collapses it,
        // so the <services> block must emit a single <bwprocess> per name — not a duplicate.
        List<DeploymentConfigGenerator.ServiceModel.ProcessEntry> procs = new ArrayList<>();
        procs.add(new DeploymentConfigGenerator.ServiceModel.ProcessEntry(
            "CommonCore/SharedResources/GlobalInstance/EMSRuntimeGlobalInstance.serviceagent",
            "EMSRuntimeGlobalInstance"));
        procs.add(new DeploymentConfigGenerator.ServiceModel.ProcessEntry(
            "CommonCore/SharedResources/GlobalInstance/EMSRuntimeGlobalInstance.serviceagent",
            "EMSRuntimeGlobalInstance"));
        procs.add(new DeploymentConfigGenerator.ServiceModel.ProcessEntry(
            "MyApp/Mediation/Receive.process", "Receive_HTTPRequest"));
        List<DeploymentConfigGenerator.ServiceModel> services =
            Arrays.asList(new DeploymentConfigGenerator.ServiceModel("MyApp-LB.par", procs));

        File out = tmp.newFile("deploy-dup.xml");
        new DeploymentConfigGenerator().generateDeployXml(out, "MyApp", "1.0.0", "", "",
            new ArrayList<>(), services, null);
        String xml = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        int occurrences = xml.split(java.util.regex.Pattern.quote(
            "<bwprocess name=\"CommonCore/SharedResources/GlobalInstance/"
            + "EMSRuntimeGlobalInstance.serviceagent\">"), -1).length - 1;
        assertEquals("duplicate serviceagent must render exactly once", 1, occurrences);
        assertTrue("the distinct process must still render",
            xml.contains("<bwprocess name=\"MyApp/Mediation/Receive.process\">"));
    }

    @Test
    public void sortedProcessesDeduplicatesByName() {
        List<DeploymentConfigGenerator.ServiceModel.ProcessEntry> procs = new ArrayList<>();
        procs.add(new DeploymentConfigGenerator.ServiceModel.ProcessEntry("A/Dup.serviceagent", "s1"));
        procs.add(new DeploymentConfigGenerator.ServiceModel.ProcessEntry("A/Dup.serviceagent", "s1"));
        procs.add(new DeploymentConfigGenerator.ServiceModel.ProcessEntry("B/Other.process", "s2"));
        DeploymentConfigGenerator.ServiceModel svc =
            new DeploymentConfigGenerator.ServiceModel("MyApp-LB.par", procs);

        List<DeploymentConfigGenerator.ServiceModel.ProcessEntry> sorted = svc.sortedProcesses();
        assertEquals("duplicate name collapses to one entry", 2, sorted.size());
        assertEquals("A/Dup.serviceagent", sorted.get(0).name);
        assertEquals("B/Other.process", sorted.get(1).name);
    }

    @Test
    public void deployXmlWithoutServicesOmitsServicesBlock() throws Exception {
        String xml = readDeployXml(Arrays.asList(gv("A", "1", "String", false)), null);
        assertFalse(xml.contains("<services>"));
    }

    @Test
    public void deployXmlServicesReflectServiceOverrides() throws Exception {
        // A merged service-property map (as AppManage merge would apply) must drive the <services>
        // block: overridden values appear in the XML, not the generated defaults.
        DeploymentConfigGenerator gen = new DeploymentConfigGenerator();
        List<DeploymentConfigGenerator.ServiceModel> services = oneService();
        java.util.Map<String, String> flat = gen.servicePropertyMap(
                Arrays.asList(gv("CommonCore/Cache/cacheManagerConfig", "cfg", "String", true)), services);
        flat.put("bw[MyApp-LB.par]/bindings/binding[]/setting/java/maxHeapSize", "2048");
        flat.put("bw[MyApp-LB.par]/isFt", "true");

        File out = tmp.newFile("deploy-ovr.xml");
        gen.generateDeployXml(out, "MyApp", "1.0.0", "", "",
                Arrays.asList(gv("CommonCore/Cache/cacheManagerConfig", "cfg", "String", true)),
                services, flat);
        String xml = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue("overridden heap must appear in <services>", xml.contains("<maxHeapSize>2048</maxHeapSize>"));
        assertFalse("default heap must be gone", xml.contains("<maxHeapSize>256</maxHeapSize>"));
        assertTrue("overridden isFt must appear", xml.contains("<isFt>true</isFt>"));
    }

    // #16 — the top-level Global Variables block is the deployment-settable subset. A GV with
    // deploymentSettable=false is a design-time constant the administrator cannot change, and
    // AppManage leaves it out of the exported config. The flag is independent of serviceSettable:
    // a deployment-fixed but service-settable GV still belongs in the per-service Runtime Variables.
    @Test
    public void globalVariablesBlockOnlyListsDeploymentSettableVars() throws Exception {
        SubstVarParser.GlobalVariable settable = gv("App/Destinations/Queue", "q", "String", false);
        SubstVarParser.GlobalVariable constant = gv("CommonCore/LogCode/Event", "EVT", "String", false);
        constant.requiresConfiguration = false;
        SubstVarParser.GlobalVariable serviceOnly =
            gv("CommonCore/Cache/DefaultDiskStorePath", "/local/tibco/data/", "String", true);
        serviceOnly.requiresConfiguration = false;

        String xml = readDeployXml(Arrays.asList(settable, constant, serviceOnly), oneService());
        String global = xml.substring(0, xml.indexOf("</NVPairs>"));
        String services = xml.substring(xml.indexOf("<services>"));

        assertTrue("deployment-settable GV must be exported",
            global.contains("<name>App/Destinations/Queue</name>"));
        assertFalse("deploymentSettable=false GV must not reach the Global Variables block",
            global.contains("<name>CommonCore/LogCode/Event</name>"));
        assertFalse("a deployment-fixed GV stays out even when it is service-settable",
            global.contains("<name>CommonCore/Cache/DefaultDiskStorePath</name>"));
        assertTrue("...but it is still a per-service Runtime Variable",
            services.contains("<name>CommonCore/Cache/DefaultDiskStorePath</name>"));
    }

    // The predefined Deployment/Domain variables are assigned by BW from the deployment and domain
    // names; AppManage never exports them even though Designer marks them deployment-settable.
    @Test
    public void globalVariablesBlockOmitsRuntimeAssignedPredefinedVars() throws Exception {
        String xml = readDeployXml(Arrays.asList(
            gv("Deployment", "", "String", false),
            gv("Domain", "", "String", false),
            gv("DirTrace", "/local/tibco/logs", "String", false)), null);

        assertFalse("Deployment is assigned at deployment time", xml.contains("<name>Deployment</name>"));
        assertFalse("Domain is assigned at deployment time", xml.contains("<name>Domain</name>"));
        assertTrue("other predefined GVs are exported normally", xml.contains("<name>DirTrace</name>"));
    }

    // #15 — an Adapter SDK Property supplied purely through the service-property channel (one that
    // is not part of the fixed AppManage key set, e.g. java.extended.properties) must be rendered
    // in the <NVPairs name="Adapter SDK Properties"> block, appended after the fixed keys — the
    // same place AppManage puts it.
    @Test
    public void adapterSdkPropertiesRenderUserSuppliedExtraKeys() throws Exception {
        DeploymentConfigGenerator gen = new DeploymentConfigGenerator();
        List<DeploymentConfigGenerator.ServiceModel> services = oneService();
        Map<String, String> flat = gen.servicePropertyMap(new ArrayList<>(), services);
        String jvmOpts = "-Dfile.encoding=UTF-8 -XX:+HeapDumpOnOutOfMemoryError";
        flat.put("bw[MyApp-LB.par]/variables[Adapter SDK Properties]/variable[java.extended.properties]",
                jvmOpts);

        File out = tmp.newFile("deploy-sdk.xml");
        gen.generateDeployXml(out, "MyApp", "1.0.0", "", "", new ArrayList<>(), services, flat);
        String xml = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue("user-supplied Adapter SDK Property must appear in the deploy XML",
            xml.contains("<name>java.extended.properties</name>"));
        assertTrue("its value must be rendered", xml.contains("<value>" + jvmOpts + "</value>"));

        // AppManage appends extras after the fixed key set, last of which is bw.log4j.configuration.
        int sdk = xml.indexOf("<NVPairs name=\"Adapter SDK Properties\">");
        assertTrue("Adapter SDK Properties block must exist", sdk > 0);
        int log4j = xml.indexOf("<name>bw.log4j.configuration</name>", sdk);
        int extra = xml.indexOf("<name>java.extended.properties</name>", sdk);
        assertTrue("extra key must come after the fixed AppManage keys", extra > log4j);
    }

    @Test
    public void adapterSdkPropertiesKeepFixedKeysWhenNoExtrasSupplied() throws Exception {
        String xml = readDeployXml(new ArrayList<>(), oneService());
        int sdk = xml.indexOf("<NVPairs name=\"Adapter SDK Properties\">");
        String block = xml.substring(sdk, xml.indexOf("</NVPairs>", sdk));

        assertTrue(block.contains("<name>Trace.Task.*</name>"));
        assertTrue(block.contains("<name>bw.container.service.rmi.port</name>"));
        assertTrue(block.contains("<value>9995</value>"));
        assertTrue(block.contains("<name>bw.log4j.configuration</name>"));
        assertFalse("no extra key when the user supplied none",
            block.contains("<name>java.extended.properties</name>"));
    }

    // -----------------------------------------------------------------------
    //  services.properties — flat bw[<par>]/... map
    // -----------------------------------------------------------------------

    @Test
    public void servicePropertyMapContainsBindingsProcessesAndVars() {
        List<SubstVarParser.GlobalVariable> vars = Arrays.asList(
            gv("CommonCore/Cache/cacheManagerConfig", "cfg", "String", true));
        Map<String, String> flat =
            new DeploymentConfigGenerator().servicePropertyMap(vars, oneService());

        String p = "bw[MyApp-LB.par]";
        assertEquals("true", flat.get(p + "/enabled"));
        assertEquals("256", flat.get(p + "/bindings/binding[]/setting/java/maxHeapSize"));
        assertEquals("%%MyApp-LB.par-machine%%", flat.get(p + "/bindings/binding[]/machine"));
        assertEquals("Receive_HTTPRequest",
            flat.get(p + "/bwprocesses/bwprocess[MyApp/Mediation/Receive.process]/starter"));
        assertEquals("cfg",
            flat.get(p + "/variables[Runtime Variables]/variable[CommonCore/Cache/cacheManagerConfig]"));
        assertEquals("9995",
            flat.get(p + "/variables[Adapter SDK Properties]/variable[bw.container.service.rmi.port]"));
    }

    @Test
    public void servicesPropertiesFileEscapesSpacesAndSeparators() throws Exception {
        Map<String, String> flat =
            new DeploymentConfigGenerator().servicePropertyMap(
                new ArrayList<>(), oneService());
        File out = tmp.newFile("services.properties");
        new DeploymentConfigGenerator().generateServicesProperties(out, "MyApp", "1.0.0", flat);
        String txt = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        // Java .properties escaping: spaces in the key and '=' separators are escaped.
        assertTrue("space in 'Adapter SDK Properties' key is escaped",
            txt.contains("variables[Adapter\\ SDK\\ Properties]"));
        assertTrue(txt.contains("bw[MyApp-LB.par]/enabled=true"));
    }

    // -----------------------------------------------------------------------
    //  #12: service-binding resolution — named bindings + wildcard expansion
    // -----------------------------------------------------------------------

    /** Builds the generated template map for a single PAR (empty-named binding + defaults). */
    private Map<String, String> template() {
        return new DeploymentConfigGenerator().servicePropertyMap(new ArrayList<>(), oneService());
    }

    @Test
    public void namedBindingOverrideRenamesTemplateBindingAndMergesValues() {
        Map<String, String> merged = template();
        String base = "bw[MyApp-LB.par]/bindings/binding[MyApp-LB-esb06]";
        // Override supplies a NAMED binding with only two keys.
        merged.put(base + "/setting/java/initHeapSize", "256");
        merged.put(base + "/setting/java/maxHeapSize", "512");

        java.util.Set<String> explicit = new java.util.HashSet<>(Arrays.asList(
            base + "/setting/java/initHeapSize", base + "/setting/java/maxHeapSize"));

        Map<String, String> r = new DeploymentConfigGenerator().resolveServiceBindings(merged, explicit);

        // Empty-named template binding is gone; a single named binding carries defaults + overrides.
        assertFalse("empty-named template binding must be removed",
            r.containsKey("bw[MyApp-LB.par]/bindings/binding[]/machine"));
        assertEquals("override applied", "256", r.get(base + "/setting/java/initHeapSize"));
        assertEquals("override applied", "512", r.get(base + "/setting/java/maxHeapSize"));
        // Template default carried over onto the named binding (was under binding[]).
        assertEquals("template default carried over to named binding",
            "%%MyApp-LB.par-machine%%", r.get(base + "/machine"));
        assertEquals("template default carried over to named binding",
            "8", r.get(base + "/setting/threadCount"));
    }

    @Test
    public void wildcardExpandsOntoNamedBindingAndIsDropped() {
        Map<String, String> merged = template();
        String base = "bw[MyApp-LB.par]/bindings/binding[MyApp-LB-esb06]";
        merged.put(base + "/setting/java/initHeapSize", "256");   // explicit override -> names the binding
        // Common/global wildcard entries (suffix + full-glob) as they appear in a shared service file.
        merged.put("bw[*]/bindings/binding[*esb06]/machine", "host06.example.com");
        merged.put("bw[*]/bindings/binding[*]/product/version", "5.16");
        merged.put("bw[*]/bindings/binding[*]/product/type", "BW");

        java.util.Set<String> explicit = new java.util.HashSet<>(Arrays.asList(
            base + "/setting/java/initHeapSize"));

        Map<String, String> r = new DeploymentConfigGenerator().resolveServiceBindings(merged, explicit);

        assertEquals("suffix wildcard resolved onto the named binding",
            "host06.example.com", r.get(base + "/machine"));
        assertEquals("full wildcard resolved onto the named binding",
            "5.16", r.get(base + "/product/version"));
        assertEquals("BW", r.get(base + "/product/type"));
        // No structural wildcard keys survive. A literal '*' inside a variable[...] name
        // (e.g. the Adapter-SDK 'Trace.Task.*' property) is not a glob and legitimately remains.
        for (String k : r.keySet()) {
            assertFalse("no structural wildcard key must remain: " + k, k.contains("[*"));
        }
    }

    @Test
    public void explicitOverrideBeatsWildcard() {
        Map<String, String> merged = template();
        String base = "bw[MyApp-LB.par]/bindings/binding[MyApp-LB-esb06]";
        merged.put(base + "/setting/threadCount", "16");                     // explicit
        merged.put("bw[*]/bindings/binding[*]/setting/threadCount", "99");   // wildcard on same key

        java.util.Set<String> explicit = new java.util.HashSet<>(Arrays.asList(
            base + "/setting/threadCount"));

        Map<String, String> r = new DeploymentConfigGenerator().resolveServiceBindings(merged, explicit);

        assertEquals("explicit value must win over the wildcard", "16",
            r.get(base + "/setting/threadCount"));
    }

    @Test
    public void wildcardMatchingBwProcessCrossesSlashesInsideBrackets() {
        Map<String, String> merged = template();
        // The template emits bwprocess keys whose names contain '/'.
        merged.put("bw[*]/bwprocesses/bwprocess[*]/flowLimit", "16");

        Map<String, String> r = new DeploymentConfigGenerator().resolveServiceBindings(
            merged, java.util.Collections.<String>emptySet());

        assertEquals("wildcard must match a process name containing '/'", "16",
            r.get("bw[MyApp-LB.par]/bwprocesses/bwprocess[MyApp/Mediation/Receive.process]/flowLimit"));
    }

    @Test
    public void namedBindingRendersInDeployXml() throws Exception {
        Map<String, String> merged = template();
        String base = "bw[MyApp-LB.par]/bindings/binding[MyApp-LB-esb06]";
        merged.put(base + "/setting/java/initHeapSize", "256");
        merged.put("bw[*]/bindings/binding[*esb06]/machine", "host06.example.com");
        Map<String, String> r = new DeploymentConfigGenerator().resolveServiceBindings(
            merged, new java.util.HashSet<>(Arrays.asList(base + "/setting/java/initHeapSize")));

        File out = tmp.newFile("deploy-named.xml");
        new DeploymentConfigGenerator().generateDeployXml(out, "MyApp", "1.0.0", "", "",
            new ArrayList<>(), oneService(), r);
        String xml = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue("XML must render the resolved binding name",
            xml.contains("<binding name=\"MyApp-LB-esb06\">"));
        assertFalse("XML must not render an empty-named binding",
            xml.contains("<binding name=\"\">"));
        assertTrue("wildcard-resolved machine must appear in the XML",
            xml.contains("<machine>host06.example.com</machine>"));
        assertTrue("override heap size must appear in the XML",
            xml.contains("<initHeapSize>256</initHeapSize>"));
    }

    @Test
    public void literalVariableNameEndingInStarIsNotTreatedAsWildcard() {
        // 'Trace.Task.*' is a real BW Adapter-SDK engine property whose name ends in '.*'.
        // It must survive resolution as a concrete variable, not be mistaken for a glob and dropped.
        Map<String, String> merged = template();
        String v = "bw[MyApp-LB.par]/variables[Adapter SDK Properties]/variable[Trace.Task.*]";
        merged.put(v, "false");

        Map<String, String> r = new DeploymentConfigGenerator().resolveServiceBindings(
            merged, new java.util.HashSet<>(Arrays.asList(v)));

        assertEquals("literal variable name ending in '*' must be preserved", "false", r.get(v));
    }

    @Test
    public void bwWildcardExpandsOntoLiteralStarVariableName() {
        // A genuine bw[*] wildcard whose leaf is the literal 'Trace.Task.*' name must resolve onto
        // the concrete PAR, matching the variable name exactly (not as a nested glob).
        Map<String, String> merged = template();
        String concrete = "bw[MyApp-LB.par]/variables[Adapter SDK Properties]/variable[Trace.Task.*]";
        merged.put(concrete, "false");
        merged.put("bw[*]/variables[Adapter SDK Properties]/variable[Trace.Task.*]", "true");

        Map<String, String> r = new DeploymentConfigGenerator().resolveServiceBindings(
            merged, java.util.Collections.<String>emptySet());

        assertEquals("bw[*] wildcard resolves onto the concrete PAR's literal-star variable",
            "true", r.get(concrete));
        for (String k : r.keySet()) {
            assertFalse("no bw[*] wildcard key must remain: " + k, k.startsWith("bw[*]"));
        }
    }

    // -----------------------------------------------------------------------
    //  #13: runtime variables in deploy.xml — block name + override-only vars
    // -----------------------------------------------------------------------

    @Test
    public void bindingLevelRuntimeVarsUseRuntimeVariablesBlockName() throws Exception {
        // Regression: the per-binding NVPairs block was mislabelled "INSTANCE_RUNTIME_VARIABLES".
        // AppManage expects it to be named "Runtime Variables", identical to the service-level block.
        List<SubstVarParser.GlobalVariable> vars = Arrays.asList(
            gv("CommonCore/Cache/cacheManagerConfig", "cfg", "String", true));
        String xml = readDeployXml(vars, oneService());

        assertFalse("legacy INSTANCE_RUNTIME_VARIABLES block name must be gone",
            xml.contains("INSTANCE_RUNTIME_VARIABLES"));
        int blocks = xml.split(java.util.regex.Pattern.quote(
            "<NVPairs name=\"Runtime Variables\">"), -1).length - 1;
        assertEquals("binding-level and service-level runtime-var blocks are both 'Runtime Variables'",
            2, blocks);
    }

    @Test
    public void overrideOnlyBindingRuntimeVarRendersInDeployXml() throws Exception {
        // Regression: a runtime variable supplied ONLY through the service overrides (no matching GV),
        // e.g. an endpoint-specific OAuth 'scope', was dropped from deploy.xml because rendering only
        // iterated service-settable GVs. It must now appear in the binding's "Runtime Variables" block.
        Map<String, String> merged = template();
        String base = "bw[MyApp-LB.par]/bindings/binding[MyApp-LB-esb06]";
        merged.put(base + "/setting/java/initHeapSize", "256");   // explicit override -> names the binding
        String scopeKey = base + "/variables/variable[CommonHTTPClient/TokenOAuth/TokenParams/scope]";
        merged.put(scopeKey, "appl_value");
        Map<String, String> r = new DeploymentConfigGenerator().resolveServiceBindings(
            merged, new java.util.HashSet<>(Arrays.asList(
                base + "/setting/java/initHeapSize", scopeKey)));

        File out = tmp.newFile("deploy-scope.xml");
        new DeploymentConfigGenerator().generateDeployXml(out, "MyApp", "1.0.0", "", "",
            new ArrayList<>(), oneService(), r);
        String xml = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue("override-only runtime var name must appear in deploy.xml",
            xml.contains("<name>CommonHTTPClient/TokenOAuth/TokenParams/scope</name>"));
        assertTrue("override-only runtime var value must appear in deploy.xml",
            xml.contains("<value>appl_value</value>"));
    }

    @Test
    public void noNamedBindingOrWildcardLeavesMapUnchanged() {
        Map<String, String> merged = template();
        int before = merged.size();
        Map<String, String> r = new DeploymentConfigGenerator().resolveServiceBindings(
            merged, java.util.Collections.<String>emptySet());
        // Backward compatible: without overrides the empty-named template binding survives untouched.
        assertEquals(before, r.size());
        assertEquals("true", r.get("bw[MyApp-LB.par]/enabled"));
        assertTrue("empty-named template binding preserved when nothing named it",
            r.containsKey("bw[MyApp-LB.par]/bindings/binding[]/machine"));
    }
}

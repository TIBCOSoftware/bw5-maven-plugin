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
        new DeploymentConfigGenerator().generateDeployXml(out, "MyApp", "1.0.0", vars, services, null);
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
        gen.generateDeployXml(out, "MyApp", "1.0.0",
                Arrays.asList(gv("CommonCore/Cache/cacheManagerConfig", "cfg", "String", true)),
                services, flat);
        String xml = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue("overridden heap must appear in <services>", xml.contains("<maxHeapSize>2048</maxHeapSize>"));
        assertFalse("default heap must be gone", xml.contains("<maxHeapSize>256</maxHeapSize>"));
        assertTrue("overridden isFt must appear", xml.contains("<isFt>true</isFt>"));
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
}

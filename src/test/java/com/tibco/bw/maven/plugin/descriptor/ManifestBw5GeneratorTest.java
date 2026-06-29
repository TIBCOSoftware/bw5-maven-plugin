package com.tibco.bw.maven.plugin.descriptor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ManifestBw5GeneratorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File resource(String name) throws Exception {
        URL url = getClass().getClassLoader().getResource("descriptor/" + name);
        assertNotNull("Test resource not found: " + name, url);
        return new File(url.toURI());
    }

    private List<SubstVarParser.GlobalVariable> vars() throws Exception {
        return new SubstVarParser().parse(resource("defaultVars.substvar"));
    }

    // -----------------------------------------------------------------------

    @Test
    public void producesValidJsonFile() throws Exception {
        File out = new ManifestBw5Generator().generate(
            "MyApp", "1.0.0", vars(), Collections.emptyList(), Collections.emptyList(), tmp.getRoot());

        assertTrue(out.exists());
        assertEquals("manifest-bw5.json", out.getName());

        String json = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        assertTrue(json.startsWith("{"));
        assertTrue(json.trim().endsWith("}"));
    }

    @Test
    public void containsAppNameAndType() throws Exception {
        File out = new ManifestBw5Generator().generate(
            "MyApp", "2.3.1", vars(), Collections.emptyList(), Collections.emptyList(), tmp.getRoot());
        String json = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue(json.contains("\"name\": \"MyApp\""));
        assertTrue(json.contains("\"type\": \"bw5\""));
        assertTrue(json.contains("\"applicationVersion\": \"2.3.1\""));
    }

    @Test
    public void appPropertiesContainGlobalVars() throws Exception {
        File out = new ManifestBw5Generator().generate(
            "MyApp", "1.0", vars(), Collections.emptyList(), Collections.emptyList(), tmp.getRoot());
        String json = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue(json.contains("\"profile\": \"default.substvar\""));
        assertTrue(json.contains("\"name\": \"ServerHost\""));
        assertTrue(json.contains("\"default\": \"localhost\""));
        assertTrue(json.contains("\"datatype\": \"password\""));
    }

    @Test
    public void healthcheckEndpointAlwaysPresent() throws Exception {
        File out = new ManifestBw5Generator().generate(
            "MyApp", "1.0", vars(), Collections.emptyList(), Collections.emptyList(), tmp.getRoot());
        String json = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        // healthcheck uses empty name (matching provisioner format)
        assertTrue(json.contains("\"port\": \"8090\""));
        assertTrue(json.contains("\"type\": \"httpHealthCheck\""));
        assertTrue(json.contains("\"pingable\": true"));
        assertTrue(json.contains("\"public\": false"));
    }

    @Test
    public void messageEncodingAddedWhenAbsent() throws Exception {
        // defaultVars.substvar does not contain MessageEncoding — it should be injected
        File out = new ManifestBw5Generator().generate(
            "MyApp", "1.0", vars(), Collections.emptyList(), Collections.emptyList(), tmp.getRoot());
        String json = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue(json.contains("\"name\": \"MessageEncoding\""));
        assertTrue(json.contains("\"default\": \"ISO8859-1\""));
    }

    @Test
    public void messageEncodingNotDuplicatedWhenPresent() throws Exception {
        SubstVarParser.GlobalVariable enc = new SubstVarParser.GlobalVariable();
        enc.name = "MessageEncoding";
        enc.value = "UTF-8";
        enc.type = "String";
        List<SubstVarParser.GlobalVariable> withEnc = new ArrayList<>(vars());
        withEnc.add(enc);

        File out = new ManifestBw5Generator().generate(
            "MyApp", "1.0", withEnc, Collections.emptyList(), Collections.emptyList(), tmp.getRoot());
        String json = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        // Should appear exactly once
        int first = json.indexOf("\"MessageEncoding\"");
        int second = json.indexOf("\"MessageEncoding\"", first + 1);
        assertEquals("MessageEncoding must appear exactly once", -1, second);
        assertTrue(json.contains("\"default\": \"UTF-8\""));
    }

    @Test
    public void parsesLiteralPortFromSharedHttp() throws Exception {
        File sharedHttp = resource("HTTP Connection.sharedhttp");
        File out = new ManifestBw5Generator().generate(
            "MyApp", "1.0", vars(), Collections.singletonList(sharedHttp), Collections.emptyList(), tmp.getRoot());
        String json = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue(json.contains("\"sharedResourceName\": \"HTTP Connection\""));
        assertTrue(json.contains("\"port\": \"9001\""));
        assertTrue(json.contains("\"type\": \"public\""));
        assertTrue(json.contains("\"primary\": false"));
        assertTrue(json.contains("\"public\": false"));
        assertTrue(json.contains("\"path\": \"\""));
        assertTrue(json.contains("\"ping\": \"\""));
    }

    @Test
    public void endpointTypesAreCorrect() throws Exception {
        File sharedHttp = resource("HTTP Connection.sharedhttp");
        File out = new ManifestBw5Generator().generate(
            "MyApp", "1.0", vars(), Collections.singletonList(sharedHttp), Collections.emptyList(), tmp.getRoot());
        String json = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        // Regular HTTP endpoint must be "public"
        assertTrue("Regular endpoint type must be 'public'", json.contains("\"type\": \"public\""));
        // Healthcheck endpoint must be "httpHealthCheck"
        assertTrue("Healthcheck endpoint type must be 'httpHealthCheck'", json.contains("\"type\": \"httpHealthCheck\""));
    }

    @Test
    public void resolvesGvPortFromSharedHttp() throws Exception {
        File sharedHttp = resource("GVPort.sharedhttp");
        File out = new ManifestBw5Generator().generate(
            "MyApp", "1.0", vars(), Collections.singletonList(sharedHttp), Collections.emptyList(), tmp.getRoot());
        String json = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        // %%ServerPort%% resolves to "8080" from defaultVars.substvar
        assertTrue(json.contains("\"sharedResourceName\": \"GVPort\""));
        assertTrue(json.contains("\"port\": \"8080\""));
    }

    @Test
    public void healthcheckIsAlwaysLast() throws Exception {
        File sharedHttp = resource("HTTP Connection.sharedhttp");
        File out = new ManifestBw5Generator().generate(
            "MyApp", "1.0", vars(), Collections.singletonList(sharedHttp), Collections.emptyList(), tmp.getRoot());
        String json = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        // The HTTP endpoint (port 9001) must appear before the healthcheck (port 8090)
        int httpIdx = json.indexOf("\"9001\"");
        int hcIdx   = json.indexOf("\"8090\"");
        assertTrue("healthcheck must appear after HTTP endpoint", hcIdx > httpIdx);
    }

    @Test
    public void emptyGlobalVarsProducesOnlyMessageEncoding() throws Exception {
        File out = new ManifestBw5Generator().generate(
            "MyApp", "1.0", Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), tmp.getRoot());
        String json = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        // Only MessageEncoding should be injected when no vars provided
        assertTrue(json.contains("\"name\": \"MessageEncoding\""));
    }

    @Test
    public void specialCharsInAppNameAreEscaped() throws Exception {
        File out = new ManifestBw5Generator().generate(
            "My\"App\\Test", "1.0", Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), tmp.getRoot());
        String json = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue(json.contains("\"My\\\"App\\\\Test\""));
    }

    @Test
    public void volumesFromIsEmptyArray() throws Exception {
        File out = new ManifestBw5Generator().generate(
            "MyApp", "1.0", vars(), Collections.emptyList(), Collections.emptyList(), tmp.getRoot());
        String json = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue(json.contains("\"volumesFrom\": []"));
    }

    @Test
    public void resolvesProcessNameFromSharedChannel() throws Exception {
        File sharedHttp = resource("HTTP Connection.sharedhttp");
        File process = resource("HTTP Receiver.process");
        File out = new ManifestBw5Generator().generate(
            "MyApp", "1.0", vars(),
            Collections.singletonList(sharedHttp),
            Collections.singletonList(process),
            tmp.getRoot());
        String json = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        // endpoint name should come from the process filename (without extension)
        assertTrue(json.contains("\"name\": \"HTTP Receiver\""));
        // sharedResourceName and port should still be present
        assertTrue(json.contains("\"sharedResourceName\": \"HTTP Connection\""));
        assertTrue(json.contains("\"port\": \"9001\""));
    }

    @Test
    public void endpointNameIsEmptyWhenNoProcessReferences() throws Exception {
        File sharedHttp = resource("HTTP Connection.sharedhttp");
        // SubProcess.process has no <sharedChannel> — no mapping expected
        File process = resource("SubProcess.process");
        File out = new ManifestBw5Generator().generate(
            "MyApp", "1.0", vars(),
            Collections.singletonList(sharedHttp),
            Collections.singletonList(process),
            tmp.getRoot());
        String json = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        // HTTP endpoint should have empty name since no process references it
        int httpIdx = json.indexOf("\"9001\"");
        String httpBlock = json.substring(0, httpIdx);
        int lastNameIdx = httpBlock.lastIndexOf("\"name\":");
        assertTrue(json.substring(lastNameIdx).startsWith("\"name\": \"\","));
    }
}

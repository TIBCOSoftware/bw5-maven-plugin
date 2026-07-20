package com.tibco.bw.maven.plugin.descriptor;

import org.junit.Test;

import java.io.File;
import java.net.URL;

import static org.junit.Assert.*;

public class ProcessParserTest {

    private File resource(String name) throws Exception {
        URL url = getClass().getClassLoader().getResource("descriptor/" + name);
        assertNotNull("Test resource not found: " + name, url);
        return new File(url.toURI());
    }

    @Test
    public void processWithStarterParsed() throws Exception {
        ProcessParser.ProcessMetadata meta = new ProcessParser().parse(resource("Receiver.process"));
        assertEquals("com/example/Receiver.process", meta.name);
        assertEquals("HTTPReceive", meta.startName);
        assertTrue(meta.hasStarter);
        assertEquals("HTTPReceive", meta.starterName);
        assertEquals("com.tibco.plugin.http.HTTPEventSource", meta.starterType);
    }

    @Test
    public void processWithoutStarterParsed() throws Exception {
        ProcessParser.ProcessMetadata meta = new ProcessParser().parse(resource("SubProcess.process"));
        assertEquals("com/example/SubProcess.process", meta.name);
        assertFalse(meta.hasStarter);
        assertNull(meta.starterName);
    }

    @Test
    public void getReferencePathAddsLeadingSlash() throws Exception {
        ProcessParser.ProcessMetadata meta = new ProcessParser().parse(resource("Receiver.process"));
        assertEquals("/com/example/Receiver.process", meta.getReferencePath());
    }

    @Test
    public void getReferencePathDoesNotDuplicateSlash() {
        ProcessParser.ProcessMetadata meta = new ProcessParser.ProcessMetadata();
        meta.name = "/already/slashed.process";
        assertEquals("/already/slashed.process", meta.getReferencePath());
    }

    @Test
    public void getReferencePathEmptyName() {
        ProcessParser.ProcessMetadata meta = new ProcessParser.ProcessMetadata();
        meta.name = "";
        assertEquals("", meta.getReferencePath());
    }

    /**
     * Regression (Bug C): a {@code .serviceagent} file is a top-level runnable module,
     * so it must be reported as a starter with {@code starterName} taken from
     * {@code <config>/<name>}. Before the fix service agents fell through the generic
     * {@code parse()} path, which found no {@code pd:starter}, so no BwBPConfiguration
     * entry was emitted for them in the PAR TIBCO.xml.
     */
    @Test
    public void serviceAgentParsedAsStarter() throws Exception {
        ProcessParser.ProcessMetadata meta =
            new ProcessParser().parseServiceAgent(resource("SampleService_v2.serviceagent"));
        assertTrue("service agent must be reported as a starter", meta.hasStarter);
        assertEquals("starterName must come from <config>/<name>",
            "SampleService_v2", meta.starterName);
    }
}

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
}

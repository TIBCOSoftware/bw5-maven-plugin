package com.tibco.bw.maven.plugin.descriptor;

import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.util.List;

import static org.junit.Assert.*;

public class SubstVarParserTest {

    private File resource(String name) throws Exception {
        URL url = getClass().getClassLoader().getResource("descriptor/" + name);
        assertNotNull("Test resource not found: " + name, url);
        return new File(url.toURI());
    }

    @Test
    public void parsesAllVariables() throws Exception {
        List<SubstVarParser.GlobalVariable> vars = new SubstVarParser().parse(resource("defaultVars.substvar"));
        assertEquals(4, vars.size());
    }

    @Test
    public void parsesNameAndValue() throws Exception {
        SubstVarParser.GlobalVariable v = firstNamed("ServerHost");
        assertEquals("ServerHost", v.name);
        assertEquals("localhost", v.value);
    }

    @Test
    public void deploymentSettableTrueBecomesRequiresConfigurationTrue() throws Exception {
        SubstVarParser.GlobalVariable v = firstNamed("ServerHost");
        assertTrue(v.requiresConfiguration);
    }

    @Test
    public void deploymentSettableFalseBecomesRequiresConfigurationFalse() throws Exception {
        SubstVarParser.GlobalVariable v = firstNamed("DbPassword");
        assertFalse(v.requiresConfiguration);
    }

    @Test
    public void passwordTypeDetected() throws Exception {
        SubstVarParser.GlobalVariable v = firstNamed("DbPassword");
        assertTrue(v.isPassword());
    }

    /**
     * Regression (Bug B): the {@code serviceSettable} flag must be parsed independently of
     * {@code deploymentSettable}. It drives inclusion in the adapter AAR "Runtime Variables"
     * block. ServerHost is service-settable; the other variables are not.
     */
    @Test
    public void serviceSettableFlagParsed() throws Exception {
        assertTrue("ServerHost must be service-settable", firstNamed("ServerHost").serviceSettable);
        assertFalse("ServerPort must not be service-settable", firstNamed("ServerPort").serviceSettable);
        assertFalse("DbPassword must not be service-settable", firstNamed("DbPassword").serviceSettable);
    }

    @Test
    public void booleanTypePreserved() throws Exception {
        SubstVarParser.GlobalVariable v = firstNamed("DebugEnabled");
        assertEquals("Boolean", v.type);
        assertFalse(v.isPassword());
    }

    @Test
    public void descriptionParsed() throws Exception {
        SubstVarParser.GlobalVariable v = firstNamed("ServerHost");
        assertEquals("Target server hostname", v.description);
    }

    @Test
    public void emptyFileReturnsEmptyList() throws Exception {
        // create temp empty substvar
        File tmp = File.createTempFile("empty", ".substvar");
        tmp.deleteOnExit();
        try (java.io.PrintWriter pw = new java.io.PrintWriter(tmp)) {
            pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            pw.println("<repository xmlns=\"http://www.tibco.com/xmlns/repo/types/2002\"/>");
        }
        List<SubstVarParser.GlobalVariable> vars = new SubstVarParser().parse(tmp);
        assertTrue(vars.isEmpty());
    }

    private SubstVarParser.GlobalVariable firstNamed(String name) throws Exception {
        List<SubstVarParser.GlobalVariable> vars = new SubstVarParser().parse(resource("defaultVars.substvar"));
        for (SubstVarParser.GlobalVariable v : vars) {
            if (name.equals(v.name)) return v;
        }
        fail("Variable not found: " + name);
        return null;
    }
}

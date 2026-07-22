package com.tibco.bw.maven.plugin.doc;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for shared-resource reference detection in process activity configs.
 *
 * <p>Before the fix, {@code bw5:site} only detected references under {@code /SharedResources/},
 * so connection references such as {@code <sharedChannel>/HTTP Connection.sharedhttp</sharedChannel>}
 * were missed — leaving each shared resource's "Used by" at 0 and its process links broken.</p>
 */
public class ProcessDocParserTest {

    @Test
    public void detectsConnectionReferenceByExtension() {
        assertTrue(ProcessDocParser.isSharedResourceRef("/HTTP Connection.sharedhttp"));
        assertTrue(ProcessDocParser.isSharedResourceRef("/Connections/DB.sharedjdbc"));
        assertTrue(ProcessDocParser.isSharedResourceRef("JMS.sharedjmscon"));
    }

    @Test
    public void detectsSharedResourcesFolderReference() {
        assertTrue(ProcessDocParser.isSharedResourceRef("/SharedResources/Schemas/Foo.sharedparse"));
        assertTrue(ProcessDocParser.isSharedResourceRef("SharedResources/Conn/Bar.sharedjms"));
    }

    @Test
    public void ignoresFragmentSuffix() {
        assertTrue(ProcessDocParser.isSharedResourceRef(
            "/AdapterConfiguration.sharedjmscon#some.fragment"));
    }

    @Test
    public void rejectsNonSharedResourceValues() {
        assertFalse(ProcessDocParser.isSharedResourceRef("localhost"));
        assertFalse(ProcessDocParser.isSharedResourceRef("/Some/Process.process"));
        assertFalse(ProcessDocParser.isSharedResourceRef("8080"));
        assertFalse(ProcessDocParser.isSharedResourceRef(""));
        assertFalse(ProcessDocParser.isSharedResourceRef(null));
    }
}

package com.tibco.bw.maven.plugin.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Regression test for DEF-XXX: GVAR check must be classified as Warning, not Error (PRD §6.15).
 */
public class ValidateMojoGvarTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private File writeProcess(String content) throws IOException {
        File f = tmp.newFile("TestProcess.process");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(content);
        }
        return f;
    }

    private File writeSubstVar(String gvarName, String defaultValue) throws IOException {
        File f = tmp.newFile("default.substvar");
        String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<repository xmlns=\"http://www.tibco.com/xmlns/repo/types/2002\">\n"
            + "    <globalVariables>\n"
            + "        <globalVariable>\n"
            + "            <name>" + gvarName + "</name>\n"
            + "            <value>" + defaultValue + "</value>\n"
            + "            <deploymentSettable>false</deploymentSettable>\n"
            + "        </globalVariable>\n"
            + "    </globalVariables>\n"
            + "</repository>\n";
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(content);
        }
        return f;
    }

    private String processWithGvar(String gvarName) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "    <pd:name>/TestProcess</pd:name>\n"
            + "    <pd:config>%%" + gvarName + "%%</pd:config>\n"
            + "</pd:ProcessDefinition>\n";
    }

    // -----------------------------------------------------------------------
    //  DEF regression: undeclared GVar must produce WARNING, not ERROR
    // -----------------------------------------------------------------------

    @Test
    public void undeclaredGvarIsClassifiedAsWarning() throws Exception {
        File proc = writeProcess(processWithGvar("UNDEFINED_VAR"));

        ValidateMojo mojo = new ValidateMojo();
        List<ValidateMojo.Issue> issues = new ArrayList<>();
        mojo.validateGlobalVariables(
            Collections.singletonList(proc),
            Collections.emptyList(),   // no substvar → nothing declared
            issues
        );

        assertEquals("Expected exactly one GVAR issue", 1, issues.size());
        ValidateMojo.Issue issue = issues.get(0);
        assertEquals("GVAR", issue.code);
        assertEquals(
            "GVAR check must be Warning, not Error (PRD §6.15)",
            ValidateMojo.Severity.WARNING, issue.severity);
    }

    @Test
    public void declaredGvarProducesNoIssue() throws Exception {
        File substvar = writeSubstVar("MY_VAR", "default-value");
        File proc     = writeProcess(processWithGvar("MY_VAR"));

        ValidateMojo mojo = new ValidateMojo();
        List<ValidateMojo.Issue> issues = new ArrayList<>();
        mojo.validateGlobalVariables(
            Collections.singletonList(proc),
            Collections.singletonList(substvar),
            issues
        );

        assertTrue("No issues expected when GVar is declared", issues.isEmpty());
    }

    @Test
    public void multipleUndeclaredGvarsAllProduceWarnings() throws Exception {
        String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
            + "    <pd:name>/TestProcess</pd:name>\n"
            + "    <pd:config>%%ALPHA%% %%BETA%% %%GAMMA%%</pd:config>\n"
            + "</pd:ProcessDefinition>\n";
        File proc = writeProcess(content);

        ValidateMojo mojo = new ValidateMojo();
        List<ValidateMojo.Issue> issues = new ArrayList<>();
        mojo.validateGlobalVariables(
            Collections.singletonList(proc),
            Collections.emptyList(),
            issues
        );

        assertEquals(3, issues.size());
        for (ValidateMojo.Issue issue : issues) {
            assertEquals("All GVAR issues must be Warning (PRD §6.15)",
                ValidateMojo.Severity.WARNING, issue.severity);
        }
    }
}

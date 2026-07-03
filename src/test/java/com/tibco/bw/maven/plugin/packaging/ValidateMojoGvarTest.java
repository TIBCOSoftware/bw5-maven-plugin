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
    //  DEF-021 regression: malformed substvar must not dump stack trace
    // -----------------------------------------------------------------------

    @Test
    public void malformedSubstvarProducesNoGvarIssuesAndDoesNotThrow() throws Exception {
        // The XML well-formedness check (separate phase) already reports this as [ERROR][XML].
        // validateGlobalVariables() must silently skip the file without adding issues or
        // leaking a stack trace. Previously it called getLog().warn(msg, exception) which
        // printed 50+ lines of JDOMParseException to the console.
        File malformed = tmp.newFile("broken.substvar");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(malformed), StandardCharsets.UTF_8)) {
            w.write("<?xml version=\"1.0\"?><repository> NOT CLOSED");  // intentionally malformed
        }
        File proc = writeProcess(processWithGvar("SOME_VAR"));

        ValidateMojo mojo = new ValidateMojo();
        mojo.bwProjectPath = tmp.getRoot();  // needed by rel() in the catch block
        List<ValidateMojo.Issue> issues = new ArrayList<>();
        // Must not throw, must not add any GVAR issues (parse failure → file skipped)
        mojo.validateGlobalVariables(
            Collections.singletonList(proc),
            Collections.singletonList(malformed),
            issues
        );

        // SOME_VAR was referenced but the substvar couldn't be parsed → referencing-without-decl
        // yields a GVAR Warning, but NO stack trace in console.
        // Every issue must be GVAR (Warning), never an unexpected Error from the exception itself.
        for (ValidateMojo.Issue issue : issues) {
            assertEquals("GVAR", issue.code);
            assertEquals(ValidateMojo.Severity.WARNING, issue.severity);
        }
    }

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

    // -----------------------------------------------------------------------
    //  PROCESS_NAME validation: <pd:name> with leading slash and no .process
    //  extension must match the file path (DEF-026 / bfs-test validation fix)
    // -----------------------------------------------------------------------

    @Test
    public void processNameWithLeadingSlashAndNoExtensionIsAccepted() throws Exception {
        // Standard BW5 Designer format: <pd:name>/Services/MainProcess</pd:name>
        // with file at Services/MainProcess.process — must NOT produce an error.
        File dir = tmp.newFolder("Services");
        File proc = new File(dir, "MainProcess.process");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(proc), StandardCharsets.UTF_8)) {
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
                + "  <pd:name>/Services/MainProcess</pd:name>\n"
                + "</pd:ProcessDefinition>\n");
        }

        ValidateMojo mojo = new ValidateMojo();
        mojo.bwProjectPath = tmp.getRoot();
        List<ValidateMojo.Issue> issues = new ArrayList<>();
        mojo.validateProcessNames(Collections.singletonList(proc), issues);

        long nameErrors = issues.stream()
            .filter(i -> "PROCESS_NAME".equals(i.code)).count();
        assertEquals("Leading-slash <pd:name> with no .process suffix must not produce PROCESS_NAME error",
            0, nameErrors);
    }

    @Test
    public void processNameMismatchIsStillDetected() throws Exception {
        File dir = tmp.newFolder("WrongServices");
        File proc = new File(dir, "MainProcess.process");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(proc), StandardCharsets.UTF_8)) {
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
                + "  <pd:name>/Services/CompletelyDifferentName</pd:name>\n"
                + "</pd:ProcessDefinition>\n");
        }

        ValidateMojo mojo = new ValidateMojo();
        mojo.bwProjectPath = tmp.getRoot();
        List<ValidateMojo.Issue> issues = new ArrayList<>();
        mojo.validateProcessNames(Collections.singletonList(proc), issues);

        long nameErrors = issues.stream()
            .filter(i -> "PROCESS_NAME".equals(i.code)).count();
        assertEquals("Genuinely wrong <pd:name> must still produce PROCESS_NAME error", 1, nameErrors);
    }

    @Test
    public void processNameWithoutLeadingSlashIsAlsoAccepted() throws Exception {
        File dir = tmp.newFolder("NoSlashServices");
        File proc = new File(dir, "MyProc.process");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(proc), StandardCharsets.UTF_8)) {
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<pd:ProcessDefinition xmlns:pd=\"http://xmlns.tibco.com/bw/process/2003\">\n"
                + "  <pd:name>NoSlashServices/MyProc</pd:name>\n"
                + "</pd:ProcessDefinition>\n");
        }

        ValidateMojo mojo = new ValidateMojo();
        mojo.bwProjectPath = tmp.getRoot();
        List<ValidateMojo.Issue> issues = new ArrayList<>();
        mojo.validateProcessNames(Collections.singletonList(proc), issues);

        long nameErrors = issues.stream()
            .filter(i -> "PROCESS_NAME".equals(i.code)).count();
        assertEquals("<pd:name> without leading slash must also be accepted", 0, nameErrors);
    }
}

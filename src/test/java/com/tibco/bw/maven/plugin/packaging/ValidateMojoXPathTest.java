package com.tibco.bw.maven.plugin.packaging;

import org.junit.Before;
import org.junit.Test;

import javax.xml.xpath.XPath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Regression tests for DEF-018: XPATH check must detect unknown namespaced function calls.
 *
 * Root cause: XPath.compile() is lazy and does NOT invoke the XPathFunctionResolver.
 * Only evaluation invokes it, but evaluating against a dummy doc causes false positives
 * for variable-navigating expressions like $var/child. The fix scans the expression text
 * for prefix:funcName( patterns and checks each against the BW5 catalog.
 */
public class ValidateMojoXPathTest {

    private ValidateMojo mojo;
    private Map<String, int[]> catalog;
    private XPath engine;

    @Before
    public void setUp() {
        mojo    = new ValidateMojo();
        catalog = new HashMap<>();
        catalog.put("concat-sequence",  new int[]{1, 1});
        catalog.put("format-dateTime",  new int[]{2, 2});
        catalog.put("trim",             new int[]{1, 1});
        engine  = mojo.buildXPathEngine(catalog);
    }

    // -----------------------------------------------------------------------
    //  DEF-018 regression: unknown function must produce XPATH Warning
    // -----------------------------------------------------------------------

    @Test
    public void unknownNamespacedFunctionProducesXPathWarning() {
        List<ValidateMojo.Issue> issues = new ArrayList<>();
        ValidateMojo.XPathChecker.check(
            "tib:format-money(100)", "myMapper", "Services/Test.process",
            engine, catalog, issues
        );
        assertEquals("Expected exactly one XPATH issue", 1, issues.size());
        ValidateMojo.Issue issue = issues.get(0);
        assertEquals("XPATH", issue.code);
        assertEquals("XPATH issue must be Warning per PRD §6.15",
            ValidateMojo.Severity.WARNING, issue.severity);
        assertTrue("Message must name the unknown function",
            issue.message.contains("format-money"));
    }

    @Test
    public void knownNamespacedFunctionProducesNoIssue() {
        List<ValidateMojo.Issue> issues = new ArrayList<>();
        ValidateMojo.XPathChecker.check(
            "tib:concat-sequence($a)", "myMapper", "Services/Test.process",
            engine, catalog, issues
        );
        assertTrue("Known catalogued function must not produce XPATH issues", issues.isEmpty());
    }

    @Test
    public void multipleUnknownFunctionsInOneExpressionAreAllReported() {
        List<ValidateMojo.Issue> issues = new ArrayList<>();
        ValidateMojo.XPathChecker.check(
            "tib:format-money(tib:round-currency(100))", "myMapper", "Test.process",
            engine, catalog, issues
        );
        assertEquals("Both unknown functions must be reported", 2, issues.size());
        for (ValidateMojo.Issue issue : issues) {
            assertEquals(ValidateMojo.Severity.WARNING, issue.severity);
            assertEquals("XPATH", issue.code);
        }
    }

    @Test
    public void syntaxErrorProducesXPathWarningNotError() {
        List<ValidateMojo.Issue> issues = new ArrayList<>();
        ValidateMojo.XPathChecker.check(
            "((unclosed-paren", "myMapper", "Services/Test.process",
            engine, catalog, issues
        );
        assertFalse("A syntax error must produce an XPATH issue", issues.isEmpty());
        assertEquals("XPATH syntax issue must also be Warning",
            ValidateMojo.Severity.WARNING, issues.get(0).severity);
    }

    @Test
    public void validExpressionWithoutFunctionsProducesNoIssue() {
        List<ValidateMojo.Issue> issues = new ArrayList<>();
        ValidateMojo.XPathChecker.check(
            "$activityOutput/someElement/text()", "myMapper", "Services/Test.process",
            engine, catalog, issues
        );
        assertTrue("Valid expression with no function calls must produce no issues",
            issues.isEmpty());
    }

    @Test
    public void standardXPathFunctionWithoutPrefixProducesNoIssue() {
        List<ValidateMojo.Issue> issues = new ArrayList<>();
        ValidateMojo.XPathChecker.check(
            "concat($a, '-', $b)", "myMapper", "Services/Test.process",
            engine, catalog, issues
        );
        assertTrue("Standard XPath function without namespace prefix must not be flagged",
            issues.isEmpty());
    }

    @Test
    public void mixedKnownAndUnknownFunctionsOnlyFlagUnknown() {
        List<ValidateMojo.Issue> issues = new ArrayList<>();
        ValidateMojo.XPathChecker.check(
            "tib:trim(tib:format-money($amount))", "myMapper", "Services/Test.process",
            engine, catalog, issues
        );
        assertEquals("Only the unknown function must be flagged", 1, issues.size());
        assertTrue(issues.get(0).message.contains("format-money"));
    }
}

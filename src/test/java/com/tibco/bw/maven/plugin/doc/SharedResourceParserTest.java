package com.tibco.bw.maven.plugin.doc;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Regression tests for DEF-024: bw5:site failed to detect shared resource files.
 *
 * Root cause: SharedResourceParser.parse() scanned only srcDir/SharedResources/, but
 * BW5 projects store shared resources in any folder (commonly Connections/, or at project
 * root). The fix scans the full project tree and filters by .shared* extension.
 */
public class SharedResourceParserTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // -----------------------------------------------------------------------
    //  isSharedResourceFile — extension filter
    // -----------------------------------------------------------------------

    @Test
    public void recognisesSharedjdbcExtension() {
        assertTrue(SharedResourceParser.isSharedResourceFile(new File("MyDB.sharedjdbc")));
    }

    @Test
    public void recognisesSharedhttpExtension() {
        assertTrue(SharedResourceParser.isSharedResourceFile(new File("HttpConn.sharedhttp")));
    }

    @Test
    public void recognisesSharedjmsconExtension() {
        assertTrue(SharedResourceParser.isSharedResourceFile(new File("JmsConn.sharedjmscon")));
    }

    @Test
    public void recognisesSharedparseExtension() {
        assertTrue(SharedResourceParser.isSharedResourceFile(new File("Parser.sharedparse")));
    }

    @Test
    public void doesNotRecogniseProcessFiles() {
        assertFalse(SharedResourceParser.isSharedResourceFile(new File("MyProcess.process")));
    }

    @Test
    public void doesNotRecogniseSubstvarFiles() {
        assertFalse(SharedResourceParser.isSharedResourceFile(new File("default.substvar")));
    }

    // -----------------------------------------------------------------------
    //  parse() — full-tree scan
    // -----------------------------------------------------------------------

    @Test
    public void findsSharedResourceInConnectionsFolder() throws Exception {
        // Pre-fix: would have found 0 because Connections/ != SharedResources/
        File connections = tmp.newFolder("Connections");
        writeJdbcResource(connections, "MyDB.sharedjdbc", "/Connections/MyDB",
            "jdbcpalette.JDBCConnection");

        List<SharedResourceModel> result = new SharedResourceParser().parse(tmp.getRoot());

        assertEquals("Expected 1 shared resource under Connections/", 1, result.size());
        assertEquals("MyDB", result.get(0).displayName);
        assertEquals("jdbcpalette.JDBCConnection", result.get(0).type);
    }

    @Test
    public void findsMultipleSharedResourceTypesInDifferentFolders() throws Exception {
        File connections = tmp.newFolder("Connections");
        writeJdbcResource(connections, "JdbcConn.sharedjdbc", "/Connections/JdbcConn",
            "jdbcpalette.JDBCConnection");
        writeHttpResource(connections, "HttpConn.sharedhttp", "/Connections/HttpConn",
            "httppalette.HttpConnection");

        File nested = tmp.newFolder("Shared", "JMS");
        writeJmsResource(nested, "JmsConn.sharedjmscon", "/Shared/JMS/JmsConn",
            "jmspalette.JMSConnection");

        List<SharedResourceModel> result = new SharedResourceParser().parse(tmp.getRoot());

        assertEquals("Expected 3 shared resources across multiple folders", 3, result.size());
    }

    @Test
    public void stillFindsSharedResourcesInSharedResourcesFolder() throws Exception {
        // Backward-compatibility: files in SharedResources/ must still be found
        File srDir = tmp.newFolder("SharedResources");
        writeJdbcResource(srDir, "Legacy.sharedjdbc", "/SharedResources/Legacy",
            "jdbcpalette.JDBCConnection");

        List<SharedResourceModel> result = new SharedResourceParser().parse(tmp.getRoot());

        assertEquals("SharedResources/ must still be scanned", 1, result.size());
        assertEquals("Legacy", result.get(0).displayName);
    }

    @Test
    public void returnsEmptyListWhenNoSharedResourcesExist() throws Exception {
        tmp.newFile("SomeProcess.process");
        tmp.newFile("default.substvar");

        List<SharedResourceModel> result = new SharedResourceParser().parse(tmp.getRoot());

        assertTrue("No shared resources — list must be empty", result.isEmpty());
    }

    @Test
    public void skipsHiddenFilesWithSharedExtension() throws Exception {
        File f = new File(tmp.getRoot(), ".hiddenfile.sharedjdbc");
        f.createNewFile();

        List<SharedResourceModel> result = new SharedResourceParser().parse(tmp.getRoot());

        assertTrue("Hidden files must be skipped", result.isEmpty());
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private void writeJdbcResource(File dir, String filename, String name, String type)
            throws Exception {
        writeXml(dir, filename,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<jdbcSharedResource>\n"
            + "    <name>" + name + "</name>\n"
            + "    <type>" + type + "</type>\n"
            + "    <config>\n"
            + "        <DbType>Oracle</DbType>\n"
            + "        <ServerName>localhost</ServerName>\n"
            + "        <Port>1521</Port>\n"
            + "    </config>\n"
            + "</jdbcSharedResource>\n");
    }

    /**
     * Regression: a shared resource whose XML has NO top-level {@code <name>} element (the real
     * TIBCO format for e.g. {@code .sharedhttp}/{@code .sharedjdbc}, where the resource name is the
     * file name and the root is namespaced) must still be parsed — name derived from the file name,
     * type from the root element — not silently dropped.
     */
    @Test
    public void parsesResourceWithoutNameElementUsingFileName() throws Exception {
        File dir = tmp.newFolder("sr-noname");
        writeXml(dir, "HTTP Connection.sharedhttp",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<ns0:httpSharedResource xmlns:ns0=\"www.tibco.com/shared/HTTPConnection\">\n"
            + "    <config>\n"
            + "        <Host>localhost</Host>\n"
            + "        <Port>8080</Port>\n"
            + "    </config>\n"
            + "</ns0:httpSharedResource>\n");

        List<SharedResourceModel> res = new SharedResourceParser().parse(dir);
        assertEquals("resource must NOT be dropped for lacking <name>", 1, res.size());
        SharedResourceModel sr = res.get(0);
        assertEquals("HTTP Connection", sr.name);
        assertEquals("HTTP Connection", sr.displayName);
        // .sharedhttp → friendly type label (was the raw root element name before the mapping)
        assertEquals("HTTP Connection", sr.type);
        assertEquals("localhost", sr.config.get("Host"));
    }

    /**
     * Regression: a JMS shared resource uses a generic {@code <BWSharedResource>} root and carries
     * its kind in {@code <resourceType>}; the report must show a friendly JMS type (derived from the
     * .sharedjmsapp extension), not the generic root element name "BWSharedResource".
     */
    @Test
    public void jmsResourceShowsFriendlyTypeNotBWSharedResource() throws Exception {
        File dir = tmp.newFolder("sr-jms");
        writeXml(dir, "JMSProps.sharedjmsapp",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<BWSharedResource>\n"
            + "    <name>JMSProps</name>\n"
            + "    <resourceType>ae.shared.JMSAppPropResource</resourceType>\n"
            + "    <config><Foo>bar</Foo></config>\n"
            + "</BWSharedResource>\n");

        List<SharedResourceModel> res = new SharedResourceParser().parse(dir);
        assertEquals(1, res.size());
        SharedResourceModel sr = res.get(0);
        assertEquals("JMSProps", sr.name);
        assertEquals("JMS Application Properties", sr.type);
        assertEquals("ae.shared.JMSAppPropResource", sr.resourceType);
    }

    @Test
    public void friendlyTypePrefersExtensionThenResourceTypeThenRoot() {
        assertEquals("HTTP Connection",
            SharedResourceParser.friendlyType("Conn.sharedhttp", null, "httpSharedResource"));
        assertEquals("JMS Application Properties",
            SharedResourceParser.friendlyType("X.sharedjmsapp", "ae.shared.JMSAppPropResource", "BWSharedResource"));
        // unknown extension → fall back to resourceType
        assertEquals("ae.shared.Custom",
            SharedResourceParser.friendlyType("X.sharedcustomxyz", "ae.shared.Custom", "BWSharedResource"));
        // no extension match, no resourceType → root element
        assertEquals("someRoot",
            SharedResourceParser.friendlyType("X.sharedcustomxyz", null, "someRoot"));
    }

    private void writeHttpResource(File dir, String filename, String name, String type)
            throws Exception {
        writeXml(dir, filename,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<HTTPClientResource>\n"
            + "    <name>" + name + "</name>\n"
            + "    <type>" + type + "</type>\n"
            + "    <config>\n"
            + "        <Host>api.example.com</Host>\n"
            + "        <Port>443</Port>\n"
            + "    </config>\n"
            + "</HTTPClientResource>\n");
    }

    private void writeJmsResource(File dir, String filename, String name, String type)
            throws Exception {
        writeXml(dir, filename,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<jmsConnectionResource>\n"
            + "    <name>" + name + "</name>\n"
            + "    <type>" + type + "</type>\n"
            + "    <config>\n"
            + "        <ServerUrl>tcp://localhost:7222</ServerUrl>\n"
            + "    </config>\n"
            + "</jmsConnectionResource>\n");
    }

    private void writeXml(File dir, String filename, String content) throws Exception {
        File f = new File(dir, filename);
        try (Writer w = new FileWriter(f, StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }
}

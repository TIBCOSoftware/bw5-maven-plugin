package com.tibco.bw.maven.plugin.descriptor;

import org.junit.Test;

import java.io.File;
import java.net.URL;

import static org.junit.Assert.*;

public class ArchiveDescriptorParserTest {

    private File resource(String name) throws Exception {
        URL url = getClass().getClassLoader().getResource("descriptor/" + name);
        assertNotNull("Test resource not found: " + name, url);
        return new File(url.toURI());
    }

    @Test
    public void parsesEarName() throws Exception {
        ArchiveDescriptorParser.ArchiveDescriptor d = new ArchiveDescriptorParser().parse(resource("MyApp.archive"));
        assertEquals("MyApp", d.earName);
    }

    @Test
    public void parsesProcessArchiveName() throws Exception {
        ArchiveDescriptorParser.ArchiveDescriptor d = new ArchiveDescriptorParser().parse(resource("MyApp.archive"));
        assertEquals("MyApp Archive", d.getProcessArchiveName());
    }

    @Test
    public void parsesSharedArchiveName() throws Exception {
        ArchiveDescriptorParser.ArchiveDescriptor d = new ArchiveDescriptorParser().parse(resource("MyApp.archive"));
        assertEquals("MyShared Archive", d.sharedArchiveName);
    }

    @Test
    public void parsesProcessPaths() throws Exception {
        ArchiveDescriptorParser.ArchiveDescriptor d = new ArchiveDescriptorParser().parse(resource("MyApp.archive"));
        assertTrue(d.hasExplicitProcessList());
        assertEquals(2, d.getProcessPaths().size());
        assertTrue(d.getProcessPaths().contains("/com/example/Receiver.process"));
        assertTrue(d.getProcessPaths().contains("/com/example/SubProcess.process"));
    }

    @Test
    public void missingProcessPropertyGivesEmptyList() throws Exception {
        File tmp = File.createTempFile("noprocess", ".archive");
        tmp.deleteOnExit();
        try (java.io.PrintWriter pw = new java.io.PrintWriter(tmp)) {
            pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            pw.println("<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">");
            pw.println("  <enterpriseArchive>");
            pw.println("    <name>Empty</name>");
            pw.println("    <processArchive name=\"Process Archive\"/>");
            pw.println("  </enterpriseArchive>");
            pw.println("</Repository:repository>");
        }
        ArchiveDescriptorParser.ArchiveDescriptor d = new ArchiveDescriptorParser().parse(tmp);
        assertFalse(d.hasExplicitProcessList());
        assertTrue(d.getProcessPaths().isEmpty());
    }

    // -----------------------------------------------------------------------
    //  Multi-PAR
    // -----------------------------------------------------------------------

    @Test
    public void parsesMultiplePars() throws Exception {
        File tmp = writeArchive(
            "  <enterpriseArchive>\n"
            + "    <name>MultiApp</name>\n"
            + "    <processArchive name=\"OrderService\">\n"
            + "      <processProperty>/Services/Order/Recv.process</processProperty>\n"
            + "    </processArchive>\n"
            + "    <processArchive name=\"PaymentService\">\n"
            + "      <processProperty>/Services/Payment/Pay.process</processProperty>\n"
            + "    </processArchive>\n"
            + "    <sharedArchive name=\"Shared Archive\"/>\n"
            + "  </enterpriseArchive>\n");
        ArchiveDescriptorParser.ArchiveDescriptor d = new ArchiveDescriptorParser().parse(tmp);
        assertEquals(2, d.processArchives.size());
        assertEquals("OrderService", d.processArchives.get(0).name);
        assertEquals(1, d.processArchives.get(0).processPaths.size());
        assertEquals("/Services/Order/Recv.process", d.processArchives.get(0).processPaths.get(0));
        assertEquals("PaymentService", d.processArchives.get(1).name);
        assertTrue(d.isMultiPar());
        assertFalse(d.hasAdapterArchives());
    }

    // -----------------------------------------------------------------------
    //  Adapter Archive (AAR)
    // -----------------------------------------------------------------------

    @Test
    public void parsesAdapterArchive() throws Exception {
        File tmp = writeArchive(
            "  <enterpriseArchive>\n"
            + "    <name>AdapterApp</name>\n"
            + "    <adapterArchive name=\"SalesforceAdapter\">\n"
            + "      <processProperty>/Adapters/SalesforceService.serviceagent</processProperty>\n"
            + "    </adapterArchive>\n"
            + "    <sharedArchive name=\"Shared Archive\"/>\n"
            + "  </enterpriseArchive>\n");
        ArchiveDescriptorParser.ArchiveDescriptor d = new ArchiveDescriptorParser().parse(tmp);
        assertEquals(0, d.processArchives.size());
        assertEquals(1, d.adapterArchives.size());
        assertEquals("SalesforceAdapter", d.adapterArchives.get(0).name);
        assertEquals("/Adapters/SalesforceService.serviceagent",
            d.adapterArchives.get(0).processPaths.get(0));
        assertTrue(d.hasAdapterArchives());
        assertFalse(d.isMultiPar());
    }

    @Test
    public void parsesMixedParsAndAar() throws Exception {
        File tmp = writeArchive(
            "  <enterpriseArchive>\n"
            + "    <name>MixedApp</name>\n"
            + "    <processArchive name=\"MainService\">\n"
            + "      <processProperty>/Main/Start.process</processProperty>\n"
            + "    </processArchive>\n"
            + "    <adapterArchive name=\"SAP\">\n"
            + "      <processProperty>/Adapters/SapService.serviceagent</processProperty>\n"
            + "    </adapterArchive>\n"
            + "    <sharedArchive name=\"Shared Archive\"/>\n"
            + "  </enterpriseArchive>\n");
        ArchiveDescriptorParser.ArchiveDescriptor d = new ArchiveDescriptorParser().parse(tmp);
        assertEquals(1, d.processArchives.size());
        assertEquals(1, d.adapterArchives.size());
        assertFalse(d.isMultiPar());
        assertTrue(d.hasAdapterArchives());
    }

    // -----------------------------------------------------------------------
    //  Helper
    // -----------------------------------------------------------------------

    private File writeArchive(String body) throws Exception {
        File tmp = File.createTempFile("test", ".archive");
        tmp.deleteOnExit();
        try (java.io.PrintWriter pw = new java.io.PrintWriter(tmp)) {
            pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            pw.println("<Repository:repository xmlns:Repository=\"http://www.tibco.com/xmlns/repo/types/2002\">");
            pw.print(body);
            pw.println("</Repository:repository>");
        }
        return tmp;
    }
}

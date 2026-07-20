package com.tibco.bw.maven.plugin.descriptor;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;

import java.io.File;

/**
 * Parses a TIBCO BusinessWorks 5 .process file to extract metadata
 * needed for generating the PAR-level TIBCO.xml.
 *
 * A .process file has the structure:
 * <pre>
 * &lt;pd:ProcessDefinition xmlns:pd="http://xmlns.tibco.com/bw/process/2003"&gt;
 *   &lt;pd:name&gt;Path/To/Process.process&lt;/pd:name&gt;
 *   &lt;pd:startName&gt;StarterActivityName&lt;/pd:startName&gt;
 *   &lt;pd:starter name="StarterName"&gt;
 *     &lt;pd:type&gt;com.tibco.plugin.timer.TimerEventSource&lt;/pd:type&gt;
 *     ...
 *   &lt;/pd:starter&gt;
 *   ...
 * &lt;/pd:ProcessDefinition&gt;
 * </pre>
 */
public class ProcessParser {

    private static final Namespace PD_NS =
        Namespace.getNamespace("pd", "http://xmlns.tibco.com/bw/process/2003");

    /**
     * Parses a .process file and returns its metadata.
     */
    public ProcessMetadata parse(File processFile) throws Exception {
        SAXBuilder builder = new SAXBuilder();
        Document doc = builder.build(processFile);
        Element root = doc.getRootElement();

        ProcessMetadata meta = new ProcessMetadata();
        meta.name = getChildText(root, "name");
        meta.startName = getChildText(root, "startName");

        // Determine if this process has a starter (is a "top-level" process that can run)
        Element starterEl = root.getChild("starter", PD_NS);
        if (starterEl != null) {
            meta.hasStarter = true;
            meta.starterName = starterEl.getAttributeValue("name");
            meta.starterType = getChildText(starterEl, "type");
        }

        return meta;
    }

    /**
     * Parses a {@code .serviceagent} file (SOAP/REST service agent) and returns metadata
     * describing it as a deployable starter module.
     *
     * <p>A service agent has the structure:</p>
     * <pre>
     * &lt;serviceResource&gt;
     *   &lt;config&gt;
     *     &lt;name&gt;MyService_v2&lt;/name&gt;
     *     ...
     *   &lt;/config&gt;
     * &lt;/serviceResource&gt;
     * </pre>
     *
     * <p>The returned metadata always has {@code hasStarter=true} — a service agent is a
     * top-level runnable module in the PAR, so it needs its own {@code BwBPConfiguration}
     * entry. The {@code starterName} is the {@code <config>/<name>} value. The caller sets
     * {@code name} to the service agent's BW repository path.</p>
     */
    public ProcessMetadata parseServiceAgent(File serviceAgentFile) throws Exception {
        SAXBuilder builder = new SAXBuilder();
        Document doc = builder.build(serviceAgentFile);
        Element root = doc.getRootElement();

        ProcessMetadata meta = new ProcessMetadata();
        meta.hasStarter = true;
        // <config>/<name> — plain (no-namespace) elements in a .serviceagent file
        Element config = root.getChild("config");
        if (config != null) {
            Element nameEl = config.getChild("name");
            if (nameEl != null) meta.starterName = nameEl.getTextTrim();
        }
        return meta;
    }

    private String getChildText(Element parent, String childLocalName) {
        Element child = parent.getChild(childLocalName, PD_NS);
        return child != null ? child.getTextTrim() : "";
    }

    /**
     * Metadata extracted from a .process file.
     */
    public static class ProcessMetadata {
        /** Process name as stored in the file (e.g. "Path/To/MyProcess.process") */
        public String name;
        /** Name of the start activity */
        String startName;
        /** Whether the process has a starter (event source) - top-level runnable process */
        public boolean hasStarter;
        /** Name attribute of the starter element */
        public String starterName;
        /** Type of the starter (e.g. "com.tibco.plugin.timer.TimerEventSource") */
        String starterType;

        /** Returns the process path with leading slash for use in TIBCO.xml references */
        public String getReferencePath() {
            if (name == null || name.isEmpty()) return "";
            return name.startsWith("/") ? name : "/" + name;
        }
    }
}

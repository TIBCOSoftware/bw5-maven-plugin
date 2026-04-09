package com.tibco.bw.maven.plugin.descriptor;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Writes updated values back into a {@code .substvar} XML file in-place,
 * preserving all other attributes (type, description, deploymentSettable, etc.).
 *
 * <p>Only the {@code &lt;value&gt;} element of each global variable is changed;
 * the overall structure and ordering of the file are preserved.</p>
 */
public class SubstVarWriter {

    private static final Namespace REPO_NS =
        Namespace.getNamespace("http://www.tibco.com/xmlns/repo/types/2002");

    /**
     * Updates the value of each global variable listed in {@code mergedVars} inside
     * {@code substVarFile} and writes the result back to the same file.
     *
     * @param substVarFile   the original {@code .substvar} file to update
     * @param mergedVars     variables whose values should be applied (keyed by name)
     */
    public void updateValues(File substVarFile,
                             List<SubstVarParser.GlobalVariable> mergedVars) throws Exception {

        // Build name→value map from the merged list
        Map<String, String> valueMap = new LinkedHashMap<>();
        for (SubstVarParser.GlobalVariable v : mergedVars) {
            if (v.name != null) {
                valueMap.put(v.name, v.value != null ? v.value : "");
            }
        }

        // Parse the existing file, update values, write back
        SAXBuilder builder = new SAXBuilder();
        Document doc = builder.build(substVarFile);
        Element root = doc.getRootElement();

        Element globalVarsEl = root.getChild("globalVariables", REPO_NS);
        if (globalVarsEl == null) return;

        for (Element varEl : globalVarsEl.getChildren("globalVariable", REPO_NS)) {
            Element nameEl = varEl.getChild("name", REPO_NS);
            if (nameEl == null) nameEl = varEl.getChild("name");
            if (nameEl == null) continue;

            String name = nameEl.getTextTrim();
            if (!valueMap.containsKey(name)) continue;

            Element valueEl = varEl.getChild("value", REPO_NS);
            if (valueEl == null) valueEl = varEl.getChild("value");
            if (valueEl == null) {
                // Create value element if missing
                valueEl = new Element("value", REPO_NS);
                varEl.addContent(1, valueEl);
            }
            valueEl.setText(valueMap.get(name));
        }

        // Write back preserving the original encoding / format
        Format format = Format.getPrettyFormat();
        format.setEncoding(StandardCharsets.UTF_8.name());
        format.setIndent("    ");
        XMLOutputter out = new XMLOutputter(format);
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(substVarFile.toPath()), StandardCharsets.UTF_8)) {
            out.output(doc, w);
        }
    }
}

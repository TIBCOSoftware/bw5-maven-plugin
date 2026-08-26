package com.tibco.bw.maven.plugin.descriptor;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses TIBCO BusinessWorks 5 substitution variable files (.substvar).
 *
 * A .substvar file has the structure:
 * <pre>
 * &lt;repository xmlns="http://www.tibco.com/xmlns/repo/types/2002"&gt;
 *   &lt;globalVariables&gt;
 *     &lt;globalVariable&gt;
 *       &lt;name&gt;VarName&lt;/name&gt;
 *       &lt;value&gt;defaultValue&lt;/value&gt;
 *       &lt;deploymentSettable&gt;true&lt;/deploymentSettable&gt;
 *       &lt;type&gt;String&lt;/type&gt;
 *     &lt;/globalVariable&gt;
 *   &lt;/globalVariables&gt;
 * &lt;/repository&gt;
 * </pre>
 */
public class SubstVarParser {

    private static final Namespace REPO_NS =
        Namespace.getNamespace("http://www.tibco.com/xmlns/repo/types/2002");

    /**
     * Parses a .substvar file and returns its global variables.
     */
    public List<GlobalVariable> parse(File substVarFile) throws Exception {
        List<GlobalVariable> variables = new ArrayList<>();

        SAXBuilder builder = new SAXBuilder();
        Document doc = builder.build(substVarFile);
        Element root = doc.getRootElement();

        Element globalVariablesEl = root.getChild("globalVariables", REPO_NS);
        if (globalVariablesEl == null) {
            return variables;
        }

        for (Element varEl : globalVariablesEl.getChildren("globalVariable", REPO_NS)) {
            GlobalVariable var = new GlobalVariable();
            var.name = getText(varEl, "name");
            var.value = getText(varEl, "value");
            // Capture the design-time default separately: deployment overrides (gv.properties)
            // replace value, but the per-service Runtime Variables blocks must keep this default.
            var.defaultValue = var.value;
            var.description = getText(varEl, "description");
            var.type = getText(varEl, "type");

            String deployable = getText(varEl, "deploymentSettable");
            var.requiresConfiguration = "true".equalsIgnoreCase(deployable);

            String serviceSettable = getText(varEl, "serviceSettable");
            var.serviceSettable = "true".equalsIgnoreCase(serviceSettable);

            if (var.name != null && !var.name.isEmpty()) {
                variables.add(var);
            }
        }

        return variables;
    }

    private String getText(Element parent, String childName) {
        Element child = parent.getChild(childName, REPO_NS);
        if (child == null) {
            child = parent.getChild(childName);
        }
        return child != null ? child.getTextTrim() : "";
    }

    /**
     * Represents a single global variable from a .substvar file.
     */
    public static class GlobalVariable {
        public String name;
        public String value;
        /**
         * The design-time (EAR/.substvar) value, preserved even after a deployment override
         * replaces {@link #value}. The per-service {@code Runtime Variables} blocks of the
         * generated {@code -deploy.xml}/{@code -services.properties} render this default, whereas
         * the top-level {@code Global Variables} block renders the (possibly overridden) value —
         * matching AppManage. Falls back to {@code value} when not captured by the parser.
         */
        public String defaultValue;
        public String description;
        public String type;
        /** If true, the variable should be set at deployment time (requiresConfiguration=true in TIBCO.xml) */
        public boolean requiresConfiguration = true;
        /**
         * If true, the variable is service-settable (per-service deployment override).
         * buildear lists these in the adapter AAR's "Runtime Variables" block when the
         * archive descriptor has {@code addServiceSettableGvars=true}. Distinct from
         * {@link #requiresConfiguration} (deploymentSettable).
         */
        public boolean serviceSettable;
        /** Name of the .substvar file this variable was parsed from (e.g. "default.substvar"). */
        public String substVarFile;

        public boolean isPassword() {
            return "password".equalsIgnoreCase(type);
        }

        @Override
        public String toString() {
            return name + "=" + value + " (type=" + type + ", requiresConfiguration=" + requiresConfiguration + ")";
        }
    }
}

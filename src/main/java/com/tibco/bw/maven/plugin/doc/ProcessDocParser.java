package com.tibco.bw.maven.plugin.doc;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.xpath.XPathFactory;

import java.io.File;
import java.util.*;

/**
 * Parses a BW5 .process file into a {@link ProcessDocModel} for documentation generation.
 */
public class ProcessDocParser {

    private static final Namespace PD = Namespace.getNamespace("pd", "http://xmlns.tibco.com/bw/process/2003");
    private static final Namespace XSL = Namespace.getNamespace("xsl", "http://www.w3.org/1999/XSL/Transform");

    public ProcessDocModel parse(File processFile) throws Exception {
        SAXBuilder builder = new SAXBuilder();
        Document doc = builder.build(processFile);
        Element root = doc.getRootElement();

        ProcessDocModel model = new ProcessDocModel();
        model.name = text(root, "name");
        model.targetNamespace = text(root, "targetNamespace");
        model.displayName = toDisplayName(model.name != null ? model.name : processFile.getName());
        model.folderPath = extractFolder(model.name);

        // Parse starter (event source)
        Element starterEl = root.getChild("starter", PD);
        if (starterEl != null) {
            model.starter = parseActivity(starterEl, true);
            model.starter.x = intAttr(starterEl, "pd:x", root, "startX", 50);
            model.starter.y = intAttr(starterEl, "pd:y", root, "startY", 50);
        }

        // Virtual Start state node — present when there is no explicit event-source starter.
        // Defined by pd:startName / pd:startX / pd:startY; referenced in transitions as from="Start".
        if (model.starter == null) {
            String startName = text(root, "startName");
            if (startName != null && !startName.isEmpty()) {
                ProcessDocModel.Activity startAct = new ProcessDocModel.Activity();
                startAct.name = startName;
                startAct.isStarter = true;
                startAct.resourceType = "ae.process.startstate";
                startAct.x = intText(root, "startX", 50);
                startAct.y = intText(root, "startY", 50);
                model.starter = startAct;
            }
        }

        // End node (virtual activity)
        String endName = text(root, "endName");
        if (endName != null && !endName.isEmpty()) {
            ProcessDocModel.Activity end = new ProcessDocModel.Activity();
            end.name = endName;
            end.isEnd = true;
            end.x = intText(root, "endX", 450);
            end.y = intText(root, "endY", 50);
            model.activities.add(end);
        }

        // Parse activities
        for (Element actEl : root.getChildren("activity", PD)) {
            model.activities.add(parseActivity(actEl, false));
        }

        // Parse transitions
        for (Element trEl : root.getChildren("transition", PD)) {
            ProcessDocModel.Transition tr = new ProcessDocModel.Transition();
            tr.from = textDirect(trEl, "from");
            tr.to = textDirect(trEl, "to");
            tr.conditionType = textDirect(trEl, "conditionType");
            tr.condition = textDirect(trEl, "condition");
            if (tr.from != null && tr.to != null) {
                model.transitions.add(tr);
            }
        }

        // Detect ProcessGroup activities: those with outgoing transitions are start states,
        // those without are stop states.
        for (ProcessDocModel.Activity a : model.activities) {
            if ("com.tibco.pe.core.ProcessGroup".equals(a.type)
                    && (a.resourceType == null || a.resourceType.isEmpty())) {
                boolean hasOutgoing = false;
                for (ProcessDocModel.Transition t : model.transitions) {
                    if (a.name.equals(t.from)) { hasOutgoing = true; break; }
                }
                a.resourceType = hasOutgoing ? "ae.process.startstate" : "ae.process.stopstate";
            }
        }

        return model;
    }

    private ProcessDocModel.Activity parseActivity(Element el, boolean isStarter) {
        ProcessDocModel.Activity act = new ProcessDocModel.Activity();
        act.isStarter = isStarter;
        act.name = el.getAttributeValue("name");
        act.type = text(el, "type");
        act.resourceType = text(el, "resourceType");
        act.x = intText(el, "x", 50);
        act.y = intText(el, "y", 50);

        // Config summary and special-case fields
        Element configEl = el.getChild("config");
        if (configEl != null) {
            act.configSummary = extractConfigSummary(configEl);
            // Extract called process path for CallProcessActivity
            if ("com.tibco.pe.core.CallProcessActivity".equals(act.type)) {
                Element pnEl = configEl.getChild("processName");
                if (pnEl != null && !pnEl.getTextTrim().isEmpty()) {
                    act.calledProcessPath = pnEl.getTextTrim();
                }
            }
        }

        // Input mappings
        Element bindingsEl = el.getChild("inputBindings", PD);
        if (bindingsEl == null) {
            // Some activities have inputBindings as direct child without namespace
            bindingsEl = el.getChild("inputBindings");
        }
        if (bindingsEl != null) {
            act.inputMappings = parseMappings(bindingsEl);
        }

        return act;
    }

    /**
     * Parses XSL input bindings into a flat list of field mappings.
     * Handles nested structures, xsl:value-of, xsl:if conditions, and literal values.
     */
    private List<ProcessDocModel.FieldMapping> parseMappings(Element bindingsEl) {
        List<ProcessDocModel.FieldMapping> result = new ArrayList<>();
        // Walk the bindings tree to find xsl:value-of elements and their target paths
        collectMappings(bindingsEl, new ArrayList<>(), null, result);
        return result;
    }

    private void collectMappings(Element el, List<String> pathStack,
                                  String currentCondition,
                                  List<ProcessDocModel.FieldMapping> result) {

        String tag = el.getName();
        Namespace ns = el.getNamespace();

        // XSL elements we handle
        if (XSL.equals(ns)) {
            switch (tag) {
                case "value-of": {
                    // This is a leaf mapping
                    String select = el.getAttributeValue("select");
                    if (select != null && !pathStack.isEmpty()) {
                        ProcessDocModel.FieldMapping m = new ProcessDocModel.FieldMapping();
                        m.targetField = pathStack.get(pathStack.size() - 1);
                        m.targetPath = String.join("/", pathStack);
                        m.sourceExpression = cleanSelectExpr(select);
                        m.isLiteral = isLiteral(select);
                        m.isConditional = currentCondition != null;
                        m.condition = currentCondition;
                        result.add(m);
                    }
                    return;
                }
                case "if": {
                    String test = el.getAttributeValue("test");
                    for (Element child : el.getChildren()) {
                        collectMappings(child, new ArrayList<>(pathStack), test, result);
                    }
                    return;
                }
                case "for-each":
                case "choose":
                case "when":
                case "otherwise":
                case "copy-of": {
                    String test = el.getAttributeValue("select");
                    for (Element child : el.getChildren()) {
                        collectMappings(child, new ArrayList<>(pathStack), test, result);
                    }
                    return;
                }
                default: {
                    for (Element child : el.getChildren()) {
                        collectMappings(child, new ArrayList<>(pathStack), currentCondition, result);
                    }
                    return;
                }
            }
        }

        // Non-XSL element: it's a target field name in the mapping
        // Skip namespace declaration elements and root wrapper elements
        List<Element> children = el.getChildren();
        boolean hasXslChildren = children.stream().anyMatch(c -> XSL.equals(c.getNamespace()));
        boolean hasNonXslChildren = children.stream().anyMatch(c -> !XSL.equals(c.getNamespace()));

        // Check for inline xsl:value-of as only/main child
        Element valueOfChild = el.getChild("value-of", XSL);
        if (valueOfChild != null) {
            String select = valueOfChild.getAttributeValue("select");
            if (select != null) {
                ProcessDocModel.FieldMapping m = new ProcessDocModel.FieldMapping();
                m.targetField = tag;
                List<String> fullPath = new ArrayList<>(pathStack);
                fullPath.add(tag);
                m.targetPath = String.join("/", fullPath);
                m.sourceExpression = cleanSelectExpr(select);
                m.isLiteral = isLiteral(select);
                m.isConditional = currentCondition != null;
                m.condition = currentCondition;
                result.add(m);
                // Continue for any other children
                for (Element child : children) {
                    if (child != valueOfChild) {
                        List<String> newPath = new ArrayList<>(pathStack);
                        newPath.add(tag);
                        collectMappings(child, newPath, currentCondition, result);
                    }
                }
                return;
            }
        }

        // Recurse into children with this element as part of the path
        if (!children.isEmpty()) {
            List<String> newPath = new ArrayList<>(pathStack);
            // Only add to path if it looks like a target field (not a wrapper like namespace-prefixed elements)
            if (!tag.equals("inputBindings") && !tag.contains(":")) {
                newPath.add(tag);
            }
            for (Element child : children) {
                collectMappings(child, newPath, currentCondition, result);
            }
        }
    }

    private String cleanSelectExpr(String select) {
        if (select == null) return "";
        // Remove outer quotes for literals: "'value'" -> "value"
        if ((select.startsWith("\"'") && select.endsWith("'\""))
                || (select.startsWith("'") && select.endsWith("'"))) {
            return select.substring(1, select.length() - 1);
        }
        return select;
    }

    private boolean isLiteral(String select) {
        if (select == null) return false;
        return (select.startsWith("'") && select.endsWith("'"))
            || (select.startsWith("\"'") && select.endsWith("'\""))
            || (select.startsWith("\"") && select.endsWith("\"") && !select.contains("$") && !select.contains("/"));
    }

    private String extractConfigSummary(Element configEl) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Element child : configEl.getChildren()) {
            String name = child.getName();
            // Skip schema/complex type children
            if (name.equals("Headers") || name.equals("InputHeaders") || name.equals("OutputHeaders")
                    || name.equals("element") || name.equals("complexType") || name.equals("sequence")) {
                continue;
            }
            String val = child.getTextTrim();
            if (!val.isEmpty() && count < 8) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(name).append("=").append(truncate(val, 40));
                count++;
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ---- XML helpers ----

    private String text(Element parent, String childName) {
        Element child = parent.getChild(childName, PD);
        if (child == null) child = parent.getChild(childName);
        return child != null ? child.getTextTrim() : null;
    }

    private String textDirect(Element parent, String childName) {
        Element child = parent.getChild(childName, PD);
        if (child == null) child = parent.getChild(childName);
        return child != null ? child.getTextTrim() : null;
    }

    private int intText(Element parent, String childName, int defaultVal) {
        String s = text(parent, childName);
        if (s == null || s.isEmpty()) return defaultVal;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultVal; }
    }

    private int intAttr(Element el, String nsAttr, Element fallbackParent, String fallbackChild, int defaultVal) {
        // Try pd:x attribute first, then x child element
        String val = el.getAttributeValue("x");
        if (val == null) val = el.getAttributeValue("x", PD);
        if (val == null) val = text(el, "x");
        if (val == null) val = text(fallbackParent, fallbackChild);
        if (val == null) return defaultVal;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return defaultVal; }
    }

    private String toDisplayName(String name) {
        if (name == null) return "Process";
        // Remove path and extension: "Utilities/String/StringUtils.process" -> "StringUtils"
        int slash = name.lastIndexOf('/');
        String base = slash >= 0 ? name.substring(slash + 1) : name;
        int dot = base.lastIndexOf('.');
        return dot >= 0 ? base.substring(0, dot) : base;
    }

    /** Returns the folder portion of a process name, e.g. "Common/ARC_BA1N" from
     *  "Common/ARC_BA1N/ARC_BA1N_Main.process". Returns empty string for root-level processes. */
    private String extractFolder(String name) {
        if (name == null || name.isEmpty()) return "";
        String n = name.endsWith(".process") ? name.substring(0, name.length() - 8) : name;
        int slash = n.lastIndexOf('/');
        return slash > 0 ? n.substring(0, slash) : "";
    }
}

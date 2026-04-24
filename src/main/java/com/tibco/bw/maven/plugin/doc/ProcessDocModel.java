package com.tibco.bw.maven.plugin.doc;

import java.util.ArrayList;
import java.util.List;

/**
 * Data model representing a parsed BW5 process for documentation generation.
 */
public class ProcessDocModel {

    /** Full process name as in the XML (e.g. "Utilities/String/StringUtils.process") */
    public String name;
    /** Display name without path and extension */
    public String displayName;
    /** Folder path without trailing slash (e.g. "Utilities/String"), empty string for root */
    public String folderPath = "";
    /** Target namespace */
    public String targetNamespace;

    /** Process description from &lt;pd:description&gt;, may be null */
    public String description;

    /** The starter (event source) activity */
    public Activity starter;
    /** All regular activities */
    public List<Activity> activities = new ArrayList<>();
    /** All transitions */
    public List<Transition> transitions = new ArrayList<>();
    /** Canvas text labels (annotations) */
    public List<Label> labels = new ArrayList<>();
    /** Activity groups (loop groups, critical sections, etc.) */
    public List<Group> groups = new ArrayList<>();

    /** Returns all activities including the starter for coordinate calculation */
    public List<Activity> allActivities() {
        List<Activity> all = new ArrayList<>();
        if (starter != null) all.add(starter);
        all.addAll(activities);
        return all;
    }

    // -----------------------------------------------------------------------

    public static class Activity {
        public String name;
        /** e.g. "com.tibco.plugin.http.HTTPEventSource" */
        public String type;
        /** e.g. "httppalette.httpEventSource" */
        public String resourceType;
        public int x;
        public int y;
        /** Whether this is the starter (event source) */
        public boolean isStarter;
        /** Whether this is the end node */
        public boolean isEnd;
        public int endX;
        public int endY;
        /** Raw config XML as string (for detail display) */
        public String configSummary;
        /** For CallProcessActivity: the target process path from &lt;processName&gt; config */
        public String calledProcessPath;
        /** Parsed input mappings */
        public List<FieldMapping> inputMappings = new ArrayList<>();

        /** Returns a friendly short type name */
        public String shortType() {
            if (type == null) return "";
            int dot = type.lastIndexOf('.');
            return dot >= 0 ? type.substring(dot + 1) : type;
        }

        /** Returns a CSS class for styling based on activity type */
        public String cssClass() {
            if (isEnd) return "act-end";
            if ("ae.process.stopstate".equals(resourceType)) return "act-end";
            if ("ae.process.startstate".equals(resourceType) && (type == null || type.isEmpty())) return "act-startstate";
            if (isStarter) return "act-starter";
            if (type == null) return "act-default";
            if (type.contains(".http.")) return "act-http";
            if (type.contains(".timer.")) return "act-timer";
            if (type.contains(".jms.")) return "act-jms";
            if (type.contains(".jdbc.")) return "act-jdbc";
            if (type.contains(".mail.")) return "act-mail";
            if (type.contains(".java.")) return "act-java";
            if (type.contains(".core.WriteToLog")) return "act-log";
            if (type.contains(".core.Assign")) return "act-assign";
            if (type.contains(".core.CallProcess")) return "act-callproc";
            if (type.contains(".core.Mapper")) return "act-mapper";
            return "act-default";
        }
    }

    public static class Transition {
        public String from;
        public String to;
        /** "always", "success", "error", "xpath", "otherwise" */
        public String conditionType;
        /** XPath expression for xpath-type transitions (from &lt;pd:xpath&gt;) */
        public String condition;
        /** Human-readable label for the xpath condition (from &lt;pd:xpathDescription&gt;) */
        public String conditionDescription;

        public String conditionLabel() {
            if (conditionType == null || "always".equalsIgnoreCase(conditionType)) return "";
            if ("xpath".equalsIgnoreCase(conditionType)) {
                if (conditionDescription != null && !conditionDescription.isEmpty()) return conditionDescription;
                return condition != null ? condition : "xpath";
            }
            return conditionType;
        }

        public String cssClass() {
            if (conditionType == null || "always".equalsIgnoreCase(conditionType)) return "tr-always";
            if ("error".equalsIgnoreCase(conditionType)) return "tr-error";
            if ("successWithCondition".equalsIgnoreCase(conditionType)) return "tr-cond";
            if ("otherwise".equalsIgnoreCase(conditionType)) return "tr-otherwise";
            return "tr-success";
        }
    }

    public static class Group {
        public String name;
        /** e.g. "com.tibco.pe.core.LoopGroup" */
        public String type;
        public int x;
        public int y;
        public int width;
        public int height;
    }

    public static class Label {
        public int x;
        public int y;
        public String text;
    }

    public static class FieldMapping {
        /** Target field name (XML element being set) */
        public String targetField;
        /** Full target path */
        public String targetPath;
        /** XPath/XSL source expression */
        public String sourceExpression;
        /** Whether this is a literal value (xsl:value-of select="'literal'") */
        public boolean isLiteral;
        /** Whether this is a conditional mapping (xsl:if / xsl:when / xsl:otherwise) */
        public boolean isConditional;
        /** The condition expression (xsl:if test / xsl:when test) */
        public String condition;
        /** "if" | "when" | "otherwise" | null */
        public String conditionKind;
        /** Unique ID of the enclosing xsl:choose block (0 = not inside a choose) */
        public int chooseId;
    }
}

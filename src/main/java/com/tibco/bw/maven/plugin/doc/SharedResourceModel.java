package com.tibco.bw.maven.plugin.doc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data model for a parsed BW5 SharedResource (JDBC, HTTP, JMS, etc.).
 */
public class SharedResourceModel {

    /** Full resource path, e.g. "/SharedResources/Connections/MyJDBC" */
    public String name;
    /** Last segment of the path, used as display name */
    public String displayName;
    /** Activity type string, e.g. "jdbcpalette.JDBCConnection" */
    public String type;
    /** Palette resource type string, e.g. "jdbcpalette.jdbcConnectionResource" */
    String resourceType;
    /** Config key→value pairs from the &lt;config&gt; element */
    public Map<String, String> config = new LinkedHashMap<>();
    /**
     * Activities (across all processes) that reference this shared resource.
     * Populated by the generator after all processes are parsed.
     * Each entry: "ProcessDisplayName / ActivityName"
     */
    public List<String> usedBy = new ArrayList<>();

    /** Short type name (last dot-segment) */
    public String shortType() {
        if (type == null) return "";
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }
}

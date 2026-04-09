package com.tibco.bw.maven.plugin.doc;

import java.util.*;

/**
 * Generates an SVG diagram for a BW5 process.
 *
 * <p>Uses the x/y coordinates stored in the process XML to position activities.
 * Draws activities as labelled boxes and transitions as arrows with condition labels.</p>
 */
public class SvgDiagramGenerator {

    // Activity box dimensions (icon 32px + label area)
    private static final int BOX_W = 100;
    private static final int BOX_H = 64;
    private static final int BOX_HALF_W = BOX_W / 2;
    private static final int BOX_HALF_H = BOX_H / 2;
    private static final int ICON_SIZE = 32;

    // Padding around the whole diagram
    private static final int PADDING = 30;

    public String generate(ProcessDocModel model) {
        List<ProcessDocModel.Activity> activities = model.allActivities();
        if (activities.isEmpty()) return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"60\"></svg>";

        // Calculate canvas bounds
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (ProcessDocModel.Activity a : activities) {
            minX = Math.min(minX, a.x);
            minY = Math.min(minY, a.y);
            maxX = Math.max(maxX, a.x);
            maxY = Math.max(maxY, a.y);
        }

        // Offset so activities start at PADDING
        final int offsetX = PADDING + BOX_HALF_W - minX;
        final int offsetY = PADDING + BOX_HALF_H - minY;

        int width  = maxX - minX + BOX_W + PADDING * 2;
        int height = maxY - minY + BOX_H + PADDING * 2;

        // Build activity name → position map
        Map<String, int[]> positions = new HashMap<>();
        for (ProcessDocModel.Activity a : activities) {
            positions.put(a.name, new int[]{ a.x + offsetX, a.y + offsetY });
        }

        StringBuilder svg = new StringBuilder();
        svg.append(String.format(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" "
            + "style=\"font-family:Arial,sans-serif;background:#fafafa;border:1px solid #e0e0e0;border-radius:6px;\">%n",
            width, height));

        appendDefs(svg);

        // Draw transitions first (below activities)
        for (ProcessDocModel.Transition tr : model.transitions) {
            int[] from = positions.get(tr.from);
            int[] to   = positions.get(tr.to);
            if (from == null || to == null) continue;
            drawTransition(svg, from, to, tr, BOX_HALF_W, BOX_HALF_H);
        }

        // Draw activities on top
        for (ProcessDocModel.Activity a : activities) {
            int cx = a.x + offsetX;
            int cy = a.y + offsetY;
            drawActivity(svg, a, cx, cy);
        }

        svg.append("</svg>");
        return svg.toString();
    }

    private void appendDefs(StringBuilder svg) {
        svg.append("  <defs>\n");
        // Arrow markers for different transition types
        appendArrowMarker(svg, "arrow-always",    "#555555");
        appendArrowMarker(svg, "arrow-success",   "#27ae60");
        appendArrowMarker(svg, "arrow-error",     "#e74c3c");
        appendArrowMarker(svg, "arrow-cond",      "#e67e22");
        appendArrowMarker(svg, "arrow-otherwise", "#8e44ad");
        svg.append("  </defs>\n");
    }

    private void appendArrowMarker(StringBuilder svg, String id, String color) {
        svg.append(String.format(
            "    <marker id=\"%s\" markerWidth=\"8\" markerHeight=\"8\" refX=\"6\" refY=\"3\" orient=\"auto\">"
            + "<path d=\"M0,0 L0,6 L8,3 z\" fill=\"%s\"/></marker>%n", id, color));
    }

    private void drawActivity(StringBuilder svg, ProcessDocModel.Activity a, int cx, int cy) {
        int x = cx - BOX_HALF_W;
        int y = cy - BOX_HALF_H;

        if (a.isEnd || "ae.process.stopstate".equals(a.resourceType)) {
            // End / stop-state: icon + label, same style as start-state
            svg.append(String.format(
                "  <circle cx=\"%d\" cy=\"%d\" r=\"22\" fill=\"#b71c1c\" stroke=\"#7f0000\" stroke-width=\"2\"/>%n",
                cx, cy));
            svg.append("  ");
            svg.append(ActivityIconRegistry.getImageElement(null, "ae.process.stopstate", cx, cy, ICON_SIZE));
            svg.append(System.lineSeparator());
            String ename = a.name != null ? a.name : "";
            svg.append(String.format(
                "  <text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-size=\"10\" fill=\"#1a1a1a\">%s</text>%n",
                cx, cy + 32, escXml(ename)));
            return;
        }

        // Start-state node (no event source — pure process-group start state): render as
        // a filled green circle with the icon overlaid, then the name label.
        if ("act-startstate".equals(a.cssClass())) {
            svg.append(String.format(
                "  <circle cx=\"%d\" cy=\"%d\" r=\"22\" fill=\"#1b5e20\" stroke=\"#2e7d32\" stroke-width=\"2\"/>%n",
                cx, cy));
            svg.append("  ");
            svg.append(ActivityIconRegistry.getImageElement(a.type, a.resourceType, cx, cy, ICON_SIZE));
            svg.append(System.lineSeparator());
            String sname = a.name != null ? a.name : "";
            svg.append(String.format(
                "  <text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-size=\"10\" fill=\"#1a1a1a\">%s</text>%n",
                cx, cy + 32, escXml(sname)));
            return;
        }

        String fillColor   = getColor(a);
        String strokeColor = getDarkerColor(a);
        int radius         = a.isStarter ? 8 : 6;
        int strokeW        = a.isStarter ? 3 : 1;

        // Background box
        svg.append(String.format(
            "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" rx=\"%d\" ry=\"%d\" "
            + "fill=\"%s\" stroke=\"%s\" stroke-width=\"%d\"/>%n",
            x, y, BOX_W, BOX_H, radius, radius, fillColor, strokeColor, strokeW));

        // Icon centred in the upper portion of the box
        int iconY = y + 4;   // 4px top padding
        svg.append("  ");
        svg.append(ActivityIconRegistry.getImageElement(a.type, a.resourceType,
            cx, iconY + ICON_SIZE / 2, ICON_SIZE));
        svg.append(System.lineSeparator());

        // Activity name label below the icon
        String name = a.name != null ? a.name : "";
        String[] lines = wrapText(name, 14);
        int labelY = iconY + ICON_SIZE + 11;   // baseline of first label line
        for (String line : lines) {
            svg.append(String.format(
                "  <text x=\"%d\" y=\"%d\" text-anchor=\"middle\" "
                + "font-size=\"10\" fill=\"#1a1a1a\" font-weight=\"%s\">%s</text>%n",
                cx, labelY, a.isStarter ? "bold" : "normal", escXml(line)));
            labelY += 12;
        }
    }

    private void drawTransition(StringBuilder svg, int[] from, int[] to,
                                ProcessDocModel.Transition tr,
                                int halfW, int halfH) {
        // Compute edge attachment points (simplified: center of box edges)
        int x1 = from[0];
        int y1 = from[1];
        int x2 = to[0];
        int y2 = to[1];

        // Adjust to box edges (right/left/top/bottom depending on direction)
        int[] start = boxEdge(x1, y1, x2, y2, halfW, halfH);
        int[] end   = boxEdge(x2, y2, x1, y1, halfW, halfH);

        String color = transitionColor(tr);
        String markerId = transitionMarkerId(tr);

        // Cubic bezier control points
        int dx = end[0] - start[0];
        int dy = end[1] - start[1];
        int cx1 = start[0] + dx / 3;
        int cy1 = start[1];
        int cx2 = end[0] - dx / 3;
        int cy2 = end[1];

        svg.append(String.format(
            "  <path d=\"M%d,%d C%d,%d %d,%d %d,%d\" fill=\"none\" stroke=\"%s\" stroke-width=\"1.8\" "
            + "marker-end=\"url(#%s)\" %s/>%n",
            start[0], start[1], cx1, cy1, cx2, cy2, end[0], end[1],
            color, markerId,
            tr.conditionType != null && "error".equalsIgnoreCase(tr.conditionType)
                ? "stroke-dasharray=\"5,3\"" : ""));

        // Condition label on transition
        String label = tr.conditionLabel();
        if (!label.isEmpty()) {
            int lx = (start[0] + end[0]) / 2;
            int ly = (start[1] + end[1]) / 2 - 6;
            svg.append(String.format(
                "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"14\" rx=\"3\" fill=\"white\" opacity=\"0.85\"/>%n",
                lx - 30, ly - 9, 60));
            svg.append(String.format(
                "  <text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-size=\"9\" fill=\"%s\">%s</text>%n",
                lx, ly, color, escXml(truncate(label, 20))));
        }
    }

    private int[] boxEdge(int cx, int cy, int targetX, int targetY, int hw, int hh) {
        double dx = targetX - cx;
        double dy = targetY - cy;
        if (Math.abs(dx) < 1 && Math.abs(dy) < 1) return new int[]{cx, cy};
        double absDx = Math.abs(dx);
        double absDy = Math.abs(dy);

        if (absDx / hw > absDy / hh) {
            // Hit left or right edge
            int ex = (int)(cx + Math.signum(dx) * hw);
            int ey = (int)(cy + dy * hw / absDx);
            return new int[]{ex, ey};
        } else {
            // Hit top or bottom edge
            int ex = (int)(cx + dx * hh / absDy);
            int ey = (int)(cy + Math.signum(dy) * hh);
            return new int[]{ex, ey};
        }
    }

    private String getColor(ProcessDocModel.Activity a) {
        switch (a.cssClass()) {
            case "act-startstate": return "#e8f5e9";
            case "act-starter":   return "#1565c0";
            case "act-http":      return "#e3f2fd";
            case "act-timer":     return "#fff8e1";
            case "act-jms":       return "#e8f5e9";
            case "act-jdbc":      return "#fce4ec";
            case "act-mail":      return "#f3e5f5";
            case "act-java":      return "#e0f2f1";
            case "act-log":       return "#f5f5f5";
            case "act-callproc":  return "#fff3e0";
            case "act-mapper":    return "#e8eaf6";
            default:              return "#f5f5f5";
        }
    }

    private String getDarkerColor(ProcessDocModel.Activity a) {
        switch (a.cssClass()) {
            case "act-startstate": return "#2e7d32";
            case "act-starter":   return "#0d47a1";
            case "act-http":      return "#1565c0";
            case "act-timer":     return "#f57f17";
            case "act-jms":       return "#2e7d32";
            case "act-jdbc":      return "#c62828";
            case "act-mail":      return "#6a1b9a";
            case "act-java":      return "#00695c";
            case "act-log":       return "#757575";
            case "act-callproc":  return "#e65100";
            case "act-mapper":    return "#283593";
            default:              return "#757575";
        }
    }

    private String transitionColor(ProcessDocModel.Transition tr) {
        if (tr.conditionType == null) return "#555";
        switch (tr.conditionType.toLowerCase()) {
            case "error":                return "#e74c3c";
            case "successwithcondition": return "#e67e22";
            case "otherwise":            return "#8e44ad";
            case "success":              return "#27ae60";
            default:                     return "#555";
        }
    }

    private String transitionMarkerId(ProcessDocModel.Transition tr) {
        if (tr.conditionType == null) return "arrow-always";
        switch (tr.conditionType.toLowerCase()) {
            case "error":                return "arrow-error";
            case "successwithcondition": return "arrow-cond";
            case "otherwise":            return "arrow-otherwise";
            case "success":              return "arrow-success";
            default:                     return "arrow-always";
        }
    }

    private String[] wrapText(String text, int maxChars) {
        if (text.length() <= maxChars) return new String[]{ text };
        // Try to break at space near middle
        int mid = text.length() / 2;
        int space = text.indexOf(' ', mid);
        if (space < 0) space = text.lastIndexOf(' ', mid);
        if (space > 0) {
            return new String[]{ text.substring(0, space).trim(), text.substring(space).trim() };
        }
        return new String[]{ text.substring(0, maxChars), text.substring(maxChars) };
    }

    private String escXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}

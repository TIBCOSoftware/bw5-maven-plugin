package com.tibco.bw.maven.plugin.doc;

import java.util.*;

/**
 * Generates an SVG diagram for a BW5 process.
 *
 * <p>Renders activities as icon + label (no surrounding rectangle), matching the
 * visual style of TIBCO Designer 5.x. Start/End states are drawn as small circles.</p>
 */
public class SvgDiagramGenerator {

    private static final int ICON_SIZE  = 32;
    private static final int ICON_HALF  = ICON_SIZE / 2;   // 16 — used for edge connection points

    /** Vertical distance from activity centre to the first label baseline. */
    private static final int LABEL_OFFSET = ICON_HALF + 13;

    /** Extra canvas space around the outermost activity. */
    private static final int PADDING = 40;

    /** Approximate half-width of the label area (for canvas sizing only). */
    private static final int SLOT_HALF_W = 44;
    /** Approximate half-height including label (for canvas sizing only). */
    private static final int SLOT_HALF_H = 30;

    // ── Public API ────────────────────────────────────────────────────────────

    public String generate(ProcessDocModel model) {
        List<ProcessDocModel.Activity> activities = model.allActivities();
        if (activities.isEmpty()) {
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"60\"></svg>";
        }

        // Calculate canvas bounds from raw process coordinates
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (ProcessDocModel.Activity a : activities) {
            minX = Math.min(minX, a.x); minY = Math.min(minY, a.y);
            maxX = Math.max(maxX, a.x); maxY = Math.max(maxY, a.y);
        }
        // Include group bounding boxes in canvas sizing
        for (ProcessDocModel.Group g : model.groups) {
            minX = Math.min(minX, g.x); minY = Math.min(minY, g.y);
            maxX = Math.max(maxX, g.x + g.width); maxY = Math.max(maxY, g.y + g.height);
        }
        // Include canvas labels so they are never clipped outside the viewport
        for (ProcessDocModel.Label lbl : model.labels) {
            if (lbl.x >= 0 && lbl.y >= 0) {
                // Approximate label width: ~6px per char, max line ~40 chars
                int approxW = Math.min(lbl.text != null ? lbl.text.length() * 6 : 0, 260);
                int approxH = lbl.text != null ? (lbl.text.split("\\r?\\n").length + 1) * 13 : 13;
                minX = Math.min(minX, lbl.x); minY = Math.min(minY, lbl.y);
                maxX = Math.max(maxX, lbl.x + approxW); maxY = Math.max(maxY, lbl.y + approxH);
            }
        }

        final int offsetX = PADDING + SLOT_HALF_W - minX;
        final int offsetY = PADDING + SLOT_HALF_H - minY;

        int width  = maxX - minX + SLOT_HALF_W * 2 + PADDING * 2;
        int height = maxY - minY + SLOT_HALF_H * 2 + PADDING * 2;

        // Activity name → canvas centre position
        Map<String, int[]> positions = new HashMap<>();
        for (ProcessDocModel.Activity a : activities) {
            positions.put(a.name, new int[]{ a.x + offsetX, a.y + offsetY });
        }
        // Groups expose distinct entry and exit boundary points so transitions
        // connect to the correct edge of the group box.
        for (ProcessDocModel.Group g : model.groups) {
            if (g.name == null) continue;
            int midY = g.y + g.height / 2 + offsetY;
            positions.put(g.name + "#entry", new int[]{ g.x + offsetX,           midY });
            positions.put(g.name + "#exit",  new int[]{ g.x + g.width + offsetX, midY });
        }

        StringBuilder svg = new StringBuilder();
        svg.append(String.format(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" "
            + "xmlns:xlink=\"http://www.w3.org/1999/xlink\" "
            + "viewBox=\"0 0 %d %d\" "
            + "width=\"100%%\" "
            + "style=\"font-family:Arial,sans-serif;background:#fafbff;display:block;\">%n",
            width, height));

        appendDefs(svg);

        // Groups — drawn first (background, behind everything)
        for (ProcessDocModel.Group g : model.groups) {
            drawGroup(svg, g, g.x + offsetX, g.y + offsetY);
        }

        // Transitions first (drawn behind activities)
        for (ProcessDocModel.Transition tr : model.transitions) {
            int[] from = positions.get(tr.from);
            int[] to   = positions.get(tr.to);
            if (from == null || to == null) continue;
            drawTransition(svg, from, to, tr);
        }

        // Activities on top
        for (ProcessDocModel.Activity a : activities) {
            drawActivity(svg, a, a.x + offsetX, a.y + offsetY);
        }

        // Canvas labels (text annotations)
        for (ProcessDocModel.Label lbl : model.labels) {
            drawCanvasLabel(svg, lbl, lbl.x + offsetX, lbl.y + offsetY);
        }

        svg.append("</svg>");
        return svg.toString();
    }

    // ── Defs (arrow markers) ──────────────────────────────────────────────────

    private void appendDefs(StringBuilder svg) {
        svg.append("  <defs>\n");
        appendArrowMarker(svg, "arr-always",    "#888888");
        appendArrowMarker(svg, "arr-success",   "#27ae60");
        appendArrowMarker(svg, "arr-error",     "#e74c3c");
        appendArrowMarker(svg, "arr-cond",      "#e67e22");
        appendArrowMarker(svg, "arr-otherwise", "#8e44ad");
        svg.append("  </defs>\n");
    }

    private void appendArrowMarker(StringBuilder svg, String id, String color) {
        svg.append(String.format(
            "    <marker id=\"%s\" markerWidth=\"8\" markerHeight=\"8\" "
            + "refX=\"6\" refY=\"3\" orient=\"auto\">"
            + "<path d=\"M0,0 L0,6 L8,3 z\" fill=\"%s\"/></marker>%n",
            id, color));
    }

    // ── Activity rendering ────────────────────────────────────────────────────

    // ── Group rendering ───────────────────────────────────────────────────────

    private static final int GROUP_TITLE_H = 20;
    private static final String GROUP_TITLE_FILL  = "#c3bdf5";
    private static final String GROUP_TITLE_STROKE = "#7e57c2";
    private static final String GROUP_BODY_FILL    = "rgba(195,189,245,0.10)";

    private void drawGroup(StringBuilder svg, ProcessDocModel.Group g, int gx, int gy) {
        String label = g.name != null ? g.name : "";
        int w = g.width;
        int h = g.height;

        // Title bar
        svg.append(String.format(
            "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" "
            + "fill=\"%s\" stroke=\"%s\" stroke-width=\"1\" rx=\"3\" ry=\"0\"/>%n",
            gx, gy, w, GROUP_TITLE_H, GROUP_TITLE_FILL, GROUP_TITLE_STROKE));

        // Body (transparent fill so activities show through)
        svg.append(String.format(
            "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" "
            + "fill=\"%s\" stroke=\"%s\" stroke-width=\"1\" "
            + "stroke-dasharray=\"4,2\" rx=\"0\" ry=\"3\"/>%n",
            gx, gy + GROUP_TITLE_H, w, h - GROUP_TITLE_H, GROUP_BODY_FILL, GROUP_TITLE_STROKE));

        // Group name centred in title bar
        String[] labelLines = wrapText(label, (int)(w / 6.5));
        int ty = gy + GROUP_TITLE_H / 2 + 4;
        for (String line : labelLines) {
            svg.append(String.format(
                "  <text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-size=\"10\" "
                + "fill=\"#311b92\" font-weight=\"bold\">%s</text>%n",
                gx + w / 2, ty, escXml(line)));
            ty += 12;
        }

        // Entry marker (▶ triangle on left edge at mid-height) and exit marker (■ on right edge)
        int midY = gy + h / 2;
        svg.append(String.format(
            "  <polygon points=\"%d,%d %d,%d %d,%d\" fill=\"%s\" stroke=\"none\"/>%n",
            gx - 6, midY - 5, gx - 6, midY + 5, gx, midY,
            GROUP_TITLE_STROKE));
        svg.append(String.format(
            "  <rect x=\"%d\" y=\"%d\" width=\"7\" height=\"7\" fill=\"%s\" stroke=\"none\"/>%n",
            gx + w - 1, midY - 3,
            GROUP_TITLE_STROKE));
    }

    // ── Activity rendering ────────────────────────────────────────────────────

    private void drawActivity(StringBuilder svg, ProcessDocModel.Activity a, int cx, int cy) {

        // End / stop-state: solid red bull's-eye (Designer "End" node)
        if (a.isEnd || "ae.process.stopstate".equals(a.resourceType)) {
            svg.append(String.format(
                "  <circle cx=\"%d\" cy=\"%d\" r=\"14\" fill=\"#c62828\" stroke=\"#7f0000\" stroke-width=\"2\"/>%n",
                cx, cy));
            svg.append(String.format(
                "  <circle cx=\"%d\" cy=\"%d\" r=\"5\" fill=\"#7f0000\"/>%n",
                cx, cy));
            appendLabel(svg, a.name, cx, cy + LABEL_OFFSET, false);
            return;
        }

        // Process start-state node: solid green circle (Designer "Start" node)
        if ("act-startstate".equals(a.cssClass())) {
            svg.append(String.format(
                "  <circle cx=\"%d\" cy=\"%d\" r=\"14\" fill=\"#2e7d32\" stroke=\"#1b5e20\" stroke-width=\"2\"/>%n",
                cx, cy));
            svg.append(String.format(
                "  <circle cx=\"%d\" cy=\"%d\" r=\"5\" fill=\"#a5d6a7\"/>%n",
                cx, cy));
            appendLabel(svg, a.name, cx, cy + LABEL_OFFSET, false);
            return;
        }

        // All other activities (including event-source starters): icon + label, no box
        svg.append("  ");
        svg.append(ActivityIconRegistry.getImageElement(a.type, a.resourceType, cx, cy, ICON_SIZE));
        svg.append(System.lineSeparator());
        appendLabel(svg, a.name, cx, cy + LABEL_OFFSET, a.isStarter);
    }

    private void appendLabel(StringBuilder svg, String name, int cx, int baseY, boolean bold) {
        String text  = name != null ? name : "";
        String[] lines = wrapText(text, 14);
        int y = baseY;
        for (String line : lines) {
            svg.append(String.format(
                "  <text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-size=\"10\" "
                + "fill=\"#1a1a1a\"%s>%s</text>%n",
                cx, y, bold ? " font-weight=\"bold\"" : "", escXml(line)));
            y += 12;
        }
    }

    private void drawCanvasLabel(StringBuilder svg, ProcessDocModel.Label lbl, int cx, int cy) {
        // Split on actual newlines first, then wrap long lines
        String[] rawLines = lbl.text.split("\\r?\\n");
        int y = cy;
        for (String raw : rawLines) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) { y += 7; continue; }
            String[] wrapped = wrapText(trimmed, 40);
            for (String line : wrapped) {
                svg.append(String.format(
                    "  <text x=\"%d\" y=\"%d\" text-anchor=\"start\" font-size=\"10\" "
                    + "fill=\"#555\" font-style=\"italic\">%s</text>%n",
                    cx, y, escXml(line)));
                y += 13;
            }
        }
    }

    // ── Transition rendering ──────────────────────────────────────────────────

    private void drawTransition(StringBuilder svg, int[] from, int[] to,
                                ProcessDocModel.Transition tr) {
        int[] start = iconEdge(from[0], from[1], to[0],   to[1]);
        int[] end   = iconEdge(to[0],   to[1],   from[0], from[1]);

        String color    = transitionColor(tr);
        String markerId = transitionMarkerId(tr);

        int dx  = end[0] - start[0];
        int cx1 = start[0] + dx / 3;
        int cy1 = start[1];
        int cx2 = end[0] - dx / 3;
        int cy2 = end[1];

        boolean isError = tr.conditionType != null
            && "error".equalsIgnoreCase(tr.conditionType);

        svg.append(String.format(
            "  <path d=\"M%d,%d C%d,%d %d,%d %d,%d\" fill=\"none\" stroke=\"%s\" "
            + "stroke-width=\"1.5\" marker-end=\"url(#%s)\"%s/>%n",
            start[0], start[1], cx1, cy1, cx2, cy2, end[0], end[1],
            color, markerId,
            isError ? " stroke-dasharray=\"5,3\"" : ""));

        // Condition label mid-arc
        String label = tr.conditionLabel();
        if (!label.isEmpty()) {
            int lx = start[0] + (end[0] - start[0]) / 2;
            int ly = start[1] + (end[1] - start[1]) / 2 - 6;
            svg.append(String.format(
                "  <rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"14\" rx=\"3\" "
                + "fill=\"white\" opacity=\"0.85\"/>%n",
                lx - 30, ly - 9, 60));
            svg.append(String.format(
                "  <text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-size=\"9\" fill=\"%s\">%s</text>%n",
                lx, ly, color, escXml(truncate(label, 20))));
        }
    }

    /**
     * Returns the point on the edge of the icon hit-area (circle of radius ICON_HALF)
     * closest to the target position.
     */
    private int[] iconEdge(int cx, int cy, int targetX, int targetY) {
        double dx = targetX - cx;
        double dy = targetY - cy;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < 1) return new int[]{cx, cy};
        return new int[]{
            (int)(cx + dx / dist * ICON_HALF),
            (int)(cy + dy / dist * ICON_HALF)
        };
    }

    // ── Transition colour / marker helpers ────────────────────────────────────

    private String transitionColor(ProcessDocModel.Transition tr) {
        if (tr.conditionType == null) return "#888";
        switch (tr.conditionType.toLowerCase(Locale.ROOT)) {
            case "error":                return "#e74c3c";
            case "xpath":
            case "successwithcondition": return "#e67e22";
            case "otherwise":            return "#8e44ad";
            case "success":              return "#27ae60";
            default:                     return "#888";
        }
    }

    private String transitionMarkerId(ProcessDocModel.Transition tr) {
        if (tr.conditionType == null) return "arr-always";
        switch (tr.conditionType.toLowerCase(Locale.ROOT)) {
            case "error":                return "arr-error";
            case "xpath":
            case "successwithcondition": return "arr-cond";
            case "otherwise":            return "arr-otherwise";
            case "success":              return "arr-success";
            default:                     return "arr-always";
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String[] wrapText(String text, int maxChars) {
        if (text.length() <= maxChars) return new String[]{ text };
        int mid   = text.length() / 2;
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

package com.tibco.bw.maven.plugin.doc;

import java.io.InputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Registry of base64-encoded SVG icons for BW5 palette activities.
 *
 * <p>Each icon is a 32×32px SVG, encoded as a base64 data URI suitable for use
 * in {@code <image>} elements inside the SVG process diagram. Icons are designed
 * to visually approximate the TIBCO BusinessWorks 5 Designer palette icons using
 * simple, self-contained SVG shapes.</p>
 *
 * <p>Usage in SVG:</p>
 * <pre>
 * String iconUri = ActivityIconRegistry.getIcon("com.tibco.plugin.http.HTTPEventSource");
 * // → "data:image/svg+xml;base64,PHN2ZyB4bWxucz..."
 *
 * // In SVG:
 * svg.append(String.format(
 *   "&lt;image x=\"%d\" y=\"%d\" width=\"32\" height=\"32\" href=\"%s\"/&gt;",
 *   activityX - 16, activityY - 16, iconUri));
 * </pre>
 */
public class ActivityIconRegistry {

    private static final Map<String, String> ICON_BY_TYPE = new HashMap<>();
    /** Keyed by palette {@code resourceType}, e.g. {@code "httppalette.httpEventSource"}. */
    private static final Map<String, String> ICON_BY_RESOURCE_TYPE = new HashMap<>();
    private static final String FALLBACK_ICON;

    static {
        // ── Timer ────────────────────────────────────────────────────────────
        register("timer.TimerEventSource",
            svg(32, 32,
                "<circle cx='16' cy='16' r='13' fill='#fff8e1' stroke='#f57f17' stroke-width='2'/>",
                "<circle cx='16' cy='16' r='1.5' fill='#f57f17'/>",
                // Clock hands
                "<line x1='16' y1='16' x2='16' y2='7' stroke='#f57f17' stroke-width='2' stroke-linecap='round'/>",
                "<line x1='16' y1='16' x2='21' y2='20' stroke='#f57f17' stroke-width='1.5' stroke-linecap='round'/>",
                // Tick marks
                "<line x1='16' y1='4' x2='16' y2='6' stroke='#f57f17' stroke-width='1.5'/>",
                "<line x1='16' y1='26' x2='16' y2='28' stroke='#f57f17' stroke-width='1.5'/>",
                "<line x1='4' y1='16' x2='6' y2='16' stroke='#f57f17' stroke-width='1.5'/>",
                "<line x1='26' y1='16' x2='28' y2='16' stroke='#f57f17' stroke-width='1.5'/>"
            ));

        register("timer.SleepActivity",
            svg(32, 32,
                "<circle cx='16' cy='16' r='13' fill='#e8eaf6' stroke='#7986cb' stroke-width='2'/>",
                "<circle cx='16' cy='16' r='1.5' fill='#5c6bc0'/>",
                "<line x1='16' y1='16' x2='16' y2='8' stroke='#5c6bc0' stroke-width='2' stroke-linecap='round'/>",
                "<text x='19' y='13' font-size='9' fill='#5c6bc0' font-family='Arial'>z</text>",
                "<text x='22' y='10' font-size='7' fill='#7986cb' font-family='Arial'>z</text>"
            ));

        // ── HTTP ─────────────────────────────────────────────────────────────
        register("http.HTTPEventSource",
            svg(32, 32,
                "<circle cx='16' cy='16' r='13' fill='#e3f2fd' stroke='#1565c0' stroke-width='2'/>",
                // Globe lines
                "<ellipse cx='16' cy='16' rx='6' ry='13' fill='none' stroke='#1565c0' stroke-width='1'/>",
                "<line x1='3' y1='16' x2='29' y2='16' stroke='#1565c0' stroke-width='1'/>",
                "<path d='M6 9.5 Q16 13 26 9.5' fill='none' stroke='#1565c0' stroke-width='1'/>",
                "<path d='M6 22.5 Q16 19 26 22.5' fill='none' stroke='#1565c0' stroke-width='1'/>",
                // Ear: small server symbol bottom-right
                "<rect x='20' y='20' width='9' height='9' rx='1' fill='#1565c0'/>",
                "<line x1='22' y1='23' x2='27' y2='23' stroke='white' stroke-width='1'/>",
                "<line x1='22' y1='25' x2='27' y2='25' stroke='white' stroke-width='1'/>"
            ));

        register("http.client.HttpRequestActivity",
            svg(32, 32,
                "<circle cx='16' cy='16' r='13' fill='#e3f2fd' stroke='#1565c0' stroke-width='2'/>",
                "<ellipse cx='16' cy='16' rx='6' ry='13' fill='none' stroke='#1565c0' stroke-width='1'/>",
                "<line x1='3' y1='16' x2='29' y2='16' stroke='#1565c0' stroke-width='1'/>",
                "<path d='M6 9.5 Q16 13 26 9.5' fill='none' stroke='#1565c0' stroke-width='1'/>",
                "<path d='M6 22.5 Q16 19 26 22.5' fill='none' stroke='#1565c0' stroke-width='1'/>",
                // Arrow indicating outgoing request
                "<polygon points='20,20 29,20 29,29 20,29' fill='#1565c0'/>",
                "<line x1='22' y1='27' x2='27' y2='22' stroke='white' stroke-width='1.5'/>",
                "<polygon points='27,22 27,25 24,22' fill='white'/>"
            ));

        register("http.HTTPResponseActivity",
            svg(32, 32,
                "<circle cx='16' cy='16' r='13' fill='#e3f2fd' stroke='#1565c0' stroke-width='2'/>",
                "<ellipse cx='16' cy='16' rx='6' ry='13' fill='none' stroke='#1565c0' stroke-width='1'/>",
                "<line x1='3' y1='16' x2='29' y2='16' stroke='#1565c0' stroke-width='1'/>",
                "<path d='M6 9.5 Q16 13 26 9.5' fill='none' stroke='#1565c0' stroke-width='1'/>",
                "<path d='M6 22.5 Q16 19 26 22.5' fill='none' stroke='#1565c0' stroke-width='1'/>",
                // Checkmark for response
                "<polygon points='20,20 29,20 29,29 20,29' fill='#27ae60'/>",
                "<polyline points='22,25 24.5,27.5 27,22' fill='none' stroke='white' stroke-width='1.5'/>"
            ));

        // ── JMS ──────────────────────────────────────────────────────────────
        String jmsBase =
            "<rect x='4' y='8' width='24' height='16' rx='3' fill='#e8f5e9' stroke='#2e7d32' stroke-width='2'/>" +
            "<line x1='4' y1='14' x2='28' y2='14' stroke='#2e7d32' stroke-width='1'/>" +
            "<line x1='4' y1='20' x2='28' y2='20' stroke='#2e7d32' stroke-width='1'/>";

        register("jms.JMSQueueSendActivity",
            svg(32, 32, jmsBase,
                "<polygon points='26,24 31,28 31,20' fill='#2e7d32'/>"));

        register("jms.JMSQueueGetActivity",
            svg(32, 32, jmsBase,
                "<polygon points='6,20 1,24 1,20 6,24' fill='#2e7d32'/>"));

        register("jms.JMSQueueEventSource",
            svg(32, 32, jmsBase,
                "<polygon points='6,20 1,24 1,20 6,24' fill='#2e7d32'/>",
                "<circle cx='28' cy='6' r='4' fill='#f57f17'/>"));

        register("jms.JMSTopicPublishActivity",
            svg(32, 32,
                "<circle cx='16' cy='16' r='12' fill='#e8f5e9' stroke='#2e7d32' stroke-width='2'/>",
                // Broadcast waves
                "<path d='M10 12 Q7 16 10 20' fill='none' stroke='#2e7d32' stroke-width='1.5'/>",
                "<path d='M7 10 Q3 16 7 22' fill='none' stroke='#2e7d32' stroke-width='1'/>",
                "<path d='M22 12 Q25 16 22 20' fill='none' stroke='#2e7d32' stroke-width='1.5'/>",
                "<path d='M25 10 Q29 16 25 22' fill='none' stroke='#2e7d32' stroke-width='1'/>",
                "<circle cx='16' cy='16' r='2.5' fill='#2e7d32'/>"));

        register("jms.JMSTopicSubscribeActivity",
            svg(32, 32,
                "<circle cx='16' cy='16' r='12' fill='#c8e6c9' stroke='#1b5e20' stroke-width='2'/>",
                "<path d='M10 12 Q7 16 10 20' fill='none' stroke='#1b5e20' stroke-width='1.5'/>",
                "<path d='M7 10 Q3 16 7 22' fill='none' stroke='#1b5e20' stroke-width='1'/>",
                "<path d='M22 12 Q25 16 22 20' fill='none' stroke='#1b5e20' stroke-width='1.5'/>",
                "<path d='M25 10 Q29 16 25 22' fill='none' stroke='#1b5e20' stroke-width='1'/>",
                "<circle cx='16' cy='16' r='2.5' fill='#1b5e20'/>",
                "<circle cx='28' cy='6' r='4' fill='#f57f17'/>"));

        // ── JDBC ─────────────────────────────────────────────────────────────
        String dbBase =
            "<ellipse cx='16' cy='10' rx='10' ry='4' fill='#fce4ec' stroke='#c62828' stroke-width='1.5'/>" +
            "<rect x='6' y='10' width='20' height='12' fill='#fce4ec' stroke='#c62828' stroke-width='1.5'/>" +
            "<ellipse cx='16' cy='22' rx='10' ry='4' fill='#fce4ec' stroke='#c62828' stroke-width='1.5'/>";

        register("jdbc.JDBCQueryActivity",
            svg(32, 32, dbBase,
                "<text x='12' y='18' font-size='8' fill='#c62828' font-family='Arial' font-weight='bold'>Q</text>"));

        register("jdbc.JDBCUpdateActivity",
            svg(32, 32, dbBase,
                "<text x='12' y='18' font-size='8' fill='#c62828' font-family='Arial' font-weight='bold'>U</text>"));

        register("jdbc.JDBCCallActivity",
            svg(32, 32, dbBase,
                "<text x='13' y='18' font-size='8' fill='#c62828' font-family='Arial' font-weight='bold'>C</text>"));

        register("jdbc.JDBCDirectUpdate",
            svg(32, 32, dbBase,
                "<text x='13' y='18' font-size='7' fill='#c62828' font-family='Arial' font-weight='bold'>DU</text>"));

        // ── Mail ─────────────────────────────────────────────────────────────
        String envelopeBase =
            "<rect x='3' y='8' width='26' height='18' rx='2' fill='#f3e5f5' stroke='#6a1b9a' stroke-width='1.5'/>" +
            "<polyline points='3,8 16,18 29,8' fill='none' stroke='#6a1b9a' stroke-width='1.5'/>";

        register("mail.MailEventSource",
            svg(32, 32, envelopeBase,
                "<circle cx='26' cy='9' r='5' fill='#f57f17'/>",
                "<line x1='26' y1='6' x2='26' y2='12' stroke='white' stroke-width='1.5'/>",
                "<line x1='23' y1='9' x2='29' y2='9' stroke='white' stroke-width='1.5'/>"));

        register("mail.MailPubActivity",
            svg(32, 32, envelopeBase,
                "<polygon points='24,22 29,25 29,19' fill='#6a1b9a'/>"));

        // ── Java ─────────────────────────────────────────────────────────────
        register("java.JavaActivity",
            svg(32, 32,
                // Coffee cup outline
                "<path d='M8 10 L8 24 Q8 27 11 27 L21 27 Q24 27 24 24 L24 10 Z' fill='#e0f2f1' stroke='#00695c' stroke-width='1.5'/>",
                "<path d='M24 14 Q29 14 29 17 Q29 21 24 21' fill='none' stroke='#00695c' stroke-width='1.5'/>",
                "<line x1='8' y1='14' x2='24' y2='14' stroke='#00695c' stroke-width='1'/>",
                // Steam
                "<path d='M12 7 Q13 5 12 3' fill='none' stroke='#00695c' stroke-width='1'/>",
                "<path d='M16 7 Q17 5 16 3' fill='none' stroke='#00695c' stroke-width='1'/>",
                "<path d='M20 7 Q21 5 20 3' fill='none' stroke='#00695c' stroke-width='1'/>"
            ));

        register("java.JavaMethodActivity",
            svg(32, 32,
                "<rect x='4' y='4' width='24' height='24' rx='3' fill='#e0f2f1' stroke='#00695c' stroke-width='1.5'/>",
                "<text x='5' y='14' font-size='8' fill='#00695c' font-family='monospace'>f()</text>",
                "<line x1='6' y1='17' x2='26' y2='17' stroke='#00695c' stroke-width='1'/>",
                "<text x='5' y='23' font-size='7' fill='#00695c' font-family='monospace'>Java</text>"
            ));

        // ── File ─────────────────────────────────────────────────────────────
        String fileBase =
            "<path d='M6 4 L20 4 L26 10 L26 28 L6 28 Z' fill='#fff9c4' stroke='#f9a825' stroke-width='1.5'/>" +
            "<path d='M20 4 L20 10 L26 10' fill='none' stroke='#f9a825' stroke-width='1.5'/>" +
            "<line x1='10' y1='15' x2='22' y2='15' stroke='#f9a825' stroke-width='1'/>" +
            "<line x1='10' y1='19' x2='22' y2='19' stroke='#f9a825' stroke-width='1'/>";

        register("file.FileEventSource",
            svg(32, 32, fileBase, "<circle cx='24' cy='8' r='5' fill='#f57f17'/>"));

        register("file.FileReadActivity",
            svg(32, 32, fileBase,
                "<polygon points='13,22 19,22 16,27' fill='#f9a825'/>"));

        register("file.FileWriteActivity",
            svg(32, 32, fileBase,
                "<polygon points='13,27 19,27 16,22' fill='#f9a825'/>"));

        register("file.FileRenameActivity",
            svg(32, 32, fileBase,
                "<text x='9' y='26' font-size='7' fill='#f9a825' font-family='Arial'>A→B</text>"));

        register("file.FileRemoveActivity",
            svg(32, 32, fileBase,
                "<line x1='11' y1='22' x2='21' y2='28' stroke='#e53935' stroke-width='2'/>",
                "<line x1='21' y1='22' x2='11' y2='28' stroke='#e53935' stroke-width='2'/>"));

        // ── FTP ──────────────────────────────────────────────────────────────
        register("ftp.FTPGetActivity",
            svg(32, 32,
                "<rect x='4' y='4' width='24' height='24' rx='3' fill='#e1f5fe' stroke='#0277bd' stroke-width='1.5'/>",
                "<text x='6' y='14' font-size='8' fill='#0277bd' font-family='Arial' font-weight='bold'>FTP</text>",
                "<line x1='12' y1='17' x2='20' y2='17' stroke='#0277bd' stroke-width='1.5'/>",
                "<polygon points='12,14 12,20 7,17' fill='#0277bd'/>"));

        register("ftp.FTPPutActivity",
            svg(32, 32,
                "<rect x='4' y='4' width='24' height='24' rx='3' fill='#e1f5fe' stroke='#0277bd' stroke-width='1.5'/>",
                "<text x='6' y='14' font-size='8' fill='#0277bd' font-family='Arial' font-weight='bold'>FTP</text>",
                "<line x1='12' y1='17' x2='20' y2='17' stroke='#0277bd' stroke-width='1.5'/>",
                "<polygon points='20,14 20,20 25,17' fill='#0277bd'/>"));

        register("ftp.FTPListActivity",
            svg(32, 32,
                "<rect x='4' y='4' width='24' height='24' rx='3' fill='#e1f5fe' stroke='#0277bd' stroke-width='1.5'/>",
                "<text x='6' y='14' font-size='8' fill='#0277bd' font-family='Arial' font-weight='bold'>FTP</text>",
                "<line x1='8' y1='18' x2='24' y2='18' stroke='#0277bd' stroke-width='1'/>",
                "<line x1='8' y1='21' x2='24' y2='21' stroke='#0277bd' stroke-width='1'/>",
                "<line x1='8' y1='24' x2='18' y2='24' stroke='#0277bd' stroke-width='1'/>"));

        // ── SOAP / Web Services ───────────────────────────────────────────────
        register("soap.SOAPSendReceiveActivity",
            svg(32, 32,
                "<rect x='3' y='6' width='26' height='20' rx='3' fill='#fff3e0' stroke='#e65100' stroke-width='1.5'/>",
                "<text x='5' y='14' font-size='7' fill='#e65100' font-family='Arial' font-weight='bold'>SOAP</text>",
                "<line x1='5' y1='16' x2='27' y2='16' stroke='#e65100' stroke-width='1'/>",
                "<line x1='5' y1='18' x2='20' y2='18' stroke='#e65100' stroke-width='1'/>",
                "<line x1='5' y1='20' x2='22' y2='20' stroke='#e65100' stroke-width='1'/>",
                "<line x1='5' y1='22' x2='17' y2='22' stroke='#e65100' stroke-width='1'/>"));

        register("soap.SOAPRequestReplyActivity",
            svg(32, 32,
                "<rect x='3' y='6' width='26' height='20' rx='3' fill='#fff3e0' stroke='#e65100' stroke-width='1.5'/>",
                "<text x='5' y='14' font-size='7' fill='#e65100' font-family='Arial' font-weight='bold'>SOAP</text>",
                "<line x1='5' y1='16' x2='27' y2='16' stroke='#e65100' stroke-width='1'/>",
                "<polygon points='5,19 12,19 12,17 5,17' fill='#e65100'/>",
                "<polygon points='27,23 20,23 20,21 27,21' fill='#27ae60'/>"));

        register("pe.core.ServiceInvokeActivity",
            svg(32, 32,
                "<circle cx='16' cy='16' r='12' fill='#fff3e0' stroke='#e65100' stroke-width='1.5'/>",
                // Gear
                "<circle cx='16' cy='16' r='5' fill='none' stroke='#e65100' stroke-width='2'/>",
                "<circle cx='16' cy='16' r='2' fill='#e65100'/>",
                "<rect x='14.5' y='3' width='3' height='5' rx='1' fill='#e65100'/>",
                "<rect x='14.5' y='24' width='3' height='5' rx='1' fill='#e65100'/>",
                "<rect x='3' y='14.5' width='5' height='3' rx='1' fill='#e65100'/>",
                "<rect x='24' y='14.5' width='5' height='3' rx='1' fill='#e65100'/>"));

        // ── RV / Rendezvous ──────────────────────────────────────────────────
        register("rv.RVPublishActivity",
            svg(32, 32,
                "<circle cx='16' cy='16' r='12' fill='#e8f5e9' stroke='#2e7d32' stroke-width='1.5'/>",
                "<text x='8' y='14' font-size='7' fill='#2e7d32' font-family='Arial' font-weight='bold'>TibRV</text>",
                "<polygon points='16,18 26,22 26,18' fill='#2e7d32'/>",
                "<line x1='8' y1='20' x2='26' y2='20' stroke='#2e7d32' stroke-width='1.5'/>"));

        register("rv.RVSubscribeActivity",
            svg(32, 32,
                "<circle cx='16' cy='16' r='12' fill='#c8e6c9' stroke='#1b5e20' stroke-width='1.5'/>",
                "<text x='8' y='14' font-size='7' fill='#1b5e20' font-family='Arial' font-weight='bold'>TibRV</text>",
                "<polygon points='6,18 16,22 16,18' fill='#1b5e20'/>",
                "<line x1='6' y1='20' x2='24' y2='20' stroke='#1b5e20' stroke-width='1.5'/>",
                "<circle cx='26' cy='8' r='4' fill='#f57f17'/>"));

        register("rv.RVRequestActivity",
            svg(32, 32,
                "<circle cx='16' cy='16' r='12' fill='#e8f5e9' stroke='#2e7d32' stroke-width='1.5'/>",
                "<text x='8' y='14' font-size='7' fill='#2e7d32' font-family='Arial' font-weight='bold'>TibRV</text>",
                "<line x1='8' y1='19' x2='24' y2='19' stroke='#2e7d32' stroke-width='1.5'/>",
                "<polygon points='20,16 26,19 20,22' fill='#2e7d32'/>",
                "<polygon points='12,22 6,19 12,16' fill='#2e7d32'/>"));

        // ── XML ──────────────────────────────────────────────────────────────
        register("xml.XMLParseActivity",
            svg(32, 32,
                "<rect x='4' y='4' width='24' height='24' rx='2' fill='#e8eaf6' stroke='#3949ab' stroke-width='1.5'/>",
                "<text x='5' y='13' font-size='8' fill='#3949ab' font-family='monospace'>&lt;XML&gt;</text>",
                "<polygon points='8,22 14,17 8,12' fill='#3949ab'/>",
                "<line x1='14' y1='17' x2='24' y2='17' stroke='#3949ab' stroke-width='1.5'/>"));

        register("xml.XMLRenderActivity",
            svg(32, 32,
                "<rect x='4' y='4' width='24' height='24' rx='2' fill='#e8eaf6' stroke='#3949ab' stroke-width='1.5'/>",
                "<text x='5' y='13' font-size='8' fill='#3949ab' font-family='monospace'>&lt;XML&gt;</text>",
                "<polygon points='24,22 18,17 24,12' fill='#3949ab'/>",
                "<line x1='8' y1='17' x2='18' y2='17' stroke='#3949ab' stroke-width='1.5'/>"));

        register("xml.XMLValidateActivity",
            svg(32, 32,
                "<rect x='4' y='4' width='24' height='24' rx='2' fill='#e8eaf6' stroke='#3949ab' stroke-width='1.5'/>",
                "<text x='5' y='13' font-size='8' fill='#3949ab' font-family='monospace'>&lt;XML&gt;</text>",
                "<polyline points='9,22 13,26 23,16' fill='none' stroke='#27ae60' stroke-width='2'/>"));

        // ── Core activities ──────────────────────────────────────────────────
        register("pe.core.WriteToLogActivity",
            svg(32, 32,
                "<rect x='5' y='4' width='22' height='24' rx='2' fill='#f5f5f5' stroke='#757575' stroke-width='1.5'/>",
                "<line x1='8' y1='10' x2='24' y2='10' stroke='#757575' stroke-width='1'/>",
                "<line x1='8' y1='14' x2='24' y2='14' stroke='#757575' stroke-width='1'/>",
                "<line x1='8' y1='18' x2='24' y2='18' stroke='#757575' stroke-width='1'/>",
                "<line x1='8' y1='22' x2='18' y2='22' stroke='#757575' stroke-width='1'/>",
                // Pen
                "<line x1='20' y1='22' x2='26' y2='28' stroke='#757575' stroke-width='2'/>",
                "<polygon points='20,22 22,20 24,22 22,24' fill='#757575'/>"));

        register("pe.core.AssignActivity",
            svg(32, 32,
                "<rect x='4' y='4' width='24' height='24' rx='3' fill='#f5f5f5' stroke='#546e7a' stroke-width='1.5'/>",
                "<text x='7' y='20' font-size='14' fill='#546e7a' font-family='Arial' font-weight='bold'>:=</text>"));

        register("pe.core.CallProcessActivity",
            svg(32, 32,
                "<rect x='3' y='6' width='20' height='20' rx='2' fill='#fff3e0' stroke='#e65100' stroke-width='1.5'/>",
                "<rect x='9' y='9' width='20' height='20' rx='2' fill='#fff8e1' stroke='#f57f17' stroke-width='1.5'/>",
                "<polygon points='16,16 22,20 22,12' fill='#e65100'/>"));

        register("pe.core.Mapper",
            svg(32, 32,
                "<rect x='4' y='4' width='24' height='24' rx='3' fill='#e8eaf6' stroke='#283593' stroke-width='1.5'/>",
                "<line x1='8' y1='10' x2='14' y2='10' stroke='#283593' stroke-width='1.5'/>",
                "<line x1='8' y1='16' x2='14' y2='16' stroke='#283593' stroke-width='1.5'/>",
                "<line x1='8' y1='22' x2='14' y2='22' stroke='#283593' stroke-width='1.5'/>",
                "<line x1='18' y1='10' x2='24' y2='10' stroke='#283593' stroke-width='1.5'/>",
                "<line x1='18' y1='16' x2='24' y2='16' stroke='#283593' stroke-width='1.5'/>",
                "<line x1='18' y1='22' x2='24' y2='22' stroke='#283593' stroke-width='1.5'/>",
                // Arrows in center
                "<polygon points='14,14 18,16 14,18' fill='#283593'/>",
                "<polygon points='18,12 14,10 18,8' fill='#283593'/>"));

        register("pe.core.ThrowActivity",
            svg(32, 32,
                "<circle cx='16' cy='16' r='12' fill='#ffebee' stroke='#c62828' stroke-width='2'/>",
                // Lightning bolt
                "<polygon points='18,5 12,17 16,17 14,27 20,15 16,15' fill='#c62828'/>"));

        register("pe.core.CatchActivity",
            svg(32, 32,
                "<circle cx='16' cy='16' r='12' fill='#ffebee' stroke='#c62828' stroke-width='2' stroke-dasharray='3,2'/>",
                "<polygon points='18,5 12,17 16,17 14,27 20,15 16,15' fill='#c62828' opacity='0.6'/>",
                "<line x1='8' y1='8' x2='24' y2='24' stroke='#c62828' stroke-width='2.5'/>"));

        register("pe.core.NullActivity",
            svg(32, 32,
                "<circle cx='16' cy='16' r='12' fill='#f5f5f5' stroke='#bdbdbd' stroke-width='2'/>",
                "<line x1='10' y1='10' x2='22' y2='22' stroke='#bdbdbd' stroke-width='2'/>",
                "<line x1='22' y1='10' x2='10' y2='22' stroke='#bdbdbd' stroke-width='2'/>"));

        register("pe.core.GenerateErrorActivity",
            svg(32, 32,
                "<circle cx='16' cy='16' r='12' fill='#ffebee' stroke='#b71c1c' stroke-width='2'/>",
                "<text x='12' y='21' font-size='16' fill='#b71c1c' font-family='Arial' font-weight='bold'>!</text>"));

        // ── Custom Function ───────────────────────────────────────────────────
        register("customfunction",
            svg(32, 32,
                "<rect x='4' y='4' width='24' height='24' rx='3' fill='#e0f7fa' stroke='#006064' stroke-width='1.5'/>",
                "<text x='5' y='16' font-size='10' fill='#006064' font-family='monospace' font-weight='bold'>f(x)</text>",
                "<line x1='5' y1='19' x2='27' y2='19' stroke='#006064' stroke-width='1'/>",
                "<text x='5' y='27' font-size='7' fill='#006064' font-family='Arial'>Custom</text>"));

        // ── Adapter ───────────────────────────────────────────────────────────
        register("adapter.AdapterEventSource",
            svg(32, 32,
                "<rect x='4' y='4' width='24' height='24' rx='3' fill='#fbe9e7' stroke='#bf360c' stroke-width='1.5'/>",
                "<text x='6' y='14' font-size='8' fill='#bf360c' font-family='Arial' font-weight='bold'>ADP</text>",
                "<circle cx='16' cy='22' r='5' fill='#bf360c'/>",
                "<circle cx='26' cy='8' r='4' fill='#f57f17'/>"));

        register("adapter.AdapterRequestResponseActivity",
            svg(32, 32,
                "<rect x='4' y='4' width='24' height='24' rx='3' fill='#fbe9e7' stroke='#bf360c' stroke-width='1.5'/>",
                "<text x='6' y='14' font-size='8' fill='#bf360c' font-family='Arial' font-weight='bold'>ADP</text>",
                "<line x1='6' y1='20' x2='26' y2='20' stroke='#bf360c' stroke-width='1.5'/>",
                "<polygon points='20,17 26,20 20,23' fill='#bf360c'/>",
                "<polygon points='12,17 6,20 12,23' fill='#bf360c'/>"));

        // ── Fallback ──────────────────────────────────────────────────────────
        FALLBACK_ICON = encode(svg(32, 32,
            "<rect x='4' y='4' width='24' height='24' rx='4' fill='#eceff1' stroke='#90a4ae' stroke-width='1.5'/>",
            "<line x1='10' y1='12' x2='22' y2='12' stroke='#90a4ae' stroke-width='1.5'/>",
            "<line x1='10' y1='16' x2='22' y2='16' stroke='#90a4ae' stroke-width='1.5'/>",
            "<line x1='10' y1='20' x2='18' y2='20' stroke='#90a4ae' stroke-width='1.5'/>"));
    }

    // Load real TIBCO GIF icons from bundled properties file (resourceType → base64)
    static {
        try (InputStream is = ActivityIconRegistry.class
                .getResourceAsStream("/activity-icons.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                for (String key : props.stringPropertyNames()) {
                    ICON_BY_RESOURCE_TYPE.put(key,
                        "data:image/gif;base64," + props.getProperty(key));
                }
            }
        } catch (Exception ignored) {
            // fall back to SVG icons on any error
        }
    }

    // -----------------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------------

    /**
     * Returns a base64-encoded data URI for the icon associated with the given
     * palette {@code resourceType} (e.g. {@code "httppalette.httpEventSource"}).
     *
     * <p>Looks up the real TIBCO GIF icon from the bundled
     * {@code activity-icons.properties} file. Falls back to the generic SVG
     * fallback icon if the resourceType is not found.</p>
     *
     * @param resourceType palette resource type from the process XML
     * @return data URI, never null
     */
    public static String getIconByResourceType(String resourceType) {
        if (resourceType != null) {
            String icon = ICON_BY_RESOURCE_TYPE.get(resourceType);
            if (icon != null) return icon;
        }
        return FALLBACK_ICON;
    }

    /**
     * Returns a base64-encoded data URI for the icon associated with the given
     * BW5 activity type string.
     *
     * <p>The match is performed by checking whether the type string contains
     * a known suffix (e.g. {@code "timer.TimerEventSource"}). If no match is
     * found, a generic fallback icon is returned.</p>
     *
     * @param activityType full activity type string, e.g.
     *                     {@code "com.tibco.plugin.timer.TimerEventSource"}
     * @return data URI: {@code "data:image/svg+xml;base64,..."}, never null
     */
    public static String getIcon(String activityType) {
        if (activityType == null) return FALLBACK_ICON;
        for (Map.Entry<String, String> entry : ICON_BY_TYPE.entrySet()) {
            if (activityType.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return FALLBACK_ICON;
    }

    /**
     * Returns an SVG {@code <image>} element string centred on the given
     * coordinates, resolving the icon via {@code resourceType} first (real TIBCO
     * GIF), then falling back to the SVG icon matched by {@code activityType}.
     *
     * @param activityType full activity type string (fallback lookup)
     * @param resourceType palette resource type (primary lookup)
     * @param cx           centre x coordinate
     * @param cy           centre y coordinate
     * @param size         icon size in pixels (typically 32)
     * @return SVG {@code <image>} element
     */
    public static String getImageElement(String activityType, String resourceType,
                                         int cx, int cy, int size) {
        String icon = ICON_BY_RESOURCE_TYPE.getOrDefault(resourceType, null);
        if (icon == null) icon = getIcon(activityType);
        int half = size / 2;
        // Emit both href (SVG 2 / browsers) and xlink:href (SVG 1.1 / Batik/PDF)
        return String.format(
            "<image x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" href=\"%s\" xlink:href=\"%s\"/>",
            cx - half, cy - half, size, size, icon, icon);
    }

    /**
     * Returns an SVG {@code <image>} element string for the given activity type,
     * centred on the given coordinates.
     *
     * @param activityType full activity type string
     * @param cx           centre x coordinate
     * @param cy           centre y coordinate
     * @param size         icon size in pixels (typically 24 or 32)
     * @return SVG {@code <image>} element, e.g.
     *         {@code <image x="0" y="0" width="32" height="32" href="data:..."/>}
     * @deprecated use {@link #getImageElement(String, String, int, int, int)} with resourceType
     */
    @Deprecated
    public static String getImageElement(String activityType, int cx, int cy, int size) {
        int half = size / 2;
        String icon = getIcon(activityType);
        // Emit both href (SVG 2 / browsers) and xlink:href (SVG 1.1 / Batik/PDF)
        return String.format(
            "<image x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" href=\"%s\" xlink:href=\"%s\"/>",
            cx - half, cy - half, size, size, icon, icon);
    }

    // -----------------------------------------------------------------------
    //  Builder helpers
    // -----------------------------------------------------------------------

    private static void register(String typeKey, String svgSource) {
        ICON_BY_TYPE.put(typeKey, encode(svgSource));
    }

    private static String encode(String svgSource) {
        byte[] bytes = svgSource.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static String svg(int w, int h, String... elements) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "<svg xmlns='http://www.w3.org/2000/svg' width='%d' height='%d' viewBox='0 0 %d %d'>",
            w, h, w, h));
        for (String el : elements) {
            sb.append(el);
        }
        sb.append("</svg>");
        return sb.toString();
    }
}

package com.tibco.bw.maven.plugin.doc;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps fully-qualified BW5 activity class names to the TIBCO plugin (add-on palette)
 * that provides them.
 *
 * <p>Only third-party / add-on plugins are listed here. Built-in BW5 palette activities
 * (HTTP, JMS, JDBC, File, SOAP, XML, Java, …) are part of the base BusinessWorks
 * installation and are intentionally excluded.</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   String plugin = PluginRegistry.getPlugin(activity.type);  // null if built-in
 * </pre>
 */
public final class PluginRegistry {

    private PluginRegistry() {}

    // ── Activity type → plugin code ──────────────────────────────────────────

    private static final Map<String, String> ACTIVITY_MAP;

    static {
        Map<String, String> m = new HashMap<>();

        // Smart Mapper / Cross-Reference
        m.put("com.tibco.solution.xref.plugin.activity.BulkExtractActivity",         "adsmartmapper");
        m.put("com.tibco.solution.xref.plugin.activity.BulkLoadActivity",            "adsmartmapper");
        m.put("com.tibco.solution.xref.plugin.activity.CreateXRefObjectsActivity",   "adsmartmapper");
        m.put("com.tibco.solution.xref.plugin.activity.UpdateXRefObjectActivity",    "adsmartmapper");
        m.put("com.tibco.solution.xref.plugin.activity.DeleteXRefObjectsActivity",   "adsmartmapper");
        m.put("com.tibco.solution.xref.plugin.activity.CreateMappingActivity",       "adsmartmapper");
        m.put("com.tibco.solution.xref.plugin.activity.AddEntriesToMappingActivity", "adsmartmapper");
        m.put("com.tibco.solution.xref.plugin.activity.DeleteMappingActivity",       "adsmartmapper");
        m.put("com.tibco.solution.xref.plugin.activity.LookupActivity",              "adsmartmapper");
        m.put("com.tibco.solution.xref.plugin.activity.DynamicLookupActivity",       "adsmartmapper");
        m.put("com.tibco.solution.xref.plugin.activity.DynamicDeleteActivity",       "adsmartmapper");
        m.put("com.tibco.solution.xref.plugin.activity.GenerateIdActivity",          "adsmartmapper");
        m.put("com.tibco.solution.xref.plugin.activity.WizardActivity",              "adsmartmapper");

        // SFTP
        m.put("com.tibco.plugin.sp.SFTPChangeDefaultDirActivity", "sp");
        m.put("com.tibco.plugin.sp.SFTPCloseConnectionActivity",  "sp");
        m.put("com.tibco.plugin.sp.SFTPDeleteFileActivity",       "sp");
        m.put("com.tibco.plugin.sp.SFTPDirActivity",              "sp");
        m.put("com.tibco.plugin.sp.SFTPGetActivity",              "sp");
        m.put("com.tibco.plugin.sp.SFTPGetDefaultDirActivity",    "sp");
        m.put("com.tibco.plugin.sp.SFTPMakeRemoteDirActivity",    "sp");
        m.put("com.tibco.plugin.sp.SFTPPutActivity",              "sp");
        m.put("com.tibco.plugin.sp.SFTPRemoveRemoteDirActivity",  "sp");
        m.put("com.tibco.plugin.sp.SFTPRenameActivity",           "sp");
        m.put("com.tibco.plugin.sp.SFTPExecRemoteCmdActivity",    "sp");

        // ActiveSpaces
        m.put("com.tibco.plugin.firefly.activities.PutActivity",           "as");
        m.put("com.tibco.plugin.firefly.activities.UpdateActivity",        "as");
        m.put("com.tibco.plugin.firefly.activities.GetActivity",           "as");
        m.put("com.tibco.plugin.firefly.activities.DeleteActivity",        "as");
        m.put("com.tibco.plugin.firefly.activities.QueryActivity",         "as");
        m.put("com.tibco.plugin.firefly.activities.QueryBySQLActivity",    "as");
        m.put("com.tibco.plugin.firefly.activities.TableListenerActivity", "as");

        // Salesforce
        m.put("com.tibco.plugin.salesforce.getsession.SalesforceGetSessionActivity",   "salesforce");
        m.put("com.tibco.plugin.salesforce.createall.SalesforceCreateAllActivity",     "salesforce");
        m.put("com.tibco.plugin.salesforce.deleteall.SalesforceDeleteAllActivity",     "salesforce");
        m.put("com.tibco.plugin.salesforce.queryall.SalesforceQueryAllActivity",       "salesforce");
        m.put("com.tibco.plugin.salesforce.retrieveall.SalesforceRetrieveAllActivity", "salesforce");
        m.put("com.tibco.plugin.salesforce.updateall.SalesforceUpdateAllActivity",     "salesforce");
        m.put("com.tibco.plugin.salesforce.upsertall.SalesforceUpsertAllActivity",     "salesforce");
        m.put("com.tibco.plugin.salesforce.eventsource.SalesforceEventSourceActivity", "salesforce");

        // BW Large XML (LX)
        m.put("com.tibco.plugin.bwlx.BWLXFileToStreamActivity", "lx");
        m.put("com.tibco.plugin.bwlx.BWLXGetFragmentActivity",  "lx");
        m.put("com.tibco.plugin.bwlx.BWLXStreamToFileActivity", "lx");
        m.put("com.tibco.plugin.bwlx.BWLXTransformActivity",    "lx");
        m.put("com.tibco.plugin.bwlx.BWLXSplitterActivity",     "lx");
        m.put("com.tibco.plugin.bwlx.BWLXValidationActivity",   "lx");

        // SharePoint
        m.put("com.tibco.plugin.sharepoint.activities.SharePointNotificationListener",   "sharepoint");
        m.put("com.tibco.plugin.sharepoint.activities.SharePointAddListItemActivity",    "sharepoint");
        m.put("com.tibco.plugin.sharepoint.activities.SharePointUpdateListItemActivity", "sharepoint");
        m.put("com.tibco.plugin.sharepoint.activities.SharePointDeleteListItemActivity", "sharepoint");
        m.put("com.tibco.plugin.sharepoint.activities.SharePointSelectListItemActivity", "sharepoint");
        m.put("com.tibco.plugin.sharepoint.activities.SharePointQueryActivity",          "sharepoint");

        // MongoDB
        m.put("com.tibco.plugin.mongodb.outbound.InsertDocumentActivity",         "mongodb");
        m.put("com.tibco.plugin.mongodb.inbound.QueryDocumentActivity",           "mongodb");
        m.put("com.tibco.plugin.mongodb.outbound.UpdateDocumentActivity",         "mongodb");
        m.put("com.tibco.plugin.mongodb.connection.MongoDBGetConnectionActivity", "mongodb");
        m.put("com.tibco.plugin.mongodb.outbound.DBCommandActivity",              "mongodb");
        m.put("com.tibco.plugin.mongodb.outbound.MapReduceActivity",              "mongodb");
        m.put("com.tibco.plugin.mongodb.outbound.RemoveDocumentActivity",         "mongodb");

        // Twitter / X
        m.put("com.tibco.plugin.twitter.activities.TwitterSearchActivity",                     "twitter");
        m.put("com.tibco.plugin.twitter.activities.TwitterPublishActivity",                    "twitter");
        m.put("com.tibco.plugin.twitter.activities.TwitterQueryActivity",                      "twitter");
        m.put("com.tibco.plugin.twitter.activities.eventsource.TwitterStreamListenerActivity", "twitter");

        // SWIFT (adswift)
        m.put("com.tibco.swift2.bwplugin.swiftcheck.SwiftBICPlusIBANGeneratorActivity", "adswift");
        m.put("com.tibco.swift2.bwplugin.swiftcheck.SwiftParserActivity",               "adswift");
        m.put("com.tibco.swift2.bwplugin.swiftcheck.SwiftRendererActivity",             "adswift");
        m.put("com.tibco.swift2.bwplugin.swiftcheck.SwiftRouterActivity",               "adswift");
        m.put("com.tibco.swift2.bwplugin.swiftcheck.SwiftBICPlusIBANValidatorActivity", "adswift");
        m.put("com.tibco.swift2.bwplugin.swiftmxcheck.SwiftMXParserActivity",           "adswift");
        m.put("com.tibco.swift2.bwplugin.swiftmxcheck.SwiftMXRenderActivity",           "adswift");
        m.put("com.tibco.swift2.bwplugin.swiftmxcheck.SwiftMXRouterActivity",           "adswift");

        // REST / JSON
        m.put("com.tibco.plugin.json.activities.JSONParserActivity",                  "restjson");
        m.put("com.tibco.plugin.json.activities.JSONRenderActivity",                  "restjson");
        m.put("com.tibco.plugin.json.activities.RestSharedResource.RestSharedConfig", "restjson");
        m.put("com.tibco.plugin.json.activities.RestActivity",                        "restjson");

        // EJB
        m.put("com.tibco.plugin.ejb.EJBHomeActivity",     "ejb");
        m.put("com.tibco.plugin.ejb.EJBRemoteActivity",   "ejb");
        m.put("com.tibco.plugin.ejb.EJBSharedConnection", "ejb");

        // PDF
        m.put("com.tibco.plugin.pdf.writer.model.PdfWriterModel", "pdf");
        m.put("com.tibco.plugin.pdf.reader.model.PdfReaderModel", "pdf");

        // Facebook
        m.put("com.tibco.plugin.facebook.activities.FBPublishActivity",                      "facebook");
        m.put("com.tibco.plugin.facebook.activities.FBGraphActivity",                        "facebook");
        m.put("com.tibco.plugin.facebook.activities.eventsource.FBRealTimeListenerActivity", "facebook");
        m.put("com.tibco.plugin.facebook.activities.FBDeleteActivity",                       "facebook");

        // Workday
        m.put("com.tibco.plugin.workday.activities.wrapper.WorkdayInvokeActivityModel", "workday");

        // Oracle EBS
        m.put("com.tibco.plugin.oracleebs.plsqlapi.model.OracleEBSPLSQLAPIActivity",                               "oracleebs");
        m.put("com.tibco.plugin.oracleebs.customplsqlapi.model.OracleEBSCustomPLSQLAPIActivity",                   "oracleebs");
        m.put("com.tibco.plugin.oracleebs.concurrentprogram.model.OracleEBSConcurrentProgramActivity",             "oracleebs");
        m.put("com.tibco.plugin.oracleebs.customconcurrentprogram.model.OracleEBSCustomConcurrentProgramActivity", "oracleebs");
        m.put("com.tibco.plugin.oracleebs.businessevents.model.OracleEBSEventSource",                              "oracleebs");

        // Mobile Interaction (MI)
        m.put("com.tibco.plugin.bwmi.AckEventSource",               "mi");
        m.put("com.tibco.plugin.bwmi.FeedbackEventSource",          "mi");
        m.put("com.tibco.plugin.bwmi.SendPushNotificationActivity", "mi");
        m.put("com.tibco.plugin.bwmi.SubscriptionEventSource",      "mi");
        m.put("com.tibco.plugin.bwmi.TimeoutEventSource",           "mi");
        m.put("com.tibco.plugin.bwmi.WaitForAckActivity",           "mi");
        m.put("com.tibco.plugin.bwmi.PushNotificationActivity",     "mi");

        // NetSuite
        m.put("com.tibco.plugin.netsuite.activities.eventsource.NetsuiteRecordListenerActivity", "netsuite");
        m.put("com.tibco.plugin.netsuite.activities.crud.NetSuiteAddRecordActivity",             "netsuite");
        m.put("com.tibco.plugin.netsuite.activities.crud.NetSuiteUpdateRecordActivity",          "netsuite");
        m.put("com.tibco.plugin.netsuite.activities.crud.NetSuiteUpsertRecordActivity",          "netsuite");
        m.put("com.tibco.plugin.netsuite.activities.crud.NetSuiteDeleteRecordActivity",          "netsuite");
        m.put("com.tibco.plugin.netsuite.activities.crud.NetSuiteGetRecordActivity",             "netsuite");
        m.put("com.tibco.plugin.netsuite.activities.crud.NetSuiteGetAllRecordActivity",          "netsuite");
        m.put("com.tibco.plugin.netsuite.activities.search.NetSuiteSearchRecordActivity",        "netsuite");
        m.put("com.tibco.plugin.netsuite.activities.search.NetSuiteSavedSearchRecordActivity",   "netsuite");

        // Apache Pulsar
        m.put("com.tibco.plugin.pulsar.sharedresource.PulsarSharedResourceModel", "ap");
        m.put("com.tibco.plugin.pulsar.activity.PulsarConsumerActivity",          "ap");
        m.put("com.tibco.plugin.pulsar.activity.PulsarProducerActivity",          "ap");

        // IBM MQ
        m.put("com.tibco.plugin.bwmq.MqInquireActivity",       "mq");
        m.put("com.tibco.plugin.bwmq.MqPutActivity",           "mq");
        m.put("com.tibco.plugin.bwmq.MqGetActivity",           "mq");
        m.put("com.tibco.plugin.bwmq.MqBrowseActivity",        "mq");
        m.put("com.tibco.plugin.bwmq.MqSyncPointActivity",     "mq");
        m.put("com.tibco.plugin.bwmq.QueueListenerActivity",   "mq");
        m.put("com.tibco.plugin.bwmq.MqAppProp",               "mq");
        m.put("com.tibco.plugin.bwmq.MqMsgBodyProp",           "mq");
        m.put("com.tibco.plugin.bwmq.MqPublishActivity",       "mq");
        m.put("com.tibco.plugin.bwmq.TopicSubscriberActivity", "mq");
        m.put("com.tibco.plugin.bwmq.MqReqReplyActivity",      "mq");

        // COBOL Copybook
        m.put("com.tibco.plugin.cobol.CCBParserActivity", "copybook");
        m.put("com.tibco.plugin.cobol.CCBRenderActivity", "copybook");

        // Apache Kafka
        m.put("com.tibco.plugin.kafka.activity.send.KafkaSend",       "kafka");
        m.put("com.tibco.plugin.kafka.activity.receive.KafkaReceive", "kafka");

        // CICS PI
        m.put("com.tibco.plugin.cicspi.CicsPiActivity",      "cp");
        m.put("com.tibco.plugin.cicspi.CicsIntxnActivity",   "cp");
        m.put("com.tibco.plugin.cicspi.CicsChannelActivity", "cp");
        m.put("com.tibco.plugin.cicspi.PoolmgrActivity",     "cp");
        m.put("com.tibco.plugin.cicspi.scr.ScreenActivity",  "cp");

        // HL7
        m.put("com.tibco.plugin.hl7.bwactivities.HL7TranslateActivity",    "hl7");
        m.put("com.tibco.plugin.hl7.bwactivities.HL7ValidationActivity",   "hl7");
        m.put("com.tibco.plugin.hl7.bwactivities.HL7ParseHeaderActivity",  "hl7");
        m.put("com.tibco.plugin.hl7.bwactivities.tcp.TCPEventSource",      "hl7");
        m.put("com.tibco.plugin.hl7.bwactivities.tcp.TCPSendActivity",     "hl7");
        m.put("com.tibco.plugin.hl7.bwactivities.tcp.TCPResponseActivity", "hl7");
        m.put("com.tibco.plugin.hl7.bwactivities.tcp.TCPSharedConfig",     "hl7");

        // iProcess
        m.put("com.tibco.plugin.iProcessForms.iProcessFormsConnectionResourceUI", "iprocess");
        m.put("com.tibco.plugin.iProcessForms.iProcessFormsGetContextActivity",   "iprocess");
        m.put("com.tibco.plugin.iProcessForms.iProcessFormsKeepReleaseActivity",  "iprocess");
        m.put("com.tibco.plugin.iProcessForms.iProcessFormsCancelUndoActivity",   "iprocess");
        m.put("com.tibco.plugin.staffware.iProcessGetCaseNumberActivity",         "iprocess");
        m.put("com.tibco.plugin.staffware.iProcessServiceAgentResource",          "iprocess");
        m.put("com.tibco.plugin.staffware.iProcessConnectionResourceUI",          "iprocess");
        m.put("com.tibco.plugin.staffware.iProcessStartCaseActivity",             "iprocess");
        m.put("com.tibco.plugin.staffware.iProcessDelayedReleaseActivity",        "iprocess");
        m.put("com.tibco.plugin.staffware.iProcessGraftActivity",                 "iprocess");
        m.put("com.tibco.plugin.staffware.iProcessAuditEntryActivity",            "iprocess");
        m.put("com.tibco.plugin.staffware.iProcessCaseManagementActivity",        "iprocess");

        // BusinessConnect (B2B)
        m.put("com.tibco.plugin.ax.bc.B2BResponderRequestEventSource", "bc");
        m.put("com.tibco.plugin.ax.bc.B2BReceiveResponseEventSource",  "bc");
        m.put("com.tibco.plugin.ax.bc.B2BSendRequestActivity",         "bc");
        m.put("com.tibco.plugin.ax.bc.B2BReceiveMiscMsgEventSource",   "bc");
        m.put("com.tibco.plugin.ax.bc.B2BSendMiscMsgActivity",         "bc");
        m.put("com.tibco.plugin.ax.bc.B2BSendResponseActivity",        "bc");
        m.put("com.tibco.plugin.ax.bc.B2BServerSharedConfig",          "bc");

        ACTIVITY_MAP = Collections.unmodifiableMap(m);
    }

    // ── Plugin code → display info ───────────────────────────────────────────
    // Each entry: {displayName, emoji, description}

    private static final Map<String, String[]> PLUGIN_INFO;

    static {
        Map<String, String[]> m = new HashMap<>();
        m.put("adsmartmapper", new String[]{"Smart Mapper / XRef",  "🔁", "TIBCO Cross-Reference and Smart Mapper"});
        m.put("sp",            new String[]{"SFTP",                 "🔒", "Secure FTP file transfer"});
        m.put("as",            new String[]{"ActiveSpaces",         "⚡", "TIBCO ActiveSpaces in-memory data grid"});
        m.put("salesforce",    new String[]{"Salesforce",           "☁", "Salesforce CRM integration"});
        m.put("lx",            new String[]{"BW Large XML",         "📄", "Large XML streaming and transformation"});
        m.put("sharepoint",    new String[]{"SharePoint",           "📁", "Microsoft SharePoint integration"});
        m.put("mongodb",       new String[]{"MongoDB",              "🍃", "MongoDB NoSQL database"});
        m.put("twitter",       new String[]{"Twitter / X",          "🐦", "Twitter/X social API"});
        m.put("adswift",       new String[]{"SWIFT",                "💳", "SWIFT financial messaging (MT and MX)"});
        m.put("restjson",      new String[]{"REST / JSON",          "🌐", "REST adapter and JSON processing"});
        m.put("ejb",           new String[]{"EJB",                  "☕", "Enterprise JavaBeans"});
        m.put("pdf",           new String[]{"PDF",                  "📑", "PDF generation and parsing"});
        m.put("facebook",      new String[]{"Facebook",             "📘", "Facebook Graph API"});
        m.put("workday",       new String[]{"Workday",              "👔", "Workday HCM/Finance integration"});
        m.put("oracleebs",     new String[]{"Oracle EBS",           "🔶", "Oracle E-Business Suite integration"});
        m.put("mi",            new String[]{"Mobile Interaction",   "📱", "TIBCO Mobile Interaction"});
        m.put("netsuite",      new String[]{"NetSuite",             "☁", "Oracle NetSuite ERP"});
        m.put("ap",            new String[]{"Apache Pulsar",        "⚡", "Apache Pulsar messaging"});
        m.put("mq",            new String[]{"IBM MQ",               "🏭", "IBM WebSphere MQ messaging"});
        m.put("copybook",      new String[]{"COBOL Copybook",       "🏦", "COBOL Copybook parsing and rendering"});
        m.put("kafka",         new String[]{"Apache Kafka",         "⚡", "Apache Kafka streaming"});
        m.put("cp",            new String[]{"IBM CICS PI",          "🏦", "IBM CICS Transaction Gateway"});
        m.put("hl7",           new String[]{"HL7",                  "🏥", "HL7 healthcare message processing"});
        m.put("iprocess",      new String[]{"iProcess",             "📊", "TIBCO iProcess Suite integration"});
        m.put("bc",            new String[]{"BusinessConnect",      "🔗", "TIBCO BusinessConnect B2B integration"});
        PLUGIN_INFO = Collections.unmodifiableMap(m);
    }

    /**
     * Returns the plugin code for the given fully-qualified activity class name,
     * or {@code null} if the activity is a built-in BW5 palette activity.
     */
    public static String getPlugin(String activityType) {
        if (activityType == null) return null;
        return ACTIVITY_MAP.get(activityType);
    }

    /**
     * Returns display metadata for the given plugin code:
     * {@code [displayName, emoji, description]}.
     * Falls back to the plugin code itself if not found.
     */
    public static String[] getPluginInfo(String pluginCode) {
        if (pluginCode == null) return new String[]{"Unknown", "🔧", ""};
        String[] info = PLUGIN_INFO.get(pluginCode);
        return info != null ? info : new String[]{pluginCode, "🔧", ""};
    }
}

package com.tibco.bw.maven.plugin.compile;

import org.apache.maven.plugin.logging.Log;
import org.jdom2.Element;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class PrepareJcfBytecodeMojoTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // -----------------------------------------------------------------------
    //  DEF-025: skip message must be at INFO level, not DEBUG
    // -----------------------------------------------------------------------

    @Test
    public void skipLogsAtInfoLevel() throws Exception {
        PrepareJcfBytecodeMojo mojo = new PrepareJcfBytecodeMojo();
        CapturingLog log = new CapturingLog();
        setField(mojo, "skip", true);
        mojo.setLog(log);

        mojo.execute();

        assertFalse("INFO messages must not be empty when skip=true", log.infoMessages.isEmpty());
        boolean found = false;
        for (String msg : log.infoMessages) {
            if (msg.contains("skipped")) {
                found = true;
                break;
            }
        }
        assertTrue("Skip message must be logged at INFO (visible without -X), not DEBUG", found);
        assertTrue("No work must be done when skip=true", log.debugMessages.stream()
            .noneMatch(m -> m.contains("skipped")));
    }

    @Test
    public void skipDoesNotLogAtDebugOnly() throws Exception {
        PrepareJcfBytecodeMojo mojo = new PrepareJcfBytecodeMojo();
        CapturingLog log = new CapturingLog();
        setField(mojo, "skip", true);
        mojo.setLog(log);

        mojo.execute();

        boolean onlyInDebug = log.infoMessages.stream().noneMatch(m -> m.contains("skipped"))
            && log.debugMessages.stream().anyMatch(m -> m.contains("skipped"));
        assertFalse("skip message must not be logged at DEBUG-only level (regression for DEF-025)", onlyInDebug);
    }

    // -----------------------------------------------------------------------
    //  extractClassNameFromRoot
    // -----------------------------------------------------------------------

    @Test
    public void extractClassNameFromUnixPath() {
        Element root = buildLocationElement(
            "file:/opt/tibco/bw/5.14/classes/com/example/MyFunction.class");
        assertEquals("com.example.MyFunction",
            PrepareJcfBytecodeMojo.extractClassNameFromRoot(root));
    }

    @Test
    public void extractClassNameFromWindowsPath() {
        Element root = buildLocationElement(
            "C:\\tibco\\bw\\classes\\com\\example\\MyFunction.class");
        assertEquals("com.example.MyFunction",
            PrepareJcfBytecodeMojo.extractClassNameFromRoot(root));
    }

    @Test
    public void extractClassNameStripsDotClass() {
        Element root = buildLocationElement("file:///build/classes/pkg/Func.class");
        assertEquals("pkg.Func", PrepareJcfBytecodeMojo.extractClassNameFromRoot(root));
    }

    @Test
    public void extractClassNameReturnsNullWhenNoClassesSegment() {
        Element root = buildLocationElement("/opt/tibco/bw/something/else/Func.class");
        assertNull(PrepareJcfBytecodeMojo.extractClassNameFromRoot(root));
    }

    @Test
    public void extractClassNameReturnsNullWhenElementMissing() {
        Element root = new Element("javaxpath");
        assertNull(PrepareJcfBytecodeMojo.extractClassNameFromRoot(root));
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private Element buildLocationElement(String location) {
        Element root = new Element("javaxpath");
        Element locEl = new Element("loadedFromLocation", PrepareJcfBytecodeMojo.JCF_NS);
        locEl.setText(location);
        root.addContent(locEl);
        return root;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Class<?> cls = target.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " not found in " + target.getClass());
    }

    static class CapturingLog implements Log {
        final List<String> infoMessages  = new ArrayList<>();
        final List<String> debugMessages = new ArrayList<>();
        final List<String> warnMessages  = new ArrayList<>();

        @Override public boolean isDebugEnabled() { return true; }
        @Override public boolean isInfoEnabled()  { return true; }
        @Override public boolean isWarnEnabled()  { return true; }
        @Override public boolean isErrorEnabled() { return true; }

        @Override public void debug(CharSequence content) { debugMessages.add(str(content)); }
        @Override public void debug(CharSequence content, Throwable error) { debugMessages.add(str(content)); }
        @Override public void debug(Throwable error) {}
        @Override public void info(CharSequence content) { infoMessages.add(str(content)); }
        @Override public void info(CharSequence content, Throwable error) { infoMessages.add(str(content)); }
        @Override public void info(Throwable error) {}
        @Override public void warn(CharSequence content) { warnMessages.add(str(content)); }
        @Override public void warn(CharSequence content, Throwable error) { warnMessages.add(str(content)); }
        @Override public void warn(Throwable error) {}
        @Override public void error(CharSequence content) {}
        @Override public void error(CharSequence content, Throwable error) {}
        @Override public void error(Throwable error) {}

        private String str(CharSequence cs) { return cs == null ? "" : cs.toString(); }
    }
}

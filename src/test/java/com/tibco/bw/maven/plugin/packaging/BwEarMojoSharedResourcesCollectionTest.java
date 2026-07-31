package com.tibco.bw.maven.plugin.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static org.junit.Assert.assertTrue;

/**
 * Regression tests for {@link BwEarMojo#collectFiles} and the {@code EXCLUDED_NAMES} filter.
 *
 * <p>buildear copies the complete subtree of every folder path listed in the {@code .archive}
 * {@code <sharedResources>} descriptor into the SAR (e.g. {@code /config}, {@code /VersionInfo}).
 * A BW5 project routinely contains folders/files literally named {@code config} and
 * {@code VersionInfo} that hold those shared resources.</p>
 *
 * <p>Before this fix, {@code config} and {@code VersionInfo} were members of
 * {@code EXCLUDED_NAMES}, so {@code collectFiles} skipped them by name before the
 * shared-resource inclusion logic could ever run — every file beneath them was dropped and
 * the SAR diverged from the buildear reference. They must now be collected like any other
 * resource.</p>
 */
public class BwEarMojoSharedResourcesCollectionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final Method COLLECT_FILES;

    static {
        try {
            COLLECT_FILES = BwEarMojo.class.getDeclaredMethod(
                "collectFiles", File.class, File.class, List.class, List.class, List.class);
            COLLECT_FILES.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private File writeFile(File dir, String name, String content) throws Exception {
        File f = new File(dir, name);
        if (f.getParentFile() != null && !f.getParentFile().isDirectory()
                && !f.getParentFile().mkdirs()) {
            throw new java.io.IOException("Could not create " + f.getParentFile());
        }
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void collectFiles(File root, List parFiles, List sarFiles, List metaFiles)
            throws Exception {
        COLLECT_FILES.invoke(new BwEarMojo(), root, root, parFiles, sarFiles, metaFiles);
    }

    @SuppressWarnings("rawtypes")
    private Set<String> relPaths(List bwFiles) throws Exception {
        Set<String> paths = new LinkedHashSet<>();
        for (Object bwf : bwFiles) {
            String rel = (String) bwf.getClass().getDeclaredField("relativePath").get(bwf);
            paths.add(rel);
        }
        return paths;
    }

    /**
     * Regression (Fix #1): files under folders named {@code config} and {@code VersionInfo}
     * — including {@code .folder} descriptors and plain {@code .txt} content — must be
     * collected, not filtered out by {@code EXCLUDED_NAMES}.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void configAndVersionInfoSubtreesAreCollected() throws Exception {
        File root = tmp.newFolder("proj");

        // /config subtree (a shared-resource folder path in the .archive descriptor)
        writeFile(root, "config/.folder", "<folder/>");
        writeFile(root, "config/init/startup.txt", "boot");
        writeFile(root, "config/init/.folder", "<folder/>");

        // /VersionInfo subtree
        writeFile(root, "VersionInfo/.folder", "<folder/>");
        writeFile(root, "VersionInfo/build.txt", "1.0.0");

        List parFiles = new ArrayList();
        List sarFiles = new ArrayList();
        List metaFiles = new ArrayList();
        collectFiles(root, parFiles, sarFiles, metaFiles);

        Set<String> sar = relPaths(sarFiles);
        assertTrue("config/init/startup.txt must be collected into the SAR pool",
            sar.contains("config/init/startup.txt"));
        assertTrue("config/.folder must be collected",
            sar.contains("config/.folder"));
        assertTrue("config/init/.folder must be collected",
            sar.contains("config/init/.folder"));
        assertTrue("VersionInfo/build.txt must be collected into the SAR pool",
            sar.contains("VersionInfo/build.txt"));
        assertTrue("VersionInfo/.folder must be collected",
            sar.contains("VersionInfo/.folder"));
    }
}

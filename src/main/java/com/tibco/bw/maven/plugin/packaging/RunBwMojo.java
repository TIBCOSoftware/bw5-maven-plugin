package com.tibco.bw.maven.plugin.packaging;

import com.tibco.bw.maven.plugin.tra.TraFile;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Runs a TIBCO BusinessWorks 5.x application locally using the BW Engine.
 *
 * <p>Launches the {@code bwengine} executable found under
 * {@code <tibcoHome>/bw/<bwVersion>/bin/bwengine[.exe]} against the BW project
 * directory ({@code bwProjectPath}, defaults to {@code ${basedir}}).</p>
 *
 * <p>The engine process can run in the foreground (Maven waits for it) or in
 * the background (Maven continues immediately). When run in the foreground,
 * a JVM shutdown hook stops the engine when Maven exits.</p>
 *
 * <h3>Required configuration</h3>
 * <p>At minimum you must provide {@code tibcoHome} and {@code bwVersion}.
 * These can be set in your {@code settings.xml} so they do not pollute the
 * project POM:</p>
 * <pre>{@code
 * <!-- ~/.m2/settings.xml -->
 * <profiles>
 *   <profile>
 *     <id>tibco-local</id>
 *     <properties>
 *       <bw5.tibcoHome>/opt/tibco</bw5.tibcoHome>
 *       <bw5.bwVersion>5.13.0</bw5.bwVersion>
 *     </properties>
 *   </profile>
 * </profiles>
 * }</pre>
 *
 * <h3>Usage</h3>
 * <pre>
 *   mvn bw5:run
 *   mvn bw5:run -Dbw5.run.background=true
 *   mvn bw5:run -Dbw5.tibcoHome=/opt/tibco -Dbw5.bwVersion=5.13.0
 * </pre>
 */
@Mojo(
    name = "run",
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
    threadSafe = false
)
public class RunBwMojo extends AbstractBw5Mojo {

    /**
     * TIBCO BusinessWorks 5.x version string (e.g. {@code 5.13.0}).
     * Used to locate the {@code bw/<version>/bin/bwengine} executable under
     * {@code tibcoHome}.
     */
    @Parameter(defaultValue = "5.13.0", property = "bw5.bwVersion")
    private String bwVersion;

    /** {@code classpathMode} value: add nothing to the engine classpath (default). */
    static final String CP_MODE_NONE = "none";

    /** {@code classpathMode} value: add the {@code target/bw-lib} staging directory. */
    static final String CP_MODE_LIB_DIR = "libDir";

    /** {@code classpathMode} value: add each resolved JAR from the local repository. */
    static final String CP_MODE_DEPENDENCIES = "dependencies";

    /** {@code classpathPosition} value: inject into {@code CUSTOM_EXT_PREPEND_CP} (default). */
    static final String CP_POSITION_PREPEND = "prepend";

    /** {@code classpathPosition} value: inject into {@code CUSTOM_EXT_APPEND_CP}. */
    static final String CP_POSITION_APPEND = "append";

    /**
     * What to put on the BW engine's Java classpath, on top of the TIBCO defaults.
     *
     * <p>{@code tibco.alias.*} entries in {@code bwengine.properties} only resolve projlib and
     * resource references; the Java classes behind Java activities and custom functions have to be
     * on the real JVM classpath, which the TRA launcher builds from the {@code CUSTOM_EXT_*_CP}
     * variables. This is the runtime counterpart of the design-time classpath that
     * {@code bw5:designer-setup} injects into {@code designer.tra}.</p>
     *
     * <ul>
     *   <li>{@code none} (default) — add nothing; the engine runs with the installation classpath,
     *       as it did before this parameter existed.</li>
     *   <li>{@code libDir} — add {@code target/bw-lib}, where {@code bw5:initialize} stages the
     *       resolved projlibs and JARs. The TRA launcher expands a directory into the JARs it
     *       contains, so one entry covers every dependency. Requires the staging step to have run
     *       (e.g. {@code mvn package bw5:run}).</li>
     *   <li>{@code dependencies} — add each resolved JAR individually, straight from the local
     *       Maven repository. Works without staging.</li>
     * </ul>
     */
    @Parameter(defaultValue = CP_MODE_NONE, property = "bw5.run.classpathMode")
    private String classpathMode;

    /**
     * Where the entries selected by {@code classpathMode} go relative to the TIBCO classpath:
     * {@code prepend} (default) writes them to {@code tibco.env.CUSTOM_EXT_PREPEND_CP}, in front of
     * the standard entries; {@code append} writes them to {@code tibco.env.CUSTOM_EXT_APPEND_CP},
     * behind them.
     *
     * <p>Prepending gives the project's own versions precedence, which is usually what you want
     * while developing. It also puts them ahead of the TIBCO hotfix, bouncycastle and Rendezvous
     * entries that ship in {@code CUSTOM_EXT_PREPEND_CP}, so a transitive {@code xerces},
     * {@code log4j} or {@code commons-*} can shadow a library the engine itself depends on. Switch
     * to {@code append} if the engine starts behaving oddly once dependencies are on the
     * classpath.</p>
     */
    @Parameter(defaultValue = CP_POSITION_PREPEND, property = "bw5.run.classpathPosition")
    private String classpathPosition;

    /**
     * When {@code true}, the generated {@code bwengine.tra} sets {@code java.property.user.home} to
     * the build directory, so the engine JVM sees {@code target/} as the home directory.
     *
     * <p>Anything the engine or a Java activity resolves against {@code ~} then lands inside
     * {@code target/} — {@code ~/.TIBCO}, cache and temp files, and whatever a JDBC driver or
     * logging framework decides to write. That makes a run self-contained and disposable with
     * {@code mvn clean}, at the cost of hiding configuration the developer keeps in their real home
     * directory, which is why it is off by default.</p>
     *
     * <p>The process working directory is a separate concern, already covered by
     * {@code bw5.run.workingDir}.</p>
     */
    @Parameter(defaultValue = "false", property = "bw5.run.projectUserHome")
    private boolean projectUserHome;

    /** TRA entry that becomes {@code -Duser.home} on the engine JVM. */
    private static final String USER_HOME_KEY = "java.property.user.home";

    /**
     * When {@code true}, the BW engine is started as a background process and
     * Maven returns immediately after the engine has started.
     * When {@code false} (default), Maven blocks until the engine process exits.
     */
    @Parameter(defaultValue = "false", property = "bw5.run.background")
    private boolean background;

    /**
     * Number of seconds to wait for the engine to start before reporting an error.
     * Only meaningful when {@code background=true}.
     * Set to {@code 0} to disable the startup check and return immediately.
     * Default: {@code 30} seconds.
     */
    @Parameter(defaultValue = "30", property = "bw5.run.startupWaitSeconds")
    private int startupWaitSeconds;

    /**
     * Path to a BW domain home directory.
     * When provided, the {@code -d} flag is added to the engine command line.
     * Leave empty (default) to run without a domain (stand-alone / test mode).
     */
    @Parameter(property = "bw5.run.domainHome")
    private File domainHome;

    /**
     * Additional arguments appended verbatim to the {@code bwengine} command line.
     * Each element in the list becomes a separate token.
     *
     * <p>Example:</p>
     * <pre>{@code
     * <extraArgs>
     *   <arg>-t</arg>
     *   <arg>trace</arg>
     * </extraArgs>
     * }</pre>
     */
    @Parameter
    private String[] extraArgs;

    /**
     * Working directory for the engine process.
     * Defaults to {@code ${project.build.directory}}.
     */
    @Parameter(defaultValue = "${project.build.directory}", property = "bw5.run.workingDir")
    private File workingDir;

    /**
     * Optional properties file whose entries are merged into the generated
     * {@code bwengine.properties} and take precedence over auto-generated aliases.
     *
     * <p>Use this to supply engine control variables and global variable overrides, e.g.:</p>
     * <pre>
     * tibco.clientVar.defaultVars/MyApp/Connections/DB_URL=jdbc:oracle:thin:@localhost:1521/XE
     * bw.plugin.jms.recoverOnStartupError=true
     * </pre>
     *
     * <p>When not set, only the auto-generated alias entries are written to
     * {@code target/bwengine.properties}.</p>
     */
    @Parameter(property = "bw5.run.propertiesFile")
    private File propertiesFile;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("bw5:run skipped.");
            return;
        }

        File engine      = resolveEngineExecutable();
        File engineProps = generateEngineProperties();
        File engineTra   = prepareEngineTra(new File(engine.getParentFile(), "bwengine.tra"),
                                            engineTraFile(),
                                            engineClasspathKey(classpathPosition),
                                            engineClasspathEntries(),
                                            projectUserHome
                                                ? project.getBuild().getDirectory() : null);

        List<String> cmd = buildCommand(engine, engineProps, engineTra);

        getLog().info("BW Engine  : " + engine.getAbsolutePath());
        getLog().info("App name   : " + project.getArtifactId());
        getLog().info("Props file : " + engineProps.getAbsolutePath());
        getLog().info("BW project : " + getEffectiveSourceDir().getAbsolutePath());
        getLog().info("Background : " + background);
        getLog().info("Command    : " + String.join(" ", cmd));

        if (!workingDir.mkdirs() && !workingDir.isDirectory()) {
            throw new MojoExecutionException("Failed to create directory: " + workingDir.getAbsolutePath());
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workingDir);
            pb.redirectErrorStream(true);   // merge stderr into stdout

            Process proc = pb.start();

            if (background) {
                if (startupWaitSeconds > 0) {
                    waitForStartupThenLog(proc, startupWaitSeconds);
                } else {
                    streamToLogInBackground(new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8)));
                }
                getLog().info("BW engine running in background.");
                // No shutdown hook in background mode: engine must outlive Maven (PRD §4 Non-Goals)
            } else {
                // Foreground: pipe stdout to Maven log, block until exit
                registerShutdownHook(proc);
                pipeToLog(proc);
                int exit = proc.waitFor();
                if (exit != 0) {
                    throw new MojoExecutionException("BW engine exited with code " + exit);
                }
            }
        } catch (MojoExecutionException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("bw5:run interrupted", e);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to start BW engine: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    //  Resolution helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the bwengine.properties alias key for a projlib dependency.
     * The BW5 engine looks up libraries by filename ({@code artifactId + ".projlib"}),
     * not by Maven GAV coordinates. Package-private for testing.
     */
    static String projlibAliasKey(String artifactId) {
        return "tibco.alias." + artifactId + ".projlib";
    }

    private File resolveEngineExecutable() throws MojoExecutionException {
        File home = resolvedTibcoHome();
        if (home == null || !home.isDirectory()) {
            throw new MojoExecutionException(
                "bw5.tibcoHome is not set or does not exist: " + home
                + "\nSet -Dbw5.tibcoHome=/opt/tibco or the TIBCO_HOME environment variable.");
        }

        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String exeName    = isWindows ? "bwengine.exe" : "bwengine";

        // Primary location: <tibcoHome>/bw/<bwVersion>/bin/bwengine
        File engine = Paths.get(home.getAbsolutePath(), "bw", bwVersion, "bin", exeName).toFile();
        if (engine.isFile()) return engine;

        // Fallback: some installations use a flat <tibcoHome>/bw/bin/bwengine
        File fallback = Paths.get(home.getAbsolutePath(), "bw", "bin", exeName).toFile();
        if (fallback.isFile()) return fallback;

        throw new MojoExecutionException(
            "Cannot find bwengine executable. Looked in:\n"
            + "  " + engine.getAbsolutePath() + "\n"
            + "  " + fallback.getAbsolutePath() + "\n"
            + "Check that bw5.tibcoHome=" + home + " and bw5.bwVersion=" + bwVersion + " are correct.");
    }

    private File generateEngineProperties() throws MojoExecutionException {
        // LinkedHashMap preserves insertion order: aliases first, then user overrides
        Map<String, String> entries = new LinkedHashMap<>();

        // 1. Auto-generate tibco.alias entries from Maven dependencies.
        //    Both projlibs and JARs are resolved by filename, matching what the BW5 engine
        //    and .aliaslib files use as lookup keys (e.g. "MyLib.projlib", "util-1.0.0.jar").
        for (Artifact projlib : getProjectlibDependencies()) {
            entries.put(projlibAliasKey(projlib.getArtifactId()),
                projlib.getFile().getAbsolutePath());
        }
        for (Artifact jar : getJarDependencies()) {
            String key = "tibco.alias." + jar.getArtifactId() + "-" + jar.getVersion() + ".jar";
            entries.put(key, jar.getFile().getAbsolutePath());
        }
        getLog().info("Generated " + entries.size() + " alias entrie(s) from Maven dependencies.");

        // 2. Merge user-provided properties (user wins over generated aliases)
        if (propertiesFile != null) {
            if (!propertiesFile.isFile()) {
                throw new MojoExecutionException(
                    "bw5.run.propertiesFile does not exist: " + propertiesFile.getAbsolutePath());
            }
            Properties userProps = new Properties();
            try (InputStream is = java.nio.file.Files.newInputStream(propertiesFile.toPath())) {
                userProps.load(is);
            } catch (IOException e) {
                throw new MojoExecutionException(
                    "Failed to load properties file: " + propertiesFile + ": " + e.getMessage(), e);
            }
            for (String key : userProps.stringPropertyNames()) {
                entries.put(key, userProps.getProperty(key));
            }
            getLog().info("Merged " + userProps.size() + " user properties from " + propertiesFile);
        }

        // 3. Write manually — Properties.store() would escape ':' as '\:' which can break
        //    user-provided keys that contain colon separators
        File outFile = new File(project.getBuild().getDirectory(), "bwengine.properties");
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(java.nio.file.Files.newOutputStream(outFile.toPath()), StandardCharsets.UTF_8))) {
            writer.write("# Generated by bw5:run — edit and set bw5.run.propertiesFile to customise");
            writer.newLine();
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                // Escape ':' in keys — Properties.load() treats bare ':' as a key-value
                // separator, which would split Maven coordinates like groupId:artifactId:version
                writer.write(entry.getKey().replace(":", "\\:") + "=" + entry.getValue());
                writer.newLine();
            }
        } catch (IOException e) {
            throw new MojoExecutionException(
                "Failed to write bwengine.properties: " + e.getMessage(), e);
        }
        return outFile;
    }

    /** Location of the project-local bwengine.tra copy: {@code target/.TIBCO/bwengine.tra}. */
    private File engineTraFile() {
        return new File(project.getBuild().getDirectory(), ".TIBCO/bwengine.tra");
    }

    /**
     * The TRA classpath variable selected by {@code classpathPosition}. Both are combined by
     * {@code tibco.class.path.extended %CUSTOM_EXT_PREPEND_CP%:%STD_EXT_CP%:%CUSTOM_EXT_APPEND_CP%},
     * so the choice only decides whether the project's entries win or lose against the standard
     * ones. Package-private for testing.
     *
     * @throws MojoExecutionException if the value is not {@code prepend} or {@code append}
     */
    static String engineClasspathKey(String position) throws MojoExecutionException {
        if (CP_POSITION_PREPEND.equalsIgnoreCase(position)) {
            return "tibco.env.CUSTOM_EXT_PREPEND_CP";
        }
        if (CP_POSITION_APPEND.equalsIgnoreCase(position)) {
            return "tibco.env.CUSTOM_EXT_APPEND_CP";
        }
        throw new MojoExecutionException("Invalid bw5.run.classpathPosition: '" + position
            + "'. Expected '" + CP_POSITION_PREPEND + "' or '" + CP_POSITION_APPEND + "'.");
    }

    /**
     * The classpath entries selected by {@code mode}. Kept free of Maven state so the mode dispatch
     * can be tested on its own. Package-private for testing.
     *
     * @param mode       one of {@code none}, {@code libDir}, {@code dependencies}
     * @param libDirPath absolute path of the staging directory, or {@code null} when it does not
     *                   exist — an absent directory yields no entries rather than a broken one
     * @param jarPaths   absolute paths of the resolved JAR dependencies
     * @throws MojoExecutionException if {@code mode} is not one of the three accepted values
     */
    static List<String> classpathEntries(String mode, String libDirPath, List<String> jarPaths)
            throws MojoExecutionException {

        if (CP_MODE_NONE.equalsIgnoreCase(mode)) {
            return new ArrayList<>();
        }
        if (CP_MODE_LIB_DIR.equalsIgnoreCase(mode)) {
            return libDirPath == null
                ? new ArrayList<String>()
                : new ArrayList<>(Arrays.asList(libDirPath));
        }
        if (CP_MODE_DEPENDENCIES.equalsIgnoreCase(mode)) {
            return new ArrayList<>(jarPaths);
        }
        throw new MojoExecutionException("Invalid bw5.run.classpathMode: '" + mode + "'. Expected '"
            + CP_MODE_NONE + "', '" + CP_MODE_LIB_DIR + "' or '" + CP_MODE_DEPENDENCIES + "'.");
    }

    /** Resolves {@code classpathMode} against the project, warning about a missing staging dir. */
    private List<String> engineClasspathEntries() throws MojoExecutionException {
        if (CP_MODE_NONE.equalsIgnoreCase(classpathMode)) {
            return new ArrayList<>();
        }

        String libDirPath = null;
        if (bwLibDirectory != null && bwLibDirectory.isDirectory()) {
            libDirPath = bwLibDirectory.getAbsolutePath();
        } else if (CP_MODE_LIB_DIR.equalsIgnoreCase(classpathMode)) {
            getLog().warn("bw5.run.classpathMode=" + CP_MODE_LIB_DIR + " but " + bwLibDirectory
                + " does not exist — nothing added to the engine classpath. Run bw5:initialize first"
                + " (e.g. 'mvn package bw5:run'), or use bw5.run.classpathMode="
                + CP_MODE_DEPENDENCIES + ".");
        }

        List<String> jarPaths = new ArrayList<>();
        for (Artifact jar : getJarDependencies()) {
            jarPaths.add(jar.getFile().getAbsolutePath());
        }
        return classpathEntries(classpathMode, libDirPath, jarPaths);
    }

    /**
     * Generates {@code target/.TIBCO/bwengine.tra}: a copy of the {@code bwengine.tra} that sits next
     * to the engine binary. The engine is then started with {@code --propFile <this copy>}, so the
     * project can adjust launcher settings without ever writing to the TIBCO installation — which is
     * typically shared, and often read-only, on a developer machine. This mirrors what
     * {@code bw5:designer-setup} already does with {@code designer.tra}.
     *
     * <p>The copy is rewritten on every run so a patched or hot-fixed installation is always picked
     * up; it must never be committed or cached.</p>
     *
     * @param baseTra          the installed {@code bwengine.tra} to copy from
     * @param traCopy          where to write the project-local copy
     * @param classpathKey     TRA variable to inject {@code classpathEntries} into
     * @param classpathEntries entries to add to the engine classpath; empty leaves the copy verbatim
     * @param userHome         value for {@code java.property.user.home}, or {@code null} to keep the
     *                         installed one; see {@code bw5.run.projectUserHome}
     * @return the generated copy, or {@code null} when {@code baseTra} does not exist — in which case
     *         the engine is started with the installation defaults, exactly as before this goal
     *         generated a TRA file at all
     */
    File prepareEngineTra(File baseTra, File traCopy, String classpathKey,
            List<String> classpathEntries, String userHome) throws MojoExecutionException {

        if (!baseTra.isFile()) {
            if (!classpathEntries.isEmpty()) {
                getLog().warn("bw5.run.classpathMode is set but bwengine.tra was not found at "
                    + baseTra.getAbsolutePath() + " — the engine classpath cannot be extended.");
            }
            getLog().warn("bwengine.tra not found at " + baseTra.getAbsolutePath()
                + " — starting the engine with the installation defaults (no --propFile).");
            return null;
        }

        List<String> lines;
        try {
            lines = TraFile.read(baseTra);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read " + baseTra + ": " + e.getMessage(), e);
        }

        if (!classpathEntries.isEmpty()) {
            lines = TraFile.injectClasspath(lines, classpathKey, classpathEntries, File.pathSeparator);
            getLog().info("Engine CP  : " + classpathKey + " += "
                + String.join(File.pathSeparator, classpathEntries));
        }

        if (userHome != null) {
            lines = TraFile.setProperty(lines, USER_HOME_KEY, TraFile.escapePath(userHome));
            getLog().info("User home  : " + userHome + " (engine JVM -Duser.home)");
        }

        File parent = traCopy.getParentFile();
        if (!parent.mkdirs() && !parent.isDirectory()) {
            throw new MojoExecutionException("Failed to create directory: " + parent.getAbsolutePath());
        }
        try {
            TraFile.write(traCopy, lines);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write " + traCopy + ": " + e.getMessage(), e);
        }
        getLog().info("Generated: " + traCopy.getAbsolutePath()
            + " (copied from " + baseTra.getAbsolutePath() + ")");
        return traCopy;
    }

    private List<String> buildCommand(File engine, File engineProps, File engineTra) {
        List<String> cmd = new ArrayList<>();
        cmd.add(engine.getAbsolutePath());

        // Project-local bwengine.tra copy; null when the installed one could not be found.
        if (engineTra != null) {
            cmd.add("--propFile");
            cmd.add(engineTra.getAbsolutePath());
            getLog().info("TRA file   : " + engineTra.getAbsolutePath());
        }

        // Application name
        cmd.add("-n");
        cmd.add(project.getArtifactId());

        // Generated properties file (aliases + user overrides)
        cmd.add("-p");
        cmd.add(engineProps.getAbsolutePath());

        // Optional domain home
        if (domainHome != null && domainHome.isDirectory()) {
            cmd.add("-d");
            cmd.add(domainHome.getAbsolutePath());
        }

        // Extra args
        if (extraArgs != null) {
            cmd.addAll(Arrays.asList(extraArgs));
        }

        // BW project path — positional, must be last. Use the effective source dir so a build's
        // processed sources (target/bw-src, with compiled JCF bytecode) are run when present,
        // falling back to the configured bwProjectPath when running standalone.
        cmd.add(getEffectiveSourceDir().getAbsolutePath());

        return cmd;
    }

    // -----------------------------------------------------------------------
    //  Process management helpers
    // -----------------------------------------------------------------------

    /**
     * Startup markers scanned in bwengine stdout to detect a successful start.
     * Package-private for testing.
     */
    static final String[] START_MARKERS = {
        "BWENGINE-300002",        // BW 5.16+: "BWENGINE-300002 Engine <hostname> started"
        "Engine Initialized",     // older BW 5.x variants
        "Application started",
        "bwengine started",
        "BusinessWorks started",
        "Deployed application"
    };

    /** Returns {@code true} when {@code line} contains any known startup marker. Package-private for testing. */
    static boolean detectStartupMarker(String line) {
        for (String marker : START_MARKERS) {
            if (line.contains(marker)) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} when a JVM shutdown hook should be registered to kill the engine.
     * The hook is needed only in foreground mode; in background mode the engine must outlive Maven.
     * Package-private for testing.
     */
    static boolean isShutdownHookNeeded(boolean background) {
        return !background;
    }

    /**
     * Reads from the engine's stdout until a startup marker is found or the deadline passes,
     * logging every line to the Maven log. Then transfers stream ownership to a daemon thread
     * that continues logging until EOF.
     *
     * A single reader is used for the entire lifetime of the stream, eliminating the race that
     * would occur if startBackgroundLogging and waitForStartup each opened their own reader
     * on the same underlying InputStream.
     */
    @SuppressWarnings("PMD.CloseResource") // reader ownership transferred to streamToLogInBackground
    void waitForStartupThenLog(Process proc, int seconds) {
        long deadline = System.currentTimeMillis() + (long) seconds * 1000;
        // Reader ownership is transferred to streamToLogInBackground via finally — do NOT use
        // try-with-resources here or the stream would be closed before the thread can read it.
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        try {
            while (System.currentTimeMillis() < deadline && proc.isAlive()) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line != null) {
                        getLog().info("[bwengine] " + line);
                        if (detectStartupMarker(line)) {
                            getLog().info("BW engine startup detected.");
                            return;
                        }
                    }
                } else {
                    Thread.sleep(200);
                }
            }
            getLog().warn("BW engine startup marker not detected within " + seconds
                + "s. The process may still be starting.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            getLog().warn("Error reading engine output: " + e.getMessage());
        } finally {
            streamToLogInBackground(reader);
        }
    }

    /** Transfers reader ownership to a daemon thread that logs remaining output to Maven log. */
    private void streamToLogInBackground(BufferedReader reader) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = reader) {
                String line;
                while ((line = br.readLine()) != null) {
                    getLog().info("[bwengine] " + line);
                }
            } catch (IOException ignored) {
            }
        }, "bwengine-log");
        t.setDaemon(true);
        t.start();
    }

    /** Pipes the engine's stdout/stderr to the Maven log (blocking). */
    private void pipeToLog(Process proc) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                getLog().info("[bwengine] " + line);
            }
        } catch (IOException e) {
            getLog().warn("Error reading engine output: " + e.getMessage());
        }
    }

    /** Registers a JVM shutdown hook that destroys the engine process on exit. */
    private void registerShutdownHook(Process proc) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (proc.isAlive()) {
                getLog().info("Stopping BW engine...");
                proc.destroy();
                try { proc.waitFor(); } catch (InterruptedException ignored) { }
            }
        }, "bwengine-shutdown"));
    }
}

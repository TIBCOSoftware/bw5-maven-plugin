package com.tibco.bw.maven.plugin.packaging;

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

        List<String> cmd = buildCommand(engine, engineProps);

        getLog().info("BW Engine  : " + engine.getAbsolutePath());
        getLog().info("App name   : " + project.getArtifactId());
        getLog().info("Props file : " + engineProps.getAbsolutePath());
        getLog().info("BW project : " + bwProjectPath.getAbsolutePath());
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
                startBackgroundLogging(proc);
                if (startupWaitSeconds > 0) {
                    waitForStartup(proc, startupWaitSeconds);
                }
                getLog().info("BW engine started in background (PID-based process handle).");
                registerShutdownHook(proc);
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

        // 1. Auto-generate tibco.alias entries from Maven dependencies
        //    Projlibs: engine resolves by full Maven coordinate (groupId:artifactId:version:type)
        //    JARs: engine resolves by filename, matching what .aliaslib files store
        for (Artifact projlib : getProjectlibDependencies()) {
            String key = "tibco.alias." + projlib.getGroupId() + ":"
                + projlib.getArtifactId() + ":" + projlib.getVersion() + ":projlib";
            entries.put(key, projlib.getFile().getAbsolutePath());
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
            try (InputStream is = new FileInputStream(propertiesFile)) {
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

        // 3. Write manually — Properties.store() would escape ':' as '\:' which breaks
        //    TIBCO's engine when it looks up projlib aliases by Maven coordinate
        File outFile = new File(project.getBuild().getDirectory(), "bwengine.properties");
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8))) {
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

    private List<String> buildCommand(File engine, File engineProps) {
        List<String> cmd = new ArrayList<>();
        cmd.add(engine.getAbsolutePath());

        // bwengine.tra in the same directory as the engine binary
        File traFile = new File(engine.getParentFile(), "bwengine.tra");
        if (traFile.isFile()) {
            cmd.add("--propFile");
            cmd.add(traFile.getAbsolutePath());
            getLog().info("TRA file   : " + traFile.getAbsolutePath());
        } else {
            getLog().warn("bwengine.tra not found at " + traFile.getAbsolutePath() + " — skipping --propFile");
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

        // BW project path — positional, must be last
        cmd.add(bwProjectPath.getAbsolutePath());

        return cmd;
    }

    // -----------------------------------------------------------------------
    //  Process management helpers
    // -----------------------------------------------------------------------

    /**
     * Waits up to {@code seconds} for the engine to emit a startup marker on stdout.
     * BW5 engine typically logs "-- Engine Initialized --" or "Application ... started".
     */
    private void waitForStartup(Process proc, int seconds) throws MojoExecutionException {
        final String[] START_MARKERS = {
            "Engine Initialized",
            "Application started",
            "bwengine started",
            "BusinessWorks started",
            "Deployed application"
        };

        long deadline = System.currentTimeMillis() + (long) seconds * 1000;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (System.currentTimeMillis() < deadline && proc.isAlive()) {
                if (reader.ready()) {
                    line = reader.readLine();
                    if (line != null) {
                        getLog().info("[bwengine] " + line);
                        for (String marker : START_MARKERS) {
                            if (line.contains(marker)) {
                                getLog().info("BW engine startup detected.");
                                return;
                            }
                        }
                    }
                } else {
                    Thread.sleep(200);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            getLog().warn("Error reading engine output: " + e.getMessage());
        }
        // No marker found within timeout — warn but do not fail
        getLog().warn("BW engine startup marker not detected within " + seconds
            + "s. The process may still be starting.");
    }

    /** Starts a daemon thread that pipes engine stdout to the Maven log. */
    private void startBackgroundLogging(Process proc) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    getLog().info("[bwengine] " + line);
                }
            } catch (IOException ignored) { }
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

package com.tibco.bw.maven.plugin.packaging;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.*;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs a TIBCO BusinessWorks 5.x application locally using the BW Engine.
 *
 * <p>Looks for the built EAR at {@code target/<finalName>.ear} and launches it
 * with the {@code bwengine} executable found under
 * {@code <tibcoHome>/bw/<bwVersion>/bin/bwengine[.exe]}.</p>
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
 *       <tibco.Home>/opt/tibco</tibco.Home>
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
 *   mvn bw5:run -Dtibco.Home=/opt/tibco -Dbw5.bwVersion=5.13.0
 * </pre>
 */
@Mojo(
    name = "run",
    requiresDependencyResolution = ResolutionScope.NONE,
    threadSafe = false
)
public class RunBwMojo extends AbstractBw5Mojo {

    /**
     * TIBCO installation root directory (e.g. {@code /opt/tibco} or
     * {@code C:\tibco}).
     *
     * <p>The BW engine executable is expected at:
     * {@code <tibcoHome>/bw/<bwVersion>/bin/bwengine[.exe]}.</p>
     *
     * <p>Uses the same property name as the BW6 plugin ({@code tibco.Home})
     * so a single settings.xml entry covers both.</p>
     */
    @Parameter(property = "tibco.Home", required = true)
    private File tibcoHome;

    /**
     * TIBCO BusinessWorks 5.x version string (e.g. {@code 5.13.0}).
     * Used to locate the {@code bw/<version>/bin/bwengine} executable under
     * {@code tibcoHome}.
     */
    @Parameter(defaultValue = "5.13.0", property = "bw5.bwVersion")
    private String bwVersion;

    /**
     * Path to the EAR file to run.
     * Defaults to {@code target/<finalName>.ear} (the artifact built by
     * {@code bw5:bwear}). Override this to run a specific EAR.
     */
    @Parameter(property = "bw5.run.earFile")
    private File earFile;

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

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("bw5:run skipped.");
            return;
        }

        File engine = resolveEngineExecutable();
        File ear    = resolveEarFile();

        List<String> cmd = buildCommand(engine, ear);

        getLog().info("BW Engine  : " + engine.getAbsolutePath());
        getLog().info("EAR file   : " + ear.getAbsolutePath());
        getLog().info("Background : " + background);
        getLog().info("Command    : " + String.join(" ", cmd));

        workingDir.mkdirs();

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
        if (tibcoHome == null || !tibcoHome.isDirectory()) {
            throw new MojoExecutionException(
                "tibco.Home is not set or does not exist: " + tibcoHome
                + "\nConfigure it in settings.xml: <tibco.Home>/opt/tibco</tibco.Home>");
        }

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String exeName    = isWindows ? "bwengine.exe" : "bwengine";

        // Primary location: <tibcoHome>/bw/<bwVersion>/bin/bwengine
        File engine = Paths.get(tibcoHome.getAbsolutePath(), "bw", bwVersion, "bin", exeName).toFile();
        if (engine.isFile()) return engine;

        // Fallback: some installations use a flat <tibcoHome>/bw/bin/bwengine
        File fallback = Paths.get(tibcoHome.getAbsolutePath(), "bw", "bin", exeName).toFile();
        if (fallback.isFile()) return fallback;

        throw new MojoExecutionException(
            "Cannot find bwengine executable. Looked in:\n"
            + "  " + engine.getAbsolutePath() + "\n"
            + "  " + fallback.getAbsolutePath() + "\n"
            + "Check that tibco.Home=" + tibcoHome + " and bw5.bwVersion=" + bwVersion + " are correct.");
    }

    private File resolveEarFile() throws MojoExecutionException {
        if (earFile != null && earFile.isFile()) return earFile;

        // Auto-detect from project artifact
        File defaultEar = new File(project.getBuild().getDirectory(),
                                   project.getBuild().getFinalName() + ".ear");
        if (defaultEar.isFile()) return defaultEar;

        throw new MojoExecutionException(
            "Cannot find EAR file to run. Looked at: " + defaultEar.getAbsolutePath()
            + "\nRun 'mvn package' first, or set bw5.run.earFile explicitly.");
    }

    private List<String> buildCommand(File engine, File ear) {
        List<String> cmd = new ArrayList<>();
        cmd.add(engine.getAbsolutePath());

        // EAR path
        cmd.add("-p");
        cmd.add(ear.getAbsolutePath());

        // Optional domain home
        if (domainHome != null && domainHome.isDirectory()) {
            cmd.add("-d");
            cmd.add(domainHome.getAbsolutePath());
        }

        // Extra args
        if (extraArgs != null) {
            cmd.addAll(Arrays.asList(extraArgs));
        }

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
                new InputStreamReader(proc.getInputStream()))) {
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
                    new InputStreamReader(proc.getInputStream()))) {
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
                new InputStreamReader(proc.getInputStream()))) {
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

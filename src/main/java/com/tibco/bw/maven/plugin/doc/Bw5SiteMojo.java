package com.tibco.bw.maven.plugin.doc;

import com.tibco.bw.maven.plugin.packaging.AbstractBw5Mojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates HTML documentation for a BW5 project.
 *
 * <p>Produces a self-contained HTML site under {@code target/site/bw5/} with:</p>
 * <ul>
 *   <li>Project overview page (starters, dependencies, statistics)</li>
 *   <li>One page per process with: SVG diagram, activity table,
 *       transition table with conditions, and data mapping visualization</li>
 * </ul>
 *
 * <p>No TIBCO tools required. Run standalone:</p>
 * <pre>
 *   mvn bw5:site
 * </pre>
 * <p>Or bind to the Maven site lifecycle by adding an execution in the plugin configuration.</p>
 */
@Mojo(
    name = "site",
    defaultPhase = LifecyclePhase.SITE,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
    threadSafe = false
)
public class Bw5SiteMojo extends AbstractBw5Mojo {

    /**
     * Output directory for generated HTML documentation.
     * Defaults to {@code ${project.build.directory}/site/bw5}.
     */
    @Parameter(defaultValue = "${project.build.directory}/site/bw5", property = "bw5.siteOutputDir")
    private File siteOutputDir;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("bw5:site skipped.");
            return;
        }

        File srcDir = bwSourcesDirectory.exists() ? bwSourcesDirectory : bwProjectPath;
        if (!srcDir.exists()) {
            getLog().warn("BW project source directory not found: " + srcDir.getAbsolutePath()
                + "\nSkipping site generation. Run 'mvn generate-sources bw5:site' to include sources copy.");
            return;
        }

        getLog().info("Generating BW5 site documentation...");
        getLog().info("Source: " + srcDir.getAbsolutePath());
        getLog().info("Output: " + siteOutputDir.getAbsolutePath());

        try {
            // 1. Discover and parse all .process files
            List<File> processFiles = findProcessFiles(srcDir);
            getLog().info("Found " + processFiles.size() + " process file(s)");

            ProcessDocParser parser = new ProcessDocParser();
            List<ProcessDocModel> models = new ArrayList<>();

            for (File pf : processFiles) {
                try {
                    ProcessDocModel model = parser.parse(pf);
                    models.add(model);
                    getLog().debug("  Parsed: " + model.displayName
                        + " (" + model.activities.size() + " activities, "
                        + model.transitions.size() + " transitions, "
                        + totalMappings(model) + " mappings)");
                } catch (Exception e) {
                    getLog().warn("Could not parse " + pf.getName() + ": " + e.getMessage());
                }
            }

            // 2. Generate HTML
            SiteHtmlGenerator generator = new SiteHtmlGenerator(siteOutputDir, project);

            // Index page
            generator.generateIndex(models);
            getLog().info("Generated: " + new File(siteOutputDir, "index.html").getAbsolutePath());

            // Process pages
            for (ProcessDocModel model : models) {
                generator.generateProcessPage(model);
                getLog().debug("  Generated process page: " + model.displayName);
            }

            getLog().info("BW5 site generated successfully: " + siteOutputDir.getAbsolutePath());
            getLog().info("Open in browser: file://" + new File(siteOutputDir, "index.html").getAbsolutePath());

        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate BW5 site: " + e.getMessage(), e);
        }
    }

    private List<File> findProcessFiles(File dir) {
        List<File> result = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return result;
        for (File f : files) {
            if (f.isDirectory()) {
                // Skip hidden dirs
                if (!f.getName().startsWith(".")) {
                    result.addAll(findProcessFiles(f));
                }
            } else if (f.getName().endsWith(".process")) {
                result.add(f);
            }
        }
        return result;
    }

    private int totalMappings(ProcessDocModel model) {
        int count = model.starter != null ? model.starter.inputMappings.size() : 0;
        for (ProcessDocModel.Activity a : model.activities) {
            count += a.inputMappings.size();
        }
        return count;
    }
}

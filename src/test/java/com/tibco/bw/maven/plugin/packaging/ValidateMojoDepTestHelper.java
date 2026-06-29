package com.tibco.bw.maven.plugin.packaging;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.DefaultArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusContainer;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal test double for ValidateMojo that wires up project.getDependencies()
 * and session.getLocalRepository() without a full Maven container.
 */
class ValidateMojoDepTestHelper extends ValidateMojo {

    private final List<Dependency> dependencies = new ArrayList<>();
    private final String localRepoBasedir;

    ValidateMojoDepTestHelper(String localRepoBasedir) throws Exception {
        this.localRepoBasedir = localRepoBasedir;

        MavenProject proj = new MavenProject() {
            @Override
            public List<Dependency> getDependencies() {
                return dependencies;
            }
        };

        ArtifactRepository localRepo = new DefaultArtifactRepository(
            "local", "file://" + localRepoBasedir, new DefaultRepositoryLayout()
        );

        MavenSession sess = new MavenSession(
            (PlexusContainer) null,
            new DefaultMavenExecutionRequest(),
            new DefaultMavenExecutionResult(),
            new ArrayList<>()
        ) {
            @Override
            public ArtifactRepository getLocalRepository() {
                return localRepo;
            }
        };

        this.project = proj;
        this.session = sess;
    }

    void addDependency(String groupId, String artifactId, String version,
                        String type, String scope) {
        Dependency dep = new Dependency();
        dep.setGroupId(groupId);
        dep.setArtifactId(artifactId);
        dep.setVersion(version);
        dep.setType(type);
        dep.setScope(scope);
        dependencies.add(dep);
    }
}

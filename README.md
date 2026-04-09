# bw5-maven-plugin

Maven plugin for **TIBCO BusinessWorks 5.x** that provides full application lifecycle management — dependency management, EAR/projlib assembly, deployment config generation, and documentation — **without requiring any TIBCO tools installed on the build machine**.

> **Requires:** Java 11+, Maven 3.6.3+

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [Packaging Types](#packaging-types)
3. [Build Lifecycle](#build-lifecycle)
4. [Project Layout](#project-layout)
5. [Goals Reference](#goals-reference)
   - [bw5:init](#bw5init)
   - [bw5:bwear](#bw5bwear)
   - [bw5:bw5module](#bw5bw5module)
   - [bw5:deploy-config](#bw5deploy-config)
   - [bw5:designer-setup](#bw5designer-setup)
   - [bw5:run](#bw5run)
   - [bw5:site](#bw5site)
6. [Dependency Management](#dependency-management)
7. [Property Override Model](#property-override-model)
8. [Archive Descriptor Support](#archive-descriptor-support)
9. [LibBuilder Descriptor Support](#libbuilder-descriptor-support)
10. [Static Analysis & Vulnerability Scanning](#static-analysis--vulnerability-scanning)
11. [Configuration Reference](#configuration-reference)

---

## Quick Start

### Application (EAR)

```xml
<!-- pom.xml — place alongside your BW project files -->
<project>
    <groupId>com.example</groupId>
    <artifactId>my-bw5-app</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>bwear</packaging>

    <build>
        <plugins>
            <plugin>
                <groupId>com.tibco.bw</groupId>
                <artifactId>bw5-maven-plugin</artifactId>
                <version>1.0.0-SNAPSHOT</version>
                <extensions>true</extensions>
            </plugin>
        </plugins>
    </build>
</project>
```

```bash
mvn package   # → target/my-bw5-app-1.0.0-SNAPSHOT.ear
              #   target/my-bw5-app-1.0.0-SNAPSHOT-deploy.xml
              #   target/my-bw5-app-1.0.0-SNAPSHOT-deploy.properties
              #   target/values.yaml
mvn install   # → local Maven repository
mvn deploy    # → remote repository (Nexus/Artifactory)
```

### Library (projlib)

```xml
<packaging>projlib</packaging>
```

```bash
mvn package   # → target/my-bw5-lib-1.0.0-SNAPSHOT.projlib
```

---

## Packaging Types

| Type | Extension | Description |
|------|-----------|-------------|
| `bwear` | `.ear` | BW5 application — PAR + SAR + TIBCO.xml |
| `projlib` | `.projlib` | BW5 reusable library |

---

## Build Lifecycle

### `bwear` packaging

| Phase | Goal | Description |
|-------|------|-------------|
| `initialize` | `bw5:initialize` | Validates BW project structure |
| `generate-sources` | `bw5:copy-bw-sources` | Copies BW sources to `target/bw-src` |
| `generate-sources` | `bw5:extract-java-sources` | Extracts Java Code activities to `target/generated-sources/bw-java` |
| `process-resources` | `bw5:resolve-dependencies` | Copies projlib/JAR deps to `target/bw-lib` |
| `compile` | `maven-compiler-plugin:compile` | Compiles extracted Java sources |
| `package` | `bw5:bwear` | Assembles EAR + generates deploy configs |
| `install` | standard | Installs to local Maven repository |
| `deploy` | standard | Deploys to remote Maven repository |

### `projlib` packaging

| Phase | Goal | Description |
|-------|------|-------------|
| `initialize` | `bw5:initialize` | Validates BW project structure |
| `generate-sources` | `bw5:copy-bw-sources`, `bw5:extract-java-sources` | Source preparation |
| `process-resources` | `bw5:resolve-dependencies` | Resolves dependencies |
| `compile` | `maven-compiler-plugin:compile` | Compiles Java sources |
| `package` | `bw5:bw5module` | Assembles `.projlib` |
| `install` / `deploy` | standard | Publishes artifact |

---

## Project Layout

The default layout places `pom.xml` at the root of the BW project — matching how TIBCO Designer organises projects on disk:

```
my-bw5-app/
├── pom.xml
├── .folder
├── defaultVars/
│   └── defaultVars.substvar
├── SharedResources/
│   ├── Connections/
│   └── Schemas/
└── Services/
    ├── StartProcess.process
    └── SubProcess.process
```

If you prefer the Maven source layout, set `bwProjectPath`:

```xml
<configuration>
    <bwProjectPath>${basedir}/src/main/bw</bwProjectPath>
</configuration>
```

---

## Goals Reference

### bw5:init

Generates an initial `pom.xml` for an existing BW5 project or library directory. Run it **once per project** after the first checkout, before running any other Maven goal.

**Detection logic:**

| Found in project dir | Packaging |
|----------------------|-----------|
| `*.archive` file | `bwear` |
| `*.libbuilder` file (root or `Library/` subdir) | `projlib` |
| Neither | Error |

**What it produces:**

- `pom.xml` with the correct `<packaging>`, Maven coordinates, plugin block
- Commented-out `<dependency>` stubs for each entry in `.designtimelibs` (if present), with `TODO` placeholders for the groupId and version

```bash
# Minimum — groupId is required, everything else is auto-detected
mvn com.tibco.bw:bw5-maven-plugin:1.0.0-SNAPSHOT:init -DgroupId=com.example

# Override artifact coordinates
mvn com.tibco.bw:bw5-maven-plugin:1.0.0-SNAPSHOT:init \
    -DgroupId=com.example \
    -DartifactId=my-service \
    -Dversion=2.0.0-SNAPSHOT

# Overwrite an existing pom.xml
mvn com.tibco.bw:bw5-maven-plugin:1.0.0-SNAPSHOT:init \
    -DgroupId=com.example \
    -Dbw5.init.force=true
```

**Example output for an EAR project:**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project ...>
    <groupId>com.example</groupId>
    <artifactId>myapp</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>bwear</packaging>

    <!--
        Projlib dependencies detected from .designtimelibs.
        Replace the TODO placeholders with real Maven coordinates,
        then run 'mvn bw5:designer-setup' to stage them for Designer.
    -->
    <!--
    <dependencies>
        <dependency>
            <groupId>TODO</groupId>
            <artifactId>FrameworkCommon</artifactId>
            <version>TODO</version>
            <type>projlib</type>
        </dependency>
    </dependencies>
    -->

    <build>
        <plugins>
            <plugin>
                <groupId>com.tibco.bw</groupId>
                <artifactId>bw5-maven-plugin</artifactId>
                <version>1.0.0-SNAPSHOT</version>
                <extensions>true</extensions>
            </plugin>
        </plugins>
    </build>
</project>
```

**Key parameters:**

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `groupId` | `groupId` | *(required)* | Maven groupId |
| `artifactId` | `artifactId` | auto-detected from descriptor | Maven artifactId |
| `version` | `version` | `1.0.0-SNAPSHOT` | Maven version |
| `projectDir` | `bw5.init.projectDir` | `${basedir}` | BW5 project directory to scan |
| `force` | `bw5.init.force` | `false` | Overwrite existing `pom.xml` |

**Typical workflow after `bw5:init`:**

```bash
# 1. Generate pom.xml
mvn com.tibco.bw:bw5-maven-plugin:1.0.0-SNAPSHOT:init -DgroupId=com.example

# 2. Fill in real Maven coordinates in the commented-out <dependencies>
#    (edit pom.xml manually)

# 3. Stage dependencies for Designer
mvn bw5:designer-setup

# 4. Build
mvn package
```

---

### bw5:bwear

Assembles the BW5 Enterprise Archive. Runs automatically during `mvn package` for `bwear` projects.

**What it produces:**

```
target/
├── my-app-1.0.0-SNAPSHOT.ear         ← EAR (PAR + SAR + TIBCO.xml)
├── my-app-1.0.0-SNAPSHOT-deploy.xml  ← AppManage XML for TIBCO Administrator
├── my-app-1.0.0-SNAPSHOT-deploy.properties  ← Flat key=value
└── values.yaml                        ← Helm values for Kubernetes deployments
```

**EAR structure:**

```
my-app-1.0.0-SNAPSHOT.ear
├── TIBCO.xml                 ← EAR descriptor (FileAliases, GlobalVars, Modules)
├── Process Archive.par       ← Process Archive
│   ├── TIBCO.xml             ← PAR descriptor (BwBPConfigurations, EXTERNAL_DEPENDENCIES)
│   └── **/*.process
└── Shared Archive.sar        ← Shared resources (connections, schemas, variables)
```

**Key parameters:**

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `bwProjectPath` | `bw5.bwProjectPath` | `${basedir}` | Root of BW project sources |
| `archiveName` | `bw5.archiveName` | `${project.artifactId}` | PAR name within EAR |
| `sharedArchiveName` | `bw5.sharedArchiveName` | `Shared Archive` | SAR name |
| `includeSharedArchive` | `bw5.includeSharedArchive` | `true` | Include SAR in EAR |
| `earOnly` | `bw5.earOnly` | `false` | Skip deploy config generation |
| `generateDeployXml` | `bw5.generateDeployXml` | `true` | Generate `-deploy.xml` |
| `generateProperties` | `bw5.generateProperties` | `true` | Generate `-deploy.properties` |
| `generateValuesYaml` | `bw5.generateValuesYaml` | `true` | Generate `values.yaml` |
| `archiveDescriptorFile` | `bw5.archiveDescriptorFile` | — | TIBCO `.archive` descriptor |
| `globalPropertiesFile` | `bw5.deployConfig.globalPropertiesFile` | — | Global property overrides |
| `projectPropertiesFile` | `bw5.deployConfig.projectPropertiesFile` | — | Project-specific overrides |
| `skip` | `bw5.skip` | `false` | Skip goal |

**Examples:**

```bash
# Standard build
mvn package

# EAR only, no deploy configs
mvn package -Dbw5.earOnly=true

# Build with environment-specific properties
mvn package -Dbw5.deployConfig.globalPropertiesFile=/etc/bw5/prod.properties

# Override individual variables
mvn package -Dbw5.project.JmsHost=mq.prod.example.com
```

---

### bw5:bw5module

Assembles the BW5 projlib archive. Runs automatically during `mvn package` for `projlib` projects.

**What it produces:**

```
target/my-lib-1.0.0-SNAPSHOT.projlib
```

The projlib is a ZIP archive containing all BW project files with a `library.manifest` entry containing Maven GAV metadata. When a `.libbuilder` descriptor is present, only the resources listed in it are included — matching TIBCO Designer's `buildlibrary` behaviour exactly.

**Key parameters:**

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `bwProjectPath` | `bw5.bwProjectPath` | `${basedir}` | Root of BW project sources |
| `libBuilderFile` | `bw5.libBuilderFile` | auto-detect | TIBCO `.libbuilder` descriptor |
| `skip` | `bw5.skip` | `false` | Skip goal |

---

### bw5:deploy-config

Generates deployment configuration files from the project's substitution variables, applying environment-specific overrides. Use this when you need to regenerate configs without rebuilding the EAR (the same logic also runs automatically inside `bw5:bwear`).

```bash
# Regenerate configs with production properties
mvn bw5:deploy-config -Dbw5.deployConfig.projectPropertiesFile=config/prod.properties

# Override a single variable
mvn bw5:deploy-config -Dbw5.project.AppQueueName=PROD.ORDERS.IN

# Update .substvar source files with merged values
mvn bw5:deploy-config -Dbw5.deployConfig.updateSubstVarFiles=true \
    -Dbw5.deployConfig.projectPropertiesFile=config/uat.properties
```

See [Property Override Model](#property-override-model) for full details.

---

### bw5:designer-setup

Downloads and stages projlib/JAR dependencies for use with TIBCO Designer. Run once after checkout (or whenever dependencies change) before opening the project in Designer.

```bash
# Stage dependencies for Designer
mvn bw5:designer-setup

# Stage and open Designer
mvn bw5:designer-setup -Dbw5.designerSetup.launchDesigner=true

# Force re-copy of all dependencies
mvn bw5:designer-setup -Dbw5.designerSetup.force=true
```

**What it does:**

1. Resolves all `projlib` and `jar` dependencies from the Maven repository
2. Copies them to `${basedir}/.designer-libs/` (add to `.gitignore`)
3. Updates `.designtimelibs` in the BW project with entries in TIBCO Designer format
4. Optionally launches TIBCO Designer on the project directory

**Key parameters:**

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `designerLibsDir` | `bw5.designerLibsDir` | `${basedir}/.designer-libs` | Staging directory |
| `force` | `bw5.designerSetup.force` | `false` | Force re-copy of all deps |
| `launchDesigner` | `bw5.designerSetup.launchDesigner` | `false` | Open Designer after staging |
| `tibcoHome` | `bw5.tibcoHome` | `$TIBCO_HOME` | TIBCO installation root |

**Add to `.gitignore`:**

```
.designer-libs/
```

---

### bw5:run

Starts a BW5 engine locally for developer testing. Requires a local TIBCO installation.

Configure `tibco.Home` in `~/.m2/settings.xml` to avoid polluting the project POM:

```xml
<!-- ~/.m2/settings.xml -->
<profiles>
    <profile>
        <id>tibco-local</id>
        <activation><activeByDefault>true</activeByDefault></activation>
        <properties>
            <tibco.Home>/opt/tibco</tibco.Home>
            <bw5.bwVersion>5.13.0</bw5.bwVersion>
        </properties>
    </profile>
</profiles>
```

```bash
# Build and run (foreground — blocks until engine exits)
mvn package bw5:run

# Run in background (Maven returns after startup marker detected)
mvn package bw5:run -Dbw5.run.background=true

# Run pre-built EAR
mvn bw5:run -Dbw5.run.earFile=target/my-app-1.0.0-SNAPSHOT.ear
```

**Key parameters:**

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `tibcoHome` | `tibco.Home` | *(required)* | TIBCO installation root |
| `bwVersion` | `bw5.bwVersion` | `5.13.0` | BW5 version string |
| `earFile` | `bw5.run.earFile` | `target/<finalName>.ear` | EAR to run |
| `background` | `bw5.run.background` | `false` | Run as background process |
| `startupWaitSeconds` | `bw5.run.startupWaitSeconds` | `30` | Wait time for background start |

---

### bw5:site

Generates HTML documentation from BW process definitions. Integrates with `mvn site`.

```bash
mvn site           # Full Maven site (includes process docs, test results, PMD, SpotBugs)
mvn bw5:site       # Process docs only, standalone
```

**Output** (`target/site/bw5/`):
- **Index page** — project overview, process list, dependency table
- **Per-process pages** — SVG diagram, activity table, transition table, data mapping table

---

## Dependency Management

Declare projlib dependencies like any Maven artifact using `<type>projlib</type>`:

```xml
<dependencies>
    <!-- BW5 library dependency -->
    <dependency>
        <groupId>com.example</groupId>
        <artifactId>common-framework</artifactId>
        <version>2.1.0</version>
        <type>projlib</type>
    </dependency>

    <!-- JAR dependency (usable in Java Code activities) -->
    <dependency>
        <groupId>org.apache.commons</groupId>
        <artifactId>commons-lang3</artifactId>
        <version>3.12.0</version>
    </dependency>
</dependencies>
```

Both projlibs and JARs are registered as `FileAliases` in `TIBCO.xml` — they are **never bundled** inside the EAR (matching standard BW5 deployment behaviour).

**Publish a projlib:**

```bash
# Publish to local repo
mvn install

# Publish to remote repo (Nexus/Artifactory)
mvn deploy
```

---

## Property Override Model

The plugin implements a **two-level property override model** for environment-specific configuration. This applies to both `bw5:bwear` (during `mvn package`) and `bw5:deploy-config` (standalone).

### Merge priority (lowest → highest)

1. `.substvar` default values
2. Global properties file (`bw5.deployConfig.globalPropertiesFile`)
3. Maven properties prefixed `bw5.global.*`
4. Project properties file (`bw5.deployConfig.projectPropertiesFile`)
5. Maven properties prefixed `bw5.project.*`

### Configuration

```xml
<!-- pom.xml — project-level override file -->
<plugin>
    <groupId>com.tibco.bw</groupId>
    <artifactId>bw5-maven-plugin</artifactId>
    <configuration>
        <globalPropertiesFile>${project.basedir}/../config/global.properties</globalPropertiesFile>
        <projectPropertiesFile>${project.basedir}/config/project.properties</projectPropertiesFile>
    </configuration>
</plugin>
```

### Properties file format

Standard Java `.properties` format. Keys must match variable names in `.substvar` files:

```properties
# config/prod.properties
JmsProviderUrl=tcp://mq.prod.example.com:7222
JmsProviderUrl_backup=tcp://mq2.prod.example.com:7222
DbConnectionString=jdbc:oracle:thin:@prod-db:1521/MYDB
```

### CI/CD pipeline example

```yaml
# .github/workflows/build.yml
- name: Build EAR for production
  run: |
    mvn package \
      -Dbw5.deployConfig.globalPropertiesFile=${{ runner.temp }}/global-prod.properties \
      -Dbw5.project.AppVersion=${{ github.ref_name }}
```

### Write merged values back to .substvar

```bash
# Patch source .substvar files with environment values (for Docker image bake)
mvn bw5:deploy-config \
    -Dbw5.deployConfig.updateSubstVarFiles=true \
    -Dbw5.deployConfig.projectPropertiesFile=config/prod.properties
```

---

## Archive Descriptor Support

If your project uses a TIBCO Designer `.archive` file, the plugin can use it to determine which processes to include in the PAR and to read PAR/SAR names:

```xml
<configuration>
    <archiveDescriptorFile>${basedir}/MyApp.archive</archiveDescriptorFile>
</configuration>
```

When provided:
- Only processes listed in `processArchive/processProperty` are included
- PAR name is read from `processArchive/@name`
- SAR name is read from `sharedArchive/@name`

Without it, all `.process` files in the project are included.

---

## LibBuilder Descriptor Support

For projlib projects, the plugin auto-detects the TIBCO Designer `.libbuilder` file under the `Library/` subdirectory. When found, only the resources listed in its `<resources>` element are included — matching `buildlibrary` output exactly.

```
my-lib/
├── pom.xml
├── Library/
│   └── MyLibrary.libbuilder   ← auto-detected
├── SharedResources/
└── Services/
```

To specify the file explicitly:

```xml
<configuration>
    <libBuilderFile>${basedir}/Library/MyLibrary.libbuilder</libBuilderFile>
</configuration>
```

Without a `.libbuilder`, all BW project files are included (except system exclusions: `AESchemas/`, `vcrepo.dat`, `.designtimelibs`).

---

## Static Analysis & Vulnerability Scanning

The plugin's own build uses PMD and SpotBugs. You can integrate the same checks into your BW5 application projects.

### Run static analysis on the plugin

```bash
mvn verify          # compiles + tests + PMD + SpotBugs
mvn site            # generates HTML reports for all checks
```

### OWASP Dependency Check (vulnerability scanning)

Disabled by default to keep local builds fast. Enable on demand:

```bash
# Scan dependencies for known CVEs (downloads NVD database ~first run)
mvn verify -Dodc.skip=false

# With NVD API key for faster downloads (free at nvd.nist.gov)
mvn verify -Dodc.skip=false -Dnvd.api.key=<your-key>
```

Reports are written to `target/dependency-check-report.html` and `.json`. The build fails if any dependency has a CVSS score ≥ 7 (High severity).

### Integrate in CI

```yaml
# Run vulnerability scan in CI (not on every local build)
- name: Security scan
  run: mvn verify -Dodc.skip=false -Dnvd.api.key=${{ secrets.NVD_API_KEY }}
```

---

## Configuration Reference

### Full pom.xml example

```xml
<plugin>
    <groupId>com.tibco.bw</groupId>
    <artifactId>bw5-maven-plugin</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <extensions>true</extensions>
    <configuration>
        <!-- BW project location (default: ${basedir}) -->
        <bwProjectPath>${basedir}</bwProjectPath>

        <!-- PAR name inside the EAR -->
        <archiveName>${project.artifactId}</archiveName>

        <!-- SAR name inside the EAR -->
        <sharedArchiveName>Shared Archive</sharedArchiveName>

        <!-- Optional: TIBCO .archive descriptor for selective process inclusion -->
        <!-- <archiveDescriptorFile>${basedir}/MyApp.archive</archiveDescriptorFile> -->

        <!-- Optional: TIBCO .libbuilder descriptor (projlib only, auto-detected) -->
        <!-- <libBuilderFile>${basedir}/Library/MyLib.libbuilder</libBuilderFile> -->

        <!-- Deploy config generation (all true by default) -->
        <generateDeployXml>true</generateDeployXml>
        <generateProperties>true</generateProperties>
        <generateValuesYaml>true</generateValuesYaml>

        <!-- Property override files (optional) -->
        <!-- <globalPropertiesFile>${project.basedir}/../config/global.properties</globalPropertiesFile> -->
        <!-- <projectPropertiesFile>${project.basedir}/config/project.properties</projectPropertiesFile> -->

        <!-- Skip all goals -->
        <skip>false</skip>
    </configuration>
</plugin>
```

### Common command-line flags

| Flag | Description |
|------|-------------|
| `-Dbw5.skip=true` | Skip all plugin goals |
| `-Dbw5.earOnly=true` | Assemble EAR only, no deploy configs |
| `-Dbw5.generateValuesYaml=false` | Skip `values.yaml` generation |
| `-Dbw5.deployConfig.globalPropertiesFile=<path>` | Global property overrides |
| `-Dbw5.deployConfig.projectPropertiesFile=<path>` | Project property overrides |
| `-Dbw5.global.<name>=<value>` | Override individual global variable |
| `-Dbw5.project.<name>=<value>` | Override individual project variable |
| `-Dbw5.archiveName=<name>` | Override PAR name |
| `-Dbw5.bwProjectPath=<path>` | Override BW project path |
| `-Dodc.skip=false` | Enable OWASP vulnerability scan |

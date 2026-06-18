# BW5 Maven Plugin — Goals Reference

User-facing reference for all plugin goals. Describes what each goal does, the parameters it accepts, and how to invoke it.

---

## Table of Contents

| Goal | Summary | Default phase |
|---|---|---|
| [`init`](#init) | Generates a `pom.xml` for an existing BW5 project | _(standalone)_ |
| [`bwear`](#bwear) | Packages the project as a deployable EAR | `package` |
| [`bw5module`](#bw5module) | Packages a library as a projlib | `package` |
| [`validate`](#validate) | Validates project structure and integrity | `verify` |
| [`deploy-config`](#deploy-config) | Generates environment-specific deployment configuration files | `generate-resources` |
| [`site`](#site) | Generates an HTML documentation site | `site` |
| [`run`](#run) | Runs the BW5 application locally | _(standalone)_ |
| [`designer-setup`](#designer-setup) | Syncs Maven dependencies for TIBCO Designer | _(standalone)_ |
| [Build lifecycle goals](#build-lifecycle-goals) | Internal goals invoked automatically by Maven | various |

---

## `init`

Generates an initial `pom.xml` for an existing BW5 project. It inspects the project folder for TIBCO Designer descriptor files (`.archive` or `.libbuilder`) to automatically determine the packaging type and seed the Maven coordinates.

> **When to use:** The first time you bring a BW5 project under Maven. Run it once per project.

### Parameters

| Parameter | Maven property | Default | Description |
|---|---|---|---|
| `projectDir` | `bw5.init.projectDir` | `${basedir}` | Root directory of the BW5 project |
| `groupId` | `groupId` | `com.tibco` | Maven groupId for the generated project |
| `artifactId` | `artifactId` | _(from descriptor)_ | Maven artifactId. If omitted, derived from the `.archive` or `.libbuilder` name |
| `version` | `version` | `1.0.0-SNAPSHOT` | Maven version for the generated project |
| `force` | `bw5.init.force` | `false` | If `true`, overwrites an existing `pom.xml`. See warning below. |

### Examples

```bash
# Generate pom.xml in the current directory (EAR project)
mvn com.tibco.bw:bw5-maven-plugin:init \
  -DgroupId=com.mycompany.bw \
  -DartifactId=my-main-process \
  -Dversion=2.1.0-SNAPSHOT

# Generate pom.xml for a project in another folder
mvn com.tibco.bw:bw5-maven-plugin:init \
  -Dbw5.init.projectDir=/path/to/bw5-project \
  -DgroupId=com.mycompany.bw

# Regenerate an existing pom.xml — re-supply ALL coordinates explicitly
mvn com.tibco.bw:bw5-maven-plugin:init \
  -Dbw5.init.force=true \
  -DgroupId=com.mycompany.bw \
  -DartifactId=my-main-process \
  -Dversion=2.1.0-SNAPSHOT
```

> **Warning — `force=true` rewrites from scratch.** This goal is a one-shot scaffolding tool. When `force=true` is used the existing `pom.xml` is **not read** — its current values are discarded. Any coordinate not explicitly supplied via `-D` is re-derived from the descriptor file name (`artifactId`) or reset to its default (`version` → `1.0.0-SNAPSHOT`). Always re-supply every value you previously overrode, or edit the `pom.xml` directly instead.

The plugin auto-detects the project type:
- `.archive` found → generates `pom.xml` with `packaging = bwear`
- `.libbuilder` found → generates `pom.xml` with `packaging = projlib`
- `.designtimelibs` found → includes the discovered dependencies as commented stubs in the `pom.xml` for review

---

## `bwear`

Packages the BW5 project as an EAR (Enterprise Archive) file ready to deploy to a TIBCO BusinessWorks domain. **No TIBCO installation required.**

The EAR contains:
- **PAR** (Process Archive): `.process` files and the `TIBCO.xml` descriptor
- **SAR** (Shared Archive): shared resources such as schemas, connections, etc.
- Optional deployment configuration files (XML, properties, YAML)

> **When to use:** To compile and package the project for delivery or deployment. Normally invoked via `mvn package`.

### Parameters

| Parameter | Maven property | Default | Description |
|---|---|---|---|
| `bwProjectPath` | `bw5.bwProjectPath` | `${basedir}` | Root directory of the BW5 sources |
| `archiveName` | `bw5.archiveName` | `${project.artifactId}` | Name of the PAR inside the EAR |
| `archiveDescriptorFile` | `bw5.archiveDescriptorFile` | _(auto-detected)_ | Path to the Designer `.archive` file. Controls which processes are included |
| `includeSharedArchive` | `bw5.includeSharedArchive` | `true` | If `false`, the SAR is not included in the EAR |
| `sharedArchiveName` | `bw5.sharedArchiveName` | `Shared Archive` | Name of the SAR inside the EAR |
| `generateDeployXml` | `bw5.generateDeployXml` | `true` | Generates an AppManage-compatible deployment XML file |
| `generateProperties` | `bw5.generateProperties` | `true` | Generates a flat `.properties` deployment file |
| `generateValuesYaml` | `bw5.generateValuesYaml` | `true` | Generates a `values.yaml` file for Helm |
| `globalPropertiesFile` | `bw5.deployConfig.globalPropertiesFile` | — | `.properties` file with global variable overrides applied to all modules in the build |
| `projectPropertiesFile` | `bw5.deployConfig.projectPropertiesFile` | — | `.properties` file with overrides specific to this project |
| `skipResolveDependencies` | `bw5.bwear.skipResolveDependencies` | `false` | If `true`, skips copying dependencies to the build directory |
| `earOnly` | `bw5.earOnly` | `false` | If `true`, only assembles the EAR without generating any deployment configuration files |
| `oldJavaCustomFunctions` | `bw5.oldJavaCustomFunctions` | `false` | If `true`, uses the `.javaxpath` bytecode already embedded in the source files instead of freshly compiled classes |
| `skip` | `bw5.skip` | `false` | If `true`, skips the goal entirely |

### Examples

```bash
# Standard build: produces the EAR and all deployment configuration files
mvn package

# Build with environment-specific global variable overrides for staging
mvn package \
  -Dbw5.deployConfig.globalPropertiesFile=config/global-staging.properties \
  -Dbw5.deployConfig.projectPropertiesFile=config/my-process-staging.properties

# EAR only, no deployment configuration files
mvn package -Dbw5.earOnly=true

# Skip the plugin entirely (e.g. when only installing dependencies)
mvn install -Dbw5.skip=true
```

**Sample `global-staging.properties`:**
```properties
# Format: global_variable_name = value
DB_HOST=db-staging.mycompany.com
DB_PORT=1521
MAX_RETRIES=3
```

---

## `bw5module`

Packages a BW5 library project as a `.projlib` (Project Library) file. A projlib is a ZIP archive containing processes, schemas, and shared resources that other BW5 projects can consume as a Maven dependency.

> **When to use:** When building a reusable BW5 shared library. Normally invoked via `mvn package` in projects with `packaging = projlib`.

### Parameters

| Parameter | Maven property | Default | Description |
|---|---|---|---|
| `bwProjectPath` | `bw5.bwProjectPath` | `${basedir}` | Root directory of the BW5 sources |
| `libBuilderFile` | `bw5.libBuilderFile` | _(auto-detected)_ | Path to the `.libbuilder` descriptor. When present, only the resources that TIBCO Designer would include are packaged |
| `skip` | `bw5.skip` | `false` | If `true`, skips the goal entirely |

### Examples

```bash
# Standard library build
mvn package

# Explicitly specify the libbuilder descriptor
mvn package -Dbw5.libBuilderFile=Library/my-library.libbuilder
```

Install the projlib to the local Maven repository with `mvn install`, then declare it as a dependency from any EAR project:

```xml
<!-- In the EAR project's pom.xml -->
<dependency>
  <groupId>com.mycompany.bw</groupId>
  <artifactId>my-common-library</artifactId>
  <version>1.5.0</version>
  <type>projlib</type>
</dependency>
```

---

## `validate`

Validates the structure and integrity of a BW5 project without requiring TIBCO to be installed. Catches problems before packaging or deployment.

**What it checks:**
- XML well-formedness of all `.process`, `.substvar`, `.archive`, and `.aliaslib` files
- That every process declared in the `.archive` descriptor actually exists on disk
- Valid process names and absence of duplicates
- Global variables: referenced but not defined, and (optionally) defined but never referenced
- Maven dependencies resolved — projlibs and JARs present in the repository
- XPath expression syntax in activity mappings and transition conditions

> **When to use:** As a quality gate in CI/CD pipelines before packaging, or locally before committing changes.

### Parameters

| Parameter | Maven property | Default | Description |
|---|---|---|---|
| `failOnError` | `bw5.validate.failOnError` | `false` | If `true`, fails the build when errors are found |
| `failOnWarning` | `bw5.validate.failOnWarning` | `false` | If `true`, fails the build when warnings are present |
| `showUnusedGVars` | `bw5.validate.showUnusedGVars` | `false` | Reports global variables that are defined in `.substvar` files but never referenced |
| `skipXPath` | `bw5.validate.skipXPath` | `false` | Skips XPath expression syntax checking |
| `skipResolveDependencies` | `bw5.validate.skipResolveDependencies` | `false` | Skips resolving dependencies before validation |
| `skip` | `bw5.skip` | `false` | If `true`, skips the goal entirely |

### Examples

```bash
# Informational validation — reports issues without failing the build
mvn verify

# Strict validation for CI: fail on any error or warning
mvn verify \
  -Dbw5.validate.failOnError=true \
  -Dbw5.validate.failOnWarning=true \
  -Dbw5.validate.showUnusedGVars=true

# Quick validation without XPath checking (faster)
mvn verify -Dbw5.validate.skipXPath=true
```

---

## `deploy-config`

Generates or refreshes deployment configuration files by applying environment-specific global variable overrides. Implements a two-level override model: global (applies to every module in the build) and per-project.

**Files generated** (under `target/`):
- `deploy.xml` — compatible with TIBCO AppManage
- `deploy.properties` — flat key=value format
- `values.yaml` — for Helm / Kubernetes deployments

> **When to use:** To regenerate deployment configuration for a specific environment without rebuilding the entire EAR, or as an automated pre-deployment step.

### Parameters

| Parameter | Maven property | Default | Description |
|---|---|---|---|
| `globalPropertiesFile` | `bw5.deployConfig.globalPropertiesFile` | — | `.properties` file with overrides applied to all modules in the build |
| `projectPropertiesFile` | `bw5.deployConfig.projectPropertiesFile` | — | `.properties` file with overrides specific to this project |
| `updateSubstVarFiles` | `bw5.deployConfig.updateSubstVarFiles` | `false` | If `true`, writes the merged values back into the project's `.substvar` source files |
| `generateDeployXml` | `bw5.deployConfig.generateDeployXml` | `true` | Generates `deploy.xml` |
| `generateProperties` | `bw5.deployConfig.generateProperties` | `true` | Generates `deploy.properties` |
| `generateValuesYaml` | `bw5.deployConfig.generateValuesYaml` | `true` | Generates `values.yaml` |
| `skip` | `bw5.skip` | `false` | If `true`, skips the goal entirely |

### Examples

```bash
# Generate configuration for the production environment
mvn generate-resources \
  -Dbw5.deployConfig.globalPropertiesFile=envs/prod/global.properties \
  -Dbw5.deployConfig.projectPropertiesFile=envs/prod/my-process.properties

# Generate only the Helm YAML (skip XML and properties)
mvn generate-resources \
  -Dbw5.deployConfig.globalPropertiesFile=envs/k8s/global.properties \
  -Dbw5.deployConfig.generateDeployXml=false \
  -Dbw5.deployConfig.generateProperties=false
```

**Override priority** (highest to lowest):
1. `-D` properties passed on the command line
2. `projectPropertiesFile` (module-specific)
3. `globalPropertiesFile` (build-wide)
4. Default values in the project's `.substvar` files

---

## `site`

Generates a self-contained HTML documentation site for the BW5 project under `target/site/bw5/`. No TIBCO installation required.

**What it generates:**
- **`index.html`** — project overview: statistics, detected plugins, process list, projlib dependencies
- **One page per process** — interactive SVG flow diagram, activity table, transition table with conditions, and a 3-column data mapping visualizer (Source → Target → Value/Expression)

> **When to use:** To generate technical documentation for the project, as a build artifact in CI/CD, or to onboard new team members.

### Parameters

| Parameter | Maven property | Default | Description |
|---|---|---|---|
| `siteOutputDir` | `bw5.siteOutputDir` | `${project.build.directory}/site/bw5` | Output directory for the generated HTML documentation |
| `bwProjectPath` | `bw5.bwProjectPath` | `${basedir}` | Root directory of the BW5 sources |
| `skip` | `bw5.skip` | `false` | If `true`, skips the goal entirely |

### Examples

```bash
# Generate the documentation site
mvn site

# Generate to a custom directory
mvn site -Dbw5.siteOutputDir=/var/www/docs/my-project

# Invoke directly without running the full build lifecycle
mvn com.tibco.bw:bw5-maven-plugin:site
```

Open the documentation in a browser:
```bash
xdg-open target/site/bw5/index.html   # Linux
open target/site/bw5/index.html        # macOS
start target/site/bw5/index.html       # Windows
```

---

## `run`

Runs the BW5 application locally using the BW engine installed on the machine. Automatically generates a `bwengine.properties` file with the correct dependency aliases derived from the Maven project.

> **When to use:** For local testing or iterative development. **Requires TIBCO BusinessWorks 5.x installed locally.**

### Parameters

| Parameter | Maven property | Default | Required | Description |
|---|---|---|---|---|
| `tibcoHome` | `tibco.Home` | — | Yes | TIBCO installation root directory (e.g. `/opt/tibco`) |
| `bwVersion` | `bw5.bwVersion` | `5.13.0` | — | Installed BusinessWorks 5.x version string |
| `background` | `bw5.run.background` | `false` | — | If `true`, starts the engine as a background process and Maven returns immediately |
| `startupWaitSeconds` | `bw5.run.startupWaitSeconds` | `30` | — | Seconds to wait for the engine to start before reporting an error (background mode only) |
| `domainHome` | `bw5.run.domainHome` | — | — | Path to a BW domain home directory. Passed to the engine via the `-d` flag |
| `workingDir` | `bw5.run.workingDir` | `${project.build.directory}` | — | Working directory for the engine process |
| `propertiesFile` | `bw5.run.propertiesFile` | — | — | Additional `.properties` file whose entries override the auto-generated `bwengine.properties` |
| `extraArgs` | — | — | — | Additional arguments appended verbatim to the `bwengine` command line |
| `skip` | `bw5.skip` | `false` | — | If `true`, skips the goal entirely |

### Examples

```bash
# Run in the foreground (Maven blocks until the engine stops)
mvn com.tibco.bw:bw5-maven-plugin:run \
  -Dtibco.Home=/opt/tibco \
  -Dbw5.bwVersion=5.13.0

# Run in the background (Windows path)
mvn com.tibco.bw:bw5-maven-plugin:run \
  -Dtibco.Home="C:/tibco" \
  -Dbw5.run.background=true \
  -Dbw5.run.startupWaitSeconds=60

# Run with additional local property overrides
mvn com.tibco.bw:bw5-maven-plugin:run \
  -Dtibco.Home=/opt/tibco \
  -Dbw5.run.propertiesFile=local-overrides.properties
```

**Sample `local-overrides.properties`:**
```properties
# Override engine aliases or properties for local development
Adapter.JDBC.ALIAS=jdbc:oracle:thin:@localhost:1521:XE
```

---

## `designer-setup`

Downloads and stages projlib and JAR dependencies into a local folder so TIBCO Designer can resolve them, and updates the project's `.designtimelibs` file accordingly. Optionally launches TIBCO Designer after syncing.

> **When to use:** When onboarding a new developer, or after adding or updating projlib dependencies and Designer stops resolving references. Only needs to be run once (or when dependencies change).

### Parameters

| Parameter | Maven property | Default | Description |
|---|---|---|---|
| `designerLibsDir` | `bw5.designerLibsDir` | `${basedir}/.designer-libs` | Folder where projlibs and JARs are copied for Designer |
| `designtimeLibsDir` | `bw5.designtimeLibsDir` | _(auto-detected)_ | Path to the BW source directory where the `.designtimelibs` file is updated |
| `force` | `bw5.designerSetup.force` | `false` | If `true`, always overwrites staged files even if they appear up to date |
| `launchDesigner` | `bw5.designerSetup.launchDesigner` | `false` | If `true`, launches TIBCO Designer after staging |
| `tibcoHome` | `bw5.tibcoHome` | — | TIBCO installation root directory. Required only when `launchDesigner=true` |
| `skip` | `bw5.skip` | `false` | If `true`, skips the goal entirely |

### Examples

```bash
# Sync dependencies for Designer
mvn com.tibco.bw:bw5-maven-plugin:designer-setup

# Sync and launch Designer immediately
mvn com.tibco.bw:bw5-maven-plugin:designer-setup \
  -Dbw5.designerSetup.launchDesigner=true \
  -Dbw5.tibcoHome=/opt/tibco

# Force a full re-sync (useful after version upgrades)
mvn com.tibco.bw:bw5-maven-plugin:designer-setup \
  -Dbw5.designerSetup.force=true
```

The plugin automatically adds `.designer-libs` to `.gitignore` so staged files are not committed to source control.

---

## Build lifecycle goals

These goals run automatically as part of the Maven lifecycle (`mvn package`, `mvn install`, etc.) and do not normally need to be invoked directly. They are documented here for reference.

### `initialize`

Prepares the build environment: validates that the BW5 project directory exists and creates the working directories under `target/` (`bw-src`, `bw-lib`, `generated-sources/bw-java`, `bw-classes`).

- **Phase:** `initialize`
- **Direct invocation:** `mvn initialize`

---

### `copy-bw-sources`

Copies the BW5 project sources from the source directory to `target/bw-src`. All subsequent packaging goals operate on this copy, ensuring that the original source files are never modified during the build.

- **Phase:** `generate-sources`
- **Direct invocation:** `mvn generate-sources`

---

### `resolve-dependencies`

Downloads `projlib` and `jar` dependencies from the Maven repository and copies them to `target/bw-lib/` using the naming convention `artifactId-version.projlib` / `artifactId-version.jar`, making them accessible to the BW engine during packaging.

- **Phase:** `process-resources`
- **Direct invocation:** `mvn process-resources`

---

### `extract-java-sources`

Extracts inline Java source code from Java Code activities embedded in `.process` files and writes them as `.java` files under `target/generated-sources/bw-java/`. The standard Maven compiler plugin then compiles them during the `compile` phase.

- **Phase:** `generate-sources`
- **Direct invocation:** `mvn generate-sources`

---

### `prepare-jcf-bytecode`

Reads the compiled bytecode of Java Custom Functions (`.javaxpath` files), Base64-encodes it, and stores it as Maven project properties for the `bwear` goal to embed directly into the EAR. This is a silent no-op when no `.javaxpath` files are present in the project.

- **Phase:** `process-classes`
- **Direct invocation:** `mvn process-classes`

---

## Global parameters

These parameters are inherited by every goal in the plugin.

| Parameter | Maven property | Default | Description |
|---|---|---|---|
| `bwProjectPath` | `bw5.bwProjectPath` | `${basedir}` | Root directory of the BW5 sources |
| `archiveName` | `bw5.archiveName` | `${project.artifactId}` | Name of the PAR inside the EAR |
| `skip` | `bw5.skip` | `false` | Disables all plugin goals when set to `true` |

---

## Configuring the plugin in `pom.xml`

Instead of passing parameters on the command line every time, set them permanently in the project's `pom.xml`:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>com.tibco.bw</groupId>
      <artifactId>bw5-maven-plugin</artifactId>
      <version>1.0.0-SNAPSHOT</version>
      <extensions>true</extensions>
      <configuration>
        <!-- BW5 source directory, if it differs from basedir -->
        <bwProjectPath>${basedir}/src/main/bw</bwProjectPath>

        <!-- Deployment configuration -->
        <globalPropertiesFile>${project.basedir}/config/global.properties</globalPropertiesFile>
        <generateValuesYaml>true</generateValuesYaml>

        <!-- Documentation site -->
        <siteOutputDir>${project.build.directory}/site/bw5</siteOutputDir>
      </configuration>
    </plugin>
  </plugins>
</build>
```

---

## Typical build flow

```
mvn init            →  Generate pom.xml (first time only)
        ↓
mvn package         →  initialize → copy-bw-sources → resolve-dependencies
                        → extract-java-sources → compile → prepare-jcf-bytecode
                        → bwear  (or bw5module for libraries)
        ↓
mvn verify          →  validate (checks structure and integrity)
        ↓
mvn install         →  Installs the EAR / projlib in the local repository
        ↓
mvn site            →  Generates the HTML documentation site
```

# Product Requirements Document
## BW5 Maven Plugin (`bw5-maven-plugin`)

**Version:** 1.2
**Status:** Draft — For Review by BW5 Product Management & Engineering
**Author:** TIBCO BW5 Community
**Date:** 2026-04-01

---

## 1. Executive Summary

The `bw5-maven-plugin` is a new Maven plugin for TIBCO BusinessWorks 5.x that enables full application lifecycle management — dependency management, compilation, packaging, documentation, and CI/CD integration — **without requiring any TIBCO tools to be installed on the build machine**.

This plugin fills a critical gap: the existing community plugin (`tibco-bwmaven`, FastConnect, 2011) requires a local TIBCO Designer installation and wraps proprietary binaries (`buildear`, `buildlibrary`, `appmanage`) that make it unusable in modern containerised CI/CD environments. The `bw5-maven-plugin` replaces those binary invocations with pure Java implementations, making BW5 projects first-class citizens in any Maven-based build pipeline.

The naming conventions, packaging types, and lifecycle structure are intentionally aligned with the official `bw6-plugin-maven` plugin, enabling teams that manage both BW5 and BW6 estates to use a consistent toolchain.

A companion `bw5-deploy-plugin` (separate artifact) covers runtime deployment across all supported BW5 environments: TIBCO Administrator (classic on-prem), BW5 Containers via Platform API, and TIBCO Control Tower via Platform API.

---

## 2. Background & Problem Statement

### 2.1 Current State of BW5 Build Tooling

TIBCO BusinessWorks 5.x projects are managed today either manually through TIBCO Designer or via the open-source `tibco-bwmaven` plugin (FastConnect, last significant update ~2014). Both approaches present serious limitations in enterprise CI/CD environments:

| Issue | Impact |
|---|---|
| `buildear` / `buildlibrary` binaries must be installed on every build agent | CI/CD agents require a full licensed TIBCO installation — costly and operationally complex |
| `mvn deploy` deploys to TIBCO Administrator (not Maven repository) | Breaks Maven lifecycle semantics; cannot co-exist with standard artifact promotion pipelines |
| Projlib dependencies stored as binary files in source control | No versioning, no reproducibility, no dependency graph |
| No standard documentation generation | Teams hand-craft process documentation in Word/Confluence |
| Java Code activities and Custom Functions compiled inside proprietary binary | Black box; compilation is untestable, unobservable, not IDE-friendly |
| Plugin last updated >10 years ago | Java 6 target, Maven 3.1 API, unsupported dependencies |
| No deployment parity across BW5 runtime environments | Administrator, containers, and future control plane each require bespoke scripting |

### 2.2 What Teams Need

- Build BW5 EARs on **any standard CI server** (Jenkins, GitHub Actions, GitLab CI) without TIBCO software installed
- Manage projlib dependencies via **Maven coordinates** (groupId:artifactId:version)
- `mvn install` / `mvn deploy` publishes to a **Maven repository** (Nexus, Artifactory), not a TIBCO domain
- Deploying to a TIBCO runtime should be an **explicit, separate step** using a dedicated deployment plugin
- **Uniform deployment interface** across classic Administrator, BW5 Containers, and future Control Tower
- Compile Java Code activities and Java Custom Functions with **standard JDK tooling**
- Generate **living documentation** from process definitions without manual effort

---

## 3. Goals

1. **Eliminate the TIBCO installation requirement** for building, packaging, and publishing BW5 artifacts.
2. **Restore Maven lifecycle semantics**: `mvn install` = local repo, `mvn deploy` = remote repo.
3. **Enable Maven-native dependency management** for projlib artifacts.
4. **Support Java Code activity compilation** using standard JDK tooling.
5. **Support Java Custom Function compilation** — compile, package, and register custom XPath functions.
6. **Full EAR assembly support**: single-PAR, multi-PAR, and Adapter Archive (AAR) projects.
7. **Generate professional process documentation** with realistic activity icons and SVG diagrams.
8. **Align packaging conventions with BW6** so multi-generation teams use one mental model.
9. **Preserve TIBCO Designer compatibility** via `mvn bw5:designer-setup`.
10. **Zero-friction project onboarding** via `mvn bw5:init` — generate a correct `pom.xml` from any existing BW5 project directory without manual setup.
11. **Provide deployment parity** across all BW5 runtime environments via a companion `bw5-deploy-plugin`.

## 4. Non-Goals

- This plugin does **not** wrap or extend TIBCO Designer. It requires no Designer installation.
- This plugin does **not** support TIBCO BusinessWorks 6.x or BWCE (use `bw6-plugin-maven` for those).
- The **build plugin** (`bw5-maven-plugin`) does not deploy to any TIBCO runtime — that is exclusively the role of the **deploy plugin** (`bw5-deploy-plugin`).
- `bw5:run` is a local development aid — it is not a substitute for the deploy plugin in CI/CD pipelines.

---

## 5. User Personas

### 5.1 BW5 Application Developer
Works in TIBCO Designer day-to-day. Needs Maven integration to manage projlib dependencies and open projects with Maven-managed libraries without manually copying files.

*Key pain point:* After checking out a project, projlib and JAR files needed by Designer are missing.

### 5.2 DevOps / Build Engineer
Owns the CI/CD pipeline. Needs to build and promote BW5 EAR artifacts without TIBCO software on build agents. Needs reproducible builds and artifact versioning.

*Key pain point:* Cannot run `buildear` in a container without a TIBCO license and installation.

### 5.3 Platform / Operations Engineer
Manages deployment of BW5 applications across on-prem Administrator domains, containerised BW5 environments, and (in future) Control Tower. Needs one consistent deployment interface regardless of the target runtime.

*Key pain point:* Three different runtimes require three completely different deployment procedures and scripts.

### 5.4 Software Architect / Tech Lead
Responsible for code quality and documentation. Needs auto-generated process documentation for architecture reviews, audit trails, and onboarding.

*Key pain point:* Process documentation is always out of date because it is maintained manually.

### 5.5 BW5 → BW6 Migration Lead
Managing a portfolio of BW5 and BW6 applications. Needs consistent toolchain conventions across both generations.

*Key pain point:* Two completely different build paradigms for BW5 and BW6.

---

## 6. Functional Requirements

### 6.1 Maven Packaging Types

| Packaging Type | File Extension | Description | BW6 Equivalent |
|---|---|---|---|
| `bwear` | `.ear` | BW5 application archive | `bwear` |
| `projlib` | `.projlib` | BW5 reusable library | `bwmodule` |

### 6.2 Maven Lifecycle Binding

**For `bwear` packaging:**

| Maven Phase | Goal |
|---|---|
| `initialize` | `bw5:initialize` — validates BW project structure, creates work directories |
| `generate-sources` | `bw5:copy-bw-sources` — copies BW project sources to `target/bw-src` |
| `generate-sources` | `bw5:extract-java-sources` — extracts Java Code activities and Custom Function sources |
| `process-resources` | `bw5:resolve-dependencies` — resolves projlib/JAR dependencies to `target/bw-lib` |
| `compile` | `maven-compiler-plugin:compile` — compiles extracted Java sources with standard JDK |
| `package` | `bw5:bwear` — assembles PAR(s) + SAR + TIBCO.xml into `.ear`; applies property overrides; generates `deploy.xml`, `deploy.properties`, `values.yaml` |
| `install` | Standard Maven install |
| `deploy` | Standard Maven deploy (to Maven repository) |

**For `projlib` packaging:**

| Maven Phase | Goal |
|---|---|
| `initialize` | `bw5:initialize` |
| `generate-sources` | `bw5:copy-bw-sources`, `bw5:extract-java-sources` |
| `process-resources` | `bw5:resolve-dependencies` |
| `compile` | `maven-compiler-plugin:compile` |
| `package` | `bw5:bw5module` — assembles `.projlib` archive |
| `install` | Standard Maven install |
| `deploy` | Standard Maven deploy |

### 6.3 EAR Assembly (`bw5:bwear`)

The goal shall produce a valid BW5 EAR without invoking `buildear`.

**Single-PAR EAR structure (default):**

```
<artifactId>-<version>.ear
├── TIBCO.xml                 ← Generated deployment descriptor
│                                FileAliases, Global Variables, Modules list
├── <archiveName>.par         ← Process Archive
│   ├── TIBCO.xml             ← PAR descriptor (BwBPConfigurations, EXTERNAL_DEPENDENCIES)
│   └── **/*.process          ← Process files, directory structure preserved
└── Shared Archive.sar        ← Shared resources (connections, schemas, variables, etc.)
```

**Multi-PAR EAR structure (when `<archives>` is configured):**

```
<artifactId>-<version>.ear
├── TIBCO.xml                 ← Lists all PAR modules
├── ServiceA.par              ← Processes in ServiceA group
├── ServiceB.par              ← Processes in ServiceB group
└── Shared Archive.sar
```

**Adapter EAR structure (when adapter archives are present):**

```
<artifactId>-<version>.ear
├── TIBCO.xml
├── <archiveName>.aar         ← Adapter Archive (instead of PAR for adapter-based apps)
└── Shared Archive.sar
```

**Requirements:**
- The PAR name defaults to `${project.artifactId}`, configurable via `<archiveName>`
- Multi-PAR projects configure process-to-archive assignments via `<archives>` plugin configuration (see §6.9)
- Adapter Archive projects are detected by the presence of `.aar` or adapter descriptors; the archive type is configurable
- Global variables are read from all `.substvar` files
- projlib and JAR dependencies are registered as FileAliases in TIBCO.xml (never bundled in the EAR)
- Compiled Java classes (Code activities and Custom Functions) are included in the PAR under `JavaCode/`

### 6.4 Projlib Assembly (`bw5:bw5module`)

**Requirements:**
- `.projlib` is a ZIP archive preserving the full BW project directory structure
- `/library.manifest` entry is included with Maven GAV metadata
- Compiled Java classes (Code activities and Custom Functions) included under `JavaCode/`
- Projlib files install to and resolve from any standard Maven repository

### 6.5 Java Code Activity Compilation

**Requirements:**
- Java source embedded in `.process` files (`com.tibco.plugin.java.JavaActivity`, `<config><code>`) is extracted to `target/generated-sources/bw-java`
- Extracted directory is registered as a Maven compile source root automatically
- Class name and package are read from `<className>`; imports from `<imports>`
- TIBCO palette JARs needed at compile time are declared as `provided`-scope dependencies

### 6.6 Java Custom Function Compilation

Custom Functions in BW5 are Java classes that extend the BW XPath function library. They are distinct from Java Code activities.

**Requirements:**
- Java sources for Custom Functions reside under `src/main/java/` (standard Maven source layout) and are compiled by `maven-compiler-plugin` without any special extraction step
- The plugin shall scan the compiled output for classes annotated with or implementing TIBCO's Custom Function contract (e.g., implementing `com.tibco.pe.core.api.PluginActivator` or annotated with palette metadata)
- Custom Function classes shall be packaged into a JAR that is:
  - Bundled inside the projlib or EAR under `CustomFunctions/` (so the BW engine can discover them at runtime)
  - Also attached as a classified Maven artifact (classifier `custom-functions`) for use as a compile-time dependency in other projects
- The `bw5:extract-java-sources` goal shall recognise Custom Function metadata files (`.customfunction` or equivalent descriptor) in the BW project and register the function signatures for validation
- Custom Function JARs can themselves depend on other JARs declared in the project pom.xml

**Example project structure for a projlib with Custom Functions:**

```
my-custom-functions-lib/
├── pom.xml                        ← <packaging>projlib</packaging>
├── src/
│   ├── main/
│   │   ├── bw/                    ← BW project (process definitions)
│   │   └── java/                  ← Custom Function implementations
│   │       └── com/example/
│   │           └── functions/
│   │               ├── StringFunctions.java
│   │               └── DateFunctions.java
│   └── test/
│       └── java/                  ← Unit tests for Custom Functions
```

### 6.7 Multi-PAR Projects (`bw5:bwear` with `<archives>`)

Some BW5 projects are logically divided into multiple services (PARs) within one EAR. The split is defined in plugin configuration rather than by a Designer archive descriptor file.

**Configuration:**

```xml
<plugin>
    <groupId>com.tibco.bw</groupId>
    <artifactId>bw5-maven-plugin</artifactId>
    <version>1.0.0</version>
    <extensions>true</extensions>
    <configuration>
        <archives>
            <archive>
                <name>OrderService</name>
                <includes>
                    <include>Services/Order/**</include>
                </includes>
            </archive>
            <archive>
                <name>PaymentService</name>
                <includes>
                    <include>Services/Payment/**</include>
                </includes>
            </archive>
        </archives>
        <!-- Processes not matched by any archive go to a default PAR named after the artifactId -->
        <defaultArchiveName>${project.artifactId}-common</defaultArchiveName>
    </configuration>
</plugin>
```

**Requirements:**
- Each `<archive>` element defines one PAR file in the EAR
- Process files are assigned to archives using Ant-style glob patterns on their path within the BW project
- A process not matching any explicit archive is assigned to the default archive
- Each PAR gets its own TIBCO.xml descriptor
- The EAR-level TIBCO.xml Modules section lists all PARs

### 6.8 Adapter Archive (AAR) Support (`bw5:bwear`)

Adapter-based BW5 applications produce `.aar` files (Adapter Archives) instead of or in addition to `.par` files.

**Requirements:**
- The plugin detects adapter archives in the BW project via adapter descriptor files
- `.aar` files are assembled using the same ZIP approach as PARs, with the appropriate internal structure for the adapter runtime
- The `<archiveType>` parameter controls whether the output is a PAR, AAR, or both
- Adapter-specific TIBCO.xml sections (adapter SDK properties) are generated correctly
- Mixed EARs (both PAR and AAR) are supported for projects that combine standard BW processes with adapter services

### 6.9 Dependency Management

**Requirements:**
- projlib: `<dependency ... <type>projlib</type>>` resolved from Maven repo
- Standard `jar` dependencies usable in Java Code activities and Custom Functions
- Transitive projlib dependencies resolved via standard Maven

### 6.10 Deployment Config Generation (`bw5:deploy-config`)

The `deploy-config` goal implements a **two-level property override model** that injects environment-specific values into a BW5 project's substitution variables and generates all deployment configuration files. It is a standalone goal for when configs need to be regenerated without rebuilding the EAR. The same logic also runs automatically inside `bw5:bwear` during `mvn package`.

#### Two-level model

| Level | Scope | Source |
|---|---|---|
| **Global** | All BW5 projects in a multi-module build | `bw5.deployConfig.globalPropertiesFile` or Maven properties prefixed `bw5.global.*` |
| **Project** | This Maven module only | `bw5.deployConfig.projectPropertiesFile` or Maven properties prefixed `bw5.project.*` |

#### Merge priority (lowest → highest)

1. `.substvar` default values (from source files)
2. Global properties file (`bw5.deployConfig.globalPropertiesFile`)
3. Maven properties with prefix `bw5.global.` (set in `<properties>` or `-D` CLI)
4. Project properties file (`bw5.deployConfig.projectPropertiesFile`)
5. Maven properties with prefix `bw5.project.` (highest priority)

#### Output

After merging, the goal regenerates all deployment configuration files with the merged values:
- `<finalName>-deploy.xml` — AppManage XML for TIBCO Administrator
- `<finalName>-deploy.properties` — flat key=value for BW5 containers
- `values.yaml` — Helm override values for BW5 Platform / Kubernetes

#### Optional substvar write-back

When `bw5.deployConfig.updateSubstVarFiles=true`, the goal writes the merged values back into the source `.substvar` files. Intended for dedicated environment-configuration pipelines, not standard builds.

#### Key parameters

| Parameter | Property | Default | Description |
|---|---|---|---|
| `globalPropertiesFile` | `bw5.deployConfig.globalPropertiesFile` | — | Path to global `.properties` file |
| `projectPropertiesFile` | `bw5.deployConfig.projectPropertiesFile` | — | Path to project `.properties` file |
| `updateSubstVarFiles` | `bw5.deployConfig.updateSubstVarFiles` | `false` | Write merged values back to `.substvar` |
| `generateDeployXml` | `bw5.deployConfig.generateDeployXml` | `true` | Generate AppManage XML |
| `generateProperties` | `bw5.deployConfig.generateProperties` | `true` | Generate `.properties` file |
| `generateValuesYaml` | `bw5.deployConfig.generateValuesYaml` | `true` | Generate `values.yaml` |

#### Integration with `bw5:bwear`

`bw5:bwear` incorporates the full deploy-config logic inline: it accepts the same `globalPropertiesFile` and `projectPropertiesFile` parameters and generates `deploy.xml`, `deploy.properties`, and `values.yaml` as part of every `mvn package`. Use `bw5:deploy-config` only when you need to regenerate config files without rebuilding the EAR, or when `updateSubstVarFiles=true` is needed.

#### Example

```xml
<!-- In a parent POM or profile — global infrastructure values -->
<properties>
  <bw5.global.JmsProviderUrl>tcp://mq.prod.example.com:7222</bw5.global.JmsProviderUrl>
  <bw5.global.AdminServer>admin.prod.example.com</bw5.global.AdminServer>
</properties>

<!-- In module POM — project-specific values -->
<plugin>
  <groupId>com.tibco.bw</groupId>
  <artifactId>bw5-maven-plugin</artifactId>
  <configuration>
    <globalPropertiesFile>${project.basedir}/../../config/global.properties</globalPropertiesFile>
    <projectPropertiesFile>${project.basedir}/config/project.properties</projectPropertiesFile>
  </configuration>
</plugin>
```

```bash
# Regenerate deploy configs for a specific environment without rebuilding the EAR
mvn bw5:deploy-config -Dbw5.project.AppQueueName=PROD.ORDERS.IN

# Full package with property merge (same result, EAR also rebuilt)
mvn package -Dbw5.deployConfig.globalPropertiesFile=/etc/bw5/global.properties
```

---

### 6.11 Local Execution (`bw5:run`)

The `run` goal starts a TIBCO BusinessWorks 5.x engine locally using the EAR produced by `bw5:bwear`. It is designed for **developer inner-loop testing** and local integration checks.

#### Engine location

The BW engine executable is resolved at:

```
<tibcoHome>/bw/<bwVersion>/bin/bwengine[.exe]
```

where `tibcoHome` and `bwVersion` are Maven properties configured in the developer's `settings.xml` so they do not pollute the project POM:

```xml
<!-- ~/.m2/settings.xml -->
<profiles>
  <profile>
    <id>tibco-local</id>
    <properties>
      <tibco.Home>/opt/tibco</tibco.Home>
      <bw5.bwVersion>5.13.0</bw5.bwVersion>
    </properties>
  </profile>
</profiles>
```

The `tibco.Home` property name is intentionally aligned with the BW6 plugin so a single `settings.xml` entry covers both generations.

#### Background mode

When `bw5.run.background=true`, Maven starts the engine as a background OS process and returns immediately after the startup marker is detected in the engine's stdout (or after `bw5.run.startupWaitSeconds` seconds, whichever comes first). A JVM shutdown hook ensures the engine process is killed when Maven exits.

When `bw5.run.background=false` (default), Maven blocks until the engine process exits, piping all output to the Maven log.

#### Key parameters

| Parameter | Property | Default | Description |
|---|---|---|---|
| `tibcoHome` | `tibco.Home` | *(required)* | TIBCO installation root |
| `bwVersion` | `bw5.bwVersion` | `5.13.0` | BW5 version string |
| `earFile` | `bw5.run.earFile` | `target/<finalName>.ear` | EAR file to run |
| `background` | `bw5.run.background` | `false` | Run engine in background |
| `startupWaitSeconds` | `bw5.run.startupWaitSeconds` | `30` | Seconds to wait for startup when background=true |
| `domainHome` | `bw5.run.domainHome` | — | Optional domain home directory (`-d` flag) |
| `extraArgs` | — | — | Additional `bwengine` arguments |
| `workingDir` | `bw5.run.workingDir` | `${project.build.directory}` | Engine working directory |

#### Usage

```bash
# Run in foreground (blocks until engine exits)
mvn bw5:run

# Run in background (Maven returns after startup)
mvn bw5:run -Dbw5.run.background=true

# Run with explicit TIBCO home
mvn bw5:run -Dtibco.Home=/opt/tibco -Dbw5.bwVersion=5.14.0

# Build and run in one command
mvn package bw5:run -Dbw5.run.background=true
```

---

### 6.12 Designer Alignment (`bw5:designer-setup`)

**Requirements:**
- Copies all `projlib` and `jar` dependencies to `${basedir}/.designer-libs/` (configurable)
- Updates `.designtimelibs` in format `N=groupId:artifactId:version:type\=`
- Idempotent: skips files already present with correct size; `force` flag overrides
- Auto-adds `.designer-libs/` to `.gitignore`
- Optionally launches TIBCO Designer on the project directory (`launchDesigner=true`); Designer executable located via `tibcoHome` parameter or `TIBCO_HOME` environment variable

### 6.13 Documentation Generation (`bw5:site`)

**Requirements:**

**Project overview page** shall include:
- Project metadata, statistics (processes, starters, activities, projlib deps)
- Process list with starter type, activity count, transition count
- Dependency table

**Per-process page** shall include:
- **SVG process diagram** using x/y coordinates from the `.process` XML:
  - Activity boxes rendered with **palette-specific icons** (see §6.11.1) at their native positions
  - Transitions as directed arrows, colour-coded and labelled by condition type (always / success / error / successWithCondition / otherwise)
  - Condition expressions on conditional transitions
- **Activity table**: name, type, configuration summary
- **Transition table**: from, to, condition type (badge), condition expression
- **Data mapping table**: target field, source XPath, literal flag, conditional flag + expression

#### 6.13.1 Activity Icon Registry

The `ActivityIconRegistry` class shall provide base64-encoded SVG icons for all standard BW5 palette activities. Icons are embedded directly in the SVG diagram as `<image>` elements (data URIs), requiring no external resources.

**Coverage (minimum for v1.0):**

| Category | Activities |
|---|---|
| **Timer** | TimerEventSource, SleepActivity |
| **HTTP** | HTTPEventSource, HttpRequestActivity, HTTPResponseActivity |
| **JMS** | JMSQueueSendActivity, JMSQueueGetActivity, JMSQueueEventSource, JMSTopicPublishActivity, JMSTopicSubscribeActivity |
| **JDBC** | JDBCQueryActivity, JDBCUpdateActivity, JDBCCallActivity, JDBCDirectUpdate |
| **Mail** | MailEventSource, MailPubActivity |
| **Java** | JavaActivity (Java Code), JavaMethodActivity (Java Method) |
| **File** | FileEventSource, FileReadActivity, FileWriteActivity, FileRenameActivity, FileRemoveActivity |
| **FTP** | FTPGetActivity, FTPPutActivity, FTPListActivity |
| **SOAP/WS** | SOAPSendReceiveActivity, SOAPRequestReplyActivity, ServiceInvokeActivity |
| **RV** | RVPublishActivity, RVSubscribeActivity, RVRequestActivity |
| **XML** | XMLParseActivity, XMLRenderActivity, XMLValidateActivity |
| **Core** | WriteToLogActivity, AssignActivity, CallProcessActivity, Mapper, ThrowActivity, CatchActivity, NullActivity, GenerateErrorActivity |
| **Custom Functions** | CustomFunctionActivity |
| **Adapter** | AdapterEventSource, AdapterRequestResponseActivity |

Icons are 32×32px SVG shapes encoded as base64. The registry provides a fallback generic activity icon for unrecognised types.

**Non-functional requirements for documentation:**
- Pure HTML + CSS, no external dependencies
- Under 10 seconds for 50 processes
- Integrates with `mvn site` lifecycle; also runs standalone

---

### 6.14 Project Initialisation (`bw5:init`)

The `init` goal generates an initial `pom.xml` for an existing BW5 project or library directory. It is a **bootstrap goal** that runs without a project and is the recommended first step when onboarding any BW5 project into Maven.

#### Detection logic

The goal scans the target directory for TIBCO Designer descriptor files to determine the correct Maven packaging type:

| Descriptor found | Packaging type |
|---|---|
| `*.archive` file in project root | `bwear` |
| `*.libbuilder` file in project root or `Library/` subdirectory | `projlib` |
| Neither | Error with actionable message |

When both a `.archive` and a `.libbuilder` file are found, the `.archive` takes precedence (the project is an EAR).

#### Auto-detected defaults

| Field | Source | Fallback |
|---|---|---|
| `artifactId` | EAR/library name from descriptor, normalised (lowercase, spaces→hyphens) | Descriptor filename without extension |
| `version` | `1.0.0-SNAPSHOT` | — |
| `packaging` | From descriptor as above | — |

Users override any field via command-line properties (`-DartifactId=...`, `-Dversion=...`). `groupId` is the only required parameter with no default.

#### Dependency stubs from `.designtimelibs`

If a `.designtimelibs` file is present, the generated `pom.xml` includes commented-out `<dependency>` blocks — one per projlib path in the file. The `artifactId` is derived from the projlib filename; `groupId` and `version` are left as `TODO` placeholders for the developer to fill in once the libraries have been published to a Maven repository.

#### Safety behaviour

- If `pom.xml` already exists and `bw5.init.force` is `false` (default), the goal fails with a clear error message rather than silently overwriting.
- Set `bw5.init.force=true` to overwrite.

#### Key parameters

| Parameter | Property | Default | Description |
|---|---|---|---|
| `groupId` | `groupId` | *(required)* | Maven groupId — no sensible default |
| `artifactId` | `artifactId` | Auto-detected from descriptor name | Maven artifactId |
| `version` | `version` | `1.0.0-SNAPSHOT` | Maven version |
| `projectDir` | `bw5.init.projectDir` | `${basedir}` | BW5 project directory to scan |
| `force` | `bw5.init.force` | `false` | Overwrite existing `pom.xml` |

#### Usage

```bash
# Minimum — run from inside the BW5 project directory
mvn com.tibco.bw:bw5-maven-plugin:1.0.0-SNAPSHOT:init -DgroupId=com.example

# Override all coordinates
mvn com.tibco.bw:bw5-maven-plugin:1.0.0-SNAPSHOT:init \
    -DgroupId=com.example \
    -DartifactId=my-service \
    -Dversion=2.0.0-SNAPSHOT

# Scan a different directory
mvn com.tibco.bw:bw5-maven-plugin:1.0.0-SNAPSHOT:init \
    -DgroupId=com.example \
    -Dbw5.init.projectDir=/path/to/bw-project

# Regenerate / overwrite existing pom.xml
mvn com.tibco.bw:bw5-maven-plugin:1.0.0-SNAPSHOT:init \
    -DgroupId=com.example -Dbw5.init.force=true
```

#### Typical onboarding workflow

```bash
# 1. Check out the BW5 project
git clone https://repo.example.com/bw5-projects/MyService.git
cd MyService

# 2. Generate pom.xml (detects .archive → bwear)
mvn com.tibco.bw:bw5-maven-plugin:1.0.0-SNAPSHOT:init -DgroupId=com.example

# 3. Edit pom.xml: replace TODO placeholders in the commented <dependencies>
#    with real Maven coordinates for each projlib

# 4. Stage dependencies for Designer
mvn bw5:designer-setup

# 5. Build
mvn package
```

---

## 7. Deployment — `bw5-deploy-plugin`

Deployment to BW5 runtimes is handled by a **separate, companion Maven plugin** (`bw5-deploy-plugin`, groupId `com.tibco.bw`). This separation ensures the build plugin has zero dependency on TIBCO tooling, while the deploy plugin can carry optional runtime dependencies.

### 7.1 Deployment Parity Requirement

The deploy plugin shall provide a **uniform Maven interface** across all supported BW5 runtime environments. The same goal (`bw5-deploy:deploy`) is used regardless of the target environment; the environment type is a configuration parameter.

```bash
# Deploy to TIBCO Administrator (classic on-prem)
mvn bw5-deploy:deploy -Dbw5.deploy.environment=administrator

# Deploy to BW5 Containers via Platform API
mvn bw5-deploy:deploy -Dbw5.deploy.environment=container

# Deploy to Control Tower (on-prem) via Platform API
mvn bw5-deploy:deploy -Dbw5.deploy.environment=control-tower
```

### 7.2 Environment 1: TIBCO BW5 Classic — Administrator + Hawk

**Target:** Traditional on-premises BW5 domain managed by TIBCO Administrator and monitored via TIBCO Hawk.

**Mechanism:** Wraps the `appmanage` CLI tool. Requires TIBCO tools installed on the **deployment agent** (not the build agent — this distinction is by design).

**Goals:**

| Goal | Description |
|---|---|
| `bw5-deploy:deploy` | Deploys an EAR to a TIBCO Administrator domain |
| `bw5-deploy:undeploy` | Removes a deployment from the domain |
| `bw5-deploy:start` | Starts a deployed application |
| `bw5-deploy:stop` | Stops a running application |
| `bw5-deploy:restart` | Stops and restarts an application |
| `bw5-deploy:status` | Reports application status via Hawk |

**Key configuration parameters:**

| Parameter | Description |
|---|---|
| `tibco.home` | Path to TIBCO installation (e.g. `/opt/tibco`) |
| `tibco.domain` | Target TIBCO Administrator domain name |
| `tibco.server` | Application server (hawk server) within the domain |
| `tibco.user` / `tibco.password` | Administrator credentials |
| `tibco.ear` | Path to EAR file (defaults to `${project.build.directory}/${project.build.finalName}.ear`) |
| `tibco.deployConfig` | Optional deployment configuration XML override |

**CI/CD integration:**

```yaml
# Deploy stage in pipeline (requires TIBCO on the agent)
- run: mvn bw5-deploy:deploy
  env:
    BW5_DOMAIN: PRODUCTION
    BW5_SERVER: bwserver01
    BW5_USER: ${{ secrets.TIBCO_ADMIN_USER }}
    BW5_PASSWORD: ${{ secrets.TIBCO_ADMIN_PASSWORD }}
    TIBCO_HOME: /opt/tibco/5.14
```

### 7.3 Environment 2: TIBCO BW5 Containers — Platform API

**Target:** BW5 applications running as containers (Docker/Kubernetes) managed via the TIBCO Platform API (REST).

**Mechanism:** Pure REST client — no TIBCO tools required on the deployment agent.

**Goals:** Same set as §7.2 (`deploy`, `undeploy`, `start`, `stop`, `restart`, `status`).

**Key configuration parameters:**

| Parameter | Description |
|---|---|
| `tibco.platform.url` | Platform API base URL |
| `tibco.platform.org` | Organisation / tenant |
| `tibco.platform.space` | Deployment space (environment) |
| `tibco.platform.clientId` / `tibco.platform.clientSecret` | OAuth2 credentials |
| `tibco.platform.replicas` | Number of container replicas (for scale) |
| `tibco.platform.resources.cpu` / `.memory` | Container resource limits |

**CI/CD integration:**

```yaml
- run: mvn bw5-deploy:deploy -Dbw5.deploy.environment=container
  env:
    BW5_PLATFORM_URL: https://platform.example.com
    BW5_PLATFORM_ORG: my-org
    BW5_PLATFORM_SPACE: staging
    BW5_CLIENT_ID: ${{ secrets.PLATFORM_CLIENT_ID }}
    BW5_CLIENT_SECRET: ${{ secrets.PLATFORM_CLIENT_SECRET }}
```

### 7.4 Environment 3: TIBCO BW5 On-Prem Control Tower — Platform API

**Target:** BW5 applications managed by TIBCO Control Tower deployed on-premises. Control Tower provides a unified management plane for BW5 workloads running on-prem, using a Platform API compatible with Environment 2.

**Status:** Control Tower Platform API specification is under development. The plugin shall implement support once the API contract is published.

**Mechanism:** REST client against the on-prem Control Tower API. No TIBCO CLI tools required on the deployment agent.

**Goals:** Same set as §7.2.

**Key configuration parameters:**

| Parameter | Description |
|---|---|
| `tibco.platform.url` | On-prem Control Tower URL |
| `tibco.platform.org` | Organisation within Control Tower |
| `tibco.platform.space` | Deployment space |
| `tibco.platform.clientId` / `tibco.platform.clientSecret` | OAuth2 credentials |

> **Note for Engineering:** The deploy plugin must abstract the Platform API client behind an interface (`PlatformApiClient`) so that Environment 2 (cloud) and Environment 3 (on-prem) share the same REST implementation with only the base URL and auth configuration differing. This interface also allows mock implementations in tests.

### 7.5 Environment Parity Matrix

| Capability | Classic (Administrator) | Containers (Platform API) | Control Tower (Platform API) |
|---|---|---|---|
| Deploy EAR | ✅ | ✅ | ✅ |
| Undeploy | ✅ | ✅ | ✅ |
| Start application | ✅ | ✅ | ✅ |
| Stop application | ✅ | ✅ | ✅ |
| Restart application | ✅ | ✅ | ✅ |
| Application status | ✅ (via Hawk) | ✅ | ✅ |
| Scale (replicas) | ❌ (N/A) | ✅ | ✅ |
| Property override at deploy time | ✅ | ✅ | ✅ |
| Deployment config XML | ✅ | ❌ (YAML/JSON) | ❌ (YAML/JSON) |
| No TIBCO install on agent | ❌ (requires TIBCO_HOME) | ✅ | ✅ |

---

## 8. Technical Architecture

### 8.1 Plugin Coordinates

```xml
<!-- Build plugin -->
<plugin>
    <groupId>com.tibco.bw</groupId>
    <artifactId>bw5-maven-plugin</artifactId>
    <version>1.0.0</version>
    <extensions>true</extensions>
</plugin>

<!-- Deploy plugin (separate artifact, used only in deployment pipelines) -->
<plugin>
    <groupId>com.tibco.bw</groupId>
    <artifactId>bw5-deploy-plugin</artifactId>
    <version>1.0.0</version>
</plugin>
```

### 8.2 BW Project Source Layout

The default layout places `pom.xml` alongside the BW project files (`bwProjectPath` defaults to `${basedir}`), which matches how TIBCO Designer projects are normally structured on disk:

```
my-bw5-application/
├── pom.xml                        ← <packaging>bwear</packaging>
├── .folder
├── defaultVars/
│   └── defaultVars.substvar
├── SharedResources/
└── Services/
    └── *.process
```

Projects with a Maven source layout (`src/main/bw/`) are also supported by setting `<bwProjectPath>${basedir}/src/main/bw</bwProjectPath>`:

```
my-bw5-application/
├── pom.xml
└── src/
    └── main/
        ├── bw/                    ← BW project (process definitions, shared resources)
        │   ├── .folder
        │   ├── defaultVars/defaultVars.substvar
        │   ├── SharedResources/
        │   └── Services/*.process
        └── java/                  ← Custom Function implementations (optional)
            └── com/example/functions/
```

### 8.3 Key Implementation Decisions

| Decision | Rationale |
|---|---|
| **EAR assembled in pure Java (`java.util.zip`)** | Eliminates `buildear`. BW5 processes are XML interpreted at runtime — no compilation needed. |
| **Standard `maven-compiler-plugin` for Java** | Java Code activities and Custom Functions extracted to `target/generated-sources/bw-java` and `src/main/java` respectively, compiled transparently by the standard Maven compiler. Full IDE support, testable output. |
| **`mvn deploy` targets Maven repository only** | Deployment is an explicit operational step, not part of the build. Separation enforced at the artifact level (two plugins). |
| **Uniform deploy interface across environments** | Same Maven goal, same lifecycle position, same property naming convention regardless of target. Reduces operator training and pipeline divergence. |
| **`bwear` / `projlib` packaging types aligned with BW6** | Consistency for multi-generation teams; `projlib` uses the native BW5 term (more discoverable than `bwmodule`). |
| **SVG diagrams from native coordinates + icon registry** | TIBCO Designer stores x/y activity positions in `.process` XML. Combined with a palette icon registry (base64 SVGs), the plugin generates diagrams that visually match the Designer canvas. |
| **No lib.zip in EAR** | Production BW5 EARs reference JARs/projlibs via FileAliases — they are not bundled. The plugin replicates this model. |
| **Multi-PAR via plugin config, not `.archiveDescriptor`** | Avoids reading a proprietary binary-format descriptor. Archive assignment via glob patterns in `pom.xml` is transparent and version-controlled. |

### 8.4 Technology Stack

| Concern | Technology | Version |
|---|---|---|
| Maven Plugin API | `maven-plugin-api` | 3.6.3 |
| Maven Core | `maven-core` | 3.6.3 |
| XML Processing | JDOM2 | 2.0.6.1 |
| File Operations | Apache Commons IO | 2.13.0 |
| REST Client (deploy plugin) | Jersey Client or Apache HttpClient | TBD |
| JSON/YAML (deploy plugin) | Jackson | 2.15+ |
| Java Target | Java 11 | — |
| Minimum Maven | Maven 3.6.3 | — |

---

## 9. Example Usage

### 9.1 Projlib with Custom Functions

```xml
<packaging>projlib</packaging>

<dependencies>
    <dependency>
        <groupId>org.apache.commons</groupId>
        <artifactId>commons-lang3</artifactId>
        <version>3.12.0</version>
    </dependency>
</dependencies>
```

```bash
mvn package   # → target/common-framework-2.1.0.projlib (includes compiled Custom Functions)
mvn deploy    # → published to Nexus/Artifactory
```

### 9.2 Multi-PAR BW5 Application

```xml
<packaging>bwear</packaging>

<dependencies>
    <dependency>
        <groupId>com.example</groupId>
        <artifactId>common-framework</artifactId>
        <version>2.1.0</version>
        <type>projlib</type>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>com.tibco.bw</groupId>
            <artifactId>bw5-maven-plugin</artifactId>
            <version>1.0.0</version>
            <extensions>true</extensions>
            <configuration>
                <archives>
                    <archive>
                        <name>OrderService</name>
                        <includes><include>Services/Order/**</include></includes>
                    </archive>
                    <archive>
                        <name>PaymentService</name>
                        <includes><include>Services/Payment/**</include></includes>
                    </archive>
                </archives>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### 9.3 CI/CD Pipeline

```yaml
# Build stage — no TIBCO software required
build:
  steps:
    - uses: actions/setup-java@v4
      with: { java-version: '11' }
    - run: mvn clean deploy   # → EAR published to Artifactory

# Deploy stage — environment-specific
deploy-to-staging:
  steps:
    - run: mvn bw5-deploy:deploy
      env:
        BW5_DEPLOY_ENVIRONMENT: container
        BW5_PLATFORM_URL: https://platform.staging.example.com
        BW5_PLATFORM_ORG: my-org
        BW5_PLATFORM_SPACE: staging
        BW5_CLIENT_ID: ${{ secrets.CLIENT_ID }}
        BW5_CLIENT_SECRET: ${{ secrets.CLIENT_SECRET }}
```

---

## 10. Configuration Reference

### 10.1 Build Plugin Parameters (`bw5-maven-plugin`)

| Parameter | Property | Default | Description |
|---|---|---|---|
| `bwProjectPath` | `bw5.bwProjectPath` | `${basedir}` | Root directory of BW5 project sources (pom.xml alongside BW project files) |
| `archiveName` | `bw5.archiveName` | `${project.artifactId}` | PAR name within EAR (single-PAR mode) |
| `archives` | — | — | Multi-PAR archive assignment configuration |
| `defaultArchiveName` | — | `${project.artifactId}` | Archive name for unmatched processes in multi-PAR mode |
| `archiveType` | `bw5.archiveType` | `par` | Archive type: `par`, `aar`, or `par+aar` |
| `includeSharedArchive` | — | `true` | Whether to include the SAR in the EAR |
| `sharedArchiveName` | — | `Shared Archive` | SAR file name |
| `earOnly` | `bw5.earOnly` | `false` | Assemble EAR only; skip deploy config file generation |
| `generateDeployXml` | `bw5.generateDeployXml` | `true` | Generate `<finalName>-deploy.xml` alongside the EAR |
| `generateProperties` | `bw5.generateProperties` | `true` | Generate `<finalName>-deploy.properties` alongside the EAR |
| `generateValuesYaml` | `bw5.generateValuesYaml` | `true` | Generate `values.yaml` alongside the EAR |
| `globalPropertiesFile` | `bw5.deployConfig.globalPropertiesFile` | — | Global property overrides applied before config generation |
| `projectPropertiesFile` | `bw5.deployConfig.projectPropertiesFile` | — | Project-specific property overrides (takes precedence) |
| `archiveDescriptorFile` | `bw5.archiveDescriptorFile` | — | Optional TIBCO Designer `.archive` descriptor file |
| `skip` | `bw5.skip` | `false` | Skip all plugin goals |

### 10.2 `bw5:designer-setup` Parameters

| Parameter | Property | Default | Description |
|---|---|---|---|
| `designerLibsDir` | `bw5.designerLibsDir` | `${basedir}/.designer-libs` | Staging directory for Designer dependencies |
| `designtimeLibsDir` | `bw5.designtimeLibsDir` | `${bw5.bwProjectPath}` | Directory where `.designtimelibs` is written |
| `force` | `bw5.designerSetup.force` | `false` | Force re-copy of all dependencies |
| `launchDesigner` | `bw5.designerSetup.launchDesigner` | `false` | Launch TIBCO Designer after staging dependencies |
| `tibcoHome` | `bw5.tibcoHome` | `$TIBCO_HOME` | TIBCO installation root (to locate Designer executable) |

### 10.3 `bw5:site` Parameters

| Parameter | Property | Default | Description |
|---|---|---|---|
| `siteOutputDir` | `bw5.siteOutputDir` | `${project.build.directory}/site/bw5` | Output directory for generated HTML |

### 10.4 Deploy Plugin Parameters (`bw5-deploy-plugin`)

| Parameter | Property | Description |
|---|---|---|
| `environment` | `bw5.deploy.environment` | Target environment: `administrator`, `container`, `control-tower` |
| `earFile` | `bw5.deploy.earFile` | EAR file to deploy (defaults to project artifact) |
| **Classic (administrator)** | | |
| `tibcoHome` | `bw5.tibco.home` | Path to TIBCO installation |
| `domain` | `bw5.deploy.domain` | TIBCO Administrator domain |
| `server` | `bw5.deploy.server` | Application server in the domain |
| `user` / `password` | `bw5.deploy.user` / `.password` | Administrator credentials |
| `deployConfig` | `bw5.deploy.config` | Deployment configuration XML file |
| **Platform API (container / control-tower)** | | |
| `platformUrl` | `bw5.platform.url` | Platform API base URL |
| `platformOrg` | `bw5.platform.org` | Organisation |
| `platformSpace` | `bw5.platform.space` | Deployment space |
| `clientId` / `clientSecret` | `bw5.platform.clientId` / `.clientSecret` | OAuth2 credentials |
| `replicas` | `bw5.deploy.replicas` | Container replicas (container/control-tower only) |

---

## 11. Compatibility & Migration

### 11.1 Compatibility with Existing BW5 Projects

1. **No changes to BW project files**: `.process`, `.substvar`, `.sharedhttp` etc. are read but never modified.
2. **Migration from `tibco-bwmaven`**: Replace the old plugin declaration. Change `<packaging>bw-ear</packaging>` → `<packaging>bwear</packaging>`. Remove `TIBCO_HOME` and binary configuration.
3. **Source layout**: By default `bwProjectPath` is `${basedir}` — `pom.xml` sits alongside the BW project files. If the project uses a nested layout (e.g. `src/main/bw/`), set `<bwProjectPath>${basedir}/src/main/bw</bwProjectPath>`.

### 11.2 BW6 Alignment

| Concept | BW5 (`bw5-maven-plugin`) | BW6 (`bw6-plugin-maven`) |
|---|---|---|
| Application packaging | `bwear` → `.ear` | `bwear` → `.ear` |
| Library packaging | `projlib` → `.projlib` | `bwmodule` → `.jar` |
| Goal prefix | `bw5:` | `bw6:` |
| Source path | `src/main/bw` | `src/main/java` |
| Deploy lifecycle | `mvn deploy` → Maven repo | `mvn deploy` → Maven repo |
| Runtime deployment | `bw5-deploy-plugin` | `bw6-deploy-plugin` / TCI goals |

---

## 12. Open Items & Future Work

The following items are out of scope for v1.0:

| Item | Priority | Notes |
|---|---|---|
| **Maven archetype** | High | `mvn archetype:generate` templates for new `bwear` and `projlib` projects |
| **Integration test support** | Medium | `mvn integration-test` with local BW engine execution; opt-in, requires BW engine install |
| **Enhanced mapping visualisation** | Medium | Graphical source→target mapping diagrams (Sankey-style) for complex XSLT |
| **Process validation / lint** | Medium | Static analysis: detect broken references, missing shared resources, unused variables |
| **WSDL/service documentation** | Low | Service contract docs from `.wsdl` and `.serviceagent` files in `bw5:site` |
| **Control Tower API implementation** | Blocked | Depends on Control Tower Platform API specification (§7.4) — implement when API is published |
| **BW engine unit testing framework** | Low | Run individual BW processes as JUnit tests without full domain setup |

---

## 13. Acceptance Criteria — v1.0.0

- [ ] `mvn clean package` on a single-PAR `bwear` project produces a valid `.ear` deployable to TIBCO Administrator, **no TIBCO software on build machine**
- [ ] `mvn clean package` on a multi-PAR `bwear` project (via `<archives>`) produces an EAR with multiple correctly-structured PAR files
- [ ] `mvn clean package` on an adapter `bwear` project produces a valid EAR with an `.aar` archive
- [ ] `mvn clean package` on a `projlib` project produces a valid `.projlib`
- [ ] A `.projlib` published to Nexus/Artifactory is resolvable as `<dependency type="projlib">` in another project
- [ ] Java Code activities are correctly extracted, compiled, and bundled in the artifact
- [ ] Java Custom Functions in `src/main/java` are compiled and bundled under `CustomFunctions/` in the artifact
- [ ] `mvn bw5:pull` followed by opening the project in TIBCO Designer resolves all dependencies without manual file copying
- [ ] `mvn bw5:site` produces an HTML site with SVG diagrams using palette-specific activity icons for all standard activity types
- [ ] `bw5-deploy:deploy -Dbw5.deploy.environment=administrator` deploys the EAR to a TIBCO Administrator domain
- [ ] `bw5-deploy:deploy -Dbw5.deploy.environment=container` deploys via Platform API REST (no TIBCO tools needed)
- [ ] All build goals complete on vanilla JDK 11 + Maven 3.6.3 with no TIBCO software installed
- [ ] A standard CI pipeline (`mvn clean deploy`) publishes to a Maven repository without TIBCO tools

---

*This document is intended for review by TIBCO BW5 Product Management and Engineering. Section 7 (Deployment) and Section 12 (Open Items) are particularly relevant for roadmap prioritisation discussions.*

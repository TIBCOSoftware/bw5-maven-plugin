# Product Requirements Document
## BW5 Maven Plugin (`bw5-maven-plugin`)

**Version:** 1.5
**Status:** Updated — Reflects actual implementation as of 2026-06-22
**Author:** TIBCO BW5 Community
**Date:** 2026-06-22

---

## 1. Executive Summary

The `bw5-maven-plugin` is a Maven plugin for TIBCO BusinessWorks 5.x that enables full application lifecycle management — dependency management, compilation, packaging, documentation, and CI/CD integration — **without requiring any TIBCO tools to be installed on the build machine**.

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
7. **Generate professional process documentation** with realistic activity icons, SVG diagrams, optional Markdown export, and optional PDF export.
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
| `process-classes` | `bw5:prepare-jcf-bytecode` — Base64-encodes compiled Custom Function bytecode into Maven properties |
| `package` | `bw5:bwear` — assembles PAR(s) + SAR + TIBCO.xml into `.ear`; applies property overrides; generates `deploy.xml`, `deploy.properties`, `values.yaml` |
| `verify` | `bw5:validate` — static analysis: XML well-formedness, process names, global variable completeness, XPath syntax (see §6.15) |
| `install` | Standard Maven install |
| `deploy` | Standard Maven deploy (to Maven repository) |

**For `projlib` packaging:**

| Maven Phase | Goal |
|---|---|
| `initialize` | `bw5:initialize` |
| `generate-sources` | `bw5:copy-bw-sources`, `bw5:extract-java-sources` |
| `process-resources` | `bw5:resolve-dependencies` |
| `compile` | `maven-compiler-plugin:compile` |
| `process-classes` | `bw5:prepare-jcf-bytecode` |
| `package` | `bw5:bw5module` — assembles `.projlib` archive |
| `install` | Standard Maven install |
| `deploy` | Standard Maven deploy |

### 6.3 EAR Assembly (`bw5:bwear`)

The goal shall produce a valid BW5 EAR without invoking `buildear`.

**Single-PAR EAR structure (current implementation):**

```
<artifactId>-<version>.ear
├── TIBCO.xml                 ← Generated deployment descriptor
│                                FileAliases, Global Variables, Modules list
├── Process Archive.par       ← Process Archive (default name; overridden by .archive descriptor)
│   ├── TIBCO.xml             ← PAR descriptor (BwBPConfigurations, EXTERNAL_DEPENDENCIES)
│   └── **/*.process          ← Process files, directory structure preserved
└── Shared Archive.sar        ← Shared resources (connections, schemas, variables, etc.)
```

> **Note — `archiveName` vs. PAR filename:** The `bw5.archiveName` parameter (`${project.artifactId}` by default) sets the **application name** used inside `TIBCO.xml` and `manifest-bw5.json`. It does **not** control the PAR filename. The PAR filename comes from the `.archive` descriptor's `processArchive/@name` attribute, or defaults to the hardcoded value `"Process Archive.par"` when no descriptor is configured. This matches TIBCO Designer's `buildEAR` behaviour.

**Multi-PAR EAR structure (see §6.7):**

```
<artifactId>-<version>.ear
├── TIBCO.xml                 ← Lists all PAR modules
├── ServiceA.par              ← Processes reachable from ServiceA entry points
├── ServiceB.par              ← Processes reachable from ServiceB entry points
└── Shared Archive.sar        ← Union of shared resources across all PARs
```

**Adapter EAR structure (see §6.8):**

```
<artifactId>-<version>.ear
├── TIBCO.xml
├── <archiveName>.par         ← Process Archive (optional, when processArchive declared)
├── <adapterName>.aar         ← Adapter Archive per adapterArchive element
└── Shared Archive.sar        ← Shared resources + .adapter files duplicated here
```

**Requirements:**
- The PAR filename defaults to `"Process Archive.par"`; overridden by the `.archive` descriptor's `processArchive/@name` attribute when `archiveDescriptorFile` is configured
- Global variables are read from all `.substvar` files
- projlib and JAR dependencies are registered as FileAliases in TIBCO.xml (never bundled in the EAR)
- Compiled Java classes (Code activities and Custom Functions) are included in the PAR under `JavaCode/`
- `.folder` files (TIBCO Designer folder metadata) are **not** included by default (`includeFolderMetadata` defaults to `false`); set to `true` to include them
- Maven build descriptor files (`pom.xml`) are excluded from all archives
- If `archiveDescriptorFile` is configured and the file is missing or malformed, the build fails with a descriptive error (no silent fallback)
- If a process declared in the `.archive` descriptor's `processProperty` list is missing on disk, the build fails listing all missing files
- Process files not reachable from any `processProperty` entry point are excluded from the PAR (applies to both single-PAR and multi-PAR mode)
- Each `.adapter` file is included both inside its dedicated AAR and as a copy in the SAR (matching TIBCO Designer behaviour)

**Transitive dependency analysis (when `.archive` descriptor present):**

When an `.archive` descriptor is present, `bw5:bwear` performs a BFS traversal starting from the process entry points declared in the descriptor. This analysis:
- Detects resource references by content: any XML element value starting with `/` and matching a known BW resource extension is treated as a reference — no element-name whitelist needed
- Follows shared resource internal references transitively for all SAR file types (e.g. `.sharedhttp` → `.id` / `.cert`)
- Expands directory references (e.g. `<cert>/Certificates</cert>`) to include all SAR files under that path
- Follows XSD imports transitively
- Always includes `.javaxpath` files and files under paths listed in `sharedResources`
- Files not reachable from entry points and not in shared resource paths are excluded from the archive

**SAR file extensions covered:**
All extensions starting with `.shared*` are covered generically (e.g. `.sharedhttp`, `.sharedjdbc`, `.sharedjmscon`, `.sharedjmsapp`, `.sharedftp`, `.sharedvariable`, `.sharedLock`, `.sharedpartner`, `.sharedparse` — and any future `.shared*` type automatically).
Additional explicit types: `.rvtransport`, `.httpProxy`, `.jobsharedvariable`, `.serviceagent`, `.securityPolicy`, `.securityPolicyAssociation`, `.contextResource`, `.wsdl`, `.xsd`, `.aeschema`, `.id`, `.cert`, `.properties`, `.xslt`, `.xsl`, `.javaxpath`, `.xml`, `.adapter`

### 6.4 Projlib Assembly (`bw5:bw5module`)

**Requirements:**
- `.projlib` is a ZIP archive preserving the full BW project directory structure
- `/library.manifest` entry is included with Maven GAV metadata
- When a `.libbuilder` descriptor is present, only Designer-managed files (as listed in the descriptor) are included — plus always includes `.substvar` files and `.folder` files for any directory (including the project root) that contains at least one listed resource
- Without a `.libbuilder` descriptor: includes all BW files, excluding AESchemas, `.designtimelibs`, `vcrepo.dat`, `.DS_Store`, `Thumbs.db`, `.git`, `.svn`, `target/`
- Compiled Java classes (Code activities and Custom Functions) included under `JavaCode/`
- Projlib files install to and resolve from any standard Maven repository

### 6.5 Java Code Activity Compilation

**Requirements:**
- Java source embedded in `.process` files (`com.tibco.plugin.java.JavaActivity`, `<config><code>`) is extracted to `target/generated-sources/bw-java`
- Extracted directory is registered as a Maven compile source root automatically
- Class name and package are read from `<className>`; imports from `<imports>`
- TIBCO palette JARs needed at compile time are declared as `provided`-scope dependencies

### 6.6 Java Custom Function Compilation

Custom Functions in BW5 are Java classes that implement TIBCO's XPath extension contract and are referenced from `.javaxpath` descriptor files in the BW project.

**Build flow:**
1. Java sources for Custom Functions reside under `src/main/java/` (standard Maven layout) and are compiled by `maven-compiler-plugin`
2. `bw5:prepare-jcf-bytecode` (phase `process-classes`) scans for `.javaxpath` files, derives the fully-qualified class name from the `<ns0:loadedFromLocation>` element, locates the compiled `.class` file, and stores the Base64-encoded bytecode as Maven property `bw5.jcf.bytecode.<className>`
3. `bw5:bwear` / `bw5:bw5module` injects this bytecode into the `.javaxpath` file's `<data>` element when assembling the archive

**Legacy mode:** When `<oldJavaCustomFunctions>true</oldJavaCustomFunctions>` is set, the goal uses pre-existing bytecode already embedded in the `.javaxpath` file and emits a warning rather than overwriting it.

**Requirements:**
- Custom Function classes shall be packaged into the projlib or EAR under `CustomFunctions/` (or inline in `.javaxpath`), enabling the BW engine to discover them at runtime
- Custom Function JARs can themselves depend on other JARs declared in the project `pom.xml`
- The `bw5:validate` goal recognises `.javaxpath` descriptor files and validates their presence

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

### 6.7 Multi-PAR Projects (`bw5:bwear` with multiple `<processArchive>` in `.archive` descriptor)

Some BW5 projects are logically divided into multiple services (PARs) within one EAR. In TIBCO Designer the split is defined in the project's `.archive` descriptor file via multiple `<processArchive>` elements — one per PAR. The plugin reads this file and replicates the same structure without invoking `buildEAR`.

**Designer `.archive` descriptor format (multi-PAR):**

```xml
<Repository:repository xmlns:Repository="http://www.tibco.com/xmlns/repo/types/2002">
    <enterpriseArchive>
        <name>MyApp</name>
        <processArchive name="OrderService">
            <processProperty>/Services/Order/ReceiveOrder.process,/Services/Order/Sub.process</processProperty>
        </processArchive>
        <processArchive name="PaymentService">
            <processProperty>/Services/Payment/ProcessPayment.process</processProperty>
        </processArchive>
        <sharedArchive name="Shared Archive">
            <sharedResources>/SharedResources,/config_ear</sharedResources>
        </sharedArchive>
    </enterpriseArchive>
</Repository:repository>
```

**Configuration (pom.xml — points to the Designer descriptor):**

```xml
<configuration>
    <archiveDescriptorFile>${basedir}/MyApp.archive</archiveDescriptorFile>
</configuration>
```

**Requirements:**
- The plugin reads all `<processArchive>` elements from the `.archive` descriptor
- Each `<processArchive>` element produces one PAR file in the EAR, named `<name>.par`
- Each PAR gets its own TIBCO.xml descriptor with its own process list and `EXTERNAL_DEPENDENCIES`
- Transitive dependency analysis is applied independently per PAR, starting from that PAR's `<processProperty>` entry points
- The SAR is shared across all PARs and assembled from `<sharedArchive>` as usual
- The EAR-level TIBCO.xml Modules section lists all PARs
- If a process declared in any PAR's `<processProperty>` is missing on disk, the build fails listing all missing files
- When no `.archive` descriptor is configured, the plugin falls back to single-PAR mode (existing behaviour)

### 6.8 Adapter Archive (AAR) Support (`bw5:bwear`)

Adapter-based BW5 applications produce `.aar` files (Adapter Archives) instead of or in addition to `.par` files. In TIBCO Designer, adapter archives are declared in the `.archive` descriptor via `<adapterArchive>` elements. The plugin reads this declaration and assembles the AAR without invoking `buildEAR`.

**Designer `.archive` descriptor format (adapter EAR):**

```xml
<Repository:repository xmlns:Repository="http://www.tibco.com/xmlns/repo/types/2002">
    <enterpriseArchive>
        <name>MyAdapterApp</name>
        <adapterArchive name="SalesforceAdapter">
            <adapterReference>/Adapters/Salesforce.adapter#adapter.GenericAdapterConfiguration</adapterReference>
            <sdkVersion>5.3.0</sdkVersion>
        </adapterArchive>
        <sharedArchive name="Shared Archive"/>
    </enterpriseArchive>
</Repository:repository>
```

**Requirements:**
- AAR assembly is driven entirely by `<adapterArchive>` elements in the `.archive` descriptor — there is no auto-detection and no `archiveType` parameter
- Each `<adapterArchive>` produces one `.aar` file named `<name>.aar`
- Each AAR contains two entries: `TIBCO.xml` (adapter-specific descriptor with `StartAsOneOf`/SDK version, `EXTERNAL_DEPENDENCIES`, and `RepoConfigUrl` sections) and the `.adapter` file at its absolute BW repository path (with leading `/`)
- The `.adapter` file is also duplicated in the SAR, matching TIBCO Designer behaviour
- AESchemas (`/AESchemas/ae.aeschema`, `/AESchemas/ae/BW/AESchema.aeschema`) are included in the SAR when referenced by adapter process files
- Mixed EARs (any combination of `<processArchive>` and `<adapterArchive>` elements) are supported; the EAR-level TIBCO.xml lists all modules in a single `<Modules>` block
- When no `<adapterArchive>` element is present in the descriptor, AAR assembly is skipped

### 6.9 Dependency Management

**Requirements:**
- projlib: `<dependency ... <type>projlib</type>>` resolved from Maven repo
- Standard `jar` dependencies usable in Java Code activities and Custom Functions
- Transitive projlib dependencies resolved via standard Maven

### 6.10 Deployment Config Generation (`bw5:deploy-config`)

The `deploy-config` goal implements a **two-level property override model** that injects environment-specific values into a BW5 project's substitution variables and generates all deployment configuration files. It is a standalone goal (phase `generate-resources`) for when configs need to be regenerated without rebuilding the EAR. The same logic also runs automatically inside `bw5:bwear` during `mvn package`.

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

The `run` goal starts a TIBCO BusinessWorks 5.x engine locally against the BW project sources. It is designed for **developer inner-loop testing** and local integration checks. No EAR file is required — the engine is invoked directly against the BW project directory, which matches the BW5 development workflow in TIBCO Designer.

#### Engine location and invocation

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

The engine is invoked with the following command structure:

```
bwengine --propFile bwengine.tra -n <artifactId> -p target/bwengine.properties [-d <domainHome>] [extraArgs] <bwProjectPath>
```

- `--propFile` points to `bwengine.tra` in the same directory as the engine binary (standard TIBCO runtime agent configuration)
- `-n <artifactId>` sets the application name in the engine
- `-p target/bwengine.properties` passes auto-generated runtime properties (see below)
- `<bwProjectPath>` is the BW project directory (not an EAR file)

#### Generated `bwengine.properties`

Before starting the engine, the plugin generates `target/bwengine.properties` by merging:

1. **Auto-generated `tibco.alias.*` entries** for all Maven dependencies:
   - Projlibs: `tibco.alias.<groupId>\:<artifactId>\:<version>\:projlib = /path/to/file.projlib`
   - JARs: `tibco.alias.<artifactId>-<version>.jar = /path/to/file.jar`
2. **User-provided properties file** (configured via `propertiesFile`): merged on top, so user entries win

The colons in projlib alias keys are escaped as `\:` so Java `Properties.load()` parses the full Maven coordinate as a single key.

#### Background mode

When `bw5.run.background=true`, Maven starts the engine as a background OS process and returns immediately after the startup marker is detected in the engine's stdout (markers: `"Engine Initialized"`, `"Application started"`, `"BusinessWorks started"`), or after `bw5.run.startupWaitSeconds` seconds, whichever comes first. A JVM shutdown hook ensures the engine process is killed when Maven exits.

When `bw5.run.background=false` (default), Maven blocks until the engine process exits, piping all output to the Maven log.

#### Key parameters

| Parameter | Property | Default | Description |
|---|---|---|---|
| `tibcoHome` | `tibco.Home` | *(required)* | TIBCO installation root |
| `bwVersion` | `bw5.bwVersion` | `5.13.0` | BW5 version string |
| `propertiesFile` | `bw5.run.propertiesFile` | — | Optional user-provided properties file merged into `target/bwengine.properties`; user entries take precedence over auto-generated aliases |
| `background` | `bw5.run.background` | `false` | Run engine in background |
| `startupWaitSeconds` | `bw5.run.startupWaitSeconds` | `30` | Seconds to wait for startup when `background=true` |
| `domainHome` | `bw5.run.domainHome` | — | Optional domain home directory (`-d` flag) |
| `extraArgs` | — | — | Additional `bwengine` arguments |
| `workingDir` | `bw5.run.workingDir` | `${project.build.directory}` | Engine working directory |
| `skipResolveDependencies` | `bw5.run.skipResolveDependencies` | `false` | Skip copying projlib/JAR deps to `target/bw-lib` before starting the engine |

#### Usage

```bash
# Run in foreground (blocks until engine exits)
mvn bw5:run

# Run in background (Maven returns after startup)
mvn bw5:run -Dbw5.run.background=true

# Run with explicit TIBCO home
mvn bw5:run -Dtibco.Home=/opt/tibco -Dbw5.bwVersion=5.14.0

# Run with extra runtime properties (e.g. global variable overrides)
mvn bw5:run -Dbw5.run.propertiesFile=config/local.properties

# Build EAR then run (resolve deps, start engine)
mvn package bw5:run -Dbw5.run.background=true
```

---

### 6.15 Static Validation (`bw5:validate`)

The `validate` goal performs **static analysis of BW project sources** without requiring a TIBCO installation. It runs in the `verify` phase and is designed to give developers early feedback on structural and syntactic issues. Validation failures are reported as warnings by default and do not block EAR assembly.

#### Checks performed

| Code | Check | Severity |
|---|---|---|
| `XML` | XML well-formedness of all `.process`, `.archive`, `.substvar`, `.aliaslib`, `.sharedhttp`, `.schema` files | Error |
| `ARCHIVE` | Archive descriptor (`.archive` file) exists and all declared process paths resolve to files on disk | Error |
| `PROCESS_NAME` | Process `<name>` element matches the file path | Warning |
| `DUPLICATE` | No duplicate process names within the same archive | Error |
| `GVAR` | All `%%VAR%%` global variable references in process config text are declared in a `.substvar` file | Warning |
| `GVAR_UNUSED` | Global variables declared in `.substvar` that are not referenced anywhere (only when `showUnusedGVars=true`) | Warning |
| `DEP` | All projlib dependencies are resolvable from the Maven repository | Error |
| `XPATH` | XPath expressions in process data mappings and transition conditions compile without syntax errors using the full BW5 custom function catalog | Warning |

#### What is NOT checked (vs. TIBCO Designer's `ValidateProject`)

The following checks require a TIBCO runtime or Designer installation and are therefore out of scope:

- Schema validation against TIBCO XSD types
- Resource connectivity (JDBC connections, JMS destinations, RV transports)
- Cross-process reference integrity (`CallProcess` activity target resolution)
- Adapter-specific configuration validation
- Java Code activity compilation (handled by `maven-compiler-plugin`)
- Palette-version compatibility

#### BW5 XPath function catalog

XPath expressions are compiled using the full catalog of BW5 custom functions extracted from the BW5 installation (`mapper.jar`). The catalog covers 63 TIBCO-specific functions across categories: string, date/time, binary, number, logical, and set — each with exact arity checking. Standard XPath 1.0 functions and XPath 2.0 functions supported by BW5's Saxon engine are also accepted. Unknown functions or wrong argument counts are reported as `XPATH` warnings.

#### Key parameters

| Parameter | Property | Default | Description |
|---|---|---|---|
| `failOnError` | `bw5.validate.failOnError` | `false` | Fail the build if any **Error**-severity issue is found |
| `failOnWarning` | `bw5.validate.failOnWarning` | `false` | Fail the build if any Warning-severity issue is found |
| `showUnusedGVars` | `bw5.validate.showUnusedGVars` | `false` | Enable `GVAR_UNUSED` check |
| `skipXPath` | `bw5.validate.skipXPath` | `false` | Skip XPath expression validation |
| `skipResolveDependencies` | `bw5.validate.skipResolveDependencies` | `false` | Skip resolving projlib/JAR deps to `target/bw-lib` before validation |
| `skip` | `bw5.skip` | `false` | Skip this goal entirely |

#### Usage

```bash
# Run validation as part of the normal build (verify phase)
mvn verify

# Run standalone
mvn bw5:validate

# Fail the build on any error
mvn bw5:validate -Dbw5.validate.failOnError=true

# Full strict mode — fail on errors and warnings, show unused GVars
mvn bw5:validate \
    -Dbw5.validate.failOnError=true \
    -Dbw5.validate.failOnWarning=true \
    -Dbw5.validate.showUnusedGVars=true

# Skip XPath checking (faster, useful during rapid iteration)
mvn bw5:validate -Dbw5.validate.skipXPath=true
```

#### Sample output

```
[INFO] bw5:validate — scanning BW project at /path/to/MyService
[INFO] Checked 12 process file(s), 1 archive descriptor(s), 2 substvar file(s)
[WARN] [GVAR] %%DB_PASSWORD%% referenced in Services/InvoiceService.process but not declared in any .substvar
[WARN] [XPATH] Services/PaymentService.process: XPath compile error in xsl:value-of: Unknown function 'tib:format-money' (no namespace expected for BW5 functions)
[INFO] Validation complete: 0 error(s), 2 warning(s)
```

---

### 6.12 Designer Alignment (`bw5:designer-setup`)

**Requirements:**
- Copies all `projlib` and `jar` dependencies to `${basedir}/.designer-libs/` (configurable)
- Updates `.designtimelibs` in format `N=groupId:artifactId:version:type\=`
- Idempotent: skips files already present with correct size; `force` flag overrides
- Generates `target/.TIBCO/Designer5.prefs` with Maven-path filealias entries (reads the system `~/.TIBCO/Designer5.prefs`, strips existing `filealias.*` entries, and regenerates with Maven-managed paths — the engine is launched with `-Duser.home=target` so this file is used instead of the user's global preferences, avoiding corruption)
- Auto-adds `.designer-libs/` to `.gitignore`
- Optionally launches TIBCO Designer on the project directory (`launchDesigner=true`); Designer executable located via `tibcoHome` parameter or `TIBCO_HOME` environment variable

### 6.13 Documentation Generation (`bw5:site`)

**Requirements:**

**Project overview page** (`index.html`) shall include:
- Project metadata, statistics (processes, event sources, activities, transitions, shared resources, global variables)
- Process list with starter type, activity count, transition count — displayed as a **collapsible directory tree** grouped by folder path (collapsed by default)
- Global variable list as a **collapsible directory tree** grouped by substvar file/folder (collapsed by default)
- Shared resource summary table
- Dependency table (all Maven dependencies)

**Per-process page** shall include:
- **Description card** (if process description is present in the XML)
- **SVG process diagram** using x/y coordinates from the `.process` XML:
  - Activity boxes rendered with **palette-specific icons** (see §6.13.1) at their native positions
  - Activity groups (colored grouping boxes with labels) rendered as semi-transparent rectangles
  - Canvas labels rendered as floating text elements
  - Transitions as directed arrows, colour-coded and labelled by condition type (always / success / error / successWithCondition / otherwise)
  - Condition expressions on conditional transitions
- **Event source** configuration table
- **Activity table**: name, type; `CallProcess` activities link to the target process page
- **Transition table**: from, to, condition (badge + expression)
- **Data mapping table**: target field, source XPath, literal flag — one table per activity, 3-column layout in HTML
- **Shared resources used** (connections referenced by activities in this process) — cross-reference section
- **Global variables used** (GVs referenced via `%%VAR%%` or `$_globalVariables/` XPath) — cross-reference section

**Per-shared-resource page** shall include:
- Configuration table (passwords/secrets redacted as `[hidden]`)
- "Used by" list with links to the process pages that reference this resource

**Optional Markdown output** (`-Dbw5.site.generateMarkdown=true`):
- Generates `index.md` and `processes/*.md` alongside the HTML files using the same structure

**Optional PDF export** (`-Dbw5.site.generatePdf=true`):
- Produces `bw5-doc.pdf` in `target/site/bw5/`
- Self-contained PDF with: cover page (TIBCO blue branding, project coordinates, description, date), table of contents, project overview section, one section per process with process diagram, one section per shared resource
- Process diagrams are pre-rendered from SVG to PNG (via Apache Batik `PNGTranscoder`) so activity icons (data URI GIFs/PNGs) survive Batik's security model — `data:` URIs are first converted to temp files to work around Java's lack of `data:` URL protocol support
- Falls back to simplified rectangle rendering per activity if PNG transcoding fails
- Password/secret fields redacted in shared resource config tables

**Non-functional requirements for documentation:**
- Pure HTML + CSS, no external dependencies
- Under 10 seconds for 50 processes
- Integrates with `mvn site` lifecycle; also runs standalone with `mvn bw5:site`

#### 6.13.1 Activity Icon Registry

The `ActivityIconRegistry` class provides base64-encoded SVG icons for all standard BW5 palette activities. Icons are embedded directly in the SVG diagram as `<image>` elements (data URIs), requiring no external resources.

**Coverage (implemented in v1.0):**

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

Icons are 32×32px SVG shapes encoded as base64 data URIs. The registry provides a fallback generic activity icon for unrecognised types. Lookup is done by both the full TIBCO activity type (e.g., `com.tibco.plugin.http.HTTPEventSource`) and the shorter palette resource type (e.g., `httppalette.httpEventSource`).

---

### 6.14 Project Initialisation (`bw5:init`)

The `init` goal generates an initial `pom.xml` for an existing BW5 project or library directory. It is a **bootstrap goal** that runs without a project and is the recommended first step when onboarding any BW5 project into Maven.

#### Detection logic

The goal scans the target directory for TIBCO Designer descriptor files to determine the correct Maven packaging type:

| Descriptor found | Packaging type |
|---|---|
| `*.archive` file in project root | `bwear` |
| `*.libbuilder` file in project root or `Library/` subdirectory | `projlib` |
| `AESchemas/` directory or `vcrepo.dat` file | `bwear` (with a warning that no descriptor was found) |
| None of the above | Error with actionable message |

When both a `.archive` and a `.libbuilder` file are found, the `.archive` takes precedence (the project is an EAR).

#### Auto-detected defaults

| Field | Source | Fallback |
|---|---|---|
| `artifactId` | EAR/library name from descriptor, normalised (lowercase, spaces→hyphens) | Descriptor filename without extension |
| `groupId` | — | `com.tibco` |
| `version` | — | `1.0.0-SNAPSHOT` |
| `packaging` | From descriptor as above | — |

Users override any field via command-line properties (`-DgroupId=...`, `-DartifactId=...`, `-Dversion=...`).

#### Dependency stubs from `.designtimelibs`

If a `.designtimelibs` file is present, the generated `pom.xml` includes commented-out `<dependency>` blocks — one per projlib path in the file. The `artifactId` is derived from the projlib filename; `groupId` and `version` are left as `TODO` placeholders for the developer to fill in once the libraries have been published to a Maven repository.

#### Safety behaviour

- If `pom.xml` already exists and `bw5.init.force` is `false` (default), the goal fails with a clear error message rather than silently overwriting.
- Set `bw5.init.force=true` to overwrite.
- **Warning — `force=true` rewrites from scratch.** The existing `pom.xml` is not read. Any coordinate not explicitly re-supplied via `-D` (`artifactId`, `version`, etc.) is re-derived from the descriptor file name or reset to its default. Always re-supply every previously overridden value when using `force=true`, or edit the `pom.xml` directly instead.

#### Key parameters

| Parameter | Property | Default | Description |
|---|---|---|---|
| `groupId` | `groupId` | `com.tibco` | Maven groupId |
| `artifactId` | `artifactId` | Auto-detected from descriptor name | Maven artifactId |
| `version` | `version` | `1.0.0-SNAPSHOT` | Maven version |
| `packaging` | `packaging` | Auto-detected | Override packaging type: `bwear` or `projlib` |
| `projectDir` | `bw5.init.projectDir` | `${basedir}` | BW5 project directory to scan |
| `force` | `bw5.init.force` | `false` | Overwrite existing `pom.xml` |

#### Usage

```bash
# Minimum — run from inside the BW5 project directory
mvn com.tibco.bw:bw5-maven-plugin:1.0.0-SNAPSHOT:init

# Override coordinates
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
    <version>1.0.0-SNAPSHOT</version>
    <extensions>true</extensions>
</plugin>

<!-- Deploy plugin (separate artifact, used only in deployment pipelines) -->
<plugin>
    <groupId>com.tibco.bw</groupId>
    <artifactId>bw5-deploy-plugin</artifactId>
    <version>1.0.0-SNAPSHOT</version>
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
| **`bw5:prepare-jcf-bytecode` injects bytecode into `.javaxpath`** | Base64-encoded bytecode is stored as a Maven property during `process-classes` and injected into the archive during packaging, avoiding any need for a TIBCO-specific build step. |
| **`mvn deploy` targets Maven repository only** | Deployment is an explicit operational step, not part of the build. Separation enforced at the artifact level (two plugins). |
| **Uniform deploy interface across environments** | Same Maven goal, same lifecycle position, same property naming convention regardless of target. Reduces operator training and pipeline divergence. |
| **`bwear` / `projlib` packaging types aligned with BW6** | Consistency for multi-generation teams; `projlib` uses the native BW5 term (more discoverable than `bwmodule`). |
| **SVG diagrams from native coordinates + icon registry** | TIBCO Designer stores x/y activity positions in `.process` XML. Combined with a palette icon registry (base64 SVGs), the plugin generates diagrams that visually match the Designer canvas. Process groups and canvas labels are also rendered. |
| **PDF diagrams pre-rendered to PNG via Batik** | Batik cannot load `data:` URIs directly (Java's URL class has no `data:` protocol handler). Icons are decoded to temp files, then Batik's `PNGTranscoder` renders the SVG to PNG using `RelaxedExternalResourceSecurity`. The PNG is base64-embedded in the PDF as an `<img>` element. |
| **No lib.zip in EAR** | Production BW5 EARs reference JARs/projlibs via FileAliases — they are not bundled. The plugin replicates this model. |
| **Multi-PAR and AAR driven by `.archive` descriptor, not pom.xml** | The TIBCO Designer `.archive` file already defines the archive split (`<processArchive>`, `<adapterArchive>`) and is the authoritative source — reading it keeps the plugin compatible with Designer without requiring developers to duplicate the configuration in the pom.xml. |
| **Transitive dependency BFS when `.archive` present** | Only files reachable from process entry points are included in the archive, matching the behaviour of `buildear` without invoking it. |
| **Designer isolation via `target/.TIBCO/Designer5.prefs`** | Prevents `bw5:designer-setup` from corrupting the developer's global Designer preferences. The engine JVM is started with `-Duser.home=target`. |
| **SpotBugs + PMD + OWASP Dependency Check** | Static analysis integrated into the `verify` phase. SpotBugs (effort=Max, threshold=Low) and PMD (best-practices, error-prone, performance rulesets) run on every build. OWASP dependency vulnerability scanning is skipped by default (slow NVD download) and enabled on demand with `-Dodc.skip=false`. |

### 8.4 Technology Stack

| Concern | Technology | Version |
|---|---|---|
| Maven Plugin API | `maven-plugin-api` | 3.8.1 |
| Maven Core | `maven-core` | 3.8.1 |
| XML Processing | JDOM2 | 2.0.6.1 |
| File Operations | Apache Commons IO | 2.14.0 |
| PDF generation | OpenHTMLtoPDF | 1.0.10 |
| SVG-to-PNG transcoding | Apache Batik (via openhtmltopdf-svg-support) | bundled with OpenHTMLtoPDF 1.0.10 |
| REST Client (deploy plugin) | Jersey Client or Apache HttpClient | TBD |
| JSON/YAML (deploy plugin) | Jackson | 2.15+ |
| Static analysis | SpotBugs 4.8.6.4, PMD 3.21.2 | — |
| Vulnerability scanning | OWASP Dependency Check 10.0.3 | — |
| Java Target | Java 11 | — |
| Minimum Maven | Maven 3.8.1 | — |

### 8.5 Package Structure

```
com.tibco.bw.maven.plugin
├── packaging/          ← Core MOJOs + base class (12 classes)
│   ├── AbstractBw5Mojo       — base class: dependency resolution, GV prefix utilities
│   ├── InitMojo              — bw5:init — pom.xml generation
│   ├── InitializeMojo        — bw5:initialize — directory setup
│   ├── CopyBwSourcesMojo     — bw5:copy-bw-sources
│   ├── ResolveDependenciesMojo — bw5:resolve-dependencies
│   ├── BwEarMojo             — bw5:bwear — EAR assembly + transitive analysis
│   ├── Bw5ModuleMojo         — bw5:bw5module — projlib assembly
│   ├── ValidateMojo          — bw5:validate
│   ├── ConfigureMojo         — bw5:deploy-config
│   └── RunBwMojo             — bw5:run
├── compile/            ← Java extraction MOJOs (2 classes)
│   ├── ExtractJavaSourcesMojo — bw5:extract-java-sources
│   └── PrepareJcfBytecodeMojo — bw5:prepare-jcf-bytecode
├── designer/           ← Designer alignment (1 class)
│   └── PullMojo              — bw5:designer-setup
├── descriptor/         ← Parsers and generators (10 classes)
│   ├── ArchiveDescriptorParser, LibBuilderParser, ProcessParser
│   ├── SubstVarParser, SubstVarWriter, PropertyMerger
│   ├── DesignTimeLibsParser, AliasLibParser
│   ├── DeploymentConfigGenerator, TibcoXmlGenerator
└── doc/                ← Documentation (11 classes)
    ├── Bw5SiteMojo           — bw5:site
    ├── ProcessDocParser, ProcessDocModel
    ├── SharedResourceParser, SharedResourceModel
    ├── SiteHtmlGenerator, SiteMarkdownGenerator, SitePdfGenerator
    ├── SvgDiagramGenerator
    ├── ActivityIconRegistry
    └── PluginRegistry
```

---

## 9. Example Usage

### 9.1 Projlib with Custom Functions

```xml
<packaging>projlib</packaging>

<dependencies>
    <dependency>
        <groupId>org.apache.commons</groupId>
        <artifactId>commons-lang3</artifactId>
        <version>3.18.0</version>
    </dependency>
</dependencies>
```

```bash
mvn package   # → target/common-framework-2.1.0.projlib (includes compiled Custom Functions)
mvn deploy    # → published to Nexus/Artifactory
```

### 9.2 Multi-PAR BW5 Application

The archive split is defined in the TIBCO Designer `.archive` descriptor already present in the project:

```xml
<!-- MyApp.archive (committed alongside the BW project) -->
<Repository:repository xmlns:Repository="http://www.tibco.com/xmlns/repo/types/2002">
    <enterpriseArchive>
        <name>MyApp</name>
        <processArchive name="OrderService">
            <processProperty>/Services/Order/ReceiveOrder.process</processProperty>
        </processArchive>
        <processArchive name="PaymentService">
            <processProperty>/Services/Payment/ProcessPayment.process</processProperty>
        </processArchive>
        <sharedArchive name="Shared Archive"/>
    </enterpriseArchive>
</Repository:repository>
```

```xml
<!-- pom.xml — just point to the descriptor -->
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
            <version>1.0.0-SNAPSHOT</version>
            <extensions>true</extensions>
            <configuration>
                <archiveDescriptorFile>${basedir}/MyApp.archive</archiveDescriptorFile>
            </configuration>
        </plugin>
    </plugins>
</build>
```

```
mvn package  →  OrderService.par + PaymentService.par + Shared Archive.sar inside the EAR
```

### 9.3 Documentation Generation

```bash
# HTML only (default)
mvn bw5:site

# HTML + Markdown
mvn bw5:site -Dbw5.site.generateMarkdown=true

# HTML + PDF (includes cover page, TOC, diagrams as PNG images)
mvn bw5:site -Dbw5.site.generatePdf=true

# All three formats
mvn bw5:site -Dbw5.site.generateMarkdown=true -Dbw5.site.generatePdf=true
```

### 9.4 CI/CD Pipeline

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

### 10.1 Build Plugin Parameters (`bw5-maven-plugin` — common)

| Parameter | Property | Default | Description |
|---|---|---|---|
| `bwProjectPath` | `bw5.bwProjectPath` | `${basedir}` | Root directory of BW5 project sources |
| `archiveName` | `bw5.archiveName` | `${project.artifactId}` | Application name used in `TIBCO.xml` and `manifest-bw5.json` — does **not** control the PAR filename |
| `archiveDescriptorFile` | `bw5.archiveDescriptorFile` | — | Path to TIBCO Designer `.archive` descriptor; drives PAR/AAR naming, entry points, transitive analysis, multi-PAR and AAR assembly |
| `includeSharedArchive` | `bw5.includeSharedArchive` | `true` | Whether to include the SAR in the EAR |
| `sharedArchiveName` | — | `Shared Archive` | SAR file name |
| `earOnly` | `bw5.earOnly` | `false` | Assemble EAR only; skip deploy config file generation |
| `generateDeployXml` | `bw5.generateDeployXml` | `true` | Generate `<finalName>-deploy.xml` |
| `generateProperties` | `bw5.generateProperties` | `true` | Generate `<finalName>-deploy.properties` |
| `generateValuesYaml` | `bw5.generateValuesYaml` | `true` | Generate `values.yaml` |
| `globalPropertiesFile` | `bw5.deployConfig.globalPropertiesFile` | — | Global property overrides |
| `projectPropertiesFile` | `bw5.deployConfig.projectPropertiesFile` | — | Project-specific property overrides |
| `archiveDescriptorFile` | `bw5.archiveDescriptorFile` | — | Optional TIBCO Designer `.archive` descriptor file |
| `includeFolderMetadata` | `bw5.includeFolderMetadata` | `false` | Include `.folder` Designer metadata files in the archive (default matches `buildEAR`) |
| `oldJavaCustomFunctions` | — | `false` | Use pre-existing bytecode in `.javaxpath` files (legacy mode) |
| `skipResolveDependencies` | `bw5.bwear.skipResolveDependencies` | `false` | Skip resolving deps to `target/bw-lib` before EAR assembly |
| `skip` | `bw5.skip` | `false` | Skip all plugin goals |

### 10.1a `bw5:run` Parameters

| Parameter | Property | Default | Description |
|---|---|---|---|
| `tibcoHome` | `tibco.Home` | *(required)* | TIBCO installation root |
| `bwVersion` | `bw5.bwVersion` | `5.13.0` | BW5 version string |
| `propertiesFile` | `bw5.run.propertiesFile` | — | User properties file merged into `target/bwengine.properties` |
| `background` | `bw5.run.background` | `false` | Run engine in background |
| `startupWaitSeconds` | `bw5.run.startupWaitSeconds` | `30` | Seconds to wait when `background=true` |
| `domainHome` | `bw5.run.domainHome` | — | Domain home directory (`-d` flag) |
| `extraArgs` | — | — | Extra `bwengine` arguments |
| `workingDir` | `bw5.run.workingDir` | `${project.build.directory}` | Engine working directory |
| `skipResolveDependencies` | `bw5.run.skipResolveDependencies` | `false` | Skip resolving deps before starting the engine |

### 10.1b `bw5:validate` Parameters

| Parameter | Property | Default | Description |
|---|---|---|---|
| `failOnError` | `bw5.validate.failOnError` | `false` | Fail build on Error-severity issues |
| `failOnWarning` | `bw5.validate.failOnWarning` | `false` | Fail build on Warning-severity issues |
| `showUnusedGVars` | `bw5.validate.showUnusedGVars` | `false` | Enable `GVAR_UNUSED` check |
| `skipXPath` | `bw5.validate.skipXPath` | `false` | Skip XPath expression validation |
| `skipResolveDependencies` | `bw5.validate.skipResolveDependencies` | `false` | Skip resolving deps before validation |

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
| `siteOutputDir` | `bw5.siteOutputDir` | `${project.build.directory}/site/bw5` | Output directory for generated documentation |
| `generateMarkdown` | `bw5.site.generateMarkdown` | `false` | Also generate Markdown files alongside HTML |
| `generatePdf` | `bw5.site.generatePdf` | `false` | Generate `bw5-doc.pdf` alongside HTML |

### 10.4 `bw5:init` Parameters

| Parameter | Property | Default | Description |
|---|---|---|---|
| `groupId` | `groupId` | `com.tibco` | Maven groupId |
| `artifactId` | `artifactId` | Auto-detected | Maven artifactId |
| `version` | `version` | `1.0.0-SNAPSHOT` | Maven version |
| `packaging` | `packaging` | Auto-detected | Override: `bwear` or `projlib` |
| `projectDir` | `bw5.init.projectDir` | `${basedir}` | BW5 project directory to scan |
| `force` | `bw5.init.force` | `false` | Overwrite existing `pom.xml` |

### 10.5 Deploy Plugin Parameters (`bw5-deploy-plugin`)

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

1. **No changes to BW project files**: `.process`, `.substvar`, `.sharedhttp` etc. are read but never modified (unless `updateSubstVarFiles=true` is explicitly set).
2. **Migration from `tibco-bwmaven`**: Replace the old plugin declaration. Change `<packaging>bw-ear</packaging>` → `<packaging>bwear</packaging>`. Remove `TIBCO_HOME` and binary configuration.
3. **Source layout**: By default `bwProjectPath` is `${basedir}` — `pom.xml` sits alongside the BW project files. If the project uses a nested layout (e.g. `src/main/bw/`), set `<bwProjectPath>${basedir}/src/main/bw</bwProjectPath>`.

### 11.2 BW6 Alignment

| Concept | BW5 (`bw5-maven-plugin`) | BW6 (`bw6-plugin-maven`) |
|---|---|---|
| Application packaging | `bwear` → `.ear` | `bwear` → `.ear` |
| Library packaging | `projlib` → `.projlib` | `bwmodule` → `.jar` |
| Goal prefix | `bw5:` | `bw6:` |
| Source path | `src/main/bw` (optional convention) | `src/main/java` |
| Deploy lifecycle | `mvn deploy` → Maven repo | `mvn deploy` → Maven repo |
| Runtime deployment | `bw5-deploy-plugin` | `bw6-deploy-plugin` / TCI goals |

---

## 12. Open Items & Future Work

The following items are out of scope for v1.0:

| Item | Priority | Notes |
|---|---|---|
| **bw5-deploy-plugin** | High | Companion deploy plugin not yet implemented; §7 describes the design |
| **Maven archetype** | Medium | `mvn archetype:generate` templates for new `bwear` and `projlib` projects |
| **Integration test support** | Medium | `mvn integration-test` with local BW engine execution; opt-in, requires BW engine install |
| **Enhanced mapping visualisation** | Medium | Graphical source→target mapping diagrams (Sankey-style) for complex XSLT |
| **WSDL/service documentation** | Low | Service contract docs from `.wsdl` and `.serviceagent` files in `bw5:site` |
| **Control Tower API implementation** | Blocked | Depends on Control Tower Platform API specification (§7.4) — implement when API is published |
| **BW engine unit testing framework** | Low | Run individual BW processes as JUnit tests without full domain setup |

---

## 13. Acceptance Criteria — v1.0.0

- [x] `mvn clean package` on a single-PAR `bwear` project produces a valid `.ear` deployable to TIBCO Administrator, **no TIBCO software on build machine**
- [x] `mvn clean package` on a multi-PAR `bwear` project (multiple `<processArchive>` in `.archive` descriptor) produces an EAR with one correctly-structured PAR per archive element, each with its own TIBCO.xml
- [x] `mvn clean package` on an adapter `bwear` project (`<adapterArchive>` in `.archive` descriptor) produces a valid EAR with an `.aar` archive and correct EAR-level TIBCO.xml module entries
- [x] A mixed EAR (both `<processArchive>` and `<adapterArchive>`) produces all archive types in the same EAR
- [x] `mvn clean package` on a `projlib` project produces a valid `.projlib`
- [x] A `.projlib` published to Nexus/Artifactory is resolvable as `<dependency type="projlib">` in another project
- [x] Java Code activities are correctly extracted, compiled, and bundled in the artifact
- [x] Java Custom Functions in `src/main/java` are compiled, bytecode is Base64-injected into `.javaxpath`, and bundled in the artifact
- [x] `mvn bw5:validate` reports XML errors, missing GVars, and XPath syntax issues without requiring a TIBCO installation; does not block EAR generation by default
- [x] `mvn bw5:validate -Dbw5.validate.failOnError=true` fails the build when structural errors are found
- [x] `mvn bw5:designer-setup` followed by opening the project in TIBCO Designer resolves all dependencies without manual file copying
- [x] `mvn bw5:site` produces an HTML site with SVG diagrams using palette-specific activity icons for all standard activity types, with collapsible process/GV trees and per-process GV/SR cross-references
- [x] `mvn bw5:site -Dbw5.site.generatePdf=true` produces a PDF with activity icons rendered as PNG images
- [x] `mvn bw5:site -Dbw5.site.generateMarkdown=true` produces Markdown documentation alongside HTML
- [ ] `bw5-deploy:deploy -Dbw5.deploy.environment=administrator` deploys the EAR to a TIBCO Administrator domain _(requires `bw5-deploy-plugin` — not yet implemented)_
- [ ] `bw5-deploy:deploy -Dbw5.deploy.environment=container` deploys via Platform API REST _(requires `bw5-deploy-plugin` — not yet implemented)_
- [x] All build goals complete on vanilla JDK 11 + Maven 3.8.1 with no TIBCO software installed
- [x] A standard CI pipeline (`mvn clean deploy`) publishes to a Maven repository without TIBCO tools

---

*This document reflects the implementation state as of 2026-06-22. All v1.0 build goals are implemented and validated against TIBCO Designer reference EARs across 15 real BW5 projects. The two remaining unchecked acceptance criteria (§13) require the `bw5-deploy-plugin` companion artifact, which is not yet implemented (see §7 and §12).*

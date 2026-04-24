---
marp: true
theme: default
paginate: true
style: |
  section {
    font-size: 1.1em;
  }
  section.lead h1 {
    font-size: 2em;
  }
  table {
    font-size: 0.85em;
  }
  code {
    font-size: 0.85em;
  }
---

<!-- _class: lead -->

# BW5 Maven Plugin
## Modern Build Tooling for TIBCO BusinessWorks 5.x

**Version 1.3 · April 2026**
TIBCO BW5 Community

---

## Executive Summary

- **Full Maven lifecycle for BW5** — dependency management, packaging, documentation, and CI/CD — **zero TIBCO installation required on build agents**
- **Replaces proprietary `buildear`** with a pure-Java implementation that runs on any standard CI server (Jenkins, GitHub Actions, GitLab CI)
- **Two focused artifacts**: `bw5-maven-plugin` (build & package) + `bw5-deploy-plugin` (runtime deployment) — clean separation of concerns
- **Aligned with the official BW6 plugin** — same packaging conventions, same lifecycle model, one mental model for mixed BW5/BW6 teams

---

## The Problem with Today's BW5 Tooling

| Problem | Business Impact |
|---|---|
| `buildear` / `buildlibrary` must be on every build agent | Full TIBCO licensed installation on every CI node — expensive and fragile |
| `mvn deploy` ships to TIBCO Administrator, not Maven repo | Breaks standard artifact promotion pipelines (Nexus, Artifactory) |
| Projlibs stored as binary blobs in Git | No versioning, no reproducibility, no dependency graph |
| Java compilation inside a proprietary black box | Untestable, unobservable, breaks IDE support |
| FastConnect plugin last updated in 2014 | Java 6 target, Maven 3.1 API, unsupported dependencies |
| No deployment parity across BW5 environments | Administrator, containers, and Control Tower each require bespoke scripting |

---

## What Engineering Teams Need

- Build BW5 EARs on **any standard CI server** — no TIBCO software, no license
- Manage projlib dependencies via **Maven coordinates** (`groupId:artifactId:version`)
- `mvn deploy` publishes to a **Maven repository** — Nexus, Artifactory, AWS CodeArtifact
- Runtime deployment as an **explicit, auditable, separate step**
- **One unified deployment interface** across Administrator, BW5 Containers, and Control Tower
- Java Code activities compiled with **standard JDK tooling** — full IDE and test support
- **Living documentation** auto-generated from process definitions — always in sync

---

## Who Benefits

| Persona | Today's Pain | With `bw5-maven-plugin` |
|---|---|---|
| **BW5 Developer** | Projlibs missing after checkout | `bw5:designer-setup` stages everything automatically |
| **DevOps / Build Engineer** | `buildear` cannot run in a container | Pure-Java EAR assembly, no TIBCO on the agent |
| **Platform / Ops Engineer** | 3 BW5 runtimes = 3 different scripts | `bw5-deploy-plugin` unifies the deployment interface |
| **Architect / Tech Lead** | Process docs always out of date | `bw5:site` generates SVG diagrams + HTML from `.process` files |
| **BW5 → BW6 Migration Lead** | Two incompatible build paradigms | Same packaging model and lifecycle as BW6 |

---

## Two Plugins, One Clean Architecture

```
bw5-maven-plugin          bw5-deploy-plugin
(build · package)         (runtime deployment)
──────────────────        ─────────────────────
bw5:bwear       ─────▶   bw5-deploy:deploy
bw5:projlib               bw5-deploy:start / stop
bw5:validate              bw5-deploy:restart
bw5:run  (dev only)       bw5-deploy:status
bw5:site
bw5:designer-setup
bw5:init
```

- The **build plugin** never touches a TIBCO runtime
- The **deploy plugin** is the only artifact that may carry TIBCO tooling dependencies
- `bw5:run` is a **developer inner-loop aid** — not a CI/CD deployment mechanism

---

## Maven Lifecycle — `bwear` Packaging

| Maven Phase | Goal |
|---|---|
| `initialize` | `bw5:initialize` — validates project structure, creates working directories |
| `generate-sources` | `bw5:copy-bw-sources`, `bw5:extract-java-sources` |
| `process-resources` | `bw5:resolve-dependencies` — projlibs/JARs → `target/bw-lib` |
| `compile` | `maven-compiler-plugin:compile` — Java Code activities & Custom Functions |
| `package` | **`bw5:bwear`** — EAR + `deploy.xml` + `deploy.properties` + `values.yaml` |
| `verify` | **`bw5:validate`** — static analysis: XML, GVars, XPath syntax |
| `install` / `deploy` | Standard Maven — publishes to your Maven repository |

---

## EAR Assembly — `bw5:bwear`

**No `buildear`. No TIBCO on the build agent. Ever.**

- PAR(s) + SAR + `TIBCO.xml` assembled using `java.util.zip` — pure Java
- **Single-PAR** (default): one PAR named `${artifactId}`
- **Multi-PAR**: archive assignment via Ant-style glob patterns in `pom.xml` — no proprietary binary descriptor
- **Adapter Archive (AAR)**: auto-detected from adapter descriptor files
- Projlibs and JARs registered as **FileAliases** in `TIBCO.xml` — never bundled inside the EAR
- Generates `deploy.xml`, `deploy.properties` and `values.yaml` as part of every `mvn package`
- **Automatic dependency resolution** before assembly — opt out with `skipResolveDependencies=true`

---

## Projlib Dependency Management

```xml
<!-- Declare a projlib just like any other Maven dependency -->
<dependency>
    <groupId>com.example</groupId>
    <artifactId>common-framework</artifactId>
    <version>2.1.0</version>
    <type>projlib</type>
</dependency>
```

- Projlibs published to **Nexus / Artifactory** as first-class Maven artifacts
- **Transitive dependency resolution** via standard Maven — no manual file management
- `bw5:resolve-dependencies` copies to `target/bw-lib` with convention naming: `artifactId-version.projlib`
- **`bw5:designer-setup`**: idempotent staging to `.designer-libs/` + updates `.designtimelibs` so TIBCO Designer opens the project without any manual steps

---

## Java Compilation — Code Activities & Custom Functions

**Java Code Activities:**
- Sources extracted from `.process` files → `target/generated-sources/bw-java`
- Compiled by standard `maven-compiler-plugin` — full IDE support, unit-testable output
- Classes packaged into the PAR under `JavaCode/`

**Java Custom Functions:**
- Sources live under `src/main/java/` — standard Maven layout, no special extraction step
- Compiled with standard JDK, bundled under `CustomFunctions/` in the artifact
- Also published as a classified Maven artifact (`custom-functions`) for compile-time reuse in downstream projects

**Result:** Java compilation is a transparent, observable, IDE-friendly step — not a black box.

---

## Static Validation — `bw5:validate`

**Catch issues early, without TIBCO installed.** Runs at `verify` phase. Warns by default — never blocks the EAR build unless explicitly configured.

| Check Code | What It Validates | Severity |
|---|---|---|
| `XML` | Well-formedness of `.process`, `.substvar`, `.archive`, schemas | Error |
| `ARCHIVE` | Archive descriptor exists and references a valid PAR | Error |
| `PROCESS_NAME` / `DUPLICATE` | Process naming rules, no duplicates | Warn / Error |
| `GVAR` | Every `%%VAR%%` reference declared in a `.substvar` file | Warning |
| `DEP` | All projlib dependencies resolvable from Maven repo | Error |
| `XPATH` | XPath expression syntax using the full BW5 function catalog | Warning |

```bash
mvn bw5:validate                                  # warn-only (default, never blocks)
mvn bw5:validate -Dbw5.validate.failOnError=true  # break the build on errors
```

---

## `bw5:validate` — XPath & BW5 Function Catalog

- Ships a **built-in catalog of 63 TIBCO custom XPath functions** extracted from BW 5.16 `mapper.jar`
- Categories: string, date/time, binary, number, logical, set — each with **exact arity checking**
- Standard XPath 1.0 functions and Saxon-supported XPath 2.0 functions are also accepted
- Unknown function or wrong argument count → `XPATH` warning

**What is intentionally out of scope** (requires a TIBCO runtime):
- Resource connectivity validation (JDBC, JMS, RV transports)
- Schema validation against TIBCO XSD types
- `CallProcess` reference resolution across the process graph
- Adapter-specific configuration validation

---

## Local Development — `bw5:run`

Start a BW5 engine **directly against the project sources** — no EAR required.

```bash
# Run in foreground (blocks until engine exits)
mvn bw5:run

# Run in background (Maven returns after engine startup)
mvn bw5:run -Dbw5.run.background=true

# Inject global variable overrides at runtime
mvn bw5:run -Dbw5.run.propertiesFile=config/local.properties
```

**Generated engine command:**
```
bwengine --propFile bwengine.tra -n <artifactId> \
         -p target/bwengine.properties <bwProjectPath>
```

- `target/bwengine.properties` auto-generated: **projlib aliases** (Maven coordinate format with `\:`-escaped colons) + JAR aliases + user-provided property overrides
- Configure `tibcoHome` and `bwVersion` in `~/.m2/settings.xml` — keep them out of the project POM

---

## Process Documentation — `bw5:site`

**Auto-generated, always in sync, zero manual effort.**

- **SVG process diagrams** using native x/y coordinates from `.process` XML + palette icon registry (base64-embedded, no external resources)
- Transitions rendered as directed arrows, **colour-coded by type**: always / success / error / conditional
- Per-process pages: activity table, transition table, data mapping table
- Project overview: statistics (processes, starters, activities, projlib deps), full process list

```bash
mvn site          # integrated with standard Maven site lifecycle
mvn bw5:site      # standalone — no full build required
```

Covers all standard BW5 palette types: Timer, HTTP, JMS, JDBC, Mail, Java, File, SOAP, RV, XML, Core, Adapters.

---

## Project Onboarding — `bw5:init` & `bw5:designer-setup`

**`bw5:init`** — generate a correct `pom.xml` from any existing BW5 project in seconds:
- Auto-detects packaging type: `.archive` → `bwear` / `.libbuilder` → `projlib`
- Generates commented-out `<dependency>` stubs from `.designtimelibs` — just fill in the GAV
- Safe by default: fails if `pom.xml` already exists (override with `bw5.init.force=true`)

```bash
# Four steps to Maven-enable any BW5 project
git clone https://repo.example.com/MyService.git && cd MyService
mvn com.tibco.bw:bw5-maven-plugin:1.0.0:init -DgroupId=com.example
# Edit pom.xml — fill in dependency coordinates
mvn bw5:designer-setup    # TIBCO Designer can now open the project
mvn package               # First clean build — no TIBCO on the machine
```

---

## Deployment Plugin — `bw5-deploy-plugin`

**One interface, three runtime environments:**

```bash
mvn bw5-deploy:deploy -Dbw5.deploy.environment=administrator   # Classic on-prem
mvn bw5-deploy:deploy -Dbw5.deploy.environment=container       # BW5 Containers / REST
mvn bw5-deploy:deploy -Dbw5.deploy.environment=control-tower   # Control Tower on-prem
```

| Capability | Administrator | Containers | Control Tower |
|---|---|---|---|
| Deploy / Undeploy / Start / Stop / Restart | ✅ | ✅ | ✅ |
| Application status | ✅ via Hawk | ✅ | ✅ |
| Scale (replicas) | ❌ N/A | ✅ | ✅ |
| No TIBCO tools on deployment agent | ❌ requires `TIBCO_HOME` | ✅ REST only | ✅ REST only |

---

## Technical Architecture — Key Decisions

| Decision | Rationale |
|---|---|
| **EAR assembled with `java.util.zip`** | Eliminates `buildear`; BW5 processes are XML interpreted at runtime — no proprietary compilation step needed |
| **Standard `maven-compiler-plugin` for Java** | Full IDE support, testable output, no black-box compilation |
| **`mvn deploy` targets Maven repo only** | Deployment is an operational step, not a build artifact — separation enforced at plugin level |
| **Uniform deploy interface across all environments** | Reduces operator training, eliminates environment-specific scripting |
| **SVG diagrams from native coordinates + icon registry** | Designer stores x/y activity positions in `.process` XML — diagrams faithfully match the Designer canvas |
| **Multi-PAR via `pom.xml` config, not binary descriptor** | Transparent, version-controlled, no proprietary format parsing |

**Stack:** Java 11 · Maven 3.6.3+ · JDOM2 · Apache Commons IO · JDK XPath API

---

## Migration from `tibco-bwmaven`

**Minimal changes to adopt — BW project files are never modified.**

```xml
<!-- Before (FastConnect plugin) -->
<packaging>bw-ear</packaging>
<plugin>
    <groupId>com.tibco.bwmaven</groupId>
    <artifactId>bwmaven-plugin</artifactId>
    <!-- Required TIBCO_HOME, buildear, appmanage on every agent -->
</plugin>

<!-- After -->
<packaging>bwear</packaging>
<plugin>
    <groupId>com.tibco.bw</groupId>
    <artifactId>bw5-maven-plugin</artifactId>
    <extensions>true</extensions>
    <!-- No TIBCO software on the build agent -->
</plugin>
```

All `.process`, `.substvar`, `.sharedhttp` and other BW project files are **read-only** — never touched by the plugin.

---

## Roadmap — v1.x Open Items

| Item | Priority | Notes |
|---|---|---|
| **Maven archetype** | High | `mvn archetype:generate` templates for new `bwear` and `projlib` projects |
| **Integration test support** | Medium | `mvn integration-test` with local BW engine; opt-in, requires engine install |
| **Enhanced mapping visualisation** | Medium | Graphical source→target mapping diagrams for complex XSLT |
| **WSDL / service documentation** | Low | Service contract docs from `.wsdl` and `.serviceagent` in `bw5:site` |
| **Control Tower API implementation** | Blocked | Pending Control Tower Platform API specification publication (§7.4) |

**Already delivered in v1.3:**
`bw5:validate` · BW5 XPath function catalog · `bw5:run` with auto-generated `bwengine.properties` · automatic dependency resolution in `bw5:bwear`

---

## Acceptance Criteria — v1.0.0

- `mvn clean package` on a `bwear` project produces a deployable EAR — **no TIBCO software on the build machine**
- `mvn clean package` on a `projlib` project produces a `.projlib` publishable to Nexus/Artifactory
- A published projlib is resolvable as `<dependency type="projlib">` in any downstream project
- Java Code activities correctly extracted, compiled, and bundled in the artifact
- `bw5:validate` reports XML errors, missing GVars, and XPath issues without TIBCO installed
- `bw5:validate -Dbw5.validate.failOnError=true` breaks the build on structural errors
- `bw5:site` generates HTML + SVG with palette-specific icons for all standard activity types
- `bw5-deploy:deploy` operates correctly against all three target environments
- A complete `mvn clean deploy` pipeline runs on a vanilla JDK 11 + Maven 3.6.3 agent — zero TIBCO tooling

---

<!-- _class: lead -->

## Next Steps

1. **Review and sign off on this PRD** — Product Management & Engineering alignment
2. **Implement core goals** — `bw5:bwear`, `bw5:projlib`, `bw5:resolve-dependencies`
3. **Pilot onboarding** — run `bw5:init` on a real BW5 project, validate the full build
4. **Reference CI/CD pipeline** — GitHub Actions end-to-end with no TIBCO on the agent
5. **Publish Maven archetype** — accelerate adoption for new `bwear` and `projlib` projects
6. **Track Control Tower API** — prerequisite to complete `bw5-deploy-plugin` for that environment

---

*Based on: `PRD-BW5-Maven-Plugin.md` v1.3 · April 2026*
*TIBCO BW5 Community — For internal review*

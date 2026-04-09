# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Overview

This directory contains symlinks to two separate Maven plugin projects for TIBCO BusinessWorks:

- `tibco-bwmaven` — FastConnect's Maven plugin for TIBCO BusinessWorks **5.x** (BW5)
- `bw6-plugin-maven` — TIBCO's official Maven plugin for TIBCO BusinessWorks **6 / Container Edition** (BW6/BWCE)

Both are Maven plugin projects (`packaging: maven-plugin`) written in Java, following standard Maven project conventions.

---

## tibco-bwmaven (BW5 Plugin)

**Source**: `tibco-bwmaven/`
**Java version**: 1.6
**Maven requirement**: 3.1+

### Build

```bash
cd tibco-bwmaven
mvn clean install                  # build core modules
mvn -P source clean install        # build with sources attached
mvn -P archetypes clean install    # build archetype modules
mvn site                           # generate documentation
```

### Module Structure

```
tibco-bwmaven/
├── bw-maven-plugin/                    # Core plugin (92 Java files)
├── bw-maven-assemblies/                # Archive distributions
├── bw-javaxpath-maven-plugin/          # XPath utility plugin
├── archetypes/                         # Maven project templates for BW5 projects
│   ├── archetype-bw-default-project
│   ├── archetype-bw-default-projlib
│   └── archetype-bw-mavenizer
├── tibco-application-management-schema/ # JAXB-generated classes from XSD (v5.7)
└── tibco-bw-process-model-schema/       # JAXB-generated classes from XSD (v5.9)
```

### Key Architecture (bw-maven-plugin)

All Mojo classes live under `src/main/java/fr/fastconnect/factory/tibco/bw/maven/`:

- **`packaging/`** — XML↔Properties conversion for TIBCO application archives; `ApplicationManagement` wraps JAXB-generated types; includes event monitoring classes
- **`source/`** — Mavenizer tools (`MassMavenizerMojo`, `MassUnMavenizerMojo`) to migrate BW5 projects to/from Maven; dependency management Mojos
- **`compile/`** — Build pipeline for BW5 `.ear` compilation
- **`deployment/`** — Deployment configuration and execution
- **`hawk/`** — TIBCO Hawk monitoring integration for deployed applications
- **`builtin/`** — Test support: `ResolveBWTestDependenciesMojo`, `CopyBWTestSourcesMojo`, `ITRunTestsMojo`
- **`bwengine/`** — BusinessWorks engine integration

JAXB schema generation is configured in `bw-maven-plugin/pom.xml` — it generates Java classes from `alias.xsd` and `bw-doc.xsd` during `generate-sources`.

---

## bw6-plugin-maven (BW6/BWCE Plugin)

**Source**: `bw6-plugin-maven/Source/bw6-maven-plugin/`
**Java version**: 11
**Maven requirement**: 3.6.2+

### Build

```bash
cd bw6-plugin-maven/Source/bw6-maven-plugin
mvn clean install
```

### Key Architecture

All Mojo classes live under `src/main/java/com/tibco/`:

- **`module/`** — `BWModulePackageMojo`: core OSGi-compliant bundle packaging
- **`application/`** — EAR packaging (`BWEARPackagerMojo`), deployment (`BWDeployMojo`, `BWDeploymentMojo`), installation, and JSON config generation
- **`test/`** (38 files) — Full BW test execution framework: `BWTestMojo`, DTOs for test cases/assertions/coverage, support for local and enterprise test environments, test suite parsing and reporting
- **`admin/`** — BW domain/server administration operations
- **`tci/`** — TIBCO Cloud Integration (TCI) support: `TCIDeployer`, DTOs for organizations, app status, properties
- **`osgi/`** — OSGi manifest parsing/writing and version handling
- **`platform/`** — Platform-specific deployment (Helm/Kubernetes for BWCE)
- **`lifecycle/`** — Custom Maven lifecycle binding definitions
- **`build/`** — `BuildProperties` / `BuildPropertiesParser` for BW design utility integration

Platform environment properties (Mac/Unix/Windows) are in `src/main/resources/com/tibco/resources/`.

### Multi-Platform Deployment

The plugin supports three deployment targets, each with distinct Mojo paths:
1. **BW6 ActiveMatrix** — traditional domain-based deployment via `admin/` Mojos
2. **BWCE** — Docker/Helm/Kubernetes via `platform/` Mojos
3. **TCI** — TIBCO Cloud via `tci/TCIDeployer` using REST (Jersey/Jackson)

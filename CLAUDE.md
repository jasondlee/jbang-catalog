# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A [JBang](https://www.jbang.dev/) catalog — a collection of standalone Java CLI utilities by Jason Lee. Each `.java` file in the root is a self-contained JBang script (no build system — dependencies and Java version are declared inline via `//DEPS` and `//JAVA` directives). The catalog is registered in `jbang-catalog.json`, which maps alias names to script files.

## Running Scripts

```bash
jbang <script>.java [args]          # run directly
jbang mvnsrch@jdlee [args]          # run via catalog alias (remote)
```

No build step — JBang compiles and caches on the fly.

## Scripts in the Catalog

Registered in `jbang-catalog.json`:

- **mvnsrch** — Search Maven Central (Aesh + Jackson). Uses Sonatype REST API.
- **startserver** — Manage WildFly/EAP server startup with CLI configuration (Aesh + Jash).
- **maven-dep-graph** — Build a DOT dependency graph from Maven coordinates (Aesh + maven-model).
- **base64** — Encode/decode files to/from Base64 (Aesh).

## Conventions

- Each script is a single `.java` file with a shebang-style `/// usr/bin/env jbang "$0" "$@" ; exit $?` first line.
- Dependencies are declared with `//DEPS group:artifact:version` comments at the top.
- Java version requirement is declared with `//JAVA 17+`.
- CLI framework: [Aesh](https://aesh.github.io/) 3.8 (all scripts).
- Class name must match the filename (JBang requirement).
- To add a new script to the catalog, add an entry in `jbang-catalog.json` under `aliases`.

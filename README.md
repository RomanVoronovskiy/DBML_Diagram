# DBML Diagram

[![CI](https://github.com/RomanVoronovskiy/DBML_Diagram/actions/workflows/ci.yml/badge.svg)](https://github.com/RomanVoronovskiy/DBML_Diagram/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2024.1--2026.1-purple.svg)](https://plugins.jetbrains.com/)

DBML Diagram is an open-source, offline IntelliJ Platform plugin that shows a realtime SVG entity-relationship diagram next to a `.dbml` file. It supports IntelliJ IDEA and DataGrip-compatible Platform IDEs. Marketplace publication is currently pending moderation.

## Features

- Standard IntelliJ text editor with **Code**, **Preview**, and **Code + Preview** modes.
- Debounced (300 ms), cancellable background parsing, layout, and SVG rendering.
- Last valid diagram remains visible while the document contains a parse error.
- Pan, Ctrl/Cmd + mouse-wheel zoom, Fit, zoom buttons, 100%, and manual Refresh.
- Full-diagram standalone SVG and PNG export. PNG rasterization uses Apache Batik and has a 16,384 px / 40 MP safety limit.
- Light and Darcula-aware diagram colors.
- DBML file type, icon, comments, and basic syntax highlighting.
- No browser window, network service, Node.js, database, CLI, or external executable is used at runtime.

## Build

Requirements: JDK 17 and network access for the first Gradle dependency download.

```bash
./gradlew test
./gradlew buildPlugin
```

The installable ZIP is produced under:

```text
dbml-intellij-plugin/build/distributions/
```

## Run sandbox IDE

```bash
./gradlew runIde
```

Open or create a `.dbml` file in the sandbox IDE. The editor mode selector is provided by IntelliJ's standard `TextEditorWithPreview` component.

## Install locally

1. Run `./gradlew buildPlugin`.
2. In IntelliJ IDEA or DataGrip, open **Settings | Plugins**.
3. Choose the gear menu, then **Install Plugin from Disk…**.
4. Select the ZIP from `dbml-intellij-plugin/build/distributions/` and restart the IDE.

## Architecture

```text
dbml-core
├── model       immutable schema and diagnostics
├── parser      tolerant, IntelliJ-independent DBML parser
├── layout      DiagramLayoutEngine + deterministic layered layout
└── renderer    DiagramRenderer + standalone SVG renderer

dbml-intellij-plugin
├── language    file type, commenter, lexer/highlighter
├── editor      standard text editor + preview composition
└── preview     debounce scheduler, JCEF UI, SVG/PNG export
```

The parser never calls the renderer. `DbmlSchema` is immutable and suitable for a future semantic diff. Layout is behind `DiagramLayoutEngine`, so ELK or another engine can replace the current dependency-free layered algorithm without changing parsing or preview code. JCEF receives only generated, self-contained HTML/SVG.

## Supported DBML subset

- `Project`, `Table`, schema-qualified and quoted table names, aliases, and table notes.
- Columns with arbitrary type strings, including parameterized numeric/varchar types.
- `[pk]`, `[not null]`, `[null]`, `[unique]`, `[increment]`, `[default: ...]`, and `[note: ...]`.
- Simple and composite `indexes` with `unique` and `name` settings.
- `Enum` values.
- `Ref:` and named `Ref { ... }` using `>`, `<`, `-`, and `<>` cardinalities.
- Reference settings such as `[delete: cascade]` and `[update: no action]` are accepted.
- Inline column `[ref: ...]` references.
- `//` line comments and `/* ... */` block comments.
- Warnings for duplicate tables/columns and unknown reference tables/columns.

## Known limitations

- This is a tolerant subset parser, not a complete DBML grammar or PSI implementation.
- The built-in layered layout is deterministic and fast, but does not perform advanced edge crossing minimization for very dense cyclic schemas.
- Relationship settings such as referential actions are currently ignored.
- JCEF must be enabled in the host IDE for the visual preview; the code editor remains available if JCEF is unavailable.
- Syntax highlighting is lexical only; navigation, completion, inspections, and refactoring are not included.
- A temporarily incomplete custom type is accepted when it is syntactically a valid type token; an unclosed block/settings/string is reported as an error.

## Contributing

Contributions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md), follow the [Code of Conduct](CODE_OF_CONDUCT.md), and report vulnerabilities according to [SECURITY.md](SECURITY.md).

The project is released under the [Apache License 2.0](LICENSE). Changes are tracked in [CHANGELOG.md](CHANGELOG.md).

## Roadmap

Future work may add DBML PSI, completion/navigation/refactoring, ELK layout, persisted manual positions, visual editing, Git history, and semantic schema diff.

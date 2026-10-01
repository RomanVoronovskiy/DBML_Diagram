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
- Drag tables by their headers; personal positions are restored per DBML file.
- Full-diagram standalone SVG and PNG export. PNG rasterization uses Apache Batik and has a 16,384 px / 40 MP safety limit.
- PostgreSQL, MySQL, and Oracle DDL export with named primary, unique, and foreign-key constraints, indexes, defaults, comments, and referential actions.
- Visible `PK`, `FK`, `UNIQ`, and `NOT_NULL` markers on diagram columns.
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
├── renderer    DiagramRenderer + standalone SVG renderer
└── ddl         PostgreSQL, MySQL, and Oracle DDL generators

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
- Reference settings such as `[delete: cascade]` and `[update: no action]` are accepted and translated where the selected SQL dialect supports them.
- Inline column `[ref: ...]` references.
- `//` line comments and `/* ... */` block comments.
- Warnings for duplicate tables/columns and unknown reference tables/columns.

## DDL export

Choose **PostgreSQL**, **MySQL**, or **Oracle** next to **DDL dialect**, then use **Export DDL** to save the latest valid schema as a `.sql` file. The first selection follows `Project.database_type` when it names a supported dialect. The exporters translate identifiers, types, enum representation, identity/auto-increment syntax, comments, and referential actions for the selected database.

Diagram relationships are anchored to their actual columns, drawn from the physical foreign-key column toward the referenced column, and labelled `N → 1` or `1 → 1`. Column constraints are shown between the column name and SQL type as `PK`, `FK`, `UNIQ`, and `NOT_NULL`.

Drag a table by its header to create a custom layout. Positions are stored in the IDE workspace for that DBML file, survive preview refreshes, and are applied to exported SVG and PNG files. Use **Reset layout** to discard them and return to the automatic layered layout.

Many-to-many `<>` relationships require an explicit junction table in DBML; the generated SQL includes a comment when such a relationship cannot be represented as a direct foreign key.

## Known limitations

- This is a tolerant subset parser, not a complete DBML grammar or PSI implementation.
- The built-in layered layout is deterministic and fast, but does not perform advanced edge crossing minimization for very dense cyclic schemas.
- Visualization-only relationship settings such as colors are currently ignored.
- JCEF must be enabled in the host IDE for the visual preview; the code editor remains available if JCEF is unavailable.
- Syntax highlighting is lexical only; navigation, completion, inspections, and refactoring are not included.
- A temporarily incomplete custom type is accepted when it is syntactically a valid type token; an unclosed block/settings/string is reported as an error.

## Contributing

Contributions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md), follow the [Code of Conduct](CODE_OF_CONDUCT.md), and report vulnerabilities according to [SECURITY.md](SECURITY.md).

The project is released under the [Apache License 2.0](LICENSE). Changes are tracked in [CHANGELOG.md](CHANGELOG.md).

## Roadmap

Future work may add DBML PSI, completion/navigation/refactoring, ELK layout, richer visual editing, Git history, and semantic schema diff.

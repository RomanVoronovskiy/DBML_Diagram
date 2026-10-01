# Changelog

All notable changes to DBML Diagram are documented in this file. The project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.2.0] - 2026-10-01

### Added

- PostgreSQL, MySQL, and Oracle DDL export with an explicit dialect selector in the diagram toolbar.
- Named primary, unique, and foreign-key constraints, indexes, comments, identity columns, defaults, and referential actions in generated DDL.
- Explicit `PK`, `FK`, `UNIQ`, and `NOT_NULL` column markers in the SVG diagram.
- Directed FK-to-referenced-column relationship arrows with `N:1` and `1:1` endpoint labels.
- Composite primary-key parsing from DBML index blocks.
- Open-source project governance, contribution guidelines, security policy, and CI.

### Fixed

- Foreign-key markers are now shown only on the referencing side of a relationship.
- Constraint markers now use the space between column names and SQL types.
- Quoted string defaults and backtick SQL expressions are preserved for DDL generation.

## [0.1.0] - 2026-09-28

### Added

- Offline DBML file type and lexical syntax highlighting.
- Code, Preview, and Code + Preview editor modes.
- Tolerant DBML parser with structured diagnostics.
- Realtime debounced SVG entity-relationship diagrams.
- Pan, zoom, fit, refresh, SVG export, and full-diagram PNG export.
- Light and Darcula preview themes.
- Support for tables, columns, indexes, enums, inline and global references, comments, notes, and common column settings.
- Reference action parsing for constructs such as `[delete: cascade]`.

[Unreleased]: https://github.com/RomanVoronovskiy/DBML_Diagram/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/RomanVoronovskiy/DBML_Diagram/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/RomanVoronovskiy/DBML_Diagram/releases/tag/v0.1.0

# Changelog

All notable changes to DBML Diagram are documented in this file. The project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.0.0] - 2026-10-07

### Added

- Optional dbdiagram.io integration through official CLI browser authentication or the public workspace API.
- A dbdiagram.io tool window with My Diagrams, literal name/ID search, sortable metadata, and download/link/open-on-web actions.
- Link ID, Pull, and Push buttons in the responsive DBML preview toolbar; local SVG/PNG/DDL export remains available.
- Workspace API credentials in IDE PasswordSafe, with nonsensitive connection settings and per-project file bindings.
- Explicit overwrite/upload confirmations, remote baseline conflict checks, protection against concurrent local edits during Pull, and account-change binding invalidation.
- Isolated CLI execution without modifying project settings/hooks/viz files, cancellable background requests, fixed official hosts, bounded HTTP/CLI responses, and no automatic push retries.
- Contract tests for API/CLI serialization, listing, authentication commands, ID validation, limits, cleanup, metadata persistence, and IDE list filtering.
- Documentation for both authentication modes, optional network use, provider telemetry, and synchronization limitations.

## [0.3.0] - 2026-10-04

### Added

- Relationship validation for 1:1, 1:N/N:1, and conceptual N:N relations, including standalone and inline references.
- Editor error underlines at the reference source range, red invalid relationship/column highlighting, and detailed preview diagnostics.
- Export guards that validate the current document rather than exporting a stale valid preview.

### Fixed

- Relationships reroute around table cards during dragging, refresh, and SVG/PNG export; control points inside cards are moved to a safe position.
- Attachment anchors are now clickable, endpoint hit areas are larger, and drag completion is handled at window level.
- Selection clicks no longer save a route or reload the preview; clicking an unselected arrowhead grabs its endpoint, and edited relationships retain their selection after refresh.
- Repeated table drags retain the accumulated translation, keeping the card and relationship endpoints aligned.
- The toolbar reports its wrapped height, keeping PNG and other export buttons visible in narrow preview panes.
- Relationship endpoints on left/right table sides now align with the actual FK/referenced column rows, including live dragging, snap points, and exported diagrams.
- PNG distributions no longer bundle legacy JAXP classes (`xml-apis`) that conflict with IntelliJ's XML parser classloader; an isolated packaged-ZIP rasterization smoke test covers this regression.
- PNG export now resolves SVG CSS variables and unsupported transparent strokes before Apache Batik rasterization.
- PNG export runs in the background, validates dimensions before allocating an image, refreshes the saved file, and preserves existing files if rendering fails.
- Members of composite PK/UNIQUE constraints are no longer treated as individually unique.
- All inline reference settings on a column are parsed and validated.
- Superseded background renders no longer replace a newer preview or re-enable exports.

## [0.2.0] - 2026-10-01

### Added

- PostgreSQL, MySQL, and Oracle DDL export with an explicit dialect selector in the diagram toolbar.
- Named primary, unique, and foreign-key constraints, indexes, comments, identity columns, defaults, and referential actions in generated DDL.
- Explicit `PK`, `FK`, `UNIQ`, and `NOT_NULL` column markers in the SVG diagram.
- Directed FK-to-referenced-column relationship arrows with `N:1` and `1:1` endpoint labels.
- Draggable tables with per-file workspace persistence and custom-layout SVG/PNG export.
- Persistent manual relationship routing with four attachment sides per table plus independent horizontal, vertical, and free-movement route controls.
- Composite primary-key parsing from DBML index blocks.
- Open-source project governance, contribution guidelines, security policy, and CI.

### Fixed

- Foreign-key markers are now shown only on the referencing side of a relationship.
- Constraint markers now use the space between column names and SQL types.
- Relationship lines and arrowheads now render on a foreground layer instead of disappearing behind table cards.
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

[Unreleased]: https://github.com/RomanVoronovskiy/DBML_Diagram/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/RomanVoronovskiy/DBML_Diagram/compare/v0.3.0...v1.0.0
[0.3.0]: https://github.com/RomanVoronovskiy/DBML_Diagram/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/RomanVoronovskiy/DBML_Diagram/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/RomanVoronovskiy/DBML_Diagram/releases/tag/v0.1.0

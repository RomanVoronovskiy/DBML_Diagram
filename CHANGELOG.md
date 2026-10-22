# Changelog

All notable changes to DBML Diagram are documented in this file. The project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [2.0.0] - 2026-10-22

### Colored TableGroup visualization

- Render `TableGroup` declarations as named, rounded frames with a subtle tinted background and member counts. Accept `#rgb`/`#rrggbb` colors and use a stable name-based palette when no valid color is supplied.
- Parse quoted group/member names, schema-qualified tables, aliases, forward-declared members, header notes, and inline/block group notes. Group names and notes are escaped before rendering; paint values cannot inject URLs or markup.
- Pack groups into separate blocks in automatic layout; keep ungrouped tables outside those blocks. Existing diagrams without groups retain the previous layout. Personal saved table positions still take precedence; **Reset layout** reapplies group-aware placement.
- Resize group frames during table dragging and after restoring positions. Keep frames behind cards and relationships, preserve four-side attachment and obstacle routing, and include complete frame bounds in Fit/SVG/PNG output.
- Warn at the source location for missing, duplicate, or shared members and invalid colors. Empty groups draw no frame. If a table belongs to several groups, the first group owns its automatic position and overlapping frames are possible. Manual overlapping table positions are preserved rather than silently rearranged.
- Keep groups visualization-only: PostgreSQL/MySQL/Oracle DDL output is unchanged. Standalone sticky `Note` declarations remain unsupported with warnings; group notes are available in SVG title tooltips.
- Highlight `TableGroup` and `checks` keywords. Add core, bounded-heap, real-pointer browser, and packaged-PNG regression coverage, plus a synthetic public example and light/dark screenshots for a future walkthrough.

## [1.0.3] - 2026-10-22

### Schema processing improvements and CHECK constraints

- Support named table-level `checks { ... }` blocks and repeated inline `check:` expressions without mistaking them for columns or closing the table prematurely.
- Preserve CHECK constraint names and SQL expressions in PostgreSQL, MySQL, and Oracle DDL export. Empty/malformed expressions and duplicate CHECK names produce source-located errors. Expressions are preserved verbatim, not evaluated or translated between SQL dialects.
- Display the number of CHECK constraints in table headers, with their names and expressions in a tooltip.
- Parse ordered composite foreign keys, including quoted/schema-qualified identifiers, aliases, and reference actions. Validate all members, arity, candidate keys, cardinality, duplicate columns, and pairwise type compatibility.
- Render each composite FK as one relationship anchored at its first column, with the full tuple in its tooltip; mark every participating FK/error column. Preserve existing single-column route identifiers.
- Export complete composite FK column lists instead of truncating them. Declare Oracle unique keys as constraints and add an ordered supporting unique index for reordered MySQL reference tuples when needed.
- Add regression coverage for complex schemas, CHECK constraints, composite keys, diagram/PNG output, and malformed input. Private reproduction schemas remain outside the repository.

## [1.0.2] - 2026-10-17

### Fixed

- Prevent parser recovery from repeatedly reporting the same closing brace until the IDE runs out of Java heap memory; unexpected top-level braces now produce errors at their actual locations and parsing continues.
- Safely skip complete `TableGroup`, standalone `Note`, and unknown declaration blocks, including nested blocks and quoted braces. These visualization-only blocks produce warnings and are not rendered; tables and relationships following them remain available.
- Read triple-single-quoted multiline notes as single values, preserving apostrophes and source locations; treat quoted brackets, commas, and other punctuation as text rather than DBML structure.
- Keep a table's closing brace intact after its inline `Note`, including a multiline note ending on the same line.
- Accept opening braces on the following line for tables, enums, projects, indexes, and named reference blocks.
- Add bounded-heap subprocess regressions for recovery, malformed input, multiline notes, source ranges, and full parse/layout/SVG rendering of a generated large schema. Private user diagrams are not included in test fixtures.

## [1.0.1] - 2026-10-14

### Changed

- Extend the supported IntelliJ Platform range from 241–261 to 241–262; retain all 1.0.0 cloud features.
- Update Apache Batik codec/transcoder from 1.17 to 1.19, IntelliJ Platform Gradle Plugin from 2.2.1 to 2.19.0, Kotlin from 2.0.21 to 2.4.21, and Gradle from 8.10.2 to 9.7.0 as a coordinated, supported build-toolchain set.
- Pin the official Gradle distribution checksum, regenerate wrapper scripts/JAR, and enable the local Gradle build cache.
- Keep JVM 17 bytecode and Kotlin source/API level 2.0; compile core against IDE 2024.1's stdlib 1.9.22 without shipping a duplicate runtime.
- Reuse a single cached parse result for layout-only refreshes and stop obsolete preview jobs before layout, SVG rendering, or UI delivery.
- Group coordinated Dependabot updates for Batik and the build toolchain; document the audit of PRs #5–#9.

### Fixed

- Ignore rendering requests after disposal and treat cancellation/interruption as cancellation, not preview errors.
- Reject SVG DTD/entity declarations before PNG parsing, deny external resources at the Batik user-agent boundary, and explicitly disable on-load script execution.
- Migrate the newer Gradle plugin's verification DSL and allow local IDE verification without first downloading the remote matrix.
- Provide explicit test-only Kotlin runtime/JUnit Platform launcher dependencies for standalone tests with the new build tooling.
- Extend packaged ZIP tests to module JARs; add regression tests for render caching, superseded work, disposal, and PNG resource restrictions.
- Inherit IDE interface defaults instead of generating compiler forwarders to internal/experimental tool-window APIs; guard the compiled factory method set in a regression test.

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

[Unreleased]: https://github.com/RomanVoronovskiy/DBML_Diagram/compare/v1.0.1...HEAD
[1.0.1]: https://github.com/RomanVoronovskiy/DBML_Diagram/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/RomanVoronovskiy/DBML_Diagram/compare/v0.3.0...v1.0.0
[0.3.0]: https://github.com/RomanVoronovskiy/DBML_Diagram/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/RomanVoronovskiy/DBML_Diagram/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/RomanVoronovskiy/DBML_Diagram/releases/tag/v0.1.0

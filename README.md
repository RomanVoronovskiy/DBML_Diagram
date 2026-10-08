# DBML Diagram

[![CI](https://github.com/RomanVoronovskiy/DBML_Diagram/actions/workflows/ci.yml/badge.svg)](https://github.com/RomanVoronovskiy/DBML_Diagram/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2024.1--2026.1-purple.svg)](https://plugins.jetbrains.com/)

DBML Diagram is an open-source IntelliJ Platform plugin that shows a realtime SVG entity-relationship diagram next to a `.dbml` file. Version **1.0.0** adds optional dbdiagram.io synchronization while keeping local editing, preview, validation, and exports offline. It supports IntelliJ IDEA and DataGrip-compatible Platform IDEs.

## Features

- Standard IntelliJ text editor with **Code**, **Preview**, and **Code + Preview** modes.
- Debounced (300 ms), cancellable background parsing, layout, and SVG rendering.
- Last valid diagram remains visible while the document contains a parse error.
- Semantic relationship errors are underlined in the editor and drawn in red in the current diagram. Exports are disabled until the current document is valid.
- Pan, Ctrl/Cmd + mouse-wheel zoom, Fit, zoom buttons, 100%, and manual Refresh.
- Drag tables by their headers; personal positions are restored per DBML file.
- Drag relationship lines to adjust their orthogonal route. A selected route has separate horizontal and vertical handles, while its endpoints can move between the top, right, bottom, and left table anchors.
- Full-diagram standalone SVG and PNG export. PNG rasterization uses Apache Batik and has a 16,384 px / 40 MP safety limit.
- PostgreSQL, MySQL, and Oracle DDL export with named primary, unique, and foreign-key constraints, indexes, defaults, comments, and referential actions.
- Visible `PK`, `FK`, `UNIQ`, and `NOT_NULL` markers on diagram columns.
- Light and Darcula-aware diagram colors.
- DBML file type, icon, comments, and basic syntax highlighting.
- Optional dbdiagram.io browser login through the official CLI, or a workspace API token stored in IDE PasswordSafe.
- Search and select remote diagrams in **My Diagrams**, download DBML, link an existing file, and explicitly Pull or Push.
- Local editing, preview, validation, and SVG/PNG/DDL exports do not require an account, network access, Node.js, or CLI tools.

## dbdiagram.io integration (1.0.0)

Open **View | Tool Windows | dbdiagram.io**, or click **dbdiagram** in the DBML preview toolbar. Use **Connection…** to choose one of two authentication methods:

### Browser login — official CLI

1. Install Node.js **22.14 or newer** and run `npm install -g dbdiagram` in a terminal. The integration was developed against official CLI **0.6.3**.
2. Select **Browser login (official CLI)**. The plugin detects Node.js and `node_modules/dbdiagram/dist/index.js` when possible; otherwise enter their absolute paths. Do not select the `.cmd`/`.ps1` shim as the CLI entry.
3. Leave **Workspace ID** blank for your personal workspace, or enter a team workspace's ID.
4. Click **Login / Connect** and finish authentication in the browser opened by the official CLI. The plugin then loads **My Diagrams**.

The CLI manages its own global credentials; the plugin never reads that credentials file or your account password. **Disconnect** logs out the shared CLI account, including terminal use. An existing terminal CLI login can be reused with **Refresh** instead of logging in again. The plugin ignores inherited `DBDIAGRAM_TOKEN` and CLI host-override environment variables in this mode so browser credentials and official hosts are used consistently.

Each CLI operation runs in a private temporary directory with explicit diagram/file arguments. The plugin does not run `dbdiagram init`, execute project hooks, or change your repository's CLI settings, `.env`, or adjacent visualization files. Official CLI authentication and its own telemetry remain governed by the CLI/provider.

### Workspace API token — no Node.js required

1. In dbdiagram.io, open the relevant workspace and its **API Tokens** tab (shown in the website's My Diagrams window).
2. Generate a workspace token as the workspace owner.
3. Select **Workspace API token (no Node.js)**, paste it, save, and click **Login / Connect**.

The [public API](https://docs.dbdiagram.io/api/v1/) is currently beta and requires a paid plan. Tokens grant workspace-scoped access. This mode connects directly to the documented HTTPS API and requires neither npm nor the CLI. The token is stored using **IntelliJ PasswordSafe**, never in project files. Leaving the token field blank preserves the saved token; **Disconnect** removes it.

### Select, edit, Pull, Push

1. Search **My Diagrams** by name or ID; sort columns by clicking their headers. Select a diagram and click **Pull to file…** to download and open a `.dbml` file. The downloaded file is automatically linked.
2. Alternatively, open an existing `.dbml` file and click **Link to current DBML** for the selected diagram. **Link ID** in the preview toolbar also accepts an ID or `https://dbdiagram.io/d/...` URL. Linking records the remote baseline without replacing local content.
3. Edit DBML normally. **Pull** replaces local text only after confirmation, including unsaved edits, and is undoable in the editor. If the document changes during the download/confirmation, the operation stops without discarding edits.
4. **Push** sends the editor's current text, including unsaved edits, after confirmation. Before upload it fetches the remote DBML and compares it with the baseline from the previous Link/Pull/Push. If someone else changed it, upload is stopped: download into a separate file, merge manually, and relink before retrying.

Push updates the linked existing diagram, not a new diagram. It does not rename the diagram or upload the plugin's table positions/relationship routes. Website visualization settings are retained by omitting them from the update. No automatic synchronization, automatic push retry, diagram creation, deletion, or rename is performed. Empty local documents and DBML larger than 8 MiB are not pushed. If local parser errors exist, explicit confirmation is required because the cloud supports a wider DBML grammar; the server remains responsible for accepting or rejecting the DBML.

Changing the API token, using **Login** again, or disconnecting invalidates old bindings; relink files before syncing with a different account. If you change the shared CLI account outside the IDE, relink files manually as well. **Refresh** reuses the current account without invalidating bindings.

The public API/CLI does not provide an atomic compare-and-swap update: another writer could still change the diagram between the final check and upload. The check reduces accidental overwrites but is not a distributed lock. Coordinate simultaneous edits. If a push times out/is canceled, its server outcome may be uncertain; inspect the remote before retrying.

The list shows the diagrams accessible to the selected CLI workspace or API token. Metadata unavailable from that backend is shown as `—` (for example, the CLI does not return creation dates). Team workspace selection currently uses a workspace ID in Connection settings, not a website-style workspace dropdown. See [PRIVACY.md](PRIVACY.md) for data handling.

## Build

Requirements: JDK 17 and network access for the first Gradle dependency download.

```bash
./gradlew test
./gradlew buildPlugin
./gradlew :dbml-intellij-plugin:testPackagedPlugin
```

`testPackagedPlugin` checks the actual ZIP libraries in an isolated child-first classloader and rasterizes a PNG, guarding against bundled XML APIs that conflict with IntelliJ. For preview interaction geometry, run `node dbml-intellij-plugin/src/test/js/preview-routing.test.cjs` (Node is needed only for this optional test).

With Playwright and a Chromium browser installed, `node dbml-intellij-plugin/src/test/js/preview-pointer.test.cjs` checks actual pointer events, attachment clicks, zoomed dragging, persistence messages, and live obstacle avoidance using the HTML generated by the Gradle tests. Set `DBML_BROWSER_CHANNEL=msedge` to test with installed Microsoft Edge instead of Playwright Chromium.

To also verify against an installed IDE, run `./gradlew :dbml-intellij-plugin:verifyPlugin -PverifyLocalIde="/path/to/IDE"`.

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
├── preview     debounce scheduler, JCEF UI, SVG/PNG export
└── cloud       optional official API/CLI clients, credentials, bindings, My Diagrams
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
- Warnings for duplicate tables/columns; errors for unknown reference tables/columns and invalid relationships.

## Relationship validation

The plugin validates single-column relationships against declared constraints:

- `>` / `<` (N:1 / 1:N): the referenced side must have a single-column PK or UNIQUE constraint, and the many-side FK must not be individually unique.
- `-` (1:1): both endpoints must be individually unique. A shared-primary-key relationship is valid; add `[unique]` to a non-PK FK to declare uniqueness explicitly.
- `<>` (N:N): both endpoints must identify entities through single-column PK/UNIQUE constraints. This is a conceptual relationship, so different entity key types are allowed. SQL export still requires an explicit junction table.
- Direct FKs require compatible type families. Unknown endpoints and a column referencing itself are errors; hierarchical self-references between distinct columns are allowed.
- A column inside a composite PK or UNIQUE index is not considered unique by itself. A separate single-column UNIQUE constraint can make it a valid referenced key.

Invalid `Ref` expressions (including inline settings) receive an editor error underline with an explanation. The preview displays invalid relationships as red dashed lines and highlights affected columns/tables; unresolved relationships are listed in the diagnostic banner. DDL, SVG, and PNG exports are blocked while errors remain. Syntax errors retain the previous diagram, but never permit export of stale data.

## DDL export

Choose **PostgreSQL**, **MySQL**, or **Oracle** next to **DDL dialect**, then use **Export DDL** to save the latest valid schema as a `.sql` file. The first selection follows `Project.database_type` when it names a supported dialect. The exporters translate identifiers, types, enum representation, identity/auto-increment syntax, comments, and referential actions for the selected database.

Diagram relationships attach to the actual column rows on the left/right table sides by default, are drawn from the physical foreign-key column toward the referenced column, and are labelled `N → 1` or `1 → 1`. Row alignment is retained when tables move and in SVG/PNG exports. Manually selected top/bottom attachments remain table-border anchors; hovering a relationship always shows the exact column endpoints. Column constraints are shown between the column name and SQL type as `PK`, `FK`, `UNIQ`, and `NOT_NULL`.

Drag a table by its header to create a custom layout. Positions are stored in the IDE workspace for that DBML file, survive preview refreshes, and are applied to exported SVG and PNG files. Use **Reset layout** to discard them and return to the automatic layered layout.

Drag a relationship line or its central handle to move the routing control point freely. Selecting the line also reveals a horizontal handle for moving the route only left/right, a vertical handle for moving it only up/down, both endpoint handles, and all four attachment points on the related tables. Drag an endpoint to choose another side, or click a small attachment point directly. Routes are rebuilt around table cards when any table moves; unsafe control points inside a card are moved to a free position. Routes are stored alongside table positions and used for SVG/PNG export. Overlapping cards or extremely large blocked routing grids can prevent a collision-free route; separate overlapping tables before adjusting their routes.

The toolbar wraps onto additional rows in narrow preview panels so **Export PNG** and the other export actions remain visible.

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

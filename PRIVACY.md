# DBML Diagram privacy and data handling

Updated: 2026-10-29 — version 3.0.0.

## Local features

Local DBML editing, parsing, validation, diagram preview, and SVG/PNG/DDL exports run on your computer. They do not send schemas to the plugin author or require dbdiagram.io. The plugin adds no analytics or telemetry of its own.

Table positions, relationship routes, and cloud file bindings are saved in IDE workspace storage. Cloud bindings contain the local file URL, remote diagram ID/name, an opaque connection identifier, and a SHA-256 hash of the last synchronized remote DBML. They do not contain the DBML source or API credentials. Connection settings store only the chosen backend, CLI executable paths, optional workspace ID, and an opaque connection identifier.

## PostgreSQL schema comparison

When explicitly requested with Compare DB, the plugin connects directly to the chosen PostgreSQL host. It sends credentials through PostgreSQL JDBC using the selected SSL mode and reads catalog metadata in a read-only transaction. It does not select application-table rows or execute generated migrations. Use verify-full with appropriate certificates when server identity verification is required. Host, port, database, user, schema, SSL mode and a credential identifier are stored in IDE workspace settings. Remembered passwords are stored only in IDE PasswordSafe, whose persistence/encryption behavior depends on the configured IDE provider.

Snapshots contain table/column names, types, defaults, keys, indexes, checks and relationships and are held only in memory per DBML file. Editor changes compare against the snapshot locally. Compare DB refreshes it; Disconnect DB clears it; closing the project removes snapshots. Disconnect keeps the saved profile/password; uncheck Remember password when connecting to clear that profile's saved password. No snapshot, password or migration is uploaded to the plugin author or dbdiagram.io by this feature. SQL is saved only to the chosen destination. Query/connect timeouts bound network operations.

## Local SQL import

SQL → DBML import invokes the separately installed official `@dbml/cli` with Node.js. The plugin writes the selected SQL editor text to a private temporary directory and reads the generated DBML there; both files are removed on completion/failure/cancellation. Abnormal process/system termination can leave temporary files. The plugin does not execute SQL against a database or intentionally send schema text to a server. Third-party converter behavior is governed by its installed version and provider; no plugin telemetry is added. Conversion errors can display a short converter diagnostic in the local IDE. The destination is chosen after conversion.

Sticky-note positions, camera/grid preferences, table positions, and routes are kept in IDE workspace storage. Import settings store only executable paths and the last selected dialect in IDE application settings. Neither is committed to the project by the plugin.

## Optional dbdiagram.io synchronization

When you explicitly connect, refresh, download, link, pull, or push, the plugin communicates with dbdiagram.io through one of two selected backends. Listing retrieves accessible diagram metadata; the public API also returns schema content as part of its list response, which the plugin discards from displayed metadata. Download/link/pull and the pre-push conflict check retrieve remote DBML. Confirmed Push sends the current local editor text, including unsaved changes, to the chosen diagram. A push does not upload your entire project or the plugin's local visualization settings.

Native API mode uses documented endpoints at `https://api.dbdiagram.io/v1` with a workspace API token in the `dbdiagram-access-token` header. HTTP redirects are not followed. The token is stored in IntelliJ PasswordSafe, not in repository files. The PasswordSafe provider and persistence behavior are configured by your IDE. Disconnect removes this saved token. The plugin does not collect or store your dbdiagram account password.

Browser login uses the separately installed official dbdiagram CLI. The CLI manages its global authentication credentials and opens the provider's login page; the plugin does not read its credential files. Disconnect in this mode logs out the shared CLI account. CLI operations run in a private temporary directory; downloaded/uploaded DBML and any temporary visualization sidecar are removed after the operation completes. Abnormal process/system termination can leave temporary files in the operating system's temporary directory; those files are not sent to the plugin author.

The official CLI is third-party software and can make additional provider-controlled requests, including its own command/runtime telemetry. In CLI 0.6.3, its implementation creates a global telemetry device identifier and sends command/runtime events to a Holistics collector; events can include account, diagram and workspace identifiers as well as CLI/Node version and operating system. This is distinct from the plugin, which adds no tracking. Use native API mode if you do not want to invoke the CLI. Review the provider's current policies and CLI implementation before using cloud synchronization with sensitive schemas.

Clicking Open on web opens an official dbdiagram.io diagram URL in your browser. Normal browser/account behavior then applies. Generated SQL/PNG/SVG files are saved only to the locations you choose; there is no automatic cloud upload.

## Control and limitations

Cloud synchronization is manual. There are no automatic pushes or automatic push retries. Remove a saved API token with Disconnect; revoke provider tokens in the workspace's API Tokens tab. CLI credentials and provider-side data must be managed through the official CLI/provider. Clearing local IDE workspace storage removes the plugin's local bindings/layout, not remote diagrams.

Remote baseline checks are best-effort: the upstream API/CLI provides no atomic revision lock. If a push times out or is canceled, inspect the remote before retrying. HTTP/CLI responses and individual DBML documents have an 8 MiB safety limit.

For issues or questions, use the [project issue tracker](https://github.com/RomanVoronovskiy/DBML_Diagram/issues). Do not attach account passwords, tokens, CLI credentials files, or confidential schemas to public issues.

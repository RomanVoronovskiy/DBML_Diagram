# Contributing to DBML Diagram

Thank you for helping improve DBML Diagram. Bug reports, DBML compatibility cases, documentation changes, tests, and code contributions are welcome.

## Before opening an issue

- Search existing issues to avoid duplicates.
- Use the latest plugin version.
- For parser problems, include the smallest DBML sample that reproduces the issue.
- Remove credentials and private schema details before posting.

Security vulnerabilities should be reported according to [SECURITY.md](SECURITY.md), not through public issues.

## Development setup

Requirements:

- JDK 17
- Git
- Network access for the first Gradle dependency download

Run the checks:

```bash
./gradlew test
./gradlew buildPlugin
```

Start a sandbox IDE:

```bash
./gradlew runIde
```

## Pull requests

1. Create a focused branch from `main`.
2. Keep IntelliJ-independent logic in `dbml-core`.
3. Add or update tests for behavioral changes.
4. Do not perform parsing, layout, or rendering on the EDT.
5. Run `./gradlew test buildPlugin` before opening the pull request.
6. Explain user-visible behavior and known limitations in the pull request.

By submitting a contribution, you agree that it is licensed under the Apache License, Version 2.0.

# Contributing to Task-Engine

Thanks for your interest in contributing! This document describes how to build
the project, run the tests, and submit changes.

## Prerequisites

- **JDK 21** (the project targets Java 21).
- **Docker** — the integration tests (`*IT`) spin up a real PostgreSQL via
  [Testcontainers](https://testcontainers.com/). A running Docker daemon is
  required for the full `verify` build.
- Maven is **not** required locally; use the bundled Maven Wrapper (`./mvnw`).

## Building & testing

```bash
# Unit + integration tests across the whole reactor (needs Docker)
./mvnw -B verify

# Unit tests only, skipping the Testcontainers-backed *IT classes
./mvnw -B test -Dtest='!*IT'

# Build just the starter module
./mvnw -B -pl task-engine-starter -am verify
```

On Windows use `mvnw.cmd` instead of `./mvnw`.

## Project layout

| Module                  | Purpose                                                        |
|-------------------------|----------------------------------------------------------------|
| `task-engine-starter`   | The Spring Boot starter (the published artifact).              |
| `example-application`   | A runnable demo wiring the starter to Postgres + Grafana.      |

## Pull requests

1. Fork the repository and create a topic branch off `main`.
2. Keep changes focused; add or update tests for the behaviour you touch.
3. Make sure `./mvnw -B verify` passes locally before opening the PR.
4. Describe **what** changed and **why** in the PR description. Reference the
   requirement ID (e.g. `R14`) from the README where applicable.

## Coding conventions

- Follow the existing formatting; an [`.editorconfig`](.editorconfig) is provided.
- Public API changes should be reflected in the README's API/configuration
  reference.

## Versioning & releasing

The project follows [Semantic Versioning](https://semver.org/): `MAJOR` for
incompatible API changes, `MINOR` for backward-compatible features, `PATCH` for
fixes. While on `0.x` the public API is still considered unstable.

The version is a **single source of truth** via Maven's CI-friendly `${revision}`
property in the root [pom.xml](pom.xml). `main` always carries a `-SNAPSHOT`
revision; a release is cut by **pushing a tag** — the version is derived from it,
nothing in the POMs needs editing:

```bash
git tag v0.2.0
git push origin v0.2.0
```

This triggers [`.github/workflows/release.yml`](.github/workflows/release.yml),
which builds + tests, attaches sources/Javadoc (`-Prelease`) and publishes the
**starter** (plus its parent POM) to GitHub Packages via the built-in
`GITHUB_TOKEN`. The example application is excluded from publishing. A matching
GitHub Release with auto-generated notes is created for the tag.

You can also trigger a release manually from the Actions tab
(`workflow_dispatch`, supply the version without the leading `v`).

## Consuming from GitHub Packages

Published artifacts live in the repository's GitHub Packages registry. Consumers
add the repository and authenticate with a GitHub token that has `read:packages`:

```xml
<dependency>
    <groupId>io.github.svenwirz</groupId>
    <artifactId>task-engine-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>

<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/SvenWirz/task-engine</url>
    </repository>
</repositories>
```

Credentials go into `~/.m2/settings.xml` under a `<server>` with `<id>github</id>`
(username = GitHub user, password = a personal access token with `read:packages`).

## Reporting issues

Please open a GitHub issue with steps to reproduce, the expected vs. actual
behaviour, and the relevant versions (JDK, Spring Boot, PostgreSQL).

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).

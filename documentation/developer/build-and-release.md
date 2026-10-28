# Build & release

## Build

```bash
./gradlew buildPlugin                         # build/distributions/jetbrains-ide-hole-plugin-<version>.zip
./gradlew buildPlugin -PpluginVersion=1.2.3   # the same with an explicit version
```

The version in `gradle.properties` stays a `-SNAPSHOT`; releases pass the real version with `-PpluginVersion` and
never commit it. `buildSearchableOptions` is disabled — the plugin has one small settings page, and the task would
start a whole IDE during the build.

`plugin.xml` declares `sinceBuild = 252` and no `untilBuild`, so the plugin stays installable in new IDE releases.
Compatibility with them is checked by `./gradlew verifyPlugin`, which runs the Plugin Verifier against the IDEs
JetBrains recommends for the declared range; it must report *Compatible* with no deprecated or internal API
usages. It downloads several GB, so it is not part of CI — run it before a release and after platform API changes.

## CI workflows

| Workflow | Trigger | What it does |
|----------|---------|--------------|
| `.github/workflows/dev.yml` (*Dev*) | push to `dev`, pull requests targeting `dev` | `scripts/generate-adr-index.sh --check`, then `./gradlew test` and `./gradlew buildPlugin` on Java 21. A newer run for the same branch cancels the previous one. |
| `.github/workflows/release.yml` (*Release*) | push to `main` | resolves the next version, tests and builds with it, publishes a GitHub release with notes and attaches the plugin ZIP. Runs are queued, never cancelled. |

## Versioning

The version is derived from the commit history by
[`codacy/git-version`](https://github.com/codacy/git-version), with `main` as the release branch and `dev` as the
development branch. It relies on [conventional commits](https://www.conventionalcommits.org/):

| Commit | Bump |
|--------|------|
| `feat: …` / `feat(scope): …` | minor |
| `fix: …`, `chore: …`, `docs: …`, anything else | patch |
| a `!` after the type (`feat!: …`) or `BREAKING CHANGE` in the message | major |

Tags carry a `v` prefix (`v1.2.3`); the plugin version is the same without it.

## Release notes

[Release Drafter](https://github.com/release-drafter/release-drafter) (`.github/release-drafter.yml`) collects the
pull requests merged since the last release into the release notes and publishes the release with the resolved
version. PRs are grouped by label: `feature` / `enhancement` under *Features*, `fix` / `bugfix` / `bug` under
*Bug Fixes*. Label every PR, otherwise it is listed without a category.

## Releasing

1. Make sure `dev` is green and, if platform API usage changed since the last release, `./gradlew verifyPlugin` is
   *Compatible*.
2. Merge `dev` into `main` and push. The *Release* workflow does the rest; the ZIP appears in the release's assets.
3. Users install it with *Settings › Plugins › ⚙ › Install Plugin from Disk…*.

The plugin is not published to the JetBrains Marketplace yet.

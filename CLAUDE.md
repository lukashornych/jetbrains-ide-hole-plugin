# CLAUDE.md

IntelliJ Platform plugin for managing [Hole](https://github.com/lukashornych/hole) sandboxes. The behaviour is
specified in `documentation/specification.md`; `documentation/design-mockup.html` is the UI mockup. For questions
about Hole itself, read its sources at `~/www/oss/hole` (never modify them).

## Build

- `./gradlew test` — all tests (fake `hole` script, no Docker needed).
- `HOLE_REAL_BINARY=$(command -v hole) ./gradlew test --tests '*RealHoleBinaryTest'` — opt-in checks against a real
  Hole (needs the `docker`/`podman` CLI with compose on `PATH`).
- `./gradlew verifyPlugin` — Plugin Verifier against the recommended IDEs (downloads several GB); must stay
  Compatible with no deprecated/internal API usages.
- `./gradlew buildPlugin` — ZIP in `build/distributions/`; `./gradlew runIde` — sandbox IDE.

Inside a Hole sandbox, the hosts listed in `.hole/settings.json` must be allowed for Gradle to resolve the platform.

## Constraints

- Java 21 toolchain, Lombok, constructor injection; target IntelliJ 2025.2+ (`sinceBuild = 252`, no upper bound).
- Supported Hole: 2.6.0 or newer (`hole list --json`, `--instance-id`, `hole settings get|create`, `hole modify`).
- No internal, experimental or deprecated platform APIs; no new dependencies.
- Code instrumentation is on: `@NotNull` parameters are checked at runtime, so null handling of persisted state
  belongs in the `storage` beans, not in model records.
- Never log the environment, settings file contents or full `hole start` command lines (agent arguments may hold
  API keys).
- Test data must be anonymised (no real user paths or customer project names).

## Layout (`io.github.lukashornych.hole.idea`)

- `hole` — Hole CLI clients (`version`, `list --json`, `settings get|create`, `modify`), settings file resolver,
  command builder, metadata poller.
- `process` — plain CLI runner and executable lookup.
- `session` — sandbox lifecycle: PTY process (pty4j), signals to the process group, terminal connector.
- `storage` — persisted settings and favourites (application and project level).
- `ui` — tool window (incl. the favourites start page), sandbox tabs, dialogs, settings page; `ui/network` — the
  runtime network window.
- `model`, `git` — value records and `git worktree list` parsing.

Tests use `src/test/resources/fake-hole/hole`, a shell script that mimics Hole 2.6.0 including `settings create` and
`modify` (switches documented in its header). Prefer integration tests with it over mocks.

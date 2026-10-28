# Guidelines

## Toolchain

- **Java 21** (Gradle toolchain; `options.release = 21`), **Gradle** with the IntelliJ Platform Gradle Plugin 2.x.
- **Target platform:** IntelliJ IDEA 2025.2 and newer (`sinceBuild = 252`, no upper bound), so the plugin keeps
  working in new IDE releases without a re-release. Compatibility is checked by `./gradlew verifyPlugin` and by
  running the tests with `-PplatformVersion` (see [development](development.md#testing-against-other-ide-versions)).
- **Supported Hole:** 2.6.0 or newer. A feature that needs a newer Hole raises `HoleVersion.MINIMUM`, the version in
  the README and the specification, in the same change.

### Dependencies

No new runtime dependencies. The plugin ships as a small ZIP and uses what the IntelliJ Platform and the bundled
Terminal plugin already provide (pty4j, jediterm, the JSON and UI libraries of the platform). Lombok is a
compile-time-only dependency. Before adding a test dependency, check whether the platform test framework or a small
helper in `testutil/` does the job.

### Platform API

Only public, stable platform API: **no internal, experimental or deprecated APIs**. `./gradlew verifyPlugin` must
report *Compatible* with no deprecated or internal API usages for every recommended IDE. When an API differs between
supported IDE versions, pick the one that works on all of them (see `HoleTtyConnector` for an example) and say why
in the class Javadoc.

## Java conventions

- Modern Java: records for value types, `final` classes unless designed for extension, `final` local variables,
  `this.` for fields — follow the surrounding code.
- **Lombok** for boilerplate; **constructor injection** only. Services take their collaborators in a constructor
  (tests use it) and offer a no-arg or `Project` constructor for the platform plus a static `getInstance()`.
- Annotate parameters and return values with `@NotNull` / `@Nullable` (JetBrains annotations). **Code
  instrumentation is on**: `@NotNull` parameters are checked at runtime, so a `null` from persisted state would throw
  in a model record. Null handling of persisted state therefore belongs in the `storage` beans, which convert to
  model records with defaults.
- Model records are immutable; collections in them are copied with `List.copyOf` and friends.
- Commands are argument lists built by `HoleCommandBuilder`, never shell strings.
- Blocking methods say so in their Javadoc ("Blocking; call off the EDT"). Never run a process or touch the file
  system on the EDT.

### Comments and Javadoc

Every class gets a Javadoc describing its purpose; business logic gets method Javadoc. Javadoc and comments explain
constraints and non-obvious reasons — a Hole contract, a platform quirk, why the obvious alternative does not work —
not what the next line plainly does.

Keep the source free of plan-level commentary: no phases, task numbers or references to implementation plans,
analyses or other working notes that are not committed. Extended documentation belongs in
`documentation/developer/`; reasoning behind decisions in [ADRs](../adr/README.md).

## Non-negotiable rules

- **Never log** the process environment, settings file contents or full `hole start` command lines — agent arguments
  may hold API keys. `CliException` messages name the command and exit code, never the environment. Commands that
  carry no secrets (e.g. `hole modify` with domain entries) may be logged. Error logs must include actionable
  context (instance ID, project path, exit code).
- **Hole is the source of truth.** Ask Hole through its machine-readable CLI; never read Hole's internal files,
  compute its settings paths or parse its human-readable output. See [architecture](architecture.md#principles).
- **Stop sandboxes through the process group** of `hole start` (SIGINT, SIGKILL for Force Stop) — never the process
  tree, never `0x03` written to the pty. See [architecture](architecture.md#stopping-signals-to-the-process-group).
- **No GUI for Hole's settings content.** Hole's settings keys change often; the plugin links to the files and lets
  Hole create them, nothing more.
- **Behaviour changes update the [specification](specification.md)** in the same change.

## Testing

- **TDD where possible.** Every bug fix comes with a regression test.
- **Prefer integration tests against the fake `hole` script over mocks.** Extend the script when a test needs new
  Hole behaviour, and run `RealHoleBinaryTest` against a real Hole when the plugin starts using a new command or
  option. See [development](development.md#tests).
- Keep logic Swing-free so it can be tested with plain JUnit; use `BasePlatformTestCase` only where the platform is
  really needed.
- Test data must be anonymised: no real user paths, user names or customer project names.

## Git workflow

- `main`: released versions only — every push to `main` triggers the release workflow and publishes a GitHub
  release.
- `dev`: current development, the target of pull requests and feature branches; every push and pull request is
  verified by the `Dev` workflow (tests and plugin build).
- Feature branches are created from `dev`.
- Use [conventional commits](https://www.conventionalcommits.org/) — release versioning depends on it:
  `feat:` bumps the minor version, a `!` after the type (`feat!:`) or `BREAKING CHANGE` bumps the major version,
  everything else the patch version. See [build & release](build-and-release.md#versioning).
- Pull requests are labelled (`feature`, `enhancement`, `fix`, `bug`, …) so release notes group them.

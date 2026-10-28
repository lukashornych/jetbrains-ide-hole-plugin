# Development environment

## Prerequisites

- **JDK**: none needs to be installed by hand — Gradle provisions the Java 21 toolchain. The Gradle wrapper
  (`./gradlew`) is checked in.
- **OS**: macOS or Linux. The plugin relies on Unix process groups and pty4j; on Windows the tool window only says
  that Hole is not supported.
- **Hole** 2.6.0 or newer and a container runtime (`docker` or `podman` with compose) — only for manual testing in
  a sandbox IDE and for the opt-in real-binary tests. The regular test suite needs neither.

The first build downloads IntelliJ IDEA 2025.2 (the platform the plugin compiles against) into Gradle's cache.

### Developing inside a Hole sandbox

The plugin can be developed with an agent inside a Hole sandbox. Gradle needs the hosts listed in
`.hole/settings.json` (Gradle plugin portal, JetBrains repositories and their CDNs) to resolve the platform; Hole
picks the file up as the project settings. When a build fails to download something, add the host there rather
than starting the sandbox with unrestricted network.

## Commands

```bash
./gradlew test                  # all tests, against the fake `hole` script (no Docker needed)
./gradlew buildPlugin           # plugin ZIP in build/distributions/
./gradlew runIde                # a sandbox IDE with the plugin installed
./gradlew verifyPlugin          # Plugin Verifier against the recommended IDEs (downloads several GB)
scripts/generate-adr-index.sh   # regenerate the ADR index (--check only verifies it)
```

Run a single test class with `./gradlew test --tests '*SandboxSessionTest'`.

The tests need a UTF-8 locale: without one (no `LANG` / `LC_ALL`, as in some containers) the JVM cannot encode
non-ASCII file names and `SandboxPathFilterTest` fails. Run them with `LC_ALL=C.UTF-8` in that case.

### Testing against other IDE versions

`-PplatformVersion=<version>` builds and tests against another IntelliJ IDEA (2025.3 and newer ship as one
unified IntelliJ IDEA), e.g. `./gradlew test -PplatformVersion=2026.1`. IDEs from 2026.1 are compiled for Java 25,
so that run uses a Java 25 toolchain — e.g. the IDE's bundled JBR via
`-Porg.gradle.java.installations.paths=<idea>/jbr`. The plugin itself is always compiled for Java 21.

## Tests

### The fake `hole` script

Almost every test that involves Hole runs against `src/test/resources/fake-hole/hole`, a POSIX shell script that
implements just enough of Hole 2.6.0: `start`, `list [--json] [--no-gc] [id]`, `settings get|create`, `modify` and
`version`. Its state lives in `$FAKE_HOLE_STATE_DIR` (running instances as `hole list --json` objects, a call log,
recorded `modify` entries), and its behaviour is switched by environment variables (`FAKE_HOLE_MODE=old`,
`FAKE_HOLE_START_EXIT`, `FAKE_HOLE_HANG`, `FAKE_HOLE_CHILD`, …). The full list of switches is documented in the
script's header — read it before writing a test.

Tests use it through `testutil/FakeHole`, which installs the script into a temporary directory and provides the
executable and a minimal process environment. That way the real code paths run end to end — process spawning in a
pty, signals to the process group, JSON parsing, exit-code handling — without Docker. **Prefer such integration
tests over mocks.** When a test needs Hole behaviour the script does not have yet, extend the script (see
[recipes](recipes.md#extend-the-fake-hole)).

`FakeHoleScriptTest` pins the script's own behaviour, so a change to it that breaks other tests shows up there
first.

### Platform tests

Most tests are plain JUnit 5 tests, because the logic is kept free of Swing (see
[architecture](architecture.md#testability)). A few need a running IntelliJ Platform — persistence, services, the
tool window view, terminal key dispatch, the path filter, image paste. They extend `BasePlatformTestCase`
(JUnit 3/4 style) and run on the JUnit Platform through the vintage engine.

`TerminalKeyDispatchTest` pins how the terminal widget dispatches keys, which the image paste relies on; when it
fails after an IDE upgrade, the platform changed underneath the plugin.

### Checks against a real Hole

`RealHoleBinaryTest` runs the plugin's CLI clients against a real Hole to catch drift between the fake script and
the real CLI. It is opt-in and skipped unless `HOLE_REAL_BINARY` points to the executable:

```bash
HOLE_REAL_BINARY=$(command -v hole) ./gradlew test --tests '*RealHoleBinaryTest'
```

It needs the `docker` or `podman` CLI with compose on the `PATH`, but no running daemon: Hole runs with a temporary
`HOME`, so the developer's settings and instances are not touched, and the sandbox it starts fails before it needs
the daemon. Run it whenever the plugin starts using a new Hole
command or option, or after a Hole release.

### Test data

Test data must be anonymised: no real user paths, user names or customer project names in fixtures, JSON samples or
expected strings. Use generic values such as `/home/user/project`, `my-project` or `example.com`.

## Manual testing

`./gradlew runIde` starts a sandbox IDE with the plugin. It finds `hole` the same way the installed plugin does
(*Settings › Tools › Hole*, then the `PATH` of the user's shell, then `~/.local/bin/hole`), so a locally built Hole
can be tested by pointing the setting at its binary. Things worth checking by hand after UI changes: light and dark
theme, a sandbox start and stop, restart from the header, the Network window, image paste with a screenshot on the
clipboard.

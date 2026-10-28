# Recipes

Step-by-step instructions for common changes. Each recipe ends with the tests that should prove it; the general
[pre-PR checklist](#pre-pr-checklist) applies to all of them.

## Add a sandbox option

A new checkbox of the New Sandbox / Edit Favourites form that maps to a `hole start` flag (follow `trustProject` or
`imagePaste` through the code):

1. **Model** — add the component to `model/SandboxConfig` with a Javadoc `@param` naming the flag, and to its
   defaults.
2. **Persistence** — add the field to `storage/FavouriteBean` and both conversions (`of`, `toFavourite`).
   Favourites saved by an older plugin version do not have the field, so its initial value in the bean is what they
   get — pick it deliberately (`imagePaste = true` is an example of a non-false default).
3. **Command** — emit the flag in `hole/HoleCommandBuilder`, at its place in the documented flag order. Decide
   whether it also belongs in the *Debug* command.
4. **Form** — add the checkbox with the flag as its hint to `ui/SandboxForm`, read and write it there, and make sure
   the command preview updates.
5. **Hole version** — if the flag is newer than the current minimum, raise `HoleVersion.MINIMUM` (see
   [guidelines](guidelines.md#toolchain)).
6. **Specification and README** — list the option in the [specification](specification.md#new-sandbox-dialog) and,
   when it is user-visible enough, in the README.

Tests: `HoleCommandBuilderTest` (flag present, position, debug command), `SandboxConfigTest`,
`FavouritesPersistenceTest` (round trip, and loading XML without the new field).

## Call a new Hole command

1. Check that Hole offers a **machine-readable** form of the command (`--json`, documented exit codes). If it does
   not, the change belongs in Hole first — the plugin does not parse human-readable output.
2. Build the argument list in `hole/HoleCommandBuilder`.
3. Add a client in `hole/` modelled on `HoleSettingsClient` or `HoleModifyClient`: it runs through
   `process/CliRunner` with an explicit timeout, maps exit codes to typed failures and is documented as blocking.
   Parse JSON leniently (ignore unknown fields, default missing ones), like `HoleListParser`.
4. Put new value types into `model/` as immutable records.
5. Call the client off the EDT and apply the result on the EDT (see
   [architecture](architecture.md#threading)).
6. Never log the environment or secrets; log the command only when it cannot carry any.

Tests: a parser test with a sample under `src/test/resources/`, a client test against the fake `hole` (extend it,
below), and a case in `RealHoleBinaryTest` that runs the command against a real Hole.

## Extend the fake `hole`

`src/test/resources/fake-hole/hole` is a POSIX `sh` script, so it runs on macOS and Linux without extra tools.

1. Implement the new subcommand or option to the extent the plugin needs, mimicking Hole's real output and exit
   codes (read Hole's sources at `cmd/hole` and `internal/` or its README).
2. Keep state in `$FAKE_HOLE_STATE_DIR`; add behaviour switches as `FAKE_HOLE_*` environment variables.
3. **Document every new switch and state file in the script header** — it is the script's only documentation.
4. Add a case to `FakeHoleScriptTest`, then use the new behaviour from the test that needed it through
   `testutil/FakeHole`.

## Add a UI element with logic

1. Put the decisions (what is shown, enabled, which text) into a Swing-free model next to the component — like
   `HeaderModel` for `SandboxHeaderPanel` or `EmptyStateModel` for `HoleEmptyState` — and test that model with plain
   JUnit.
2. Keep the Swing class thin: it renders the model and forwards events.
3. Use platform components (`JBLabel`, `ActionLink`, `DialogWrapper`, `JBUI` insets and colours) so the element
   follows the IDE theme; icons need a `_dark` variant in `src/main/resources/icons/` and an entry in `HoleIcons`.
4. Check it in `./gradlew runIde` in both a light and a dark theme.

## Pre-PR checklist

- [ ] `./gradlew test` passes.
- [ ] `./gradlew buildPlugin` succeeds.
- [ ] `./gradlew verifyPlugin` reports *Compatible* with no deprecated or internal API usages — at least when
      platform API usage changed.
- [ ] `RealHoleBinaryTest` passes against a real Hole when a Hole command or option was added or changed.
- [ ] Behaviour changes are in the [specification](specification.md) and, when user-visible, in the README;
      developer-facing changes are in `documentation/developer/`.
- [ ] Nothing logs the environment, settings contents or `hole start` command lines; test data is anonymised.
- [ ] If the work clears the bar in `.claude/rules/adr.md`, the ADR is written in its final form
      (`status: accepted`, dated the day it is committed, no edits after the merge),
      `scripts/generate-adr-index.sh --check` passes and the plan it replaces is deleted.
- [ ] Commit messages follow conventional commits; the PR targets `dev` and has a label for the release notes.

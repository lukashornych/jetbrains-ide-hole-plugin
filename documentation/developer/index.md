# Hole plugin — Developer Documentation

The Hole plugin is an IntelliJ Platform plugin that starts, manages and switches between
[Hole](https://github.com/lukashornych/hole) sandboxes from a bottom tool window of the IDE. Each sandbox runs
`hole start` in a real pseudo terminal inside its own tab; the plugin talks to Hole only through its CLI
(`hole version`, `hole list --json`, `hole settings get|create`, `hole modify`).

This documentation is aimed at developers working on the plugin itself. For user-facing documentation
(installation, features), see the [README](../../README.md). Hole's own behaviour is documented in the
[Hole README](https://github.com/lukashornych/hole#readme).

## Recommended reading order

Recommended for anyone (human or AI agent) new to the codebase:

0. [Development environment](development.md) — prerequisites, build and test commands, the fake `hole` script,
   checks against a real Hole, running a sandbox IDE
1. [Specification](specification.md) — the behaviour of the plugin: every window, dialog, action and lifecycle
   rule. It is the source of truth for *what* the plugin does
2. [Architecture](architecture.md) — package layout, the sandbox session state machine, metadata polling,
   stopping through process groups, threading, persistence
3. [Guidelines](guidelines.md) — toolchain, Java conventions, non-negotiable rules, logging, testing, git
   workflow
4. [Recipes](recipes.md) — add a sandbox option, call a new Hole command, extend the fake `hole`, pre-PR
   checklist
5. [Build & release](build-and-release.md) — versioning, CI workflows, release notes, compatibility checks
6. [Decision records](../adr/README.md) — why things are the way they are; search before touching an area, write
   one when a change clears the bar in `.claude/rules/adr.md`

## Repository layout

```
src/main/java/io/github/lukashornych/hole/idea/
  hole/                     Hole CLI clients, command builder, version probe, metadata poller
  process/                  plain CLI runner, executable lookup on the user's shell PATH
  session/                  sandbox lifecycle: pty process, signals, terminal connector, image paste folders
  storage/                  persisted plugin settings and favourites (application and project level)
  ui/                       tool window, sandbox tabs, dialogs, settings page
  ui/network/               the runtime network window (`hole modify`)
  model/, git/              value records, `git worktree list` parsing
src/main/resources/         plugin.xml, icons (each with a `_dark` variant)
src/test/java/              tests, mirroring the main packages; helpers in `testutil/`
src/test/resources/
  fake-hole/hole            shell script that mimics Hole 2.6.0 (see development.md)
  hole-list/, hole-modify/  JSON samples of Hole's machine-readable output
documentation/developer/    this documentation
documentation/user/assets/  screenshots used by the README
documentation/adr/          architectural decision records (see .claude/rules/adr.md)
documentation/analysis/     analyses — git-ignored working notes, never committed
documentation/plans/        implementation plans — git-ignored working notes, never committed
scripts/                    developer tooling (ADR index generator)
.github/workflows/          dev.yml (verify `dev` and its PRs), release.yml (release from `main`)
.hole/settings.json         hosts Gradle needs when the plugin is developed inside a Hole sandbox
```

## Documentation rules

- **User-facing** changes (new features, changed behaviour, requirements) must be reflected in the
  [README](../../README.md) and in the [specification](specification.md).
- **Developer-facing** changes (architecture, internals, conventions) must be reflected in these files, in the
  same change as the code.
- Keep the source code free of plan-level commentary; see [guidelines](guidelines.md#comments-and-javadoc).

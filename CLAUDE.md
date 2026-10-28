# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

IntelliJ Platform plugin for managing [Hole](https://github.com/lukashornych/hole) sandboxes: a bottom tool window
in which each tab runs `hole start` in a real terminal, with dialogs for starting sandboxes, favourites, settings file
links and a runtime network editor. The plugin talks to Hole only through its CLI.

Technology stack: Java 21, Gradle with the IntelliJ Platform Gradle Plugin 2.x, IntelliJ Platform 2025.2+ with the
bundled Terminal plugin (jediterm, pty4j), Lombok, JUnit 5 (+ vintage engine for platform tests).

For questions about Hole itself, read its sources — a local checkout next to this repository (`../hole`) when there
is one, otherwise [GitHub](https://github.com/lukashornych/hole). Never modify them.

## Documentation

Developer documentation lives in `documentation/developer/` — **read it before analyzing source code**; it covers
the behaviour, architecture, conventions and step-by-step recipes:

- [index](documentation/developer/index.md) — TOC with recommended reading order and repository layout
- [development](documentation/developer/development.md) — prerequisites, build and test commands, the fake `hole`
  script, checks against a real Hole, other IDE versions
- [specification](documentation/developer/specification.md) — the behaviour of every window, dialog, action and
  lifecycle rule; the source of truth for *what* the plugin does
- [architecture](documentation/developer/architecture.md) — packages, session state machine, metadata polling,
  process-group signals, terminal, persistence, threading, testability
- [guidelines](documentation/developer/guidelines.md) — toolchain, Java conventions, non-negotiable rules, testing,
  git workflow
- [recipes](documentation/developer/recipes.md) — add a sandbox option, call a new Hole command, extend the fake
  `hole`, pre-PR checklist
- [build & release](documentation/developer/build-and-release.md) — versioning, CI workflows, release notes

When implementing or changing any functionality, it HAS TO BE REFLECTED in the documentation files: behaviour in
the specification (and user-visible features in `README.md`), developer-facing changes in `documentation/developer/`.

### Source code pollution

Don't overcomment the source code itself; focus documentation into `documentation/developer/`. Javadoc and comments
keep only what the code cannot express (a Hole contract, a platform quirk, a non-obvious reason) — never
implementation-plan content like phases, task lists or references to plans and analyses.

## Building

```bash
./gradlew test                  # all tests (fake `hole` script, no Docker needed)
./gradlew buildPlugin           # ZIP in build/distributions/
./gradlew runIde                # sandbox IDE with the plugin
./gradlew verifyPlugin          # Plugin Verifier against the recommended IDEs (downloads several GB)
HOLE_REAL_BINARY=$(command -v hole) ./gradlew test --tests '*RealHoleBinaryTest'
                                # opt-in checks against a real Hole (needs `docker`/`podman` CLI with compose on PATH)
```

`verifyPlugin` must stay *Compatible* with no deprecated/internal API usages. Inside a Hole sandbox, the hosts listed
in `.hole/settings.json` must be allowed for Gradle to resolve the platform. Details:
[development](documentation/developer/development.md).

## Constraints

- Java 21 toolchain, Lombok, constructor injection; target IntelliJ 2025.2+ (`sinceBuild = 252`, no upper bound).
- Supported Hole: 2.6.0 or newer (`hole list --json`, `--instance-id`, `hole settings get|create`, `hole modify`).
- No internal, experimental or deprecated platform APIs; no new dependencies.
- Code instrumentation is on: `@NotNull` parameters are checked at runtime, so null handling of persisted state
  belongs in the `storage` beans, not in model records.
- Never log the environment, settings file contents or full `hole start` command lines (agent arguments may hold
  API keys).
- Test data must be anonymised (no real user paths or customer project names).
- Hole is the source of truth: use its machine-readable CLI only; never read its internal files or parse its
  human-readable output. Stop sandboxes by signalling the process group of `hole start`.

## Layout (`io.github.lukashornych.hole.idea`)

- `hole` — Hole CLI clients (`version`, `list --json`, `settings get|create`, `modify`), settings file resolver,
  command builder, metadata poller.
- `process` — plain CLI runner and executable lookup.
- `session` — sandbox lifecycle: PTY process (pty4j), signals to the process group, terminal connector, image paste
  directories (`PasteDirectory`, `PasteStore`).
- `storage` — persisted settings and favourites (application and project level).
- `ui` — tool window (incl. the favourites start page), sandbox tabs, dialogs, settings page; `ui/network` — the
  runtime network window.
- `model`, `git` — value records and `git worktree list` parsing.

Tests use `src/test/resources/fake-hole/hole`, a shell script that mimics Hole 2.6.0 including `settings create` and
`modify` (switches documented in its header). Prefer integration tests with it over mocks. Every bug fix comes with
a regression test.

## Mandatory advisor calls (cost discipline)

ALWAYS call advisor() BEFORE:

- Invoking /batch or any skill that spawns multiple parallel Agent calls
- Spawning 3+ Agent tool calls in a single turn
- Committing to a multi-hour delegation plan (10+ work units)
- Any action that crosses multiple repositories

Non-negotiable. A 30-second advisor call is cheaper than a failed 90M-token orchestration. Advisor sees the full
transcript and will flag memory-rule violations, cost concerns, and fan-out mistakes before they happen.

## Analyzing, planning and ADR

When an analysis of a problem is requested, do not write an implementation plan, just state the facts the developer
and their team need to know to make a decision. These documents are stored inside this project at
`documentation/analysis/`.

If an implementation plan of a specific request is requested, write a detailed implementation plan and store it
inside this project at `documentation/plans/`, and delete it after an ADR record is written (see below).

`documentation/adr/` holds one dated record per line of work — what was done, when, why, which alternatives were
declined **and why they lost**, and what it relates to. It is the project's memory of reasoning; the code only
remembers outcomes.

- Before starting work, search it for the area you are touching and read the "Consequences & open follow-ups" of
  every hit — known traps and deliberately-deferred work live there.
- After finishing work that clears the bar, write the record and delete the `documentation/plans/` it replaces. Not
  optional, and not a follow-up task.
- Never attribute anything to a customer — see "No customer attribution" in `.claude/rules/adr.md`.
- In-flight plans `documentation/plans/` and analyses `documentation/analysis/` are never committed to git. They are
  working notes, not a deliverable — tracking them puts intent into the history where a later reader cannot tell it
  apart from outcome. They are git-ignored to make this automatic rather than a matter of remembering, and no
  tracked file may link into them.
- A plan must leave the disk when the work finalizes — merged, abandoned, or decided against. Two exits, no third:
  write the ADR and delete the plan, or delete the plan outright. A plan left behind reads as current intent long
  after it stopped being true.
- The bar defaults to no. All three must hold: a genuine fork (a reasonable alternative was rejected for a reason the
  code doesn't show), reasoning that outlives the change (it constrains work beyond the files you touched), and
  nothing cheaper that carries it (a commit message or a code comment is the first choice). Most work does not clear
  it — a thin ADR is worse than none. Never for renames, dependency or platform version bumps, fixture or fake
  `hole` tweaks, or anything the diff plus its commit message already explains.

Template: `documentation/adr/TEMPLATE.md`. Index: `documentation/adr/README.md`, generated by
`scripts/generate-adr-index.sh`. Full rules — when a record is required, which date it carries, what to keep and what
to delete: `.claude/rules/adr.md`.

## Git Workflow

- `main`: released versions only — every push to `main` triggers the release workflow
- `dev`: current development (target for PRs and feature branches)
- Feature branches: created from `dev`
- Use [conventional commits](https://www.conventionalcommits.org/) — release versioning depends on this (`feat:`
  bumps minor, `!`/`BREAKING CHANGE` major, everything else patch); keep the description coarse and do not mention
  any author
- Details: [guidelines — git workflow](documentation/developer/guidelines.md#git-workflow)
- Do NOT commit any changes unless explicitly asked.

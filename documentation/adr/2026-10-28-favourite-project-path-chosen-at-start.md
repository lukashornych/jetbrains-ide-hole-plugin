---
title: Favourites store no project path; the project root or a git worktree is chosen at start
date: 2026-10-28
updated: 2026-10-28 16:02
status: accepted
kind: feature
issues: []
prs: []
areas: [src/main/java/io/github/lukashornych/hole/idea/model, src/main/java/io/github/lukashornych/hole/idea/storage, src/main/java/io/github/lukashornych/hole/idea/ui]
supersedes: []
superseded-by: []
relates: []
---

# Favourites store no project path; the project root or a git worktree is chosen at start

A favourite no longer carries a project path. Starting one (dropdown next to `+`, start page) lists the git
worktrees of the IDE project root in the background: without a linked worktree it starts at once in `.`; otherwise a
popup offers *Project root* (first, selected) and every other non-bare worktree, and Escape cancels. The New Sandbox
dialog keeps its *Project path* combo; Edit Favourites no longer shows it.

## Why

Git worktrees come and go, so a project path stored in a favourite was the part that went stale: a favourite saved
for a worktree pointed at a directory that no longer existed once the worktree was removed. A favourite should keep
*how* to start (agent, profile, options, libraries, arguments); *where* depends on the worktrees that exist at the
moment of the start.

### Previous state

`FavouriteBean` persisted `projectPath`, the Edit Favourites form edited it, and a favourite started exactly there.
Global favourites were advised to use `.` so that they worked in any project.

## Options considered

### Option A — no stored path, choice at start (chosen)

- **Pros:** one favourite serves the project root and every current worktree; nothing goes stale; no new setting;
  the common case (no worktrees) stays one click.
- **Cons:** a second click when worktrees exist; stored paths of existing favourites are dropped on load.

### Option B — keep storing a project path per favourite (previous state)

- **Pros:** a favourite can target a fixed directory, also outside the project; no migration of stored favourites.
- **Cons:** the stored path goes stale with the worktree it names; worktrees are not offered at start.
- **Rejected because:** the stale path is the failure this change removes, and the path was asked to be dropped
  altogether rather than kept next to a worktree choice. Revisit if favourites must be pinned to a directory outside
  the project (the New Sandbox dialog covers that ad hoc).

### Option C — map the IDE project's sub-directory into the chosen worktree

- **Pros:** an IDE project opened on `repo/module` would start in `worktree/module`.
- **Cons:** needs the project root's position inside its worktree and a check that the sub-directory exists in the
  other worktree.
- **Rejected because:** deferred, not refused — no current setup needs it, and the top level of a worktree is a
  correct (if wider) sandbox. See follow-ups.

## Decision

**Chosen: Option A.** It removes the stale-path failure instead of working around it, and costs nothing when the
project has no worktrees. Option B would win again if favourites had to target fixed directories outside the project.

## Key technical details

- `model/Favourite` normalises `config.projectPath` to `.` in its compact constructor, so *Save as Favourite…* and
  every other construction drop the path; `SandboxConfig` stays one record because New Sandbox needs the path.
- `storage/FavouriteBean` has no `projectPath`; `XmlSerializer` ignores the option written by older versions (no
  error, not written back).
- `ui/StartLocations` (pure) builds the choices; the worktree containing the project root is left out by comparing real
  paths (git prints real paths, the IDE path may go through a symlink such as `/var` → `/private/var`), longest prefix
  wins for nested worktrees.
- `ui/FavouriteStarter` runs `git worktree list` on a pooled thread for every start (no cache) and shows the popup
  with the caller's placement. The debug flag comes from the click on the favourite; Shift in the location popup is
  not read.
- `ui/SandboxForm` takes `projectPathEditable`: without it the path row is not mounted, worktrees are not loaded, the
  config has `.` and `PROJECT_PATH` problems are filtered out. The Edit Favourites command preview therefore shows `.`.

## Verification

- `StartLocationsTest` — no/only-main worktree → one location; linked worktrees in git order; bare skipped; project
  root in a linked worktree, a sub-directory or a nested worktree; detached label; a real `git init` + `git worktree
  add` opened through a symlink.
- `FavouritesPersistenceTest` — legacy XML with `<option name="projectPath" value="…"/>` loads with `.` and is not
  written back; `Favourite` drops the path also through `withConfig`.
- `SandboxFormTest`, `EmptyStateModelTest`, `EditFavouritesDialogTest` — `.` and no path validation when not editable,
  secondary texts show the agent only.
- `./gradlew test` on Linux with the fake `hole` and `LC_ALL=C.UTF-8`: 333 tests, 5 skipped, 0 failures. Not
  exercised: macOS (the `/private/var` case the real-path comparison exists for) and the popup itself in `runIde`.

## Consequences & open follow-ups

- Favourites saved with a non-`.` path silently become root/worktree-chosen favourites; this is intended.
- **Sub-directory mapping deferred** (Option C): an IDE project on `repo/module` starts a worktree at its top level
  while *Project root* starts in `.` (`ui/StartLocations`).
- **No progress or cancel UI** while `git worktree list` runs (10 s timeout, `git/WorktreeLister.TIMEOUT`), mirroring
  `ProjectPathCombo`; a hanging git delays the start silently.

## Timeline

- **2026-10-28** — implemented and accepted

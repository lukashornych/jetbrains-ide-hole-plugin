---
title: Common day-to-day settings may be edited in the GUI, each through a narrow editor of one top-level key; libraries are the second
date: 2026-11-05
updated: 2026-11-06 07:00
status: proposed
kind: feature
issues: []
prs: []
areas: [src/main/java/io/github/lukashornych/hole/idea/hole, src/main/java/io/github/lukashornych/hole/idea/ui, src/main/java/io/github/lukashornych/hole/idea/ui/settings, src/main/java/io/github/lukashornych/hole/idea/ui/library, src/main/java/io/github/lukashornych/hole/idea/ui/network]
supersedes: []
superseded-by: []
relates: [2026-11-05-network-entries-saved-to-settings-by-the-plugin]
---

# Common day-to-day settings may be edited in the GUI, each through a narrow editor of one top-level key; libraries are the second

A sandbox header action **Libraries…** opens a non-modal window that lists the top-level `libraries` of the global
and project settings files (merged as Hole merges them, plus the `--library` values of this start) and adds, edits
and removes libraries in either file. Hole cannot change the mounts of a running sandbox, so changes are marked
*restart to apply* and closing the window after a change offers the usual restart. With it, the plugin's rule on
settings content changes: instead of "no GUI for the settings content, network entries being the single exception",
**common day-to-day settings may be changed in the GUI**, each one a curated, top-level setting with a narrow editor
of its own; there is never a generic settings-content editor, the files stay Hole's and unknown keys are kept.

## Why

Adding or removing a sibling project or a shared library to the mounts is a day-to-day task while working with
agents, and it was only possible by opening the settings file and writing the JSON by hand (string shorthand or an
object with `readwrite`, the merge of the two files, trust in project files). Hole has no CLI that writes a settings
file. The standing rule (`guidelines.md`, and the network ADR's decision "limited to the top-level arrays … so it
cannot grow into a settings editor") forbade the plugin from doing it, so it needed a decision rather than a
feature branch.

### Previous state

The guidelines said *No GUI for Hole's settings content*, with the Network window's saved entries as the single
sanctioned exception; the specification said the plugin does not provide a GUI for the settings content; the
architecture's *Hole is the source of truth* principle named the Network window as the one place that reads and
writes settings content. That was acceptable while network entries were the only setting users changed during a
session.

## Options considered

### Option A — a narrow editor per day-to-day setting, rule changed (chosen)

The plugin edits one top-level key per window (`hole.SettingsLibraries`, sibling of `SettingsEntries`), through the
IDE document of a file Hole names (`ui.settings.SettingsDocuments`, extracted from the Network window's saver so both windows
share it). Untouched entries keep their form, unknown keys stay, `profiles` is never read or written.

- **Pros:** the task is done where the sandbox is; works with every supported Hole (2.6.0+); the shape of `libraries`
  (`map[host] → "mount" | {"path", "readwrite"}`) is stable and documented in Hole's README.
- **Cons:** the plugin now knows the shape and the merge rule of a second key; every further setting needs the same
  care (merge semantics, trust gating, form preservation); a hand-formatted file is re-indented on the first save.

### Option B — a read-only Libraries window (table, *Open file* links, restart offer) (declined)

- **Pros:** no new settings-writing code; the rule stays as it was.
- **Cons:** the user still writes the JSON by hand for the common case.
- **Rejected because:** adding or removing a library is a day-to-day task; a window that only shows the libraries
  and then sends the user to the file saves little over the *Global* / *Project* links the header already has.
  Revisit only if the write path turns out to break files Hole reads (none known).

### Option C — wait for a Hole command (`hole settings libraries add|remove`) (declined)

- **Pros:** the settings contract stays entirely in Hole, with its schema validation.
- **Cons:** blocks on a Hole release and raises the minimum supported Hole for everyone.
- **Rejected because:** the same reason as Option B of the network ADR: a release dependency and a minimum-version
  bump for a write of one stable key. Revisit when Hole ships such a command: then `SettingsLibraries` is replaced by
  the CLI call, like `SettingsEntries` would be.

## Decision

**Chosen: Option A.** The rule is: a setting gets a GUI editor only when it is common day-to-day work, top-level and
small enough for a narrow editor; the editor changes only its key and keeps the file Hole's. A generic
settings-content editor (a form or tree over the whole document) stays out, because Hole's keys change often and the
plugin would have to follow its schema. This lifts the "cannot grow into a settings editor" limit of the
[network ADR](2026-11-05-network-entries-saved-to-settings-by-the-plugin.md) — that record's decision (the plugin
edits the JSON itself) stands and is now the general rule rather than an exception. A further setting needs a reason
why it is day-to-day, written into the guidelines' list (and a record when it carries a fork of its own).

## Key technical details

- `hole/SettingsLibraries.java` — pure JSON edit. Read-only libraries are written as the shorthand, read-write ones in
  the object form; Edit never collapses an object to a string and writes both `path` and `readwrite` (a project
  object without `readwrite` keeps a global `true` after Hole's merge, so an explicit `false` is the only way to
  switch it off).
- `SettingsLibraries.merged` reproduces Hole's `config.Merge` for one key: per **literal** key, two objects merge
  field by field, anything else takes the project value. `~/lib` and `/home/me/lib` are two entries there; Hole
  resolves them only in `mergeLibraries` (keyed by resolved path, from a Go map, so which one wins is not
  predictable). `HostResolver` (`~`, relative → project directory, normalise; no `$VAR`) is used only to warn and to
  refuse an Add of a directory already in the file — never to merge.
- No check that the global settings file lies inside a new library: Hole checks only a `HOLE_SETTINGS_FILE` override
  (`checkSettingsFileNotExposed`), which the plugin cannot see without computing Hole's paths; Hole reports it in the
  tab.
- *Restart to apply* compares with a snapshot taken when the window first opens for an instance id (kept by
  `SandboxTabPanel`, like the Network window's removals), not at sandbox start; edits made in the file before that
  are not marked.
- The window closes on a new instance id or tab close, not on EXITED (unlike the Network window): fixing a library is
  how a failed start is repaired. Closing it after a change offers the restart only while RUNNING; in EXITED it shows
  a hint instead.
- `ui/settings/SettingsDocuments.java` — the document write plus `hole settings get|create` shared with
  `ui/network/SettingsEntrySaver`; every `ModalityState` must come from the window or modal input that asked (the
  network ADR's regression).

## Verification

- `SettingsLibrariesTest` — both value forms, shorthand vs. object on add, key order and unknown keys kept, `profiles`
  untouched, refusal of the same directory under another key, invalid shapes, literal-key removal, form preservation
  on change, Hole's per-literal-key merge (object+object, string over object, object over string), host path
  resolution, mount path check.
- `LibraryFilesTest` (platform test, fake `hole`) — missing files, merge of both files, an invalid file reported,
  global file created on add, project file of the sandbox directory, refusals (duplicate, missing directory), change
  and remove, add while a modal input is open.
- `LibraryTableModelTest`, `LibrariesDialogTextsTest`, `SettingsDocumentsTest`, `HeaderModelTest` (the toolbar flag).
- Not yet tried in a running IDE (`runIde`) and not run through `verifyPlugin`.

## Consequences & open follow-ups

- `this start` (`--library`) rows are read-only; removing one would need per-instance memory of a changed launch
  config.
- The New Sandbox / Favourites library rows still have no mount path (`model.Library` has no `MOUNT`), although
  `--library PATH[:MOUNT][:rw]` allows one.
- Libraries of `profiles.<name>.libraries` and git worktree links are not listed (a note says so); a profile library
  is edited in the file.
- The EXITED hint asks the user to press Restart in the sandbox header, which needs the restart of an exited sandbox;
  until that ships the toolbar action is enabled only while RUNNING.
- Each load runs `hole settings get` twice (both files), like the Network window; files changed outside the IDE are
  seen after the IDE's VFS refresh (frame activation).

## Related work

- [2026-11-05 network entries saved to settings](2026-11-05-network-entries-saved-to-settings-by-the-plugin.md) —
  the first narrow editor; this record lifts its "cannot grow into a settings editor" limit and reuses its saver.

## Timeline

- **2026-11-05** — decided: full editor for `libraries`; the rule on settings content changed
- **2026-11-06** — implemented

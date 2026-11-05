---
title: The Network window reads, saves and removes its entries in the global and project settings files by editing the JSON itself
date: 2026-11-05
updated: 2026-11-05 22:30
status: proposed
kind: feature
issues: []
prs: []
areas: [src/main/java/io/github/lukashornych/hole/idea/hole, src/main/java/io/github/lukashornych/hole/idea/ui/network]
supersedes: []
superseded-by: []
relates: []
---

# The Network window reads, saves and removes its entries in the global and project settings files by editing the JSON itself

Every **Add** in the Network window (Blocked tab prefills, Allowed and Host gateway *Add…*) now asks in a popup how
long the entries stay: only until the sandbox stops (default, the previous behaviour), or also saved to the global or
project settings file. Saving runs after the runtime `hole modify … add` succeeded; the plugin asks Hole for the file
(`hole settings get`, `create` when missing) and appends the entries to the top-level `network.allow` /
`network.hostGatewayDomains` array itself. The entry lists read both files to show a *Saved in* column, and
**Remove** can take an entry out of the files (one confirmation; when an entry in force from settings was removed, the
usual restart confirmation appears once the user closes the window, so several changes share one restart).

## Why

A domain allowed at runtime is gone on the next start, so a domain needed every time had to be allowed again or
copied into a settings file by hand. Hole has no command that writes a settings file: `hole modify` is documented as
"changes the running sandbox only and is never saved to a settings file", and `hole settings create --append` only
merges the template. The plugin's rules said *Hole is the source of truth* and *no GUI for Hole's settings content*,
so persisting needed a decision.

## Options considered

### Option A — the plugin edits `settings.json` (chosen)

Parse with Gson (already used for `hole list --json`), extend one array, write the whole document back with Hole's
formatting (two-space indent, key order kept, trailing newline), through the file's IDE document.

- **Pros:** works with every supported Hole (2.6.0+); Hole reads the file with `encoding/json` and keeps unknown keys,
  so a round trip loses nothing Hole cares about; the path still comes from Hole.
- **Cons:** the plugin now knows the shape of two settings keys; a hand-formatted file is re-indented on the first
  save.

Reading the files was not optional once saving existed: `hole modify list` reports a saved entry as `runtime`
until the restart and a base entry only as `settings`, without the file, so neither *Saved in* nor a Remove from
settings can be built on Hole's output alone. Keeping an in-memory list of what this window saved was considered and
dropped: it is lost when the window reopens and cannot locate entries saved earlier or by hand.

### Option B — a Hole command first (`hole settings … add` / `hole modify --persist`) (declined)

- **Pros:** the settings contract stays entirely in Hole, with its schema validation and migration checks.
- **Cons:** the feature waits for a Hole release and raises the minimum supported Hole version for everyone.
- **Rejected because:** it blocks on a Hole release and a minimum-version bump for a write of one array whose shape is
  stable. Revisit when Hole ships such a command: then `SettingsEntries` is replaced by the CLI call and the
  guideline exception is dropped.

## Decision

**Chosen: Option A**, limited to the top-level arrays of the two `hole modify` settings so it cannot grow into a
settings editor (the exception is written into the guidelines).

## Key technical details

- `hole/SettingsEntries.java` — pure JSON edit; rejects a document that is not an object or whose `network` / array
  has another type (the file is left alone, the window shows the reason). Never touches `profiles`.
- Matching uses `SettingsEntries.canonical`, a copy of Hole's canonical form (lower-case host, sorted ports,
  default `80,443`). CIDRs are not masked and IPs not normalised as Hole does, so such an entry written differently
  in a file shows an empty *Saved in* and cannot be removed here. If Hole changes `network.Entry.String()`, matching
  breaks silently.
- Every `ModalityState` handed to `SettingsEntrySaver` comes from the window that asked: the Add input is modal, and
  an `invokeAndWait` in the non-modal state waited until the user closed it by hand (regression test
  `SettingsEntrySaverTest.testSaveFinishesWhileTheModalInputIsOpen`).
- `ui/network/SettingsEntrySaver.java` — resolves the file via `SettingsFileResolver` (creates a missing one without
  asking: choosing *Also Save* is the consent), edits it in a `WriteCommandAction` on the document and saves it, so
  an open editor's unsaved edits are kept and the change is undoable.
- `NetworkDialog.add` — runtime add first (it validates the entries, exit 2 keeps the input open); a save failure is
  reported as *Allowed in this sandbox, but not saved…* and does not roll back the runtime entry.
- The entry is saved as typed, after `hole modify add` accepted it: Hole's `network.ParseEntry` /
  `ParseHostGatewayDomain` use the same domain pattern as `assets/schema/settings.schema.json`, whose patterns are
  looser everywhere else (ports, CIDR), so an accepted entry always passes the settings validation of `hole start`.
  If Hole ever tightens the schema beyond the runtime parser, this breaks first.
- Project settings: Hole's trust gate hashes the `network.*` values of a project file, so a saved entry makes the next
  `hole start` ask for trust again; the popup says so.

## Verification

- `SettingsEntriesTest` — template, append without duplicates (canonically), unrelated keys and `profiles` kept,
  blank document, invalid shapes, canonical form, reading, removal.
- `SettingsEntrySaverTest` (platform test, fake `hole`) — missing global file created and extended, project file of
  the sandbox directory, invalid file left unchanged, unsaved editor changes kept, save while a modal input is open,
  saved entries of both files, removal.
- `NetworkDialogTextsTest` — default target, failure text, *Saved in* pairing, removability, remove confirmation,
  an entry removed with the restart cancelled (marked, not removable).
- The first iteration (popup and save only) was tried manually in a running IDE (versions not recorded); it found the
  modal-wait bug (the Add input did not close by itself) and that saved entries could not be told apart from runtime
  ones, which led to *Saved in* and Remove from settings. The second iteration was tried manually as well; it found
  the unmarked leftover row after a Remove with the restart cancelled, now covered by `NetworkDialogTextsTest`.
- `SettingsEntrySaverTest` enters a modal state through `LaterInvocator` (`…application.impl`, internal): test code
  only, there is no public way to build a modal `ModalityState` there.

## Consequences & open follow-ups

- Saved entries show the source `runtime` until the sandbox restarts (Hole reports them so); *Saved in* shows the file.
- Every list load runs `hole settings get` twice more (both files); no caching, because a file may appear meanwhile.
- After a Remove from settings with the restart cancelled, the entry stays listed with source `settings` until the
  restart (the gateway still enforces it), marked *removed — restart to apply*. Hole cannot say an entry left the
  file, so the sandbox tab remembers the removals per instance id; a removal made by hand, or in another IDE window,
  shows the profile hint instead.
- A settings file the user formatted by hand is re-indented by the first save.

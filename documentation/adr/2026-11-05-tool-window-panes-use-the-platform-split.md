---
title: Sandbox tabs are shown side by side through the platform's tool window split, with one listener per tab
date: 2026-11-05
updated: 2026-11-06 09:00
status: proposed
kind: feature
issues: []
prs: []
areas: [src/main/java/io/github/lukashornych/hole/idea/ui]
supersedes: []
superseded-by: []
relates: []
---

# Sandbox tabs are shown side by side through the platform's tool window split, with one listener per tab

The Hole tool window opts in to the IntelliJ Platform's own tool window split (the `ALLOW_DND_FOR_TABS` client property
up to 2025.3, the experimental `ToolWindow.setTabsSplittingAllowed(true)` from 2026.1 — both set, see below):
*Split Right* / *Split Down* / *Unsplit* in the tab context menu and dragging a tab to an edge show whole, already
running sandboxes in panes, horizontally, vertically and nested. Because each pane has its own nested
`ContentManager`, the tool window view no longer listens on the root content manager: every sandbox tab has its own
listener that follows the tab from manager to manager, treats moves (`Content.TEMPORARY_REMOVED_KEY`) as neither a
close nor a user selection, and every view call goes to the manager the tab is in now. A new sandbox opens in the
pane whose `+` was clicked.

## Why

Users run several agents at once and want to watch two of them side by side. Hole has no command that opens a second
shell in a running sandbox, so a pane can only show another sandbox's tab, not a new process.

The platform split is opt-in per tool window, and the plugin's tab lifecycle was built for one content manager. The
moment the window opts in, a split fires `contentRemoveQuery` / `contentRemoved` on the root for every moved tab —
which the plugin turned into SIGINT and session disposal — while closes inside a pane reached no plugin listener at
all (no stop request, sessions left without a tab), removals through the root were rejected for tabs in panes (the
tab of an exited sandbox never went away), and new sandboxes always landed in the first pane.

### Previous state

`HoleToolWindowView` registered one `ContentManagerListener` on `toolWindow.getContentManager()` and called only that
manager. Correct while the window could not be split.

## Options considered

### Option A — the platform's tool window split (chosen)

Set `ALLOW_DND_FOR_TABS` on the tool window component (as the Run tool window does) and make the view work with any
number of content managers.

- **Pros:** both directions, nesting, drag and drop between panes and *Unsplit* for free; the platform copies the tab
  actions (`+`, the favourites arrow) into every pane; looks and behaves like the Run tool window, so users already
  know it; no layout code of our own.
- **Cons:** the opt-in has no stable API across the supported IDEs (see *Opt-in across IDE versions*);
  the split's internals (nested managers, moves as remove + add, a content without a manager during a drag) leak
  into the plugin's lifecycle code; the split layout is not persisted (sandboxes are not either).

### Option B — an own `JBSplitter` layout inside one content (declined)

One content holding a splitter tree of sandbox panels.

- **Pros:** fully under the plugin's control, public API only, no nested content managers.
- **Cons:** re-implements split, nesting, drag and drop and unsplit; tabs would no longer map one-to-one to sandboxes.
- **Rejected because:** it duplicates a feature the platform already has, and once the platform split is available
  to the window two splitting mechanisms in one tool window confuse users. It was reconsidered when `verifyPlugin`
  showed the opt-in to be deprecated / experimental, and declined again for the same reason. Revisit if the platform
  removes `setTabsSplittingAllowed` without a stable successor.

### Option C — keep the split disabled (declined)

- **Pros:** no lifecycle changes at all; the single-manager view stays correct, since nothing can split the window.
- **Cons:** no way to see two sandboxes side by side.
- **Rejected because:** the requirement is to show sandboxes side by side; the window had no split before, and
  nothing else gives it.

### Opt-in across IDE versions (sub-decision)

`verifyPlugin` (2026-11-06) showed that the opt-in changed after 2025.2: `ALLOW_DND_FOR_TABS` is deprecated from
2025.3 (its replacement there, `ToolWindowContentUi.setAllowTabsReordering`, is deprecated in 2026.1 too) and from
2026.1 no longer enables splitting at all; only `ToolWindow.setTabsSplittingAllowed(true)` does, which is
`@ApiStatus.Experimental` and does not exist in 2025.2. No API that is neither deprecated nor experimental covers
`sinceBuild = 252` and newer.

- **Chosen: set both.** The client property (deprecated from 2025.3) for 2025.2 / 2025.3, and
  `setTabsSplittingAllowed(true)` called reflectively (it is absent at compile time against 2025.2) for 2026.1+. Both
  live in `HoleToolWindowFactory.allowTabSplitting`, marked `@ApiStatus.Experimental`, and are the project's only
  deliberate exception to the no-deprecated / no-experimental rule (guidelines, *Platform API*).
- **Raise `sinceBuild` to 261 and call only `setTabsSplittingAllowed` — rejected because:** it drops 2025.2 and
  2025.3 users for one feature and still uses an experimental API.
- **Only the client property — rejected because:** panes would silently not exist from 2026.1 on.
- **Hold the feature until a stable API exists — rejected because:** no date for one; the Run and Terminal tool
  windows are the API's named users, so it is unlikely to disappear without a successor.

When `sinceBuild` reaches a release where the API is stable, drop the client property and the reflection.

### Listener: one per tab vs. one on the root (sub-decision)

One listener per content, registered on `content.getManager()` and moved along through the content's
`"contentManager"` property change — the pattern of the platform's `BaseContentCloseListener`.
**One root listener rejected because:** listeners fire only for a manager's own contents, they are not copied to pane
managers, and there is no public API to enumerate nested managers or be told about a new pane.

### Placement of a new tab (sub-decision)

The pane whose `+` (or arrow) was clicked; fallbacks: the pane of the most recently selected sandbox, then the root
(which delegates to the first pane).

- **`SELECTED_CONTENT_TAB_LABEL` / `CONTENT_MANAGER` from the action's data context — rejected because:** both are
  provided only by the content manager's component, while the tab toolbar's target is the pane's header, a sibling
  of it; the `+` action would most likely see neither (unverified at runtime). Both are also `platform-impl` types.
  Chosen instead: walk the Swing hierarchy from the clicked button to the nearest ancestor that shows a sandbox tab
  (public API only; the cell decorator holds the pane's header and its tab).
- **Always the first pane (platform default for the root manager) — rejected because:** clicking `+` in the right
  pane and getting the sandbox on the left is surprising.

### Selection history (sub-decision)

Kept global across panes (`TabHistory`): closing a tab selects the previously selected sandbox in whichever pane it
is. Limiting it to the pane was considered; global keeps today's rule unchanged and needs no per-pane bookkeeping.

## Decision

**Chosen: Option A with a per-tab listener, opted in through both the deprecated key and the experimental API.** It
delivers the requirement with the platform's well-known UI at the cost of one deliberate API-rule exception and
lifecycle code that knows about moves. If the platform removes the split for plugins, or changes how a split moves
contents (no `TEMPORARY_REMOVED_KEY`, or listeners copied to pane managers), revisit: Option B becomes the fallback.

## Key technical details

- `ui/HoleToolWindowFactory.allowTabSplitting` opts in (client property + reflective `setTabsSplittingAllowed`, a
  missing method is ignored); `ui/SandboxContentListener` is the per-tab listener;
  `ui/HoleToolWindowView` routes every call through `content.getManager()`.
- **Moves.** A move fires remove query and removal on the source with `Content.TEMPORARY_REMOVED_KEY` set; both are
  ignored. A move also makes the source pane select a neighbour (that tab is *not* marked), so selections are not
  reported to `TabHistory` while any tab is between two managers (remove query seen, add not yet).
- **Drag.** Between drag start and drop the content belongs to **no** manager. `select` is skipped; `remove` is
  remembered and done after the drop via `invokeLater` — the drop's `addContent` still runs with the key set, and
  the content is not in the manager's list when its manager property changes.
- **Already selected.** `setSelectedContent` of a pane's selected tab fires no event, so `select` reports the
  selection itself; `remove` restores the manager's chosen selection (without focus) after the pane selects a
  neighbour or an unsplit — triggered by the pane's last tab closing — selects the first tab moved back.
- **Drag start gap.** `ToolWindowInnerDragHelper.startDrag` sets `TEMPORARY_REMOVED_KEY` at once but removes the
  content from its manager in a later event; `remove` treats a marked content like one without a manager (pending),
  otherwise it would dispose a tab the drop then adds again.
- **"Selected" is tab selection, not focus.** Clicking into the terminal of a pane whose tab is already selected does
  not change the selected sandbox; this affects only the placement fallback and *select previous on close*.
- `"contentManager"` is passed as a string, not `ContentImpl.PROP_CONTENT_MANAGER`, to keep `ContentImpl` out of the
  plugin.

## Verification

12 headless platform tests in `HoleToolWindowViewTest` (real `ContentManagerImpl` root with nested managers created
by `addNestedManager`, moves done like `InternalDecoratorImpl.moveContent` incl. `setManager` before `addContent`,
the unsplit simulated by a pane listener registered before any content that moves the tabs back and disposes both
panes, fake `hole` 2.6.0) and `HoleToolWindowFactoryTest` (the opt-in sets the client property and tolerates the
missing 2026.1 method on 2025.2); the full suite has 388 tests, all green (5 skipped). The pane scenarios:

- `testMovingTabsIntoPanesKeepsTheirSandboxesAndTheSelectionHistory`
- `testClosingATabInAPaneStopsTheSandboxAndRemovesTheTabFromThatPane`
- `testClosingAnExitedTabInAPaneRemovesIt`
- `testSelectingATabInAPaneIsRecorded`
- `testNewSandboxOpensInTheClickedPaneElseInThePaneOfTheSelectedSandbox`
- `testClosingTheLastTabOfAPaneKeepsThePreviouslySelectedTabSelectedAfterTheUnsplit`
- `testClickedPaneIsTheCellOfTheClickedButton`
- `testATabMarkedForDragButStillInItsPaneIsRemovedOnlyAfterTheDrop`
- `testRemovalOfADraggedTabIsAppliedWhenItIsDropped`

The move guard, the re-selection and the drag-start-gap check were each disabled once to confirm a test fails.
`verifyPlugin` (IC-252.28539.149, IU-253.33813.99, IU-261.27258.136, IU-262.11879.100, IU-263.7307.200): all
*Compatible*; deprecated usage reported on 253+ for `ALLOW_DND_FOR_TABS` (expected) and on 261+ for
`ReadAction.compute`, which predates this work (`ui/network`). The reflective call is not visible to the verifier.
Not yet verified in `runIde` (see below).

## Consequences & open follow-ups

- **On by default.** IntelliJ 2025.2 ships `ide.allow.split.and.reorder.in.tool.window=true` (`registry.properties`
  in `lib/util-8.jar`, restart required), so the split is available as soon as the window opts in; a user who turns
  the key off gets the unsplit window. From 2026.1 the split depends only on `canSplitTabs()`, not on that key.
- **API-rule exception.** `verifyPlugin` reports the deprecated key on 2025.3+ until `sinceBuild` passes the
  release where `setTabsSplittingAllowed` is stable; then remove the key and the reflection.

- **Unverified at runtime** (no `runIde` run yet): panes on 2026.1+ through the reflective
  `setTabsSplittingAllowed` call (*Split Right* in the tab menu, drag to an edge); the `+` placement walk in an unsplit, split and nested window; focus
  and pty resize after a move; a sandbox exiting during a drag; unsplit disposing pane managers while the moved tabs
  stay alive (content re-parenting); closing the last tab of a pane (visible selection after the unsplit); the
  header tab strip look with the property set; the empty state (*No sandboxes are running*) while the window is
  split; `+` invoked from the keyboard (the walk then starts from the focused component).
- **Widget disposal may kill `hole` directly.** jediterm's `ProcessTtyConnector.close()` calls `Process.destroy()`; if
  disposing a terminal widget closes its connector, a tab disposed while its sandbox runs bypasses the process-group
  SIGINT (latent today on project close). Deliberately deferred to a separate fix after observing it in `runIde`.
- **Known gap:** a drag cancelled before the drag helper removed the content (the content never left its manager)
  fires no `contentAdded`, so a removal postponed in that gap is not applied and the ended sandbox's tab stays until
  closed by hand. Not handled: it needs a sandbox exit within one EDT event of a drag start; a fix would watch the
  content's `TEMPORARY_REMOVED_KEY` being cleared, which fires no event.
- The split layout is not persisted by the platform.

## Timeline

- **2026-11-05** — analysed the platform split (IntelliJ 2025.2 sources) and decided on panes via the platform split
- **2026-11-06** — implemented on `feat/tool-window-panes`, not yet merged; `verifyPlugin` found the opt-in deprecated
  from 2025.3 and replaced by an experimental API from 2026.1; decided to set both

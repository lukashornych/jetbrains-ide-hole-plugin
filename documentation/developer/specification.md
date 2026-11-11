# Specification

A JetBrains IDE (mainly IntelliJ IDEA) plugin for managing [Hole](https://github.com/lukashornych/hole) sandboxes.
The plugin provides a convenient way to create, manage, and switch between sandboxes directly from within the IDE. It is a
more convenient alternative to launching Hole manually in the built-in Terminal tool window.

This document is the source of truth for the plugin's behaviour; every behaviour change updates it in the same change.
How the behaviour is implemented is described in [architecture](architecture.md).

Related documents:

- [Hole README](https://github.com/lukashornych/hole#readme) – the Hole CLI contract the plugin relies on, in particular
  `--instance-id`, the [machine-readable list](https://github.com/lukashornych/hole#machine-readable-list)
  (`hole list --json`), [stopping a sandbox from another program](https://github.com/lukashornych/hole#stopping-a-sandbox-from-another-program)
  and `hole modify` (all in Hole 2.6.0, the minimum version the plugin supports).

## GUI

### Hole tool window

The plugin adds a bottom tool window named **Hole**, similar to the built-in Terminal tool window, with a tabbed view.
Each tab is a single running sandbox.

**Tab title** is `agent[:profile] · <agent title>`, where *agent title* is the terminal title the agent sets through the
OSC 0/2 escape sequence (for example Claude Code's `✳ Fix EntityIndexTest`). Until the agent sets a title, or for
agents that never set one, the project folder name is shown instead (plain text). Each tab also shows:

- a status dot: amber = starting, green = running, red = stopping,
- a bug icon when the sandbox runs in debug shell mode,
- a tooltip with the instance ID and the absolute project path.

**Tab action** at the end of the tab strip: `+` (*New Sandbox or Favourite*) opens the favourites popup underneath
it with:

- *New Sandbox…* (opens the **New Sandbox** dialog),
- favourites grouped into *This project* and *All projects* (a labelled separator each), each with its agent (`agent`
  or `agent:profile`) in grey; clicking a favourite starts it (see [Starting a favourite](#starting-a-favourite)),
  **Shift+click** (or Shift+Enter) starts it in debug shell mode; the popup shows this hint,
- *Edit Favourites…* (below a separator when there are favourites).

**Panes.** The tool window can be split into panes that show sandbox tabs side by side, using the IDE's own tool
window split: *Split Right* / *Split Down* / *Unsplit* in the tab context menu (*Split* only for a pane with more than
one tab), or dragging a tab to an edge of the window or between the tabs of another pane. Panes can be nested. A pane
holds whole, already running sandboxes; moving a tab does not touch its sandbox (no stop, no restart, the terminal
keeps its session). Every pane has its own `+`. Everything in this document applies to a tab in any pane:
closing it stops the sandbox, an exited tab stays with *Exited with code N*, restart, the Docker pill and the Network
window work as in an unsplit window. A pane whose last tab closes disappears (the IDE unsplits it). A sandbox that ends while its tab is being dragged closes
its tab once the tab is dropped. The split is not
kept after an IDE restart (sandboxes are not either). In IntelliJ 2025.2 and 2025.3 the IDE registry key
`ide.allow.split.and.reorder.in.tool.window` turns the split and tab drag and drop off for all tool windows.

**Where a new sandbox opens:** in the pane whose `+` was clicked (also when the start goes through the
New Sandbox dialog, a favourite or the worktree popup opened from it); otherwise in the pane of the most recently
selected sandbox tab; otherwise in the first (left or top) pane. "Selected" means selecting a tab: clicking into the
terminal of a pane whose tab is already selected does not count.

**Selection after a close** is across panes: when a tab closes, the previously selected sandbox tab is selected again,
in whichever pane it is, and the pane that lost the tab shows its neighbour. Moving tabs between panes does not change
which tab counts as previously selected.

When no sandbox is running, the tool window shows an empty state: *No sandboxes are running*, a *New Sandbox…* link,
the grey hint *Shift+click starts in Debug Shell* and the favourites grouped into *This project* and *All projects*
(grey headers), each with its agent in grey (`claude`). Clicking a favourite starts it in agent mode (see
[Starting a favourite](#starting-a-favourite)), **Shift+click** starts it in debug shell mode (Shift must still be held
when the mouse button is released). At most **5** favourites are listed (project ones first), because a bottom tool
window is short and the empty state does not scroll; the hint takes the place of the empty line above the favourites,
so it adds no height. When there are more favourites, a *More…* link opens the favourites popup. The last line is
*Edit Favourites…*. When Hole cannot start (missing or too old), the favourites are grey text, the reason follows
them, and an empty line replaces the hint. Without favourites only *New Sandbox…* and *Edit Favourites…* are shown.
The empty state follows saved favourites at once.

### Starting a favourite

A favourite does not store a project path; where it runs is decided when it starts. The plugin lists the git worktrees
of the IDE project root (`git worktree list --porcelain`, in the background, anew for every start):

- **No linked worktree** – also when the project is not a git repository, `git` is missing or fails – the favourite
  starts at once in the IDE project root (`.`).
- **Linked worktrees** – a popup *Start '<name>' in* lists *Project root* first (selected) and, below a separator,
  every other non-bare worktree with its branch (the folder name when detached) and its absolute path in grey; the
  worktree the project root lies in is not listed again. Speed search filters the list. Choosing an entry starts the
  favourite there; **Escape** cancels the start. The popup opens underneath `+`, or in the middle of the tool window
  for the start page.

The mode is decided by the click on the favourite: a Shift+click starts the chosen location in Debug Shell (the popup
shows *Debug Shell*), and Shift in the location popup is not read. A worktree starts at its top level, also when the
IDE project is a sub-directory of the repository (no sub-directory mapping); *Project root* starts in `.`. Restart
and the Docker-in-Docker toggle keep the chosen location.

### Sandbox tab body

The tab body has a small header and, below it, the interactive terminal with the Hole sandbox output.

Hole's own interactive prompts (e.g. `Update agents now? [Y/n/s]`, project trust) are left in the terminal for the
user to answer. The plugin does not intercept them.

**`Escape`** in a sandbox terminal always goes to the program (e.g. interrupts Claude Code); it does not move the focus
to the editor as in other tool windows. The Terminal plugin's "Move focus to the editor with Escape" advanced setting
does not apply to sandbox tabs.

The **header** shows, in this order:

- the project folder name followed by its git checkout kind: `app (main)` for the main worktree, and for a linked
  worktree also its parent folder, e.g. `app-worktrees/fix (worktree)`. The absolute path is in the tooltip. The kind
  is decided once per tab by `git worktree list --porcelain` in the background; until then, and when the folder is
  not in a git repository or git is missing or fails, only the folder name is shown,
- instance ID,
- a *Debug Shell* pill for debug runs,
- the **Settings** field with two links, **Global** and **Project**: a link of a file used by this sandbox is shown
  normally, a file not used (missing, or created after the start) is dimmed with the tooltip *Not used by this
  sandbox; restart to apply after creating it*. The field's tooltip has the `hole list` labels
  (e.g. `global(~/.hole/settings.json)+project`, overrides as `project(<path>)`). A click runs
  `hole settings get global` / `hole settings get project <sandbox project path>` in the background and opens the file
  in an IDE editor; when Hole reports that it does not exist, the plugin asks *The project settings file … does not
  exist. Create it from Hole's template?* and runs `hole settings create …` (a file created meanwhile is simply
  opened). Other failures (e.g. a relative `HOLE_SETTINGS_FILE`) end in a balloon with Hole's message. A worktree
  sandbox opens its worktree's file,
- the **Network** mode: a yellow `RESTRICTED` pill by default, a red `UNRESTRICTED` pill when the sandbox was started
  with `-u` (`flags.unrestricted` of `hole list`); while the sandbox runs, clicking the pill restarts it with
  unrestricted network toggled (see *Restart* below; the favourite is not changed). The allow list itself is shown
  only in the *Network…* window,
- whether the Docker-in-Docker sidecar is enabled; while the sandbox runs, clicking the pill restarts it with
  Docker-in-Docker toggled (see *Restart* below; the favourite it was started from is not changed),
- a small toolbar at the right end with **Restart** (enabled while the sandbox runs or has exited), **Network…** and
  **Libraries…** (both enabled only while the sandbox runs).

The instance ID is known from the start (the plugin chooses it). The other fields (the project first) are rendered
lazily, with skeleton placeholders until Hole reports the instance. While the sandbox is stopping, the header shows
*Stopping for N s* (*Restarting for N s* during a restart), and after 30 seconds it offers a **Force Stop** button.
After Hole has failed, the header shows *Exited with code N* with a **Close** button, and the toolbar's **Restart**
starts it again; the Network and Docker pills stay plain labels then.

**Restart** of a running sandbox (toolbar, or the Network / Docker pill) always asks first: *Restart 'claude' in
'my-project'? The agent session ends and a new sandbox starts in this tab.* (with *with unrestricted network
enabled/disabled* or *with Docker-in-Docker enabled/disabled* for the pills), plus *N network entries added with
'hole modify' will be lost.* when `hole list` reports runtime entries. There is no *Do not ask again*. Restart of an
exited sandbox (toolbar) asks nothing: there is no agent session to lose. See [Sandbox lifecycle](#sandbox-lifecycle)
for what a restart does.

### Runtime network

**Network…** opens a non-modal window for the running sandbox, like Hole's `hole modify` TUI, titled
`Network · my-project · claude (k3x9a2)`. One window per sandbox (pressing *Network…* again brings it to the front);
it closes when the sandbox restarts, stops or its tab closes. Three tabs:

1. **Allowed** (`network.allow`): table *Entry* | *Source* (`settings`, `agent:<name>`, `runtime`) | *Saved in*
   (`global`, `project`, `global, project` or empty) with **Add…**, **Remove** and **Refresh** (see
   [Saved entries](#saved-entries)). In a sandbox started with `-u` it shows *Unrestricted network: everything is
   already allowed* and is read-only.
2. **Host gateway** (`network.hostGatewayDomains`): the same table and actions; it stays editable with `-u`, while
   the *Blocked* tab then shows nothing (nothing is blocked).
3. **Blocked** (`network.allow list-blocked`): *Name* | *Count* | *Last seen* (local time), newest first. **Allow**
   (`name:443,80`), **Allow on Ports…** (`name:`) and **Allow `*.parent`** (`*.<parent>:443,80`, only when the parent
   has at least two dots, like Hole's `WildcardFor`) open the Add input prefilled, so the entry is seen before it is
   applied; also in the context menu, double-click = Allow. After an add the list is reloaded (allowed names
   disappear).

The Add input takes one or more entries separated by spaces, with Hole's syntax hint (`example.com`,
`*.example.com:443,80`, `db.example.com:5432`). **Add** (in every tab, Allowed and Host gateway included) first opens a
popup under the button asking how long the entries stay:

1. **Only Until the Sandbox Stops** — the default (Enter); runtime entries only, as before.
2. **Also Save to Global Settings** — the runtime add, then the entries are appended to the setting's array
   (`network.allow` / `network.hostGatewayDomains`) of the global settings file.
3. **Also Save to Project Settings** — the same with the project settings file of the sandbox's project directory.
   The popup notes *Project settings: the next start asks you to trust the change*: Hole gates the `network.*` keys of
   a project file behind its trust prompt (or *Trust project settings*, `--trust-project`).

Escape closes the popup and keeps the input open. The file is saved only after Hole applied the runtime add (which
also validates the entries). Hole names the file (`hole settings get`); a missing one is created from Hole's template
(`hole settings create`) without another question. The entries go to the top-level setting, never into `profiles`;
entries already in the array are skipped, everything else in the file is kept (two-space indent, key order). The
change goes through the file's IDE document, so unsaved edits of an open editor are kept and it can be undone. The input
closes once the save is done; the list then shows the entry with source `runtime` (Hole reports it so until the
sandbox restarts) and *Saved in* `global` / `project`. When saving fails (e.g. the file is not valid
JSON, or `network` is not an object), the input closes and the window shows *Allowed in this sandbox, but not saved
to the global settings: <reason>*; the runtime entries stay.

#### Saved entries

Hole reports only that an entry comes from `settings`, not from which file. So on every load of the Allowed / Host
gateway list the plugin also asks Hole for both settings files (`hole settings get`, never creating one) and reads
their top-level setting, comparing entries in Hole's canonical form (host in lower case, ports sorted, `80,443` when
none are given: `Example.com` in a file matches the listed `example.com:80,443`). *Saved in* names the files holding
the entry; `profiles` and agent entries are not looked at. The files are read through their IDE documents, so unsaved
editor changes count; a file that is not valid JSON counts as empty.

**Remove** is enabled when every selected entry is `runtime` or saved in a settings file:

- Only runtime entries that are in no file: removed at once, no question (as before).
- Any entry in a settings file: one confirmation lists each entry and where it goes from (*this sandbox*, *the global
  settings file*, *the project settings file*). Then the runtime entries are removed (one `hole modify … remove`),
  then every matching value is removed from the top-level setting of each file. A failure shows *Not removed from the
  settings: <reason>* in the window.
- An entry in force from `settings` stays in the gateway until a restart. The restart is not asked right away, so
  several adds and removes can be made first: when the user closes the window (*Close*, Esc, the title bar) after at
  least one such removal, the usual **Restart Sandbox** confirmation appears once (the standard one, with no extra
  reason; the Remove confirmation already said that closing offers it). The window closed by the sandbox tab (stop,
  restart, exit) asks nothing. *Cancel* keeps the sandbox running: the entry stays listed until the restart with
  *Saved in* *removed — restart to apply* and, when selected, *Removed from the settings files — in force until the
  sandbox restarts* (the sandbox tab remembers these removals per instance, so a reopened window still marks them; a
  restart clears them). Selecting an entry in force from a file shows *From settings — Remove edits the settings
  file; closing this window offers a restart to apply it*.
- Entries in no file and not `runtime` cannot be removed: an agent's shows *From an agent — cannot be removed*; one
  from settings that is in neither file (a profile's, or one removed by hand while the sandbox runs) shows *From
  settings, but not in the top level of either file (a profile?) — edit the file*.

Unlike the TUI, **every confirmed add or remove is applied at once** (one `hole modify … add|remove v1 v2…` call, one
gateway apply); Hole cannot apply additions and removals together, so staging would not be atomic. The entry lists
load on open and after each change, the blocked list on open, on *Refresh* and after each add — never on a timer,
because `list-blocked` reads the gateway's whole log. All calls run in the background (30 s timeout) and disable the
actions while they run. Errors by Hole's exit code: 2 → Hole's message under the input, which stays open (e.g. a host
gateway conflict); 3 → *The sandbox is not running or still starting* (for a sandbox started by an older Hole with a
*Restart Sandbox* button); 4 → *The gateway did not apply the change; the previous allow list is still in force* with
Hole's message; anything else → Hole's message.

### Libraries

**Libraries…** opens a non-modal window for the sandbox, titled `Libraries · my-project · claude (k3x9a2)`, that
edits the top-level `libraries` of the global and project settings files (Hole cannot change the mounts of a running
sandbox, so every change applies on the next start). One window per sandbox (pressing *Libraries…* again brings it to
the front); it closes when the sandbox restarts (a new instance) or its tab closes, but it **stays open when the
sandbox exits with an error**, so a library can be fixed before it is started again.

The table *Host path* | *Mount path* | *Access* (`read-only` / `read-write`) | *Source* lists:

- `global` / `project` — the top-level `libraries` of the settings files, read through their IDE documents (unsaved
  editor changes count). Hole merges the two files per **literal** key: the same key in both is listed once as
  `project (overrides global)` with the merged value (two objects merge field by field, so a project entry without
  `readwrite` keeps a global `readwrite: true`; any other combination takes the project value). An entry that is not
  a string or an object with a `path` shows *(not a valid library value)*.
- `this start` — the `--library` values of the sandbox's start (New Sandbox or favourite; read-only here); a value
  without a mount path is mounted at its host path, like Hole does. The image paste folder is not listed.
- A host path that names the same directory as another row under a different key (`~/lib` and `/home/me/lib`,
  compared after `~` expansion, relative paths in the project directory, without `$VAR` expansion) is marked `⚠`;
  selecting it says *Same directory as … — Hole mounts only one of them*, or that the `--library` value of this start
  wins.
- Libraries of `profiles.<name>.libraries` and the git worktree links are **not** listed; a grey note under the table
  says so. A file that cannot be read (not valid JSON, `libraries` not an object) counts as empty and the window says
  why.

The first time the window opens for a sandbox instance it remembers the files' libraries (the tab keeps this
snapshot per instance, so a reopened window still compares with it; a restart takes a new one). It is not taken
while a file cannot be read, so a repaired file does not show all its libraries as changed. Rows that differ
from it are marked *restart to apply* in *Source*; libraries that left the files stay listed as *removed — restart to
apply* (still mounted until the restart). Changes made in the files before the first opening are not marked.

Actions (all run in the background; the buttons are disabled meanwhile):

- **Add…** — a dialog with the host directory (folder chooser), the mount path (prefilled with the host path, Hole's
  `--library` default; the settings file needs it written out), *Read-write* and **Save to** *Global settings (every
  project)* or *Project settings*; the project choice notes *The next start asks you to trust the project's settings
  again* (Hole gates `libraries` of a project file behind its trust prompt, and any change of them asks again). The
  mount path must be absolute, in the sandbox home (`~/…`) or start with a variable (`$HOME/…`, expanded by Hole). In the background the host path must be an existing
  directory (not checked for a path with `$`, which Hole expands from its environment first) and must not already be in the chosen file (also not under another key for the same directory); a
  failure is shown in the dialog, which stays open. A missing file is created from Hole's template
  (`hole settings create`) without another question. A read-only library is written in the shorthand
  `"~/lib": "/libs/lib"`, a read-write one as `{"path": "/libs/lib", "readwrite": true}`. The plugin does not check
  whether the global settings file would lie inside the library (Hole refuses that only for a `HOLE_SETTINGS_FILE`
  override and reports it in the tab).
- **Edit…** (or double-click) — the mount path and *Read-write* of a library of a file; a `project (overrides
  global)` row edits the project file. A shorthand stays a shorthand unless it becomes read-write; an object keeps
  its form and gets both `path` and `readwrite` written out (an explicit `false` is the only way to switch off a
  global `readwrite: true` from the project file). Saving without a change writes nothing.
- **Remove** — a confirmation names the file: *Remove ~/lib from the global settings file?* with *This affects every
  project.*, or for the project file the trust note (and *The global entry for it applies again.* for an override).
  `this start` rows and removed rows cannot be edited or removed.
- **Refresh** reloads both files.

Every change edits the file through its IDE document (undoable, unsaved editor edits kept), keeps everything else in
the file (two-space indent, key order, untouched entries in their form) and never touches `profiles`. When the user
closes the window (*Close*, Esc, the title bar) after a change, the usual **Restart Sandbox** confirmation appears
once while the sandbox runs; *Cancel* keeps it running with the rows marked. Once the sandbox has exited, no question
is asked: the window shows *The sandbox has exited: press Restart in the sandbox header to start it with the changed
libraries.* The window closed by the sandbox tab asks nothing.

*Libraries…* is enabled only while the sandbox runs until a sandbox that exited with an error can be restarted from
its header (a separate change); until then the EXITED flow above applies only to a window opened while the sandbox
was running, and the hint names a Restart that is not offered yet.

### Image paste

An agent in the sandbox cannot read the host clipboard, and the terminal pastes text only. So in a sandbox tab,
**`Cmd+V`** (the terminal's paste shortcut; on other systems its shortcuts, e.g. `Ctrl+Shift+V`, `Shift+Insert`) and
**`Ctrl+V`** on every system, pressed with an **image on the clipboard**, save the image as PNG into the sandbox's paste folder and type its
container path into the terminal as a bracketed paste (`ESC[200~/tmp/hole-idea-paste/20261027-101503-1.png ESC[201~`,
no Enter); Claude Code turns it into `[Image #1]`. Without bracketed paste mode (e.g. a debug shell) the path is typed
plain. With text on the clipboard both keys behave as before. When the clipboard holds both an image and text (e.g.
cells copied from a spreadsheet on macOS), the image wins, so screenshot tools that also put a file name there work. Only a key press in the IDE reads the clipboard;
nothing inside the sandbox can read it (writing to it is described in [Copy to the clipboard](#copy-to-the-clipboard)).

- **Paste folder**: `~/.hole/idea-paste/<instance ID>/` (the parent is private, `0700`), mounted read-only with
  `--library ~/.hole/idea-paste/<ID>:/tmp/hole-idea-paste` after the user's libraries; the command preview shows it.
  It is created before every `hole start` (also the duplicate-ID retry and a restart, which get a new folder) and
  deleted when the process has exited. Folders left behind (project closed, IDE killed) are removed when the plugin
  first needs a project's sandbox manager (tool window, New Sandbox): those not of a running instance in
  `hole list --json` and older than 10 minutes.
- **Off**: when *Image paste* is unticked, the folder path contains `:` or `$` (Hole cannot mount it), or it cannot be
  created, the sandbox starts without the mount; a paste of an image then tells the user why, once per sandbox.
- **Limits**: one clipboard image per paste, at most 10 MB as PNG; failures (too big, not readable, the sandbox
  ended) show a balloon and type nothing. Copied image files and drag and drop are not supported.
- With a global `files.exclude`, Hole warns about entries that match nothing in the paste folder at every start.

### Copy to the clipboard

Programs in the sandbox copy text to the host clipboard through **OSC 52** (`ESC ] 52 ; c ; <base64> BEL`): Claude
Code's `/copy` (in the container it has no native clipboard tool), Codex, neovim, tmux with `set-clipboard on`. The
terminal of a sandbox tab drops the sequence, so the plugin reads it from the output itself, decodes the text and puts
it into the host clipboard. The status bar of the project shows `Hole: copied N characters from <tab title>`; no
balloon, the agent confirms the copy in the terminal itself.

- **Never read**: a query (`ESC ] 52 ; c ; ?`) is ignored and gets no answer; the sandbox still cannot read the host
  clipboard.
- **Targets** (`c`, `p`, `s`, `0`–`7`, empty) are not distinguished: every write goes to the system clipboard.
- **Invalid** payloads (not base64, not UTF-8, empty) are ignored. Unlike xterm, an empty payload does not clear the
  clipboard: nothing in the sandbox can wipe it.
- **Limit**: a payload over 1 MiB of base64 (about 768 KiB of text) is dropped whole and the status bar shows
  `Hole: text from <tab title> too large to copy`. Claude Code itself writes no longer sequence.
- **Always on**, no setting: in a regular terminal with OSC 52 support the same sandbox can already write the
  clipboard, so a switch would protect against nothing; every write is visible in the status bar.
- Not guaranteed: OSC 52 wrapped in a DCS passthrough (`ESC P tmux; …`) by a multiplexer the user runs inside the
  sandbox; it is not recognised as such and may or may not be copied.

### New Sandbox dialog

The dialog always opens with **default values** (no memory of previous ad-hoc values), so an ad-hoc start is a single
click. Favourites are an explicit choice of the user; they are not listed in this dialog.

Form fields:

- **Agent** – `claude`, `gemini`, `codex` (and custom agents defined in Hole settings).
- **Profile** – none by default, or a profile defined in Hole settings (`agent:profile`).
- **Project path** – an editable combo box. Default `.`, meaning the IDE project root (the process working directory).
  Any absolute path can be typed. The dropdown suggests all git worktrees of the project (`git worktree list --porcelain`)
  and a *Browse…* item.
- **Hole settings** – links to the settings files, see [Hole settings files](#hole-settings-files).
- **Sandbox options** (checkboxes): Docker-in-Docker sidecar (`--with-docker`), unrestricted network (`-u`), trust
  project settings (`--trust-project`), log network access on exit (`-n`), rebuild images (`-r`), update agents before
  start (`--update-agents`), and **Image paste** (on by default; not a Hole flag, see [Image paste](#image-paste)).
- **Libraries** – repeatable rows of host path and `:rw` flag (`--library PATH[:rw]`).
- **Agent arguments** – free text passed after `--`.
- **Command preview** – read-only, shows both the *Start* and the *Debug* command exactly as they will be executed,
  updated live.

Buttons:

- **Save as Favourite…** – opens *Edit Favourites* with a new favourite prefilled from the current form, except for the
  project path, which a favourite does not store.
- **Cancel**.
- **Start in Debug Shell** – an alternative run, not a checkbox. It uses the same form, adds `-d`, and leaves out the
  agent arguments (Hole does not accept `-d` together with arguments after `--`).
- **Start** – starts the sandbox in a new tab.

### Edit Favourites dialog

A master–detail dialog: the list of favourites on the left (grouped *This project* / *All projects*, with add,
remove and copy actions, the agent as the tooltip of a row), the same form as New Sandbox without the **Project path**
on the right, plus:

- **Name**,
- **Stored in** – *This project* (`.idea/hole.xml`) or *All projects* (IDE application settings), with the hint
  *The project path is chosen at start: the project root, or a git worktree of it when there is one.*

The settings links and the command preview use the IDE project root (`.`), the location of a start without linked
worktrees; a start in a worktree uses that worktree instead (see [Starting a favourite](#starting-a-favourite)).
Favourites saved by older versions with a project path load without it.

### Hole settings files

Both dialogs (and the sandbox header, see above) show links to the settings files. The plugin does not compute their
paths itself; it asks Hole with
`hole settings get global` and `hole settings get project <resolved path>`, which resolve them exactly like
`hole start` (including the `HOLE_SETTINGS_FILE` / `HOLE_PROJECT_SETTINGS_FILE` overrides of the user's shell
environment). The calls run in the background, debounced, and again whenever the project path changes.

- The **global** settings file and the **project** settings file of the selected (resolved) project path: when the
  file exists, its path with *Open* and the icon below. When it does not exist, its path is shown dimmed with
  *(not created)* and a **Create** link. When Hole cannot tell (e.g. Hole is not installed, the project path is not a
  directory, a rejected override), the row is hidden.

**Open** opens the file in an IDE editor tab; an icon next to it opens the file in the system's default editor.
**Create** asks for confirmation and runs `hole settings create global` / `hole settings create project <path>` in the
background (Hole writes its template with `$schema`, so the IDE validates the JSON); the dialog stays open and re-runs
its checks, so the row switches to *Open* and agent / profile suggestions are refreshed. The plugin never passes
`--overwrite` / `--append`.
The plugin does **not** provide a GUI for the settings content in general, because Hole's settings keys change
often. Only a few common day-to-day settings get a narrow editor of their own — the network entries of the
[Network window](#runtime-network) and the [libraries](#libraries) — each limited to its top-level key; everything
else is edited in the file. The files stay Hole's: unknown keys are kept, and the plugin never validates them beyond
the key it edits.

## How it will work

### GUI

It uses existing JetBrains UI components, including the terminal widget from the bundled Terminal plugin.

### Hole communication

Based on the dialog, the plugin builds a `hole start` command line and runs `hole` **directly as the process** of the
tab's terminal (no intermediate shell), with the IDE project root as the working directory. The process ending is
therefore the signal that the sandbox is gone.

**Environment.** Hole runs with the environment of the user's login shell, the one a new terminal tab has: Hole
expands `$VAR` in its settings (`environment`, agent arguments) from its own environment, and finds `docker`/`podman`
on its `PATH`. Before **every start and restart** the plugin reads it anew by running `$SHELL -l -i -c` (with
`INTELLIJ_ENVIRONMENT_READER=true`, at most 10 s), so a changed `~/.zshrc` applies to the next start without an IDE
restart. A read taking longer than a second shows a cancellable background progress *Reading shell environment*;
cancelling it cancels the start. When the environment cannot be read (the shell fails, times out or is missing),
nothing is started and a dialog says *Could not read the environment of your shell (`<shell>`: `<reason>`). Variables
from your shell profile would be missing in the sandbox.* with **Retry** (the same start or restart again) and
**Cancel**; a restart whose read fails leaves the running sandbox alone. The other Hole and git calls (`hole list`,
`hole settings`, `hole modify`, `git worktree list`) use the most recently read environment; before the first
successful read they use the environment the IDE loaded at its start. A running sandbox keeps the environment it
was started with.

The plugin generates the 6-character instance ID (`[a-z0-9]`) itself and passes it with `--instance-id`. The header
metadata and the lifecycle status are read by polling `hole list --json --no-gc` (one unfiltered call for all
sandboxes, fanned out by instance ID; every second while starting, less often afterwards). `--no-gc` keeps each poll from running Hole's cleanup of dead sandboxes, which costs several container
engine calls; Hole still cleans up on every `hole start`. These options were added to Hole for the plugin and
released in **Hole 2.6.0** (see the [Hole README](https://github.com/lukashornych/hole#machine-readable-list)).

**Minimum Hole version: 2.6.0.** The plugin reads `hole version` (`hole 2.6.0`, `hole 2.6.0 (go install)`; development
builds count as supported). For an older Hole it shows the notice "This plugin needs Hole 2.6.0 or newer. Run
`hole update`." and disables starting sandboxes. When the version cannot be determined, starting stays possible and
Hole shows its own error in the tab. There is no fallback to parsing terminal output or the `hole list` table.

**Stopping.** Following Hole's contract for stopping a sandbox from another program, the plugin signals the **process
group** of `hole start` (`kill(-pid, …)`): `hole` runs directly as the pty process and leads its own process group, so
the group signal also stops a running image build or container start instead of waiting for it. The plugin never
signals the process tree (that would also hit Hole's teardown watchdog, which runs in its own session) and never
writes Ctrl-C (`0x03`) to the pty.

### Sandbox lifecycle

- **Sandbox ends from within** (e.g. `/exit` in the agent): Hole tears down, the `hole` process exits, the tab closes and
  the previously selected tab is selected.
- **Hole fails** (the `hole` process exits with a non-zero code without the plugin having stopped it, e.g. an unknown
  profile or Docker not running): the tab stays open so the error output can be read, the header shows
  *Exited with code N* with a **Close** button; the toolbar's **Restart** starts it again. Stops requested by the plugin (tab close, Force Stop,
  IDE exit) always close the tab, whatever the exit code (Hole exits with 130 on SIGINT).
- **Tab is closed by the user**: no confirmation. The plugin sends SIGINT to the process group of `hole start`, the
  tab shows the stopping state, and when the process exits the tab closes and the previously selected tab is selected.
  After 30 seconds the header offers *Force Stop*, which sends SIGKILL to the process group; Hole's watchdog survives
  it and finishes the teardown in the background.
- **Restart** (header toolbar, Network or Docker pill, only while running): SIGINT to the process group like a
  close; once the process has exited, `hole start` runs again **in the same tab** with a **new instance ID** (after
  Force Stop or Hole's relay timeout the old one is still taken), the same mode (agent / debug shell), the possibly
  changed config, a freshly read shell environment and a fresh probe of Hole (executable and environment may have
  changed after `hole update`). The terminal widget is replaced, so the old output is gone. Force Stop during a
  restart only kills the old process; the restart goes on. A close request during a pending restart wins and closes
  the tab. When the new process cannot be started, the tab shows *Exited with code N* and a notification tells the
  user that the sandbox has stopped and the new Hole process could not be started.
- **Restart of an exited sandbox** (header toolbar; no confirmation): there is
  no process to stop, so `hole start` runs again at once **in the same tab** with a new instance ID, the same mode and
  config, a freshly read shell environment and a fresh probe of Hole, exactly as above. While the new process is
  spawned the header shows *Restarting for N s* without *Force Stop* (there is no process to kill yet); a second
  Restart is ignored then (also a second click whose turn comes only after the sandbox runs again — it never stops it
  without the confirmation), and *Close* (closing the tab) wins: the tab closes and a process spawned meanwhile gets
  SIGINT. When the shell environment cannot be read or Hole cannot
  be used, the user is told and the tab stays exited. When the new process cannot be started, the tab stays at
  *Exited with code N* with the **old** exit code and the restart notification tells the user why. A sandbox that
  exited after a failed restart is restarted the same way.
- **IDE is closed**: the plugin sends SIGINT to the process group of every running sandbox and does **not** wait for
  shutdown, because no tab needs to be selected afterwards.

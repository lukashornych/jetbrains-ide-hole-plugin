# Project specification

We will build a JetBrains IDE (mainly IntelliJ IDEA) plugin for managing [Hole](https://github.com/lukashornych/hole) sandboxes.
The plugin provides a convenient way to create, manage, and switch between sandboxes directly from within the IDE. It is a
more convenient alternative to launching Hole manually in the built-in Terminal tool window.

Related documents:

- `documentation/design-mockup.html` – interactive design mockup (published as an artifact) with the decision log.
- `documentation/hole-assignment-instance-id-and-list-json.md` – changes required in Hole itself (implemented in
  Hole 2.6.0, the minimum version the plugin supports).

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

**Tab actions** at the end of the tab strip:

- `+` opens the **New Sandbox** dialog.
- An arrow next to `+` opens a dropdown with:
  - favourites grouped into *This project* and *All projects*; clicking a favourite starts it immediately,
    **Shift+click** (or Shift+Enter) starts it in debug shell mode; the popup shows this hint,
  - *New Sandbox…*,
  - *Edit Favourites…*.

When no sandbox is running, the tool window shows an empty state: *No sandboxes are running*, a *New Sandbox…* link
and the favourites grouped into *This project* and *All projects* (grey headers), each with its command in grey
(`claude ./worktrees/feature`). Clicking a favourite starts it in agent mode; Debug Shell stays in the dropdown (the
empty state cannot see Shift). At most **5** favourites are listed (project ones first), because a bottom tool window
is short and the empty state does not scroll; when there are more, a *More…* link opens the favourites dropdown. The
last line is *Edit Favourites…*. When Hole cannot start (missing or too old), the favourites are grey text and the
reason follows them. Without favourites only *New Sandbox…* and *Edit Favourites…* are shown. The empty state follows
saved favourites at once.

### Sandbox tab body

The tab body has a small header and, below it, the interactive terminal with the Hole sandbox output.

The **header** shows:

- instance ID,
- whether the Docker-in-Docker sidecar is enabled; while the sandbox runs, clicking the pill restarts it with
  Docker-in-Docker toggled (see *Restart* below; the favourite it was started from is not changed),
- the **Settings** field with two links, **Global** and **Project**: a link of a file used by this sandbox is shown
  normally, a file not used (missing, or created after the start) is dimmed with the tooltip *Not used by this
  sandbox; restart to apply after creating it*. The field's tooltip has the `hole list` labels
  (e.g. `global(~/.hole/settings.json)+project`, overrides as `project(<path>)`). A click runs
  `hole settings get global` / `hole settings get project <sandbox project path>` in the background and opens the file
  in an IDE editor; when Hole reports that it does not exist, the plugin asks *The project settings file … does not
  exist. Create it from Hole's template?* and runs `hole settings create …` (a file created meanwhile is simply
  opened). Other failures (e.g. a relative `HOLE_SETTINGS_FILE`) end in a balloon with Hole's message. A worktree
  sandbox opens its worktree's file,
- the absolute project path,
- a *Debug Shell* pill for debug runs,
- a small toolbar at the right end with **Restart** and **Network…**, both enabled only while the sandbox runs.

The instance ID is known from the start (the plugin chooses it). The other fields are rendered lazily, with skeleton
placeholders until Hole reports the instance. While the sandbox is stopping, the header shows *Stopping for N s*
(*Restarting for N s* during a restart), and after 30 seconds it offers a **Force Stop** button.

**Restart** (toolbar, or the Docker pill) always asks first: *Restart 'claude' in 'my-project'? The agent session ends
and a new sandbox starts in this tab.* (with *with Docker-in-Docker enabled/disabled* for the pill), plus *N network
entries added with 'hole modify' will be lost.* when `hole list` reports runtime entries. There is no *Do not ask
again*. See [Sandbox lifecycle](#sandbox-lifecycle) for what a restart does.

### Runtime network

**Network…** opens a non-modal window for the running sandbox, like Hole's `hole modify` TUI, titled
`Network · my-project · claude (k3x9a2)`. One window per sandbox (pressing *Network…* again brings it to the front);
it closes when the sandbox restarts, stops or its tab closes. Three tabs:

1. **Allowed** (`network.allow`): table *Entry* | *Source* (`settings`, `agent:<name>`, `runtime`) with **Add…**,
   **Remove** (only `runtime` entries; selecting another shows *From settings — edit the settings file and restart*)
   and **Refresh**. In a sandbox started with `-u` it shows *Unrestricted network: everything is already allowed* and
   is read-only.
2. **Host gateway** (`network.hostGatewayDomains`): the same table and actions; it stays editable with `-u`, while
   the *Blocked* tab then shows nothing (nothing is blocked).
3. **Blocked** (`network.allow list-blocked`): *Name* | *Count* | *Last seen* (local time), newest first. **Allow**
   (`name:443,80`), **Allow on Ports…** (`name:`) and **Allow `*.parent`** (`*.<parent>:443,80`, only when the parent
   has at least two dots, like Hole's `WildcardFor`) open the Add input prefilled, so the entry is seen before it is
   applied; also in the context menu, double-click = Allow. After an add the list is reloaded (allowed names
   disappear).

The Add input takes one or more entries separated by spaces, with Hole's syntax hint (`example.com`,
`*.example.com:443,80`, `db.example.com:5432`).

Unlike the TUI, **every confirmed add or remove is applied at once** (one `hole modify … add|remove v1 v2…` call, one
gateway apply); Hole cannot apply additions and removals together, so staging would not be atomic. The entry lists
load on open and after each change, the blocked list on open, on *Refresh* and after each add — never on a timer,
because `list-blocked` reads the gateway's whole log. All calls run in the background (30 s timeout) and disable the
actions while they run. Errors by Hole's exit code: 2 → Hole's message under the input, which stays open (e.g. a host
gateway conflict); 3 → *The sandbox is not running or still starting* (for a sandbox started by an older Hole with a
*Restart Sandbox* button); 4 → *The gateway did not apply the change; the previous allow list is still in force* with
Hole's message; anything else → Hole's message.

Hole's own interactive prompts (e.g. `Update agents now? [Y/n/s]`, project trust) are left in the terminal for the
user to answer. The plugin does not intercept them.

### Image paste

An agent in the sandbox cannot read the host clipboard, and the terminal pastes text only. So in a sandbox tab,
**`Cmd+V`** (the terminal's paste shortcut; on other systems its shortcuts, e.g. `Ctrl+Shift+V`, `Shift+Insert`) and
**`Ctrl+V`** on every system, pressed with an **image on the clipboard**, save the image as PNG into the sandbox's paste folder and type its
container path into the terminal as a bracketed paste (`ESC[200~/tmp/hole-idea-paste/20261027-101503-1.png ESC[201~`,
no Enter); Claude Code turns it into `[Image #1]`. Without bracketed paste mode (e.g. a debug shell) the path is typed
plain. With text on the clipboard both keys behave as before. When the clipboard holds both an image and text (e.g.
cells copied from a spreadsheet on macOS), the image wins, so screenshot tools that also put a file name there work. Only a key press in the IDE reaches the clipboard;
nothing inside the sandbox can trigger it.

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

- **Save as Favourite…** – opens *Edit Favourites* with a new favourite prefilled from the current form.
- **Cancel**.
- **Start in Debug Shell** – an alternative run, not a checkbox. It uses the same form, adds `-d`, and leaves out the
  agent arguments (Hole does not accept `-d` together with arguments after `--`).
- **Start** – starts the sandbox in a new tab.

### Edit Favourites dialog

A master–detail dialog: the list of favourites on the left (grouped *This project* / *All projects*, with add,
remove and copy actions), the same form as New Sandbox on the right, plus:

- **Name**,
- **Stored in** – *This project* (`.idea/hole.xml`) or *All projects* (IDE application settings).

Global favourites typically use `.` as the project path, so they work in any project.

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
The plugin does **not** provide a GUI for the settings content, because Hole's settings keys change often.

## How it will work

### GUI

It uses existing JetBrains UI components, including the terminal widget from the bundled Terminal plugin.

### Hole communication

Based on the dialog, the plugin builds a `hole start` command line and runs `hole` **directly as the process** of the
tab's terminal (no intermediate shell), with the IDE project root as the working directory. The process ending is
therefore the signal that the sandbox is gone.

The plugin generates the 6-character instance ID (`[a-z0-9]`) itself and passes it with `--instance-id`. The header
metadata and the lifecycle status are read by polling `hole list --json --no-gc` (one unfiltered call for all
sandboxes, fanned out by instance ID; every second while starting, less often afterwards). `--no-gc` keeps each poll from running Hole's cleanup of dead sandboxes, which costs several container
engine calls; Hole still cleans up on every `hole start`. These options are the Hole change described in
`documentation/hole-assignment-instance-id-and-list-json.md`, released in **Hole 2.6.0**.

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
  *Exited with code N* and a **Close** button. Stops requested by the plugin (tab close, Force Stop, IDE exit) always
  close the tab, whatever the exit code (Hole exits with 130 on SIGINT).
- **Tab is closed by the user**: no confirmation. The plugin sends SIGINT to the process group of `hole start`, the
  tab shows the stopping state, and when the process exits the tab closes and the previously selected tab is selected.
  After 30 seconds the header offers *Force Stop*, which sends SIGKILL to the process group; Hole's watchdog survives
  it and finishes the teardown in the background.
- **Restart** (header toolbar or Docker pill, only while running): SIGINT to the process group like a close; once
  the process has exited, `hole start` runs again **in the same tab** with a **new instance ID** (after Force Stop or
  Hole's relay timeout the old one is still taken), the same mode (agent / debug shell), the possibly changed config,
  and a fresh probe of Hole (executable and environment may have changed after `hole update`). The terminal widget is
  replaced, so the old output is gone. Force Stop during a restart only kills the old process; the restart goes on. A
  close request during a pending restart wins and closes the tab. When the new process cannot be started, the tab
  shows *Exited with code N* and a notification tells the user that the old sandbox is gone.
- **IDE is closed**: the plugin sends SIGINT to the process group of every running sandbox and does **not** wait for
  shutdown, because no tab needs to be selected afterwards.

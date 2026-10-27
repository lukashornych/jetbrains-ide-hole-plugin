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

When no sandbox is running, the tool window shows an empty state with a *New Sandbox…* link.

### Sandbox tab body

The tab body has a small header and, below it, the interactive terminal with the Hole sandbox output.

The **header** shows:

- instance ID,
- whether the Docker-in-Docker sidecar is enabled,
- which settings files are used (same labels as `hole list`, e.g. `global(~/.hole/settings.json)+project`),
- the absolute project path,
- uptime,
- a *Debug Shell* pill for debug runs.

The instance ID is known from the start (the plugin chooses it). The other fields are rendered lazily, with skeleton
placeholders until Hole reports the instance. While the sandbox is stopping, the header shows *Stopping for N s*, and
after 30 seconds it offers a **Force Stop** button.

Hole's own interactive prompts (e.g. `Update agents now? [Y/n/s]`, project trust) are left in the terminal for the
user to answer. The plugin does not intercept them.

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
  start (`--update-agents`).
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

Both dialogs show links to the settings files. The plugin does not compute their paths itself; it asks Hole with
`hole settings get global` and `hole settings get project <resolved path>`, which resolve them exactly like
`hole start` (including the `HOLE_SETTINGS_FILE` / `HOLE_PROJECT_SETTINGS_FILE` overrides of the user's shell
environment). The calls run in the background, debounced, and again whenever the project path changes.

- The **global** settings file: when it exists, its path with the actions below. When it does not exist, its path is
  shown dimmed with *(not created)* and without actions; the plugin never creates it. When Hole cannot tell (e.g.
  Hole is not installed), the row is hidden.
- The **project** settings file of the selected project path: shown only if the file exists.

**Open** opens the file in an IDE editor tab; an icon next to it opens the file in the system's default editor.
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
sandboxes, fanned out by instance ID; every second while starting, less often afterwards; uptime is computed from
`startedAt`). `--no-gc` keeps each poll from running Hole's cleanup of dead sandboxes, which costs several container
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
- **IDE is closed**: the plugin sends SIGINT to the process group of every running sandbox and does **not** wait for
  shutdown, because no tab needs to be selected afterwards.

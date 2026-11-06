# Architecture

This page explains how the plugin is put together. *What* it does — every window, dialog and lifecycle rule — is in
the [specification](specification.md); this page links to it instead of repeating it. Class-level details live in
the Javadoc of the classes named here.

All packages below are under `io.github.lukashornych.hole.idea`.

## Principles

- **Hole is the only source of truth about sandboxes.** The plugin never reads Hole's internal files
  (`~/.hole/instances/…`), never computes settings file paths itself and never parses the human-readable output of
  `hole start` or `hole list`. Everything it knows comes from Hole's machine-readable CLI contract: `hole version`,
  `hole list --json`, `hole settings get|create` and `hole modify … --json`, all introduced or stabilised in
  **Hole 2.6.0**, the minimum supported version. The one place the plugin reads and writes settings file content
  itself is the Network window's saved entries (Hole has no CLI for it): Hole still names the files, and only the
  top-level array of the two `hole modify` settings is read, extended or reduced — see [the ADR](../adr/2026-11-05-network-entries-saved-to-settings-by-the-plugin.md).
- **No intermediate shell.** Every process — `hole start` included — is started from an argument list, never from a
  shell string. `hole start` runs directly as the process of the tab's pty, so its exit is the signal that the
  sandbox is gone. The only shell the plugin starts is the environment reader below, and its output never reaches a
  parser or a tab.
- **The user's environment, not the IDE's.** Processes get the environment of the user's login-interactive shell,
  read by `process.ShellEnvironment` (`$SHELL -l -i -c '/usr/bin/env -0 > <temp dir>/env'`), and executables are
  resolved on its `PATH` by `process.ExecutableLocator`, because an IDE started from the macOS Dock has a different
  `PATH` than the user's shell, and Hole expands `$VAR` in settings from its own environment. Every start and restart
  reads it again (like a new terminal tab re-sources the rc files); a failed read starts nothing and asks *Retry /
  Cancel* instead of falling back. The short-lived CLI calls use the most recent successful read
  (`ShellEnvironment.latest()`), or the platform's snapshot when none has succeeded yet.
  - Not `EnvironmentUtil.getEnvironmentMap()`: the platform skips loading it when the IDE looks launched from a
    terminal (`SHLVL > 0`), falls back to the IDE's environment silently on a timeout or failing shell, and never
    refreshes it after IDE start.
  - Not running every command through the shell: rc-file output on stdout would corrupt the parsed CLI output, the
    poller would spawn a login shell every second, and for `hole start` the interactive shell would run in the tab's
    pty (tmux auto-attach, prompts) where a hanging rc file is indistinguishable from a hanging Hole.
  - The environment goes to a file the shell creates in an owner-only temporary directory (a pre-created file would
    fail under `noclobber`), so rc files may print anything; the shell gets
    `INTELLIJ_ENVIRONMENT_READER=true`, the platform's marker that rc files test to skip interactive things.
    `_`, `PWD`, `OLDPWD`, `SHLVL` and the marker are dropped. `csh`/`tcsh` get no `-l` (they reject it with `-c`).
    Not read on Windows (no tool window there).
- **Logic free of Swing.** State machines, models and texts are plain Java, so they are tested without a UI (see
  [testability](#testability)).

## Packages

| Package | Responsibility |
|---------|----------------|
| `process` | `CliRunner` runs short-lived, non-interactive commands with captured output and a timeout; `ExecutableLocator` resolves executables on the user's `PATH`; `ShellEnvironment` reads the user's shell environment. `CliRunner` is not used for `hole start`. |
| `hole` | One client per Hole command (`HoleListClient`, `HoleSettingsClient`, `HoleModifyClient`) with its parser; `HoleExecutable` locates and probes `hole`; `HoleCommandBuilder` builds every argument list; `InstanceIdGenerator`; `MetadataPoller`. |
| `session` | The lifecycle of one sandbox (`SandboxSession`) and of all sandboxes of a project (`SandboxSessionManager`); the pty (`HoleTerminalRunner`, `HoleTtyConnector`); process-group signals (`ProcessSignaller`); image paste folders (`PasteDirectory`, `PasteStore`). |
| `storage` | `PersistentStateComponent` services: plugin settings and favourites of both scopes. Null handling of persisted state lives here. |
| `ui` | Tool window, sandbox tabs and their header, New Sandbox / Edit Favourites dialogs with the shared `SandboxForm`, favourite starts (`FavouriteStarter`: project root or a git worktree from `StartLocations`), settings page, notifications, terminal path links and image paste key handling. |
| `ui/network` | The runtime Network window over `hole modify`; `SettingsEntrySaver` reads and changes its entries in the settings files (`hole.SettingsEntries` edits the JSON). |
| `model`, `git` | Immutable value records (`SandboxConfig`, `Favourite`, `InstanceInfo`, …) and `git worktree list --porcelain` parsing, incl. whether a directory lies in a linked worktree (shown in the sandbox header). |

Dependencies point downwards: `ui` → `session` → `hole` → `process`; `model` is used everywhere and depends on
nothing. `session` holds no Swing code — it talks to the tool window through the `SessionView` and
`StartFailureNotifier` interfaces (the IDE implementations, `HoleToolWindowView` and `HoleNotifications`, live in
`ui`).

Services are light services (`@Service`), obtained through their static `getInstance()` methods:
`HoleExecutable`, `ShellEnvironment`, `MetadataPoller`, `HolePluginSettings` and `GlobalFavouritesService` at application level,
`SandboxSessionManager` and `ProjectFavouritesService` at project level. `plugin.xml` registers only the tool
window, the notification group and the settings page.

## Locating and probing Hole

`HoleExecutable` finds `hole` in this order: the path from *Settings › Tools › Hole* (used exclusively when set, so
a typo is reported instead of silently using another binary), `hole` on the user's `PATH`, `~/.local/bin/hole`
(Hole's install location). It then runs `hole version` once and parses it with `HoleVersion` into a
`HoleCapability`: supported, too old (starting is disabled with a notice to run `hole update`), missing, or unknown
(starting stays possible and Hole reports its own error in the tab). Every start and restart asks for the
capability; a positive result is cached per executable path and modification time, so `hole update` or a changed
setting triggers a new probe, and negative results are not cached at all.

## Sandbox session

`SandboxSession` owns one `hole start` process, its lifecycle state, the metadata Hole reports and the title the
agent sets. Its states (`SessionState`):

```
STARTING --(Hole reports running)--> RUNNING
STARTING|RUNNING --(Hole reports stopping, or the plugin sends SIGINT)--> STOPPING
process exit: stop requested by the plugin, or exit code 0 --> CLOSED  (the tab is removed)
              otherwise                                     --> EXITED  (the tab stays: "Exited with code N")
EXITED --(Close)--> CLOSED
RUNNING --(restart: SIGINT)--> STOPPING --(process exit, new process)--> STARTING  (same tab, new instance ID)
EXITED  --(restart)--> STOPPING ("Restarting", while spawning) --> STARTING        (same tab, new instance ID)
                       spawn failure --> EXITED (old exit code); close request --> CLOSED
```

Whether a tab closes depends only on *who asked for the stop* and on the exit code — never on Hole's `stopping`
status, which can be missed between two polls.

- **Instance ID.** The plugin generates the 6-character ID itself and passes it with `--instance-id`, so the tab
  knows it before Hole reports anything. When Hole rejects the ID (in use, or leftovers of an abandoned sandbox), the
  session starts once more with a new ID in the same tab. `HoleTtyConnector` sees what the terminal reads only to
  recognise that one error.
- **Restart** replaces a running sandbox in the same tab: SIGINT like a close, and when the process has exited a new
  `hole start` with a new instance ID and a fresh probe. The terminal widget is replaced. An EXITED sandbox is
  restarted by `SandboxSession.relaunch`: it switches to STOPPING with a pending restart **under the same lock that
  checks EXITED**, then runs the second half of a restart (`restartExited`). Leaving it EXITED during the spawn would
  break two things: a Close in EXITED goes straight to CLOSED without `closeRequested`, so the spawned process would
  revive a closed tab; and a second Restart would spawn a second `hole start` and orphan one of them.
- **Thread safety.** `SandboxSession` is thread-safe; its listeners are called on whichever thread caused the change
  (process reaper, poller, terminal emulator) and must switch to the EDT themselves.

`SandboxSessionManager` owns the sessions of one project: it starts them (probe and spawn on a background thread),
keeps the tab selection history (`TabHistory`) so that closing a tab selects the previously selected one, and stops
every sandbox when the project closes (SIGINT, without waiting).

### Stopping: signals to the process group

Hole's contract for stopping a sandbox from another program
([Hole README, "Stopping a sandbox from another program"](https://github.com/lukashornych/hole#stopping-a-sandbox-from-another-program))
is a signal to the **process group** of `hole start`, as a terminal's Ctrl-C does. pty4j starts `hole` as the
leader of a new session and process group, so `ProcessSignaller` sends `kill(-pid, SIGINT)`. Signalling only the
`hole` process would also tear the sandbox down, but during an image build or a container start it would wait for
that step to finish, which can take minutes.

The plugin never signals the process *tree* (that would also hit Hole's teardown watchdog, which runs in its own
session) and never writes Ctrl-C (`0x03`) to the pty. Force Stop, offered after 30 seconds, sends SIGKILL to the
group; Hole's watchdog survives it and finishes the teardown in the background.

### Terminal

`SandboxTabPanel` attaches a plain `JBTerminalWidget` to the already running process through `HoleTtyConnector`
(a jediterm `ProcessTtyConnector`). Two platform choices matter here:

- not the Terminal plugin's `ShellTerminalWidget`, whose shell-command tracking can consume Enter and refreshes the
  VFS after every Enter;
- not IntelliJ's `PtyProcessTtyConnector`, whose constructor changed between IDE versions, so a plugin compiled
  against one does not run on the other.

The agent's OSC 0/2 title is read through jediterm's title listener. File paths in the output are linked by
`SandboxPathFilter`, because the Terminal plugin hands its own path filter only to its reworked terminal.
`ImagePasteHandler` is installed as a pre-key handler of the terminal panel and turns a paste key with an image on
the clipboard into a PNG in the sandbox's paste folder plus a bracketed paste of its container path (details in the
[specification](specification.md#image-paste)). `EscapeKeyHandler`, another pre-key handler, writes a plain `Escape`
to the pty and consumes it: otherwise the platform's internal `TerminalEscapeKeyListener`, which runs after the
pre-key handlers, moves the focus to the editor in every tool window but Terminal while the program draws in the main
screen buffer (as Claude Code does).

## Metadata polling

`MetadataPoller` is **one loop for all sessions of all projects**: each round runs one unfiltered
`hole list --json --no-gc` and fans the result out by instance ID, because every `hole list` asks the container
engine. It polls every second while any subscribed instance has not been seen yet or is `starting`, every
10 seconds otherwise, and only while there are subscribers. `--no-gc` keeps the polls from running Hole's cleanup of
dead sandboxes; Hole still cleans up on every `hole start`.

`HoleListParser` and `HoleModifyParser` read leniently: unknown fields are ignored and missing ones get defaults, so a
newer Hole does not break the plugin.

## Persistence

| What | Where | Service |
|------|-------|---------|
| Plugin settings (path of `hole`) | `options/hole.xml` in the IDE configuration, not roamed by Settings Sync (machine-specific) | `HolePluginSettings` |
| Favourites of *All projects* | `options/holeFavourites.xml` in the IDE configuration | `GlobalFavouritesService` |
| Favourites of *This project* | `.idea/hole.xml`, shareable through version control | `ProjectFavouritesService` |

Persisted beans (`FavouriteBean`, `FavouritesState`) are mutable with no-arg constructors, as the XML serializer
requires, and convert to and from the immutable `model` records. A favourite has no project path (`Favourite`
normalises its config to `.`); the `projectPath` option written by older versions is ignored on load. Saving
favourites publishes `FavouritesListener` on the application message bus, because favourites of *All projects* change
the empty state of every open project.

## Threading

- Everything that runs a process or touches the file system runs off the EDT: `executeOnPooledThread`, or a
  pooled-thread `Alarm` for debounced form checks (`SandboxForm`). Results are applied on the EDT with
  `invokeLater` (with `ModalityState.any()` where a modal dialog is open).
- The CLI clients are blocking by design and say so in their Javadoc ("call off the EDT").
- `ShellEnvironment.read()` blocks for the shell (at most 10 s; concurrent callers share one run), and `latest()`
  blocks until the first read ended, so `CliRunner.SHELL_ENVIRONMENT` must not be read on the EDT either. A start's
  read slower than 1 s shows a cancellable background progress (`readShowingProgress`).
- View calls of `SandboxSessionManager` and its session list are confined to its UI executor (the EDT in the IDE).

## Testability

The UI is split into a Swing-free model and a thin Swing part, so the logic is tested with plain JUnit:

| Model (tested without UI) | Swing part |
|---------------------------|------------|
| `SandboxSession`, `SessionState` | `SandboxTabPanel` |
| `HeaderModel` | `SandboxHeaderPanel` |
| `EmptyStateModel` | `HoleEmptyState` |
| `SessionPresentation` (tab texts) | `HoleToolWindowView` |
| `SandboxFormValidator`, `FormChecks` | `SandboxForm` |
| `StartLocations` | `FavouriteStarter` |
| `SettingsFileResolver` (question as a callback) | `SettingsFileOpener`, `SettingsFileLinks` |
| `BlockedSuggestions` | `NetworkDialog`, `EntryInputDialog` |
| `SettingsEntries` (settings JSON edit) | `SettingsEntrySaver` (IDE document) |

`SessionServices` bundles what a session needs from outside (pty runner, signaller, metadata source, ID generator,
clock), so tests pass the real implementations running against the fake `hole` script. See
[development](development.md#tests).

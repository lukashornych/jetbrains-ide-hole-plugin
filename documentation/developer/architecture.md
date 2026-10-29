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
  **Hole 2.6.0**, the minimum supported version.
- **No intermediate shell.** Every process — `hole start` included — is started from an argument list, never from a
  shell string. `hole start` runs directly as the process of the tab's pty, so its exit is the signal that the
  sandbox is gone.
- **The user's environment, not the IDE's.** Processes get the user's shell environment
  (`EnvironmentUtil.getEnvironmentMap()`), and executables are resolved on its `PATH` by `process.ExecutableLocator`,
  because an IDE started from the macOS Dock has a different `PATH` than the user's shell.
- **Logic free of Swing.** State machines, models and texts are plain Java, so they are tested without a UI (see
  [testability](#testability)).

## Packages

| Package | Responsibility |
|---------|----------------|
| `process` | `CliRunner` runs short-lived, non-interactive commands with captured output and a timeout; `ExecutableLocator` resolves executables on the user's `PATH`. Not used for `hole start`. |
| `hole` | One client per Hole command (`HoleListClient`, `HoleSettingsClient`, `HoleModifyClient`) with its parser; `HoleExecutable` locates and probes `hole`; `HoleCommandBuilder` builds every argument list; `InstanceIdGenerator`; `MetadataPoller`. |
| `session` | The lifecycle of one sandbox (`SandboxSession`) and of all sandboxes of a project (`SandboxSessionManager`); the pty (`HoleTerminalRunner`, `HoleTtyConnector`); process-group signals (`ProcessSignaller`); image paste folders (`PasteDirectory`, `PasteStore`). |
| `storage` | `PersistentStateComponent` services: plugin settings and favourites of both scopes. Null handling of persisted state lives here. |
| `ui` | Tool window, sandbox tabs and their header, New Sandbox / Edit Favourites dialogs with the shared `SandboxForm`, favourite starts (`FavouriteStarter`: project root or a git worktree from `StartLocations`), settings page, notifications, terminal path links and image paste key handling. |
| `ui/network` | The runtime Network window over `hole modify`. |
| `model`, `git` | Immutable value records (`SandboxConfig`, `Favourite`, `InstanceInfo`, …) and `git worktree list --porcelain` parsing. |

Dependencies point downwards: `ui` → `session` → `hole` → `process`; `model` is used everywhere and depends on
nothing. `session` holds no Swing code — it talks to the tool window through the `SessionView` and
`StartFailureNotifier` interfaces (the IDE implementations, `HoleToolWindowView` and `HoleNotifications`, live in
`ui`).

Services are light services (`@Service`), obtained through their static `getInstance()` methods:
`HoleExecutable`, `MetadataPoller`, `HolePluginSettings` and `GlobalFavouritesService` at application level,
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
```

Whether a tab closes depends only on *who asked for the stop* and on the exit code — never on Hole's `stopping`
status, which can be missed between two polls.

- **Instance ID.** The plugin generates the 6-character ID itself and passes it with `--instance-id`, so the tab
  knows it before Hole reports anything. When Hole rejects the ID (in use, or leftovers of an abandoned sandbox), the
  session starts once more with a new ID in the same tab. `HoleTtyConnector` sees what the terminal reads only to
  recognise that one error.
- **Restart** replaces a running sandbox in the same tab: SIGINT like a close, and when the process has exited a new
  `hole start` with a new instance ID and a fresh probe. The terminal widget is replaced.
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

`SessionServices` bundles what a session needs from outside (pty runner, signaller, metadata source, ID generator,
clock), so tests pass the real implementations running against the fake `hole` script. See
[development](development.md#tests).

---
title: The plugin reads the user's login-shell environment itself before every start, and does not start without it
date: 2026-11-04
updated: 2026-11-04 15:30
status: proposed
kind: fix
issues: []
prs: []
areas: [src/main/java/io/github/lukashornych/hole/idea/process, src/main/java/io/github/lukashornych/hole/idea/session, src/main/java/io/github/lukashornych/hole/idea/ui, src/test/resources/fake-shell]
supersedes: []
superseded-by: []
relates: []
---

# The plugin reads the user's login-shell environment itself before every start, and does not start without it

`process.ShellEnvironment` runs `$SHELL -l -i -c '/usr/bin/env -0 > <temp dir>/env'` and parses the file. Every start
and restart reads it fresh and spawns `hole start` with it directly (no shell in between); when the read fails,
nothing starts and a *Retry / Cancel* dialog appears. The short-lived CLI calls (`hole list`, `settings`, `modify`,
`git worktree list`) use the most recent successful read, falling back to the platform snapshot only before the
first success.

## Why

A variable from `~/.zshrc` (`PROJECT_PATH`) referenced in Hole settings as `"$PROJECT_PATH"` reached the sandbox
literally; Hole printed `undefined environment variable '$PROJECT_PATH', leaving unexpanded`. Hole expands
`environment` values and agent arguments from **its own process environment** (`hostenv.ExpandEnvVars` →
`os.LookupEnv`) and leaves undefined variables as written, so whatever environment the plugin spawns Hole with is
what the user's settings see.

### Previous state

Every process got `CliRunner.SHELL_ENVIRONMENT` = `EnvironmentUtil.getEnvironmentMap()`, a snapshot the platform
takes once at IDE start. Measured on the affected setup (macOS, zsh, IDE launched through a Toolbox shortcut): the
map had `SHLVL=2`, 32 entries, nothing from `~/.zshrc`; `zsh -l -i -c 'echo $PROJECT_PATH'` printed the value in
0.59 s with exit code 0; `idea.log` had no shell-env lines. The most likely cause is the platform's
launched-from-a-terminal heuristic (`SHLVL > 0` → skip); a timeout or a failing shell also falls back to the IDE's
environment silently (not verified against platform sources). The built-in IDE terminal starts its own interactive
shell, so checking there hides the problem. The same gap affected the `PATH` lookup of `hole`/`git`, `HOME`, and
`HOLE_SETTINGS_FILE` / `HOLE_PROJECT_SETTINGS_FILE`.

## Options considered

### Option A — run every command through the user's shell (declined)

`$SHELL -l -i -c 'exec "$0" "$@"' hole …` for every process.

- **Pros:** always exactly the terminal's environment, including `umask`/`ulimit` set in rc files; no temp file or
  parsing.
- **Cons / Rejected because:**
  - CLI calls parse stdout (`hole list --json`, `hole modify --json`, `git worktree list --porcelain`); anything an rc
    file prints (motd, fortune, p10k instant prompt, `nvm use`) corrupts it.
  - `MetadataPoller` runs `hole list` every second while a sandbox starts and every 10 s otherwise; a
    login-interactive shell (0.6 s of CPU measured) per poll is a permanent, pointless load.
  - For `hole start` the interactive shell would run **in the tab's pty**: rc files see a real terminal, so
    tmux/zellij auto-attach takes over the tab, prompts and `stty` run, and their output lands in the tab.
  - A hanging or failing rc file is indistinguishable from Hole hanging or failing, so the *Retry / Cancel* dialog
    cannot be built.
  - The CLI calls would still need Option B — two mechanisms instead of one.
- **Revisit if** something besides environment variables from rc files (e.g. `umask`) must reach Hole.

### Option B — read the environment in the plugin, start Hole directly (chosen)

- **Pros:** one mechanism for all processes; rc output never reaches the tab or a parser (it goes to stdout, the
  environment to a file); a failed read is detected and reported before anything starts.
- **Cons:** one login shell per start (≈0.6 s measured); `umask`/`ulimit` from rc files do not reach Hole.

### Option C — the platform's own reader (declined)

- **Rejected because:** the target IDE has no accessible `EnvironmentUtil.ShellEnvReader` (IDE Scripting Console:
  `MissingPropertyException: No such property: ShellEnvReader`), and `EnvironmentUtil` offers only the frozen
  snapshot. **Revisit if** the platform publishes a non-internal API to read a shell environment on demand.

### Option D — fall back silently to `EnvironmentUtil` (status quo, declined)

- **Rejected because:** that is exactly how this bug stayed invisible; a sandbox without the user's variables is
  broken anyway.

## Decision

**Chosen: Option B**, with two decisions taken with the developer:

- **Freshness:** read before **every start and restart**, like a new terminal tab re-sources the rc files. No
  manual reload action.
- **Failure is not silent for starts:** a failed read stops the start with *Retry / Cancel*. There is no *Start
  anyway*; easy to add if users ask. Background CLI calls cannot show a dialog, so before the first success they
  fall back to the platform snapshot (logged once): opening the tool window reads the environment before the first
  probe, `hole modify` needs a running sandbox (which had a good read), and the only pre-success writer is
  `hole settings create` from the New Sandbox form.

## Key technical details

- `process/ShellEnvironment.java` — `read()` always runs the shell; concurrent callers share one in-flight run.
  `readShowingProgress(project)` (used for starts) shows a cancellable *Reading shell environment* progress after
  1 s. `latest()` returns the last success; before one it waits for a read, and after a failed read it does **not**
  run the shell again (only starts do), so a broken rc file does not cost a shell — and up to 10 s — per poll.
- Command: `-l -i -c` (`csh`/`tcsh` without `-l`, which they reject with `-c`), `/usr/bin/env -0` into a file the
  shell creates in an owner-only temp directory, deleted in `finally`. The plugin must not pre-create the file:
  `>` onto an existing file fails under `noclobber` (common in dotfiles), which would block every start; `>|` is
  not portable (csh `>!`, fish has none). Success = exit code 0 of the command (that of `env`, not of the last rc line)
  and a non-empty parse. Timeout 10 s, then SIGTERM and SIGKILL after 2 s (`CliRunner`).
- The shell gets `INTELLIJ_ENVIRONMENT_READER=true`, the platform's marker that rc files already test to skip tmux
  auto-attach and other things that would hang without a terminal. `_`, `PWD`, `OLDPWD`, `SHLVL` and the marker are
  dropped from the result.
- `session/SandboxSessionManager` reads **before** the Hole probe: the probe's `locate()` uses `latest()`, so it
  finds `hole` on the freshly read `PATH` (Homebrew's `/opt/homebrew/bin` is often only in `.zprofile`), and a
  failed read is not misreported as "Hole was not found on PATH". Both happen before a session exists or the old
  process is stopped, so a failed read on a restart leaves the running sandbox untouched.
- `ShellEnvironmentException` carries the shell and a reason (exit code, timeout, not found) — never values or rc
  output; a failed run is deliberately not wrapped in `CliException(result, …)`, which would quote stderr.
- A session keeps the environment of its `Launch`; the tab's own CLI calls (settings links, Network window) use
  `latest()`, so after a later start they may see a newer environment than the sandbox runs with.
- `SHELL` missing from the IDE's environment means `/bin/sh -l -i`, which does not source zsh/bash rc files; logged
  at INFO.
- `CliRunner.SHELL_ENVIRONMENT` now blocks until the first read ended: never read it on the EDT.
- Not read on Windows (no tool window there); the snapshot is returned instead.

## Verification

- `ShellEnvironmentTest` (fake shell `src/test/resources/fake-shell/shell`): rc output on stdout/stderr; `noclobber`
  (`set -C`); `latest()` joining a start's read after an earlier failure; values
  with `=` and newlines; `-l -i -c` and the marker; non-zero exit, missing shell and a hang (500 ms timeout, the shell
  process is gone afterwards) with the reason and no rc output in the message; temp file removed; concurrent reads
  share one run, sequential reads run again; `latest()` keeps the last success and falls back without one.
- `SandboxSessionManagerTest`, every start through the fake shell with `SHLVL=2`: the regression (fake `hole start`
  with `FAKE_HOLE_PRINT_ENV=PROJECT_PATH` prints the value from the rc file), a changed rc file between two starts,
  a failed read (no probe, no start, dialog callback, *Retry* starts), a failed read on restart (old sandbox keeps
  running), `hole` only on the rc file's `PATH` (pins read-before-probe), and a cancelled read (nothing started, no dialog).
- Before this record becomes `accepted` (and is renamed to the merge date), the manual check on macOS, with IDE,
  Hole and macOS versions quoted here: `/usr/bin/env -0` of BSD `env`; `./gradlew runIde` launched with `SHLVL=2` and the
  variable only in `~/.zshrc`; editing `~/.zshrc` between starts; an rc file with `sleep 30` behind
  `[ -z "$INTELLIJ_ENVIRONMENT_READER" ] ||` shows the dialog after 10 s.

## Consequences & open follow-ups

- Every start costs one login-interactive shell (≈0.6 s measured with zsh); the start shows nothing during the first
  second, then the progress.
- Cancelling the progress only stops waiting; the shell still runs until it ends or its 10 s timeout (plus the 2 s
  SIGTERM grace of `CliRunner`, so a hung read blocks a start for up to 12 s).
- On timeout only the shell is signalled: a hung *child* of an rc file (`sleep`, tmux) survives and keeps running.
  Killing `process.descendants()` would need a change to `CliRunner`, which also runs `hole list`; deferred.
- `readShowingProgress` (the progress and its cancel) has no automated test; it needs a platform test.
- After a failed read, `latest()` does not retry until the next start, so polling and forms keep the IDE snapshot
  even after the user fixed the rc file.
- An rc file that behaves differently with `INTELLIJ_ENVIRONMENT_READER` set, or that needs a terminal, may export
  less than a terminal tab has; the README troubleshooting entry explains the marker.
- `umask`/`ulimit` from rc files do not reach Hole (see Option A's revisit trigger).
- BSD `env -0` on macOS is not covered by CI (Linux); if a macOS version lacks `-0`, every read fails with
  *exited with code 1* and starts are blocked — the first thing to check on such a report.

## Timeline

- **2026-11-04** — implemented

---
title: The plugin interprets OSC 52 clipboard writes from the sandbox output itself, always on, never answering reads
date: 2026-11-11
updated: 2026-11-11 16:00
status: accepted
kind: feature
issues: []
prs: []
areas: [src/main/java/io/github/lukashornych/hole/idea/session, src/test/resources/fake-hole]
supersedes: []
superseded-by: []
relates: []
---

# The plugin interprets OSC 52 clipboard writes from the sandbox output itself, always on, never answering reads

`session/Osc52Parser` watches the pty output of every `hole start` process (the output tap of `HoleTtyConnector`)
for OSC 52 *set* sequences, decodes the text and hands it to `ClipboardSink`, which puts it into the host clipboard
through `CopyPasteManager` and shows `Hole: copied N characters from <tab title>` in the status bar. Queries are
never answered, there is no setting, and payloads over 1 MiB of base64 are dropped.

## Why

Claude Code's `/copy` did nothing in a plugin tab. In the container Claude Code has no native clipboard tool; its
debug log shows `clipboard: setClipboard mux=none ssh=false native=false predicted=osc52 emit=raw`, so it writes
`ESC ] 52 ; c ; <base64> BEL`. `hole start` passes the sequence through `docker attach` unchanged (the same sandbox
in Ghostty copies fine), but jediterm's `JediEmulator.doProcessOsc` has no case for 52 and drops it. The same gap hits
every OSC 52 program (Codex, neovim, tmux with `set-clipboard on`).

### Previous state

The sequence reached jediterm and vanished: nothing in the sandbox could write the host clipboard, which the
specification presented as isolation ("nothing inside the sandbox can trigger it") although it was an accident of
the terminal. The only way out was mouse selection plus the IDE copy shortcut, which cannot select an answer longer
than the screen.

## Options considered

### Option A — the plugin parses OSC 52 from the pty output (chosen)

The plugin owns `HoleTtyConnector` and its output tap, so it sees every character jediterm sees.

- **Pros:** public API only; no change in Hole or the image; works for every OSC 52 program.
- **Cons:** a hand-written streaming parser the plugin must maintain; duplicated once jediterm supports OSC 52.

### Option B — OSC 1341 custom command (`TerminalCustomCommandListener`)

- **Pros:** jediterm parses the sequence; the plugin only implements a listener.
- **Cons:** needs a wrapper in the container that translates.
- **Rejected because:** Claude Code does not emit OSC 1341, so it needs a container-side wrapper on top of the plugin
  listener — more moving parts than A for the same result.

### Option C — container-side shim (`xclip`/`wl-copy` + `DISPLAY`) with Hole hooks

`setup`/`prestart` in the container, `setupHost`/`cleanupHost` running a host listener over a shared mount.

- **Pros:** independent of the terminal.
- **Cons:** per-user configuration outside the plugin, a host process per sandbox, a shared mount.
- **Rejected because:** none of it is the plugin's to ship, and setting `DISPLAY` changes how other tools in the
  container behave. Revisit if Hole ships a clipboard bridge of its own; the plugin would then disable A.

### Option D — select with the mouse, copy with the IDE shortcut

- **Rejected because:** cannot select an answer longer than the screen. Stays as the fallback.

### Option E — wait for OSC 52 in jediterm upstream

- **Rejected because:** not in jediterm `master`; the plugin cannot depend on its timing. Whether the reworked
  platform terminal supports OSC 52 was not checked; the plugin does not use it.

### Gating — a setting to turn writes on or off (declined)

Some terminals gate OSC 52 (e.g. iTerm2).

- **Rejected because:** opt-in leaves `/copy` silently broken for everyone who does not find it; opt-out protects
  against nothing a regular terminal does not already allow — `hole start` passes the output unchanged, so in any
  OSC 52 terminal anything printed in the sandbox (e.g. `cat` of a crafted file) can already replace the clipboard,
  and Claude Code outside Hole writes it with `pbcopy`. Revisit if the plugin ever answers clipboard reads, or Hole's
  threat model starts treating clipboard writes as an escape.

## Decision

**Chosen: Option A, always on.** It is the only option that works with what Claude Code actually emits, needs nothing
outside the plugin and uses public API only. The isolation that matters stays: the sandbox never *reads* the host
clipboard (queries get no answer), cannot clear it (an empty payload is ignored, unlike xterm), and every write is
visible in the status bar.

## Key technical details

- `session/Osc52Parser` — streaming state machine; survives a sequence split at any point between reads; skips other
  OSCs (titles, hyperlinks) without buffering; never buffers more than the limit (1 MiB of base64; Claude Code itself
  writes at most 1 048 576 characters, so the limit never cuts a real `/copy`). ESC followed by anything but `\`, and
  CAN/SUB, abort a sequence like in xterm.
- **Strict base64**: CR/LF/space/tab are stripped, then `Base64.getDecoder()`; `getMimeDecoder()` was not used
  because it silently skips any non-alphabet character and turns junk into garbage text. Invalid UTF-8 is rejected too.
- One parser per `SandboxSession` attempt (one `hole start` process): a retry or restart gets a fresh one, so half a
  sequence of the old process cannot complete in the new one.
- `ClipboardSink` in `SessionServices`; the platform one runs on the EDT with `ModalityState.any()` so an open modal
  dialog does not hold the copy back. The sink is called from jediterm's reader thread; its exceptions are caught so
  they cannot end the tab's output.
- The copied text is never logged (it may hold secrets the agent printed); debug logs carry the instance ID and length.
- Targets (`c`, `p`, `s`, `0`–`7`) are not distinguished; everything goes to the system clipboard.
- A DCS-wrapped OSC 52 from a multiplexer inside the sandbox is not recognised as such (it may or may not copy).

## Verification

- `Osc52ParserTest` (24 tests): every split point of a full sequence, BEL and ST, targets, UTF-8 with emoji, query,
  invalid base64 / UTF-8, empty payload, limit and recovery, aborts, other OSCs interleaved, unbuffered long titles,
  a fresh parser after a half-fed sequence.
- `SandboxSessionTest.osc52WriteReachesTheClipboardOfTheSession` with the fake `hole` switch `FAKE_HOLE_OSC52`;
  `FakeHoleScriptTest.startWritesTheClipboardThroughOsc52`. Full suite: 456 tests, 0 failures.
- Manually verified by the developer in `runIde`: `/copy` from Claude Code in a real Hole sandbox lands in the host
  clipboard.
- `StatusBar.Info.set(String, Project)` and `CopyPasteManager.setContents` checked in the 2025.2 platform jar: public,
  not deprecated, not internal or experimental.

## Consequences & open follow-ups

- **Remove when jediterm handles OSC 52** — only if its behaviour matches (never answers reads, size limit, no clear
  on empty payload); otherwise the plugin must keep the sequence away from it.
- A switch to the reworked platform terminal has to carry this over; the output tap of `HoleTtyConnector` is what it
  hangs on.
- Not verified: `verifyPlugin` for IDEs newer than 2025.2, a Linux host (X11 clipboard through `CopyPasteManager`).

## Timeline

- **2026-11-11** — cause found (jediterm drops OSC 52), implemented, reviewed and verified manually

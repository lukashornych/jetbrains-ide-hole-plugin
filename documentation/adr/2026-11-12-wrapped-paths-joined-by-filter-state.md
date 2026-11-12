---
title: Paths the agent wraps itself are joined by state kept in the path filter, linking only the continuation
date: 2026-11-12
updated: 2026-11-12 14:00
status: accepted
kind: fix
issues: []
prs: []
areas: [src/main/java/io/github/lukashornych/hole/idea/ui]
supersedes: []
superseded-by: []
relates: []
---

# Paths the agent wraps itself are joined by state kept in the path filter, linking only the continuation

Claude Code (Ink) wraps its output itself to the terminal width. A long path has no space, so it breaks mid-word
into two real lines (or two lines of a table cell), and neither part was a link. `ui/SandboxPathFilter` now remembers
a path fragment that ends a line, or a table cell, at its edge, and links the first token of a following line (or of
the cell at the same position) to the joined path when that file exists. Only the continuation becomes a link; the
first part stays plain text.

## Why

The terminal receives two lines separated by `\n`. jediterm joins only *soft-wrapped* lines (`TerminalLine.isWrapped`)
before it runs the filters (`TextProcessing.buildLineInfo`), so each part was filtered alone and neither resolved to
a file. The constraints that made this non-obvious:

- A platform `Filter` gets only the text of one logical line, with no line index and no access to neighbours
  (`JBTerminalWidget.addMessageFilter` → `JediTermHyperlinkFilterAdapter`).
- The resulting links are written only onto the lines of that one request, so a filter cannot reach back to the
  previous line. When line N is processed, line N+1 does not exist yet, and jediterm never re-processes N because of
  N+1.
- No internal, experimental or deprecated API (project rule).

### Previous state

`SandboxPathFilter` was stateless: every path-like token of a line was a candidate, and the base class linked those
that exist. Paths that fit on one line (and soft-wrapped ones) worked. Paths that Claude Code broke did not, which is
common in its lists and Markdown tables of long Java paths.

## Options considered

### Option A — fragment kept in the filter (chosen)

The filter (one instance per tab) remembers per *box*, either the whole line or a table cell numbered by its position,
the last path token with a `/` that ends the box at its right edge. The first token of the same box on one of the
following lines is joined with it; the join is linked only if the file exists.

- **Pros:** stays within the `Filter` API; no buffer access, no locking, no line identity; the existence check
  makes a wrong pairing almost never visible.
- **Cons:** links only the continuation; relies on the platform running one widget's filters sequentially in output
  order, which is an implementation detail, not a contract.

### Option B — prefix completion of the first part

List the fragment's directory and link the fragment when exactly one entry starts with its last segment.

- **Pros:** links the visually larger part; stateless.
- **Cons:** ambiguous when the break falls early (several files share the prefix); a break inside a directory name
  identifies no file; a `:line` suffix on the next line is lost; one directory listing per candidate.
- **Rejected because:** with A in place it would only add a second, separate link on the first part, and it carries
  its own false-positive risk (a truncated path at the end of a line gets completed). Worth revisiting if users ask
  for the first part to be clickable too; it combines with A without changes to A.

### Option C — peek at the previous line in the text buffer

The filter for the continuation line reads the line above it from `TerminalTextBuffer`.

- **Pros:** reads the real previous line instead of relying on processing order.
- **Cons:** the filter does not know which buffer line it is processing; `AsyncHyperlinkFilter.LineInfo` is opaque
  and jediterm's own `TextProcessing.LineInfoImpl` and `TerminalLineFinder` are private.
- **Rejected because:** the line would have to be found by its text, which is ambiguous for repeated lines and races
  with Ink's redraws and with lines moving into history. It also takes the buffer lock from the filter thread. Revisit
  if jediterm exposes the line index in `LineInfo`.

### Option D — buffer post-processor writing one link across both lines

A component watches buffer changes, finds hard-wrapped pairs and writes one `HyperlinkStyle` onto both lines,
bypassing jediterm's `TextProcessing`.

- **Pros:** the only option that produces one whole link.
- **Cons:** the detailed change listener `TextBufferChangesListener` is `@ApiStatus.Experimental`; the line
  modification counters (`TerminalLineUtil`) are `internal`; `TextProcessing.fireHyperlinksChanged` is private;
  styles written behind jediterm's back race with Ink's redraws of the live region.
- **Rejected because:** it needs experimental API (forbidden) or degrades to the coarse `TerminalModelListener` with
  a rescan and an unfixable race. Revisit if jediterm makes the change listener stable or joins hard-wrapped lines
  itself.

## Decision

**Chosen: Option A.** It is the only option that stays in the stable `Filter` API and needs neither line identity nor
the buffer. Losing the link on the first part was accepted. The developer confirmed in a real IDE that lists and
tables are linked. If the platform starts running filters in parallel or out of order, A degrades to missing links
(never wrong ones, thanks to the existence check). In that case C (if the line index becomes available) or D (if the
listener becomes stable) is the successor.

## Key technical details

- Entry point: `ui/SandboxPathFilter.parse`. It is `synchronized`, and the fragment map is the only state.
  `SandboxTabPanel.createWidget` passes the terminal width (`getTerminalTextBuffer().getWidth()`).
- A line edge allows 8 columns of slack for Claude Code's right margin. A cell edge is its `│` border, minus one space
  of padding and one character of slack. A fragment must contain `/`, so the last word of wrapped prose costs no file
  lookup.
- A fragment lives for at most 2 further lines, a successful join consumes it, and a horizontal table rule (joints
  only, no letters or digits) clears the cell fragments, so rows never join across it. Cells are keyed by position,
  not by column, so wide characters in another cell do not shift the key.
- A join links only regular files. It wins over a plain link of the same token.
- Behaviour is described in the specification, *File path links*. Reliance on the sequential order of the platform
  adapter is documented in `architecture.md`.

## Verification

- `SandboxPathFilterTest`: continuation in a list (with `:line`), a break right after `/`, a path over three lines, a
  line ending before the edge is not continued, fragment expiry, a table cell continuation, a table rule ending the
  row, and a tree line (`├── …`) not taken for a rule. Full suite green (466 tests, `LC_ALL=C.UTF-8`).
- Manual: `runIde` with Claude Code in a sandbox. A bullet list and a Markdown table of long Java paths broken by the
  agent: the continuations open the files.
- Platform behaviour (soft-wrap-only joining, sequential filter adapter, API annotations) was read in jediterm
  `master` and intellij-community `252` / `master`. The jediterm 3.54 bundled with 2025.2 was not available to read.

## Consequences & open follow-ups

- Only the continuation is a link. Option B could add the first part.
- Wide characters (CJK, emoji) before a path on a plain line shift the column count (one `char` per column), so the
  edge may be missed or falsely found. The existence check keeps a false hit harmless.
- Tokens outside the table borders (before the first or after the last `│`) are never joined. Other layouts (a path
  split across columns, other programs' own wrapping) are not handled.
- A line over `MAX_LINE_LENGTH` returns early and does not age fragments.

## Timeline

- **2026-11-11** — reported: links only on paths that fit on a line. Cause found (hard wrap by the agent), options
  analysed.
- **2026-11-12** — option A implemented for lines, then for table cells, verified in `runIde`, polished.

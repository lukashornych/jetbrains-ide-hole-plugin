# Architectural decision records

Every change to the plugin whose reasoning has to outlive it leaves one record here explaining **what
was done, when, why, which alternatives were declined and why they lost, and what it relates to**. A
record replaces the plan it grew out of: plans describe intent that may never have shipped, records
describe what actually happened. Most changes do not need one — the bar defaults to no.

- **Writing one:** [`TEMPLATE.md`](TEMPLATE.md) — two shapes, point decision and campaign.
- **Rules** — when a record is required, which date it carries, what to keep and what to delete:
  `.claude/rules/adr.md`.
- **Starting new work?** Search here first. `rg -l "<package>" documentation/adr/` and read the
  **Consequences & open follow-ups** section of every hit — that is where the known traps and the
  deliberately-deferred work live.

## Conventions in one paragraph

A record is `YYYY-MM-DD-<slug>.md` in this folder — the date is when the decision was **accepted**,
normally the merge date of the implementing PR, so `ls` reads chronologically. When a line of work
produced several decisions *and* evidence worth keeping, it becomes `YYYY-MM-DD-<slug>/` with the
record at `README.md` and the evidence beside it — never more files than earn their place. Every
record opens with YAML front matter (`status`, `kind`, `issues`, `prs`, `areas`, `relates`,
`supersedes`), which is what makes this folder greppable — `rg -l "status: proposed"
documentation/adr/` — and the index below generated rather than hand-maintained.

## Index

Newest first. **Generated** by `scripts/generate-adr-index.sh` from each record's front matter — edit
the records, then re-run it; edits made directly to the table are overwritten. `--check` fails when
the table is stale, and the script also reports records with missing front-matter fields or a
filename date that disagrees with `date:`.

<!-- ADR-INDEX:START -->

| Date | Record | Kind | Status | Refs |
|------|--------|------|--------|------|
| 2026-11-11 | [The plugin interprets OSC 52 clipboard writes from the sandbox output itself, always on, never answering reads](2026-11-11-plugin-interprets-osc52-clipboard-writes.md) | feature | accepted | — |
| 2026-11-05 | [The Network window reads, saves and removes its entries in the global and project settings files by editing the JSON itself](2026-11-05-network-entries-saved-to-settings-by-the-plugin.md) | feature | proposed | — |
| 2026-11-05 | [Sandbox tabs are shown side by side through the platform's tool window split, with one listener per tab](2026-11-05-tool-window-panes-use-the-platform-split.md) | feature | proposed | — |
| 2026-11-05 | [Common day-to-day settings may be edited in the GUI, each through a narrow editor of one top-level key; libraries are the second](2026-11-05-day-to-day-settings-editable-in-the-gui.md) | feature | proposed | — |
| 2026-11-04 | [The plugin reads the user's login-shell environment itself before every start, and does not start without it](2026-11-04-plugin-reads-shell-environment.md) | fix | proposed | — |
| 2026-10-28 | [Favourites store no project path; the project root or a git worktree is chosen at start](2026-10-28-favourite-project-path-chosen-at-start.md) | feature | accepted | — |

<!-- ADR-INDEX:END -->

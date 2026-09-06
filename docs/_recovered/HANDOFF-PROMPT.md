# Handoff prompt — paste this into a fresh session

You are picking up **Handoff**, a native Kotlin Android app I've been building with Claude Code
for several weeks. The chat history was lost from the Claude Code UI when the desktop app was
reinstalled, but the transcripts survived and I've had them digested. Nothing about the code or
the repo was lost — only the chat list.

Working directory: `C:\Users\wasil\Dev\Car_Parking`

## First: read yourself into the project

Read these in order. Do not start work until you've read them.

**Project rules and state**
- `CLAUDE.md` — the project's standing rules. Binding.
- `docs/TIMELINE.md` — release-by-release history (53 KB; skim, then read the last ~200 lines closely)
- `docs/BACKLOG.md` — what's queued
- `docs/USER-MODEL.md` — who uses this and how
- `docs/USE-CASES.md` — the scenarios the app must handle
- `docs/v0.6-zone-registry.md` — parking-zone data model
- `docs/CONDITION-ACTION-AUDIT.md`

**Recovered chat history** — in `docs/_recovered/`, oldest to newest:
- `digest-1c491631.md` → `digest-40a7abc7.md` → `digest-83384767.md` →
  `digest-0465ea0f.md` → `digest-875258db.md` → `digest-3fd0b40f.md`

Each digest has a most-edited-files ranking and a dated timeline of my prompts plus the
assistant's reasoning. **`digest-3fd0b40f.md` is the important one** — it's the most recent
session and it ends mid-task. Read its last ~150 lines before anything else in that folder.

These digests are condensed. The full transcripts are at
`C:\Users\wasil\.claude\projects\C--Users-wasil-Dev-Car-Parking\*.jsonl`
(matched by the first 8 characters of the filename) if you ever need the exact wording of a
decision. They are large — 5 to 71 MB — so grep them, never read one whole.

## What the app is

A shared Amsterdam visitor parking permit, passed between my car and my brother Walid's. The
app tracks which car currently holds the permit, detects when a car has parked, resolves which
tariff zone it parked in, and decides whether to auto-claim the permit. It renders Amsterdam's
tariff areas on a map and shows what parking costs right now and what changes next.

Package root: `dev.wasil.permit`. Sources under `android/app/src/main/java/dev/wasil/permit/`,
tests under `android/app/src/test/java/dev/wasil/permit/`.

## Where things stand

- **v0.7.1 is released and tagged.** versionCode 28. Published at
  `https://github.com/Wasil-W/Car_Parking/releases/tag/v0.7.1`
- On `master`, clean except untracked `docs/_recovered/`
- Latest commit: `464309d Merge v0.7.1: the release review's own findings`
- ~556 tests passing as of v0.7.1

## The task that was in flight

I asked for **every parking garage, P+R and surface lot in Amsterdam to be added to the app**,
sourced from the gemeente map (https://www.amsterdam.nl/parkeren/parkeertarieven-kaart/) and
whatever open data backs it.

The previous session had already scouted the sources and found the right one. Carry this
forward rather than re-deriving it:

- The source is **RDW's NPR open data** (the Dutch national parking register) — the
  *GEO Parkeer Garages* dataset plus separate tariff, capacity, address and opening-hours tables.
- **Amsterdam is `areamanagerid 363`.** It publishes 606 areas total.
- Relevant usage codes: `GARAGEP`, `PARKRIDE`, `TERREINP`.
- Amsterdam publishes **28 facilities** — 18 garages, 8 P+R, 4 surface lots — with WKT geometry
  in WGS84. Tariffs, capacity and opening hours require a join across tables.

Something in that session failed to complete. Work out what, re-run it, and then implement the
facilities into the app so I can see them.

## Standing rules — these are mine, follow them

1. **Versioning.** Layout-only changes take a **patch** bump. Features take a **minor** bump.
2. **`versionCode` must always increase.** v0.7.1 used 28. Reusing a consumed number produces an
   APK Android refuses to install as an upgrade — this has bitten us before.
3. **`docs/TIMELINE.md` must be updated in the same commit as the version bump.** CLAUDE.md
   requires it.
4. **Work on a branch.** Never merge or push without me saying so explicitly.
5. **Separate layout changes from behaviour changes** into different releases.
6. **The Firebase database URL is never committed.** Not in code, not in docs, not in a commit
   message. Note: it already leaked into three past commits and is immutable in a public repo —
   if this comes up, the fix is rotating the resource, not editing files.
7. **Releases ship the debug APK** (debug-signed, so it installs). The release build is unsigned.
8. **Verify on the emulator before tagging** — including the upgrade path from the previous
   version, not just a clean install.

## How I work

Tell me what you actually did and what you found, including the things that went wrong. If a
review turns up something real after you've already shipped, say so plainly instead of burying
it. Don't claim something is verified unless you ran it and saw the output.

Start by reading the files above, then tell me what you understand the state to be and what you
plan to do about the garages — before you write any code.

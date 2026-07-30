# Tooling recommendations

Researched 2026-07-30. Candidate Claude Code skills, plugins and MCP servers
that would improve work on this project, ranked by how much they'd actually
help *here* — not by popularity.

Already installed: superpowers (+dev, +lab), claude-mem, episodic-memory,
superpowers-chrome, elements-of-style, claude-session-driver, and the official
plugin marketplace.

---

## 1. An Android emulator / ADB bridge — the biggest gap by far

**The problem it solves.** Throughout v0.3.1 the recurring limitation was that
nobody could *see* the app. The arc geometry was wrong twice and only caught by
hand-computing coordinates. Whether the mark's proportions look right, whether
light mode feels balanced, whether edge-to-edge collides with the status bar —
none of that is answerable by a green test suite, and every one of those got
deferred to "check it on your phone". A screenshot capability would close that
loop entirely: build, install to emulator, screenshot, look, iterate.

For a project whose whole current release is *visual*, this is worth more than
every other tool on this list combined.

**Candidates**, all MCP servers exposing ADB (screenshots returned as images,
plus tap/swipe/text input, UI hierarchy dumps, and logcat):

| Repo | Notes |
|---|---|
| [martingeidobler/android-mcp-server](https://github.com/martingeidobler/android-mcp-server) | Screenshots compressed in-memory and returned as base64 for visual analysis; also does bug documentation. Node, `claude mcp add` install. |
| [AlexGladkov/claude-in-mobile](https://github.com/AlexGladkov/claude-in-mobile) | Broader: Android via ADB, iOS Simulator via simctl, and Compose Desktop. Relevant given the possible iOS port. Homebrew install, so awkward on Windows. |
| [Anjos2/mcp-android-emulator](https://github.com/Anjos2/mcp-android-emulator) | Screen as base64, UI hierarchy, gestures, text input, key presses. |
| [minhalvp/android-mcp-server](https://github.com/minhalvp/android-mcp-server) | Python-based ADB control. |

**Read this before installing any of them.** An MCP server is a program that
runs on your machine with whatever access you give it, published by someone
neither of us knows. Installing one means executing a stranger's code with ADB
access to your phone or emulator. That is a genuine supply-chain decision, not a
formality. Before installing, at minimum: read the source (these are small),
check the repo's age, commit history and issue activity, and prefer one you can
audit in a sitting. Pin a commit rather than tracking `latest`.

A safer alternative exists and costs nothing: **I can already drive `adb`
directly through the shell.** `adb shell screencap -p /sdcard/s.png`, pull it,
and read it as an image. That covers screenshots and installs with no third-party
code at all — it is just clumsier than a purpose-built tool. Worth trying first.

**Prerequisite either way:** an emulator image, or your phone in USB-debugging
mode. `C:\Android\sdk` is already here, so `emulator` and `adb` should be
available.

---

## 2. Compose and Android expertise skills

These improve the *quality* of Compose code rather than adding a capability.
Relevant because v0.3.1 wrote a theme, a custom `Canvas` composable, and four
screens largely from first principles.

**[chrisbanes/skills](https://github.com/chrisbanes/skills)** — the pick of the
bunch. Chris Banes is on Google's Android team, which makes this the most
authoritative source here. Narrow, opinionated skills on state authoring, state
hoisting, recomposition performance and stability, focus navigation, Compose UI
testing, structured concurrency, and KMP boundaries.

```
/plugin marketplace add chrisbanes/skills
/plugin install chrisbanes-skills
```

**[aldefy/compose-skill](https://github.com/aldefy/compose-skill)** — one skill,
`compose-expert`: 24 reference guides plus six real source files pulled from
`androidx/androidx`, so its advice is grounded in actual framework source rather
than recollection. Installed by cloning and copying into `~/.claude/skills/`.

**[rcosteira79/android-skills](https://github.com/rcosteira79/android-skills)** —
21 skills, broad rather than deep: architecture, modularisation, Retrofit,
coroutines, Flows, Gradle build performance, testing discipline, debugging, and
an `android-source-search` skill for fetching AOSP/AndroidX source. Confirmed
to contain **no** emulator or visual-verification skill, so it does not overlap
with item 1.

```
/plugin marketplace add rcosteira79/android-skills
/plugin install android-skills@android-skills
```

Note this project uses Retrofit and plain OkHttp with no DI framework and a
hand-rolled composition root, deliberately. Several of these skills assume Hilt
or Koin and multi-module structures. Their advice is worth reading, not
obeying — a two-user family app does not need that architecture.

---

## 3. Recommendation

If only one thing gets added: **the emulator bridge**, because it removes the one
limitation that actually bit repeatedly during this release. But try raw `adb`
through the shell first — no third-party code, and it may be enough.

If two: add **chrisbanes/skills** as well. Small, authoritative, and directly
relevant to the Compose work that's now the app's whole UI layer.

Everything else here is optional. More skills installed is not better; each one
competes for attention, and three overlapping Compose skills would mostly add
noise.

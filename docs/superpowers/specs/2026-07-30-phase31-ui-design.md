# v0.3.1: Handoff — branding and layout — Design

Approved 2026-07-29/30. A visual and layout release only: no detection logic,
no claim logic, no new capabilities. The app gains a name, an identity, a real
Compose theme, and screens organised around how often you actually touch them.

Functional work found along the way is recorded in `docs/BACKLOG.md` and
deliberately excluded here. Read that file before planning any future update.

## Why this release exists

Phases 1–3 built a working app with no visual design at all:

- `MaterialTheme { }` with no arguments — Material 3's baseline purple.
- Light-only. Bare `MaterialTheme` defaults to `lightColorScheme()`, so the
  phone's dark mode is ignored.
- XML parent `android:Theme.Material.Light.NoActionBar` — Material 1, from 2014.
- Launcher icon `@android:drawable/sym_def_app_icon` — the stock Android robot.
  No mipmaps or adaptive icon exist.
- No `enableEdgeToEdge()`, which Android 15 requires.
- Settings is one flat list mixing set-once configuration, occasional toggles,
  and system state.

The dominant interaction is a two-second glance while walking to the car:
*who has the permit right now?* Today that answer is a line of ordinary body
text, weighted the same as everything around it.

## Identity

**Name: Handoff.** Replaces "Permit Switcher" in `strings.xml` and the launcher
label. One word, describes what the app does, fits under a home-screen icon.

**Mark:** two facing arcs with a dot between them. The arcs are the two cars;
the dot is the permit. The dot sits nestled against whichever arc currently
holds it, and that arc renders in its owner's colour while the other dims to
neutral. Position, colour, and contrast all encode the same fact, so the state
survives a glance, a small size, and colour-blindness.

On a switch, the dot animates across the gap. This is the only animation in the
release and it exists because it is the app's central event.

**Icon field:** flat charcoal `#232320`. No gradient, no pattern — the arcs
carry the colour, and a patterned field competes with them.

### Two colour systems, deliberately separated

The first palette attempt used sage-teal and clay-ochre, which are desaturated
green and amber. That collided with status colour: a green card meant both
"success" and "Wasil", and a clay button meant both "Walid" and "risky action".
Identity therefore moves to hues that carry no state meaning.

| Role | Colour | Where it may appear |
|---|---|---|
| Wasil | slate blue `#5A7D9A` | Large fills only — hero card, lit arc, "whose car" labels |
| Walid | terracotta `#B07B55` | Large fills only — same |
| Inactive arc | `#3E3E39` | The dimmed half of the mark |
| Fine | green `#5C8A54` | Small icons and labels only |
| Needs attention | rust `#B3503C` | Small icons and labels only |

Identity is separated from state by **hue and by scale**: identity is only ever
a large fill, state is only ever a small icon plus label. If two colours ever
drift close, size still disambiguates them.

Blue against orange is also the most colour-blind-safe pairing available, which
matters when colour is load-bearing.

Dark neutrals: surface `#171715`, card `#201F1C`, hairline `#2E2D28`, primary
text `#DEDCD4`, secondary text `#918E85`.

Light neutrals: surface `#FAF9F5`, card `#FFFFFF`, hairline `#E3E1D9`, primary
text `#26241F`, secondary text `#6B6862` — warm off-whites, matching the warm
cast of the dark set rather than pure grey.

Identity hues stay the same in both modes when used as a **fill**, so the hero
card reads as the same blue or terracotta on either theme. When an identity
colour is used as **text or a hairline** on a light surface it darkens one step
— Wasil `#4A6B87`, Walid `#96653F` — to hold contrast against white. State
colours do the same: green `#4C7645`, rust `#96412F` on light.

## Theme

A real theme package replaces the bare `MaterialTheme`:

- `ui/theme/Color.kt` — the palette above as named constants.
- `ui/theme/Theme.kt` — `lightColorScheme()` and `darkColorScheme()` built from
  it, selected by `isSystemInDarkTheme()`. **Not** dynamic colour: the two
  phones must agree on what blue and terracotta mean, so a wallpaper-derived
  palette would break the identity system.
- `ui/theme/Type.kt` — typography scale. The permit holder's name is the
  largest text on screen; everything else steps down from it.
- Identity colours live outside `ColorScheme` (it has no slot for "person"),
  exposed through a small `LocalIdentityColors` composition local so both
  screens and the mark read them from one place.

The XML theme parent becomes a Material 3 `NoActionBar` parent with matching
background and status-bar colours, and `MainActivity` calls
`enableEdgeToEdge()` with insets handled per screen.

## Launcher icon

An adaptive icon (`ic_launcher_foreground` / `ic_launcher_background` plus
`mipmap-anydpi-v26` XML) drawn as vectors, replacing the stock robot.

The launcher icon is **static**, with the dot centred. Runtime icon swapping
would need `activity-alias` juggling that makes the app flicker out of the
launcher and can break shortcuts — not acceptable for a cosmetic gain. A
home-screen widget is the correct way to show live state and is deferred to
v0.4 (see backlog).

## Screens

### First-run setup

The reason Settings is cluttered is that set-once questions have nowhere else
to live. A stepped flow asks them once, one screen per step, then never appears
again: credentials → whose phone is this → car Bluetooth device → permissions →
database URL → home zone.

Each step states why it is needed in one line. Steps that can be skipped say
so, and skipped steps surface later in the Settings health row rather than
silently degrading behaviour.

"Whose phone is this" is what makes the two phones mirror each other from one
APK — Wasil's phone offers "Hand to Walid", Walid's offers "Hand to Wasil".
There is no separate build.

### Main screen

Structure, top to bottom: wordmark and small mark; the hero card; one primary
action; the other car's status; an icon row.

The hero card is filled with the holder's identity colour and carries the mark,
the holder's name, and the plate. It is the largest element on screen and
answers the glance question on its own.

Because there are exactly two cars, the permit can only ever move to one place,
so the screen needs **one** button, not two: "Hand to Walid" when you hold it,
"Take it back" when he does. Fewer decisions, a bigger target, and the app's
name as a verb.

Refresh, map, and settings become an icon row instead of three stacked text
links.

The blocked-switch warning stays a dialog here, with the same facts and the
same "Claim anyway" override as today.

### Settings

Reorganised by how often each item changes:

- **Setup summary** — a single card showing whose phone, plate, sync status and
  paired car, with one "Edit" affordance back into the first-run steps. This is
  where credentials, database URL, and whose-phone go to stop nagging.
- **Detection** — auto-switch toggle, car Bluetooth device.
- **Zones** — home zone, free zones (both still list-based this release; the
  map takes them over in v0.4).
- **System** — one row per requirement with a state icon: green tick when fine,
  rust triangle plus a "Fix" affordance when not. Quiet when everything is in
  order, instead of a permanent wall of permission buttons. Battery
  optimisation keeps its current behaviour, which works well.

### Decision screen

Every notification gets a content intent, so tapping it opens a full-screen
version of the same decision instead of only offering cramped notification
actions. The blocked-claim case matters most: the mark with the dot mid-travel,
the facts (when the other car parked, when it was last seen, why claiming is
risky), and three choices — claim anyway, mark this spot free, leave it.

This is the one item in the release that is not purely visual: it needs a
`PendingIntent`, a screen, and ViewModel state to carry the decision. It is
included because a decision screen nothing can open is worthless.

Notification small icons reflect state — which arc is lit — since that costs
only a drawable.

### Map and setup screen

Restyled to the new theme, otherwise unchanged. The map's real rework — home
zone shown and draggable, free zones managed there, correcting the parked pin,
tariff polygons overlaid and selectable — is v0.4.

## Out of scope

Detection logic, claim logic, the shared-state protocol, the collision guard,
and the tariff data all stay exactly as Phase 3 left them. The two known bugs
(the retry loop when the permit is already yours, and the missing retry cap)
are recorded in the backlog and fixed separately from this release, so a
visual change never has to be bisected against a behavioural one.

No home-screen widget, no map rework, no Bluetooth picker redesign beyond
restyling, no notification changes beyond the content intent and state icon.

## Testing

Compose UI carries no logic, so the existing 96 unit tests must still pass
untouched — that is the primary evidence this release changed nothing
functional. Any test that needs editing means the change was not layout-only
and should be questioned.

New coverage is limited to what has real logic:

- The mark's state mapping: holder → which arc is lit, which side the dot sits.
- The primary action's label and target: holder plus whose-phone → button text
  and the car it hands to, including both phones' perspectives.
- The Settings health row: permission and battery state → icon and whether a
  "Fix" affordance shows.

Manual checks: both light and dark mode on every screen; the icon on a light
and a dark wallpaper; first-run flow start to finish on a clean install;
tapping each notification type; and confirming the upgrade path leaves an
existing install's configuration intact.

## Version

`versionName` 0.3.1, `versionCode` 4. A patch bump because nothing behavioural
changes — the convention agreed for this project is that layout-only releases
take a patch and behaviour takes a minor.

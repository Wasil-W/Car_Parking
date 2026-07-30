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

Identity needs three roles, not one. The hero card is a **tint** with the strong
colour as its border, not a slab of saturated colour — a full-strength card was
what read as "bright and shiny" in review, and the tinted version as "sleek".

| Role | Dark mode | Light mode | Where it may appear |
|---|---|---|---|
| Wasil strong | `#5A7D9A` | `#45657F` | Lit arc, hero card border, the primary button |
| Wasil container | `#1E2A33` | `#E8EDF2` | Hero card background only |
| Wasil on-container | `#A8C0D4` | `#2F4A5F` | Text and plate inside that card |
| Walid strong | `#B07B55` | `#8A5C39` | Same three uses, mirrored |
| Walid container | `#2C2118` | `#F3E9E0` | Hero card background only |
| Walid on-container | `#D9B48F` | `#6B4529` | Text and plate inside that card |
| Inactive arc | `#3E3E39` | `#C9C6BC` | The dimmed half of the mark |
| Fine | `#5C8A54` | `#4C7645` | Small icons and labels only |
| Needs attention | `#B3503C` | `#96412F` | Small icons and labels only |

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

### How the light palette was derived

Light mode gets its **own** shades of the same two hues, not the dark values
reused. Measured against their own backgrounds, the dark values were already
contrast-matched — `#5A7D9A` is 4.13:1 on `#171715` and 4.12:1 on `#FAF9F5`,
because a mid-tone sits near the perceptual middle and lands in the same place
from either direction.

The number hides the actual problem, which is the text sitting **on** the fill.
Cream text on `#5A7D9A` is only 3.16:1 — past the 3:1 large-text minimum and
nothing more. A mid-tone fill on a near-white page also simply reads as washed.
So the light fills drop a step, which raises both the fill's contrast against
the page and white text's contrast against the fill:

| Pair | Contrast |
|---|---|
| Wasil `#45657F` on `#FAF9F5` | 5.8:1 |
| Walid `#8A5C39` on `#FAF9F5` | 5.4:1 |
| White text on either light fill | ~6:1 |
| Fine `#4C7645` on `#FAF9F5` | 5.0:1 |
| Alert `#96412F` on `#FAF9F5` | 6.4:1 |

The two identity colours are also balanced against **each other**, not just the
background: the first terracotta tried came out at 4.7:1 against the blue's
5.8:1, which would have made Walid's card read as consistently weaker than
Wasil's. `#8A5C39` closes that gap.

Rule for implementers: every identity and state colour has a distinct value per
mode. Nothing is shared between the two palettes.

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

### Notifications

Notifications keep their current behaviour, wording, and actions. The only
change is cosmetic: their small icons become the Handoff mark instead of the
stock Android drawables (`ic_menu_mylocation`, `ic_dialog_alert`) they borrow
today. A pure asset swap with no logic attached.

Making notifications tappable — a content intent opening a full-screen decision
view, and a state-aware small icon — is deferred to v0.4, because both need
real plumbing rather than a drawable.

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

Also out: the notification decision screen and content intents, a home-screen
widget, the map rework, and any Bluetooth picker redesign beyond restyling. All
are recorded in `docs/BACKLOG.md` against v0.4.

The rule this release holds to: if a change needs a `PendingIntent`, a
ViewModel field, or a new code path, it is not layout and does not belong here.

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
and a dark wallpaper; first-run flow start to finish on a clean install; each
notification type showing its new icon with unchanged text and actions; and
confirming the upgrade path leaves an existing install's configuration intact.

## Version

`versionName` 0.3.1, `versionCode` 4. A patch bump because nothing behavioural
changes — the convention agreed for this project is that layout-only releases
take a patch and behaviour takes a minor.

# Changelog

Every released version, newest first. Each entry is written to double as its
GitHub release notes.

Convention from v0.3.1 onward: **visual and layout releases take a patch bump**
(0.3 → 0.3.1), **releases that change behaviour take a minor bump** (0.3 → 0.4).

---

## v0.3.1 — Handoff *(in progress)*

Entry written when the release is finished.

---

## v0.3 — Phase 3: shared state between the two phones

Tag [`v0.3`](https://github.com/Wasil-W/Car_Parking/releases/tag/v0.3) ·
commit `e072aeb` · `versionCode` 3

The two phones now know about each other, so claiming the permit can no longer
silently strand the other car.

**Collision guard.** Before any switch — automatic, from a notification, or from
the main screen — the app checks the other phone. If that car is parked in a paid
zone, was heard from within six hours, and the permit is currently on its plate,
the switch is blocked with a "Claim anyway" override rather than going through
silently. Taking it anyway alerts the other phone.

**Shared state without the Firebase SDK.** Each phone publishes whether it is
parked in a paid zone, where, and since when, to a Firebase Realtime Database
over plain HTTPS. No SDK, no `google-services.json`. The room both phones share
is derived from the permit username (SHA-256), so there is no pairing step, and
the database URL is entered in Settings and never committed. Heartbeats use
server timestamps, so staleness checks survive phone clock skew.

**Real paid zones.** Amsterdam's 29 official tariff areas are bundled as
polygons, so parking outside every paid area is recognised as free street
parking and claims nothing. Claim notifications show the hourly rate and zone
code.

**Home zone.** A 30–200 m circle per phone. Parking there never claims and never
blocks the other car.

**Automatic give-back.** Park at home or somewhere free while the other car is
still parked in a paid zone and needs the permit, and it is handed back without
you doing anything.

**Personal map.** Your car's last parked spot and your own position. Nothing on
it is shared with the other phone.

**Fixes for problems found in real use during Phase 2:**

- Claim retries now wait for connectivity instead of burning their exponential
  backoff while still offline — the cause of "it worked once, then only manual
  retries worked".
- A confirmed park with no GPS fix now asks instead of claiming blind, which
  previously could claim the permit while parked at home.
- "Free here" reads a fresh location at the moment you tap it, rather than
  storing a stale one.
- Pending claims are cancelled when you get back in the car, so an old failed
  claim can no longer fire while driving.
- Settings gained a "Disable battery optimisation" button; Samsung's app-sleep
  was very likely why detection worked once and then stopped.

**Setup:** one-time Firebase project, URL pasted into Settings on both phones —
see `SETUP_FIREBASE.md`. Both phones must run 0.3 or later for the guard to
work; a phone on an older build never publishes its state and reads as "not
parked".

96 unit tests. Tariff data © Gemeente Amsterdam, CC-BY 4.0.

---

## v0.2 — Phase 2: automatic parked detection

Tag [`v0.2`](https://github.com/Wasil-W/Car_Parking/releases/tag/v0.2) ·
commit `54009d3`

No more remembering to open the app.

**Bluetooth as the park trigger.** When the car's Bluetooth disconnects, the app
starts checking whether you actually parked.

**Confirmation before acting.** Activity Recognition and GPS are sampled for up
to 90 seconds, so a Bluetooth blip while still driving is never acted on. Only a
confirmed park claims the permit.

**Auto-claim** on a confirmed park, still verified read-after-write, with a
persistent notification showing who currently holds the permit. Reconnecting to
the car clears the parked state.

**"Free here"** marks a spot with a 60 m radius so the permit is never touched
there again — intended for home and private parking.

**Settings:** pick your car from paired Bluetooth devices, set whose phone this
is, and turn auto-claim off if you would rather be asked each time.

> **Known issue with this release:** the version number was never bumped. This
> APK reports itself as `versionName` 0.1 / `versionCode` 1, identical to v0.1,
> so the two builds cannot be told apart on-device. Corrected from v0.3 onward.
> If you are unsure which build a phone is running, reinstall.

---

## v0.1 — Phase 1: one-tap switching

Tag [`v0.1`](https://github.com/Wasil-W/Car_Parking/releases/tag/v0.1) ·
commit `c7f54e3` · `versionCode` 1

First working version. Replaces logging into the permit website on a phone
browser and fighting the form.

**One tap** to move the permit between Wasil's and Walid's plate.

**Read-after-write verification.** A 200 response from the activate call is not
treated as proof. Every switch re-reads permit state and compares; a mismatch
raises a loud warning instead of a false success. A wrong permit is a parking
fine, so the app never assumes.

**Transparent re-login.** The API token lives one hour; the app re-authenticates
on a 401 without involving you.

**Credentials stay on the phone,** in `EncryptedSharedPreferences`, entered on a
one-time setup screen. Nothing sensitive is in the repository.

Built on the Phase 0 Python CLI (`permit.py`), which remains the reference for
the API's request and response shapes.

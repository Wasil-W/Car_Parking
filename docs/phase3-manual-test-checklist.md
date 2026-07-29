# Phase 3 on-device checklist (needs both phones on 0.3)

Setup: Firebase URL saved + "Connection OK" on both; home zone set on both;
battery optimization disabled on both.

1. **Paid-zone park:** drive somewhere paid, park, walk away. Expect:
   status notification "Permit on <me>'s car" with €-rate + zone code.
   Other phone's main screen shows "<me>: parked outside since HH:MM".
2. **Collision:** with car 1 still parked, park car 2 in a paid zone.
   Expect on phone 2: "…'s car is parked — permit NOT claimed" with
   Claim anyway / Ignore. Permit unchanged on the website.
3. **Override + takeover alert:** tap Claim anyway on phone 2. Phone 1 gets
   "<other> took the permit" within ~15 min. Reclaim from that notification
   works (and warns, since car 2 is now the parked holder).
4. **Give-back:** phone 2 parked+holding, phone 1 drives home and parks in
   the home zone. Expect phone 1: "Parked — permit untouched (at home)";
   permit switches to car 2 automatically ("Permit on <other>'s car").
5. **Home zone:** park at home. Expect no claim, no block ("at home").
6. **Free street:** park outside any paid polygon (e.g. far suburb).
   Expect "free street parking (outside paid zones)", no claim.
7. **Offline retry (the Phase 2 bug):** airplane mode ON, park in a paid
   zone, keep airplane mode for 5+ min, turn it off. Expect the claim to
   fire shortly after connectivity returns, without tapping anything.
8. **Free here fresh location:** on a manual-decision notification tap
   "Free here" while standing at the spot. Settings → Free zones shows a
   zone at the CURRENT location.
9. **Main-screen guard:** with the other car parked+holding, tap "Set to
   my car" on the main screen. Expect the warning dialog; Cancel leaves the
   permit; Claim anyway switches it.
10. **Back in car:** reconnect Bluetooth. Status stays, park notifications
    dismissed, other phone sees "not parked outside" within a minute.

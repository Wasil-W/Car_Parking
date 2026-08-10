# One-time Firebase setup (Wasil does this once)

The app shares "who is parked where" between the two phones through a free
Firebase Realtime Database, accessed directly over HTTPS. No Firebase SDK, no
google-services.json — only a database URL entered in the app.

**The database URL is a secret. Never commit it to this repo (repo and APKs
are public). It lives only in the app's Settings on the two phones.**

1. Go to https://console.firebase.google.com → **Add project**. Name it
   anything (e.g. `permit-switcher`). Disable Analytics. Free Spark plan — no
   credit card.
2. In the left menu: **Build → Realtime Database → Create Database**. Choose
   **Belgium (europe-west1)**. Start in **locked mode**.
3. Open the **Rules** tab, replace the contents with exactly this, and publish:

   ```json
   { "rules": { "rooms": { "$room": { ".read": true, ".write": true } } } }
   ```

   The root stays unreadable, so nobody can list rooms. Each room path is
   derived from the permit username (128-bit hash) — unguessable in practice.
4. Copy the database URL shown at the top of the Data tab. It has the shape
   `https://<YOUR-PROJECT>-default-rtdb.europe-west1.firebasedatabase.app`.

   > **Never commit the real one.** This repo and its APKs are public, and the
   > rules above put security entirely on the room hash: URL plus room hash is
   > read and write. The placeholder here used to spell out the example project
   > name from step 1, which made it indistinguishable from a real URL for
   > anyone who followed the guide literally.
5. On BOTH phones: app → Settings → **Shared state (Firebase)** → paste the
   URL → Save → **Test connection** must say "Connection OK."

Both phones must be on app version 0.3+ and have the same permit credentials;
the shared room is derived from the username automatically.

What is stored there: for each phone, whether it is parked in a paid zone,
the parked coordinates, and timestamps; plus which plate last claimed the
permit. Anyone who somehow learned the URL AND the room hash could read that.
Acceptable for a two-person family tool; revisit with Firebase Auth if that
ever changes.

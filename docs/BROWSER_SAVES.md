# Browser matches and saves

Quick and full four-player matches have independent browser sessions. Tabs in the same browser share a match; separate browser profiles have separate matches. There are no accounts or cross-device cloud saves.

## Recovery

Each completed order returns an AES-256-GCM authenticated snapshot, saved in localStorage before the page reloads. The snapshot includes all four hands, the ordered deck and discards, economy, board positions, wounds and equipment, per-round action limits, diplomacy and identifiers, pending occupations and combined orders with actual defender-turn counters, events, elimination and winner state. Hidden hands and deck order are encrypted.

After a server restart or cache eviction, the page sends its saved snapshot back and the server reconstructs the complete match before enabling controls. The persistent HttpOnly cookie binds saves to this browser. A stable server key must survive deployment and restart. No database or persistent disk is used.

Wait for “Match saved in this browser” after issuing orders. Failed storage or restoration disables controls and preserves the previous save. A damaged, foreign, or unsupported save is not silently replaced by a new match. The explicit discard control closes the server match and removes the browser save.

Clearing cookies or site storage, changing browser profiles, losing the server key, or rotating it prevents restoration. Private browsing storage may disappear when the window closes. A response lost before the browser stores it may lose that last order after a subsequent server restart. This is a browser-held playtest save, not an account-backed durability guarantee. The basic sandbox and historical replays remain temporary; reset sandbox intentionally closes the saved match.

## Server configuration

Set WILD_DECK_SAVE_KEY to the base64 encoding of 32 cryptographically random bytes, generated once and stored as a hosting secret. Keep that value stable across restarts. Do not commit it or print it in logs. Startup fails if it is absent or invalid, avoiding misleading restart claims with a temporary key.

Cookies are Secure, HttpOnly, SameSite=Strict, with a one-year lifetime. WILD_DECK_COOKIE_SECURE=false is only for local HTTP development and subprocess tests; leave it unset on HTTPS hosting.

The server caches at most 64 browser sessions. A least-recently-used session can be evicted and restored from its browser save on reload. Requests serialize within each session; revisions reject stale commands from other tabs. Restore cannot roll back an initialized server session. Same-origin custom request headers protect mutating routes; no CORS access is granted. Request and decrypted-save sizes are bounded. A user who retained an older valid token can restore it after server state has disappeared; preventing that rollback would require durable authoritative server storage.

## Verification

MatchSnapshotTest checks JSON round trips, private hands and deck order, future command equivalence, pending forest response windows, diplomacy identifiers, non-default combat/economy/occupation/victory fields, detached collections, schema versions, and coverage of every mutable engine field.

BrowserGameServerTest uses independent HTTP clients and cookies to check isolation, stale revisions, cross-session saves, tampering, wrong keys, and same-origin request requirements. It launches two separate Java processes in sequence, restores two different matches into the second process, compares their complete snapshots, then checks equivalent continuation and continued isolation.

These are automated HTTP/process checks. Live browser and hosted restart validation must still be performed once the development host is available and its stable save key is provisioned.

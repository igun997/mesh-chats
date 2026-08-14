# Mesh Chats — UI/UX Design

**Date:** 2026-08-13
**Status:** Validated (brainstorming session)
**Scope:** Design system, app shell, and screen-level UX for an E2E mesh chat app with Wi-Fi / Bluetooth / LoRa transports, an optional TUN/TAP relay for global reach, offline maps, and SOS.

---

## 1. Product constraints

- End-to-end encrypted chat over a mesh: Wi-Fi (Aware/Direct), Bluetooth LE, LoRa (attached radio), plus an optional TUN/TAP relay for global reach.
- **No message is ever stored on a server.** The relay forwards ciphertext it cannot read and keeps no queue beyond in-flight delivery.
- Monochrome (pure black and white) visual identity.
- Bottom navigation with icons.
- Maps that work with zero connectivity.
- SOS that works when infrastructure is gone.
- Professional-grade layout: safe areas / insets calculated, not guessed.

## 2. Decisions taken

| # | Decision | Choice |
|---|---|---|
| 1 | Color | Pure monochrome, zero hue. No Material You dynamic color. |
| 2 | Nav + SOS | 4 tabs + center-docked SOS with hold-to-arm. |
| 3 | Transport visibility | Transport-first: persistent strip, per-message transport + hops. |
| 4 | Identity | Device keypair, 4-word fingerprint, QR / read-aloud verification. |
| 5 | Map | MapLibre GL Native, custom mono style, offline vector tiles. |
| 6 | Layout | Edge-to-edge + adaptive panes (nav rail + list-detail at Medium+). |
| 7 | SOS audience | E2E to verified contacts **plus** signed open beacon to nearby nodes. Manual emergency dial only, never automatic. |
| 8 | Local storage | Encrypted at rest (SQLCipher) + disappearing timers + panic wipe. |

Rejected: transport-coded accent colors, SOS as a nav tab, emoji/color safety numbers, Google Maps SDK, phone-only fixed layout, RAM-only storage, auto-dial to emergency services.

---

## 3. Design system

**No dynamic color.** `MeshChatsTheme(dynamicColor = false)`; remove the `dynamicDark/LightColorScheme` branch so identity is identical on every device.

**No shadows anywhere.** In monochrome, elevation shadows read as grey smudge. Depth = surface steps + 1dp hairlines.

```
Dark (default)        Light
#000000 background    #FFFFFF
#0B0B0B surface       #FAFAFA
#151515 surfaceHi     #F1F1F1
#232323 surfaceMax    #E7E7E7
hairline = onSurface @14%, 1dp
```

**Text is an opacity ladder,** not a set of greys: 100% primary, 72% secondary, 48% metadata/tertiary, 32% disabled. Same ladder in both themes.

**Type.** System sans for UI. Tabular figures for anything that counts (byte counter, SOS timer, hops, peers, RSSI) so digits do not jitter. Monospace for fingerprints and IDs. Scale: display 32/40, title 22/28, body 16/24, label 14/20, meta 12/16.

**Icons.** Material Symbols; outlined = inactive, filled = active — that pair carries state now that color is gone. 24dp, never below 20dp.

**Motion.** 120ms micro, 240ms standard, 400ms emphasized (pane change). SOS pulse 1000ms loop, scale 1.0→1.06, opacity 100→70. All gated on the system animator scale for reduce-motion.

**Contrast floor.** ≥4.5:1 for every text/background pair. Hairlines are never the only carrier of meaning.

---

## 4. App shell, navigation, insets

**Bottom bar:** `Chats · Map — [SOS] — Mesh · Settings`. Icons `forum`, `map`, `hub`, `tune`. Outlined→filled on select, labels always visible, plus a 3dp underline under the active label (second cue for mono).

**SOS dock** is the only inverted element in the product: 64dp circle, full inverse fill, floating 12dp above the nav bar with `navigationBarsPadding()` so it clears the gesture pill. Hold-to-arm draws a 3dp progress ring. Nothing else uses 100%-on-0% inversion, so it always reads as the emergency control without a single red pixel.

**Transport header status:** compact cluster in each screen's top-app-bar action slot.

```
Screen title           WiFi  BT  LoRa  RELAY  4 peers
```
Solid white glyph = actively carrying traffic, white outline = radio on but no peer (idle or scanning), dim struck = off or absent (LoRa struck when no hardware attached). On vs off is the tint (white vs dim); carrying vs idle is the fill. Tap → Mesh tab. This is the always-on trust indicator without spending a second header row.

**Insets contract (one rule, no per-screen guessing):**
- `enableEdgeToEdge()`; `Scaffold` consumes system bars.
- Lists: `contentPadding = innerPadding + 76dp bottom` so the last row clears the SOS dock.
- Chat composer: `imePadding() + navigationBarsPadding()`; the dock hides while the keyboard is up.
- Map: tiles full-bleed under the status bar, every control inside `safeDrawing` + 8dp.
- Nothing interactive within 8dp of `safeDrawing`; SOS keeps a 12dp edge exclusion.

**Adaptive:** at `WindowWidthSizeClass.Medium+`, bottom bar → `NavigationRail` (SOS docked at rail bottom) and chats become `ListDetailPaneScaffold`. Per-tab back stacks, predictive back on, 240ms crossfade between tabs (no slide — mono has no depth).

---

## 5. Chats

**List rows.** Avatar = 4-glyph fingerprint monogram in a 44dp square; hairline ring = unverified, solid ring + corner check = verified. Row: name (bold if unread) · last message @72% · time (tabular) · transport glyph of that message.

Reachability drives sort order, not recency alone: reachable peers first, out-of-range peers at 48% with struck transport glyph under an `OUT OF RANGE` header.

**Chat header.** Line 1: name + verified state. Line 2 (mono type): the route — `direct · BT · 1 hop`, `LoRa · 3 hops · 890ms`, or `relay · global · E2E`. Tap → peer sheet (fingerprint, verify QR, disappearing timer, block-screenshots toggle, block).

**Bubbles.** Outgoing = `surfaceMax` fill, right-aligned. Incoming = transparent + hairline, left-aligned. 16dp radius, max width 80%. Deliberately not inverted — inversion is reserved for SOS.

**Per-message metadata** (12sp @48%): time · transport glyph · hop count · delivery state as shape, never color: `○` queued, `◐` sent, `●` delivered, `×` failed. Read receipts off by default; delivery ack only.

**Composer adapts to the active route.** Normal: multiline, 4-line cap. On LoRa: tabular byte counter `137/200` (bold at 90%), over-limit shows `2 fragments · ~14s` rather than blocking. Out of range: send label flips to `Queue` and a chip reads `Queued · sends when peer in range`.

**Long-press a message → detail sheet:** full hop path as fingerprint monograms with per-hop transport, byte size, key used, and whether the message ever touched the relay. This is the surface that proves the no-server claim.

---

## 6. Mesh tab

**One card per transport** (88dp, hairline border):

```
WiFi   Wi-Fi Aware / Direct     active   2 peers · 1.2 MB/s     [toggle]
BT     Bluetooth LE mesh        active   1 peer  · 12 kB/s      [toggle]
LoRa   No device attached       absent   attach USB/BLE radio   [attach]
RLY    Relay · vpn.example:443  global   E2E · stores nothing   [toggle]
```
Attached LoRa reads `RAK4631 · USB · 868MHz · SF7 · duty 1.2%` with a duty-cycle bar, because regional duty limits actually throttle sending.

Every transport row, **including Bluetooth**, carries a normal switch. The Bluetooth switch persists the user's discovery intent (DataStore) rather than toggling the system radio: OFF stops advertising/scanning and survives restart, and the row honestly reads Off with BLE peers cleared; ON resumes foreground-only scanning while the Mesh screen is visible. While OFF the permission/Bluetooth-off prompt cards are hidden, so a disabled switch never nags for permissions.

**List / Graph toggle.** Graph = mono topology: nodes are fingerprint monograms; edges are hairlines style-coded by transport (solid Wi-Fi, dotted BT, dashed LoRa, double-line relay) with opacity = link quality. Rendered from real neighbour tables.

**Peer rows.** Monogram · name · fingerprint (2 words, mono) · reachable-via glyphs · `−67 dBm` or `2 hops` · last seen. Swipe → verify. Unverified peers grouped under `UNVERIFIED (n)` so verification behaves like an inbox.

**`LOCAL MESH ONLY` master switch** at the top: hard-disables the relay and every internet path; the RELAY glyph goes struck app-wide.

**Permissions block** (only when missing), plain language: "Nearby devices: find peers over Bluetooth. Android also requires location permission for BT scanning; we never read your location for this." Inline grants.

**Power row.** Per-radio battery estimate + `Battery saver` (longer scan intervals, LoRa listen-only windows).

---

## 7. Map and location sharing

**Cartography.** Custom MapLibre style, Day (white land, black ink, 0.5px roads) and Night (true-black land, white ink). POIs and brand labels off. Water 8% fill + hairline shoreline. Contours on where available — terrain reads better than shading in mono.

**Markers.** Self = filled dot + heading cone. Peers = monogram puck; hairline ring unverified, solid verified; accuracy as hairline halo; stale position = dashed ring + `12m ago`. Cluster counts in tabular figures.

**Radar fallback.** With no offline tiles for the area, do not show a grey void — switch to concentric distance rings with peers plotted by bearing and distance, device heading up. Usable on LoRa-only with no tiles. Manual map↔radar toggle always available.

**Offline regions.** `Download area`: frame the area, see `~48 MB · z10–14`, download with progress, then manage a list (`3 regions · 240 MB`). Unmetered-only by default.

**Sharing sheet (per conversation).** `Share location` → duration `15m / 1h / until I stop`; precision `Exact` or `~1 km`, where coarse snaps to a grid cell and the map draws that cell so the user sees exactly what the peer sees. No global always-on sharing.

**Persistent bar while sharing:** `SHARING · 2 peers · 42:10` (bold + tabular) with stop button. No inversion — that stays SOS-only.

**SOS on map.** Pulsing ring, top layer, never clustered. Tap → bearing + straight-line distance (`134° · 1.2 km`) and `Follow`, instead of fake offline turn-by-turn.

**Own breadcrumb trail:** local-only, toggleable, for backtracking. Never transmitted.

---

## 8. SOS

Four states, each a full screen. Cancelling must always be easier than firing.

**1. Arm.** Hold the dock 1.5s: ring fills, haptics ramp in three ticks. Release early = abort with `SOS cancelled`. Accessibility alternative: double-tap → explicit `ARM SOS` button (no timed hold).

**2. Countdown (10s).** Full-screen inversion, screen wakes, brightness forced to max. Tabular `10…0`, one line `Sending to 3 verified contacts + nearby mesh`, full-width 88dp `CANCEL` (single tap). TalkBack announces at 10/5/3/1 as an assertive live region.

**3. Active.** Stays inverted. Elapsed timer, `beacon #7 · LoRa+BT`, ack list as monograms (`2 of 3 acked`), battery %, `Add note` (60-char cap = one LoRa frame), `Call emergency services` (manual dial only), `STOP SOS` behind a 2s hold. Ongoing non-dismissible notification, full-screen intent when locked. Cadence: every 30s for 5 min, then 2 min. Below 10% battery: LoRa-only, 2-min cadence, banner explains why.

**4. Receiving.** Full-screen inverted alarm on the alarm channel so it pierces silent/DND (permission requested during onboarding with honest copy). Shows sender monogram + verified state, coarse location, `134° · 1.2 km`, and `ACKNOWLEDGE` (acks and relays onward) / `OPEN MAP` / `MESSAGE`. When relaying someone else's beacon: hairline chip `relaying · 2 hops`.

**Payloads.** Full detail (identity, location, note, battery) E2E to verified contacts. Minimal signed beacon (`distress`, coarse location, timestamp, public key) readable and relayable by any nearby node — a Settings toggle, default on, with copy explaining exactly what leaves the device.

**False alarm.** On stop: `Tell recipients this was a false alarm?` sends a signed cancel beacon; recipients see `SOS cancelled by <fingerprint>`. `Test SOS` in Settings targets only your own devices.

---

## 9. Identity, settings, onboarding, empty states

**Identity.** Ed25519 keypair generated on first run. Peer identity = display name + 4-word fingerprint (`anchor · drift · lantern · nine`). Two states only: unverified (hairline ring) and verified (solid ring + check). Verify via QR face-to-face or by reading the four words over an existing channel. Display names are never unique and never silently merged.

**Settings (list rows, hairline dividers, no cards):**
- **Identity** — monogram, name, fingerprint, `Show my QR`, `Scan to verify`, `Back up identity` (passphrase-encrypted), `Rotate identity` (destructive; resets all verifications).
- **Privacy** — default disappearing timer, block screenshots, read receipts (off), typing indicators (off), `Open distress beacon` (on), `App lock` (biometric/PIN + auto-lock), `Panic wipe` (duress PIN wipes keys + DB, then opens a clean empty app).
- **Network** — relay URL, `Local mesh only`, LoRa device + region (`EU868 / US915 / AS923`, required pick — wrong region is illegal), battery saver, background-service explainer.
- **Storage** — DB size, media per chat, offline map regions, clear cache.
- **About** — version, licenses, and a `What this can't protect you from` page: traffic analysis by a relay operator, a seized unlocked phone, malicious peers you verified, RF direction-finding on LoRa.

**Onboarding — 4 screens, no account:** (1) what it is + honest limits; (2) create identity, write down your four words; (3) permissions in plain language, requested in context (location only when Map/BT scan first needs it, alarm-over-DND for SOS); (4) live peer scan with a guided first verification. `Skip` allowed everywhere, everything revisitable.

**Empty states carry instruction, never art:** Chats → `No conversations yet. Peers appear here when Wi-Fi or Bluetooth is on.` + `Open Mesh`. Map with no tiles → radar + `Download this area`.

**App lock:** mono keypad, tabular digits, no "forgot PIN" (no recovery by design), duress PIN wipes silently.

---

## 10. Code structure

Delete the dynamic-color branch in `ui/theme/Theme.kt`; add tokens M3 has no slot for:

```kotlin
data class MeshTokens(
  val hairline: Color,     // onSurface @14%
  val glyphActive: Color,  // 100%
  val glyphIdle: Color,    // 48%
  val glyphOff: Color,     // 32% + strike
  val alarmBg: Color,      // inverse surface — SOS only
  val meta: Color,         // 48%
)
val LocalMeshTokens = staticCompositionLocalOf<MeshTokens> { error("no tokens") }
```

Packages mirror future Gradle modules so the split is mechanical:

```
core/crypto      identity, handshake, sealed sender
core/transport   Transport interface + wifi / ble / lora / relay impls
core/mesh        routing table, store-and-forward, dedupe, TTL
core/data        Room (SQLCipher), DataStore, TTL sweeper
feature/{chats, chat, mesh, map, sos, settings, onboarding}
ui/components    ui/theme
```

```kotlin
interface Transport {
  val id: TransportId                       // WIFI, BLE, LORA, RELAY
  val state: StateFlow<TransportState>       // Off, Idle, Active(peers, throughput)
  val incoming: Flow<Frame>
  suspend fun send(frame: Frame): SendResult // Queued | Sent | Fragmented(n)
  val constraints: Constraints               // maxPayload, latency, dutyCycle
}
```

`constraints.maxPayload` drives the composer's byte counter — no hardcoded 200.

**Components first** (each with Day / Night / edge-case previews): `TransportHeaderStatus`, `SosDock`, `PeerMonogram`, `MessageBubble` + `DeliveryGlyph`, `MeshRadioCard`, `HairlineDivider`, `MonoKeypad`, `HoldToConfirmButton`.

**Navigation.** Nested graph per tab with its own back stack, `@Serializable` routes. SOS countdown/active are top-level overlay destinations so they render above any tab.

**State.** Per screen: immutable `UiState` + sealed `Intent`, `StateFlow` from the ViewModel, one-shot effects via `Channel`. The transport header status reads a shared `MeshStateRepository` through `derivedStateOf` so RSSI ticks don't recompose chat lists.

**Map.** `AndroidView` + MapLibre `MapView` bound to lifecycle; styles at `assets/styles/mono-day.json` and `mono-night.json`.

---

## 11. Accessibility

- Every state carries two cues: fill + label, fill + underline, or glyph + strike. Never fill alone.
- TalkBack: transport header status is one merged button (`Mesh: Wi-Fi active, Bluetooth active, LoRa not attached, relay off, 4 peers. Open mesh`). SOS dock is a button (`SOS, hold to arm`) with a custom `Arm SOS` action so holding is never mandatory.
- Targets ≥48dp; SOS 64dp with 12dp edge exclusion.
- `fontScale 2.0` is a supported layout — chat rows, transport header status, SOS countdown all verified at 200%.
- Reduce-motion: SOS pulse becomes static max contrast; crossfades become instant.
- Full RTL mirroring, including bubble alignment and radar bearings.

## 12. Testing

- **Roborazzi screenshots:** every component × {day, night} × {1.0x, 2.0x font}. Monochrome regressions are invisible in review — a 48%→60% opacity drift ships silently without them.
- **Compose UI:** arm→cancel timing on a virtual clock, byte counter driven by a fake `Transport.constraints`, offline `Queue` state, panic-PIN path.
- **Unit:** routing dedupe, TTL sweeper, disappearing timers, and a version-pinned golden test on the fingerprint wordlist — changing the word mapping silently invalidates every past verification.
- **Instrumented:** SQLCipher open/migrate; panic wipe asserts no plaintext remains on disk.

## 13. Known risks

- **Background execution.** BLE/Wi-Fi scanning under Doze needs a `connectedDevice` foreground service plus a battery-optimization exemption prompt. Set the expectation: mesh runs while the app is open or the service is active.
- **Wi-Fi Aware availability.** The dev device (SM-A225F, Android 13) may not support it — fall back to Wi-Fi Direct; verify on hardware.
- **Relay via `VpnService`.** Android allows one active VPN, so it conflicts with users' existing VPNs. Needs explicit UX.
- **LoRa.** Requires USB-serial or a BLE-bridged radio; regional duty cycles legally limit send rate.
- **BLE throughput.** Tens of kB/s — media sharing needs chunking and realistic progress UI, or should be Wi-Fi/relay-only.

## 14. Next steps

1. Strip dynamic color, add `MeshTokens`, build the component library with previews.
2. Build the shell: 4 tabs + SOS dock + compact transport header status + insets contract, wired to fake `MeshState`.
3. Chats and chat screens against a fake `Transport` (constraint-driven composer).
4. Mesh tab with real Wi-Fi/BLE transports; LoRa and relay behind the same interface.
5. Map with mono style + offline regions + radar fallback.
6. SOS end-to-end, including receive path and false-alarm cancel.
7. Identity, verification, panic wipe, app lock.

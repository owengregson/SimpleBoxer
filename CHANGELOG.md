# Changelog

## 0.7.0 — wall glue solved, potions, navigation & crit-spam

Boxers now collide, confirm teleports, and re-baseline byte-for-byte like a
vanilla client — the wall glue is fixed at its actual root and every
detect-and-recover hack is deleted. Potions genuinely throw (and heal, and
outlast the hotbar), navigation finds off-line stairs and keeps a human berth,
jumps time themselves to the boxer's real speed, and a roofed melee pocket
becomes a crit-spam opportunity. Everything still sits *upstream* of the move
input — exactly like a real player.

### Potions — they throw now
- **Real Instant Health II.** The seeded splash pots carry the upgraded healing
  stamp on every matrix version — resolved against the public `PotionMeta`
  interface (the old reflection targeted the package-private CraftBukkit class
  and threw `IllegalAccessException` on *every* version, silently degrading to
  effect-less "empty" pots), with era fallbacks: `STRONG_HEALING` →
  `PotionData(INSTANT_HEAL, upgraded)` → a custom effect. A failure now warns
  once instead of degrading silently.
- **Throws survive the spam gate.** A built `ServerboundUseItemPacket` left
  Spigot's anti-spam `timestamp` at zero, so a connection got exactly nine
  lifetime use-items before the server silently dropped every one — pots
  included. The emulator now stamps the packet the way the network decode
  constructor does, byte-identical to a real client's.
- **The whole inventory is the reserve.** `splash-pot-count` widens to 0–36:
  seeding overflows past the hotbar into main inventory, and the pot slot
  restocks from anywhere in it — a boxer can spend its entire inventory of
  pots over a fight, switching slots like a player scrolling to the next one.
- **Fluid, confirmed healing.** The heal routine throws on the run — yaw stays
  on the flee heading (the old routine aimed at its own feet, degenerate math
  whipping the crosshair to world-south while it stood still), the weave kites
  a bounded ring instead of drifting forever, and a pot only counts against
  the budget when its `ThrownPotion` actually spawns.

### Navigation — stairs, berth, and takeoffs
- **Off-line stairs are found and climbed.** Elevation plans go straight to the
  jump-capable search with a real budget and an adaptive extent (the old
  walk-only first pass returned a dead-end breadcrumb *under* the target that
  short-circuited the stair route; the old budget couldn't reach stairs ten
  cells away), gates measure true 3D distance (they used to switch off exactly
  at the dead end), partial routes lean toward the open frontier, and a
  committed climb is latched until the boxer actually gains the level — a
  waypoint is a floor cell, consumed only when stood on. Real stair blocks
  read as two half-steps, the way a client walks them.
- **A human berth.** Both planners charge a soft clearance surcharge and the
  steering ring carries lateral wall danger, so a boxer rounds obstacles a
  couple of blocks wide like a person instead of hugging the geometry — while
  corridors stay passable.
- **Speed-scaled jump timing.** The step-hop trigger is a time-to-contact
  takeoff window computed from the boxer's actual movement speed (the
  attribute now rides the perception line into the brain), so Speed I/II
  boxers leave the ground early enough to clear a block with momentum intact;
  route ascents schedule their takeoffs from the waypoint's step face.

### Combat — crit-spam under a roof
- **Roof-aware crit-spam.** With a solid ceiling three blocks up, a boxer in
  the melee pocket hops in pulsed jumps (the bonk cycle restarts the fall
  almost immediately), gates its attacks to the descending, crit-eligible
  half of each hop, and drops sprint for the click the way the 1.9+ attack
  path demands — real inputs, real packets, `combat.crit-spam` to taste.

### GUI
- **Four-door hub.** `/boxer` now opens exactly four doors — Spawn, Manage,
  Presets & Defaults, Plugin — with the standalone Reload button folded into
  Plugin Settings (which already carried it). The roster footer keeps its
  quick-spawn as the one deliberate duplicate.
- **One preset apply.** "Apply Preset" left the boxer panel; the single
  whole-preset apply lives on the settings hub, which serves live boxers,
  defaults and presets uniformly.
- **Recut categories.** Six intent-based pages — W-Tap merged into Combat,
  both strafe knobs together under Movement, and a new Potions & Healing page
  gathering the whole self-heal band and the pot supply, so enabling working
  potion healing is one page instead of two.
- **Hotbar layout editor.** The five hotbar-slot number tiles became one
  screen that shows the hotbar as itself: click a slot to cycle what it
  carries (weapon → rod → potions → food → blocks → empty); roles swap slots,
  so every tool always keeps exactly one slot.
- **Dependent knobs dim.** A knob whose master toggle is off — the rod range,
  w-tap timing, the heal band, pot count, eat threshold — renders grayed with
  a "requires …" note. Still clickable, so values can be staged in advance.
- **One hunger knob.** feed-hunger and natural-hunger collapsed into a single
  three-state cycle — pinned-full / natural / untouched (config keys are
  unchanged underneath).
- **Save as preset.** A live boxer's hand-tuned settings can be captured as a
  named preset straight from its settings hub — presets finally flow both
  ways.
- **Live screens.** The roster and each boxer's panel re-render every second
  while open, so paused/target/ping lore no longer goes stale.
- The settings hub computes its category tiles from the enum (a new category
  can no longer silently fail to appear), and ~9 dead GUI framework members
  were removed.

### Physics & protocol — wall-glue root fix
- **Fixed boxers gluing to walls at the root: the sim's bounding box now matches the
  server's bit for bit.** The glue was seeded by a 1.19e-8 width disagreement — the
  emulator halved `0.6` in double arithmetic while the server rebuilds every claimed
  position's AABB from float-promoted dimensions (`EntityDimensions.makeBoundingBox`:
  half-width `(double) (0.6f / 2.0f)` = `0.30000001192092896`, height `(double) 1.8f` =
  `1.7999999523162842`). Every flush wall rest therefore rebuilt server-side into a
  `(0, 1e-7]` penetration, which Paper 1.21.11+/26.x's strict full-cube collision collect
  classifies as a new collision and rejects **silently** (`CLIPPED_INTO_BLOCK` — not the
  warn-logged "moved wrongly" the 0.6.2 analysis blamed; vertical error is unconditionally
  zeroed before that check can fire), teleporting the body back to its pre-packet position
  every tick. `PLAYER_WIDTH`/`PLAYER_HEIGHT` now carry the float-promoted values, so a
  flush rest round-trips to penetration exactly `0.0` — a wall-pressed boxer is simply
  never corrected, on every matrix version.
- **Sweep clamp parity with Paper's `collideX/Y/Z`.** The axis sweep keeps the raw
  (possibly negative, down to −1e-7) gap instead of clamping at zero, reproducing the
  server's sub-epsilon back-out so a 1-ulp overlap self-heals within one tick exactly as a
  real client's collide would.
- **Vanilla-atomic teleport confirms.** On every `ClientboundPlayerPositionPacket` the
  boxer now adopts position+rotation+velocity unconditionally and answers with the vanilla
  pair — `AcceptTeleportation(id)` immediately followed by a `MovePlayer.PosRot` echo of
  the exact adopted position with `onGround=false` — so a correction round always ends
  with a delta-zero accepted move that re-baselines the server cleanly. Dead boxers
  (awaiting respawn) adopt before acking too, retiring the emulator's last
  ack-without-adopt site. 1.17.1-era teleport resends (a new id every ~20 ticks) ride the
  same path as fresh corrections.
- **Removed the 0.6.1/0.6.2 wall-glue recovery machinery wholesale** — the correction-loop
  detector, the adoption suppression, and the per-tick force-write of the server body onto
  the sim. Both mechanisms fought the correction stream by breaking the accept-teleport
  contract (acking positions the sim refused) and bypassing server authority with raw
  `Entity.setPos`; with the geometry fixed there is no correction stream to fight. The
  `-Dsimpleboxer.debug` wall traces stay (minus the retired `recover`/`ignore`/`streak`
  fields), and 0.6.2's `BukkitCollisionView` readability gating (unreadable cell = solid)
  **remains** — it is genuine client-sim/Folia conservatism, not part of the hack.
- **Debug forensics for movement rejections.** Under `-Dsimpleboxer.debug`, a reflective
  `PlayerFailMoveEvent` listener (modern Paper; skipped where the event class is absent)
  logs every rejection's gate (`failReason`) and both positions — the silent
  `CLIPPED_INTO_BLOCK` path is exactly how this bug hid, and a cancelled `PlayerJumpEvent`
  teleports the mover back through the same machinery. The wall integration tests
  additionally pin ZERO rejections for a wall-pressed boxer wherever the event exists.
- Deferred wire-fidelity follow-ups surfaced by the investigation (documented, not
  bundled): `ServerboundClientTickEndPacket` (1.21.2+; the server zeroes known-movement
  off it), per-idle-tick `StatusOnly` moves (the emulator flushes on a 20-tick idle
  cadence), `ROTATE_DELTA` rotation of kept velocity in 1.21.2+ teleports, and
  collision-view parity for the world border / hard entity colliders / context-dependent
  block shapes / sneak pose — latent one-off divergence classes, none of them this glue's
  seed. Two more, found during verification: the sim stamps `horizontalCollision` and
  zeroes axis velocity on exact `!=` where vanilla uses `Mth.equal`'s ~1e-5 tolerance
  (the sim flags sub-epsilon clamps a client ignores — cosmetic), and `MotorQuantizer`'s
  0.35 deadband can turn a steering-approved heading with a small ledge-ward component
  into a full diagonal key press, overshooting the point the ledge probe validated — a
  `!mayLeaveLedges` goal can still creep over a lip under sprint momentum.

## 0.6.2 — wall-glue fix, properly

Supersedes the 0.6.1 escape hack, which almost never fired (its "not descending"
detector was reset every few ticks by the boxer momentarily touching the floor,
and the real failure descends a full ~0.42/cycle — past its own threshold).

- **Fixed boxers gluing to walls — at the root.** Re-diagnosed with the live
  trace: the boxer's own collision finds the real floor correctly; the stuck one
  is the *server body*. A boxer that jumps into a wall is held at its jump apex
  (`floor + 0.42`, the jump strength — not a real ledge) because the server's
  anti-cheat rejects the wall-pressed descent and snaps the body back up every
  tick, and the emulator kept re-adopting that correction — an infinite
  oscillation. The boxer now recognises the correction *loop*, stops re-adopting
  the up-snaps, and drives the server body down onto the floor the sim already
  found, holding it there until the server accepts the position (the correction
  stream goes quiet) — so it slides down and settles instead of hanging.
- **Physics collision now respects region/chunk readability.** The collision
  reader gated block lookups the way navigation already does: an unreadable
  cross-region or unloaded cell near a seam is treated as solid rather than
  silently skipped, so the emulator can't fall through a floor the server holds
  on Folia.
- The `-Dsimpleboxer.debug` wall trace now reports server ground state and the
  recovery/streak counters.

## 0.6.1 — wall-glue fix

- **Fixed boxers gluing to walls.** The reported "sticks to a wall and won't fall"
  bug was a *server-side* correction loop, not a physics bug: when a boxer is
  airborne and pressed into a wall, a collision-shape disagreement between the
  emulator and the server (seen at chunk borders) made the server "moved-wrongly"-
  correct the boxer's fall **back up** to the same spot every tick, so it hung on
  the wall. The boxer now detects the non-descending airborne wall-contact and
  briefly drives straight off the wall to a position the server accepts, so gravity
  takes over again. (Confirmed from a live `-Dsimpleboxer.debug` trace; the debug
  line now also reports the escape countdown.)

## 0.6.0 — combat, navigation & fidelity upgrade

A follow-up to the 0.5.0 rework. Boxers move, fight, and survive more like a real
client: they slide off walls and are trapped by cobwebs, path in true 3D (up
stairs, down drops, across gaps — including to elevated targets), strafe
decisively and pick sides, throw a finite supply of splash potions fluidly, and
wear their kit like a real player. Everything still sits *upstream* of the move
input / action list / aim seams, so a boxer takes knockback and hit registration
exactly like a real player with the same state.

### Movement & physics
- **Cobwebs now trap boxers.** The client integrator models vanilla
  `makeStuckInBlock` (cobweb ×0.25/0.05/0.25, sweet-berry bush), so a boxer
  caught in a web is slowed exactly like a real client — it can no longer walk or
  hit through webs at full speed. Non-web motion is byte-identical.
- **Boxers slide off surfaces.** Pursuers walk *off* ledges toward their target
  instead of clinging to platform edges; they graze *along* walls when the goal
  points obliquely into one instead of snapping 90°; and they ease off near a
  hazard by crouching (the previously-computed slow-down is now actually applied).
  The collision integrator was already vanilla-faithful — it slides along walls
  and off ledges, now pinned by a dedicated test — so the old "sticking" lived in
  the steering layer above it, which is what changed.

### Pathfinding — Baritone-quality, server-side
- **A real 3D voxel A\*** (`BaritoneStylePlanner`) replaces the 2.5D local
  planner: traverse / diagonal / ascend / descend / multi-block fall, with a
  tick-scaled cost model ported from Baritone's `ActionCosts` and an admissible
  sprint heuristic. Baritone itself is a client mod and cannot run server-side —
  this is a clean-room reimplementation over the plugin's collision seam, so it
  spans 1.17.1 → 26.x with no bundled dependency.
- **Reaches elevated targets.** When a target sits on a platform reachable only
  by stairs or step-ups *off the direct line*, the boxer now discovers and takes
  that access route instead of stalling directly underneath it — a vertical
  heuristic term makes the search seek elevation, and a proactive elevation gate
  runs the planner before the boxer gets stuck.
- **Degrades gracefully** — an anytime partial path always heads toward the goal,
  and the whole search stays inside the readable region (Folia-safe).

### Combat
- **Predictive strafing that actually picks a side.** The old adaptive orbit
  could only hold or blind-flip a fixed side off an unsigned, dead-zoned signal.
  It now actively *chooses* a side from the opponent's signed aim-tracking rate
  and velocity, jukes to break a lock, and can time the change onto the w-tap
  sprint re-press. Named presets — `none` / `orbit` / `juke` / `wtap-sync` —
  replace the bare on/off toggle, and circle-strafe is more decisive at range.

### Survival & kit
- **Fluid potion play with a finite supply.** A `fill-splash-pots` toggle seeds
  the hotbar with a finite number of instant-health splash potions
  (`splash-pot-count`) that the boxer throws to heal and can genuinely run out of.
  The heal routine no longer stands robotically still — it throws while juking
  sideways in the cloud and starts aiming down a tick early so the pot lands at
  its feet.
- **Kits wear like a real client.** Armor chips on hit, weapons dull on attack,
  and a broken piece empties its slot — all via the vanilla durability paths, now
  that the `unbreakable-kit` toggle (default: wear) gates the old Unbreakable
  stamp. Locked/fixture kits stay unbreakable so calibration dummies spar forever.

### Notes
- The "boxer glued to a wall" report was investigated at length: the client
  integrator is proven to slide down walls in every synthetic and in-server case
  we could construct, and it is not a server position-correction. Debug forensics
  (`-Dsimpleboxer.debug=true`) now log the sim-vs-server position on wall contact
  to pinpoint any remaining case from a live session.

## 0.5.0 — the boxer rework

A ground-up rework of how boxers think, move, fight, and survive. The client
physics integrator, the packet-perfect wire, and the latency model are unchanged;
everything new sits *upstream* of the move input, so a boxer still takes knockback
and hit registration exactly like a real player with the same state.

### Movement & pathfinding
- **Fixed sticky walls.** Boxers no longer grind to a halt against a wall. A
  context-steering layer slides them *along* obstacles instead of pressing into
  them, and a bounded local path planner routes them *around* walls and pillars
  when reactive steering is trapped — active, situation-aware navigation, not a
  straight line into geometry.
- **Fixed one-block-step jumps.** A proactive jump fires *before* contact, while
  horizontal momentum is intact (a real bunny-hop), so boxers reliably clear
  single-block steps instead of stalling against the face and sliding back down.
- **Fixed circle-strafe.** It now holds a real orbit radius band instead of
  spiralling straight into the target; strafing is genuinely tangential and still
  sprints, using a duty-cycled digital keyboard (never a fractional impulse).

### AI — a modular, weighted-goal brain
- The monolithic decision code is replaced by a **utility-AI brain**: a set of
  scored *goals* (engage, circle-strafe, retreat-to-heal, rod-poke, seek-food, …)
  each weighing situational *considerations* (health, distance, how hard the
  opponent is tracking you, hunger, terrain), arbitrated with dwell/commit
  hysteresis so the boxer commits to a decision instead of dithering. New
  behaviours are one class in a registry — the "runs routines" architecture.
- **Adaptive strafing** (opt-in): the boxer reads how the opponent is aiming at
  it and flips to break a tight track or holds to exploit a mistrack.
- **S-tapping** (opt-in): straight-line combos with a sprint reset and no A/D strafe.
- Deterministic: identical boxers make identical decisions (seeded, no wall-clock
  in the decision path).

### Combat techniques (opt-in)
- **Fishing-rod knockback:** swap to a rod to knock an approaching target back,
  then swap back to the weapon for the combo.
- **Blockhit combos:** tap a sword-block in the gaps between hits (Mental reads it
  to re-arm the sprint bonus).
- **Splash-pot self-heal:** a low-health mortal boxer disengages, retreats,
  splashes instant-health at its feet until it recovers, then re-engages.

### Survival
- **Proper invincibility.** A burst larger than current health no longer kills the
  boxer before it heals — an otherwise-lethal hit is capped the same tick (staying
  above zero, so knockback still lands) and health is topped up. No more burst-kill.
- **Death, drops, and manual respawn.** The default boxer is now mortal: it dies,
  drops its items, and stays down until you respawn it (`/boxer`… or the API).
  `MORTAL_RESPAWN` (auto-respawn in place) and `INVINCIBLE` are opt-in modes; the
  `dummy` preset stays an invincible punching bag.
- **Hunger + food:** boxers can get hungry (opt-in) and eat.
- **Real inventory + pickup:** boxers keep a standard inventory, swap hotbar slots,
  and can pick up items (opt-in) without clobbering their kit.

### Integrations
- **Respects clientside velocity mods.** A server-side `setVelocity` (StarEnchants
  launches/knockback, Mental's delivery, vanilla) now moves a clientless boxer —
  previously lost on modern Paper. All received velocity flows through one ranked,
  deduplicated resolver so a single knock is never applied twice.
- **Greater Mental compatibility** across its knockback delivery and sword-blocking.

### Config & GUI
- `BoxerSettings` grew survival/combat/item/hunger sub-records (append-only; the
  config round-trip stays pinned). A new mortal, technique-using `sweat` preset
  showcases the rework.
- The in-game GUI is rebuilt around a descriptor-driven, categorized settings
  surface so every new knob is editable in-game.

### Notes
- Advanced item-use packets (rod/potion/eat use, blockhit) target modern Paper
  (1.20.6+); older versions degrade gracefully and report it. Core fixes (physics,
  pathfinding, AI, invincibility, velocity capture) work across the whole
  1.17.1 → 26.x range.
- When a boxer stalls at an obstacle it can't hop, it now routes *around* it
  (walk-only stall recovery) rather than freezing. Momentum step-jumps land on
  1.17.1 → 1.21.x; on the bleeding-edge 26.x preview the stricter server-side
  movement validation rejects the jump-climb itself, so there the boxer takes the
  walk-around instead. The whole matrix is validated in-server; the step-jump and
  the walk-around are unit-pinned.

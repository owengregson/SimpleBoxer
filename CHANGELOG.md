# Changelog

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

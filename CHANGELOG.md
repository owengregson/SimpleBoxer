# Changelog

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

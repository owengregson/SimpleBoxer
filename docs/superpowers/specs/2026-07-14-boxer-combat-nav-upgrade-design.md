# SimpleBoxer — combat, navigation & fidelity upgrade (design)

Follow-up to the 0.5.0 boxer rework. Six independent workstreams that make
boxers move, fight, and survive more like a real client, plus a ground-up
pathfinding upgrade. Everything sits **upstream of the move input / action
list / aim seams** — the packet-perfect wire, the client physics integrator's
core collision math, the latency model, and the player-identity principle are
untouched, so a boxer still takes knockback and hit registration exactly like a
real player with identical state.

## Goals

1. **Surfaces feel vanilla** — boxers slide along walls and off ledges instead of
   sticking, and are correctly slowed/trapped by cobwebs (can't move or hit
   through them at full speed).
2. **Pathfinding is Baritone-quality** — a real 3D voxel A* that routes around
   concave obstacles, up stairs, down drops, and across gaps, as the *primary*
   approach controller, not a rare stuck-rescue. Reimplemented server-side
   (Baritone cannot be bundled — see §Baritone).
3. **Potion play is fluid + finite** — the heal routine throws while moving/juking
   instead of standing robotically, and a config toggle seeds a *finite* splash-pot
   hotbar that actually depletes.
4. **Predictive strafing works** — it actively *chooses* a side from how the
   opponent aims/moves, strafes decisively, and ships new named presets.
5. **Kits wear like vanilla** — armor on hit, weapon on attack, break→empty, behind
   a toggle that defaults to wear for normal boxers and keeps fixtures unbreakable.

## Non-goals

- No bundling of Baritone or any client mod (infeasible; see §Baritone).
- No global/cross-region maze solving — navigation stays region-bounded (Folia).
- No change to the client-physics constants pinned in
  `docs/research/2026-06-06-client-motion-pins.md` beyond adding cobweb handling.
- No new death/persistence semantics; boxers stay ephemeral fixtures.

## Cross-cutting invariants (every workstream must honor)

- **Player identity** — nothing may change how a boxer takes knockback or hit
  registration vs a real player with identical state. All work is upstream of the
  three brain seams (`MoveInput`, aim angles, action list).
- **Owning-thread only** — all brain/perception/inventory writes happen on the
  boxer's owning thread inside `tick()`; `BukkitCollisionView` stays single-caller;
  no new cross-region block reads (Folia).
- **Determinism** — any new randomness draws from `BrainMemory.rng` (seeded), never
  `Math.random`/`nanoTime`; `common` unit tests pin behavior against synthetic
  `CollisionView`s.
- **Config round-trips** — `parse(empty) == DEFAULTS` and `parse(write(s)) == s`
  stay green; the settings schema is append-only (extend existing sub-records,
  don't add top-level positional fields).
- **Integration matrix** — the per-version suite (1.17.1 → 26.x) and the Mental/OCM
  coexistence legs must still pass; new behavior gets tester coverage where it
  crosses the wire.

---

# Workstream 1 — Surface fidelity: sticking, cobwebs, jump-into-wall glue

Three *distinct* surface defects with three different root causes. 1a is horizontal
(steering); 1c is vertical (the sim↔server boundary) and is a genuine, separate bug —
the collision integrator itself is faithful (proven by a repro battery), so the "no
gravity on the wall" glue lives at the packet/server-validation boundary, not in the
math.

## 1a. Horizontal "sticking" (slide along walls / off ledges)

**Root cause (confirmed):** `ClientPhysics.collide()` is already vanilla-faithful —
per-axis sweep (Y → larger-horizontal → smaller-horizontal), zeroing only the
blocked axis, so it *does* slide. The horizontal sticking is produced one layer up in
`common/brain/ContextSteering.java`:

- **Unconditional, goal-blind ledge penalty** (`ContextSteering.java:76,128`;
  `LEDGE_PENALTY=5.0`) — a *pursuing* boxer refuses to walk off an edge toward its
  target and paces the rim. This is the clearest "sticks to the surface" defect.
- **Pre-contact wall deflection** (`:32,122`; `WALL_PENALTY=10.0` at coarse
  `LOOK_AHEAD=0.55`) — the boxer turns ~90° to walk *along* a wall instead of
  holding its heading and letting the (correct) physics graze-slide.
- **The intended soft ease-off is dead code** — `MoveHeading.speedScale` and
  `nearLedge` are computed but **dropped**: `Brain.tick` calls
  `motor.toInput(heading, aim.yaw(), sprint, jump, /*sneak=*/false)` and
  `MotorQuantizer` never reads `speedScale`. So "ease off near a hazard" is actually
  "hard-avoid the hazard."

**Design:**

1. **Goal-conditional ledge avoidance.** Thread a `mayLeaveLedges` flag from the
   winning `Goal`/`Intent` through `Brain.resolveHeading` into
   `ContextSteering.steer`. Pursuit/engage goals (approaching a target) zero the
   ledge cost — they walk off edges like a real client. Flee/heal/retreat goals keep
   it (a fleeing boxer still must not sprint off a cliff, per
   `MoveHeading` javadoc). Add a `boolean mayLeaveLedges()` (default `false`) to the
   `Goal` interface; `EngageGoal`/`RodPokeGoal` return `true`, survival goals `false`.
2. **Oblique wall pass-through.** When the desired heading is blocked but a heading
   within ~±45° of it is clear, keep the *nearest-to-desired* clear candidate rather
   than snapping to the pure perpendicular; scale/loosen the slide-decision
   look-ahead by speed so a candidate that merely grazes the wall isn't rejected —
   let `ClientPhysics.collide` finish the grazing slide. Keep `WALL_PENALTY` only for
   genuinely unclearable walls near-normal to travel.
3. **Consume the ease-off.** Wire `MoveHeading.speedScale`/`nearLedge` into
   `Brain.tick`: duty-cycle the digital forward when `speedScale < 1` and press
   `sneak` when `nearLedge`, converting the binary avoid into the intended soft
   slow-down (matches a vanilla player edging along a rim). Removes latent no-ops.

**Do not touch:** `ClientPhysics.collide/sweep`, the step-up block, `STEP_HEIGHT`,
`EPSILON`. Add a **new physics pin** `diagonalIntoWallPreservesParallelVelocity`
so the slide is locked independently of the brain.

**Tests to keep green:** `ContextSteeringTest.openFloor_desiredEast_headingStaysEast`,
`wallDueEast_desiredEast_deflectsToSlideAlongWall`, `desiredZero_returnsStill`,
`AntiStuckTest` detour-speed pin, plus new pursuit-walks-off-ledge and
oblique-wall-slide steering tests.

## 1b. Cobwebs

**Root cause:** `ClientPhysics` explicitly doesn't model cobwebs
(`ClientPhysics.java:16`), and `CollisionView` exposes only collision boxes +
slipperiness. A cobweb has **no collision box**, so the boxer can't see it, isn't
slowed, and moves/attacks through it at full speed while a real client is trapped.

**Design:**

1. **Extend `CollisionView`** with a block-motion query — a small, well-bounded seam:
   ```java
   /** Vanilla "stuck in block" motion multiplier at a block, or null for none.
    *  Cobweb ≈ (0.25, 0.05, 0.25); default view returns null (no special block). */
   Vec3d stuckMultiplier(int blockX, int blockY, int blockZ);
   ```
   `BukkitCollisionView` implements it (owning-thread, region-bounded read of the
   block type / material); synthetic test views default to `null` so existing tests
   are unaffected. (This is also the seam the pathfinder's `isReadable` extension
   uses — WS2 — so both land on one interface change.)
2. **Apply vanilla web physics in `ClientPhysics.step`.** Before the drag stage, if
   the player box overlaps a web block, clamp velocity by the multiplier
   (`vx*=0.25, vy*=0.05, vz*=0.25`) exactly as vanilla `Entity.makeStuckInBlock`.
   Model it as the vanilla `stuckSpeedMultiplier` override so acceleration/drag order
   matches. Pin the terminal in-web speed with a hand-computed unit test.
3. **Combat consequence falls out for free** — once the boxer is physically slowed in
   the web, it no longer closes/hits at full cadence ("hitting through" webs stops
   because the boxer is actually stuck, like a real client). No combat-code change;
   verify with an integration/arena scenario.

**New tests:** `ClientPhysicsTest.webClampsHorizontalAndVerticalSpeed`,
`webTerminalSpeed` (hand-computed pin); `CollisionView` fake gains a web cell.

## 1c. Jump-into-wall vertical "glue" (integration boundary — not the integrator)

**Symptom:** a boxer that jumps into a wall *sometimes* sticks to the wall face and
stops falling ("glued", no gravity).

**Investigation to date (systematic-debugging Phase 1):**
- A repro battery drove pure `ClientPhysics` into five wall/jump geometries (tall wall,
  platform-lip with a pit, overhang, step-then-wall, corner pocket). **All five fall and
  land correctly** — the integrator is *not* the cause. (Scratch test deleted; its result
  is captured here and will be re-pinned as a keeper.)
- The move packet faithfully reports the falling sim position + `horizontalCollision`
  flag (`BoxerImpl.queueMovement:761`, `dispatch:775`) — the sim tells the server it's
  falling.
- Therefore the glue is at the **sim↔server boundary**: the server's `handleMovePlayer`
  processing of a *clientless* fake player's move-into-wall. **Prime suspect:** a
  server-side collision / "moved-wrongly" correction (`ClientboundPlayerPositionPacket`)
  that `route()` applies as `physics.teleport(...)` at `BoxerImpl.java:517-522`, snapping
  the sim back up; repeated corrections hold it on the wall. The delayed move-packet
  pipeline (simulated ping) makes it timing/depth dependent → "sometimes." (Secondary
  suspects: the `doTick`/`reanchorServerPosition` interaction; the client-loaded gate
  dropping movement.)

**This is a real bug but not yet root-caused** — the failing boundary is live NMS
move-validation, not unit-testable in `common`. Do **not** guess a fix.

**Plan:**
1. **Instrument the boundary** (behind the existing `simpleboxer.debug` flag): when
   `physics.horizontalCollision()` is true, log per tick the sim `(x,y,z,vy)`, the server
   entity `(x,y,z)`, and every inbound `PositionSync`/teleport correction applied at
   `route()`. One live run of a boxer into a wall reveals *which* boundary breaks (is the
   server teleporting it back up? is the entity frozen while the sim falls?).
2. **Add a `MovementSuite` integration test** — spawn a boxer, place a wall, target a
   point past it, make it jump into the wall, assert its Y returns to the ground within N
   ticks. This reproduces in the real NMS environment where the bug lives and becomes the
   regression pin.
3. **Fix at the identified boundary** (e.g. suppress/ignore self-inflicted server position
   corrections while the sim is legitimately wall-collided and falling; or correct the
   reanchor to preserve the sim's vertical) — chosen only after step 1 shows the evidence.
4. Add the keeper `ClientPhysicsTest` pin that the integrator itself falls off/along walls
   (the deleted battery, distilled to assertions).

---

# Workstream 2 — Baritone-quality pathfinding (server-side A*)

## Baritone feasibility (decision record)

**Baritone cannot be bundled.** It is a client mod: SpongePowered-Mixin-injected
into the Minecraft *client*, and even `baritone-api` imports `net.minecraft.client.*`
(`Minecraft`, `LocalPlayer`, `ClientLevel`). Builds are pinned one-per-MC-version
with **no build past 1.21.11** — nothing for the plugin's 1.22+/26.x range. The
maintainers closed "run headless" as won't-fix. Automatone (Fabric mod, MC 1.18/1.20)
and ZenithProxy (from-scratch reimplementation over a packet-level world model) confirm
the only viable path: **reimplement the algorithm server-side.** Baritone/Automatone are
LGPL-3.0 — used as *algorithm references only*; no code is copied (avoids LGPL relink/
source obligations). Sources archived in the research notes.

## Current state

`LocalPathPlanner` is a thin 2.5D bounded A* (one floor-Y per `(x,z)` cell, 8-connected,
octile heuristic, binary jump/walk edges, `MAX_EXTENT=10`, `PLAN_BUDGET=400`). It is
invoked *only* as a stuck-rescue: `resolveHeading` calls it after `AntiStuck.shouldReroute`
(12 consecutive stuck ticks) **and** `distance>2.5` **and** `isApproaching`
(`Brain.java:208-219`). So 99% of ticks the boxer is driven by greedy, memoryless
`ContextSteering` with 0.55-block look-ahead. Concave/U-shaped traps are the headline
failure; multi-block gaps, true verticality, and long walls are unsupported.

## Target design: `BaritoneStylePlanner`

Keep the seam identical, upgrade the graph + cost model behind it. Drop-in signature
(unchanged so `Brain.planRoute`/`followRoute` and the whole motor stack are untouched):

```java
Optional<List<Vec3d>> route(Vec3d start, Vec3d goal, CollisionView world,
                            int budget, boolean allowJump)
```

- **3D voxel graph.** Node = feet block `(x,y,z)` packed into a `long` (extend the
  current 2-int key to 3D, e.g. 26/12/26 bits). Standability reuses
  `NavGeometry.groundHeight`/`playerBox`/`collides` so nodes still snap to real terrain.
- **`MovementType` enum** (mirrors Baritone's `Moves`, minus mine/place):
  `TRAVERSE`, `DIAGONAL`, `ASCEND` (+1, jump onto step), `DESCEND` (−1),
  `FALL` (dynamic-Y drop to first ground within a `maxFall` cap), `PARKOUR`
  (dynamic-XZ 2–4 sprint-jump gap, arc head/feet clearance + landing standable).
  Omit `PILLAR`/`DOWNWARD` — boxers don't modify arena geometry, and omitting them
  keeps every read a pure collision query (Folia-friendly).
- **Tick-scaled cost model.** Port Baritone's `ActionCosts` constants + the
  physically-derived `distanceToTicks` into a pure `common.brain.MoveCosts`
  (plain doubles; hand-computed unit pins in the codebase's existing style):
  `WALK≈4.633`, `SPRINT≈3.564`, `WALK_OFF≈3.706`, `CENTER_AFTER_FALL≈0.927`,
  `FALL_N_BLOCKS[]`, `JUMP_ONE_BLOCK`. Per-edge cost = base per-block (sprint when
  legal) + jump/fall penalties. Reconcile against `ClientPhysics` (`STEP_HEIGHT=0.6`,
  sprint mult 1.3) so costs match the integrator the boxer actually uses.
- **3D tick-scaled heuristic (horizontal + vertical).** `h = octile_horizontal ·
  SPRINT_ONE_BLOCK + verticalTerm`, where `verticalTerm` for an *upward* goal is
  `Δy_up · JUMP_ONE_BLOCK_COST` (you cannot gain height without paying jump arcs) and
  for a *downward* goal is ~0 (falling is cheap). Baritone's `GoalBlock` is exactly
  `GoalXZ + GoalYLevel` for this reason. The vertical term is what makes the search
  actively *seek elevation* toward a raised target and — critically — stops the
  `bestSoFar` partial from stalling **directly under** an out-of-reach platform
  (horizontally close but a dead end). The vertical term is mildly inflated where a
  diagonal-ascend covers both axes at once, so the search is bounded-suboptimal
  (weighted-A*, like Baritone) rather than strictly optimal — an acceptable, deliberate
  trade that favors elevation-seeking and search speed. Flat/downward goals keep the
  admissible pure-horizontal behavior and jumpy routes still correctly lose to flat
  sprint routes.
- **Anytime `bestSoFar` partial path.** Track the node minimizing `h` (optionally
  Baritone's `h + g/coeff` set for tunable greediness). On budget/bound exhaustion,
  reconstruct from `bestSoFar` instead of returning empty (today's empty-on-exhaustion
  is a strict regression vs Baritone). Only return a partial if it advanced ≥ ~2 cells,
  else cleanly fall back to reactive steering.
- **Caching + incremental replan.** Reuse `memory.path`/`pathCursor`/`lastGoalCell`.
  Add cheap per-tick re-validation of the next 1–2 waypoints (invalidate on block
  change/perception drift) and segment plan-ahead when the target is outside the box.
  Optionally make the search resumable across ticks (persist `open`/`closed`/`gScore`
  in `BrainMemory`, expand ≤ budget nodes/tick) for larger radii — never blocks.

## Folia safety

Only world access is `CollisionView` on the owning region thread. Two layers keep the
search inside the readable set:

1. Keep the `MAX_EXTENT` box (+ a new Y-band) comfortably inside a region.
2. **Extend `CollisionView` with `boolean isReadable(int x,int y,int z)`** (default
   `true` for synthetic views; core returns `false` for cells outside the current
   region / unloaded chunk *without* calling into another region). The planner gates
   every neighbor cell (footprint + head + fall column) through `isReadable`;
   unreadable = no edge = treated as a wall. Search halts at the loaded/region
   frontier and returns a `bestSoFar` partial that lives entirely in readable space.
   Segmentation gives cross-region *traversal over time* with no cross-region *read*.

## Handoff to the reactive motor (range-gated primary)

Promote the planner from stuck-rescue to **primary approach controller** in
`Brain.resolveHeading`:

- **Path (transit) when** `distance > engageRange` (reuse `closingFromAfar`, ideally
  the settings engage radius) **and** `isApproaching` — plan/follow waypoints across
  terrain (walls/stairs/gaps).
- **Strafe/orbit when** `distance ≤ engageRange` — `clearPath()` and hand the desired
  direction straight from `EngageGoal`/`AdaptiveStrafe` into `steering.steer`. The
  reactive duty-cycle owns the pocket; pathing a 1–2 block orbit would be wasteful/jittery.
- **AntiStuck stays** as the in-segment escalation (lateral detour → reroute), now
  layered under a planner that's the default approach path rather than its only trigger.
- Waypoints follow the existing path: `followRoute` → `steering.steer(p, toWaypoint)` →
  `ProactiveJump` → `MotorQuantizer`. Optionally enrich to `record Waypoint(Vec3d pos,
  MovementType via)` so ASCEND/PARKOUR waypoints signal `ProactiveJump` to pre-jump;
  the plain `Vec3d` list stays the default so nothing downstream must change day one.

## Phasing (internal to WS2)

- **Phase 0** — seams & constants (low risk): add `CollisionView.isReadable`
  (+ `stuckMultiplier` from WS1 on the same interface change), port `MoveCosts` with
  unit pins, extend the cell key to 3D. No behavior change.
- **Phase 1** — MVP 3D A* (TRAVERSE/DIAGONAL/ASCEND/DESCEND/FALL) behind the flag; A/B
  against the current planner on the same synthetic scenes + in-arena, then default it.
- **Phase 2** — PARKOUR + resumable/segmented search + promote to range-gated primary
  approach controller; enrich waypoints for pre-jump.
- **Phase 3** (optional) — favoring/soft-avoid costs, incremental repair, binary-heap
  open set; retire `LocalPathPlanner` at parity.

**Tests:** flat sprint, wall detour, concave U-trap escape, single step-up (ASCEND),
descending staircase, pit (FALL), gap-with-no-parkour (routes around), gap-with-parkour
(Phase 2), region-frontier (unreadable → in-bounds partial), determinism/seed pins.
Dead code to retire while here: `NodeKind.STEP`, `BrainMemory.climbTicks`.

---

# Workstream 3 — Predictive strafing + new presets

**Root cause:** "Predictive" = `AdaptiveStrafe.adaptiveOrbit` (`:110-131`). It can only
**hold or blind-flip a fixed initial side**, keyed off `oppTrackRateDegPerTick`, which is
an **unsigned** magnitude (`abs`, `BoxerImpl.java:573`), **dead-zoned** at 3°/tick, and
**dwell-gated** — so it never actually *chooses* a side, and under calm aim never jukes.
It also only takes effect in `STRAFE_CIRCLE` style and is gated off entirely beyond
~4.25 blocks (`EngageGoal.orbit():117`). The signed signals that would let it choose
(`TargetState.bearingToMeYaw`, target `velocity()`) exist but are **dead code**.

**Design:**

1. **Rewrite `adaptiveOrbit` to choose a side each tick** (hysteresis to avoid chatter):
   - **Signed opponent aim error** — add a `signedTrackRateDegPerTick` to
     `TargetState` (compute `wrapDegrees(view.yaw()-prevTargetYaw)` *without* `abs` in
     `BoxerImpl.buildPerception`), and start consuming `bearingToMeYaw`: strafe toward
     the side the opponent's crosshair is *not* covering / is lagging.
   - **Opponent velocity** (`TargetState.velocity()`, already populated) — juke to the
     side that opens the angle relative to their motion.
   - **Wall-open side** — pick the clearer of ±tangent via `NavGeometry.wallAhead`/
     `ledgeAhead` (or by scoring both through `ContextSteering`) *before* colliding,
     instead of only flipping on contact.
   - **W-tap sync** — time the side change to the sprint-reset re-press
     (`BrainMemory.wtapCountdown`/`wtapReleaseLeft`, already present) so juke + fresh
     sprint knock land together.
2. **Strafe more decisively** — raise the in-band tangential authority (reduce the
   forward-bias dilution in `EngageGoal.orbit`) and widen the range gate so the boxer
   sidesteps sooner, not only inside 4.25 blocks.
3. **New named presets.** Replace the bare `Combat.adaptiveStrafe` boolean with a
   `StrafePreset` enum (e.g. `NONE`, `ORBIT`, `JUKE`, `WTAP_SYNC`) parsed/written like
   the aim presets; promote the `AdaptiveStrafe` tuning constants (`WEAVE_MIN/MAX`,
   `ORBIT_MIN/MAX`, `TRACK_THRESHOLD`, dwell/jitter) to per-preset parameters passed
   into `AdaptiveStrafe.next(...)`. Optionally add a new `Movement.Style` (e.g.
   `STRAFE_JUKE`). Update `DifficultyPresets` EXPERT/SWEAT to the new preset;
   keep round-trips green.

**Plumbing:** `TargetState` field touches the record + single producer + ~6 test
constructors; the preset enum threads `BoxerSettings.Combat` → parser/writer/GUI cycle
(`SettingsRegistry`) / `DifficultyPresets`. Behavior rewrite is confined to
`AdaptiveStrafe.adaptiveOrbit` (+ `EngageGoal.orbit` tangent selection). All owning-thread,
deterministic (draw from `mem.rng`). `AdaptiveStrafeTest.isDeterministicForAFixedSeed`
stays green; add tests for signed-aim side choice, velocity juke, wall-open side,
wtap-sync timing.

---

# Workstream 4 — Fluid potions + finite splash-pot hotbar

**Root cause:** `PotHealGoal` is a rigid FSM — swap slot → throw ONE pot at feet →
**stand motionless facing the ground 10 ticks** → repeat, all `exclusive` +
`suppressesAttack` with `Vec3d.ZERO` (~12 immobile ticks/pot, up to ~2s backpedal). And
"fails to use pots" because **nothing seeds the pot slot**: `syncKit()` writes only the 6
Loadout slots (`BoxerImpl.java:958-979`), so `hasPots()` is false and the routine never
arms. The throw itself works (`ActionIntent.StartUse` → `ServerboundUseItemPacket` →
`ThrownPotion`, decrements the stack — **finite depletion already works**). The `Intent`
model already carries move + facing + action simultaneously; the rigidity is purely in the
FSM.

**Design:**

1. **Seed a finite splash-pot hotbar (config toggle).** Add to the `Items` sub-record
   (append-only): `boolean fillSplashPots` + `int potCount` (config keys
   `items.fill-splash-pots`, `items.pot-count`; DEFAULT `false`/`0` so round-trips hold).
   Add a `seedConsumables()` step at the owning-thread `syncKit()` seam (same
   `loadoutDirty` branch, re-applied on respawn) that writes a `SPLASH_POTION` stack of
   `potCount` **instant-health** `PotionMeta` into `items.potSlot()` (and adjacent slots
   for a larger reserve). Do **not** route it through `durable()` (consumables deplete by
   count — that IS "can run out"). GUI: a toggle + integer descriptor under ITEMS.
2. **Make the routine fluid.** Rework `PotHealGoal.decide` to emit non-`ZERO` `Intent`s —
   keep repositioning/juking while throwing and during the cloud wait; overlap the wait
   with movement; start pitching the crosshair **down a couple ticks before** the
   `StartUse` (the `AimSpring` chases pitch gradually, so throwing on the same tick it
   aims down sends the pot out shallow); pre-seat the pot slot as held so no per-throw
   swap handshake is needed (fastest fusion — avoids widening `Intent.action`); make the
   goal interruptible rather than a hard `exclusive` lock where safe.
3. **Reconcile the two limiters** so the *inventory* is the real limit: drive give-up off
   `hasPots` (empty inventory) rather than the fixed `splashCap`, or set `splashCap ≥
   potCount`. A boxer that runs out re-engages weaponless-of-pots naturally.

**Files:** `PotHealGoal.java` (fluidity), `BoxerImpl.java` (`syncKit`/seed +
`inventoryView`), `BoxerSettings.java` + parser/writer + `SettingsRegistry` + `config.yml`
(the new `items` fields), `BoxerSettingsWriterTest` (add args). All inventory writes stay
owning-thread behind `loadoutDirty`. New goal tests for move-while-throw, early aim-down,
run-out→re-engage.

*(Offensive splash pots were considered and deferred — heal-only per the chosen scope.
The seed/throw machinery generalizes cleanly to an offensive goal later if wanted.)*

---

# Workstream 5 — Kit durability

**Root cause (one line):** `BoxerImpl.durable()` stamps `setUnbreakable(true)`
(`BoxerImpl.java:988`) on every kit piece. All vanilla durability paths already run for a
real `ServerPlayer` — armor-on-hit (via uncancelled `hurt()`), weapon-on-attack (the real
`ServerboundInteract` → `Player.attack` → `hurtEnemy`), and break→empty-slot — they're just
nullified there.

**Design:**

1. **Gate the Unbreakable stamp.** Add `boolean unbreakableKit` to the `Items`
   sub-record (config key `items.unbreakable-kit`, **DEFAULT `false`** = wear).
   `durable()` stamps `setUnbreakable(true)` only when `items.unbreakableKit() ||
   items.lockLoadout()` — so a *locked* kit (already a tireless fixture) stays
   unbreakable, and an operator can force unbreakable on any boxer explicitly, but a
   normal boxer wears its gear. Fixture-oriented presets (e.g. `DUMMY`, and any
   invincible/`AUTO_RESPAWN` tier meant to spar forever) set `unbreakableKit=true`.
   Once items are damageable, **no new damage code is needed** — vanilla handles
   armor/weapon/break entirely.
2. **Fix the re-apply clobber.** `syncKit()` rewrites slots from pristine `Loadout`
   clones, so an operator `equip()` mid-fight would reset worn durability. Guard it:
   compare the currently-worn `EntityEquipment` item against the kit item **ignoring
   damage** (same type/enchants/name) and **skip** the `setXxx` write when they match, so
   accumulated damage survives; write only when the slot is empty or the kit item changed.
   Respawn/handle-swap re-applies to pristine gear stay as-is (vanilla-correct — a
   respawned player gets fresh gear).

**Files:** `BoxerImpl.java` (`durable` gate + `syncKit` skip-on-match), `BoxerSettings.Items`
+ parser/writer/GUI/config.yml. No changes to `InvincibilityGuard`, `KnockbackListener`,
the NMS bridge. Tester: a boxer's weapon/armor accrues damage over a staged fight when the
toggle is on; a locked fixture's gear stays pristine.

---

# Sequencing & delivery

All five workstreams are largely **independent** and share only the settings/GUI plumbing
pattern and the brain seams — ideal for parallel implementation with adversarial review.
Proposed order (risk/independence ascending, on a `feat/boxer-upgrade` branch, each a
focused reviewable unit):

1. **WS5 Durability** — smallest, isolated; one gate + one skip-on-match.
2. **WS1 Surface fidelity** — steering ledge/wall + the `CollisionView` `stuckMultiplier`
   seam + cobweb physics (the `CollisionView` interface change also unblocks WS2 Phase 0).
3. **WS4 Potions** — self-contained goal + inventory seed + config.
4. **WS3 Strafing** — self-contained brain + preset plumbing.
5. **WS2 Pathfinding** — largest; delivered in its own internal phases (0→1 MVP first,
   then 2), consuming the WS1 `CollisionView` change for `isReadable`.

Each workstream ships with `common` unit pins and, where it crosses the wire, a `tester`
suite addition; the full integration matrix + Mental/OCM coexistence run before merge.
CHANGELOG + README + ARCHITECTURE.md "Known deferrals" updated as capabilities land
(the pathfinding deferral is retired for the region-bounded case).

## Risks

- **3D node blow-up** (WS2) — mitigated by Y-band + box + budget + `bestSoFar` + resumable
  search.
- **Jump execution timing** (WS2 Phase 2) — de-risked by enriched `Waypoint(via)` driving
  `ProactiveJump`.
- **Round-trip/preset test churn** (WS3/WS4/WS5) — every new field carries a DEFAULT/OFF
  and updates inline preset constructors + writer tests; pinned by the existing round-trip
  suite.
- **Fixture longevity** (WS5) — default keeps locked fixtures unbreakable so long-running
  test dummies don't self-destruct.
- **Behavior regressions in steering** (WS1) — new pins for pursuit-off-ledge and
  oblique-wall-slide lock the intended behavior; physics collide math is untouched.

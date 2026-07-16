# Boxer combat / nav / fidelity upgrade — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. The **design source of truth** is `docs/superpowers/specs/2026-07-14-boxer-combat-nav-upgrade-design.md` — every task cites its spec section for the detailed design, root-cause file:line refs, and test expectations. Read the cited spec section before implementing a task.

**Goal:** Make boxers move, fight, and survive more like a real client — vanilla surface physics (slide/cobwebs), Baritone-quality server-side pathfinding, decisive predictive strafing, fluid finite-supply potion play, and vanilla kit durability.

**Architecture:** All changes sit upstream of the three brain seams (`MoveInput`, aim, action list) or at well-bounded settings/inventory seams; the packet wire, `ClientPhysics` core collision, latency model, and player-identity principle are preserved. Pure logic lives in `common` (unit-tested against synthetic `CollisionView`); plugin/NMS glue in `core`; integration coverage in `tester`.

**Tech Stack:** Java 25 toolchain (release 17 bytecode), Gradle multi-module, JUnit 5 (`common`), run-paper integration harness (`tester`), Paper/Folia NMS via reflection-remapper.

## Global Constraints

- **Player identity:** nothing may change how a boxer takes knockback/hit registration vs a real player with identical state.
- **Owning-thread only:** all brain/perception/inventory writes inside `tick()`; `BukkitCollisionView` single-caller; no new cross-region block reads (Folia).
- **Determinism:** new randomness draws from `BrainMemory.rng` only; never `Math.random`/`nanoTime`.
- **Config round-trips:** `parse(empty) == DEFAULTS` and `parse(write(s)) == s` stay green; settings schema is append-only (extend existing sub-records; new field carries a DEFAULT/OFF; update inline preset constructors + writer tests + the `assertEquals(7, DifficultyPresets.names().size())` pin if presets change).
- **Version matrix:** `common` unit tests must pass; the run-paper matrix (1.17.1 → 26.x) + Mental/OCM coexistence legs run before merge.
- **Commit cadence:** commit after each task's tests pass, on branch `feat/boxer-upgrade`.
- **Config plumbing pattern (every new setting):** model in `BoxerSettings` sub-record → `BoxerSettingsParser` read (warn-and-fallback) → `BoxerSettingsWriter` write (exact inverse key) → `DifficultyPresets` inline ctors + `config.yml` doc → `SettingsRegistry` `SettingDescriptor` (toggle/integer/cycle) + `XxxEdit` helper → consumer in `common.brain`. Round-trip tests in `BoxerSettingsParserTest`/`BoxerSettingsWriterTest`.

## Sequencing rationale (why mostly serial)

WS5, WS4, WS3 all edit the **same** shared files (`BoxerSettings.java`, parser, writer, `SettingsRegistry.java`, `config.yml`, `DifficultyPresets.java`) — parallel edits would conflict. They run **serially** in ascending risk. The `common`-only pieces (WS1a steering, WS1b/WS2 physics/pathfinding) touch different files and *may* run in parallel worktrees, but WS2 depends on the WS1 `CollisionView` interface change, so WS1 lands first. **Order: WS1c (debug, in flight) → WS5 → WS1a+WS1b → WS4 → WS3 → WS2.**

---

## WS1c — Jump-into-wall glue: diagnose then fix  *(spec §1c)*

Status: SUPERSEDED (0.7.0). The detect-and-recover fixes this section spawned (0.6.1's escape,
0.6.2's suppress+force-write — task 1c.2's option (a)) shipped and were then REMOVED as
player-identity violations. The root cause was a 1.19e-8 bounding-box half-width disagreement
(sim `0.6/2.0` in doubles vs the server's float-promoted `EntityDimensions.makeBoundingBox`
extents) that Paper 1.21.11+/26.x's strict full-cube collision collect turns into a silent
per-tick `CLIPPED_INTO_BLOCK` rejection loop. Fixed by byte-parity `PLAYER_WIDTH`/
`PLAYER_HEIGHT`, Paper-parity sweep clamps, and the vanilla accept+PosRot-echo teleport
confirm — see CHANGELOG 0.7.0. Task 1c.4 was reversed by f25cee4: the `-Dsimpleboxer.debug`
traces are PERMANENT matrix forensics, now joined by a debug-gated `PlayerFailMoveEvent`
listener. Do not re-ship the recovery approach from the task list below (kept as history).

- [ ] **Task 1c.1 — Capture the boundary trace.** Read the run-paper log (`build/integration-test-logs/1.21.4.log`) and `run/1.21.4/plugins/SimpleBoxerTester/test-failures.txt`. Confirm whether the `movement: a boxer that jumps into a wall falls back down` test fails and whether `POSITION-CORRECTION` / `wallCollide` traces show the server teleporting the sim back up, or the entity frozen while the sim falls. Record the failing boundary.
- [ ] **Task 1c.2 — Fix at the identified boundary.** Based on 1c.1 evidence only. Likely one of: (a) ignore/clamp self-inflicted server position-corrections while the sim is legitimately wall-collided and falling (`route()` PositionSync, `BoxerImpl.java:517`); or (b) preserve the sim's vertical in `reanchorServerPosition()` (`BoxerImpl.java:426`). Make the `MovementSuite` wall test pass.
- [ ] **Task 1c.3 — Keeper physics pin.** Re-add a distilled `ClientPhysicsTest` assertion (from the deleted repro battery) that the integrator falls off/along walls, so the integrator stays pinned independent of the brain.
- [ ] **Task 1c.4 — Remove the temporary `DEBUG` instrumentation** in `BoxerImpl` once the fix is verified (keep the integration test).

---

## WS5 — Kit durability  *(spec §Workstream 5)*

**Files:** Modify `common/.../settings/BoxerSettings.java` (`Items` record), `BoxerSettingsParser.java`, `BoxerSettingsWriter.java`, `DifficultyPresets.java`, `core/.../gui/menu/SettingsRegistry.java`, `core/src/main/resources/config.yml`, `core/.../boxer/BoxerImpl.java` (`durable`, `syncKit`). Test: `BoxerSettingsParserTest`, `BoxerSettingsWriterTest`, new `tester` MovementSuite/CombatSuite case.

**Interfaces produced:** `BoxerSettings.Items.unbreakableKit()` (boolean, DEFAULT false).

- [ ] **Task 5.1 — Add `Items.unbreakableKit` field (TDD).** Write a failing `BoxerSettingsWriterTest.reworkSubRecordsRoundTrip` extension asserting an `Items` with `unbreakableKit=true` round-trips; add the field to the `Items` record (config key `items.unbreakable-kit`, DEFAULT false), thread parser/writer, update `Items.DEFAULT` + the `ItemsEdit`/positional reconstructions in `SettingsRegistry`, and any inline `new Items(...)` in `DifficultyPresets` (SWEAT). Run round-trip tests → green. Commit.
- [ ] **Task 5.2 — Gate the Unbreakable stamp.** In `BoxerImpl.durable()` (`:988`), stamp `setUnbreakable(true)` only when `settings.items().unbreakableKit() || settings.items().lockLoadout()`. Fixture presets (`DUMMY`, invincible/AUTO_RESPAWN tiers) set `unbreakableKit=true`. Commit.
- [ ] **Task 5.3 — Fix the `syncKit` re-apply clobber.** In `syncKit()` (`:958`), when a slot's currently-worn `EntityEquipment` item equals the kit item **ignoring damage** (same type/enchants/name), skip the `setXxx` write so accumulated durability survives an operator `equip()` re-publish; write only when empty or the kit item changed. Respawn/handle-swap re-applies stay pristine. Commit.
- [ ] **Task 5.4 — GUI toggle + config doc.** Add a `SettingDescriptor.toggle("unbreakable-kit", ITEMS, …)` and `.unbreakableKit(boolean)` to `ItemsEdit`; document `items.unbreakable-kit` in `config.yml`. Commit.
- [ ] **Task 5.5 — Integration pin.** `tester` case: a mortal boxer's weapon/armor accrues `Damageable.damage` over a staged fight; a `lockLoadout`/`unbreakableKit` boxer's gear stays pristine. Commit.

---

## WS1a — Horizontal sticking (steering)  *(spec §1a)*

**Files:** `common/.../brain/ContextSteering.java`, `Goal.java` (add `mayLeaveLedges()` default false), goals (`EngageGoal`, `RodPokeGoal` return true), `Brain.java` (`resolveHeading` threads the flag; `tick` consumes `MoveHeading.speedScale`/`nearLedge`), `MotorQuantizer.java` (accept speedScale duty-cycle + sneak-on-ledge). Tests: `ContextSteeringTest`, `MotorQuantizerTest`, new `ClientPhysicsTest` slide pin.

- [ ] **Task 1a.1 — Goal-conditional ledge cost (TDD).** Failing `ContextSteeringTest.pursuitWalksOffLedgeTowardTarget`: with `mayLeaveLedges=true`, the winner heads off the edge toward the goal. Add `mayLeaveLedges` param to `steer(...)`; zero `ledgeCost` when set. Thread from `Goal.mayLeaveLedges()` via `Brain.resolveHeading`. Keep `desiredZero_returnsStill` + flat-floor pins green. Commit.
- [ ] **Task 1a.2 — Oblique wall pass-through (TDD).** Failing `ContextSteeringTest.obliqueWallKeepsNearestDesiredHeading`: a heading within ±45° of desired that merely grazes a wall is preferred over the pure perpendicular. Loosen/speed-scale the slide look-ahead so `ClientPhysics.collide` finishes the graze. Keep `wallDueEast_deflectsToSlideAlongWall` green. Commit.
- [ ] **Task 1a.3 — Consume the ease-off.** Wire `MoveHeading.speedScale`/`nearLedge` into `Brain.tick` `motor.toInput(...)`: duty-cycle digital forward for `speedScale<1`, press `sneak` when `nearLedge`. Add `MotorQuantizerTest` for both. Retire dead `MoveHeading` no-op. Commit.

## WS1b — Cobwebs  *(spec §1b)*

**Files:** `common/.../physics/CollisionView.java` (add `stuckMultiplier`), `ClientPhysics.java` (`step` web clamp), `core/.../boxer/BukkitCollisionView.java` (implement), synthetic test views. Tests: `ClientPhysicsTest`.

- [ ] **Task 1b.1 — Extend `CollisionView` (TDD).** Add `Vec3d stuckMultiplier(int x,int y,int z)` (default `null`/none on synthetic views). Implement in `BukkitCollisionView` (owning-thread region read; cobweb → `(0.25,0.05,0.25)`). No behavior change yet; existing tests green (fakes return null). Commit.
- [ ] **Task 1b.2 — Web velocity clamp (TDD).** Failing `ClientPhysicsTest.webClampsHorizontalAndVerticalSpeed` + `webTerminalSpeed` (hand-computed pin). In `ClientPhysics.step`, when the player box overlaps a web cell, apply vanilla `makeStuckInBlock` clamp before drag. Commit.

*(Combat "hits through webs" falls out — the slowed boxer no longer closes/attacks at full cadence; verify via a `tester` web-arena case.)*

---

## WS4 — Fluid potions + finite splash-pot hotbar  *(spec §Workstream 4)*

**Files:** `BoxerSettings.java` (`Items.fillSplashPots`, `Items.potCount`), parser/writer/`SettingsRegistry`/`config.yml`/`DifficultyPresets`, `core/.../boxer/BoxerImpl.java` (`syncKit` seed / `seedConsumables`, `inventoryView`), `common/.../brain/goal/PotHealGoal.java`. Tests: round-trip, `PotHealGoalTest`, `tester` case.

**Interfaces produced:** `Items.fillSplashPots()` (bool), `Items.potCount()` (int, e.g. [0,36]).

- [ ] **Task 4.1 — Settings fields (TDD).** Round-trip test for `Items.fillSplashPots`/`potCount`; add fields (DEFAULT false/0), parser/writer/GUI/config.yml/preset ctors. Commit.
- [ ] **Task 4.2 — Seed a finite splash-pot hotbar.** In `BoxerImpl` at the `loadoutDirty` `syncKit` seam, `seedConsumables()`: write a `SPLASH_POTION` instant-health stack of `potCount` into `items.potSlot()` (owning-thread; re-seeded on respawn). Not via `durable()` (consumable depletes by count). Commit.
- [ ] **Task 4.3 — Make the heal routine fluid (TDD).** `PotHealGoalTest` for move-while-throw + early aim-down. Rewrite `PotHealGoal.decide` to emit non-`ZERO` `Intent`s (reposition/juke while throwing and during cloud wait), pitch down a couple ticks before `StartUse`, pre-seat the pot slot as held (no per-throw swap), and drive give-up off `hasPots()` (empty inventory) not the fixed `splashCap`. Make it interruptible where safe. Commit.
- [ ] **Task 4.4 — Integration pin.** `tester`: a low-health boxer with a seeded finite supply heals fluidly, depletes the stack, then re-engages weaponless-of-pots. Commit.

---

## WS3 — Predictive strafing + presets  *(spec §Workstream 3)*

**Files:** `common/.../brain/AdaptiveStrafe.java` (rewrite `adaptiveOrbit`), `EngageGoal.java` (orbit tangent + range gate), `Perception.java` (add `TargetState.signedTrackRateDegPerTick`; start consuming `bearingToMeYaw`), `core/.../boxer/BoxerImpl.java` (`buildPerception` producer), `BoxerSettings.Combat` (`StrafePreset` enum), parser/writer/`SettingsRegistry`/`DifficultyPresets`/`config.yml`, ~6 test constructors. Tests: `AdaptiveStrafeTest`, round-trip.

- [ ] **Task 3.1 — Signed opponent-aim signal (TDD).** Add `signedTrackRateDegPerTick` to `TargetState` (producer: `wrapDegrees(view.yaw()-prevTargetYaw)` **without** `abs`, `BoxerImpl.buildPerception`); update the ~6 `TargetState` test constructors. Commit.
- [ ] **Task 3.2 — Rewrite `adaptiveOrbit` to choose sides (TDD).** `AdaptiveStrafeTest` cases: side chosen from signed aim error + `bearingToMeYaw`; juke opposite opponent `velocity()`; pick the wall-open side pre-contact; time the change to the w-tap re-press (`BrainMemory.wtapCountdown/wtapReleaseLeft`). Keep `isDeterministicForAFixedSeed` (draw from `mem.rng`). Commit.
- [ ] **Task 3.3 — Strafe more decisively.** In `EngageGoal.orbit`, raise in-band tangential authority and widen the range gate so it sidesteps sooner (not only <4.25 blocks). Update/extend engage tests. Commit.
- [ ] **Task 3.4 — `StrafePreset` enum + plumbing (TDD).** Replace `Combat.adaptiveStrafe` boolean with a `StrafePreset` enum (`NONE/ORBIT/JUKE/WTAP_SYNC`); promote `AdaptiveStrafe` tuning constants to per-preset params. Round-trip + GUI cycle + parser/writer/`DifficultyPresets` (EXPERT/SWEAT). Optionally add a `Movement.Style.STRAFE_JUKE`. Commit.

---

## WS2 — Baritone-quality pathfinding (server-side A*)  *(spec §Workstream 2)*

Internal phases 0→3. Keep the exact `route(...)` seam; the motor stack is untouched.

**Files:** new `common/.../brain/MoveCosts.java`, new `common/.../brain/BaritoneStylePlanner.java` (+`MovementType` enum), modify `CollisionView.java` (add `isReadable`), `NavGeometry.java` (reuse), `Brain.java` (`resolveHeading` range-gated primary), `BukkitCollisionView.java` (implement `isReadable`), retire `NodeKind.STEP`/`BrainMemory.climbTicks`. Tests: new planner tests mirroring `LocalPathPlannerTest`.

- [ ] **Task 2.0a — `CollisionView.isReadable` seam (TDD).** Add `boolean isReadable(int x,int y,int z)` (default `true` synthetic; `BukkitCollisionView` false outside region/unloaded chunk, no cross-region call). Commit.
- [ ] **Task 2.0b — `MoveCosts` (TDD).** Port Baritone `ActionCosts` constants + `distanceToTicks` into a pure class with hand-computed unit pins (reconcile against `ClientPhysics` STEP_HEIGHT/sprint). Commit.
- [ ] **Task 2.0c — 3D cell key.** Extend the cell pack to 3D `(x,y,z)`. Commit.
- [ ] **Task 2.1 — MVP 3D A* (TDD).** `BaritoneStylePlanner.route(...)` with TRAVERSE/DIAGONAL/ASCEND/DESCEND/FALL, 3D tick-scaled heuristic **with the vertical term** (`Δy_up·JUMP_ONE_BLOCK`; ~0 down) so it seeks elevation and the `bestSoFar` partial never stalls under a raised target, anytime `bestSoFar` partial path, box+Y-band+`isReadable`+budget bounds. Tests: flat sprint, wall detour, **concave U-trap escape**, ASCEND step-up, descending staircase, FALL pit, gap-no-parkour (routes around), **elevated target reached via off-line stairs/step-ups**, region-frontier (unreadable→in-bounds partial), determinism. Behind a flag; A/B vs `LocalPathPlanner`. Commit per scenario.
- [ ] **Task 2.2 — Range-gated primary + PARKOUR + segmentation (TDD).** Promote the planner to the primary approach controller in `resolveHeading` using **3D reachability** (not horizontal distance) for the transit-vs-strafe gate; add PARKOUR (dynamic-XZ 2–4 gap) + enriched `Waypoint(pos, via)` for pre-jump; resumable/segmented search for larger radii. Commit per piece.
- [ ] **Task 2.3 — Default it; retire `LocalPathPlanner`** at parity; remove dead `NodeKind.STEP`/`climbTicks`. Update ARCHITECTURE.md "World-scale pathfinding" deferral (region-bounded case retired). Commit.

---

## Release wrap

- [ ] Update `CHANGELOG.md`, `README.md`, `ARCHITECTURE.md` as capabilities land.
- [ ] Run `./gradlew :common:test` + `integrationTest` (floor+ceiling) + `integrationTestCombat` (if Mental/OCM jars staged) → all green.
- [ ] Bump version; open PR from `feat/boxer-upgrade`.

## Self-review (coverage)

Spec §1a→WS1a, §1b→WS1b, §1c→WS1c, §2→WS2, §3→WS3, §4→WS4, §5→WS5, cross-cutting invariants→Global Constraints. No spec section without a task.

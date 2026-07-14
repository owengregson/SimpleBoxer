# SimpleBoxer major rework — design spec

Status: **approved 2026-07-14**. Source of truth for the multi-feature rework shipping in one release.

## 0. Guiding principles (inviolable)
- **Player-identity**: no boxer property changes how it takes knockback or hit-registration vs a real
  player with identical state. Everything new is *upstream of the wire* — it still ends in one
  `MoveInput → ClientPhysics.step` plus genuine serverbound packets through the boxer's own game listener.
- **The integrator is correct.** `ClientPhysics` + `BukkitCollisionView` are vanilla-accurate (real
  partial block shapes). The reported bugs (sticky walls, failed 1-block jumps, spiral strafe) are
  **motor/decision bugs**. Do NOT change `ClientPhysics` collision math.
- **Digital input only**: emitted `MoveInput.forward/strafe ∈ {-1,0,1}`. `ClientPhysics` silently
  accepts fractional impulses no real keyboard sends — forbid them (unit-pinned).
- **Latency lines preserved**: brain reads only the matured (delayed) perception; every new action rides
  the existing action `LatencyLine` (action-delay preserved). Opponent-aim estimate is itself delayed.
- **Pure logic lives in `common`** (no Bukkit/NMS), unit-testable via seams (NavProbe mirrors CollisionView).
- **Determinism**: one uuid-seeded `Random` per boxer in `BrainMemory`; no `System.nanoTime` inside decisions.
- **Round-trip invariants**: `parse(empty)==DEFAULTS` and `parse(write(s))==s` stay pinned (re-pinned for v2 schema).
- **Folia**: all decision logic pure; version-specific packets via probe-in-`resolve()`; brain runs
  `repeatOn(entity)`; NavProbe/planner clamp to `MAX_EXTENT` so A* never crosses a region.
- **Netty-invisibility**: boxer outbound packets don't traverse netty → tests assert observable
  server-side/world effects only; new inbound intake is via Bukkit events, not packet sniffing.
- **Do NOT modify** Mental/StrikeSync or StarEnchants (reference/compat only).

## 1. Product decisions (locked by the user)
1. New combat techniques (rod, pot-heal, blockhit, adaptive strafe, s-tap) default **OFF**; showcased in
   a new top-tier preset. Bug-fixes (physics/pathfinding/invincibility/velocity-capture) are **always-on**.
2. **Survival default = MORTAL_MANUAL**: default boxer dies, drops items, stays dead until manual respawn.
   Opt-in modes: `MORTAL_RESPAWN` (auto-respawn in place), `INVINCIBLE` (fixed). `dummy` preset pins INVINCIBLE.
3. **Version scope = focus modern (1.20.6+)** for advanced item-use/blockhit (graceful degrade + boot
   report below); core fixes stay full-range 1.17.1→26.x.

## 2. Brain architecture (new; replaces `BoxerImpl.decideInput/strafeInput/considerClicking`)

Tick pipeline (owning thread). The **outer `tick()` shell is byte-identical** except the block currently
occupied by `decideInput()`+`considerClicking()`; `aim.step()` and `actions.offer()` move inside the brain
adapter. All existing physics/latency/packet tests remain valid.

```
BukkitPerceptionSource.build() → Perception (immutable, from matured/delayed data)
  → Arbiter.select(perception, memory) → Intent            [utility over weighted Considerations,
                                                             dwell/commit hysteresis, exclusive latch]
  → Motor: ContextSteering.steer() → ProactiveJump.evaluate() → AntiStuck.observe()/escalate()
           → (LocalPathPlanner when NavigateAroundObstacle wins / AntiStuck escalates, replan-gated)
           → MotorQuantizer.toInput() → MoveInput ({-1,0,1})
  → ClickController.consider() + Routine effectors → List<ActionIntent>
  → BrainOutput{ MoveInput move, float aimYaw, float aimPitch, List<ActionIntent> actions, boolean sprintDesire }
```

### 2.1 `common` modules (pure)
- `perception/Perception` (record): `SelfState self, @Nullable TargetState target, TerrainProbe terrain,
  InventoryView inv, CombatState combat, int pingMs`.
  - `SelfState{double x,y,z; Vec3d vel; boolean onGround, horizontalCollision; double healthPct, hungerPct;
    UseItemState useItem; boolean blocking}`
  - `TargetState{double x,y,z,eyeY; Vec3d vel; double bearingToMeYaw, oppTrackRateDegPerTick, distance; boolean blocking}`
  - `InventoryView{boolean hasRod,hasPots,hasFood,hasSword,hasShield; int selectedSlot; OptionalInt slotOf(category)}`
  - `CombatState{double attackMeter; int noDamageTicks; boolean mentalCombo; long lastHitTick}`
- `decision/Consideration` (interface `double eval(Perception)`) + `ResponseCurve` (linear/expo/logistic/band).
- `decision/Goal` (interface): `String id(); double utility(Perception); Intent decide(Perception, IntentBuilder, BrainMemory);
  default int minDwellTicks(){return 0;} default double commitBonus(){return 0;} default boolean exclusive(){return false;}`
- `decision/Arbiter`: holds Goal/Routine registry + `BrainProfile` weights; `Intent select(Perception, BrainMemory)`.
  Scores all goals (utility = weighted product of Considerations × profile weight), applies dwell/commit
  hysteresis, honors exclusive latch, lets MODIFIER goal (NavigateAroundObstacle) overlay the winner's moveDir.
- `decision/Intent` (record): `Vec3d moveDirWorld; FacingIntent facing; ActionIntent action; boolean wantSprint; JumpHint jump`.
  `FacingIntent = AimAt(point) | FaceMove`. `ActionIntent = None | Attack | StartBlock | ReleaseUse | UseRod | ThrowPot | Eat | SwapSlot(n)`.
- `nav/NavProbe` (interface, seam over geometry): `double stepHeightAhead(x,y,z,Vec3d dir,double dist);
  boolean solidAhead(...); boolean ledgeAhead(...); NodeKind classify(int bx,int by,int bz); List<Box> around(Box);`
  `enum NodeKind{STAND,STEP,JUMP,BLOCKED}`.
- `nav/ContextSteering`: `MoveHeading steer(Perception, Vec3d desiredDirWorld, NavProbe)` — K-ray interest−danger,
  danger probed at foot AND +0.6/+1.0 plus ledges + incoming-KB direction → best clear heading (slides along walls).
- `nav/ProactiveJump`: `JumpHint evaluate(Perception, MoveHeading, NavProbe)` — fire jump BEFORE contact when a
  climbable step (>STEP_HEIGHT, ≤~1.2) is within the look-ahead window with horizontal momentum intact.
  Replaces the reactive `onGround && horizontalCollision` jump and the `climbTicks≥10` gate.
- `nav/AntiStuck`: rolling intended-vs-actual displacement window; escalate jump → lateral detour →
  invoke LocalPathPlanner on stall. Deterministic (seeded).
- `nav/LocalPathPlanner`: bounded voxel A* over `NavProbe.classify` with WALK/STEP/JUMP edges (full blocks
  become jump edges since STEP_HEIGHT=0.6), clamped to `MAX_EXTENT`. `Optional<List<Vec3d>> route(Cell,Cell,NavProbe,budget)`;
  `boolean needsReplan(Perception)` — replan ONLY on goal-cell-move / watched-predicate-flip / route-FAILED.
- `motor/MotorQuantizer`: `MoveInput toInput(MoveHeading, float aimYawDeg, boolean sprint, JumpHint, boolean sneak, double radiusError)`
  — decompose world heading in the AIM-YAW frame, quantize to {-1,0,1}. Orbit = radius-band duty-cycle
  (forward=1 at/outside ring to keep sprint [impulse≥0.8]; 0/back-pedal inside). Hysteresis deadband near 45°.
- `strafe/AdaptiveStrafe`: `StrafeDecision next(Perception, BrainMemory)` → `{int sign; boolean flip; StrafeMode mode}`;
  `StrafeMode{ORBIT,WEAVE,NONE}`. Chooses sign/flip from opponent-aim estimate (their yaw-error to us + rate,
  both delayed): flip to break a tight track, hold to exploit a mistrack. Seeded jitter.
- `combat/ClickController`: `void consider(Perception, ActionIntent goalAction, ClickScheduler, long nowMs, List<ActionIntent> out)`
  — wraps the existing CPS clock + in-reach + aim-cone gate PLUS latency-aware reach prediction (predict target
  pos at click-landing given action delay) PLUS routine suppression (no attack while retreating/mid-swap; blockhit
  inserts a block between clicks) PLUS attack-meter awareness. Attack before Swing; swing always.
- `routine/Routine` (interface extends Goal): `boolean interruptible(); ActionIntent nextAction(Perception, BrainMemory);
  Phase phase();` — a modular multi-tick HFSM that both scores as a Goal and emits actions.
- `brain/Brain`: top orchestrator; `BrainOutput tick(Perception)`. Holds registry, arbiter, controllers.
- `brain/BrainMemory`: per-boxer mutable, owning-thread only; single uuid-seeded `Random`, strafe sign, routine
  phase, dwell/stuck counters, cached path. Replaces the scattered `wtapCountdown/strafeSign/strafeFlipIn/climbTicks` fields.
- `brain/BrainProfile` (record, nested in BoxerSettings): `Map<String,Double> goalWeights; StrafeStyle strafe;
  RoutineToggles routines; NavParams nav`. Its DEFAULTS reproduce today's RUSH so round-trip pins hold.

### 2.2 `core` adapters
- `BukkitPerceptionSource` — owning-thread; assembles Perception from the matured `perceived` TargetView,
  health/hunger, `isBlocking()` / NMS `getUseItem().getUseAnimation()==BLOCK`, inventory snapshot, attack-meter,
  and opponent-aim estimate from the target's yaw history (delayed).
- `BukkitNavProbe` — implements NavProbe over `BukkitCollisionView` block queries; owning-thread; clamped to
  `MAX_EXTENT` (Folia region-safe). Read fresh per plan (view is bound to spawn world, cache per-plan only).
- `ActionDriver` — maps ActionIntent → the existing action `LatencyLine`; new Action records ride the same line.

## 3. KnockbackResolver (features 10/11/14 correctness; `common`, pure, thread-safe)
Replaces the three racy paths (`receivedKnockback` drain, own-id `SetEntityMotion` echo branch,
`drainHurtMarked` poll) and subsumes `eventBasedKnockback`.
- Channels (REPLACE, ranked): `HURT_MARKED(0) < MOTION_ECHO(1) < MELEE_KB(2) < PLAYER_VELOCITY(3)`; plus one
  summed `EXPLOSION` ADD lane.
- `void offer(Channel, double vx,vy,vz, long serverTick, long nanos); void offerExplosion(double x,y,z, long serverTick, long nanos);
  void resolve(long now, long oneWayNanos, PhysicsSink sink)`.
- Matures samples through the perception line (oneWay = ping/2 nanos), groups by serverTick, holds each matured
  bucket **one grace tick** to absorb same-serverTick higher-rank stragglers, emits exactly one `applyVelocity`
  (rank winner; ties→latest nanos) + one summed `addVelocity` per matured bucket.
- Core wiring: `ExternalVelocityListener` (`PlayerVelocityEvent`@MONITOR) → `offer(PLAYER_VELOCITY,...)`
  (StarEnchants setVelocity + Mental DeskRouter). `KnockbackListener` (EntityKnockbackEvent, ignoreCancelled) →
  `offer(MELEE_KB,...)` (Mental cancels vanilla knock so it won't double-fire). Own-id echo → `MOTION_ECHO`.
  hurtMarked poll (pre-1.20.6/no-PVE fallback) → `HURT_MARKED`.
- In the re-anchor step, **mirror the sim's current velocity into ServerPlayer deltaMovement** so a plugin
  reading `player.getVelocity()` (StarEnchants additive base) sees the boxer's real perceived velocity.
  Guard against a feedback loop (the mirrored motion must not be re-captured as a spurious PVE).

## 4. BoxerSettings v2 (append-only; `common`)
Every existing top-level positional field stays IDENTICAL and in place (`pingMs,cps,clickJitter,aim,reach,
aimToleranceDegrees,wtap,movement,invincible,feedHunger`). Add at the END:
- `InvincibleMode invincibleMode` — `{ZERO_DAMAGE, LEGACY_RESTORE, OFF}`. (Meaningful only when `invincible`.)
- `Death death{boolean dropItemsOnDeath, Mode mode}` — `Mode{AUTO_RESPAWN, MANUAL}`. **DEFAULT `{true, MANUAL}`** (decision 2).
- `Combat combat{boolean blockHit, rodKnockback; double rodMin=3.0, rodMax=6.0; boolean adaptiveStrafe, sTap; double missChance=0.0}` — DEFAULT all-off.
- `SelfHeal selfHeal{boolean enabled; double triggerHealth=6.0, resumeHealth=18.0; int splashCap=6}` — DEFAULT off.
- `Items items{boolean autoPickup, lockLoadout; int weaponSlot=0, rodSlot=1, potSlot=2, foodSlot=3, blockSlot=4}`.
- `Hunger hunger{boolean natural; int eatThreshold=14}` — DEFAULT off (feedHunger pin stays default-on).
- `BrainProfile brain{...}` — DEFAULT reproduces RUSH.

`invincible` DEFAULT becomes **false** (mortal-manual default). `feedHunger` DEFAULT stays true (hunger opt-in).
Group withers `withDeath/withCombat/withSelfHeal/withItems/withHunger/withInvincibleMode/withBrain` (each re-lists
all fields once, existing pattern; optional `toBuilder()` to tame combinatorics). Compact-ctor range validation
per record. Parser: per-sub-record `parseX(section,base,warnings)` warn-and-fallback over the DEFAULT constant
(absent section → DEFAULT); Writer: symmetric `writeX` fresh(). Legacy flat-key fallback for `invincible`/`feed-hunger`
and migration aliases. Re-pin `parse(empty)==DEFAULTS` and `parse(write(s))==s` for v2.

## 5. Inventory (feature 8; `api`/`core`)
- `Loadout` UNCHANGED (6-slot immutable spawn-seed VIEW). Add `BoxerInventory` (41 slots: 9 hotbar+27 storage+
  4 armor+1 offhand) + `selectedSlot(0-8)` + `managedSlots` bitmask + `ItemCategory` classifier
  (`held(); select(int); OptionalInt firstSlotOf(Predicate<ItemStack>); Loadout asLoadout(); long managedSlots();`).
- `applyLoadout` → `layoutDirty`-gated `syncKit(dirtySlots)` MERGE: stamps operator kit + Unbreakable only at
  spawn/respawn/explicit-equip, HANDS follow `selectedSlot` (never per-tick overwrite) → pickups persist.
  `lockLoadout` restores the per-tick re-stamp for pure fixtures.
- `PickupListener`: `EntityPickupItemEvent` HIGH cancel-if-`!autoPickup`; MONITOR mirror accepted pickups into
  `BoxerInventory`/`managedSlots` + re-stamp Unbreakable on picked weapons.

## 6. Packets & actions (`core`)
New serverbound factories in `PacketIO` via probe-in-`resolve()`+public-factory (dispatch is already generic):
- `setCarriedItem(int slot)` — `ServerboundSetCarriedItemPacket` (int-vs-short flag).
- `useItem(boolean mainHand, int seq, float yaw, float pitch)` — `ServerboundUseItemPacket` (arity-probe seq[1.19+]/rotation[1.21.3+]).
- `releaseUseItem(int seq)` — `ServerboundPlayerActionPacket` RELEASE_USE_ITEM (enum name→remap→ordinal; BlockPos.ZERO+Direction.DOWN; seq flag).
Each nullable → dispatch no-op when absent → routine capability flag disables it in arbitration + boot report.
Per-boxer `AtomicInteger` block-change sequence.
New sealed `Action` records in BoxerImpl: `SelectSlot(int)`, `UseItem(boolean mainHand,float yaw,float pitch)`,
`ReleaseUse()` — routed through the existing `actions` LatencyLine.
`HeldItemController` (press-hold-release bookkeeper): emit UseItem ONCE at press, hold across ticks, ReleaseUse on
routine-end OR server useDuration; reconcile selectedSlot vs server truth like `syncSprint`.
`Effectors` (interface; hides packets from routines): `selectSlot/beginUse/endUse/throwHeld/castRod/setBlocking/isBlocking/world()`.

## 7. Guards decomposition (`core`) — replaces `SurvivalGuards`
- `InvincibilityGuard` — `onDamage` HIGHEST/ignoreCancelled: when `invincible && mode==ZERO_DAMAGE`, zero every
  applicable `DamageModifier` then `setDamage(0)`. NEVER cancel (suppresses knockback), NEVER restore-next-tick
  (that IS the burst-kill). `LEGACY_RESTORE` = today's restore path; `OFF` disabled. Verify per-version that
  knockback still lands (InvincibilityKnockback suite).
- `DeathPolicyGuard` — `onDeath` HIGHEST: `dropItemsOnDeath` → populate real drops from BoxerInventory (keepInventory
  false); `mode==MANUAL` → skip respawn, park `AWAITING_RESPAWN`, keep keepalive life-support; `Boxer.respawn()`
  resurrects at death spot. `AUTO_RESPAWN` = today's respawn-in-place.
- `HungerGuard` — `onHunger` HIGHEST/ignoreCancelled: `feedHunger` pins food/sat (today); `Hunger.natural` lets
  vanilla exhaustion run (don't cancel) so Eat routine has an honest trigger.
- `ExternalVelocityListener` (§3). `PickupListener` (§5).
- `MentalBridge` — reads self/target block state (`isBlocking()`/NMS use-animation, since Mental has no query),
  consumes `KnockbackApplyEvent`/`ComboStart`/`ComboEnd`/`AsyncHitRegisterEvent` (async→marshalled to
  owning-thread volatile flags) as routine triggers; optional reflective `MentalApi` diagnostics.
- New API: `Boxer.respawn()`, `Boxer.State{ALIVE,AWAITING_RESPAWN} state()`, `Boxer.inventory():BoxerInventory`.
  In `AWAITING_RESPAWN` the tick runs keepalive-only (answer KeepAlive, minimal ServerPlayer tick) and returns early.

## 8. Routines (features 5/6/12/13/15; `common` logic + `core` effectors)
- `RodPoke` — utility spikes on high closing-speed in the rod band (`rodMin..rodMax`) + hasRod → `selectSlot(rod)` →
  `castRod()` (UseItem spawns real FishHook → vanilla knockback) → `selectSlot(weapon)` for the combo.
- `PotHeal` (MORTAL only, disjoint from invincibility) — HFSM: CreateSpace(flee)→SwapToPots→Splash(AimAt feet +
  throwHeld until `healthPct≥resumeHealth`, capped `splashCap`)→SwapBack→re-engage; abort to Chase on threat spike.
- `Blockhit` — drives own sword-block gesture (`selectSlot(sword)`→`beginUse` [PlayerInteractEvent RIGHT_CLICK_AIR
  w/ sword = Mental's read] hold→`ReleaseUse`) + own START_SPRINTING re-arm between hits. Documented ceiling: cannot
  drive Mental's exact WIRE blockhit (needs live connection ledger) — approximation via own gesture + sprint re-arm.
- `STap` — strafe=0 straight-line, sprint-reset pulse (reuses the wtap countdown shape via `onHitLanded`).
- `SeekFood`/`Eat` — `Hunger.natural` + hunger low → `selectSlot(food)`→`beginUse` (hold ~useDuration)→`ReleaseUse`
  while backing off (fires PlayerItemConsumeEvent, player-identical).
- `GrabItems` — utility rises when a wanted item entity is nearby and safe; steer to it (pickup is server-driven).

## 9. GUI (feature 9; `core`, reuse framework)
Descriptor-driven, categorized; replaces the hand-laid 54-slot `SettingsMenu`.
- `SettingDescriptor<T>(String id, Category cat, Kind kind{TOGGLE,NUMERIC,CYCLE,TEXT}, Icon icon,
  Function<BoxerSettings,T> get, BiFunction<BoxerSettings,ClickIntent,BoxerSettings> apply, step/options, lore)`
  rendered by one generic `DescriptorButton`. Adding a knob = append a descriptor, never lay out a slot.
- `SettingsHubMenu` category tiles (Aim, Combat, Movement, Survival, Items, Routines, Presets) → auto-laid-out
  paginated `CategoryMenu`s. `InventoryEditorMenu` edits the real BoxerInventory via an opt-in editable region.
  Management surface: boxer list, pause/resume/**respawn**/remove, spawn wizard. Reuses `Menu/Icon/ChatPrompts/
  PaginatedMenu/SettingsTarget` (Writer round-trip untouched). Typed numeric entry via ChatPrompts.

## 10. Presets
Update `dummy` → INVINCIBLE(ZERO_DAMAGE) punching bag. Existing tiers gain brain-weight bundles. Add a new
top-tier preset (e.g. `sweat`) enabling `adaptiveStrafe`, `blockHit` (if Mental), `rodKnockback`, `selfHeal`,
`sTap`, tuned weights — the showcase (decision 1). Weights ship at first-pass values.

## 11. Test plan
**Unit (`common`, pure, FakeNavProbe/FakeCollisionView like ClientPhysicsTest):** Considerations (curve monotonicity/bounds);
Arbiter (winner selection + dwell/commit hysteresis + exclusive latch); MotorQuantizer (orbit tangent→strafe±1
duty-cycled; **every emitted forward/strafe ∈ {-1,0,1}**); ORBIT radius-band regression driven through the REAL
ClientPhysics (radial distance stays in band, angular travel accumulates — pins the spiral fix); ContextSteering+
ProactiveJump (synthetic 1.0 wall: heading deflects along wall; jump raised BEFORE contact; boxer clears the block
through real ClientPhysics while the naive path FAILS the same fixture); LocalPathPlanner (pillar trap route +
replan-gate skip); AntiStuck (stall→reroute); Routine FSMs (scripted HP/food/distance timelines); AdaptiveStrafe
(synthetic aim traces: flip on tight track, hold on mistrack); ClickController (reach+cone+prediction+suppression);
KnockbackResolver (rank winner; explosion ADD; grace straddle; maturation); BoxerSettings v2 (`parse(empty)==DEFAULTS`,
`parse(write(s))==s`, legacy fallback, per-record ranges); determinism (same seed → same stream); PacketIO shape-flag
branches via mock constructors.
**Integration (`tester`, effects-only, matrix 1.17.1/1.18.2/1.19.4/1.20.6/1.21.4/1.21.11/26.1.2), new suites registered
in SBTesterPlugin.start():** Navigation (pillar/wall/stair/ledge arena → reaches target in budget); CircleStrafe
(orbit band, no inward spiral); StepJump (clears a 1-block ledge); InvincibilityKnockback (burst > HP → survives AND
displaced); RodKnockback (FishHook spawns + target velocity changes); SelfHeal (ThrownPotion spawns + HP climbs +
re-engage); Pickup (item enters inventory + survives next tick; autoPickup=false → not vacuumed); DeathDrops (stays
dead until manual respawn, dropped items); ExternalVelocity (setVelocity integrates once, no double under
eventBasedKnockback); Blockhit (Mental present → isBlocking rhythm + reduced trade). `gradle check` greps PASS.

## 12. Implementation phases (parallelize independent NEW files; serialize shared-file edits)
- **P0 Scaffolding & schema**: BoxerSettings v2 + parser/writer + tests; BrainProfile; api additions
  (Boxer.respawn/state/inventory, BoxerInventory interface). Keep build green.
- **P1 Packets & inventory**: PacketIO factories + Action records + HeldItemController; BoxerInventory impl +
  syncKit + PickupListener. Guards decomposition (Invincibility/Death/Hunger/ExternalVelocity/Pickup) + KnockbackResolver.
- **P2 Brain core (`common`)**: Perception, Considerations, Goals, Arbiter, Intent, NavProbe, ContextSteering,
  ProactiveJump, AntiStuck, LocalPathPlanner, MotorQuantizer, AdaptiveStrafe, ClickController, BrainMemory, Brain.
  Heavy unit tests. (Most parallelizable — independent files behind interfaces.)
- **P3 Routines + Effectors + Mental**: Rod/PotHeal/Blockhit/STap/SeekFood/GrabItems; Effectors; MentalBridge.
- **P4 Integration**: BukkitPerceptionSource, BukkitNavProbe, ActionDriver; wire Brain into BoxerImpl behind the
  three seams; remove obsolete fields; keep every existing test green.
- **P5 GUI**: descriptor registry + DescriptorButton + SettingsHubMenu + CategoryMenus + InventoryEditorMenu +
  management surface + presets.
- **P6 Tests + ship**: new tester suites; unit-test sweep; `./gradlew build`; integration matrix (floor+ceiling then
  full where feasible); version bump root build.gradle.kts (0.4.0 → 0.5.0) → plugin.yml; changelog/README; release jar.

## 13. Known ceilings (documented, not defects)
- Blockhit is an approximation (no live connection ledger for Mental's wire path); test asserts direction, not parity.
- Orbit is a radius band, not a perfect circle (digital keys; a real player has the same limit).
- Nav is bounded local A* + reactive steering (Folia forbids cross-region reads); not a world-scale maze solver.
- ZERO_DAMAGE invincibility means a plugin reading FINAL damage at MONITOR sees 0 (the user's literal ask);
  `LEGACY_RESTORE` is the opt-out for exact-damage-number testing.
- Boxer outbound is netty-invisible → advanced item-use packets on old versions self-disable + report.

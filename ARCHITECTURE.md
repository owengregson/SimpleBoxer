# SimpleBoxer — architecture

Virtual sparring players ("boxers") for combat-plugin testing, built to pair
with [Mental](../StrikeSync) and OldCombatMechanics on Paper 1.17.1 → 26.x.
The design borrows Mental's bones: multi-module Gradle, immutable records,
atomic config snapshots, pure math classes with hand-computed unit pins, a
single scheduling seam, and an in-server integration harness gated by
run-paper across the full version matrix.

## The player-identity principle

**No property of a boxer may make it take knockback or hit registration any
differently than a real player with identical state.** Everything below
serves that constraint:

- A boxer **is a real `ServerPlayer`** — constructed on NMS (the
  battle-tested OCM/Mental FakePlayer lineage), registered through
  `placeNewPlayer` (direct PlayerList registration as fallback, including
  the lowercased by-NAME map so `getPlayerExact` and name-targeted commands
  resolve). Vanilla `/tp`, `/effect`, `/give`, potion effects, inventories,
  armor — all work because the server sees a player, not an NPC.
- **Knockback arrives the real way**: the server sends
  `ClientboundSetEntityMotionPacket` to the boxer's connection exactly as it
  would to a real client (Mental's pre-send + authoritative pipeline, OCM's
  shaping, or vanilla — whatever the server ships, ships). The boxer's
  connection **captures** outbound packets instead of voiding them; the
  client brain integrates the velocity into its own client-side physics and
  walks the resulting trajectory — precisely what a vanilla client does.
  Melee knockback to players is send-then-restore server-side, so a bot that
  ignored these packets would never move; a bot that read server motion
  fields would move wrongly. Packet integration is the only faithful model.
- **Movement leaves the real way**: the brain emits genuine
  `ServerboundMovePlayerPacket.PosRot` (and sprint `PlayerCommand`, attack
  `Interact`, `Swing`, the 1.21.2+ `PlayerInput` keyboard state on change)
  objects dispatched through the boxer's own
  `ServerGamePacketListenerImpl` on the owning thread — the same handlers,
  validation, ground-state bookkeeping, and Bukkit events
  (`PlayerMoveEvent`, `PlayerToggleSprintEvent`, `PlayerInputEvent`) a
  socket delivers for real clients.
- **The client-loaded handshake is answered** (1.21.4+): the server arms a
  60-tick gate at every game-listener construction and re-arms it on every
  respawn; until `ServerboundPlayerLoadedPacket` arrives (or the gate times
  out) it silently drops sprint commands, interactions and movement. A real
  client answers when its level renders; the boxer answers at spawn and
  again after every respawn — without the answer, a freshly respawned boxer
  spends three seconds punching plain and walking unsprinted.
- **Survival semantics stay vanilla**: damage events are never cancelled and
  damage amounts never altered (invulnerability would change the
  difference-rule and immunity-window behavior combat plugins implement);
  the default "invincible" mode restores health after vanilla processing
  completes and intercepts death by respawning in place. Hunger pins full
  via `FoodLevelChangeEvent` so sprint stays legal and no exhaustion noise
  leaks in.

### The self-velocity contract (load-bearing)

A player's own knockback packet is shipped by the ENTITY TRACKER when it
processes `hurtMarked` — and a solo boxer has no tracker viewers, so
vanilla never ships it at all. The brain therefore drains the flag itself
BEFORE the ServerPlayer tick (after it, the tick's travel has already
dragged the server motion fields and the packet would go out one decay
stale — real players' fields never decay), replicating the tracker's
contract exactly: PlayerVelocityEvent fires, cancellation leaves the flag
set, a listener-modified velocity applies, the packet is built by the same
vanilla constructor, and viewers receive the same pristine broadcast.
Field resolution walks the class hierarchy remapping at each declaring
level — `hurtMarked` lives on Entity, and spigot-mapped remapping resolves
only against declaring classes.

### Two model corrections the suites forced (load-bearing)

- **The server must never self-integrate a boxer's motion.** On older
  versions `ServerPlayer.doTick` runs the entity's own travel, moving it by
  its server motion fields; real players never show this because their
  clients stream absolute positions every tick that overwrite it. The brain
  restores the server position after `doTick` — a boxer's entity follows
  ONLY its move packets and teleports. (Found by the 400 ms latency test:
  an idle boxer "flew" 0.9+0.49+0.45 blocks of pure server travel while its
  delayed move packets were still parked.)
- **Players retire on quit, not on death.** `isValid()` drops while dead,
  which retired the brain task and removed the boxer before the respawn
  intercept could run. Scheduling liveness is player-aware (`isOnline`),
  and after a respawn the brain refreshes its NMS handle — respawn REPLACES
  the ServerPlayer entity and entity id while the Bukkit player and the
  Connection (with our capture handler) survive.

### Honest boundary

In-process boxers have no socket, so their packets do not traverse the netty
pipeline — packet-sniffing plugins (PacketEvents/ProtocolLib listeners, e.g.
Mental's netty fast path for the **boxer's own attacks** and its
`GroundPacketTap` for boxer movement) do not see boxer traffic. Mental
handles clientless players by design (tick-sampler fallback; authoritative
damage path), and the *interesting* direction — a real player attacking a
boxer — exercises the full fast path, because the attacker's own connection
carries the ATTACK packet and the boxer receives its knockback through the
standard outbound pipeline. Wire-level claims about boxer-thrown hits are
validated externally against a protocol client (Mental's legacy-lab
harness) rather than asserted in-process.

## Modules

| Module   | Contents | Deps |
| --- | --- | --- |
| `common` | Pure logic, no plugin lifecycle: `ClientPhysics` (the client motion integrator), `AimSpring`, `ClickScheduler`, `LatencyLine`, settings records + presets + parsing, `Scheduling` seam | paper-api (compileOnly) |
| `api`    | Public surface other plugins consume: `Boxer`, `BoxerService`, `Loadout`, Bukkit events | `common` |
| `core`   | The plugin: NMS bridge (spawn/connection/packets), identity (skins/tab), guards, the brain, behaviors, the GUI, commands, config | `api`, `common`, reflection-remapper (shaded) |
| `tester` | In-server integration suite (Mental's TestHarness pattern), runs per-version via run-paper; detects Mental/OCM and adds coexistence suites | `core` (compileOnly) |

## The brain (per tick, on the boxer's owning thread)

```
world snapshot ──► PerceptionLine (delay = ping/2) ──► Behavior (intent)
                                                          │
                 ┌────────────────────────────────────────┤
                 ▼                                        ▼
            AimSpring (yaw/pitch chase)             MovementController
                 │                                  (sprint, w-tap FSM,
                 ▼                                   strafe, jump)
            CombatController                              │
            (CPS clock, reach gate, aim cone)             ▼
                 │                                  ClientPhysics step
                 │                                  (input accel + captured
                 │                                   velocity packets after
                 │                                   perception delay)
                 ▼                                        │
            ActionLine (delay = ping/2) ◄─────────────────┘
                 │
                 ▼
            PacketIO → MovePlayer/PlayerCommand/Interact/Swing
                       through the boxer's own game listener
```

- **Latency model**: simulated ping is a symmetric RTT split into two delay
  lines. The *perception* line delays everything the brain knows about the
  world (target positions, velocities) AND the velocity packets the physics
  integrates — a 100 ms boxer starts flying back ~50 ms after the server
  stamped the knock, like a real laggy client. The *action* line delays
  decided actions (movement, clicks) on their way to the handlers.
  Timestamp-deadline queues, tick-quantized exactly like real netty arrival.
- **Self-knowledge rides the wire too**: the boxer's movement-speed
  attribute (Speed/Slowness potions, armor or plugin modifiers — the
  server's sprint modifier stripped, since the integrator applies its own
  ×1.3 from the held key) and its Jump Boost amplifier snapshot each tick
  and age through the perception line — the in-process stand-in for
  `ClientboundUpdateAttributes` / `UpdateMobEffect`.
- **The pocket is pushed, not parked**: the brain runs the client-predicted
  half of `pushEntities` (the 0.05-per-overlap shove, vanilla's quirky
  √absMax math), so a W-holding boxer bulldozes through bodies exactly like
  a real client. The default movement therefore never releases forward —
  releasing in the pocket drops sprint (vanilla needs forward impulse
  ≥ 0.8) and the momentum that survives combos. `stop-distance` rings, and
  the analog-free ±1 strafe keys, are explicit opt-ins on top — and a ring
  is a w-tap machine by construction: every re-entry re-presses W, which
  re-arms sprint (and any sprint-freshness ledger watching the toggles), so
  a ringed boxer lands sprint-fresh knockback on each return punch even
  with `w-tap: false`, while its in-ring punches land plain. That is
  player-identical (range discipline IS w-tapping) but it is NOT a
  symmetric hold-W trading partner; keep the ring at 0 for trade sparring.
  Measured on the lab (ring 2.5 vs 0): mixed 0.4/0.9 stamps at pocket
  cadence vs uniform 0.9 with the 2-tick toggle-client re-arm rhythm.
- **The sprint-attack proc** (load-bearing under OCM): vanilla
  `Player.attack` clears the ATTACKER's sprint flag and multiplies its own
  motion ×0.6 horizontally on every successful full-meter sprint hit —
  with restored 1.8 hit speed that is every landed punch. The brain applies
  the self-slow when the damage event confirms the hit (the event fires
  inside `hurt()`, so the flag and meter still read their pre-clear
  values), and `syncSprint` reconciles its cache against the server flag
  each tick, re-sending START_SPRINTING exactly like a toggle-sprint
  client's auto re-arm — one fresh PlayerCommand per landed hit, the real
  wire rhythm that re-arms sprint-extra knockback. A stale cache here left
  boxers permanently unsprinting after their first punch on OCM servers
  while looking sprint-ish in open chase: the original "sometimes they
  aren't trying to sprint" report.
- **Aim model**: yaw/pitch chase the desired direction through a
  spring-damper with a max angular velocity. Underdamped springs naturally
  overshoot when a strafing target flips direction — overshoot is a physics
  consequence, not a bolted-on random. Presets (locked/sharp/smooth/sloppy)
  tune stiffness, damping, and velocity cap; granular knobs stay exposed.
- **Combat**: a CPS clock with jitter schedules clicks; a click lands only
  when the (delayed) target is inside reach and inside the aim cone.
  Misclicks on empty air still swing (like a spam-clicking human). W-tap is
  a state machine: hit lands → release forward + stop-sprint → re-press +
  start-sprint after the configured delay — the sprint packets re-arm
  sprint-extra knockback exactly as a real w-tapper's do.
- **Difficulty presets** bundle ping, CPS, aim params, reach discipline,
  w-tap usage/delay, and strafe style into named tiers; every component
  stays individually overridable per boxer at spawn or at runtime.

## The GUI and virtual inventories

The plugin is GUI-first: `/boxer` opens an in-game menu that fronts the whole
feature set (spawn, manage, tune, kit, presets, config). The command tree is
unchanged underneath — it stays for console and the integration suite — but a
player never needs it.

- **Framework** (`gui`): a `Menu` is its own `InventoryHolder`, so the single
  `MenuListener` routes events by `getHolder() instanceof Menu` and a foreign
  GUI is never mistaken for ours. The contract is "cancel every click and drag,
  then opt back in" through `Button`s and a couple of override hooks — a menu
  never lets Bukkit move a real item. `Icon` builds icons against the
  cross-version common denominator (legacy §-string display API, the two
  stable item flags, a glow enchant resolved by registry KEY since the
  constants were renamed in the 1.20.5 alignment). Screens that show live
  state (the roster, a boxer's panel) re-render in place every 20 ticks while
  open, so status lore never goes stale. `ChatPrompts` captures the one thing
  a grid of icons can't express — free text — on Paper's `AsyncChatEvent`,
  the canonical chat event across the whole supported range (the legacy
  `AsyncPlayerChatEvent` is no longer guaranteed to fire on modern Paper).
- **Virtual inventory** (`api.Loadout` + `BoxerImpl`): a `Loadout` is six
  immutable, defensively-cloned equipment slots (four armor pieces, both
  hands). `equip()` publishes it from any thread and sets a dirty flag; the
  brain applies it to the real `EntityEquipment` on the boxer's **owning
  thread** in `tick()`, so equipment writes — and the `PlayerArmorChangeEvent`
  they fire, which custom-enchant plugins key passive effects off — never race
  the brain or a Folia region tick. Because the boxer is a real `ServerPlayer`,
  the kit is vanilla-real: armor and weapon attributes register, vanilla and
  custom enchants apply, and the loadout editor (`LoadoutMenu`) is dupe-safe by
  construction — it copies items into a model and never consumes the operator's
  own. The kit re-applies after a respawn (the entity is replaced; the dirty
  flag re-arms on the handle swap) so it is never lost.
- **Persistence** (`BoxerSettingsWriter` + `ConfigStore`): the GUI's
  defaults/preset edits serialise back through the exact inverse of the parser
  (`parse(write(s)) == s`, pinned by test) and save to `config.yml`. Live-boxer
  edits retune in place and persist nothing — boxers stay ephemeral.

## Threading

Mental's rules, inherited wholesale: all entity work through
`Scheduling.runOn/repeatOn` (one brain task per boxer on its owning thread);
the outbound-capture handler runs on arbitrary server threads and only
enqueues into a concurrent timestamped queue the brain drains; skin lookups
run async and re-hop; no netty listeners, no packet injection — SimpleBoxer
is invisible to the server's network stack.

## Testing

1. **Unit** (`common`): hand-computed pins for the physics integrator
   (equilibrium −0.0784, walk/sprint terminal speeds, knock trajectories
   matching Mental's measured era wire values, jump arc ≈ 1.25), aim spring
   behavior (convergence, overshoot on reversal), latency line timing, CPS
   distribution, preset/parse round-trips, `parse(empty) == DEFAULTS`.
2. **Integration** (`tester`, per version): spawn/identity (online, hidden
   from tab, named, skinned, command-targetable), movement (walks/sprints to
   a target), combat (CPS within tolerance, damage events fire), knockback
   (a staged hit flies the boxer along the client-integrated trajectory),
   latency (delay measurably shifts reaction), guards (health hold, hunger
   pin, death respawn), pause/resume, commands.
3. **Acceptance** (lab): Paper + Mental + OCM with boxers vs the legacy-lab
   protocol client — cross-validating that a boxer's received knockback
   matches a real client's byte-for-byte under identical hits.

## The brain, reworked (0.5.0)

The monolithic `decideInput()` is replaced by a **utility-AI brain** living in
`common.brain` (pure, unit-tested against a synthetic `CollisionView`), landing
behind the same three seams: the `MoveInput` fed to `ClientPhysics`, the aim
angles, and the action list. Per tick: an immutable, perception-delayed
`Perception` → a utility `Arbiter` over weighted `Goal`s (engage, circle-strafe,
retreat-to-heal, rod-poke, seek-food, …) with dwell/commit hysteresis and
exclusive latches → an `Intent` → the motor stack (`ContextSteering` slides along
walls, `ProactiveJump` hops a step before contact with momentum intact,
`AntiStuck` + a bounded `LocalPathPlanner` route around obstacles,
`MotorQuantizer` emits strictly digital `{-1,0,1}` impulses — orbit is a
radius-band duty-cycle, never a fractional forward) → `ClickController` (CPS clock
+ reach/aim-cone gate with latency prediction). All received velocity now funnels
through one ranked, deduplicated `KnockbackResolver` (own-id echo, melee
knockback, `PlayerVelocityEvent`, explosions), so StarEnchants/Mental/vanilla
pushes reach the clientless boxer without double-applying. Survival is decomposed
into `InvincibilityGuard` (same-tick lethal cap + top-up = burst-proof,
knockback-preserving), `DeathPolicyGuard` (drops + manual/auto respawn),
`HungerGuard`, and `PickupListener`.

## Known deferrals

- **Folia**: the `Scheduling` seam keeps the door open, but spawn/placement
  and cross-region brains need dedicated work; `folia-supported` stays unset
  until done.
- **World-scale pathfinding**: navigation is a Baritone-style 3D voxel A*
  (`BaritoneStylePlanner` — traverse/diagonal/ascend/descend/fall, tick-scaled
  `MoveCosts`, a vertical heuristic that reaches elevated targets, anytime partial
  paths) over the reactive steering motor, re-planned as the boxer moves. It is
  still **region-bounded** by design — every neighbour cell is gated through
  `CollisionView.isReadable`, so the search halts at the loaded/region frontier
  and never reads cross-region (Folia-safe). Rich enough for arena stairs,
  pillars, gaps, and platforms; it is not a global maze solver across regions.
- **Persistence**: boxers are ephemeral test fixtures — despawned cleanly on
  shutdown, never written to player data.

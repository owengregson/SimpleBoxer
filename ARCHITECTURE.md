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
  `Interact`, `Swing`) objects dispatched through the boxer's own
  `ServerGamePacketListenerImpl` on the owning thread — the same handlers,
  validation, ground-state bookkeeping, and Bukkit events
  (`PlayerMoveEvent`, `PlayerToggleSprintEvent`) a socket delivers for real
  clients.
- **Survival semantics stay vanilla**: damage events are never cancelled and
  damage amounts never altered (invulnerability would change the
  difference-rule and immunity-window behavior combat plugins implement);
  the default "invincible" mode restores health after vanilla processing
  completes and intercepts death by respawning in place. Hunger pins full
  via `FoodLevelChangeEvent` so sprint stays legal and no exhaustion noise
  leaks in.

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
| `api`    | Public surface other plugins consume: `Boxer`, `BoxerService`, `BoxerBehavior`, Bukkit events | `common` |
| `core`   | The plugin: NMS bridge (spawn/connection/packets), identity (skins/tab), guards, the brain, behaviors, commands, config | `api`, `common`, reflection-remapper (shaded) |
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

## Known deferrals

- **Folia**: the `Scheduling` seam keeps the door open, but spawn/placement
  and cross-region brains need dedicated work; `folia-supported` stays unset
  until done.
- **Pathfinding**: boxers sprint straight lines, strafe patterns, and jump
  single-block steps; they do not navigate mazes. Arena-style flat/simple
  terrain is the use case.
- **Persistence**: boxers are ephemeral test fixtures — despawned cleanly on
  shutdown, never written to player data.

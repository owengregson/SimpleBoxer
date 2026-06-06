# SimpleBoxer

Virtual sparring players for combat-plugin testing — built to pair with
[Mental](https://github.com/owengregson/Mental) and OldCombatMechanics on
Paper **1.17.1 → 26.x**.

A boxer is a **real player** to the server: a real `ServerPlayer` in the
player list, with a real inventory, real movement packets, and real
knockback. The brain driving it is an in-process client — it receives the
server's actual velocity packets, integrates them through the vanilla
client's motion math (decompile-pinned), and reports positions back through
the same movement handlers a socket would feed. Whatever shapes combat on
your server — Mental profiles, OCM modesets, plain vanilla — shapes the
boxer identically, because the boxer rides the same wire.

## The player-identity principle

No property of a boxer may make it take knockback or hit registration any
differently than a real player with identical state:

- Knockback arrives as `ClientboundSetEntityMotionPacket` on the boxer's
  connection and is integrated client-side — including the launch-tick
  ground decay, slipperiness, and every era nuance the integrator carries
  (`docs/research/2026-06-06-client-motion-pins.md`).
- Movement, sprint toggles, attacks, swings, teleport confirms and respawns
  leave as genuine serverbound packets through the boxer's own game
  listener — same validation, same Bukkit events.
- Damage to a boxer is **never cancelled or reduced**: invincibility takes
  the full hit (knockback, immunity window, `lastDamage`) and restores the
  health a tick later. One-shots still kill; the boxer respawns in place.
- Vanilla commands (`/tp`, `/effect`, `/give`, …) and other plugins target
  boxers like anyone else; they are tab-completable but hidden from the tab
  list.

## Quick start

```
/boxer spawn Bot                          # unhandicapped sparring partner at your feet
/boxer spawn Bot hard                     # the difficulty ladder: dummy/easy/medium/hard/expert/aimbot
/boxer spawn Bot expert skin:Notch target:YourName
/boxer target Bot YourName                # sic it on someone
/boxer set Bot ping 150                   # live tuning: ping/cps/reach/aim/wtap/preset/movement/invincible
/boxer pause Bot · /boxer resume Bot · /boxer remove all · /boxer info Bot
```

## What's configurable

| Knob | What it does |
| --- | --- |
| `ping-ms` | Simulated RTT, split symmetrically: perception (world + own knockback) and action (movement + clicks) each age one-way |
| `cps`, `click-jitter` | The clicking finger; clicks gate on reach + aim cone, out-of-cone clicks swing at air |
| `aim` | A spring-damper crosshair: presets `locked/sharp/smooth/sloppy` or granular stiffness/damping/max-velocity; underdamped springs overshoot strafe flips naturally |
| `reach`, `aim-tolerance-degrees` | Attack discipline |
| `w-tap` | Release-forward + sprint-drop after landed hits, re-arming sprint knockback like a human w-tapper |
| `movement` | `rush` / `strafe-circle` / `strafe-weave` / `stand`, stop distance, sprint |
| `invincible`, `feed-hunger` | Tireless test fixtures by default |

Difficulty presets bundle all of it; `config.yml` lets you define your own
as sparse overlays.

## Building & testing

```bash
./gradlew build                    # unit tests (59 pins: physics, aim, latency, settings)
./gradlew integrationTest          # real servers: floor (1.17.1) + ceiling (26.1.2)
./gradlew integrationTestMatrix    # every version in gradle.properties
./gradlew integrationTestCombat    # floor+ceiling WITH Mental/OCM staged:
                                   #   run/mental-jar/Mental.jar
                                   #   run/ocm-jar/OldCombatMechanics.jar
```

The in-server suite (20 cases) covers spawn/identity, the velocity-packet
architecture proof, follow/pause, immunity-gated combat rates, the w-tap
rhythm, knockback delivery, guards, measurable latency, and the command
round-trip. Results land in `run/<version>/plugins/SimpleBoxerTester/
test-results.txt` — trust those files, not the console banner.

## Architecture

See `ARCHITECTURE.md` for the module map, the captured-connection design,
threading rules, and the honest boundaries (in-process packets are invisible
to packet-sniffing plugins' listeners; Folia and pathfinding are deferred).

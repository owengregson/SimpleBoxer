# SimpleBoxer

**Virtual sparring players for testing combat plugins.** Spawn a bot that is
a *real player* to your server — real player-list entry, real inventory, real
movement packets, real knockback — then tune its ping, aim, click speed,
reach, and movement to spar against. Built for Paper **1.17.1 → 26.x**.

[![Minecraft](https://img.shields.io/badge/Minecraft-1.17.1%20%E2%86%92%2026.x-brightgreen)](#requirements)
[![Platform](https://img.shields.io/badge/platform-Paper-orange)](https://papermc.io)
[![Release](https://img.shields.io/github/v/release/owengregson/SimpleBoxer?include_prereleases&sort=semver)](https://github.com/owengregson/SimpleBoxer/releases/latest)

---

## Why SimpleBoxer?

Testing a combat plugin (knockback tuning, anti-cheat, OldCombatMechanics
modesets, [Mental](https://github.com/owengregson/Mental) profiles) usually
means rounding up real people. SimpleBoxer gives you opponents on demand.

A boxer is **indistinguishable from a real player to the server**. Whatever
shapes combat on your server shapes the boxer identically, because the boxer
rides the same wire:

- Knockback arrives as the same velocity packet a real client receives, and is
  integrated through the vanilla client's motion math — so a boxer flies back
  exactly like a player with the same ping would.
- Movement, sprinting, attacks, and swings leave as genuine player packets —
  same server validation, same Bukkit events (`PlayerMoveEvent`,
  `PlayerToggleSprintEvent`, …).
- Vanilla commands (`/tp`, `/effect`, `/give`) and other plugins target boxers
  like any player. They're hidden from the tab list but stay tab-completable.

> **Use case:** arena-style flat/simple terrain. Boxers sprint, strafe, and
> jump single-block steps — they don't path through mazes.

---

## Features

- 🥊 **Real-player bots** — no NPC shortcuts; the server sees a `ServerPlayer`.
- 🎚️ **Difficulty ladder** — `dummy` → `easy` → `medium` → `hard` → `expert` →
  `aimbot`, each a full behaviour bundle.
- 🛰️ **Simulated ping** — symmetric RTT that delays both perception and action,
  like a real laggy client.
- 🎯 **Spring-damper aim** — presets `locked`/`sharp`/`smooth`/`sloppy`, or tune
  stiffness, damping, and max turn speed by hand.
- 🖱️ **Configurable clicking** — CPS with jitter, reach and aim-cone gating.
- 🏃 **Movement styles** — `rush`, `strafe-circle`, `strafe-weave`, `stand`,
  with w-tap rhythm and stop-distance rings.
- ⚙️ **Live tuning** — change any knob at runtime with `/boxer set`.
- 🧩 **Skins & targets** — wear any account's skin; pre-bind a follow target.
- 🛡️ **Tireless fixtures** — invincible (full hit + restore) and well-fed by
  default, so a test never ends because the bot starved or died.

---

## Requirements

| | |
| --- | --- |
| **Server** | [Paper](https://papermc.io) (or a Paper fork) `1.17.1` through `26.x` |
| **Java** | 17+ for MC 1.17–1.20.4 · 21+ for MC 1.20.5 and newer |

> SimpleBoxer reaches into server internals (NMS) to make boxers real players.
> It targets Paper; Spigot/CraftBukkit are untested. If your exact version is
> unsupported the plugin disables itself cleanly and logs why — it never
> half-loads.

---

## Installation

1. **Download** the latest `SimpleBoxer-x.y.z.jar` from the
   [**Releases**](https://github.com/owengregson/SimpleBoxer/releases/latest)
   page.
2. **Drop it** into your server's `plugins/` folder.
3. **Restart** the server (a reload won't load a new plugin cleanly).
4. **Verify** — you should see `SimpleBoxer x.y.z enabled` in the console, and
   a `plugins/SimpleBoxer/config.yml` will be created.
5. **Spawn one** in-game:
   ```
   /boxer spawn Rival hard target:YourName
   ```

That's it. By default only operators can use the commands (see
[Permissions](#permissions)).

---

## Quick start

```
/boxer spawn Bot                       # an unhandicapped partner at your feet
/boxer spawn Bot hard                  # pick a difficulty from the ladder
/boxer spawn Bot expert skin:Notch target:Steve
/boxer target Bot Steve                # sic an existing boxer onto a player
/boxer set Bot ping 150                # tune anything, live
/boxer pause Bot                       # freeze its brain (it still takes hits)
/boxer info Bot                        # inspect its current settings
/boxer remove all                      # clean up
```

Alias: `/sb` works anywhere `/boxer` does.

---

## Commands

All commands are under `/boxer` (alias `/sb`).

| Command | Description |
| --- | --- |
| `/boxer spawn <name> [preset] [skin:<player>] [target:<player>] [at <x> <y> <z>]` | Spawn a boxer. With no preset it uses your `defaults`. `at` is required from console. |
| `/boxer remove <name\|all>` | Remove one boxer, or every boxer. |
| `/boxer list` | List live boxers with their target, ping, and CPS. |
| `/boxer info <name>` | Full settings dump for one boxer. |
| `/boxer target <name> <player\|none>` | Set or clear a boxer's follow/attack target. |
| `/boxer pause <name\|all>` | Freeze a boxer's brain (it still receives knockback). |
| `/boxer resume <name\|all>` | Un-freeze. |
| `/boxer set <name> <key> <value>` | Tune one setting at runtime (see below). |
| `/boxer reload` | Re-read `config.yml`. |

### Tunable keys (`/boxer set`)

| Key | Value | Example |
| --- | --- | --- |
| `ping` | whole number of ms (0–2000) | `/boxer set Bot ping 150` |
| `cps` | clicks per second (0–50; 0 = never attacks) | `/boxer set Bot cps 12` |
| `reach` | blocks (0.5–6) | `/boxer set Bot reach 3.2` |
| `aim` | `locked` / `sharp` / `smooth` / `sloppy` | `/boxer set Bot aim smooth` |
| `wtap` | `true` / `false` | `/boxer set Bot wtap true` |
| `movement` | `rush` / `strafe-circle` / `strafe-weave` / `stand` | `/boxer set Bot movement strafe-circle` |
| `preset` | any preset name | `/boxer set Bot preset expert` |
| `invincible` | `true` / `false` | `/boxer set Bot invincible false` |

Invalid input is answered in plain language — e.g. `set Bot ping abc` replies
`ping expects a whole number, not 'abc'.` rather than throwing.

---

## Difficulty presets

Each preset bundles ping, CPS, aim, reach discipline, w-tap, and movement into
a named tier. Every component stays individually overridable at spawn or with
`/boxer set`.

| Preset | Feel | Ping | CPS | Aim |
| --- | --- | --- | --- | --- |
| `dummy` | Stands still, never attacks — a punching bag | 0 | 0 | smooth |
| `easy` | High ping, slow sloppy clicks, walks | 120 | 4 | sloppy |
| `medium` | An ordinary player | 60 | 7 | smooth |
| `hard` | A practiced PvPer, disciplined w-taps | 35 | 10 | sharp |
| `expert` | Tournament-grade, circles its target | 15 | 13 | tight |
| `aimbot` | The calibrator: zero ping, locked aim | 0 | 16 | locked |

You can also define your own presets in `config.yml` as sparse overlays — an
entry there with a built-in's name overrides it.

---

## Permissions

Everything defaults to **operators only**. Grant these nodes to delegate.

| Node | Grants | Default |
| --- | --- | --- |
| `simpleboxer.command.use` | Run `/boxer`, plus `help`, `list`, `info` | op |
| `simpleboxer.command.spawn` | `spawn` and `remove` | op |
| `simpleboxer.command.control` | `target`, `pause`, `resume` | op |
| `simpleboxer.command.tune` | `set` | op |
| `simpleboxer.command.reload` | `reload` | op |
| `simpleboxer.*` | All of the above | op |

---

## Configuration

A documented `config.yml` is written to `plugins/SimpleBoxer/` on first start.
The two top-level blocks are `defaults` (applied to any spawn that names no
preset, and the base every preset overlays) and `presets` (your own named
overlays). Every key inherits a sensible default when omitted, and a malformed
value warns in the console and keeps the inherited value — **a typo can never
break a spawn.**

```yaml
# Keep boxers out of the tab list (still tab-completable in commands).
hide-from-tab: true

defaults:
  ping-ms: 0              # simulated RTT, 0–2000 (split: half perception, half action)
  cps: 8.0               # clicks per second, 0–50 (0 = never attacks)
  click-jitter: 0.3      # per-click interval wobble, 0–0.9
  aim:
    preset: sharp         # locked / sharp / smooth / sloppy
    # stiffness: 0.55     # optional granular overrides on top of the preset
    # damping: 0.30
    # max-velocity: 60.0
  reach: 3.0             # attack range in blocks, 0.5–6
  aim-tolerance-degrees: 10.0   # a click only attacks within this cone
  w-tap:
    enabled: false
    delay-ticks: 1        # ticks after a hit before forward releases (0–20)
    release-ticks: 2      # ticks forward stays released (1–20)
  movement:
    style: rush           # rush / strafe-circle / strafe-weave / stand
    stop-distance: 0.0    # 0 = hold W through the target (true rusher)
    sprint: true
  invincible: true        # take the full hit, then restore health
  feed-hunger: true       # pin hunger full so sprint stays legal

# Your own presets (sparse overlays over `defaults`):
presets:
  # laggy-spammer:
  #   ping-ms: 180
  #   cps: 14
  #   aim:
  #     preset: smooth
```

After editing, run `/boxer reload`.

---

## Developer API

Other plugins can spawn and control boxers. The `api` module exposes
`BoxerService`, obtainable from Bukkit's `ServicesManager`:

```java
BoxerService boxers = Bukkit.getServicesManager().load(BoxerService.class);

boxers.spawn(new BoxerSpawnRequest(
        "Rival",
        player.getLocation(),
        DifficultyPresets.HARD,
        "Notch",        // skin owner, or null
        player.getName() // target, or null
)).thenAccept(boxer -> boxer.setTarget(player));
```

`BoxerSpawnEvent` and `BoxerRemoveEvent` fire on the Bukkit event bus.

---

## Building from source

Requires a JDK 21+ (the build provisions a Java 25 toolchain automatically).

```bash
git clone https://github.com/owengregson/SimpleBoxer.git
cd SimpleBoxer
./gradlew build          # compiles + runs unit tests; jar in core/build/libs/
```

The shaded plugin jar is `core/build/libs/SimpleBoxer-<version>.jar`.

### Tests

```bash
./gradlew build                  # unit tests (physics, aim, latency, settings)
./gradlew integrationTest        # boots real Paper servers (floor + ceiling) and runs the in-server suite
./gradlew integrationTestMatrix  # every version listed in gradle.properties
```

Integration results land in
`run/<version>/plugins/SimpleBoxerTester/test-results.txt`.

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the module map, the
captured-connection design, threading rules, and the honest boundaries
(in-process packets are invisible to packet-sniffing plugins; Folia and
pathfinding are deferred).

---

## Honest boundaries

- In-process boxers have no socket, so their outbound packets don't traverse
  the netty pipeline — ProtocolLib/PacketEvents listeners won't see boxer
  traffic. The interesting direction (a real player attacking a boxer) works
  fully, because the attacker's own connection carries the packets.
- **Folia** isn't supported yet (the scheduling seam is ready; placement and
  cross-region brains aren't).
- Boxers are **ephemeral** — they're never written to player data and are
  despawned cleanly on shutdown.

---

## Credits

Built by [owengregson](https://github.com/owengregson) to pair with
[Mental](https://github.com/owengregson/Mental) and OldCombatMechanics.
Issues and pull requests welcome on
[GitHub](https://github.com/owengregson/SimpleBoxer).

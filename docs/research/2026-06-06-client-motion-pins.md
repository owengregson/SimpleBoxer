# Client motion pins — what ClientPhysics emulates

Source: Paper 1.21.11 server jar, Mojang-mapped, CFR-decompiled
(`legacy-lab/decomp-1.21.11` in the Mental repo). Player movement is
client-authoritative; the server carries the same shared `LivingEntity`
code, so the server jar is a faithful source for the *client's* integrator.
This model is stable across the supported range (1.17.1 → 26.x) — the
constants below predate 1.9 and the knocked-flight portion is byte-identical
back to 1.7.10 (see Mental's era research).

## The per-tick pipeline (player, on land)

Order matters; each step cites its decompile anchor.

1. **Input** — forward/strafe impulses ∈ {−1, 0, 1}; sneaking multiplies by
   `SNEAKING_SPEED` (0.3); `applyInput` scales both axes ×0.98
   (`LivingEntity.applyInput`, L3382). Effective travel vector magnitude for
   a held key: 0.98.
2. **Jump** — if jump held ∧ `onGround` ∧ `noJumpDelay == 0`:
   `vy = max(jumpPower, vy)` where
   `jumpPower = JUMP_STRENGTH(0.42) × blockJumpFactor + 0.1×(jumpBoost+1)`
   (`getJumpPower` L2679, `jumpFromGround` L2688 — note the **max**: a knock
   already lifting faster than 0.42 survives a jump). Sprinting adds the
   facing push `(−sin(yaw)·0.2, 0, cos(yaw)·0.2)`. While held,
   `noJumpDelay = 10`; releasing resets it (aiStep L3290+). Paper's server
   copy gates the sprint push behind a 250 ms `lastJumpTime` window — a
   Paper patch on the server entity only; real clients are ungated and the
   emulator models the client.
3. **travelInAir** (L2772):
   - `slip = onGround ? friction(block below feet) : 1.0`; the sampled block
     is at y − 0.5000001 (`getBlockPosBelowThatAffectsMyMovement`).
   - ground drag `f1 = slip × 0.91` (airborne: 0.91).
   - **input acceleration** `moveRelative(speed, travelVector)` (Entity
     L2115): input vector normalized only if length > 1, scaled by `speed`,
     rotated by yaw. `speed = onGround ?
     getSpeed() × (0.21600002 / slip³) : (sprinting ? 0.025999999 : 0.02)`
     (`getFrictionInfluencedSpeed` L2980, `Player.getFlyingSpeed` L1969).
     0.21600002 = 0.6³: the factor is exactly 1.0 on default ground, and
     *lowers* acceleration on ice (0.216/0.98³ ≈ 0.2295).
   - **move** with collisions (`Entity.collide` L1569): axis-separated
     sweeps in `axisStepOrder` (Y first, then the larger horizontal axis);
     step-up when grounded-or-landing and horizontally collided, trying
     candidate heights ≤ `STEP_HEIGHT` (0.6 attribute) and keeping the
     result with more horizontal progress. Downward collision ⇒ grounded,
     vy zeroed.
   - **drags, post-move, from the PRE-move ground state**:
     `vx,vz ×= f1`; `vy = (vy − gravity) × 0.98` with gravity 0.08
     (`DEFAULT_BASE_GRAVITY` L299; slow-falling caps at 0.01 while falling).
4. **Velocity packet (self)** — `ClientboundSetEntityMotionPacket` REPLACES
   `deltaMovement` (all three axes) when handled; the next tick's step 3
   integrates it. Explosions ADD. Position packets teleport and apply
   relative-flag velocity semantics.

## Attributes (player defaults)

| Attribute | Value |
| --- | --- |
| MOVEMENT_SPEED | 0.1 (`Player.createAttributes` L273) |
| sprint modifier | +0.3 ADD_MULTIPLIED_TOTAL ⇒ ×1.3 ⇒ 0.13 (LivingEntity L291) |
| SNEAKING_SPEED | 0.3 |
| JUMP_STRENGTH | 0.42 |
| STEP_HEIGHT | 0.6 |
| ENTITY_INTERACTION_RANGE | 3.0 (modern attack-reach default) |

The client reads MOVEMENT_SPEED from its attribute map, synced by
`ClientboundUpdateAttributesPacket` — Speed/Slowness potions, armor and
plugin modifiers all arrive that way, and `setSprinting` installs the ×1.3
modifier locally on BOTH sides. The brain stands in for the packet by
snapshotting the server-side Bukkit attribute each tick (sprint modifier
stripped — the integrator applies its own ×1.3 from the held key) and
aging it through the perception line; Jump Boost rides the same snapshot
in place of `ClientboundUpdateMobEffectPacket`. Air acceleration is the
hard-coded 0.02 / 0.026 (`Player.getFlyingSpeed`) — attribute-immune,
which is why Speed potions do nothing mid-air.

## Entity pushing (client-predicted, `Entity.push(Entity)`)

`LivingEntity.aiStep` runs `pushEntities()` AFTER travel — for every
player whose box overlaps (modern: no inflation), each side adds a shove
to its own deltaMovement that rides into the NEXT tick's move:

```
dx = other.x − x;  dz = other.z − z;  d = absMax(dx, dz)
if d ≥ 0.01:  d = √d;  shove = −(dx/d, dz/d) × min(1/d, 1) × 0.05F
```

The divisor is `√absMax`, not the vector norm (vanilla's ancient quirk),
and 0.05F promotes to 0.05000000074505806. The client predicts this for
its LOCAL player only — remote entities are interpolated, so each party
computes exactly its own half. This is why a W-holder bulldozes an AFK
body instead of stopping at it, and why the brain must model it: the
boxer's server entity follows only its move packets, so an unmodeled
pocket would let boxers stand inside their targets.

## Slipperiness (block under feet)

Default 0.6 (→ ground drag 0.546); ICE / PACKED_ICE / FROSTED_ICE 0.98;
BLUE_ICE 0.989; SLIME_BLOCK 0.8 (cross-checked against Mental's
`GroundFriction`, decompile-cited there).

## Derived equilibria (the unit-test pins)

Per-tick displacement at steady state is `a / (1 − f1)` (carry converges to
`a·f1/(1−f1)`, displacement = carry + accel):

| State | accel a | drag f1 | move/tick | m/s |
| --- | --- | --- | --- | --- |
| walk, stone | 0.1×0.98 = 0.098 | 0.546 | 0.21586 | 4.317 ✓ canon |
| sprint, stone | 0.13×0.98 = 0.1274 | 0.546 | 0.28061 | 5.612 ✓ canon |
| sprint, airborne | 0.026×0.98 = 0.02548 | 0.91 | 0.28311 | 5.662 |
| standing vy | — | — | −0.0784 equilibrium (grounded collision zeroes, next drag yields −0.0784) | |
| falling terminal | — | — | −3.92 | |
| jump apex | 0.42 impulse | — | ≈ +1.2523 blocks over 12 ticks | |

## Deliberate simplifications (documented, arena-appropriate)

- No fluids, ladders, elytra, levitation, cobwebs, powder snow, honey/soul
  sand speed factors, vehicles, or sneaking-edge clamping — boxers spar on
  open arena floors. The integrator asserts "plain air/ground" state and
  the brain avoids the rest.
- Step-up uses the classic full-step retry rather than 1.20's candidate
  enumeration — identical outcomes on ordinary stairs/slabs ≤ 0.6.
- Entity pushing reads LIVE server positions for neighbours rather than
  client-interpolated ones — interpolation is the one wire detail the
  brain does not model.

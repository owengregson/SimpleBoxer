package me.vexmc.simpleboxer.gui.menu;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.simpleboxer.common.aim.AimParams;
import me.vexmc.simpleboxer.common.settings.BoxerSettings;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.Combat;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.Death;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.Hunger;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.InvincibleMode;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.Items;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.Movement;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.SelfHeal;
import me.vexmc.simpleboxer.common.settings.BoxerSettings.WTap;
import me.vexmc.simpleboxer.common.settings.BoxerSettingsParser;
import me.vexmc.simpleboxer.common.settings.BoxerSettingsWriter;
import me.vexmc.simpleboxer.gui.menu.SettingDescriptor.CycleOption;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

/**
 * The one place every tunable knob is declared. Each {@link SettingDescriptor}
 * here maps one {@link BoxerSettings} field to a {@link SettingCategory} tile
 * and its click behaviour; the GUI reads this list and lays screens out
 * automatically, so a future knob is added <em>here</em> and nowhere else —
 * never as a hand-placed slot.
 *
 * <p>Every write goes through a {@code withX(...)} copy (or a rebuilt
 * sub-record), so the immutable settings and the pinned writer/parser
 * round-trip stay untouched. Cross-field bands (the rod range, the self-heal
 * band) are clamped inside their apply lambdas so a step can never build an
 * out-of-range record.</p>
 */
final class SettingsRegistry {

    private static final List<SettingDescriptor> ALL = build();

    private SettingsRegistry() {}

    static @NotNull List<SettingDescriptor> all() {
        return ALL;
    }

    static @NotNull List<SettingDescriptor> byCategory(@NotNull SettingCategory category) {
        List<SettingDescriptor> out = new ArrayList<>();
        for (SettingDescriptor d : ALL) {
            if (d.category() == category) {
                out.add(d);
            }
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /*  The descriptor table                                               */
    /* ------------------------------------------------------------------ */

    private static @NotNull List<SettingDescriptor> build() {
        List<SettingDescriptor> d = new ArrayList<>();

        /* ---- Aim & clicking -------------------------------------------- */
        d.add(SettingDescriptor.integer("ping", SettingCategory.AIM, "Ping", Material.CLOCK, "ms",
                s -> s.pingMs(), 10, 100, 0, 2000,
                (s, v) -> s.withPingMs((int) Math.round(v)),
                "§8Simulated round-trip latency (0-2000ms)."));
        d.add(SettingDescriptor.number("cps", SettingCategory.AIM, "CPS", Material.LEVER, "",
                s -> s.cps(), 0.5, 1.0, 0.0, 50.0, (s, v) -> s.withCps(v),
                "§8Clicks per second (0-50; 0 never attacks)."));
        d.add(SettingDescriptor.number("click-jitter", SettingCategory.AIM, "Click jitter",
                Material.SUGAR, "", s -> s.clickJitter(), 0.05, 0.1, 0.0, 0.9,
                (s, v) -> s.withClickJitter(v),
                "§8Per-click interval wobble (0-0.9)."));
        d.add(SettingDescriptor.number("reach", SettingCategory.AIM, "Reach", Material.FISHING_ROD,
                "", s -> s.reach(), 0.1, 0.5, 0.5, 6.0, (s, v) -> s.withReach(v),
                "§8Attack range in blocks (0.5-6)."));
        d.add(SettingDescriptor.number("aim-cone", SettingCategory.AIM, "Aim cone",
                Material.SPYGLASS, "°", s -> s.aimToleranceDegrees(), 1.0, 10.0, 0.0, 180.0,
                (s, v) -> s.withAimToleranceDegrees(v),
                "§8A click only attacks within this cone."));
        d.add(SettingDescriptor.cycle("aim-spring", SettingCategory.AIM, "Aim spring",
                Material.ENDER_EYE, "custom", aimOptions(),
                "§8How the crosshair chases the target.",
                "§8locked / sharp / smooth / sloppy"));

        /* ---- Combat ---------------------------------------------------- */
        d.add(SettingDescriptor.toggle("block-hit", SettingCategory.COMBAT, "Block-hit",
                Material.SHIELD, s -> s.combat().blockHit(),
                s -> s.withCombat(combat(s).blockHit(!s.combat().blockHit())),
                "§8Sword-block between hits to re-arm the",
                "§8sprint knockback bonus."));
        d.add(SettingDescriptor.toggle("rod-knockback", SettingCategory.COMBAT, "Rod knockback",
                Material.FISHING_ROD, s -> s.combat().rodKnockback(),
                s -> s.withCombat(combat(s).rodKnockback(!s.combat().rodKnockback())),
                "§8Rod-poke an approaching target back",
                "§8before committing to a melee combo."));
        d.add(SettingDescriptor.number("rod-min", SettingCategory.COMBAT, "Rod min range",
                Material.TRIPWIRE_HOOK, "", s -> s.combat().rodMin(), 0.1, 0.5, 0.5, 6.0,
                (s, v) -> s.withCombat(combat(s).rodMin(Math.min(v, s.combat().rodMax()))),
                "§8Nearest range the boxer will rod-poke from.",
                "§8(Kept at or below rod max.)"));
        d.add(SettingDescriptor.number("rod-max", SettingCategory.COMBAT, "Rod max range",
                Material.LEAD, "", s -> s.combat().rodMax(), 0.1, 0.5, 0.5, 6.0,
                (s, v) -> s.withCombat(combat(s).rodMax(Math.max(v, s.combat().rodMin()))),
                "§8Farthest range the boxer will rod-poke from.",
                "§8(Kept at or above rod min.)"));
        d.add(SettingDescriptor.toggle("adaptive-strafe", SettingCategory.COMBAT, "Adaptive strafe",
                Material.COMPASS, s -> s.combat().adaptiveStrafe(),
                s -> s.withCombat(combat(s).adaptiveStrafe(!s.combat().adaptiveStrafe())),
                "§8Pick strafe direction from how the target",
                "§8is tracking — break a tight aim, exploit a miss."));
        d.add(SettingDescriptor.toggle("s-tap", SettingCategory.COMBAT, "S-tap",
                Material.SUGAR, s -> s.combat().sTap(),
                s -> s.withCombat(combat(s).sTap(!s.combat().sTap())),
                "§8Straight-line s-tap combos (sprint reset,",
                "§8no A/D strafe)."));
        d.add(SettingDescriptor.number("miss-chance", SettingCategory.COMBAT, "Miss chance",
                Material.GUNPOWDER, "", s -> s.combat().missChance(), 0.05, 0.1, 0.0, 1.0,
                (s, v) -> s.withCombat(combat(s).missChance(v)),
                "§8Fraction of clicks aimed intentionally off (0-1)."));

        /* ---- Movement -------------------------------------------------- */
        d.add(SettingDescriptor.cycle("movement-style", SettingCategory.MOVEMENT, "Movement",
                Material.LEATHER_BOOTS, "custom", movementOptions(),
                "§8How the boxer closes distance.",
                "§8rush / strafe-circle / strafe-weave / stand"));
        d.add(SettingDescriptor.number("stop-distance", SettingCategory.MOVEMENT, "Stop distance",
                Material.TARGET, "", s -> s.movement().stopDistance(), 0.5, 1.0, 0.0, 6.0,
                (s, v) -> s.withMovement(new Movement(s.movement().style(), v, s.movement().sprint())),
                "§80 = hold W through the target (true rusher)."));
        d.add(SettingDescriptor.toggle("sprint", SettingCategory.MOVEMENT, "Sprint",
                Material.RABBIT_FOOT, s -> s.movement().sprint(),
                s -> s.withMovement(new Movement(s.movement().style(),
                        s.movement().stopDistance(), !s.movement().sprint())),
                "§8Hold sprint whenever forward is down.",
                "§8(Speed/Slowness still apply on top.)"));

        /* ---- Survival -------------------------------------------------- */
        d.add(SettingDescriptor.toggle("invincible", SettingCategory.SURVIVAL, "Invincible",
                Material.TOTEM_OF_UNDYING, s -> s.invincible(),
                s -> s.withInvincible(!s.invincible()),
                "§8Survive every hit — knockback and events",
                "§8still land; the damage never sticks."));
        d.add(SettingDescriptor.cycle("invincible-mode", SettingCategory.SURVIVAL, "Invincible mode",
                Material.GOLDEN_APPLE, "custom", invincibleModeOptions(),
                "§8zero-damage: hit registers, damage zeroed.",
                "§8legacy-restore: take it, restore next tick."));
        d.add(SettingDescriptor.toggle("death-drop-items", SettingCategory.SURVIVAL, "Drop items on death",
                Material.BONE, s -> s.death().dropItemsOnDeath(),
                s -> s.withDeath(new Death(!s.death().dropItemsOnDeath(), s.death().mode())),
                "§8Scatter the kit on death (a real fighter's",
                "§8drop) vs. keep it for the next round."));
        d.add(SettingDescriptor.cycle("death-mode", SettingCategory.SURVIVAL, "Death mode",
                Material.SKELETON_SKULL, "custom", deathModeOptions(),
                "§8manual: stay down until respawned.",
                "§8auto-respawn: pop back up in place."));
        d.add(SettingDescriptor.toggle("feed-hunger", SettingCategory.SURVIVAL, "Feed hunger",
                Material.COOKED_BEEF, s -> s.feedHunger(),
                s -> s.withFeedHunger(!s.feedHunger()),
                "§8Pin hunger full so sprint stays legal."));
        d.add(SettingDescriptor.toggle("hunger-natural", SettingCategory.SURVIVAL, "Natural hunger",
                Material.WHEAT, s -> s.hunger().natural(),
                s -> s.withHunger(new Hunger(!s.hunger().natural(), s.hunger().eatThreshold())),
                "§8Let vanilla exhaustion drain food; the boxer",
                "§8eats when it drops to the threshold below."));
        d.add(SettingDescriptor.integer("eat-threshold", SettingCategory.SURVIVAL, "Eat threshold",
                Material.BREAD, "", s -> s.hunger().eatThreshold(), 1, 5, 0, 20,
                (s, v) -> s.withHunger(new Hunger(s.hunger().natural(), (int) Math.round(v))),
                "§8Food level the boxer eats at (0-20).",
                "§8Only used with natural hunger on."));
        d.add(SettingDescriptor.toggle("self-heal", SettingCategory.SURVIVAL, "Self-heal",
                Material.SPLASH_POTION, s -> s.selfHeal().enabled(),
                s -> s.withSelfHeal(selfHeal(s).enabled(!s.selfHeal().enabled())),
                "§8Retreat and splash instant-health when low,",
                "§8then re-engage (mortal boxers only)."));
        d.add(SettingDescriptor.number("heal-trigger", SettingCategory.SURVIVAL, "Heal trigger HP",
                Material.REDSTONE, "hp", s -> s.selfHeal().triggerHealth(), 1.0, 2.0, 0.0, 20.0,
                (s, v) -> s.withSelfHeal(selfHeal(s)
                        .triggerHealth(Math.min(v, s.selfHeal().resumeHealth()))),
                "§8Health that makes the boxer disengage (0-20).",
                "§8(Kept at or below the resume health.)"));
        d.add(SettingDescriptor.number("heal-resume", SettingCategory.SURVIVAL, "Heal resume HP",
                Material.GLOWSTONE_DUST, "hp", s -> s.selfHeal().resumeHealth(), 1.0, 2.0, 0.0, 20.0,
                (s, v) -> s.withSelfHeal(selfHeal(s)
                        .resumeHealth(Math.max(v, s.selfHeal().triggerHealth()))),
                "§8Health it heals back to before re-engaging.",
                "§8(Kept at or above the trigger health.)"));
        d.add(SettingDescriptor.integer("splash-cap", SettingCategory.SURVIVAL, "Splash cap",
                Material.BREWING_STAND, "", s -> s.selfHeal().splashCap(), 1, 6, 0, 36,
                (s, v) -> s.withSelfHeal(selfHeal(s).splashCap((int) Math.round(v))),
                "§8Most pots spent on one heal (0-36)."));

        /* ---- Items ----------------------------------------------------- */
        d.add(SettingDescriptor.toggle("auto-pickup", SettingCategory.ITEMS, "Auto-pickup",
                Material.HOPPER, s -> s.items().autoPickup(),
                s -> s.withItems(items(s).autoPickup(!s.items().autoPickup())),
                "§8Vacuum nearby dropped items into the",
                "§8boxer's real inventory."));
        d.add(SettingDescriptor.toggle("lock-loadout", SettingCategory.ITEMS, "Lock loadout",
                Material.IRON_BARS, s -> s.items().lockLoadout(),
                s -> s.withItems(items(s).lockLoadout(!s.items().lockLoadout())),
                "§8Re-stamp the kit every tick — a pure fixture",
                "§8whose gear never changes."));
        d.add(SettingDescriptor.toggle("unbreakable-kit", SettingCategory.ITEMS, "Unbreakable kit",
                Material.DIAMOND_CHESTPLATE, s -> s.items().unbreakableKit(),
                s -> s.withItems(items(s).unbreakableKit(!s.items().unbreakableKit())),
                "§8Stamp every kit piece Unbreakable. Off — the",
                "§8default — wears armor on hit and weapons on",
                "§8attack like a real player (locked kits stay",
                "§8unbreakable regardless)."));
        d.add(SettingDescriptor.integer("weapon-slot", SettingCategory.ITEMS, "Weapon slot",
                Material.DIAMOND_SWORD, "", s -> s.items().weaponSlot(), 1, 1, 0, 8,
                (s, v) -> s.withItems(items(s).weaponSlot((int) Math.round(v))),
                "§8Hotbar slot holding the melee weapon (0-8)."));
        d.add(SettingDescriptor.integer("rod-slot", SettingCategory.ITEMS, "Rod slot",
                Material.FISHING_ROD, "", s -> s.items().rodSlot(), 1, 1, 0, 8,
                (s, v) -> s.withItems(items(s).rodSlot((int) Math.round(v))),
                "§8Hotbar slot holding the fishing rod (0-8)."));
        d.add(SettingDescriptor.integer("pot-slot", SettingCategory.ITEMS, "Potion slot",
                Material.SPLASH_POTION, "", s -> s.items().potSlot(), 1, 1, 0, 8,
                (s, v) -> s.withItems(items(s).potSlot((int) Math.round(v))),
                "§8Hotbar slot holding heal potions (0-8)."));
        d.add(SettingDescriptor.toggle("fill-splash-pots", SettingCategory.ITEMS, "Fill splash pots",
                Material.SPLASH_POTION, s -> s.items().fillSplashPots(),
                s -> s.withItems(items(s).fillSplashPots(!s.items().fillSplashPots())),
                "§8Seed the hotbar with a finite supply of",
                "§8instant-health splash potions the boxer",
                "§8throws to heal — and can run out of."));
        d.add(SettingDescriptor.integer("splash-pot-count", SettingCategory.ITEMS, "Splash pot count",
                Material.BREWING_STAND, "", s -> s.items().splashPotCount(), 1, 3, 0, 9,
                (s, v) -> s.withItems(items(s).splashPotCount((int) Math.round(v))),
                "§8How many splash potions to seed (0-9),",
                "§8when Fill splash pots is on."));
        d.add(SettingDescriptor.integer("food-slot", SettingCategory.ITEMS, "Food slot",
                Material.COOKED_BEEF, "", s -> s.items().foodSlot(), 1, 1, 0, 8,
                (s, v) -> s.withItems(items(s).foodSlot((int) Math.round(v))),
                "§8Hotbar slot holding food (0-8)."));
        d.add(SettingDescriptor.integer("block-slot", SettingCategory.ITEMS, "Block slot",
                Material.COBBLESTONE, "", s -> s.items().blockSlot(), 1, 1, 0, 8,
                (s, v) -> s.withItems(items(s).blockSlot((int) Math.round(v))),
                "§8Hotbar slot holding blocks to place (0-8)."));

        /* ---- W-tap ----------------------------------------------------- */
        d.add(SettingDescriptor.toggle("wtap", SettingCategory.WTAP, "W-tap",
                Material.FEATHER, s -> s.wtap().enabled(),
                s -> s.withWtap(new WTap(!s.wtap().enabled(),
                        s.wtap().delayTicks(), s.wtap().releaseTicks())),
                "§8Release+re-press forward after a hit to",
                "§8re-arm sprint knockback."));
        d.add(SettingDescriptor.integer("wtap-delay", SettingCategory.WTAP, "W-tap delay",
                Material.REPEATER, "t", s -> s.wtap().delayTicks(), 1, 5, 0, 20,
                (s, v) -> s.withWtap(new WTap(s.wtap().enabled(),
                        (int) Math.round(v), s.wtap().releaseTicks())),
                "§8Ticks after a hit before forward releases (0-20)."));
        d.add(SettingDescriptor.integer("wtap-release", SettingCategory.WTAP, "W-tap release",
                Material.REDSTONE, "t", s -> s.wtap().releaseTicks(), 1, 5, 1, 20,
                (s, v) -> s.withWtap(new WTap(s.wtap().enabled(),
                        s.wtap().delayTicks(), (int) Math.round(v))),
                "§8Ticks forward stays released (1-20)."));

        return List.copyOf(d);
    }

    /* ------------------------------------------------------------------ */
    /*  Cycle option tables                                                */
    /* ------------------------------------------------------------------ */

    private static @NotNull List<CycleOption> aimOptions() {
        List<CycleOption> options = new ArrayList<>();
        for (String name : List.of("locked", "sharp", "smooth", "sloppy")) {
            AimParams resolved = BoxerSettingsParser.aimPresetByName(name);
            AimParams params = resolved != null ? resolved : AimParams.SHARP;
            options.add(new CycleOption(name,
                    s -> s.withAim(params),
                    s -> name.equals(BoxerSettingsWriter.aimPresetName(s.aim()))));
        }
        return options;
    }

    private static @NotNull List<CycleOption> movementOptions() {
        List<CycleOption> options = new ArrayList<>();
        for (Movement.Style style : List.of(Movement.Style.RUSH, Movement.Style.STRAFE_CIRCLE,
                Movement.Style.STRAFE_WEAVE, Movement.Style.STAND)) {
            options.add(new CycleOption(BoxerSettingsWriter.styleName(style),
                    s -> s.withMovement(new Movement(style,
                            s.movement().stopDistance(), s.movement().sprint())),
                    s -> s.movement().style() == style));
        }
        return options;
    }

    private static @NotNull List<CycleOption> invincibleModeOptions() {
        List<CycleOption> options = new ArrayList<>();
        for (InvincibleMode mode : InvincibleMode.values()) {
            options.add(new CycleOption(BoxerSettingsWriter.token(mode.name()),
                    s -> s.withInvincibleMode(mode),
                    s -> s.invincibleMode() == mode));
        }
        return options;
    }

    private static @NotNull List<CycleOption> deathModeOptions() {
        List<CycleOption> options = new ArrayList<>();
        for (Death.Mode mode : List.of(Death.Mode.MANUAL, Death.Mode.AUTO_RESPAWN)) {
            options.add(new CycleOption(BoxerSettingsWriter.token(mode.name()),
                    s -> s.withDeath(new Death(s.death().dropItemsOnDeath(), mode)),
                    s -> s.death().mode() == mode));
        }
        return options;
    }

    /* ------------------------------------------------------------------ */
    /*  Sub-record field-swappers — a tiny fluent copy per nested record   */
    /*  so the descriptor lambdas read one field at a time.                */
    /* ------------------------------------------------------------------ */

    private static @NotNull CombatEdit combat(@NotNull BoxerSettings s) {
        return new CombatEdit(s.combat());
    }

    private static @NotNull SelfHealEdit selfHeal(@NotNull BoxerSettings s) {
        return new SelfHealEdit(s.selfHeal());
    }

    private static @NotNull ItemsEdit items(@NotNull BoxerSettings s) {
        return new ItemsEdit(s.items());
    }

    private record CombatEdit(@NotNull Combat c) {
        @NotNull Combat blockHit(boolean v) {
            return new Combat(v, c.rodKnockback(), c.rodMin(), c.rodMax(),
                    c.adaptiveStrafe(), c.sTap(), c.missChance());
        }
        @NotNull Combat rodKnockback(boolean v) {
            return new Combat(c.blockHit(), v, c.rodMin(), c.rodMax(),
                    c.adaptiveStrafe(), c.sTap(), c.missChance());
        }
        @NotNull Combat rodMin(double v) {
            return new Combat(c.blockHit(), c.rodKnockback(), v, c.rodMax(),
                    c.adaptiveStrafe(), c.sTap(), c.missChance());
        }
        @NotNull Combat rodMax(double v) {
            return new Combat(c.blockHit(), c.rodKnockback(), c.rodMin(), v,
                    c.adaptiveStrafe(), c.sTap(), c.missChance());
        }
        @NotNull Combat adaptiveStrafe(boolean v) {
            return new Combat(c.blockHit(), c.rodKnockback(), c.rodMin(), c.rodMax(),
                    v, c.sTap(), c.missChance());
        }
        @NotNull Combat sTap(boolean v) {
            return new Combat(c.blockHit(), c.rodKnockback(), c.rodMin(), c.rodMax(),
                    c.adaptiveStrafe(), v, c.missChance());
        }
        @NotNull Combat missChance(double v) {
            return new Combat(c.blockHit(), c.rodKnockback(), c.rodMin(), c.rodMax(),
                    c.adaptiveStrafe(), c.sTap(), v);
        }
    }

    private record SelfHealEdit(@NotNull SelfHeal h) {
        @NotNull SelfHeal enabled(boolean v) {
            return new SelfHeal(v, h.triggerHealth(), h.resumeHealth(), h.splashCap());
        }
        @NotNull SelfHeal triggerHealth(double v) {
            return new SelfHeal(h.enabled(), v, h.resumeHealth(), h.splashCap());
        }
        @NotNull SelfHeal resumeHealth(double v) {
            return new SelfHeal(h.enabled(), h.triggerHealth(), v, h.splashCap());
        }
        @NotNull SelfHeal splashCap(int v) {
            return new SelfHeal(h.enabled(), h.triggerHealth(), h.resumeHealth(), v);
        }
    }

    private record ItemsEdit(@NotNull Items i) {
        @NotNull Items autoPickup(boolean v) {
            return with(v, i.lockLoadout(), i.weaponSlot(), i.rodSlot(), i.potSlot(),
                    i.foodSlot(), i.blockSlot(), i.unbreakableKit(), i.fillSplashPots(), i.splashPotCount());
        }
        @NotNull Items lockLoadout(boolean v) {
            return with(i.autoPickup(), v, i.weaponSlot(), i.rodSlot(), i.potSlot(),
                    i.foodSlot(), i.blockSlot(), i.unbreakableKit(), i.fillSplashPots(), i.splashPotCount());
        }
        @NotNull Items weaponSlot(int v) {
            return with(i.autoPickup(), i.lockLoadout(), v, i.rodSlot(), i.potSlot(),
                    i.foodSlot(), i.blockSlot(), i.unbreakableKit(), i.fillSplashPots(), i.splashPotCount());
        }
        @NotNull Items rodSlot(int v) {
            return with(i.autoPickup(), i.lockLoadout(), i.weaponSlot(), v, i.potSlot(),
                    i.foodSlot(), i.blockSlot(), i.unbreakableKit(), i.fillSplashPots(), i.splashPotCount());
        }
        @NotNull Items potSlot(int v) {
            return with(i.autoPickup(), i.lockLoadout(), i.weaponSlot(), i.rodSlot(), v,
                    i.foodSlot(), i.blockSlot(), i.unbreakableKit(), i.fillSplashPots(), i.splashPotCount());
        }
        @NotNull Items foodSlot(int v) {
            return with(i.autoPickup(), i.lockLoadout(), i.weaponSlot(), i.rodSlot(), i.potSlot(),
                    v, i.blockSlot(), i.unbreakableKit(), i.fillSplashPots(), i.splashPotCount());
        }
        @NotNull Items blockSlot(int v) {
            return with(i.autoPickup(), i.lockLoadout(), i.weaponSlot(), i.rodSlot(), i.potSlot(),
                    i.foodSlot(), v, i.unbreakableKit(), i.fillSplashPots(), i.splashPotCount());
        }
        @NotNull Items unbreakableKit(boolean v) {
            return with(i.autoPickup(), i.lockLoadout(), i.weaponSlot(), i.rodSlot(), i.potSlot(),
                    i.foodSlot(), i.blockSlot(), v, i.fillSplashPots(), i.splashPotCount());
        }
        @NotNull Items fillSplashPots(boolean v) {
            return with(i.autoPickup(), i.lockLoadout(), i.weaponSlot(), i.rodSlot(), i.potSlot(),
                    i.foodSlot(), i.blockSlot(), i.unbreakableKit(), v, i.splashPotCount());
        }
        @NotNull Items splashPotCount(int v) {
            return with(i.autoPickup(), i.lockLoadout(), i.weaponSlot(), i.rodSlot(), i.potSlot(),
                    i.foodSlot(), i.blockSlot(), i.unbreakableKit(), i.fillSplashPots(), v);
        }
        private static @NotNull Items with(boolean autoPickup, boolean lockLoadout, int weaponSlot,
                int rodSlot, int potSlot, int foodSlot, int blockSlot, boolean unbreakableKit,
                boolean fillSplashPots, int splashPotCount) {
            return new Items(autoPickup, lockLoadout, weaponSlot, rodSlot, potSlot, foodSlot,
                    blockSlot, unbreakableKit, fillSplashPots, splashPotCount);
        }
    }
}

package me.vexmc.simpleboxer.tester;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.simpleboxer.SimpleBoxerPlugin;
import me.vexmc.simpleboxer.common.scheduling.Scheduling;
import me.vexmc.simpleboxer.common.scheduling.TaskHandle;
import me.vexmc.simpleboxer.tester.suite.BootSuite;
import me.vexmc.simpleboxer.tester.suite.SpawnSuite;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Boots inside a real server next to SimpleBoxer, waits for the world to
 * settle, runs the suite, writes PASS/FAIL for the Gradle build, and shuts
 * the server down. When Mental and/or OldCombatMechanics are installed the
 * coexistence suites join the run — that pairing is the reason this plugin
 * exists.
 */
public final class SBTesterPlugin extends JavaPlugin {

    private static final long SETTLE_TICKS = 40L;

    @Override
    public void onEnable() {
        try {
            start();
        } catch (Throwable failure) {
            // An enable-time explosion (missing class, broken dependency)
            // must STILL answer the Gradle build and shut the server down —
            // a silent orphan holds the port and session lock for every
            // subsequent boot.
            getLogger().severe("Tester failed to start: " + failure);
            TestResultWriter.write(this, false, List.of("tester enable: " + failure));
            getServer().shutdown();
        }
    }

    private void start() {
        SimpleBoxerPlugin simpleBoxer =
                (SimpleBoxerPlugin) getServer().getPluginManager().getPlugin("SimpleBoxer");
        if (simpleBoxer == null || !simpleBoxer.isEnabled()) {
            getLogger().severe("SimpleBoxer is not installed/enabled — cannot test");
            TestResultWriter.write(this, false, List.of("SimpleBoxer plugin missing or disabled"));
            getServer().shutdown();
            return;
        }

        Scheduling scheduling = simpleBoxer.scheduling();
        TaskHandle[] starter = new TaskHandle[1];
        starter[0] = scheduling.repeatGlobal(SETTLE_TICKS, 72_000L, () -> {
            starter[0].cancel();
            List<TestCase> suite = new ArrayList<>(BootSuite.tests(simpleBoxer));
            suite.addAll(SpawnSuite.tests(simpleBoxer));
            new TestHarness(this, scheduling).run(suite);
        });
    }
}

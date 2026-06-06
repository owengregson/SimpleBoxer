package me.vexmc.simpleboxer.tester.suite;

import java.util.List;
import me.vexmc.simpleboxer.SimpleBoxerPlugin;
import me.vexmc.simpleboxer.tester.TestCase;
import org.jetbrains.annotations.NotNull;

/** Sanity: the plugin enabled cleanly and its services are reachable. */
public final class BootSuite {

    private BootSuite() {}

    public static @NotNull List<TestCase> tests(@NotNull SimpleBoxerPlugin simpleBoxer) {
        return List.of(
                new TestCase("boot: plugin is enabled", context ->
                        context.expect(simpleBoxer.isEnabled(), "SimpleBoxer should be enabled")),

                new TestCase("boot: scheduling service is live", context -> {
                    String backend = context.sync(() -> simpleBoxer.scheduling().describe());
                    context.expect(!backend.isBlank(), "scheduling backend should describe itself");
                    context.note("scheduling backend: " + backend);
                }));
    }
}

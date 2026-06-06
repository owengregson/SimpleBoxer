package me.vexmc.simpleboxer.common.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickSchedulerTest {

    /** Ticks the scheduler at 50 ms steps for {@code seconds}, counts clicks. */
    private static int clicksOver(ClickScheduler scheduler, int seconds) {
        int clicks = 0;
        for (long now = 0; now < seconds * 1000L; now += 50) {
            if (scheduler.shouldClick(now)) {
                clicks++;
            }
        }
        return clicks;
    }

    @Test
    void tenCpsLandsSixHundredClicksAMinute() {
        ClickScheduler scheduler = new ClickScheduler(10.0, 0.0, 42L);
        int clicks = clicksOver(scheduler, 60);
        assertEquals(600, clicks, 2, "10 CPS over 60s");
    }

    @Test
    void jitterPreservesTheLongRunRate() {
        ClickScheduler scheduler = new ClickScheduler(12.0, 0.5, 7L);
        int clicks = clicksOver(scheduler, 120);
        assertEquals(1440, clicks, 40, "12 CPS over 120s with heavy jitter");
    }

    @Test
    void twentyCpsSaturatesAtOnePerTick() {
        ClickScheduler scheduler = new ClickScheduler(20.0, 0.0, 1L);
        for (long now = 0; now < 5000; now += 50) {
            scheduler.shouldClick(now); // at most one per call by contract
        }
        int clicks = clicksOver(new ClickScheduler(20.0, 0.0, 1L), 10);
        assertEquals(200, clicks, 4, "20 CPS at 20 TPS = every tick");
    }

    @Test
    void zeroCpsNeverClicks() {
        ClickScheduler scheduler = new ClickScheduler(0.0, 0.0, 5L);
        assertEquals(0, clicksOver(scheduler, 10));
    }

    @Test
    void sameSeedClicksIdentically() {
        ClickScheduler a = new ClickScheduler(14.0, 0.4, 99L);
        ClickScheduler b = new ClickScheduler(14.0, 0.4, 99L);
        for (long now = 0; now < 30_000; now += 50) {
            assertEquals(a.shouldClick(now), b.shouldClick(now), "deterministic at t=" + now);
        }
    }

    @Test
    void retuneTakesEffect() {
        ClickScheduler scheduler = new ClickScheduler(5.0, 0.0, 3L);
        clicksOver(scheduler, 5);
        scheduler.retune(15.0, 0.0);
        int after = 0;
        for (long now = 5000; now < 25_000; now += 50) {
            if (scheduler.shouldClick(now)) {
                after++;
            }
        }
        assertEquals(300, after, 3, "15 CPS over the next 20s");
    }

    @Test
    void firstCallNeverClicksImmediately() {
        ClickScheduler scheduler = new ClickScheduler(20.0, 0.0, 11L);
        assertFalse(scheduler.shouldClick(0), "the finger arms before it fires");
        assertTrue(clicksOver(scheduler, 2) > 30, "then fires on schedule");
    }
}

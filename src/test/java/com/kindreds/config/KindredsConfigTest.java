package com.kindreds.config;
import org.junit.jupiter.api.*; import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*; import static org.junit.jupiter.api.Assertions.*;
class KindredsConfigTest {
  @Test void defaultsRoundTripAndPreset(@TempDir Path dir) {
    Path f = dir.resolve("k.json");
    KindredsConfig c = KindredsConfig.load(f);
    assertEquals(DeathPenalty.KEEP, c.deathPenalty);
    assertTrue(c.enableVision); assertEquals(1.0, c.xpRateGlobal, 1e-9);
    assertTrue(Files.exists(f));
    c.applyPreset("legendary");
    assertEquals(DeathPenalty.LOSE_PERCENT, c.deathPenalty);
    c.save(f);
    assertEquals(DeathPenalty.LOSE_PERCENT, KindredsConfig.load(f).deathPenalty);
  }

  // Task 13 Fix 4: enableCurses/enableVision are now read (CurseService/CurseContextService,
  // SetVisionLensC2S) rather than just declared. The actual gating is MC-bound (touches a live
  // ServerPlayerEntity), so - matching AbilityApplier's "compile-verified only" precedent for
  // MC-bound code - it isn't unit-tested here; this just locks down the plain-data contract those
  // gates read from: defaults, and that both fields persist through a save/load round trip.
  @Test void enableCursesAndEnableVisionDefaultTrueAndRoundTrip(@TempDir Path dir) {
    Path f = dir.resolve("k.json");
    KindredsConfig c = KindredsConfig.load(f);
    assertTrue(c.enableCurses);
    assertTrue(c.enableVision);

    c.enableCurses = false;
    c.enableVision = false;
    c.save(f);

    KindredsConfig reloaded = KindredsConfig.load(f);
    assertFalse(reloaded.enableCurses);
    assertFalse(reloaded.enableVision);
  }
}

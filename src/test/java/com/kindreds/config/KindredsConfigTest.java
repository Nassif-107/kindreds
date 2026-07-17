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
}

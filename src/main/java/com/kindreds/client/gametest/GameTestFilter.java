package com.kindreds.client.gametest;

/**
 * Which client gametests should run this time.
 *
 * <p>All the entrypoints in {@code fabric.mod.json} fire on every client gametest run, which is
 * right when the question is "does anything anywhere still work" and wrong when it is "is the
 * inscriptions stone column fixed yet" - the second question does not need eight screenshots and a
 * dozen abilities first, and waiting minutes for it discourages checking at all.
 *
 * <p>So {@code -Dkindreds.gametest.only=<name>} narrows the run. Unset, everything runs exactly as
 * before.
 */
public final class GameTestFilter {

    private static final String ONLY = System.getProperty("kindreds.gametest.only", "");

    private GameTestFilter() {
    }

    /** Whether a test with this name should run now. */
    public static boolean shouldRun(String name) {
        return ONLY.isEmpty() || ONLY.equalsIgnoreCase(name);
    }

    /** Logs and answers, for an entrypoint that is about to skip itself. */
    public static boolean skip(String name, org.slf4j.Logger logger) {
        if (shouldRun(name)) {
            return false;
        }
        logger.info("[kindreds] skipping {} (kindreds.gametest.only={})", name, ONLY);
        return true;
    }
}

package com.kindreds.client;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Remembers which <b>discipline</b> the skill page should open on - the one you were last reading or
 * last spent a point in.
 *
 * <p>It used to remember which <em>page</em> {@code K} opened, and that was the wrong thing to
 * remember. The hub is one keypress away from everywhere, so re-opening the last page saved nothing
 * and cost the player their bearings: {@code K} would drop them into a screen they had not asked for
 * and had to back out of to reach the one they wanted. A menu key that does not open the menu is a
 * menu key that is wrong. {@code K} now always opens the hub.
 *
 * <p>The discipline is a different case entirely. There is no cheap way back to it - it is a tab
 * inside a screen inside the hub - and a player working through one lane returns to that same lane
 * essentially every time. Remembering it removes two clicks from the most repeated action in the mod
 * without ever putting the player somewhere they did not choose to be.
 *
 * <p>Deliberately tiny. The value lives in memory and is only written to disk when it actually
 * changes, so opening the same page twenty times costs nothing. It is per-installation and
 * per-profile because it lives in the client config directory, which is exactly the scope a personal
 * UI preference belongs in - it is a reading habit, not game state, so it has no business on the
 * server or in the save.
 */
public final class ClientUiState {
    private ClientUiState() {
    }

    /** Discipline path (e.g. {@code "combat"}), or {@code null} for "never picked one". */
    private static String lastDiscipline;
    private static boolean loaded;

    /**
     * The discipline the skill page should focus, or {@code null} if there is no preference yet.
     *
     * <p>A path string rather than an enum because the discipline set is data-driven: a datapack may
     * add one, and a name this build does not know must read as "no preference" rather than crash a
     * screen. The caller validates it against the race's actual tabs regardless - see
     * {@code SkillTreeScreen#buildTabs} - so a stale name after a race change is handled there too.
     */
    public static String lastDiscipline() {
        if (!loaded) {
            load();
        }
        return lastDiscipline;
    }

    /** Records the discipline being read. Writes only on a real change. */
    public static void rememberDiscipline(String discipline) {
        if (!loaded) {
            load();
        }
        if (discipline == null || discipline.equals(lastDiscipline)) {
            return;
        }
        lastDiscipline = discipline;
        save();
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("kindreds-ui.txt");
    }

    private static void load() {
        loaded = true;
        try {
            Path p = file();
            if (!Files.exists(p)) {
                return;
            }
            String raw = Files.readString(p, StandardCharsets.UTF_8).trim();
            // The file previously held a page name (HUB/TRAITS/SKILLS/...). Those are all uppercase
            // and no discipline path is, so an old file is recognised and ignored rather than
            // producing a "discipline" no tree will ever match.
            lastDiscipline = raw.isEmpty() || raw.equals(raw.toUpperCase(java.util.Locale.ROOT))
                    ? null : raw;
        } catch (Exception ignored) {
            // Unreadable: no preference, which is always a safe place to land.
            lastDiscipline = null;
        }
    }

    private static void save() {
        try {
            Files.writeString(file(), lastDiscipline, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // A preference that fails to persist is a preference that resets - not worth a crash.
        }
    }
}

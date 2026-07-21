package com.kindreds.client;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Remembers which page {@code K} should open - the one you were last reading.
 *
 * <p>Deliberately tiny. The value lives in memory and is only written to disk when it actually
 * changes, so opening the same page twenty times costs nothing: no file handle, no serialization,
 * no allocation beyond the enum. It is per-installation and per-profile because it lives in the
 * client config directory, which is exactly the scope a personal UI preference belongs in - it is a
 * reading habit, not game state, so it has no business on the server or in the save.
 */
public final class ClientUiState {
    private ClientUiState() {
    }

    public enum Page { HUB, TRAITS, SKILLS }

    private static Page lastPage;
    private static boolean loaded;

    /** The page to open, defaulting to the hub the very first time. */
    public static Page lastPage() {
        if (!loaded) {
            load();
        }
        return lastPage == null ? Page.HUB : lastPage;
    }

    /** Records the page being opened. Writes only on a real change. */
    public static void remember(Page page) {
        if (!loaded) {
            load();
        }
        if (page == null || page == lastPage) {
            return;
        }
        lastPage = page;
        save();
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("kindreds-ui.txt");
    }

    private static void load() {
        loaded = true;
        try {
            Path p = file();
            if (Files.exists(p)) {
                lastPage = Page.valueOf(Files.readString(p, StandardCharsets.UTF_8).trim());
            }
        } catch (Exception ignored) {
            // unreadable or a page name that no longer exists: fall back to the hub, which is
            // always a safe place to land
            lastPage = Page.HUB;
        }
    }

    private static void save() {
        try {
            Files.writeString(file(), lastPage.name(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // a preference that fails to persist is a preference that resets - not worth a crash
        }
    }
}

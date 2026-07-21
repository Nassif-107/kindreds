package com.kindreds.client.screen;

import com.kindreds.network.SyncConfigS2C;

/**
 * The client's read-only mirror of the server's rule settings, filled by {@link SyncConfigS2C} on join
 * and after every change.
 *
 * <p>Display only. Nothing here confers authority: changing a setting always goes back to the server,
 * which re-checks operator permission. Null until the first sync arrives (a vanilla server, or the
 * brief moment before the join packet lands), which callers must tolerate.
 */
public final class ClientConfigMirror {
    private ClientConfigMirror() {
    }

    private static volatile SyncConfigS2C.View current;

    public static SyncConfigS2C.View get() {
        return current;
    }

    public static void set(SyncConfigS2C.View view) {
        current = view;
    }
}

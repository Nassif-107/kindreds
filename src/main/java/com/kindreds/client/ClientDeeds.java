package com.kindreds.client;

import net.minecraft.text.Text;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The client's copy of what each Great Deed asks for, as sent once on join by
 * {@link com.kindreds.network.SyncDeedsS2C}. Whether a deed is <em>done</em> is not kept here - that
 * lives in the ordinary data mirror ({@code ClientKindredData.INSTANCE.renown()}), so there is only
 * ever one answer to it.
 */
public final class ClientDeeds {
    private ClientDeeds() {
    }

    private static volatile Map<String, Text> requirements = Map.of();

    public static void accept(Map<String, Text> lines) {
        requirements = new LinkedHashMap<>(lines);
    }

    /** Every deed the server knows, in the order it sent them. */
    public static Map<String, Text> all() {
        return requirements;
    }

    /** What {@code path} ("renown/dwarf/khazad_work") asks of you, or null if the server said nothing. */
    public static Text requirement(String path) {
        return requirements.get(path);
    }
}

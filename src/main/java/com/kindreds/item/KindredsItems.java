package com.kindreds.item;

import com.kindreds.Kindreds;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/** Registers Kindreds' items. Call {@link #register()} once from {@link Kindreds#onInitialize()}. */
public final class KindredsItems {
    private KindredsItems() {
    }

    public static final Identifier CODEX_ID = Identifier.of(Kindreds.MOD_ID, "codex");

    /** The Kindred Codex book - opens the codex almanac on use. */
    public static Item CODEX;

    public static void register() {
        RegistryKey<Item> codexKey = RegistryKey.of(RegistryKeys.ITEM, CODEX_ID);
        CODEX = Registry.register(Registries.ITEM, CODEX_ID,
                new CodexItem(new Item.Settings().registryKey(codexKey).maxCount(1)));
    }
}

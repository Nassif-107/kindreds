package com.kindreds.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.kindreds.playerdata.KindredData;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sync snapshot must carry every field the client reads.
 *
 * It failed to carry {@code nodeRanks} once, and nothing downstream noticed: the packet codec
 * serialised the map correctly, it was merely always empty. On the client every owned node then
 * read as rank 1, which understated spent points - so the tree offered points the server refused
 * to spend, and a rank-3 node's tooltip said "Rank 1 of 3".
 */
class SyncSnapshotTest {

    @Test
    @DisplayName("snapshot carries node ranks, not just the owned set")
    void snapshotCarriesRanks() {
        KindredData live = new KindredData();
        live.unlockedNodes().add("dwarf.mining.tireless_pick");
        live.setRank("dwarf.mining.tireless_pick", 3);

        KindredData copy = SyncKindredDataS2C.snapshot(live);

        assertEquals(3, copy.rankOf("dwarf.mining.tireless_pick"),
                "a rank-3 node must still be rank 3 after the snapshot");
    }

    @Test
    @DisplayName("snapshot is detached: mutating the original does not change the copy")
    void snapshotIsDetached() {
        KindredData live = new KindredData();
        live.unlockedNodes().add("dwarf.mining.vein_miner");
        live.setRank("dwarf.mining.vein_miner", 2);

        KindredData copy = SyncKindredDataS2C.snapshot(live);
        live.setRank("dwarf.mining.vein_miner", 3);

        // The whole reason snapshot() exists is to be safe to hand to another thread.
        assertEquals(2, copy.rankOf("dwarf.mining.vein_miner"),
                "the copy must not share the live rank map");
    }

    @Test
    @DisplayName("an owned node with no explicit rank is rank 1")
    void ownedWithoutRankIsOne() {
        KindredData live = new KindredData();
        live.unlockedNodes().add("dwarf.mining.caveborn");

        KindredData copy = SyncKindredDataS2C.snapshot(live);

        assertEquals(1, copy.rankOf("dwarf.mining.caveborn"));
        assertEquals(0, copy.rankOf("dwarf.mining.not_owned"), "an unowned node is rank 0");
    }

    @Test
    @DisplayName("snapshot carries discipline xp")
    void snapshotCarriesXp() {
        KindredData live = new KindredData();
        live.addXp(Identifier.of("kindreds", "mining"), 1234L);

        assertEquals(1234L, SyncKindredDataS2C.snapshot(live).xpIn(Identifier.of("kindreds", "mining")));
    }
}

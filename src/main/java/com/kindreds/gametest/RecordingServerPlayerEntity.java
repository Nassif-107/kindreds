package com.kindreds.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A real {@link ServerPlayerEntity} that records every {@link #sendMessage(Text, boolean)} call
 * instead of delivering it - the one observable seam {@code rankCrossingAnnounces} needs.
 *
 * <h2>Why this class exists (javap findings)</h2>
 * {@code TestContext.createMockPlayer(GameMode)} returns a plain {@code PlayerEntity} (verified by
 * disassembling {@code net.minecraft.test.TestContext$1}: it extends {@code PlayerEntity}, not
 * {@code ServerPlayerEntity}) - too shallow for anything in this mod's threat system, which is typed
 * against {@code ServerPlayerEntity} throughout. {@code TestContext.createMockCreativeServerPlayerInWorld()}
 * does return a real {@code ServerPlayerEntity}, but the concrete class instantiated
 * ({@code net.minecraft.test.TestContext$2}) is package-private in {@code net.minecraft.test} and
 * anonymous - it cannot be extended or intercepted from mod code, and it hardcodes
 * {@code getGameMode()} to {@code CREATIVE} unconditionally. Subclassing "the mock player" the brief
 * asks for is therefore infeasible through that factory method.
 *
 * <p>What IS feasible: {@code TestContext$2}'s own construction recipe (disassembled from its
 * bytecode) uses only public API end to end - {@link ServerPlayerEntity}'s 4-arg constructor,
 * {@link ConnectedClientData#createDefault}, {@link ClientConnection}'s 1-arg constructor, and
 * {@code PlayerManager#onPlayerConnect} are all public in 1.21.8. This class reproduces that exact
 * recipe with our own subclass in place of theirs, giving a real, fully-connected
 * {@code ServerPlayerEntity} (fires {@code ServerPlayConnectionEvents.JOIN} the same way, has a real
 * {@code networkHandler}) whose {@link #sendMessage} we control.
 */
public class RecordingServerPlayerEntity extends ServerPlayerEntity {
    /** Every message this player was ever told, in arrival order. Not delivered anywhere further -
     * see the class javadoc; recording is all {@code rankCrossingAnnounces} needs, and there is no
     * real client on the other end of the embedded channel to render it anyway. */
    public final List<Text> sentMessages = new ArrayList<>();

    private RecordingServerPlayerEntity(net.minecraft.server.MinecraftServer server, ServerWorld world,
                                         GameProfile profile,
                                         net.minecraft.network.packet.c2s.common.SyncedClientOptions options) {
        super(server, world, profile, options);
    }

    @Override
    public void sendMessage(Text message, boolean overlayActionBar) {
        sentMessages.add(message);
    }

    /**
     * Builds and connects a fresh {@link RecordingServerPlayerEntity} into {@code world}, via the
     * same "fake connection" dance {@code TestContext$2} uses internally (see the class javadoc):
     * a default {@link ConnectedClientData}, a {@link ClientConnection} backed by a netty
     * {@link EmbeddedChannel} (nothing ever reads its outbound queue - this player is never asked to
     * render anything), then {@code PlayerManager#onPlayerConnect}, which is what actually performs
     * the join (adds the player to the world, fires {@code ServerPlayConnectionEvents.JOIN}, assigns
     * {@code networkHandler}).
     */
    public static RecordingServerPlayerEntity create(ServerWorld world) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), "test-recording-player");
        ConnectedClientData clientData = ConnectedClientData.createDefault(profile, false);
        RecordingServerPlayerEntity player = new RecordingServerPlayerEntity(
                world.getServer(), world, clientData.gameProfile(), clientData.syncedOptions());
        ClientConnection connection = new ClientConnection(NetworkSide.SERVERBOUND);
        new EmbeddedChannel(new ChannelHandler[]{connection});
        world.getServer().getPlayerManager().onPlayerConnect(connection, player, clientData);
        return player;
    }
}

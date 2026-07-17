package com.kindreds.playerdata;

/**
 * Client-side holder for the most recent {@link KindredData} snapshot the server has sent via
 * {@link com.kindreds.network.SyncKindredDataS2C}. UI/HUD code reads {@link #INSTANCE} directly;
 * it is only ever written from the client network receiver (on the client thread).
 */
public final class ClientKindredData {
    private ClientKindredData() {
    }

    public static volatile KindredData INSTANCE = new KindredData();
}

package com.minenorth.vehicles.miscs;

import com.minenorth.vehicles.config.VehicleConfig;
import net.minecraft.server.level.ServerPlayer;

public final class Msg {
    private Msg() {}

    public static void send(ServerPlayer p, String s) {
        p.sendSystemMessage(Gui.comp(VehicleConfig.PREFIX.get() + " " + s));
    }
}

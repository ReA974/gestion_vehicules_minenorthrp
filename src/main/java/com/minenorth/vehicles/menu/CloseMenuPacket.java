package com.minenorth.vehicles.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client -> serveur : le menu a été fermé. */
public final class CloseMenuPacket {
    public final int menuId;

    public CloseMenuPacket(int menuId) {
        this.menuId = menuId;
    }

    public static void encode(CloseMenuPacket m, FriendlyByteBuf b) {
        b.writeVarInt(m.menuId);
    }

    public static CloseMenuPacket decode(FriendlyByteBuf b) {
        return new CloseMenuPacket(b.readVarInt());
    }

    public static void handle(CloseMenuPacket m, Supplier<NetworkEvent.Context> sup) {
        NetworkEvent.Context ctx = sup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer p = ctx.getSender();
            if (p != null) MenuNet.handleClose(p, m);
        });
        ctx.setPacketHandled(true);
    }
}

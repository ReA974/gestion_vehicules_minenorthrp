package com.minenorth.vehicles.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client -> serveur : clic sur un objet du menu (slot d'origine + bouton de souris). */
public final class MenuClickPacket {
    public final int menuId, slot, button;

    public MenuClickPacket(int menuId, int slot, int button) {
        this.menuId = menuId;
        this.slot = slot;
        this.button = button;
    }

    public static void encode(MenuClickPacket m, FriendlyByteBuf b) {
        b.writeVarInt(m.menuId);
        b.writeVarInt(m.slot);
        b.writeVarInt(m.button);
    }

    public static MenuClickPacket decode(FriendlyByteBuf b) {
        return new MenuClickPacket(b.readVarInt(), b.readVarInt(), b.readVarInt());
    }

    public static void handle(MenuClickPacket m, Supplier<NetworkEvent.Context> sup) {
        NetworkEvent.Context ctx = sup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer p = ctx.getSender();
            if (p != null) MenuNet.handleClick(p, m);
        });
        ctx.setPacketHandled(true);
    }
}

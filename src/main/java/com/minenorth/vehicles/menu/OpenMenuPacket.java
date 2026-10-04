package com.minenorth.vehicles.menu;

import com.minenorth.vehicles.client.MenuClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Serveur -> client : un menu à afficher (mêmes objets qu'un coffre, mais dessinés par l'écran personnalisé). */
public final class OpenMenuPacket {
    public record Slot(int slot, ItemStack stack) {}

    public final int menuId, rows;
    public final String title;
    public final List<Slot> slots;

    public OpenMenuPacket(int menuId, String title, int rows, List<Slot> slots) {
        this.menuId = menuId;
        this.title = title;
        this.rows = rows;
        this.slots = slots;
    }

    public static void encode(OpenMenuPacket m, FriendlyByteBuf b) {
        b.writeVarInt(m.menuId);
        b.writeUtf(m.title, 256);
        b.writeVarInt(m.rows);
        b.writeVarInt(m.slots.size());
        for (Slot s : m.slots) {
            b.writeVarInt(s.slot());
            b.writeItem(s.stack());
        }
    }

    public static OpenMenuPacket decode(FriendlyByteBuf b) {
        int id = b.readVarInt();
        String title = b.readUtf(256);
        int rows = b.readVarInt();
        int n = b.readVarInt();
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < n; i++) slots.add(new Slot(b.readVarInt(), b.readItem()));
        return new OpenMenuPacket(id, title, rows, slots);
    }

    public static void handle(OpenMenuPacket m, Supplier<NetworkEvent.Context> sup) {
        NetworkEvent.Context ctx = sup.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MenuClient.open(m)));
        ctx.setPacketHandled(true);
    }
}

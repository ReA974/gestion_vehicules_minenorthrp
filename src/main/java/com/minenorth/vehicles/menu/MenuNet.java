package com.minenorth.vehicles.menu;

import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.miscs.Gui;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Menus personnalisés : le serveur garde la même logique qu'avec un coffre (Gui.open + callback de clic),
 * mais l'affichage est fait par un écran du mod côté client. Un client sans le mod garde le coffre.
 */
@Mod.EventBusSubscriber(modid = VehiclesMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MenuNet {
    private static final String PROTOCOL = "1";
    /** Le canal accepte un client qui ne l'a pas : ces joueurs retombent sur les menus coffre. */
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(VehiclesMod.MODID, "menus"), () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL), NetworkRegistry.acceptMissingOr(PROTOCOL));

    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    /** Délai minimal entre deux clics acceptés (ticks) : évite le double achat / la double récupération. */
    private static final int CLICK_COOLDOWN_TICKS = 10;

    private static final class Session {
        final int id, size;
        final Set<Integer> filled;
        final Gui.SlotClick onClick;
        long lastClick = -1000;

        Session(int id, int size, Set<Integer> filled, Gui.SlotClick onClick) {
            this.id = id;
            this.size = size;
            this.filled = filled;
            this.onClick = onClick;
        }
    }

    private MenuNet() {}

    @SubscribeEvent
    public static void onSetup(FMLCommonSetupEvent e) {
        e.enqueueWork(() -> {
            int id = 0;
            CHANNEL.registerMessage(id++, OpenMenuPacket.class, OpenMenuPacket::encode, OpenMenuPacket::decode, OpenMenuPacket::handle);
            CHANNEL.registerMessage(id++, MenuClickPacket.class, MenuClickPacket::encode, MenuClickPacket::decode, MenuClickPacket::handle);
            CHANNEL.registerMessage(id++, CloseMenuPacket.class, CloseMenuPacket::encode, CloseMenuPacket::decode, CloseMenuPacket::handle);
        });
    }

    /** Ce joueur a-t-il le mod côté client ? */
    public static boolean hasClientMod(ServerPlayer p) {
        try {
            return p.connection != null && CHANNEL.isRemotePresent(p.connection.connection);
        } catch (Exception e) {
            return false;
        }
    }

    public static void open(ServerPlayer p, String title, int rows, ItemStack[] items, Gui.SlotClick onClick) {
        int r = Math.max(1, Math.min(6, rows));
        List<OpenMenuPacket.Slot> slots = new ArrayList<>();
        Set<Integer> filled = new HashSet<>();
        for (int i = 0; i < items.length && i < r * 9; i++) {
            if (items[i] != null && !items[i].isEmpty()) {
                slots.add(new OpenMenuPacket.Slot(i, items[i]));
                filled.add(i);
            }
        }
        int id = NEXT_ID.incrementAndGet();
        SESSIONS.put(p.getUUID(), new Session(id, r * 9, filled, onClick));
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new OpenMenuPacket(id, title, r, slots));
    }

    static void handleClick(ServerPlayer p, MenuClickPacket m) {
        Session s = SESSIONS.get(p.getUUID());
        if (s == null || s.id != m.menuId) return;                     // menu périmé : ignoré
        if (m.slot < 0 || m.slot >= s.size || !s.filled.contains(m.slot)) return;
        long now = p.getServer().getTickCount();
        if (now - s.lastClick < CLICK_COOLDOWN_TICKS) return;
        s.lastClick = now;
        s.onClick.click(m.slot, m.button);
    }

    static void handleClose(ServerPlayer p, CloseMenuPacket m) {
        Session s = SESSIONS.get(p.getUUID());
        if (s != null && s.id == m.menuId) SESSIONS.remove(p.getUUID());
    }

    @Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
    public static final class Events {
        private Events() {}

        @SubscribeEvent
        public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
            SESSIONS.remove(e.getEntity().getUUID());
        }
    }
}

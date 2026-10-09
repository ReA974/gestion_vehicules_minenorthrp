package com.minenorth.vehicles.handle;

import com.minenorth.vehicles.miscs.Msg;
import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.garage.Garage;
import com.minenorth.vehicles.garage.GarageData;
import com.minenorth.vehicles.shop.Catalog;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class VehicleEvents {
    private static final Set<UUID> IN_ZONE = new HashSet<>();

    private VehicleEvents() {}

    @SubscribeEvent
    public static void onStarting(ServerStartingEvent e) {
        int n = Catalog.load();
        VehiclesMod.LOGGER.info("[Vehicules] Catalogue : {} véhicules chargés.", n);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        UUID id = e.getEntity().getUUID();
        IN_ZONE.remove(id);
    }

    /** Messages d'entrée / sortie des zones garage. */
    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.getTickCount();

        // --- zones garage ---
        if (now % VehicleConfig.CHECK_INTERVAL.get() != 0) return;
        GarageData data = GarageData.get(server);
        if (data.zones.isEmpty()) return;

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID id = p.getUUID();
            GarageData.Zone z = Garage.zoneAt(p);
            if (z == null) {
                if (IN_ZONE.remove(id)) Msg.send(p, VehicleConfig.LEAVE_MSG.get());
                continue;
            }
            if (IN_ZONE.add(id)) Msg.send(p, enterMessage(z));
        }
    }

    private static String enterMessage(GarageData.Zone z) {
        String key = z.service.isEmpty() ? z.tag : "garage." + z.service;   // zones de service : messages « garage.police » / « garage.pompier »
        for (String s : VehicleConfig.ZONE_MESSAGES.get()) {
            int i = s.indexOf('=');
            if (i > 0 && s.substring(0, i).trim().equals(key)) return s.substring(i + 1);
        }
        return VehicleConfig.ENTER_DEFAULT.get();
    }
}

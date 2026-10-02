package com.minenorth.vehicles.handle;

import com.minenorth.vehicles.miscs.Msg;
import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.garage.Garage;
import com.minenorth.vehicles.garage.GarageData;
import com.minenorth.vehicles.shop.Catalog;
import com.minenorth.vehicles.shop.Shop;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class VehicleEvents {
    private static final Set<UUID> IN_ZONE = new HashSet<>();
    private static final Set<UUID> PROMPTED = new HashSet<>();
    private static final Map<UUID, Vec3> LAST = new HashMap<>();

    private record Check(Entity entity, long dueTick) {}

    private static final List<Check> CHECKS = new ArrayList<>();

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
        PROMPTED.remove(id);
        LAST.remove(id);
        Shop.WAITING.remove(id);
    }

    /** Véhicule MTS qui apparaît : on vérifie quelques ticks plus tard (position définitive) s'il s'agit d'un achat. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || e.loadedFromDisk()) return;
        Entity ent = e.getEntity();
        if (Shop.WAITING.isEmpty() || !MtsBridge.isVehicle(ent)) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        CHECKS.add(new Check(ent, server.getTickCount() + 5));
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.getTickCount();

        // --- poses d'achat en attente de vérification ---
        if (!CHECKS.isEmpty()) {
            List<Check> due = new ArrayList<>();
            Iterator<Check> it = CHECKS.iterator();
            while (it.hasNext()) {
                Check c = it.next();
                if (c.dueTick() <= now) {
                    due.add(c);
                    it.remove();
                }
            }
            for (Check c : due) Shop.onVehiclePlaced(server, c.entity());
        }

        Shop.tick(server);

        // --- zones garage ---
        if (now % VehicleConfig.CHECK_INTERVAL.get() != 0) return;
        GarageData data = GarageData.get(server);
        if (data.zones.isEmpty()) return;

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID id = p.getUUID();
            GarageData.Zone z = Garage.zoneAt(p);
            if (z == null) {
                if (IN_ZONE.remove(id)) Msg.send(p, VehicleConfig.LEAVE_MSG.get());
                PROMPTED.remove(id);
                LAST.remove(id);
                continue;
            }
            if (IN_ZONE.add(id)) Msg.send(p, enterMessage(z));
            /* Permet d'ouvrir le garage quand on rentre dans la zone mais je ne suis pas fan du rendu au final
            if (!VehicleConfig.AUTO_PROMPT.get() || PROMPTED.contains(id) || p.containerMenu != p.inventoryMenu) continue;
            Entity v = MtsBridge.vehicleOf(p);
            if (v == null) {
                LAST.remove(id);
                continue;
            }
            Vec3 last = LAST.put(id, v.position());

            if (last != null && last.distanceTo(v.position()) <= VehicleConfig.PROMPT_MAX_MOVE.get()) {
                PROMPTED.add(id); // une seule proposition par passage dans la zone
                Garage.promptStore(p, v);
            }*/
        }
    }

    private static String enterMessage(GarageData.Zone z) {
        for (String s : VehicleConfig.ZONE_MESSAGES.get()) {
            int i = s.indexOf('=');
            if (i > 0 && s.substring(0, i).trim().equals(z.tag)) return s.substring(i + 1);
        }
        return VehicleConfig.ENTER_DEFAULT.get();
    }
}

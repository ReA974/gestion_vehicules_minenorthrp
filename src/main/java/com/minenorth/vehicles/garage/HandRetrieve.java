package com.minenorth.vehicles.garage;

import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.handle.MtsBridge;
import com.minenorth.vehicles.miscs.Msg;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sortie « en main » : le joueur reçoit l'item du véhicule et doit le poser DANS la zone garage. Le véhicule posé est alors
 * remplacé par le véhicule sauvegardé (pièces, carburant, coffres, dégâts conservés). Posé hors zone, ou si le temps est écoulé,
 * le véhicule retourne dans le garage et l'item est repris.
 * Le véhicule sorti du garage reste « en attente » dans les données du monde : un redémarrage du serveur ne le perd pas.
 */
@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class HandRetrieve {
    private HandRetrieve() {}

    private record Check(Entity entity, long due) {}

    private static final List<Check> CHECKS = new ArrayList<>();

    public static final int FALLBACK = 0, STARTED = 1, REFUSED = -1;

    /** Démarre la sortie en main. FALLBACK : l'item du véhicule est introuvable, l'appelant sort le véhicule directement. */
    public static int start(ServerPlayer p, GarageData.Zone zone, List<GarageData.Stored> list, int index) {
        MinecraftServer server = p.getServer();
        GarageData data = GarageData.get(server);
        if (data.pending.containsKey(p.getUUID())) {
            Msg.send(p, "&cTu as déjà un véhicule à poser dans la zone garage. Pose-le d'abord !");
            return REFUSED;
        }
        GarageData.Stored s = list.get(index);
        Item item = VehicleConfig.item(s.itemId);
        if (item == Items.AIR) {
            Msg.send(p, "&eItem du véhicule introuvable : sortie directe à la place.");
            return FALLBACK;
        }
        list.remove(index);
        GarageData.Pending pe = new GarageData.Pending();
        pe.vehicle = s;
        pe.zone = zone.name;
        pe.expireMs = System.currentTimeMillis() + VehicleConfig.HAND_TIMEOUT.get() * 1000L;
        data.pending.put(p.getUUID(), pe);
        data.setDirty();
        ItemHandlerHelper.giveItemToPlayer(p, new ItemStack(item));
        Msg.send(p, "&a" + s.label + " dans ta main ! Pose-le dans la zone garage (" + VehicleConfig.HAND_TIMEOUT.get()
                + " s), sinon il retourne dans ton garage.");
        return STARTED;
    }

    /** Remet le véhicule en attente dans le garage et reprend l'item (tout de suite, ou à la prochaine connexion). */
    public static void giveBack(MinecraftServer server, UUID id, String why) {
        giveBack(server, id, why, true);
    }

    /** strip = false : l'item a déjà été consommé par la pose, rien à reprendre. */
    private static void giveBack(MinecraftServer server, UUID id, String why, boolean strip) {
        GarageData data = GarageData.get(server);
        GarageData.Pending pe = data.pending.remove(id);
        if (pe == null) return;
        data.of(id).add(pe.vehicle);
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) {
            if (strip) stripOne(p, pe.vehicle.itemId);
            Msg.send(p, why);
        } else if (strip) {
            data.strip.computeIfAbsent(id, k -> new ArrayList<>()).add(pe.vehicle.itemId);
        }
        data.setDirty();
    }

    /** Retire un exemplaire de cet item de l'inventaire. */
    private static boolean stripOne(ServerPlayer p, String itemId) {
        Item item = VehicleConfig.item(itemId);
        if (item == Items.AIR) return false;
        for (ItemStack st : p.getInventory().items) {
            if (!st.isEmpty() && st.getItem() == item) {
                st.shrink(1);
                return true;
            }
        }
        for (ItemStack st : p.getInventory().offhand) {
            if (!st.isEmpty() && st.getItem() == item) {
                st.shrink(1);
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || e.loadedFromDisk()) return;
        if (!MtsBridge.isVehicle(e.getEntity())) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || GarageData.get(server).pending.isEmpty()) return;
        CHECKS.add(new Check(e.getEntity(), server.getTickCount() + 5));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        GarageData data = GarageData.get(p.getServer());
        List<String> strip = data.strip.remove(p.getUUID());
        if (strip != null) {
            for (String id : strip) stripOne(p, id);
            data.setDirty();
        }
        GarageData.Pending pe = data.pending.get(p.getUUID());
        if (pe != null && System.currentTimeMillis() >= pe.expireMs) {
            giveBack(p.getServer(), p.getUUID(), "&eLe temps pour poser ton véhicule est écoulé : il est retourné dans ton garage.");
        }
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.getTickCount();
        if (!CHECKS.isEmpty()) {
            List<Check> due = new ArrayList<>();
            Iterator<Check> it = CHECKS.iterator();
            while (it.hasNext()) {
                Check c = it.next();
                if (c.due() <= now) {
                    due.add(c);
                    it.remove();
                }
            }
            for (Check c : due) onPlaced(server, c.entity());
        }
        if (now % 20 != 0) return;
        GarageData data = GarageData.get(server);
        if (data.pending.isEmpty()) return;
        long ms = System.currentTimeMillis();
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, GarageData.Pending> en : data.pending.entrySet()) {
            // Hors ligne : il garde son véhicule en attente jusqu'à sa prochaine connexion (traité dans onLogin).
            if (ms >= en.getValue().expireMs && server.getPlayerList().getPlayer(en.getKey()) != null) expired.add(en.getKey());
        }
        for (UUID id : expired) giveBack(server, id, "&eLe temps pour poser ton véhicule est écoulé : il est retourné dans ton garage.");
    }

    private static void onPlaced(MinecraftServer server, Entity ent) {
        if (ent.isRemoved() || MtsBridge.ownerOf(ent) != null || !(ent.level() instanceof ServerLevel level)) return;
        GarageData data = GarageData.get(server);
        String dim = level.dimension().location().toString();
        String placedId = MtsBridge.itemId(MtsBridge.snapshot(ent));
        for (Map.Entry<UUID, GarageData.Pending> en : new ArrayList<>(data.pending.entrySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            GarageData.Pending pe = en.getValue();
            GarageData.Zone z = data.zones.get(pe.zone);
            if (p == null || z == null || !p.level().dimension().location().toString().equals(dim)) continue;
            if (ent.distanceToSqr(p) > 20 * 20) continue;
            if (!placedId.equalsIgnoreCase(pe.vehicle.itemId)) continue;

            Vec3 pos = ent.position();
            double dx = pos.x - z.x, dz = pos.z - z.z;
            boolean inside = z.dim.equals(dim) && Math.sqrt(dx * dx + dz * dz) <= z.radius;
            MtsBridge.discard(ent);
            if (inside) {
                Entity placed = Spawner.spawn(level, pe.vehicle.nbt, pos.x, pos.y, pos.z, en.getKey());
                if (placed == null) {
                    giveBack(server, en.getKey(), "&cImpossible de recréer ce véhicule : il est retourné dans ton garage.", false);
                    return;
                }
                data.pending.remove(en.getKey());
                data.setDirty();
                Garage.giveKey(p, pe.vehicle.label);
                Msg.send(p, "&aVéhicule posé, intact ! Bonne route.");
            } else {
                giveBack(server, en.getKey(), "&cTu dois poser ton véhicule DANS la zone garage : il est retourné dans ton garage.", false);
            }
            return;
        }
    }
}

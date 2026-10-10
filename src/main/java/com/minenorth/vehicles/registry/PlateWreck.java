package com.minenorth.vehicles.registry;

import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.garage.Garage;
import com.minenorth.vehicles.handle.MtsBridge;
import com.minenorth.vehicles.miscs.Msg;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Véhicule détruit SANS assurance : sa plaque sort du fichier des immatriculations (ou y est marquée « détruit », voir
 * {@link VehicleConfig#REGISTRY_REMOVE_DESTROYED}). Un véhicule assuré revient au garage avec sa plaque : rien ne change.
 * Il faut que la plaque posée soit bien celle de CE véhicule : même propriétaire et même modèle que l'entrée du fichier.
 * Une plaque posée sur un autre véhicule que celui acheté n'est jamais touchée.
 *
 * Quand MTS détruit un véhicule, il en sort les pièces (en objets au sol) AVANT de le retirer du monde : la plaque n'est donc plus
 * lisible sur le véhicule au moment du retrait. Les plaques (et les points de vie) sont donc relevées toutes les 5 s sur les véhicules
 * chargés et gardées en mémoire ; c'est cette dernière lecture qui sert quand le véhicule disparaît.
 */
@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class PlateWreck {
    private PlateWreck() {}

    private record Seen(Set<String> plates, double health) {}

    /** Dernier relevé des véhicules chargés (propriétaire connu, non assurés) : entité -> plaques lues, points de vie du modèle. */
    private static Map<UUID, Seen> seen = new HashMap<>();
    private static int ticks;

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || ++ticks < 100) return;
        ticks = 0;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        Map<UUID, Seen> now = new HashMap<>();
        try {
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity v : level.getAllEntities()) {
                    if (!MtsBridge.isVehicle(v) || MtsBridge.ownerOf(v) == null || MtsBridge.isInsured(v)) continue;
                    Set<String> plates = new HashSet<>();
                    String onCar = MtsBridge.plate(v);
                    if (!onCar.isEmpty()) plates.add(PlateRegistry.norm(onCar));
                    Seen old = seen.get(v.getUUID());
                    if (plates.isEmpty() && old != null) plates = old.plates();   // plaque momentanément illisible : on garde la dernière lue
                    now.put(v.getUUID(), new Seen(plates, MtsBridge.health(v)));
                }
            }
        } catch (RuntimeException ex) {
            VehiclesMod.LOGGER.warn("Registre des plaques : relevé des véhicules impossible : {}", ex.toString());
            return;
        }
        seen = now;
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent e) {
        Entity v = e.getEntity();
        if (e.getLevel().isClientSide() || !(e.getLevel() instanceof ServerLevel level) || !MtsBridge.isVehicle(v)) return;
        if (MtsBridge.isInsured(v) || v.getPersistentData().getBoolean(MtsBridge.HANDLED)) return;
        // Retrait par MTS (pas un déchargement de chunk ni un changement de dimension).
        Entity.RemovalReason why = v.getRemovalReason();
        if (why != Entity.RemovalReason.KILLED && why != Entity.RemovalReason.DISCARDED) return;
        UUID owner = MtsBridge.ownerOf(v);
        if (owner == null) return;
        MinecraftServer server = level.getServer();
        Seen last = seen.remove(v.getUUID());
        try {
            CompoundTag snap = MtsBridge.snapshot(v);
            String itemId = MtsBridge.itemId(snap);

            // Vraiment détruit (et pas retiré autrement) : drapeau MTS, ou dégâts >= points de vie du modèle.
            double health = MtsBridge.health(v) > 0 ? MtsBridge.health(v) : last == null ? 0 : last.health();
            double damage = MtsBridge.damageIn(snap);
            boolean broken = MtsBridge.outOfHealth(v) || (health > 0 && damage >= health);

            Set<String> plates = new HashSet<>();
            String onCar = MtsBridge.plate(v);
            if (!onCar.isEmpty()) plates.add(PlateRegistry.norm(onCar));
            collect(snap, plates);
            if (last != null) plates.addAll(last.plates());

            PlateRegistry reg = PlateRegistry.get(server);
            VehiclesMod.LOGGER.info("[Plaques] Véhicule {} de {} retiré ({}) : détruit={} (drapeau MTS={}, dégâts {}/{}), plaques lues {}, entrées du propriétaire {}.",
                    itemId, owner, why, broken, MtsBridge.outOfHealth(v), damage, health, plates, reg.ofOwner(owner).size());
            if (!broken) return;

            for (PlateRegistry.Entry en : reg.ofOwner(owner)) {
                if (en.destroyed || !plates.contains(PlateRegistry.norm(en.plate))) continue;
                // Même modèle que celui vendu avec cette plaque (sinon c'est une plaque posée sur une autre voiture).
                if (!en.itemId.isEmpty() && !itemId.isEmpty() && !en.itemId.toLowerCase(Locale.ROOT).equals(itemId)) continue;
                boolean remove = VehicleConfig.REGISTRY_REMOVE_DESTROYED.get();
                if (!reg.wreck(server, en, remove)) continue;
                VehiclesMod.LOGGER.info("Immatriculation {} ({}) : véhicule détruit sans assurance, entrée {}.", en.plate, en.model, remove ? "supprimée" : "marquée détruite");
                ServerPlayer o = server.getPlayerList().getPlayer(owner);
                if (o != null) Msg.send(o, "&c[Immatriculation] &7Ton véhicule &e" + (en.model.isEmpty() ? Garage.labelOf(itemId, snap) : en.model)
                        + "&7 a été détruit sans assurance : la plaque &f" + en.plate + "&7 est " + (remove ? "radiée du registre." : "marquée détruite au registre."));
            }
        } catch (RuntimeException ex) {
            VehiclesMod.LOGGER.warn("Registre des plaques : traitement d'un véhicule détruit impossible : {}", ex.toString());
        }
    }

    /** Toutes les chaînes plausibles comme plaque (4 à 12 caractères) du NBT du véhicule, normalisées. */
    private static void collect(Tag tag, Set<String> out) {
        if (tag instanceof StringTag st) {
            String n = PlateRegistry.norm(st.getAsString());
            if (n.length() >= 4 && n.length() <= 12) out.add(n);
        } else if (tag instanceof CompoundTag c) {
            for (String k : c.getAllKeys()) collect(c.get(k), out);
        } else if (tag instanceof ListTag l) {
            for (Tag t : l) collect(t, out);
        }
    }
}

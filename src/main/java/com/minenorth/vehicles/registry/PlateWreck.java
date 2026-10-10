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
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Véhicule détruit SANS assurance : sa plaque sort du fichier des immatriculations (ou y est marquée « détruit », voir
 * {@link VehicleConfig#REGISTRY_REMOVE_DESTROYED}). Un véhicule assuré revient au garage avec sa plaque : rien ne change.
 * Il faut que la plaque posée soit bien celle de CE véhicule : même propriétaire et même modèle que l'entrée du fichier.
 * Une plaque posée sur un autre véhicule que celui acheté n'est jamais touchée.
 */
@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class PlateWreck {
    private PlateWreck() {}

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent e) {
        Entity v = e.getEntity();
        if (e.getLevel().isClientSide() || !(e.getLevel() instanceof ServerLevel level) || !MtsBridge.isVehicle(v)) return;
        if (MtsBridge.isInsured(v) || v.getPersistentData().getBoolean(MtsBridge.HANDLED)) return;
        // Retrait par MTS (pas un déchargement de chunk ni un changement de dimension) et véhicule vraiment détruit (pas ramassé).
        Entity.RemovalReason why = v.getRemovalReason();
        if ((why != Entity.RemovalReason.KILLED && why != Entity.RemovalReason.DISCARDED) || !MtsBridge.outOfHealth(v)) return;
        UUID owner = MtsBridge.ownerOf(v);
        if (owner == null) return;
        MinecraftServer server = level.getServer();
        try {
            CompoundTag snap = MtsBridge.snapshot(v);
            String itemId = MtsBridge.itemId(snap);
            PlateRegistry reg = PlateRegistry.get(server);

            Set<String> plates = new HashSet<>();
            String onCar = MtsBridge.plate(v);
            if (!onCar.isEmpty()) plates.add(PlateRegistry.norm(onCar));
            collect(snap, plates);

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

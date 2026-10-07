package com.minenorth.vehicles.garage;

import com.minenorth.vehicles.miscs.Gui;
import com.minenorth.vehicles.miscs.Msg;
import com.minenorth.vehicles.handle.MtsBridge;
import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.shop.Catalog;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class Garage {
    private Garage() {}

    // ------------------------------------------------------------------ zones

    /** Zone garage accessible où se trouve le joueur (distance horizontale, comme le script d'origine). */
    public static GarageData.Zone zoneAt(ServerPlayer p) {
        GarageData d = GarageData.get(p.getServer());
        String dim = p.level().dimension().location().toString();
        for (GarageData.Zone z : d.zones.values()) {
            if (!z.dim.equals(dim)) continue;
            if (!z.tag.isEmpty() && !p.getTags().contains(z.tag) && !p.hasPermissions(2)) continue;
            double dx = p.getX() - z.x, dz = p.getZ() - z.z;
            if (Math.sqrt(dx * dx + dz * dz) <= z.radius) return z;
        }
        return null;
    }

    // ------------------------------------------------------------------ utilitaires

    public static Item iconOf(String itemId) {
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        Item it = rl == null ? Items.AIR : ForgeRegistries.ITEMS.getValue(rl);
        return it == null || it == Items.AIR ? Items.MINECART : it;
    }

    public static String labelOf(String itemId, CompoundTag nbt) {
        Catalog.Vehicle v = Catalog.byItem(itemId);
        if (v != null) return v.model + (v.color.equals("Défaut") ? "" : " (" + v.color + ")");
        String sys = nbt.getString("systemName");
        return sys.isEmpty() ? "Véhicule" : sys.replace('_', ' ');
    }

    private static void giveKey(ServerPlayer p, String label) {
        if (!VehicleConfig.GIVE_KEY.get()) return;
        Item k = VehicleConfig.item(VehicleConfig.KEY_ITEM.get());
        if (k == Items.AIR) return;
        ItemStack st = new ItemStack(k);
        st.setHoverName(Gui.comp("&e&l" + VehicleConfig.KEY_NAME_PREFIX.get() + " " + label));
        ItemHandlerHelper.giveItemToPlayer(p, st);
    }

    /** Retire la clé de CE véhicule (même nom que celle donnée à l'achat / à la sortie). Les codes couleur § du nom sont ignorés. */
    private static void removeOneKey(ServerPlayer p, String label) {
        Item k = VehicleConfig.item(VehicleConfig.KEY_ITEM.get());
        if (k == Items.AIR) return;
        String expected = VehicleConfig.KEY_NAME_PREFIX.get() + " " + label;
        for (ItemStack s : p.getInventory().items) {
            if (!s.isEmpty() && s.getItem() == k && expected.equals(net.minecraft.ChatFormatting.stripFormatting(s.getHoverName().getString()))) {
                s.shrink(1);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ stockage

    /** Range le véhicule (NBT complet : pièces, inventaires, carburant, dégâts...) dans le garage de {@code target}. */
    public static boolean storeInto(ServerPlayer actor, Entity vehicle, UUID target, boolean admin) {
        GarageData data = GarageData.get(actor.getServer());
        List<GarageData.Stored> list = data.of(target);
        int max = VehicleConfig.GARAGE_MAX.get();
        if (list.size() >= max) {
            Msg.send(actor, "&cLe garage est plein (" + max + "/" + max + ") !");
            return false;
        }
        UUID owner = MtsBridge.ownerOf(vehicle);
        if (!admin && owner != null && !owner.equals(target) && !actor.hasPermissions(2)) {
            Msg.send(actor, "&cCe véhicule ne t'appartient pas.");
            return false;
        }
        CompoundTag snap = MtsBridge.snapshot(vehicle);
        GarageData.Stored s = new GarageData.Stored();
        s.nbt = snap;
        s.itemId = MtsBridge.itemId(snap);
        s.label = labelOf(s.itemId, snap);

        if (actor.getVehicle() != null && vehicle == MtsBridge.vehicleOf(actor)) actor.stopRiding();
        vehicle.discard();
        list.add(s);
        data.setDirty();
        if (target.equals(actor.getUUID())) removeOneKey(actor, s.label);
        Msg.send(actor, "&aVéhicule rangé dans le garage, intact ! (" + list.size() + "/" + max + ")");
        return true;
    }

    public static void storeNearby(ServerPlayer p) {
        chooseAndStore(p, p.getUUID(), false);
    }

    /** Véhicules/remorques rangeables à proximité : les siens + celui où il est assis (tous si admin). */
    private static List<Entity> candidates(ServerPlayer p, boolean admin) {
        ServerLevel level = (ServerLevel) p.level();
        AABB box = p.getBoundingBox().inflate(VehicleConfig.SEARCH_RADIUS.get());
        Entity seat = MtsBridge.vehicleOf(p);
        List<Entity> out = new ArrayList<>();
        for (Entity e : level.getEntities((Entity) null, box, MtsBridge::isVehicle)) {
            if (admin || e == seat || p.getUUID().equals(MtsBridge.ownerOf(e))) out.add(e);
        }
        out.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        return out;
    }

    public static void chooseAndStore(ServerPlayer p, UUID target, boolean admin) {
        List<Entity> list = candidates(p, admin);
        if (list.isEmpty()) {
            Msg.send(p, "&cAucun véhicule ou remorque à toi à moins de " + VehicleConfig.SEARCH_RADIUS.get() + " blocs.");
            return;
        }
        if (list.size() == 1) {
            storeInto(p, list.get(0), target, admin);
            return;
        }
        int n = Math.min(54, list.size());
        ItemStack[] items = new ItemStack[n];
        for (int i = 0; i < n; i++) {
            Entity e = list.get(i);
            CompoundTag snap = MtsBridge.snapshot(e);
            String id = MtsBridge.itemId(snap);
            items[i] = Gui.item(iconOf(id), "&e&l" + labelOf(id, snap),
                    "&7Distance : &f" + (int) e.distanceTo(p) + " blocs", "&7Clique pour le ranger");
        }
        Gui.open(p, "&aQuel véhicule ranger ?", (n + 8) / 9, items, slot -> {
            if (slot < n) Gui.later(p, () -> {
                p.closeContainer();
                Entity e = list.get(slot);
                if (!e.isRemoved()) storeInto(p, e, target, admin);
            });
        });
    }

    // ------------------------------------------------------------------ sortie

    public static void retrieve(ServerPlayer p, int index) {
        // 1. On récupère la zone où se trouve le joueur
        GarageData.Zone zone = zoneAt(p);
        if (zone == null) {
            Msg.send(p, "&cTu dois être dans une zone garage.");
            return;
        }

        GarageData data = GarageData.get(p.getServer());
        List<GarageData.Stored> list = data.of(p.getUUID());
        if (index < 0 || index >= list.size()) return;
        GarageData.Stored s = list.get(index);

        ServerLevel level = (ServerLevel) p.level();

        double spawnX = zone.x;
        double spawnZ = zone.z;

        Entity e = Spawner.spawn(level, s.nbt, zone.x, zone.y, zone.z, p.getUUID());
        if (e == null) {
            Msg.send(p, "&cImpossible de recréer ce véhicule (entité MTS introuvable ?). Il reste dans ton garage.");
            return;
        }
        list.remove(index);
        data.setDirty();
        giveKey(p, s.label);
        Msg.send(p, "&aVéhicule récupéré ! Il t'attend au centre de la zone.");
    }

    // ------------------------------------------------------------------ menus

    /** Menu du PNJ garage (commande /garagemenu). */
    public static void openMenu(ServerPlayer p) {
        if (GarageData.get(p.getServer()).zones.isEmpty()) {
            Msg.send(p, "&cAucune zone garage n'a été configurée.");
            return;
        }
        if (zoneAt(p) == null) {
            Msg.send(p, "&cTu dois être près d'un garage pour utiliser cette commande.");
            return;
        }
        ItemStack[] items = new ItemStack[9];
        items[3] = Gui.item(Items.PAPER, "&e&lRentrer mon véhicule", "&7Monte dans ton véhicule ou reste à côté.");
        items[5] = Gui.item(Items.PAPER, "&e&lVoir mon garage");
        Gui.open(p, "&aGarage", 1, items, slot -> {
            if (slot == 3) Gui.later(p, () -> {
                p.closeContainer();
                storeNearby(p);
            });
            else if (slot == 5) Gui.later(p, () -> openList(p));
        });
    }

    /** Proposition affichée quand on entre dans une zone garage à bord d'un véhicule. */
    public static void promptStore(ServerPlayer p, Entity vehicle) {
        GarageData data = GarageData.get(p.getServer());
        int n = data.of(p.getUUID()).size();
        int max = VehicleConfig.GARAGE_MAX.get();
        CompoundTag snap = MtsBridge.snapshot(vehicle);
        String id = MtsBridge.itemId(snap);
        String label = labelOf(id, snap);

        ItemStack[] items = new ItemStack[27];
        items[11] = Gui.item(Items.LIME_CONCRETE, "&a&lStocker ce véhicule",
                "&7" + label, "&7Garage : &e" + n + "/" + max, "&8Pièces, carburant et coffres conservés");
        items[13] = Gui.item(iconOf(id), "&e&l" + label);
        items[15] = Gui.item(Items.RED_CONCRETE, "&c&lAnnuler");
        items[22] = Gui.item(Items.PAPER, "&e&lVoir mon garage");
        Gui.open(p, "&aStocker ce véhicule ?", 3, items, slot -> {
            if (slot == 11) Gui.later(p, () -> {
                p.closeContainer();
                if (!vehicle.isRemoved()) storeInto(p, vehicle, p.getUUID(), false);
            });
            else if (slot == 15) Gui.later(p, p::closeContainer);
            else if (slot == 22) Gui.later(p, () -> openList(p));
        });
    }

    public static void openList(ServerPlayer p) {
        GarageData data = GarageData.get(p.getServer());
        List<GarageData.Stored> list = data.of(p.getUUID());
        if (list.isEmpty()) {
            Msg.send(p, "&cTon garage est vide, aucun véhicule à récupérer.");
            return;
        }
        ItemStack[] items = listItems(list);
        Gui.open(p, "&aMon Garage", Math.min(6, (list.size() + 8) / 9), items, slot -> {
            if (slot < list.size()) Gui.later(p, () -> {
                p.closeContainer();
                retrieve(p, slot);
            });
        });
    }

    public static ItemStack[] listItems(List<GarageData.Stored> list) {
        int rows = Math.min(6, Math.max(1, (list.size() + 8) / 9));
        ItemStack[] items = new ItemStack[rows * 9];
        for (int i = 0; i < list.size() && i < items.length; i++) {
            GarageData.Stored s = list.get(i);
            items[i] = Gui.item(iconOf(s.itemId), "&e&l" + s.label,
                    "&7Clique pour sortir ce véhicule", "&8Intact : pièces, carburant et coffres conservés");
        }
        return items;
    }
}

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
        return zoneAt(p, null);
    }

    /** Zone accessible où se trouve le joueur ET qui accepte ce type de véhicule (type null = n'importe lequel). */
    public static GarageData.Zone zoneAt(ServerPlayer p, VehicleType type) {
        GarageData d = GarageData.get(p.getServer());
        String dim = p.level().dimension().location().toString();
        for (GarageData.Zone z : d.zones.values()) {
            if (!z.dim.equals(dim)) continue;
            if (type != null && !z.accepts(type)) continue;
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

    public static void giveKey(ServerPlayer p, String label) {
        if (!VehicleConfig.GIVE_KEY.get()) return;
        Item k = VehicleConfig.item(VehicleConfig.KEY_ITEM.get());
        if (k == Items.AIR) return;
        ItemStack st = new ItemStack(k);
        st.setHoverName(Gui.comp("&e&l" + VehicleConfig.KEY_NAME_PREFIX.get() + " " + label));
        ItemHandlerHelper.giveItemToPlayer(p, st);
    }

    /**
     * Retire la clé de CE véhicule : d'abord celle qui porte son nom (« Clé - <véhicule> », codes couleur ignorés) ;
     * à défaut, une clé MTS sans nom (une clé liée avant le correctif de nommage perd son nom).
     */
    public static void removeOneKey(ServerPlayer p, String label) {
        Item k = VehicleConfig.item(VehicleConfig.KEY_ITEM.get());
        if (k == Items.AIR) return;
        String prefix = VehicleConfig.KEY_NAME_PREFIX.get();
        String expected = prefix + " " + label;
        List<ItemStack> all = new ArrayList<>(p.getInventory().items);
        all.addAll(p.getInventory().offhand);
        for (ItemStack s : all) {
            if (!s.isEmpty() && s.getItem() == k && expected.equals(net.minecraft.ChatFormatting.stripFormatting(s.getHoverName().getString()))) {
                s.shrink(1);
                return;
            }
        }
        for (ItemStack s : all) {
            if (s.isEmpty() || s.getItem() != k) continue;
            String name = net.minecraft.ChatFormatting.stripFormatting(s.getHoverName().getString());
            if (name == null || !name.startsWith(prefix)) {
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
        if (!admin && refusedHere(actor, VehicleType.of(s.itemId, snap))) return false;

        if (actor.getVehicle() != null && vehicle == MtsBridge.vehicleOf(actor)) actor.stopRiding();
        MtsBridge.discard(vehicle);
        list.add(s);
        data.setDirty();
        if (target.equals(actor.getUUID())) removeOneKey(actor, s.label);
        Msg.send(actor, "&aVéhicule rangé dans le garage, intact ! (" + list.size() + "/" + max + ")");
        return true;
    }

    /** true (et message) si le joueur est dans une zone garage mais qu'aucune ne prend ce type de véhicule. */
    private static boolean refusedHere(ServerPlayer p, VehicleType type) {
        GarageData.Zone any = zoneAt(p);
        if (any == null || zoneAt(p, type) != null) return false;
        Msg.send(p, "&cCe garage n'accepte pas les véhicules " + type.tag() + "&c (garage : &f" + any.typesLabel() + "&c).");
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
                    "&7Type : " + VehicleType.of(id, snap).tag(), "&7Distance : &f" + (int) e.distanceTo(p) + " blocs", "&7Clique pour le ranger");
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
        if (zoneAt(p) == null) {
            Msg.send(p, "&cTu dois être dans une zone garage.");
            return;
        }

        GarageData data = GarageData.get(p.getServer());
        List<GarageData.Stored> list = data.of(p.getUUID());
        if (index < 0 || index >= list.size()) return;
        GarageData.Stored s = list.get(index);
        GarageData.Zone zone = zoneAt(p, typeOf(s));
        if (zone == null) {
            Msg.send(p, "&cCe garage ne peut pas sortir de véhicule " + typeOf(s).tag() + "&c (garage : &f" + zoneAt(p).typesLabel() + "&c). Va dans la bonne zone.");
            return;
        }

        // Mode « en main » : l'item du véhicule est donné, à poser dans la zone (sinon retour au garage)
        if (data.handMode.contains(p.getUUID())) {
            int r = HandRetrieve.start(p, zone, list, index);
            if (r != HandRetrieve.FALLBACK) return;
        }

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

    // ------------------------------------------------------------------ mode de sortie

    public static boolean handMode(ServerPlayer p) {
        return GarageData.get(p.getServer()).handMode.contains(p.getUUID());
    }

    public static void toggleMode(ServerPlayer p) {
        GarageData d = GarageData.get(p.getServer());
        if (!d.handMode.remove(p.getUUID())) d.handMode.add(p.getUUID());
        d.setDirty();
        Msg.send(p, handMode(p)
                ? "&aSortie du véhicule : &eEN MAIN&a. Tu devras le poser dans la zone garage, sinon il retourne au garage."
                : "&aSortie du véhicule : &eDIRECTE&a. Il apparaît tout de suite dans la zone garage.");
    }

    public static ItemStack modeItem(ServerPlayer p) {
        boolean hand = handMode(p);
        return Gui.item(hand ? Items.CHEST : Items.MINECART, "&e&lSortie : " + (hand ? "en main" : "directe"),
                hand ? "&7Tu reçois le véhicule en main et dois" : "&7Le véhicule apparaît tout de suite",
                hand ? "&7le poser dans la zone garage," : "&7au centre de la zone garage.",
                hand ? "&7sinon il retourne au garage." : "&8Clique pour passer en mode « en main »",
                hand ? "&8Clique pour passer en mode « direct »" : "");
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
        // Ligne 1 : rentrer / voir le garage. Ligne 2 : assurance / filtre / vente.
        ItemStack[] items = new ItemStack[18];
        items[2] = Gui.item(Items.PAPER, "&e&lRentrer mon véhicule", "&7Monte dans ton véhicule ou reste à côté.");
        items[6] = Gui.item(Items.PAPER, "&e&lVoir mon garage");
        items[11] = Gui.item(Items.SHIELD, "&b&lAssurer mes véhicules", "&7Paie une assurance : si le véhicule est détruit,",
                "&7il revient seul dans ton garage (une seule fois).");
        items[13] = filterItem(p);
        items[15] = Gui.item(Items.EMERALD, "&a&lVendre un véhicule", "&7Vends un véhicule de ton garage", "&7(avec sa plaque) à un autre joueur.");
        Gui.open(p, "&aGarage", 2, items, slot -> {
            if (slot == 2) Gui.later(p, () -> {
                p.closeContainer();
                storeNearby(p);
            });
            else if (slot == 6) Gui.later(p, () -> openList(p));
            else if (slot == 11) Gui.later(p, () -> com.minenorth.vehicles.assurance.Insurance.openMenu(p));
            else if (slot == 13) Gui.later(p, () -> {
                cycleFilter(p);
                openMenu(p);
            });
            else if (slot == 15) Gui.later(p, () -> VehicleSale.openSell(p));
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
        if (refusedHere(p, VehicleType.of(id, snap))) return;

        ItemStack[] items = new ItemStack[27];
        items[11] = Gui.item(Items.LIME_CONCRETE, "&a&lStocker ce véhicule",
                "&7" + label, "&7Garage : &e" + n + "/" + max, "&8Pièces, carburant et coffres conservés");
        items[13] = Gui.item(iconOf(id), "&e&l" + label, "&7Type : " + VehicleType.of(id, snap).tag());
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

    public static VehicleType typeOf(GarageData.Stored s) { return VehicleType.of(s.itemId, s.nbt); }

    private static final java.util.Map<UUID, VehicleType> FILTER = new java.util.HashMap<>();

    private static long count(List<GarageData.Stored> l, VehicleType t) {
        return l.stream().filter(s -> typeOf(s) == t).count();
    }

    /** Filtre du garage (Tous -> Terrestre -> Aérien -> Maritime -> Tous) : choisi dans le menu principal, appliqué à la liste. */
    private static void cycleFilter(ServerPlayer p) {
        VehicleType filter = FILTER.get(p.getUUID());
        VehicleType[] v = VehicleType.values();
        VehicleType next = filter == null ? v[0] : filter.ordinal() + 1 < v.length ? v[filter.ordinal() + 1] : null;
        if (next == null) FILTER.remove(p.getUUID()); else FILTER.put(p.getUUID(), next);
    }

    private static ItemStack filterItem(ServerPlayer p) {
        VehicleType filter = FILTER.get(p.getUUID());
        List<GarageData.Stored> all = GarageData.get(p.getServer()).of(p.getUUID());
        return Gui.item(filter == null ? Items.COMPASS : filter == VehicleType.AIR ? Items.FEATHER : filter == VehicleType.MER ? Items.WATER_BUCKET : Items.MINECART,
                "&e&lFiltre : " + (filter == null ? "&fTous" : filter.tag()),
                "&7Terrestre : &f" + count(all, VehicleType.TERRE) + " &7| Aérien : &f" + count(all, VehicleType.AIR) + " &7| Maritime : &f" + count(all, VehicleType.MER),
                "&8Clique pour changer de type", "&8S'applique à « Voir mon garage »");
    }

    public static void openList(ServerPlayer p) {
        GarageData data = GarageData.get(p.getServer());
        List<GarageData.Stored> all = data.of(p.getUUID());
        if (all.isEmpty()) {
            Msg.send(p, "&cTon garage est vide, aucun véhicule à récupérer.");
            return;
        }
        VehicleType filter = FILTER.get(p.getUUID());
        // index réels des véhicules affichés (filtre Terrestre / Aérien / Maritime)
        GarageData.Zone zoneHere = zoneAt(p);
        List<GarageData.Stored> here = new ArrayList<>();
        List<Integer> shown = new ArrayList<>();
        List<GarageData.Stored> view = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            VehicleType ty = typeOf(all.get(i));
            if (zoneHere != null && zoneAt(p, ty) == null) continue; // la zone masque les types qu'elle n'accepte pas
            here.add(all.get(i));
            if (filter == null || ty == filter) {
                shown.add(i);
                view.add(all.get(i));
            }
        }
        if (here.isEmpty()) {
            Msg.send(p, "&cAucun de tes véhicules ne peut sortir de ce garage (" + zoneHere.typesLabel() + " uniquement).");
            return;
        }
        // Dernier emplacement : mode de sortie. Assurance, vente et filtre sont dans le menu principal.
        int rows = Math.min(6, Math.max(1, (shown.size() + 9) / 9));
        ItemStack[] items = new ItemStack[rows * 9];
        ItemStack[] vehicles = listItems(view, p);
        System.arraycopy(vehicles, 0, items, 0, Math.min(vehicles.length, items.length));
        int modeSlot = items.length - 1;
        boolean extras = shown.size() <= modeSlot;
        if (extras) items[modeSlot] = modeItem(p);
        Gui.open(p, "&aMon Garage" + (filter == null ? "" : " - " + filter.label), rows, items, slot -> {
            if (slot < shown.size()) Gui.later(p, () -> {
                p.closeContainer();
                retrieve(p, shown.get(slot));
            });
            else if (extras && slot == modeSlot) Gui.later(p, () -> {
                toggleMode(p);
                openList(p);
            });
        });
    }

    public static ItemStack[] listItems(List<GarageData.Stored> list, ServerPlayer p) {
        int rows = Math.min(6, Math.max(1, (list.size() + 8) / 9));
        ItemStack[] items = new ItemStack[rows * 9];
        for (int i = 0; i < list.size() && i < items.length; i++) {
            GarageData.Stored s = list.get(i);
            items[i] = Gui.item(iconOf(s.itemId), "&e&l" + s.label,
                    "&7Type : " + typeOf(s).tag(), "&7Clique pour sortir ce véhicule", "&8Intact : pièces, carburant et coffres conservés",
                    MtsBridge.isInsured(s.nbt) ? "&bAssuré" : "&8Non assuré",
                    p != null && zoneAt(p) != null && zoneAt(p, typeOf(s)) == null ? "&cNon disponible dans ce garage" : "");
        }
        return items;
    }
}

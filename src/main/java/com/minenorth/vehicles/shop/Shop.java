package com.minenorth.vehicles.shop;

import com.minenorth.vehicles.*;
import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.garage.Garage;
import com.minenorth.vehicles.garage.GarageData;
import com.minenorth.vehicles.handle.Money;
import com.minenorth.vehicles.handle.MtsBridge;
import com.minenorth.vehicles.miscs.Gui;
import com.minenorth.vehicles.miscs.Msg;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

public final class Shop {
    public static final class Waiting {
        public String itemId;
        public long expireTick;
        public String plate;
    }

    /** Joueurs qui ont acheté et doivent poser leur véhicule dans la zone de pose. */
    public static final Map<UUID, Waiting> WAITING = new HashMap<>();

    private Shop() {}

    private static Item registryItem(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item it = rl == null ? Items.AIR : ForgeRegistries.ITEMS.getValue(rl);
        return it == null ? Items.AIR : it;
    }

    // ------------------------------------------------------------------ menus

    public static void openCategories(ServerPlayer p) {
        if (GarageData.get(p.getServer()).shopPos == null && !p.hasPermissions(2)) {
            Msg.send(p, "&cAucune zone de pose n'a été configurée. Un membre du staff doit faire /vehiclezone set.");
            return;
        }
        List<String> cats = new ArrayList<>();
        for (String c : Catalog.CATEGORIES) {
            for (Catalog.Vehicle v : Catalog.VEHICLES) {
                if (v.category.equals(c)) {
                    cats.add(c);
                    break;
                }
            }
        }
        if (cats.isEmpty()) {
            Msg.send(p, "&cLe catalogue est vide ou invalide. Un membre du staff doit vérifier catalog.json puis faire /vloaddata.");
            return;
        }
        ItemStack[] items = new ItemStack[Math.min(54, cats.size())];
        for (int i = 0; i < items.length; i++) {
            Item icon = registryItem(Catalog.CATEGORY_ICON.getOrDefault(cats.get(i), "minecraft:book"));
            items[i] = Gui.item(icon == Items.AIR ? Items.BOOK : icon, "&e&l" + cats.get(i));
        }
        Gui.open(p, "&9&lVendeur - Catégories", (items.length + 8) / 9, items, slot -> {
            if (slot < cats.size()) Gui.later(p, () -> openModels(p, cats.get(slot)));
        });
    }

    private static void openModels(ServerPlayer p, String cat) {
        Map<String, List<Catalog.Vehicle>> models = new LinkedHashMap<>();
        for (Catalog.Vehicle v : Catalog.VEHICLES) {
            if (v.category.equals(cat)) models.computeIfAbsent(v.model, k -> new ArrayList<>()).add(v);
        }
        List<String> names = new ArrayList<>(models.keySet());
        if (names.size() > 53) VehiclesMod.LOGGER.warn("[Vehicules] Catégorie {} : {} modèles, seuls 53 sont affichés.", cat, names.size());

        int count = Math.min(53, names.size());
        int size = Math.min(54, Math.max(9, ((count + 8) / 9) * 9));
        // Si l'inventaire est plein jusqu'à la dernière ligne, on s'assure d'avoir 54 slots pour placer le retour au slot 53
        if (size < 54 && count % 9 == 0) size += 9;

        ItemStack[] items = new ItemStack[size];
        for (int i = 0; i < count; i++) {
            List<Catalog.Vehicle> vs = models.get(names.get(i));
            int min = Integer.MAX_VALUE;
            for (Catalog.Vehicle v : vs) min = Math.min(min, v.price);
            items[i] = Gui.item(Garage.iconOf(vs.get(0).item), "&e&l" + names.get(i),
                    "&7À partir de &a" + min + "€", "&7" + vs.size() + " couleur(s)");
        }

        int backSlot = size - 1;
        items[backSlot] = Gui.item(Items.BARRIER, "&c&lRetour", "&7Revenir aux catégories");

        Gui.open(p, "&e" + cat, size / 9, items, slot -> {
            if (slot == backSlot) {
                Gui.later(p, () -> openCategories(p));
            } else if (slot < count) {
                Gui.later(p, () -> openColors(p, cat, names.get(slot)));
            }
        });
    }

    private static void openColors(ServerPlayer p, String cat, String model) {
        List<Catalog.Vehicle> list = new ArrayList<>();
        for (Catalog.Vehicle v : Catalog.VEHICLES) {
            if (v.category.equals(cat) && v.model.equals(model)) list.add(v);
        }

        int count = Math.min(53, list.size());
        int size = Math.min(54, Math.max(9, ((count + 8) / 9) * 9));
        if (size < 54 && count % 9 == 0) size += 9;

        ItemStack[] items = new ItemStack[size];
        for (int i = 0; i < count; i++) {
            Catalog.Vehicle v = list.get(i);
            Item glass = registryItem(Catalog.COLOR_ICON.getOrDefault(v.color, "minecraft:white_stained_glass"));
            items[i] = Gui.item(glass == Items.AIR ? Items.WHITE_STAINED_GLASS : glass,
                    "&e&l" + v.color + " &7- &a" + v.price + "€");
        }

        int backSlot = size - 1;
        items[backSlot] = Gui.item(Items.BARRIER, "&c&lRetour", "&7Revenir au modèle");

        Gui.open(p, "&0" + model, size / 9, items, slot -> {
            if (slot == backSlot) {
                Gui.later(p, () -> openModels(p, cat));
            } else if (slot < count) {
                Gui.later(p, () -> {
                    p.closeContainer();
                    buy(p, list.get(slot));
                });
            }
        });
    }

    // ------------------------------------------------------------------ achat

    private static void buy(ServerPlayer p, Catalog.Vehicle v) {
        MinecraftServer server = p.getServer();
        GarageData d = GarageData.get(server);
        if (d.shopPos == null) {
            Msg.send(p, "&cAucune zone de pose n'a été configurée.");
            return;
        }
        if (WAITING.containsKey(p.getUUID())) {
            Msg.send(p, "&cTu as déjà un véhicule à poser dans la zone. Pose-le d'abord !");
            return;
        }
        Item it = registryItem(v.item);
        if (it == Items.AIR) {
            Msg.send(p, "&cItem du véhicule introuvable : " + v.item + " (voir catalog.json, /vehicleshop check).");
            return;
        }
        List den = VehicleConfig.currency();
        int total = Money.count(p, den);
        if (total < v.price) {
            Msg.send(p, "&cIl te manque " + (v.price - total) + "€ pour acheter ce véhicule (" + v.price + "€, tu as " + total + "€).");
            return;
        }
        Money.take(p, v.price, den);

        // 1. Génération de la plaque
        String plate = generatePlate();

        // 2. Donner l'item du véhicule (tel quel pour préserver la physique/définition MTS)
        ItemStack vehicleStack = new ItemStack(it);
        ItemHandlerHelper.giveItemToPlayer(p, vehicleStack);

        // 3. Give de 2 Plaques d'immatriculation MTS (mts:gvp.eu_plate)
        Item plateItem = registryItem("mts:gvp.eu_plate");
        if (plateItem != Items.AIR) {
            ItemStack plateStack = new ItemStack(plateItem, 2); // Quantité : 2
            CompoundTag plateNbt = plateStack.getOrCreateTag();

            // NBT utilisé par MTS pour le texte de la plaque
            plateNbt.putString("textCode", plate);
            plateNbt.putString("textCountry Code", "FR");

            // Nom affiché dans l'inventaire
            plateStack.setHoverName(Gui.comp("&fPlaque : &e" + plate));

            ItemHandlerHelper.giveItemToPlayer(p, plateStack);
        }

        String label = v.model + (v.color.equals("Défaut") ? "" : " (" + v.color + ")");

        // 4. Clé du véhicule
        if (VehicleConfig.GIVE_KEY.get()) {
            Item k = registryItem(VehicleConfig.KEY_ITEM.get());
            if (k != Items.AIR) {
                ItemStack key = new ItemStack(k);
                CompoundTag keyNbt = key.getOrCreateTag();
                keyNbt.putString("plateTag", plate);

                key.setHoverName(Gui.comp("&e&l" + VehicleConfig.KEY_NAME_PREFIX.get() + " " + label + " &7[" + plate + "]"));
                ItemHandlerHelper.giveItemToPlayer(p, key);
            }
        }

        // 5. File d'attente
        Waiting w = new Waiting();
        w.itemId = v.item;
        w.plate = plate;
        w.expireTick = server.getTickCount() + VehicleConfig.PLACE_TIMEOUT.get() * 20L;
        WAITING.put(p.getUUID(), w);

        Msg.send(p, "&a" + label + " achetée pour " + v.price + "€ ! Reçu avec 2 plaques [&e" + plate + "&a].");
    }

    // Méthode pour générer une plaque aléatoire style français
    private static String generatePlate() {
        String letters = "ABCDEFGHJKLMNPQRSTUVWXYZ"; // Exclusion des lettres ambiguës I/O
        Random r = new Random();
        char l1 = letters.charAt(r.nextInt(letters.length()));
        char l2 = letters.charAt(r.nextInt(letters.length()));
        char l3 = letters.charAt(r.nextInt(letters.length()));
        char l4 = letters.charAt(r.nextInt(letters.length()));
        int num = r.nextInt(900) + 100;
        return "" + l1 + l2 + "-" + num + "-" + l3 + l4;
    }

    // ------------------------------------------------------------------ pose

    /** Appelé quelques ticks après l'apparition d'un véhicule MTS. */
    public static void onVehiclePlaced(MinecraftServer server, Entity ent) {
        if (ent.isRemoved() || MtsBridge.ownerOf(ent) != null) return;
        GarageData d = GarageData.get(server);
        if (d.shopPos == null || d.shopDim == null) return;
        if (!ent.level().dimension().location().toString().equals(d.shopDim)) return;

        Iterator<Map.Entry<UUID, Waiting>> it = WAITING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Waiting> en = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            if (p == null || ent.distanceToSqr(p) > 14 * 14) continue;

            Vec3 c = Vec3.atCenterOf(d.shopPos);
            double dx = ent.getX() - c.x, dz = ent.getZ() - c.z;
            if (Math.sqrt(dx * dx + dz * dz) <= VehicleConfig.SHOP_PLACE_RADIUS.get()) {
                MtsBridge.setOwner(ent, en.getKey());
                it.remove();
                Msg.send(p, "&aVéhicule posé avec succès ! Bonne route.");
            } else {
                ent.discard();
                Item item = registryItem(en.getValue().itemId);
                if (item != Items.AIR) ItemHandlerHelper.giveItemToPlayer(p, new ItemStack(item));
                Msg.send(p, "&cTu dois poser ton véhicule DANS la zone marquée ! Reprends l'item dans ton inventaire et réessaie.");
            }
            return;
        }
    }

    /** Particules sur la zone de pose + expiration. À appeler à chaque tick serveur. */
    public static void tick(MinecraftServer server) {
        if (WAITING.isEmpty()) return;
        GarageData d = GarageData.get(server);
        long now = server.getTickCount();
        Iterator<Map.Entry<UUID, Waiting>> it = WAITING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Waiting> en = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            if (p == null || now > en.getValue().expireTick) {
                if (p != null) Msg.send(p, "&eLa zone de pose a été libérée (temps écoulé).");
                it.remove();
                continue;
            }
            if (now % 10 != 0 || d.shopPos == null || d.shopDim == null) continue;
            if (!p.level().dimension().location().toString().equals(d.shopDim)) continue;
            ServerLevel lvl = (ServerLevel) p.level();
            Vec3 c = Vec3.atCenterOf(d.shopPos);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    lvl.sendParticles(p, ParticleTypes.HAPPY_VILLAGER, true, c.x + dx, c.y + 0.6, c.z + dz, 2, 0.25, 0.05, 0.25, 0.0);
                }
            }
        }
    }
}
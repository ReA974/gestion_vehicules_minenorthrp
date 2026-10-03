package com.minenorth.vehicles.shop;

import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.config.VehicleConfig;
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
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.*;

/** Vendeurs multiples : chacun a ses catégories (shops.json) et sa propre zone de pose (/vendeurzone set <id>). */
@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class ShopMulti {
    public static final class Waiting {
        public String shopId, itemId;
        public long expireTick;
    }

    public static final Map<UUID, Waiting> WAITING = new HashMap<>();

    private record Check(Entity entity, long due) {}

    private static final List<Check> CHECKS = new ArrayList<>();

    private ShopMulti() {}

    private static Item registryItem(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item it = rl == null ? Items.AIR : ForgeRegistries.ITEMS.getValue(rl);
        return it == null ? Items.AIR : it;
    }

    private static Item iconOf(String id) {
        Item it = registryItem(id);
        return it == Items.AIR ? Items.MINECART : it;
    }

    // ------------------------------------------------------------------ menus

    public static void open(ServerPlayer p, String shopId) {
        ShopProfiles.Profile sp = ShopProfiles.get(shopId);
        if (sp == null) {
            Msg.send(p, "&cVendeur inconnu : " + shopId + " (voir shops.json).");
            return;
        }
        if (!ShopZoneData.get(p.getServer()).zones.containsKey(sp.id)) {
            Msg.send(p, "&cAucune zone de pose pour ce vendeur. Le staff doit faire /vendeurzone set " + sp.id + ".");
            return;
        }
        List<String> cats = categoriesOf(sp);
        if (cats.isEmpty()) {
            Msg.send(p, "&cAucun véhicule pour ce vendeur (voir catalog.json et shops.json).");
            return;
        }
        if (cats.size() == 1) openModels(p, sp, cats.get(0), false);
        else openCategories(p, sp, cats);
    }

    private static List<String> categoriesOf(ShopProfiles.Profile sp) {
        List<String> out = new ArrayList<>();
        for (String c : Catalog.CATEGORIES) {
            if (!sp.categories.isEmpty() && sp.categories.stream().noneMatch(x -> x.equalsIgnoreCase(c))) continue;
            for (Catalog.Vehicle v : Catalog.VEHICLES) {
                if (v.category.equals(c)) {
                    out.add(c);
                    break;
                }
            }
        }
        return out;
    }

    private static void openCategories(ServerPlayer p, ShopProfiles.Profile sp, List<String> cats) {
        int n = Math.min(54, cats.size());
        ItemStack[] items = new ItemStack[n];
        for (int i = 0; i < n; i++) {
            Item icon = registryItem(Catalog.CATEGORY_ICON.getOrDefault(cats.get(i), "minecraft:book"));
            items[i] = Gui.item(icon == Items.AIR ? Items.BOOK : icon, "&e&l" + cats.get(i));
        }
        Gui.open(p, sp.title, (n + 8) / 9, items, slot -> {
            if (slot < n) Gui.later(p, () -> openModels(p, sp, cats.get(slot), true));
        });
    }

    private static void openModels(ServerPlayer p, ShopProfiles.Profile sp, String cat, boolean multi) {
        Map<String, List<Catalog.Vehicle>> models = new LinkedHashMap<>();
        for (Catalog.Vehicle v : Catalog.VEHICLES) {
            if (v.category.equals(cat)) models.computeIfAbsent(v.model, k -> new ArrayList<>()).add(v);
        }
        List<String> names = new ArrayList<>(models.keySet());
        int n = Math.min(53, names.size());
        int rows = (n + (multi ? 1 : 0) + 8) / 9;
        int back = rows * 9 - 1;
        ItemStack[] items = new ItemStack[rows * 9];
        for (int i = 0; i < n; i++) {
            List<Catalog.Vehicle> vs = models.get(names.get(i));
            int min = Integer.MAX_VALUE;
            for (Catalog.Vehicle v : vs) min = Math.min(min, v.price);
            items[i] = Gui.item(iconOf(vs.get(0).item), "&e&l" + names.get(i),
                    "&7À partir de &a" + min + "€", "&7" + vs.size() + " couleur(s)");
        }
        if (multi) items[back] = Gui.item(Items.ARROW, "&c« Retour");
        Gui.open(p, "&e" + cat, rows, items, slot -> {
            if (slot < n) Gui.later(p, () -> openColors(p, sp, cat, names.get(slot), multi));
            else if (multi && slot == back) Gui.later(p, () -> open(p, sp.id));
        });
    }

    private static void openColors(ServerPlayer p, ShopProfiles.Profile sp, String cat, String model, boolean multi) {
        List<Catalog.Vehicle> list = new ArrayList<>();
        for (Catalog.Vehicle v : Catalog.VEHICLES) {
            if (v.category.equals(cat) && v.model.equals(model)) list.add(v);
        }
        int n = Math.min(53, list.size());
        int rows = (n + 1 + 8) / 9;
        int back = rows * 9 - 1;
        ItemStack[] items = new ItemStack[rows * 9];
        for (int i = 0; i < n; i++) {
            Catalog.Vehicle v = list.get(i);
            Item glass = registryItem(Catalog.COLOR_ICON.getOrDefault(v.color, "minecraft:white_stained_glass"));
            items[i] = Gui.item(glass == Items.AIR ? Items.WHITE_STAINED_GLASS : glass, "&e&l" + v.color + " &7- &a" + v.price + "€");
        }
        items[back] = Gui.item(Items.ARROW, "&c« Retour");
        Gui.open(p, "&0" + model, rows, items, slot -> {
            if (slot < n) Gui.later(p, () -> {
                p.closeContainer();
                buy(p, sp, list.get(slot));
            });
            else if (slot == back) Gui.later(p, () -> openModels(p, sp, cat, multi));
        });
    }

    // ------------------------------------------------------------------ achat

    private static void buy(ServerPlayer p, ShopProfiles.Profile sp, Catalog.Vehicle v) {
        MinecraftServer server = p.getServer();
        if (!ShopZoneData.get(server).zones.containsKey(sp.id)) {
            Msg.send(p, "&cAucune zone de pose pour ce vendeur.");
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
        List<VehicleConfig.Denom> den = VehicleConfig.currency();
        int total = Money.count(p, den);
        if (total < v.price) {
            Msg.send(p, "&cIl te manque " + (v.price - total) + "€ pour acheter ce véhicule (" + v.price + "€, tu as " + total + "€).");
            return;
        }
        Money.take(p, v.price, den);
        ItemHandlerHelper.giveItemToPlayer(p, new ItemStack(it));

        String plate = generatePlate();

        Item plateItem = registryItem("mts:gvp.eu_plate");
        if (plateItem != Items.AIR && (sp.title.contains("voitures") || sp.title.contains("Camions") || sp.title.contains("Motos"))) {
            ItemStack plateStack = new ItemStack(plateItem, 2); // Quantité : 2
            CompoundTag plateNbt = plateStack.getOrCreateTag();

            plateNbt.putString("textCode", plate);
            plateNbt.putString("textCountry Code", "FR");

            plateStack.setHoverName(Gui.comp("&fPlaque : &e" + plate));

            ItemHandlerHelper.giveItemToPlayer(p, plateStack);
        }

        String label = v.model + (v.color.equals("Défaut") ? "" : " (" + v.color + ")");
        if (VehicleConfig.GIVE_KEY.get()) {
            Item k = registryItem(VehicleConfig.KEY_ITEM.get());
            if (k != Items.AIR) {
                ItemStack key = new ItemStack(k);
                key.setHoverName(Gui.comp("&e&l" + VehicleConfig.KEY_NAME_PREFIX.get() + " " + label));
                ItemHandlerHelper.giveItemToPlayer(p, key);
            }
        }
        Waiting w = new Waiting();
        w.shopId = sp.id;
        w.itemId = v.item;
        w.expireTick = server.getTickCount() + VehicleConfig.PLACE_TIMEOUT.get() * 20L;
        WAITING.put(p.getUUID(), w);
        Msg.send(p, "&a" + label + " achetée pour " + v.price + "€ ! Pose ton véhicule sur la zone marquée.");
    }

    private static double radiusOf(String shopId) {
        ShopProfiles.Profile sp = ShopProfiles.get(shopId);
        return sp != null && sp.placeRadius > 0 ? sp.placeRadius : VehicleConfig.SHOP_PLACE_RADIUS.get();
    }

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

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || e.loadedFromDisk() || WAITING.isEmpty()) return;
        if (!MtsBridge.isVehicle(e.getEntity())) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        CHECKS.add(new Check(e.getEntity(), server.getTickCount() + 5));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        WAITING.remove(e.getEntity().getUUID());
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
            for (Check c : due) onVehiclePlaced(server, c.entity());
        }
        if (!WAITING.isEmpty()) tickWaiting(server, now);
    }

    private static void onVehiclePlaced(MinecraftServer server, Entity ent) {
        if (ent.isRemoved() || MtsBridge.ownerOf(ent) != null) return;
        ShopZoneData zd = ShopZoneData.get(server);
        String dim = ent.level().dimension().location().toString();
        Iterator<Map.Entry<UUID, Waiting>> it = WAITING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Waiting> en = it.next();
            Waiting w = en.getValue();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            ShopZoneData.Zone z = zd.zones.get(w.shopId);
            if (p == null || z == null || !z.dim.equals(dim)) continue;
            double radius = radiusOf(w.shopId);
            if (ent.distanceToSqr(p) > (radius + 14) * (radius + 14)) continue;

            Vec3 c = Vec3.atCenterOf(z.pos);
            double dx = ent.getX() - c.x, dz = ent.getZ() - c.z;
            if (Math.sqrt(dx * dx + dz * dz) <= radius) {
                MtsBridge.setOwner(ent, en.getKey());
                it.remove();
                Msg.send(p, "&aVéhicule posé avec succès ! Bonne route.");
            } else {
                ent.discard();
                Item item = registryItem(w.itemId);
                if (item != Items.AIR) ItemHandlerHelper.giveItemToPlayer(p, new ItemStack(item));
                Msg.send(p, "&cTu dois poser ton véhicule DANS la zone marquée ! Reprends l'item dans ton inventaire et réessaie.");
            }
            return;
        }
    }

    private static void tickWaiting(MinecraftServer server, long now) {
        ShopZoneData zd = ShopZoneData.get(server);
        Iterator<Map.Entry<UUID, Waiting>> it = WAITING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Waiting> en = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            if (p == null || now > en.getValue().expireTick) {
                if (p != null) Msg.send(p, "&eLa zone de pose a été libérée (temps écoulé).");
                it.remove();
                continue;
            }
            ShopZoneData.Zone z = zd.zones.get(en.getValue().shopId);
            if (now % 10 != 0 || z == null || !p.level().dimension().location().toString().equals(z.dim)) continue;
            ServerLevel lvl = (ServerLevel) p.level();
            Vec3 c = Vec3.atCenterOf(z.pos);
            double r = radiusOf(en.getValue().shopId);
            lvl.sendParticles(p, ParticleTypes.HAPPY_VILLAGER, true, c.x, c.y + 0.6, c.z, 2, 0.2, 0.05, 0.2, 0.0);
            for (int a = 0; a < 360; a += 30) {
                double rad = Math.toRadians(a);
                lvl.sendParticles(p, ParticleTypes.HAPPY_VILLAGER, true, c.x + Math.cos(rad) * r, c.y + 0.6,
                        c.z + Math.sin(rad) * r, 1, 0.05, 0.05, 0.05, 0.0);
            }
        }
    }
}

package com.minenorth.vehicles.shop;

import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.handle.Money;
import com.minenorth.vehicles.handle.EconomyBridge;
import com.minenorth.vehicles.handle.MtsBridge;
import com.minenorth.vehicles.handle.MtsFuel;
import com.minenorth.vehicles.handle.Payment;
import com.minenorth.vehicles.miscs.Gui;
import com.minenorth.vehicles.miscs.Msg;
import com.minenorth.vehicles.registry.IdentityBridge;
import com.minenorth.vehicles.registry.PlateRegistry;
import fr.minenorth.api.PayResult;
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
            items[i] = Gui.item(glass == Items.AIR ? Items.WHITE_STAINED_GLASS : glass, "&e&l" + v.color + " &7- &a" + v.price + "€",
                    "&7Clique pour choisir le mode de paiement");
        }
        items[back] = Gui.item(Items.ARROW, "&c« Retour");
        Gui.open(p, "&0" + model, rows, items, slot -> {
            if (slot < n) Gui.later(p, () -> openPayment(p, sp, cat, model, multi, list.get(slot)));
            else if (slot == back) Gui.later(p, () -> openModels(p, sp, cat, multi));
        });
    }

    /** Choix du mode de paiement : espèces ou carte, avec l'état de chacun. */
    private static void openPayment(ServerPlayer p, ShopProfiles.Profile sp, String cat, String model, boolean multi, Catalog.Vehicle v) {
        List<VehicleConfig.Denom> den = VehicleConfig.currency();
        int cash = Money.count(p, den);
        PayResult card = Payment.cardStatus(p, v.price);
        String label = v.model + (v.color.equals("Défaut") ? "" : " (" + v.color + ")");

        ItemStack[] items = new ItemStack[9];
        items[4] = Gui.item(iconOf(v.item), "&e&l" + label, "&7Prix : &a" + v.price + "€");
        Item cashIcon = VehicleConfig.item("minenorth_eurobank:bill_50e");
        items[2] = Gui.item(cashIcon == Items.AIR ? Items.GOLD_INGOT : cashIcon, "&a&lPayer en espèces",
                "&7Espèces sur toi : &f" + cash + "€",
                cash >= v.price ? "&aClique pour payer" : "&cEspèces insuffisantes");
        Item cardIcon = VehicleConfig.item("minenorth_eurobank:bank_card");
        String cardLine = card == PayResult.OK ? "&aClique pour payer"
                : "&c" + (card == PayResult.INSUFFICIENT_FUNDS ? "Solde insuffisant" : card.message());
        items[6] = Gui.item(cardIcon == Items.AIR ? Items.PAPER : cardIcon, "&b&lPayer par carte",
                "&7Solde du compte : &f" + EconomyBridge.balance(p) + "€", cardLine);
        items[8] = Gui.item(Items.ARROW, "&c« Retour");
        Gui.open(p, "&0Paiement", 1, items, slot -> {
            if (slot == 2) Gui.later(p, () -> {
                p.closeContainer();
                buy(p, sp, v, Payment.Method.CASH);
            });
            else if (slot == 6) Gui.later(p, () -> {
                p.closeContainer();
                buy(p, sp, v, Payment.Method.CARD);
            });
            else if (slot == 8) Gui.later(p, () -> openColors(p, sp, cat, model, multi));
        });
    }

    // ------------------------------------------------------------------ achat

    private static void buy(ServerPlayer p, ShopProfiles.Profile sp, Catalog.Vehicle v, Payment.Method method) {
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
        String err = Payment.precheck(p, v.price, method, den, "pour acheter ce véhicule");
        if (err != null) {
            Msg.send(p, "&c" + err);
            return;
        }
        Payment.Result paid = Payment.pay(p, v.price, method, den, "garage:vente");
        if (!paid.ok()) {
            Msg.send(p, "&c" + paid.message());
            return;
        }
        ItemHandlerHelper.giveItemToPlayer(p, new ItemStack(it));

        String plate = generatePlate(server);

        Item plateItem = registryItem("mts:gvp.eu_plate");
        if (plateItem != Items.AIR && sp.givesPlates()) {
            register(p, sp, v, method, plate);

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
        Msg.send(p, "&a" + label + " achetée pour " + v.price + "€ (" + (method == Payment.Method.CARD ? "carte" : "espèces")
                + ") ! Pose ton véhicule sur la zone marquée.");
    }

    private static double radiusOf(String shopId) {
        ShopProfiles.Profile sp = ShopProfiles.get(shopId);
        return sp != null && sp.placeRadius > 0 ? sp.placeRadius : VehicleConfig.SHOP_PLACE_RADIUS.get();
    }

    /** Inscrit la vente au fichier des immatriculations (consultable par la police) avec l'identité RP de l'acheteur. */
    private static void register(ServerPlayer p, ShopProfiles.Profile sp, Catalog.Vehicle v, Payment.Method method, String plate) {
        PlateRegistry.Entry e = new PlateRegistry.Entry();
        e.plate = plate;
        e.model = v.model;
        e.color = v.color.equals("Défaut") ? "" : v.color;
        e.itemId = v.item;
        e.shopId = sp.id;
        e.method = method == Payment.Method.CARD ? "carte" : "espèces";
        e.price = v.price;
        e.owner = p.getUUID();
        e.ownerName = p.getGameProfile().getName();
        String[] id = IdentityBridge.identity(p.getServer(), p.getUUID());
        if (id != null) {
            e.firstName = id[0];
            e.lastName = id[1];
            e.birthDate = id[2];
            e.birthPlace = id[3];
            e.nationality = id[4];
            e.cardNumber = id[5];
        }
        e.time = System.currentTimeMillis();
        PlateRegistry.get(p.getServer()).add(p.getServer(), e);
        Msg.send(p, "&7Immatriculation &e" + plate + " &7enregistrée au nom de &f" + e.displayName() + "&7.");
    }

    /** Plaque aléatoire qui n'existe pas encore dans le fichier des immatriculations. */
    private static String generatePlate(MinecraftServer server) {
        PlateRegistry reg = PlateRegistry.get(server);
        String plate = generatePlate();
        for (int i = 0; i < 50 && reg.taken(plate); i++) plate = generatePlate();
        return plate;
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
        if (!FUEL.isEmpty()) tickFuel();
    }

    /** Véhicules achetés en attente du plein -> essais restants. */
    private static final Map<Entity, Integer> FUEL = new HashMap<>();

    private static void tickFuel() {
        Iterator<Map.Entry<Entity, Integer>> it = FUEL.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Entity, Integer> en = it.next();
            Entity ent = en.getKey();
            if (ent.isRemoved()) {
                it.remove();
                continue;
            }
            MtsFuel.Result r = MtsFuel.fill(ent, VehicleConfig.FUEL_FLUID.get());
            if (r != MtsFuel.Result.RETRY || en.getValue() <= 1) it.remove();
            else en.setValue(en.getValue() - 1);
        }
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
                if (VehicleConfig.FILL_FUEL.get()) FUEL.put(ent, 100);   // plein fait au tick suivant (moteurs montés après l'apparition)
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

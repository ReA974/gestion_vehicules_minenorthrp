package com.minenorth.vehicles.garage;

import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.handle.EconomyBridge;
import com.minenorth.vehicles.miscs.Gui;
import com.minenorth.vehicles.miscs.Msg;
import com.minenorth.vehicles.registry.PlateRegistry;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import fr.minenorth.api.BankService;
import fr.minenorth.api.MineNorth;
import fr.minenorth.api.PayResult;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Vente d'un véhicule du garage entre deux joueurs, avec sa plaque d'immatriculation.
 *   1. Le vendeur choisit le véhicule dans le menu garage (« Vendre un véhicule »).
 *   2. /vente <acheteur> <prix> envoie l'offre ; l'acheteur répond avec /vente accepter ou /vente refuser.
 *   3. À l'acceptation : virement bancaire acheteur -> vendeur, le véhicule passe d'un garage à l'autre et la plaque est
 *      transférée au nom de l'acheteur dans le fichier des immatriculations (consultable par la police).
 * La plaque du véhicule est retrouvée dans ses données (texte de la plaque posée) et comparée au fichier des immatriculations :
 * un véhicule qui porte la plaque d'un autre ne peut pas être vendu.
 */
@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class VehicleSale {
    private VehicleSale() {}

    private record Selection(GarageData.Stored vehicle, long expireMs) {}

    private record Offer(UUID seller, String sellerName, UUID buyer, GarageData.Stored vehicle, int price, String plate, long expireMs) {}

    /** Véhicule choisi par chaque vendeur (en attente de /vente). */
    private static final Map<UUID, Selection> SELECTED = new HashMap<>();
    /** Offre en cours par acheteur. */
    private static final Map<UUID, Offer> OFFERS = new HashMap<>();

    // ------------------------------------------------------------------ choix du véhicule

    public static void openSell(ServerPlayer p) {
        List<GarageData.Stored> list = GarageData.get(p.getServer()).of(p.getUUID());
        if (list.isEmpty()) {
            Msg.send(p, "&cTon garage est vide : aucun véhicule à vendre.");
            return;
        }
        int n = Math.min(54, list.size());
        ItemStack[] items = new ItemStack[(Math.min(6, (n + 8) / 9)) * 9];
        for (int i = 0; i < n; i++) {
            GarageData.Stored s = list.get(i);
            String plate = findPlate(p.getServer(), p.getUUID(), s.nbt);
            items[i] = Gui.item(Garage.iconOf(s.itemId), "&e&l" + s.label,
                    plate == null ? "&7Plaque : &8aucune plaque enregistrée" : "&7Plaque : &f" + plate,
                    "&7Clique pour choisir ce véhicule à vendre");
        }
        Gui.open(p, "&aQuel véhicule vendre ?", items.length / 9, items, slot -> {
            if (slot < n) Gui.later(p, () -> {
                p.closeContainer();
                select(p, slot);
            });
        });
    }

    private static void select(ServerPlayer p, int index) {
        List<GarageData.Stored> list = GarageData.get(p.getServer()).of(p.getUUID());
        if (index < 0 || index >= list.size()) return;
        GarageData.Stored s = list.get(index);
        SELECTED.put(p.getUUID(), new Selection(s, System.currentTimeMillis() + 5 * 60_000L));
        Msg.send(p, "&a" + s.label + " choisi. Propose-le avec &e/vente <joueur> <prix>&a (dans les 5 minutes).");
    }

    // ------------------------------------------------------------------ plaque du véhicule

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

    /** Plaque (du fichier des immatriculations) à ce propriétaire que porte ce véhicule, ou null. */
    static String findPlate(MinecraftServer server, UUID owner, CompoundTag nbt) {
        Set<String> strings = new HashSet<>();
        collect(nbt, strings);
        for (PlateRegistry.Entry e : PlateRegistry.get(server).ofOwner(owner)) {
            if (strings.contains(PlateRegistry.norm(e.plate))) return e.plate;
        }
        return null;
    }

    /** Entrée du fichier d'un AUTRE propriétaire dont la plaque est posée sur ce véhicule, ou null. */
    private static PlateRegistry.Entry foreignPlate(MinecraftServer server, UUID owner, CompoundTag nbt) {
        Set<String> strings = new HashSet<>();
        collect(nbt, strings);
        PlateRegistry reg = PlateRegistry.get(server);
        for (String s : strings) {
            PlateRegistry.Entry e = reg.byPlate(s);
            if (e != null && e.owner != null && !e.owner.equals(owner)) return e;
        }
        return null;
    }

    // ------------------------------------------------------------------ offre

    private static void propose(ServerPlayer seller, ServerPlayer buyer, int price) {
        MinecraftServer server = seller.getServer();
        Selection sel = SELECTED.get(seller.getUUID());
        GarageData data = GarageData.get(server);
        if (sel == null || System.currentTimeMillis() > sel.expireMs()) {
            Msg.send(seller, "&cChoisis d'abord un véhicule : menu du garage > « Vendre un véhicule ».");
            return;
        }
        List<GarageData.Stored> mine = data.of(seller.getUUID());
        if (!mine.contains(sel.vehicle())) {
            Msg.send(seller, "&cCe véhicule n'est plus dans ton garage.");
            return;
        }
        if (buyer.getUUID().equals(seller.getUUID())) {
            Msg.send(seller, "&cTu ne peux pas te vendre un véhicule à toi-même.");
            return;
        }
        if (price > VehicleConfig.SALE_MAX_PRICE.get()) {
            Msg.send(seller, "&cPrix maximum : " + VehicleConfig.SALE_MAX_PRICE.get() + "€.");
            return;
        }
        double max = VehicleConfig.SALE_DISTANCE.get();
        if (max > 0 && (seller.level() != buyer.level() || seller.distanceTo(buyer) > max)) {
            Msg.send(seller, "&cL'acheteur doit être à moins de " + (int) max + " blocs de toi.");
            return;
        }
        BankService bank = MineNorth.bank();
        if (!bank.hasAccount(server, buyer.getUUID()) || !bank.hasAccount(server, seller.getUUID())) {
            Msg.send(seller, "&cLe vendeur et l'acheteur doivent avoir un compte bancaire.");
            return;
        }
        GarageData.Stored v = sel.vehicle();
        PlateRegistry.Entry foreign = foreignPlate(server, seller.getUUID(), v.nbt);
        if (foreign != null) {
            Msg.send(seller, "&cCe véhicule porte la plaque &f" + foreign.plate + "&c, immatriculée au nom de &f" + foreign.displayName()
                    + "&c : vente impossible. Retire cette plaque d'abord.");
            return;
        }
        String plate = findPlate(server, seller.getUUID(), v.nbt);
        // Une seule offre sortante par vendeur
        OFFERS.values().removeIf(o -> o.seller().equals(seller.getUUID()));
        Offer offer = new Offer(seller.getUUID(), MineNorth.displayName(seller), buyer.getUUID(), v, price, plate,
                System.currentTimeMillis() + VehicleConfig.SALE_EXPIRE.get() * 1000L);
        OFFERS.put(buyer.getUUID(), offer);
        Msg.send(seller, "&aOffre envoyée à &f" + MineNorth.displayName(buyer) + "&a : " + v.label + " pour " + price + "€"
                + (plate == null ? "" : " (plaque " + plate + " incluse)") + ". Elle expire dans " + VehicleConfig.SALE_EXPIRE.get() + " s.");

        buyer.sendSystemMessage(Gui.comp(VehicleConfig.PREFIX.get() + " &e" + offer.sellerName() + " &7te propose son véhicule &e" + v.label
                + "&7 pour &a" + price + "€&7" + (plate == null ? "" : ", plaque &f" + plate + "&7 incluse") + "."));
        buyer.sendSystemMessage(Component.literal("  ")
                .append(button("[Accepter]", "§a", "/vente accepter"))
                .append(Component.literal("  "))
                .append(button("[Refuser]", "§c", "/vente refuser")));
    }

    private static Component button(String label, String color, String command) {
        return Component.literal(color + "§l" + label).withStyle(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }

    private static void accept(ServerPlayer buyer) {
        MinecraftServer server = buyer.getServer();
        Offer o = OFFERS.remove(buyer.getUUID());
        if (o == null) {
            Msg.send(buyer, "&cAucune offre en attente.");
            return;
        }
        ServerPlayer seller = server.getPlayerList().getPlayer(o.seller());
        if (System.currentTimeMillis() > o.expireMs()) {
            Msg.send(buyer, "&cL'offre a expiré.");
            return;
        }
        GarageData data = GarageData.get(server);
        List<GarageData.Stored> sl = data.of(o.seller());
        List<GarageData.Stored> bl = data.of(buyer.getUUID());
        if (!sl.contains(o.vehicle())) {
            Msg.send(buyer, "&cCe véhicule n'est plus dans le garage du vendeur.");
            return;
        }
        int max = VehicleConfig.GARAGE_MAX.get();
        if (bl.size() >= max) {
            Msg.send(buyer, "&cTon garage est plein (" + max + "/" + max + ") : fais de la place avant d'acheter.");
            return;
        }
        PlateRegistry reg = PlateRegistry.get(server);
        if (o.plate() != null) {
            PlateRegistry.Entry e = reg.byPlate(o.plate());
            if (e == null || !o.seller().equals(e.owner)) {
                Msg.send(buyer, "&cLa plaque n'est plus immatriculée au nom du vendeur : vente annulée.");
                return;
            }
        }
        PayResult r = MineNorth.bank().transfer(server, buyer.getUUID(), o.seller(), o.price() * EconomyBridge.CENTS_PER_UNIT);
        if (r != PayResult.OK) {
            Msg.send(buyer, "&cPaiement refusé : " + r.message());
            return;
        }
        sl.remove(o.vehicle());
        bl.add(o.vehicle());
        data.setDirty();
        if (o.plate() != null) reg.transfer(server, o.plate(), buyer.getUUID(), buyer.getGameProfile().getName(), o.price(), "vente entre particuliers");
        SELECTED.remove(o.seller());
        if (seller != null) Garage.removeOneKey(seller, o.vehicle().label);

        Msg.send(buyer, "&aAchat conclu : &e" + o.vehicle().label + "&a pour " + o.price() + "€. Il t'attend dans ton garage"
                + (o.plate() == null ? "." : ", plaque &f" + o.plate() + "&a à ton nom."));
        if (seller != null) {
            Msg.send(seller, "&aVente conclue : &e" + o.vehicle().label + "&a à &f" + MineNorth.displayName(buyer) + "&a pour " + o.price() + "€ (virement reçu).");
        }
        VehiclesMod.LOGGER.info("[Vente] {} -> {} : {} ({}) pour {}€", o.sellerName(), buyer.getGameProfile().getName(),
                o.vehicle().label, o.plate() == null ? "sans plaque" : o.plate(), o.price());
    }

    private static void refuse(ServerPlayer buyer) {
        Offer o = OFFERS.remove(buyer.getUUID());
        if (o == null) {
            Msg.send(buyer, "&cAucune offre en attente.");
            return;
        }
        Msg.send(buyer, "&eOffre refusée.");
        ServerPlayer seller = buyer.getServer().getPlayerList().getPlayer(o.seller());
        if (seller != null) Msg.send(seller, "&e" + MineNorth.displayName(buyer) + " a refusé ton offre.");
    }

    private static void cancel(ServerPlayer seller) {
        boolean any = OFFERS.values().removeIf(o -> o.seller().equals(seller.getUUID()));
        Msg.send(seller, any ? "&eOffre annulée." : "&cTu n'as aucune offre en cours.");
    }

    // ------------------------------------------------------------------ commandes et nettoyage

    @SubscribeEvent
    public static void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("vente")
                .then(Commands.literal("accepter").executes(c -> run(c.getSource(), VehicleSale::accept)))
                .then(Commands.literal("refuser").executes(c -> run(c.getSource(), VehicleSale::refuse)))
                .then(Commands.literal("annuler").executes(c -> run(c.getSource(), VehicleSale::cancel)))
                .then(Commands.argument("acheteur", EntityArgument.player())
                        .then(Commands.argument("prix", IntegerArgumentType.integer(1))
                                .executes(c -> {
                                    propose(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "acheteur"),
                                            IntegerArgumentType.getInteger(c, "prix"));
                                    return 1;
                                }))));
    }

    private static int run(CommandSourceStack src, java.util.function.Consumer<ServerPlayer> action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        action.accept(src.getPlayerOrException());
        return 1;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        UUID id = e.getEntity().getUUID();
        SELECTED.remove(id);
        OFFERS.remove(id);
        OFFERS.values().removeIf(o -> o.seller().equals(id));
    }
}

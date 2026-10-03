package com.minenorth.vehicles.miscs;

import com.minenorth.vehicles.handle.MtsBridge;
import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.garage.Garage;
import com.minenorth.vehicles.garage.GarageData;
import com.minenorth.vehicles.shop.Catalog;
import com.minenorth.vehicles.shop.ShopMulti;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class VehicleCommands {
    private VehicleCommands() {}

    private static boolean admin(CommandSourceStack s) {
        return s.hasPermission(VehicleConfig.COMMAND_LEVEL.get());
    }

    private static void say(CommandSourceStack s, String msg) {
        // 1. On applique le préfixe et on remplace les codes de couleur '&' par '§'
        String fullMsg = (VehicleConfig.PREFIX.get() + " " + msg).replace('&', '§');

        // 2. On envoie directement le message système à l'entité source (joueur ou console)
        s.sendSystemMessage(Component.literal(fullMsg));
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();

        // --- Catalogue du vendeur ---
        d.register(Commands.literal("vloaddata").requires(VehicleCommands::admin).executes(c -> reload(c.getSource())));
        d.register(Commands.literal("vehicleshop").requires(VehicleCommands::admin)
                .then(Commands.literal("reload").executes(c -> reload(c.getSource())))
                .then(Commands.literal("check").executes(c -> check(c.getSource()))));

        // --- Menus PNJ (exécutés par la console / un PNJ) ---
        d.register(Commands.literal("vendeurvehicule").requires(VehicleCommands::admin)
                .executes(c -> {
                    ShopMulti.open(c.getSource().getPlayerOrException(), "default");
                    return 1;
                })
                .then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    ShopMulti.open(c.getSource().getPlayerOrException(), "default");
                    return 1;
                })));
        d.register(Commands.literal("garagemenu").requires(VehicleCommands::admin)
                .executes(c -> {
                    Garage.openMenu(c.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    Garage.openMenu(EntityArgument.getPlayer(c, "player"));
                    return 1;
                })));

        // --- Zones garage ---
        d.register(Commands.literal("garagezone").requires(VehicleCommands::admin)
                .then(Commands.literal("list").executes(c -> listZones(c.getSource())))
                .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.word())
                        .executes(c -> removeZone(c.getSource(), StringArgumentType.getString(c, "name")))))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(c -> setZone(c, VehicleConfig.DEFAULT_ZONE_RADIUS.get(), ""))
                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.5))
                                .executes(c -> setZone(c, DoubleArgumentType.getDouble(c, "radius"), ""))
                                .then(Commands.argument("tag", StringArgumentType.word())
                                        .executes(c -> setZone(c, DoubleArgumentType.getDouble(c, "radius"),
                                                StringArgumentType.getString(c, "tag")))))));

        // --- Admin garage ---
        d.register(Commands.literal("garageadmin").requires(VehicleCommands::admin)
                .then(Commands.literal("see").then(Commands.argument("target", GameProfileArgument.gameProfile())
                        .executes(VehicleCommands::adminSee)))
                .then(Commands.literal("add").then(Commands.argument("target", GameProfileArgument.gameProfile())
                        .executes(VehicleCommands::adminAdd)))
                .then(Commands.literal("remove").then(Commands.argument("target", GameProfileArgument.gameProfile())
                        .executes(VehicleCommands::adminRemove)))
                .then(Commands.literal("reset").then(Commands.argument("target", GameProfileArgument.gameProfile())
                        .executes(VehicleCommands::adminReset))));

        // --- Diagnostic MTS ---
        d.register(Commands.literal("vehicledebug").requires(VehicleCommands::admin).executes(c -> debug(c.getSource())));
    }

    // ------------------------------------------------------------------ catalogue

    private static int reload(CommandSourceStack s) {
        int n = Catalog.load();
        if (n < 0) say(s, "&cErreur de lecture de catalog.json (voir la console).");
        else say(s, "&aCatalogue chargé : " + n + " véhicules.");
        return Math.max(n, 0);
    }

    private static int check(CommandSourceStack s) {
        List<String> missing = new ArrayList<>();
        for (Catalog.Vehicle v : Catalog.VEHICLES) {
            ResourceLocation rl = ResourceLocation.tryParse(v.item);
            if (rl == null || !ForgeRegistries.ITEMS.containsKey(rl)) missing.add(v.item);
        }
        say(s, "&e" + Catalog.VEHICLES.size() + " véhicules, &c" + missing.size() + " item(s) introuvable(s)&e.");
        for (int i = 0; i < Math.min(10, missing.size()); i++) say(s, "&7- " + missing.get(i));
        if (missing.size() > 10) say(s, "&7... (liste complète dans la console)");
        for (String m : missing) VehiclesMod.LOGGER.warn("[Vehicules] Item introuvable : {}", m);
        return missing.size();
    }

    // ------------------------------------------------------------------ zones

    private static int setZone(CommandContext<CommandSourceStack> c, double radius, String tag) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(c, "name");
        GarageData g = GarageData.get(p.getServer());
        GarageData.Zone z = new GarageData.Zone();
        z.name = name;
        z.dim = p.level().dimension().location().toString();
        z.x = p.getX();
        z.y = p.getY();
        z.z = p.getZ();
        z.radius = radius;
        z.tag = tag;
        g.zones.put(name, z);
        g.setDirty();
        Msg.send(p, "&aGarage \"" + name + "\" défini à ta position (" + radius + " blocs, "
                + (tag.isEmpty() ? "public" : "tag requis : " + tag) + ").");
        return 1;
    }

    private static int listZones(CommandSourceStack s) {
        GarageData g = GarageData.get(s.getServer());
        VehiclesMod.LOGGER.warn("[Vehicules] Garage trouvé : {}", g);
        if (g.zones.isEmpty()) {
            say(s, "&eAucune zone garage définie. Place-toi au centre et fais /garagezone <nom> [rayon] [tag].");
            return 0;
        }
        say(s, "&eGarages définis (" + g.zones.size() + ") :");
        for (GarageData.Zone z : g.zones.values()) {
            say(s, String.format(java.util.Locale.ROOT, "&7- &f%s &8| %s | %.0f %.0f %.0f | rayon %.1f | %s",
                    z.name, z.dim, z.x, z.y, z.z, z.radius, z.tag.isEmpty() ? "public" : z.tag));
        }
        return g.zones.size();
    }

    private static int removeZone(CommandSourceStack s, String name) {
        GarageData g = GarageData.get(s.getServer());
        if (g.zones.remove(name) == null) {
            say(s, "&cGarage inconnu : " + name);
            return 0;
        }
        g.setDirty();
        say(s, "&aGarage \"" + name + "\" supprimé.");
        return 1;
    }

    // ------------------------------------------------------------------ admin garage

    private static GameProfile target(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return GameProfileArgument.getGameProfiles(c, "target").iterator().next();
    }

    private static int adminSee(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        GameProfile gp = target(c);
        List<GarageData.Stored> list = GarageData.get(p.getServer()).of(gp.getId());
        if (list.isEmpty()) {
            Msg.send(p, "&e" + gp.getName() + " n'a aucun véhicule dans son garage.");
            return 0;
        }
        Gui.open(p, "&aGarage de " + gp.getName(), Math.min(6, (list.size() + 8) / 9), Garage.listItems(list), slot -> {});
        return 1;
    }

    private static int adminRemove(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        GameProfile gp = target(c);
        GarageData g = GarageData.get(p.getServer());
        List<GarageData.Stored> list = g.of(gp.getId());
        if (list.isEmpty()) {
            Msg.send(p, "&cLe garage de " + gp.getName() + " est déjà vide.");
            return 0;
        }
        UUID id = gp.getId();
        Gui.open(p, "&cRetirer un véhicule", Math.min(6, (list.size() + 8) / 9), Garage.listItems(list), slot -> {
            if (slot < list.size()) Gui.later(p, () -> {
                p.closeContainer();
                List<GarageData.Stored> cur = g.of(id);
                if (slot < cur.size()) {
                    cur.remove(slot);
                    g.setDirty();
                    Msg.send(p, "&aVéhicule retiré du garage. (" + cur.size() + " restant(s))");
                }
            });
        });
        return 1;
    }

    /** Range dans le garage du joueur ciblé le véhicule dans lequel se trouve l'admin (ou le plus proche). */
    private static int adminAdd(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        GameProfile gp = target(c);
        Garage.chooseAndStore(p, gp.getId(), true);
        return 1;
    }

    private static int adminReset(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        GameProfile gp = target(c);
        GarageData g = GarageData.get(c.getSource().getServer());
        g.garages.remove(gp.getId());
        g.setDirty();
        say(c.getSource(), "&aLe garage de " + gp.getName() + " a été remis à 0.");
        return 1;
    }

    // ------------------------------------------------------------------ diagnostic

    private static int debug(CommandSourceStack s) throws CommandSyntaxException {
        ServerPlayer p = s.getPlayerOrException();
        say(s, "&eVéhicule configuré : &f" + VehicleConfig.VEHICLE_ENTITY.get());
        int depth = 0;
        for (Entity e = p.getVehicle(); e != null; e = e.getVehicle()) {
            say(s, "&7monture niveau " + (depth++) + " : &f" + MtsBridge.typeId(e));
        }
        if (depth == 0) say(s, "&7Tu n'es assis dans aucune entité.");
        Entity v = MtsBridge.vehicleOf(p);
        say(s, "&eVéhicule détecté (assis) : &f" + (v == null ? "aucun" : MtsBridge.typeId(v)));

        List<Entity> near = ((ServerLevel) p.level()).getEntities((Entity) null, p.getBoundingBox().inflate(16), MtsBridge::isVehicle);
        say(s, "&e" + near.size() + " véhicule(s) à moins de 16 blocs :");
        for (Entity e : near) {
            net.minecraft.nbt.CompoundTag t = MtsBridge.snapshot(e);
            String id = MtsBridge.itemId(t);
            boolean known = !id.isEmpty() && ForgeRegistries.ITEMS.containsKey(ResourceLocation.tryParse(id));
            say(s, String.format("&7- dist %.1f, item déduit &f%s &7(%s), propriétaire %s",
                    e.distanceTo(p), id.isEmpty() ? "?" : id, known ? "&aexiste" : "&cintrouvable", MtsBridge.ownerOf(e)));
            String keys = String.join(", ", t.getAllKeys());
            say(s, "&8  clés NBT : " + (keys.length() > 300 ? keys.substring(0, 300) + "..." : keys));
        }
        return 1;
    }
}

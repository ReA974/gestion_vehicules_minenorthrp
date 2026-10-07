package com.minenorth.vehicles.registry;

import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.config.VehicleConfig;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * /immat <plaque>              : fiche du véhicule et de son propriétaire (police ou OP)
 * /immat recherche <texte>     : recherche par plaque, nom, prénom, pseudo ou modèle (police ou OP)
 * /immat joueur <joueur>       : véhicules immatriculés au nom d'un joueur (police ou OP)
 * /immat transferer <plaque> <joueur> : change le propriétaire (OP)
 * /immat supprimer <plaque>    : retire une ligne du fichier (OP)
 * /immat export                : réécrit le JSON (OP)
 */
@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class PlateCommands {
    private PlateCommands() {}

    private static boolean admin(CommandSourceStack s) { return s.hasPermission(VehicleConfig.COMMAND_LEVEL.get()); }

    /** Policier (effectifs du mod Police, via MineNorth API) ou OP. */
    private static boolean police(CommandSourceStack s) {
        if (admin(s)) return true;
        return s.getEntity() instanceof ServerPlayer p && fr.minenorth.api.MineNorth.police().isPolice(p);
    }

    private static void say(CommandSourceStack s, String msg) {
        s.sendSystemMessage(Component.literal((VehicleConfig.PREFIX.get() + " " + msg).replace('&', '§')));
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        d.register(Commands.literal("immat").requires(PlateCommands::police)
                .then(Commands.literal("recherche").then(Commands.argument("texte", StringArgumentType.greedyString())
                        .executes(c -> search(c.getSource(), StringArgumentType.getString(c, "texte")))))
                .then(Commands.literal("joueur").then(Commands.argument("joueur", GameProfileArgument.gameProfile())
                        .executes(c -> player(c.getSource(), GameProfileArgument.getGameProfiles(c, "joueur")))))
                .then(Commands.literal("transferer").requires(PlateCommands::admin)
                        .then(Commands.argument("plaque", StringArgumentType.string())
                                .then(Commands.argument("joueur", GameProfileArgument.gameProfile()).executes(c -> {
                                    String plate = StringArgumentType.getString(c, "plaque");
                                    Collection<GameProfile> gp = GameProfileArgument.getGameProfiles(c, "joueur");
                                    if (gp.size() != 1) { say(c.getSource(), "&cIndique un seul joueur."); return 0; }
                                    GameProfile g = gp.iterator().next();
                                    boolean ok = PlateRegistry.get(c.getSource().getServer()).transfer(c.getSource().getServer(), plate, g.getId(), g.getName());
                                    say(c.getSource(), ok ? "&aPlaque &e" + plate + " &atransférée à &f" + g.getName() + "&a." : "&cPlaque inconnue : " + plate);
                                    return ok ? 1 : 0;
                                }))))
                .then(Commands.literal("supprimer").requires(PlateCommands::admin)
                        .then(Commands.argument("plaque", StringArgumentType.string()).executes(c -> {
                            String plate = StringArgumentType.getString(c, "plaque");
                            boolean ok = PlateRegistry.get(c.getSource().getServer()).remove(c.getSource().getServer(), plate);
                            say(c.getSource(), ok ? "&aPlaque &e" + plate + " &aretirée du fichier." : "&cPlaque inconnue : " + plate);
                            return ok ? 1 : 0;
                        })))
                .then(Commands.literal("export").requires(PlateCommands::admin).executes(c -> {
                    PlateRegistry.get(c.getSource().getServer()).exportJson(c.getSource().getServer());
                    say(c.getSource(), "&aFichier exporté : <monde>/minenorth_immatriculations.json");
                    return 1;
                }))
                .then(Commands.argument("plaque", StringArgumentType.greedyString())
                        .executes(c -> show(c.getSource(), StringArgumentType.getString(c, "plaque")))));
    }

    private static int show(CommandSourceStack s, String plate) {
        PlateRegistry.Entry e = PlateRegistry.get(s.getServer()).byPlate(plate);
        if (e == null) { say(s, "&cAucun véhicule immatriculé &e" + plate + "&c."); return 0; }
        String[] live = IdentityBridge.identity(s.getServer(), e.owner);
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy HH:mm");
        say(s, "&9&lFichier des immatriculations &7— &e&l" + e.plate);
        say(s, "&bVéhicule : &f" + e.model + (e.color.isEmpty() ? "" : " (" + e.color + ")"));
        if (live != null) {
            say(s, "&bPropriétaire : &f" + live[1].toUpperCase() + " " + live[0] + " &7(" + e.ownerName + ")");
            say(s, "&bNé(e) le : &f" + live[2] + " &bà &f" + live[3] + " &7— &bNationalité : &f" + live[4]);
            say(s, "&bN° de carte : &f" + live[5]);
        } else if (!e.lastName.isBlank() || !e.firstName.isBlank()) {
            say(s, "&bPropriétaire : &f" + e.lastName.toUpperCase() + " " + e.firstName + " &7(" + e.ownerName + ")");
            say(s, "&bNé(e) le : &f" + e.birthDate + " &bà &f" + e.birthPlace + " &7— &bNationalité : &f" + e.nationality);
            say(s, "&bN° de carte : &f" + e.cardNumber);
        } else {
            say(s, "&bPropriétaire : &f" + e.ownerName + " &c(aucune carte d'identité)");
        }
        say(s, "&bAchat : &f" + fmt.format(new Date(e.time)) + " &7— vendeur " + e.shopId + ", " + e.price + "€ (" + e.method + ")");
        return 1;
    }

    private static int search(CommandSourceStack s, String q) {
        List<PlateRegistry.Entry> list = PlateRegistry.get(s.getServer()).search(q);
        if (list.isEmpty()) { say(s, "&cAucun résultat pour « " + q + " »."); return 0; }
        say(s, "&9" + list.size() + " résultat(s) :");
        for (int i = 0; i < list.size() && i < 15; i++) line(s, list.get(i));
        if (list.size() > 15) say(s, "&7… affine ta recherche.");
        return list.size();
    }

    private static int player(CommandSourceStack s, Collection<GameProfile> profiles) {
        int n = 0;
        for (GameProfile g : profiles) {
            List<PlateRegistry.Entry> list = PlateRegistry.get(s.getServer()).ofOwner(g.getId());
            say(s, "&9Véhicules de &f" + g.getName() + " &9: " + list.size());
            for (PlateRegistry.Entry e : list) line(s, e);
            n += list.size();
        }
        return n;
    }

    private static void line(CommandSourceStack s, PlateRegistry.Entry e) {
        say(s, "&e" + e.plate + " &7- &f" + e.model + (e.color.isEmpty() ? "" : " (" + e.color + ")") + " &7- &b" + e.displayName());
    }
}

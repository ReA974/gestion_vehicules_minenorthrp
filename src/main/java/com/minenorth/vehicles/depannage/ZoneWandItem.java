package com.minenorth.vehicles.depannage;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Bâton de sélection de zone garage : clic droit sur un bloc = centre, clic gauche = bord. Les clics sont gérés par {@link ZoneSelection}. */
public class ZoneWandItem extends Item {
    public ZoneWandItem() { super(new Item.Properties().stacksTo(1)); }

    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) { return false; }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.translatable(getDescriptionId() + ".tooltip").withStyle(ChatFormatting.GRAY));
        tip.add(Component.translatable(getDescriptionId() + ".tooltip2").withStyle(ChatFormatting.DARK_GRAY));
    }
}

package com.minenorth.vehicles.client;

import com.minenorth.vehicles.menu.CloseMenuPacket;
import com.minenorth.vehicles.menu.MenuClickPacket;
import com.minenorth.vehicles.menu.MenuNet;
import com.minenorth.vehicles.menu.OpenMenuPacket;
import com.minenorth.vehicles.menu.OpenMenuPacket.Slot;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu "jeu vidéo" : les objets du menu serveur sont affichés comme des cartes (icône, nom, deux lignes de description).
 * Un objet flèche / barrière placé sur le dernier slot devient le bouton Retour / Fermer en bas de la fenêtre.
 */
@OnlyIn(Dist.CLIENT)
public class MenuScreen extends Screen {
    private static final ResourceLocation LOGO = new ResourceLocation("minenorth_eurobank", "textures/gui/logo.png");
    private static final int BG = 0xFF161048, PANEL = 0xFF0E0A34, CARD = 0xFF1C1560, EDGE = 0xFF3A2FA0;
    private static final int CYAN = 0xFF20AAEB, VIOLET = 0xFF4A3CB4, PINK = 0xFFC83CF0, GREY = 0xFF8FA8E0, TEXT = 0xFFCFE3FF;
    private static final int CW = 150, CH = 40, GAP = 4, PAD = 12;

    private final int menuId;
    private final String title;
    private final List<Slot> cards = new ArrayList<>();
    private Slot footer;
    private int cols = 1, scroll;
    private int left, top, W, H, bodyX, bodyTop, bodyH;
    private long lockUntil;

    public MenuScreen(OpenMenuPacket m) {
        super(Component.literal("Menu"));
        this.menuId = m.menuId;
        this.title = m.title;
        int last = m.rows * 9 - 1;
        for (Slot s : m.slots) {
            boolean back = s.slot() == last && (s.stack().is(Items.ARROW) || s.stack().is(Items.BARRIER));
            if (back) footer = s;
            else cards.add(s);
        }
    }

    // ------------------------------------------------------------------ mise en page

    private static String plain(String s) {
        return s.replaceAll("(?i)[&\u00a7][0-9a-fk-or]", "");
    }

    @Override
    protected void init() {
        int n = cards.size();
        int wantCols = n <= 3 ? Math.max(1, n) : n <= 8 ? 2 : 3;
        int fit = Math.max(1, (width - 2 * PAD - 16 + GAP) / (CW + GAP));
        cols = Math.min(wantCols, fit);
        int rowsNeeded = Math.max(1, (n + cols - 1) / cols);
        W = Math.max(230, cols * (CW + GAP) - GAP + 2 * PAD);
        int header = 40, footerH = 30;
        int maxBody = Math.max(CH, height - 16 - header - footerH);
        bodyH = Math.min(rowsNeeded * (CH + GAP) - GAP, maxBody);
        H = header + bodyH + 8 + footerH;
        left = (width - W) / 2;
        top = (height - H) / 2;
        bodyTop = top + header;
        bodyX = left + (W - (cols * (CW + GAP) - GAP)) / 2;
    }

    private int visibleRows() {
        return Math.max(1, (bodyH + GAP) / (CH + GAP));
    }

    private int maxScroll() {
        int rows = (cards.size() + cols - 1) / cols;
        return Math.max(0, rows - visibleRows());
    }

    private int cardAt(double mx, double my) {
        if (mx < bodyX || my < bodyTop || my >= bodyTop + bodyH) return -1;
        int col = (int) ((mx - bodyX) / (CW + GAP));
        int row = (int) ((my - bodyTop) / (CH + GAP));
        if (col < 0 || col >= cols || row < 0 || row >= visibleRows()) return -1;
        if ((mx - bodyX) % (CW + GAP) >= CW || (my - bodyTop) % (CH + GAP) >= CH) return -1;
        int idx = (row + scroll) * cols + col;
        return idx < cards.size() ? idx : -1;
    }

    private boolean inFooter(double mx, double my) {
        int y = top + H - 26;
        return footer != null && mx >= left + PAD && mx < left + PAD + 120 && my >= y && my < y + 18;
    }

    private boolean inClose(double mx, double my) {
        return mx >= left + W - 24 && mx < left + W - 6 && my >= top + 6 && my < top + 24;
    }

    // ------------------------------------------------------------------ interaction

    private void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private void send(Slot s, int button) {
        long now = System.currentTimeMillis();
        if (now < lockUntil) return;
        lockUntil = now + 600;
        click();
        MenuNet.CHANNEL.sendToServer(new MenuClickPacket(menuId, s.slot(), button));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (inClose(mx, my)) {
            click();
            onClose();
            return true;
        }
        if (inFooter(mx, my)) {
            send(footer, button);
            return true;
        }
        int i = cardAt(mx, my);
        if (i >= 0) {
            send(cards.get(i), button);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll + (int) -Math.signum(delta)));
        return true;
    }

    @Override
    public void removed() {
        MenuNet.CHANNEL.sendToServer(new CloseMenuPacket(menuId));
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ rendu

    private void drawScaled(GuiGraphics g, Component c, int x, int y, float s, int color) {
        g.pose().pushPose();
        g.pose().scale(s, s, 1f);
        g.drawString(font, c, Math.round(x / s), Math.round(y / s), color, false);
        g.pose().popPose();
    }

    private static List<Component> lore(ItemStack st) {
        List<Component> out = new ArrayList<>();
        CompoundTag d = st.getTagElement("display");
        if (d == null || !d.contains("Lore", Tag.TAG_LIST)) return out;
        ListTag l = d.getList("Lore", Tag.TAG_STRING);
        for (int i = 0; i < l.size(); i++) {
            try {
                Component c = Component.Serializer.fromJson(l.getString(i));
                if (c != null && !c.getString().isBlank()) out.add(c);
            } catch (RuntimeException ignored) {
                // ligne de description illisible : ignorée
            }
        }
        return out;
    }

    private void firstLine(GuiGraphics g, Component c, int x, int y, int maxW, int color) {
        List<FormattedCharSequence> lines = font.split(c, maxW);
        if (!lines.isEmpty()) g.drawString(font, lines.get(0), x, y, color, false);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);

        g.fill(left - 3, top - 3, left + W + 3, top + H + 3, 0xFF0A0820);
        g.fillGradient(left, top, left + W, top + H, 0xFF1E1566, BG);
        g.fill(left, top + 34, left + W, top + 35, CYAN);
        g.fill(left, top + 35, left + W, top + 36, PINK);

        RenderSystem.enableBlend();
        g.blit(LOGO, left + 8, top + 4, 26, 26, 0, 0, 96, 96, 96, 96);
        drawScaled(g, Component.literal(plain(title)).withStyle(ChatFormatting.BOLD), left + 40, top + 11, 1.4f, 0xFFFFFFFF);

        boolean xHov = inClose(mx, my);
        g.fill(left + W - 24, top + 6, left + W - 6, top + 24, xHov ? PINK : VIOLET);
        g.drawCenteredString(font, "X", left + W - 15, top + 11, 0xFFFFFFFF);

        // cartes
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        int hover = cardAt(mx, my);
        g.enableScissor(left, bodyTop, left + W, bodyTop + bodyH);
        for (int i = 0; i < cards.size(); i++) {
            int row = i / cols - scroll;
            if (row < 0 || row >= visibleRows()) continue;
            int x = bodyX + (i % cols) * (CW + GAP);
            int y = bodyTop + row * (CH + GAP);
            renderCard(g, cards.get(i).stack(), x, y, i == hover);
        }
        g.disableScissor();
        if (maxScroll() > 0) {
            int total = (cards.size() + cols - 1) / cols;
            int barH = Math.max(12, bodyH * visibleRows() / total);
            int barY = bodyTop + (bodyH - barH) * scroll / maxScroll();
            g.fill(left + W - 6, bodyTop, left + W - 3, bodyTop + bodyH, PANEL);
            g.fill(left + W - 6, barY, left + W - 3, barY + barH, CYAN);
        }

        // pied : retour + aide
        if (footer != null) {
            int y = top + H - 26;
            boolean hov = inFooter(mx, my);
            boolean close = footer.stack().is(Items.BARRIER);
            int base = close ? PINK : VIOLET;
            g.fill(left + PAD, y, left + PAD + 120, y + 18, hov ? 0xFF6A5AD6 : base);
            if (hov) {
                g.fill(left + PAD, y, left + PAD + 120, y + 1, 0xFFFFFFFF);
                g.fill(left + PAD, y + 17, left + PAD + 120, y + 18, 0xFFFFFFFF);
            }
            List<FormattedCharSequence> t = font.split(footer.stack().getHoverName(), 112);
            if (!t.isEmpty()) {
                g.drawString(font, t.get(0), left + PAD + 60 - font.width(t.get(0)) / 2, y + 5, 0xFFFFFFFF, false);
            }
        }
        String hint = "Echap : fermer";
        g.drawString(font, hint, left + W - PAD - font.width(hint), top + H - 21, GREY, false);

        super.render(g, mx, my, pt);

        if (hover >= 0) g.renderTooltip(font, cards.get(hover).stack(), mx, my);
    }

    private void renderCard(GuiGraphics g, ItemStack st, int x, int y, boolean hov) {
        g.fill(x, y, x + CW, y + CH, hov ? 0xFF2E2480 : CARD);
        int edge = hov ? CYAN : EDGE;
        g.fill(x, y, x + CW, y + 1, edge);
        g.fill(x, y + CH - 1, x + CW, y + CH, edge);
        g.fill(x, y, x + 1, y + CH, edge);
        g.fill(x + CW - 1, y, x + CW, y + CH, edge);
        if (hov) g.fill(x, y, x + 2, y + CH, CYAN);

        g.pose().pushPose();
        g.pose().translate(x + 7, y + 6, 0);
        g.pose().scale(1.75f, 1.75f, 1f);
        g.renderItem(st, 0, 0);
        g.pose().popPose();

        int tx = x + 42, tw = CW - 46;
        firstLine(g, st.getHoverName(), tx, y + 5, tw, 0xFFFFFFFF);
        List<Component> lines = lore(st);
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            firstLine(g, lines.get(i), tx, y + 16 + i * 10, tw, TEXT);
        }
    }
}

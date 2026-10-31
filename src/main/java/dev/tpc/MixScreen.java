package dev.tpc;

import com.mojang.blaze3d.platform.NativeImage;
import dev.tpc.PackScanner.Source;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Minecraft 26.x menu.
 * Left: a short fixed list (Armor, Sword, Crystal, ... Sky, GUI) - only entries you have textures for.
 * Middle: the styles (resource packs) for the chosen entry. Right: big preview + overview of everything in that style.
 */
public class MixScreen extends Screen {
    record Img(Identifier id, int w, int h) {}

    // ---------- the fixed list ----------
    record Cat(String name, Predicate<String> match, int max, List<String> samples, List<String> top, List<String> side) {}

    private static String nm(String p) { return p.substring(p.lastIndexOf('/') + 1, p.length() - 4); }
    private static boolean item(String p) { return p.startsWith("item/"); }

    private static Cat cat(String name, Predicate<String> m, int max, String... samples) {
        return new Cat(name, m, max, List.of(samples), null, null);
    }
    private static Cat cube(String name, Predicate<String> m, List<String> top, List<String> side) {
        return new Cat(name, m, 1, top, top, side);
    }

    static final List<Cat> CATS = List.of(
            cat("Armor", p -> {
                String n = nm(p);
                return p.startsWith("models/armor/") || p.startsWith("entity/equipment/humanoid")
                        || (item(p) && (n.contains("helmet") || n.contains("chestplate") || n.contains("leggings") || n.contains("boots")));
            }, 4, "netherite_helmet", "netherite_chestplate", "netherite_leggings", "netherite_boots",
                    "diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots",
                    "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots"),
            cat("Sword", p -> item(p) && nm(p).endsWith("_sword"), 1, "netherite_sword", "diamond_sword", "iron_sword", "golden_sword", "stone_sword", "wooden_sword"),
            cat("Pickaxe", p -> item(p) && nm(p).endsWith("_pickaxe"), 1, "netherite_pickaxe", "diamond_pickaxe", "iron_pickaxe", "golden_pickaxe", "stone_pickaxe", "wooden_pickaxe"),
            cat("Axe", p -> item(p) && nm(p).endsWith("_axe"), 1, "netherite_axe", "diamond_axe", "iron_axe", "golden_axe", "stone_axe", "wooden_axe"),
            cat("Crossbow", p -> item(p) && nm(p).startsWith("crossbow"), 1, "crossbow_standby", "crossbow_pulling_0", "crossbow_arrow", "crossbow_firework"),
            cat("Cobweb", p -> p.equals("block/cobweb.png") || p.equals("item/cobweb.png"), 1, "cobweb"),
            cat("Crystal", p -> p.startsWith("entity/end_crystal/") || p.equals("item/end_crystal.png"), 1, "end_crystal"),
            cube("Obsidian", p -> p.equals("block/obsidian.png") || p.equals("block/crying_obsidian.png"), List.of("obsidian", "crying_obsidian"), List.of("obsidian", "crying_obsidian")),
            cube("Anchor", p -> p.startsWith("block/respawn_anchor"), List.of("respawn_anchor_top", "respawn_anchor_top_off"), List.of("respawn_anchor_side4", "respawn_anchor_side0")),
            cube("Glowstone", p -> p.equals("block/glowstone.png"), List.of("glowstone"), List.of("glowstone")),
            cat("XP Bottle", p -> p.equals("item/experience_bottle.png"), 1, "experience_bottle"),
            cat("Ender Pearl", p -> p.equals("item/ender_pearl.png"), 1, "ender_pearl"),
            cat("Golden Apple", p -> p.equals("item/golden_apple.png") || p.equals("item/enchanted_golden_apple.png"), 2, "golden_apple", "enchanted_golden_apple"),
            cat("Elytra", p -> nm(p).contains("elytra"), 1, "elytra", "broken_elytra"),
            cat("Totem", p -> nm(p).contains("totem"), 1, "totem_of_undying"),
            cat("Mace", p -> p.equals("item/mace.png"), 1, "mace"),
            cat("Wind Charge", p -> nm(p).contains("wind_charge"), 1, "wind_charge"),
            cat("Trident", p -> nm(p).startsWith("trident"), 1, "trident", "trident_in_hand"),
            cat("Sky", p -> p.startsWith("environment/"), 2, "sun", "full_moon", "moon_phases", "clouds"),
            cat("GUI", p -> p.startsWith("gui/"), 2, "hotbar", "inventory", "widgets"));

    /** One entry of the list, with all textures that belong to it and the packs that provide them. */
    static final class CatData {
        final Cat def;
        final Map<String, List<Source>> texes = new TreeMap<>();
        private List<String> packs;
        private final Map<String, Map<String, Source>> faceCache = new HashMap<>();
        private final Map<String, List<Source>> sampleCache = new HashMap<>();
        private final Map<String, List<Source>> ovCache = new HashMap<>();

        CatData(Cat def) { this.def = def; }

        List<String> packs() {
            if (packs == null) {
                LinkedHashSet<String> s = new LinkedHashSet<>();
                for (var l : texes.values()) for (Source x : l) s.add(x.pack());
                packs = new ArrayList<>(s);
                packs.sort(String.CASE_INSENSITIVE_ORDER);
            }
            return packs;
        }

        Map<String, Source> faces(String pack) {
            return faceCache.computeIfAbsent(pack, k -> {
                Map<String, Source> m = new LinkedHashMap<>();
                for (var e : texes.entrySet())
                    for (Source s : e.getValue()) if (s.pack().equals(k)) { m.put(e.getKey(), s); break; }
                return m;
            });
        }

        private static Source find(Map<String, Source> f, String name) {
            Source any = null;
            for (var e : f.entrySet()) {
                if (!nm(e.getKey()).equals(name)) continue;
                if (item(e.getKey()) || e.getKey().startsWith("block/")) return e.getValue();
                if (any == null) any = e.getValue();
            }
            return any;
        }

        private Source first(String pack, List<String> names) {
            Map<String, Source> f = faces(pack);
            for (String n : names) { Source s = find(f, n); if (s != null) return s; }
            return null;
        }

        Source cubeTop(String pack) { return def.top() == null ? null : first(pack, def.top()); }
        Source cubeSide(String pack) { return def.side() == null ? null : first(pack, def.side()); }

        /** A few typical textures of this style, used as its picture. */
        List<Source> samples(String pack, int max) {
            return sampleCache.computeIfAbsent(pack + "|" + max, k -> {
                Map<String, Source> f = faces(pack);
                List<Source> out = new ArrayList<>();
                for (String n : def.samples()) {
                    if (out.size() >= max) break;
                    Source s = find(f, n);
                    if (s != null && !out.contains(s)) out.add(s);
                }
                if (out.isEmpty() && !f.isEmpty()) out.add(f.values().iterator().next());
                return out;
            });
        }

        /** Everything this style contains (for the overview). */
        List<Source> overview(String pack) {
            return ovCache.computeIfAbsent(pack, k -> new ArrayList<>(faces(k).values()));
        }
    }

    // ---------- layout constants ----------
    private static final int L = 12, T = 30, LEFT_W = 150, RIGHT_W = 190, ROW = 24;
    private static final int CELL = 80, GAP = 8, LABEL = 16, N = 16, TH = 26, TG = 4, PREVIEW = 88;
    private static final int BG = 0xFF0C0A14;

    private final Minecraft mc = Minecraft.getInstance();
    private final CompletableFuture<Map<String, List<Source>>> scan = CompletableFuture
            .supplyAsync(() -> PackScanner.scan(MixBuilder.packDir()))
            .exceptionally(e -> new TreeMap<>());
    private Map<String, List<Source>> index;
    private final List<CatData> data = new ArrayList<>();   // only entries that have at least one texture
    private final Map<String, String> selPack = new HashMap<>(); // entry name -> chosen pack
    private final Map<Source, Img> imgs = new HashMap<>();
    private CatData cur;
    private String inspectPack;
    private int loadBudget, mouseX, mouseY;
    private double leftScroll = 0, scroll = 0, ovScroll = 0;
    private boolean dirty, applying, closing, shiftReleased;
    private String status = "";

    public MixScreen() { super(Component.literal("Texturepack Client")); }

    // ---------- data ----------
    private void finishScan() {
        index = scan.join();
        for (Cat def : CATS) {
            CatData cd = new CatData(def);
            for (var e : index.entrySet()) if (def.match().test(e.getKey())) cd.texes.put(e.getKey(), e.getValue());
            if (!cd.texes.isEmpty()) data.add(cd);
        }
        // restore the last applied choice: the pack that supplies most of the saved textures of an entry
        Map<String, String> saved = MixBuilder.loadSelection();
        for (CatData cd : data) {
            Map<String, Integer> votes = new HashMap<>();
            saved.forEach((tex, pack) -> { if (cd.texes.containsKey(tex)) votes.merge(pack, 1, Integer::sum); });
            votes.entrySet().stream().max(Map.Entry.comparingByValue())
                    .ifPresent(b -> { if (cd.packs().contains(b.getKey())) selPack.put(cd.def.name(), b.getKey()); });
        }
        cur = data.isEmpty() ? null : data.get(0);
    }

    private String previewPack(CatData c) {
        String p = selPack.get(c.def.name());
        return p != null ? p : c.packs().get(0);
    }

    // ---------- layout ----------
    private int cx0() { return L + LEFT_W + 10; }
    private int cx1() { return width - L - RIGHT_W - 10; }
    private int bottom() { return height - 40; }
    private int cols() { return Math.max(1, (cx1() - cx0() + GAP) / (CELL + GAP)); }
    private int ovTop() { return T + PREVIEW + 40; }
    private int perRow() { return Math.max(1, (RIGHT_W + TG) / (TH + TG)); }

    private int maxLeft() { return Math.max(0, data.size() * ROW - (bottom() - T)); }

    private int maxCenter() {
        if (cur == null) return 0;
        int rows = (cur.packs().size() + cols() - 1) / cols();
        return Math.max(0, rows * (CELL + LABEL + GAP) - (bottom() - T));
    }

    private int maxOv(int count) {
        int rows = (count + perRow() - 1) / perRow();
        return Math.max(0, rows * (TH + TG) - (bottom() - ovTop()));
    }

    private int rowAt(double mx, double my) {
        if (mx < L || mx > L + LEFT_W || my < T || my > bottom()) return -1;
        int i = (int) ((my - T + leftScroll) / ROW);
        return i >= 0 && i < data.size() ? i : -1;
    }

    private int cellAt(double mx, double my) {
        if (cur == null || mx < cx0() || mx > cx1() || my < T || my > bottom()) return -1;
        int col = (int) ((mx - cx0()) / (CELL + GAP));
        int row = (int) ((my - T + scroll) / (CELL + LABEL + GAP));
        if (col >= cols()) return -1;
        double inX = (mx - cx0()) - col * (CELL + GAP);
        double inY = (my - T + scroll) - row * (CELL + LABEL + GAP);
        if (inX > CELL || inY > CELL + LABEL) return -1;
        int idx = row * cols() + col;
        return idx >= 0 && idx < cur.packs().size() ? idx : -1;
    }

    private int ovAt(double mx, double my, int count) {
        int rx = width - L - RIGHT_W;
        if (mx < rx || mx > rx + RIGHT_W || my < ovTop() || my > bottom()) return -1;
        int col = (int) ((mx - rx) / (TH + TG));
        int row = (int) ((my - ovTop() + ovScroll) / (TH + TG));
        if (col >= perRow()) return -1;
        int idx = row * perRow() + col;
        return idx >= 0 && idx < count ? idx : -1;
    }

    // ---------- helpers ----------
    private String fit(String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        int n = s.length();
        while (n > 1 && font.width(s.substring(0, n) + "..") > maxW) n--;
        return s.substring(0, n) + "..";
    }

    private Img img(Source s) {
        if (s == null) return null;
        Img i = imgs.get(s);
        if (i != null || loadBudget <= 0) return i;
        loadBudget--;
        try (var in = new ByteArrayInputStream(s.read())) {
            NativeImage ni = NativeImage.read(in);
            Identifier id = Identifier.fromNamespaceAndPath("tpc", "p/" + imgs.size());
            mc.getTextureManager().register(id, new DynamicTexture(() -> "tpc", ni));
            i = new Img(id, ni.getWidth(), ni.getHeight());
        } catch (Exception e) {
            i = new Img(null, 0, 0);
        }
        imgs.put(s, i);
        return i;
    }

    private static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int col) {
        g.fill(x, y, x + w, y + 1, col);
        g.fill(x, y + h - 1, x + w, y + h, col);
        g.fill(x, y, x + 1, y + h, col);
        g.fill(x + w - 1, y, x + w, y + h, col);
    }

    private void flatImg(GuiGraphicsExtractor g, Img i, int cx, int cy, int size) {
        if (i == null || i.id() == null) return;
        int rh = Math.min(i.w(), i.h()); // first frame only for animated strips
        g.blit(RenderPipelines.GUI_TEXTURED, i.id(), cx - size / 2, cy - size / 2, 0f, 0f, size, size, i.w(), rh, i.w(), i.h());
    }

    /** Draws a texture as a parallelogram: (ox,oy) = top-left corner, (ux,uy) = right edge, (vx,vy) = down edge. */
    private void face(GuiGraphicsExtractor g, Img img, float ox, float oy, float ux, float uy, float vx, float vy, int shade) {
        if (img == null || img.id() == null) return;
        var pose = g.pose();
        pose.pushMatrix();
        pose.mul(new Matrix3x2f(ux / N, uy / N, vx / N, vy / N, ox, oy));
        int rh = Math.min(img.w(), img.h());
        g.blit(RenderPipelines.GUI_TEXTURED, img.id(), 0, 0, 0f, 0f, N, N, img.w(), rh, img.w(), img.h());
        if (shade != 0) g.fill(0, 0, N, N, shade);
        pose.popMatrix();
    }

    /** Isometric cube centred on (cx, cy); the whole thing is `size` pixels high. */
    private void cube(GuiGraphicsExtractor g, Img top, Img side, float cx, float cy, float size) {
        float e = size / 2f, w = 0.866f * e, t = 0.5f * e;
        face(g, top, cx - w, cy - e / 2, w, -t, w, t, 0);
        face(g, side, cx - w, cy - e / 2, w, t, 0, e, 0x30000000);
        face(g, side, cx, cy, w, -t, 0, e, 0x60000000);
    }

    /** The picture of one style: a 3D cube for blocks, 1 to 4 icons for everything else. */
    private void style(GuiGraphicsExtractor g, CatData c, String pack, int cx, int cy, int size, boolean single) {
        if (c.def.top() != null) {
            Img t = img(c.cubeTop(pack)), s = img(c.cubeSide(pack));
            if (t != null || s != null) { cube(g, t != null ? t : s, s != null ? s : t, cx, cy, size); return; }
        }
        List<Source> sm = c.samples(pack, single ? 1 : c.def.max());
        int n = sm.size();
        if (n <= 1) { if (n == 1) flatImg(g, img(sm.get(0)), cx, cy, size); return; }
        if (n == 2) {
            int s = (int) (size * 0.6f), o = (int) (size * 0.28f);
            flatImg(g, img(sm.get(0)), cx - o, cy, s);
            flatImg(g, img(sm.get(1)), cx + o, cy, s);
            return;
        }
        int s = (int) (size * 0.48f), o = (int) (size * 0.26f);
        for (int i = 0; i < Math.min(4, n); i++)
            flatImg(g, img(sm.get(i)), cx + (i % 2 == 0 ? -o : o), cy + (i < 2 ? -o : o), s);
    }

    // ---------- keys (polled) ----------
    private void pollKeys() {
        long h = mc.getWindow().handle();
        boolean shift = GLFW.glfwGetKey(h, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        if (!shift) shiftReleased = true;
        else if (shiftReleased && !closing) { closing = true; mc.execute(this::onClose); }
    }

    // ---------- render ----------
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        mouseX = mx; mouseY = my; loadBudget = 10;
        g.fill(0, 0, width, height, 0xEE0C0A14);
        pollKeys();
        if (index == null) {
            g.text(font, "TEXTUREPACK CLIENT", L, 10, 0xFFB58CFF, false);
            if (!scan.isDone()) { g.text(font, "Scanning resource packs...", L, T + 4, 0xFFAAAAAA, false); return; }
            finishScan();
        }
        leftScroll = Math.max(0, Math.min(maxLeft(), leftScroll));
        scroll = Math.max(0, Math.min(maxCenter(), scroll));

        // ----- left: the short fixed list -----
        int hoverRow = rowAt(mx, my);
        for (int i = 0; i < data.size(); i++) {
            int y = T + i * ROW - (int) leftScroll;
            if (y + ROW < T || y > bottom()) continue;
            CatData c = data.get(i);
            boolean active = c == cur;
            if (active) g.fill(L, y, L + LEFT_W, y + ROW - 2, 0x883A2A66);
            else if (i == hoverRow) g.fill(L, y, L + LEFT_W, y + ROW - 2, 0x44302850);
            style(g, c, previewPack(c), L + 13, y + 11, 18, true);
            g.text(font, c.def.name(), L + 28, y + 7, active ? 0xFFFFFFFF : 0xFFBBBBBB, false);
            String n = String.valueOf(c.packs().size());
            g.text(font, n, L + LEFT_W - 6 - font.width(n), y + 7, selPack.containsKey(c.def.name()) ? 0xFF55FF55 : 0xFFB58CFF, false);
        }

        // ----- middle: the styles for the chosen entry -----
        int hoverCell = cellAt(mx, my);
        if (cur != null) {
            int cols = cols();
            List<String> packs = cur.packs();
            for (int idx = 0; idx < packs.size(); idx++) {
                int x = cx0() + (idx % cols) * (CELL + GAP);
                int yy = T + (idx / cols) * (CELL + LABEL + GAP) - (int) scroll;
                if (yy + CELL + LABEL < T || yy > bottom()) continue;
                String pack = packs.get(idx);
                boolean active = pack.equals(selPack.get(cur.def.name()));
                g.fill(x, yy, x + CELL, yy + CELL, 0xFF1A1730);
                style(g, cur, pack, x + CELL / 2, yy + CELL / 2, CELL - 14, false);
                border(g, x, yy, CELL, CELL, active ? 0xFF55FF55 : (idx == hoverCell ? 0xFFB58CFF : 0xFF2E2950));
                String lab = pack.endsWith(".zip") ? pack.substring(0, pack.length() - 4) : pack;
                g.text(font, fit(lab, CELL), x, yy + CELL + 3, active ? 0xFF55FF55 : 0xFFDDDDDD, false);
            }
        }

        // ----- right: overview thumbnails first (the preview block below hides anything scrolled above it) -----
        int rx = width - L - RIGHT_W;
        String shown = null;
        List<Source> ov = List.of();
        if (cur != null) {
            shown = hoverCell >= 0 ? cur.packs().get(hoverCell) : (inspectPack != null ? inspectPack : previewPack(cur));
            ov = cur.overview(shown);
            ovScroll = Math.max(0, Math.min(maxOv(ov.size()), ovScroll));
            int per = perRow();
            for (int i = 0; i < ov.size(); i++) {
                int x = rx + (i % per) * (TH + TG);
                int y = ovTop() + (i / per) * (TH + TG) - (int) ovScroll;
                if (y + TH < ovTop() - 2 || y > bottom()) continue;
                g.fill(x, y, x + TH, y + TH, 0xFF1A1730);
                flatImg(g, img(ov.get(i)), x + TH / 2, y + TH / 2, TH - 4);
            }
        }
        int hoverOv = ovAt(mx, my, ov.size());

        g.fill(rx, T, rx + RIGHT_W, ovTop(), BG);
        g.fill(rx, T, rx + RIGHT_W, T + PREVIEW, 0xFF1A1730);
        if (cur != null && shown != null) {
            style(g, cur, shown, rx + RIGHT_W / 2, T + PREVIEW / 2, PREVIEW - 16, false);
            boolean act = shown.equals(selPack.get(cur.def.name()));
            String lab = shown.endsWith(".zip") ? shown.substring(0, shown.length() - 4) : shown;
            g.text(font, fit(cur.def.name() + " - " + lab, RIGHT_W), rx, T + PREVIEW + 6, 0xFFFFFFFF, false);
            g.text(font, act ? "SELECTED" : "not selected", rx, T + PREVIEW + 18, act ? 0xFF55FF55 : 0xFF777777, false);
            String ovLabel = hoverOv >= 0 ? ov.get(hoverOv).tex().replace(".png", "") : "OVERVIEW (" + ov.size() + ")";
            g.text(font, fit(ovLabel, RIGHT_W), rx, ovTop() - 12, hoverOv >= 0 ? 0xFFFFFFFF : 0xFFB58CFF, false);
        }

        // ----- hide what scrolled out at the top/bottom, then header and footer -----
        g.fill(0, 0, width, T, BG);
        g.fill(0, bottom(), width, height, BG);
        g.text(font, "TEXTUREPACK CLIENT", L, 10, 0xFFB58CFF, false);
        if (cur != null)
            g.text(font, cur.def.name() + "  -  " + cur.packs().size() + (cur.packs().size() == 1 ? " style" : " styles"),
                    cx0(), 10, 0xFFFFFFFF, false);
        else
            g.text(font, "No matching textures found. Put packs in .minecraft/resourcepacks", cx0(), 10, 0xFF777777, false);

        int by = height - 30;
        button(g, L, by, 80, applying ? "..." : "APPLY", mx, my, dirty ? 0xFF3C8D3C : 0xFF2E2950);
        button(g, L + 88, by, 80, "RESET", mx, my, 0xFF2E2950);
        g.text(font, status.isEmpty() ? "CLICK A STYLE, THEN APPLY / RIGHT SHIFT CLOSES" : status, L + 180, by + 5, 0xFF999999, false);
        String cnt = selPack.size() + " selected";
        g.text(font, cnt, width - L - font.width(cnt), by + 5, 0xFF999999, false);
    }

    private void button(GuiGraphicsExtractor g, int x, int y, int w, String label, int mx, int my, int color) {
        boolean h = in(mx, my, x, y, w, 18);
        g.fill(x, y, x + w, y + 18, h ? color + 0x00202020 : color);
        g.text(font, label, x + (w - font.width(label)) / 2, y + 5, 0xFFFFFFFF, false);
    }

    private boolean in(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx <= x + w && my >= y && my <= y + h; }

    // ---------- input ----------
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return onClick(mouseX, mouseY, event.button()) || super.mouseClicked(event, doubleClick);
    }

    private boolean onClick(double mx, double my, int button) {
        if (index == null) return false;
        int by = height - 30;
        if (button == 0 && in(mx, my, L, by, 80, 18)) { apply(); return true; }
        if (button == 0 && in(mx, my, L + 88, by, 80, 18)) {
            selPack.clear(); dirty = true; status = "Cleared - press APPLY.";
            return true;
        }
        int row = rowAt(mx, my);
        if (row >= 0) { cur = data.get(row); inspectPack = null; scroll = 0; ovScroll = 0; return true; }
        int cell = cellAt(mx, my);
        if (cell >= 0 && cur != null) {
            String pack = cur.packs().get(cell);
            inspectPack = pack;
            ovScroll = 0;
            if (button == 0) {
                if (pack.equals(selPack.get(cur.def.name()))) selPack.remove(cur.def.name()); else selPack.put(cur.def.name(), pack);
                dirty = true;
                status = "Pending changes - press APPLY.";
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x < cx0() - 5) leftScroll = Math.max(0, Math.min(maxLeft(), leftScroll - vertical * ROW * 2));
        else if (x > cx1() + 5) ovScroll = Math.max(0, ovScroll - vertical * (TH + TG) * 2); // upper limit is clamped while drawing
        else scroll = Math.max(0, Math.min(maxCenter(), scroll - vertical * 30));
        return true;
    }

    private void apply() {
        if (applying) return;
        applying = true;
        status = "Applying...";
        Map<String, Source> snap = new HashMap<>();
        for (var e : selPack.entrySet())
            for (CatData cd : data)
                if (cd.def.name().equals(e.getKey())) snap.putAll(cd.faces(e.getValue()));
        CompletableFuture.supplyAsync(() -> {
            try { return MixBuilder.write(snap); } catch (Exception e) { throw new RuntimeException(e); }
        }).whenComplete((failed, err) -> mc.execute(() -> {
            applying = false;
            if (err != null) { status = "Error: " + err.getMessage(); return; }
            try {
                MixBuilder.enable();
                dirty = false;
                status = "Applied " + (snap.size() - failed) + " textures" + (failed > 0 ? " (" + failed + " failed)" : "") + ".";
            } catch (Exception e) {
                status = "Error: " + e.getMessage();
            }
        }));
    }

    @Override
    public void removed() {
        for (Img i : imgs.values()) if (i.id() != null) mc.getTextureManager().release(i.id());
        imgs.clear();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}

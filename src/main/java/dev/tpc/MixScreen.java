package dev.tpc;

import com.mojang.blaze3d.platform.NativeImage;
import dev.tpc.PackScanner.Source;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
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

/**
 * Minecraft 26.x menu: left = every block/item you have a texture for, centre = the styles (packs) for it,
 * blocks are drawn as small 3D cubes. Tabs on top: PVP, CPVP, BLOCKS, ITEMS, OTHER, ALL.
 */
public class MixScreen extends Screen {
    record Img(Identifier id, int w, int h) {}

    // ---------- one block / item and all the packs that have a texture for it ----------
    static final String[][] ROLES = {
            {"_top", "_end", "", "_side", "_front"},   // 0 top face
            {"_front", "_side", "", "_top"},           // 1 left face
            {"_side", "", "_front", "_top"}};          // 2 right face

    static final class Entry {
        final String key, label;
        final int kind; // 0 = block, 1 = item, 2 = other
        final Map<String, List<Source>> texes = new TreeMap<>();
        private List<String> packs;

        Entry(String key, int kind) {
            this.key = key;
            this.kind = kind;
            this.label = key.substring(key.lastIndexOf('/') + 1);
        }

        List<String> packs() {
            if (packs == null) {
                LinkedHashSet<String> s = new LinkedHashSet<>();
                for (var l : texes.values()) for (Source x : l) s.add(x.pack());
                packs = new ArrayList<>(s);
                packs.sort(String.CASE_INSENSITIVE_ORDER);
            }
            return packs;
        }

        /** All textures of this entry that the given pack provides. */
        Map<String, Source> faces(String pack) {
            Map<String, Source> m = new LinkedHashMap<>();
            for (var e : texes.entrySet())
                for (Source s : e.getValue()) if (s.pack().equals(pack)) { m.put(e.getKey(), s); break; }
            return m;
        }

        Source role(String pack, int r) {
            if (kind != 0) return null;
            Map<String, Source> f = faces(pack);
            String dir = key.substring(0, key.lastIndexOf('/') + 1);
            for (String suf : ROLES[r]) {
                Source s = f.get(dir + label + suf + ".png");
                if (s != null) return s;
            }
            return null;
        }

        Source flat(String pack) {
            Source s = role(pack, 2);
            if (s == null) s = role(pack, 0);
            if (s != null) return s;
            Map<String, Source> f = faces(pack);
            return f.isEmpty() ? null : f.values().iterator().next();
        }
    }

    // ---------- tabs ----------
    private static final String[] TABS = {"PVP", "CPVP", "BLOCKS", "ITEMS", "OTHER", "ALL"};
    private static final String[] PVP = {"sword", "_axe", "crossbow", "shield", "totem", "golden_apple", "potion",
            "helmet", "chestplate", "leggings", "boots", "mace", "trident", "arrow", "wind_charge", "ender_pearl",
            "experience_bottle"};
    private static final String[] CPVP = {"crystal", "obsidian", "anchor", "glowstone", "ender_pearl", "totem",
            "golden_apple", "experience_bottle", "netherite", "ender_chest", "fire", "cobweb"};

    private static boolean has(String name, String[] words) {
        for (String w : words) if (name.contains(w)) return true;
        return false;
    }

    private static boolean inTab(Entry e, int tab) {
        return switch (tab) {
            case 0 -> has(e.label, PVP) || e.label.equals("bow") || e.label.startsWith("bow_");
            case 1 -> has(e.label, CPVP);
            case 2 -> e.kind == 0;
            case 3 -> e.kind == 1;
            case 4 -> e.kind == 2;
            default -> true;
        };
    }

    // ---------- layout constants ----------
    private static final int L = 12, T = 62, LEFT_W = 180, RIGHT_W = 130, ROW = 22;
    private static final int CELL = 72, GAP = 8, LABEL = 16, N = 16;
    private static final int BG = 0xFF0C0A14;

    private final Minecraft mc = Minecraft.getInstance();
    private final CompletableFuture<Map<String, List<Source>>> scan = CompletableFuture
            .supplyAsync(() -> PackScanner.scan(MixBuilder.packDir()))
            .exceptionally(e -> new TreeMap<>());
    private Map<String, List<Source>> index;
    private final Map<String, Entry> entries = new TreeMap<>();
    private final Map<String, Entry> texToEntry = new HashMap<>();
    private final Map<String, String> selPack = new HashMap<>(); // entry key -> chosen pack
    private final Map<Source, Img> imgs = new HashMap<>();
    private List<Entry> listed = List.of();
    private Entry cur;
    private String inspectPack;
    private int tab = 0, loadBudget, mouseX, mouseY;
    private double leftScroll = 0, scroll = 0;
    private boolean flat, dirty, applying, closing, shiftReleased, bsWasDown;
    private String status = "", query = "";
    private long bsSince, bsLast;

    public MixScreen() { super(Component.literal("Texturepack Client")); }

    // ---------- data ----------
    private static String stripFace(String name) {
        for (String s : new String[]{"_top", "_side", "_bottom", "_front", "_end", "_back"})
            if (name.endsWith(s) && name.length() > s.length()) return name.substring(0, name.length() - s.length());
        return name;
    }

    private void finishScan() {
        index = scan.join();
        for (var e : index.entrySet()) {
            String tex = e.getKey();
            String noExt = tex.substring(0, tex.length() - 4);
            int slash = noExt.lastIndexOf('/');
            String dir = noExt.substring(0, slash + 1), name = noExt.substring(slash + 1);
            String key;
            int kind;
            if (dir.equals("block/")) { key = dir + stripFace(name); kind = 0; }
            else if (dir.startsWith("item/")) { key = noExt; kind = 1; }
            else { key = noExt; kind = 2; }
            Entry en = entries.computeIfAbsent(key, k -> new Entry(k, kind));
            en.texes.put(tex, e.getValue());
            texToEntry.put(tex, en);
        }
        MixBuilder.loadSelection().forEach((tex, pack) -> {
            Entry en = texToEntry.get(tex);
            if (en != null && en.packs().contains(pack)) selPack.put(en.key, pack);
        });
        rebuildList();
    }

    private void rebuildList() {
        String q = query.toLowerCase(Locale.ROOT);
        List<Entry> l = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (!inTab(e, tab)) continue;
            if (!q.isEmpty() && !e.label.toLowerCase(Locale.ROOT).contains(q)) continue;
            l.add(e);
        }
        listed = l;
        leftScroll = 0;
        scroll = 0;
        if (cur == null || !l.contains(cur)) { cur = l.isEmpty() ? null : l.get(0); inspectPack = null; }
    }

    // ---------- layout ----------
    private int cx0() { return L + LEFT_W + 10; }
    private int cx1() { return width - L - RIGHT_W - 10; }
    private int bottom() { return height - 40; }
    private int cols() { return Math.max(1, (cx1() - cx0() + GAP) / (CELL + GAP)); }

    private int maxLeft() { return Math.max(0, listed.size() * ROW - (bottom() - T)); }

    private int maxCenter() {
        if (cur == null) return 0;
        int rows = (cur.packs().size() + cols() - 1) / cols();
        return Math.max(0, rows * (CELL + LABEL + GAP) - (bottom() - T));
    }

    /** Index of the style cell under the mouse, or -1. */
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

    private int rowAt(double mx, double my) {
        if (mx < L || mx > L + LEFT_W || my < T || my > bottom()) return -1;
        int i = (int) ((my - T + leftScroll) / ROW);
        return i >= 0 && i < listed.size() ? i : -1;
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

    /** Draws a texture as a parallelogram: (ox,oy) = top-left corner, (ux,uy) = right edge, (vx,vy) = down edge. */
    private void face(GuiGraphicsExtractor g, Img img, float ox, float oy, float ux, float uy, float vx, float vy, int shade) {
        if (img == null || img.id() == null) return;
        var pose = g.pose();
        pose.pushMatrix();
        pose.mul(new Matrix3x2f(ux / N, uy / N, vx / N, vy / N, ox, oy));
        int rh = Math.min(img.w(), img.h()); // first frame only for animated strips
        g.blit(RenderPipelines.GUI_TEXTURED, img.id(), 0, 0, 0f, 0f, N, N, img.w(), rh, img.w(), img.h());
        if (shade != 0) g.fill(0, 0, N, N, shade);
        pose.popMatrix();
    }

    /** Isometric cube centred on (cx, cy); the whole thing is `size` pixels high. */
    private void cube(GuiGraphicsExtractor g, Img top, Img left, Img right, float cx, float cy, float size) {
        float e = size / 2f, w = 0.866f * e, t = 0.5f * e;
        face(g, top, cx - w, cy - e / 2, w, -t, w, t, 0);              // top
        face(g, left, cx - w, cy - e / 2, w, t, 0, e, 0x30000000);     // left (a bit darker)
        face(g, right, cx, cy, w, -t, 0, e, 0x60000000);               // right (darkest)
    }

    /** Draws one style (= one pack) of an entry, as a cube for blocks or flat for everything else. */
    private void variant(GuiGraphicsExtractor g, Entry e, String pack, int cx, int cy, int size) {
        if (e.kind == 0 && !flat) {
            Img t = img(e.role(pack, 0)), l = img(e.role(pack, 1)), r = img(e.role(pack, 2));
            if (t == null && l == null && r == null) { flatImg(g, img(e.flat(pack)), cx, cy, size); return; }
            cube(g, t != null ? t : r, l != null ? l : r, r != null ? r : l, cx, cy, size);
        } else {
            flatImg(g, img(e.flat(pack)), cx, cy, size);
        }
    }

    private void flatImg(GuiGraphicsExtractor g, Img i, int cx, int cy, int size) {
        if (i == null || i.id() == null) return;
        int rh = Math.min(i.w(), i.h());
        g.blit(RenderPipelines.GUI_TEXTURED, i.id(), cx - size / 2, cy - size / 2, 0f, 0f, size, size, i.w(), rh, i.w(), i.h());
    }

    private String previewPack(Entry e) {
        String p = selPack.get(e.key);
        return p != null ? p : e.packs().get(0);
    }

    // ---------- keys (polled) ----------
    private void pollKeys() {
        long h = mc.getWindow().handle();
        boolean shift = GLFW.glfwGetKey(h, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        if (!shift) shiftReleased = true;
        else if (shiftReleased && !closing) { closing = true; mc.execute(this::onClose); }

        boolean bs = GLFW.glfwGetKey(h, GLFW.GLFW_KEY_BACKSPACE) == GLFW.GLFW_PRESS;
        long now = System.currentTimeMillis();
        if (bs && index != null) {
            if (!bsWasDown) { bsSince = now; bsLast = now; backspace(); }
            else if (now - bsSince > 400 && now - bsLast > 45) { bsLast = now; backspace(); }
        }
        bsWasDown = bs;
    }

    private void backspace() {
        if (!query.isEmpty()) { query = query.substring(0, query.length() - 1); rebuildList(); }
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        String s = event.codepointAsString();
        if (index != null && !s.isEmpty() && !Character.isISOControl(s.charAt(0)) && query.length() < 32) {
            query += s;
            rebuildList();
        }
        return true;
    }

    // ---------- render ----------
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        mouseX = mx; mouseY = my; loadBudget = 8;
        g.fill(0, 0, width, height, 0xEE0C0A14);
        pollKeys();
        if (index == null) {
            g.text(font, "TEXTUREPACK CLIENT", L, 8, 0xFFB58CFF, false);
            if (!scan.isDone()) { g.text(font, "Scanning resource packs...", L, T, 0xFFAAAAAA, false); return; }
            finishScan();
        }
        leftScroll = Math.max(0, Math.min(maxLeft(), leftScroll));
        scroll = Math.max(0, Math.min(maxCenter(), scroll));

        // ----- left list: every block/item you have a texture for -----
        int hoverRow = rowAt(mx, my);
        for (int i = 0; i < listed.size(); i++) {
            int y = T + i * ROW - (int) leftScroll;
            if (y + ROW < T || y > bottom()) continue;
            Entry e = listed.get(i);
            boolean active = e == cur;
            if (active) g.fill(L, y, L + LEFT_W, y + ROW - 2, 0x883A2A66);
            else if (i == hoverRow) g.fill(L, y, L + LEFT_W, y + ROW - 2, 0x44302850);
            variant(g, e, previewPack(e), L + 12, y + 10, 18);
            g.text(font, fit(e.label, LEFT_W - 52), L + 26, y + 6, active ? 0xFFFFFFFF : 0xFFBBBBBB, false);
            String n = String.valueOf(e.packs().size());
            g.text(font, n, L + LEFT_W - 6 - font.width(n), y + 6, selPack.containsKey(e.key) ? 0xFF55FF55 : 0xFFB58CFF, false);
        }

        // ----- centre: the styles (packs) for the chosen entry -----
        int hoverCell = cellAt(mx, my);
        if (cur != null) {
            int cols = cols();
            List<String> packs = cur.packs();
            for (int idx = 0; idx < packs.size(); idx++) {
                int x = cx0() + (idx % cols) * (CELL + GAP);
                int yy = T + (idx / cols) * (CELL + LABEL + GAP) - (int) scroll;
                if (yy + CELL + LABEL < T || yy > bottom()) continue;
                String pack = packs.get(idx);
                boolean active = pack.equals(selPack.get(cur.key));
                g.fill(x, yy, x + CELL, yy + CELL, 0xFF1A1730);
                variant(g, cur, pack, x + CELL / 2, yy + CELL / 2, CELL - 12);
                border(g, x, yy, CELL, CELL, active ? 0xFF55FF55 : (idx == hoverCell ? 0xFFB58CFF : 0xFF2E2950));
                String lab = pack.endsWith(".zip") ? pack.substring(0, pack.length() - 4) : pack;
                g.text(font, fit(lab, CELL), x, yy + CELL + 3, active ? 0xFF55FF55 : 0xFFDDDDDD, false);
            }
        }

        // ----- right: inspector -----
        int rx = width - L - RIGHT_W;
        g.fill(rx, T, rx + RIGHT_W, T + RIGHT_W, 0xFF1A1730);
        if (cur != null) {
            String shown = hoverCell >= 0 ? cur.packs().get(hoverCell)
                    : (inspectPack != null ? inspectPack : previewPack(cur));
            variant(g, cur, shown, rx + RIGHT_W / 2, T + RIGHT_W / 2, RIGHT_W - 24);
            boolean act = shown.equals(selPack.get(cur.key));
            String lab = shown.endsWith(".zip") ? shown.substring(0, shown.length() - 4) : shown;
            g.text(font, fit(cur.label, RIGHT_W), rx, T + RIGHT_W + 6, 0xFFFFFFFF, false);
            g.text(font, fit(lab, RIGHT_W), rx, T + RIGHT_W + 18, 0xFFB58CFF, false);
            g.text(font, act ? "SELECTED" : "not selected", rx, T + RIGHT_W + 30, act ? 0xFF55FF55 : 0xFF777777, false);
        }

        // ----- cover anything that scrolled out of the list areas, then draw header and footer on top -----
        g.fill(0, 0, width, T, BG);
        g.fill(0, bottom(), width, height, BG);

        g.text(font, "TEXTUREPACK CLIENT", L, 6, 0xFFB58CFF, false);
        int tx = L;
        for (int i = 0; i < TABS.length; i++) {
            int w = font.width(TABS[i]) + 14;
            boolean a = i == tab;
            g.fill(tx, 20, tx + w, 36, a ? 0xFF4A3A8A : (in(mx, my, tx, 20, w, 16) ? 0xFF2E2950 : 0xFF1A1730));
            g.text(font, TABS[i], tx + 7, 24, a ? 0xFFFFFFFF : 0xFFAAAAAA, false);
            tx += w + 4;
        }
        String mode = flat ? "FLAT" : "3D";
        g.fill(width - L - 50, 20, width - L, 36, in(mx, my, width - L - 50, 20, 50, 16) ? 0xFF4A3A8A : 0xFF2E2950);
        g.text(font, mode, width - L - 50 + (50 - font.width(mode)) / 2, 24, 0xFFFFFFFF, false);

        // search box (filters the left list)
        g.fill(L, 40, L + LEFT_W, 56, 0xFF1A1730);
        border(g, L, 40, LEFT_W, 16, 0xFF2E2950);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        g.text(font, query.isEmpty() ? "Search block / item..." : query + (blink ? "_" : ""), L + 5, 44,
                query.isEmpty() ? 0xFF666666 : 0xFFFFFFFF, false);

        // centre title
        if (cur != null)
            g.text(font, cur.label + "  -  " + cur.packs().size() + (cur.packs().size() == 1 ? " style" : " styles"),
                    cx0(), 44, 0xFFFFFFFF, false);
        else
            g.text(font, "No textures found for this tab. Put packs in .minecraft/resourcepacks", cx0(), 44, 0xFF777777, false);

        // bottom bar
        int by = height - 30;
        button(g, L, by, 80, applying ? "..." : "APPLY", mx, my, dirty ? 0xFF3C8D3C : 0xFF2E2950);
        button(g, L + 88, by, 80, "RESET", mx, my, 0xFF2E2950);
        g.text(font, status.isEmpty() ? "CLICK A STYLE TO SELECT / APPLY TO USE / RIGHT SHIFT CLOSE" : status,
                L + 180, by + 5, 0xFF999999, false);
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
        if (in(mx, my, width - L - 50, 20, 50, 16)) { flat = !flat; return true; }
        int tx = L;
        for (int i = 0; i < TABS.length; i++) {
            int w = font.width(TABS[i]) + 14;
            if (in(mx, my, tx, 20, w, 16)) { tab = i; rebuildList(); return true; }
            tx += w + 4;
        }
        int row = rowAt(mx, my);
        if (row >= 0) { cur = listed.get(row); inspectPack = null; scroll = 0; return true; }
        int cell = cellAt(mx, my);
        if (cell >= 0 && cur != null) {
            String pack = cur.packs().get(cell);
            inspectPack = pack;
            if (button == 0) {
                if (pack.equals(selPack.get(cur.key))) selPack.remove(cur.key); else selPack.put(cur.key, pack);
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
        else scroll = Math.max(0, Math.min(maxCenter(), scroll - vertical * 30));
        return true;
    }

    private void apply() {
        if (applying) return;
        applying = true;
        status = "Applying...";
        Map<String, Source> snap = new HashMap<>();
        for (var e : selPack.entrySet()) {
            Entry en = entries.get(e.getKey());
            if (en != null) snap.putAll(en.faces(e.getValue()));
        }
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

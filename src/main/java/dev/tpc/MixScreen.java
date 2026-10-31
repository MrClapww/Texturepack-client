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
 * The end crystal and block entries are built as real spinning 3D models from the pack textures.
 */
public class MixScreen extends Screen {
    record Img(Identifier id, int w, int h) {}

    // ---------- the fixed list ----------
    record Cat(String name, Predicate<String> match, int max, List<String> samples, List<String> top, List<String> side, List<String> single) {}

    private static String nm(String p) { return p.substring(p.lastIndexOf('/') + 1, p.length() - 4); }
    private static boolean item(String p) { return p.startsWith("item/"); }

    private static Cat cat(String name, Predicate<String> m, int max, String... samples) {
        return new Cat(name, m, max, List.of(samples), null, null, null);
    }
    private static Cat cube(String name, Predicate<String> m, List<String> top, List<String> side) {
        return new Cat(name, m, 1, top, top, side, null);
    }
    private static Cat withSingle(Cat c, String... single) {
        return new Cat(c.name(), c.match(), c.max(), c.samples(), c.top(), c.side(), List.of(single));
    }

    static final List<Cat> CATS = List.of(
            withSingle(cat("Armor", p -> {
                String n = nm(p);
                return p.startsWith("models/armor/") || p.startsWith("entity/equipment/humanoid")
                        || (item(p) && (n.contains("helmet") || n.contains("chestplate") || n.contains("leggings") || n.contains("boots")));
            }, 4, "netherite_helmet", "netherite_chestplate", "netherite_leggings", "netherite_boots",
                    "diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots",
                    "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots"),
                    "netherite_chestplate", "diamond_chestplate", "iron_chestplate", "golden_chestplate", "chainmail_chestplate", "leather_chestplate"),
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

        Source exact(String pack, String path) { return faces(pack).get(path); }

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
                List<String> names = (max == 1 && def.single() != null) ? def.single() : def.samples();
                List<Source> out = new ArrayList<>();
                for (String n : names) {
                    if (out.size() >= max) break;
                    Source s = find(f, n);
                    if (s != null && !out.contains(s)) out.add(s);
                }
                if (out.isEmpty() && max == 1 && def.single() != null) {
                    for (String n : def.samples()) { Source s = find(f, n); if (s != null) { out.add(s); break; } }
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

    // ---------- look ----------
    private static final int C_BG = 0xFF0A0C0F, C_PANEL = 0xFF12161B, C_PANEL2 = 0xFF1A2027, C_LINE = 0xFF2B343E,
            C_ACCENT = 0xFF21D4FD, C_TEXT = 0xFFECEFF4, C_DIM = 0xFF8A94A0, C_OK = 0xFF3DDC84;

    // ---------- layout constants ----------
    private static final int L = 14, T = 36, LEFT_W = 150, RIGHT_W = 190, ROW = 24;
    private static final int CELL = 84, GAP = 8, LABEL = 16, N = 16, TH = 26, TG = 4, PREVIEW = 96;

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
    private int cx0() { return L + LEFT_W + 14; }
    private int cx1() { return width - L - RIGHT_W - 14; }
    private int bottom() { return height - 40; }
    private int cols() { return Math.max(1, (cx1() - cx0() + GAP) / (CELL + GAP)); }
    private int ovTop() { return T + 6 + PREVIEW + 40; }
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

    // ---------- small drawing helpers ----------
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

    /** Only the four corners of a rectangle (the "tech" frame). */
    private static void brackets(GuiGraphicsExtractor g, int x, int y, int w, int h, int len, int col) {
        g.fill(x, y, x + len, y + 1, col);         g.fill(x, y, x + 1, y + len, col);
        g.fill(x + w - len, y, x + w, y + 1, col); g.fill(x + w - 1, y, x + w, y + len, col);
        g.fill(x, y + h - 1, x + len, y + h, col); g.fill(x, y + h - len, x + 1, y + h, col);
        g.fill(x + w - len, y + h - 1, x + w, y + h, col); g.fill(x + w - 1, y + h - len, x + w, y + h, col);
    }

    private void flatImg(GuiGraphicsExtractor g, Img i, int cx, int cy, int size) {
        if (i == null || i.id() == null) return;
        int rh = Math.min(i.w(), i.h()); // first frame only for animated strips
        g.blit(RenderPipelines.GUI_TEXTURED, i.id(), cx - size / 2, cy - size / 2, 0f, 0f, size, size, i.w(), rh, i.w(), i.h());
    }

    // ---------- small 3D engine: boxes made of textured quads, spinning, painter's algorithm ----------
    /** One textured face: origin o, edge vectors u (texture right) and v (texture down), normal (nx,nz). */
    record Face(Img img, double ox, double oy, double oz, double ux, double uy, double uz, double vx, double vy, double vz,
                double nx, double nz, float[] reg, double theta, double bob, boolean top) {}

    private static final double TILT = Math.toRadians(25), CT = Math.cos(TILT), ST = Math.sin(TILT), YAW = 0.6;

    private static double[] proj(double x, double y, double z, double c, double s) {
        double x1 = x * c + z * s, z1 = -x * s + z * c;
        return new double[]{x1, -y * CT + z1 * ST, z1 * CT + y * ST};
    }

    /** Box with Minecraft's entity texture layout (texOffs tu,tv; uv size uw x uh x ud) and its own geometry size. */
    private static void box(List<Face> out, Img img, double cx, double cy, double cz, double gw, double gh, double gd,
                            double tu, double tv, double uw, double uh, double ud, double s, double theta, double bob) {
        double hw = gw / 2, hh = gh / 2, hd = gd / 2;
        float[][] r = {
                {(float) ((tu + ud) * s), (float) (tv * s), (float) (uw * s), (float) (ud * s)},
                {(float) ((tu + ud) * s), (float) ((tv + ud) * s), (float) (uw * s), (float) (uh * s)},
                {(float) ((tu + ud + uw) * s), (float) ((tv + ud) * s), (float) (ud * s), (float) (uh * s)},
                {(float) ((tu + ud + uw + ud) * s), (float) ((tv + ud) * s), (float) (uw * s), (float) (uh * s)},
                {(float) (tu * s), (float) ((tv + ud) * s), (float) (ud * s), (float) (uh * s)}};
        addFaces(out, img, img, cx, cy, cz, hw, hh, hd, r, r, theta, bob);
    }

    private static void addFaces(List<Face> out, Img topImg, Img sideImg, double cx, double cy, double cz,
                                 double hw, double hh, double hd, float[][] rTop, float[][] rSide, double theta, double bob) {
        out.add(new Face(topImg, cx - hw, cy + hh, cz - hd, 2 * hw, 0, 0, 0, 0, 2 * hd, 0, 0, rTop[0], theta, bob, true));
        out.add(new Face(sideImg, cx - hw, cy + hh, cz + hd, 2 * hw, 0, 0, 0, -2 * hh, 0, 0, 1, rSide[1], theta, bob, false));
        out.add(new Face(sideImg, cx + hw, cy + hh, cz + hd, 0, 0, -2 * hd, 0, -2 * hh, 0, 1, 0, rSide[2], theta, bob, false));
        out.add(new Face(sideImg, cx + hw, cy + hh, cz - hd, -2 * hw, 0, 0, 0, -2 * hh, 0, 0, -1, rSide[3], theta, bob, false));
        out.add(new Face(sideImg, cx - hw, cy + hh, cz - hd, 0, 0, 2 * hd, 0, -2 * hh, 0, -1, 0, rSide[4], theta, bob, false));
    }

    private static float[] whole(Img i) { return new float[]{0, 0, i.w(), Math.min(i.w(), i.h())}; }

    /** Draws all visible faces back to front. (cx,cy) = screen centre, k = pixels per model unit. */
    private void model(GuiGraphicsExtractor g, List<Face> faces, float cx, float cy, float k, double midY) {
        record Pr(Face f, double ux, double uy, double vx, double vy, double ox, double oy, double depth, int shade) {}
        double sy0 = -midY * CT;
        List<Pr> list = new ArrayList<>();
        for (Face f : faces) {
            if (f.img() == null || f.img().id() == null) continue;
            double c = Math.cos(f.theta() + YAW), s = Math.sin(f.theta() + YAW);
            double[] p0 = proj(f.ox(), f.oy() + f.bob(), f.oz(), c, s);
            double[] p1 = proj(f.ox() + f.ux(), f.oy() + f.uy() + f.bob(), f.oz() + f.uz(), c, s);
            double[] p2 = proj(f.ox() + f.vx(), f.oy() + f.vy() + f.bob(), f.oz() + f.vz(), c, s);
            double ux = p1[0] - p0[0], uy = p1[1] - p0[1], vx = p2[0] - p0[0], vy = p2[1] - p0[1];
            if (ux * vy - uy * vx <= 0.0001) continue; // faces away from the camera
            double[] pc = proj(f.ox() + (f.ux() + f.vx()) / 2, f.oy() + (f.uy() + f.vy()) / 2 + f.bob(), f.oz() + (f.uz() + f.vz()) / 2, c, s);
            int shade = 0;
            if (!f.top()) {
                double nxr = f.nx() * c + f.nz() * s, nzr = -f.nx() * s + f.nz() * c;
                double light = Math.max(0, Math.min(1, ((-0.45 * nxr + 0.85 * nzr) + 1) / 2));
                shade = ((int) ((1 - light) * 0x70)) << 24;
            }
            list.add(new Pr(f, ux, uy, vx, vy, p0[0], p0[1], pc[2], shade));
        }
        list.sort(Comparator.comparingDouble(Pr::depth));
        var pose = g.pose();
        for (Pr p : list) {
            Img im = p.f().img();
            float[] r = p.f().reg();
            pose.pushMatrix();
            pose.mul(new Matrix3x2f((float) (p.ux() * k / N), (float) (p.uy() * k / N), (float) (p.vx() * k / N), (float) (p.vy() * k / N),
                    (float) (cx + p.ox() * k), (float) (cy + (p.oy() - sy0) * k)));
            g.blit(RenderPipelines.GUI_TEXTURED, im.id(), 0, 0, r[0], r[1], N, N,
                    Math.max(1, Math.round(r[2])), Math.max(1, Math.round(r[3])), im.w(), im.h());
            if (p.shade() != 0) g.fill(0, 0, N, N, p.shade());
            pose.popMatrix();
        }
    }

    private static double spin(boolean animate) {
        return animate ? (System.currentTimeMillis() % 1_000_000L) / 1000.0 * 0.9 : 0.5;
    }

    /** A cube made of one "top" and one "side" texture. */
    private void cubeModel(GuiGraphicsExtractor g, Img top, Img side, int cx, int cy, int size, boolean animate) {
        List<Face> f = new ArrayList<>();
        addFaces(f, top, side, 0, 0, 0, 8, 8, 8, new float[][]{whole(top), whole(top), whole(top), whole(top), whole(top)},
                new float[][]{whole(side), whole(side), whole(side), whole(side), whole(side)}, spin(animate), 0);
        model(g, f, cx, cy, size / 24f, 0);
    }

    /** The end crystal as in the game: bedrock base, three nested spinning cubes, bobbing up and down. */
    private void crystalModel(GuiGraphicsExtractor g, Img img, int cx, int cy, int size, boolean animate) {
        double s = img.w() / 64.0, sp = spin(animate);
        double bob = animate ? Math.sin(System.currentTimeMillis() % 1_000_000L / 1000.0 * 2.0) * 0.9 : 0;
        List<Face> f = new ArrayList<>();
        box(f, img, 0, -9, 0, 12, 4, 12, 0, 16, 12, 4, 12, s, 0, 0);                  // base
        box(f, img, 0, 0, 0, 6.1, 6.1, 6.1, 32, 0, 8, 8, 8, s, sp + 1.6, bob);        // core
        box(f, img, 0, 0, 0, 7, 7, 7, 0, 0, 8, 8, 8, s, sp + 0.8, bob);               // inner glass
        box(f, img, 0, 0, 0, 8, 8, 8, 0, 0, 8, 8, 8, s, sp, bob);                     // outer glass
        model(g, f, cx, cy, size / 22f, -3.5);
    }

    /** The picture of one style: 3D for the crystal and the blocks, 1 to 4 icons for everything else. */
    private void style(GuiGraphicsExtractor g, CatData c, String pack, int cx, int cy, int size, boolean single, boolean animate) {
        if (c.def.name().equals("Crystal")) {
            Source ent = c.exact(pack, "entity/end_crystal/end_crystal.png");
            if (ent != null) {
                Img e = img(ent);
                if (e != null && e.id() != null) crystalModel(g, e, cx, cy, size, animate);
                if (e != null && e.id() != null || e == null) return;
            }
        }
        if (c.def.top() != null) {
            Img t = img(c.cubeTop(pack)), s = img(c.cubeSide(pack));
            if (t != null && t.id() == null) t = null;
            if (s != null && s.id() == null) s = null;
            if (t != null || s != null) { cubeModel(g, t != null ? t : s, s != null ? s : t, cx, cy, size, animate && !single); return; }
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
        g.fill(0, 0, width, height, C_BG);
        pollKeys();
        if (index == null) {
            title(g);
            if (!scan.isDone()) { g.text(font, "Scanning resource packs...", L, T + 8, C_DIM, false); return; }
            finishScan();
        }
        leftScroll = Math.max(0, Math.min(maxLeft(), leftScroll));
        scroll = Math.max(0, Math.min(maxCenter(), scroll));
        int rx = width - L - RIGHT_W;

        // panels
        g.fill(L - 4, T, L + LEFT_W + 4, bottom(), C_PANEL);
        g.fill(cx0() - 6, T, cx1() + 6, bottom(), C_PANEL);
        g.fill(rx - 6, T, rx + RIGHT_W + 6, bottom(), C_PANEL);

        // ----- left: the short fixed list -----
        int hoverRow = rowAt(mx, my);
        for (int i = 0; i < data.size(); i++) {
            int y = T + i * ROW - (int) leftScroll;
            if (y + ROW < T || y > bottom()) continue;
            CatData c = data.get(i);
            boolean active = c == cur;
            if (active || i == hoverRow) g.fill(L, y, L + LEFT_W, y + ROW - 2, active ? C_PANEL2 : 0xFF161B21);
            if (active) g.fill(L, y, L + 2, y + ROW - 2, C_ACCENT);
            style(g, c, previewPack(c), L + 15, y + 11, 18, true, false);
            g.text(font, c.def.name(), L + 30, y + 7, active ? C_TEXT : 0xFFB4BCC6, false);
            String n = String.valueOf(c.packs().size());
            boolean has = selPack.containsKey(c.def.name());
            g.text(font, n, L + LEFT_W - 8 - font.width(n), y + 7, has ? C_OK : C_DIM, false);
            if (has) g.fill(L + LEFT_W - 4, y + 4, L + LEFT_W - 2, y + ROW - 6, C_OK);
        }

        // ----- middle: the styles for the chosen entry -----
        int hoverCell = cellAt(mx, my);
        if (cur != null) {
            int cols = cols();
            List<String> packs = cur.packs();
            for (int idx = 0; idx < packs.size(); idx++) {
                int x = cx0() + (idx % cols) * (CELL + GAP);
                int yy = T + 4 + (idx / cols) * (CELL + LABEL + GAP) - (int) scroll;
                if (yy + CELL + LABEL < T || yy > bottom()) continue;
                String pack = packs.get(idx);
                boolean active = pack.equals(selPack.get(cur.def.name()));
                boolean hov = idx == hoverCell;
                g.fill(x, yy, x + CELL, yy + CELL, active ? 0xFF14261D : C_PANEL2);
                style(g, cur, pack, x + CELL / 2, yy + CELL / 2, CELL - 18, false, hov || active);
                border(g, x, yy, CELL, CELL, C_LINE);
                brackets(g, x, yy, CELL, CELL, 8, active ? C_OK : (hov ? C_ACCENT : 0xFF45525F));
                if (active) g.text(font, "ACTIVE", x + 5, yy + 5, C_OK, false);
                String lab = pack.endsWith(".zip") ? pack.substring(0, pack.length() - 4) : pack;
                g.text(font, fit(lab, CELL), x, yy + CELL + 4, active ? C_OK : C_TEXT, false);
            }
        }

        // ----- right: overview thumbnails first (the preview block below hides anything scrolled above it) -----
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
                g.fill(x, y, x + TH, y + TH, C_PANEL2);
                flatImg(g, img(ov.get(i)), x + TH / 2, y + TH / 2, TH - 4);
            }
        }
        int hoverOv = ovAt(mx, my, ov.size());

        g.fill(rx - 6, T, rx + RIGHT_W + 6, ovTop(), C_PANEL);
        g.fill(rx, T + 6, rx + RIGHT_W, T + 6 + PREVIEW, C_PANEL2);
        brackets(g, rx, T + 6, RIGHT_W, PREVIEW, 10, C_ACCENT);
        if (cur != null && shown != null) {
            style(g, cur, shown, rx + RIGHT_W / 2, T + 6 + PREVIEW / 2, PREVIEW - 22, false, true);
            boolean act = shown.equals(selPack.get(cur.def.name()));
            String lab = shown.endsWith(".zip") ? shown.substring(0, shown.length() - 4) : shown;
            g.text(font, fit(cur.def.name() + " - " + lab, RIGHT_W), rx, T + PREVIEW + 14, C_TEXT, false);
            g.text(font, act ? "SELECTED" : "not selected", rx, T + PREVIEW + 26, act ? C_OK : C_DIM, false);
            String ovLabel = hoverOv >= 0 ? ov.get(hoverOv).tex().replace(".png", "") : "OVERVIEW (" + ov.size() + ")";
            g.text(font, fit(ovLabel, RIGHT_W), rx, ovTop() - 12, hoverOv >= 0 ? C_TEXT : C_ACCENT, false);
        }

        // ----- hide what scrolled out at the top/bottom, then frames, header and footer -----
        g.fill(0, 0, width, T, C_BG);
        g.fill(0, bottom(), width, height, C_BG);
        border(g, L - 4, T, LEFT_W + 8, bottom() - T, C_LINE);
        border(g, cx0() - 6, T, cx1() - cx0() + 12, bottom() - T, C_LINE);
        border(g, rx - 6, T, RIGHT_W + 12, bottom() - T, C_LINE);
        title(g);
        if (cur != null)
            g.text(font, cur.def.name().toUpperCase(Locale.ROOT) + "   " + cur.packs().size() + (cur.packs().size() == 1 ? " STYLE" : " STYLES"),
                    cx0(), 14, C_TEXT, false);
        else
            g.text(font, "No matching textures found. Put packs in .minecraft/resourcepacks", cx0(), 14, C_DIM, false);

        int by = height - 30;
        g.fill(0, bottom() + 2, width, bottom() + 3, C_LINE);
        button(g, L, by, 80, applying ? "..." : "APPLY", mx, my, dirty);
        button(g, L + 88, by, 80, "RESET", mx, my, false);
        g.text(font, status.isEmpty() ? "CLICK A STYLE, THEN APPLY  -  RIGHT SHIFT CLOSES" : status, L + 182, by + 5, C_DIM, false);
        String cnt = selPack.size() + " SELECTED";
        g.text(font, cnt, width - L - font.width(cnt), by + 5, C_DIM, false);
    }

    private void title(GuiGraphicsExtractor g) {
        g.fill(L, 10, L + 3, 24, C_ACCENT);
        g.text(font, "TEXTUREPACK", L + 8, 12, C_TEXT, false);
        g.text(font, "CLIENT", L + 8 + font.width("TEXTUREPACK "), 12, C_ACCENT, false);
        g.fill(0, T - 3, width, T - 2, C_LINE);
        g.fill(L, T - 3, L + 96, T - 2, C_ACCENT);
    }

    private void button(GuiGraphicsExtractor g, int x, int y, int w, String label, int mx, int my, boolean primary) {
        boolean h = in(mx, my, x, y, w, 18);
        g.fill(x, y, x + w, y + 18, primary ? C_ACCENT : (h ? 0xFF232B33 : C_PANEL2));
        g.fill(x, y + 16, x + w, y + 18, primary ? 0xFF0E8FB0 : C_ACCENT);
        g.text(font, label, x + (w - font.width(label)) / 2, y + 4, primary ? 0xFF00141C : C_TEXT, false);
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

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
    private static final int CELL = 84, GAP = 8, LABEL = 16, N = 16, PREVIEW = 96;

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
    private double leftScroll = 0, scroll = 0;
    private boolean dirty, applying, closing, shiftReleased;
    private String status = "";

    // overview window (big, structured view of everything one style contains)
    private boolean ovOpen;
    private CatData ovCat;
    private String ovPack;
    private List<Section> ovSections = List.of();
    private CompletableFuture<Map<Source, int[]>> ovDimsFuture;
    private Map<Source, int[]> ovDims;
    private double ovScroll2;
    private int ovHeight, ovViewH;

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

    private int maxLeft() { return Math.max(0, data.size() * ROW - (bottom() - T)); }

    private int maxCenter() {
        if (cur == null) return 0;
        int rows = (cur.packs().size() + cols() - 1) / cols();
        return Math.max(0, rows * (CELL + LABEL + GAP) - (bottom() - T));
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


    // ---------- overview window ----------
    record Tile(Source src, int part, String label, int boxW, int boxH, boolean fixed) {}
    record Section(String title, List<Tile> tiles) {}
    record Placed(Tile t, int x, int y, int w, int h) {}
    record Row(int y, String text) {}
    record Layout(List<Placed> placed, List<Row> rows, int height) {}

    private static Tile fixedTile(Source s, String label, int w, int h) { return new Tile(s, -1, label, w, h, true); }
    private static Tile freeTile(Source s) { return new Tile(s, -1, "", 0, 0, false); }

    private static void addSection(List<Section> out, String title, List<Tile> tiles) {
        if (!tiles.isEmpty()) out.add(new Section(title, tiles));
    }

    private List<Section> genericSections(Map<String, Source> f, Set<String> skip) {
        TreeMap<String, List<Tile>> byDir = new TreeMap<>();
        for (var e : f.entrySet()) {
            if (skip != null && skip.contains(e.getKey())) continue;
            String k = e.getKey();
            String dir = k.substring(0, k.lastIndexOf('/'));
            if (dir.startsWith("gui/")) dir = dir.substring(4);
            byDir.computeIfAbsent(dir, d -> new ArrayList<>()).add(freeTile(e.getValue()));
        }
        List<Section> out = new ArrayList<>();
        byDir.forEach((d, l) -> out.add(new Section(d.replace("sprites/", "").toUpperCase(Locale.ROOT), l)));
        return out;
    }

    /** Armor: one row per material with helmet, chestplate, leggings, boots and the worn layers. */
    private List<Section> armorSections(Map<String, Source> f) {
        List<Section> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        String[] mats = {"netherite", "diamond", "iron", "golden", "chainmail", "leather", "turtle"};
        String[] parts = {"helmet", "chestplate", "leggings", "boots"};
        for (String m : mats) {
            List<Tile> tiles = new ArrayList<>();
            boolean any = false;
            for (String part : parts) {
                String key = "item/" + m + "_" + part + ".png";
                Source s = f.get(key);
                if (s != null) { used.add(key); any = true; }
                if (s != null || !m.equals("turtle")) tiles.add(fixedTile(s, part.equals("helmet") ? "helm" : part.equals("chestplate") ? "chest" : part.equals("leggings") ? "legs" : part, 40, 40));
            }
            String wk = m.equals("golden") ? "gold" : m;
            List<String> l1 = new ArrayList<>(List.of("entity/equipment/humanoid/" + wk + ".png", "models/armor/" + wk + "_layer_1.png"));
            List<String> l2 = new ArrayList<>(List.of("entity/equipment/humanoid_leggings/" + wk + ".png", "models/armor/" + wk + "_layer_2.png"));
            if (m.equals("turtle")) { l1.add("entity/equipment/humanoid/turtle_scute.png"); l2.add("entity/equipment/humanoid_leggings/turtle_scute.png"); }
            for (String k : l1) if (f.containsKey(k)) { tiles.add(fixedTile(f.get(k), "worn 1", 96, 48)); used.add(k); any = true; break; }
            for (String k : l2) if (f.containsKey(k)) { tiles.add(fixedTile(f.get(k), "worn 2", 96, 48)); used.add(k); any = true; break; }
            if (any) out.add(new Section(m.toUpperCase(Locale.ROOT), tiles));
        }
        out.addAll(genericSections(f, used));
        return out;
    }

    /** Sky: sun, the eight moon phases (cut out of the atlas), clouds, end sky, weather. */
    private List<Section> skySections(Map<String, Source> f) {
        List<Section> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        List<Tile> sun = new ArrayList<>(), moon = new ArrayList<>(), clouds = new ArrayList<>(), end = new ArrayList<>(), weather = new ArrayList<>();
        for (var e : f.entrySet()) {
            String k = e.getKey(), n = nm(k);
            Source s = e.getValue();
            if (n.equals("moon_phases")) { for (int i = 0; i < 8; i++) moon.add(new Tile(s, i, String.valueOf(i + 1), 40, 40, true)); }
            else if (n.equals("sun")) sun.add(fixedTile(s, "", 56, 56));
            else if (n.contains("moon")) moon.add(fixedTile(s, "", 40, 40));
            else if (n.contains("cloud")) clouds.add(fixedTile(s, "", 96, 96));
            else if (n.contains("end_sky")) end.add(fixedTile(s, "", 96, 96));
            else if (n.equals("rain") || n.equals("snow")) weather.add(fixedTile(s, n, 56, 56));
            else continue;
            used.add(k);
        }
        addSection(out, "SUN", sun);
        addSection(out, "MOON", moon);
        addSection(out, "CLOUDS", clouds);
        addSection(out, "END SKY", end);
        addSection(out, "WEATHER", weather);
        out.addAll(genericSections(f, used));
        return out;
    }

    private void openOverview(CatData c, String pack) {
        Map<String, Source> f = c.faces(pack);
        List<Section> secs;
        if (c.def.name().equals("Armor")) secs = armorSections(f);
        else if (c.def.name().equals("Sky")) secs = skySections(f);
        else secs = genericSections(f, null);
        ovCat = c; ovPack = pack; ovSections = secs; ovOpen = true; ovScroll2 = 0; ovDims = null;
        List<Source> srcs = new ArrayList<>();
        for (Section s : secs) for (Tile t : s.tiles()) if (t.src() != null) srcs.add(t.src());
        ovDimsFuture = CompletableFuture.supplyAsync(() -> readDims(srcs));
    }

    private static int[] pngSize(byte[] h) {
        if (h.length < 24) return new int[]{16, 16};
        int w = ((h[16] & 255) << 24) | ((h[17] & 255) << 16) | ((h[18] & 255) << 8) | (h[19] & 255);
        int hh = ((h[20] & 255) << 24) | ((h[21] & 255) << 16) | ((h[22] & 255) << 8) | (h[23] & 255);
        return new int[]{Math.max(1, w), Math.max(1, hh)};
    }

    /** Reads the image sizes straight from the PNG headers (fast, no decoding). */
    private static Map<Source, int[]> readDims(Collection<Source> srcs) {
        Map<Source, int[]> out = new HashMap<>();
        Map<java.nio.file.Path, List<Source>> byFile = new HashMap<>();
        for (Source s : srcs) byFile.computeIfAbsent(s.file(), k -> new ArrayList<>()).add(s);
        for (var e : byFile.entrySet()) {
            try {
                if (e.getValue().get(0).zip()) {
                    try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(e.getKey().toFile())) {
                        for (Source s : e.getValue()) {
                            var ze = z.getEntry(PackScanner.ROOT + s.tex());
                            if (ze == null) continue;
                            try (var in = z.getInputStream(ze)) { out.put(s, pngSize(in.readNBytes(24))); }
                        }
                    }
                } else {
                    for (Source s : e.getValue()) {
                        try (var in = java.nio.file.Files.newInputStream(e.getKey().resolve(PackScanner.ROOT + s.tex()))) {
                            out.put(s, pngSize(in.readNBytes(24)));
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** Size a tile is drawn at (keeps the aspect ratio; fixed tiles are centred in their box). */
    private int[] disp(Tile t) {
        int[] d = ovDims == null ? null : ovDims.get(t.src());
        int w = d == null ? 16 : d[0], h = d == null ? 16 : d[1];
        if (t.part() >= 0) { w = Math.max(1, w / 4); h = Math.max(1, h / 2); }
        else h = Math.min(w, h); // animated strips: first frame only
        double sc;
        if (t.fixed()) sc = Math.min((double) t.boxW() / w, (double) t.boxH() / h);
        else if (w <= 32 && h <= 32) sc = Math.min(2.5, 40.0 / Math.max(w, h)); // small sprites stay small
        else sc = Math.min(140.0 / w, 96.0 / h);                                // big ones are fitted into 140 x 96
        return new int[]{Math.max(4, (int) Math.round(w * sc)), Math.max(4, (int) Math.round(h * sc))};
    }

    private Layout layout(List<Section> secs, int maxW) {
        List<Placed> placed = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        int y = 0;
        for (Section s : secs) {
            rows.add(new Row(y, s.title() + "  (" + s.tiles().size() + ")"));
            y += 16;
            int x = 0, rowH = 0;
            for (Tile t : s.tiles()) {
                int[] d = disp(t);
                int w = t.fixed() ? t.boxW() : d[0];
                int h = (t.fixed() ? t.boxH() : d[1]) + (t.label().isEmpty() ? 0 : 11);
                if (x > 0 && x + w > maxW) { y += rowH + 6; x = 0; rowH = 0; }
                placed.add(new Placed(t, x, y, w, h));
                x += w + 6;
                rowH = Math.max(rowH, h);
            }
            y += rowH + 14;
        }
        return new Layout(placed, rows, y);
    }

    private int ovCloseX() { return width - L + 4 - 76; }

    private void drawTile(GuiGraphicsExtractor g, Placed p, int ox, int oy, boolean hov) {
        Tile t = p.t();
        int x = ox + p.x(), y = oy + p.y();
        int boxH = p.h() - (t.label().isEmpty() ? 0 : 11);
        g.fill(x, y, x + p.w(), y + boxH, C_PANEL2);
        if (t.src() == null) {
            g.text(font, "-", x + p.w() / 2 - 2, y + boxH / 2 - 4, 0xFF4A5560, false);
        } else {
            Img im = img(t.src());
            if (im != null && im.id() != null) {
                int[] d = disp(t);
                int dx = x + (p.w() - d[0]) / 2, dy = y + (boxH - d[1]) / 2;
                float u0 = 0, v0 = 0;
                int rw, rh;
                if (t.part() >= 0) {
                    rw = Math.max(1, im.w() / 4); rh = Math.max(1, im.h() / 2);
                    u0 = (t.part() % 4) * rw; v0 = (t.part() / 4) * rh;
                } else { rw = im.w(); rh = Math.min(im.w(), im.h()); }
                g.blit(RenderPipelines.GUI_TEXTURED, im.id(), dx, dy, u0, v0, d[0], d[1], rw, rh, im.w(), im.h());
            }
        }
        border(g, x, y, p.w(), boxH, hov ? C_ACCENT : C_LINE);
        if (!t.label().isEmpty()) g.text(font, fit(t.label(), p.w() + 6), x, y + boxH + 2, t.src() == null ? 0xFF4A5560 : C_DIM, false);
    }

    private void drawOverview(GuiGraphicsExtractor g, int mx, int my) {
        int x0 = L - 4, x1 = width - L + 4, y0 = T, y1 = bottom();
        g.fill(x0, y0, x1, y1, C_PANEL);
        Tile hoverTile = null;
        int cx = L + 4, top = y0 + 30;
        ovViewH = y1 - top - 4;
        if (ovDims == null) {
            if (ovDimsFuture != null && ovDimsFuture.isDone()) ovDims = ovDimsFuture.join();
            else g.text(font, "Loading overview...", cx, top + 6, C_DIM, false);
        }
        if (ovDims != null) {
            Layout lay = layout(ovSections, x1 - x0 - 16);
            ovHeight = lay.height();
            ovScroll2 = Math.max(0, Math.min(Math.max(0, ovHeight - ovViewH), ovScroll2));
            int oy = top - (int) ovScroll2;
            for (Row r : lay.rows()) {
                int y = oy + r.y();
                if (y + 12 < top || y > y1) continue;
                g.text(font, r.text(), cx, y, C_ACCENT, false);
                g.fill(cx, y + 11, x1 - 12, y + 12, C_LINE);
            }
            for (Placed p : lay.placed()) {
                int y = oy + p.y();
                if (y + p.h() < top || y > y1) continue;
                boolean hov = mx >= cx + p.x() && mx <= cx + p.x() + p.w() && my >= y && my <= y + p.h() && my >= top && my <= y1;
                if (hov) hoverTile = p.t();
                drawTile(g, p, cx, oy, hov);
            }
        }
        // title bar on top (hides content that scrolled up)
        g.fill(x0, y0, x1, y0 + 26, C_PANEL);
        g.fill(x0, y0 + 26, x1, y0 + 27, C_LINE);
        String pl = ovPack.endsWith(".zip") ? ovPack.substring(0, ovPack.length() - 4) : ovPack;
        g.text(font, "OVERVIEW  " + ovCat.def.name().toUpperCase(Locale.ROOT) + "  -  " + pl, cx, y0 + 9, C_TEXT, false);
        if (hoverTile != null) {
            String name = hoverTile.src() == null ? "not in this pack" : hoverTile.src().tex().replace(".png", "");
            String fitted = fit(name, Math.max(40, ovCloseX() - cx - font.width("OVERVIEW  " + ovCat.def.name().toUpperCase(Locale.ROOT) + "  -  " + pl) - 24));
            g.text(font, fitted, ovCloseX() - 8 - font.width(fitted), y0 + 9, C_ACCENT, false);
        }
        button(g, ovCloseX(), y0 + 4, 70, "CLOSE", mx, my, false);
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

        // ----- right: big preview + open-overview button -----
        String shown = null;
        if (cur != null) shown = hoverCell >= 0 ? cur.packs().get(hoverCell) : (inspectPack != null ? inspectPack : previewPack(cur));
        g.fill(rx, T + 6, rx + RIGHT_W, T + 6 + PREVIEW, C_PANEL2);
        brackets(g, rx, T + 6, RIGHT_W, PREVIEW, 10, C_ACCENT);
        if (cur != null && shown != null) {
            style(g, cur, shown, rx + RIGHT_W / 2, T + 6 + PREVIEW / 2, PREVIEW - 22, false, true);
            boolean act = shown.equals(selPack.get(cur.def.name()));
            String lab = shown.endsWith(".zip") ? shown.substring(0, shown.length() - 4) : shown;
            g.text(font, fit(cur.def.name() + " - " + lab, RIGHT_W), rx, T + PREVIEW + 14, C_TEXT, false);
            g.text(font, act ? "SELECTED" : "not selected", rx, T + PREVIEW + 26, act ? C_OK : C_DIM, false);
            button(g, rx, T + PREVIEW + 40, RIGHT_W, "OPEN OVERVIEW", mx, my, false);
            g.text(font, "Shows every texture of this", rx, T + PREVIEW + 66, C_DIM, false);
            g.text(font, "style, sorted and labelled.", rx, T + PREVIEW + 77, C_DIM, false);
            g.text(font, "(right-click a style = same)", rx, T + PREVIEW + 88, C_DIM, false);
        }

        if (ovOpen) drawOverview(g, mx, my);

        // ----- hide what scrolled out at the top/bottom, then frames, header and footer -----
        g.fill(0, 0, width, T, C_BG);
        g.fill(0, bottom(), width, height, C_BG);
        if (ovOpen) {
            border(g, L - 4, T, width - 2 * L + 8, bottom() - T, C_ACCENT);
        } else {
            border(g, L - 4, T, LEFT_W + 8, bottom() - T, C_LINE);
            border(g, cx0() - 6, T, cx1() - cx0() + 12, bottom() - T, C_LINE);
            border(g, rx - 6, T, RIGHT_W + 12, bottom() - T, C_LINE);
        }
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
        String cnt = selPack.size() + " SELECTED";
        int hintW = width - L - font.width(cnt) - (L + 182) - 16;
        g.text(font, fit(status.isEmpty() ? "CLICK = SELECT   RIGHT-CLICK = OVERVIEW   RIGHT SHIFT = CLOSE" : status, hintW), L + 182, by + 5, C_DIM, false);
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
        if (ovOpen) {
            if (button == 1 || in(mx, my, ovCloseX(), T + 4, 70, 18)) ovOpen = false;
            return true;
        }
        int by = height - 30;
        if (button == 0 && in(mx, my, L, by, 80, 18)) { apply(); return true; }
        if (button == 0 && in(mx, my, L + 88, by, 80, 18)) {
            selPack.clear(); dirty = true; status = "Cleared - press APPLY.";
            return true;
        }
        int rxb = width - L - RIGHT_W;
        if (cur != null && button == 0 && in(mx, my, rxb, T + PREVIEW + 40, RIGHT_W, 18)) {
            openOverview(cur, inspectPack != null ? inspectPack : previewPack(cur));
            return true;
        }
        int row = rowAt(mx, my);
        if (row >= 0) { cur = data.get(row); inspectPack = null; scroll = 0; return true; }
        int cell = cellAt(mx, my);
        if (cell >= 0 && cur != null) {
            String pack = cur.packs().get(cell);
            inspectPack = pack;
            if (button == 1) { openOverview(cur, pack); return true; }
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
        if (ovOpen) {
            ovScroll2 = Math.max(0, Math.min(Math.max(0, ovHeight - ovViewH), ovScroll2 - vertical * 30));
            return true;
        }
        if (x < cx0() - 5) leftScroll = Math.max(0, Math.min(maxLeft(), leftScroll - vertical * ROW * 2));
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

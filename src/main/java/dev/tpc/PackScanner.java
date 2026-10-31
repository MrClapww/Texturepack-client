package dev.tpc;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.zip.ZipFile;

/** Finds every texture in every pack inside the resourcepacks folder. */
public final class PackScanner {
    public static final String ROOT = "assets/minecraft/textures/";
    static final List<String> DIRS = List.of("block/", "item/", "gui/", "environment/", "particle/",
            "entity/end_crystal/", "entity/shield");

    public record Source(String pack, String tex, Path file, boolean zip) {
        public byte[] read() throws IOException { return readEntry(ROOT + tex, true); }

        /** Optional .mcmeta (animation / gui scaling). Returns null if the pack has none. */
        public byte[] readMeta() {
            try { return readEntry(ROOT + tex + ".mcmeta", false); } catch (IOException e) { return null; }
        }

        private byte[] readEntry(String path, boolean required) throws IOException {
            if (!zip) {
                Path f = file.resolve(path);
                if (!Files.exists(f)) { if (required) throw new IOException("missing " + path); return null; }
                return Files.readAllBytes(f);
            }
            try (ZipFile z = new ZipFile(file.toFile())) {
                var e = z.getEntry(path);
                if (e == null) { if (required) throw new IOException("missing " + path); return null; }
                try (var in = z.getInputStream(e)) { return in.readAllBytes(); }
            }
        }

        public String packLabel() { return pack.endsWith(".zip") ? pack.substring(0, pack.length() - 4) : pack; }

        public String texLabel() {
            String n = tex.substring(tex.lastIndexOf('/') + 1);
            return n.substring(0, n.length() - 4);
        }
    }

    public record Category(String name, Predicate<String> match) {}

    public static final List<Category> CATEGORIES = List.of(
            new Category("All", t -> true),
            new Category("Crystal", t -> t.contains("crystal")),
            new Category("Obsidian", t -> t.contains("obsidian")),
            new Category("Armor", t -> t.contains("helmet") || t.contains("chestplate") || t.contains("leggings") || t.contains("boots")),
            new Category("Tools", t -> t.contains("sword") || t.contains("pickaxe") || t.contains("_axe") || t.contains("shovel") || t.contains("hoe") || t.contains("mace") || t.contains("trident")),
            new Category("Gaps & Pearls", t -> t.contains("golden_apple") || t.contains("ender_pearl")),
            new Category("Totem", t -> t.contains("totem")),
            new Category("HUD", t -> t.startsWith("gui/")),
            new Category("Fire", t -> t.contains("fire") || t.contains("flame")),
            new Category("Sky", t -> t.startsWith("environment/")),
            new Category("Stone", t -> t.contains("stone") || t.contains("deepslate")),
            new Category("Grass", t -> t.contains("grass") || t.contains("dirt")),
            new Category("Shield", t -> t.contains("shield")),
            new Category("Cobwebs", t -> t.contains("cobweb")),
            new Category("Blocks", t -> t.startsWith("block/")),
            new Category("Items", t -> t.startsWith("item/")));

    public static Map<String, List<Source>> scan(Path dir) {
        Map<String, List<Source>> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String name = p.getFileName().toString();
                if (name.equals(MixBuilder.MIX_DIR)) continue;
                try {
                    if (Files.isDirectory(p)) {
                        Path root = p.resolve(ROOT);
                        if (!Files.isDirectory(root)) continue;
                        try (var w = Files.walk(root)) {
                            w.filter(f -> f.toString().endsWith(".png"))
                             .forEach(f -> add(out, name, root.relativize(f).toString().replace('\\', '/'), p, false));
                        }
                    } else if (name.endsWith(".zip")) {
                        try (ZipFile z = new ZipFile(p.toFile())) {
                            z.stream().filter(e -> !e.isDirectory() && e.getName().startsWith(ROOT) && e.getName().endsWith(".png"))
                             .forEach(e -> add(out, name, e.getName().substring(ROOT.length()), p, true));
                        }
                    }
                } catch (IOException | RuntimeException ignored) { /* broken pack: skip it */ }
            }
        } catch (IOException ignored) {}
        return out;
    }

    private static void add(Map<String, List<Source>> out, String pack, String tex, Path file, boolean zip) {
        for (String d : DIRS) {
            if (tex.startsWith(d)) {
                out.computeIfAbsent(tex, k -> new ArrayList<>()).add(new Source(pack, tex, file, zip));
                return;
            }
        }
    }
}

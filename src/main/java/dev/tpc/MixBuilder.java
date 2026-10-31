package dev.tpc;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Writes the chosen textures into a folder-pack and enables it (Minecraft 26.x). */
public final class MixBuilder {
    public static final String MIX_DIR = "tpc_mix";
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("texturepack-client.json");

    public static Path packDir() { return FabricLoader.getInstance().getGameDir().resolve("resourcepacks"); }

    public static Map<String, String> loadSelection() {
        Map<String, String> m = new HashMap<>();
        try {
            if (Files.exists(CONFIG)) {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG)).getAsJsonObject();
                o.entrySet().forEach(e -> m.put(e.getKey(), e.getValue().getAsString()));
            }
        } catch (Exception ignored) {}
        return m;
    }

    /** Step 1 (any thread): write files. Returns how many textures could not be copied. */
    public static int write(Map<String, PackScanner.Source> sel) throws IOException {
        Path dir = packDir().resolve(MIX_DIR);
        if (Files.exists(dir)) {
            try (var w = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) w.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
            }
        }
        Files.createDirectories(dir);
        // resource pack format 88 = Minecraft 26.2
        Files.writeString(dir.resolve("pack.mcmeta"),
                "{\"pack\":{\"description\":\"Texturepack Client mix\",\"min_format\":[88,0],\"max_format\":[88,0]}}");
        int failed = 0;
        JsonObject cfg = new JsonObject();
        for (var e : sel.entrySet()) {
            try {
                Path target = dir.resolve(PackScanner.ROOT + e.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, e.getValue().read());
                byte[] meta = e.getValue().readMeta();
                if (meta != null) Files.write(target.resolveSibling(target.getFileName() + ".mcmeta"), meta);
                cfg.addProperty(e.getKey(), e.getValue().pack());
            } catch (Exception ex) {
                failed++;
            }
        }
        Files.createDirectories(CONFIG.getParent());
        Files.writeString(CONFIG, new GsonBuilder().setPrettyPrinting().create().toJson(cfg));
        return failed;
    }

    /** Step 2 (client thread): enable the mix pack and reload. */
    public static void enable() {
        Minecraft mc = Minecraft.getInstance();
        PackRepository repo = mc.getResourcePackRepository();
        repo.reload();
        List<String> selected = new ArrayList<>(repo.getSelectedIds());
        String id = "file/" + MIX_DIR;
        boolean already = selected.contains(id);
        if (!already) selected.add(id); // last entry = highest priority
        repo.setSelected(selected);
        mc.options.updateResourcePacks(repo); // reloads by itself when the list changed
        if (already) mc.reloadResourcePacks();
    }
}

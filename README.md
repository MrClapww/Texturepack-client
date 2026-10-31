# Texturepack Client (Minecraft 26.2)

Client-side Fabric mod. Press **Right Shift** in game:
- left: every block / item you have a texture for (tabs PVP, CPVP, BLOCKS, ITEMS, OTHER, ALL + search)
- middle: the styles (resource packs) for the chosen entry, blocks are drawn as small 3D cubes (FLAT/3D button top right)
- APPLY writes your choices into `resourcepacks/tpc_mix` and reloads.

Needs Fabric Loader 0.20.1+ and Fabric API for 26.2.

## Build
Push to GitHub: the Actions tab builds the jar (Artifacts -> texturepack-client-26.2).
Locally: JDK 25 + Gradle 9.8+, then `gradle build`.

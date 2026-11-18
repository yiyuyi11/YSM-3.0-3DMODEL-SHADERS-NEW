package com.ysmnpr.endfield;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * config/endfield_npr/endfield_npr.properties: renderer-global settings only (LOD, shadows, exposure, debug, and the
 * "global" group standing in for the game's _CharacterParams0..16, whose real values are set per scene by the game and
 * are not stored in any asset). Everything per model (ramps, LUT, face SDF/mask/highlight, iris matcap, overlay
 * layers, material values) ships inside the model as its NPR package (ModelPackages).
 */
public final class NprConfig {
    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    /**
     * Bumped whenever defaults change meaning (0.4.0: display transform, linear mixes, eye adaptation; 0.5.0: overlay
     * layers from the model profile, self-shadow, ambient occlusion, retuned scene shadow; 6: crowd LOD tiers,
     * animation LOD, GPU storage, lodDistance replaced by lodYsmDistance; 7: per-model material values moved into the
     * model's NPR package; 8: eye adaptation retuned against Bliss, adaptStrength 1.3 -> 0.9, adaptWeather 0). Files of
     * the versions in
     * {@link #MIGRATE_RESET} are migrated in place (backed up as endfield_npr.properties.v&lt;old&gt;.bak, the user's
     * values kept except the listed keys, which take the current defaults; keys the code no longer knows are dropped).
     * Any other version is moved to the backup and replaced by the current defaults, so stale values from an older
     * package never override tested defaults.
     */
    static final String CONFIG_VERSION = "8";
    /** Migratable versions and the keys whose defaults changed meaning since them. */
    private static final Map<String, java.util.List<String>> MIGRATE_RESET = Map.of(
        "7", java.util.List.of("adaptStrength"), // 8: the gain no longer follows rain/thunder (adaptWeather), 0.9 fits
        "6", java.util.List.of("adaptStrength"), // 7 drops the material keys (skin ... outlineColorHairEye): they are per model now
        "5", java.util.List.of("adaptStrength"), // 6 only adds keys; lodDistance (handed characters to YSM at 48 blocks) is dropped
        "4", java.util.List.of("sceneShadowStrength", "adaptStrength")); // 0.5.0 retuned it (0.6 -> 0.5) with sceneShadowAmbient

    static {
        d("configVersion", CONFIG_VERSION, "Config format version (older files are migrated or backed up and replaced by the current defaults)");
        d("enabled", "true", "Draw YSM humanoid-import models with the Endfield NPR program");
        d("outline", "true", "Draw the screen-space Endfield outline shell");
        d("antiAliasing", "ssaa", "Antialiasing of the close-up NPR characters (drawn after the shader pack's TAA, so otherwise aliased): ssaa = shaded per sample in a shared multisampled target (edges and shading; needs GL 4.0 or ARB_sample_shading, else msaa), msaa = shaded per pixel (edges only), off = no antialiasing for anyone");
        d("aaSamples", "4", "Samples per pixel for antiAliasing (2, 4 or 8, clamped to the GPU's limits); ssaa costs about this many times a close-up's fragment shading");
        d("aaHeroSize", "0.25", "A FULL character at least this tall on screen (fraction of the screen height, after any scope/spyglass zoom) is a close-up and gets antiAliasing; others within half the largest close-up's height join it (the local player on this size alone)");
        d("aaOthers", "msaa", "Antialiasing of the other characters: msaa = per pixel in the same multisampled target and depth as the close-ups (a passer-by in front of a close-up occludes it correctly), off = none (drawn straight into the main target first: cheapest, aliased edges)");
        d("lodYsmDistance", "0", "Beyond this many blocks from the camera a character goes back to YSM's own path (0 = never; replaces the old lodDistance, which handed characters to YSM at 48 blocks)");
        d("lodTiers", "true", "Multiplayer LOD tiers by projected size and count: FULL (body, overlay layers, outline, self-shadow, AO, scene-shadow PCF, face SDF), REDUCED (body only with the depth prepass), MINIMAL (body with diffuse ramp, ambient, exposure and display transform only) (false = FULL for every character)");
        d("lodFullSize", "0.10", "FULL tier from this projected height (fraction of the screen height; 0.10 = 13 blocks at FOV 70)");
        d("lodReducedSize", "0.025", "REDUCED tier from this projected height; smaller characters are MINIMAL");
        d("lodMaxFull", "4", "At most this many FULL characters (largest on screen first; the rest REDUCED)");
        d("lodMaxNpr", "12", "At most this many FULL + REDUCED characters (largest first; the rest MINIMAL; at most 128 NPR characters in all, any beyond go to YSM's path)");
        d("lodHysteresis", "0.15", "Tier/interval boundaries move by +-this fraction around the current state (and the cap ranking favours the current state by it), so characters near a boundary do not pop");
        d("animLod", "true", "Animation LOD: YSM's pose extraction (bones, morphs, springs) runs every 1/2/4/8 frames by projected size, staggered per character; off-screen characters only every animLodOffscreenInterval frames; shadow/main-pass repeats of a frame's extract are skipped; NPR characters reuse their last skin between updates");
        d("animLodSizes", "0.12,0.06,0.03", "Projected-height thresholds for update intervals 1 | 2 | 4 | 8 frames");
        d("animLodMaxFullRate", "8", "At most this many characters update every frame (largest first; the rest at least every 2nd frame); the local player is never throttled");
        d("animLodOffscreenInterval", "8", "Pose extraction interval (frames) of characters not rendered last frame; also the most frames a pose can be old when a character comes back into view");
        d("gpuPersistentBuffers", "true", "Vertex storage as persistently mapped ARB_buffer_storage pages (YSM writes REDUCED/MINIMAL skins straight into GPU-visible memory, FULL skins via a CPU copy; fenced double buffers); false or no extension = glBufferSubData");
        d("gpuCoherentMapping", "true", "Persistent pages mapped MAP_COHERENT (false = MAP_FLUSH_EXPLICIT with glFlushMappedBufferRange per capture)");
        d("gpuMultiDraw", "true", "One glMultiDrawElementsIndirect per batched group of REDUCED/MINIMAL characters (ARB_multi_draw_indirect + ARB_base_instance); false or missing = one glDrawElementsBaseVertex per character");
        d("gpuDirectStateAccess", "true", "Create and edit the mod's own framebuffers, textures, buffers and vertex arrays by name (GL 4.5 / ARB_direct_state_access: no bind-to-edit); false or missing = bind-to-edit");
        d("debugTiming", "false", "Log averaged per-frame ms (frame, capture, upload, draw CPU/GPU, YSM clone draws, pose extracts) every debugTimingFrames frames");
        d("debugTimingFrames", "120", "Frames per debugTiming log line");
        d("debugClones", "0", "Debug crowd: draw this many extra copies of the local player's character in 5-wide rows ahead of it (each with its own LOD tier, skin and draw)");
        d("debugCloneSpacing", "1.5", "Spacing of the debug copies in blocks");
        d("debugView", "0", "0 off; 1 view-space normal as colour; 2 raw N.L (grey); 3 N.L of the flat triangle normal (dFdx/dFdy); 4 r = ramp lit alpha, g = view alpha, b = rim + back light; 5 r = self-shadow Y, g = ambient occlusion, b = scene shadow X; 6 the normal-mapped shading normal as colour (1 = the mesh normal); 7 raw N.L of the normal-mapped normal");
        d("debugFaceCrack", "0", "Test input of faceCrackFill: cut a vertical crack this many pixels wide through every FULL character's face (0 = off)");
        d("debugDepthOffset", "false", "Diagnostic: true = the 0.6.2 depth handling under a shader pack (prepassed characters drawn with a polygon offset against an exact copy of the pack depth, which let hidden surfaces such as the mouth cavity through as dots); false = no offset, the copy is pushed back by its local slope");
        // global (_CharacterParams stand-ins)
        d("exposure", "1.0", "Final multiplier");
        d("tonemap", "true", "Apply the game's display transform to the character (ACES_modified post LUT, neutral grading); the pack's tonemap ran before this pass");
        d("tonemapExposure", "1.3", "Linear exposure into the ACES_modified curve (tuned under Bliss clear daylight against ref 07: lit cheek sat 0.09-0.10 / val 0.90-0.92)");
        d("tonemapSaturation", "0.93", "Saturation inside the post LUT (0.93 in the game's LutBuilder)");
        d("postSaturation", "0.85", "Display-space saturation after the transform, standing in for the game's uncaptured colour grade (tuned: hair sat 0.07-0.08, body 0.07, as ref 07)");
        d("highlightSatBoost", "1.0", "Weight of the highlight saturation boost (1 = full, 0 = off)");
        d("linearColorMath", "true", "Skin edge tint, ramp chroma tint and highlight saturation boost in linear colour, as the game does (false = the 0.3.x display-space mixes)");
        d("adaptExposure", "true", "Eye adaptation like the shader pack's auto exposure: gain = (adaptReference / light level)^adaptStrength, so a sunset is not underexposed against the adapted world");
        d("adaptReference", "1.337", "Own light level at clear noon under open sky (key luminance + 0.35 x ambient)");
        d("adaptStrength", "0.9", "Exponent of the adaptation (fitted in Bliss: character vs grass at sunset, time 12500, as at clear noon; 0.6.2 used 1.3, which lifted her 1.5x too bright at sunset)");
        d("adaptWeather", "0.0", "Share of the rain/thunder dimming the adaptation compensates (0 = none: Bliss keeps rain and thunder dark, the character darkens with the rain-dimmed key light and lightmap; 1 = 0.6.2, which lifted her 2.5-2.9x and left her 2.8-3.7x too bright against the grass)");
        d("adaptLegacy", "false", "A/B switch: true = the 0.6.2 adaptation (adaptStrength 1.3, adaptWeather 1) whatever those keys say");
        d("adaptRange", "0.5,8.0", "Clamp of the adaptation gain (min, max)");
        d("adaptNightMax", "2.5", "Adaptation ceiling with the sun below the horizon (blends to adaptRange max over sunrise/sunset), so nights stay night-like");
        d("adaptTime", "1.0", "Eye adaptation time constant in seconds: the shared light level eases toward the viewer's surroundings like the pack's smoothed auto exposure (0 = instant). A per-character, per-frame light level made running figures flicker");
        d("eyeBrightness", "1.0", "Multiplier on the final colour of eye-class texels (iris and brows; the sclera is face class), after the iris matcap and before the display transform; never on the outline");
        d("eyeNightBrightness", "0.8", "Extra eye multiplier at night, blended in with the sun like adaptNightMax (1 at day, this value with the sun below the horizon): the iris takes no AO or scene shadow and the night adaptation lifts it, so the eyes looked self-lit");
        d("eyeNightGlow", "0.15", "A little self-lit iris by night: this share of the iris colour (linear) is added after exposure, blended in with the sun like eyeNightBrightness (0 at day, this value with the sun below the horizon); iris disc only, never the brows or the outline; 0 = off");
        d("irisTerms", "true", "Iris terms from the model's iris material (materials.<iris>.params): the _ParallaxScale uv shift toward the view, _EyeHighLightColor outside the iris circle and _EyeScatteringColor by the iris alpha (baseAlpha map). Models without those params are unchanged");
        d("browThroughHair", "true", "Brows (and iris) show through the bangs where the hair's _HairBrowMask allows it (_DrawUnderBrow = 1; FULL tier): the eye-material quads are drawn again over hair texels with mask.r < _HairBrowMaskThreshold. Models without a browMask map are unchanged");
        d("browRevealDepth", "0.04", "How far behind the masked bangs (blocks) a brow may lie and still show through them; the sample model's bangs sit 6-37 mm in front of her brows across views (tools/eyebrow_eval.py)");
        d("sceneShadow", "true", "Receive the shader pack's sun shadows (trees, walls, overhangs) as Endfield's scene shadow X (needs Oculus/Iris with a shadow map)");
        d("sceneShadowStrength", "0.5", "X = lerp(1, shadow map, strength) (EFL_SourceSceneShadowX; the game's per-scene value is not captured). Tuned in Bliss at noon under a 7x7 roof: body -40% (0.85 gave -62%, 0.6 -44%)");
        d("sceneShadowAmbient", "0.85", "Ambient left in the pack's sun shadow under open sky (1 = the sky-light ambient as in the open; the lightmap does not see the canopy)");
        d("sceneShadowSelfDistance", "1.0", "Occluders closer than this (blocks, toward the sun) are the character herself and are ignored (her own shadow is the self-shadow map)");
        d("sceneShadowSoftness", "1.5", "3x3 PCF tap spacing in shadow-map texels");
        d("sceneShadowDistort", "1.8,0.04,0.61,6.0", "Pack shadow-map distortion k, d0, d1, z divisor (Bliss lib/Shadow_Params.glsl + shadow.vsh at shadowDistance 128)");
        d("selfShadow", "true", "Character self-shadow Y (hair on face, arms on body) from her own shadow map along the shading light, as Endfield's character shadow atlas");
        d("selfShadowResolution", "1536", "Self-shadow map size in texels (orthographic, fitted to the mesh each frame; 256-4096)");
        d("selfShadowStrength", "1.0", "Y = lerp(1, map, strength); Y switches the shadowed texels to the toon shadow colour like the ramp's own terminator");
        d("selfShadowBias", "0.006,0.004", "Receiver offset toward the light and along the normal (blocks), scaled by 1 - clamp(N.L, 0, 0.9) (EndfieldMainSelfShadowMask.usf)");
        d("selfShadowSoftness", "1.0", "Self-shadow 4x4 filter tap spacing in map texels");
        d("depthPrepass", "true", "Write her depth into the shader pack's gbuffer depth so its SSAO and contact shadows see her (ground AO at her feet); needs Oculus with a pack");
        d("translucentSeeThrough", "0.8", "With the depth prepass: where glass or water lies in front of her she is drawn at this opacity over the pack's translucent image (the pack's translucent layer cannot be re-applied on top)");
        d("ambientOcclusion", "1.0", "Strength of the scene-consistent AO on her ambient light: the pack's own SSAO (Bliss composite.fsh SSAO, 7 taps on depthtex1) evaluated for her pixels; 0 = off");
        d("aoCurve", "2.0", "Exponent on the AO before it scales her ambient (Bliss uses pow(SSAO, 6) on temporally denoised AO; on her single-frame AO 6 darkened the whole figure and showed tap patterns, 2 keeps contact AO only)");
        d("characterLight", "1.0", "CP1.w blend from the sun toward the CharInfo camera-follow key light (pitch >= 32 deg, yaw +12 deg; 0 = sun only)");
        d("sunScale", "1.0", "Main light intensity at full daylight");
        d("ambientScale", "1.0", "Lightmap ambient multiplier");
        d("lightScale", "1.0", "CP0.y direct-light multiplier");
        d("shadowScale", "1.0", "CP0.z shadow albedo / minimum lit factor");
        d("shadowAmbScale", "1.0", "CP0.w shadow-side ambient multiplier");
        d("ambToWhite", "1.0", "CP1.y lit side: ambient hue -> white");
        d("ndotlBias", "0.0", "CP11.w*CP12.x N.L bias before the ramp");
        d("backlight", "1.0", "1-CP12.x back-light wrap / dark-silhouette lift");
        d("specScale", "1.0", "CP13.w specular multiplier");
        d("rimColor", "1.0,1.0,1.0", "CP8.rgb rim colour");
        d("rimIntensity", "0.35", "CP8.a rim intensity");
        d("rimScreenDir", "0.3,1.0", "CP9.xy rim offset; rim axis = cross(camBack, offset) = (-y, x): (0.3,1) puts the rim on the screen-left edges as in refs 14/16 (negating it is the same as swapping the cross order)");
        d("rimAlbedo", "0.5", "CP9.z rim tinted by albedo");
        d("rimWidth", "0.5", "CP9.w rim width");
        d("rimDepth", "true", "Hair rim gated by screen-space depth: the scene depth rimWidth x 0.006 of the screen height away along the screen normal must lie rimDepthRange blocks behind the pixel, so the rim lights the silhouette against what is behind it and never the inside of the hair; needs the depth prepass under a shader pack (otherwise, and with false, the 1 - |N.V| angle gate)");
        d("rimDepthRange", "0.1,0.2", "rimDepth smoothstep range of the depth difference in blocks (0.1, 0.2 m in the game)");
        d("sdfBias", "0.0", "CP16.w face SDF threshold bias");
        d("outlineScale", "1.0", "Multiplier on every model's outline widths");
        d("normalMapStrength", "1.0", "Multiplier on every material's _BumpScale / _SpecBumpScale (normal maps and the hair split normal of the model package's materials data; 0 = off, the mesh normal)");
        d("useShadowLut", "true", "Skin/face/brow shadow colour from the model package's skin LUT (read V-flipped: PNG rows are top-down, Unity GPU rows bottom-up)");
        d("overlay", "true", "Draw the model package's OverlayShadow layers (eye-white shadow, eyeshadow, bangs shadow) multiplied over the face");
        d("faceCrackFill", "true", "Fill crack pixels in faces (an unwelded seam showing hair, the head's inside, the outline shell or the sky) with the surrounding face colour: a non-face, non-eye pixel with face pixels within 2 px in at least 6 of 8 directions, each nearer than it by more than faceCrackDepth; needs GL 4.5 / a texture barrier");
        d("faceCrackDepth", "0.003", "faceCrackFill: how much farther than the face around it (view-space blocks, 0.003 = 3 mm) a pixel must be to count as a crack; lashes, the lip line and highlights lie in front of or level with the face and are never filled");
        d("localLights", "true", "Light-emitting blocks near a FULL character (torches, lanterns, glowstone, lava...) shade her as the game's NPR local lights (Type0 Default: lit side vs back contrast 0.25 cloth/hair/eye, 0.5 skin/face) with the share localLightShare of the lightmap's block light taken out of her ambient, so the average brightness stays the same");
        d("localLightShare", "0.75", "Share of the lightmap's block light (the part the found lights explain) re-aimed from the ambient at the light blocks (0 = ambient only; 1 = all of it: cave contrast lit/dark 3.5 on cloth, 1.9 on skin at a torch 1 block away)");
        d("localLightIntensity", "1.0", "Multiplier on the local lights (1 = energy-neutral: averaged over all normals they return exactly the ambient they took)");
        d("localLightSpecular", "1.0", "Multiplier on the local lights' GGX highlight (cloth, skin, face; the game's specular intensity P7.z)");
        d("localLightCount", "4", "Most local lights per character (0-4; the strongest at her eyes that she can see)");
        d("localLightRadius", "8", "Scan radius in blocks around her eyes (1-15; a box of (2r+1)^3 blocks, only chunk sections whose palette holds an emitter are read)");
        d("localLightRefresh", "4", "Rescan a character's lights every this many ticks (and at once when her eyes move more than a block)");
        // ---- PropMaps: per-material maps of the model package (NprMaps) ----
        d("materialMaps", "true", "A/B switch for the model package's per-material maps (materials.*.maps: property/gloss, emission, hair line, normal and split normal, outline mask, brow mask, cloth parallax): false = drawn as if the package had none (per-class constants, generic outline widths, no brow reveal), live");
        d("hairGlossFallback", "0.6", "Hair _MetallicGlossMap.G stand-in for models whose package has no prop map and sets no hairSpecMask (scales the primary Kajiya highlight and its RS lookup; Endfield's hair RS ramps are black below u = 0.52, so <= 0.5 means no primary highlight). 0.6: 1.5x the energy, 0.33x the peak of the sample model's real map (tools/hair_gloss_eval.py)");
        d("emissionScale", "1.0", "Multiplier on the package's per-material emission (_EmissionMap x _EmissionColor x _EmissionBrightness); 0 = off");
        d("propMetal", "1.0", "Multiplier on the cloth _MetallicGlossMap R (metal) of the model package (0 = no metal; only materials drawn by the game's cloth shader), live");
        d("propShadowWeight", "1.0", "How much the _MetallicGlossMap B (property shadow weight: receiver x B, lit = min(.., B)) darkens hair and cloth: 1 = full, 0 = ignored, live");
        d("envReflection", "1.0", "Cloth environment reflection (cube IBL x EnvBRDF) with the light reaching the surface standing in for the reflection probe; lights metal cloth (_MetallicGlossMap R), which is otherwise black; 0 = off, live");
        d("mapResolution", "2048", "Largest side of the composited per-material map layers (atlas-aligned array, one layer per map group); applies to packages loaded afterwards");
        d("mapBudgetMB", "48", "GPU budget per model for the per-material maps (layers with mipmaps + tiled sheet); the layer resolution drops until they fit; applies to packages loaded afterwards");
        d("mapCacheMB", "256", "Disk cache of the composited per-material maps (.ysm/endfield_npr/cache in the game directory), so a model loaded again skips its map PNG decodes and compositing; least recently used entries go beyond this size (about 2-6 MB per model); 0 = off");
    }

    private static final Properties VALUES = new Properties();
    /** Parsed values; cleared on every (re)load so the per-frame getters never parse strings. */
    private static final Map<String, float[]> PARSED = new HashMap<>();
    /** Word values (lower case), cleared with {@link #PARSED}. */
    private static final Map<String, String> WORDS = new HashMap<>();

    private NprConfig() {}

    private static void d(String key, String value, String comment) {
        DEFAULTS.put(key, value + "\t# " + comment);
    }

    public static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve(EndfieldNpr.MOD_ID);
    }

    private static long loadedStamp;

    /** Re-reads the properties when the file changed on disk (live tuning without a restart). */
    static void reloadIfChanged() {
        try {
            var stamp = Files.getLastModifiedTime(dir().resolve("endfield_npr.properties")).toMillis();
            if (stamp != loadedStamp) {
                VALUES.clear();
                PARSED.clear();
                WORDS.clear();
                load();
            }
        } catch (IOException ignored) {
            // missing file: keep the current values
        }
    }

    static void load() {
        var file = dir().resolve("endfield_npr.properties");
        var migrated = false;
        try {
            Files.createDirectories(dir());
            warnLegacyFiles();
            if (Files.exists(file)) {
                loadedStamp = Files.getLastModifiedTime(file).toMillis();
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    VALUES.load(reader);
                }
                var version = VALUES.getProperty("configVersion", "0").split("#", 2)[0].trim();
                if (!CONFIG_VERSION.equals(version)) {
                    var backup = dir().resolve("endfield_npr.properties.v" + version + ".bak");
                    var reset = MIGRATE_RESET.get(version);
                    if (reset != null) {
                        Files.copy(file, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        reset.forEach(VALUES::remove);
                        VALUES.setProperty("configVersion", CONFIG_VERSION);
                        migrated = true;
                        EndfieldNpr.LOG.info("Endfield NPR: migrated config version {} to {} (backup {}; reset to "
                            + "defaults: {})", version, CONFIG_VERSION, backup.getFileName(), reset);
                    } else {
                        Files.move(file, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        VALUES.clear();
                        EndfieldNpr.LOG.warn("Endfield NPR: config version {} is outdated (current {}); moved it to {} "
                            + "and wrote the current defaults", version, CONFIG_VERSION, backup.getFileName());
                    }
                }
            }
            var rewrite = migrated || DEFAULTS.keySet().stream().anyMatch(k -> !VALUES.containsKey(k))
                || VALUES.stringPropertyNames().stream().anyMatch(k -> !DEFAULTS.containsKey(k)); // stale keys
            if (rewrite) {
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    writer.write("# Endfield NPR for YSM: renderer-global settings. Ramps, LUT, face SDF, overlay\n"
                        + "# textures and material values ship inside each model (VRM extension ENDFIELD_npr).\n");
                    for (var e : DEFAULTS.entrySet()) {
                        var current = VALUES.getProperty(e.getKey());
                        // the stored value still carries its old "# comment" (Properties keeps inline text): drop it
                        var line = current != null
                            ? current.split("#", 2)[0].trim() + "\t# " + e.getValue().split("\t# ", 2)[1]
                            : e.getValue();
                        writer.write(e.getKey() + "=" + line + "\n");
                    }
                }
                loadedStamp = Files.getLastModifiedTime(file).toMillis();
            }
        } catch (IOException failure) {
            EndfieldNpr.LOG.warn("Could not read/write {}: {}", file, failure.toString());
        }
    }

    private static boolean legacyChecked;

    /**
     * Before 0.6 the per-model data lived here (ramps, LUT, SDF, overlay PNGs, models/*.properties, material keys).
     * It is no longer read; say so once, so nobody tunes files that have no effect.
     */
    private static void warnLegacyFiles() {
        if (legacyChecked) {
            return;
        }
        legacyChecked = true;
        try (var files = Files.list(dir())) {
            var legacy = files.map(f -> f.getFileName().toString())
                .filter(n -> n.endsWith(".png") || n.equals("models")).sorted().toList();
            if (!legacy.isEmpty()) {
                EndfieldNpr.LOG.warn("Endfield NPR: {} in {} are no longer used (NPR textures and model profiles now ship "
                    + "inside each model as its NPR package); they can be deleted", legacy, dir());
            }
        } catch (IOException ignored) {
            // unreadable directory: nothing to report
        }
    }

    private static String raw(String key) {
        var v = VALUES.getProperty(key);
        if (v == null) {
            v = DEFAULTS.get(key);
        }
        return v.split("#", 2)[0].trim();
    }

    /** A word value (trimmed, lower case), e.g. antiAliasing; cached until the next reload. */
    public static String word(String key) {
        var cached = WORDS.get(key);
        if (cached == null) {
            cached = raw(key).toLowerCase(java.util.Locale.ROOT);
            WORDS.put(key, cached);
        }
        return cached;
    }

    public static boolean bool(String key) {
        return vec(key, 1)[0] != 0f;
    }

    public static float f(String key) {
        return vec(key, 1)[0];
    }

    /**
     * A comma-separated vector, padded with zeros to at least n (and at least 4) components; booleans parse to 1/0.
     * A value that is neither (a typo in the hand-edited file) falls back to the key's default with a warning, never an
     * exception: this runs inside entity rendering. The returned array is cached and shared: callers must not modify it.
     */
    public static float[] vec(String key, int n) {
        var cached = PARSED.get(key);
        if (cached != null && cached.length >= n) {
            return cached;
        }
        var text = raw(key);
        var out = parse(text, n);
        if (out == null) {
            var fallback = DEFAULTS.get(key).split("\t", 2)[0].trim();
            EndfieldNpr.LOG.warn("Endfield NPR config: {}={} is not a number list or true/false, using the default {}",
                key, text, fallback);
            out = parse(fallback, n);
        }
        PARSED.put(key, out);
        return out;
    }

    /** {@link #vec} of one value, or null when it is neither booleans nor numbers. */
    private static float @org.jetbrains.annotations.Nullable [] parse(String text, int n) {
        var parts = text.split(",");
        var out = new float[Math.max(Math.max(n, 4), parts.length)];
        if (parts.length == 1 && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
            out[0] = Boolean.parseBoolean(text) ? 1f : 0f;
            return out;
        }
        try {
            for (var i = 0; i < parts.length; i++) {
                out[i] = Float.parseFloat(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }
}

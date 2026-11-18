#version 150

#moj_import <fog.glsl>

// Stylized PBR shading for imported humanoids, after Arknights: Endfield and Girls' Frontline 2, without outlines: rim
// light separates the figure. Diffuse falls off softly into light shadow tones taken from the materials' own MMD toon
// ramps (cloth a neutral grey-lavender, skin barely darker and warm, hair in between), keeps its color instead of going
// grey, and the lit side still shades gently with the light's angle. A sky-to-ground term lifts upward faces so shadows
// never go dead. Specular is physically based (GGX) from the model's LabPBR specular map in Sampler3, lightly
// compressed; hair gets a wide, soft, whitish Kajiya-Kay band along world-up strands instead; a Fresnel rim warm on the
// lit side and cool on the shadow side outlines the silhouette. Eyes keep their painted shading. The output stays in
// range: imported skin and cloth are painted near white, so highlights and rim screen over the diffuse instead of
// adding to it and never clip to a flat white patch. Tint, hurt overlay, world light and fog apply as in the vanilla
// entity shader.

uniform sampler2D Sampler0;
uniform sampler2D Sampler3;
// Optional Endfield maps beside the model texture (see ToonExtraMaps): light ramp, specular ramp and shadow colour.
// A model without them binds a white pixel, which leaves every term below unchanged.
uniform sampler2D Sampler4;
uniform sampler2D Sampler5;
uniform sampler2D Sampler6;
// Slices packed into Sampler6 when the model supplies a 3D LUT as a vertical strip of square slices; 1 for a plain
// horizontal gradient (and for the one-pixel fallback).
uniform int LutSlices;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec3 lightNormal;
in vec3 viewPosition;
in vec3 viewUp;

out vec4 fragColor;

// A 3D LUT exported as a vertical strip of square slices, sampled trilinearly; a plain 2D gradient (LutSlices 1) is
// read directly. A model without a LUT binds one white pixel, so every term below stays unchanged.
vec3 lutSample(float x, float y, float z) {
    if (LutSlices <= 1) {
        return texture(Sampler6, vec2(x, 0.5)).rgb;
    }
    float slices = float(LutSlices);
    float s = clamp(z, 0.0, 1.0) * (slices - 1.0);
    float s0 = floor(s);
    float s1 = min(s0 + 1.0, slices - 1.0);
    float f = s - s0;
    vec2 uv0 = vec2(x, (y + s0) / slices);
    vec2 uv1 = vec2(x, (y + s1) / slices);
    return mix(texture(Sampler6, uv0).rgb, texture(Sampler6, uv1).rgb, f);
}

const float PI = 3.14159265;
const vec3 LUMA = vec3(0.299, 0.587, 0.114);
// Light below this cosine falls into shadow, over a band this wide on each side (Endfield's smoothstep from 0.1 to
// 0.4); scattering skin widens it.
const float SHADOW_EDGE = 0.25;
const float EDGE_SOFTNESS = 0.15;
const float SKIN_SOFTNESS = 0.3;
// Shadow tones per surface. Skin keeps a warm, scattered shadow deep enough to model the form; cloth and hair turn
// toward the cool sky fill, so the figure balances warm light against cool shade.
const vec3 SHADOW_TINT = vec3(0.78, 0.80, 0.88);
const vec3 SKIN_SHADOW_TINT = vec3(0.94, 0.80, 0.78);
const vec3 HAIR_SHADOW_TINT = vec3(0.84, 0.82, 0.90);
const float SHADOW_SATURATION = 1.1;
// The key light is slightly warm, and fully lit surfaces keep this share of their painted color: characters painted
// near white would otherwise stand out brighter than any block around them.
const vec3 KEY_TINT = vec3(1.0, 0.985, 0.96);
const float EXPOSURE = 0.94;
// The lit side darkens to this share where light grazes it.
const float LIT_FLOOR = 0.84;
// Faces turned straight down keep this share of their light, fading to all of it at the horizontal: less sky and more
// ground reaches them. Upright and upward faces are not darkened.
const float GROUND_SHADE = 0.88;
// Warm light scattered through skin at the terminator, as a share of the base color.
const vec3 SCATTER_GLOW = vec3(0.06, 0.02, 0.005);
// Metals reflect most of their color: diffuse keeps this share.
const float METAL_DIFFUSE = 0.3;
const float SPECULAR_STRENGTH = 0.35;
// Hair band: sharpness of the Kajiya-Kay lobe, the lobe values cut into a soft, wide band, its strength, and how much of
// the hair's own color it carries: a gloss on the hair, not a white stripe.
const float HAIR_EXPONENT = 12.0;
const float HAIR_BAND_START = 0.45;
const float HAIR_BAND_END = 0.85;
const float HAIR_STRENGTH = 0.08;
const float HAIR_BAND_COLOR = 0.7;
// Rim light over this range of 1 - |N.V|, as a fraction of the base color: warm where lit, cool in shadow.
const float RIM_START = 0.5;
const float RIM_END = 0.9;
const float RIM_LIT = 0.2;
const float RIM_SHADOW = 0.14;
const vec3 RIM_WARM = vec3(1.0, 0.96, 0.88);
const vec3 RIM_COOL = vec3(0.72, 0.82, 1.0);
// Eyes never fall below this share of their color, however dark the world: a floor, not a glow.
const float EYE_FLOOR = 0.35;
// Shaderpack compatibility: the brightest output the shader allows. Shaderpacks tone map and bloom what the entity
// shader writes, so a painted-near-white character under a bright pack would clip to a flat patch; capping here keeps
// the shading readable and lets the pack's own tone mapping do the rest. Vanilla never reaches this cap.
const float HIGHLIGHT_CAP = 0.94;

vec3 saturateTone(vec3 color, float amount) {
    return clamp(mix(vec3(dot(color, LUMA)), color, amount), 0.0, 1.0);
}

void main() {
    vec4 base = texture(Sampler0, texCoord0);
    if (base.a < 0.1) {
        discard;
    }
    // LabPBR: red perceptual smoothness, green F0 (255 for a metal tinted by its albedo), blue porosity up to 64 and
    // subsurface scattering above. Blue 115-135 marks hair, 140 and up skin, porosity 5 eyes (see SpecularAtlas).
    vec4 surface = texture(Sampler3, texCoord0);
    float blue = surface.b * 255.0;
    float scattering = blue >= 65.0 ? (blue - 65.0) / 190.0 : 0.0;
    float hair = blue >= 115.0 && blue <= 135.0 ? 1.0 : 0.0;
    float eye = abs(blue - 5.0) < 0.5 ? 1.0 : 0.0;
    float metal = surface.g >= 0.995 ? 1.0 : 0.0;
    vec3 f0 = mix(vec3(surface.g), base.rgb, metal);

    // Imported surfaces are double-sided: the back of a skirt or hair card faces the other way.
    float side = gl_FrontFacing ? 1.0 : -1.0;
    vec3 n = normalize(lightNormal) * side;
    vec3 v = normalize(-viewPosition);
    vec3 l0 = normalize(Light0_Direction);
    vec3 l1 = normalize(Light1_Direction);
    float key0 = dot(l0, n);
    float key1 = dot(l1, n);
    vec3 l = key0 >= key1 ? l0 : l1;
    float key = max(key0, key1);

    // Diffuse: a soft ramp into the shadow tone, gentle shading across the lit side.
    // Hair's blue codes sit in the scattering range too, but hair shades as hair.
    float skin = clamp(scattering * 2.4, 0.0, 1.0) * (1.0 - hair);
    float softness = mix(EDGE_SOFTNESS, SKIN_SOFTNESS, skin);
    float lit = smoothstep(SHADOW_EDGE - softness, SHADOW_EDGE + softness, key);
    vec3 tint = mix(mix(SHADOW_TINT, SKIN_SHADOW_TINT, skin), HAIR_SHADOW_TINT, hair);
    vec3 shadowTone = lutSample(lit, 0.5, 0.5);
    // Endfield's own ramps run from black through a tinted mid tone to white, so the mid tone divided by its brightest
    // channel is the shadow's colour cast: warm for skin, cool for cloth. Without a ramp the pixel is white.
    vec3 rampShadow = texture(Sampler4, vec2(0.25, 0.5)).rgb;
    float rampMax = max(max(rampShadow.r, rampShadow.g), rampShadow.b);
    vec3 rampTint = rampShadow / max(rampMax, 0.001);
    vec3 shadow = saturateTone(base.rgb * tint * shadowTone * mix(vec3(1.0), rampTint, 0.6),
        SHADOW_SATURATION);
    vec3 litTone = base.rgb * KEY_TINT * mix(LIT_FLOOR, 1.0, smoothstep(0.0, 0.8, key));
    vec3 glow = base.rgb * SCATTER_GLOW * skin * 4.0 * lit * (1.0 - lit);
    vec3 diffuse = (mix(shadow, litTone, lit) + glow) * mix(1.0, METAL_DIFFUSE, metal) * EXPOSURE;
    diffuse *= mix(GROUND_SHADE, 1.0, clamp(dot(n, normalize(viewUp)) + 1.0, 0.0, 1.0));
    // Eyes take no shadow from the head's shape: their painted shading and highlights stay as drawn.
    diffuse = mix(diffuse, base.rgb, eye);

    // GGX specular from the key light, shadowed with the ramp.
    vec3 h = normalize(l + v);
    float nh = max(dot(n, h), 0.0);
    float nv = max(dot(n, v), 1e-3);
    float nl = max(dot(n, l), 0.0);
    float vh = max(dot(v, h), 0.0);
    float roughness = max((1.0 - surface.r) * (1.0 - surface.r), 0.03);
    float a2 = roughness * roughness;
    float d = a2 / (PI * pow(nh * nh * (a2 - 1.0) + 1.0, 2.0));
    float k = roughness * 0.5;
    float visibility = 0.25 / ((nv * (1.0 - k) + k) * (nl * (1.0 - k) + k));
    vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - vh, 5.0);
    vec3 specular = fresnel * d * visibility * nl * lit;
    specular = specular / (1.0 + 0.5 * specular) * SPECULAR_STRENGTH * texture(Sampler5, vec2(nh, 0.5)).rgb;

    // Hair: a Kajiya-Kay band where the strand direction (world up across the surface) is normal to the half vector.
    vec3 strand = viewUp - n * dot(n, viewUp);
    strand = dot(strand, strand) > 1e-6 ? normalize(strand) : vec3(0.0, 1.0, 0.0);
    float th = dot(strand, h);
    float lobe = pow(sqrt(max(1.0 - th * th, 0.0)), HAIR_EXPONENT);
    float ring = smoothstep(HAIR_BAND_START, HAIR_BAND_END, lobe) * lit * HAIR_STRENGTH;
    specular = mix(specular, mix(vec3(1.0), base.rgb, HAIR_BAND_COLOR) * ring, hair);

    float facing = abs(dot(n, v));
    float rim = smoothstep(RIM_START, RIM_END, 1.0 - facing) * mix(RIM_SHADOW, RIM_LIT, lit) * mix(1.0, 0.6, skin);
    vec3 rimColor = mix(RIM_COOL, RIM_WARM, lit);

    // Screen the highlights over the diffuse: they brighten dark surfaces fully and bright ones only up to white.
    vec3 highlights = clamp((specular + base.rgb * rimColor * rim) * (1.0 - eye), 0.0, 1.0);
    vec3 color = 1.0 - (1.0 - clamp(diffuse, 0.0, 1.0)) * (1.0 - highlights);
    vec4 result = vec4(color, base.a) * vertexColor * ColorModulator;
    result.rgb = mix(overlayColor.rgb, result.rgb, overlayColor.a);
    result.rgb = max(result.rgb * lightMapColor.rgb, base.rgb * vertexColor.rgb * EYE_FLOOR * eye);
    result.a *= lightMapColor.a;
    // Shaderpack compatibility: a pack's light map already carries its own exposure, so cap the top end here instead of
    // letting it clip into flat white; below the cap nothing changes, so vanilla shading is untouched.
    result.rgb = min(result.rgb, vec3(HIGHLIGHT_CAP));
    fragColor = linear_fog(result, vertexDistance, FogStart, FogEnd, FogColor);
}
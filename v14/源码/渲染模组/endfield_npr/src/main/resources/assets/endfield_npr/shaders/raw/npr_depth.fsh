#version 150
// Mode 0 (gbuffer prepass): depth only. Mode 1 (self-shadow caster): R32F caster depth, +2 when the caster is a face
// texel (a face receiver ignores those: no nose, cheek or lid shadows across the SDF-shaded face, while hair still
// shades the face and the chin the neck); iris and brow texels do not cast. A texel is a face caster when it lies in
// the profile's face rectangle (FaceRect, atlas uv): YSM's class map is not reliable there (it classes face texels
// by colour, the white sclera as cloth), and face texels it misses cast onto the face. Transparent texels (the
// OverlayShadow cards' stand-in texture among them) never write depth.
uniform sampler2D Atlas;
uniform sampler2D ClassMap; // YSM's specular/class atlas (blue: 5 eye/brow, 155+ face)
uniform float HasClassMap;
uniform vec4 FaceRect;      // face_01 region in atlas uv (zero without a profile)
uniform int Mode;

in vec2 texCoord0;

out vec4 casterOut;

void main() {
    if (texture(Atlas, texCoord0).a < 0.1) {
        discard;
    }
    float face = 0.0;
    if (Mode == 1) {
        if (HasClassMap > 0.5) {
            float blue = texture(ClassMap, texCoord0).b * 255.0;
            if (abs(blue - 5.0) < 2.5) {
                discard;
            }
            face = blue >= 155.0 ? 2.0 : 0.0;
        }
        vec2 f = (texCoord0 - FaceRect.xy) / max(FaceRect.zw, vec2(1e-6));
        if (FaceRect.z > 0.0 && all(greaterThanEqual(f, vec2(0.0))) && all(lessThanEqual(f, vec2(1.0)))) {
            face = 2.0;
        }
    }
    casterOut = vec4(gl_FragCoord.z + face);
}

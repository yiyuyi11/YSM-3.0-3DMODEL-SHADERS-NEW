#version 150

#moj_import <fog.glsl>

// Outline hull: cut the hull away where the model's outline mask is transparent, so the mask can switch the line off
// per pixel (Endfield's _OutlineMask). Without a mask the sidecar loader binds a white pixel and every hull pixel draws.

uniform sampler2D Sampler0;
uniform sampler2D Sampler4;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 base = texture(Sampler0, texCoord0);
    if (base.a < 0.1) {
        discard;
    }
    if (texture(Sampler4, texCoord0).r < 0.5) {
        discard;
    }
    vec3 color = vec3(0.08, 0.07, 0.09) * lightMapColor.rgb * vertexColor.rgb;
    vec4 result = vec4(color, 1.0) * ColorModulator;
    fragColor = linear_fog(result, vertexDistance, FogStart, FogEnd, FogColor);
}

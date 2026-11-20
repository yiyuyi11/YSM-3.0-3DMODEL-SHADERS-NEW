#version 150
#ifdef GL_ARB_sample_shading
#extension GL_ARB_sample_shading : enable
#endif
// HGRP/CharacterNPR_OverlayShadow fragment stage (decompiled OverlayShadow_CharacterNPR_OverlayShadow.glsl 89-94 and
// 629-644; ue5ref ShadowOverlay.ush stages 01-05), blended DstColor x Src (npr_overlay.json):
//   shadow = lerp(t, (1, 1, 1, t.r), _UseGrayAsAlpha) * _BaseColor;  out = lerp(1, shadow.rgb, shadow.a)
// with no VFX colour adjustment, dither, scene-depth clip or local-light suppression (all off in Pelica's materials).
// Receiver gate (EFL_ShadowOverlayStage_Receiver): the layer draws where (stencil & 20) == (_ShadowOverIris & 20); the
// body pass wrote that class into the Receiver texture as k/8 (2 skin/cloth -> 4, 3 face -> 4, 4 hair -> 4,
// 6 iris/brow -> 20, 0 nothing).
// _DisableDrawUnderHair = 0 suppresses the layer where hair is the receiver.
// With antialiasing (NprMsaa) the receiver is multisampled. With ARB_sample_shading (GL 4.0) this program reads
// gl_SampleID, which runs it once per sample in every mode: each sample is gated by its own receiver class. Without it
// the gate is evaluated on every sample of the pixel and the layer applied by the fraction that passes.
// Game order (overlayGameOrder): the game's layer multiplies the HDR scene colour before its tonemap; ours lands on
// the displayed colour, which made every layer far darker in bright light (noon eye-white shadow 0.33 instead of
// 0.75). The body pass stores its pre-tonemap luminance Y in the receiver's green channel, and the layer multiplies
// the displayed colour by tone(Y x out) / tone(Y) per channel (tone = npr_character.fsh displayTransform of a grey),
// or by out^(1/2.2) without our display transform. Stacked layers each use the body's Y (second order).
uniform sampler2D OverlayTex; // _BaseMap (grey in red)
uniform sampler2D Receiver;   // r receiver class, g pre-tonemap luminance (ReceiverTarget.java)
uniform sampler2DMS ReceiverMS; // the same, multisampled (NprMsaa), bound by NprRenderer on its own unit
uniform float ReceiverSamples;  // samples of ReceiverMS, 0 = single-sample Receiver
uniform vec4 LayerColor;      // _BaseColor
uniform vec4 LayerParams;     // _UseGrayAsAlpha, _ShadowOverIris, _DisableDrawUnderHair, _ShadowAngleRange
uniform vec2 OverlayTone;     // x 1 = game order (overlayGameOrder), y 1 = our display transform is on (tonemap)
in vec2 cardUV;

out vec4 fragColor;

// 1 where the layer applies over receiver value r: the stencil test passes and the receiver is not hair the layer
// must not draw under (that case drew with zero alpha: no change, same as not drawing)
float passes(float r) {
    float k = floor(r * 8.0 + 0.5);
    if (k > 6.5) { // 7: catchlight texels of the iris (npr_character.fsh irisCatch, eyeHighlightOverShadow): no layer
        return 0.0;
    }
    int stencil = k > 4.5 ? 20 : k > 0.5 ? 4 : -1;
    if (stencil != (int(LayerParams.y + 0.5) & 20)) {
        return 0.0;
    }
    return LayerParams.z < 0.5 && abs(k - 4.0) < 0.5 ? 0.0 : 1.0;
}

// npr_character.fsh acesModifiedCurve for one channel
float acesCurve(float x) {
    return clamp(x * (x * 2.785085 + 0.107772) / (x * (x * 2.936045 + 0.887122) + 0.806889), 0.0, 1.0);
}

// a grey of linear luminance y (after exposure) through npr_character.fsh displayTransform: the curve, the highlight
// pull toward white, sRGB encoding
float tone(float y) {
    float t = mix(acesCurve(y), 1.0, clamp((y - 0.5) * 0.6666667, 0.0, 1.0));
    return t <= 0.003131 ? t * 12.92 : 1.055 * pow(t, 0.416667) - 0.055;
}

// the multiplier on the displayed colour for the layer's own multiplier m, over a receiver with green g
vec3 displayed(vec3 m, float g) {
    if (OverlayTone.x < 0.5) {
        return m;
    }
    if (OverlayTone.y < 0.5) {
        return pow(m, vec3(1.0 / 2.2));
    }
    float y = exp2(g * 18.0 - 14.0); // npr_character.fsh receiverLum
    return vec3(tone(y * m.r), tone(y * m.g), tone(y * m.b)) / max(tone(y), 1e-5);
}

void main() {
    ivec2 px = ivec2(floor(gl_FragCoord.xy));
    vec4 t = texture(OverlayTex, cardUV);
    vec4 shadow = mix(t, vec4(1.0, 1.0, 1.0, t.r), LayerParams.x) * LayerColor;
    vec3 m = mix(vec3(1.0), shadow.rgb, clamp(shadow.a, 0.0, 1.0));
    float cover = 0.0;
    vec3 mul = vec3(0.0); // the passing samples' displayed multipliers, summed (averaged over the pixel)
    if (ReceiverSamples > 0.5) {
#ifdef GL_ARB_sample_shading
        vec2 r = texelFetch(ReceiverMS, px, gl_SampleID).rg;
        cover = passes(r.r);
        mul = cover * displayed(m, r.g);
#else
        int n = int(ReceiverSamples + 0.5);
        for (int s = 0; s < n; s++) {
            vec2 r = texelFetch(ReceiverMS, px, s).rg;
            float p = passes(r.r);
            cover += p;
            mul += p * displayed(m, r.g);
        }
        cover /= float(n);
        mul /= float(n);
#endif
    } else {
        vec2 r = texelFetch(Receiver, px, 0).rg;
        cover = passes(r.r);
        mul = cover * displayed(m, r.g);
    }
    if (cover <= 0.0) {
        discard;
    }
    fragColor = vec4(vec3(1.0 - cover) + mul, 1.0);
}

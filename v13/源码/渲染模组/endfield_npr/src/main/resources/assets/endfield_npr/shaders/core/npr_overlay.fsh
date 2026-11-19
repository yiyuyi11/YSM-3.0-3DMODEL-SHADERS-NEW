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
uniform sampler2D OverlayTex; // _BaseMap (grey in red)
uniform sampler2D Receiver;   // receiver class per screen pixel (ReceiverTarget.java)
uniform sampler2DMS ReceiverMS; // the same, multisampled (NprMsaa), bound by NprRenderer on its own unit
uniform float ReceiverSamples;  // samples of ReceiverMS, 0 = single-sample Receiver
uniform vec4 LayerColor;      // _BaseColor
uniform vec4 LayerParams;     // _UseGrayAsAlpha, _ShadowOverIris, _DisableDrawUnderHair, _ShadowAngleRange

in vec2 cardUV;

out vec4 fragColor;

// 1 where the layer applies over receiver value r: the stencil test passes and the receiver is not hair the layer
// must not draw under (that case drew with zero alpha: no change, same as not drawing)
float passes(float r) {
    float k = floor(r * 8.0 + 0.5);
    int stencil = k > 4.5 ? 20 : k > 0.5 ? 4 : -1;
    if (stencil != (int(LayerParams.y + 0.5) & 20)) {
        return 0.0;
    }
    return LayerParams.z < 0.5 && abs(k - 4.0) < 0.5 ? 0.0 : 1.0;
}

void main() {
    ivec2 px = ivec2(floor(gl_FragCoord.xy));
    float cover = 0.0;
    if (ReceiverSamples > 0.5) {
#ifdef GL_ARB_sample_shading
        cover = passes(texelFetch(ReceiverMS, px, gl_SampleID).r);
#else
        int n = int(ReceiverSamples + 0.5);
        for (int s = 0; s < n; s++) {
            cover += passes(texelFetch(ReceiverMS, px, s).r);
        }
        cover /= float(n);
#endif
    } else {
        cover = passes(texelFetch(Receiver, px, 0).r);
    }
    if (cover <= 0.0) {
        discard;
    }
    vec4 t = texture(OverlayTex, cardUV);
    vec4 shadow = mix(t, vec4(1.0, 1.0, 1.0, t.r), LayerParams.x) * LayerColor;
    fragColor = vec4(mix(vec3(1.0), shadow.rgb, clamp(shadow.a, 0.0, 1.0) * cover), 1.0);
}

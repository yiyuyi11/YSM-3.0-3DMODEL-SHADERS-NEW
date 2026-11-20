#version 150
// HGRP/CharacterNPR_OverlayShadow vertex stage for one layer, drawn over the card's own quads only (MeshBuffer
// cardRuns). The card slides sideways with the light (ue5ref ShadowOverlay.ush EFL_ShadowOverlayStage_VertexOffset:
// -CameraRight * light.x * _ShadowAngleRange); the atlas uv is mapped back to the card's own 0..1 uv through the
// profile's card rectangle (fix_pelica.py puts the card uvs in the middle half of a transparent stand-in region).
in vec3 Position;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 LightDirView;
uniform vec4 CardRect;    // the card's uv rectangle in atlas uv: xy origin, zw size
uniform vec4 LayerParams; // _UseGrayAsAlpha, _ShadowOverIris (stencil reference), _DisableDrawUnderHair, _ShadowAngleRange

out vec2 cardUV;

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    view.x -= LightDirView.x * LayerParams.w;
    gl_Position = ProjMat * view;
    cardUV = (UV0 - CardRect.xy) / CardRect.zw;
}

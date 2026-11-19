#version 150
// Depth-only pass of the captured character (RawGl): the gbuffer depth prepass (ProjMat = the camera projection) and
// the self-shadow caster map (ProjMat = the light's orthographic projection). The position math is the same as
// npr_character.vsh and both declare gl_Position invariant, so the prepass depth equals the NPR body's depth exactly
// and its LEQUAL test passes on her own prepass.
in vec3 Position;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 Model; // pose_now * pose_at_skin^-1 of a reused skin (identity otherwise), as NprDrawBlock.model
uniform mat4 ProjMat;

out vec2 texCoord0;

invariant gl_Position;

void main() {
    vec4 view = ModelViewMat * (Model * vec4(Position, 1.0));
    gl_Position = ProjMat * view;
    texCoord0 = UV0;
}

#version 150

#moj_import <fog.glsl>

// Same inputs as the vanilla entity shaders; lighting moves to the fragment stage for cel shading.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;
uniform int FogShape;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
// In the space of Light0_Direction and Light1_Direction, as vanilla lights entity normals: the view's orientation.
out vec3 lightNormal;
out vec3 viewPosition;
// World up in the same space, along which hair strands run.
out vec3 viewUp;

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;

    vertexDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);
    vertexColor = Color;
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;
    lightNormal = Normal;
    viewPosition = view.xyz;
    // IViewRotMat turns view directions into world ones; its transpose turns world up back into the view.
    viewUp = vec3(IViewRotMat[0][1], IViewRotMat[1][1], IViewRotMat[2][1]);
}

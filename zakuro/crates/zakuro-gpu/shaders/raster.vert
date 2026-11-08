#version 450

// vertices the CPU already shaded, clipped and placed on the target, with
// w kept so the varyings interpolate with perspective.
layout(location = 0) in vec4 position;
layout(location = 1) in vec4 color;
layout(location = 2) in vec4 texcoords01;
layout(location = 3) in vec4 texcoord2_depth;
layout(location = 4) in vec4 quaternion;
layout(location = 5) in vec4 view;

layout(location = 0) out vec4 out_color;
layout(location = 1) out vec4 out_texcoords01;
layout(location = 2) out vec2 out_texcoord2;
// the mapped depth before any w-buffer scaling, linear on the screen
layout(location = 3) noperspective out float out_depth;
layout(location = 4) out vec4 out_quaternion;
layout(location = 5) out vec3 out_view;

// the rasterizer can't clamp depth, the vertices' is clamped here so that
// none is clipped, and a draw reaching past the range works its fragments'
// out in the fragment shader
layout(constant_id = 0) const bool CLAMP_DEPTH = false;

void main() {
    // the mapped depth on the rasterizer's side too, for draws whose
    // fragment shader leaves depth alone
    float z = CLAMP_DEPTH ? clamp(texcoord2_depth.z, 0.0, 1.0) : texcoord2_depth.z;
    gl_Position = vec4(position.xy, z * position.w, position.w);
    out_color = color;
    out_texcoords01 = texcoords01;
    out_texcoord2 = texcoord2_depth.xy;
    out_depth = texcoord2_depth.z;
    out_quaternion = quaternion;
    out_view = view.xyz;
}

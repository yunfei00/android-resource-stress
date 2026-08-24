#version 450

layout(location = 0) in vec2 uv;
layout(location = 1) flat in int layer;
layout(location = 0) out vec4 outputColor;

layout(push_constant) uniform Scene {
    float time;
    float target;
    float aspect;
    float padding;
} scene;

void main() {
    vec2 point = uv * 2.0 - 1.0;
    point.x *= scene.aspect;
    vec2 warped = point;
    float energy = 0.0;
    float detail = 1.0;
    int complexity = int(mix(4.0, 16.0, scene.target));
    for (int iteration = 0; iteration < 16; ++iteration) {
        if (iteration >= complexity) break;
        float phase = scene.time * (0.22 + float(iteration) * 0.009) +
            float(iteration) * 0.71;
        warped = vec2(
            sin(warped.x * 1.73 + warped.y * 0.83 + phase),
            cos(warped.y * 1.51 - warped.x * 0.69 - phase)
        );
        float orbit = length(warped + vec2(sin(phase), cos(phase)) * 0.32);
        energy += (0.055 + 0.018 * sin(phase)) / max(0.07, abs(orbit - 0.72));
        detail *= 0.94;
        warped = warped.yx * detail + point * 0.17;
    }
    float radius = length(point);
    float rings = 0.5 + 0.5 * cos(radius * 18.0 - scene.time * 2.4);
    float grid = pow(max(0.0,
        sin(point.x * 43.0 + scene.time * 0.9) *
        cos(point.y * 37.0 - scene.time * 1.1)), 12.0);
    vec3 palette = 0.5 + 0.5 * cos(
        vec3(0.1, 2.2, 4.3) + energy * 0.42 + scene.time * 0.16);
    vec3 color = palette * (0.16 + energy * 0.12) +
        vec3(0.04, 0.22, 0.62) * rings * 0.24 +
        vec3(1.0, 0.42, 0.12) * grid;
    color = color / (1.0 + color);
    outputColor = vec4(pow(max(color, vec3(0.0)), vec3(0.82)), 1.0);
}

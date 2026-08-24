#version 450

layout(location = 0) in vec2 uv;
layout(location = 1) flat in int layer;
layout(location = 0) out vec4 outputColor;

void main() {
    vec2 point = uv * 2.0 - 1.0;
    float value = float(layer + 1) * 0.071;
    for (int iteration = 0; iteration < 28; ++iteration) {
        float phase = float(iteration) * 0.137 + value;
        point = vec2(
            sin(point.x * 1.731 + point.y * 0.917 + phase),
            cos(point.y * 1.413 - point.x * 0.773 + phase)
        );
        value += dot(point, point.yx) * 0.019 + 0.003;
    }
    outputColor = vec4(
        0.35 + 0.35 * sin(value * 3.1),
        0.40 + 0.35 * cos(value * 2.7),
        0.55 + 0.30 * sin(value * 1.9 + 1.2),
        1.0
    );
}

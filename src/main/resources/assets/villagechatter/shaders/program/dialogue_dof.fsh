#version 150

// Depth-of-field for conversations: keeps the speaker (at FocusDistance) sharp and softens what's behind.

uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;

uniform float FocusDistance;
uniform float Strength;
uniform float Near;
uniform float Far;

in vec2 texCoord;
in vec2 oneTexel;

out vec4 fragColor;

const float MAX_RADIUS = 7.0; // pixels at full strength

const vec2 DISK[12] = vec2[](
    vec2(-0.326, -0.406), vec2(-0.840, -0.074), vec2(-0.696,  0.457),
    vec2(-0.203,  0.621), vec2( 0.962, -0.195), vec2( 0.473, -0.480),
    vec2( 0.519,  0.767), vec2( 0.185, -0.893), vec2( 0.507,  0.064),
    vec2( 0.896,  0.412), vec2(-0.322, -0.933), vec2(-0.792, -0.598)
);

float linearDepth(float d) {
    float z = d * 2.0 - 1.0;
    return (2.0 * Near * Far) / (Far + Near - z * (Far - Near));
}

// 0 = in focus, 1 = fully blurred.
float blurAmount(float depth) {
    float behind = clamp((depth - FocusDistance * 1.35) / (FocusDistance * 2.5 + 4.0), 0.0, 1.0);
    float front = clamp((FocusDistance * 0.5 - depth) / max(FocusDistance * 0.5, 0.5), 0.0, 1.0) * 0.5;
    return max(behind, front);
}

void main() {
    vec3 base = texture(DiffuseSampler, texCoord).rgb;
    float depth = linearDepth(texture(DepthSampler, texCoord).r);
    float coc = blurAmount(depth) * Strength;
    if (coc < 0.02) {
        fragColor = vec4(base, 1.0);
        return;
    }

    float radius = coc * MAX_RADIUS;
    vec3 sum = base;
    float weight = 1.0;
    for (int i = 0; i < 12; i++) {
        vec2 uv = texCoord + DISK[i] * radius * oneTexel;
        float sampleDepth = linearDepth(texture(DepthSampler, uv).r);
        // Don't smear the sharp speaker into the blurred background around them.
        float w = (sampleDepth < depth * 0.7) ? 0.0 : 1.0;
        sum += texture(DiffuseSampler, uv).rgb * w;
        weight += w;
    }
    fragColor = vec4(sum / weight, 1.0);
}

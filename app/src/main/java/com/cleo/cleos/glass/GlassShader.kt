package com.cleo.cleos.glass

/**
 * The liquid glass, as one AGSL pass over the (already blurred) backdrop.
 *
 * Coordinates are those of the padded layer the glass renders into: the glass itself
 * occupies [origin, origin + size], the margin around it holds backdrop content for the
 * refraction to pull from and room for the drop shadow.
 *
 * How it reads, top to bottom:
 *  - A rounded-rect signed distance gives "how deep inside the edge is this pixel".
 *  - The rim is modelled as a quarter-circle bevel of width `bevel`. Where the surface
 *    curves the sample point is pushed along the outward normal, hardest right at the
 *    edge. Positive `refraction` pulls content in from beyond the edge, so things slide
 *    into the rim before they are underneath the glass; negative magnifies instead.
 *  - Red and blue are displaced a little less / more than green (`dispersion`), which is
 *    the faint rainbow fringe real glass shows at a curved edge.
 *  - Vibrancy (saturation) and a tint are applied after refraction, so the tint does not
 *    wash out the bent edge detail.
 *  - A thin specular line runs along the edge, brightest where the rim faces `light` and,
 *    more weakly, on the opposite side where light exits.
 *  - Outside the glass only a soft shadow is drawn.
 *  - `zoom` magnifies the flat middle (the tab bar lens uses it while it is held).
 *
 * `origin`/`size` may describe a rectangle larger than the view the glass belongs to:
 * a pressed button swells by growing its outline here, inside the padded layer, rather
 * than by scaling the view. Scaling the view would scale the backdrop it samples too,
 * and the glass would stop lining up with what is actually behind it.
 *
 * `visible` is the part of the layer that is on screen. Samples are clamped into it:
 * the renderer only produces the effect's input for the region it will actually show
 * (it cannot know this shader reaches sideways), so a sample past the screen edge reads
 * transparent black and paints a hard, yellow-fringed cut into glass near the edge.
 * Clamping repeats the edge pixels instead, which reads as more glass.
 *
 * Every uniform is read somewhere: the shader compiler drops unused uniforms and setting
 * one that has been dropped throws.
 */
internal const val LIQUID_GLASS_AGSL = """
uniform shader content;

uniform float4 visible;
uniform float2 origin;
uniform float2 size;
uniform float radius;
uniform float bevel;
uniform float refraction;
uniform float zoom;
uniform float dispersion;
uniform float4 tint;
uniform float saturation;
uniform float lift;
uniform float highlight;
uniform float rimWidth;
uniform float2 light;
uniform float3 shadow;
uniform float4 touch;

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

// Outward normal. `r` here may be larger than the real corner radius: rounding the
// normal field more than the outline keeps the diagonal crease (where the nearest edge
// switches from the side to the top) out of the rim, where it would show as a seam.
float2 outwardNormal(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    float2 n;
    if (q.x > 0.0 && q.y > 0.0) {
        n = normalize(q);
    } else if (q.x > q.y) {
        n = float2(1.0, 0.0);
    } else {
        n = float2(0.0, 1.0);
    }
    return float2(p.x < 0.0 ? -n.x : n.x, p.y < 0.0 ? -n.y : n.y);
}

float4 sampleAt(float2 p) {
    return content.eval(clamp(p, visible.xy, visible.zw));
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 p = coord - origin - halfSize;
    float minHalf = min(halfSize.x, halfSize.y);
    float r = min(radius, minHalf);
    float d = sdRoundRect(p, halfSize, r);

    float4 below = float4(0.0);
    if (shadow.x > 0.0) {
        float ds = sdRoundRect(p - float2(0.0, shadow.z), halfSize, r);
        float s = clamp(1.0 - max(ds, 0.0) / shadow.y, 0.0, 1.0);
        below = float4(0.0, 0.0, 0.0, shadow.x * s * s);
    }

    float coverage = clamp(0.5 - d, 0.0, 1.0);
    if (coverage <= 0.0) {
        return half4(below);
    }

    float depth = -d;
    float rim = max(min(bevel, minHalf), 0.001);
    float t = clamp(1.0 - depth / rim, 0.0, 1.0);
    float bend = 1.0 - sqrt(1.0 - t * t);
    float2 n = outwardNormal(p, halfSize, max(r, rim));
    float2 offset = n * (bend * refraction);
    float2 base = origin + halfSize + p / zoom;

    float4 c = sampleAt(base + offset);
    if (dispersion > 0.0) {
        float2 spread = offset * dispersion;
        c.r = sampleAt(base + offset - spread).r;
        c.b = sampleAt(base + offset + spread).b;
    }

    float a = c.a;
    float3 rgb = a > 0.0 ? c.rgb / a : float3(0.0);

    float luma = dot(rgb, float3(0.2126, 0.7152, 0.0722));
    rgb = clamp(mix(float3(luma), rgb, saturation) + lift, 0.0, 1.0);
    rgb = mix(rgb, tint.rgb, tint.a);
    a = mix(a, 1.0, tint.a);

    float facing = dot(n, light);
    float lit = pow(max(facing, 0.0), 1.5) + 0.55 * pow(max(-facing, 0.0), 1.5);
    float line = 1.0 - smoothstep(0.0, rimWidth, depth);
    float spec = highlight * (line * (0.35 + 0.65 * lit) + 0.22 * bend * lit);
    if (touch.w > 0.0) {
        float2 dt = coord - touch.xy;
        spec += touch.w * 0.3 * exp(-dot(dt, dt) / (2.0 * touch.z * touch.z));
    }
    rgb = rgb + (1.0 - rgb) * clamp(spec, 0.0, 1.0);

    float4 glass = float4(rgb * a, a) * coverage;
    return half4(glass + below * (1.0 - coverage));
}
"""

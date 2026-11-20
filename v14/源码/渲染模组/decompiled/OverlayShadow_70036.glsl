#version 450

layout(binding = 0, std140) uniform cb45_struct
{
    vec4 _m0[45];
} cb0_0;

layout(binding = 1, std140) uniform cb123_struct
{
    vec4 _m0[123];
} cb1_0;

layout(binding = 2, std140) uniform cb5_struct
{
    vec4 _m0[5];
} cb2_0;

layout(binding = 3, std140) uniform cb2054_struct
{
    vec4 _m0[2054];
} cb3_0;

layout(binding = 4, std140) uniform cb4_struct
{
    vec4 _m0[4];
} cb4_0;

layout(binding = 5, std140) uniform cb5_0
{
    vec4 _m0[4];
} cb5_0_1;

layout(binding = 9) uniform usamplerBuffer t0;
uniform sampler2D SPIRV_Cross_Combinedt1s0;
uniform sampler2D SPIRV_Cross_Combinedt3s2;
uniform sampler2D SPIRV_Cross_Combinedt4SPIRV_Cross_DummySampler;
uniform sampler3D SPIRV_Cross_Combinedt2s1;

layout(location = 1) in vec2 v1;
layout(location = 2) in vec3 v2;
layout(location = 0) out vec4 o0;
vec4 v0;

void main()
{
    vec4 _57 = gl_FragCoord;
    _57.w = 1.0 / _57.w;
    v0 = vec4(_57.xyw.x, _57.xyw.y, v0.z, _57.xyw.z);
    vec2 _93 = v0.xy * vec2(0.5);
    vec4 r0;
    r0 = vec4(_93.x, _93.y, r0.z, r0.w);
    vec2 _99 = floor(r0.xy);
    r0 = vec4(_99.x, _99.y, r0.z, r0.w);
    r0.x = dot(r0.xy, vec2(0.067110560834407806396484375, 0.005837149918079376220703125));
    r0.x = fract(r0.x);
    r0.x *= 52.98291778564453125;
    r0.x = fract(r0.x);
    r0.y = uintBitsToFloat((cb2_0._m0[4u].x >= 0.0) ? 4294967295u : 0u);
    r0.y = (floatBitsToUint(r0.y) != 0u) ? r0.x : (-r0.x);
    r0.y = (-r0.y) + cb2_0._m0[4u].x;
    r0.z = isnan(cb2_0._m0[4u].y) ? cb2_0._m0[4u].z : (isnan(cb2_0._m0[4u].z) ? cb2_0._m0[4u].y : max(cb2_0._m0[4u].z, cb2_0._m0[4u].y));
    r0.x = (-r0.z) + (-r0.x);
    r0.x += 1.0;
    r0.x = isnan(r0.y) ? r0.x : (isnan(r0.x) ? r0.y : min(r0.x, r0.y));
    r0.x = uintBitsToFloat((r0.x < 0.0) ? 4294967295u : 0u);
    if (floatBitsToUint(r0.x) != 0u)
    {
        discard;
    }
    r0.x = uintBitsToFloat((0.5 < cb5_0_1._m0[0u].w) ? 4294967295u : 0u);
    if (floatBitsToUint(r0.x) != 0u)
    {
        vec2 _214 = v0.xy * cb1_0._m0[0u].zw;
        r0 = vec4(_214.x, _214.y, r0.z, r0.w);
        r0.x = textureLod(SPIRV_Cross_Combinedt1s0, r0.xyxx.xy, 0.0).x;
        r0.x = fma(cb1_0._m0[2u].z, r0.x, cb1_0._m0[2u].w);
        r0.x = 1.0 / r0.x;
        r0.x += (-v0.w);
        r0.x = (-abs(r0.x)) + cb5_0_1._m0[1u].x;
        r0.x = uintBitsToFloat((r0.x < 0.0) ? 4294967295u : 0u);
        if (floatBitsToUint(r0.x) != 0u)
        {
            discard;
        }
    }
    vec2 _273 = uintBitsToFloat(uvec2(v0.xy));
    r0 = vec4(_273.x, _273.y, r0.z, r0.w);
    vec4 r1 = texture(SPIRV_Cross_Combinedt3s2, v1.xyxx.xy, cb1_0._m0[26u].x);
    vec4 r2;
    r2.x = 1.0;
    r2.w = r1.x;
    r2 = (-r1) + r2.xxxw;
    r1 = fma(cb5_0_1._m0[0u].xxxx, r2, r1);
    r2 = r1 * cb5_0_1._m0[2u];
    vec2 _308 = vec2(floatBitsToUint(r0.xy));
    vec4 r3;
    r3 = vec4(_308.x, _308.y, r3.z, r3.w);
    vec2 _315 = r3.xy * vec2(0.03125);
    r3 = vec4(r3.x, r3.y, _315.x, _315.y);
    vec2 _320 = floor(r3.zw);
    r3 = vec4(r3.x, r3.y, _320.x, _320.y);
    r0.w = fma(r3.w, cb4_0._m0[2u].y, r3.z);
    r0.w *= 8.0;
    r0.w = intBitsToFloat(int(r0.w));
    r1.w = fma(-cb1_0._m0[3u].y, cb4_0._m0[3u].w, v0.w);
    r1.w = floor(r1.w);
    r3.z = (-1.0) + cb4_0._m0[2u].w;
    r3.w = isnan(0.0) ? r1.w : (isnan(r1.w) ? 0.0 : max(r1.w, 0.0));
    r3.z = isnan(r3.w) ? r3.z : (isnan(r3.z) ? r3.w : min(r3.z, r3.w));
    r3.w = r3.z * 8.0;
    r3.w = intBitsToFloat(int(r3.w));
    r1.w = uintBitsToFloat((r3.z >= r1.w) ? 4294967295u : 0u);
    r0.w = intBitsToFloat(floatBitsToInt(r0.w) + floatBitsToInt(cb4_0._m0[0u].z));
    r3.z = intBitsToFloat(floatBitsToInt(r3.w) + floatBitsToInt(cb4_0._m0[0u].w));
    vec4 r4;
    r4.w = 1.0;
    r3.w = 0.0;
    vec4 r5;
    r5.x = 0.0;
    vec4 r6;
    vec4 r7;
    vec4 r8;
    vec4 r9;
    vec4 r10;
    vec4 r11;
    vec4 r12;
    vec4 r13;
    vec4 r14;
    for (;;)
    {
        r5.y = uintBitsToFloat((7 < floatBitsToInt(r5.x)) ? 4294967295u : 0u);
        if (floatBitsToUint(r5.y) != 0u)
        {
            break;
        }
        r5.y = intBitsToFloat(floatBitsToInt(r0.w) + floatBitsToInt(r5.x));
        r5.y = uintBitsToFloat(texelFetch(t0, int((uint(floatBitsToInt(r5.y)) * 1u) + (uint(0) >> 2u))).x);
        r5.z = intBitsToFloat(floatBitsToInt(r3.z) + floatBitsToInt(r5.x));
        r5.z = uintBitsToFloat(texelFetch(t0, int((uint(floatBitsToInt(r5.z)) * 1u) + (uint(0) >> 2u))).x);
        r5.y = uintBitsToFloat(floatBitsToUint(r5.z) & floatBitsToUint(r5.y));
        r5.y = uintBitsToFloat(floatBitsToUint(r1.w) & floatBitsToUint(r5.y));
        r5.z = intBitsToFloat(floatBitsToInt(r5.x) << 5);
        r5.w = r3.w;
        r6.x = r5.y;
        for (;;)
        {
            if (floatBitsToUint(r6.x) == 0u)
            {
                break;
            }
            r6.y = uintBitsToFloat(uint(findLSB(floatBitsToUint(r6.x))));
            r6.z = intBitsToFloat(1 << floatBitsToInt(r6.y));
            r6.z = uintBitsToFloat(floatBitsToUint(r6.z) ^ floatBitsToUint(r6.x));
            r6.y = intBitsToFloat(floatBitsToInt(r5.z) + floatBitsToInt(r6.y));
            vec3 _567 = uintBitsToFloat(uvec3(bitfieldInsert(1u, floatBitsToUint(r6.y), int(3u & 31u), int(29u & 31u)), bitfieldInsert(5u, floatBitsToUint(r6.y), int(3u & 31u), int(29u & 31u)), bitfieldInsert(6u, floatBitsToUint(r6.y), int(3u & 31u), int(29u & 31u))));
            r7 = vec4(_567.x, _567.y, _567.z, r7.w);
            r6.w = uintBitsToFloat(uint(cb3_0._m0[uint(floatBitsToInt(r7.y)) + 6u].w));
            r6.w = uintBitsToFloat(floatBitsToUint(r6.w) & 1u);
            r6.w = uintBitsToFloat((floatBitsToInt(r6.w) == 1) ? 4294967295u : 0u);
            if (floatBitsToUint(r6.w) != 0u)
            {
                vec3 _608 = v2 + (-cb3_0._m0[uint(floatBitsToInt(r7.x)) + 6u].xyz);
                r4 = vec4(_608.x, _608.y, _608.z, r4.w);
                vec3 _622 = uintBitsToFloat(uvec3(65535u) & floatBitsToUint(cb3_0._m0[uint(floatBitsToInt(r7.y)) + 6u].xzy));
                r8 = vec4(_622.x, _622.y, _622.z, r8.w);
                vec3 _634 = uintBitsToFloat(uvec3(65535u) & floatBitsToUint(cb3_0._m0[uint(floatBitsToInt(r7.z)) + 6u].yxz));
                r9 = vec4(_634.x, _634.y, _634.z, r9.w);
                vec3 _648 = uintBitsToFloat(floatBitsToUint(cb3_0._m0[uint(floatBitsToInt(r7.y)) + 6u].xzy) >> uvec3(16u));
                r10 = vec4(_648.x, _648.y, _648.z, r10.w);
                vec3 _660 = uintBitsToFloat(floatBitsToUint(cb3_0._m0[uint(floatBitsToInt(r7.z)) + 6u].yxz) >> uvec3(16u));
                r11 = vec4(_660.x, _660.y, _660.z, r11.w);
                vec3 _678 = vec3(unpackHalf2x16(floatBitsToUint(r8.x)).x, unpackHalf2x16(floatBitsToUint(r8.y)).x, unpackHalf2x16(floatBitsToUint(r8.z)).x);
                r8 = vec4(_678.x, _678.y, _678.z, r8.w);
                vec3 _696 = vec3(unpackHalf2x16(floatBitsToUint(r9.x)).x, unpackHalf2x16(floatBitsToUint(r9.y)).x, unpackHalf2x16(floatBitsToUint(r9.z)).x);
                r9 = vec4(_696.x, _696.y, _696.z, r9.w);
                vec3 _714 = vec3(unpackHalf2x16(floatBitsToUint(r10.x)).x, unpackHalf2x16(floatBitsToUint(r10.y)).x, unpackHalf2x16(floatBitsToUint(r10.z)).x);
                r10 = vec4(_714.x, _714.y, _714.z, r10.w);
                vec3 _732 = vec3(unpackHalf2x16(floatBitsToUint(r11.y)).x, unpackHalf2x16(floatBitsToUint(r11.x)).x, unpackHalf2x16(floatBitsToUint(r11.z)).x);
                r11 = vec4(_732.x, _732.y, r11.z, _732.z);
                r12 = vec4(r8.x, r12.y, r8.z, r12.w);
                r12 = vec4(r12.x, r10.x, r12.z, r10.z);
                r6.w = dot(r4, r12);
                r10.x = r8.y;
                r10.z = r9.y;
                r10.w = r11.x;
                r7.y = dot(r4, r10);
                r11 = vec4(r9.x, r11.y, r9.z, r11.w);
                r4.x = dot(r4, r11);
                r4.y = uintBitsToFloat(bitfieldInsert(7u, floatBitsToUint(r6.y), int(3u & 31u), int(29u & 31u)));
                float _776 = abs(r6.w);
                float _779 = abs(r7.y);
                r4.z = isnan(_779) ? _776 : (isnan(_776) ? _779 : max(_776, _779));
                float _784 = abs(r4.x);
                r4.x = isnan(r4.z) ? _784 : (isnan(_784) ? r4.z : max(_784, r4.z));
                r4.z = fma(cb3_0._m0[uint(floatBitsToInt(r4.y)) + 6u].x, 0.5, 0.5);
                r4.x = (-r4.z) + r4.x;
                r4.y = fma(-cb3_0._m0[uint(floatBitsToInt(r4.y)) + 6u].x, 0.5, 0.5);
                float _819 = r4.x / r4.y;
                float _3154 = isnan(0.0) ? _819 : (isnan(_819) ? 0.0 : max(_819, 0.0));
                r4.x = isnan(1.0) ? _3154 : (isnan(_3154) ? 1.0 : min(_3154, 1.0));
                r4.x = (-r4.x) + 1.0;
                r4.x *= r4.x;
            }
            else
            {
                r4.x = 1.0;
            }
            r4.y = uintBitsToFloat((r4.x < 0.001000000047497451305389404296875) ? 4294967295u : 0u);
            if (floatBitsToUint(r4.y) != 0u)
            {
                r6.x = r6.z;
                continue;
            }
            r4.y = intBitsToFloat(floatBitsToInt(r6.y) << 3);
            r4.z = uintBitsToFloat(uint(cb3_0._m0[uint(floatBitsToInt(r4.y)) + 6u].w));
            r6.w = uintBitsToFloat((floatBitsToUint(r4.z) >= 4u) ? 4294967295u : 0u);
            if (floatBitsToUint(r6.w) != 0u)
            {
                r7.y = r5.w;
            }
            if (floatBitsToUint(r6.w) == 0u)
            {
                r4.y = uintBitsToFloat((cb3_0._m0[uint(floatBitsToInt(r4.y)) + 6u].w < 1.5) ? 4294967295u : 0u);
                if (floatBitsToUint(r4.y) != 0u)
                {
                    r4.y = uintBitsToFloat(bitfieldInsert(3u, floatBitsToUint(r6.y), int(3u & 31u), int(29u & 31u)));
                    r6.w = uintBitsToFloat((16 == floatBitsToInt(cb3_0._m0[uint(floatBitsToInt(r4.y)) + 6u].w)) ? 4294967295u : 0u);
                    r7.w = cb1_0._m0[122u].z + cb3_0._m0[uint(floatBitsToInt(r4.y)) + 6u].z;
                    r7.w = uintBitsToFloat((r7.w < 0.5) ? 4294967295u : 0u);
                    r6.w = uintBitsToFloat(floatBitsToUint(r6.w) | floatBitsToUint(r7.w));
                    if (floatBitsToUint(r6.w) == 0u)
                    {
                        vec2 _976 = uintBitsToFloat(uvec2(bitfieldInsert(2u, floatBitsToUint(r6.y), int(3u & 31u), int(29u & 31u)), bitfieldInsert(4u, floatBitsToUint(r6.y), int(3u & 31u), int(29u & 31u))));
                        r6 = vec4(r6.x, _976.x, r6.z, _976.y);
                        r4.z = uintBitsToFloat(floatBitsToUint(r4.z) & 1u);
                        r7.w = uintBitsToFloat((floatBitsToInt(r4.z) != 0) ? 4294967295u : 0u);
                        r8.x = uintBitsToFloat((0.0 < cb3_0._m0[uint(floatBitsToInt(r6.y)) + 6u].z) ? 4294967295u : 0u);
                        r7.w = uintBitsToFloat(floatBitsToUint(r7.w) & floatBitsToUint(r8.x));
                        r4.y = uintBitsToFloat((4 == floatBitsToInt(cb3_0._m0[uint(floatBitsToInt(r4.y)) + 6u].w)) ? 4294967295u : 0u);
                        r4.z = (floatBitsToUint(r4.z) != 0u) ? 0.0 : 1.0;
                        r8.x = fma(cb3_0._m0[uint(floatBitsToInt(r6.y)) + 6u].y, 0.5, 0.5);
                        r8.z = r8.x + (-abs(cb3_0._m0[uint(floatBitsToInt(r6.y)) + 6u].x));
                        r8.x = (-r8.z) + cb3_0._m0[uint(floatBitsToInt(r6.y)) + 6u].y;
                        r8.w = (-abs(r8.z)) + 1.0;
                        r8.w = (-abs(r8.x)) + r8.w;
                        r8.w = isnan(0.00048828125) ? r8.w : (isnan(r8.w) ? 0.00048828125 : max(r8.w, 0.00048828125));
                        r9.x = uintBitsToFloat((cb3_0._m0[uint(floatBitsToInt(r6.y)) + 6u].x >= 0.0) ? 4294967295u : 0u);
                        r8.y = (floatBitsToUint(r9.x) != 0u) ? r8.w : (-r8.w);
                        r8.w = dot(r8.xyz, r8.xyz);
                        r8.w = inversesqrt(r8.w);
                        vec3 _1120 = r8.www * r8.xyz;
                        r8 = vec4(_1120.x, _1120.y, _1120.z, r8.w);
                        r8.w = cb3_0._m0[uint(floatBitsToInt(r6.w)) + 6u].y + cb3_0._m0[uint(floatBitsToInt(r6.w)) + 6u].y;
                        r8.w = isnan(0.100000001490116119384765625) ? r8.w : (isnan(r8.w) ? 0.100000001490116119384765625 : max(r8.w, 0.100000001490116119384765625));
                        r9.x = uintBitsToFloat(floatBitsToUint(r4.y) & 1065353216u);
                        r8.w += (-cb3_0._m0[uint(floatBitsToInt(r7.z)) + 6u].w);
                        r7.z = fma(r9.x, r8.w, cb3_0._m0[uint(floatBitsToInt(r7.z)) + 6u].w);
                        vec3 _1185 = (-v2) + cb3_0._m0[uint(floatBitsToInt(r7.x)) + 6u].xyz;
                        r9 = vec4(_1185.x, _1185.y, _1185.z, r9.w);
                        r8.w = dot(r9.yzx, -r8.xyz);
                        r9.w = uintBitsToFloat((0.5 < cb3_0._m0[uint(floatBitsToInt(r6.w)) + 6u].z) ? 4294967295u : 0u);
                        r9.w = uintBitsToFloat(floatBitsToUint(r4.y) & floatBitsToUint(r9.w));
                        r9.w = uintBitsToFloat(floatBitsToUint(r9.w) & 1065353216u);
                        r9.w = r4.z * r9.w;
                        vec3 _1235 = fma(-r8.zxy, r8.www, -r9.xyz);
                        r10 = vec4(_1235.x, _1235.y, _1235.z, r10.w);
                        vec3 _1244 = fma(r9.www, r10.xyz, r9.xyz);
                        r9 = vec4(_1244.x, _1244.y, _1244.z, r9.w);
                        r8.w = dot(r9.xyz, r9.xyz);
                        r9.w = inversesqrt(r8.w);
                        vec3 _1261 = r9.www * r9.xyz;
                        r10 = vec4(_1261.x, _1261.y, _1261.z, r10.w);
                        if (floatBitsToUint(r7.w) != 0u)
                        {
                            vec3 _1279 = r8.zxy * cb3_0._m0[uint(floatBitsToInt(r6.y)) + 6u].zzz;
                            r11 = vec4(_1279.x, _1279.y, _1279.z, r11.w);
                            vec3 _1288 = fma(-r11.xyz, vec3(0.5), r9.xyz);
                            r12 = vec4(_1288.x, _1288.y, _1288.z, r12.w);
                            vec3 _1295 = fma(r11.xyz, vec3(0.5), r9.xyz);
                            r11 = vec4(_1295.x, _1295.y, _1295.z, r11.w);
                            r9.w = dot(r12.xyz, r12.xyz);
                            r9.w = sqrt(r9.w);
                            r10.w = dot(r11.xyz, r11.xyz);
                            r10.w = sqrt(r10.w);
                            vec3 _1322 = r8.xyz * r10.xyz;
                            r13 = vec4(_1322.x, _1322.y, _1322.z, r13.w);
                            vec3 _1332 = fma(r8.zxy, r10.yzx, -r13.xyz);
                            r13 = vec4(_1332.x, _1332.y, _1332.z, r13.w);
                            vec3 _1339 = r8.xyz * r13.xyz;
                            r14 = vec4(_1339.x, _1339.y, _1339.z, r14.w);
                            vec3 _1349 = fma(r13.zxy, r8.yzx, -r14.xyz);
                            r13 = vec4(_1349.x, _1349.y, _1349.z, r13.w);
                            r11.w = dot(r13.xyz, r13.xyz);
                            r11.w = inversesqrt(r11.w);
                            vec3 _1366 = r11.www * r13.xyz;
                            r10 = vec4(_1366.x, _1366.y, _1366.z, r10.w);
                            r11.w = dot(r12.xyz, r11.xyz);
                            r11.w = fma(r9.w, r10.w, r11.w);
                            r11.w = fma(r11.w, 0.5, 1.0);
                            r11.w = 1.0 / r11.w;
                            r12.x = dot(r10.xyz, r12.xyz);
                            r9.w = r12.x / r9.w;
                            r11.x = dot(r10.xyz, r11.xyz);
                            r10.w = r11.x / r10.w;
                            r9.w += r10.w;
                            float _1423 = r9.w * 0.5;
                            float _3175 = isnan(0.0) ? _1423 : (isnan(_1423) ? 0.0 : max(_1423, 0.0));
                            r9.w = isnan(1.0) ? _3175 : (isnan(_3175) ? 1.0 : min(_3175, 1.0));
                            r9.w *= r11.w;
                        }
                        else
                        {
                            r9.w = 1.0;
                        }
                        r10.w = uintBitsToFloat((r7.z < 0.0) ? 4294967295u : 0u);
                        if (floatBitsToUint(r10.w) != 0u)
                        {
                            r10.w = cb3_0._m0[uint(floatBitsToInt(r7.x)) + 6u].w * cb3_0._m0[uint(floatBitsToInt(r7.x)) + 6u].w;
                            r10.w = r8.w * r10.w;
                            r10.w = fma(-r10.w, r10.w, 1.0);
                            r10.w = isnan(0.0) ? r10.w : (isnan(r10.w) ? 0.0 : max(r10.w, 0.0));
                            r8.w += 1.0;
                            r8.w = 1.0 / r8.w;
                            r7.w = uintBitsToFloat(floatBitsToUint(r7.w) & 1065353216u);
                            r11.x = (-r8.w) + r9.w;
                            r7.w = fma(r7.w, r11.x, r8.w);
                            r8.w = r10.w * r10.w;
                            r7.w *= r8.w;
                        }
                        else
                        {
                            vec3 _1530 = r9.xyz * cb3_0._m0[uint(floatBitsToInt(r7.x)) + 6u].www;
                            r9 = vec4(_1530.x, _1530.y, _1530.z, r9.w);
                            r7.x = dot(r9.xyz, r9.xyz);
                            r7.x = isnan(1.0) ? r7.x : (isnan(r7.x) ? 1.0 : min(r7.x, 1.0));
                            r7.x = (-r7.x) + 1.0;
                            r7.x = log2(r7.x);
                            r7.x *= r7.z;
                            r7.x = exp2(r7.x);
                            r7.w = r7.x * r9.w;
                        }
                        r7.x = dot(r10.yzx, -r8.xyz);
                        r7.x += (-cb3_0._m0[uint(floatBitsToInt(r6.y)) + 6u].z);
                        float _1596 = r7.x * cb3_0._m0[uint(floatBitsToInt(r6.y)) + 6u].w;
                        float _3196 = isnan(0.0) ? _1596 : (isnan(_1596) ? 0.0 : max(_1596, 0.0));
                        r6.y = isnan(1.0) ? _3196 : (isnan(_3196) ? 1.0 : min(_3196, 1.0));
                        r6.y = fma(r6.y, r6.y, -1.0);
                        r4.z = fma(r4.z, r6.y, 1.0);
                        r4.z *= r7.w;
                        r4.x *= r4.z;
                        r4.z = uintBitsToFloat((9.9999997473787516355514526367188e-05 < r4.x) ? 4294967295u : 0u);
                        if (floatBitsToUint(r4.z) != 0u)
                        {
                            if (floatBitsToUint(r4.y) != 0u)
                            {
                                float _1653 = fma(r4.x, cb3_0._m0[uint(floatBitsToInt(r6.w)) + 6u].x, r5.w);
                                float _3207 = isnan(0.0) ? _1653 : (isnan(_1653) ? 0.0 : max(_1653, 0.0));
                                r5.w = isnan(1.0) ? _3207 : (isnan(_3207) ? 1.0 : min(_3207, 1.0));
                            }
                        }
                    }
                }
            }
            else
            {
                r5.w = r7.y;
            }
            r6.x = r6.z;
            continue;
        }
        r3.w = r5.w;
        r5.x = intBitsToFloat(floatBitsToInt(r5.x) + 1);
        continue;
    }
    vec2 _1676 = intBitsToFloat(ivec2(v0.xy));
    r4 = vec4(_1676.x, _1676.y, r4.z, r4.w);
    r4 = vec4(r4.x, r4.y, vec2(0.0).x, vec2(0.0).y);
    r0.w = texelFetch(SPIRV_Cross_Combinedt4SPIRV_Cross_DummySampler, floatBitsToInt(r4.xy), floatBitsToInt(r4.w)).w;
    r0.w = uintBitsToFloat((r0.w == 1.0) ? 4294967295u : 0u);
    r1.w = (-r3.w) + 1.0;
    r1.w *= r2.w;
    r4.w = (floatBitsToUint(r0.w) != 0u) ? 0.0 : r1.w;
    r0.w = uintBitsToFloat((cb1_0._m0[122u].w < 0.5) ? 4294967295u : 0u);
    if (floatBitsToUint(r0.w) != 0u)
    {
        r0.w = uintBitsToFloat((0.0 == cb1_0._m0[4u].w) ? 4294967295u : 0u);
        vec3 _1743 = (-v2) + cb0_0._m0[44u].xyz;
        r5 = vec4(_1743.x, _1743.y, _1743.z, r5.w);
        r6.x = cb0_0._m0[0u].z;
        r6.y = cb0_0._m0[1u].z;
        r6.z = cb0_0._m0[2u].z;
        vec3 _1768 = mix(r6.xyz, r5.xyz, notEqual(floatBitsToUint(r0.www), uvec3(0u)));
        r5 = vec4(_1768.x, _1768.y, _1768.z, r5.w);
        r0.w = dot(-r5.xyz, -r5.xyz);
        r1.w = isnan(9.9999999392252902907785028219223e-09) ? r0.w : (isnan(r0.w) ? 9.9999999392252902907785028219223e-09 : max(r0.w, 9.9999999392252902907785028219223e-09));
        r1.w = inversesqrt(r1.w);
        r2.w = r0.w * r1.w;
        vec3 _1799 = r1.www * (-r5.xyz);
        r5 = vec4(_1799.x, _1799.y, _1799.z, r5.w);
        r3.z = fma(v2.y, cb1_0._m0[74u].w, cb1_0._m0[75u].w);
        r3.w = fma(r2.w, cb1_0._m0[72u].w, -cb1_0._m0[71u].w);
        vec2 _1832 = mix(mix(max(r3.zw, vec2(0.00999999977648258209228515625, 0.0)), vec2(0.00999999977648258209228515625, 0.0), isnan(r3.zw)), r3.zw, isnan(vec2(0.00999999977648258209228515625, 0.0)));
        r3 = vec4(r3.x, r3.y, _1832.x, _1832.y);
        r5.w = r3.z * (-1.44269502162933349609375);
        r5.w = exp2(r5.w);
        r5.w = (-r5.w) + 1.0;
        r3.z = r5.w / r3.z;
        r5.w = fma(v2.y, cb1_0._m0[74u].w, cb1_0._m0[76u].w);
        r5.w *= 1.44269502162933349609375;
        r5.w = exp2(r5.w);
        r3.z *= r5.w;
        r3.z *= (-r3.w);
        vec3 _1894 = r3.zzz * cb1_0._m0[73u].xyz;
        r7 = vec4(_1894.x, _1894.y, _1894.z, r7.w);
        vec3 _1900 = r7.xyz * vec3(1.44269502162933349609375);
        r7 = vec4(_1900.x, _1900.y, _1900.z, r7.w);
        vec3 _1905 = exp2(r7.xyz);
        r7 = vec4(_1905.x, _1905.y, _1905.z, r7.w);
        r3.z = dot(r5.xyz, cb1_0._m0[72u].xyz);
        r3.w = fma(cb1_0._m0[73u].w, cb1_0._m0[73u].w, 1.0);
        r5.w = dot(r3.zz, cb1_0._m0[73u].ww);
        r3.w += (-r5.w);
        r5.w = uintBitsToFloat((0.0 < cb1_0._m0[83u].z) ? 4294967295u : 0u);
        if (floatBitsToUint(r5.w) != 0u)
        {
            r0.z = uintBitsToFloat(7u & floatBitsToUint(cb1_0._m0[26u].w));
            vec3 _1968 = intBitsToFloat((floatBitsToInt(r0.xyz) * ivec3(1664525)) + ivec3(1013904223));
            r0 = vec4(_1968.x, _1968.y, _1968.z, r0.w);
            r0.x = intBitsToFloat((floatBitsToInt(r0.y) * floatBitsToInt(r0.z)) + floatBitsToInt(r0.x));
            r0.y = intBitsToFloat((floatBitsToInt(r0.z) * floatBitsToInt(r0.x)) + floatBitsToInt(r0.y));
            r0.z = intBitsToFloat((floatBitsToInt(r0.x) * floatBitsToInt(r0.y)) + floatBitsToInt(r0.z));
            r8.x = intBitsToFloat((floatBitsToInt(r0.y) * floatBitsToInt(r0.z)) + floatBitsToInt(r0.x));
            r0.x = dot(r5.xyz, -r6.xyz);
            r5.w = v2.y + (-cb0_0._m0[44u].y);
            r6.x = uintBitsToFloat((5.9604644775390625e-08 < r0.x) ? 4294967295u : 0u);
            r0.x = 1.0 / r0.x;
            r0.x = uintBitsToFloat(floatBitsToUint(r0.x) & floatBitsToUint(r6.x));
            r0.x *= cb1_0._m0[83u].w;
            r6.x = 1.0 / r2.w;
            r6.y = r0.x * r6.x;
            r6.z = fma(r6.y, r5.w, cb0_0._m0[44u].y);
            r5.w = fma(-r6.y, r5.w, r5.w);
            r0.x = fma(-r0.x, r6.x, 1.0);
            r6.x = r2.w * r0.x;
            r6.y = r5.w * cb1_0._m0[77u].z;
            r5.w *= cb1_0._m0[80u].x;
            r5.w = isnan(-127.0) ? r5.w : (isnan(r5.w) ? (-127.0) : max(r5.w, -127.0));
            r6.w = r6.z + (-cb1_0._m0[77u].x);
            r6.w *= cb1_0._m0[77u].z;
            vec2 _2145 = mix(mix(max(r6.yw, vec2(-127.0)), vec2(-127.0), isnan(r6.yw)), r6.yw, isnan(vec2(-127.0)));
            r6 = vec4(r6.x, _2145.x, r6.z, _2145.y);
            r6.w = exp2(-r6.w);
            r6.w *= cb1_0._m0[77u].y;
            r7.w = uintBitsToFloat((5.9604644775390625e-08 < abs(r6.y)) ? 4294967295u : 0u);
            r8.z = exp2(-r6.y);
            r8.z = (-r8.z) + 1.0;
            r8.z /= r6.y;
            r6.y = fma(-r6.y, 0.2402265071868896484375, 0.693147182464599609375);
            r6.y = (floatBitsToUint(r7.w) != 0u) ? r8.z : r6.y;
            r6.z += (-cb1_0._m0[80u].z);
            r6.z *= cb1_0._m0[80u].x;
            r6.z = isnan(-127.0) ? r6.z : (isnan(r6.z) ? (-127.0) : max(r6.z, -127.0));
            r6.z = exp2(-r6.z);
            r6.z *= cb1_0._m0[80u].y;
            r7.w = uintBitsToFloat((5.9604644775390625e-08 < abs(r5.w)) ? 4294967295u : 0u);
            r8.z = exp2(-r5.w);
            r8.z = (-r8.z) + 1.0;
            r8.z /= r5.w;
            r5.w = fma(-r5.w, 0.2402265071868896484375, 0.693147182464599609375);
            r5.w = (floatBitsToUint(r7.w) != 0u) ? r8.z : r5.w;
            r5.w *= r6.z;
            r5.w = fma(r6.w, r6.y, r5.w);
            vec2 _2292 = fma(r2.ww, cb1_0._m0[78u].wy, cb1_0._m0[78u].zx);
            vec2 _3243 = mix(mix(max(_2292, vec2(0.0)), vec2(0.0), isnan(_2292)), _2292, isnan(vec2(0.0)));
            vec2 _2294 = mix(mix(min(_3243, vec2(1.0)), vec2(1.0), isnan(_3243)), _3243, isnan(vec2(1.0)));
            r6 = vec4(r6.x, _2294.x, _2294.y, r6.w);
            r6.x *= r5.w;
            r6.x = exp2(-r6.x);
            r6.x = isnan(1.0) ? r6.x : (isnan(r6.x) ? 1.0 : min(r6.x, 1.0));
            r6.x = isnan(cb1_0._m0[79u].w) ? r6.x : (isnan(r6.x) ? cb1_0._m0[79u].w : max(r6.x, cb1_0._m0[79u].w));
            r6.x = r6.z + r6.x;
            r6.x = r6.y + r6.x;
            r6.x = isnan(1.0) ? r6.x : (isnan(r6.x) ? 1.0 : min(r6.x, 1.0));
            r8.y = intBitsToFloat((floatBitsToInt(r0.z) * floatBitsToInt(r8.x)) + floatBitsToInt(r0.y));
            vec2 _2354 = uintBitsToFloat(floatBitsToUint(r8.xy) >> uvec2(16u));
            r0 = vec4(r0.x, _2354.x, _2354.y, r0.w);
            vec2 _2360 = vec2(floatBitsToUint(r0.yz));
            r0 = vec4(r0.x, _2360.x, _2360.y, r0.w);
            vec2 _2368 = fma(r0.yz, vec2(3.05180437862873077392578125e-05), vec2(-1.0));
            r0 = vec4(r0.x, _2368.x, _2368.y, r0.w);
            vec2 _2379 = fma(r0.yz, cb1_0._m0[87u].ww, r3.xy);
            r0 = vec4(r0.x, _2379.x, _2379.y, r0.w);
            vec2 _2388 = r0.yz * cb1_0._m0[85u].xy;
            r8 = vec4(_2388.x, _2388.y, r8.z, r8.w);
            r0.y = fma(v0.w, cb1_0._m0[84u].x, cb1_0._m0[84u].y);
            r0.y = log2(r0.y);
            r0.y *= cb1_0._m0[84u].z;
            r8.z = r0.y / cb1_0._m0[83u].z;
            r8 = textureLod(SPIRV_Cross_Combinedt2s1, r8.xyzx.xyz, 0.0);
            r0.y = v0.w + (-cb1_0._m0[86u].z);
            float _2439 = r0.y * 1000000.0;
            float _3269 = isnan(0.0) ? _2439 : (isnan(_2439) ? 0.0 : max(_2439, 0.0));
            r0.y = isnan(1.0) ? _3269 : (isnan(_3269) ? 1.0 : min(_3269, 1.0));
            r8 += vec4(-0.0, -0.0, -0.0, -1.0);
            r8 = fma(r0.yyyy, r8, vec4(0.0, 0.0, 0.0, 1.0));
            r0.y = (-r6.x) + 1.0;
            float _2463 = dot(-r5.xyz, cb1_0._m0[81u].xyz);
            float _3280 = isnan(0.0) ? _2463 : (isnan(_2463) ? 0.0 : max(_2463, 0.0));
            r0.z = isnan(1.0) ? _3280 : (isnan(_3280) ? 1.0 : min(_3280, 1.0));
            r0.z = log2(r0.z);
            r0.z *= cb1_0._m0[82u].w;
            r0.z = exp2(r0.z);
            vec3 _2487 = r0.zzz * cb1_0._m0[82u].xyz;
            r9 = vec4(_2487.x, _2487.y, _2487.z, r9.w);
            r0.x = fma(r0.x, r2.w, -cb1_0._m0[81u].w);
            r0.x = isnan(0.0) ? r0.x : (isnan(r0.x) ? 0.0 : max(r0.x, 0.0));
            r0.x *= r5.w;
            r0.x = exp2(-r0.x);
            r0.x = isnan(1.0) ? r0.x : (isnan(r0.x) ? 1.0 : min(r0.x, 1.0));
            r0.x = (-r0.x) + 1.0;
            vec3 _2528 = r0.xxx * r9.xyz;
            r9 = vec4(_2528.x, _2528.y, _2528.z, r9.w);
            r0.x = (-r6.y) + 1.0;
            vec3 _2540 = r0.xxx * r9.xyz;
            r6 = vec4(r6.x, _2540.x, _2540.y, _2540.z);
            vec3 _2550 = fma(cb1_0._m0[79u].xyz, r0.yyy, r6.yzw);
            r0 = vec4(_2550.x, _2550.y, _2550.z, r0.w);
            vec3 _2559 = fma(r0.xyz, r8.www, r8.xyz);
            r0 = vec4(_2559.x, _2559.y, _2559.z, r0.w);
            r3.x = r6.x * r8.w;
        }
        else
        {
            r3.y = v2.y + (-cb0_0._m0[44u].y);
            r5.w = r3.y * cb1_0._m0[77u].z;
            r5.w = isnan(-127.0) ? r5.w : (isnan(r5.w) ? (-127.0) : max(r5.w, -127.0));
            r3.y *= cb1_0._m0[80u].x;
            r3.y = isnan(-127.0) ? r3.y : (isnan(r3.y) ? (-127.0) : max(r3.y, -127.0));
            r6.x = cb0_0._m0[44u].y + (-cb1_0._m0[77u].x);
            r6.x *= cb1_0._m0[77u].z;
            r6.x = isnan(-127.0) ? r6.x : (isnan(r6.x) ? (-127.0) : max(r6.x, -127.0));
            r6.x = exp2(-r6.x);
            r6.x *= cb1_0._m0[77u].y;
            r6.y = uintBitsToFloat((5.9604644775390625e-08 < abs(r5.w)) ? 4294967295u : 0u);
            r6.z = exp2(-r5.w);
            r6.z = (-r6.z) + 1.0;
            r6.z /= r5.w;
            r5.w = fma(-r5.w, 0.2402265071868896484375, 0.693147182464599609375);
            r5.w = (floatBitsToUint(r6.y) != 0u) ? r6.z : r5.w;
            r6.y = cb0_0._m0[44u].y + (-cb1_0._m0[80u].z);
            r6.y *= cb1_0._m0[80u].x;
            r6.y = isnan(-127.0) ? r6.y : (isnan(r6.y) ? (-127.0) : max(r6.y, -127.0));
            r6.y = exp2(-r6.y);
            r6.y *= cb1_0._m0[80u].y;
            r6.z = uintBitsToFloat((5.9604644775390625e-08 < abs(r3.y)) ? 4294967295u : 0u);
            r6.w = exp2(-r3.y);
            r6.w = (-r6.w) + 1.0;
            r6.w /= r3.y;
            r3.y = fma(-r3.y, 0.2402265071868896484375, 0.693147182464599609375);
            r3.y = (floatBitsToUint(r6.z) != 0u) ? r6.w : r3.y;
            r3.y *= r6.y;
            r3.y = fma(r6.x, r5.w, r3.y);
            vec2 _2761 = fma(r2.ww, cb1_0._m0[78u].wy, cb1_0._m0[78u].zx);
            vec2 _3321 = mix(mix(max(_2761, vec2(0.0)), vec2(0.0), isnan(_2761)), _2761, isnan(vec2(0.0)));
            vec2 _2762 = mix(mix(min(_3321, vec2(1.0)), vec2(1.0), isnan(_3321)), _3321, isnan(vec2(1.0)));
            r6 = vec4(_2762.x, _2762.y, r6.z, r6.w);
            r2.w *= r3.y;
            r2.w = exp2(-r2.w);
            r2.w = isnan(1.0) ? r2.w : (isnan(r2.w) ? 1.0 : min(r2.w, 1.0));
            r2.w = isnan(cb1_0._m0[79u].w) ? r2.w : (isnan(r2.w) ? cb1_0._m0[79u].w : max(r2.w, cb1_0._m0[79u].w));
            r2.w = r6.y + r2.w;
            r2.w = r6.x + r2.w;
            r3.x = isnan(1.0) ? r2.w : (isnan(r2.w) ? 1.0 : min(r2.w, 1.0));
            r2.w = (-r3.x) + 1.0;
            float _2814 = dot(-r5.xyz, cb1_0._m0[81u].xyz);
            float _3347 = isnan(0.0) ? _2814 : (isnan(_2814) ? 0.0 : max(_2814, 0.0));
            r5.x = isnan(1.0) ? _3347 : (isnan(_3347) ? 1.0 : min(_3347, 1.0));
            r5.x = log2(r5.x);
            r5.x *= cb1_0._m0[82u].w;
            r5.x = exp2(r5.x);
            vec3 _2837 = r5.xxx * cb1_0._m0[82u].xyz;
            r5 = vec4(_2837.x, _2837.y, _2837.z, r5.w);
            r0.w = fma(r0.w, r1.w, -cb1_0._m0[81u].w);
            r0.w = isnan(0.0) ? r0.w : (isnan(r0.w) ? 0.0 : max(r0.w, 0.0));
            r0.w *= r3.y;
            r0.w = exp2(-r0.w);
            r0.w = isnan(1.0) ? r0.w : (isnan(r0.w) ? 1.0 : min(r0.w, 1.0));
            r0.w = (-r0.w) + 1.0;
            vec3 _2878 = r0.www * r5.xyz;
            r5 = vec4(_2878.x, _2878.y, _2878.z, r5.w);
            r0.w = (-r6.x) + 1.0;
            vec3 _2890 = r0.www * r5.xyz;
            r5 = vec4(_2890.x, _2890.y, _2890.z, r5.w);
            vec3 _2900 = fma(cb1_0._m0[79u].xyz, r2.www, r5.xyz);
            r0 = vec4(_2900.x, _2900.y, _2900.z, r0.w);
        }
        vec3 _2907 = r3.xxx * r7.xyz;
        r5 = vec4(_2907.x, _2907.y, _2907.z, r5.w);
        r0.w = dot(r5.xyz, vec3(0.3333333432674407958984375));
        r1.w = fma(r3.z, r3.z, 1.0);
        r1.w *= 0.0596831031143665313720703125;
        vec3 _2935 = fma(cb1_0._m0[74u].xyz, r1.www, cb1_0._m0[76u].xyz);
        r5 = vec4(_2935.x, _2935.y, _2935.z, r5.w);
        r1.w = fma(-cb1_0._m0[73u].w, cb1_0._m0[73u].w, 1.0);
        r2.w = r3.w * 12.56637096405029296875;
        r3.y = sqrt(r3.w);
        r2.w *= r3.y;
        r2.w = isnan(0.001000000047497451305389404296875) ? r2.w : (isnan(r2.w) ? 0.001000000047497451305389404296875 : max(r2.w, 0.001000000047497451305389404296875));
        r1.w /= r2.w;
        vec3 _2979 = fma(cb1_0._m0[75u].xyz, r1.www, r5.xyz);
        vec3 _3373 = mix(mix(max(_2979, vec3(0.0)), vec3(0.0), isnan(_2979)), _2979, isnan(vec3(0.0)));
        vec3 _2982 = mix(mix(min(_3373, vec3(1.0)), vec3(1.0), isnan(_3373)), _3373, isnan(vec3(1.0)));
        r3 = vec4(r3.x, _2982.x, _2982.y, _2982.z);
        vec3 _2989 = r3.yzw * vec3(255.0);
        r3 = vec4(r3.x, _2989.x, _2989.y, _2989.z);
        vec3 _2995 = (-r7.xyz) + vec3(1.0);
        r5 = vec4(_2995.x, _2995.y, _2995.z, r5.w);
        vec3 _3002 = r3.yzw * r5.xyz;
        r3 = vec4(r3.x, _3002.x, _3002.y, _3002.z);
        vec3 _3011 = fma(r3.yzw, r3.xxx, r0.xyz);
        r0 = vec4(_3011.x, _3011.y, _3011.z, r0.w);
        float _3016 = dot(r0.xyz, vec3(0.3333333432674407958984375));
        float _3384 = isnan(0.0) ? _3016 : (isnan(_3016) ? 0.0 : max(_3016, 0.0));
        r0.x = isnan(1.0) ? _3384 : (isnan(_3384) ? 1.0 : min(_3384, 1.0));
        r0.x = (-r0.x) + 1.0;
        r0.x *= r0.w;
        r4.w = r0.x * r4.w;
    }
    r0.x = cb5_0_1._m0[0u].z * cb5_0_1._m0[3u].w;
    vec3 _3053 = fma(-r1.xyz, cb5_0_1._m0[2u].xyz, cb5_0_1._m0[3u].xyz);
    r0 = vec4(r0.x, _3053.x, _3053.y, _3053.z);
    vec3 _3062 = fma(r0.xxx, r0.yzw, r2.xyz);
    r0 = vec4(_3062.x, _3062.y, _3062.z, r0.w);
    r0.w = r4.w * cb5_0_1._m0[2u].w;
    r1.x = 1.0 + (-cb5_0_1._m0[0u].z);
    r1.y = 1.0 + (-cb5_0_1._m0[3u].w);
    r1.x = fma(r1.y, cb5_0_1._m0[0u].z, r1.x);
    r0.w *= r1.x;
    vec3 _3102 = r0.xyz + vec3(-1.0);
    r0 = vec4(_3102.x, _3102.y, _3102.z, r0.w);
    vec3 _3109 = fma(r0.www, r0.xyz, vec3(1.0));
    r4 = vec4(_3109.x, _3109.y, _3109.z, r4.w);
    o0 = r4;
}


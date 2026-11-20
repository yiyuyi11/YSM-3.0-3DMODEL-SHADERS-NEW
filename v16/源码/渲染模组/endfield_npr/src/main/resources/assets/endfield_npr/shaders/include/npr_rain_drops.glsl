// =====================================================================================================
// Ours, not the game's: per-drop density, jitter, speed and sliding of the rain drops (0.8.6, 0.8.7). Imported by npr_rain.glsl
// before npr_rain_asm.glsl, whose drop blocks (rainSkinDrops, rainHairDrops, rainClothWet) call these hooks at the
// lines rain_gen.py marks `// hook`. Each procedural block runs six drop lookups k (3 triplanar planes x 2 scales); a
// lookup's drop lives in one grid cell of the object position and keeps the randoms of its cell id (rainDropCell),
// so a drop never flickers and is culled, moved or resized whole:
//   rainDropKeep   drawn only if its random < rainDropDensity (on top of the game's own wetness threshold)
//   rainDropSize   x [1 - 0.3 j, 1 + 0.3 j], j = rainDropJitter
//   rainDropShift  the game's offset (+-0.25 cell) blended toward a random one over the whole room the drop's radius
//                  leaves in its cell (j > 0 also keeps every drop inside its cell: no half-cut drops at the borders)
//   rainDropPhase  the drop's appear/fade cycle at x [1 - s, 1 + s] of the block's speed, s = rainDropSpeedJitter
//                  (falling rain only: the static drops after rain keep the game's wetness-driven phase)
// Sliding (0.8.7, rainDropSlide > 0, falling rain only). The game's drops never move: the grid coordinate has no time
// term, time only runs each drop's appear/fade cycle (phase), and its fade-out flattens the drop's bump (the normal
// tilt x the visibility), so the glint drops out abruptly while the drop is still half visible. Here:
//   rainDropScroll the cell grid of the two vertical planes ((x, y), (z, y)) moves down object y (the anchored body
//                  frame) at rainDropSlide blocks/s x a random factor of the column [1 - s, 1 + s]: each drop rides in
//                  its own cell, so it never crosses a cell border and is never cut; columns differ in speed. The
//                  horizontal plane's drops (top faces) stay where they are.
//   rainDropLife   the drop's life is the game's visible window of the cycle (phase 0.2-0.85): it grows from a point
//                  over the first 12 % and shrinks back to nothing over the last half (smoothsteps of its size and
//                  radius, so the bump, the darkening and the glint keep their shape and fade with the area);
//   rainDropVis    the visibility is 1 over the life instead of the game's quick pop-in and flattening fade-out.
// The texture drops and streaks of the cloth (rain_01 B drops + RG normal, rain_02 B streaks + RG normal) are
// thinned by a low-frequency noise mask in rainTex (rainTexKeep), off the tile grid, so each copy of the tile shows
// another subset. At the neutral values (density 1, jitter 0, speed jitter 0, slide 0) every hook is the identity:
// tools/rain/rain_check.py runs the blocks with them against the asm.
// =====================================================================================================

uniform vec4 RainDrops;  // x rainDropDensity, y rainDropJitter, z rainDropSpeedJitter, w rainDropSlide (NprConfig, live)

vec4 rainDropA[6];       // per lookup: x keep, y speed, zw position randoms of the drop's cell
float rainDropB[6];      // per lookup: size random
float rainDropS[6];      // per lookup: the scroll of its column, in cells (vertical planes only)
float rainDropEnv[6];    // per lookup: the life envelope of the drop's size (1 unless sliding)
float rainDropT;         // time x speed of the phase line being hooked (read before the line overwrites it)
vec2 rainDropD;          // the offset change of the offset line being hooked
float rainDropAnim = 1.0; // 1 in falling rain (the phase runs on time), 0 for the static drops after rain
float rainDropClock = 0.0; // time in s (RainParams.y), set by rainSurface

// hash of a 2D lattice point -> 4 randoms in [0, 1) (Dave Hoskins' hash42, no sin)
vec4 rainHash42(vec2 p) {
    vec4 p4 = fract(p.xyxy * vec4(0.1031, 0.1030, 0.0973, 0.1099));
    p4 += dot(p4, p4.wzxy + 33.33);
    return fract((p4.xxyz + p4.yzzw) * p4.zywx);
}

void rainDropCell(int k, vec2 cell) {
    vec2 p = cell + float(k) * vec2(127.0, 311.0);
    rainDropA[k] = rainHash42(p);
    rainDropB[k] = rainHash42(p + vec2(1013.0, 2027.0)).x;
}

float rainDropKeep(int k, float x) {
    return rainDropA[k].x < RainDrops.x ? x : -2.0; // -2: below every threshold of the blocks (>= 0)
}

float rainDropSize(int k) {
    return 1.0 + RainDrops.y * (rainDropB[k] - 0.5) * 0.6;
}

// h: the game's offset / 0.25 (hC x 2 - 1); radius: the drop's (the `size x 0.25` line). The drop reaches radius /
// 0.75 from its centre at most (the tail along gravity, the blocks' 1.25 / 0.75 metric).
vec2 rainDropShift(int k, vec2 h, float radius) {
    float j = RainDrops.y;
    if (j <= 0.0) {
        return vec2(0.0);
    }
    vec2 game = h * 0.25;
    float room = max(0.48 - radius * 1.3333334, 0.0);
    vec2 want = (rainDropA[k].zw * 2.0 - 1.0) * room;
    return clamp(mix(game, want, min(j, 1.0)), -room, room) - game;
}

float rainDropPhase(int k) {
    return rainDropT * (rainDropAnim * RainDrops.z * (rainDropA[k].y * 2.0 - 1.0));
}

bool rainDropSliding() {
    return RainDrops.w > 0.0 && rainDropAnim > 0.0;
}

// Scroll (cells) of lookup k's cell column (the cell's horizontal coordinate) after rainDropClock seconds:
// rainDropSlide blocks/s x cells per block (the block's grid scale x rainTiling) x the column's random speed factor.
// The block re-floors the cell's y at y + scroll and adds the scroll to the local y: the grid moves down.
float rainDropScroll(int k, float column, float cellsPerBlock) {
    float r = rainHash42(vec2(column, 37.0 + float(k) * 113.0)).x;
    float speed = RainDrops.w * max(1.0 + RainDrops.z * (r * 2.0 - 1.0), 0.0);
    rainDropS[k] = rainDropSliding() ? rainDropClock * speed * cellsPerBlock : 0.0;
    return rainDropS[k];
}

// Life envelope of a sliding drop from its phase (before fract): life 0..1 over the game's visible window 0.2-0.85
// (Skin 481-486, Hair 522-528, CharacterNPR 586-592: smoothstep 0.2-0.22 in, 0.55-0.85 out); grows over 0-0.12,
// shrinks over 0.5-1.
// Floored at 1e-3 (the blocks divide by the radius); rainDropVis hides the drop outside its life.
void rainDropLife(int k, float phase) {
    float life = (fract(phase) - 0.2) * 1.5384616;
    float env = smoothstep(0.0, 0.12, life) * (1.0 - smoothstep(0.5, 1.0, life));
    rainDropEnv[k] = rainDropSliding() ? max(env, 1e-3) : 1.0;
}

float rainDropVis(int k, float vis) {
    return rainDropSliding() ? (rainDropEnv[k] > 1e-3 ? 1.0 : 0.0) : vis;
}

// value noise in [0, 1]
float rainNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(rainHash42(i).x, rainHash42(i + vec2(1.0, 0.0)).x, f.x),
               mix(rainHash42(i + vec2(0.0, 1.0)).x, rainHash42(i + vec2(1.0, 1.0)).x, f.x), f.y);
}

// Keep weight of the cloth texture drops/streaks at a tile uv: two octaves of value noise (features ~0.5 and ~0.2 of
// a tile, rotated off the tile axes), cut at the threshold that leaves ~rainDropDensity of the area. The soft edge
// (~0.05 tile) is wide against a drop (~0.01-0.02 tile): a drop on it fades as a whole rather than being cut.
float rainTexKeep(vec2 uv) {
    vec2 q = mat2(0.8, -0.6, 0.6, 0.8) * uv * 2.0 + vec2(17.3, 5.9);
    float n = rainNoise(q) * 0.7 + rainNoise(q * 2.31 + vec2(3.1, 9.7)) * 0.3;
    // the noise is ~normal around 0.5 (sd 0.163): its quantile of 1 - density (logistic approximation, area within
    // ~0.03 of the density)
    float d = clamp(RainDrops.x, 0.001, 0.999);
    float c = 0.5 + 0.163 * log((1.0 - d) / d) / 1.702;
    return smoothstep(c - 0.04, c + 0.04, n);
}

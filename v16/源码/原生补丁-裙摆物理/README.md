# Spring solver patch: blend the animated pose across fixed steps

`spring-substep-interpolation.diff` changes the YesSteveModel-Native spring bone solver
(`modules/gfx/src/renderer/spring_physics.cc`, `modules/gfx/include/gfx/renderer/model_state.h`).
Built for Windows on 2026-11-17 (see *Cross build from Linux* below); the offline Python port of the solver
(`tools/springsim`) measured its effect.
`orig/` holds the unpatched files the diff was made against. The patched files sit beside them.

## Cause

`ModelState::StepSprings` integrates the springs in fixed 1/120 s steps. When one extraction covers several steps
(about 12 at 10 fps, 2 at 60 fps), every step runs against the same animated pose, the one from this extraction:
`spring_animated_poses_` is copied back into `bone_poses_` before each step. As a result:

1. Colliders jump the whole frame's motion at once. A sprinting thigh can turn 30-60° between two 10 fps frames, so
   the knee lands 8-20 cm further on. A skirt tail that was in front of the thigh ends up inside the capsule, past its
   axis, and the push-out sends it to the far side (behind the leg or between the legs).
2. The body's travel arrives at once too. `frame.motion` moves the tails as world-fixed points at the start of the
   frame. The joints' pivots, however, are already at this frame's position, so every tail sits a whole frame's
   travel behind its joint (56 cm at sprint speed and 10 fps, 9 cm at 60 fps). The bone length is then restored by
   projecting the tail onto the sphere around the pivot, which flings the chain backwards. `carry` only pulls it back
   over the following steps.

## Change

- `spring_previous_poses_` and `spring_previous_visited_` keep the previous extraction's animated poses. At the next
  extraction they are moved into the new model space with `frame.motion`, so they show where the body was, seen from
  now.
- Fixed step `k` of `n` poses every visited bone `(k+1)/n` of the way from that previous pose to the current one
  (`BlendPose`). The pivot moves in a straight line and each basis column is interpolated with nlerp and keeps its
  length, so an intermediate pose doesn't shrink or swing about the model origin the way a plain matrix lerp does.
  The last step is exactly the current pose.
- Nothing else changes: reset, size and visibility mismatches, a single step per extraction and the final draw all
  use the current pose as before. Per extraction the cost is one pose copy plus one blend per bone per step.
- The JNI and Java side are unchanged (the `PhysicsFrame` layout is the same). Only private members of `ModelState`
  were added.

## Effect (offline port of StepSprings, Pelica, generated walk/run clips)

Measured with `tools/springsim/table.py`, which runs `tools/springsim/sim.py`, a Python port of this solver;
`interp=True` models the patch. The table gives skirt vertices more than 1 cm inside the leg mesh, averaged per drawn
frame, with the colliders of the current `fix_pelica.py`:

| fps | stock walk / run | patched walk / run |
|----:|-----------------:|-------------------:|
| 10  | 36.5 / 21.3      | 2.3 / 9.5          |
| 30  | 0.2 / 4.0        | 2.3 / 9.3          |
| 60  | 2.7 / 3.6        | 2.3 / 9.4          |
| 144 | 1.4 / 6.7        | 3.2 / 3.7          |

With the patch the result hardly depends on the frame rate, and 10 fps improves a lot. At 30-60 fps the stock solver
does a little better in the run, because the VRM's colliders were tuned against the stock solver; with the patch the
skirt is no longer thrown back by each frame's travel. If the patch ships, retune the front chains' hit radius with
the sim.

## Applying and building

From the YesSteveModel-Native repository root:

```bash
patch -p1 < spring-substep-interpolation.diff        # or: git apply spring-substep-interpolation.diff
```

Then build per `docs/build_cn.md`:

- Index the dependencies once: `./bootstrap.sh setup` (Linux) or `.\bootstrap.cmd setup` (Windows, inside a vcvars
  shell).
- Build: `./bootstrap.sh build native` or `.\bootstrap.cmd build native`.

The prerequisites are Git ≥ 2.49, CMake ≥ 3.29 + Ninja, NASM and Conan ≥ 2.28, plus a C++23 toolchain: Visual Studio
2022+ on Windows, build-essential on Linux. Release builds use the toolchain in `docs/official-build-environment_cn.md`
(custom LLVM, clang-cl for `ysm.dll`). The native library has to stay ABI-compatible with the Java side it ships with,
so build it from the same source revision as the jar and replace both the Windows and the Linux library in the jar.
`modules/test/src/spring_physics_test.cc` exercises the solver and should still pass.

## Cross build from Linux (how the 2026-11-17 test ysm.dll was made)

The official toolchain (TomatoPuddin clang 23.1.4) needs AVX2 and does not run on the build VM, so upstream LLVM 21
(apt.llvm.org: clang-cl-21, lld-link-21, llvm-lib/rc/mt/ml-21) builds against the Windows SDK 10.0.26100 and MSVC CRT
fetched with `xwin splat` (MSVC STL instead of the official libc++; the DLL exports only C symbols). A toolchain
directory for `bootstrap.sh build windows-x64 --toolchain <dir>` holds `conan/windows-x64` (os=Windows,
compiler=clang 21, runtime static, cppstd 23, `/imsvc` + `/libpath` to the xwin tree, CMAKE_LINKER/AR/MT/RC set to
the LLVM tools, `CMAKE_TRY_COMPILE_CONFIGURATION=Release`), `conan/linux-x64`, `tool/{conan,cmake,ninja}/bin` and an
`ml64` wrapper (`llvm-ml-21 -m64`) on PATH for blake3's MASM sources. One source change was needed, in a dependency:

- `proxy-clang-cl-no-unique-address.patch` (proxy 4.1.0, added to `third-party/proxy/conandata.yml` in the build
  copy): upstream clang-cl still miscomputes `[[msvc::no_unique_address]]` in a base class (llvm/llvm-project#143245;
  the official fork has it fixed), and proxy refuses to build. With `YSM_CLANG_CL_NO_UNIQUE_ADDRESS_BROKEN` defined
  (only in this toolchain's cxxflags) proxy uses plain members instead: larger but correct objects inside the DLL.

Checks: exports identical to the shipped ysm.dll (`JNI_OnLoad`, `JNI_OnUnload`, `YSM_ABI_0_1_2_unstable`), imports
only system DLLs (kernel32, advapi32, dbghelp; static CRT), all 55 JNI registrations match the jar. Under Wine with a
Windows JDK 17 it loads through YSM's own NativeRuntime and converts Pelica (`tools/ysmgen GenMxc`) to the same
geometry and NPR package as the shipped DLL (the generated animations differ run to run with either DLL).

# Spring review patch: centers, extended colliders, angle limits, dynamic colliders (2026-11-18)

`spring-physics-review.diff` (on top of the two diffs above; applied in `~/ysm/nativebuild/src`) extends the spring
format and solver for the physics review (REVISION 23 of the importer):

- **Format** (`geo_model.proto.h`, mirrored by `mod/proto/asset/model/data/geo_model.proto`; field numbers append):
  `SpringJoint.limit / limit_rotation / limit_angle / limit_yaw` (VRMC_springBone_limit), `SpringCollider.type`
  (0 outside, 1 inside, 2 plane through head with normal toward tail), `SpringChain.center` (bone index + 1).
  Baked: the same fields plus `BakedSprings.chain_centers`; validated on bake and on cache load.
- **Cache key**: `NativeBakedModel.nCapability()` ORs a baked layout revision (`1 << 16`) above the SIMD type, so
  baked caches written by older natives (other layout) are never read back.
- **Center**: per chain, the center bone's pose change over each fixed step carries the chain's tails, and the body
  drift term (`carry`) is off for those chains: inertia is computed in the center's space.
- **Collider types**: inside sphere / capsule keep the tail within `radius - hitRadius` of the axis; planes keep it
  `hitRadius` in front. The bone-segment backstop and the animated-side push stay outside-only.
- **Limits**: cone, hinge and spherical as in the extension's reference code, in a limit space the bone's current
  pose turns; applied after inertia, after each collider push and with the length constraint.
- **Dynamic colliders**: a collider whose bone is in a simulated joint's subtree is posed where the previous fixed
  step left it (cached ends, moved with the body motion), so bodies on simulated bones collide where they swung; a
  joint skips the colliders its own subtree carries. A bounding-sphere test skips far capsules.
- Tests (`spring_physics_test.cc`): center carry, inside sphere, plane, cone / hinge / spherical limits, colliders
  on simulated bones, pivots following their animated parent (MMD type 2), cache round trip of the new fields.
  Full `ysm-test`: 238 passed, 14 skipped (AVX2/AVX-512 not on the build VM), 0 failed.

# Spring LOD patch: level swaps and per-chain hold (2026-11-18)

`spring-lod.diff` (on top of `spring-physics-review.diff`; applied in `~/ysm/nativebuild/src`) supports the mesh LOD
of importer REVISION 24 (tech doc section 19, `docs/LOD设计.md`):

- **Level swaps keep the springs**: a model state keeps its spring state when the new baked model has the same spring
  fingerprint (`SpringFingerprint` in `spring_physics.cc`); the LOD levels of one geometry share it. A different spring
  table still resets.
- **Restricted step blend**: each fixed step interpolates and restores only the bones the springs read (Pelica: 69 of
  294).
- **Per-chain modes**: FULL (simulate) or HOLD (keep the current shape, carried rigidly by the body); JNI
  `NativeModelState.nSetSpringModes(J[B)V`. Leaving HOLD resumes from rest, so there is no jump.
- Tests: level swap every other frame is bit-identical to no swap; HOLD on entry draws exactly where full simulation
  would; the first frame after leaving HOLD moves by one frame of gravity. Full `ysm-test`: 240 passed, 15 skipped, 0
  failed.

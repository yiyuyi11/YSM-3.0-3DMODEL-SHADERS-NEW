# fpbench working notes (FpBench agent; restart log)

## Status
- [x] java/FpDump.java: dumps arm model (HumanoidImport.arm()), atlas, armAnimation. Compiles against the deployed jar
  ~/mc/client/mods/ysm-3.0-dev-forge+mc1.20.1-pelica-motion.jar + ~/mc/client/libraries; native
  YSM_NATIVE_PATH=~/mc/client/home/.ysm/unstable/libysm-core-3.0-dev-forge+mc1.20.1.so. ~25 s. Test dump: /tmp/fpb/dump_pelica
- [x] fpb/ysm_arm.py (dump cache, FK/skin, finger chains, molang eval) — curl_keyframe reproduces importer keys exactly
  (incl. right index -25 spread). AttachmentType: SCOPE MUZZLE STOCK GRIP LASER EXTENDED_MAG; constraint node anims go
  to ConstraintObject (not the part).
- [x] fpb/tacz_gun.py, scene.py, render.py: first renders OK (/tmp/fpb/t_*.png), 0.18 s/frame warm
- [ ] metrics.py, ik.py, CLI fpbench.py + wrapper, README
- [ ] validation in game (lock held by NprPerf-2 at 04:2x), timing, IK before/after, R2 upload, commit
- Decompiled TACZ in /tmp/fpb/dec (vineflower /tmp/vineflower.jar, slow: batch classes in one call); jar unpacked /tmp/fpb/tacz

## Transform chain (all verified from bytecode / source)
Axis map (SRG): XP=f_252529_, XN=f_252495_, YP=f_252436_, YN=f_252392_, ZP=f_252403_, ZN=f_252393_.
1. Hand projection: GameRenderer.renderItemInHand uses getFov(useFovSetting=false)=70, TACZ CameraSetupEvent
   .applyGunModelFovModifying replaces it by display `model_fov` (GunDisplay default 70.0; ak47 60). Perspective near 0.05.
   NOTE: hands are not drawn when HUD hidden (F1) -> in-game shots keep HUD.
2. Pose at RenderHandEvent: identity (+ vanilla sway XP/YP (viewRot-bob)*0.1, cancelled by TACZ GunItemRendererWrapper
   .renderFirstPerson XP/YP(-0.1*...), zero when standing still) + camera anim quaternion (BeforeRenderHandEvent,
   CameraRotateListener: quat(x, y, -z) of 'camera' bone rotation channel).
3. GunItemRendererWrapper.renderFirstPerson: translate(0,1.5,0); rotate ZP 180; applyFirstPersonGunTransform:
   FirstPersonRenderGunEvent.applyFirstPersonPositioningTransform: translate(0,1.5,0); mul(idleViewInverse);
   translate(0,-1.5,0); constraint transform weight = aimingProgress -> identity at hip.
   getPositioningNodeInverse(path): for i=last..0: rotate XN(xRot) YN(yRot) ZN(zRot); translate(-x/16, -y/16 (root:
   1.5-y/16), -z/16)  (rest values only, no anim offsets).
4. BedrockModel load: part pos = pivot - parentPivot (x,z), parentPivot.y - pivot.y (y); root: (px, 24-py, pz);
   rotation = deg->rad, no negation. Cube origin: x=ox-bpx, y=bpy-oy-sy, z=oz-bpz; rotated cubes: T(convertPivot)
   ZP YP XP then origin relative to cube pivot. Vertices /16. Face uv: EAST->west, WEST->east, UP->down, DOWN->up.
   BedrockPart.translateAndRotateAndScale: T(offset) T(x/16,y/16,z/16) ZP(zRot) YP(yRot) XP(xRot) Q(additional) S(scale)
5. Animations (Animations.createAnimationFromBedrock): position /16 -> offset (x, -y, z); rotation deg->rad euler ->
   additionalQuaternion = MathUtil.toQuaternion(x,y,z) = Rz*Ry*Rx; scale direct. Blend: offsets add, euler add, scale mul.
   Interp: CustomInterpolator (linear on euler; catmullrom splineCurve; from=post, to=pre).
   State machine default_state_machine_v2: static_idle on BASE track (loop), draw/reload/inspect on MAIN (non-blend,
   overrides per bone), shoot on GUN_KICK line (blend), rifle_default 'idle' on MOVEMENT (blend).
6. Hands: FunctionalBedrockPart lefthand_pos/righthand_pos -> LeftHandRender: rotate ZP 180, capture pose; after gun:
   RenderHelper.renderFirstPersonArm -> PlayerRenderer.renderLeft/RightHand -> ForgeHooksClient
   .renderSpecificFirstPersonArm (RenderArmEvent) -> YSM ReplacePlayerHandRenderEvent -> CustomFirstPersonArmRenderer
   .render: [placeOnGun -> TaczGrips.place: scale(1, |col0|/|col1|, 1); translate(palmShift/16)] (only fitted guns,
   only tacz:m4a1 in v18/deployed jar); translate(left?0.25:-0.25, 1.8, 0); scale(-1,-1,1); native skinned draw.
7. YSM native bone: T((pivot - pos.x, pivot+pos.y, pivot+pos.z)/16) RotateZYX(rot) S T(-pivot/16); rot = rest +
   keyframe with X,Y negated (rad). Skin matrix = pose (no inverse bind; mesh is in zero-rotation bind space).
8. FP arm anim: only `parallel2` (fingers + wrists ctrl.tac_grip(..)). Context with gun: tac_hold_gun=1,
   tac_grip('fitted')=1 only for fitted guns.
- HARNESS (successor 05:12): in-game shots taken at 960x540 (MC_RES), pitch -45 sky, HUD on; copies in /tmp/fpb/shots
  (fpb_<gun>_idle.png, fpb_<gun>_rl_NN.png bursts 200 ms after pressing R; R also triggers Oculus 'Shaders Reloaded'
  chat). Guns ak47 m4a1 glock_17. Harness restored (tacz removed, oculus/options from backup), lock released 05:13.
- metrics.py tested OK (sdf culled+matmul). Next: fpb/compare.py (silhouette-edge residual + t fit), CLI, README.
- 05:40 VALIDATION DONE (/tmp/fpb/validate.json, overlays /tmp/fpb/cmp_*.png, script /tmp/fpb/validate.py): with fitted
  idle_t/t and bob_view=False (TACZ applies camera anim to hands only if View Bobbing on: CameraSetupEvent.java:80,
  harness forces bobView false) all 6 cases: hand-on-grip residual <= 0.54 px, IoU 0.986-0.9999.
- Fixed: tacz_gun.triangles magazine re-parent (singular inv at scale 0) -> self.locals. compare.py written.
- fpbench.py CLI + ./fpbench wrapper written & render/ik tested. IK: ak47 in-gun verts L398->190 R137->120; m4a1 L379->50 R56->0.
- Next: IK relaxed-threshold tweak, README, sample sheet to R2, commit (only tools/fpbench).
- 05:35 DONE: IK livelock fix + importer_layer Molang output; README; committed c85538f (git add -f, tools/* is
  gitignored); R2 fpbench-示例-20261118.png md5 3a08d2b504d865dc25422fd0978e08f3 verified. Harness restored, lock free.

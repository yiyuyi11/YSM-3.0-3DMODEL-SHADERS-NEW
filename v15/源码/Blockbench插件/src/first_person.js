// First-person view: Minecraft's hand camera and the arm placement of YSM's CustomFirstPersonArmRenderer.
//
// Sources (YSM upstream, ~/ysm/src/YesSteveModel, identical in the fork jar; Minecraft 1.20.1 from the decompiled
// forge-1.20.1-47.4.3-client.jar, Vineflower, SRG names):
//  - Projection: GameRenderer.renderItemInHand (m_109120_, GameRenderer.java:960-962) uses getFov(..., false), which is a
//    fixed 70° (m_109141_, GameRenderer.java:895), Matrix4f.setPerspective(fov, w/h, 0.05, far). The hands are drawn in
//    view space: camera at the eye, looking down -Z, +Y up. Bobbing/hurt tilt are zero when standing still.
//  - ItemInHandRenderer.renderHandsWithItems (m_109314_, :328): XP/YP((view - bob) * 0.1) = 0 when still; then per hand
//    renderArmWithItem (m_109371_, :391): an empty main hand → renderPlayerArm (m_109346_, :243-268); a filled map with
//    something in the off hand → renderOneHandedMap (m_109353_, :176-200); a filled map in the main hand and an empty
//    off hand → renderTwoHandedMap (m_109339_, :202-222) → renderMapHand (m_109361_, :158-174) for both arms. Any
//    other held item draws no arm in vanilla (only TACZ guns and a few mods call the arm renderer themselves).
//  - Both reach PlayerRenderer.renderRightHand/renderLeftHand (PlayerRenderer.java:189-198), which posts Forge's
//    RenderArmEvent; YSM's ReplacePlayerHandRenderEvent.onRenderHand (client/event/ReplacePlayerHandRenderEvent.java:17)
//    calls CustomFirstPersonArmRenderer.render, which does (client/renderer/CustomFirstPersonArmRenderer.java:54-58):
//      [TaczGrips.place on a fitted TACZ gun] translate(left ? 0.25 : -0.25, 1.8, 0); scale(-1, -1, 1)
//    and draws that arm (the other one hidden) with the arm model's bones in blocks (pivot px / 16).
//  - Axis.XP/YP/ZP are f_252529_/f_252436_/f_252403_; rotationDegrees is a right-handed turn, as in three.js.
// Everything here is in blocks; Blockbench's scene uses pixels, so a placement P becomes S(16)·P·S(1/16).

YSM3D.FP_MODES = {
	empty: '空手（主手）',
	map_one: '主手持地图（副手有物品）',
	map_two: '双手持地图（副手为空）',
	tacz: 'TACZ 枪（主手）',
};

/** A PoseStack in blocks. */
YSM3D.PoseStack = class PoseStack {
	constructor(m) {
		this.m = m ? m.clone() : new THREE.Matrix4();
		this.stack = [];
	}
	push() {
		this.stack.push(this.m.clone());
		return this;
	}
	pop() {
		this.m = this.stack.pop();
		return this;
	}
	translate(x, y, z) {
		this.m.multiply(new THREE.Matrix4().makeTranslation(x, y, z));
		return this;
	}
	scale(x, y, z) {
		this.m.multiply(new THREE.Matrix4().makeScale(x, y, z));
		return this;
	}
	rot(axis, deg) {
		const v = axis === 'x' ? new THREE.Vector3(1, 0, 0) : axis === 'y' ? new THREE.Vector3(0, 1, 0) : new THREE.Vector3(0, 0, 1);
		this.m.multiply(new THREE.Matrix4().makeRotationAxis(v, deg * Math.PI / 180));
		return this;
	}
	mul(matrix) {
		this.m.multiply(matrix);
		return this;
	}
	get() {
		return this.m.clone();
	}
};

const fround = Math.fround;
const sinf = (x) => fround(Math.sin(x));

/** ItemInHandRenderer.renderPlayerArm (m_109346_, ItemInHandRenderer.java:243-268) up to renderRightHand/LeftHand. */
YSM3D.renderPlayerArm = function (ps, right, equip, swing) {
	const f = right ? 1 : -1;
	const f1 = Math.sqrt(swing);
	const f2 = -0.3 * sinf(f1 * Math.PI);
	const f3 = 0.4 * sinf(f1 * Math.PI * 2);
	const f4 = -0.4 * sinf(swing * Math.PI);
	ps.translate(f * (f2 + 0.64000005), f3 + -0.6 + equip * -0.6, f4 + -0.71999997);
	ps.rot('y', f * 45);
	const f5 = sinf(swing * swing * Math.PI);
	const f6 = sinf(f1 * Math.PI);
	ps.rot('y', f * f6 * 70);
	ps.rot('z', f * f5 * -20);
	ps.translate(f * -1, 3.6, 3.5);
	ps.rot('z', f * 120);
	ps.rot('x', 200);
	ps.rot('y', f * -135);
	ps.translate(f * 5.6, 0, 0);
	return ps;
};

/** ItemInHandRenderer.renderMap (m_109366_, :224-241): the map quad's matrix, quad corners (-7..135, z 0). */
YSM3D.renderMapQuad = function (ps) {
	ps.rot('y', 180);
	ps.rot('z', 180);
	ps.scale(0.38, 0.38, 0.38);
	ps.translate(-0.5, -0.5, 0);
	ps.scale(0.0078125, 0.0078125, 0.0078125);
	return ps.get();
};

/** CustomFirstPersonArmRenderer.render (:57-58) after RenderArmEvent. */
YSM3D.ysmArmPart = function (ps, left) {
	ps.translate(left ? 0.25 : -0.25, 1.8, 0);
	ps.scale(-1, -1, 1);
	return ps.get();
};

/** Records an arm drawn at a RenderArmEvent: the event's pose (for the vanilla arm reference) and YSM's placement. */
YSM3D.armAt = function (out, right, ps) {
	const name = right ? 'RightArm' : 'LeftArm';
	out.event[name] = ps.get();
	out.arms[name] = YSM3D.ysmArmPart(new YSM3D.PoseStack(ps.get()), !right);
};

/**
 * Placement of each arm (arm model blocks → view blocks) and the held-item references, for a first-person state.
 * Right-handed player; main hand = right arm.
 * @returns {{arms: {RightArm?: Matrix4, LeftArm?: Matrix4}, refs: [{type, matrix}], env: object}}
 */
YSM3D.firstPersonLayout = function (state) {
	const mode = state.mode || 'empty';
	const equip = 0;  // settled (no re-equip animation)
	const swing = 0;
	const out = {arms: {}, event: {}, refs: [], env: {}};
	if (mode === 'empty') {
		// renderArmWithItem (:407-409): empty main hand → renderPlayerArm(main arm).
		YSM3D.armAt(out, true, YSM3D.renderPlayerArm(new YSM3D.PoseStack(), true, equip, swing));
	} else if (mode === 'map_one') {
		// renderOneHandedMap (m_109353_, :176-200), main hand right.
		const f = 1;
		const ps = new YSM3D.PoseStack();
		ps.translate(f * 0.125, -0.125, 0);
		ps.push();
		ps.rot('z', f * 10);
		YSM3D.armAt(out, true, YSM3D.renderPlayerArm(ps, true, equip, swing));
		ps.pop();
		ps.push();
		ps.translate(f * 0.51, -0.08 + equip * -1.2, -0.75);
		const f1 = Math.sqrt(swing);
		const f2 = sinf(f1 * Math.PI);
		ps.translate(f * (-0.5 * f2), 0.4 * sinf(f1 * Math.PI * 2) - 0.3 * f2, -0.3 * sinf(swing * Math.PI));
		ps.rot('x', f2 * -45);
		ps.rot('y', f * f2 * -30);
		out.refs.push({type: 'map', matrix: YSM3D.renderMapQuad(ps)});
		ps.pop();
		out.env = {'ysm.has_mainhand': 1, 'ysm.has_offhand': 1};
	} else if (mode === 'map_two') {
		// renderTwoHandedMap (m_109339_, :202-222) with the view pitch (m_109312_, :152-156).
		const pitch = state.pitch || 0;
		const ps = new YSM3D.PoseStack();
		const f = Math.sqrt(swing);
		const f1 = -0.2 * sinf(swing * Math.PI);
		const f2 = -0.4 * sinf(f * Math.PI);
		ps.translate(0, -f1 / 2, f2);
		let a = 1 - pitch / 45 + 0.1;
		a = Math.min(1, Math.max(0, a));
		const f3 = -Math.cos(a * Math.PI) * 0.5 + 0.5;
		ps.translate(0, 0.04 + equip * -1.2 + f3 * -0.5, -0.72);
		ps.rot('x', f3 * -85);
		ps.push();
		ps.rot('y', 90);
		for (const right of [true, false]) {
			// renderMapHand (m_109361_, :158-174)
			const s = right ? 1 : -1;
			ps.push();
			ps.rot('y', 92);
			ps.rot('x', 45);
			ps.rot('z', s * -41);
			ps.translate(s * 0.3, -1.1, 0.45);
			YSM3D.armAt(out, right, ps);
			ps.pop();
		}
		ps.pop();
		ps.rot('x', sinf(f * Math.PI) * 20);
		ps.scale(2, 2, 2);
		out.refs.push({type: 'map', matrix: YSM3D.renderMapQuad(ps)});
		out.env = {'ysm.has_mainhand': 1, 'ysm.has_offhand': 0};
	} else if (mode === 'tacz' && YSM3D.taczLayout) {
		YSM3D.taczLayout(state, out);
	}
	if (state.vanilla) {
		for (const name in out.event) out.refs.push({type: 'vanilla_arm', right: name === 'RightArm', matrix: out.event[name]});
	}
	return out;
};

/** View blocks → Blockbench pixels for a reference drawn in its own local units (the matrix ends in those units). */
YSM3D.viewPixels = function (m) {
	return new THREE.Matrix4().makeScale(16, 16, 16).multiply(m);
};
/** A placement of the arm model (pixels in, blocks inside the chain) as a pixel → pixel matrix. */
YSM3D.blocksToPixels = function (m) {
	return new THREE.Matrix4().makeScale(16, 16, 16).multiply(m).multiply(new THREE.Matrix4().makeScale(1 / 16, 1 / 16, 1 / 16));
};

// ---- held-item references (view space)
YSM3D.mapTexture = function () {
	if (YSM3D._mapTexture) return YSM3D._mapTexture;
	const c = document.createElement('canvas');
	c.width = c.height = 128;
	const g = c.getContext('2d');
	g.fillStyle = '#d9c9a0';
	g.fillRect(0, 0, 128, 128);
	g.strokeStyle = '#8a7350';
	g.lineWidth = 2;
	for (let i = 8; i < 128; i += 16) {
		g.beginPath(); g.moveTo(i, 4); g.lineTo(i, 124); g.stroke();
		g.beginPath(); g.moveTo(4, i); g.lineTo(124, i); g.stroke();
	}
	g.fillStyle = '#5a4630';
	g.font = 'bold 26px sans-serif';
	g.textAlign = 'center';
	g.fillText('地图', 64, 72);
	const t = new THREE.CanvasTexture(c);
	t.flipY = false;  // the map quad's uv v=0 is the top row (ItemInHandRenderer.renderMap)
	t.magFilter = THREE.NearestFilter;
	YSM3D._mapTexture = t;
	return t;
};

/**
 * The vanilla (wide) player arm at a RenderArmEvent pose, as PlayerRenderer.renderHand (PlayerRenderer.java:201-214)
 * would draw it: ModelPart right_arm at pivot (-5, 2, 0) px, cube (-3, -2, -2) 4×12×4 (left: pivot (5, 2, 0), cube
 * (-1, -2, -2)); setupAnim(…, ageInTicks 0) bobs zRot by ±0.1 rad (AnimationUtils.bobArms, AnimationUtils.java:55-58),
 * xRot is reset to 0; ModelPart.translateAndRotate (ModelPart.java:147-156) = T(pivot/16)·RotZYX. Drawn as an outline:
 * YSM's ArmFit puts the shoulder and palm of the imported arm where this arm is.
 */
YSM3D.vanillaArmMatrix = function (event, right) {
	const ps = new YSM3D.PoseStack(event);
	ps.translate((right ? -5 : 5) / 16, 2 / 16, 0);
	ps.mul(new THREE.Matrix4().makeRotationFromEuler(new THREE.Euler(0, 0, right ? 0.1 : -0.1, 'ZYX')));
	return ps.get();
};

YSM3D.buildReference = function (ref) {
	if (ref.type === 'vanilla_arm') {
		const x0 = ref.right ? -3 : -1;
		const box = new THREE.BoxGeometry(4 / 16, 12 / 16, 4 / 16);
		box.translate((x0 + 2) / 16, 4 / 16, 0);
		const lines = new THREE.LineSegments(new THREE.EdgesGeometry(box),
			new THREE.LineBasicMaterial({color: 0x44ddff, depthTest: false, transparent: true}));
		lines.renderOrder = 10;
		lines.matrixAutoUpdate = false;
		lines.matrix.copy(YSM3D.viewPixels(YSM3D.vanillaArmMatrix(ref.matrix, ref.right)));
		return lines;
	}
	if (ref.type === 'map') {
		// The map quad of ItemInHandRenderer.renderMap: corners (-7,-7)..(135,135), uv (0..1).
		const g = new THREE.BufferGeometry();
		g.setAttribute('position', new THREE.BufferAttribute(new Float32Array([-7, 135, 0, 135, 135, 0, 135, -7, 0, -7, -7, 0]), 3));
		g.setAttribute('uv', new THREE.BufferAttribute(new Float32Array([0, 1, 1, 1, 1, 0, 0, 0]), 2));
		g.setIndex([0, 1, 2, 0, 2, 3]);
		const mesh = new THREE.Mesh(g, new THREE.MeshBasicMaterial({map: YSM3D.mapTexture(), side: THREE.DoubleSide}));
		mesh.matrixAutoUpdate = false;
		mesh.matrix.copy(YSM3D.viewPixels(ref.matrix));
		return mesh;
	}
	if (ref.object) {
		ref.object.matrixAutoUpdate = false;
		ref.object.matrix.copy(YSM3D.viewPixels(ref.matrix));
		return ref.object;
	}
	return null;
};

/**
 * Applies the first-person layout to a project:
 *  - Project.model_3d.matrix = placement of the main (right) arm, so that arm's bones, gizmos and pivots are exact;
 *  - the left arm's root group mesh gets the extra P_right⁻¹·P_left (only when both arms are drawn);
 *  - the arm meshes not drawn in game are hidden; references are placed in view space;
 *  - Molang variables of the state (ysm.has_mainhand, ctrl.tac_* ...) are set for the preview.
 */
YSM3D.FirstPerson = class FirstPerson {
	constructor(project, preview) {
		this.project = project;
		this.preview = preview;
		this.refRoot = new THREE.Object3D();
		this.refRoot.name = 'ysm3d_fp_refs';
		this.refRoot.matrixAutoUpdate = false;
		project.model_3d.add(this.refRoot);
		this.extra = {};  // root bone name → Matrix4 (px) premultiplied to that root's local matrix
	}

	state() {
		const s = this.project.ysm3d && this.project.ysm3d.fp || {};
		return {mode: s.mode || 'empty', pitch: s.pitch || 0, gun: s.gun || '', vanilla: !!s.vanilla,
			fov: s.fov || YSM3D.setting('fov') || 70};
	}

	apply() {
		const layout = YSM3D.firstPersonLayout(this.state());
		this.layout = layout;
		const right = layout.arms.RightArm ? YSM3D.blocksToPixels(layout.arms.RightArm) : new THREE.Matrix4();
		const model = this.project.model_3d;
		model.matrixAutoUpdate = false;
		model.matrix.copy(right);
		model.matrixWorldNeedsUpdate = true;
		const inverse = right.clone().invert();
		this.extra = {};
		if (layout.arms.LeftArm) this.extra.LeftArm = inverse.clone().multiply(YSM3D.blocksToPixels(layout.arms.LeftArm));
		if (this.preview) {
			this.preview.setPartVisible('RightArm', !!layout.arms.RightArm);
			this.preview.setPartVisible('LeftArm', !!layout.arms.LeftArm);
		}
		// references live in view space: undo the model placement
		this.refRoot.matrix.copy(inverse);
		this.refRoot.matrixWorldNeedsUpdate = true;
		for (const child of this.refRoot.children.slice()) this.refRoot.remove(child);
		for (const ref of layout.refs) {
			const obj = YSM3D.buildReference(ref);
			if (obj) this.refRoot.add(obj);
		}
		this.hookRoots();
		YSM3D.applyMolangEnv(layout.env);
	}

	/** Keeps the per-root extra placement on the root groups' meshes (Blockbench may re-create meshes). */
	hookRoots() {
		if (typeof Project === 'undefined' || Project !== this.project) return;
		for (const group of Group.all) {
			if (group.parent instanceof Group) continue;
			const mesh = group.mesh;
			if (!mesh) continue;
			const extra = this.extra[group.name];
			if (extra) {
				if (mesh.updateMatrix !== YSM3D._extraUpdateMatrix) mesh.updateMatrix = YSM3D._extraUpdateMatrix;
				mesh.ysm3dExtra = extra;
			} else if (mesh.updateMatrix === YSM3D._extraUpdateMatrix) {
				delete mesh.updateMatrix;
				delete mesh.ysm3dExtra;
			}
		}
	}

	/** Camera at the eye: view origin, looking down -Z, Minecraft's hand FOV. */
	/** The hand projection's FOV: TACZ's model_fov with a gun, else Minecraft's 70 (or the user's value). */
	fov() {
		return (this.layout && this.layout.fov) || this.state().fov;
	}

	camera(all) {
		const fov = this.fov();
		const previews = all ? Preview.all : [Preview.selected].filter(Boolean);
		for (const p of previews) {
			p.loadAnglePreset({position: [0, 0, 0], target: [0, 0, -16], projection: 'perspective', fov});
			p.setFOV(fov);
		}
	}

	release() {
		const model = this.project.model_3d;
		model.matrixAutoUpdate = true;
		model.matrix.identity();
		model.matrixWorldNeedsUpdate = true;
		this.extra = {};
		this.hookRoots();
		if (this.refRoot.parent) this.refRoot.parent.remove(this.refRoot);
	}
};

YSM3D._extraUpdateMatrix = function () {
	THREE.Object3D.prototype.updateMatrix.call(this);
	if (this.ysm3dExtra) this.matrix.premultiply(this.ysm3dExtra);
};

// Molang variables YSM sets in game; Blockbench's Molang falls back to 0 for the rest.
YSM3D.MOLANG_KEYS = ['ysm.has_mainhand', 'ysm.has_offhand', 'ctrl.tac_hold_gun', 'ctrl.tac_grip'];
YSM3D.applyMolangEnv = function (env) {
	const vars = Animator.MolangParser.global_variables;
	for (const key of YSM3D.MOLANG_KEYS) delete vars[key];
	for (const key in env || {}) vars[key] = env[key];
};

// First-person TACZ reference: a static gun from a gun pack (zip/jar) at TACZ's hand position, with the arms placed on
// its lefthand_pos/righthand_pos as TACZ and YSM do. A port of fpbench (tools/fpbench/fpb/tacz_gun.py, scene.py), which
// was verified pixel-exact against in-game shots; TACZ references are to the decompiled TACZ 1.2.0-hotfix classes:
//  - pack layout: data/<ns>/index/guns/<id>.json → display (assets/<ns>/display/guns/...) → geo_models, textures,
//    animations; use_default_animation → assets/tacz/animations/<kind>_default.animation.json (the TACZ jar);
//  - BedrockModel loading: pos = pivot - parentPivot (y negated, root 24 - y); BedrockPart.translateAndRotateAndScale =
//    T(offset) T(xyz/16) ZP YP XP Q(anim) S; face uv: east↔west, up↔down (FaceUVsItem.getFace);
//  - pose: static_idle at t = 0 (base track, default_state_machine_v2.lua); ModelTranslateListener offset (x, -y, z)/16,
//    ModelRotateListener MathUtil.toQuaternion; no idle sway, no camera animation (a still reference);
//  - GunItemRendererWrapper.renderFirstPerson: translate(0, 1.5, 0), ZP 180; FirstPersonRenderGunEvent
//    applyFirstPersonPositioningTransform: translate(0, 1.5, 0)·idleViewInverse·translate(0, -1.5, 0) (hip fire);
//  - LeftHandRender/RightHandRender: hand part pose · ZP 180 → RenderHelper.renderFirstPersonArm → RenderArmEvent;
//    YSM CustomFirstPersonArmRenderer: TaczGrips.place for fitted guns (client/compat/tacz/TaczGrips.java:73-98), then
//    translate(±0.25, 1.8, 0), scale(-1, -1, 1).
//  - projection: TACZ CameraSetupEvent.applyGunModelFovModifying uses the display's model_fov (default 70).

YSM3D.Tacz = {sources: [], guns: {}};

// TaczGrips.GRIPS (TaczGrips.java:28-30): gun id → (left, right) hand (palmX, palmY, palmZ, wristX, wristY, wristZ).
YSM3D.Tacz.GRIPS = {'tacz:m4a1': [[5.0, 18.282, 1.155, 80.64, -63.68, -49.49], [-7.092, 13.806, -1.283, 38.73, 0.0, 9.0]]};

YSM3D.Tacz.stripJson = function (text) {
	let out = '';
	let inStr = false;
	for (let i = 0; i < text.length; i++) {
		const c = text[i];
		if (inStr) {
			out += c;
			if (c === '\\') out += text[++i];
			else if (c === '"') inStr = false;
		} else if (c === '"') {
			inStr = true;
			out += c;
		} else if (text.startsWith('//', i)) {
			const j = text.indexOf('\n', i);
			i = j < 0 ? text.length : j - 1;
		} else if (text.startsWith('/*', i)) {
			const j = text.indexOf('*/', i + 2);
			i = j < 0 ? text.length : j + 1;
		} else {
			out += c;
		}
	}
	return out.replace(/,(\s*[}\]])/g, '$1');
};

/** Adds a gun pack (zip, or the TACZ jar with its default pack). */
YSM3D.Tacz.addPack = async function (name, arrayBuffer) {
	const zip = await JSZip.loadAsync(arrayBuffer);
	const source = {name, zip, guns: {}};
	zip.forEach((path, entry) => {
		const m = !entry.dir && path.match(/^(.*?)data\/([^/]+)\/index\/guns\/(.+)\.json$/);
		if (m && !source.guns[m[2] + ':' + m[3]]) source.guns[m[2] + ':' + m[3]] = m[1];
	});
	YSM3D.Tacz.sources = YSM3D.Tacz.sources.filter((s) => s.name !== name).concat([source]);
	return Object.keys(source.guns).length;
};
YSM3D.Tacz.gunIds = () => [...new Set(YSM3D.Tacz.sources.flatMap((s) => Object.keys(s.guns)))].sort();

YSM3D.Tacz.readJson = async function (source, path) {
	const f = source.zip.file(path);
	return f ? JSON.parse(YSM3D.Tacz.stripJson((await f.async('string')).replace(/^\uFEFF/, ''))) : null;
};
const taczRl = (rid, ns) => rid.includes(':') ? rid.split(':') : [ns, rid];

/** Loads a gun (geometry, texture, static pose) into YSM3D.Tacz.guns[id]. */
YSM3D.Tacz.loadGun = async function (gunId) {
	if (YSM3D.Tacz.guns[gunId]) return YSM3D.Tacz.guns[gunId];
	const source = YSM3D.Tacz.sources.find((s) => gunId in s.guns);
	if (!source) throw '没有载入包含 ' + gunId + ' 的枪包。';
	const root = source.guns[gunId];
	const [ns, path] = taczRl(gunId, 'tacz');
	const index = await YSM3D.Tacz.readJson(source, `${root}data/${ns}/index/guns/${path}.json`);
	const [dns, dpath] = taczRl(index.display, ns);
	const display = await YSM3D.Tacz.readJson(source, `${root}assets/${dns}/display/guns/${dpath}.json`);
	const [mns, mpath] = taczRl(display.model, dns);
	const geo = await YSM3D.Tacz.readJson(source, `${root}assets/${mns}/geo_models/${mpath}.json`);
	const [tns, tpath] = taczRl(display.texture, dns);
	const png = source.zip.file(`${root}assets/${tns}/textures/${tpath}.png`);
	const clips = {};
	if (display.use_default_animation) {
		// the TACZ jar's default clips (in any loaded source)
		for (const s of YSM3D.Tacz.sources) {
			const json = await YSM3D.Tacz.readJson(s, `assets/tacz/animations/${display.use_default_animation}_default.animation.json`);
			if (json) {
				Object.assign(clips, json.animations || {});
				break;
			}
		}
	}
	if (display.animation) {
		const [ans, apath] = taczRl(display.animation, dns);
		const json = await YSM3D.Tacz.readJson(source, `${root}assets/${ans}/animations/${apath}.animation.json`);
		if (json) Object.assign(clips, json.animations || {});  // the gun's own clips replace defaults by name
	}
	const gun = {id: gunId, modelFov: typeof display.model_fov === 'number' ? display.model_fov : 70,
		textNodes: new Set(Object.keys(display.text_show || {}))};
	Object.assign(gun, YSM3D.Tacz.parseGeo(geo));
	gun.state = YSM3D.Tacz.staticPose(gun, clips.static_idle);
	gun.mats = YSM3D.Tacz.matrices(gun);
	gun.viewBase = YSM3D.Tacz.viewBase(gun);
	gun.texture = png ? await png.async('base64') : null;
	YSM3D.Tacz.guns[gunId] = gun;
	return gun;
};

// ---- geometry (BedrockModel; fpbench load_geo)
const FACE_KEY = {down: 'up', up: 'down', west: 'east', north: 'north', east: 'west', south: 'south'};
const mat4 = () => new THREE.Matrix4();
const T = (x, y, z) => mat4().makeTranslation(x, y, z);
const RX = (a) => mat4().makeRotationX(a);
const RY = (a) => mat4().makeRotationY(a);
const RZ = (a) => mat4().makeRotationZ(a);

YSM3D.Tacz.parseGeo = function (data) {
	let bones, tw, th;
	if (data['minecraft:geometry']) {
		const g = data['minecraft:geometry'][0];
		tw = g.description?.texture_width || 64;
		th = g.description?.texture_height || 64;
		bones = g.bones || [];
	} else {
		const key = Object.keys(data).find((k) => k.startsWith('geometry.'));
		tw = data[key].texturewidth || 64;
		th = data[key].textureheight || 64;
		bones = data[key].bones || [];
	}
	const index = {};
	for (const b of bones) if (!index[b.name]) index[b.name] = b;
	const parts = {};
	for (const b of bones) parts[b.name] = {name: b.name, parent: null, children: [], x: 0, y: 0, z: 0, rot: [0, 0, 0], pos: [], uv: []};
	const roots = [];
	for (const b of bones) {
		const p = parts[b.name];
		const piv = b.pivot || [0, 0, 0];
		if (b.parent != null && index[b.parent]) {
			const pp = index[b.parent].pivot || [0, 0, 0];
			[p.x, p.y, p.z] = [piv[0] - pp[0], pp[1] - piv[1], piv[2] - pp[2]];
			p.parent = b.parent;
			if (!parts[b.parent].children.includes(p)) parts[b.parent].children.push(p);
		} else {
			[p.x, p.y, p.z] = [piv[0], 24 - piv[1], piv[2]];
			roots.push(p);
		}
		if (b.rotation) p.rot = b.rotation.map((a) => a * Math.PI / 180);
		for (const cube of b.cubes || []) {
			const size = cube.size || [0, 0, 0];
			const origin = cube.origin || [0, 0, 0];
			const inflate = cube.inflate || 0;
			const mirror = cube.mirror !== undefined ? cube.mirror : !!b.mirror;
			let x, y, z, transform = null;
			if (!cube.rotation) {
				[x, y, z] = [origin[0] - piv[0], piv[1] - origin[1] - size[1], origin[2] - piv[2]];
			} else {
				const cp = cube.pivot || [0, 0, 0];
				[x, y, z] = [origin[0] - cp[0], cp[1] - origin[1] - size[1], origin[2] - cp[2]];
				const r = cube.rotation.map((a) => a * Math.PI / 180);
				transform = T(cp[0] - piv[0], piv[1] - cp[1], cp[2] - piv[2]).multiply(RZ(r[2])).multiply(RY(r[1])).multiply(RX(r[0]));
			}
			const uv = cube.uv || [0, 0];
			const perFace = !Array.isArray(uv);
			const faces = YSM3D.Tacz.boxFaces(x, y, z, size[0], size[1], size[2], inflate, mirror && !perFace);
			let rects;
			if (!perFace) {
				const [dx, dy, dz] = size.map((s) => Math.trunc(s));
				const [u0, v0] = uv;
				const p1 = u0 + dz, p2 = p1 + dx, p3 = p2 + dx, p4 = p2 + dz, p5 = p4 + dx, p6 = v0 + dz, p7 = p6 + dy;
				rects = {down: [p1, v0, p2, p6], up: [p2, p6, p3, v0], west: [u0, p6, p1, p7], north: [p1, p6, p2, p7],
					east: [p2, p6, p4, p7], south: [p4, p6, p5, p7]};
			}
			for (const [dir, corners0] of faces) {
				const corners = transform ? corners0.map((c) => new THREE.Vector3(...c).applyMatrix4(transform).toArray()) : corners0;
				let rect, mir;
				if (perFace) {
					const f = uv[FACE_KEY[dir]];
					if (!f || !f.uv) continue;
					const [su, sv] = f.uv_size || [0, 0];
					rect = [f.uv[0], f.uv[1], f.uv[0] + su, f.uv[1] + sv];
					mir = false;
				} else {
					rect = rects[dir];
					mir = mirror;
				}
				// BedrockPolygon: corner uvs (u2,v1) (u1,v1) (u1,v2) (u2,v2), reversed when mirrored
				const [u1, v1, u2, v2] = rect;
				let cuv = [[u2 / tw, v1 / th], [u1 / tw, v1 / th], [u1 / tw, v2 / th], [u2 / tw, v2 / th]];
				let cs = corners;
				if (mir) {
					cs = cs.slice().reverse();
					cuv = cuv.slice().reverse();
				}
				for (const k of [0, 1, 2, 0, 2, 3]) {
					p.pos.push(cs[k][0] / 16, cs[k][1] / 16, cs[k][2] / 16);
					p.uv.push(cuv[k][0], cuv[k][1]);
				}
			}
		}
	}
	return {parts, roots};
};

/** BedrockCubeBox vertex layout (fpbench _box_faces). */
YSM3D.Tacz.boxFaces = function (x, y, z, w, h, d, delta, mirror) {
	let xe = x + w, ye = y + h, ze = z + d;
	[x, y, z, xe, ye, ze] = [x - delta, y - delta, z - delta, xe + delta, ye + delta, ze + delta];
	if (mirror) [x, xe] = [xe, x];
	const v = {1: [x, y, z], 2: [xe, y, z], 3: [xe, ye, z], 4: [x, ye, z], 5: [x, y, ze], 6: [xe, y, ze], 7: [xe, ye, ze], 8: [x, ye, ze]};
	return [['down', [6, 5, 1, 2]], ['up', [3, 4, 8, 7]], ['west', [1, 5, 8, 4]], ['north', [2, 1, 4, 3]],
		['east', [6, 2, 3, 7]], ['south', [5, 6, 7, 8]]].map(([dir, idx]) => [dir, idx.map((i) => v[i])]);
};

// ---- pose
/** MathUtil.toQuaternion(x, y, z) = q(Rz(z)·Ry(y)·Rx(x)). */
YSM3D.Tacz.quat = (x, y, z) => new THREE.Quaternion().setFromEuler(new THREE.Euler(x, y, z, 'ZYX'));

/** static_idle at t = 0: each channel's first key (its post value), as CustomInterpolator returns at t ≤ the first key. */
YSM3D.Tacz.staticPose = function (gun, clip) {
	const state = {};
	for (const name in gun.parts) state[name] = {off: [0, 0, 0], q: new THREE.Quaternion(), s: [1, 1, 1], touched: false};
	if (!clip || !clip.bones) return state;
	const first = (spec) => {
		if (Array.isArray(spec) || typeof spec !== 'object') return spec;
		const keys = Object.keys(spec).sort((a, b) => parseFloat(a) - parseFloat(b));
		let v = spec[keys[0]];
		if (v && !Array.isArray(v) && typeof v === 'object') v = v.post !== undefined ? v.post : (v.pre !== undefined ? v.pre : v.vector);
		return v;
	};
	const vec = (v) => (Array.isArray(v) ? v : [v, v, v]).slice(0, 3).map(Number);
	for (const bone in clip.bones) {
		const s = state[bone];
		if (!s) continue;  // the camera/constraint nodes are not geometry
		const ch = clip.bones[bone];
		if (ch.position !== undefined) {
			const v = vec(first(ch.position)).map((a) => a * 0.0625);
			s.off = [v[0], -v[1], v[2]];
			s.touched = true;
		}
		if (ch.rotation !== undefined) {
			const v = vec(first(ch.rotation)).map((a) => a * Math.PI / 180);
			s.q = YSM3D.Tacz.quat(v[0], v[1], v[2]);
			s.touched = true;
		}
		if (ch.scale !== undefined) {
			s.s = vec(first(ch.scale));
			s.touched = true;
		}
	}
	return state;
};

YSM3D.Tacz.matrices = function (gun) {
	const out = {};
	const walk = (p, M) => {
		const st = gun.state[p.name];
		const L = T(...st.off).multiply(T(p.x / 16, p.y / 16, p.z / 16)).multiply(RZ(p.rot[2])).multiply(RY(p.rot[1]))
			.multiply(RX(p.rot[0])).multiply(mat4().makeRotationFromQuaternion(st.q)).multiply(mat4().makeScale(...st.s));
		const m = M.clone().multiply(L);
		out[p.name] = m;
		for (const c of p.children) walk(c, m);
	};
	for (const r of gun.roots) walk(r, mat4());
	return out;
};

/** FirstPersonRenderGunEvent.getPositioningNodeInverse(idle_view) with rest values; then the view base (fpbench). */
YSM3D.Tacz.viewBase = function (gun) {
	const inv = mat4();
	const path = [];
	for (let p = gun.parts.idle_view; p; p = p.parent ? gun.parts[p.parent] : null) path.unshift(p);
	for (const part of path.slice().reverse()) {
		inv.multiply(RX(-part.rot[0])).multiply(RY(-part.rot[1])).multiply(RZ(-part.rot[2]));
		inv.multiply(part.parent ? T(-part.x / 16, -part.y / 16, -part.z / 16) : T(-part.x / 16, 1.5 - part.y / 16, -part.z / 16));
	}
	return T(0, 1.5, 0).multiply(RZ(Math.PI)).multiply(T(0, 1.5, 0)).multiply(inv).multiply(T(0, -1.5, 0));
};

const TACZ_FUNCTIONAL = new Set(['lefthand_pos', 'righthand_pos', 'muzzle_flash', 'additional_magazine']);
const TACZ_HIDDEN = new Set(['mount', 'sight_folded', 'handguard_tactical', 'mag_extended_1', 'mag_extended_2', 'mag_extended_3']);
YSM3D.Tacz.functional = (gun, name) => TACZ_FUNCTIONAL.has(name) || name === 'shell' || /^shell_\d+$/.test(name)
	|| gun.textNodes.has(name) || ['muzzle', 'stock', 'grip', 'laser', 'extended_mag'].some((t) => name === t + '_pos');
YSM3D.Tacz.hidden = (gun, name) => TACZ_HIDDEN.has(name) || gun.parts[name]?.parent === 'attachment_adapter';

/** The gun mesh in gun model space (blocks), drawn parts only (BedrockPart.render / FunctionalBedrockPart.render). */
YSM3D.Tacz.object = function (gun) {
	const pos = [];
	const uv = [];
	const v = new THREE.Vector3();
	const walk = (p) => {
		if (YSM3D.Tacz.functional(gun, p.name) || YSM3D.Tacz.hidden(gun, p.name)) return;
		const m = gun.mats[p.name];
		for (let i = 0; i < p.pos.length; i += 3) {
			v.set(p.pos[i], p.pos[i + 1], p.pos[i + 2]).applyMatrix4(m);
			pos.push(v.x, v.y, v.z);
		}
		uv.push(...p.uv);
		for (const c of p.children) walk(c);
	};
	for (const r of gun.roots) walk(r);
	const geometry = new THREE.BufferGeometry();
	geometry.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
	geometry.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
	geometry.computeVertexNormals();
	const material = new THREE.MeshLambertMaterial({side: THREE.DoubleSide, alphaTest: 0.1, color: gun.texture ? 0xffffff : 0x888888});
	if (gun.texture) {
		material.map = YSM3D.textureFromPng(gun.texture);
		material.map.magFilter = THREE.NearestFilter;
		material.map.minFilter = THREE.NearestFilter;
		material.map.generateMipmaps = false;
	}
	const mesh = new THREE.Mesh(geometry, material);
	mesh.name = 'ysm3d_tacz_' + gun.id;
	return mesh;
};

/** TaczGrips.place (TaczGrips.java:73-98; fpbench scene.place_on_gun / palm_shift). */
YSM3D.Tacz.placeOnGun = function (H, hand, left, wrist, palm) {
	const e = H.elements;
	const across = Math.hypot(e[0], e[1], e[2]);
	const along = Math.hypot(e[4], e[5], e[6]);
	const out = H.clone();
	if (along > 1e-6) out.multiply(mat4().makeScale(1, across / along, 1));
	const side = left ? 4 : -4;
	const w = new THREE.Vector3(side - wrist[0], 28.8 - wrist[1], wrist[2]);
	const p = new THREE.Vector3(side - palm[0], 28.8 - palm[1], palm[2]);
	const turn = RZ(hand[5] * Math.PI / 180).multiply(RY(hand[4] * Math.PI / 180)).multiply(RX(hand[3] * Math.PI / 180));
	const shifted = p.sub(w).applyMatrix4(turn).add(w);
	const s = new THREE.Vector3(hand[0], hand[1], hand[2]).sub(shifted);
	return out.multiply(T(s.x / 16, s.y / 16, s.z / 16));
};

/** First-person layout for mode 'tacz' (called by YSM3D.firstPersonLayout). */
YSM3D.taczLayout = function (state, out) {
	const gun = YSM3D.Tacz.guns[state.gun];
	if (!gun) return out;
	const rig = YSM3D.rt(Project)?.rig;
	const grip = YSM3D.Tacz.GRIPS[gun.id];
	for (const left of [true, false]) {
		const handPart = gun.mats[left ? 'lefthand_pos' : 'righthand_pos'];
		if (!handPart) continue;
		// LeftHandRender/RightHandRender: the hand part's pose · ZP 180, in view space
		let H = gun.viewBase.clone().multiply(handPart).multiply(RZ(Math.PI));
		const name = left ? 'LeftArm' : 'RightArm';
		out.event[name] = H.clone();
		if (grip && rig) {
			const wristBone = rig.rigs.arm && YSM3D.parseMaybe(rig.rigs.arm.geo)['minecraft:geometry'][0].bones
				.find((b) => b.name === (left ? 'LeftHand' : 'RightHand'));
			const palm = rig.firstPerson && rig.firstPerson.palm && rig.firstPerson.palm[left ? 'left' : 'right'];
			if (wristBone && palm) {
				const wrist = [-wristBone.pivot[0], wristBone.pivot[1], wristBone.pivot[2]];  // scene pivot
				H = YSM3D.Tacz.placeOnGun(H, grip[left ? 0 : 1], left, wrist, palm);
			}
		}
		out.arms[name] = YSM3D.ysmArmPart(new YSM3D.PoseStack(H), left);
	}
	if (!gun.object) gun.object = YSM3D.Tacz.object(gun);
	out.refs.push({type: 'object', object: gun.object, matrix: gun.viewBase});
	out.fov = gun.modelFov;
	// HumanoidImport fp_arm Molang (fpbench ysm_arm.fp_env): holding a gun in the main hand, fitted grip if listed
	const keys = ['left_x', 'left_y', 'left_z', 'right_x', 'right_y', 'right_z'];
	out.env = {
		'ysm.has_mainhand': 1, 'ysm.has_offhand': 0, 'ctrl.tac_hold_gun': 1,
		'ctrl.tac_grip': (key) => {
			if (!grip) return 0;
			if (key === 'fitted') return 1;
			const i = keys.indexOf(key);
			return i < 0 ? 0 : (i < 3 ? grip[0][3 + i] : grip[1][i]);
		},
	};
	return out;
};

YSM3D.taczForm = function (s) {
	const guns = YSM3D.Tacz.gunIds();
	const options = {};
	for (const g of guns) options[g] = g;
	return {
		gun: guns.length
			? {label: 'TACZ 枪', type: 'select', options, value: guns.includes(s.gun) ? s.gun : guns[0], condition: (f) => f.mode === 'tacz'}
			: {type: 'info', text: '还没有载入枪包：选择“TACZ 枪”后确定，会让你选择枪包 zip（或 TACZ 模组 jar，里面有默认枪包）。',
				condition: (f) => f.mode === 'tacz'},
	};
};

/** Before applying: in mode 'tacz', make sure a pack is loaded and the chosen gun is parsed. */
YSM3D.taczApply = async function (runtime, result) {
	if (result.mode !== 'tacz') return;
	if (!YSM3D.Tacz.gunIds().length) await YSM3D.Tacz.pickPacks();
	const gun = result.gun && YSM3D.Tacz.gunIds().includes(result.gun) ? result.gun : YSM3D.Tacz.gunIds()[0];
	if (!gun) throw '枪包里没有找到枪（data/<命名空间>/index/guns/*.json）。';
	await YSM3D.Tacz.loadGun(gun);
	Project.ysm3d.fp.gun = gun;
};

YSM3D.Tacz.pickPacks = function () {
	return new Promise((resolve, reject) => {
		Blockbench.import({resource_id: 'ysm3d_tacz', extensions: ['zip', 'jar'], type: 'TACZ 枪包 / TACZ 模组 jar',
			readtype: 'binary', multiple: true}, async (files) => {
			try {
				let n = 0;
				for (const f of files) n += await YSM3D.Tacz.addPack(f.name, f.content);
				YSM3D.message(`已载入 ${files.length} 个枪包，共 ${n} 把枪。`);
				resolve(n);
			} catch (err) {
				reject(err);
			}
		});
	});
};

// Texture painting: the model's real textures and its endfield_npr render maps as Blockbench textures, painted with
// Blockbench's own tools; live update of the skinned preview; write-back into the VRM.
//
// - Every glTF image a material uses (base colour, normal, emission, ... any `*Texture` slot) and every image the
//   ENDFIELD_npr extension references (materials.<m>.maps.<kind>, ramps, face SDF/mask/highlight, iris matcap,
//   overlays, skin LUT) becomes one Blockbench texture, grouped by material (TextureGroup).
// - Base-colour images are re-composited into their atlas rect (ysmrig-format.md materials[]: rect, the linear uv
//   mapping), so the preview shows the painted texture as the game will after the next import.
// - "预览贴图" shows the selected map on the materials that use it, through the material uv (atlas uv → material uv →
//   the map's Unity tiling), as a plain texture.
// - "写回 VRM" re-encodes the edited images as PNG and rewrites the GLB (YSM3D.Vrm.build), keeping a backup.

YSM3D.paint = {sessions: {}, previewOn: false};

YSM3D.KIND_LABELS = {
	baseColor: '底色', normalTexture: '法线 (glTF)', emissiveTexture: '自发光 (glTF)', occlusionTexture: 'AO (glTF)',
	metallicRoughnessTexture: '金属/粗糙 (glTF)', shadeMultiplyTexture: '阴影色 (MToon)', matcapTexture: 'Matcap (MToon)',
	rimMultiplyTexture: '边缘光 (MToon)', shadingShiftTexture: '明暗偏移 (MToon)', outlineWidthMultiplyTexture: '描边宽度 (MToon)',
	prop: '属性图 prop', emission: '自发光 emission', line: '线条 line', normal: '法线 normal', splitNormal: '分离法线 splitNormal',
	outlineMask: '描边遮罩 outlineMask', browMask: '眉毛遮罩 browMask', parallax: '视差 parallax', baseAlpha: '底色 Alpha baseAlpha',
	'face.sdf': '面部 SDF', 'face.sdfMask': '面部 SDF 遮罩', 'face.highlight': '面部高光', 'iris.matcap': '虹膜 Matcap',
	skinLut: '皮肤 LUT',
};
YSM3D.kindLabel = function (kind) {
	if (YSM3D.KIND_LABELS[kind]) return YSM3D.KIND_LABELS[kind];
	if (/^ramps\./.test(kind)) return '色带 ' + kind.slice(6);
	if (/^overlays\.\d+/.test(kind)) return '叠加层 ' + kind.split('.')[1];
	return kind;
};
/** Kinds sampled with the material's own uv (so they can be shown on the mesh). Ramps, LUTs, matcaps are not. */
YSM3D.kindOnMesh = (kind) => !/^(ramps\.|overlays\.|skinLut|iris\.matcap|matcapTexture)/.test(kind);
YSM3D.kindTiled = (kind) => kind === 'line' || kind === 'parallax';

/** Every image use in the glTF: [{image, material, kind, scale?, offset?}]. */
YSM3D.collectImageUses = function (json) {
	const uses = [];
	const source = (ti) => json.textures && json.textures[ti] ? json.textures[ti].source : undefined;
	(json.materials || []).forEach((m) => {
		const walk = (o) => {
			for (const key in o) {
				const v = o[key];
				if (!v || typeof v !== 'object') continue;
				if (/Texture$/.test(key) && typeof v.index === 'number') {
					const image = source(v.index);
					if (image !== undefined) uses.push({image, material: m.name, kind: key === 'baseColorTexture' ? 'baseColor' : key});
				} else {
					walk(v);
				}
			}
		};
		walk(m);
	});
	const npr = json.extensions && json.extensions.ENDFIELD_npr;
	if (npr) {
		for (const name in npr.materials || {}) {
			const maps = npr.materials[name].maps || {};
			for (const kind in maps) {
				if (typeof maps[kind].image === 'number') {
					uses.push({image: maps[kind].image, material: name, kind, scale: maps[kind].scale, offset: maps[kind].offset});
				}
			}
		}
		const walk = (o, path, material) => {
			if (!o || typeof o !== 'object') return;
			if (typeof o.material === 'string') material = o.material;
			if (o.card && typeof o.card.material === 'string') material = o.card.material;
			if (typeof o.image === 'number') uses.push({image: o.image, material: material || null, kind: path.join('.')});
			for (const key in o) {
				if (key === 'materials' && path.length === 0) continue;
				if (o[key] && typeof o[key] === 'object') walk(o[key], path.concat(key), material);
			}
		};
		walk(npr, [], null);
	}
	return uses;
};

YSM3D.paint.session = function (project) {
	const info = project && project.ysm3d;
	return info ? YSM3D.paint.sessions[info.model_key || info.model] : null;
};

/** Reads the model's VRM (desktop: the rig's source path; web: asks for the file). */
YSM3D.paint.readVrm = function (project) {
	return new Promise((resolve, reject) => {
		const path = project.ysm3d.source;
		if (isApp && path && YSM3D.exists(path)) {
			try {
				const buf = YSM3D.fs().readFileSync(path);
				resolve({bytes: new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength), path});
			} catch (err) {
				reject(err);
			}
			return;
		}
		Blockbench.import({resource_id: 'ysm3d_vrm', extensions: ['vrm'], type: 'VRM 模型', readtype: 'binary'}, (files) => {
			const f = files[0];
			resolve({bytes: new Uint8Array(f.content), path: isApp ? f.path : '', name: f.name});
		});
	});
};

YSM3D.paint.open = async function () {
	const project = Project;
	if (!YSM3D.isYsmProject(project)) return;
	const runtime = YSM3D.rt(project);
	if (!runtime) {
		YSM3D.message('请先载入 YSM 预览（视图 → 重新载入 YSM 预览）。');
		return;
	}
	const {bytes, path, name} = await YSM3D.paint.readVrm(project);
	const glb = YSM3D.Vrm.parse(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));
	const key = project.ysm3d.model_key || project.ysm3d.model;
	const session = {key, rig: runtime.rig, glb, path, name: name || (path ? path.split(/[\\/]/).pop() : project.ysm3d.model + '.vrm'),
		uses: YSM3D.collectImageUses(glb.json), dirty: new Set(), atlases: {}, mapTextures: {}};
	YSM3D.paint.sessions[key] = session;

	// one texture per image, in the group of its first material
	const byImage = {};
	for (const u of session.uses) (byImage[u.image] = byImage[u.image] || []).push(u);
	const existing = {};
	for (const t of Texture.all) if (t.ysm3d_image >= 0) existing[t.ysm3d_image] = t;
	const groups = {};
	const groupFor = (label) => {
		if (typeof TextureGroup === 'undefined') return null;
		if (!groups[label]) {
			groups[label] = (TextureGroup.all || []).find((g) => g.name === label) || new TextureGroup({name: label}).add(false);
		}
		return groups[label];
	};
	const images = Object.keys(byImage).map(Number).sort((a, b) => a - b);
	let added = 0;
	Undo.initEdit({textures: [], texture_groups: []});
	const newTextures = [];
	for (const index of images) {
		if (existing[index]) continue;
		const list = byImage[index];
		const url = YSM3D.Vrm.imageDataUrl(glb, index);
		if (!url) continue;
		const materials = [...new Set(list.map((u) => u.material).filter(Boolean))];
		const kinds = [...new Set(list.map((u) => YSM3D.kindLabel(u.kind)))];
		const label = (materials[0] || '全局') + ' · ' + kinds.join(' / ')
			+ (materials.length > 1 ? '（共用：' + materials.slice(1).join('、') + '）' : '');
		const texture = new Texture({name: label, ysm3d_image: index}).fromDataURL(url).add(false);
		texture.ysm3d_image = index;
		const group = groupFor(materials[0] || '全局贴图');
		if (group) texture.group = group.uuid;
		newTextures.push(texture);
		added++;
	}
	Undo.finishEdit('载入模型贴图', {textures: newTextures});
	YSM3D.paint.attach(project, runtime.rig);
	YSM3D.message(`已载入 ${added} 张贴图（共 ${images.length} 张被材质使用）。在“绘制”模式里用 2D 编辑器绘制；“写回 VRM”保存。`, 5000);
};

// ---- atlas re-compositing (base colour)
YSM3D.paint.atlas = function (session, textureIndex) {
	if (session.atlases[textureIndex]) return session.atlases[textureIndex];
	const src = session.rig.textures[textureIndex];
	const canvas = document.createElement('canvas');
	canvas.width = src.width || 4096;
	canvas.height = src.height || 4096;
	const ctx = canvas.getContext('2d');
	const texture = new THREE.CanvasTexture(canvas);
	texture.flipY = false;
	texture.minFilter = THREE.LinearMipmapLinearFilter;
	texture.magFilter = THREE.LinearFilter;
	const entry = {canvas, ctx, texture, ready: false};
	const image = new Image();
	image.onload = () => {
		ctx.drawImage(image, 0, 0);
		entry.ready = true;
		for (const idx of entry.pending || []) session.dirty.add(idx);
		texture.needsUpdate = true;
	};
	image.src = 'data:image/png;base64,' + src.png;
	session.atlases[textureIndex] = entry;
	return entry;
};

/** Draws a painted base-colour image into every atlas rect that uses it (TextureAtlas.build: resample, factor, alpha). */
YSM3D.paint.composite = function (session, imageIndex, texture) {
	const rigMaterials = session.rig.materials || [];
	for (const m of rigMaterials) {
		if (m.image !== imageIndex || m.outline) continue;
		const atlas = YSM3D.paint.atlas(session, m.texture || 0);
		if (!atlas.ready) {
			(atlas.pending = atlas.pending || []).push(imageIndex);
			continue;
		}
		const [x, y, w, h] = m.rect;
		const ctx = atlas.ctx;
		ctx.save();
		ctx.globalCompositeOperation = 'copy';
		ctx.beginPath();
		ctx.rect(x, y, w, h);
		ctx.clip();
		ctx.imageSmoothingEnabled = true;
		ctx.imageSmoothingQuality = 'high';
		ctx.drawImage(texture.canvas, x, y, w, h);
		ctx.restore();
		const gm = session.glb.json.materials && session.glb.json.materials[m.gltfMaterial];
		const factor = gm && gm.pbrMetallicRoughness && gm.pbrMetallicRoughness.baseColorFactor || [1, 1, 1, 1];
		const opaque = m.alphaMode === 'OPAQUE';
		if (opaque || factor.some((f) => f !== 1)) {
			const data = ctx.getImageData(x, y, w, h);
			const d = data.data;
			for (let i = 0; i < d.length; i += 4) {
				d[i] *= factor[0];
				d[i + 1] *= factor[1];
				d[i + 2] *= factor[2];
				d[i + 3] = opaque ? 255 : d[i + 3] * factor[3];
			}
			ctx.putImageData(data, x, y);
		}
		atlas.texture.needsUpdate = true;
	}
};

/** Points every YSM preview of the session's model at the session's atlas canvases. */
YSM3D.paint.attach = function (project) {
	const session = YSM3D.paint.session(project);
	if (!session) return;
	for (const p of YSM3D.siblingProjects(project)) {
		const rt = YSM3D.rt(p);
		if (!rt) continue;
		rt.preview.groups.forEach((g, gi) => {
			const atlas = YSM3D.paint.atlas(session, g.texture || 0);
			rt.preview.setGroupMap(gi, atlas.texture);
		});
	}
	YSM3D.paint.updatePreviewMaps();
};

// ---- "预览贴图": the selected map on its materials
YSM3D.paint.mapTexture = function (session, texture, use, rigMaterial) {
	const key = texture.uuid + '|' + rigMaterial.name + '|' + use.kind;
	let t = session.mapTextures[key];
	if (!t) {
		t = new THREE.CanvasTexture(texture.canvas);
		t.flipY = false;
		t.ysm3dSource = texture;
		const tiled = YSM3D.kindTiled(use.kind);
		t.wrapS = t.wrapT = tiled ? THREE.RepeatWrapping : THREE.ClampToEdgeWrapping;
		// atlas uv U → material uv u = (U - off) / sc → map uv: tiled maps use Unity's _ST in Unity's v-up space:
		// x = u·sx + ox, y(top-based) = v·sy + (1 - sy - oy).
		const [offU, offV] = rigMaterial.uvOffset || [0, 0];
		const [scU, scV] = rigMaterial.uvScale || [1, 1];
		const [sx, sy] = tiled && use.scale ? use.scale : [1, 1];
		const [ox, oy] = tiled && use.offset ? use.offset : [0, 0];
		const a = sx / scU;
		const b = ox - offU * a;
		const c = sy / scV;
		const d = (tiled ? 1 - sy - oy : 0) - offV * c;
		t.matrixAutoUpdate = false;
		t.matrix.set(a, 0, b, 0, c, d, 0, 0, 1);
		session.mapTextures[key] = t;
	}
	return t;
};

YSM3D.paint.updatePreviewMaps = function () {
	const project = Project;
	const session = YSM3D.paint.session(project);
	if (!session) return;
	const selected = YSM3D.paint.previewOn && Texture.selected && Texture.selected.ysm3d_image >= 0 ? Texture.selected : null;
	const uses = selected ? session.uses.filter((u) => u.image === selected.ysm3d_image && u.kind !== 'baseColor'
		&& YSM3D.kindOnMesh(u.kind)) : [];
	for (const p of YSM3D.siblingProjects(project)) {
		const rt = YSM3D.rt(p);
		if (!rt) continue;
		rt.preview.groups.forEach((g, gi) => {
			const m = session.rig.materials && session.rig.materials[g.material];
			const use = m && uses.find((u) => u.material === m.name);
			const map = use ? YSM3D.paint.mapTexture(session, selected, use, m) : YSM3D.paint.atlas(session, g.texture || 0).texture;
			rt.preview.setGroupMap(gi, map);
		});
	}
	if (selected && !uses.length && YSM3D.paint.previewOn && selected.ysm3d_image >= 0) {
		const base = session.uses.some((u) => u.image === selected.ysm3d_image && u.kind === 'baseColor');
		if (!base) YSM3D.message('这张贴图不按模型 UV 采样（色带 / LUT / Matcap / 叠加层），只能在 2D 编辑器里查看。');
	}
};

/** Throttled: re-composite edited base colours, refresh map previews. */
YSM3D.paint.tick = function () {
	const session = YSM3D.paint.session(Project);
	if (!session || !session.dirty.size) return;
	const now = performance.now();
	if (session.lastTick && now - session.lastTick < 250) return;
	session.lastTick = now;
	const dirty = [...session.dirty];
	session.dirty.clear();
	for (const t of Texture.all) {
		if (!(t.ysm3d_image >= 0) || !dirty.includes(t.ysm3d_image) || !t.canvas) continue;
		YSM3D.paint.composite(session, t.ysm3d_image, t);
		for (const key in session.mapTextures) {
			if (session.mapTextures[key].ysm3dSource === t) session.mapTextures[key].needsUpdate = true;
		}
	}
};

YSM3D.paint.onEditTexture = function (data) {
	const t = data && data.texture;
	if (!t || !(t.ysm3d_image >= 0)) return;
	t.ysm3d_edited = true;
	const session = YSM3D.paint.session(Project);
	if (session) session.dirty.add(t.ysm3d_image);
};
YSM3D.paint.onUndoRedo = function () {
	const session = YSM3D.paint.session(Project);
	if (!session) return;
	for (const t of Texture.all) if (t.ysm3d_image >= 0 && t.ysm3d_edited) session.dirty.add(t.ysm3d_image);
};

// ---- write-back
YSM3D.paint.save = async function () {
	const project = Project;
	if (!YSM3D.isYsmProject(project)) return;
	const edited = Texture.all.filter((t) => t.ysm3d_image >= 0 && t.ysm3d_edited);
	if (!edited.length) {
		YSM3D.message('没有修改过的模型贴图。');
		return;
	}
	let session = YSM3D.paint.session(project);
	if (!session || !session.glb) {
		const {bytes, path, name} = await YSM3D.paint.readVrm(project);
		const glb = YSM3D.Vrm.parse(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));
		session = Object.assign(session || {key: project.ysm3d.model_key || project.ysm3d.model, rig: YSM3D.rt(project)?.rig,
			uses: YSM3D.collectImageUses(glb.json), dirty: new Set(), atlases: {}, mapTextures: {}},
		{glb, path, name: name || (path ? path.split(/[\\/]/).pop() : project.ysm3d.model + '.vrm')});
		YSM3D.paint.sessions[session.key] = session;
	}
	const replacements = {};
	for (const t of edited) {
		replacements[t.ysm3d_image] = {bytes: await YSM3D.canvasPng(t.canvas), mimeType: 'image/png'};
	}
	const out = YSM3D.Vrm.build(session.glb, replacements);
	if (isApp && session.path) {
		const fs = YSM3D.fs();
		try {
			const original = session.path + '.original.bak';
			if (!YSM3D.exists(original)) fs.copyFileSync(session.path, original);
			fs.copyFileSync(session.path, session.path + '.bak');
			fs.writeFileSync(session.path, out);
		} catch (err) {
			YSM3D.error('写回 VRM 失败', err);
			return;
		}
		YSM3D.message(`已写回 ${session.path}（${edited.length} 张贴图；旧文件备份为 .bak）。游戏里 /ysm model reload 生效。`, 5000);
	} else {
		Blockbench.export({resource_id: 'ysm3d_vrm', type: 'VRM 模型', extensions: ['vrm'], name: session.name.replace(/\.vrm$/i, ''),
			content: out, savetype: 'buffer'});
		YSM3D.message(`已下载 ${session.name}：替换模型文件夹里的原文件（先自行备份），游戏里 /ysm model reload。`, 5000);
	}
	session.glb = YSM3D.Vrm.parse(out.buffer);
	for (const t of edited) t.ysm3d_edited = false;
	YSM3D.paint.lastWrite = out;
};

YSM3D.paint.register = function () {
	YSM3D.onUnload(new Property(Texture, 'number', 'ysm3d_image', {default: -1, exposed: false}));
	YSM3D.onUnload(new Property(Texture, 'boolean', 'ysm3d_edited', {exposed: false}));
	const inYsm = () => YSM3D.isYsmProject(Project);
	const add = (action, menus) => {
		for (const m of menus || []) MenuBar.addAction(action, m);
		YSM3D.onUnload(action);
		return action;
	};
	add(new Action('ysm3d_paint_open', {
		name: '载入模型贴图（绘制）', description: '把 VRM 的贴图和 endfield_npr 渲染贴图作为 Blockbench 贴图载入，按材质分组',
		icon: 'palette', category: 'textures', condition: inYsm,
		click: () => YSM3D.paint.open().catch((err) => YSM3D.error('载入贴图失败', err)),
	}), ['image', 'file.import']);
	add(new Action('ysm3d_paint_save', {
		name: '写回 VRM', description: '把修改过的贴图写回模型的 VRM 文件（保留备份）', icon: 'save_as', category: 'textures',
		condition: () => inYsm() && Texture.all.some((t) => t.ysm3d_image >= 0),
		click: () => YSM3D.paint.save().catch((err) => YSM3D.error('写回 VRM 失败', err)),
	}), ['image', 'file.export']);
	add(new Toggle('ysm3d_paint_preview', {
		name: '预览贴图', description: '在 3D 预览的模型上显示选中的渲染贴图（法线、属性图、线条……），按普通贴图显示',
		icon: 'texture', category: 'textures', value: false, condition: inYsm,
		onChange(value) {
			YSM3D.paint.previewOn = value;
			YSM3D.paint.updatePreviewMaps();
		},
	}), ['image', 'view']);
	const on = (name, fn) => {
		Blockbench.on(name, fn);
		YSM3D.onUnload(() => Blockbench.removeListener(name, fn));
	};
	on('edit_texture', (d) => YSM3D.paint.onEditTexture(d));
	on('undo', () => YSM3D.paint.onUndoRedo());
	on('redo', () => YSM3D.paint.onUndoRedo());
	on('select_texture', () => YSM3D.paint.updatePreviewMaps());
	on('render_frame', () => YSM3D.paint.tick());
};

// Skinned mesh preview: a THREE.SkinnedMesh per project, driven by the project's Blockbench groups.
//
// Spaces: Blockbench's Bedrock codec negates pivot x and rotation X/Y on import (bedrock.js parseBone), so its scene
// space is YSM's internal model space in pixels, and a group's mesh.matrixWorld is YSM's bone pose
// T(p) RotZYX(rest + key) T(-p) chained down the hierarchy (keyframes are imported with X/Y negated too).
// The rig mesh is in that scene space in YSM's zero-rotation bind pose ("bind": "zero"), so the skin matrix of bone b is
//     group_b.mesh.matrixWorld · T(-pivot_b)
// (the native skinning convention: the bone pose itself applied to bind-space vertices). Bones are plain THREE.Bone
// objects outside the scene graph; their matrixWorld is written right before three.js uploads the bone texture
// (Skeleton.update, called once per render for visible skinned meshes), so the mesh follows the very frame Blockbench
// renders: posing, playback and scrubbing alike. The meshes hang under Project.model_3d, so they switch with the
// project tab, and they are not elements: never saved, never raycast, never selected.

YSM3D.textureFromPng = function (base64, onload) {
	const image = new Image();
	const texture = new THREE.Texture(image);
	texture.flipY = false;  // rig uvs are top-left based like Minecraft's
	texture.magFilter = THREE.LinearFilter;
	texture.minFilter = THREE.LinearMipmapLinearFilter;
	texture.generateMipmaps = true;
	image.onload = () => {
		texture.needsUpdate = true;
		if (onload) onload(texture);
	};
	image.src = 'data:image/png;base64,' + base64;
	return texture;
};

/** Root bone name (top of the parent chain) per bone, from the Bedrock geo. */
YSM3D.boneRoots = function (geoBones) {
	const parent = {};
	for (const b of geoBones) parent[b.name] = b.parent || null;
	const roots = {};
	for (const b of geoBones) {
		let n = b.name;
		for (let guard = 0; parent[n] && guard < 512; guard++) n = parent[n];
		roots[b.name] = n;
	}
	return roots;
};

YSM3D.SkinPreview = class SkinPreview {
	/**
	 * @param rig    the decoded .ysmrig.json
	 * @param role   'main' or 'arm'
	 * @param split  for the arm rig: split the triangles by root bone (LeftArm / RightArm) into separate meshes
	 */
	constructor(rig, role, split) {
		const r = rig.rigs[role];
		const mesh = r.mesh;
		const geo = YSM3D.parseMaybe(r.geo);
		const geoBones = (geo['minecraft:geometry'][0].bones) || [];
		this.role = role;
		this.boneNames = mesh.boneNames.slice();

		// Inverse bind matrices T(-pivot) in scene space (pivot_scene = (-x, y, z) of the Bedrock pivot).
		const pivots = {};
		for (const b of geoBones) {
			const p = b.pivot || [0, 0, 0];
			pivots[b.name] = [-p[0], p[1], p[2]];
		}
		this.invBind = this.boneNames.map((n) => {
			const p = pivots[n] || [0, 0, 0];
			return new THREE.Matrix4().makeTranslation(-p[0], -p[1], -p[2]);
		});
		this.bones = this.boneNames.map((n) => {
			const bone = new THREE.Bone();
			bone.name = n;
			bone.matrixAutoUpdate = false;
			return bone;
		});
		this.skeleton = new THREE.Skeleton(this.bones, this.bones.map(() => new THREE.Matrix4()));
		const skeletonUpdate = this.skeleton.update.bind(this.skeleton);
		this.skeleton.update = () => {
			this.updateBones();
			skeletonUpdate();
		};

		const positions = YSM3D.f32(mesh.positions);
		const geometryAttributes = {
			position: new THREE.BufferAttribute(positions, 3),
			normal: new THREE.BufferAttribute(YSM3D.f32(mesh.normals), 3),
			uv: new THREE.BufferAttribute(YSM3D.f32(mesh.uvs), 2),
			skinIndex: new THREE.BufferAttribute(YSM3D.u16(mesh.joints), 4),
			skinWeight: new THREE.BufferAttribute(YSM3D.f32(mesh.weights), 4),
		};
		this.attributes = geometryAttributes;
		const indices = YSM3D.u32(mesh.indices);
		const groups = (mesh.groups && mesh.groups.length) ? mesh.groups : [{start: 0, count: indices.length, texture: 0}];
		this.groups = groups;

		// Part of every triangle: the root bone of its first vertex's strongest joint (arm rig), else one part.
		const roots = YSM3D.boneRoots(geoBones);
		const joints = geometryAttributes.skinIndex.array;
		const weights = geometryAttributes.skinWeight.array;
		const partOfVertex = (v) => {
			let best = 0;
			for (let k = 1; k < 4; k++) if (weights[v * 4 + k] > weights[v * 4 + best]) best = k;
			return roots[this.boneNames[joints[v * 4 + best]]] || '';
		};
		const parts = {};
		groups.forEach((g, gi) => {
			if (YSM3D.isOutlineGroup(g, rig)) return;
			for (let t = g.start; t < g.start + g.count; t += 3) {
				const key = split ? partOfVertex(indices[t]) : 'all';
				const part = parts[key] || (parts[key] = groups.map(() => []));
				part[gi].push(t);
			}
		});

		this.textures = (rig.textures || []).map((t) => YSM3D.textureFromPng(t.png));
		this.materials = groups.map((g) => YSM3D.previewMaterial(g, this.textures[g.texture || 0]));

		this.root = new THREE.Object3D();
		this.root.name = 'ysm3d_preview';
		this.meshes = {};
		for (const key in parts) {
			const lists = parts[key];
			let total = 0;
			lists.forEach((l) => total += l.length * 3);
			const index = new Uint32Array(total);
			const geometry = new THREE.BufferGeometry();
			for (const name in geometryAttributes) geometry.setAttribute(name, geometryAttributes[name]);
			let o = 0;
			lists.forEach((list, gi) => {
				const start = o;
				for (const t of list) {
					index[o++] = indices[t];
					index[o++] = indices[t + 1];
					index[o++] = indices[t + 2];
				}
				if (o > start) geometry.addGroup(start, o - start, gi);
			});
			geometry.setIndex(new THREE.BufferAttribute(index, 1));
			geometry.computeBoundingSphere();
			const skinned = new THREE.SkinnedMesh(geometry, this.materials);
			skinned.name = 'ysm3d_skin_' + key;
			skinned.frustumCulled = false;  // the bind-pose bounds don't follow the pose
			skinned.bind(this.skeleton, new THREE.Matrix4());
			skinned.raycast = () => {};
			this.meshes[key] = skinned;
			this.root.add(skinned);
		}
		this._identity = new THREE.Matrix4();
	}

	attach(project) {
		this.project = project;
		project.model_3d.add(this.root);
	}

	/** Writes every bone's skin matrix from its Blockbench group (called from Skeleton.update during rendering). */
	updateBones() {
		const project = this.project;
		if (!project || typeof Project === 'undefined' || Project !== project) return;
		if (!this._groupMap || this._groupCount !== Group.all.length || this._groupStamp !== YSM3D.groupStamp) {
			this._groupMap = {};
			for (const g of Group.all) this._groupMap[g.name] = g;
			this._groupCount = Group.all.length;
			this._groupStamp = YSM3D.groupStamp;
		}
		const base = project.model_3d.matrixWorld;
		for (let i = 0; i < this.bones.length; i++) {
			const group = this._groupMap[this.boneNames[i]];
			const m = this.bones[i].matrixWorld;
			if (group && group.mesh) {
				m.multiplyMatrices(group.mesh.matrixWorld, this.invBind[i]);
			} else {
				m.copy(base);  // bone missing from the outliner: zero pose
			}
		}
	}

	setPartVisible(key, visible) {
		if (this.meshes[key]) this.meshes[key].visible = visible;
	}

	/** Replaces the image of a material group's texture (painting preview). */
	setGroupMap(groupIndex, texture) {
		const mat = this.materials[groupIndex];
		if (!mat) return;
		mat.map = texture;
		mat.needsUpdate = true;
	}

	dispose() {
		if (this.root.parent) this.root.parent.remove(this.root);
		const seen = new Set();
		for (const key in this.meshes) {
			const g = this.meshes[key].geometry;
			if (!seen.has(g)) g.dispose();
			seen.add(g);
		}
		this.materials.forEach((m) => m.dispose());
		this.textures.forEach((t) => t.dispose());
		this.skeleton.dispose();
	}
};

/** Outline hull groups (inverted hull drawn by the game's outline pass) are left out of the preview. */
YSM3D.isOutlineGroup = function (g, rig) {
	const m = rig && rig.materials && typeof g.material === 'number' ? rig.materials[g.material] : null;
	return !!((m && m.outline) || g.outline || g.alpha === 'outline');
};

YSM3D.previewMaterial = function (group, texture) {
	const alpha = group.alpha || 'opaque';
	const material = new THREE.MeshLambertMaterial({
		map: texture || null,
		side: THREE.DoubleSide,
		transparent: alpha === 'blend',
		alphaTest: alpha === 'cutout' ? (typeof group.cutoff === 'number' ? group.cutoff : 0.5) : (alpha === 'blend' ? 0.004 : 0),
		depthWrite: true,
	});
	material.skinning = true;  // three r129 (Blockbench 4.x/5.x) still needs the flag
	return material;
};

// Animation files: the read-only generated set ("生成动作"), the user's clips ("我的动作"), copy and export.
// Every animation of a YSM project carries `ysm3d_kind` ('generated' | 'user'), saved in the .bbmodel.

YSM3D.animCodec = function () {
	return (typeof AnimationCodec !== 'undefined' && AnimationCodec.codecs && AnimationCodec.codecs.bedrock) || null;
};
/** Bedrock animation JSON → new Animation objects (Blockbench 5: AnimationCodec; 4.x: Animator.loadFile). */
YSM3D.loadAnimations = function (path, json, filter) {
	const file = {path, name: path, content: JSON.stringify(json), json};
	const codec = YSM3D.animCodec();
	const list = codec ? codec.loadFile(file, filter) : Animator.loadFile(file, filter);
	return list || [];
};
/**
 * Animation → Bedrock animation JSON of that clip, as Blockbench exports it, but with exact keyframe times: Blockbench
 * rounds every timecode to the clip's snapping (Timeline.snapTime, at most 120 fps), which would move the keyframes of
 * generated clips whose times are not on such a grid (e.g. walk: 0.7697 s / 28). Times keep 4 decimals, like YSM's own.
 */
YSM3D.compileAnimation = function (animation) {
	const codec = YSM3D.animCodec();
	const snapTime = Timeline.snapTime;
	Timeline.snapTime = function (time, anim) {
		if (time === undefined || isNaN(time)) return snapTime.call(this, time, anim);
		return Math.max(0, Math.round(time * 10000) / 10000);
	};
	try {
		return codec ? codec.compileAnimation(animation) : animation.compileBedrockAnimation();
	} finally {
		Timeline.snapTime = snapTime;
	}
};

YSM3D.isYsmProject = (project) => !!(project && project.ysm3d && project.ysm3d.role);
YSM3D.roleOf = (project) => YSM3D.ROLES[project.ysm3d.role];

/** Tags animations without a kind (e.g. made with Blockbench's "add animation") as user clips. */
YSM3D.claimAnimations = function () {
	if (!YSM3D.isYsmProject(Project)) return;
	for (const a of Animation.all) {
		if (a.ysm3d_kind !== 'generated' && a.ysm3d_kind !== 'user') a.ysm3d_kind = 'user';
		const path = a.ysm3d_kind === 'generated' ? YSM3D.GEN_PATH : YSM3D.USER_PATH;
		if (a.path !== path) a.path = path;
		if (a.ysm3d_kind === 'generated') a.saved = true;
	}
};

YSM3D.loadGenerated = function (json) {
	const list = YSM3D.loadAnimations(YSM3D.GEN_PATH, json);
	for (const a of list) {
		a.ysm3d_kind = 'generated';
		a.path = YSM3D.GEN_PATH;  // Blockbench only merges `path` on desktop
		a.saved = true;
	}
	return list;
};

YSM3D.loadUser = function (json, saved) {
	YSM3D.allowEdit = true;
	try {
		const names = Object.keys(json.animations || {});
		// a clip already in "我的动作" with the same name is replaced
		for (const a of Animation.all.slice()) {
			if (a.ysm3d_kind === 'user' && names.includes(a.name)) a.remove(false, false);
		}
		const list = YSM3D.loadAnimations(YSM3D.USER_PATH, json);
		for (const a of list) {
			a.ysm3d_kind = 'user';
			a.path = YSM3D.USER_PATH;
			a.saved = !!saved;
		}
		return list;
	} finally {
		YSM3D.allowEdit = false;
	}
};

/** Copies generated clips into "我的动作" (same name = overrides the generated clip in game). */
YSM3D.copyToMine = function (animations) {
	const sources = animations.filter((a) => a && a.ysm3d_kind === 'generated');
	if (!sources.length) {
		YSM3D.message('请先在“生成动作”里选中一个动作。');
		return;
	}
	const existing = sources.filter((s) => Animation.all.some((a) => a.ysm3d_kind === 'user' && a.name === s.name));
	const run = () => {
		const json = {format_version: '1.8.0', animations: {}};
		for (const s of sources) json.animations[s.name] = YSM3D.compileAnimation(s);
		YSM3D.allowEdit = true;
		try {
			Undo.initEdit({animations: []});
			for (const a of Animation.all.slice()) {
				if (a.ysm3d_kind === 'user' && json.animations[a.name]) a.remove(false, false);
			}
			const list = YSM3D.loadAnimations(YSM3D.USER_PATH, json);
			for (const a of list) {
				a.ysm3d_kind = 'user';
				a.path = YSM3D.USER_PATH;
				a.saved = false;
			}
			Undo.finishEdit('复制到我的动作', {animations: list});
			if (list[0]) list[0].select();
			YSM3D.message(`已复制 ${list.length} 个动作到“我的动作”。同名动作会在游戏里覆盖生成的动作。`);
		} finally {
			YSM3D.allowEdit = false;
		}
	};
	if (existing.length) {
		Blockbench.showMessageBox({
			title: '复制到我的动作',
			message: '“我的动作”里已有同名动作：' + existing.map((a) => a.name).join('、') + '。要用生成的版本替换吗？',
			buttons: ['替换', 'dialog.cancel'],
		}, (button) => {
			if (button === 0) run();
		});
	} else {
		run();
	}
};

/** {path, json} of the current project's user clips: only "我的动作", in Bedrock animation format. */
YSM3D.compileUserFile = function () {
	const role = YSM3D.roleOf(Project);
	const json = {format_version: '1.8.0', animations: {}};
	for (const a of Animation.all) {
		if (a.ysm3d_kind === 'user') json.animations[a.name] = YSM3D.compileAnimation(a);
	}
	return {file: role.file, json};
};

/** Model folder of a project (desktop), asking once if the rig didn't say. */
YSM3D.modelFolder = function (project, ask) {
	const info = project.ysm3d;
	if (info.model_dir && YSM3D.exists(info.model_dir)) return info.model_dir;
	if (!ask || !isApp) return '';
	const dir = Blockbench.pickDirectory({title: '选择模型文件夹（.vrm 所在的文件夹）', resource_id: 'ysm3d_model'});
	if (dir) {
		for (const p of YSM3D.siblingProjects(project)) p.ysm3d.model_dir = dir;
	}
	return dir || '';
};

/**
 * Exports the current project's user clips. Desktop: writes <model folder>/animations/<set>.animation.json; web:
 * downloads the file. Clips of a set without user clips: an existing file is replaced by an empty set ("animations": {}).
 */
YSM3D.exportCurrent = function (quiet) {
	if (!YSM3D.isYsmProject(Project)) return false;
	YSM3D.claimAnimations();
	const {file, json} = YSM3D.compileUserFile();
	const count = Object.keys(json.animations).length;
	const text = typeof compileJSON === 'function' ? compileJSON(json) : JSON.stringify(json, null, '\t');
	if (isApp) {
		const folder = YSM3D.modelFolder(Project, true);
		if (!folder) return false;
		// humanoid.json may name another overlay path (the rig's overlay.<set>.file, relative to the model folder)
		const target = YSM3D.joinPath(folder, ...(Project.ysm3d.overlay_file || 'animations/' + file).split(/[\\/]/));
		// no user clips and no file yet: write nothing (a model without overlay files imports exactly as before)
		if (!count && !YSM3D.exists(target)) return false;
		const fs = YSM3D.fs();
		try {
			fs.mkdirSync(YSM3D.dirname(target), {recursive: true});
			fs.writeFileSync(target, text, {encoding: 'utf-8'});
		} catch (err) {
			YSM3D.error('导出失败', err);
			return false;
		}
		for (const a of Animation.all) if (a.ysm3d_kind === 'user') a.saved = true;
		if (!quiet) YSM3D.message(`已写入 ${target}（${count} 个动作）。游戏里 /ysm model reload 生效。`, 4000);
		return target;
	}
	if (!count) {
		if (!quiet) YSM3D.message('“我的动作”是空的，没有要导出的内容。');
		return false;
	}
	Blockbench.export({
		resource_id: 'ysm3d_animation',
		type: 'Bedrock Animation',
		extensions: ['json'],
		name: file.replace(/\.json$/, ''),
		content: text,
		savetype: 'text',
	}, () => {
		for (const a of Animation.all) if (a.ysm3d_kind === 'user') a.saved = true;
	});
	if (!quiet) YSM3D.message(`已下载 ${file}：放进模型文件夹的 animations/ 里，游戏里 /ysm model reload。`, 4000);
	return file;
};

/** Both tabs of the model: selects each, exports, comes back. */
YSM3D.exportAll = function () {
	if (!YSM3D.isYsmProject(Project)) return;
	const start = Project;
	const written = [];
	for (const p of YSM3D.siblingProjects(start)) {
		if (Project !== p) p.select();
		const r = YSM3D.exportCurrent(true);
		if (r) written.push(r);
	}
	if (Project !== start) start.select();
	if (written.length) YSM3D.message('已导出：\n' + written.join('\n') + '\n游戏里 /ysm model reload 生效。', 5000);
	else YSM3D.message('“我的动作”是空的，没有要导出的内容。');
};

YSM3D.siblingProjects = function (project) {
	const key = project.ysm3d.model_key || project.ysm3d.model;
	return ModelProject.all.filter((p) => YSM3D.isYsmProject(p) && (p.ysm3d.model_key || p.ysm3d.model) === key);
};

// ---- read-only guard for "生成动作"
// An edit that touches a generated clip (keyframes, its properties) is undone right after it lands.
YSM3D.allowEdit = false;
YSM3D.guardEdit = function (data) {
	if (YSM3D.allowEdit || !YSM3D.isYsmProject(Project) || !data || !data.aspects) return;
	const aspects = data.aspects;
	const touched = new Set();
	if (aspects.keyframes) {
		for (const kf of aspects.keyframes) {
			const anim = kf && kf.animator && kf.animator.animation;
			if (anim) touched.add(anim);
		}
		if (Animation.selected) touched.add(Animation.selected);
	}
	if (aspects.animations) for (const a of aspects.animations) touched.add(a);
	const generated = [...touched].filter((a) => a && a.ysm3d_kind === 'generated' && Animation.all.includes(a));
	if (generated.length) {
		setTimeout(() => {
			Undo.undo();
			Blockbench.showQuickMessage('“生成动作”是只读的：右键动作 →“复制到我的动作”后再编辑。', 3500);
		}, 0);
	}
};

// Blockbench's own save of an animation file (the save icon in the Animations panel, Ctrl+S in the Bedrock format)
// would write to the virtual paths; route it to the plugin export instead.
YSM3D.wrapAnimationSaving = function () {
	const isOurs = (path) => path === YSM3D.USER_PATH || path === YSM3D.GEN_PATH
		|| (YSM3D.isYsmProject(Project) && Animation.all.some((a) => a.path === path && a.ysm3d_kind));
	const wrap = (owner, name, test) => {
		if (!owner || typeof owner[name] !== 'function') return;
		const original = owner[name];
		owner[name] = function (...args) {
			if (test.apply(this, args)) {
				if (this && this.ysm3d_kind === 'generated') return;
				YSM3D.exportCurrent();
				return;
			}
			return original.apply(this, args);
		};
		YSM3D.onUnload(() => owner[name] = original);
	};
	const codec = YSM3D.animCodec();
	if (codec) {
		wrap(codec, 'exportFile', (path) => isOurs(path));
		wrap(codec, 'saveAnimation', (animation) => !!(animation && animation.ysm3d_kind));
	}
	if (Animator && typeof Animator.exportAnimationFile === 'function') wrap(Animator, 'exportAnimationFile', (path) => isOurs(path));
	wrap(Animation.prototype, 'save', function () { return !!this.ysm3d_kind; });
};

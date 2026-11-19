// Project tabs: "<model> 第三人称" (main rig) and "<model> 第一人称" (arm rig), their previews, and restoring them
// from a saved .bbmodel.

// Runtime state per project uuid: {preview, fp, rig}. Never saved.
YSM3D.runtime = {};
YSM3D.groupStamp = 0;
YSM3D.rt = (project) => project && YSM3D.runtime[project.uuid];

/**
 * Opens the two tabs for a rig. `info`: {rig_path, model_dir} (desktop; empty on the web).
 */
YSM3D.openRig = async function (rig, info) {
	YSM3D.checkRig(rig);
	info = info || {};
	const modelDir = info.model_dir || YSM3D.rigFolder(rig);
	const key = rig.model + '@' + (modelDir || 'web');
	YSM3D.rigs[key] = rig;
	const created = [];
	for (const role of ['main', 'arm']) {
		if (!rig.rigs[role]) continue;
		created.push(YSM3D.createProject(rig, role, {
			model: rig.model, model_key: key, role, model_dir: modelDir, rig_path: info.rig_path || '',
			source: YSM3D.rigSourceFile(rig), fp: role === 'arm' ? {mode: 'empty', pitch: 0} : undefined,
		}));
	}
	if (created[0] && Project !== created[0]) created[0].select();
	return created;
};

YSM3D.createProject = function (rig, role, info) {
	const r = rig.rigs[role];
	const geo = JSON.parse(JSON.stringify(YSM3D.parseMaybe(r.geo)));
	const description = geo['minecraft:geometry'][0].description || {};
	// Without an identifier Blockbench skips its client-entity lookup (BedrockEntityManager) on desktop.
	delete description.identifier;
	newProject(Formats.bedrock);
	const project = Project;
	Codecs.bedrock.parse(geo, '', {switch_to_existing_tab: false});
	project.name = rig.model + ' ' + YSM3D.ROLES[role].title;
	project.model_identifier = rig.model + '.' + role;
	project.ysm3d = info;
	if (typeof setProjectTitle === 'function') setProjectTitle();

	// The rig's set has the user's overlay applied (ysmrig-format.md "animations, animationSources, overlay"):
	// "生成动作" = the set without the overlay clips, plus the base clips the overlay replaced; "我的动作" = the overlay.
	const setName = YSM3D.ROLES[role].set;
	const set = rig.animations && YSM3D.parseMaybe(rig.animations[setName]);
	const overlay = rig.overlay && rig.overlay[setName];
	if (set && set.animations) {
		const userNames = overlay && overlay.clips || [];
		const base = {format_version: set.format_version || '1.8.0', animations: {}};
		const user = {format_version: base.format_version, animations: {}};
		for (const name in set.animations) {
			if (userNames.includes(name)) user.animations[name] = set.animations[name];
			else base.animations[name] = set.animations[name];
		}
		Object.assign(base.animations, overlay && overlay.replaced || {});
		YSM3D.loadGenerated(base);
		if (Object.keys(user.animations).length) YSM3D.loadUser(user, true);
	}
	if (overlay && overlay.file) info.overlay_file = overlay.file;
	YSM3D.attachPreview(project, rig);
	if (typeof Undo.history !== 'undefined') {
		Undo.history.length = 0;
		Undo.index = 0;
	}
	project.saved = true;
	return project;
};

/** Builds the skinned preview (and the first-person rig) of a project from its rig. */
YSM3D.attachPreview = function (project, rig) {
	YSM3D.detachPreview(project);
	const role = project.ysm3d.role;
	if (!rig.rigs[role]) throw '这个 .ysmrig.json 里没有 ' + role + ' 骨架。';
	const preview = new YSM3D.SkinPreview(rig, role, role === 'arm');
	preview.attach(project);
	const runtime = {preview, rig};
	YSM3D.runtime[project.uuid] = runtime;
	if (role === 'arm') {
		runtime.fp = new YSM3D.FirstPerson(project, preview);
		if (Project === project) {
			runtime.fp.apply();
			runtime.fp.camera(true);
		}
	}
	if (YSM3D.paint && YSM3D.paint.attach) YSM3D.paint.attach(project, rig);
	return runtime;
};

YSM3D.detachPreview = function (project) {
	const runtime = YSM3D.rt(project);
	if (!runtime) return;
	if (runtime.fp) runtime.fp.release();
	runtime.preview.dispose();
	delete YSM3D.runtime[project.uuid];
};

/** After a .bbmodel loads: rebuild the preview from the remembered rig (desktop) or ask for it (web). */
YSM3D.restore = function (project) {
	if (!YSM3D.isYsmProject(project) || YSM3D.rt(project)) return;
	const info = project.ysm3d;
	const key = info.model_key || info.model;
	YSM3D.claimAnimations();
	if (YSM3D.rigs[key]) {
		YSM3D.attachPreview(project, YSM3D.rigs[key]);
		return;
	}
	if (isApp && info.rig_path && YSM3D.exists(info.rig_path)) {
		Blockbench.read([info.rig_path], {readtype: 'text', errorbox: false}, (files) => {
			try {
				const rig = YSM3D.checkRig(JSON.parse(files[0].content));
				YSM3D.rigs[key] = rig;
				YSM3D.attachPreview(project, rig);
			} catch (err) {
				YSM3D.error('读取骨架文件失败', err);
			}
		});
		return;
	}
	const source = info.source || info.model_dir;
	Blockbench.showMessageBox({
		title: 'YSM 3D 预览',
		message: `这个项目属于模型「${info.model}」。要重建蒙皮预览，需要它的骨架：`
			+ (isApp && source ? `\n\n可以重新运行导出器（${source}），或打开 .ysmrig.json。` : '\n\n请打开它的 .ysmrig.json 文件。'),
		buttons: isApp && source ? ['重新导出', '打开 .ysmrig.json', 'dialog.cancel'] : ['打开 .ysmrig.json', 'dialog.cancel'],
	}, (button) => {
		const rerun = isApp && source;
		if (rerun && button === 0) {
			YSM3D.runExporter(source).then(({rig, path}) => {
				YSM3D.rigs[key] = rig;
				for (const p of YSM3D.siblingProjects(project)) p.ysm3d.rig_path = path;
				YSM3D.attachPreview(project, rig);
			}, (err) => YSM3D.error('导出失败', err));
		} else if (button === (rerun ? 1 : 0)) {
			YSM3D.pickRig((rig, path) => {
				YSM3D.rigs[key] = rig;
				if (path) project.ysm3d.rig_path = path;
				YSM3D.attachPreview(project, rig);
			});
		}
	});
};

YSM3D.pickRig = function (callback) {
	Blockbench.import({
		resource_id: 'ysm3d_rig',
		extensions: ['json'],
		type: 'YSM 骨架文件 (.ysmrig.json)',
		readtype: 'text',
	}, (files) => {
		try {
			callback(YSM3D.checkRig(JSON.parse(files[0].content)), isApp ? files[0].path : '');
		} catch (err) {
			YSM3D.error('无法打开骨架文件', err);
		}
	});
};

// ---- project switching
YSM3D.gridGroup = () => Canvas.scene.children.find((c) => c.name === 'grid_group');

YSM3D.onSelectProject = function () {
	const runtime = YSM3D.rt(Project);
	if (YSM3D.isYsmProject(Project) && !runtime) YSM3D.restore(Project);
	const fp = runtime && runtime.fp;
	if (fp) {
		fp.apply();
		if (!fp.cameraSet) {
			fp.camera(true);
			fp.cameraSet = true;
		} else {
			Preview.all.forEach((p) => p.setFOV(fp.fov()));
		}
	} else {
		YSM3D.applyMolangEnv({});
		Preview.all.forEach((p) => p.setFOV(settings.fov.value));
	}
};

/** Per frame: keep the first-person hooks, hide the floor grid at eye height in first person. */
YSM3D.onRenderFrame = function () {
	const runtime = YSM3D.rt(Project);
	const fp = runtime && runtime.fp;
	if (fp) fp.hookRoots();
	const grid = YSM3D.gridGroup();
	if (grid) {
		if (fp && grid.visible) {
			grid.visible = false;
			YSM3D._gridHidden = true;
		} else if (!fp && YSM3D._gridHidden) {
			grid.visible = true;
			YSM3D._gridHidden = false;
		}
	}
};

YSM3D.onFinishedEdit = function (data) {
	YSM3D.groupStamp++;
	YSM3D.claimAnimations();
	YSM3D.guardEdit(data);
};

YSM3D.firstPersonDialog = function () {
	const runtime = YSM3D.rt(Project);
	if (!runtime || !runtime.fp) {
		YSM3D.message('请先切到“第一人称”标签页。');
		return;
	}
	const s = runtime.fp.state();
	const form = {
		mode: {label: '手持状态', type: 'select', options: Object.assign({}, YSM3D.FP_MODES), value: s.mode},
		fov: {label: '视场角 FOV', type: 'number', value: s.fov, min: 30, max: 110, step: 1},
		pitch: {label: '视线俯仰（双手地图用，度）', type: 'number', value: s.pitch, min: -90, max: 90, step: 1,
			condition: (f) => f.mode === 'map_two'},
		vanilla: {label: '显示原版手臂轮廓（参考）', type: 'checkbox', value: s.vanilla},
	};
	if (YSM3D.taczForm) Object.assign(form, YSM3D.taczForm(s));
	new Dialog({
		id: 'ysm3d_fp',
		title: '第一人称视图',
		width: 520,
		form,
		lines: ['<p style="font-size:12px;opacity:.8">相机在眼睛处、看向 -Z，手臂按 YSM CustomFirstPersonArmRenderer 摆放。原版只在空手和拿地图时画手臂；'
			+ '拿其他物品时游戏里看不到手臂。</p>'],
		onConfirm(result) {
			Project.ysm3d.fp = Object.assign({}, Project.ysm3d.fp, result);
			Project.saved = false;
			const done = () => {
				runtime.fp.apply();
				runtime.fp.camera(true);
			};
			if (YSM3D.taczApply) YSM3D.taczApply(runtime, result).then(done, (err) => YSM3D.error('TACZ 枪包', err));
			else done();
		},
	}).show();
};

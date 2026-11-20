// Registration: properties, actions, menus, the .ysmrig.json codec and event hooks.

YSM3D.importDialog = function () {
	if (!isApp) {
		YSM3D.pickRig((rig) => YSM3D.openRig(rig, {}).catch((err) => YSM3D.error('打开失败', err)));
		return;
	}
	new Dialog({
		id: 'ysm3d_import',
		title: '导入 YSM 3D 模型',
		width: 600,
		form: {
			folder: {label: '模型文件夹（.vrm 所在的文件夹）', type: 'folder', value: ''},
			file: {label: '或者：模型文件', type: 'file', extensions: ['vrm', 'pmx', 'pmd', 'xps', 'mesh'], filetype: 'VRM / PMX / PMD / XPS', value: ''},
			_note: {type: 'info', text: '导出的是游戏从这个文件夹实际加载的模型（ysm.json 指定的，或文件夹里最大的模型文件）。'},
			_info: {type: 'info', text: '将用 YSM 模组 jar 里的导入器（Java）生成骨架。Java / jar / libraries 的路径在 设置 → YSM 3D 动画 里，'
				+ '首次会自动检测 .minecraft。没有 Java 时，可以用“打开 .ysmrig.json”。'},
		},
		buttons: ['导入', '打开 .ysmrig.json', 'dialog.cancel'],
		cancelIndex: 2,
		onConfirm(result) {
			const model = result.file || result.folder;
			if (!model) {
				YSM3D.message('请选择模型文件夹或模型文件。');
				return false;
			}
			YSM3D.runExporter(model).then(({rig, path}) => {
				const folder = YSM3D.rigFolder(rig) || (result.file ? YSM3D.dirname(result.file) : result.folder);
				return YSM3D.openRig(rig, {rig_path: path, model_dir: folder});
			}).catch((err) => YSM3D.error('导入失败', err));
		},
		onButton(index) {
			if (index === 1) {
				this.hide();
				YSM3D.openRigFile();
			}
		},
	}).show();
};

YSM3D.openRigFile = function () {
	YSM3D.pickRig((rig, path) => YSM3D.openRig(rig, {rig_path: path}).catch((err) => YSM3D.error('打开失败', err)));
};

YSM3D.importUserFile = function () {
	if (!YSM3D.isYsmProject(Project)) return;
	Blockbench.import({resource_id: 'ysm3d_animation', extensions: ['json'], type: 'Bedrock 动画', readtype: 'text'}, (files) => {
		try {
			const json = JSON.parse(files[0].content);
			Undo.initEdit({animations: []});
			const list = YSM3D.loadUser(json, false);
			Undo.finishEdit('导入我的动作', {animations: list});
			YSM3D.message(`已导入 ${list.length} 个动作到“我的动作”。`);
		} catch (err) {
			YSM3D.error('导入失败', err);
		}
	});
};

YSM3D.registerActions = function () {
	const add = (action, menus) => {
		for (const m of menus || []) MenuBar.addAction(action, m);
		YSM3D.onUnload(action);
		return action;
	};
	const inYsm = () => YSM3D.isYsmProject(Project);
	add(new Action('ysm3d_import', {
		name: 'YSM 3D 模型', description: '导入 VRM/GLB 模型（运行 YSM 导出器），打开第三人称与第一人称两个标签页', icon: 'accessibility_new',
		category: 'file', click: () => YSM3D.importDialog(),
	}), ['file.import']);
	add(new Action('ysm3d_open_rig', {
		name: 'YSM 骨架文件 (.ysmrig.json)', description: '直接打开导出器生成的 .ysmrig.json（网页版用这个）', icon: 'file_open',
		category: 'file', click: () => YSM3D.openRigFile(),
	}), ['file.import']);
	add(new Action('ysm3d_export', {
		name: '导出我的动作 (YSM)', description: '把“我的动作”写入模型文件夹的 animations/main.animation.json 与 fp_arm.animation.json',
		icon: 'save', category: 'animation', condition: inYsm,
		click: () => YSM3D.exportAll(),
	}), ['file.export', 'animation']);
	const copy = add(new Action('ysm3d_copy_to_mine', {
		name: '复制到我的动作', description: '把选中的生成动作复制到“我的动作”以便编辑（同名会在游戏里覆盖生成的动作）',
		icon: 'content_copy', category: 'animation',
		condition: () => inYsm() && Animation.selected && Animation.selected.ysm3d_kind === 'generated',
		click: () => YSM3D.copyToMine(Animation.all.filter((a) => a.selected || a === Animation.selected)),
	}), ['animation']);
	if (Animation.prototype.menu && Animation.prototype.menu.addAction) {
		Animation.prototype.menu.addAction(copy, 0);
	}
	add(new Action('ysm3d_import_user', {
		name: '导入到我的动作…', description: '把已有的 Bedrock 动画文件读入“我的动作”', icon: 'playlist_add', category: 'animation',
		condition: inYsm, click: () => YSM3D.importUserFile(),
	}), ['animation']);
	add(new Action('ysm3d_first_person', {
		name: '第一人称视图…', description: '手持状态、FOV，并把相机放回眼睛处', icon: 'visibility', category: 'view',
		condition: () => inYsm() && Project.ysm3d.role === 'arm', click: () => YSM3D.firstPersonDialog(),
	}), ['view']);
	add(new Action('ysm3d_fp_camera', {
		name: '回到第一人称相机', icon: 'center_focus_strong', category: 'view',
		condition: () => inYsm() && Project.ysm3d.role === 'arm',
		click() {
			const rt = YSM3D.rt(Project);
			if (rt && rt.fp) rt.fp.camera(false);
		},
	}), ['view']);
	add(new Action('ysm3d_reload_preview', {
		name: '重新载入 YSM 预览…', description: '重新读取 .ysmrig.json 或重新运行导出器', icon: 'refresh', category: 'view',
		condition: inYsm,
		click() {
			YSM3D.detachPreview(Project);
			delete YSM3D.rigs[Project.ysm3d.model_key || Project.ysm3d.model];
			YSM3D.restore(Project);
		},
	}), ['view']);
};

YSM3D.registerCodec = function () {
	// Dropping a .ysmrig.json onto Blockbench (or opening it from the start screen) opens the two tabs.
	const codec = new Codec('ysm3d_rig', {
		name: 'YSM 骨架文件',
		extension: 'json',
		remember: false,
		load_filter: {
			type: 'json',
			extensions: ['json'],
			condition: (model) => YSM3D.isRig(model),
		},
		load(model, file) {
			YSM3D.openRig(model, {rig_path: isApp ? file.path : ''}).catch((err) => YSM3D.error('打开失败', err));
		},
	});
	YSM3D.onUnload(codec);
	// A rig has a top-level "textures" array, which Blockbench's Java block codec takes for its own (java_block.ts
	// load_filter: parent || elements || textures) and is asked first; keep every other JSON codec off rig files.
	for (const id in Codecs) {
		const filter = Codecs[id].load_filter;
		if (Codecs[id] === codec || !filter || filter.type !== 'json' || typeof filter.condition !== 'function') continue;
		const condition = filter.condition;
		filter.condition = function (model) {
			return !YSM3D.isRig(model) && condition.apply(this, arguments);
		};
		YSM3D.onUnload(() => filter.condition = condition);
	}
};

YSM3D.registerProperties = function () {
	YSM3D.onUnload(new Property(ModelProject, 'object', 'ysm3d', {default: null, exposed: false}));
	YSM3D.onUnload(new Property(Animation, 'string', 'ysm3d_kind', {exposed: false}));
};

YSM3D.registerEvents = function () {
	const on = (name, fn) => {
		Blockbench.on(name, fn);
		YSM3D.onUnload(() => Blockbench.removeListener(name, fn));
	};
	on('select_project', () => YSM3D.onSelectProject());
	on('load_project', () => setTimeout(() => YSM3D.restore(Project), 0));
	on('close_project', ({project}) => YSM3D.detachPreview(project || Project));
	on('finished_edit', (data) => YSM3D.onFinishedEdit(data));
	on('render_frame', () => YSM3D.onRenderFrame());
};

YSM3D.unloadAll = function () {
	for (const uuid in YSM3D.runtime) {
		const project = ModelProject.all.find((p) => p.uuid === uuid);
		if (project) YSM3D.detachPreview(project);
	}
	YSM3D.applyMolangEnv({});
	const grid = YSM3D.gridGroup();
	if (grid) grid.visible = true;
	for (const d of YSM3D.deletables.splice(0).reverse()) {
		try {
			d.delete();
		} catch (err) {
			console.warn('[YSM3D] unload', err);
		}
	}
};

(typeof BBPlugin !== 'undefined' ? BBPlugin : Plugin).register(YSM3D.PLUGIN_ID, {
	title: 'YSM 3D Animator',
	author: 'YSM pelica fork',
	description: '为 YSM 导入的 VRM/GLB 3D 模型制作第三人称与第一人称动画：蒙皮实时预览、生成动作参考、导出到模型文件夹。',
	icon: 'accessibility_new',
	version: YSM3D.VERSION,
	min_version: '4.12.0',
	variant: 'both',
	tags: ['Minecraft', 'Animation', 'YSM'],
	onload() {
		YSM3D.registerProperties();
		YSM3D.registerSettings();
		YSM3D.registerActions();
		YSM3D.registerCodec();
		YSM3D.registerEvents();
		YSM3D.wrapAnimationSaving();
		if (YSM3D.paint && YSM3D.paint.register) YSM3D.paint.register();
		for (const p of ModelProject.all) if (YSM3D.isYsmProject(p) && Project === p) YSM3D.restore(p);
	},
	onunload() {
		YSM3D.unloadAll();
	},
});

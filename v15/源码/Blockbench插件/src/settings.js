// Plugin settings (Settings → "YSM 3D 动画") and .minecraft auto-detection.

YSM3D.setting = function (id) {
	const s = settings['ysm3d_' + id];
	return s ? s.value : undefined;
};
YSM3D.setSetting = function (id, value) {
	const s = settings['ysm3d_' + id];
	if (!s) return;
	s.value = value;
	if (typeof Settings.saveLocalStorages === 'function') Settings.saveLocalStorages();
	else if (typeof Settings.save === 'function') Settings.save();
};

YSM3D.registerSettings = function () {
	if (typeof Settings.addCategory === 'function' && !Settings.structure.ysm3d) {
		Settings.addCategory('ysm3d', {name: 'YSM 3D 动画'});
		YSM3D.onUnload(() => delete Settings.structure.ysm3d);
	}
	const category = Settings.structure.ysm3d ? 'ysm3d' : 'general';
	const add = (id, data) => YSM3D.onUnload(new Setting('ysm3d_' + id, Object.assign({category}, data)));
	add('java', {name: 'Java 路径', description: 'Java 17 或更新版本的 java 可执行文件。留空则使用系统 PATH 里的 java。',
		type: 'text', value: ''});
	add('jar', {name: 'YSM 模组 jar', description: '带骨架导出器的 YSM 模组 jar（通常在 .minecraft/mods）。', type: 'text', value: ''});
	add('libraries', {name: 'Minecraft libraries 文件夹', description: '.minecraft/libraries（导出器需要 Forge 1.20.1 的运行库）。',
		type: 'text', value: ''});
	add('fov', {name: '第一人称视场角 (FOV)', description: 'Minecraft 画手时固定使用 70°（GameRenderer.renderItemInHand）。',
		type: 'number', value: 70, min: 30, max: 110});
	add('detect', {name: '自动检测 .minecraft', description: '查找 Java、YSM jar 与 libraries 并填入上面的设置。', type: 'click',
		icon: 'search', click() { YSM3D.detectPaths(true); }});
};

/** Candidate .minecraft folders for this OS. */
YSM3D.minecraftDirs = function () {
	const info = YSM3D.systemInfo();
	if (!info) return [];
	const home = info.home_directory;
	const dirs = [];
	if (info.platform === 'win32') {
		if (info.appdata_directory) dirs.push(YSM3D.joinPath(info.appdata_directory, '.minecraft'));
		dirs.push(YSM3D.joinPath(home, 'AppData', 'Roaming', '.minecraft'));
	} else if (info.platform === 'darwin') {
		dirs.push(YSM3D.joinPath(home, 'Library', 'Application Support', 'minecraft'));
	} else {
		dirs.push(YSM3D.joinPath(home, '.minecraft'));
	}
	return dirs.filter((d, i) => dirs.indexOf(d) === i && YSM3D.exists(d));
};

YSM3D.listDir = function (dir) {
	try {
		return YSM3D.fs().readdirSync(dir);
	} catch (err) {
		return [];
	}
};
YSM3D.statSafe = function (file) {
	try {
		return YSM3D.fs().statSync(file);
	} catch (err) {
		return null;
	}
};

/** The newest YSM jar in <mc>/mods and <mc>/versions/<v>/mods (version-isolated launchers such as HMCL/PCL). */
YSM3D.findYsmJar = function (mcDirs) {
	const found = [];
	for (const mc of mcDirs) {
		const modDirs = [YSM3D.joinPath(mc, 'mods')];
		for (const v of YSM3D.listDir(YSM3D.joinPath(mc, 'versions'))) modDirs.push(YSM3D.joinPath(mc, 'versions', v, 'mods'));
		for (const dir of modDirs) {
			for (const f of YSM3D.listDir(dir)) {
				if (/\.jar$/i.test(f) && /(^ysm|yes.?steve)/i.test(f)) {
					const p = YSM3D.joinPath(dir, f);
					const st = YSM3D.statSafe(p);
					if (st) found.push({p, t: st.mtimeMs});
				}
			}
		}
	}
	found.sort((a, b) => b.t - a.t);
	return found.length ? found[0].p : '';
};

/** A Java 17+ runtime: the Minecraft launcher's bundled one first, then common install folders. */
YSM3D.findJava = function (mcDirs) {
	const info = YSM3D.systemInfo();
	const exe = info && info.platform === 'win32' ? 'java.exe' : 'java';
	const candidates = [];
	for (const mc of mcDirs) {
		const runtime = YSM3D.joinPath(mc, 'runtime');
		for (const name of ['java-runtime-gamma', 'java-runtime-delta', 'java-runtime-beta']) {
			for (const plat of YSM3D.listDir(YSM3D.joinPath(runtime, name))) {
				candidates.push(YSM3D.joinPath(runtime, name, plat, name, 'bin', exe));
			}
		}
	}
	if (info && info.platform === 'win32') {
		if (info.appdata_directory) {
			const local = YSM3D.joinPath(info.appdata_directory, '..', 'Local', 'Packages',
				'Microsoft.4297127D64EC6_8wekyb3d8bbwe', 'LocalCache', 'Local', 'runtime', 'java-runtime-gamma');
			for (const plat of YSM3D.listDir(local)) candidates.push(YSM3D.joinPath(local, plat, 'java-runtime-gamma', 'bin', exe));
		}
		for (const root of ['C:\\Program Files\\Java', 'C:\\Program Files\\Eclipse Adoptium', 'C:\\Program Files\\Microsoft',
			'C:\\Program Files\\Zulu', 'C:\\Program Files\\BellSoft']) {
			for (const d of YSM3D.listDir(root).sort().reverse()) candidates.push(YSM3D.joinPath(root, d, 'bin', exe));
		}
	}
	return candidates.find((c) => YSM3D.exists(c)) || '';
};

/** Fills empty settings (or all, when `force`) from the first .minecraft found. Returns a summary. */
YSM3D.detectPaths = function (force) {
	if (!isApp) {
		YSM3D.message('网页版无法运行 Java，请直接打开 .ysmrig.json。');
		return null;
	}
	const mcDirs = YSM3D.minecraftDirs();
	const result = {minecraft: mcDirs[0] || '', jar: '', libraries: '', java: ''};
	if (mcDirs.length) {
		result.jar = YSM3D.findYsmJar(mcDirs);
		const libs = mcDirs.map((d) => YSM3D.joinPath(d, 'libraries')).find((d) => YSM3D.exists(d));
		result.libraries = libs || '';
		result.java = YSM3D.findJava(mcDirs);
	}
	for (const key of ['jar', 'libraries', 'java']) {
		if (result[key] && (force || !YSM3D.setting(key))) YSM3D.setSetting(key, result[key]);
	}
	if (force) {
		Blockbench.showMessageBox({
			title: '自动检测 .minecraft',
			message: mcDirs.length
				? `找到：${result.minecraft}\n\nYSM jar：${result.jar || '（未找到，请手动填写）'}\nlibraries：${result.libraries || '（未找到）'}\n`
					+ `Java：${result.java || '（未找到，将使用 PATH 里的 java）'}`
				: '没有找到 .minecraft。请在设置里手动填写 Java、YSM jar 和 libraries 的路径。',
		});
	}
	return result;
};

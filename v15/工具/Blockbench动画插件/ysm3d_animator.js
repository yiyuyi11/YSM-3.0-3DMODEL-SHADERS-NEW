/* YSM 3D Animator 1.0.0: Blockbench plugin. Built from tools/blockbench-plugin/src by build.sh; do not edit. */
(function () {
"use strict";

// ---- src/core.js
// Shared namespace, native-module access and .ysmrig.json decoding.
// Every module adds to the global YSM3D (the NPR module provides YSM3D.Npr).
const YSM3D = (typeof window !== 'undefined' ? (window.YSM3D = window.YSM3D || {}) : {});
YSM3D.VERSION = '1.0.0';
YSM3D.PLUGIN_ID = 'ysm3d_animator';

// Virtual animation "files" shown in the Animations panel. They are never written by Blockbench itself: the plugin's
// export (and its wrappers around Blockbench's own animation save) write the user clips to the model folder.
YSM3D.GEN_PATH = '生成动作';
YSM3D.USER_PATH = '我的动作';
// Animation set per rig role: main → animations/main.animation.json, arm → animations/fp_arm.animation.json.
YSM3D.ROLES = {
	main: {set: 'main', file: 'main.animation.json', title: '第三人称'},
	arm: {set: 'fp_arm', file: 'fp_arm.animation.json', title: '第一人称'},
};

// Things to undo on unload: {delete()} objects and plain functions.
YSM3D.deletables = [];
YSM3D.onUnload = function (fn) {
	YSM3D.deletables.push(typeof fn === 'function' ? {delete: fn} : fn);
};

// ---- native modules (desktop only)
// Blockbench 5 passes `requireNativeModule` to plugin code and asks the user once per module; Blockbench 4.x
// exposes Node's `require` directly.
YSM3D.nativeModule = function (name, message) {
	if (typeof isApp === 'undefined' || !isApp) return null;
	try {
		if (typeof requireNativeModule === 'function') {
			return requireNativeModule(name, {message: message || 'YSM 3D 动画插件需要此权限来运行骨架导出器并读写模型文件夹。'});
		}
		if (typeof require === 'function') return require(name);
	} catch (err) {
		console.warn('[YSM3D] native module ' + name + ' unavailable:', err);
	}
	return null;
};
YSM3D.fs = function () {
	if (!YSM3D._fs) YSM3D._fs = YSM3D.nativeModule('fs', 'YSM 3D 动画插件需要读写模型文件夹（导出动作、查找 .minecraft、写回 VRM）。');
	return YSM3D._fs;
};
YSM3D.path = function () {
	if (!YSM3D._path) YSM3D._path = YSM3D.nativeModule('path');
	return YSM3D._path;
};
YSM3D.systemInfo = function () {
	if (typeof SystemInfo === 'object' && SystemInfo && SystemInfo.home_directory) return SystemInfo;
	const os = YSM3D.nativeModule('os');
	if (!os) return null;
	const env = (typeof process !== 'undefined' && process.env) || {};
	return {platform: os.platform(), home_directory: os.homedir(), appdata_directory: env.APPDATA,
		temp_directory: os.tmpdir()};
};
YSM3D.joinPath = function (...parts) {
	const p = YSM3D.path();
	if (p) return p.join(...parts);
	return parts.join('/').replace(/[\\/]+/g, '/');
};
YSM3D.dirname = function (file) {
	const p = YSM3D.path();
	if (p) return p.dirname(file);
	return file.replace(/[\\/][^\\/]*$/, '');
};
YSM3D.exists = function (file) {
	const fs = YSM3D.fs();
	try {
		return !!(fs && file && fs.existsSync(file));
	} catch (err) {
		return false;
	}
};

// ---- decoding
YSM3D.parseMaybe = function (value) {
	return typeof value === 'string' ? JSON.parse(value) : value;
};
YSM3D.base64Bytes = function (b64) {
	const bin = atob(b64);
	const out = new Uint8Array(bin.length);
	for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
	return out;
};
YSM3D.bytesBase64 = function (bytes) {
	let s = '';
	const chunk = 0x8000;
	for (let i = 0; i < bytes.length; i += chunk) {
		s += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
	}
	return btoa(s);
};
// Little-endian typed views over a base64 field (fresh buffers, so always aligned).
YSM3D.f32 = (b64) => new Float32Array(YSM3D.base64Bytes(b64).buffer);
YSM3D.u16 = (b64) => new Uint16Array(YSM3D.base64Bytes(b64).buffer);
YSM3D.u32 = (b64) => new Uint32Array(YSM3D.base64Bytes(b64).buffer);

/** Validates a parsed .ysmrig.json and returns it, or throws a user-readable message. */
YSM3D.checkRig = function (rig) {
	if (!rig || typeof rig !== 'object' || !rig.rigs) throw '这不是 .ysmrig.json 骨架文件。';
	if (rig.format !== 1) throw '不支持的 .ysmrig.json 格式版本 ' + rig.format + '，请更新插件。';
	return rig;
};
YSM3D.isRig = function (json) {
	return !!(json && typeof json === 'object' && json.format === 1 && json.rigs && (json.rigs.main || json.rigs.arm));
};
/** The model folder a rig came from (absolute, desktop), or ''. */
YSM3D.rigFolder = function (rig) {
	return (rig.source && (rig.source.folder || (rig.source.path && YSM3D.dirname(rig.source.path))))
		|| (rig.files && rig.files.folder) || '';
};
/** The source model file (absolute, desktop), or ''. */
YSM3D.rigSourceFile = function (rig) {
	if (rig.source && rig.source.path) return rig.source.path;
	const folder = YSM3D.rigFolder(rig);
	const name = (rig.source && (rig.source.file || rig.source.name)) || (rig.files && rig.files.main);
	return folder && name ? YSM3D.joinPath(folder, name) : '';
};

// Rigs loaded in this session, by model name: the projects keep only a reference.
YSM3D.rigs = {};

YSM3D.message = function (text, ms) {
	Blockbench.showQuickMessage(text, ms || 2500);
};
YSM3D.error = function (title, err) {
	console.error('[YSM3D]', title, err);
	Blockbench.showMessageBox({title: title, message: String(err && err.message || err), icon: 'error'});
};

// ---- src/settings.js
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

// ---- src/java_import.js
// Desktop import: runs the rig exporter in the YSM jar (com.elfmcys.ysm.format.parser.RigExport) with Java.
//   java -Xmx2G -cp <ysm jar><sep><10 libraries> com.elfmcys.ysm.format.parser.RigExport <model> <out.ysmrig.json>
// (~/ysm/handoff/ysmrig-format.md "Running the exporter"). The classpath goes through the CLASSPATH environment
// variable: Windows passes it as UTF-16, so non-ASCII user folders survive, and the command line stays short.

YSM3D.EXPORTER_MAIN = 'com.elfmcys.ysm.format.parser.RigExport';

YSM3D.hashString = function (s) {
	let h = 2166136261;
	for (let i = 0; i < s.length; i++) {
		h ^= s.charCodeAt(i);
		h = Math.imul(h, 16777619);
	}
	return (h >>> 0).toString(16);
};

/** Where generated .ysmrig.json files are kept (so a saved .bbmodel can rebuild its preview without Java). */
YSM3D.cacheDir = function () {
	const info = YSM3D.systemInfo();
	const base = info ? info.home_directory : '';
	const dir = YSM3D.joinPath(base, '.ysm3d_animator', 'rigs');
	try {
		YSM3D.fs().mkdirSync(dir, {recursive: true});
	} catch (err) {
		console.warn('[YSM3D] cache dir', err);
	}
	return dir;
};

// The library artifacts the exporter needs (ysmrig-format.md: "exactly these 10 artifacts", any version).
YSM3D.EXPORTER_LIBRARIES = ['com/google/code/gson/gson', 'it/unimi/dsi/fastutil', 'org/joml/joml', 'net/java/dev/jna/jna',
	'org/apache/logging/log4j/log4j-api', 'org/apache/logging/log4j/log4j-core', 'org/apache/logging/log4j/log4j-slf4j2-impl',
	'org/slf4j/slf4j-api', 'io/netty/netty-common', 'org/apache/commons/commons-lang3'];

/** The newest jar of each needed artifact; falls back to every jar under libraries if one is missing. */
YSM3D.exporterClasspath = function (libraries) {
	const jars = [];
	for (const artifact of YSM3D.EXPORTER_LIBRARIES) {
		const dir = YSM3D.joinPath(libraries, ...artifact.split('/'));
		const versions = YSM3D.listDir(dir).filter((v) => YSM3D.statSafe(YSM3D.joinPath(dir, v))?.isDirectory());
		versions.sort((a, b) => a.localeCompare(b, undefined, {numeric: true}));
		let found = '';
		for (const v of versions.reverse()) {
			const jar = YSM3D.listDir(YSM3D.joinPath(dir, v)).find((f) => /\.jar$/i.test(f) && !/-(sources|javadoc)\.jar$/i.test(f));
			if (jar) {
				found = YSM3D.joinPath(dir, v, jar);
				break;
			}
		}
		if (!found) {
			if (artifact.endsWith('log4j-slf4j2-impl')) continue;  // only silences a warning
			return YSM3D.collectJars(libraries, [], 0);
		}
		jars.push(found);
	}
	return jars;
};

YSM3D.collectJars = function (dir, out, depth) {
	if (depth > 12) return out;
	for (const name of YSM3D.listDir(dir)) {
		const p = YSM3D.joinPath(dir, name);
		if (/\.jar$/i.test(name)) {
			out.push(p);
		} else {
			const st = YSM3D.statSafe(p);
			if (st && st.isDirectory()) YSM3D.collectJars(p, out, depth + 1);
		}
	}
	return out;
};

/** Checks the settings, filling gaps by auto-detection; returns {java, jar, libraries} or throws a message. */
YSM3D.javaConfig = function () {
	if (!YSM3D.setting('jar') || !YSM3D.setting('libraries')) YSM3D.detectPaths(false);
	const java = YSM3D.setting('java') || 'java';
	const jar = YSM3D.setting('jar');
	const libraries = YSM3D.setting('libraries');
	if (!jar || !YSM3D.exists(jar)) {
		throw '找不到 YSM 模组 jar。请在 设置 → YSM 3D 动画 里填写（或点“自动检测 .minecraft”）。';
	}
	if (!libraries || !YSM3D.exists(libraries)) {
		throw '找不到 Minecraft 的 libraries 文件夹。请在 设置 → YSM 3D 动画 里填写。';
	}
	return {java, jar, libraries};
};

/**
 * Runs the exporter for a model folder or .vrm/.glb file. Resolves with {rig, path} (path of the written
 * .ysmrig.json) or rejects with a message.
 */
YSM3D.runExporter = function (modelPath) {
	return new Promise((resolve, reject) => {
		let config;
		try {
			config = YSM3D.javaConfig();
		} catch (err) {
			reject(err);
			return;
		}
		const cp = YSM3D.nativeModule('child_process', 'YSM 3D 动画插件需要启动 Java 来运行 YSM 骨架导出器。');
		if (!cp) {
			reject('没有“启动外部程序”的权限，无法运行 Java。可以改为直接打开 .ysmrig.json。');
			return;
		}
		const info = YSM3D.systemInfo();
		const sep = info && info.platform === 'win32' ? ';' : ':';
		const jars = [config.jar].concat(YSM3D.exporterClasspath(config.libraries));
		const name = modelPath.replace(/[\\/]+$/, '').split(/[\\/]/).pop().replace(/\.[a-z]+$/i, '');
		const out = YSM3D.joinPath(YSM3D.cacheDir(), name + '-' + YSM3D.hashString(modelPath) + '.ysmrig.json');
		const env = Object.assign({}, (typeof process !== 'undefined' && process.env) || {}, {CLASSPATH: jars.join(sep)});
		const args = ['-Xmx2G', '-Dfile.encoding=UTF-8', YSM3D.EXPORTER_MAIN, modelPath, out];

		const log = [];
		const dialog = new Dialog({
			id: 'ysm3d_java_progress',
			title: '正在导出 YSM 骨架…',
			width: 640,
			lines: ['<p>YSM 的导入器正在转换模型，大模型约需半分钟。</p>',
				'<pre id="ysm3d_java_log" style="max-height:260px;overflow:auto;font-size:11px;white-space:pre-wrap"></pre>'],
			buttons: ['dialog.cancel'],
			onCancel() {
				child.kill();
			},
		});
		dialog.show();
		const show = (text) => {
			log.push(text);
			const el = document.getElementById('ysm3d_java_log');
			if (el) {
				el.textContent = log.join('').slice(-6000);
				el.scrollTop = el.scrollHeight;
			}
		};
		let child;
		try {
			child = cp.spawn(config.java, args, {env, windowsHide: true});
		} catch (err) {
			dialog.close();
			reject('无法启动 Java（' + config.java + '）：' + err);
			return;
		}
		child.stdout.on('data', (d) => show(d.toString()));
		child.stderr.on('data', (d) => show(d.toString()));
		child.on('error', (err) => {
			dialog.close();
			reject('无法启动 Java（' + config.java + '）：' + err.message
				+ '\n\n请安装 Java 17+，或在 设置 → YSM 3D 动画 里填写 java 的路径。');
		});
		child.on('close', (code) => {
			dialog.close();
			if (code !== 0) {
				const text = log.join('');
				const lines = text.split(/\r?\n/).filter((l) => /^RigExport: |caused by:/.test(l));
				reject('导出器失败（退出码 ' + code + '）：\n' + (lines.length ? lines.join('\n') : text.slice(-3000)));
				return;
			}
			Blockbench.read([out], {readtype: 'text', errorbox: false}, (files) => {
				try {
					resolve({rig: YSM3D.checkRig(JSON.parse(files[0].content)), path: out});
				} catch (err) {
					reject('读取导出结果失败：' + err);
				}
			});
		});
	});
};

// ---- src/preview.js
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

// ---- src/first_person.js
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

// ---- src/tacz.js
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

// ---- src/animations.js
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

// ---- src/project.js
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

// ---- src/vrm.js
// GLB (VRM) reading and rewriting with replaced images.
// A rewrite keeps every other byte of the binary chunk: buffer views keep their order and bytes, only the replaced
// image views get new bytes, and the views after them move (byteOffset updated, 4-byte aligned). The JSON is the
// original object with those offsets/lengths, the buffer length and the replaced images' mimeType changed.

YSM3D.Vrm = {};

/** @returns {{json: object, bin: Uint8Array}} */
YSM3D.Vrm.parse = function (arrayBuffer) {
	const bytes = new Uint8Array(arrayBuffer);
	const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
	if (view.getUint32(0, true) !== 0x46546C67) throw '不是 GLB/VRM 文件（文件头不对）。';
	if (view.getUint32(4, true) !== 2) throw '只支持 glTF 2.0 的 GLB。';
	let offset = 12;
	let json = null;
	let bin = null;
	while (offset + 8 <= bytes.length) {
		const length = view.getUint32(offset, true);
		const type = view.getUint32(offset + 4, true);
		const data = bytes.subarray(offset + 8, offset + 8 + length);
		if (type === 0x4E4F534A) json = JSON.parse(new TextDecoder('utf-8').decode(data));
		else if (type === 0x004E4942 && !bin) bin = data;
		offset += 8 + length;
	}
	if (!json) throw 'GLB 里没有 JSON 块。';
	return {json, bin: bin || new Uint8Array(0)};
};

YSM3D.Vrm.imageBytes = function (glb, index) {
	const image = glb.json.images[index];
	if (!image || image.bufferView === undefined) return null;
	const bv = glb.json.bufferViews[image.bufferView];
	if ((bv.buffer || 0) !== 0) return null;
	return glb.bin.subarray(bv.byteOffset || 0, (bv.byteOffset || 0) + bv.byteLength);
};

YSM3D.Vrm.imageDataUrl = function (glb, index) {
	const bytes = YSM3D.Vrm.imageBytes(glb, index);
	if (!bytes) return null;
	const mime = glb.json.images[index].mimeType || 'image/png';
	return 'data:' + mime + ';base64,' + YSM3D.bytesBase64(bytes);
};

/**
 * @param replacements {imageIndex: {bytes: Uint8Array, mimeType}}
 * @returns Uint8Array of the new GLB
 */
YSM3D.Vrm.build = function (glb, replacements) {
	const json = JSON.parse(JSON.stringify(glb.json));
	const views = json.bufferViews || [];
	const replacedView = {};
	for (const key in replacements) {
		const image = json.images[key];
		if (!image || image.bufferView === undefined) throw '图片 ' + key + ' 不在 GLB 的二进制块里，无法写回。';
		replacedView[image.bufferView] = replacements[key];
		if (replacements[key].mimeType) image.mimeType = replacements[key].mimeType;
	}
	// regions of buffer 0: overlapping views move together
	const onBin = views.map((v, i) => ({i, start: v.byteOffset || 0, end: (v.byteOffset || 0) + v.byteLength}))
		.filter((v) => (views[v.i].buffer || 0) === 0)
		.sort((a, b) => a.start - b.start || b.end - a.end);
	const regions = [];
	for (const v of onBin) {
		const last = regions[regions.length - 1];
		if (last && v.start < last.end) {
			if (replacedView[v.i] !== undefined || last.views.some((i) => replacedView[i] !== undefined)) {
				throw '要替换的图片和其他数据共用了一段缓冲区，无法安全写回。';
			}
			last.end = Math.max(last.end, v.end);
			last.views.push(v.i);
		} else {
			regions.push({start: v.start, end: v.end, views: [v.i]});
		}
	}
	const align = (n) => (n + 3) & ~3;
	let size = 0;
	for (const r of regions) {
		const replaced = replacedView[r.views[0]];
		r.newStart = align(size);
		r.newLength = replaced ? replaced.bytes.length : r.end - r.start;
		size = r.newStart + r.newLength;
	}
	const binLength = align(size);
	const bin = new Uint8Array(binLength);
	for (const r of regions) {
		const replaced = replacedView[r.views[0]];
		if (replaced) {
			bin.set(replaced.bytes, r.newStart);
			const v = views[r.views[0]];
			v.byteOffset = r.newStart;
			v.byteLength = replaced.bytes.length;
		} else {
			bin.set(glb.bin.subarray(r.start, r.end), r.newStart);
			for (const i of r.views) views[i].byteOffset = r.newStart + ((glb.json.bufferViews[i].byteOffset || 0) - r.start);
		}
	}
	if (json.buffers && json.buffers[0]) json.buffers[0].byteLength = size;

	const jsonBytes = new TextEncoder().encode(JSON.stringify(json));
	const jsonLength = align(jsonBytes.length);
	const total = 12 + 8 + jsonLength + 8 + binLength;
	const out = new Uint8Array(total);
	const dv = new DataView(out.buffer);
	dv.setUint32(0, 0x46546C67, true);
	dv.setUint32(4, 2, true);
	dv.setUint32(8, total, true);
	dv.setUint32(12, jsonLength, true);
	dv.setUint32(16, 0x4E4F534A, true);
	out.set(jsonBytes, 20);
	for (let i = 20 + jsonBytes.length; i < 20 + jsonLength; i++) out[i] = 0x20;
	const binHeader = 20 + jsonLength;
	dv.setUint32(binHeader, binLength, true);
	dv.setUint32(binHeader + 4, 0x004E4942, true);
	out.set(bin, binHeader + 8);
	return out;
};

YSM3D.canvasPng = function (canvas) {
	return new Promise((resolve, reject) => {
		canvas.toBlob((blob) => {
			if (!blob) {
				reject('PNG 编码失败');
				return;
			}
			blob.arrayBuffer().then((b) => resolve(new Uint8Array(b)), reject);
		}, 'image/png');
	});
};

// ---- src/paint.js
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

// ---- src/plugin.js
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

})();

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

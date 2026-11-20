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

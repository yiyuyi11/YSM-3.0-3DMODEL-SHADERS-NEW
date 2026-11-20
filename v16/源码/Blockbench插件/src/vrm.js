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

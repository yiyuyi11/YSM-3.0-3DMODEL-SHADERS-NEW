"""Reader for Endfield *_MPData.bin (baked GPU mount points): frames x channels x [x, height, forward, qx, qy, qz, qw]."""
import struct

import numpy as np


def read(path):
    data = open(path, "rb").read()
    for offset in range(0, min(len(data) - 16, 512), 4):
        frames, channels, magic, rate = struct.unpack_from("<4i", data, offset)
        if magic == 70 and 0 < frames < 10000 and 0 < channels < 64:
            break
    else:
        raise ValueError(f"{path}: no MPData header")
    count = frames * channels
    for marker in range(offset + 16, len(data) - 4, 4):
        if struct.unpack_from("<i", data, marker)[0] == count and len(data) - (marker + 4) >= count * 28:
            break
    else:
        raise ValueError(f"{path}: no transform count")
    values = np.frombuffer(data, "<f4", count * 7, marker + 4).reshape(frames, channels, 7).astype(np.float64)
    if not np.allclose(np.linalg.norm(values[..., 3:], axis=-1), 1, atol=1e-3):
        raise ValueError(f"{path}: quaternions are not unit length; layout misread")
    return values[..., :3], values[..., 3:], rate


def loop(path):
    """Positions and quaternions of a loop without its closing frame, which repeats the first up to compression noise
    (positions within 2 mm, quaternions within 2e-3 up to sign)."""
    positions, quaternions, _ = read(path)
    seam_q = np.minimum(np.abs(quaternions[-1] - quaternions[0]).max(-1), np.abs(quaternions[-1] + quaternions[0]).max(-1))
    if len(positions) > 1 and np.abs(positions[-1] - positions[0]).max() < 2e-3 and seam_q.max() < 2e-3:
        positions, quaternions = positions[:-1], quaternions[:-1]
    return positions, quaternions

package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.util.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

/** One source texture of an imported humanoid: size is known up front, texels decode on demand. */
interface HumanoidTexture extends Closeable {
    int width();

    int height();

    /** RGBA8 texels, row-major from the top-left corner. */
    ByteBuffer decode() throws IOException;

    @Override
    default void close() {}

    /** Formats the native codec reads (PNG, JPEG, WebP, AVIF). */
    static HumanoidTexture encoded(ByteBuffer bytes) throws IOException {
        var storage = NativeBuffer.allocate(bytes.remaining());
        final Image image;
        try {
            storage.nio().put(0, bytes, bytes.position(), bytes.remaining());
            image = Image.probe(storage);
        } finally {
            storage.close();
        }
        return new HumanoidTexture() {
            @Override
            public int width() {
                return image.width();
            }

            @Override
            public int height() {
                return image.height();
            }

            @Override
            public ByteBuffer decode() throws IOException {
                try (var decoded = image.decodeToBuffer()) {
                    var source = decoded.nio();
                    var copy = ByteBuffer.allocate(source.capacity());
                    copy.put(0, source, 0, source.capacity());
                    return copy;
                }
            }

            @Override
            public void close() {
                image.close();
            }
        };
    }

    /** Already decoded RGBA8 texels. */
    static HumanoidTexture decoded(int width, int height, ByteBuffer rgba) {
        return new HumanoidTexture() {
            @Override
            public int width() {
                return width;
            }

            @Override
            public int height() {
                return height;
            }

            @Override
            public ByteBuffer decode() {
                return rgba.duplicate();
            }
        };
    }
}

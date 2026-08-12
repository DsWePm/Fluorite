package io.github.dswepm.fluorite.rt.sky;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict loader for Fluorite's deliberately small, Vulkan-native KTX2 subset. */
final class RtKtx2 {
    private static final byte[] IDENTIFIER = new byte[]{
            (byte) 0xab, 0x4b, 0x54, 0x58, 0x20, 0x32, 0x30, (byte) 0xbb, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final int HEADER_BYTES = 80;
    private static final int MAX_BYTES = 256 * 1024 * 1024;

    record Image(int vkFormat, int typeSize, int width, int height,
                 List<byte[]> levels, Map<String, String> metadata) {
        Image {
            levels = List.copyOf(levels);
            metadata = Map.copyOf(metadata);
        }
    }

    static Image read(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IOException("KTX2 exceeds 256 MiB limit");
        if (bytes.length < HEADER_BYTES) throw new IOException("Truncated KTX2 header");
        for (int i = 0; i < IDENTIFIER.length; i++) {
            if (bytes[i] != IDENTIFIER[i]) throw new IOException("Invalid KTX2 identifier");
        }
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        in.position(12);
        int vkFormat = in.getInt();
        int typeSize = in.getInt();
        int width = in.getInt();
        int height = in.getInt();
        int depth = in.getInt();
        int layers = in.getInt();
        int faces = in.getInt();
        int levelCount = in.getInt();
        int supercompression = in.getInt();
        long dfdOffset = Integer.toUnsignedLong(in.getInt());
        long dfdLength = Integer.toUnsignedLong(in.getInt());
        long kvdOffset = Integer.toUnsignedLong(in.getInt());
        long kvdLength = Integer.toUnsignedLong(in.getInt());
        long sgdOffset = in.getLong();
        long sgdLength = in.getLong();

        if (vkFormat != 122 && vkFormat != 97) throw new IOException("Unsupported KTX2 VkFormat " + vkFormat);
        int expectedTypeSize = vkFormat == 122 ? 4 : 2;
        if (typeSize != expectedTypeSize) throw new IOException("KTX2 typeSize does not match VkFormat");
        if (width <= 0 || height <= 0 || depth != 0 || layers != 0 || faces != 1) {
            throw new IOException("Only ordinary 2D KTX2 images are supported");
        }
        if (levelCount <= 0 || levelCount > 32) throw new IOException("Invalid KTX2 level count");
        if (supercompression != 0 || sgdOffset != 0 || sgdLength != 0) {
            throw new IOException("Supercompressed KTX2 is not supported");
        }
        checkedRange(dfdOffset, dfdLength, bytes.length, "DFD");
        if (dfdLength < 28 || in.getInt((int) dfdOffset) != dfdLength) {
            throw new IOException("Invalid KTX2 data format descriptor");
        }
        if (kvdLength == 0) {
            if (kvdOffset != 0) throw new IOException("KTX2 has an offset for empty metadata");
        } else {
            checkedRange(kvdOffset, kvdLength, bytes.length, "metadata");
        }

        long[] offsets = new long[levelCount];
        long[] lengths = new long[levelCount];
        long[] uncompressed = new long[levelCount];
        for (int level = 0; level < levelCount; level++) {
            offsets[level] = in.getLong(HEADER_BYTES + level * 24);
            lengths[level] = in.getLong(HEADER_BYTES + level * 24 + 8);
            uncompressed[level] = in.getLong(HEADER_BYTES + level * 24 + 16);
        }
        int bytesPerPixel = vkFormat == 122 ? 4 : 8;
        List<byte[]> levelData = new ArrayList<>(levelCount);
        int levelWidth = width;
        int levelHeight = height;
        for (int level = 0; level < levelCount; level++) {
            long expected = Math.multiplyExact(Math.multiplyExact((long) levelWidth, levelHeight), bytesPerPixel);
            if (lengths[level] != expected || uncompressed[level] != expected) {
                throw new IOException("KTX2 level " + level + " has " + lengths[level]
                        + " bytes; expected " + expected);
            }
            if ((offsets[level] & 7L) != 0L) throw new IOException("KTX2 level is not 8-byte aligned");
            checkedRange(offsets[level], lengths[level], bytes.length, "level " + level);
            levelData.add(Arrays.copyOfRange(bytes, Math.toIntExact(offsets[level]),
                    Math.toIntExact(offsets[level] + lengths[level])));
            levelWidth = Math.max(1, levelWidth / 2);
            levelHeight = Math.max(1, levelHeight / 2);
        }
        return new Image(vkFormat, typeSize, width, height, levelData,
                kvdLength == 0 ? Map.of() : parseMetadata(bytes, (int) kvdOffset, (int) kvdLength));
    }

    private static Map<String, String> parseMetadata(byte[] bytes, int offset, int length) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int cursor = offset;
        int end = Math.addExact(offset, length);
        while (cursor < end) {
            if (end - cursor < 4) throw new IOException("Truncated KTX2 metadata length");
            int entryLength = in.getInt(cursor);
            cursor += 4;
            if (entryLength <= 1 || entryLength > end - cursor) throw new IOException("Invalid KTX2 metadata entry");
            int zero = -1;
            for (int i = cursor; i < cursor + entryLength; i++) {
                if (bytes[i] == 0) { zero = i; break; }
            }
            if (zero <= cursor) throw new IOException("KTX2 metadata key is not terminated");
            String key = new String(bytes, cursor, zero - cursor, StandardCharsets.UTF_8);
            int valueEnd = cursor + entryLength;
            if (valueEnd > zero + 1 && bytes[valueEnd - 1] == 0) valueEnd--;
            String value = new String(bytes, zero + 1, valueEnd - zero - 1, StandardCharsets.UTF_8);
            if (result.putIfAbsent(key, value) != null) throw new IOException("Duplicate KTX2 metadata key " + key);
            cursor += entryLength;
            cursor = (cursor + 3) & ~3;
        }
        if (cursor != end) throw new IOException("KTX2 metadata padding exceeds section");
        return result;
    }

    private static void checkedRange(long offset, long length, int total, String label) throws IOException {
        if (offset < 0 || length < 0 || offset > total || length > total - offset) {
            throw new IOException("KTX2 " + label + " is outside the file");
        }
    }

    private RtKtx2() {
    }
}

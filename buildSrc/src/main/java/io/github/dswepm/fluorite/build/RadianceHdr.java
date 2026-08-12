package io.github.dswepm.fluorite.build;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal, strict Radiance RGBE reader used only by the offline End-environment build. */
final class RadianceHdr {
    private static final Pattern RESOLUTION = Pattern.compile("([+-])Y\\s+(\\d+)\\s+([+-])X\\s+(\\d+)");

    private RadianceHdr() {}

    static Image read(Path path) throws IOException {
        try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(path), 1 << 20)) {
            String magic = readLine(input);
            if (!"#?RADIANCE".equals(magic) && !"#?RGBE".equals(magic)) {
                throw new IOException("Not a Radiance RGBE file: " + path);
            }
            Map<String, String> headers = new LinkedHashMap<>();
            for (String line; !(line = readLine(input)).isEmpty();) {
                if (line.startsWith("#")) continue;
                int separator = line.indexOf('=');
                if (separator > 0) headers.put(line.substring(0, separator), line.substring(separator + 1));
            }
            if (!"32-bit_rle_rgbe".equals(headers.get("FORMAT"))) {
                throw new IOException("Unsupported Radiance FORMAT: " + headers.get("FORMAT"));
            }
            String resolution = readLine(input);
            Matcher matcher = RESOLUTION.matcher(resolution);
            if (!matcher.matches()) throw new IOException("Unsupported Radiance resolution: " + resolution);
            if (!"-".equals(matcher.group(1)) || !"+".equals(matcher.group(3))) {
                throw new IOException("Only top-to-bottom -Y +X Radiance images are supported: " + resolution);
            }
            int height = Integer.parseInt(matcher.group(2));
            int width = Integer.parseInt(matcher.group(4));
            if (width < 8 || width > 0x7fff || height <= 0) {
                throw new IOException("Unsupported Radiance dimensions: " + width + "x" + height);
            }
            byte[] rgbe = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
            byte[] channel = new byte[width];
            for (int y = 0; y < height; y++) {
                int a = readByte(input), b = readByte(input), hi = readByte(input), lo = readByte(input);
                if (a != 2 || b != 2 || ((hi << 8) | lo) != width) {
                    throw new IOException("Unsupported old or corrupt Radiance scanline at y=" + y);
                }
                for (int c = 0; c < 4; c++) {
                    decodeChannel(input, channel, y, c);
                    for (int x = 0; x < width; x++) rgbe[(y * width + x) * 4 + c] = channel[x];
                }
            }
            return new Image(width, height, rgbe, Map.copyOf(headers));
        }
    }

    private static void decodeChannel(InputStream input, byte[] output, int y, int channel) throws IOException {
        int cursor = 0;
        while (cursor < output.length) {
            int code = readByte(input);
            if (code > 128) {
                int count = code - 128;
                if (count == 0 || cursor + count > output.length) {
                    throw new IOException("Corrupt Radiance run at y=" + y + ", channel=" + channel);
                }
                byte value = (byte) readByte(input);
                for (int i = 0; i < count; i++) output[cursor++] = value;
            } else {
                int count = code;
                if (count == 0 || cursor + count > output.length) {
                    throw new IOException("Corrupt Radiance literal at y=" + y + ", channel=" + channel);
                }
                int read = 0;
                while (read < count) {
                    int n = input.read(output, cursor + read, count - read);
                    if (n < 0) throw new EOFException("Radiance literal ended early");
                    read += n;
                }
                cursor += count;
            }
        }
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException("Radiance file ended early");
        return value;
    }

    private static String readLine(InputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int value = input.read();
            if (value < 0) throw new EOFException("Radiance header ended early");
            if (value == '\n') return line.toString();
            if (value != '\r') line.append((char) value);
            if (line.length() > 4096) throw new IOException("Radiance header line is unreasonably long");
        }
    }

    record Image(int width, int height, byte[] rgbe, Map<String, String> headers) {
        float red(int x, int y) { return component(x, y, 0); }
        float green(int x, int y) { return component(x, y, 1); }
        float blue(int x, int y) { return component(x, y, 2); }

        private float component(int x, int y, int channel) {
            int offset = (y * width + x) * 4;
            int exponent = rgbe[offset + 3] & 0xff;
            if (exponent == 0) return 0.0f;
            return (float) ((rgbe[offset + channel] & 0xff) * Math.scalb(1.0, exponent - 136));
        }
    }
}

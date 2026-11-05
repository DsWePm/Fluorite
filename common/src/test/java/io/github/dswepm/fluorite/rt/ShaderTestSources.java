package io.github.dswepm.fluorite.rt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.ArrayDeque;
import java.util.regex.Pattern;

/** Read the canonical source behind the two static transport specializations once for contract tests. */
public final class ShaderTestSources {
    private ShaderTestSources() {}

    public static String read(Path root, String relative) throws IOException {
        Path file = root.resolve(relative);
        String source = Files.readString(file).replace("\r\n", "\n");
        if (!relative.equals("shaders/world/cloud.slang") && !relative.equals("shaders/world/volume.slang"))
            return source;
        var includes = Pattern.compile("#include \\\"([^\\\"]+_kernel\\.slang)\\\"").matcher(source);
        var kernels = new LinkedHashSet<String>();
        while (includes.find()) kernels.add(includes.group(1));
        for (String kernel : kernels) {
            String body = fullRadiance(Files.readString(file.getParent().resolve(kernel)).replace("\r\n", "\n"));
            // These tests check the full-radiance instantiation; compilation tests check the T-only one.
            body = body.replaceAll("(?:CLOUD|VOLUME)_KERNEL_FN\\((\\w+)\\)", "$1");
            source += "\n" + body;
        }
        return source;
    }

    private static String fullRadiance(String source) throws IOException {
        var parents = new ArrayDeque<Boolean>();
        boolean active = true;
        var output = new StringBuilder();
        for (String line : source.split("\n", -1)) {
            if (line.matches("#if (?:CLOUD|VOLUME)_KERNEL_SOURCE")) {
                parents.push(active);
            } else if (line.equals("#else") && !parents.isEmpty()) {
                active = parents.peek() && !active;
            } else if (line.equals("#endif") && !parents.isEmpty()) {
                active = parents.pop();
            } else if (line.startsWith("#")) {
                throw new IOException("Unexpected transport-kernel directive in source contract: " + line);
            } else if (active) {
                output.append(line).append('\n');
            }
        }
        if (!parents.isEmpty()) throw new IOException("Unterminated transport source specialization");
        return output.toString();
    }
}

package io.github.dswepm.fluorite.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ShaderCompileCacheTest {
    @TempDir Path temporary;

    @Test void readsCompilerMakeEscapesAndContinuations() throws Exception {
        String rule = "F\\:/out.spv: F\\:\\\\Shader\\\\a\\ b.slang \\\r\n"
                + " module\\ name.slang price$$.slang\n";
        assertEquals(List.of("F:\\Shader\\a b.slang", "module name.slang", "price$.slang"),
                ShaderCompileCache.parseDependencies(rule));
        assertThrows(java.io.IOException.class, () -> ShaderCompileCache.parseDependencies("not a rule"));
    }

    @Test void changingAReuseDependencyInvalidatesOnlyItsVariant() throws Exception {
        Path common = write("common.slang", "shared");
        Path reuse = write("reuse.slang", "reuse");
        Path off = write("off.spv", "validated off");
        Path on = write("on.spv", "validated on");
        ShaderCompileCache cache = cache();
        save(cache, "off", "off-command", off, List.of(common));
        save(cache, "on", "on-command", on, List.of(common, reuse));
        assertTrue(hit(cache, "off", "off-command", off));
        assertTrue(hit(cache, "on", "on-command", on));
        Files.writeString(reuse, "changed reuse");
        assertTrue(hit(cache, "off", "off-command", off));
        assertFalse(hit(cache, "on", "on-command", on));
        Files.writeString(common, "changed shared");
        assertFalse(hit(cache, "off", "off-command", off));
    }

    @Test void refusesChangedFlagsCorruptOutputMissingSourceAndPartialMetadata() throws Exception {
        Path source = write("source.slang", "source");
        Path output = write("shader.spv", "validated");
        ShaderCompileCache cache = cache();
        save(cache, "shader", "command", output, List.of(source));
        assertFalse(hit(cache, "shader", "different-defines-or-toolchain", output));
        Files.writeString(output, "unvalidated");
        assertFalse(hit(cache, "shader", "command", output));
        Files.writeString(output, "validated");
        Files.delete(source);
        assertFalse(hit(cache, "shader", "command", output));
        Files.writeString(temporary.resolve("cache/shader.properties"), "format=1\ndependencies=broken\n");
        assertFalse(hit(cache, "shader", "command", output));
    }

    @Test void doesNotAcceptANewCompilationUntilExplicitlyPublished() throws Exception {
        Path source = write("source.slang", "source");
        Path output = write("shader.spv", "old valid output");
        ShaderCompileCache cache = cache();
        save(cache, "shader", "command", output, List.of(source));
        Files.writeString(source, "new source");
        Path candidate = write("candidate.spv", "new candidate");
        cache.compiled("command", candidate, depfile("candidate", List.of(source)));
        assertFalse(hit(cache, "shader", "command", output), "An aborted batch cannot publish new metadata");
        assertEquals("old valid output", Files.readString(output));
    }

    @Test void dependencyDiscoveryIncludesFilesOutsideTheShaderDirectory() throws Exception {
        Path external = write("external module.slang", "external");
        Path output = write("shader.spv", "validated");
        save(cache(), "shader", "command", output, List.of(external));
        assertEquals(List.of(external), ShaderCompileCache.dependencyFiles(temporary.resolve("cache")));
    }

    @Test void fingerprintsToolBinariesAndSlangLibraries() throws Exception {
        Path compiler = write("slangc.exe", "compiler one");
        Path library = write("slang-compiler.dll", "library one");
        List<String> commands = List.of(compiler.toString());
        String first = ShaderCompileCache.toolchainFingerprint(commands, temporary);
        Files.writeString(library, "library two");
        String second = ShaderCompileCache.toolchainFingerprint(commands, temporary);
        assertNotEquals(first, second);
        Files.writeString(compiler, "compiler two");
        assertNotEquals(second, ShaderCompileCache.toolchainFingerprint(commands, temporary));
    }

    @Test void signaturesPreserveArgumentBoundaries() {
        assertNotEquals(ShaderCompileCache.signature(List.of("ab", "c")),
                ShaderCompileCache.signature(List.of("a", "bc")));
    }

    private ShaderCompileCache cache() { return new ShaderCompileCache(temporary.resolve("cache"), temporary); }
    private Path write(String name, String value) throws Exception {
        Path path = temporary.resolve(name); Files.writeString(path, value); return path;
    }
    private Path depfile(String name, List<Path> dependencies) throws Exception {
        String paths = String.join(" ", dependencies.stream().map(Path::toString)
                .map(s -> s.replace("\\", "\\\\").replace(":", "\\:").replace(" ", "\\ ")).toList());
        return write(name + ".d", "output: " + paths + "\n");
    }
    private void save(ShaderCompileCache cache, String name, String signature, Path output,
                      List<Path> dependencies) throws Exception {
        cache.store(name, cache.compiled(signature, output, depfile(name, dependencies)));
    }
    private boolean hit(ShaderCompileCache cache, String name, String signature, Path output) {
        return cache.copyIfValid(name, signature, output, temporary.resolve(name + ".copy"));
    }
}

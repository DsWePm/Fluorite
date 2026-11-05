package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class RtTransportKernelTest {
    @TempDir Path temporary;

    @Test void transmittanceOnlyCanRunAsComputeWithoutSceneTracingOrLightingCalls() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) root = root.getParent();
        assertNotNull(root);
        Path shader = temporary.resolve("transport.comp.slang"), spv = temporary.resolve("transport.spv");
        Files.writeString(shader, """
                import world_common;
                import world_core;
                import volume;
                import cloud;
                [[vk::binding(100,0)]] StructuredBuffer<WorldPush> params;
                [[vk::binding(101,0)]] RWStructuredBuffer<float4> result;
                [shader("compute")][numthreads(1,1,1)]
                void main(uint3 id:SV_DispatchThreadID) {
                    worldPush=params[id.x];
                    float3 ro=worldPush.camOffset.xyz, rd=normalize(worldPush.lightDir.xyz);
                    float3 medium=segmentPathTransmittance(worldPush.fogExtinction.xyz,
                            worldPush.flags&3u,ro,rd,10000.0,id.x);
                    float clouds=cloudPathTransmittance(ro,rd,10000.0,id.x,true);
                    result[id.x]=float4(medium,clouds);
                }
                """);
        run("slangc", List.of(shader.toString(), "-target", "spirv", "-profile", "spirv_1_5",
                "-I", root.resolve("shaders/world").toString(), "-o", spv.toString()), "compile");
        run("spirv-val", List.of("--target-env", "vulkan1.2", spv.toString()), "validate");
        Path disassembly = temporary.resolve("transport.spvasm");
        run("spirv-dis", List.of(spv.toString(), "-o", disassembly.toString()), "disassemble");
        String code = Files.readString(disassembly);
        assertFalse(code.contains("OpTraceRay") || code.contains("OpRayQuery"),
                "transmittance evaluation must not retain source-light visibility rays");
        assertTrue(code.contains("OpLoopMerge") && code.contains("OpImageSampleExplicitLod"),
                "the canonical heterogeneous density walks must still be present");
    }

    private void run(String tool, List<String> arguments, String label) throws Exception {
        String sdk = System.getenv("VULKAN_SDK");
        String executable = tool;
        if (sdk != null && System.getProperty("os.name").startsWith("Windows")) {
            Path candidate = Path.of(sdk, "Bin", tool + ".exe");
            if (Files.isRegularFile(candidate)) executable = candidate.toString();
        }
        var command = new ArrayList<String>(); command.add(executable); command.addAll(arguments);
        Path log = temporary.resolve(label + ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly(); fail(tool + " timed out");
        }
        assertEquals(0, process.exitValue(), () -> {
            try { return tool + ": " + Files.readString(log); }
            catch (Exception e) { return e.toString(); }
        });
    }
}

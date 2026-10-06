package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Bind the compiled shader's actual unformatted image capabilities to device query/enablement. */
class RtStorageImageCapabilityTest {
    @Test
    void skyImageCapabilitiesAreQueriedAndEnabledBeforeCreatingItsShaderModule() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) root = root.getParent();
        assertNotNull(root);
        Path module = root.resolve("build/generated/shaders/fluorite/rt/sky_transmittance.comp.spv");
        ByteBuffer code = ByteBuffer.wrap(Files.readAllBytes(module)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x07230203, code.getInt(0));
        Set<Integer> capabilities = new HashSet<>();
        for (int offset = 20; offset < code.limit();) {
            int instruction = code.getInt(offset);
            int words = instruction >>> 16;
            assertTrue(words > 0 && offset + words * 4 <= code.limit());
            if ((instruction & 0xffff) == 17) capabilities.add(code.getInt(offset + 4)); // OpCapability
            offset += words * 4;
        }
        assertTrue(capabilities.contains(55), "the actual failing shader declares StorageImageReadWithoutFormat");
        assertTrue(capabilities.contains(56), "and StorageImageWriteWithoutFormat");
        String bringup = Files.readString(root.resolve(
                "common/src/main/java/io/github/dswepm/fluorite/rt/RtDeviceBringup.java"));
        int start = bringup.indexOf("private static final List<VulkanFeature> REQUIRED_RT_FEATURES = List.of(");
        String required = bringup.substring(start, bringup.indexOf(");", start));
        for (String direction : new String[] {"READ", "WRITE"}) {
            assertTrue(required.contains("STORAGE_IMAGE_" + direction + "_WITHOUT_FORMAT_FEATURE"),
                    "all shader capabilities must enter the shared support query and device feature set");
            assertTrue(bringup.contains("VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGE" + direction + "WITHOUTFORMAT"));
        }
        assertTrue(bringup.contains("features.addAll(REQUIRED_RT_FEATURES);"));
        assertTrue(bringup.contains("if (!feature.get(available))"));
    }
}

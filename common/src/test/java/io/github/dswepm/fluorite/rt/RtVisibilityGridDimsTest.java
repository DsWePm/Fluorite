package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The volumetric visibility grid's extents, which are spelled in four files and must agree in all four.
 *
 * <p><b>The duplication is forced, not sloppy.</b> RtSky sizes the images and the dispatch;
 * volume_visibility.comp.slang bounds the bake's threads; volume_visibility.slang turns a world position
 * into a texture coordinate; and sky_froxel.comp.slang does the same division against its own binding of
 * the same image. That last one cannot import the others: volume_visibility.slang declares the grid's
 * binding, a binding declared in a module reaches every importer, and the froxel binds the same image at
 * a different index in a set that does not contain the first. So the numbers travel by copy, and nothing
 * in either language checks the copies against each other.
 *
 * <p>What that costs is specific. Changing the grid's HEIGHT -- the change issue #41's remaining half
 * would want -- and missing one copy does not fail to compile and does not warn. The froxel would keep
 * dividing by the old extent, so it would sample the shared grid at the wrong coordinate: sky occlusion
 * read from the wrong cell, everywhere, with no error to trace it by. This test is the only thing
 * standing between that change and that outcome.
 *
 * <p>RtSky is treated as the source of truth because it is the side that allocates. Changing the grid
 * means editing four files and this test keeps passing untouched, which is the property that makes it
 * worth having: it constrains agreement, not a particular size.
 */
final class RtVisibilityGridDimsTest {

    private static final String SKY = "common/src/main/java/io/github/dswepm/fluorite/rt/sky/RtSky.java";
    private static final Map<String, String> COPIES = new LinkedHashMap<>();

    static {
        COPIES.put("the bake's thread bounds", "shaders/world/volume_visibility.comp.slang");
        COPIES.put("the grid's reader", "shaders/world/volume_visibility.slang");
        COPIES.put("the froxel's own division", "shaders/world/sky_froxel.comp.slang");
    }

    @Test
    void everyCopyOfTheGridExtentsAgreesWithTheOneThatAllocates() throws IOException {
        String sky = source(SKY);
        int w = extent(sky, "W", SKY);
        int h = extent(sky, "H", SKY);
        int d = extent(sky, "D", SKY);

        for (Map.Entry<String, String> copy : COPIES.entrySet()) {
            String text = source(copy.getValue());
            String where = copy.getKey() + " (" + copy.getValue() + ")";
            assertEquals(w, extent(text, "W", where), where + " disagrees about the grid's width");
            assertEquals(h, extent(text, "H", where), where + " disagrees about the grid's height");
            assertEquals(d, extent(text, "D", where), where + " disagrees about the grid's depth");
        }
    }

    /**
     * The froxel's float3 is DERIVED from its three extents rather than written out again.
     *
     * <p>It used to be a bare {@code float3(64.0, 32.0, 64.0)} -- the one copy that did not carry the
     * constant's name, so a grep for VIS_GRID_H found three of the four sites and this one hid. Naming
     * the extents is what lets the test above see this file at all; deriving the vector is what stops a
     * fifth spelling from growing back beside them.
     */
    @Test
    void theFroxelsVectorIsBuiltFromItsNamedExtents() throws IOException {
        String froxel = source("shaders/world/sky_froxel.comp.slang");
        assertTrue(froxel.contains(
                "float3(float(VIS_GRID_W), float(VIS_GRID_H), float(VIS_GRID_D))"),
                "the froxel's VIS_GRID_DIMS must be built from its named extents");
        assertFalse(froxel.contains("float3(64.0, 32.0, 64.0)"),
                "a literal beside the named extents is a fifth copy waiting to drift");
    }

    private static int extent(String text, String axis, String where) {
        Pattern p = Pattern.compile(
                "(?:public\\s+)?static\\s+(?:final|const)\\s+int\\s+VIS_GRID_" + axis
                        + "\\s*=\\s*(\\d+)\\s*;");
        Matcher m = p.matcher(text);
        assertTrue(m.find(), () -> "no VIS_GRID_" + axis + " declaration in " + where);
        return Integer.parseInt(m.group(1));
    }

    private static String source(String relativePath) throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from "
                    + System.getProperty("user.dir"));
        }
        // Normalised, because the working tree carries CRLF on this platform while every pattern here is
        // written with LF. A contract test that passes or fails on a checkout setting is not testing the
        // contract.
        return String.join("\n", Files.readAllLines(root.resolve(relativePath)));
    }
}

package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.rt.gen.PackedPathReservoirData;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

final class RtPathEventRecordTest {
    private static String source(String file) throws Exception {
        Path p=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(p!=null && !Files.isRegularFile(p.resolve("settings.gradle")))p=p.getParent();
        assertNotNull(p);return Files.readString(p.resolve(file)).replace("\r\n","\n");
    }
    @Test void independentSourceAndNeeDensitiesFitTheAlreadyApprovedTailPadding() throws Exception {
        String c=source("shaders/world/world_common.slang");
        assertTrue(c.contains("public float  neePdf;"));assertTrue(c.contains("public float  sourcePdf;"));
        assertEquals(96,PackedPathReservoirData.BYTE_SIZE);
        assertEquals(88,PackedPathReservoirData.class.getField("NEE_PDF_OFFSET").getInt(null));
        assertEquals(92,PackedPathReservoirData.class.getField("SOURCE_PDF_OFFSET").getInt(null));
        assertTrue(c.contains("r.neePdf = 0.0;"));assertTrue(c.contains("r.sourcePdf = 0.0;"));
    }
    @Test void connectionPredecessorLobeAndEventKindDoNotOverwriteTheExistingPathTraits() throws Exception {
        String s=source("shaders/world/path_reuse_tree.slang");
        assertTrue(s.contains("REUSE_RECORD_PREVIOUS_LOBE_SHIFT = 19u"));
        assertTrue(s.contains("REUSE_RECORD_EVENT_KIND_SHIFT = 22u"));
        assertTrue(s.contains("reuseRecordSampling("));
        for(int depth=2;depth<=15;depth++)for(int previous=1;previous<=3;previous++)for(int kind=0;kind<16;kind++) {
            int old=1|(depth<<1)|(1<<5)|(2<<8)|(1<<12)|(2<<14)|(1<<16);
            int bits=old|(previous<<19)|(kind<<22);
            assertEquals(old,bits & ~((7<<19)|(15<<22)));
            assertEquals(previous,(bits>>>19)&7);assertEquals(kind,(bits>>>22)&15);
        }
        String trace=source("shaders/world/path_reuse_trace.slang");
        assertTrue(trace.contains("footprintPreviousSample.lobe, 0u, continuation.pdf, 0.0"));
    }
    @Test void selectedRecordFollowsTheSameSingleWrsDrawAndKeepsTheCoveredIntegral() throws Exception {
        String s=source("shaders/world/path_reuse_tree.slang");
        assertTrue(s.contains("bool lastSelected;"));assertTrue(s.contains("float3 sourceSum;"));
        assertTrue(s.contains("tree.lastSelected = true;"));
        assertTrue(s.contains("tree.sourceSum += value / survival;"));
        assertTrue(s.contains("if (tree.lastSelected) selected = candidate;"));
        String trace=source("shaders/world/path_reuse_trace.slang");
        assertTrue(trace.contains("reuseTreeUpdateRecord(dTree, selectedRecord, dRecord,"));
        assertTrue(trace.contains("reuseFinishTree(selectedRecord, dTree)"));
    }
}

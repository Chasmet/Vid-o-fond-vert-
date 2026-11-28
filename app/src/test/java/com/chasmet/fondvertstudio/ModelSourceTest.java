package com.chasmet.fondvertstudio;
import org.junit.Test;
import static org.junit.Assert.*;
public class ModelSourceTest {
    @Test public void pinnedModelsHaveKnownSizesAndHashes(){
        assertEquals(574041195L,ModelSource.size(ModelSource.WHISPER));
        assertEquals(66762795L,ModelSource.size(ModelSource.UVR));
        assertEquals(878702423L,ModelSource.size(ModelSource.QWEN));
        for(String model:new String[]{ModelSource.WHISPER,ModelSource.UVR,ModelSource.QWEN})
            assertTrue(ModelSource.sha(model).matches("[0-9a-f]{64}"));
    }
    @Test(expected=IllegalArgumentException.class) public void unknownModelDenied(){
        ModelSource.size("../unknown");
    }
}

package com.chasmet.fondvertstudio;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class QwenLibraryTest {
    @Test public void loadsOfficialAndroidRuntime() throws Exception {
        Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer");
        com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig config=new com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig();
        config.setMaxNewTokens(128);
        assertEquals(128,config.getMaxNewTokens());
    }
}

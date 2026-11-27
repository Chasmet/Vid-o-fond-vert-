package com.chasmet.fondvertstudio;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
@RunWith(AndroidJUnit4.class)
public class LocalWhisperLoadTest {
    @Test public void loadsNativeLibrary() throws Exception {
        Class.forName("com.chasmet.fondvertstudio.LocalWhisper");
    }
}

package com.chasmet.fondvertstudio;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class McpSettingsTest {
    @Test public void generalSettingsExposeMcpAndUpdates(){
        try(ActivityScenario<SettingsActivity> scenario=ActivityScenario.launch(SettingsActivity.class)){
            scenario.onActivity(a->{
                assertNotNull(a.findViewById(R.id.mcpManageButton));
                assertNotNull(a.findViewById(R.id.mcpConnectionStatus));
                assertNotNull(a.findViewById(R.id.mcpBatteryButton));
                assertNotNull(a.findViewById(R.id.checkUpdateButton));
            });
        }
    }
}

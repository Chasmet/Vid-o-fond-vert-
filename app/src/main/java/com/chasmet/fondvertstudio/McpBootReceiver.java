package com.chasmet.fondvertstudio;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
/** Reconnect only if the owner already enabled the MCP connection. */
public final class McpBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent){
        if(intent==null)return;
        String action=intent.getAction();
        if(Intent.ACTION_BOOT_COMPLETED.equals(action)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))
            PrompterMcpService.startIfEnabled(c);
    }
}

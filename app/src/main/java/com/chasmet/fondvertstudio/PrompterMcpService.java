package com.chasmet.fondvertstudio;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import org.json.JSONObject;
import java.lang.ref.WeakReference;

/** User-enabled foreground connection, no microphone/camera access from the service. */
public final class PrompterMcpService extends Service {
    private static final String CHANNEL="fondvert_mcp", STOP="fondvert.mcp.stop";
    private static PrompterMcpClient client;
    private static WeakReference<MusicPrompterActivity> screen=new WeakReference<>(null);
    private static PrompterMcpService service;
    private static BackgroundPrompter backend;
    private static McpTranscriptionJob analysis;
    private final Handler monitor=new Handler(Looper.getMainLooper());
    private String lastNotice="";
    private final Runnable monitorTick=new Runnable(){public void run(){
        if(service!=PrompterMcpService.this)return;
        String status=client(PrompterMcpService.this).status();
        if(!status.equals(lastNotice)){lastNotice=status;NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.notify(1608,notification(status));}
        monitor.postDelayed(this,10000);
    }};
    static boolean running(){return service!=null;}
    static void startIfEnabled(Context c){
        PrompterMcpClient client=client(c);
        if(client.enabled()&&client.paired())try{start(c);}catch(RuntimeException ignored){}
    }
    static PrompterMcpClient client(Context c){
        if(client==null){
            Context app=c.getApplicationContext();backend=new BackgroundPrompter(app);analysis=new McpTranscriptionJob(app,backend);
            client=new PrompterMcpClient(app,PrompterMcpService::dispatch);
        }
        return client;
    }
    static void attach(MusicPrompterActivity a){screen=new WeakReference<>(a);if(backend!=null)backend.release();}
    static boolean analyzing(){return analysis!=null&&analysis.running();}
    static String analysisLabel(){try{return analysis==null?"":analysis.status().optString("phase");}catch(Exception e){return "";}}
    static void refreshScreen(){MusicPrompterActivity a=screen.get();if(a!=null&&!a.isDestroyed())try{a.reloadMcpLyrics();}catch(Exception ignored){}}
    static void detach(MusicPrompterActivity a){if(screen.get()==a)screen.clear();}
    static void start(Context c){
        Intent i=new Intent(c,PrompterMcpService.class);
        if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);
    }
    static void stop(Context c){c.stopService(new Intent(c,PrompterMcpService.class));}
    private static JSONObject dispatch(String action,JSONObject args)throws Exception{
        MusicPrompterActivity a=screen.get();
        if(action.equals("analysis_status"))return analysis.status();
        if(action.equals("analysis_cancel")){analysis.cancel();return analysis.status();}
        if(action.equals("analysis_start")){if(a!=null&&a.mcpEditing())throw new java.io.IOException("Éditeur ou transcription locale en cours");return analysis.start(args);}
        if(analyzing()&&(action.equals("lyrics_add")||action.equals("lyrics_update")||action.equals("lyrics_delete")||action.equals("lyrics_restore")||action.equals("lyrics_batch")))throw new java.io.IOException("Transcription en cours : annule-la avant de modifier les paroles.");
        if(action.equals("mcp_diagnostics"))return new JSONObject().put("ok",true).put("service_running",service!=null).put("foreground_ui",a!=null&&a.mcpVisible()).put("background_supported",true);
        if(a!=null&&!a.isDestroyed()&&!a.isFinishing()){
            if(action.equals("lyrics_search")||action.equals("lyrics_audit")||action.equals("lyrics_text")||action.equals("lyrics_batch")||action.equals("lyrics_export_local")){
                if(a.mcpEditing())throw new java.io.IOException("Éditeur manuel ou transcription en cours.");
                JSONObject r=backend.execute(action,args);
                if(action.equals("lyrics_batch"))a.reloadMcpLyrics();
                return r;
            }
            if(action.equals("prompter_open_camera")||action.equals("lyrics_export_pdf")){
                if(!a.mcpVisible())return requireScreen(action);
            }
            return a.mcpExecute(action,args);
        }
        if(action.equals("prompter_open_camera")||action.equals("lyrics_export_pdf"))return requireScreen(action);
        return backend.execute(action,args);
    }
    private static JSONObject requireScreen(String action)throws Exception{
        if(service!=null)service.showRequest(action);
        return new JSONObject().put("ok",true).put("local_confirmation_required",true).put("message","Ouvre le prompteur depuis la notification et confirme localement. Aucune caméra ni aucun sélecteur lancé en arrière-plan.");
    }
    private PendingIntent open(){
        Intent i=new Intent(this,MusicPrompterActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this,12,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification(String message){
        Intent s=new Intent(this,PrompterMcpService.class).setAction(STOP);
        PendingIntent stop=PendingIntent.getService(this,13,s,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Fond Vert Studio · ChatGPT").setContentText(message).setContentIntent(open())
            .setOngoing(true).setOnlyAlertOnce(true).addAction(android.R.drawable.ic_menu_close_clear_cancel,"Arrêter",stop).build();
    }
    @Override public void onCreate(){
        super.onCreate();service=this;
        if(Build.VERSION.SDK_INT>=26){NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Connexion ChatGPT",NotificationManager.IMPORTANCE_LOW));}
        startForeground(1608,notification("Connexion MCP active · ouvrir le prompteur"));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        PrompterMcpClient c=client(this);
        if(intent!=null&&STOP.equals(intent.getAction())){c.setEnabled(false);stopSelf();return START_NOT_STICKY;}
        if(!c.enabled()||!c.paired()){stopSelf();return START_NOT_STICKY;}
        c.resume();monitor.removeCallbacks(monitorTick);monitor.post(monitorTick);return START_STICKY;
    }
    private void showRequest(String action){
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;
        nm.notify(1608,notification(action.equals("prompter_open_camera")?"Demande caméra · ouvrir et confirmer":"Demande PDF · ouvrir et confirmer"));
    }
    @Override public void onDestroy(){
        monitor.removeCallbacksAndMessages(null);
        if(client!=null)client.pause();if(analysis!=null)analysis.cancel();if(backend!=null)backend.release();service=null;stopForeground(true);super.onDestroy();
    }
    @Override public void onTaskRemoved(Intent rootIntent){super.onTaskRemoved(rootIntent);}
    @Override public IBinder onBind(Intent intent){return null;}
}

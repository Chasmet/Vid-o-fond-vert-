package com.chasmet.fondvertstudio;

import android.content.*;
import android.os.*;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Local inference independent from Activity lifecycle, with revision-safe completion. */
final class McpTranscriptionJob {
    private final Context app;
    private final BackgroundPrompter backend;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean running,cancel;
    private volatile String state="idle",phase="",engine="";
    private volatile int progress=-1;
    private long started;
    McpTranscriptionJob(Context c,BackgroundPrompter b){app=c;backend=b;}
    boolean running(){return running;}
    void cancel(){if(running){cancel=true;phase="Annulation demandée";}}
    JSONObject status()throws Exception{return new JSONObject().put("ok",true).put("state",state).put("phase",phase).put("engine",engine).put("progress",progress).put("running",running).put("elapsed_ms",running?SystemClock.elapsedRealtime()-started:0);}
    JSONObject start(JSONObject args)throws Exception{
        if(running)throw new IOException("Une transcription est déjà en cours.");
        SharedPreferences prefs=app.getSharedPreferences("music_prompter",0);
        JSONObject p=new JSONObject(prefs.getString("project","{}"));
        String rev=backend.revision(p);
        if(!rev.equals(args.optString("revision")))throw new IOException("Relire les paroles et fournir la révision actuelle.");
        String source=p.optString("source");if(source.isEmpty())throw new IOException("Importe d'abord un morceau.");
        JSONArray old=p.optJSONArray("lines");if(old!=null&&old.length()>0&&!args.optBoolean("replace",false))throw new IOException("Des paroles existent : replace=true est nécessaire pour les remplacer. Une sauvegarde est conservée.");
        String uri=p.optString("vocalPlayable");if(uri.isEmpty())uri=p.optString("vocal");if(uri.isEmpty())uri=p.optString("playable");if(uri.isEmpty())uri=source;
        final Uri input=Uri.parse(uri);final String chosen=args.optString("engine","whisper");
        if(!chosen.equals("whisper")&&!chosen.equals("qwen"))throw new IOException("Moteur : whisper ou qwen");
        if(chosen.equals("qwen")&&!QwenLocal.supported())throw new IOException("Qwen nécessite Android 8");
        final boolean isolate=args.optBoolean("isolate",false)&&p.optString("vocal").isEmpty();
        if(isolate&&!QwenLocal.supported())throw new IOException("UVR nécessite Android 8");
        final int threads=Math.max(1,Math.min(6,Runtime.getRuntime().availableProcessors()-1));
        cancel=false;running=true;state="running";phase="Préparation locale";engine=chosen;progress=-1;started=SystemClock.elapsedRealtime();
        worker.execute(()->{
            try{
                QwenLocal.Listener listener=new QwenLocal.Listener(){public void status(String s){phase=s;}public void progress(int v){progress=Math.max(0,Math.min(100,v));}public boolean abort(){return cancel;}};
                File model=null;if(chosen.equals("qwen"))QwenLocal.ensure(app,listener);else model=ensureWhisper();
                check();phase="Décodage audio";float[] pcm;
                if(isolate){pcm=UvrLocal.vocals(app,PrompterAudioDecoder.decodeStereo(app,input),threads,new UvrLocal.Listener(){public void status(String s){phase=s;}public boolean abort(){return cancel;}});}
                else pcm=PrompterAudioDecoder.decode(app,input);
                check();float peak=0;for(float x:pcm)if(!Float.isNaN(x)&&!Float.isInfinite(x))peak=Math.max(peak,Math.abs(x));
                if(peak<0.0001f)throw new IOException("Piste silencieuse");
                float gain=Math.min(5f,0.95f/peak);for(int i=0;i<pcm.length;i++){float x=pcm[i]*gain;pcm[i]=Float.isNaN(x)||Float.isInfinite(x)?0f:Math.max(-1f,Math.min(1f,x));}
                phase="Transcription locale";progress=0;
                String[] result=chosen.equals("qwen")?QwenLocal.transcribe(app,pcm,threads,listener):LocalWhisper.transcribe(model.getAbsolutePath(),pcm,threads,new LocalWhisper.Progress(){public void update(int n){progress=Math.max(0,Math.min(100,n));}public boolean shouldAbort(){return cancel;}});
                check();if(result==null||result.length==0)throw new IOException("Aucune parole détectée");
                ArrayList<LyricsTimeline.Row> found=new ArrayList<>();
                for(int i=0;i+2<result.length;i+=3){long s=Long.parseLong(result[i]),e=Long.parseLong(result[i+1]);String t=result[i+2].trim();if(s>=0&&e>s&&!t.isEmpty())found.add(new LyricsTimeline.Row(s,e,t));}
                if(found.isEmpty())throw new IOException("Aucune parole exploitable");LyricsTimeline.sort(found);
                main.post(()->{try{
                    check();JSONObject current=new JSONObject(prefs.getString("project","{}"));
                    if(!rev.equals(backend.revision(current))||!p.optString("vocal").equals(current.optString("vocal")))throw new IOException("Projet changé pendant l'analyse : paroles précédentes conservées.");
                    JSONArray out=new JSONArray();for(LyricsTimeline.Row row:found)out.put(new JSONArray().put(row.start).put(row.end).put(row.text));
                    JSONArray previous=current.optJSONArray("lines");if(previous==null)previous=new JSONArray();
                    JSONObject backup=new JSONObject().put("source",source).put("lines",previous);current.put("lines",out);
                    if(!prefs.edit().putString("project",current.toString()).putString("mcp_undo",backup.toString()).commit())throw new IOException("Sauvegarde impossible");
                    state="completed";phase=found.size()+" passages enregistrés";progress=100;PrompterMcpService.refreshScreen();
                }catch(Exception ex){state=cancel?"cancelled":"failed";phase=cancel?"Paroles précédentes conservées":String.valueOf(ex.getMessage());}finally{running=false;}});
            }catch(Exception|LinkageError|OutOfMemoryError e){main.post(()->{state=cancel?"cancelled":"failed";phase=cancel?"Paroles précédentes conservées":e instanceof OutOfMemoryError?"Mémoire insuffisante":String.valueOf(e.getMessage());running=false;});}
        });
        return status();
    }
    private void check()throws InterruptedException{if(cancel||Thread.currentThread().isInterrupted())throw new InterruptedException();}
    private File ensureWhisper()throws Exception{
        return ModelSource.ensureFile(app,ModelSource.WHISPER,n->{phase="Whisper : "+n/1000000+" / 574 Mo";},()->cancel);
    }
}

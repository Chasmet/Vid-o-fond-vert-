package com.chasmet.fondvertstudio;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.media.MediaPlayer;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

public final class MusicPrompterActivity extends AppCompatActivity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ArrayList<Line> lines=new ArrayList<>();
    private Uri source,backing,playable,backingPlayable,vocal,vocalPlayable;
    private Button pickVocal,clearVocal,cancelAnalysis;
    private TextView vocalInfo;
    private volatile boolean cancelled,dead;
    private boolean analyzing;
    private boolean useQwen=true;
    private CheckBox isolateVoice;
    private boolean separationUsed;
    private String separationNote="";
    private android.widget.RadioGroup engineChoice;
    private String phase="";
    private int analysisProgress=-1;
    private TextView fileInfo,backingInfo;
    private ProgressBar progress;
    private Button camera, pdfExport, subtitleExport;
    private static final int REQUEST_TRANSCRIPT=42;
    private String pendingExportText, pendingExportMime;
    private TextView cameraHint;
    private boolean cameraAfterCancel;
    private static final int REQUEST_LYRICS_PDF=41;
    private String pendingPdfTitle;
    private String pendingPdfText;
    private int percent=-1;
    private long startedAt;
    private boolean prepared;
    private MediaPlayer player;
    private TextView status,current,next,clock,leadLabel;
    private Button analyze,play,pick,pickBacking,edit;
    private LyricsEditorDialog lyricsEditor;
    private PrompterMcpClient mcp;
    private TextView mcpStatus;
    private SeekBar seek;
    private LinearLayout root;
    private boolean busy=false,drag=false;
    private int lead=1000,active=-1;
    private static final long MODEL_MIN_BYTES=570000000L;
    private static final String MODEL_SHA1="e050f7970618a659205450ad97eb95a18d69c9ee";
    private static final class Line {
        long start,end;String text;
        Line(long a,long b,String t){start=a;end=b;text=t;}
    }
    private String fileName(Uri u){ if(u==null)return "Aucun fichier"; try(android.database.Cursor c=getContentResolver().query(u,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))return c.getString(0);}catch(Exception ignored){}return u.getLastPathSegment()==null?"Audio sélectionné":u.getLastPathSegment();}
    private String duration(long t){return String.format(Locale.FRANCE,"%02d:%02d",t/60000,(t/1000)%60);}
    private Uri music(){return playable==null?source:playable;}
    private Uri instrumental(){return backingPlayable==null?backing:backingPlayable;}
    private TextView label(String s,int sp,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setPadding(0,12,0,12);root.addView(v);return v;}
    private Button button(String s,Runnable action){com.google.android.material.button.MaterialButton v=new com.google.android.material.button.MaterialButton(this);v.setText(s);v.setAllCaps(false);v.setTextColor(0xffffffff);v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff243b39));v.setOnClickListener(w->action.run());root.addView(v);return v;}
    @Override public void onCreate(Bundle b){
        super.onCreate(b);setContentView(R.layout.activity_music_prompter);root=findViewById(R.id.prompterRoot);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        button("Retour",()->finish());
        label("PROMPTEUR MUSICAL IA",25,0xff36e2a3);
        status=label("Modèles inclus · fonctionnement hors ligne",14,0xffb4bbc3);
        mcp=new PrompterMcpClient(this,this::mcpExecute);
        button("Connexion ChatGPT · MCP",this::showMcp);
        mcpStatus=label(mcp.status(),13,0xffb4bbc3);
        fileInfo=label("Aucun morceau importé",17,0xffffffff);
        fileInfo.setBackgroundColor(0xff172820);
        camera=button("OUVRIR LA CAMÉRA + PROMPTEUR",()->openCamera());
        camera.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff087b59));
        cameraHint=label("Importe un morceau pour ouvrir la caméra.",13,0xffb4bbc3);
        pdfExport=button("Exporter les paroles en PDF",()->choosePdf());
        subtitleExport=button("Exporter transcription / sous-titres",()->chooseTranscript());
        backingInfo=label("Instrumental : aucun",13,0xffb4bbc3);
        pick=button("Importer un audio / vidéo",()->choose(1));
        pickBacking=button("Importer instrumental (facultatif)",()->choose(2));
        vocalInfo=label("Source de transcription : morceau original",13,0xffb4bbc3);
        pickVocal=button("Importer voix studio seule (facultatif)",()->choose(3));
        clearVocal=button("Analyser le morceau original",()->{if(busy)return;vocal=null;vocalPlayable=null;save();refresh();});
        isolateVoice=new CheckBox(this);isolateVoice.setText("Isoler la voix (UVR) · plus lent");isolateVoice.setTextColor(0xffffffff);isolateVoice.setChecked(QwenLocal.supported()&&getSharedPreferences("music_prompter",0).getBoolean("isolate_voice_v2",false));root.addView(isolateVoice);isolateVoice.setOnCheckedChangeListener((v,checked)->getSharedPreferences("music_prompter",0).edit().putBoolean("isolate_voice_v2",checked).apply());
        label("Moteur de transcription",16,0xffffffff);
        label("Pour aller plus vite : Whisper Turbo, sans UVR. La voix studio seule améliore la reconnaissance sans séparation.",13,0xffb4bbc3);
        engineChoice=new android.widget.RadioGroup(this);engineChoice.setOrientation(LinearLayout.VERTICAL);
        android.widget.RadioButton q=new android.widget.RadioButton(this);q.setId(View.generateViewId());q.setText(QwenLocal.supported()?"Qwen3-ASR 0.6B · alternative chant / musique":"Qwen3-ASR · Android 8 minimum");q.setTextColor(0xffffffff);engineChoice.addView(q);
        android.widget.RadioButton wh=new android.widget.RadioButton(this);wh.setId(View.generateViewId());wh.setText("Whisper Large V3 Turbo · mode rapide");wh.setTextColor(0xffffffff);engineChoice.addView(wh);
        root.addView(engineChoice);useQwen=QwenLocal.supported()&&getSharedPreferences("music_prompter",0).getBoolean("use_qwen_v2",false);engineChoice.check(useQwen?q.getId():wh.getId());
        engineChoice.setOnCheckedChangeListener((group,checked)->{useQwen=QwenLocal.supported()&&checked==q.getId();getSharedPreferences("music_prompter",0).edit().putBoolean("use_qwen_v2",useQwen).apply();});
        analyze=button("Extraire les paroles",()->confirmAnalysis());
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setVisibility(View.GONE);root.addView(progress,new LinearLayout.LayoutParams(-1,-2));
        cancelAnalysis=button("Annuler la transcription",()->{cancelled=true;phase="Annulation…";cancelAnalysis.setEnabled(false);});
        clock=label("00:00",16,0xffb4bbc3);
        current=label("Les paroles s'afficheront ici",34,0xff36e2a3);current.setGravity(Gravity.CENTER);
        next=label("",24,0xffffffff);next.setGravity(Gravity.CENTER);
        seek=new SeekBar(this);root.addView(seek);seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar s){drag=true;}
            public void onStopTrackingTouch(SeekBar s){if(player!=null)player.seekTo(s.getProgress());drag=false;}
            public void onProgressChanged(SeekBar s,int n,boolean user){if(user)render(n);}
        });
        play=button("Lecture / pause",()->toggle());
        button("Retour au début",()->{if(player!=null)player.seekTo(0);});
        edit=button("Éditeur de paroles · ajouter / synchroniser",()->editLines());
        leadLabel=label("Anticipation : 1,0 s",15,0xffffffff);
        SeekBar advance=new SeekBar(this);advance.setMax(30);advance.setProgress(10);root.addView(advance);
        advance.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){save();}
            public void onProgressChanged(SeekBar s,int n,boolean u){lead=n*100;leadLabel.setText(String.format(Locale.FRANCE,"Anticipation : %.1f s",lead/1000f));}
        });
        button("Écouter le morceau original",()->{if(!busy)prepare(music());});
        button("Utiliser l'instrumental",()->{if(busy)return;if(backing!=null)prepare(instrumental());else status.setText("Importe d'abord ton instrumental.");});
        load();if(b!=null){pendingPdfTitle=b.getString("pdf_title");pendingPdfText=b.getString("pdf_text");pendingExportText=b.getString("export_text");pendingExportMime=b.getString("export_mime");}advance.setProgress(lead/100);refresh();ui.post(tick);
    }
    private void choose(int request){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,request);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri u=data.getData();if(request==REQUEST_LYRICS_PDF){writePdf(u);return;}if(request==REQUEST_TRANSCRIPT){writeTranscript(u);return;}try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception e){status.setText("Accès durable indisponible : "+e.getMessage());return;}
        if(request==1){if(!lines.isEmpty()){new AlertDialog.Builder(this).setTitle("Remplacer le morceau actuel ?").setMessage("Les paroles du morceau actuel seront remplacées.").setNegativeButton("Annuler",null).setPositiveButton("Remplacer",(d,w)->setSource(u)).show();}else setSource(u);}
        else if(request==2){importTrack(u,2);}
        else if(request==3){if(source==null){status.setText("Importe d’abord ton morceau original.");return;}importTrack(u,3);}
    }
    private void setSource(Uri u){importTrack(u,1);}
    private void importTrack(Uri u,int kind){
        if(busy)return;
        busy=true;percent=-1;startedAt=SystemClock.elapsedRealtime();if(player!=null&&player.isPlaying())player.pause();
        final int originalDuration=prepared&&player!=null?player.getDuration():0;
        status.setText("Import en cours : "+fileName(u));refresh();
        worker.execute(()->{
            File converted=null;
            try{
                Uri usable=u;
                if(WavToAacTranscoder.isWav(this,u)){
                    // Native Android WAV support varies; use the existing mastering WAV converter
                    // to retain stereo and make 24/32-bit and floating-point files playable.
                    converted=File.createTempFile("prompter_audio_",".m4a",getFilesDir());
                    message("Préparation du WAV (compatible studio)…");
                    WavToAacTranscoder.transcode(this,u,converted,0L,15L*60L*1000000L);
                    usable=Uri.fromFile(converted);
                }
                MediaPlayer probe=new MediaPlayer();int length;
                try{probe.setDataSource(this,usable);probe.prepare();length=probe.getDuration();}finally{probe.release();}
                if(length<=0)throw new IOException("Durée audio inconnue");
                Uri ready=usable;int ms=length;
                ui.post(()->{if(dead)return;busy=false;
                    if(kind==2){backing=u;backingPlayable=ready;}else if(kind==3){vocal=u;vocalPlayable=ready;}else{source=u;playable=ready;backing=null;backingPlayable=null;vocal=null;vocalPlayable=null;lines.clear();prepare(ready);}
                    save();refresh();status.setText("Import réussi · "+fileName(u)+" · "+duration(ms)+(kind==2?" · instrumental":kind==3?" · voix seule. Même départ que le master requis":"")+(kind==3&&originalDuration>0&&Math.abs(ms-originalDuration)>2000?" · attention : durée différente":""));
                });
            }catch(Exception e){if(converted!=null)converted.delete();ui.post(()->{if(dead)return;busy=false;status.setText("Import impossible : "+fileName(u)+" · "+e.getMessage());refresh();});}
        });
    }
    private void openCamera(){
        if(music()==null)return;
        if(busy){
            if(analyzing&&!cancelled)new AlertDialog.Builder(this).setTitle("Ouvrir la caméra maintenant ?")
                .setMessage("La transcription sera arrêtée. Les paroles déjà enregistrées sont conservées ; tu peux filmer même sans paroles.")
                .setNegativeButton("Continuer l'analyse",null)
                .setPositiveButton("Arrêter et ouvrir",(d,w)->{cameraAfterCancel=true;cancelled=true;phase="Annulation avant ouverture caméra…";refresh();}).show();
            else Toast.makeText(this,"Préparation en cours…",Toast.LENGTH_SHORT).show();
            return;
        }
        save();
        String[] choices=new String[]{"Caméra avant · vertical 9:16","Caméra arrière · vertical 9:16","Caméra avant · horizontal 16:9","Caméra arrière · horizontal 16:9"};
        new AlertDialog.Builder(this).setTitle("Filmer avec le morceau original").setItems(choices,(d,i)->{
            if(player!=null&&player.isPlaying())player.pause();
            Intent intent=new Intent(this,ClassicCameraActivity.class);
            intent.putExtra("music_prompter_camera",true);
            intent.putExtra("music_prompter_name",fileName(source));
            intent.putExtra("music_prompter_uri",music().toString());
            intent.putExtra("music_prompter_back_camera",i%2==1);
            intent.putExtra(CaptureFormat.EXTRA_FORMAT,i<2?CaptureFormat.VERTICAL:CaptureFormat.HORIZONTAL);
            startActivity(intent);
        }).show();
    }
    private void choosePdf(){
        if(lines.isEmpty()){Toast.makeText(this,"Aucune parole enregistrée à exporter.",Toast.LENGTH_SHORT).show();return;}
        new AlertDialog.Builder(this).setTitle("Exporter toutes les paroles").setItems(new String[]{"Paroles seules","Transcription détaillée (début et fin)"},(d,i)->{
            String title=fileName(source).replaceFirst("\\.[^.]+$","");
            StringBuilder content=new StringBuilder();
            for(Line line:lines){if(i==1)content.append(TranscriptExporter.timestamp(line.start,'.')).append(" → ").append(TranscriptExporter.timestamp(line.end,'.')).append("\n");content.append(line.text).append("\n\n");}
            pendingPdfTitle=title;pendingPdfText=content.toString().trim();
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/pdf");
            intent.putExtra(Intent.EXTRA_TITLE,title+" - Paroles.pdf");
            try{startActivityForResult(intent,REQUEST_LYRICS_PDF);}catch(android.content.ActivityNotFoundException e){Toast.makeText(this,"Aucun gestionnaire de fichiers disponible.",Toast.LENGTH_LONG).show();}
        }).show();
    }
    private void writePdf(Uri target){
        final String title=pendingPdfTitle,text=pendingPdfText;
        pendingPdfTitle=null;pendingPdfText=null;
        if(title==null||text==null||text.isEmpty()){Toast.makeText(this,"Export annulé : sélectionne de nouveau les paroles.",Toast.LENGTH_LONG).show();return;}
        new Thread(()->{
            try(OutputStream out=getContentResolver().openOutputStream(target,"w")){
                if(out==null)throw new IOException("Destination PDF inaccessible");
                LyricsPdfExporter.write(out,title,text);
                ui.post(()->{if(!dead)new AlertDialog.Builder(this).setTitle("PDF enregistré").setMessage("Toutes les paroles sont dans le document choisi.").setPositiveButton("Ouvrir",(d,w)->{
                    try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(target,"application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(android.content.ActivityNotFoundException e){Toast.makeText(this,"PDF enregistré. Aucun lecteur PDF installé.",Toast.LENGTH_LONG).show();}
                }).setNeutralButton("Partager",(d,w)->shareExport(target,"application/pdf")).setNegativeButton("Fermer",null).show();});
            }catch(Exception e){ui.post(()->{if(!dead)Toast.makeText(this,"Export PDF impossible : "+e.getMessage(),Toast.LENGTH_LONG).show();});}
        },"lyrics-pdf").start();
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("pdf_title",pendingPdfTitle);state.putString("pdf_text",pendingPdfText);state.putString("export_text",pendingExportText);state.putString("export_mime",pendingExportMime);}
    private void chooseTranscript(){
        if(lines.isEmpty())return;
        String[] names={"SRT · sous-titres universels","VTT · sous-titres Web","TXT · transcription détaillée","TXT · paroles seules"};
        new AlertDialog.Builder(this).setTitle("Exporter les paroles corrigées").setItems(names,(dialog,index)->{
            ArrayList<TranscriptExporter.Cue> cues=new ArrayList<>();
            for(Line l:lines)cues.add(new TranscriptExporter.Cue(l.start,l.end,l.text));
            String title=fileName(source).replaceFirst("\\.[^.]+$","");
            String[] exts={"srt","vtt","txt","txt"};
            String[] mimes={"application/x-subrip","text/vtt","text/plain","text/plain"};
            pendingExportText=TranscriptExporter.format(cues,index,title);
            pendingExportMime=mimes[index];
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(pendingExportMime);
            intent.putExtra(Intent.EXTRA_TITLE,title+(index==2?" - transcription":index==3?" - paroles":" - sous-titres")+"."+exts[index]);
            try{startActivityForResult(intent,REQUEST_TRANSCRIPT);}catch(android.content.ActivityNotFoundException e){pendingExportText=null;pendingExportMime=null;Toast.makeText(this,"Aucun gestionnaire de fichiers disponible.",Toast.LENGTH_LONG).show();}
        }).show();
    }
    private void writeTranscript(Uri target){
        final String content=pendingExportText,mime=pendingExportMime;
        pendingExportText=null;pendingExportMime=null;
        if(content==null||mime==null){Toast.makeText(this,"Sélectionne de nouveau le format.",Toast.LENGTH_LONG).show();return;}
        new Thread(()->{
            try(OutputStream out=getContentResolver().openOutputStream(target,"w")){
                if(out==null)throw new IOException("Destination inaccessible");
                out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();
                ui.post(()->{if(!dead)new AlertDialog.Builder(this).setTitle("Export enregistré").setMessage("Fichier UTF-8 prêt à être importé dans une autre application. Les horaires restent à vérifier.").setNegativeButton("Fermer",null).setPositiveButton("Partager",(d,w)->shareExport(target,mime)).show();});
            }catch(Exception e){ui.post(()->{if(!dead)Toast.makeText(this,"Export impossible : "+e.getMessage(),Toast.LENGTH_LONG).show();});}
        },"lyrics-export").start();
    }
    private void shareExport(Uri uri,String mime){
        Intent send=new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(android.content.ClipData.newRawUri("Export",uri));
        try{startActivity(Intent.createChooser(send,"Partager le fichier"));}catch(android.content.ActivityNotFoundException e){Toast.makeText(this,"Fichier enregistré.",Toast.LENGTH_SHORT).show();}
    }
    private void finishCameraRequest(){if(cameraAfterCancel){cameraAfterCancel=false;openCamera();}}
    private File model(){return new File(getFilesDir(),"ggml-large-v3-turbo-q5_0.bin");}
    private void confirmAnalysis(){if(source==null||busy)return;if(!QwenLocal.supported())useQwen=false;runAnalysis();}
    private void message(String s){ui.post(()->{if(!dead){if(analyzing){phase=s;}status.setText(s);}});}
    private void reportProgress(int value){ui.post(()->{if(dead||!analyzing||cancelled)return;analysisProgress=Math.max(analysisProgress,Math.min(100,Math.max(0,value)));if(!useQwen)phase="Transcription Large V3 Turbo";progress.setIndeterminate(analysisProgress==0);if(analysisProgress>0)progress.setProgress(analysisProgress);});}
    private void ensureModel() throws Exception {
        File dst=model();if(dst.isFile()&&dst.length()>=MODEL_MIN_BYTES)return;
        if(getFilesDir().getUsableSpace()<650000000L)throw new IOException("Libère 650 Mo pour préparer Whisper.");
        if(dst.exists()&&!dst.delete())throw new IOException("Impossible de remplacer le modèle incomplet");
        File tmp=File.createTempFile("turbo-model-", ".part",getFilesDir());
        MessageDigest hash=MessageDigest.getInstance("SHA-1");long total=0,last=0;
        try {
            message("Préparation locale de Whisper inclus…");
            try(InputStream in=getAssets().open("models/ggml-large-v3-turbo-q5_0.bin");OutputStream out=new BufferedOutputStream(new FileOutputStream(tmp))){
                byte[] buf=new byte[65536];int count;
                while((count=in.read(buf))!=-1){if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedException();out.write(buf,0,count);hash.update(buf,0,count);total+=count;if(total>650000000L)throw new IOException("Modèle trop grand");if(total-last>8000000){last=total;message("Préparation locale Whisper : "+total/1000000+" / 574 Mo");}}
            }
            StringBuilder hex=new StringBuilder();for(byte b:hash.digest())hex.append(String.format(Locale.US,"%02x",b&255));
            if(!MODEL_SHA1.equals(hex.toString()))throw new IOException("Intégrité du modèle incorrecte");
            if(!tmp.renameTo(dst))throw new IOException("Impossible de sauvegarder le modèle");
        }finally{tmp.delete();}
    }
    private void runAnalysis(){
        if(busy||source==null)return;
        busy=true;analyzing=true;cancelled=false;analysisProgress=-1;percent=-1;startedAt=SystemClock.elapsedRealtime();phase="Préparation du modèle";if(player!=null&&player.isPlaying())player.pause();refresh();
        Uri input=vocalPlayable!=null?vocalPlayable:(vocal!=null?vocal:music());
        final boolean separate=QwenLocal.supported()&&vocal==null&&isolateVoice.isChecked();separationUsed=false;separationNote="";
        worker.execute(()->{
            try{
                if(useQwen)QwenLocal.ensure(this,new QwenLocal.Listener(){public void status(String s){message(s);}public void progress(int v){reportProgress(v);}public boolean abort(){return cancelled||dead;}});else ensureModel();
                if(cancelled)throw new InterruptedException();
                message("Décodage de la piste audio…");
                float[] pcm;
                if(separate){
                    try{
                        message("Décodage stéréo pour UVR…");
                        pcm=UvrLocal.vocals(this,PrompterAudioDecoder.decodeStereo(this,input),Math.max(1,Runtime.getRuntime().availableProcessors()-1),new UvrLocal.Listener(){
                            public void status(String s){message(s);}
                            public boolean abort(){return cancelled||dead;}
                        });
                        separationUsed=true;
                    }catch(InterruptedException e){throw e;}catch(Exception|LinkageError|OutOfMemoryError e){
                        separationNote=" · UVR non utilisé : "+(e.getMessage()==null?"ressources insuffisantes":e.getMessage());
                        message("UVR indisponible : analyse du mix original");
                        pcm=PrompterAudioDecoder.decode(this,input);
                    }
                }else pcm=PrompterAudioDecoder.decode(this,input);
                if(cancelled)throw new InterruptedException();
                // Normalize only the analysis copy. The original music remains untouched.
                float peak=0f;for(float sample:pcm)if(!Float.isNaN(sample)&&!Float.isInfinite(sample))peak=Math.max(peak,Math.abs(sample));
                if(peak<0.0001f)throw new IOException("La piste audio est silencieuse");
                float gain=Math.min(5f,0.95f/peak);
                for(int k=0;k<pcm.length;k++){float x=pcm[k]*gain;pcm[k]=(!Float.isNaN(x)&&!Float.isInfinite(x))?Math.max(-1f,Math.min(1f,x)):0f;}
                message("Chargement du modèle et analyse du premier passage…");
                String[] result=useQwen?QwenLocal.transcribe(this,pcm,Math.max(1,Math.min(6,Runtime.getRuntime().availableProcessors()-1)),new QwenLocal.Listener(){public void status(String s){message(s);}public void progress(int v){reportProgress(v);}public boolean abort(){return cancelled||dead;}}):LocalWhisper.transcribe(model().getAbsolutePath(),pcm,Math.max(1,Math.min(6,Runtime.getRuntime().availableProcessors()-1)),new LocalWhisper.Progress(){
                    public void update(int value){reportProgress(value);}
                    public boolean shouldAbort(){return cancelled||dead;}
                });
                if(cancelled||dead)throw new InterruptedException();
                if(result==null||result.length==0)throw new IOException("Aucune parole détectée. Essaie une piste voix seule exportée du studio.");
                ArrayList<Line> found=new ArrayList<>();
                for(int k=0;k+2<result.length;k+=3){String text=result[k+2].trim();long from=Long.parseLong(result[k]),to=Long.parseLong(result[k+1]);if(!text.isEmpty()&&to>from)found.add(new Line(from,to,text));}
                if(found.isEmpty())throw new IOException("Aucune parole exploitable détectée");
                ui.post(()->{if(dead)return;lines.clear();lines.addAll(found);save();busy=false;analyzing=false;status.setText(lines.size()+" passages détectés · "+(useQwen?"horaires approximatifs, à corriger":"vérifie les paroles")+(separationUsed?" · voix isolée":separationNote));refresh();prepare(music());finishCameraRequest();});
            }catch(Exception|LinkageError|OutOfMemoryError e){ui.post(()->{if(dead)return;busy=false;analyzing=false;status.setText(cancelled?"Transcription annulée · anciennes paroles conservées":"Analyse impossible : "+(e instanceof OutOfMemoryError?"mémoire insuffisante pour ce modèle":e.getMessage()));refresh();finishCameraRequest();});}
        });
    }
    private void prepare(Uri uri){if(uri==null||busy)return;releasePlayer();prepared=false;try{player=new MediaPlayer();player.setDataSource(this,uri);player.prepare();prepared=true;seek.setMax(player.getDuration());player.setOnCompletionListener(p->render(p.getDuration()));}catch(Exception e){releasePlayer();status.setText("Lecture impossible : "+e.getMessage());}}
    private void toggle(){if(busy)return;if(player==null)prepare(music());if(player!=null){if(player.isPlaying())player.pause();else player.start();}}
    private final Runnable tick=new Runnable(){public void run(){if(dead)return;if(mcp!=null&&mcpStatus!=null&&!mcpStatus.getText().toString().equals(mcp.status()))mcpStatus.setText(mcp.status());if(analyzing){status.setText(phase+(analysisProgress>0?" · "+analysisProgress+" %":"")+" · "+duration(SystemClock.elapsedRealtime()-startedAt));}if(player!=null&&!drag){try{int t=player.getCurrentPosition();seek.setProgress(t);render(t);}catch(Exception ignored){}}ui.postDelayed(this,80);}};
    private void render(long t){clock.setText(duration(t)+" / "+duration(player==null?0:player.getDuration()));long at=t+lead;active=-1;int upcoming=-1;for(int i=0;i<lines.size();i++){Line l=lines.get(i);if(l.start>at){upcoming=i;break;}if(at<l.end)active=i;}current.setText(active<0?"Prépare-toi…":lines.get(active).text);next.setText(upcoming>=0?lines.get(upcoming).text:"");}
    private void showMcp(){
        if(mcp.paired()){
            String[] options={mcp.enabled()?"Désactiver le pilotage":"Activer le pilotage","Dissocier ce téléphone"};
            
            new AlertDialog.Builder(this).setTitle("Connexion ChatGPT · "+mcp.status()).setItems(options,(d,i)->{
                if(i==0)mcp.setEnabled(!mcp.enabled());
                else new AlertDialog.Builder(this).setTitle("Dissocier ?").setMessage("Le pilotage distant sera arrêté. Tes paroles et médias seront conservés.").setNegativeButton("Annuler",null).setPositiveButton("Dissocier",(q,w)->mcp.unlink((ok,msg)->Toast.makeText(this,msg,Toast.LENGTH_LONG).show())).show();
            }).setNegativeButton("Fermer",null).show();return;
        }
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(24,8,24,8);
        TextView help=new TextView(this);help.setText("Utilise CHK Browser Local 3 dans ChatGPT pour générer une invitation à usage unique. Le serveur doit être accessible en HTTPS. Les textes sont transmis uniquement sur demande MCP.");box.addView(help);
        EditText url=new EditText(this);url.setSingleLine(true);url.setHint("Adresse HTTPS du serveur /mcp");url.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);url.setText(mcp.base());box.addView(url);
        EditText invite=new EditText(this);invite.setSingleLine(true);invite.setHint("Invitation chkinvite_…");invite.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);box.addView(invite);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Associer ChatGPT").setView(box).setNegativeButton("Annuler",null).setPositiveButton("Associer",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);mcp.pair(url.getText().toString(),invite.getText().toString(),(ok,msg)->{if(isFinishing())return;if(ok){dialog.dismiss();Toast.makeText(this,msg,Toast.LENGTH_LONG).show();}else{invite.setError(msg);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);}});}));
        dialog.show();
    }
    private JSONArray mcpLines()throws Exception{
        JSONArray out=new JSONArray();for(Line l:lines)out.put(new JSONArray().put(l.start).put(l.end).put(l.text));return out;
    }
    private String mcpRevision()throws Exception{
        MessageDigest md=MessageDigest.getInstance("SHA-256");md.update((source==null?"":source.toString()).getBytes("UTF-8"));byte[] h=md.digest(mcpLines().toString().getBytes("UTF-8"));StringBuilder s=new StringBuilder();for(byte b:h)s.append(String.format(Locale.US,"%02x",b&255));return s.toString();
    }
    private JSONObject mcpExecute(String action,JSONObject args)throws Exception{
        JSONObject r=new JSONObject().put("ok",true);
        if(action.equals("prompter_status"))return r.put("version",getPackageManager().getPackageInfo(getPackageName(),0).versionName).put("song",source==null?"":fileName(source)).put("duration_ms",player==null?0:player.getDuration()).put("position_ms",player==null?0:player.getCurrentPosition()).put("busy",busy).put("count",lines.size()).put("revision",mcpRevision()).put("editor_open",lyricsEditor!=null&&lyricsEditor.isShowing());
        if(action.equals("lyrics_list")){
            int from=Math.max(0,args.optInt("offset",0)),limit=Math.max(1,Math.min(25,args.optInt("limit",25)));
            JSONArray data=new JSONArray();int i=from;for(;i<lines.size()&&i-from<limit;i++){Line l=lines.get(i);JSONObject item=new JSONObject().put("index",i).put("start_ms",l.start).put("end_ms",l.end).put("text",l.text);if(data.toString().length()+item.toString().length()>100000&&data.length()>0)break;data.put(item);}
            return r.put("revision",mcpRevision()).put("total",lines.size()).put("items",data).put("next_offset",i<lines.size()?i:JSONObject.NULL);
        }
        if(busy||source==null||player==null)throw new IOException("Morceau indisponible ou analyse en cours.");
        if(lyricsEditor!=null&&lyricsEditor.isShowing())throw new IOException("Ferme l'éditeur manuel avant le pilotage distant.");
        long length=player.getDuration();
        if(action.equals("prompter_seek")){long ms=args.getLong("position_ms");if(ms<0||ms>length)throw new IOException("Horaire hors morceau");player.seekTo((int)ms);render(ms);return r.put("requested_position_ms",ms);}
        if(action.equals("prompter_play")){if(args.getBoolean("play")){prepare(music());if(player==null)throw new IOException("Lecture impossible");long ms=args.optLong("position_ms",0);if(ms<0||ms>length)throw new IOException("Horaire hors morceau");player.seekTo((int)ms);player.start();}else player.pause();return r.put("playing",player.isPlaying());}
        if(action.equals("prompter_open_camera")){openCamera();return r.put("local_confirmation_required",true).put("message","Choisis la caméra et le format sur le téléphone. Aucun enregistrement déclenché.");}
        if(action.equals("lyrics_export_pdf")){if(lines.isEmpty())throw new IOException("Aucune parole");choosePdf();return r.put("local_confirmation_required",true).put("message","Sélecteur PDF ouvert : l'utilisateur choisit le format et le fichier.");}
        if(!mcpRevision().equals(args.optString("revision")))throw new IOException("Projet modifié : relis lyrics_list et utilise la révision actuelle.");
        ArrayList<Line> updated=new ArrayList<>();for(Line l:lines)updated.add(new Line(l.start,l.end,l.text));
        android.content.SharedPreferences prefs=getSharedPreferences("music_prompter",0);
        if(action.equals("lyrics_restore")){
            JSONObject backup=new JSONObject(prefs.getString("mcp_undo","{}"));if(!Objects.equals(backup.optString("source"),source.toString()))throw new IOException("Aucune sauvegarde MCP pour ce morceau");
            JSONArray saved=backup.getJSONArray("lines");updated.clear();for(int i=0;i<saved.length();i++){JSONArray l=saved.getJSONArray(i);updated.add(new Line(l.getLong(0),l.getLong(1),l.getString(2)));}
        }else if(action.equals("lyrics_delete")){int index=args.getInt("index");if(index<0||index>=updated.size())throw new IOException("Index invalide");updated.remove(index);}
        else if(action.equals("lyrics_update")||action.equals("lyrics_add")){
            int index=action.equals("lyrics_update")?args.getInt("index"):-1;
            if(index< -1||index>=updated.size()||(action.equals("lyrics_update")&&index<0))throw new IOException("Index invalide");
            Line old=index>=0?updated.get(index):null;
            long start=args.has("start_ms")?args.getLong("start_ms"):old==null?-1:old.start;
            long end=args.has("end_ms")?args.getLong("end_ms"):old==null?-1:old.end;
            String words=args.has("text")?args.getString("text").trim():old==null?"":old.text;
            if(words.length()>12000)throw new IOException("Passage trop long");
            LyricsTimeline.validate(start,end,length,words);
            for(int i=0;i<updated.size();i++)if(i!=index&&updated.get(i).start==start)throw new IOException("Un passage commence déjà à cet instant.");
            if(index<0){if(updated.size()>=5000)throw new IOException("Trop de passages");updated.add(new Line(start,end,words));}else updated.set(index,new Line(start,end,words));
        }else throw new IOException("Commande inconnue");
        Collections.sort(updated,(x,y)->Long.compare(x.start,y.start));
        if(!prefs.edit().putString("mcp_undo",new JSONObject().put("source",source.toString()).put("lines",mcpLines()).toString()).commit())throw new IOException("Sauvegarde impossible");
        String prior=prefs.getString("project","{}");JSONObject project=new JSONObject(prior);JSONArray entries=new JSONArray();for(Line l:updated)entries.put(new JSONArray().put(l.start).put(l.end).put(l.text));project.put("lines",entries);
        if(!prefs.edit().putString("project",project.toString()).commit())throw new IOException("Enregistrement impossible");
        lines.clear();lines.addAll(updated);refresh();render(player.getCurrentPosition());status.setText("Correction ChatGPT enregistrée · "+lines.size()+" passages");
        return r.put("revision",mcpRevision()).put("count",lines.size()).put("saved",true);
    }
    private void editLines(){
        if(busy||music()==null)return;
        long position=0,length=0;
        try{if(player!=null){position=player.getCurrentPosition();if(player.isPlaying())player.pause();length=player.getDuration();}}catch(Exception ignored){}
        ArrayList<LyricsTimeline.Row> data=new ArrayList<>();
        for(Line l:lines)data.add(new LyricsTimeline.Row(l.start,l.end,l.text));
        lyricsEditor=new LyricsEditorDialog(this,music(),length,position,data,edited->{
            lines.clear();for(LyricsTimeline.Row r:edited)lines.add(new Line(r.start,r.end,r.text));
            save();refresh();if(player!=null)render(player.getCurrentPosition());status.setText(lines.size()+" passages enregistrés · caméra et exports synchronisés");
        });
    }
    private void refresh(){isolateVoice.setEnabled(!busy&&vocal==null&&QwenLocal.supported());engineChoice.setEnabled(!busy);for(int i=0;i<engineChoice.getChildCount();i++)engineChoice.getChildAt(i).setEnabled(!busy&&(i!=0||QwenLocal.supported()));pick.setEnabled(!busy);pickBacking.setEnabled(!busy);pickVocal.setEnabled(!busy&&source!=null);clearVocal.setEnabled(!busy&&vocal!=null);cancelAnalysis.setVisibility(analyzing?View.VISIBLE:View.GONE);cancelAnalysis.setEnabled(analyzing&&!cancelled);vocalInfo.setText(vocal==null?"Source de transcription : morceau original":"Voix pour analyse : "+fileName(vocal));analyze.setEnabled(!busy&&source!=null);play.setEnabled(!busy&&source!=null);edit.setEnabled(!busy&&source!=null);camera.setEnabled(source!=null&&(!busy||(analyzing&&!cancelled)));pdfExport.setEnabled(!lines.isEmpty());subtitleExport.setEnabled(!lines.isEmpty());cameraHint.setText(source==null?"Importe un morceau pour ouvrir la caméra.":analyzing?"Analyse en cours · caméra accessible en arrêtant l’analyse.":lines.isEmpty()?"Caméra disponible · pas encore de paroles enregistrées.":lines.size()+" passages disponibles · caméra et exports prêts.");progress.setVisibility(busy?View.VISIBLE:View.GONE);if(busy){progress.setIndeterminate(percent<0);if(percent>=0)progress.setProgress(percent);}fileInfo.setText(source==null?"Aucun morceau importé":"MORCEAU IMPORTÉ\n"+fileName(source)+(prepared&&player!=null?" · "+duration(player.getDuration()):""));backingInfo.setText("Instrumental : "+(backing==null?"aucun":fileName(backing)));}
    private void save(){try{JSONObject o=new JSONObject();o.put("source",source==null?"":source.toString());o.put("backing",backing==null?"":backing.toString());o.put("lead",lead);o.put("vocal",vocal==null?"":vocal.toString());o.put("vocalPlayable",vocalPlayable==null?"":vocalPlayable.toString());o.put("playable",playable==null?"":playable.toString());o.put("backingPlayable",backingPlayable==null?"":backingPlayable.toString());JSONArray a=new JSONArray();for(Line l:lines){JSONArray x=new JSONArray();x.put(l.start);x.put(l.end);x.put(l.text);a.put(x);}o.put("lines",a);getSharedPreferences("music_prompter",0).edit().putString("project",o.toString()).apply();}catch(Exception ignored){}}
    private void load(){try{JSONObject o=new JSONObject(getSharedPreferences("music_prompter",0).getString("project","{}"));String s=o.optString("source"),b=o.optString("backing");source=s.isEmpty()?null:Uri.parse(s);backing=b.isEmpty()?null:Uri.parse(b);String pp=o.optString("playable"),bp=o.optString("backingPlayable");playable=pp.isEmpty()?null:Uri.parse(pp);backingPlayable=bp.isEmpty()?null:Uri.parse(bp);String v=o.optString("vocal"),vp=o.optString("vocalPlayable");vocal=v.isEmpty()?null:Uri.parse(v);vocalPlayable=vp.isEmpty()?null:Uri.parse(vp);lead=o.optInt("lead",1000);JSONArray a=o.optJSONArray("lines");if(a!=null)for(int i=0;i<a.length();i++){JSONArray x=a.getJSONArray(i);lines.add(new Line(x.getLong(0),x.getLong(1),x.getString(2)));}if(source!=null)prepare(music());}catch(Exception ignored){}}
    private void releasePlayer(){prepared=false;if(player!=null){player.release();player=null;}}
    @Override protected void onResume(){super.onResume();if(mcp!=null)mcp.resume();}
    @Override protected void onPause(){super.onPause();if(mcp!=null)mcp.pause();if(lyricsEditor!=null)lyricsEditor.pausePlayback();if(player!=null&&player.isPlaying())player.pause();}
    @Override protected void onDestroy(){dead=true;if(mcp!=null)mcp.close();if(lyricsEditor!=null)lyricsEditor.dispose();ui.removeCallbacksAndMessages(null);worker.shutdownNow();releasePlayer();super.onDestroy();}
}

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
    private Button camera, pdfExport;
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
        fileInfo=label("Aucun morceau importé",17,0xffffffff);
        fileInfo.setBackgroundColor(0xff172820);
        camera=button("OUVRIR LA CAMÉRA + PROMPTEUR",()->openCamera());
        camera.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff087b59));
        cameraHint=label("Importe un morceau pour ouvrir la caméra.",13,0xffb4bbc3);
        pdfExport=button("Exporter les paroles en PDF",()->choosePdf());
        backingInfo=label("Instrumental : aucun",13,0xffb4bbc3);
        pick=button("Importer un audio / vidéo",()->choose(1));
        pickBacking=button("Importer instrumental (facultatif)",()->choose(2));
        vocalInfo=label("Source de transcription : morceau original",13,0xffb4bbc3);
        pickVocal=button("Importer voix studio seule (facultatif)",()->choose(3));
        clearVocal=button("Analyser le morceau original",()->{if(busy)return;vocal=null;vocalPlayable=null;save();refresh();});
        isolateVoice=new CheckBox(this);isolateVoice.setText("Isoler automatiquement la voix (UVR inclus)");isolateVoice.setTextColor(0xffffffff);isolateVoice.setChecked(QwenLocal.supported()&&getSharedPreferences("music_prompter",0).getBoolean("isolate_voice",true));root.addView(isolateVoice);isolateVoice.setOnCheckedChangeListener((v,checked)->getSharedPreferences("music_prompter",0).edit().putBoolean("isolate_voice",checked).apply());
        label("Moteur de transcription",16,0xffffffff);
        engineChoice=new android.widget.RadioGroup(this);engineChoice.setOrientation(LinearLayout.VERTICAL);
        android.widget.RadioButton q=new android.widget.RadioButton(this);q.setId(View.generateViewId());q.setText(QwenLocal.supported()?"Qwen3-ASR 0.6B · chant / musique":"Qwen3-ASR · Android 8 minimum");q.setTextColor(0xffffffff);engineChoice.addView(q);
        android.widget.RadioButton wh=new android.widget.RadioButton(this);wh.setId(View.generateViewId());wh.setText("Whisper Large V3 Turbo · secours");wh.setTextColor(0xffffffff);engineChoice.addView(wh);
        root.addView(engineChoice);useQwen=QwenLocal.supported()&&getSharedPreferences("music_prompter",0).getBoolean("use_qwen",true);engineChoice.check(useQwen?q.getId():wh.getId());
        engineChoice.setOnCheckedChangeListener((group,checked)->{useQwen=QwenLocal.supported()&&checked==q.getId();getSharedPreferences("music_prompter",0).edit().putBoolean("use_qwen",useQwen).apply();});
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
        edit=button("Corriger les paroles et horaires",()->editLines());
        leadLabel=label("Anticipation : 1,0 s",15,0xffffffff);
        SeekBar advance=new SeekBar(this);advance.setMax(30);advance.setProgress(10);root.addView(advance);
        advance.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){save();}
            public void onProgressChanged(SeekBar s,int n,boolean u){lead=n*100;leadLabel.setText(String.format(Locale.FRANCE,"Anticipation : %.1f s",lead/1000f));}
        });
        button("Écouter le morceau original",()->{if(!busy)prepare(music());});
        button("Utiliser l'instrumental",()->{if(busy)return;if(backing!=null)prepare(instrumental());else status.setText("Importe d'abord ton instrumental.");});
        load();if(b!=null){pendingPdfTitle=b.getString("pdf_title");pendingPdfText=b.getString("pdf_text");}advance.setProgress(lead/100);refresh();ui.post(tick);
    }
    private void choose(int request){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,request);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri u=data.getData();if(request==REQUEST_LYRICS_PDF){writePdf(u);return;}try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception e){status.setText("Accès durable indisponible : "+e.getMessage());return;}
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
        new AlertDialog.Builder(this).setTitle("Exporter toutes les paroles").setItems(new String[]{"Paroles seules","Paroles et horaires"},(d,i)->{
            String title=fileName(source).replaceFirst("\\.[^.]+$","");
            StringBuilder content=new StringBuilder();
            for(Line line:lines){if(i==1)content.append(String.format(Locale.FRANCE,"%02d:%02d.%03d  ",line.start/60000,(line.start/1000)%60,line.start%1000));content.append(line.text).append("\n\n");}
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
                }).setNegativeButton("Fermer",null).show();});
            }catch(Exception e){ui.post(()->{if(!dead)Toast.makeText(this,"Export PDF impossible : "+e.getMessage(),Toast.LENGTH_LONG).show();});}
        },"lyrics-pdf").start();
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("pdf_title",pendingPdfTitle);state.putString("pdf_text",pendingPdfText);}
    private void finishCameraRequest(){if(cameraAfterCancel){cameraAfterCancel=false;openCamera();}}
    private File model(){return new File(getFilesDir(),"ggml-large-v3-turbo-q5_0.bin");}
    private void confirmAnalysis(){if(source==null||busy)return;if(!QwenLocal.supported())useQwen=false;runAnalysis();}
    private void message(String s){ui.post(()->{if(!dead){if(analyzing){phase=s;analysisProgress=-1;}status.setText(s);}});}
    private void reportProgress(int value){ui.post(()->{if(dead||!analyzing||cancelled)return;analysisProgress=Math.max(analysisProgress,Math.min(100,Math.max(0,value)));phase=useQwen?"Transcription Qwen3-ASR":"Transcription Large V3 Turbo";progress.setIndeterminate(analysisProgress==0);if(analysisProgress>0)progress.setProgress(analysisProgress);});}
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
    private final Runnable tick=new Runnable(){public void run(){if(dead)return;if(analyzing){status.setText(phase+(analysisProgress>0?" · "+analysisProgress+" %":"")+" · "+duration(SystemClock.elapsedRealtime()-startedAt));}if(player!=null&&!drag){try{int t=player.getCurrentPosition();seek.setProgress(t);render(t);}catch(Exception ignored){}}ui.postDelayed(this,80);}};
    private void render(long t){clock.setText(duration(t)+" / "+duration(player==null?0:player.getDuration()));long at=t+lead;active=-1;for(int i=0;i<lines.size();i++){if(lines.get(i).start<=at)active=i;else break;}current.setText(active<0?"Prépare-toi…":lines.get(active).text);next.setText(active+1<lines.size()?lines.get(active+1).text:"");}
    private void editLines(){if(lines.isEmpty())return;String[] choices=new String[lines.size()];for(int i=0;i<lines.size();i++)choices[i]=String.format(Locale.FRANCE,"%.2f s · %s",lines.get(i).start/1000f,lines.get(i).text);new AlertDialog.Builder(this).setTitle("Choisir un passage").setItems(choices,(d,i)->editLine(i)).setNegativeButton("Fermer",null).show();}
    private void editLine(int i){Line l=lines.get(i);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(24,12,24,12);EditText time=new EditText(this),words=new EditText(this);time.setHint("Début en secondes");time.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);time.setText(String.format(Locale.US,"%.2f",l.start/1000f));words.setText(l.text);box.addView(time);box.addView(words);new AlertDialog.Builder(this).setTitle("Corriger le passage").setView(box).setNegativeButton("Annuler",null).setPositiveButton("Enregistrer",(d,w)->{try{long v=Math.round(Double.parseDouble(time.getText().toString().replace(',','.'))*1000);if(v<0||v>900000)throw new Exception();l.start=v;l.text=words.getText().toString().trim();Collections.sort(lines,(a,b)->Long.compare(a.start,b.start));save();}catch(Exception e){status.setText("Horaire invalide");}}).show();}
    private void refresh(){isolateVoice.setEnabled(!busy&&vocal==null&&QwenLocal.supported());engineChoice.setEnabled(!busy);for(int i=0;i<engineChoice.getChildCount();i++)engineChoice.getChildAt(i).setEnabled(!busy&&(i!=0||QwenLocal.supported()));pick.setEnabled(!busy);pickBacking.setEnabled(!busy);pickVocal.setEnabled(!busy&&source!=null);clearVocal.setEnabled(!busy&&vocal!=null);cancelAnalysis.setVisibility(analyzing?View.VISIBLE:View.GONE);cancelAnalysis.setEnabled(analyzing&&!cancelled);vocalInfo.setText(vocal==null?"Source de transcription : morceau original":"Voix pour analyse : "+fileName(vocal));analyze.setEnabled(!busy&&source!=null);play.setEnabled(!busy&&source!=null);edit.setEnabled(!busy&&!lines.isEmpty());camera.setEnabled(source!=null&&(!busy||(analyzing&&!cancelled)));pdfExport.setEnabled(!lines.isEmpty());cameraHint.setText(source==null?"Importe un morceau pour ouvrir la caméra.":analyzing?"Analyse en cours · caméra accessible en arrêtant l’analyse.":lines.isEmpty()?"Caméra disponible · pas encore de paroles enregistrées.":lines.size()+" passages disponibles · caméra et PDF prêts.");progress.setVisibility(busy?View.VISIBLE:View.GONE);if(busy){progress.setIndeterminate(percent<0);if(percent>=0)progress.setProgress(percent);}fileInfo.setText(source==null?"Aucun morceau importé":"MORCEAU IMPORTÉ\n"+fileName(source)+(prepared&&player!=null?" · "+duration(player.getDuration()):""));backingInfo.setText("Instrumental : "+(backing==null?"aucun":fileName(backing)));}
    private void save(){try{JSONObject o=new JSONObject();o.put("source",source==null?"":source.toString());o.put("backing",backing==null?"":backing.toString());o.put("lead",lead);o.put("vocal",vocal==null?"":vocal.toString());o.put("vocalPlayable",vocalPlayable==null?"":vocalPlayable.toString());o.put("playable",playable==null?"":playable.toString());o.put("backingPlayable",backingPlayable==null?"":backingPlayable.toString());JSONArray a=new JSONArray();for(Line l:lines){JSONArray x=new JSONArray();x.put(l.start);x.put(l.end);x.put(l.text);a.put(x);}o.put("lines",a);getSharedPreferences("music_prompter",0).edit().putString("project",o.toString()).apply();}catch(Exception ignored){}}
    private void load(){try{JSONObject o=new JSONObject(getSharedPreferences("music_prompter",0).getString("project","{}"));String s=o.optString("source"),b=o.optString("backing");source=s.isEmpty()?null:Uri.parse(s);backing=b.isEmpty()?null:Uri.parse(b);String pp=o.optString("playable"),bp=o.optString("backingPlayable");playable=pp.isEmpty()?null:Uri.parse(pp);backingPlayable=bp.isEmpty()?null:Uri.parse(bp);String v=o.optString("vocal"),vp=o.optString("vocalPlayable");vocal=v.isEmpty()?null:Uri.parse(v);vocalPlayable=vp.isEmpty()?null:Uri.parse(vp);lead=o.optInt("lead",1000);JSONArray a=o.optJSONArray("lines");if(a!=null)for(int i=0;i<a.length();i++){JSONArray x=a.getJSONArray(i);lines.add(new Line(x.getLong(0),x.getLong(1),x.getString(2)));}if(source!=null)prepare(music());}catch(Exception ignored){}}
    private void releasePlayer(){prepared=false;if(player!=null){player.release();player=null;}}
    @Override protected void onPause(){super.onPause();if(player!=null&&player.isPlaying())player.pause();}
    @Override protected void onDestroy(){dead=true;ui.removeCallbacksAndMessages(null);worker.shutdownNow();releasePlayer();super.onDestroy();}
}

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
    private Uri source,backing;
    private MediaPlayer player;
    private TextView status,current,next,clock,leadLabel;
    private Button analyze,play,pick,pickBacking,edit;
    private SeekBar seek;
    private LinearLayout root;
    private boolean busy=false,drag=false,dead=false;
    private int lead=1000,active=-1;
    private static final String MODEL_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/0b364b566045a405be7225ee1e415a073e04da77/ggml-small-q5_1.bin";
    private static final String MODEL_SHA1="6fe57ddcfdd1c6b07cdcc73aaf620810ce5fc771";
    private static final class Line {
        long start,end;String text;
        Line(long a,long b,String t){start=a;end=b;text=t;}
    }
    private TextView label(String s,int sp,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setPadding(0,12,0,12);root.addView(v);return v;}
    private Button button(String s,Runnable action){Button v=new Button(this);v.setText(s);v.setOnClickListener(w->action.run());root.addView(v);return v;}
    @Override public void onCreate(Bundle b){
        super.onCreate(b);setContentView(R.layout.activity_music_prompter);root=findViewById(R.id.prompterRoot);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        button("Retour",()->finish());
        label("PROMPTEUR MUSICAL IA",25,0xff36e2a3);
        status=label("Whisper local · aucun envoi audio",14,0xffb4bbc3);
        pick=button("Importer chanson MP3 / WAV",()->choose(1));
        pickBacking=button("Importer instrumental (facultatif)",()->choose(2));
        analyze=button("Extraire les paroles",()->confirmAnalysis());
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
        button("Utiliser le morceau original",()->prepare(source));
        button("Utiliser l'instrumental",()->{if(backing!=null)prepare(backing);else status.setText("Importe d'abord ton instrumental.");});
        load();advance.setProgress(lead/100);refresh();ui.post(tick);
    }
    private void choose(int request){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("audio/*");i.addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,request);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri u=data.getData();try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception e){status.setText("Accès durable indisponible : "+e.getMessage());return;}
        if(request==1){if(!lines.isEmpty()){new AlertDialog.Builder(this).setTitle("Remplacer le morceau actuel ?").setMessage("Les paroles du morceau actuel seront remplacées.").setNegativeButton("Annuler",null).setPositiveButton("Remplacer",(d,w)->setSource(u)).show();}else setSource(u);}
        else if(request==2){backing=u;save();status.setText("Instrumental importé. Utilise-le avec la même structure que la chanson.");}
    }
    private void setSource(Uri u){source=u;backing=null;lines.clear();save();prepare(source);refresh();}
    private File model(){return new File(getFilesDir(),"ggml-small-q5_1.bin");}
    private void confirmAnalysis(){if(source==null)return;if(!model().exists()){new AlertDialog.Builder(this).setTitle("Télécharger le modèle local").setMessage("Premier téléchargement : environ 190 Mo. Ensuite, la transcription se fait sans Internet sur le téléphone.").setNegativeButton("Annuler",null).setPositiveButton("Télécharger et analyser",(d,w)->runAnalysis()).show();}else runAnalysis();}
    private void message(String s){ui.post(()->{if(!dead)status.setText(s);});}
    private void ensureModel() throws Exception {
        File dst=model();if(dst.exists())return;
        File tmp=new File(getFilesDir(),"whisper-model.part");
        HttpsURLConnectionHolder h=new HttpsURLConnectionHolder();
        MessageDigest hash=MessageDigest.getInstance("SHA-1");long total=0;
        try{
            h.conn=(HttpURLConnection)new URL(MODEL_URL).openConnection();h.conn.setConnectTimeout(20000);h.conn.setReadTimeout(30000);
            if(h.conn.getResponseCode()!=200)throw new IOException("Téléchargement modèle : HTTP "+h.conn.getResponseCode());
            try(InputStream in=h.conn.getInputStream();OutputStream out=new FileOutputStream(tmp)){
                byte[] buf=new byte[65536];int n;long last=0;
                while((n=in.read(buf))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException();out.write(buf,0,n);hash.update(buf,0,n);total+=n;if(total>250000000)throw new IOException("Modèle trop grand");if(total-last>4000000){last=total;message("Modèle : "+total/1000000+" Mo / 190 Mo");}}
            }
            StringBuilder hex=new StringBuilder();for(byte b:hash.digest())hex.append(String.format(Locale.US,"%02x",b&255));
            if(!MODEL_SHA1.equals(hex.toString()))throw new IOException("Intégrité du modèle incorrecte");
            if(!tmp.renameTo(dst))throw new IOException("Impossible de sauvegarder le modèle");
        }finally{if(h.conn!=null)h.conn.disconnect();tmp.delete();}
    }
    private static class HttpsURLConnectionHolder{HttpURLConnection conn;}
    private void runAnalysis(){
        if(busy||source==null)return;busy=true;if(player!=null&&player.isPlaying())player.pause();refresh();Uri input=source;
        worker.execute(()->{try{ensureModel();message("Décodage audio…");float[] pcm=PrompterAudioDecoder.decode(this,input);message("Transcription française sur ton téléphone…");String[] result=LocalWhisper.transcribe(model().getAbsolutePath(),pcm,Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()-1)));if(result==null||result.length==0)throw new IOException("Aucune parole détectée");
            ArrayList<Line> found=new ArrayList<>();for(int i=0;i+2<result.length;i+=3){String text=result[i+2].trim();if(!text.isEmpty())found.add(new Line(Long.parseLong(result[i]),Long.parseLong(result[i+1]),text));}if(found.isEmpty())throw new IOException("Aucune parole détectée");
            ui.post(()->{if(dead)return;lines.clear();lines.addAll(found);save();busy=false;status.setText(lines.size()+" passages détectés · corrige si nécessaire");refresh();prepare(source);});
        }catch(Exception|LinkageError e){ui.post(()->{if(dead)return;busy=false;status.setText("Analyse impossible : "+e.getMessage());refresh();});}});
    }
    private void prepare(Uri uri){if(uri==null||busy)return;releasePlayer();try{player=new MediaPlayer();player.setDataSource(this,uri);player.prepare();seek.setMax(player.getDuration());player.setOnCompletionListener(p->render(p.getDuration()));}catch(Exception e){releasePlayer();status.setText("Lecture impossible : "+e.getMessage());}}
    private void toggle(){if(busy)return;if(player==null)prepare(source);if(player!=null){if(player.isPlaying())player.pause();else player.start();}}
    private final Runnable tick=new Runnable(){public void run(){if(dead)return;if(player!=null&&!drag){try{int t=player.getCurrentPosition();seek.setProgress(t);render(t);}catch(Exception ignored){}}ui.postDelayed(this,80);}};
    private void render(long t){clock.setText(String.format(Locale.FRANCE,"%02d:%02d",t/60000,(t/1000)%60));long at=t+lead;active=-1;for(int i=0;i<lines.size();i++){if(lines.get(i).start<=at)active=i;else break;}current.setText(active<0?"Prépare-toi…":lines.get(active).text);next.setText(active+1<lines.size()?lines.get(active+1).text:"");}
    private void editLines(){if(lines.isEmpty())return;String[] choices=new String[lines.size()];for(int i=0;i<lines.size();i++)choices[i]=String.format(Locale.FRANCE,"%.2f s · %s",lines.get(i).start/1000f,lines.get(i).text);new AlertDialog.Builder(this).setTitle("Choisir un passage").setItems(choices,(d,i)->editLine(i)).setNegativeButton("Fermer",null).show();}
    private void editLine(int i){Line l=lines.get(i);LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(24,12,24,12);EditText time=new EditText(this),words=new EditText(this);time.setHint("Début en secondes");time.setInputType(8194);time.setText(String.format(Locale.US,"%.2f",l.start/1000f));words.setText(l.text);box.addView(time);box.addView(words);new AlertDialog.Builder(this).setTitle("Corriger le passage").setView(box).setNegativeButton("Annuler",null).setPositiveButton("Enregistrer",(d,w)->{try{long v=Math.round(Double.parseDouble(time.getText().toString().replace(',','.'))*1000);if(v<0||v>900000)throw new Exception();l.start=v;l.text=words.getText().toString().trim();Collections.sort(lines,(a,b)->Long.compare(a.start,b.start));save();}catch(Exception e){status.setText("Horaire invalide");}}).show();}
    private void refresh(){pick.setEnabled(!busy);pickBacking.setEnabled(!busy);analyze.setEnabled(!busy&&source!=null);play.setEnabled(!busy&&source!=null);edit.setEnabled(!busy&&!lines.isEmpty());}
    private void save(){try{JSONObject o=new JSONObject();o.put("source",source==null?"":source.toString());o.put("backing",backing==null?"":backing.toString());o.put("lead",lead);JSONArray a=new JSONArray();for(Line l:lines){JSONArray x=new JSONArray();x.put(l.start);x.put(l.end);x.put(l.text);a.put(x);}o.put("lines",a);getSharedPreferences("music_prompter",0).edit().putString("project",o.toString()).apply();}catch(Exception ignored){}}
    private void load(){try{JSONObject o=new JSONObject(getSharedPreferences("music_prompter",0).getString("project","{}"));String s=o.optString("source"),b=o.optString("backing");source=s.isEmpty()?null:Uri.parse(s);backing=b.isEmpty()?null:Uri.parse(b);lead=o.optInt("lead",1000);JSONArray a=o.optJSONArray("lines");if(a!=null)for(int i=0;i<a.length();i++){JSONArray x=a.getJSONArray(i);lines.add(new Line(x.getLong(0),x.getLong(1),x.getString(2)));}if(source!=null)prepare(source);}catch(Exception ignored){}}
    private void releasePlayer(){if(player!=null){player.release();player=null;}}
    @Override protected void onPause(){super.onPause();if(player!=null&&player.isPlaying())player.pause();}
    @Override protected void onDestroy(){dead=true;ui.removeCallbacksAndMessages(null);worker.shutdownNow();releasePlayer();super.onDestroy();}
}

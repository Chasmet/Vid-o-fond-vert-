package com.chasmet.fondvertstudio;

import android.app.Dialog;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;

/** Independent non-destructive timeline editor. Only Save commits to the project. */
final class LyricsEditorDialog {
    interface Saved { void accept(ArrayList<LyricsTimeline.Row> rows); }
    private final Context context;
    private final Dialog dialog;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayDeque<ArrayList<LyricsTimeline.Row>> undo=new ArrayDeque<>();
    private ArrayList<LyricsTimeline.Row> rows;
    private LyricsTimeline.Row selected;
    private final Saved saved;
    private final Uri music;
    private long duration;
    private MediaPlayer player;
    private boolean ready,drag,closed,dirty,loading;
    private long pendingSeek=-1;
    private SeekBar seek;
    private TextView clock,info,preview;
    private EditText start,end,words;
    private LinearLayout panel,list;
    private ScrollView editorScroll;
    private Button play,save,undoButton;
    private final int fg=0xfff4f6f7, accent=0xff58e4b0, bg=0xff10171c;
    LyricsEditorDialog(Context c,Uri u,long length,long position,ArrayList<LyricsTimeline.Row> data,Saved callback){
        context=c;music=u;duration=Math.max(0,length);saved=callback;rows=LyricsTimeline.copy(data);LyricsTimeline.sort(rows);
        dialog=new Dialog(c);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root=new LinearLayout(c);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);root.setPadding(dp(12),dp(10),dp(12),dp(8));
        TextView title=text(root,"ÉDITEUR DE PAROLES",22,accent);title.setGravity(Gravity.CENTER);
        LinearLayout header=horizontal(root);
        button(header,"Fermer",this::closeRequest);undoButton=button(header,"Annuler action",this::undo);save=button(header,"Enregistrer",this::commitAll);
        clock=text(root,"00:00.000 / "+LyricsTimeline.format(duration),17,fg);
        seek=new SeekBar(c);seek.setMax((int)duration);seek.setProgress((int)Math.min(position,duration));root.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){drag=true;}
            public void onStopTrackingTouch(SeekBar b){jump(b.getProgress());drag=false;}
            public void onProgressChanged(SeekBar b,int value,boolean user){if(user)showPosition(value);}
        });
        LinearLayout transport=horizontal(root);button(transport,"− 1 s",()->jump(position()-1000));play=button(transport,"Lecture",this::toggle);button(transport,"+ 1 s",()->jump(position()+1000));play.setEnabled(false);
        preview=text(root,"",14,accent);preview.setMaxLines(2);
        info=text(root,"Préparation de la musique…",13,fg);
        ScrollView scroll=new ScrollView(c);editorScroll=scroll;root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout content=new LinearLayout(c);content.setOrientation(LinearLayout.VERTICAL);scroll.addView(content);
        LinearLayout add=horizontal(content);button(add,"+ Passage ici",()->addAt(position()));button(add,"+ Couplets",this::bulk);
        LinearLayout addManual=horizontal(content);button(addManual,"+ Horaire précis",()->manual(false));button(addManual,"Aller à un horaire",this::goTo);
        panel=new LinearLayout(c);panel.setOrientation(LinearLayout.VERTICAL);content.addView(panel);
        text(panel,"Début et fin : mm:ss.mmm, secondes ou 64000 ms",13,fg);
        LinearLayout times=horizontal(panel);start=field(times,"Début",false);end=field(times,"Fin",false);
        LinearLayout marks=horizontal(panel);button(marks,"Début = lecture",()->start.setText(LyricsTimeline.format(position())));button(marks,"Fin = lecture",()->end.setText(LyricsTimeline.format(position())));
        LinearLayout adjust=horizontal(panel);button(adjust,"Début − 0,1 s",()->nudge(start,-100));button(adjust,"Début + 0,1 s",()->nudge(start,100));
        LinearLayout adjustEnd=horizontal(panel);button(adjustEnd,"Fin − 0,1 s",()->nudge(end,-100));button(adjustEnd,"Fin + 0,1 s",()->nudge(end,100));
        words=field(panel,"Paroles du passage (plusieurs lignes possibles)",true);
        LinearLayout actions=horizontal(panel);button(actions,"Appliquer",()->{if(apply()){rebuild();notice("Passage modifié");}});button(actions,"Écouter",this::listen);
        LinearLayout more=horizontal(panel);button(more,"Scinder ici",this::split);button(more,"Supprimer",this::remove);
        LinearLayout duplicate=horizontal(panel);button(duplicate,"Dupliquer à un autre horaire",()->manual(true));
        text(content,"PASSAGES · toucher pour modifier",16,accent);
        list=new LinearLayout(c);list.setOrientation(LinearLayout.VERTICAL);content.addView(list);
        dialog.setContentView(root);dialog.setCancelable(false);
        dialog.setOnKeyListener((d,key,event)->{if(key==android.view.KeyEvent.KEYCODE_BACK&&event.getAction()==android.view.KeyEvent.ACTION_UP){closeRequest();return true;}return false;});
        dialog.setOnDismissListener(d->release());
        select(rows.isEmpty()?null:rows.get(0));rebuild();showPosition(position);dialog.show();
        Window w=dialog.getWindow();if(w!=null){w.setLayout(-1,-1);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
        try{
            player=new MediaPlayer();player.setDataSource(c,music);
            player.setOnPreparedListener(mp->{if(closed)return;ready=true;duration=mp.getDuration();seek.setMax((int)duration);jump(position);play.setEnabled(true);notice("Place le curseur puis ajoute ou corrige un passage.");});
            player.setOnSeekCompleteListener(mp->{pendingSeek=-1;});
            player.setOnCompletionListener(mp->{play.setText("Lecture");});
            player.setOnErrorListener((mp,a,b)->{ready=false;play.setEnabled(false);notice("Lecture indisponible. Tu peux saisir les horaires manuellement.");return true;});
            player.prepareAsync();
        }catch(Exception e){notice("Lecture indisponible. Saisie manuelle possible.");}
        handler.post(tick);
    }
    private int dp(int v){return Math.round(v*context.getResources().getDisplayMetrics().density);}
    private TextView text(LinearLayout parent,String value,int size,int color){TextView t=new TextView(context);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(4),dp(4),dp(4),dp(4));parent.addView(t,new LinearLayout.LayoutParams(-1,-2));return t;}
    private LinearLayout horizontal(LinearLayout parent){LinearLayout l=new LinearLayout(context);l.setOrientation(LinearLayout.HORIZONTAL);parent.addView(l,new LinearLayout.LayoutParams(-1,-2));return l;}
    private Button button(LinearLayout parent,String value,Runnable callback){Button b=new com.google.android.material.button.MaterialButton(context);b.setText(value);b.setAllCaps(false);b.setTextSize(12);b.setTextColor(fg);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff263e3c));b.setOnClickListener(v->callback.run());parent.addView(b,new LinearLayout.LayoutParams(0,-2,1));return b;}
    private EditText field(LinearLayout parent,String hint,boolean multi){EditText e=new EditText(context);e.setTextColor(fg);e.setHintTextColor(0xffabb6bc);e.setHint(hint);e.setTextSize(17);
        e.setInputType(multi?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if(multi){e.setMinLines(4);e.setMaxLines(9);e.setGravity(Gravity.TOP|Gravity.START);}else e.setSingleLine(true);
        parent.addView(e,multi?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1));return e;}
    private void notice(String s){info.setText(s);}
    private long position(){if(pendingSeek>=0)return pendingSeek;if(ready&&player!=null&&!drag){try{return player.getCurrentPosition();}catch(Exception ignored){}}return seek.getProgress();}
    private void jump(long at){int n=(int)Math.max(0,Math.min(duration,at));seek.setProgress(n);if(ready&&player!=null){try{pendingSeek=n;player.seekTo(n);}catch(Exception ignored){}}showPosition(n);}
    private void showPosition(long at){clock.setText(LyricsTimeline.format(at)+" / "+LyricsTimeline.format(duration));int i=LyricsTimeline.active(rows,at);preview.setText(i<0?"Aucun passage à cet instant":rows.get(i).text);}
    private long stopAt=-1;
    private final Runnable tick=new Runnable(){public void run(){if(closed)return;if(ready&&!drag&&player!=null){try{int at=(int)position();if(stopAt>=0&&at>=stopAt){player.pause();stopAt=-1;play.setText("Lecture");}seek.setProgress(at);showPosition(at);}catch(Exception ignored){}}handler.postDelayed(this,80);}};
    private void toggle(){if(!ready)return;try{stopAt=-1;if(player.isPlaying()){player.pause();play.setText("Lecture");}else{player.start();play.setText("Pause");}}catch(Exception e){notice("Lecture impossible");}}
    private void snapshot(){undo.push(LyricsTimeline.copy(rows));while(undo.size()>40)undo.removeLast();dirty=true;undoButton.setEnabled(true);}
    private void select(LyricsTimeline.Row r){selected=r;loading=true;panel.setVisibility(r==null?View.GONE:View.VISIBLE);if(r!=null){start.setText(LyricsTimeline.format(r.start));end.setText(LyricsTimeline.format(r.end));words.setText(r.text);}loading=false;}
    private boolean pending(){if(selected==null||loading)return false;return !start.getText().toString().equals(LyricsTimeline.format(selected.start))||!end.getText().toString().equals(LyricsTimeline.format(selected.end))||!words.getText().toString().equals(selected.text);}
    private boolean apply(){
        if(selected==null)return true;
        try{long s=LyricsTimeline.parse(start.getText().toString()),e=LyricsTimeline.parse(end.getText().toString());String t=words.getText().toString().trim();
            LyricsTimeline.validate(s,e,duration,t);
            for(LyricsTimeline.Row row:rows)if(row!=selected&&row.start==s)throw new IllegalArgumentException("Un passage commence déjà à cet instant");
            if(s!=selected.start||e!=selected.end||!t.equals(selected.text)){snapshot();selected.start=s;selected.end=e;selected.text=t;LyricsTimeline.sort(rows);}
            start.setText(LyricsTimeline.format(s));end.setText(LyricsTimeline.format(e));return true;
        }catch(IllegalArgumentException ex){notice(ex.getMessage());return false;}
    }
    private void rebuild(){
        list.removeAllViews();undoButton.setEnabled(!undo.isEmpty());
        if(rows.isEmpty())text(list,"Aucune parole : ajoute un passage à la position de lecture.",15,fg);
        for(LyricsTimeline.Row r:rows){
            TextView v=text(list,LyricsTimeline.format(r.start)+"  →  "+LyricsTimeline.format(r.end)+"\n"+(r.text.isEmpty()?"(passage à compléter)":r.text),16,r==selected?accent:fg);
            v.setBackgroundColor(r==selected?0xff243b39:0xff182229);v.setPadding(dp(10),dp(9),dp(10),dp(9));
            v.setOnClickListener(view->{if(!apply())return;select(r);jump(r.start);rebuild();editorScroll.smoothScrollTo(0,0);});
        }
    }
    private void addAt(long at){if(!apply())return;try{
        ArrayList<LyricsTimeline.Row> before=LyricsTimeline.copy(rows);LyricsTimeline.Row r=LyricsTimeline.insert(rows,at,duration,"");
        undo.push(before);dirty=true;select(r);rebuild();words.requestFocus();notice("Écris les paroles puis applique. Les autres passages restent intacts.");
    }catch(IllegalArgumentException ex){notice(ex.getMessage());}}
    private void nudge(EditText field,long offset){try{long v=LyricsTimeline.parse(field.getText().toString());field.setText(LyricsTimeline.format(Math.max(0,Math.min(duration,v+offset))));}catch(IllegalArgumentException ex){notice(ex.getMessage());}}
    private void listen(){if(!apply()||selected==null||!ready)return;try{stopAt=selected.end;jump(selected.start);player.start();play.setText("Pause");}catch(Exception e){notice("Lecture impossible");}}
    private void split(){if(selected==null)return;int cursor=words.getSelectionStart();long at=position();if(!apply())return;try{ArrayList<LyricsTimeline.Row> before=LyricsTimeline.copy(rows);LyricsTimeline.Row r=LyricsTimeline.split(rows,selected,at,cursor);undo.push(before);dirty=true;select(r);rebuild();notice("Passage scindé en deux");}catch(IllegalArgumentException ex){notice(ex.getMessage());}}
    private void remove(){if(selected==null)return;new AlertDialog.Builder(context).setTitle("Supprimer ce passage ?").setNegativeButton("Annuler",null).setPositiveButton("Supprimer",(d,w)->{snapshot();rows.remove(selected);select(rows.isEmpty()?null:rows.get(0));rebuild();notice("Passage supprimé · action annulable");}).show();}
    private void undo(){if(undo.isEmpty())return;rows=undo.pop();select(rows.isEmpty()?null:rows.get(0));rebuild();notice("Dernière action annulée");}
    private void goTo(){
        EditText input=new EditText(context);input.setSingleLine(true);input.setHint("01:04.000 ou 64000 ms");input.setText(LyricsTimeline.format(position()));
        AlertDialog box=new AlertDialog.Builder(context).setTitle("Aller à un horaire").setView(input).setNegativeButton("Annuler",null).setPositiveButton("Aller",null).create();
        box.setOnShowListener(d->box.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{long ms=LyricsTimeline.parse(input.getText().toString());if(ms<0||ms>duration)throw new IllegalArgumentException("Horaire hors morceau");jump(ms);box.dismiss();}catch(IllegalArgumentException ex){input.setError(ex.getMessage());}}));box.show();
    }
    private void manual(boolean duplicate){
        if(!apply())return;
        if(duplicate&&selected==null)return;
        long at=position();
        LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(12),0,dp(12),0);
        EditText from=new EditText(context),to=new EditText(context);
        from.setSingleLine(true);from.setHint("Début : 64000 ms");from.setText(LyricsTimeline.format(at));body.addView(from);
        to.setSingleLine(true);to.setHint("Fin : 68000 ms");to.setText(LyricsTimeline.format(Math.min(duration,at+(duplicate?selected.end-selected.start:4000))));body.addView(to);
        EditText input=field(body,"Écris les paroles manquantes",true);if(duplicate)input.setText(selected.text);
        AlertDialog box=new AlertDialog.Builder(context).setTitle(duplicate?"Dupliquer un passage":"Ajouter à un horaire précis").setView(body).setNegativeButton("Annuler",null).setPositiveButton("Ajouter",null).create();
        box.setOnShowListener(d->box.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            long s=LyricsTimeline.parse(from.getText().toString()),e=LyricsTimeline.parse(to.getText().toString());String value=input.getText().toString().trim();LyricsTimeline.validate(s,e,duration,value);
            for(LyricsTimeline.Row r:rows)if(r.start==s)throw new IllegalArgumentException("Un passage commence déjà à cet instant");
            snapshot();LyricsTimeline.Row row=new LyricsTimeline.Row(s,e,value);rows.add(row);LyricsTimeline.sort(rows);select(row);jump(s);rebuild();editorScroll.smoothScrollTo(0,0);box.dismiss();notice("Passage ajouté à "+LyricsTimeline.format(s));
        }catch(IllegalArgumentException ex){input.setError(ex.getMessage());}}));box.show();
    }
    private void bulk(){
        if(!apply())return;
        LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(12),0,dp(12),0);
        EditText input=field(body,"Colle le couplet ou les paroles manquantes",true);
        EditText from=new EditText(context);from.setHint("Début");from.setText(LyricsTimeline.format(position()));body.addView(from);
        EditText to=new EditText(context);to.setHint("Fin");to.setText(LyricsTimeline.format(Math.min(duration,position()+16000)));body.addView(to);
        CheckBox perLine=new CheckBox(context);perLine.setText("Créer un passage par ligne (temps répartis approximativement)");body.addView(perLine);
        AlertDialog box=new AlertDialog.Builder(context).setTitle("Ajouter un couplet complet").setView(body).setNegativeButton("Annuler",null).setPositiveButton("Ajouter",null).create();box.setOnShowListener(d->box.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{long s=LyricsTimeline.parse(from.getText().toString()),e=LyricsTimeline.parse(to.getText().toString());String value=input.getText().toString().trim();LyricsTimeline.validate(s,e,duration,value);
                ArrayList<String> chunks=new ArrayList<>();if(perLine.isChecked()){for(String line:value.split("\\r?\\n"))if(!line.trim().isEmpty())chunks.add(line.trim());}else chunks.add(value);
                if(chunks.isEmpty()||e-s<chunks.size())throw new IllegalArgumentException("Intervalle trop court");
                ArrayList<LyricsTimeline.Row> added=new ArrayList<>();
                for(int i=0;i<chunks.size();i++){long a=s+(e-s)*i/chunks.size(),b=s+(e-s)*(i+1)/chunks.size();for(LyricsTimeline.Row r:rows)if(r.start==a)throw new IllegalArgumentException("Un passage commence déjà à "+LyricsTimeline.format(a));added.add(new LyricsTimeline.Row(a,b,chunks.get(i)));}
                snapshot();rows.addAll(added);LyricsTimeline.sort(rows);select(added.get(0));rebuild();box.dismiss();notice("Couplet ajouté. Affine les horaires en écoutant.");
            }catch(IllegalArgumentException ex){input.setError(ex.getMessage());}
        }));box.show();
    }
    private void commitAll(){
        if(!apply())return;
        try{for(LyricsTimeline.Row r:rows)LyricsTimeline.validate(r.start,r.end,duration,r.text);}catch(IllegalArgumentException ex){notice(ex.getMessage());return;}
        int overlap=0;long maxEnd=-1;for(LyricsTimeline.Row r:rows){if(r.start<maxEnd)overlap++;maxEnd=Math.max(maxEnd,r.end);}
        if(overlap>0){new AlertDialog.Builder(context).setTitle("Passages qui se chevauchent").setMessage(overlap+" passage(s) se chevauchent. Le passage le plus récent a priorité. Enregistrer quand même ?").setNegativeButton("Revenir",null).setPositiveButton("Enregistrer",(d,w)->finish()).show();}else finish();
    }
    private void finish(){saved.accept(LyricsTimeline.copy(rows));dialog.dismiss();}
    private void closeRequest(){if(!dirty&&!pending()){dialog.dismiss();return;}new AlertDialog.Builder(context).setTitle("Modifications non enregistrées").setItems(new String[]{"Enregistrer","Abandonner les modifications","Continuer la correction"},(d,i)->{if(i==0)commitAll();else if(i==1)dialog.dismiss();}).show();}
    void pausePlayback(){if(ready&&player!=null){try{player.pause();play.setText("Lecture");stopAt=-1;}catch(Exception ignored){}}}
    void dispose(){if(dialog.isShowing())dialog.dismiss();else release();}
    private void release(){closed=true;handler.removeCallbacksAndMessages(null);if(player!=null){player.release();player=null;}}
}

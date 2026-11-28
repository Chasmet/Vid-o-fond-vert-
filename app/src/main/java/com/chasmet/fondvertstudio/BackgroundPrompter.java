package com.chasmet.fondvertstudio;

import android.content.*;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Project edits are serialized on the same main looper as the UI. */
final class BackgroundPrompter {
    private final Context app;
    private final SharedPreferences prefs;
    private MediaPlayer player;private String playerUri="";
    BackgroundPrompter(Context c){app=c;prefs=c.getSharedPreferences("music_prompter",0);}
    private JSONObject project()throws Exception{return new JSONObject(prefs.getString("project","{}"));}
    private JSONArray rows(JSONObject p){JSONArray a=p.optJSONArray("lines");return a==null?new JSONArray():a;}
    private String song(JSONObject p){String s=p.optString("source");if(s.isEmpty())return "";Uri u=Uri.parse(s);try(Cursor c=app.getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))return c.getString(0);}catch(Exception ignored){}return u.getLastPathSegment();}
    private String revision(JSONObject p)throws Exception{
        MessageDigest md=MessageDigest.getInstance("SHA-256");md.update(p.optString("source").getBytes("UTF-8"));StringBuilder s=new StringBuilder();for(byte b:md.digest(rows(p).toString().getBytes("UTF-8")))s.append(String.format(Locale.US,"%02x",b&255));return s.toString();
    }
    private long length(JSONObject p)throws Exception{
        long n=p.optLong("duration_ms",0);if(n>0)return n;
        String uri=p.optString("playable");if(uri.isEmpty())uri=p.optString("source");if(uri.isEmpty())return 0;
        android.media.MediaMetadataRetriever mm=new android.media.MediaMetadataRetriever();
        try{mm.setDataSource(app,Uri.parse(uri));return Long.parseLong(mm.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION));}finally{mm.release();}
    }
    private void prepare(JSONObject p)throws Exception{
        String uri=p.optString("playable");if(uri.isEmpty())uri=p.optString("source");if(uri.isEmpty())throw new IOException("Aucun morceau");
        if(player!=null&&uri.equals(playerUri))return;release();
        MediaPlayer mp=new MediaPlayer();try{mp.setDataSource(app,Uri.parse(uri));mp.prepare();player=mp;playerUri=uri;}catch(Exception e){mp.release();throw e;}
    }
    void release(){if(player!=null){player.release();player=null;}playerUri="";}
    JSONObject execute(String action,JSONObject args)throws Exception{
        JSONObject p=project(),result=new JSONObject().put("ok",true);JSONArray a=rows(p);String rev=revision(p);
        if(action.equals("prompter_status"))return result.put("song",song(p)).put("duration_ms",length(p)).put("count",a.length()).put("revision",rev).put("background",true).put("position_ms",player==null?0:player.getCurrentPosition()).put("playing",player!=null&&player.isPlaying());
        if(action.equals("lyrics_list")||action.equals("lyrics_search")){
            int from=Math.max(0,args.optInt("offset",0)),limit=Math.max(1,Math.min(25,args.optInt("limit",25)));String q=args.optString("query").toLowerCase(Locale.ROOT);JSONArray found=new JSONArray();int i=from;
            for(;i<a.length()&&found.length()<limit;i++){JSONArray x=a.getJSONArray(i);if(action.equals("lyrics_search")&&!x.getString(2).toLowerCase(Locale.ROOT).contains(q))continue;
                JSONObject v=new JSONObject().put("index",i).put("start_ms",x.getLong(0)).put("end_ms",x.getLong(1)).put("text",x.getString(2));
                if(found.length()>0&&found.toString().length()+v.toString().length()>95000)break;found.put(v);}
            return result.put("revision",rev).put("total",a.length()).put("items",found).put("next_offset",i<a.length()?i:JSONObject.NULL);
        }
        if(action.equals("lyrics_audit")){
            long duration=length(p),last=0;JSONArray issues=new JSONArray();
            for(int i=0;i<a.length();i++){JSONArray x=a.getJSONArray(i);long s=x.getLong(0),e=x.getLong(1);if(s<last)issues.put(new JSONObject().put("index",i).put("type","overlap").put("milliseconds",last-s));if(s-last>=2000)issues.put(new JSONObject().put("index",i).put("type","gap").put("from_ms",last).put("to_ms",s));if(s<0||e<=s||e>duration||x.getString(2).trim().isEmpty())issues.put(new JSONObject().put("index",i).put("type","invalid"));last=Math.max(last,e);if(issues.length()>=100)break;}
            if(duration-last>=2000)issues.put(new JSONObject().put("type","gap").put("from_ms",last).put("to_ms",duration));
            return result.put("revision",rev).put("issues",issues).put("note","Un silence n'est pas forcément une parole oubliée.");
        }
        if(action.equals("lyrics_text")||action.equals("lyrics_export_local")){
            StringBuilder text=new StringBuilder();for(int i=0;i<a.length();i++){JSONArray x=a.getJSONArray(i);if(args.optBoolean("timestamps"))text.append(LyricsTimeline.format(x.getLong(0))).append(" — ");text.append(x.getString(2)).append("\n");if(text.length()>120000)throw new IOException("Texte trop long, utilise lyrics_list.");}
            if(action.equals("lyrics_text"))return result.put("text",text.toString()).put("revision",rev);
            File folder=new File(app.getFilesDir(),"mcp_exports");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Dossier indisponible");
            File out=File.createTempFile("Paroles-", ".pdf",folder);try(OutputStream stream=new FileOutputStream(out)){LyricsPdfExporter.write(stream,song(p),text.toString());}catch(Exception e){out.delete();throw e;}
            // The actual PDF stays private; the existing UI export is used for sharing.
            return result.put("file",out.getName()).put("bytes",out.length()).put("local_only",true);
        }
        if(action.equals("prompter_seek")||action.equals("prompter_play")){
            if(action.equals("prompter_play")&&!args.getBoolean("play")){if(player!=null)player.pause();return result.put("playing",false);}
            prepare(p);long pos=args.optLong("position_ms",player.getCurrentPosition());if(pos<0||pos>player.getDuration())throw new IOException("Horaire hors morceau");player.seekTo((int)pos);if(action.equals("prompter_play"))player.start();return result.put("requested_position_ms",pos).put("playing",player.isPlaying());
        }
        if(!rev.equals(args.optString("revision")))throw new IOException("Révision périmée : relire les paroles.");
        if(p.optString("source").isEmpty())throw new IOException("Aucun morceau");
        ArrayList<LyricsTimeline.Row> data=new ArrayList<>();for(int i=0;i<a.length();i++){JSONArray x=a.getJSONArray(i);data.add(new LyricsTimeline.Row(x.getLong(0),x.getLong(1),x.getString(2)));}
        if(action.equals("lyrics_restore")){
            JSONObject backup=new JSONObject(prefs.getString("mcp_undo","{}"));if(!p.optString("source").equals(backup.optString("source")))throw new IOException("Aucune sauvegarde pour ce morceau");JSONArray old=backup.getJSONArray("lines");data.clear();for(int i=0;i<old.length();i++){JSONArray x=old.getJSONArray(i);data.add(new LyricsTimeline.Row(x.getLong(0),x.getLong(1),x.getString(2)));}
        }else if(action.equals("lyrics_batch")){
            JSONArray ops=args.getJSONArray("operations");if(ops.length()<1||ops.length()>100)throw new IOException("1 à 100 opérations maximum");
            for(int i=0;i<ops.length();i++){JSONObject op=ops.getJSONObject(i);edit(data,op.getString("action"),op,length(p));}
        }else edit(data,action,args,length(p));
        LyricsTimeline.sort(data);JSONArray out=new JSONArray();for(LyricsTimeline.Row x:data)out.put(new JSONArray().put(x.start).put(x.end).put(x.text));
        JSONObject backup=new JSONObject().put("source",p.optString("source")).put("lines",a);
        p.put("lines",out);if(!prefs.edit().putString("mcp_undo",backup.toString()).putString("project",p.toString()).commit())throw new IOException("Sauvegarde impossible");
        return result.put("saved",true).put("count",out.length()).put("revision",revision(p));
    }
    private void edit(ArrayList<LyricsTimeline.Row> data,String action,JSONObject args,long duration)throws Exception{
        if(action.equals("lyrics_delete")){int i=args.getInt("index");if(i<0||i>=data.size())throw new IOException("Index invalide");data.remove(i);return;}
        if(!action.equals("lyrics_add")&&!action.equals("lyrics_update"))throw new IOException("Commande inconnue");
        int i=action.equals("lyrics_add")?-1:args.getInt("index");if(i< -1||i>=data.size()||(action.equals("lyrics_update")&&i<0))throw new IOException("Index invalide");
        LyricsTimeline.Row old=i<0?null:data.get(i);
        long s=args.optLong("start_ms",old==null?-1:old.start),e=args.optLong("end_ms",old==null?-1:old.end);String words=args.optString("text",old==null?"":old.text).trim();
        if(words.length()>12000)throw new IOException("Texte trop long");LyricsTimeline.validate(s,e,duration,words);for(int j=0;j<data.size();j++)if(j!=i&&data.get(j).start==s)throw new IOException("Horaire déjà utilisé");
        LyricsTimeline.Row row=new LyricsTimeline.Row(s,e,words);if(i<0){if(data.size()>=5000)throw new IOException("Trop de passages");data.add(row);}else data.set(i,row);
    }
}

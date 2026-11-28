package com.chasmet.fondvertstudio;
import java.util.*;
/** Pure Java serializer. Times are segment boundaries, not word alignment. */
final class TranscriptExporter {
    static final class Cue {
        final long start,end; final String text;
        Cue(long start,long end,String text){this.start=start;this.end=end;this.text=text;}
    }
    static String timestamp(long ms,char separator){
        ms=Math.max(0,ms);
        return String.format(Locale.ROOT,"%02d:%02d:%02d%c%03d",ms/3600000,(ms/60000)%60,(ms/1000)%60,separator,ms%1000);
    }
    static String format(List<Cue> input,int type,String title){
        if(type<0||type>3)throw new IllegalArgumentException("Format");
        ArrayList<Cue> cues=new ArrayList<>(input);
        Collections.sort(cues,(a,b)->Long.compare(a.start,b.start));
        StringBuilder out=new StringBuilder();
        if(type==1)out.append("WEBVTT\n\n");
        if(type==2)out.append(title).append("\nTranscription par segments — horaires à vérifier\n\n");
        int number=0;
        for(Cue c:cues){
            String text=c.text==null?"":c.text.trim().replace("\r\n","\n").replace('\r','\n');
            if(text.isEmpty())continue;
            long start=Math.max(0,c.start),end=Math.max(start+1,c.end);
            if(type==0||type==1){
                if(type==0)out.append(++number).append('\n');
                char sep=type==0?',':'.';
                out.append(timestamp(start,sep)).append(" --> ").append(timestamp(end,sep)).append('\n');
                text=text.replaceAll("\\n\\s*\\n","\n").replace("-->","→");
                text=text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
            }else if(type==2)out.append(timestamp(start,'.')).append(" → ").append(timestamp(end,'.')).append('\n');
            out.append(text).append("\n\n");
        }
        return out.toString();
    }
    private TranscriptExporter(){}
}

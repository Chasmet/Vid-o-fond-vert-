package com.chasmet.fondvertstudio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

final class LyricsTimeline {
    static final class Row {
        long start,end; String text;
        Row(long s,long e,String t){start=s;end=e;text=t;}
        Row copy(){return new Row(start,end,text);}
    }
    static ArrayList<Row> copy(ArrayList<Row> rows){
        ArrayList<Row> out=new ArrayList<>();for(Row r:rows)out.add(r.copy());return out;
    }
    static void sort(ArrayList<Row> rows){Collections.sort(rows,(a,b)->Long.compare(a.start,b.start));}
    static String format(long ms){return String.format(Locale.US,"%02d:%02d.%03d",ms/60000,(ms/1000)%60,ms%1000);}
    // 64,000 / 64.000 seconds, 01:04.000, 64000 or 64000 ms.
    static long parse(String input){
        String s=input.trim().toLowerCase(Locale.ROOT).replace(',','.');
        if(s.isEmpty())throw new IllegalArgumentException("Horaire vide");
        try{
            if(s.endsWith("ms"))return new BigDecimal(s.substring(0,s.length()-2).trim()).setScale(0,RoundingMode.HALF_UP).longValueExact();
            boolean seconds=s.endsWith("s");if(seconds)s=s.substring(0,s.length()-1).trim();
            if(s.contains(":")){
                String[] parts=s.split(":",-1);if(parts.length!=2)throw new IllegalArgumentException();
                long min=Long.parseLong(parts[0]);BigDecimal sec=new BigDecimal(parts[1]);
                if(min<0||sec.signum()<0||sec.compareTo(BigDecimal.valueOf(60))>=0)throw new IllegalArgumentException();
                return BigDecimal.valueOf(min).multiply(BigDecimal.valueOf(60000)).add(sec.movePointRight(3)).setScale(0,RoundingMode.HALF_UP).longValueExact();
            }
            BigDecimal value=new BigDecimal(s);
            if(!seconds&&s.matches("[0-9]+")&&value.compareTo(BigDecimal.valueOf(1000))>=0)return value.longValueExact();
            return value.movePointRight(3).setScale(0,RoundingMode.HALF_UP).longValueExact();
        }catch(RuntimeException e){throw new IllegalArgumentException("Utilise 01:04.000, 64,000 s ou 64000 ms");}
    }
    static void validate(long s,long e,long duration,String text){
        if(s<0||e<=s||e>duration)throw new IllegalArgumentException("Début < fin, dans la durée du morceau ("+format(duration)+")");
        if(text.trim().isEmpty())throw new IllegalArgumentException("Ajoute les paroles ou supprime ce passage");
    }
    static Row insert(ArrayList<Row> rows,long start,long duration,String text){
        if(start<0||start>=duration)throw new IllegalArgumentException("Choisis un instant avant la fin du morceau");
        long end=Math.min(duration,start+4000);
        for(Row r:rows){
            if(r.start==start)throw new IllegalArgumentException("Un passage commence déjà à cet instant : corrige-le ou décale le curseur");
            if(r.start>start)end=Math.min(end,r.start);
        }
        Row row=new Row(start,end,text);rows.add(row);sort(rows);return row;
    }
    static Row split(ArrayList<Row> rows,Row row,long at,int cursor){
        if(at<=row.start||at>=row.end)throw new IllegalArgumentException("Place la lecture à l'intérieur du passage");
        if(cursor<=0||cursor>=row.text.length())throw new IllegalArgumentException("Place le curseur du texte entre deux paroles");
        String first=row.text.substring(0,cursor).trim(),second=row.text.substring(cursor).trim();
        if(first.isEmpty()||second.isEmpty())throw new IllegalArgumentException("Les deux passages doivent contenir du texte");
        for(Row r:rows)if(r!=row&&r.start==at)throw new IllegalArgumentException("Un autre passage commence déjà ici");
        Row next=new Row(at,row.end,second);row.end=at;row.text=first;rows.add(next);sort(rows);return next;
    }
    static int active(ArrayList<Row> rows,long at){
        for(int i=rows.size()-1;i>=0;i--){Row r=rows.get(i);if(r.start<=at&&at<r.end)return i;}return -1;
    }
    private LyricsTimeline(){}
}

package com.chasmet.fondvertstudio;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import java.io.IOException;
import java.io.OutputStream;

/** A4 lyric sheet, paginated using Android's text layout (including accents). */
final class LyricsPdfExporter {
    private static final int WIDTH=595, HEIGHT=842, MARGIN=46, TOP=112, BOTTOM=780;
    private LyricsPdfExporter(){}
    @SuppressWarnings("deprecation")
    static void write(OutputStream out,String title,String lyrics)throws IOException {
        if(lyrics==null||lyrics.trim().isEmpty())throw new IOException("Aucune parole");
        TextPaint body=new TextPaint(Paint.ANTI_ALIAS_FLAG);
        body.setColor(0xff20252b);body.setTextSize(15);body.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        StaticLayout layout=new StaticLayout(lyrics,body,WIDTH-2*MARGIN,Layout.Alignment.ALIGN_NORMAL,1.15f,2f,false);
        TextPaint heading=new TextPaint(Paint.ANTI_ALIAS_FLAG);
        heading.setColor(0xff142a26);heading.setTextSize(22);heading.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        Paint meta=new Paint(Paint.ANTI_ALIAS_FLAG);meta.setColor(0xff45615a);meta.setTextSize(10);
        PdfDocument pdf=new PdfDocument();
        try{
            int first=0,pageNo=1;
            while(first<layout.getLineCount()){
                int startY=layout.getLineTop(first),last=first+1;
                while(last<layout.getLineCount()&&layout.getLineTop(last+1)-startY<=BOTTOM-TOP)last++;
                PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(WIDTH,HEIGHT,pageNo).create());
                try{
                    Canvas canvas=page.getCanvas();canvas.drawColor(Color.WHITE);
                    canvas.drawText("FOND VERT STUDIO  /  PAROLES",MARGIN,40,meta);
                    String safeTitle=title==null?"Morceau":title.replace('\n',' ');
                    canvas.drawText(TextUtils.ellipsize(safeTitle,heading,WIDTH-2*MARGIN,TextUtils.TruncateAt.END).toString(),MARGIN,76,heading);
                    canvas.drawLine(MARGIN,90,WIDTH-MARGIN,90,meta);
                    canvas.save();
                    canvas.clipRect(MARGIN,TOP,WIDTH-MARGIN,TOP+layout.getLineTop(last)-startY);
                    canvas.translate(MARGIN,TOP-startY);layout.draw(canvas);canvas.restore();
                    canvas.drawLine(MARGIN,797,WIDTH-MARGIN,797,meta);
                    canvas.drawText("Transcription à relire",MARGIN,814,meta);
                    canvas.drawText("Page "+pageNo,WIDTH-MARGIN-45,814,meta);
                }finally{pdf.finishPage(page);}
                first=last;pageNo++;
            }
            pdf.writeTo(out);out.flush();
        }finally{pdf.close();}
    }
}

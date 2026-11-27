package com.chasmet.fondvertstudio;

import android.content.Context;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class LyricsPdfExporterTest {
    @Test public void exportsMultiPageFrenchLyrics() throws Exception {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file=new File(c.getCacheDir(),"lyrics-test.pdf");
        StringBuilder lyrics=new StringBuilder();
        for(int i=0;i<90;i++)lyrics.append("00:01.000  J'avance malgré les épreuves et les mots de mon cœur.\n\n");
        try{
            try(FileOutputStream out=new FileOutputStream(file)){LyricsPdfExporter.write(out,"Cendres sous la peau",lyrics.toString());}
            assertTrue(file.length()>1000);
            try(PdfRenderer renderer=new PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY))){assertTrue(renderer.getPageCount()>1);}
        }finally{file.delete();}
    }
}

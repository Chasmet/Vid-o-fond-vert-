package com.chasmet.fondvertstudio;
import android.content.Context;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
final class UvrLocal {
    static { System.loadLibrary("chkwhisper"); }
    private static final String SHA="e411182ce2c53541fefdcc99a8f46f2fe03978eb22038b9497c1d9d95a00fad4";
    static native boolean available();
    private static native long create(String path,int threads);
    private static native float[] process(long handle,float[] stereo);
    private static native void destroy(long handle);
    interface Listener { void status(String s); boolean abort(); }
    private static void check(Listener l)throws InterruptedException{if(l.abort()||Thread.currentThread().isInterrupted())throw new InterruptedException();}
    static boolean ready(Context c){return new File(c.getFilesDir(),"UVR-MDX-NET-Voc_FT.onnx").length()==66762795L;}
    private static File ensure(Context c,Listener l)throws Exception{
        File model=new File(c.getFilesDir(),"UVR-MDX-NET-Voc_FT.onnx");if(ready(c))return model;
        File part=File.createTempFile("uvr-",".part",c.getFilesDir());
        try{
            l.status("Préparation locale du modèle UVR inclus…");
            MessageDigest md=MessageDigest.getInstance("SHA-256");long total=0,last=0;
            try(InputStream in=c.getAssets().open("models/UVR-MDX-NET-Voc_FT.onnx");OutputStream out=new BufferedOutputStream(new FileOutputStream(part))){
                byte[] b=new byte[65536];int k;while((k=in.read(b))!=-1){check(l);total+=k;if(total>66762795L)throw new IOException("Taille UVR incorrecte");md.update(b,0,k);out.write(b,0,k);if(total-last>=2000000){last=total;l.status("Préparation locale UVR : "+total/1000000+" / 67 Mo");}}
            }
            StringBuilder hash=new StringBuilder();for(byte b:md.digest())hash.append(String.format(Locale.US,"%02x",b&255));
            if(total!=66762795L||!SHA.equals(hash.toString()))throw new IOException("Intégrité UVR incorrecte");
            if(model.exists()&&!model.delete())throw new IOException("Ancien modèle UVR inaccessible");
            if(!part.renameTo(model))throw new IOException("Installation UVR impossible");
            return model;
        }finally{part.delete();}
    }
    static float[] vocals(Context c,float[] stereo,int threads,Listener l)throws Exception{
        if(!available())throw new IOException("Bibliothèque UVR indisponible");check(l);
        File model=ensure(c,l);l.status("Chargement UVR · isolation de la voix");check(l);
        long handle=create(model.getAbsolutePath(),Math.max(1,Math.min(4,threads)));
        if(handle==0)throw new IOException("Initialisation UVR impossible");
        try{
            int n=stereo.length/2,window=16000*20,overlap=16000;
            float[] mono=new float[n];
            for(int start=0;start<n;start+=window){
                check(l);int end=Math.min(n,start+window),from=Math.max(0,start-overlap),to=Math.min(n,end+overlap);
                l.status("Isolation UVR : "+(start/window+1)+" / "+((n+window-1)/window));
                float[] stem=process(handle,Arrays.copyOfRange(stereo,from*2,to*2));
                if(stem==null||stem.length!=to-from)throw new IOException("Séparation UVR incomplète");
                System.arraycopy(stem,start-from,mono,start,end-start);
            }check(l);return mono;
        }finally{destroy(handle);}
    }
    private UvrLocal(){}
}

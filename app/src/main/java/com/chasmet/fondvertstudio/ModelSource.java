package com.chasmet.fondvertstudio;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Locale;

/** Reuse existing installed models; fetch only missing weights from pinned official sources. */
final class ModelSource {
    static final String WHISPER="ggml-large-v3-turbo-q5_0.bin";
    static final String UVR="UVR-MDX-NET-Voc_FT.onnx";
    static final String QWEN="sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2";
    interface Progress { void update(long bytes); }
    interface Cancel { boolean cancelled(); }
    static long size(String name) {
        if(WHISPER.equals(name))return 574041195L;
        if(UVR.equals(name))return 66762795L;
        if(QWEN.equals(name))return 878702423L;
        throw new IllegalArgumentException("Modèle inconnu");
    }
    static String sha(String name) {
        if(WHISPER.equals(name))return "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2";
        if(UVR.equals(name))return "e411182ce2c53541fefdcc99a8f46f2fe03978eb22038b9497c1d9d95a00fad4";
        if(QWEN.equals(name))return "393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96";
        throw new IllegalArgumentException("Modèle inconnu");
    }
    private static String url(String name) {
        if(WHISPER.equals(name))return "https://huggingface.co/ggerganov/whisper.cpp/resolve/fb5ed9369c26eaf08392504d8446da431e0fffb9/"+name;
        if(UVR.equals(name))return "https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/"+name;
        if(QWEN.equals(name))return "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"+name;
        throw new IllegalArgumentException("Modèle inconnu");
    }
    private static InputStream open(Context c,String name)throws IOException {
        try{return c.getAssets().open("models/"+name);}catch(FileNotFoundException missing){}
        URL u=new URL(url(name));
        for(int i=0;i<8;i++){
            if(!"https".equalsIgnoreCase(u.getProtocol()))throw new IOException("Redirection non HTTPS");
            HttpURLConnection connection=(HttpURLConnection)u.openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept-Encoding","identity");
            int code;
            try{code=connection.getResponseCode();}catch(IOException e){connection.disconnect();throw e;}
            if(code==301||code==302||code==303||code==307||code==308){
                String next=connection.getHeaderField("Location");connection.disconnect();
                if(next==null)throw new IOException("Redirection modèle invalide");u=new URL(u,next);continue;
            }
            if(code!=200){connection.disconnect();throw new IOException("Téléchargement modèle HTTP "+code);}
            final HttpURLConnection active=connection;
            try{return new FilterInputStream(connection.getInputStream()){
                @Override public void close()throws IOException{try{super.close();}finally{active.disconnect();}}
            };}catch(IOException e){connection.disconnect();throw e;}
        }
        throw new IOException("Trop de redirections");
    }
    static void fetch(Context c,String name,File target,Progress progress,Cancel cancel)throws Exception {
        long expected=size(name),total=0,last=0;
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=open(c,name);OutputStream out=new BufferedOutputStream(new FileOutputStream(target))){
            byte[] buffer=new byte[65536];int n;
            while((n=in.read(buffer))!=-1){
                if(cancel.cancelled()||Thread.currentThread().isInterrupted())throw new InterruptedException();
                total+=n;if(total>expected)throw new IOException("Modèle trop volumineux");
                md.update(buffer,0,n);out.write(buffer,0,n);
                if(total-last>=2000000L){last=total;progress.update(total);}
            }
        }
        StringBuilder hex=new StringBuilder();for(byte x:md.digest())hex.append(String.format(Locale.US,"%02x",x&255));
        if(total!=expected||!sha(name).equals(hex.toString()))throw new IOException("Intégrité du modèle incorrecte");
        progress.update(total);
    }
    static synchronized File ensureFile(Context c,String name,Progress progress,Cancel cancel)throws Exception {
        File dst=new File(c.getFilesDir(),name);
        if(dst.isFile()&&dst.length()==size(name))return dst;
        if(c.getFilesDir().getUsableSpace()<size(name)+32000000L)throw new IOException("Espace insuffisant pour "+name);
        File part=File.createTempFile("model-",".part",c.getFilesDir());
        try{
            fetch(c,name,part,progress,cancel);
            if(dst.exists()&&!dst.delete())throw new IOException("Ancien modèle inaccessible");
            if(!part.renameTo(dst))throw new IOException("Installation du modèle impossible");
            return dst;
        }finally{part.delete();}
    }
    private ModelSource(){}
}

package com.chasmet.fondvertstudio;

import android.content.Context;
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;

final class QwenLocal {
    private static final String ARCHIVE="sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25";
    private static final String SHA06="393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96";
    private static final String[] REQUIRED={"conv_frontend.onnx","encoder.int8.onnx","decoder.int8.onnx","tokenizer/merges.txt","tokenizer/tokenizer_config.json","tokenizer/vocab.json"};
    interface Listener { void status(String text); void progress(int percent); boolean abort(); }
    static boolean supported(){return android.os.Build.VERSION.SDK_INT>=26;}
    static File folder(Context c){return new File(c.getFilesDir(),"qwen3-asr-06-int8");}
    static boolean ready(Context c){
        File d=folder(c);if(!new File(d,".verified").isFile())return false;
        for(String name:REQUIRED)if(!new File(d,name).isFile()||new File(d,name).length()==0)return false;
        return true;
    }
    private static void check(Listener l)throws InterruptedException{if(l.abort()||Thread.currentThread().isInterrupted())throw new InterruptedException();}
    private static void delete(File f){if(f.isDirectory()){File[] list=f.listFiles();if(list!=null)for(File child:list)delete(child);}f.delete();}
    static void ensure(Context c,Listener l)throws Exception{
        if(!supported())throw new IOException("Qwen nécessite Android 8 ou plus récent. Utilise Whisper.");
        if(ready(c))return;
        if(c.getFilesDir().getUsableSpace()<1300000000L)throw new IOException("Libère au moins 1,3 Go pour préparer les modèles Qwen inclus dans l’APK.");
        File dest=folder(c), stage=new File(c.getFilesDir(),"qwen3-asr-06-install");
        delete(stage); if(!stage.mkdirs())throw new IOException("Dossier modèle indisponible");
        try{
            l.status("Préparation locale de Qwen inclus dans l’application…");
            Set<String> allowed=new HashSet<>(Arrays.asList(REQUIRED));long unpacked=0,last=0;
            try(TarArchiveInputStream tar=new TarArchiveInputStream(new BZip2CompressorInputStream(new BufferedInputStream(c.getAssets().open("models/"+ARCHIVE+".tar.bz2"))))){
                TarArchiveEntry entry;byte[] buf=new byte[65536];
                while((entry=tar.getNextTarEntry())!=null){
                    check(l);String name=entry.getName();while(name.startsWith("./"))name=name.substring(2);
                    if(name.startsWith(ARCHIVE+"/"))name=name.substring(ARCHIVE.length()+1);
                    if(!entry.isFile()||!allowed.contains(name))continue;
                    File outFile=new File(stage,name);File parent=outFile.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Dossier modèle inaccessible");
                    try(OutputStream out=new BufferedOutputStream(new FileOutputStream(outFile))){
                        int count;while((count=tar.read(buf))!=-1){check(l);unpacked+=count;if(unpacked>1300000000L)throw new IOException("Modèle décompressé trop grand");out.write(buf,0,count);if(unpacked-last>=16000000L){last=unpacked;l.status("Préparation locale Qwen : "+unpacked/1000000+" Mo");}}
                    }
                }
            }
            for(String name:REQUIRED){File m=new File(stage,name);if(!m.isFile()||m.length()==0)throw new IOException("Composant Qwen manquant : "+name);}
            try(FileOutputStream out=new FileOutputStream(new File(stage,".verified"))){out.write(SHA06.getBytes("UTF-8"));}
            delete(dest);if(!stage.renameTo(dest))throw new IOException("Impossible de finaliser Qwen");
        }finally{delete(stage);}
    }
    static String[] transcribe(Context c,float[] pcm,int threads,Listener l)throws Exception{
        ensure(c,l);check(l);File d=folder(c);
        OfflineQwen3AsrModelConfig q=new OfflineQwen3AsrModelConfig();
        q.setConvFrontend(new File(d,REQUIRED[0]).getAbsolutePath());q.setEncoder(new File(d,REQUIRED[1]).getAbsolutePath());q.setDecoder(new File(d,REQUIRED[2]).getAbsolutePath());q.setTokenizer(new File(d,"tokenizer").getAbsolutePath());
        q.setMaxTotalLen(512);q.setMaxNewTokens(128);
        OfflineModelConfig model=new OfflineModelConfig();model.setQwen3Asr(q);model.setNumThreads(Math.max(1,Math.min(6,threads)));model.setProvider("cpu");model.setDebug(false);
        FeatureConfig feat=new FeatureConfig();feat.setSampleRate(16000);feat.setFeatureDim(128);
        OfflineRecognizerConfig config=new OfflineRecognizerConfig();config.setModelConfig(model);config.setFeatConfig(feat);
        l.status("Chargement Qwen en mémoire…");
        OfflineRecognizer recognizer=new OfflineRecognizer(null,config);
        ArrayList<String> output=new ArrayList<>();
        try{
            final int window=16000*8;
            for(int start=0;start<pcm.length;start+=window){
                check(l);int end=Math.min(start+window,pcm.length);float[] chunk=Arrays.copyOfRange(pcm,start,end);
                l.status("Qwen · passage "+(start/window+1)+" / "+((pcm.length+window-1)/window));
                OfflineStream stream=recognizer.createStream();
                try{
                    stream.setOption("language","French");
                    stream.acceptWaveform(chunk,16000);recognizer.decode(stream);
                    OfflineRecognizerResult result=recognizer.getResult(stream);
                    String text=result.getText()==null?"":result.getText().trim();
                    if(!text.isEmpty()){output.add(Long.toString(start*1000L/16000));output.add(Long.toString(end*1000L/16000));output.add(text);}
                }finally{stream.release();}
                check(l);l.progress((int)(100L*end/pcm.length));
            }
        }finally{recognizer.release();}
        return output.toArray(new String[0]);
    }
    private QwenLocal(){}
}

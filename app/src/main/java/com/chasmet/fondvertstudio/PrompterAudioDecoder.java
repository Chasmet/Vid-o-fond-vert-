package com.chasmet.fondvertstudio;
import android.content.Context;
import android.media.*;
import android.net.Uri;
import java.nio.*;
import java.io.*;
import java.util.Arrays;

final class PrompterAudioDecoder {
    private float[] samples=new float[16000*30];
    private int size=0;
    private long phase=0;
    private void append(ByteBuffer b,int channels,int rate,int encoding) throws IOException {
        b.order(ByteOrder.LITTLE_ENDIAN);
        int bytes=encoding==4?4:encoding==3?1:2;
        if(encoding!=2 && encoding!=3 && encoding!=4) throw new IOException("Format PCM non pris en charge");
        if(rate<=0||channels<=0) throw new IOException("Format audio invalide");
        while(b.remaining()>=channels*bytes){
            float v=0;
            for(int i=0;i<channels;i++) v+=encoding==4?b.getFloat():encoding==3?((b.get()&255)-128)/128f:b.getShort()/32768f;
            phase+=16000;
            while(phase>=rate){
                if(size>=16000*60*15) throw new IOException("Durée maximale : 15 minutes");
                if(size==samples.length) samples=Arrays.copyOf(samples,Math.min(samples.length*2,16000*60*15));
                samples[size++]=v/channels;phase-=rate;
            }
        }
    }
    static float[] decode(Context c,Uri uri) throws Exception {
        PrompterAudioDecoder d=new PrompterAudioDecoder();
        MediaExtractor ex=new MediaExtractor();
        MediaCodec codec=null;
        try{
            ex.setDataSource(c,uri,null);
            MediaFormat fmt=null;
            for(int i=0;i<ex.getTrackCount();i++){
                MediaFormat f=ex.getTrackFormat(i);
                if(f.getString(MediaFormat.KEY_MIME).startsWith("audio/")){fmt=f;ex.selectTrack(i);break;}
            }
            if(fmt==null)throw new IOException("Aucune piste audio");
            int rate=fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding=fmt.containsKey("pcm-encoding")?fmt.getInteger("pcm-encoding"):2;
            String mime=fmt.getString(MediaFormat.KEY_MIME);
            if("audio/raw".equals(mime)){
                ByteBuffer b=ByteBuffer.allocate(1024*1024);
                for(;;){b.clear();int count=ex.readSampleData(b,0);if(count<0)break;b.position(0);b.limit(count);d.append(b,channels,rate,encoding);ex.advance();}
            }else{
                codec=MediaCodec.createDecoderByType(mime);codec.configure(fmt,null,null,0);codec.start();
                boolean sent=false,done=false;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
                long last=android.os.SystemClock.elapsedRealtime();
                while(!done){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    if(!sent){int i=codec.dequeueInputBuffer(10000);if(i>=0){ByteBuffer in=codec.getInputBuffer(i);in.clear();int count=ex.readSampleData(in,0);if(count<0){codec.queueInputBuffer(i,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);sent=true;}else{codec.queueInputBuffer(i,0,count,ex.getSampleTime(),0);ex.advance();}last=android.os.SystemClock.elapsedRealtime();}}
                    int i=codec.dequeueOutputBuffer(info,10000);
                    if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat out=codec.getOutputFormat();rate=out.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=out.getInteger(MediaFormat.KEY_CHANNEL_COUNT);encoding=out.containsKey("pcm-encoding")?out.getInteger("pcm-encoding"):2;}
                    else if(i>=0){ByteBuffer b=codec.getOutputBuffer(i);if(info.size>0&&b!=null){b.position(info.offset);b.limit(info.offset+info.size);d.append(b,channels,rate,encoding);}done=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(i,false);last=android.os.SystemClock.elapsedRealtime();}
                    if(android.os.SystemClock.elapsedRealtime()-last>20000)throw new IOException("Décodage bloqué");
                }
            }
            if(d.size==0)throw new IOException("Audio vide");
            return Arrays.copyOf(d.samples,d.size);
        }finally{if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}ex.release();}
    }
}

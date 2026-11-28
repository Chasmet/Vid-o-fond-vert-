package com.chasmet.fondvertstudio;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
public class TranscriptExporterTest {
 @Test public void formats(){
  java.util.List<TranscriptExporter.Cue> cues=Arrays.asList(new TranscriptExporter.Cue(1234,3456,"Été"),new TranscriptExporter.Cue(0,1000,"Bonjour"));
  assertEquals("1\n00:00:00,000 --> 00:00:01,000\nBonjour\n\n2\n00:00:01,234 --> 00:00:03,456\nÉté\n\n",TranscriptExporter.format(cues,0,"Titre"));
  assertTrue(TranscriptExporter.format(cues,1,"Titre").startsWith("WEBVTT\n\n00:00:00.000 --> 00:00:01.000"));
  assertTrue(TranscriptExporter.format(cues,2,"Titre").contains("00:00:01.234 → 00:00:03.456"));
  assertFalse(TranscriptExporter.format(cues,3,"Titre").contains("00:00"));
 }
 @Test public void repairsOldInvalidEnd(){
  assertTrue(TranscriptExporter.format(Arrays.asList(new TranscriptExporter.Cue(2000,1000,"Texte")),0,"").contains("00:00:02,000 --> 00:00:02,001"));
 }
}

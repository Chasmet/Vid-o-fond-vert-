package com.chasmet.fondvertstudio;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
public class LyricsTimelineTest {
 @Test public void parsesAllTimeFormats(){
  assertEquals(64000L,LyricsTimeline.parse("64000"));
  assertEquals(64000L,LyricsTimeline.parse("64000 ms"));
  assertEquals(64000L,LyricsTimeline.parse("64,000 s"));
  assertEquals(64000L,LyricsTimeline.parse("01:04.000"));
  assertEquals(64125L,LyricsTimeline.parse("01:04.125"));
 }
 @Test public void insertsAndSplitsWithoutLosingText(){
  ArrayList<LyricsTimeline.Row> rows=new ArrayList<>();
  rows.add(new LyricsTimeline.Row(8000,16000,"premier second"));
  LyricsTimeline.Row added=LyricsTimeline.insert(rows,4000,20000,"intro");
  assertEquals(8000L,added.end);
  LyricsTimeline.Row tail=LyricsTimeline.split(rows,rows.get(1),12000,8);
  assertEquals("second",tail.text);assertEquals("premier",rows.get(1).text);
  assertEquals(2,LyricsTimeline.active(rows,13000));
  assertEquals(-1,LyricsTimeline.active(rows,17000));
 }
 @Test(expected=IllegalArgumentException.class) public void rejectsOutsideDuration(){LyricsTimeline.validate(1000,21000,20000,"test");}
 @Test(expected=IllegalArgumentException.class) public void rejectsDuplicateStart(){ArrayList<LyricsTimeline.Row> rows=new ArrayList<>();rows.add(new LyricsTimeline.Row(1000,2000,"a"));LyricsTimeline.insert(rows,1000,5000,"b");}
}

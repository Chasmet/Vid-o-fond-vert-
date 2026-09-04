package com.chasmet.fondvertstudio;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class ProjectRepositoryInstrumentedTest {
    private ProjectRepository repository;
    private File segment;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        repository = new ProjectRepository(context);
        repository.clear(true);
        segment = new File(context.getExternalFilesDir(null), "project-test-segment.mp4");
        try (FileOutputStream output = new FileOutputStream(segment)) {
            output.write(1);
        }
    }

    @After
    public void tearDown() {
        repository.clear(true);
        if (segment != null) segment.delete();
    }

    @Test
    public void savesAndRestoresCompleteDraft() throws Exception {
        ProjectRepository.Draft draft = new ProjectRepository.Draft();
        draft.format = CaptureFormat.HORIZONTAL;
        draft.musicUri = "content://tests/music/1";
        draft.audioStartMs = 2430;
        draft.quality = 720;
        draft.backgroundType = BackgroundSpec.Type.IMAGE.name();
        draft.backgroundUri = "content://tests/image/1";
        draft.backgroundLabel = "Décor test";
        draft.timeline.add(new ClipSourceTimeline.Segment(
                segment.getAbsolutePath(),
                BackgroundSpec.Type.IMAGE,
                Uri.parse(draft.backgroundUri),
                0xFF00FF00,
                draft.backgroundLabel,
                1500L));

        repository.save(draft);
        ProjectRepository.Draft restored = repository.load();

        assertNotNull(restored);
        assertTrue(repository.hasResumableDraft());
        assertEquals(CaptureFormat.HORIZONTAL, restored.format);
        assertEquals(2430, restored.audioStartMs);
        assertEquals(720, restored.quality);
        assertEquals(1, restored.timeline.size());
        assertEquals(1500L, restored.timeline.totalDurationMs());
    }
}

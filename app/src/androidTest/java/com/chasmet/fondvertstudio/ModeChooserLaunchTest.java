package com.chasmet.fondvertstudio;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

@RunWith(AndroidJUnit4.class)
public final class ModeChooserLaunchTest {
    @Test
    public void launcherShowsBothCaptureModes() {
        try (ActivityScenario<ModeChooserActivity> ignored =
                     ActivityScenario.launch(ModeChooserActivity.class)) {
            onView(withId(R.id.classicModeButton)).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withId(R.id.greenModeButton)).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withId(R.id.settingsButton)).perform(scrollTo()).check(matches(isDisplayed()));
        }
    }
}

package com.foobnix.ui2;

import android.content.Context;
import android.content.res.Configuration;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.model.BookCSS;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Locale;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ContextFontScaleDeviceTest {
    @Test public void choosingRussianPreservesSystemLargeText() { checkScale(1f, 2f); }
    @Test public void explicitAppSizeStillOverridesSystem() { checkScale(1.5f, 1.5f); }

    private void checkScale(float appScale, float expected) {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration original = new Configuration(target.getResources().getConfiguration());
        original.fontScale = 2f; original.setLocale(Locale.ENGLISH);
        Context base = target.createConfigurationContext(original);
        String oldLanguage = AppState.get().appLang;
        float oldScale = BookCSS.get().appFontScale;
        Locale oldLocale = Locale.getDefault();
        android.os.LocaleList oldLocales = android.os.LocaleList.getDefault();
        try {
            AppState.get().appLang = "ru"; BookCSS.get().appFontScale = appScale;
            Context localized = MyContextWrapper.wrap(base);
            assertEquals(expected, localized.getResources().getConfiguration().fontScale, .001f);
            assertEquals("ru", localized.getResources().getConfiguration().getLocales().get(0).getLanguage());
            assertEquals(2f, base.getResources().getConfiguration().fontScale, .001f);
            assertEquals("en", base.getResources().getConfiguration().getLocales().get(0).getLanguage());
        } finally {
            AppState.get().appLang = oldLanguage; BookCSS.get().appFontScale = oldScale;
            Locale.setDefault(oldLocale); android.os.LocaleList.setDefault(oldLocales);
        }
    }
}

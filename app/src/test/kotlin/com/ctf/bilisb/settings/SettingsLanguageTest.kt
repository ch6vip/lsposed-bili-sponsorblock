package com.ctf.bilisb.settings

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import com.ctf.bilisb.R
import com.ctf.bilisb.ui.ModuleStrings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsLanguageTest {
    @Before fun before() { ModuleStrings.resetForTest() }
    @After fun after() { ModuleStrings.resetForTest() }

    private fun englishContext(): Context {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return activity.createConfigurationContext(Configuration(activity.resources.configuration).apply { setLocale(Locale.US) })
    }

    @Test fun explicitLanguageOverridesResourcesWithoutChangingHostOrDefaultLocale() {
        val context = englishContext()
        val before = Configuration(context.resources.configuration)
        val default = Locale.getDefault()
        SettingsLanguage.apply(SettingsLanguage.CHINESE)
        assertEquals("关于", ModuleStrings.get(context, R.string.common_about))
        SettingsLanguage.apply(SettingsLanguage.ENGLISH)
        assertEquals("About", ModuleStrings.get(context, R.string.common_about))
        assertEquals(before, context.resources.configuration)
        assertEquals(default, Locale.getDefault())
    }

    @Test fun returningToSystemAndChangingSystemLocaleInvalidatesResourceCache() {
        val en = englishContext()
        SettingsLanguage.apply(SettingsLanguage.CHINESE)
        assertEquals("关于", ModuleStrings.get(en, R.string.common_about))
        SettingsLanguage.apply(SettingsLanguage.SYSTEM)
        assertEquals("About", ModuleStrings.get(en, R.string.common_about))
        val zh = en.createConfigurationContext(Configuration(en.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
        assertEquals("关于", ModuleStrings.get(zh, R.string.common_about))
    }

    @Test fun hostStringsUseModuleResourcesAndNeverChangeHostLanguage() {
        val self = englishContext()
        val host = object : android.content.ContextWrapper(self) {
            override fun getPackageName(): String = "com.bilibili.app.in"
        }
        val hostConfig = Configuration(host.resources.configuration)
        SettingsLanguage.apply(SettingsLanguage.CHINESE)
        assertEquals("关于", ModuleStrings.localized(self.resources).getString(R.string.common_about))
        SettingsLanguage.apply(SettingsLanguage.ENGLISH)
        assertEquals("About", ModuleStrings.localized(self.resources).getString(R.string.common_about))
        assertEquals(hostConfig, host.resources.configuration)
    }

    @Test fun writerRestoresLanguageWhenSettingsReopen() {
        val context = englishContext()
        context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(SettingsKeys.LANGUAGE, SettingsLanguage.CHINESE).commit()
        SettingsLanguage.apply(SettingsLanguage.ENGLISH)
        val writer = SettingsWriter(context)
        try {
            assertEquals(SettingsLanguage.CHINESE, SettingsLanguage.current)
            assertEquals("关于", ModuleStrings.get(context, R.string.common_about))
        } finally { writer.close() }
    }

    @Test fun preferenceSurvivesAllCodecTransportsAndInvalidValuesUseSystem() {
        val context = englishContext()
        val prefs = context.getSharedPreferences("language-codec-test", Context.MODE_PRIVATE)
        val snapshot = SettingsSnapshot.DEFAULT.copy(language = SettingsLanguage.CHINESE)
        SettingsCodec.writeSnapshotToPreferences(prefs, snapshot)
        assertEquals(snapshot, SettingsCodec.snapshotFromPreferences(prefs))
        assertEquals(snapshot, SettingsCodec.snapshotFromBundle(SettingsCodec.snapshotToBundle(snapshot)))
        assertEquals(snapshot, SettingsCodec.snapshotFromJson(SettingsCodec.snapshotToJson(snapshot)))
        assertEquals(SettingsLanguage.SYSTEM, SettingsCodec.snapshotFromMap(mapOf(SettingsKeys.LANGUAGE to "invalid")).language)
        assertEquals(SettingsLanguage.SYSTEM, SettingsCodec.snapshotFromMap(emptyMap()).language)
    }

    @Test fun languageRowPersistsChoiceAndRebuildsSettingsImmediately() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val writer = SettingsWriter(activity)
        try {
            writer.sharedPreferences.edit().putString(SettingsKeys.LANGUAGE, SettingsLanguage.ENGLISH).apply()
            var refreshed = false
            val root = SettingsScreenBuilder.buildMain(activity, statusWriter = writer,
                onSponsorBlockClick = {}, onLanguageChanged = { refreshed = true })
            val label = textViews(root).first { it.text.toString() == "Language" }
            var row = label as View
            while (!row.isClickable) row = row.parent as View
            row.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull(dialog)
            val list = dialog.listView
            list.performItemClick(list.getChildAt(1), 1, list.adapter.getItemId(1))
            assertTrue(refreshed)
            assertEquals(SettingsLanguage.CHINESE, writer.sharedPreferences.getString(SettingsKeys.LANGUAGE, ""))
            assertEquals("关于", ModuleStrings.get(activity, R.string.common_about))
        } finally { writer.close() }
    }

    private fun textViews(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(textViews(view.getChildAt(i)))
    }
}

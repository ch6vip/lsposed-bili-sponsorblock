package com.ctf.bilisb.settings

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.ctf.bilisb.unlock.SearchRequestPolicy
import com.ctf.bilisb.unlock.SearchUnlockHook
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnlockSettingsUiTest {
    @Test fun `area selection flows from UI to preferences snapshot and mirror`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val prefs = activity.getSharedPreferences("unlock-ui-test", 0)
            prefs.edit().clear().commit()
            val page = SettingsScreenBuilder.buildUnlock(activity, prefs)
            val row = page.findViewWithTag<TextView>(SettingsKeys.UNLOCK_SERVER_AREA)
            assertNotNull(row)
            assertFalse(prefs.contains(SettingsKeys.UNLOCK_SERVER_AREA)) // opening the screen does not save defaults
            row.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.listView.performItemClick(null, 2, 2)
            assertEquals("tw", prefs.getString(SettingsKeys.UNLOCK_SERVER_AREA, null))
            val snapshot = SettingsCodec.snapshotFromPreferences(prefs)
            assertEquals("tw", SettingsCodec.snapshotFromJson(SettingsCodec.snapshotToJson(snapshot)).unlockServerArea)
            val reopened = SettingsScreenBuilder.buildUnlock(activity, prefs)
                .findViewWithTag<TextView>(SettingsKeys.UNLOCK_SERVER_AREA)
            assertEquals(row.text.toString(), reopened.text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `only explicitly marked page changes its request type`() {
        for (native in listOf("7", "8")) {
            val args = Bundle().apply { putString("type", native) }
            assertFalse(SearchUnlockHook.markRegionalPage(args))
            assertEquals(native, args.getString("type"))
        }
        val marked = Bundle().apply {
            putString("type", "7")
            putString(SearchRequestPolicy.ROUTE_MARKER, "1")
            putString("keyword", "retained")
        }
        assertTrue(SearchUnlockHook.markRegionalPage(marked))
        assertEquals("810", marked.getString("type"))
        assertEquals("retained", marked.getString("keyword"))
    }
}

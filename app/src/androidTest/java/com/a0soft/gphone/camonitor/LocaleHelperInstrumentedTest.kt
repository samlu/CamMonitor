package com.a0soft.gphone.camonitor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.a0soft.gphone.camonitor.ui.common.LocaleHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocaleHelperInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var initialTag: String = ""

    @Before
    fun setUp() {
        initialTag = LocaleHelper.getCurrentLanguageTag(context)
    }

    @After
    fun tearDown() {
        // Restore initial language state
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            LocaleHelper.setLanguage(context, initialTag)
        }
    }

    @Test
    fun testSwitchToSimplifiedChineseUpdatesApplicationStrings() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            LocaleHelper.setLanguage(context, LocaleHelper.LANGUAGE_ZH_CN)
        }

        // Verify tag
        val currentTag = LocaleHelper.getCurrentLanguageTag(context)
        assertEquals(LocaleHelper.LANGUAGE_ZH_CN, currentTag)

        // Verify Application instance string retrieval resolves to Simplified Chinese
        val app = CamMonitorApp.instance
        assertEquals("系统日志", app.getString(R.string.system_logs))
        assertEquals("启动镜头模式", app.getString(R.string.start_camera_mode))
        assertEquals("启动观看模式", app.getString(R.string.start_viewer_mode))
        assertEquals("已配对设备", app.getString(R.string.paired_devices))
        assertEquals("返回", app.getString(R.string.action_back))
    }

    @Test
    fun testSwitchToTraditionalChineseUpdatesApplicationStrings() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            LocaleHelper.setLanguage(context, LocaleHelper.LANGUAGE_ZH_TW)
        }

        // Verify tag
        val currentTag = LocaleHelper.getCurrentLanguageTag(context)
        assertEquals(LocaleHelper.LANGUAGE_ZH_TW, currentTag)

        // Verify Application instance string retrieval resolves to Traditional Chinese
        val app = CamMonitorApp.instance
        assertEquals("系統記錄", app.getString(R.string.system_logs))
        assertEquals("啟動鏡頭模式", app.getString(R.string.start_camera_mode))
        assertEquals("啟動觀看模式", app.getString(R.string.start_viewer_mode))
        assertEquals("已配對裝置", app.getString(R.string.paired_devices))
        assertEquals("返回", app.getString(R.string.action_back))
    }

    @Test
    fun testSwitchToEnglishUpdatesApplicationStrings() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            LocaleHelper.setLanguage(context, LocaleHelper.LANGUAGE_EN)
        }

        // Verify tag
        val currentTag = LocaleHelper.getCurrentLanguageTag(context)
        assertEquals(LocaleHelper.LANGUAGE_EN, currentTag)

        // Verify Application instance string retrieval resolves to English
        val app = CamMonitorApp.instance
        assertEquals("System Logs", app.getString(R.string.system_logs))
        assertEquals("Start Camera Mode", app.getString(R.string.start_camera_mode))
        assertEquals("Start Viewer Mode", app.getString(R.string.start_viewer_mode))
        assertEquals("Paired Devices", app.getString(R.string.paired_devices))
        assertEquals("Back", app.getString(R.string.action_back))
    }

    @Test
    fun testTargetLocaleResolution() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            LocaleHelper.setLanguage(context, LocaleHelper.LANGUAGE_ZH_TW)
        }
        val zhLocale = LocaleHelper.getTargetLocale(context)
        assertEquals("zh", zhLocale.language)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            LocaleHelper.setLanguage(context, LocaleHelper.LANGUAGE_EN)
        }
        val enLocale = LocaleHelper.getTargetLocale(context)
        assertEquals("en", enLocale.language)
    }
}

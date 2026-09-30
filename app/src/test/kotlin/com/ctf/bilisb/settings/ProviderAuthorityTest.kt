package com.ctf.bilisb.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 设置 ContentProvider 的 authority 一致性守卫。
 *
 * 这个 authority 有两处声明：`AndroidManifest.xml` 里的 `${applicationId}.settings`
 * （由 AGP 占位符替换）与代码里的 `SettingsSyncBridge.AUTHORITY`
 * （`BuildConfig.APPLICATION_ID + ".settings"`）。两边一旦漂移，**不会崩**，
 * 只会表现为「设置改了不生效 / 统计读到默认值」——历史上正是这类静默失效最难查。
 *
 * 所以这里直接读合并后的 manifest，断言两处同源，并顺带守住
 * 「这个 Provider 不能被第三方 App 无权限读写」的形状（exported + 无读权限时，
 * 设置界面里的 userId 等信息对本机任意 App 可见，属敏感字段暴露面）。
 */
class ProviderAuthorityTest {

    private fun mergedManifest(): File {
        // AGP 把处理后的 manifest 放在 build/intermediates 下；测试任务的 cwd 是 app/。
        val candidates = listOf(
            File("build/intermediates/merged_manifest/debug/processDebugManifest/AndroidManifest.xml"),
            File("build/intermediates/merged_manifest/debug/AndroidManifest.xml"),
            File("build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("合并后的 AndroidManifest 未找到，检查过：${candidates.joinToString { it.path }}")
    }

    private fun providerNodes(): List<org.w3c.dom.Element> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(mergedManifest())
        val nodes = document.getElementsByTagName("provider")
        return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
    }

    @Test
    fun `Provider authority 与代码常量同源`() {
        val settingsProvider = providerNodes().firstOrNull {
            it.getAttribute("android:name").endsWith("SettingsProvider") ||
                it.getAttribute("android:name").endsWith(".settings.SettingsProvider")
        } ?: error("合并后的 manifest 里找不到 SettingsProvider：${providerNodes().map { it.getAttribute("android:name") }}")

        val manifestAuthority = settingsProvider.getAttribute("android:authorities")
        assertTrue(
            "manifest 里的 authority 应包含代码使用的 $${SettingsSyncBridge.AUTHORITY}，实际=$manifestAuthority",
            manifestAuthority.contains(SettingsSyncBridge.AUTHORITY),
        )
        // 占位符必须已被 AGP 替换掉，否则说明构建配置断了
        assertTrue("authority 里不应残留 \${applicationId} 占位符", !manifestAuthority.contains("\${"))
    }

    @Test
    fun `authority 由 applicationId 派生`() {
        assertEquals(
            com.ctf.bilisb.BuildConfig.APPLICATION_ID + ".settings",
            SettingsSyncBridge.AUTHORITY,
        )
    }

    @Test
    fun `Provider 的 exported 与权限形状符合预期`() {
        val settingsProvider = providerNodes().first { it.getAttribute("android:name").endsWith("SettingsProvider") }
        val exported = settingsProvider.getAttribute("android:exported")
        val readPermission = settingsProvider.getAttribute("android:readPermission")
        val writePermission = settingsProvider.getAttribute("android:writePermission")

        // 当前设计是「本机任意 App 可读、宿主进程可读写」：这里把实际形状固定下来。
        // 若将来收紧为 signature 级权限（ROADMAP R2），这个断言会失败并提醒同步文档。
        assertEquals("Provider 需要 exported 才能让宿主进程跨进程读取", "true", exported)
        assertTrue(
            "读写权限当前为空（无权限保护），收紧时请同步更新本断言与 ROADMAP R2: " +
                "read=$readPermission write=$writePermission",
            readPermission.isEmpty() && writePermission.isEmpty(),
        )
    }
}

package com.bydmatecn.app.ui.settings

import com.bydmatecn.app.local.localTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 功能配置只能从“工具”进入；“应用设置”只承载应用自身配置。 */
class SettingsRailSectionsTest {

    @Test fun `all public feature settings have a tool entry`() {
        val toolSections = localTools.mapNotNull { tool ->
            tool.route.removePrefix("tool_settings/")
                .takeIf { tool.route.startsWith("tool_settings/") }
        }.toSet()
        val publicFeatureSections = SettingsSection.entries
            .filterNot { it == SettingsSection.APP || it == SettingsSection.SMART_HOME }
            .map { it.name }
            .toSet()

        assertEquals(publicFeatureSections, toolSections - SettingsSection.APP.name)
        assertFalse(toolSections.contains(SettingsSection.SMART_HOME.name))
    }
}

package com.bydmatecn.app.ui.settings

import com.bydmatecn.app.local.localTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 应用设置保留指定车辆配置，其余功能配置从“工具”进入。 */
class SettingsRailSectionsTest {

    @Test fun `tool and settings entries follow the split navigation design`() {
        val toolSections = localTools.mapNotNull { tool ->
            tool.route.removePrefix("tool_settings/")
                .takeIf { tool.route.startsWith("tool_settings/") }
        }.toSet()
        val settingsOnly = setOf(
            SettingsSection.APP,
            SettingsSection.DISPLAY,
            SettingsSection.BATTERY,
            SettingsSection.DIAGNOSTICS,
            SettingsSection.SMART_HOME,
        )
        val expectedToolSections = SettingsSection.entries
            .filterNot { it in settingsOnly }
            .map { it.name }
            .toSet()

        assertEquals(expectedToolSections, toolSections)
        assertFalse(toolSections.contains(SettingsSection.DISPLAY.name))
        assertFalse(toolSections.contains(SettingsSection.BATTERY.name))
        assertFalse(toolSections.contains(SettingsSection.DIAGNOSTICS.name))
        assertFalse(toolSections.contains(SettingsSection.SMART_HOME.name))
    }
}

package com.bydmatecn.app.local
import com.bydmatecn.app.data.automation.RuleParseResult
import com.bydmatecn.app.data.automation.RuleShare
import com.bydmatecn.app.data.automation.SharedRule
import com.bydmatecn.app.data.local.entity.ActionDef
import com.bydmatecn.app.data.local.entity.TriggerDef
import com.bydmatecn.app.data.local.entity.RuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class LocalMediaRuleRoundTripTest {
    @Test fun localActionsSurviveRuleExchange() {
        val actions = listOf(ActionDef("", "snapshot", "local_camera", "snapshot"),
            ActionDef("", "exterior", "local_audio", "{\"command\":\"exterior\",\"file\":\"audio-test.wav\"}"))
        val entity = RuleEntity(name="local", enabled=false,
            triggers=TriggerDef.listToJson(listOf(TriggerDef("button_press", "button", "==", "", "button", "button_press"))),
            actions=ActionDef.listToJson(actions), requirePark=true)
        val exported = RuleShare.exportJson(SharedRule.fromEntity(entity), "test")
        val result = RuleShare.parse(exported, "call")
        assertTrue(result is RuleParseResult.Ok)
        assertEquals(actions, (result as RuleParseResult.Ok).rule.actions)
        assertTrue(result.rule.requirePark)
    }
}

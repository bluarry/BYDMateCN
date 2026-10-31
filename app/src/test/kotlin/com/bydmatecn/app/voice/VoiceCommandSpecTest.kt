package com.bydmatecn.app.voice

import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandSpecTest {
    @Test fun every_catalog_command_is_dispatchable() {
        // Each produced command must be resolvable by CommandTranslator,
        // i.e. it is a real, executable command string — no typos/orphans.
        // Mirrors VehicleApiImpl.dispatch()'s order: resolveSeat() is tried
        // first (seat heat/vent), then resolve() (everything else).
        for (spec in VoiceCatalog.ALL) {
            val sample = spec.value?.min
            val cmd = spec.command(sample)
            assertTrue(
                "command not dispatchable: $cmd",
                com.bydmatecn.app.data.vehicle.CommandTranslator.resolveSeat(cmd) != null ||
                    com.bydmatecn.app.data.vehicle.CommandTranslator.resolve(cmd).isNotEmpty()
            )
        }
    }
}

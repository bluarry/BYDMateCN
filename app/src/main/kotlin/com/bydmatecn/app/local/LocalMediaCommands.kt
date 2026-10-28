package com.bydmatecn.app.local

/** Main-thread in-process command handoff. No hidden activity launch from background rules. */
object LocalMediaCommands {
    var camera: ((String) -> Boolean)? = null
    var audio: ((String, String?) -> Boolean)? = null
}

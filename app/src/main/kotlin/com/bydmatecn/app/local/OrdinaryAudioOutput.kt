package com.bydmatecn.app.local

/** Telephony (18), built-in earpiece (1) and remote-submix are not selectable media sinks. */
internal fun ordinaryAudioOutput(type: Int): Boolean = type in setOf(2, 3, 4, 7, 8, 9, 10, 11, 12, 13, 19, 22, 26, 27)

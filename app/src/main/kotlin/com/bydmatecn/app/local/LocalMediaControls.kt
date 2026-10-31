package com.bydmatecn.app.local

internal fun LocalMediaActivity.mediaButton(text: String, action: () -> Unit) {
    ui.button(text) { LocalFileLog.get(this).record("media/action", text); action() }
}

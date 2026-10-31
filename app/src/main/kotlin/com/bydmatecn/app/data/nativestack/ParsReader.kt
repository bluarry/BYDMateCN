package com.bydmatecn.app.data.nativestack

import com.bydmatecn.app.data.remote.DiParsData

interface ParsReader {
    suspend fun fetch(): DiParsData?
}

package com.nemo.networkconditioner.interfaces

import com.nemo.networkconditioner.model.AppState

fun interface AppStateListener {
    fun appStateChanged(state: AppState)
}

package com.nemo.networkconditioner.model

/* flow: ready -> starting -> running -> stopping -> ready  */
enum class AppState {
    ready,
    starting,
    running,
    stopping
}

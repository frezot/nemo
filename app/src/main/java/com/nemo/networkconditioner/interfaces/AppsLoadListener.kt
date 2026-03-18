package com.nemo.networkconditioner.interfaces

import com.nemo.networkconditioner.model.AppDescriptor

fun interface AppsLoadListener {
    fun onAppsInfoLoaded(apps: MutableList<AppDescriptor>)
}

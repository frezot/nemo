package com.nemo.networkconditioner

import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.nemo.networkconditioner.model.AppDescriptor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object AppIconLoader {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun setIcon(imageView: ImageView, app: AppDescriptor?, placeholder: Drawable?) {
        if (app == null) {
            imageView.setImageDrawable(placeholder)
            imageView.tag = null
            return
        }

        val cached = app.getCachedIcon()
        if (cached != null) {
            imageView.setImageDrawable(cached)
            imageView.tag = null
            return
        }

        imageView.setImageDrawable(placeholder)
        val packageName = app.getPackageName()
        imageView.tag = packageName

        executor.execute {
            val icon = app.loadIcon()

            mainHandler.post {
                if (packageName == imageView.tag) {
                    app.setLoadedIcon(icon)
                    if (icon != null) {
                        imageView.setImageDrawable(icon)
                    }
                    imageView.tag = null
                }
            }
        }
    }
}

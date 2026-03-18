package com.nemo.networkconditioner.activities

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.nemo.networkconditioner.R
import com.nemo.networkconditioner.Utils

open class BaseActivity : AppCompatActivity() {
    private var backAction = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Utils.enableEdgeToEdge(this)
        super.onCreate(savedInstanceState)
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)

        val toolbar = findViewById<View?>(R.id.toolbar)
        toolbar?.let {
            // Fix padding of content below the toolbar
            ViewCompat.setOnApplyWindowInsetsListener(it) { view, insets ->
                val topInset = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars() or
                        WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout(),
                ).top
                if (topInset > 0) {
                    view.setPadding(0, topInset, 0, 0)
                }
                insets
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        applyOverrideConfiguration(Utils.getLocalizedConfig(newBase))
        super.attachBaseContext(newBase)
    }

    protected fun displayBackAction() {
        supportActionBar?.let {
            it.setDisplayHomeAsUpEnabled(true)
            backAction = true
        }
    }

    protected fun getFragment(targetClass: Class<*>): Fragment? {
        return supportFragmentManager.fragments.firstOrNull { targetClass.isInstance(it) }
    }

    protected fun getFragmentAtPos(pos: Int): Fragment? {
        return supportFragmentManager.findFragmentByTag("f$pos")
    }

    @Suppress("DEPRECATION")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (backAction && item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }

        return super.onOptionsItemSelected(item)
    }
}

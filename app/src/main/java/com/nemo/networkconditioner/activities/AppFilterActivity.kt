package com.nemo.networkconditioner.activities

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.annotation.NonNull
import androidx.collection.ArraySet
import androidx.core.view.MenuProvider
import androidx.preference.PreferenceManager
import com.nemo.networkconditioner.Log
import com.nemo.networkconditioner.R
import com.nemo.networkconditioner.Utils
import com.nemo.networkconditioner.fragments.AppsToggles
import com.nemo.networkconditioner.model.AppDescriptor
import com.nemo.networkconditioner.model.Prefs

class AppFilterActivity : BaseActivity(), MenuProvider {
    private var fragment: AppFilterFragment? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.target_apps)
        setContentView(R.layout.fragment_activity)
        addMenuProvider(this)
        displayBackAction()

        if (savedInstanceState != null) {
            fragment = supportFragmentManager.getFragment(savedInstanceState, "fragment") as? AppFilterFragment
        }
        if (fragment == null) {
            fragment = AppFilterFragment()
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment, fragment!!)
            .commit()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }

    override fun onSaveInstanceState(@NonNull outState: Bundle) {
        super.onSaveInstanceState(outState)
        supportFragmentManager.putFragment(outState, "fragment", fragment!!)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.hint_menu, menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.show_hint) {
            Utils.showHelpDialog(this, R.string.target_apps_help)
            true
        } else {
            false
        }
    }

    @Deprecated("Uses deprecated Activity.onBackPressed() for current app flow compatibility")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (fragment?.onBackPressed() == true) {
            return
        }

        super.onBackPressed()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }

    class AppFilterFragment : AppsToggles() {
        private val selectedApps = ArraySet<String>()
        private var prefs: SharedPreferences? = null

        override fun onAttach(context: Context) {
            super.onAttach(context)
            prefs = PreferenceManager.getDefaultSharedPreferences(context)

            selectedApps.clear()

            val saved = Prefs.getStringSet(requireNotNull(prefs), Prefs.PREF_APP_FILTER)
            if (saved.isNotEmpty()) {
                Log.d(TAG, "Loading ${saved.size} target apps")
                selectedApps.addAll(saved)
            }
        }

        override fun onDetach() {
            super.onDetach()
            prefs = null
        }

        override fun getCheckedApps(): MutableSet<String> = selectedApps

        override fun onAppToggled(app: AppDescriptor, checked: Boolean) {
            val packageName = app.getPackageName()
            if (selectedApps.contains(packageName) == checked) {
                return
            }

            if (checked) {
                selectedApps.add(packageName)
            } else {
                selectedApps.remove(packageName)
            }

            Log.d(TAG, "Saving ${selectedApps.size} target apps")

            prefs?.edit()
                ?.putStringSet(Prefs.PREF_APP_FILTER, selectedApps)
                ?.apply()
        }
    }

    companion object {
        private const val TAG = "AppFilterActivity"
    }
}

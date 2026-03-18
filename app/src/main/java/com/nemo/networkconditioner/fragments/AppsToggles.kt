package com.nemo.networkconditioner.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.nemo.networkconditioner.AppsLoader
import com.nemo.networkconditioner.Log
import com.nemo.networkconditioner.R
import com.nemo.networkconditioner.Utils
import com.nemo.networkconditioner.adapters.AppsTogglesAdapter
import com.nemo.networkconditioner.interfaces.AppsLoadListener
import com.nemo.networkconditioner.model.AppDescriptor
import com.nemo.networkconditioner.views.EmptyRecyclerView

abstract class AppsToggles : Fragment(), AppsLoadListener, AppsTogglesAdapter.AppToggleListener,
    MenuProvider, SearchView.OnQueryTextListener {
    private lateinit var adapter: AppsTogglesAdapter
    private var searchView: SearchView? = null
    private lateinit var emptyText: TextView
    private var queryToApply: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        return inflater.inflate(R.layout.apps_stats, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recyclerView = view.findViewById<EmptyRecyclerView>(R.id.recycler_view)
        recyclerView.layoutManager = EmptyRecyclerView.MyLinearLayoutManager(requireContext())

        adapter = AppsTogglesAdapter(requireContext(), getCheckedApps())
        recyclerView.adapter = adapter
        adapter.setAppToggleListener(this)

        emptyText = view.findViewById(R.id.no_apps)
        emptyText.setText(R.string.loading_apps)
        recyclerView.setEmptyView(emptyText)

        if (savedInstanceState != null) {
            val filter = savedInstanceState.getString("filter")
            if (!filter.isNullOrEmpty()) {
                queryToApply = filter
            }
        }

        adapter.setShowSystemApps(showSystemApps)

        Log.d(TAG, "mQueryToApply: $queryToApply")

        AppsLoader(requireActivity() as AppCompatActivity)
            .setAppsLoadListener(this)
            .loadAllApps()
    }

    override fun onPause() {
        super.onPause()
        queryToApply = searchView?.query?.toString()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.search_menu, menu)
        val searchItem = menu.findItem(R.id.search)
        searchView = searchItem.actionView as SearchView
        searchView?.setOnQueryTextListener(this)

        if (!queryToApply.isNullOrEmpty()) {
            Log.d(TAG, "Initial filter: $queryToApply")
            Utils.setSearchQuery(searchView, searchItem, queryToApply)
        }

        menu.findItem(R.id.show_system_apps)?.isChecked = showSystemApps
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return if (menuItem.itemId == R.id.show_system_apps) {
            showSystemApps = !showSystemApps
            menuItem.isChecked = showSystemApps
            adapter.setShowSystemApps(showSystemApps)
            true
        } else {
            false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val query = searchView?.query?.toString()
        if (!query.isNullOrEmpty()) {
            Log.d(TAG, "Saving filter: $query")
            outState.putString("filter", query)
        }
    }

    fun onBackPressed(): Boolean {
        Log.d(TAG, "onBackPressed")
        return Utils.backHandleSearchview(searchView)
    }

    override fun onQueryTextSubmit(query: String?): Boolean = true

    override fun onQueryTextChange(newText: String?): Boolean {
        adapter.setFilter(newText ?: "")
        return true
    }

    override fun onAppsInfoLoaded(apps: MutableList<AppDescriptor>) {
        adapter.setApps(apps)
        emptyText.setText(R.string.no_matches_found)
    }

    protected abstract fun getCheckedApps(): Set<String>

    companion object {
        private const val TAG = "AppsToggles"
        private var showSystemApps: Boolean = false
    }
}

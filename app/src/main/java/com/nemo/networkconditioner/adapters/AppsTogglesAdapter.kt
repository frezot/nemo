package com.nemo.networkconditioner.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.Nullable
import androidx.appcompat.widget.SwitchCompat
import androidx.collection.ArraySet
import androidx.recyclerview.widget.RecyclerView
import com.nemo.networkconditioner.AppIconLoader
import com.nemo.networkconditioner.Log
import com.nemo.networkconditioner.R
import com.nemo.networkconditioner.model.AppDescriptor
import java.util.Collections

class AppsTogglesAdapter(context: Context, checkedItems: Set<String>) :
    RecyclerView.Adapter<AppsTogglesAdapter.AppViewHolder>() {
    private val layoutInflater =
        context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
    private val checkedItems = ArraySet<String>(checkedItems)
    private var listener: AppToggleListener? = null
    private var filter: String = ""
    private var showSystemApps: Boolean = false
    private var apps: List<AppDescriptor> = ArrayList()
    private val filteredApps = ArrayList<AppDescriptor>()

    @Nullable
    private var recyclerView: RecyclerView? = null

    fun interface AppToggleListener {
        fun onAppToggled(app: AppDescriptor, checked: Boolean)
    }

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appName: TextView = view.findViewById(R.id.app_name)
        val packageName: TextView = view.findViewById(R.id.app_package)
        val icon: ImageView = view.findViewById(R.id.icon)
        val toggle: SwitchCompat = view.findViewById(R.id.toggle_btn)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = layoutInflater.inflate(R.layout.app_selection_item, parent, false)
        val holder = AppViewHolder(view)

        view.setOnClickListener {
            val pos = holder.absoluteAdapterPosition
            val app = getItem(pos)
            if (app != null) {
                val checked = checkedItems.contains(app.getPackageName())
                handleToggle(pos, !checked)
            }
        }

        holder.toggle.setOnClickListener { toggleView ->
            val pos = holder.absoluteAdapterPosition
            val checked = (toggleView as SwitchCompat).isChecked
            handleToggle(pos, checked)
        }

        return holder
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = getItem(position) ?: return
        holder.appName.text = app.getName()
        holder.packageName.text = app.getPackageName()
        holder.toggle.isChecked = checkedItems.contains(app.getPackageName())
        AppIconLoader.setIcon(holder.icon, app, null)
    }

    override fun getItemCount(): Int = getApps().size

    fun getItem(pos: Int): AppDescriptor? {
        if (pos < 0 || pos >= itemCount) {
            return null
        }
        return getApps()[pos]
    }

    private fun isFiltering(): Boolean = filter.isNotEmpty() || !showSystemApps

    private fun getApps(): MutableList<AppDescriptor> {
        return if (isFiltering()) filteredApps else apps.toMutableList()
    }

    private fun handleToggle(oldPos: Int, checked: Boolean) {
        val app = getItem(oldPos) ?: return
        val packageName = app.getPackageName()

        if (checked == checkedItems.contains(packageName)) {
            return
        }

        if (checked) {
            checkedItems.add(packageName)
        } else {
            checkedItems.remove(packageName)
        }

        listener?.onAppToggled(app, checked)

        if (!checked && !showSystemApps && app.isBackgroundSystemApp()) {
            val mutableApps = if (isFiltering()) filteredApps else apps.toMutableList()
            mutableApps.removeAt(oldPos)
            if (!isFiltering()) {
                apps = mutableApps
            }
            notifyItemRemoved(oldPos)
            return
        }

        val mutableApps = if (isFiltering()) filteredApps else apps.toMutableList()
        var newPos = oldPos
        for (i in mutableApps.indices) {
            val other = mutableApps[i]
            if (i != oldPos && compareCheckedFirst(app, other) <= 0) {
                newPos = i
                break
            }
        }

        if (newPos > oldPos) {
            newPos--
        }

        Log.d(TAG, "Item @$oldPos: ${if (checked) "checked" else "unchecked"} -> $newPos")
        notifyItemChanged(oldPos)

        if (newPos != oldPos) {
            mutableApps.removeAt(oldPos)
            mutableApps.add(newPos, app)

            if (!isFiltering()) {
                apps = mutableApps
            }

            notifyItemMoved(oldPos, newPos)

            recyclerView?.scrollToPosition(if (checked) newPos else oldPos)
        }
    }

    private fun compareCheckedFirst(a: AppDescriptor, b: AppDescriptor): Int {
        val aChecked = checkedItems.contains(a.getPackageName())
        val bChecked = checkedItems.contains(b.getPackageName())

        return when {
            aChecked && !bChecked -> -1
            !aChecked && bChecked -> 1
            else -> a.compareTo(b)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshFilteredApps() {
        filteredApps.clear()

        if (isFiltering()) {
            for (app in apps) {
                if (filter.isNotEmpty() && !app.matches(filter, false)) continue
                if (!showSystemApps && app.isBackgroundSystemApp() && !checkedItems.contains(app.getPackageName())) continue
                filteredApps.add(app)
            }
        }

        Collections.sort(if (isFiltering()) filteredApps else apps.toMutableList(), this::compareCheckedFirst)
        notifyDataSetChanged()
    }

    fun setApps(apps: List<AppDescriptor>) {
        this.apps = apps
        refreshFilteredApps()
    }

    fun setFilter(text: String) {
        filter = text
        refreshFilteredApps()
    }

    fun setShowSystemApps(show: Boolean) {
        showSystemApps = show
        refreshFilteredApps()
    }

    fun setAppToggleListener(listener: AppToggleListener?) {
        this.listener = listener
    }

    companion object {
        private const val TAG = "AppToggleAdapter"
    }
}

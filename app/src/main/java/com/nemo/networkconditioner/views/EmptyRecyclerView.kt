package com.nemo.networkconditioner.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator

// Adapter from https://gist.github.com/AlexZhukovich/537eaa1e3c82ef9f5d5cd22efdc80c54#file-emptyrecyclerview-java
class EmptyRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : RecyclerView(context, attrs, defStyle) {
    private var emptyView: View? = null

    init {
        initRecyclerView()
    }

    /* Workaround for crash "java.lang.IndexOutOfBoundsException: Inconsistency detected. Invalid item position 0(offset:-1)".
     * See https://stackoverflow.com/questions/30220771/recyclerview-inconsistency-detected-invalid-item-position .
     * It can be reproduced by setting CaptureService.CONNECTIONS_LOG_SIZE = 4 and triggering a rollover right after inserting
     * item 3 in the register. It may take several tries to reproduce.
     * Possibly related issues:
     *  - https://issuetracker.google.com/issues?q=componentid:192731%2B%20IndexOutOfBoundsException%20Invalid%20item%20position
     * Another way to fix the issue is to disable the item animations via setItemAnimator(null).
     */
    class MyLinearLayoutManager(context: Context) : LinearLayoutManager(context) {
        override fun supportsPredictiveItemAnimations(): Boolean = false
    }

    private fun initRecyclerView() {
        // Disable the item change animation since it cancels the touch event, making it impossible
        // to long click a continuously refreshed item.
        // https://stackoverflow.com/questions/58628885/handle-touch-events-for-recyclerview-with-frequently-changing-data
        val animator = itemAnimator
        if (animator is SimpleItemAnimator) {
            animator.supportsChangeAnimations = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )

            val isImeOpen = windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0

            val layoutParams = view.layoutParams as ViewGroup.MarginLayoutParams
            layoutParams.topMargin = insets.top
            layoutParams.bottomMargin = if (isImeOpen) insets.bottom else 0
            layoutParams.leftMargin = insets.left
            layoutParams.rightMargin = insets.right
            view.layoutParams = layoutParams

            // when IME is open, apply as a margin for proper resizing
            // when not open, apply as a padding for an optimal edge-to-edge experience
            view.setPadding(0, 0, 0, if (!isImeOpen) insets.bottom else 0)

            windowInsets
        }
        clipToPadding = false
    }

    private fun initEmptyView() {
        val isEmpty = (adapter?.itemCount ?: 0) == 0
        emptyView?.visibility = if (isEmpty) VISIBLE else GONE
        visibility = if (isEmpty) GONE else VISIBLE
    }

    private val observer = object : AdapterDataObserver() {
        override fun onChanged() {
            super.onChanged()
            initEmptyView()
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            super.onItemRangeInserted(positionStart, itemCount)
            initEmptyView()
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            super.onItemRangeRemoved(positionStart, itemCount)
            initEmptyView()
        }
    }

    override fun setAdapter(adapter: Adapter<*>?) {
        val oldAdapter = getAdapter()
        super.setAdapter(adapter)

        oldAdapter?.unregisterAdapterDataObserver(observer)
        adapter?.registerAdapterDataObserver(observer)

        initEmptyView()
    }

    fun setEmptyView(view: View?) {
        emptyView?.setOnApplyWindowInsetsListener(null)

        emptyView = view
        initEmptyView()

        view?.let {
            ViewCompat.setOnApplyWindowInsetsListener(it) { empty, windowInsets ->
                val insets: Insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
                empty.setPadding(0, insets.top, 0, 0)
                windowInsets
            }
        }
    }
}

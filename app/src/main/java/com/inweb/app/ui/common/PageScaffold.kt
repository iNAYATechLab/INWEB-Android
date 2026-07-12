package com.inweb.app.ui.common

import android.app.Activity
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.inweb.app.R

/**
 * Convenience wrapper that wires up the shared header included in every page.
 *
 * Usage in Activity.onCreate:
 *
 *   PageScaffold.setup(this, title = "Files", onBack = { finish() })
 *   PageScaffold.setActionIcon(this, R.drawable.ic_refresh) { refresh() }
 *   BottomNavHelper.attach(this, BottomNavHelper.Tab.MORE)
 */
object PageScaffold {

    fun setup(
        activity: Activity,
        title: String,
        onBack: () -> Unit
    ) {
        activity.findViewById<TextView>(R.id.headerTitle)?.text = title
        activity.findViewById<View>(R.id.headerBack)?.setOnClickListener { onBack() }
    }

    /** Show a primary action icon in the header (e.g. refresh, save). */
    fun setActionIcon(activity: Activity, iconRes: Int, tint: Int? = null, onClick: () -> Unit) {
        val btn = activity.findViewById<ImageButton>(R.id.headerAction) ?: return
        btn.setImageResource(iconRes)
        tint?.let { btn.setColorFilter(it) }
        btn.visibility = View.VISIBLE
        btn.setOnClickListener { onClick() }
    }

    /** Show a secondary action icon (e.g. overflow menu). */
    fun setSecondaryActionIcon(activity: Activity, iconRes: Int, onClick: () -> Unit) {
        val btn = activity.findViewById<ImageButton>(R.id.headerAction2) ?: return
        btn.setImageResource(iconRes)
        btn.visibility = View.VISIBLE
        btn.setOnClickListener { onClick() }
    }
}

package com.inweb.app.ui.common

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.inweb.app.MainActivity
import com.inweb.app.R
import com.inweb.app.ui.files.FilesActivity
import com.inweb.app.ui.framework.FrameworksActivity
import com.inweb.app.ui.ddns.DdnsActivity
import com.inweb.app.ui.islamic.IslamicApisActivity
import com.inweb.app.ui.logs.LogsActivity
import com.inweb.app.ui.security.SecurityActivity
import com.inweb.app.ui.dns.HostsActivity
import com.inweb.app.ui.services.ServerClusterActivity
import com.inweb.app.ui.services.ServicesActivity
import com.inweb.app.ui.share.NetworkInfoActivity
import com.inweb.app.ui.share.SettingsActivity
import com.inweb.app.ui.vhost.SitesActivity

/**
 * Attaches click handlers to the shared bottom-nav bar (see
 * `layout/inweb_bottom_nav.xml`) and highlights the tab that matches
 * the current activity.
 *
 * Usage from an activity:
 *
 *   BottomNavHelper.attach(this, Tab.SERVICES)
 */
object BottomNavHelper {

    enum class Tab { HOME, SERVICES, LOGS, SHARE, MORE }

    fun attach(activity: Activity, active: Tab) {
        val root = activity.findViewById<View>(R.id.bottomNav) ?: return

        val ids = mapOf(
            Tab.HOME     to listOf(R.id.navHome,     R.id.navHomeIcon,     R.id.navHomeLabel),
            Tab.SERVICES to listOf(R.id.navServices, R.id.navServicesIcon, R.id.navServicesLabel),
            Tab.LOGS     to listOf(R.id.navLogs,     R.id.navLogsIcon,     R.id.navLogsLabel),
            Tab.SHARE    to listOf(R.id.navShare,    R.id.navShareIcon,    R.id.navShareLabel),
            Tab.MORE     to listOf(R.id.navMore,     R.id.navMoreIcon,     R.id.navMoreLabel),
        )

        val accent   = ContextCompat.getColor(activity, R.color.accent)
        val inactive = ContextCompat.getColor(activity, R.color.text_secondary)

        for ((tab, viewIds) in ids) {
            val row  = root.findViewById<View>(viewIds[0])
            val icon = root.findViewById<ImageView>(viewIds[1])
            val lbl  = root.findViewById<TextView>(viewIds[2])
            val color = if (tab == active) accent else inactive
            icon.setColorFilter(color)
            lbl.setTextColor(color)
            if (tab == active) lbl.setTypeface(lbl.typeface, android.graphics.Typeface.BOLD)
            row.setOnClickListener { navigate(activity, tab, active) }
        }
    }

    private fun navigate(activity: Activity, target: Tab, active: Tab) {
        if (target == active) return                            // already here
        val intent = when (target) {
            Tab.HOME     -> Intent(activity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            // Services tab → rich cluster dashboard (matches user's design)
            Tab.SERVICES -> Intent(activity, ServerClusterActivity::class.java)
            Tab.LOGS     -> Intent(activity, LogsActivity::class.java)
            Tab.SHARE    -> Intent(activity, NetworkInfoActivity::class.java)
            Tab.MORE     -> { showMoreSheet(activity); return }
        }
        activity.startActivity(intent)
    }

    private fun showMoreSheet(activity: Activity) {
        val items = arrayOf(
            activity.getString(R.string.btn_sites),
            activity.getString(R.string.btn_hosts),
            activity.getString(R.string.btn_files),
            activity.getString(R.string.btn_frameworks),
            activity.getString(R.string.btn_islamic),
            activity.getString(R.string.btn_security),
            activity.getString(R.string.btn_ddns),
            activity.getString(R.string.btn_settings)
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.more_title)
            .setItems(items) { _, which ->
                activity.startActivity(
                    Intent(activity, when (which) {
                        0 -> SitesActivity::class.java
                        1 -> HostsActivity::class.java
                        2 -> FilesActivity::class.java
                        3 -> FrameworksActivity::class.java
                        4 -> IslamicApisActivity::class.java
                        5 -> SecurityActivity::class.java
                        6 -> DdnsActivity::class.java
                        else -> SettingsActivity::class.java
                    })
                )
            }.show()
    }
}

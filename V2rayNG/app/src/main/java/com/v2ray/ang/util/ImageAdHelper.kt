package com.v2ray.ang.util

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupWindow
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R

/**
 * Homegrown image popup ads (no AdMob).
 * Shows every 4th user action, and can be forced (e.g. stop VPN).
 */
object ImageAdHelper {
    @JvmStatic
    var actionCount = 1L

    fun onUserAction(activity: Activity) {
        if (actionCount % 4L == 0L) {
            showImageAd(activity)
        }
        actionCount++
    }

    fun showImageAd(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val imageView = ImageView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setImageResource(R.drawable.v2free)
            adjustViewBounds = true
            maxWidth = (activity.resources.displayMetrics.widthPixels * 0.85f).toInt()
            setOnClickListener {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(AppConfig.IMAGE_AD_CLICK_URL))
                )
            }
        }
        PopupWindow(
            imageView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).showAtLocation(activity.window.decorView, Gravity.CENTER, 0, 0)
    }
}

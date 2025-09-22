package com.example.myapplication

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.example.myapplication.utils.TAG_MAIN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MyAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var scannerJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        startForegroundServiceNotification()
        startUniversalScanner() // Start a single, continuous scanner
    }

    private fun startForegroundServiceNotification() {
        val channelId = "shorts_blocker_service_channel"
        val channelName = "Shorts Blocker Service"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Shorts Blocker is Active")
            .setContentText("Protecting your focus.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    // This is not needed in our new design
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.d(TAG_MAIN, "Service interrupted.")
        scannerJob?.cancel()
    }

    private fun startUniversalScanner() {
        scannerJob = serviceScope.launch {
            while (isActive) {
                val rootNode = rootInActiveWindow ?: run {
                    delay(500)
                    return@launch
                }

                // This safe-call ?.let prevents crashes if the package name is ever null
                rootNode.packageName?.let { currentPackageName ->
                    var isShortsDetected = false

                    when (currentPackageName) {
                        "com.google.android.youtube" -> {
                            if (isYouTubeShorts(rootNode)) {
                                Log.d(TAG_MAIN, "YouTube Shorts detected.")
                                isShortsDetected = true
                            }
                        }
                        "com.instagram.android" -> {
                            if (isInstagramReels(rootNode)) {
                                Log.d(TAG_MAIN, "Instagram Reels detected.")
                                isShortsDetected = true
                            }
                        }
                    }

                    if (isShortsDetected) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        delay(1000)
                    }
                }

                delay(500)
            }
        }
    }


    // --- YOUTUBE DETECTION LOGIC ---
    private fun isYouTubeShorts(rootNode: AccessibilityNodeInfo): Boolean {
        if (isYouTubeShortsTabActive(rootNode)) return true
        if (findNodeByResourceId(rootNode, "com.google.android.youtube:id/reel_watch_fragment_root")) return true
        if (findNavigateUpButton(rootNode)) return true
        return false
    }

    private fun isYouTubeShortsTabActive(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val isButton = "android.widget.Button" == node.className?.toString()
        val isShortsDesc = "Shorts" == node.contentDescription?.toString()
        val isSelected = node.isSelected
        if (isButton && isShortsDesc && isSelected) return true
        for (i in 0 until node.childCount) {
            if (isYouTubeShortsTabActive(node.getChild(i))) return true
        }
        return false
    }

    private fun findNavigateUpButton(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val isImageButton = "android.widget.ImageButton" == node.className?.toString()
        val isNavigateUp = "Navigate up" == node.contentDescription?.toString()
        if (isImageButton && isNavigateUp) return true
        for (i in 0 until node.childCount) {
            if (findNavigateUpButton(node.getChild(i))) return true
        }
        return false
    }

    // --- INSTAGRAM DETECTION LOGIC ---
    private fun isInstagramReels(rootNode: AccessibilityNodeInfo): Boolean {
        // Method 1: The most robust check. A Reels viewer is active AND the main feed is NOT.
        // This correctly identifies the full-screen player while ignoring in-feed previews.
        val isReelsViewerPresent = findNodeByResourceId(rootNode, "com.instagram.android:id/clips_viewer_view_pager")
        val isMainFeedPresent = isMainFeedListPresent(rootNode)
        if (isReelsViewerPresent && !isMainFeedPresent) {
            Log.d(TAG_MAIN,"Instagram reel detected in clips viewer resource ID")
            return true
        }

        // Method 2: The back button specific to some Reels UIs is present.
        if (findNodeByResourceId(rootNode, "com.instagram.android:id/action_bar_button_back")) {
            // We can make this safer too by checking that the main feed is absent.
            if (!isMainFeedPresent) {
                Log.d(TAG_MAIN,"Back button and not main feed. reel spotted.")
                return true
            }
        }

        // Method 3: The Reels tab on the bottom bar is selected. This implies the full-screen viewer.
        if (isInstagramReelsTabActive(rootNode)) {
            Log.d(TAG_MAIN,"Easiest one. instagram reels tab clicked.")
            return true
        }

        return false
    }

    /**
     * NEW: Helper function to check for the presence of the main feed's RecyclerView.
     */
    private fun isMainFeedListPresent(rootNode: AccessibilityNodeInfo?): Boolean {
        // The main feed is contained in a RecyclerView with the resource ID "android:id/list".
        return findNodeByResourceId(rootNode, "android:id/list")
    }

    private fun isInstagramReelsTabActive(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        val reelTabs = rootNode.findAccessibilityNodeInfosByViewId("com.instagram.android:id/clips_tab")
        for (node in reelTabs) {
            if (node.isSelected) return true
        }
        return false
    }

    // --- GENERIC HELPER FUNCTIONS ---
    private fun findNodeByResourceId(node: AccessibilityNodeInfo?, resourceId: String): Boolean {
        if (node == null) return false
        return node.findAccessibilityNodeInfosByViewId(resourceId).isNotEmpty()
    }

    private fun findNodeByPartialContentDescription(node: AccessibilityNodeInfo?, partialDesc: String): Boolean {
        if (node == null) return false
        if (node.contentDescription?.toString()?.contains(partialDesc, ignoreCase = true) == true) {
            return true
        }
        for (i in 0 until node.childCount) {
            if (findNodeByPartialContentDescription(node.getChild(i), partialDesc)) return true
        }
        return false
    }
}
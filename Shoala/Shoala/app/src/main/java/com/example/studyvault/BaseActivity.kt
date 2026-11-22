package com.example.studyvault

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

/** The three top-level destinations shown in the bottom navigation bar. */
enum class NavTab { HOME, STATS, SETTINGS }

private class NavItem(
    val tab: NavTab,
    val rootId: Int,
    val pillId: Int,
    val iconId: Int,
    val labelId: Int,
    val target: Class<out Activity>
)

/**
 * Shared base for all screens: NFC card handling works from any page,
 * refreshUI() is called every second while the page is visible, and the
 * bottom navigation bar is wired up here so every tab behaves the same.
 */
open class BaseActivity : AppCompatActivity() {

    protected var nfcAdapter: NfcAdapter? = null
    protected val uiHandler = Handler(Looper.getMainLooper())

    private val uiRefreshRunnable = object : Runnable {
        override fun run() {
            refreshUI()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    protected fun appPrefs(): SharedPreferences =
        getSharedPreferences("StudyVaultPrefs", Context.MODE_PRIVATE)

    protected open fun refreshUI() {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // White app: dark status-bar and navigation-bar icons.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(uiRefreshRunnable)
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiRefreshRunnable)
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    // =====================================================================
    // Bottom navigation
    // =====================================================================

    /** Call after setContentView() on Home, Stats and Settings. */
    protected fun setupBottomNav(selected: NavTab) {
        window.navigationBarColor = ContextCompat.getColor(this, R.color.surface)

        val items = listOf(
            NavItem(NavTab.HOME, R.id.navHome, R.id.navHomePill, R.id.navHomeIcon, R.id.navHomeLabel, MainActivity::class.java),
            NavItem(NavTab.STATS, R.id.navStats, R.id.navStatsPill, R.id.navStatsIcon, R.id.navStatsLabel, StatsActivity::class.java),
            NavItem(NavTab.SETTINGS, R.id.navSettings, R.id.navSettingsPill, R.id.navSettingsIcon, R.id.navSettingsLabel, SettingsActivity::class.java)
        )
        val activeColor = ContextCompat.getColor(this, R.color.accent)
        val idleColor = ContextCompat.getColor(this, R.color.text_tertiary)

        for (item in items) {
            val isSelected = item.tab == selected
            val color = if (isSelected) activeColor else idleColor

            findViewById<View>(item.pillId)
                .setBackgroundResource(if (isSelected) R.drawable.bg_nav_pill else 0)
            findViewById<ImageView>(item.iconId).imageTintList = ColorStateList.valueOf(color)
            findViewById<TextView>(item.labelId).setTextColor(color)

            findViewById<View>(item.rootId).setOnClickListener {
                if (!isSelected) navigateTo(item.target)
            }
        }
    }

    private fun navigateTo(target: Class<out Activity>) {
        val intent = Intent(this, target).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
        )
        startActivity(intent)
        // Home stays at the bottom of the stack so Back always returns to it.
        if (this !is MainActivity) finish()
    }

    // =====================================================================
    // NFC
    // =====================================================================

    protected fun handleNfcIntent(intent: Intent?) {
        if (intent?.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            intent?.action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            intent?.action == NfcAdapter.ACTION_TECH_DISCOVERED
        ) {
            val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }

            intent.action = Intent.ACTION_MAIN // consume the NFC intent so it is never processed twice

            tag?.let {
                val scannedUid = it.id.joinToString(":") { byte -> "%02X".format(byte) }
                val prefs = appPrefs()
                val savedUid = prefs.getString("SAVED_NFC_UID", null)

                if (savedUid == null) {
                    prefs.edit().putString("SAVED_NFC_UID", scannedUid).apply()
                    Toast.makeText(this, "تم تسجيل الكارت! المسه مرة أخرى لقفل السوشيال", Toast.LENGTH_LONG).show()
                    refreshUI()
                    return
                }

                if (scannedUid == savedUid) {
                    val isCurrentlyActive = prefs.getBoolean("IS_APP_ACTIVE", false)
                    if (!isCurrentlyActive) {
                        prefs.edit()
                            .putBoolean("IS_APP_ACTIVE", true)
                            .putBoolean("IS_SPENDING_FLAMES_MODE", false)
                            .apply()
                        StudySessionService.statusMessage = "تم قفل السوشيال بالكارت. ابدأ التركيز عندما تبدأ المذاكرة"
                        Toast.makeText(this, "تم تفعيل قفل السوشيال بالكارت", Toast.LENGTH_SHORT).show()
                    } else {
                        prefs.edit()
                            .putBoolean("IS_APP_ACTIVE", false)
                            .putBoolean("IS_SPENDING_FLAMES_MODE", false)
                            .apply()
                        if (StudySessionService.isStudying) {
                            stopService(Intent(this, StudySessionService::class.java))
                        }
                        StudySessionService.statusMessage = "تم إيقاف البرنامج بالكارت (حد الأمان 3 ساعات ما زال نشطاً)"
                        Toast.makeText(this, "تم فك قفل السوشيال بالكارت", Toast.LENGTH_SHORT).show()
                    }
                    refreshUI()
                } else {
                    Toast.makeText(this, "كارت غير مسجل! استخدم كارت الغرفة المسجل.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

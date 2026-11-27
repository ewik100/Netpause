#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path

def edit(path, old, new, count=1):
    p=Path(path); s=p.read_text()
    if old not in s: raise SystemExit(f"Expected source text not found in {path}: {old[:90]!r}")
    p.write_text(s.replace(old,new,count))

# First-run onboarding is based on actual Android grants, never a stale preference flag.
edit("app/src/main/java/ru/netpause/app/MainActivity.kt",
'''        val showTargetScreen = {
            val alreadyOpenedSetup = prefs.getBoolean("permission_center_intro_seen", false)
            if (openPermissions) {
                showPermissionCenter()
            } else if (!alreadyOpenedSetup) {
                prefs.edit().putBoolean("permission_center_intro_seen", true).apply()
                showPermissionCenter()
            } else {
                showDashboard()
            }
        }''',
'''        val showTargetScreen = {
            // OEM permission screens may close without granting access. Re-check actual state on every fresh launch.
            val setupIncomplete = !hasVpnPermission() || !hasOverlayPermission()
            if (openPermissions || setupIncomplete) showPermissionCenter() else showDashboard()
        }''')
# Hide Play Store update entry points until the app is actually published there.
edit("app/src/main/java/ru/netpause/app/MainActivity.kt",
'''        menu.menu.add(0, 8, 8, "Проверить обновления")
''',"")
edit("app/src/main/java/ru/netpause/app/MainActivity.kt",
'''                8 -> checkForAppUpdates()
''',"")
edit("app/src/main/java/ru/netpause/app/MainActivity.kt",
'''            Tile("⇧", "Обновления", "Проверить новую версию", { checkForAppUpdates() }),
''',"")
edit("app/src/main/java/ru/netpause/app/MainActivity.kt",
'''        root.addView(makeStyledButton("ПРОВЕРИТЬ ОБНОВЛЕНИЯ", ButtonKind.PRIMARY) {
            checkForAppUpdates()
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(14) })
''',"")
edit("app/src/main/java/ru/netpause/app/MainActivity.kt","        resumeInterruptedPlayUpdate()\\n","")
# More robust line metrics and wrapping for narrow screens.
edit("app/src/main/java/ru/netpause/app/MainActivity.kt",
'''            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)''',
'''            text = value
            textSize = size.coerceAtMost(25f)
            setTextColor(color)
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1.0f)
            if (Build.VERSION.SDK_INT >= 23) {
                breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NORMAL
            }
            if (bold) setTypeface(typeface, Typeface.BOLD)''')
# Use the new globe/pause mark in the app identity and floating control.
edit("app/src/main/java/ru/netpause/app/MainActivity.kt",
'''            setImageDrawable(launcherIconDrawable())
            scaleType = ImageView.ScaleType.FIT_CENTER''',
'''            setImageResource(R.drawable.netpause_brand_v2)
            scaleType = ImageView.ScaleType.FIT_CENTER''')
edit("app/src/main/java/ru/netpause/app/OverlayService.kt",
'''            setImageDrawable(packageManager.getApplicationIcon(packageName))
            scaleType = ImageView.ScaleType.FIT_CENTER''',
'''            setImageResource(R.drawable.netpause_brand_v2)
            scaleType = ImageView.ScaleType.FIT_CENTER''')
edit("app/src/main/java/ru/netpause/app/OverlayService.kt",
'''            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)''',
'''            textSize = 8f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)''')
edit("app/src/main/java/ru/netpause/app/OverlayService.kt",
'''            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(95, 220, 240))''',
'''            textSize = 8f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(95, 220, 240))''')
edit("app/src/main/java/ru/netpause/app/OverlayService.kt",
'''            setPadding(dp(3), dp(2), dp(3), dp(2))''',
'''            setPadding(dp(1), dp(1), dp(1), dp(1))''')
edit("app/src/main/java/ru/netpause/app/OverlayService.kt",
'''            val iconSize = dp((sizeDp * 0.52f).toInt())
            addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
            addView(timer, LinearLayout.LayoutParams(-1, dp(8)))
            addView(brand, LinearLayout.LayoutParams(-1, dp(7)))''',
'''            val iconSize = dp(minOf((sizeDp * 0.68f).toInt(), sizeDp - 17))
            addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
            addView(timer, LinearLayout.LayoutParams(-1, dp(7)))
            addView(brand, LinearLayout.LayoutParams(-1, dp(8)))''')
edit("app/src/main/java/ru/netpause/app/OverlayService.kt",
'''        val iconSize = dp((sizeDp * 0.52f).toInt())''',
'''        val iconSize = dp(minOf((sizeDp * 0.68f).toInt(), sizeDp - 17))''')
# App version and free mode remain unchanged.
edit("app/build.gradle.kts",'versionCode = 28','versionCode = 29')
edit("app/build.gradle.kts",'versionName = "6.0.2"','versionName = "6.1.0"')
PY

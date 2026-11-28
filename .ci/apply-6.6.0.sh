#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path

build = Path("app/build.gradle.kts")
s = build.read_text(encoding="utf-8")
if 'versionName = "6.5.0"' not in s or 'versionCode = 33' not in s:
    raise SystemExit("Unexpected Gradle version; refusing to apply NetPause 6.6.0")
s = s.replace('versionName = "6.5.0"', 'versionName = "6.6.0"', 1)
s = s.replace('versionCode = 33', 'versionCode = 34', 1)
build.write_text(s, encoding="utf-8")

p = Path("app/src/main/java/ru/netpause/app/OverlayService.kt")
s = p.read_text(encoding="utf-8")

def replace_once(old, new, label):
    global s
    if old not in s:
        raise SystemExit("Missing OverlayService anchor: " + label)
    s = s.replace(old, new, 1)

replace_once(
    "import android.graphics.PixelFormat\n",
    "import android.graphics.PixelFormat\nimport android.text.SpannableStringBuilder\nimport android.text.Spanned\nimport android.text.style.ForegroundColorSpan\nimport android.text.style.StrikethroughSpan\n",
    "ticker span imports"
)
replace_once(
    "import android.widget.EditText\n",
    "import android.widget.EditText\nimport android.widget.FrameLayout\n",
    "FrameLayout import"
)

start = s.index("    private fun createBubble() {")
end = s.index("    private fun updateBubbleTimer() {", start)
new_bubble = """    private fun createBubble() {
        val sizeDp = prefs.getInt("overlay_bubble_size_dp_v1", 58).coerceIn(44, 76)
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.netpause_emblem)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = tr("Значок NetPause")
        }
        bubbleIcon = icon

        val timer = TextView(this).apply {
            text = "OFF"
            textSize = 6.5f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(185, 200, 220))
            includeFontPadding = false
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            contentDescription = tr("Состояние блокировки")
        }
        bubbleTimer = timer

        val brand = TextView(this).apply {
            text = "NP"
            textSize = 6.5f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(95, 220, 240))
            includeFontPadding = false
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            contentDescription = tr("NetPause")
        }
        bubbleBrand = brand

        val statusStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(3), 0, dp(3), 0)
            background = GradientDrawable().apply {
                setColor(Color.argb(222, 4, 17, 38))
                cornerRadius = dp(7).toFloat()
                setStroke(dp(1), Color.argb(190, 75, 215, 235))
            }
            addView(brand, LinearLayout.LayoutParams(0, -1, 1f))
            addView(timer, LinearLayout.LayoutParams(0, -1, 1f))
        }

        val container = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(20, 64, 105), Color.rgb(7, 24, 49))
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.rgb(75, 215, 235))
            }
            clipToOutline = true
            elevation = dp(12).toFloat()
            contentDescription = tr("NetPause: открыть управление")
            setOnTouchListener(BubbleTouch())

            addView(icon, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            addView(statusStrip, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(14),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { setMargins(dp(3), 0, dp(3), dp(3)) })
        }
        bubble = container
        bubbleParams = WindowManager.LayoutParams(
            dp(sizeDp), dp(sizeDp),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = prefs.getInt("bubble_x", dp(14)).coerceAtLeast(0)
            y = prefs.getInt("bubble_y", dp(180)).coerceAtLeast(statusBarHeight() + dp(5))
        }
        try {
            wm.addView(container, bubbleParams)
            applyBubbleSettings()
        } catch (_: Exception) {
            prefs.edit().putBoolean("overlay_enabled", false).apply()
            stopSelf()
        }
    }

"""
s = s[:start] + new_bubble + s[end:]

replace_once(
"""    private fun updateBubbleTimer() {
        val left = SessionRuntime.remainingMs()
        bubbleTimer?.text = if (left > 0L) SessionLogic.formatCountdown(left) else "OFF"
        bubbleTimer?.contentDescription = if (left > 0L) {
            tr("До конца блокировки") + " " + SessionLogic.formatCountdown(left)
        } else "Блокировка выключена"
    }
""",
"""    private fun updateBubbleTimer() {
        val left = SessionRuntime.remainingMs()
        val active = left > 0L
        bubbleTimer?.text = if (active) "ON" else "OFF"
        bubbleTimer?.setTextColor(if (active) Color.rgb(105, 245, 180) else Color.rgb(185, 200, 220))
        bubbleTimer?.contentDescription = if (active) {
            tr("До конца блокировки") + " " + SessionLogic.formatCountdown(left)
        } else tr("Блокировка выключена")
    }
""",
    "ON/OFF badge"
)
replace_once(
"""        val iconSize = dp(minOf((sizeDp * 0.68f).toInt(), sizeDp - 17))
        bubbleIcon?.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
""",
"""        val iconSize = dp((sizeDp - 18).coerceAtLeast(28))
        bubbleIcon?.layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
""",
    "full-size bubble logo"
)

replace_once(
"""        val tickerContent = "МОЙ ПЛАН   •   $taskText"
        if (label.text.toString() != tickerContent) label.text = tickerContent
""",
"""        // Completed items stay visible, but their state is now obvious in the ticker.
        // Rebuild spans on every task-list update: comparing plain text would miss a
        // completion toggle because the task title itself does not change.
        val tickerContent = SpannableStringBuilder()
        val heading = "МОЙ ПЛАН   •   "
        tickerContent.append(heading)
        tickerContent.setSpan(
            ForegroundColorSpan(palette.accent), 0, tickerContent.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        FocusTaskStore.read(prefs).forEachIndexed { index, task ->
            if (index > 0) tickerContent.append("     •     ")
            val start = tickerContent.length
            val renderedTitle = if (task.completed) "✓ ${task.title}" else task.title
            val available = (700 - tickerContent.length).coerceAtLeast(0)
            val visibleTitle = renderedTitle.take(available)
            if (visibleTitle.isNotEmpty()) {
                tickerContent.append(visibleTitle)
                val end = tickerContent.length
                tickerContent.setSpan(
                    ForegroundColorSpan(if (task.completed) Color.rgb(145, 165, 185) else Color.WHITE),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (task.completed) {
                    val strikeStart = (start + 2).coerceAtMost(end)
                    if (strikeStart < end) tickerContent.setSpan(
                        StrikethroughSpan(), strikeStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }
        label.text = tickerContent
""",
    "completed task visual state"
)
p.write_text(s, encoding="utf-8")

launcher = Path("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml")
s = launcher.read_text(encoding="utf-8")
if '@drawable/netpause_brand_v2' not in s:
    raise SystemExit("Adaptive launcher icon anchor missing")
launcher.write_text(s.replace(
    '@drawable/netpause_brand_v2',
    '@drawable/netpause_launcher_foreground', 1
), encoding="utf-8")

Path("app/src/main/res/drawable/netpause_emblem.xml").write_text(
'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#06152F" android:pathData="M54,4 A50,50 0,1 0,54 104 A50,50 0,1 0,54 4" />
    <path android:fillColor="#00000000" android:strokeColor="#35E5F4" android:strokeWidth="3.5"
        android:pathData="M54,7 A47,47 0,1 0,54 101 A47,47 0,1 0,54 7" />
    <path android:fillColor="#00000000" android:strokeColor="#168BDF" android:strokeWidth="2"
        android:pathData="M19,38 Q54,5 89,38 M24,46 Q54,19 84,46" />
    <path android:fillColor="#102E59" android:strokeColor="#54E9F5" android:strokeWidth="2.5"
        android:pathData="M54,31 L80,42 L77,67 Q72,84 54,94 Q36,84 31,67 L28,42 Z" />
    <path android:fillColor="#59F0F7" android:pathData="M42,48 L50,48 L50,76 L42,76 Z M58,48 L66,48 L66,76 L58,76 Z" />
    <path android:fillColor="#00000000" android:strokeColor="#A7F8FF" android:strokeWidth="2"
        android:pathData="M16,72 Q20,83 30,89 M92,72 Q88,83 78,89" />
</vector>
''',
    encoding="utf-8"
)

Path("app/src/main/res/drawable/netpause_launcher_foreground.xml").write_text(
'''<?xml version="1.0" encoding="utf-8"?>
<inset xmlns:android="http://schemas.android.com/apk/res/android"
    android:drawable="@drawable/netpause_emblem"
    android:insetLeft="18dp"
    android:insetTop="18dp"
    android:insetRight="18dp"
    android:insetBottom="18dp" />
''',
    encoding="utf-8"
)

test = Path("app/src/test/java/ru/netpause/app/TaskPlannerLogicTest.kt")
s = test.read_text(encoding="utf-8")
anchor = """    @Test
    fun tasksCanBeEditedAndReorderedWithoutChangingTheirIdsOrCompletionFlags() {"""
if anchor not in s:
    raise SystemExit("Task planner test anchor missing")
addition = """    @Test
    fun completingATaskKeepsItsTitleInTheTickerAndPersistsItsCheckedState() {
        val source = listOf(
            FocusTaskEntry("task-0001", "Математика", false),
            FocusTaskEntry("task-0002", "История", false)
        )
        val completed = source.map { if (it.id == "task-0001") it.copy(completed = true) else it }
        assertEquals("Математика     •     История", FocusTaskStore.activeTickerText(completed))
        assertEquals(completed, FocusTaskStore.decode(FocusTaskStore.encode(completed)))
    }

"""
s = s.replace(anchor, addition + anchor, 1)
test.write_text(s, encoding="utf-8")
PY

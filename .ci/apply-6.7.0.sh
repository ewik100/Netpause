#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

def replace_once(path, old, new, label):
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit("Missing anchor: " + label)
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

build = Path("app/build.gradle.kts")
s = build.read_text(encoding="utf-8")
if 'versionName = "6.6.0"' not in s or 'versionCode = 34' not in s:
    raise SystemExit("Unexpected Gradle version; refusing to apply NetPause 6.7.0")
s = s.replace('versionName = "6.6.0"', 'versionName = "6.7.0"', 1)
s = s.replace('versionCode = 34', 'versionCode = 35', 1)
build.write_text(s, encoding="utf-8")

main = Path("app/src/main/java/ru/netpause/app/MainActivity.kt")
s = main.read_text(encoding="utf-8")

# Use exactly one consistent emblem in the home header and About screen.
replace_once(main, 'setImageResource(R.drawable.netpause_brand_v2)',
             'setImageResource(R.drawable.netpause_emblem)', "MainActivity brand image")
replace_once(main, 'cornerRadius = dp(14).toFloat()\n                setStroke(dp(1), Color.rgb(72, 179, 245))',
             'shape = GradientDrawable.OVAL\n                setStroke(dp(1), Color.rgb(72, 179, 245))',
             "circular brand frame")

# Reuse the same artwork on the animated splash rather than drawing a different NP mark.
replace_once(main, '        private val ring = RectF()',
             '        private val ring = RectF()\n        private val brandDrawable: android.graphics.drawable.Drawable? = this@MainActivity.getDrawable(R.drawable.netpause_emblem)',
             "splash emblem drawable")
start = s.index("            // Pulse mark and NP lettering reveal in two deliberate stages.")
end = s.index("            val glowAngle", start)
s = s[:start] + """            // The same round brand symbol is used by the launcher and floating control.
            val emblemAlpha = (255 * smooth((progress - 0.12f) / 0.30f)).toInt()
            val emblemSize = radius * 1.54f
            brandDrawable?.let { drawable ->
                val half = emblemSize / 2f
                drawable.alpha = emblemAlpha
                drawable.setBounds(
                    (cx - half).toInt(), (cy - half).toInt(),
                    (cx + half).toInt(), (cy + half).toInt()
                )
                drawable.draw(canvas)
                drawable.alpha = 255
            }

""" + s[end:]
main.write_text(s, encoding="utf-8")

# Task completion is visually explicit both in the list and in the overlay ticker.
s = main.read_text(encoding="utf-8")
old_count = 'val activeCountLabel = makeText("' + chr(36) + '{tasks.count { !it.completed }} активных",'
if old_count not in s:
    raise SystemExit("Missing initial active-task counter")
s = s.replace(old_count,
              'val activeCountLabel = makeText(FocusTaskStore.activeCountLabel(tasks.count { !it.completed }),',
              1)
old_update = 'activeCountLabel.text = "' + chr(36) + '{updated.count { !it.completed }} активных"'
if old_update not in s:
    raise SystemExit("Missing live active-task counter")
s = s.replace(old_update,
              'activeCountLabel.text = FocusTaskStore.activeCountLabel(updated.count { !it.completed })',
              1)

task_anchor = '            tasks.forEach { task ->\n                val row = LinearLayout(this).apply {'
task_insert = """            tasks.forEach { task ->
                val taskLabel: (Boolean) -> CharSequence = { completed ->
                    android.text.SpannableString(task.title).apply {
                        if (completed && task.title.isNotEmpty()) {
                            setSpan(
                                android.text.style.ForegroundColorSpan(Color.rgb(130, 155, 176)),
                                0, task.title.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            setSpan(
                                android.text.style.StrikethroughSpan(),
                                0, task.title.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                    }
                }
                val row = LinearLayout(this).apply {"""
if task_anchor not in s:
    raise SystemExit("Missing task row anchor")
s = s.replace(task_anchor, task_insert, 1)
replace_line = '                    text = task.title\n                    textSize = 12f'
if replace_line not in s:
    raise SystemExit("Missing checkbox title assignment")
s = s.replace(replace_line, '                    text = taskLabel(task.completed)\n                    textSize = 12f', 1)
old_color = '                        button.setTextColor(if (checked) Color.rgb(130, 155, 176) else Color.rgb(230, 239, 248))'
if old_color not in s:
    raise SystemExit("Missing checkbox completion color update")
s = s.replace(old_color,
              '                        button.text = taskLabel(checked)\n' + old_color,
              1)
main.write_text(s, encoding="utf-8")

planner = Path("app/src/main/java/ru/netpause/app/TaskPlanner.kt")
s = planner.read_text(encoding="utf-8")
anchor = '    private fun save(prefs: SharedPreferences, tasks: List<FocusTaskEntry>) {'
addition = """    fun tickerLabel(task: FocusTaskEntry): String =
        if (task.completed) "✓ " + task.title else task.title

    /** Russian plural form for the active-task counter. */
    fun activeCountLabel(count: Int): String {
        val n = count.coerceAtLeast(0)
        val lastTwo = n % 100
        val word = when {
            lastTwo in 11..14 -> "активных"
            n % 10 == 1 -> "активная"
            n % 10 in 2..4 -> "активные"
            else -> "активных"
        }
        return "$n $word"
    }

"""
if anchor not in s:
    raise SystemExit("Missing TaskPlanner save anchor")
s = s.replace(anchor, addition + anchor, 1)
planner.write_text(s, encoding="utf-8")

overlay = Path("app/src/main/java/ru/netpause/app/OverlayService.kt")
s = overlay.read_text(encoding="utf-8")
old_size = 'val iconSize = dp((sizeDp - 18).coerceAtLeast(28))\n        bubbleIcon?.layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)'
new_size = 'val iconSize = dp((sizeDp - 22).coerceAtLeast(22))\n        bubbleIcon?.layoutParams = FrameLayout.LayoutParams(\n            iconSize, iconSize, Gravity.TOP or Gravity.CENTER_HORIZONTAL\n        ).apply { topMargin = dp(3) }'
if old_size not in s:
    raise SystemExit("Missing floating icon sizing anchor")
s = s.replace(old_size, new_size, 1)
old_render = 'val renderedTitle = if (task.completed) "✓ ' + chr(36) + '{task.title}" else task.title'
if old_render not in s:
    raise SystemExit("Missing overlay ticker title anchor")
s = s.replace(old_render, 'val renderedTitle = FocusTaskStore.tickerLabel(task)', 1)
# Keep TalkBack state synchronized with the visible state indicator.
old_desc = '''            b.contentDescription = if (active) {
                "NetPause: блокировка активна. Нажми, чтобы открыть управление."
            } else {
                "NetPause: блокировка выключена. Нажми, чтобы открыть управление."
            }'''
new_desc = '''            b.contentDescription = if (active) {
                tr("NetPause: блокировка активна. Нажми, чтобы открыть управление.")
            } else {
                tr("NetPause: блокировка выключена. Нажми, чтобы открыть управление.")
            }'''
if old_desc in s:
    s = s.replace(old_desc, new_desc, 1)
overlay.write_text(s, encoding="utf-8")

# Use NetPause's own compact monochrome mark in VPN/session notifications too.
vpn = Path("app/src/main/java/ru/netpause/app/BlockVpnService.kt")
s = vpn.read_text(encoding="utf-8")
old_icon = '.setSmallIcon(android.R.drawable.ic_lock_lock)'
if old_icon not in s:
    raise SystemExit("Missing generic VPN notification icon")
s = s.replace(old_icon, '.setSmallIcon(R.drawable.ic_stat_netpause)')
vpn.write_text(s, encoding="utf-8")

# The app no longer requests a Play-policy-sensitive battery exemption. Users can
# still open Android's ordinary battery settings themselves when they need to.
manifest = Path("app/src/main/AndroidManifest.xml")
s = manifest.read_text(encoding="utf-8")
s = s.replace('    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />\n', '')
manifest.write_text(s, encoding="utf-8")

# Replace the battery-exemption action robustly, preserving the prior negative action.
s = main.read_text(encoding="utf-8")
start = s.find('.setNeutralButton("Открыть настройки батареи")')
if start < 0:
    raise SystemExit("Missing battery settings dialog anchor")
show = s.find(".show()", start)
if show < 0:
    raise SystemExit("Missing battery settings dialog terminator")
line_start = s.rfind("\n", 0, start) + 1
indent = s[line_start:start]
replacement = (
    indent + '.setPositiveButton("Открыть настройки батареи") { _, _ ->\n'
    + indent + '    openManualBatterySettings()\n'
    + indent + '}\n'
    + indent + '.show()'
)
s = s[:line_start] + replacement + s[show + len(".show()"):]
main.write_text(s, encoding="utf-8")

# Respect API availability and avoid an API-29 constant under an API-23 guard.
s = main.read_text(encoding="utf-8")
old_break = '''            if (Build.VERSION.SDK_INT >= 23) {
                breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NORMAL
            }'''
new_break = '''            if (Build.VERSION.SDK_INT >= 29) {
                breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
            }
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NORMAL'''
if old_break not in s:
    raise SystemExit("Missing text layout API guard")
s = s.replace(old_break, new_break, 1)
# An unknown Play update state is non-fatal and does not change application state.
old_install = '            InstallStatus.CANCELED -> unregisterPlayUpdateListener()\n        }'
if old_install in s:
    s = s.replace(old_install,
                  '            InstallStatus.CANCELED -> unregisterPlayUpdateListener()\n            else -> Unit\n        }', 1)
main.write_text(s, encoding="utf-8")

# Android 13+ themed icons use a single-color silhouette.
launcher = Path("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml")
s = launcher.read_text(encoding="utf-8")
if '<monochrome ' not in s:
    s = s.replace('</adaptive-icon>',
                  '    <monochrome android:drawable="@drawable/netpause_monochrome" />\n</adaptive-icon>')
launcher.write_text(s, encoding="utf-8")
Path("app/src/main/res/drawable/netpause_monochrome.xml").write_text(
'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#00000000" android:strokeColor="#FFFFFFFF"
        android:strokeWidth="6" android:pathData="M54,12 A42,42 0,1 0,54 96 A42,42 0,1 0,54 12" />
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M37,34 L47,34 L47,74 L37,74 Z M61,34 L71,34 L71,74 L61,74 Z" />
</vector>
''', encoding="utf-8")

# Add focused regression coverage for visible completed-task labels and grammar.
test = Path("app/src/test/java/ru/netpause/app/TaskPlannerLogicTest.kt")
s = test.read_text(encoding="utf-8")
anchor = '    @Test\n    fun tasksCanBeEditedAndReorderedWithoutChangingTheirIdsOrCompletionFlags() {'
test_add = """    @Test
    fun tickerLabelMarksCompletedTasksWithoutHidingTheirNames() {
        assertEquals("✓ История", FocusTaskStore.tickerLabel(
            FocusTaskEntry("task-0001", "История", true)
        ))
        assertEquals("Математика", FocusTaskStore.tickerLabel(
            FocusTaskEntry("task-0002", "Математика", false)
        ))
    }

    @Test
    fun activeTaskCounterUsesCorrectRussianPluralForms() {
        assertEquals("0 активных", FocusTaskStore.activeCountLabel(0))
        assertEquals("1 активная", FocusTaskStore.activeCountLabel(1))
        assertEquals("2 активные", FocusTaskStore.activeCountLabel(2))
        assertEquals("5 активных", FocusTaskStore.activeCountLabel(5))
        assertEquals("11 активных", FocusTaskStore.activeCountLabel(11))
        assertEquals("21 активная", FocusTaskStore.activeCountLabel(21))
    }

"""
if anchor not in s:
    raise SystemExit("Missing TaskPlanner test insertion point")
s = s.replace(anchor, test_add + anchor, 1)
test.write_text(s, encoding="utf-8")
PY

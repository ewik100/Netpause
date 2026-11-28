#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path
p=Path("app/src/main/java/ru/netpause/app/MainActivity.kt")
s=p.read_text()
def edit(old,new):
 global s
 if old not in s: raise SystemExit("Missing expected MainActivity anchor: "+old[:70])
 s=s.replace(old,new,1)
edit('    private val prefs by lazy { getSharedPreferences("netpause_state", MODE_PRIVATE) }',
'''    private val prefs by lazy { getSharedPreferences("netpause_state", MODE_PRIVATE) }
    private val uiLanguage: String get() = AppLanguage.normalize(prefs.getString(AppLanguage.PREF_KEY, AppLanguage.RU))
    private fun tr(value: String): String = AppLanguage.translate(value, uiLanguage)''')
edit('''    private fun makeText(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value''','''    private fun makeText(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = tr(value)''')
edit('''        return Button(this).apply {
            text = value''','''        return Button(this).apply {
            text = tr(value)''')
edit('''        menu.menu.add(0, 1, 1, "Главный экран")
        menu.menu.add(0, 2, 2, "Приложения")
        menu.menu.add(0, 3, 3, "Статистика")
        menu.menu.add(0, 4, 4, "Внешний вид")
        menu.menu.add(0, 5, 5, "Разрешения")
        menu.menu.add(0, 6, 6, "Резервная копия")
        menu.menu.add(0, 7, 7, "О NetPause")
        menu.menu.add(0, 9, 9, "Полностью выключить")''','''        menu.menu.add(0, 1, 1, tr("Главный экран"))
        menu.menu.add(0, 2, 2, tr("Приложения"))
        menu.menu.add(0, 3, 3, tr("Статистика"))
        menu.menu.add(0, 4, 4, tr("Внешний вид"))
        menu.menu.add(0, 5, 5, tr("Разрешения"))
        menu.menu.add(0, 6, 6, tr("Резервная копия"))
        menu.menu.add(0, 7, 7, tr("О NetPause"))
        menu.menu.add(0, 9, 9, tr("Полностью выключить"))
        menu.menu.add(0, 10, 10, tr(if (uiLanguage == AppLanguage.RU) "Язык: English" else "Язык: Русский"))''')
edit('''                9 -> confirmShutdown()
            }''','''                9 -> confirmShutdown()
                10 -> {
                    prefs.edit().putString(AppLanguage.PREF_KEY, AppLanguage.toggle(uiLanguage)).apply()
                    showDashboard()
                }
            }''')
edit('it.text = if (error.isBlank()) "ГОТОВО" else "ОШИБКА"','it.text = tr(if (error.isBlank()) "ГОТОВО" else "ОШИБКА")')
p.write_text(s)
g=Path("app/build.gradle.kts"); s=g.read_text().replace('versionCode = 29','versionCode = 30').replace('versionName = "6.1.0"','versionName = "6.2.0"'); g.write_text(s)
PY

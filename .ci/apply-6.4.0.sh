#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path
import re

lang = Path("app/src/main/java/ru/netpause/app/AppLanguage.kt")
s = lang.read_text(encoding="utf-8")
anchor = '        "Язык: Русский" to "Language: Russian",'
if anchor not in s:
    raise SystemExit("Missing AppLanguage 6.3 anchor")
entries = [
    ("NetPause работает", "NetPause is running"),
    ("Плавающая кнопка доступна поверх приложений", "The floating button is available over other apps"),
    ("Выключить NetPause", "Turn off NetPause"),
    ("Значок NetPause", "NetPause icon"),
    ("Обратный отсчёт NetPause", "NetPause countdown"),
    ("NetPause: открыть управление", "NetPause: open controls"),
    ("До конца блокировки", "Blocking ends in"),
    ("Блокировка выключена", "Blocking is off"),
    ("Список текущих целей NetPause", "Current NetPause goals"),
    ("Открой NetPause и перейди в «Разрешения и производительность».", "Open NetPause and go to Permissions and performance."),
    ("БЫСТРЫЙ ЗАПУСК", "QUICK START"),
    ("Укажи длительность и нажми «Начать».", "Set a duration and tap Start."),
    ("Интернет останется у", "Internet access will remain available to"),
    ("выбранных приложений. У остальных доступ к сети будет ограничен.", "selected apps. Network access will be restricted for all others."),
    ("ВЫБРАТЬ ПРИЛОЖЕНИЯ С ИНТЕРНЕТОМ", "CHOOSE APPS WITH INTERNET"),
    ("Открой NetPause для выбора приложений", "Open NetPause to choose apps"),
    ("На сколько минут ограничить интернет?", "For how many minutes should internet be limited?"),
    ("1–120 минут", "1–120 minutes"),
    ("НАЧАТЬ БЛОКИРОВКУ", "START BLOCKING"),
    ("Укажи от 1 до 120 минут", "Enter a duration from 1 to 120 minutes"),
    ("Блокировка уже запущена. Сначала останови текущий сеанс.", "Blocking is already active. Stop the current session first."),
    ("Сначала разреши VPN в центре разрешений. Само окно VPN не включает.", "Allow VPN in the permissions center first. Opening the VPN dialog does not enable it."),
    ("Запускаю блокировку на", "Starting blocking for"),
    ("мин.", "min."),
    ("Не удалось запустить VPN. Проверь разрешение.", "Could not start VPN. Check the permission."),
    ("ОСТАНОВИТЬ БЛОКИРОВКУ", "STOP BLOCKING"),
    ("Останавливаю VPN. Плавающая кнопка останется на экране.", "Stopping VPN. The floating button will remain on screen."),
    ("Исключения продолжат работать онлайн. Остальные приложения будут без интернета до конца таймера. Максимум — 120 минут.", "Allowed apps will stay online. All other apps will be offline until the timer ends. Maximum duration: 120 minutes."),
    ("СКРЫТЬ ПЛАВАЮЩУЮ КНОПКУ", "HIDE FLOATING BUTTON"),
    ("ПОЛНОСТЬЮ ВЫКЛЮЧИТЬ NETPAUSE", "TURN OFF NETPAUSE COMPLETELY"),
    ("ДОПОЛНИТЕЛЬНО  ▾", "MORE  ▾"),
    ("Не удалось открыть окно поверх приложений", "Could not open the overlay window"),
    ("● БЛОКИРОВКА АКТИВНА", "● BLOCKING ACTIVE"),
    ("○ БЛОКИРОВКА ВЫКЛЮЧЕНА", "○ BLOCKING OFF"),
    ("NetPause: блокировка активна. Нажми, чтобы открыть управление.", "NetPause: blocking is active. Tap to open controls."),
    ("NetPause: блокировка выключена. Нажми, чтобы открыть управление.", "NetPause: blocking is off. Tap to open controls."),
    ("Напоминания NetPause", "NetPause reminders"),
    ("NetPause — скоро завершение", "NetPause — ending soon"),
    ("До возвращения интернета осталось", "Internet access returns in"),
    ("Завершение сессии", "Session finished"),
    ("NetPause — сессия завершена", "NetPause — session completed"),
    ("Интернет возвращён всем приложениям.", "Internet access has been restored for all apps."),
    ("Остановить", "Stop"),
    ("NetPause — блокировка активна", "NetPause — blocking is active"),
    ("Закончится в", "Ends at"),
    ("Нажми «Остановить», чтобы вернуть интернет.", "Tap Stop to restore internet access."),
    ("Запуск блокировки…", "Starting blocking…"),
    ("Блокировка интернета", "Internet blocking"),
]
for ru, en in entries:
    if f'"{ru}" to ' not in s:
        s = s.replace(anchor, anchor + '\n' + f'        "{ru}" to "{en}",', 1)
        anchor = f'        "{ru}" to "{en}",'
lang.write_text(s, encoding="utf-8")

overlay = Path("app/src/main/java/ru/netpause/app/OverlayService.kt")
s = overlay.read_text(encoding="utf-8")
pref_anchor = '    private val prefs by lazy { getSharedPreferences("netpause_state", MODE_PRIVATE) }'
if pref_anchor not in s: raise SystemExit("OverlayService prefs anchor missing")
s = s.replace(pref_anchor, pref_anchor + '''
    private fun tr(value: String): String =
        AppLanguage.translate(value, AppLanguage.normalize(prefs.getString(AppLanguage.PREF_KEY, AppLanguage.RU)))''', 1)
s = s.replace('''        TextView(this).apply {
            text = value
            textSize = size''','''        TextView(this).apply {
            text = tr(value)
            textSize = size''', 1)
# Translate static literals assigned directly to widgets and accessibility labels.
for prop in ("text", "contentDescription", "hint"):
    s = re.sub(rf'(?m)(\b{prop}\s*=\s*)"([^"\\]*(?:\\.[^"\\]*)*)"', lambda m: m.group(1) + 'tr("' + m.group(2) + '")', s)
# Translate static notifications and actions.
s = s.replace('.setContentTitle("NetPause работает")', '.setContentTitle(tr("NetPause работает"))')
s = s.replace('.setContentText("Плавающая кнопка доступна поверх приложений")', '.setContentText(tr("Плавающая кнопка доступна поверх приложений"))')
s = s.replace('"Выключить NetPause", shutdown', 'tr("Выключить NetPause"), shutdown')
# Literal Toast messages.
s = re.sub(r'Toast\.makeText\((this(?:@OverlayService)?),\s*"([^"\\]*(?:\\.[^"\\]*)*)",',
           lambda m: f'Toast.makeText({m.group(1)}, tr("{m.group(2)}"),', s)
s = s.replace('"До конца блокировки ${SessionLogic.formatCountdown(left)}"', 'tr("До конца блокировки") + " " + SessionLogic.formatCountdown(left)')
s = s.replace('''            "● БЛОКИРОВКА АКТИВНА  " + (total / 60) + ":" + (total % 60).toString().padStart(2, '0')''',
              '''            tr("● БЛОКИРОВКА АКТИВНА") + "  " + (total / 60) + ":" + (total % 60).toString().padStart(2, '0')''')
s = s.replace('"○ БЛОКИРОВКА ВЫКЛЮЧЕНА" else "⚠ $error"',
              'tr("○ БЛОКИРОВКА ВЫКЛЮЧЕНА") else tr("⚠") + " $error"')
overlay.write_text(s, encoding="utf-8")

vpn = Path("app/src/main/java/ru/netpause/app/BlockVpnService.kt")
s = vpn.read_text(encoding="utf-8")
pref_anchor = '    private val prefs by lazy { SessionStore.prefs(this) }'
if pref_anchor not in s: raise SystemExit("BlockVpnService prefs anchor missing")
s = s.replace(pref_anchor, pref_anchor + '''
    private fun tr(value: String): String =
        AppLanguage.translate(value, AppLanguage.normalize(prefs.getString(AppLanguage.PREF_KEY, AppLanguage.RU)))''', 1)
replacements = {
    'NotificationChannel(WARNING_CHANNEL_ID, "Напоминания NetPause",': 'NotificationChannel(WARNING_CHANNEL_ID, tr("Напоминания NetPause"),',
    '.setContentTitle("NetPause — скоро завершение")': '.setContentTitle(tr("NetPause — скоро завершение"))',
    '.setContentText("До возвращения интернета осталось ${SessionLogic.formatCountdown(remaining)}.")': '.setContentText(tr("До возвращения интернета осталось") + " " + SessionLogic.formatCountdown(remaining) + ".")',
    'NotificationChannel(FINISHED_CHANNEL_ID, "Завершение сессии",': 'NotificationChannel(FINISHED_CHANNEL_ID, tr("Завершение сессии"),',
    '.setContentTitle("NetPause — сессия завершена")': '.setContentTitle(tr("NetPause — сессия завершена"))',
    '.setContentText("Интернет возвращён всем приложениям.")': '.setContentText(tr("Интернет возвращён всем приложениям."))',
    '.addAction(android.R.drawable.ic_media_pause, "Остановить", stop)': '.addAction(android.R.drawable.ic_media_pause, tr("Остановить"), stop)',
    '.setContentTitle("NetPause — блокировка активна")': '.setContentTitle(tr("NetPause — блокировка активна"))',
    '.setContentText("Закончится в $endText. Нажми «Остановить», чтобы вернуть интернет.")': '.setContentText(tr("Закончится в") + " $endText. " + tr("Нажми «Остановить», чтобы вернуть интернет."))',
    '.setContentText("Запуск блокировки…")': '.setContentText(tr("Запуск блокировки…"))',
    'NotificationChannel(CHANNEL_ID, "Блокировка интернета",': 'NotificationChannel(CHANNEL_ID, tr("Блокировка интернета"),',
}
for old, new in replacements.items():
    if old not in s: raise SystemExit("Missing BlockVpnService anchor: " + old)
    s = s.replace(old, new, 1)
vpn.write_text(s, encoding="utf-8")

test = Path("app/src/test/java/ru/netpause/app/AppLanguageTest.kt")
t = test.read_text(encoding="utf-8")
if 'overlayAndNotificationStringsAreTranslated' not in t:
    t = t.replace('''    @Test fun languageToggleIsDeterministic() {''', '''    @Test fun overlayAndNotificationStringsAreTranslated() {
        assertEquals("START BLOCKING", AppLanguage.translate("НАЧАТЬ БЛОКИРОВКУ", AppLanguage.EN))
        assertEquals("NetPause — blocking is active", AppLanguage.translate("NetPause — блокировка активна", AppLanguage.EN))
        assertEquals("Моя задача", AppLanguage.translate("Моя задача", AppLanguage.EN))
    }
    @Test fun languageToggleIsDeterministic() {''')
    test.write_text(t, encoding="utf-8")

g = Path("app/build.gradle.kts")
build = g.read_text(encoding="utf-8")
if 'versionName = "6.3.0"' not in build or 'versionCode = 31' not in build:
    raise SystemExit("Unexpected version; refusing to overwrite")
build = build.replace('versionCode = 31', 'versionCode = 32', 1)
build = build.replace('versionName = "6.3.0"', 'versionName = "6.4.0"', 1)
g.write_text(build, encoding="utf-8")
PY

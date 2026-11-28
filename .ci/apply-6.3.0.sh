#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path
import re

lang = Path("app/src/main/java/ru/netpause/app/AppLanguage.kt")
s = lang.read_text(encoding="utf-8")
anchor = '        "Полностью выключить NetPause?" to "Turn off NetPause completely?",'
if anchor not in s:
    raise SystemExit("Missing AppLanguage 6.2 anchor")
entries = [
    ('Разрешения и работа в фоне', 'Permissions and background operation'),
    ('Каждый доступ настраивается отдельно. Уже выданные разрешения повторно не запрашиваются. Само разрешение VPN не подключает телефон к VPN — соединение появляется только на время включённой блокировки.', 'Each permission is configured separately. Granted permissions are not requested again. VPN permission alone does not connect your phone to a VPN; the connection exists only while blocking is active.'),
    ('РАЗРЕШЁН', 'ALLOWED'),
    ('НУЖНО РАЗРЕШИТЬ', 'PERMISSION REQUIRED'),
    ('Плавающее окно:', 'Floating window:'),
    ('разрешено', 'allowed'),
    ('не обязательно · выключено', 'optional · off'),
    ('Дополнительные настройки:', 'Additional settings:'),
    ('Плавающее окно', 'Floating window'),
    ('Нужно, чтобы кнопка NetPause отображалась поверх других приложений. Без этого можно пользоваться приложением из обычного окна.', 'Allows the NetPause button to appear over other apps. The app can still be used normally without it.'),
    ('РАЗРЕШИТЬ ОТОБРАЖЕНИЕ', 'ALLOW DISPLAY'),
    ('Разрешение локального VPN', 'Local VPN permission'),
    ('Обязательное разрешение Android для фильтрации трафика. Оно не запускает соединение и не включает VPN само по себе.', 'Required Android permission for traffic filtering. It does not start a connection or enable the VPN by itself.'),
    ('ПРИЛОЖЕНИЯ С ИНТЕРНЕТОМ', 'APPS WITH INTERNET ACCESS'),
    ('Выбери приложения, которым нужно оставить интернет во время блокировки. Список сохраняется постоянно — можно менять его когда угодно.', 'Choose apps that should keep internet access while blocking is active. The list is saved and can be changed at any time.'),
    ('Поиск приложения или пакета…', 'Search apps or package name…'),
    ('Выбрано:', 'Selected:'),
    ('Не удалось получить список приложений.', 'Could not load the app list.'),
    ('СОХРАНИТЬ СПИСОК', 'SAVE LIST'),
    ('Список сохранён и останется после перезапуска.', 'List saved and will remain after restart.'),
    ('Новые правила применятся при следующем запуске.', 'New rules will apply the next time blocking starts.'),
    ('Открой Android → Сеть и интернет → VPN.', 'Open Android → Network & internet → VPN.'),
    ('VPN: ', 'VPN: '),
    ('СЕАНС ЗАВЕРШЁН', 'SESSION ENDED'),
    ('БЛОКИРОВКА ОСТАНОВЛЕНА', 'BLOCKING STOPPED'),
    ('Нет активной блокировки', 'No active blocking session'),
    ('Осталось времени:', 'Time remaining:'),
    ('минут', 'minutes'),
    ('Язык: Русский', 'Language: Russian'),
]
for ru, en in entries:
    if f'"{ru}" to ' not in s:
        s = s.replace(anchor, anchor + '\n' + f'        "{ru}" to "{en}",', 1)
        anchor = f'        "{ru}" to "{en}",'
lang.write_text(s, encoding="utf-8")

# Translate literal Toast messages consistently; dynamic/user-provided text is left intact.
for p in Path("app/src/main/java/ru/netpause/app").glob("*.kt"):
    src = p.read_text(encoding="utf-8")
    src2 = re.sub(
        r'Toast\.makeText\((this|applicationContext),\s*"([^"\\\\]*(?:\\\\.[^"\\\\]*)*)",',
        lambda m: f'Toast.makeText({m.group(1)}, tr("{m.group(2)}"),',
        src
    ) if p.name == "MainActivity.kt" else src
    if src2 != src:
        p.write_text(src2, encoding="utf-8")

g = Path("app/build.gradle.kts")
build = g.read_text(encoding="utf-8")
if 'versionName = "6.2.0"' not in build or 'versionCode = 30' not in build:
    raise SystemExit("Unexpected version; refusing to overwrite")
build = build.replace('versionCode = 30', 'versionCode = 31', 1)
build = build.replace('versionName = "6.2.0"', 'versionName = "6.3.0"', 1)
g.write_text(build, encoding="utf-8")
PY

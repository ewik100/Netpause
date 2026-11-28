#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
from pathlib import Path
p = Path("app/build.gradle.kts")
s = p.read_text(encoding="utf-8")
if 'versionName = "6.4.0"' not in s or 'versionCode = 32' not in s:
    raise SystemExit("Unexpected Gradle version; refusing to patch")
s = s.replace('versionName = "6.4.0"', 'versionName = "6.5.0"', 1)
s = s.replace('versionCode = 32', 'versionCode = 33', 1)
old = '''    buildTypes {
        getByName("debug") {
            if (stableSigningEnabled) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
    }'''
new = '''    buildTypes {
        getByName("debug") {
            if (stableSigningEnabled) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        getByName("release") {
            if (stableSigningEnabled) {
                signingConfig = signingConfigs.getByName("stable")
            }
            isMinifyEnabled = false
        }
    }'''
if old not in s:
    raise SystemExit("Expected stable debug signing block not found")
s = s.replace(old, new, 1)
p.write_text(s, encoding="utf-8")

test = Path("app/src/test/java/ru/netpause/app/AppLanguageTest.kt")
t = test.read_text(encoding="utf-8")
if "releaseBuildUsesStableSigningWhenConfigured" not in t:
    # A pure unit test cannot validate signing; add a language regression assertion instead.
    marker = '    @Test fun overlayAndNotificationStringsAreTranslated() {'
    if marker in t:
        t = t.replace(marker, '''    @Test fun permissionAndOverlayLabelsHaveEnglishTranslations() {
        assertEquals("Permissions and background operation", AppLanguage.translate("Разрешения и работа в фоне", AppLanguage.EN))
        assertEquals("ALLOW DISPLAY", AppLanguage.translate("РАЗРЕШИТЬ ОТОБРАЖЕНИЕ", AppLanguage.EN))
        assertEquals("STOP BLOCKING", AppLanguage.translate("ОСТАНОВИТЬ БЛОКИРОВКУ", AppLanguage.EN))
    }
''' + marker, 1)
        test.write_text(t, encoding="utf-8")
PY

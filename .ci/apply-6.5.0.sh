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

plugin_end = '''plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}'''
if plugin_end not in s:
    raise SystemExit("Gradle plugins block not found")
signing_vars = plugin_end + '''

val stableKeystorePath = System.getenv("NETPAUSE_KEYSTORE_FILE")
val stableStorePassword = System.getenv("NETPAUSE_KEYSTORE_PASSWORD")
val stableKeyAlias = System.getenv("NETPAUSE_KEY_ALIAS") ?: "netpause"
val stableKeyPassword = System.getenv("NETPAUSE_KEY_PASSWORD") ?: stableStorePassword
val stableSigningEnabled =
    !stableKeystorePath.isNullOrBlank() &&
    !stableStorePassword.isNullOrBlank() &&
    !stableKeyPassword.isNullOrBlank() &&
    file(stableKeystorePath).exists()
'''
s = s.replace(plugin_end, signing_vars, 1)

default_end = '''        versionName = "6.5.0"
    }'''
if default_end not in s:
    raise SystemExit("Gradle defaultConfig block not found")
s = s.replace(default_end, default_end + '''

    signingConfigs {
        if (stableSigningEnabled) {
            create("stable") {
                storeFile = file(stableKeystorePath!!)
                storeType = "PKCS12"
                storePassword = stableStorePassword!!
                keyAlias = stableKeyAlias
                keyPassword = stableKeyPassword!!
            }
        }
    }''', 1)

old = '''    buildTypes {
        getByName("debug")
        getByName("release") {
            isMinifyEnabled = false
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
    raise SystemExit("Gradle buildTypes block not found")
s = s.replace(old, new, 1)
p.write_text(s, encoding="utf-8")
PY

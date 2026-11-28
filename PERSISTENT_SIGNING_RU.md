# Постоянная подпись NetPause (RU)

Все обновления Android должны быть подписаны одним и тем же приватным ключом. Не теряйте keystore и пароль: восстановить приватный ключ из APK нельзя.

## Настройка GitHub Actions

Откройте репозиторий `ewik100/Netpause` → Settings → Secrets and variables → Actions и создайте Repository secrets:

- `NETPAUSE_KEYSTORE_BASE64` — полное содержимое файла `KEYSTORE_BASE64.txt` (одна длинная строка).
- `NETPAUSE_KEYSTORE_PASSWORD` — содержимое `KEYSTORE_PASSWORD.txt`.
- `NETPAUSE_KEY_ALIAS` — `netpause`.
- `NETPAUSE_KEY_PASSWORD` — тот же пароль, если пароль ключа не отличается от пароля хранилища.

Не добавляйте ключ, пароль или base64 в Git, Issues, Pull Requests или публичные сообщения. После сохранения secrets запустите Actions → NetPause Android CI → Run workflow. Сборка использует постоянный ключ для debug и release APK. Release APK будет опубликован как artifact `NetPause-6.5.0-release-apk`.

Если приложение уже публиковалось в Google Play с другим ключом, нельзя заменять его этим новым ключом — потребуется исходный ключ или настроенный Play App Signing.

Отпечаток сертификата SHA-256: `1F:B0:C5:6B:A1:31:8C:5D:47:9D:D2:43:3F:54:75:E0:69:E2:ED:82:0D:6A:91:F4:98:E2:E8:77:53:BE:01:E5`.

Сохраните keystore и пароль в двух независимых безопасных местах.

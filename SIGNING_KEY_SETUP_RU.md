# Постоянная подпись NetPause (RU)

Чтобы Android принимал каждое новое обновление поверх установленной версии, все APK должны быть подписаны одним и тем же приватным ключом. Не меняйте и не теряйте этот ключ: восстановить его из APK невозможно.

## Настройка GitHub Actions

1. Откройте репозиторий `ewik100/Netpause` → Settings → Secrets and variables → Actions.
2. Создайте Repository secrets:
   - `NETPAUSE_KEYSTORE_BASE64` — полное содержимое файла `KEYSTORE_BASE64.txt` (одна длинная строка).
   - `NETPAUSE_KEYSTORE_PASSWORD` — пароль из `KEYSTORE_PASSWORD.txt`.
   - `NETPAUSE_KEY_ALIAS` — `netpause`.
   - `NETPAUSE_KEY_PASSWORD` — тот же пароль, если пароль ключа не отличается от пароля хранилища.
3. Не добавляйте файлы ключа, пароли или base64 в Git, Issues, Pull Requests или публичные сообщения.
4. После сохранения secrets запустите Actions → NetPause Android CI → Run workflow. Сборка автоматически использует постоянный ключ для debug и release APK. Release APK появится как artifact `NetPause-6.5.0-release-apk`.

## Отпечаток сертификата

SHA-256: `1F:B0:C5:6B:A1:31:8C:5D:47:9D:D2:43:3F:54:75:E0:69:E2:ED:82:0D:6A:91:F4:98:E2:E8:77:53:BE:01:E5`

Сохраните keystore и пароль в двух независимых безопасных местах. Если ключ уже использовался для публикации приложения в Google Play, нельзя заменять его этим новым ключом — нужно использовать исходный ключ или настроенный Play App Signing.

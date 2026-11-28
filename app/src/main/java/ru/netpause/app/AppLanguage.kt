package ru.netpause.app

/** Exact UI-string translations. Unknown strings and user-created content are preserved. */
object AppLanguage {
    const val PREF_KEY = "ui_language"
    const val RU = "ru"
    const val EN = "en"
    private val english = mapOf(
        "Главный экран" to "Home", "Приложения" to "Apps", "Статистика" to "Statistics",
        "Внешний вид" to "Appearance", "Разрешения" to "Permissions",
        "Резервная копия" to "Backup", "О NetPause" to "About NetPause",
        "Полностью выключить" to "Turn off NetPause", "Язык: English" to "Language: English",
        "Язык: Русский" to "Language: Русский", "Контроль интернет-доступа" to "Internet access control",
        "Ограничь интернет на время занятия — нужные приложения оставь доступными." to "Limit internet access while you focus — keep essential apps connected.",
        "КАК РАБОТАЕТ NETPAUSE" to "HOW NETPAUSE WORKS",
        "1. Выбери время.  2. Отметь приложения, которым нужен интернет.  3. Нажми «Начать». У остальных приложений сеть будет ограничена до конца таймера." to "1. Choose a duration.  2. Select apps that need internet.  3. Tap Start. Other apps stay offline until the timer ends.",
        "СОСТОЯНИЕ БЛОКИРОВКИ" to "BLOCKING STATUS", "БЛОКИРОВКА АКТИВНА" to "BLOCKING ACTIVE",
        "ОШИБКА" to "ERROR", "ГОТОВО" to "READY", "УПРАВЛЕНИЕ СЕАНСОМ" to "SESSION CONTROLS",
        "БЫСТРЫЙ ЗАПУСК" to "QUICK START", "ОСТАНОВИТЬ БЛОКИРОВКУ" to "STOP BLOCKING",
        "НА СКОЛЬКО ВРЕМЕНИ ОГРАНИЧИТЬ ИНТЕРНЕТ?" to "HOW LONG SHOULD INTERNET BE LIMITED?",
        "БЫСТРЫЙ ДОСТУП" to "QUICK ACCESS", "Список исключений" to "Allowed apps",
        "Цели и история" to "Goals and history", "Темы и кнопка NP" to "Themes and NP button",
        "Настройка телефона" to "Phone permissions", "Сохранить или вернуть" to "Save or restore",
        "Версия и приватность" to "Version and privacy", "О приложении" to "About",
        "Назад" to "Back", "Открыть меню NetPause" to "Open NetPause menu",
        "Сейчас интернет работает как обычно. Ничего не заблокировано. Выбери длительность и нажми кнопку ниже." to "Internet is working normally. Nothing is blocked. Choose a duration and tap the button below.",
        "Начать" to "Start", "Остановить" to "Stop", "Сохранить" to "Save",
        "Отмена" to "Cancel", "Готово" to "Done", "Удалить" to "Delete",
        "Изменить" to "Edit", "Добавить" to "Add", "Поиск приложений" to "Search apps",
        "Выбрать все" to "Select all", "Снять выбор" to "Clear selection",
        "Нет выбранных приложений" to "No apps selected",
        "Выбери приложения, которым нужно оставить интернет." to "Choose apps that should keep internet access.",
        "Разрешение на VPN" to "VPN permission",
        "Разрешение на отображение поверх окон" to "Display over other apps permission",
        "Проверить разрешения" to "Check permissions", "Статистика сеансов" to "Session statistics",
        "Время фокусировки" to "Focus time", "Количество сеансов" to "Sessions",
        "История" to "History", "Настройки сохранены" to "Settings saved",
        "Язык интерфейса изменён" to "Interface language changed",
        "Полностью выключить NetPause?" to "Turn off NetPause completely?",
        "Очистить выполненные задачи?" to "Clear completed tasks?", "Изменить задачу" to "Edit task",
        "Как работает блокировка" to "How blocking works", "Выключить NetPause?" to "Turn off NetPause?"
    )
    fun normalize(value: String?): String = if (value == EN) EN else RU
    fun translate(value: String, language: String?): String =
        if (normalize(language) == EN) english[value] ?: value else value
    fun toggle(language: String?): String = if (normalize(language) == RU) EN else RU
}

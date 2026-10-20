# Уведомления о сторонних компонентах

Проект «xDrip to VK» использует и адаптирует компоненты третьих сторон.
Ниже — реестр: компонент, версия, источник, лицензия и применимые уведомления.

## Адаптированный код

### bg2max (reply-флейвор)

- **Источник**: https://github.com/s-beaver/bg2max
- **Ревизия сравнения**: `63fb7e1d44585740b57e3cda0018d5ff91114636` (ветка `main`,
  каталог `app/src/reply/java/com/bg2max/app/`)
- **Лицензия**: GNU GPL v3 — совместима с GPL-3.0-only основного кода проекта.
- **Что заимствовано и адаптировано** (пакет `com.example.xdrip2vk.max`, порт Kotlin → Java
  по той же схеме; перевод не является самостоятельным доказательством независимости —
  подход и элементы дизайна взяты из bg2max):
  - `ReplyListenerService.kt` → `MaxNotificationListener.java` — слушатель уведомлений
    MAX, извлечение кнопки «Ответить» (`RemoteInput` с `allowFreeFormInput`), список
    кандидатов, фильтр по пакету `ru.oneme.app`, игнорирование group-summary;
  - `ReplyState.kt` → `MaxReplyButton.java` / `MaxState.java` — модель кнопки ответа
    (имя чата, канал уведомления, id уведомления, PendingIntent, RemoteInput), карта
    кандидатов с разделителем `\u0000` в ключе, флаг `listenerConnected`;
  - `ReplyBackup.kt` → `MaxReplyBackup.java` — копия PendingIntent в extras тихого
    собственного уведомления для переживания перезапуска процесса;
  - `ReadingSender.kt` → `MaxSender.java` — отправка «ответом из уведомления» через
    `RemoteInput.addResultsToIntent` + `PendingIntent.send`, ожидающее показание с TTL,
    тестовое сообщение, промывка очереди по свежей кнопке;
  - `ReplyPrefs` → `MaxChannel.java` — персистентная цель выбранного чата.
- **Изменения относительно исходника**: порт на Java; идентичность цели расширена
  `notificationId`; добавлены поколения цели и pending; подтверждённая модель записи
  (`commit()` + снимки + версионированные повторы); протокол попыток
  `RESERVED/HANDED/FAILED/UNKNOWN`; единый владелец состояния на однопоточном
  исполнителе; интеграция с общим Stop и VK-конвейером; отдельная запись попытки
  тестового сообщения; блокировки по равенству операций PendingIntent — сохранены
  по исходному принципу с добавлением сверки `notificationId`.
- **Уведомление об авторстве**: Copyright © автора bg2max. Данный проект использует
  подход и адаптированные элементы реализации под GPL-3.0.

## Прямые зависимости сборки

| Компонент | Версия | Источник | Лицензия |
|---|---|---|---|
| androidx.appcompat:appcompat | 1.6.1 | Google Maven | Apache-2.0 |
| com.google.android.material:material | 1.10.0 | Google Maven | Apache-2.0 |
| androidx.preference:preference | 1.2.1 | Google Maven | Apache-2.0 |
| androidx.security:security-crypto | 1.0.0 | Google Maven | Apache-2.0 |
| junit:junit | 4.13.2 | Maven Central (тесты) | EPL-1.0 |
| org.json:json | 20231013 | Maven Central (тесты) | JSON (MIT-like) |
| androidx.test:core | 1.5.0 | Google Maven (тесты) | Apache-2.0 |
| org.robolectric:robolectric | 4.13 | Maven Central (тесты) | Apache-2.0 |

## Транзитивные зависимости, входящие в APK

Семейство `androidx.*` (activity, annotation, arch.core, cardview, collection,
concurrent-futures, constraintlayout, coordinatorlayout, core, cursoradapter,
customview, documentfile, drawerlayout, dynamicanimation, emoji2, fragment,
interpolator, legacy-support-core-utils, lifecycle, loader, localbroadcastmanager,
print, profileinstaller, recyclerview, resourceinspection-annotation, savedstate,
slidingpanelayout, startup-runtime, tracing, transition, vectordrawable,
versionedparcelable, viewpager, viewpager2, window) — Apache-2.0.

| Компонент | Версия | Лицензия |
|---|---|---|
| com.google.crypto.tink:tink-android | 1.5.0 | Apache-2.0 |
| com.google.guava:listenablefuture | 1.0 | Apache-2.0 |
| com.google.errorprone:error_prone_annotations | 2.15.0 | Apache-2.0 |
| org.jetbrains.kotlin:kotlin-stdlib* | 1.8.22 | Apache-2.0 |
| org.jetbrains.kotlinx:kotlinx-coroutines-* | 1.6.4 | Apache-2.0 |
| org.jetbrains:annotations | 13.0 | Apache-2.0 |

Полный перечень версий транзитивных зависимостей:
`./gradlew :app:dependencies --configuration debugRuntimeClasspath`.

## Примечания

- Тексты лицензий Apache-2.0 и EPL-1.0 распространяются в составе соответствующих
  библиотек и доступны в их дистрибутивах/репозиториях.
- Препятствий по лицензиям на данный момент не выявлено: все компоненты совместимы
  с GPL-3.0-only основного кода.

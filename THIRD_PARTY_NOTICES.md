# Уведомления о сторонних компонентах

Проект «xDrip to VK» использует и адаптирует компоненты третьих сторон. Реестр:
компонент, версия, источник, лицензия и применимые уведомления. Тексты лицензий —
в каталоге `licenses/`; для распространяемого APK те же материалы упакованы в
`assets/licenses/` (см. «Материалы в комплекте»).

## Адаптированный код

### bg2max (reply-флейвор) — Copyright © 2026 Sergey Efremov

- **Источник**: https://github.com/s-beaver/bg2max
- **Ревизия сравнения**: `63fb7e1d44585740b57e3cda0018d5ff91114636` (ветка `main`,
  каталог `app/src/reply/java/com/bg2max/app/`)
- **Лицензия исходника**: GNU GPL v3. Проект «xDrip to VK» распространяется под
  GPL-3.0-only; права на адаптированные части остаются за их автором —
  проект им их не присваивает.
- **Что заимствовано и адаптировано** (пакет `com.example.xdrip2vk.max`, реализация
  на Java по схеме исходника; перевод Kotlin→Java не считается доказательством
  независимости — подход и элементы дизайна взяты из bg2max):
  - `ReplyListenerService.kt` → `MaxNotificationListener.java` — слушатель
    уведомлений MAX, извлечение кнопки «Ответить» (`RemoteInput` с
    `allowFreeFormInput`), список кандидатов, фильтр по пакету `ru.oneme.app`;
  - `ReplyState.kt` → `MaxReplyButton.java` / `MaxState.java` — модель кнопки
    (имя чата, канал уведомления, id уведомления, PendingIntent, RemoteInput),
    карта кандидатов с разделителем `\u0000` в ключе;
  - `ReplyBackup.kt` → `MaxReplyBackup.java` — копия PendingIntent в extras
    тихого уведомления для переживания перезапуска процесса;
  - `ReadingSender.kt` → `MaxSender.java` — отправка «ответом из уведомления»
    (`RemoteInput.addResultsToIntent` + `PendingIntent.send`), ожидающее
    показание с TTL, тестовое сообщение;
  - `ReplyPrefs` → `MaxChannel.java` — персистентная цель выбранного чата.
- **Изменения относительно исходника**: порт на Java; идентичность цели
  расширена `notificationId`; поколения цели и pending; подтверждённая модель
  записи (`commit()` + снимки + версионированные повторы записи полного
  снимка); протокол попыток `RESERVED/HANDED/FAILED/UNKNOWN`; единый владелец
  состояния на однопоточном исполнителе; версии входящих событий; отдельная
  запись попытки тестового сообщения; интеграция с общим Stop и VK-конвейером.

## Компоненты, входящие в APK (releaseRuntimeClasspath)

Разрешённый набор на `fdcbff4` — вывод
`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`.
Все перечисленные компоненты — Apache-2.0 (текст: `licenses/apache-2.0.txt`,
внутри APK — `assets/licenses/apache-2.0.txt`).

**Прямые**: androidx.appcompat:appcompat 1.6.1;
com.google.android.material:material 1.10.0; androidx.preference:preference 1.2.1;
androidx.security:security-crypto 1.0.0.

**Транзитивные** (разрешённые версии):
androidx.activity:activity(-ktx) 1.8.0; androidx.annotation:annotation 1.3.0,
annotation-experimental 1.3.0; androidx.appcompat:appcompat-resources 1.6.1;
androidx.arch.core:core-common/runtime 2.2.0; androidx.cardview 1.0.0;
androidx.collection(-ktx) 1.1.0; androidx.concurrent:concurrent-futures 1.1.0;
androidx.constraintlayout(+solver) 2.0.1; androidx.coordinatorlayout 1.1.0;
androidx.core(-ktx) 1.9.0; androidx.cursoradapter 1.0.0; androidx.customview 1.1.0;
androidx.documentfile 1.0.0; androidx.drawerlayout 1.1.1;
androidx.dynamicanimation 1.0.0; androidx.emoji2(+views-helper) 1.2.0;
androidx.fragment(-ktx) 1.3.6; androidx.interpolator 1.0.0;
androidx.legacy:legacy-support-core-utils 1.0.0; androidx.lifecycle:* 2.6.1;
androidx.loader 1.0.0; androidx.localbroadcastmanager 1.0.0; androidx.print 1.0.0;
androidx.profileinstaller 1.3.0; androidx.recyclerview 1.1.0;
androidx.resourceinspection-annotation 1.0.1; androidx.savedstate(-ktx) 1.2.1;
androidx.slidingpanelayout 1.2.0; androidx.startup:startup-runtime 1.1.1;
androidx.tracing 1.0.0; androidx.transition 1.4.1;
androidx.vectordrawable(-animated) 1.1.0; androidx.versionedparcelable 1.1.1;
androidx.viewpager 1.0.0, viewpager2 1.0.0; androidx.window 1.0.0;
com.google.crypto.tink:tink-android 1.5.0;
com.google.errorprone:error_prone_annotations 2.15.0;
com.google.guava:listenablefuture 1.0; org.jetbrains.kotlin:kotlin-stdlib* 1.8.22;
org.jetbrains.kotlinx:kotlinx-coroutines-* 1.6.4; org.jetbrains:annotations 13.0.

Apache-2.0 совместима с GPL-3.0-only для распространяемого приложения.
Требование лицензии — включение текста Apache-2.0 и сохранение уведомлений —
выполнено файлами в `licenses/` и `assets/licenses/` APK. Файлов NOTICE у этих
артефактов нет.

## Компоненты только для сборки и тестов (в APK не входят)

| Компонент | Версия | Лицензия | Примечание |
|---|---|---|---|
| junit:junit | 4.13.2 | EPL-1.0 | тесты; в APK не включается |
| org.json:json | 20231013 | Public Domain | тесты; проект перешёл с «The JSON License» на Public Domain — до версии 20230618 действовала лицензия с условием «The Software shall be used for Good, not Evil»; используемая версия 20231013 — Public Domain |
| androidx.test:core | 1.5.0 | Apache-2.0 | тесты |
| org.robolectric:robolectric | 4.13 | Apache-2.0 | тесты |
| Android SDK / Gradle / AGP | 34 / wrapper | Apache-2.0 / проприетарные компоненты Google | инструменты сборки, не распространяются с приложением |

Заявление о совместимости относится только к компонентам APK (все Apache-2.0).
Тестовые и сборочные инструменты в распространяемое приложение не входят и на
совместимость лицензии приложения не влияют.

## Материалы в комплекте

- `LICENSE` — GPL-3.0 (собственный код, Copyright © 2026 Diabatya).
- `licenses/apache-2.0.txt` — текст Apache-2.0 для компонентов APK.
- `licenses/gpl-3.0.txt` — текст GPL-3.0 (копия LICENSE).
- Внутри APK (`assets/licenses/`): `gpl-3.0.txt`, `apache-2.0.txt`,
  `THIRD_PARTY_NOTICES.md`. Наличие проверено распаковкой собранного APK
  (`unzip -l .../caxarok_*.apk | grep licenses/`).

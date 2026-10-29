#!/usr/bin/env bash
# tools/local-release.sh — локальный выпуск релизного пакета xDrip to VK.
#
# Выполняет: проверку чистого дерева → блокировку версии → тесты + lint +
# assembleRelease → повторную проверку ревизии → подпись существующим
# ключом → проверки APK (applicationId, версии, debuggable, сертификат,
# схемы подписи, zipalign) → комплект → ZIP + .sha256 → проверка ZIP →
# повторная проверка назначения → атомарная публикация.
#
# Переменные окружения:
#   VK_RELEASE_ROOT  — корень хранилища релизов
#                      (по умолчанию /Users/denis/Devin/relise/VK)
#   VK_KEYSTORE      — путь к keystore (по умолчанию ~/.android/debug.keystore)
#   VK_KEYSTORE_PASS — пароль keystore (ОБЯЗАТЕЛЬНО; только через env)
#   VK_KEY_PASS      — пароль ключа (по умолчанию = VK_KEYSTORE_PASS)
#   VK_KEY_ALIAS     — алиас ключа (по умолчанию androiddebugkey)
#
# Гарантии:
#   * сборка только из чистого дерева — ревизия перепроверяется после сборки;
#   * подпись обязана быть действующим сертификатом приложения (точный
#     отпечаток ниже — публичный, не секрет);
#   * существующий каталог версии не перезаписывается и не пополняется;
#   * одновременный выпуск одной версии блокируется замком (mkdir);
#   * повреждённый/отсутствующий ZIP или файлы комплекта — не целый релиз;
#   * при любой ошибке staging удаляется, каталог версии не появляется.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RELEASE_ROOT="${VK_RELEASE_ROOT:-/Users/denis/Devin/relise/VK}"
KEYSTORE="${VK_KEYSTORE:-$HOME/.android/debug.keystore}"
KEY_ALIAS="${VK_KEY_ALIAS:-androiddebugkey}"

# Публичный отпечаток действующего сертификата приложения (см. BUILD_INFO 4.3.1).
EXPECTED_CERT_SHA="7e3a090c44b9bdfb9b0da69d1d516ff1df74c522f94d0a4651f4d8c3de7fc3c2"

[ -n "${VK_KEYSTORE_PASS:-}" ] || {
    echo "ОШИБКА: задайте VK_KEYSTORE_PASS через окружение (пароль keystore)." >&2
    echo "Без существующего ключа приложения официальная сборка невозможна." >&2
    exit 2
}
export VK_KEYSTORE_PASS
export VK_KEY_PASS="${VK_KEY_PASS:-$VK_KEYSTORE_PASS}"

fail() { echo "ОШИБКА: $*" >&2; exit 1; }

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -d "$ANDROID_HOME" ] || fail "не найден Android SDK: $ANDROID_HOME"

STAGE=""; CHECK=""; LOCK=""
cleanup() {
    [ -n "$STAGE" ] && rm -rf "$STAGE"
    [ -n "$CHECK" ] && rm -rf "$CHECK"
    [ -n "$LOCK" ] && rmdir "$LOCK" 2>/dev/null || true
}
trap cleanup EXIT

# --- 1. Версия и ревизия ---------------------------------------------------

GRADLE_FILE=app/build.gradle
[ -f "$GRADLE_FILE" ] || fail "нет $GRADLE_FILE — запускать из корня репозитория"
VER_NAME="$(grep -oE 'versionName "[^"]+"' "$GRADLE_FILE" | cut -d'"' -f2)"
VER_CODE="$(grep -oE 'versionCode [0-9]+' "$GRADLE_FILE" | awk '{print $2}')"
APP_ID="$(grep -oE 'applicationId "[^"]+"' "$GRADLE_FILE" | cut -d'"' -f2)"
[ -n "$VER_NAME" ] && [ -n "$VER_CODE" ] && [ -n "$APP_ID" ] \
    || fail "не прочитал versionName/versionCode/applicationId из $GRADLE_FILE"

[ -z "$(git status --porcelain)" ] || fail "рабочее дерево не чистое — официальный релиз не собирается"
SOURCE_SHA="$(git rev-parse HEAD)"
echo "== Версия: $VER_NAME ($VER_CODE), источник: $SOURCE_SHA"

VERSION_DIR="$RELEASE_ROOT/$VER_NAME"

# Проверка целостности существующего комплекта: и файлы, и ZIP.
verify_version_dir() {
    local d="$1"
    [ -f "$d/SHA256SUMS" ] && [ -f "$d/BUILD_INFO.md" ] || return 1
    grep -q "$SOURCE_SHA" "$d/BUILD_INFO.md" || return 1
    (cd "$d" && shasum -a 256 -c SHA256SUMS >/dev/null 2>&1) || return 1
    local z="$d/caxarok_${VER_NAME}_release.zip"
    [ -f "$z" ] && [ -f "$z.sha256" ] || return 1
    (cd "$d" && shasum -a 256 -c "caxarok_${VER_NAME}_release.zip.sha256" >/dev/null 2>&1) || return 1
    return 0
}

if [ -d "$VERSION_DIR" ]; then
    echo "== Каталог $VERSION_DIR существует — проверка идентичности"
    if verify_version_dir "$VERSION_DIR"; then
        echo "== Уже выпущено из этого коммита, комплект и ZIP целы — ничего не меняю"
        exit 0
    fi
    fail "каталог версии существует и отличается (другой коммит, повреждённые
     или отсутствующие файлы/ZIP). Не перезаписываю. Для новой версии
     поднимите versionName/versionCode в app/build.gradle."
fi

# --- 2. Блокировка версии (mkdir — работает и на Mac) -----------------------

mkdir -p "$RELEASE_ROOT"
LOCK_DIR="$RELEASE_ROOT/.locks"
mkdir -p "$LOCK_DIR"
LOCK="$LOCK_DIR/$VER_NAME.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    fail "выпуск версии $VER_NAME уже идёт в другом процессе (замок $LOCK).
     Если прежний запуск упал — удалите замок вручную и повторите."
fi

# Пока ждали замок, версию мог опубликовать другой процесс.
if [ -d "$VERSION_DIR" ]; then
    rmdir "$LOCK"; LOCK=""
    if verify_version_dir "$VERSION_DIR"; then
        echo "== Версию только что опубликовал другой процесс — комплект цел, ничего не меняю"
        exit 0
    fi
    fail "каталог версии появился во время ожидания и не проходит проверку — не трогаю"
fi

# --- 3. versionCode должен расти относительно известных релизов -------------

KNOWN_MAX=0
for bi in "$RELEASE_ROOT"/*/BUILD_INFO.md; do
    [ -f "$bi" ] || continue
    c="$(grep 'versionCode' "$bi" | sed -n 's|.*/ *\([0-9][0-9]*\) *|.*|\1|p' | sed -n '1p')"
    [ -n "$c" ] && [ "$c" -gt "$KNOWN_MAX" ] && KNOWN_MAX="$c"
done
PREV_TAG="$(git tag --sort=-creatordate | sed -n '1p' || true)"
if [ -n "$PREV_TAG" ]; then
    c="$(git show "$PREV_TAG:app/build.gradle" 2>/dev/null | grep -oE 'versionCode [0-9]+' | awk '{print $2}' || true)"
    [ -n "$c" ] && [ "$c" -gt "$KNOWN_MAX" ] && KNOWN_MAX="$c"
fi
[ "$VER_CODE" -gt "$KNOWN_MAX" ] \
    || fail "versionCode $VER_CODE не больше известного $KNOWN_MAX — поднимите его в app/build.gradle"

# --- 4. Инструменты ---------------------------------------------------------

BT_DIR=""
for base in "$ANDROID_HOME" "$HOME/Library/Android/sdk"; do
    [ -d "$base/build-tools" ] || continue
    cand="$(ls "$base/build-tools" | sort -V | tail -1)"
    [ -n "$cand" ] && BT_DIR="$base/build-tools/$cand" && break
done
[ -n "$BT_DIR" ] || fail "не найден Android SDK build-tools"
ZIPALIGN="$BT_DIR/zipalign"; APKSIGNER="$BT_DIR/apksigner"; AAPT2="$BT_DIR/aapt2"
[ -x "$APKSIGNER" ] && [ -x "$ZIPALIGN" ] && [ -x "$AAPT2" ] || fail "zipalign/apksigner/aapt2 не найдены в $BT_DIR"
[ -f "$KEYSTORE" ] || fail "keystore не найден: $KEYSTORE"

# --- 5. Сборка и тесты ------------------------------------------------------

echo "== Тесты, lint, release-сборка"
./gradlew --quiet testDebugUnitTest lintDebug assembleRelease

UNSIGNED="app/build/outputs/apk/release/caxarok_${VER_NAME}.apk"
[ -f "$UNSIGNED" ] || fail "нет unsigned APK: $UNSIGNED"

# Ревизия и чистота перепроверяются ПОСЛЕ сборки — изменённые файлы во время
# сборки не должны дать пакет с ложным SOURCE_SHA.
[ "$(git rev-parse HEAD)" = "$SOURCE_SHA" ] || fail "HEAD изменился во время сборки"
[ -z "$(git status --porcelain)" ] || fail "дерево стало грязным во время сборки"

# --- 6. Подпись и проверки APK ----------------------------------------------

STAGE="$(mktemp -d "$RELEASE_ROOT/.stage.XXXXXX")"

cp "$UNSIGNED" "$STAGE/caxarok_${VER_NAME}.apk"
APK="$STAGE/caxarok_${VER_NAME}.apk"

if ! "$ZIPALIGN" -c -p -f 4 "$APK" >/dev/null 2>&1; then
    "$ZIPALIGN" -p -f 4 "$APK" "$STAGE/aligned.apk" || fail "zipalign не удался"
    mv "$STAGE/aligned.apk" "$APK"
fi

"$APKSIGNER" sign \
    --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:VK_KEYSTORE_PASS --key-pass env:VK_KEY_PASS "$APK" \
    || fail "подписание не удалось"

# Вывод инструментов — во временные файлы: сначала успешность команды,
# потом разбор. Ошибка aapt2/apksigner — стоп, а не «пустой результат».
"$APKSIGNER" verify -v "$APK" > "$STAGE/apksigner_verify.txt" \
    || fail "apksigner verify завершился ошибкой"
grep -q '^Verifies' "$STAGE/apksigner_verify.txt" || fail "apksigner не подтвердил APK"
grep -q 'v2 scheme.*: true' "$STAGE/apksigner_verify.txt" || fail "нет подписи v2"
grep -q 'v3 scheme.*: true' "$STAGE/apksigner_verify.txt" || fail "нет подписи v3"

"$APKSIGNER" verify --print-certs "$APK" > "$STAGE/apksigner_certs.txt" \
    || fail "apksigner --print-certs завершился ошибкой"
CERT_SHA="$(grep -oE 'SHA-256 digest: [0-9a-f]+' "$STAGE/apksigner_certs.txt" | sed -n '1p' | awk '{print $3}')"
[ -n "$CERT_SHA" ] || fail "не удалось извлечь отпечаток сертификата"
[ "$CERT_SHA" = "$EXPECTED_CERT_SHA" ] \
    || fail "сертификат не совпадает с действующим: $CERT_SHA != $EXPECTED_CERT_SHA"

# Выравнивание проверяется ПОСЛЕ подписи.
"$ZIPALIGN" -c -p -f 4 "$APK" || fail "APK потерял выравнивание после подписи"

"$AAPT2" dump badging "$APK" > "$STAGE/badging.txt" || fail "aapt2 dump badging ошибкой"
PKG="$(sed -n '1p' "$STAGE/badging.txt")"
[ -n "$PKG" ] || fail "aapt2 вернул пустой badging"
echo "$PKG" | grep -q "name='$APP_ID'" || fail "applicationId в APK != $APP_ID: $PKG"
echo "$PKG" | grep -q "versionCode='$VER_CODE'" || fail "versionCode в APK != $VER_CODE"
echo "$PKG" | grep -q "versionName='$VER_NAME'" || fail "versionName в APK != $VER_NAME"

"$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" > "$STAGE/manifest.txt" \
    || fail "aapt2 dump xmltree завершился ошибкой"
[ -s "$STAGE/manifest.txt" ] || fail "aapt2 вернул пустой манифест"
grep -q 'debuggable' "$STAGE/manifest.txt" && fail "APK debuggable — не подходит к выпуску"

echo "== APK проверен: $APP_ID v$VER_NAME ($VER_CODE), v2+v3, cert $CERT_SHA, aligned"

# --- 7. Комплект ------------------------------------------------------------

mkdir -p "$STAGE/pkg"
mv "$APK" "$STAGE/pkg/caxarok_${VER_NAME}.apk"
cp LICENSE THIRD_PARTY_NOTICES.md "$STAGE/pkg/"

git archive --format=tar.gz --prefix="VK-${VER_NAME}/" \
    -o "$STAGE/pkg/source-${VER_NAME}.tar.gz" "$SOURCE_SHA"

{
    echo "## xDrip to VK ${VER_NAME}"
    echo
    echo "**Исходники**: коммит \`$SOURCE_SHA\`, ветка \`$(git rev-parse --abbrev-ref HEAD)\`."
    echo
    echo "### Изменения"
    echo
    if [ -n "$PREV_TAG" ] && git rev-parse -q --verify "refs/tags/$PREV_TAG" >/dev/null; then
        echo "С тега \`$PREV_TAG\`:"
        echo
        git log --format='- %s' "${PREV_TAG}..HEAD"
    else
        git log --format='- %s' -20
    fi
} > "$STAGE/pkg/RELEASE_NOTES.md"

{
    echo "# BUILD_INFO — локальный выпуск $VER_NAME"
    echo
    echo "| Параметр | Значение |"
    echo "|---|---|"
    echo "| versionName / versionCode | $VER_NAME / $VER_CODE |"
    echo "| SHA исходников | \`$SOURCE_SHA\` (дерево чистое до и после сборки) |"
    echo "| Вариант | release (assembleRelease, debuggable=false проверен в APK) |"
    echo "| Подпись | apksigner v2+v3, сертификат SHA-256 \`$CERT_SHA\` |"
    echo "| Выравнивание | zipalign проверен после подписи |"
    echo "| JDK | $(java -version 2>&1 | sed -n '1p') |"
    echo "| Gradle | $(./gradlew --version 2>/dev/null | awk '/Gradle /{print $2}' | sed -n '1p') |"
    echo "| build-tools | $(basename "$BT_DIR") |"
    echo "| APK | \`caxarok_${VER_NAME}.apk\` |"
    echo "| Дата сборки | $(date -u +%Y-%m-%dT%H:%M:%SZ) |"
    echo
    echo "Сборка локальная; GitHub Actions и GitHub Release не использовались."
} > "$STAGE/pkg/BUILD_INFO.md"

# SHA256SUMS содержимого — внутри комплекта и внутри ZIP.
(cd "$STAGE/pkg" && shasum -a 256 \
    "caxarok_${VER_NAME}.apk" "source-${VER_NAME}.tar.gz" \
    LICENSE THIRD_PARTY_NOTICES.md RELEASE_NOTES.md BUILD_INFO.md > SHA256SUMS)

(cd "$STAGE/pkg" && zip -q -r -X "../caxarok_${VER_NAME}_release.zip" . -x '.DS_Store')
mv "$STAGE/caxarok_${VER_NAME}_release.zip" "$STAGE/pkg/"

# Отдельная сумма для самого ZIP — рядом с ним, не внутри.
(cd "$STAGE/pkg" && shasum -a 256 "caxarok_${VER_NAME}_release.zip" > "caxarok_${VER_NAME}_release.zip.sha256")

# --- 8. Проверка результата, повторная проверка назначения, перенос ----------

CHECK="$(mktemp -d)"
unzip -q "$STAGE/pkg/caxarok_${VER_NAME}_release.zip" -d "$CHECK"
(cd "$CHECK" && shasum -a 256 -c SHA256SUMS >/dev/null) \
    || fail "содержимое ZIP не прошло проверку хешей"
rm -rf "$CHECK"; CHECK=""

# Назначение могло появиться за время сборки (другой процесс вне замка).
if [ -e "$VERSION_DIR" ]; then
    if verify_version_dir "$VERSION_DIR"; then
        echo "== Версию опубликовал другой процесс — комплект цел, staging удаляю"
        rm -rf "$STAGE"; STAGE=""
        exit 0
    fi
    fail "назначение $VERSION_DIR появилось и не проходит проверку — staging не переношу"
fi

mv "$STAGE/pkg" "$VERSION_DIR"
STAGE=""
rmdir "$LOCK"; LOCK=""
echo "== Готово: $VERSION_DIR"
echo "   ZIP: $VERSION_DIR/caxarok_${VER_NAME}_release.zip"

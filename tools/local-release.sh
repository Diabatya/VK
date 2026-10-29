#!/usr/bin/env bash
# tools/local-release.sh — локальный выпуск релизного пакета xDrip to VK.
#
# Выполняет: проверку чистого дерева → тесты + lint + assembleRelease →
# подпись существующим ключом приложения → проверки APK → каталог
# <VK_RELEASE_ROOT>/<versionName>/ + итоговый ZIP → проверка ZIP.
#
# Переменные окружения:
#   VK_RELEASE_ROOT  — корень хранилища релизов
#                      (по умолчанию /Users/denis/Devin/relise/VK)
#   VK_KEYSTORE      — путь к keystore (по умолчанию ~/.android/debug.keystore)
#   VK_KEYSTORE_PASS — пароль keystore (ОБЯЗАТЕЛЬНО; только через env,
#                      не пишется в файлы/логи)
#   VK_KEY_PASS      — пароль ключа (по умолчанию = VK_KEYSTORE_PASS)
#   VK_KEY_ALIAS     — алиас ключа (по умолчанию androiddebugkey)
#
# Гарантии:
#   * сборка только из чистого дерева — грязное дерево = не официальный релиз;
#   * существующий каталог версии никогда не перезаписывается:
#     идентичный — проверяется и остаётся, отличающийся — стоп с причиной;
#   * при любой ошибке каталог версии не появляется (staging + атомарный перенос).

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RELEASE_ROOT="${VK_RELEASE_ROOT:-/Users/denis/Devin/relise/VK}"
KEYSTORE="${VK_KEYSTORE:-$HOME/.android/debug.keystore}"
KEY_ALIAS="${VK_KEY_ALIAS:-androiddebugkey}"
[ -n "${VK_KEYSTORE_PASS:-}" ] || {
    echo "ОШИБКА: задайте VK_KEYSTORE_PASS через окружение (пароль keystore)." >&2
    echo "Без существующего ключа приложения официальная сборка невозможна." >&2
    exit 2
}
export VK_KEYSTORE_PASS
export VK_KEY_PASS="${VK_KEY_PASS:-$VK_KEYSTORE_PASS}"

fail() { echo "ОШИБКА: $*" >&2; exit 1; }

# Android SDK: по умолчанию стандартное расположение на Mac.
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -d "$ANDROID_HOME" ] || fail "не найден Android SDK: $ANDROID_HOME"

# --- 1. Версия и ревизия ---------------------------------------------------

GRADLE_FILE=app/build.gradle
[ -f "$GRADLE_FILE" ] || fail "нет $GRADLE_FILE — запускать из корня репозитория"
VER_NAME="$(grep -oE 'versionName "[^"]+"' "$GRADLE_FILE" | cut -d'"' -f2)"
VER_CODE="$(grep -oE 'versionCode [0-9]+' "$GRADLE_FILE" | awk '{print $2}')"
APP_ID="$(grep -oE 'applicationId "[^"]+"' "$GRADLE_FILE" | cut -d'"' -f2)"
[ -n "$VER_NAME" ] && [ -n "$VER_CODE" ] || fail "не прочитал версию из $GRADLE_FILE"

[ -z "$(git status --porcelain)" ] || fail "рабочее дерево не чистое — официальный релиз не собирается"
SOURCE_SHA="$(git rev-parse HEAD)"
echo "== Версия: $VER_NAME ($VER_CODE), источник: $SOURCE_SHA"

VERSION_DIR="$RELEASE_ROOT/$VER_NAME"

# --- 2. Существующий каталог версии ---------------------------------------

if [ -d "$VERSION_DIR" ]; then
    echo "== Каталог $VERSION_DIR существует — проверка идентичности"
    if [ -f "$VERSION_DIR/SHA256SUMS" ] && [ -f "$VERSION_DIR/BUILD_INFO.md" ] \
       && grep -q "$SOURCE_SHA" "$VERSION_DIR/BUILD_INFO.md" \
       && (cd "$VERSION_DIR" && shasum -a 256 -c SHA256SUMS >/dev/null 2>&1); then
        echo "== Уже выпущено из этого коммита, все файлы целы — ничего не меняю"
        exit 0
    fi
    fail "каталог версии существует и отличается (другой коммит или другие файлы).
     Не перезаписываю. Для новой версии поднимите versionName/versionCode в app/build.gradle."
fi

# --- 3. Инструменты ---------------------------------------------------------

BT_DIR=""
for base in "${ANDROID_HOME:-}" "$HOME/Library/Android/sdk"; do
    [ -d "$base/build-tools" ] || continue
    cand="$(ls "$base/build-tools" | sort -V | tail -1)"
    [ -n "$cand" ] && BT_DIR="$base/build-tools/$cand" && break
done
[ -n "$BT_DIR" ] || fail "не найден Android SDK build-tools"
ZIPALIGN="$BT_DIR/zipalign"; APKSIGNER="$BT_DIR/apksigner"; AAPT2="$BT_DIR/aapt2"
[ -x "$APKSIGNER" ] && [ -x "$ZIPALIGN" ] && [ -x "$AAPT2" ] || fail "zipalign/apksigner/aapt2 не найдены в $BT_DIR"
[ -f "$KEYSTORE" ] || fail "keystore не найден: $KEYSTORE"

# --- 4. Сборка и тесты ------------------------------------------------------

echo "== Тесты, lint, release-сборка"
./gradlew --quiet testDebugUnitTest lintDebug assembleRelease

UNSIGNED="app/build/outputs/apk/release/caxarok_${VER_NAME}.apk"
[ -f "$UNSIGNED" ] || fail "нет unsigned APK: $UNSIGNED"

# --- 5. Подпись и проверки APK ----------------------------------------------

mkdir -p "$RELEASE_ROOT"
STAGE="$(mktemp -d "$RELEASE_ROOT/.stage.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT

cp "$UNSIGNED" "$STAGE/caxarok_${VER_NAME}.apk"
APK="$STAGE/caxarok_${VER_NAME}.apk"

if ! "$ZIPALIGN" -c -p -f 4 "$APK" >/dev/null 2>&1; then
    "$ZIPALIGN" -p -f 4 "$APK" "$STAGE/aligned.apk" && mv "$STAGE/aligned.apk" "$APK"
fi

"$APKSIGNER" sign \
    --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:VK_KEYSTORE_PASS --key-pass env:VK_KEY_PASS "$APK"

"$APKSIGNER" verify -v "$APK" | grep -q '^Verifies' || fail "apksigner verify не прошёл"
CERT_SHA="$("$APKSIGNER" verify --print-certs "$APK" | grep -oE 'SHA-256 digest: [0-9a-f]+' | sed -n '1p' | awk '{print $3}')"

PKG="$("$AAPT2" dump badging "$APK" | sed -n '1p')"
echo "$PKG" | grep -q "name='$APP_ID'" || fail "applicationId в APK не совпадает: $PKG"
echo "$PKG" | grep -q "versionCode='$VER_CODE'" || fail "versionCode в APK != $VER_CODE"
"$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null | grep -q debuggable \
    && fail "APK содержит debuggable — не подходит к выпуску"
echo "== APK проверен: $APP_ID v$VER_NAME ($VER_CODE), подпись v2+v3, cert $CERT_SHA"

# --- 6. Комплект ------------------------------------------------------------

mkdir -p "$STAGE/pkg"
mv "$APK" "$STAGE/pkg/caxarok_${VER_NAME}.apk"
cp LICENSE THIRD_PARTY_NOTICES.md "$STAGE/pkg/"

# архив исходников точной ревизии
git archive --format=tar.gz --prefix="VK-${VER_NAME}/" \
    -o "$STAGE/pkg/source-${VER_NAME}.tar.gz" "$SOURCE_SHA"

# описание изменений — автоматически из истории с последнего тега
PREV_TAG="$(git tag --sort=-creatordate | sed -n '1p' || true)"
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
    echo "| SHA исходников | \`$SOURCE_SHA\` (дерево было чистым на момент сборки) |"
    echo "| Вариант | release (assembleRelease, без debuggable) |"
    echo "| JDK | $(java -version 2>&1 | sed -n '1p') |"
    echo "| Gradle | $(./gradlew --version 2>/dev/null | awk '/Gradle /{print $2}' | sed -n '1p') |"
    echo "| build-tools | $(basename "$BT_DIR") |"
    echo "| Подпись | apksigner v2+v3, сертификат SHA-256 \`$CERT_SHA\` |"
    echo "| APK | \`caxarok_${VER_NAME}.apk\` |"
    echo "| Дата сборки | $(date -u +%Y-%m-%dT%H:%M:%SZ) |"
    echo
    echo "Сборка локальная; GitHub Actions и GitHub Release не использовались."
} > "$STAGE/pkg/BUILD_INFO.md"

(cd "$STAGE/pkg" && shasum -a 256 caxarok_${VER_NAME}.apk source-${VER_NAME}.tar.gz \
    LICENSE THIRD_PARTY_NOTICES.md RELEASE_NOTES.md BUILD_INFO.md > SHA256SUMS)

(cd "$STAGE/pkg" && zip -q -r -X "../caxarok_${VER_NAME}_release.zip" . -x '.DS_Store')
mv "$STAGE/caxarok_${VER_NAME}_release.zip" "$STAGE/pkg/"

# --- 7. Проверка результата и атомарный перенос ------------------------------

CHECK="$(mktemp -d)"
unzip -q "$STAGE/pkg/caxarok_${VER_NAME}_release.zip" -d "$CHECK"
(cd "$CHECK" && shasum -a 256 -c SHA256SUMS >/dev/null) \
    || { rm -rf "$CHECK"; fail "ZIP не прошёл проверку хешей"; }
rm -rf "$CHECK"

mv "$STAGE/pkg" "$VERSION_DIR"
trap - EXIT
echo "== Готово: $VERSION_DIR"
echo "   ZIP: $VERSION_DIR/caxarok_${VER_NAME}_release.zip"

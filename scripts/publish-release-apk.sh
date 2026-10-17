#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_PATH="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
ASSET_NAME="lexi-android.apk"
REPO="${GITHUB_REPOSITORY:-}"
TAG=""
RELEASE_NAME=""
DISPATCH_REPO="${LEXI_HOME_REPO:-alexander812/lexi-home}"

usage() {
    cat <<'EOF'
Публикует собранный release APK в GitHub Releases и запускает деплой лендинга
в репозитории lexi-home.

Использование: scripts/publish-release-apk.sh [опции]

Опции:
  --apk <path>            другой путь к APK
  --repo <owner/repo>     репозиторий (по умолчанию — origin текущего репозитория)
  --tag <tag>             тег релиза (по умолчанию v<versionName>-<versionCode>)
  --name <name>           название релиза
  --dispatch-repo <repo>  репозиторий лендинга (по умолчанию alexander812/lexi-home)
  --no-dispatch           не запускать деплой лендинга
  -h, --help              показать эту справку

Для доступа к GitHub API нужен токен с правом Contents: Read and write.
Токен берётся из GH_TOKEN/GITHUB_TOKEN или из git credential helper.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk)
            APK_PATH="${2:?--apk требует путь}"
            shift
            ;;
        --repo)
            REPO="${2:?--repo требует owner/repo}"
            shift
            ;;
        --tag)
            TAG="${2:?--tag требует значение}"
            shift
            ;;
        --name)
            RELEASE_NAME="${2:?--name требует значение}"
            shift
            ;;
        --dispatch-repo)
            DISPATCH_REPO="${2:?--dispatch-repo требует owner/repo}"
            shift
            ;;
        --no-dispatch)
            DISPATCH_REPO=""
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Неизвестный аргумент: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
    shift
done

if [[ ! -f "$APK_PATH" ]]; then
    UNSIGNED_APK="$ROOT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
    if [[ -f "$UNSIGNED_APK" ]]; then
        echo "Release APK не подписан — публикация в GitHub Releases пропущена." >&2
        echo "Добавьте keystore.properties и пересоберите release." >&2
        exit 0
    fi
    echo "APK не найден: $APK_PATH" >&2
    echo "Соберите его: ./gradlew :app:assembleRelease" >&2
    exit 1
fi

APK_SIZE="$(wc -c < "$APK_PATH" | tr -d '[:space:]')"
MAX_APK_SIZE=$((2 * 1024 * 1024 * 1024))
if (( APK_SIZE >= MAX_APK_SIZE )); then
    echo "APK весит больше 2 ГиБ — GitHub Releases не принимает такие файлы." >&2
    exit 1
fi

if [[ -z "$REPO" ]]; then
    REMOTE_URL="$(git -C "$ROOT_DIR" remote get-url origin 2>/dev/null || true)"
    case "$REMOTE_URL" in
        git@github.com:*)
            REPO="${REMOTE_URL#git@github.com:}"
            ;;
        https://github.com/*)
            REPO="${REMOTE_URL#https://github.com/}"
            ;;
        *)
            echo "Не удалось определить GitHub-репозиторий из origin: $REMOTE_URL" >&2
            echo "Укажите его через --repo owner/repo или GITHUB_REPOSITORY." >&2
            exit 1
            ;;
    esac
    REPO="${REPO%.git}"
fi

VERSION_NAME="$(sed -n 's/^[[:space:]]*versionName = "\(.*\)"/\1/p' "$ROOT_DIR/app/build.gradle.kts" | head -n 1)"
VERSION_CODE="$(sed -n 's/^[[:space:]]*versionCode = \([0-9][0-9]*\)/\1/p' "$ROOT_DIR/app/build.gradle.kts" | head -n 1)"
if [[ -z "$VERSION_NAME" || -z "$VERSION_CODE" ]]; then
    echo "Не удалось прочитать versionName/versionCode из app/build.gradle.kts" >&2
    exit 1
fi

TAG="${TAG:-v${VERSION_NAME}-${VERSION_CODE}}"
RELEASE_NAME="${RELEASE_NAME:-Lexi Android v${VERSION_NAME} (${VERSION_CODE})}"

TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
if [[ -z "$TOKEN" ]]; then
    CREDENTIALS="$(printf 'protocol=https\nhost=github.com\n\n' \
        | GIT_TERMINAL_PROMPT=0 git -C "$ROOT_DIR" credential fill 2>/dev/null || true)"
    TOKEN="$(printf '%s\n' "$CREDENTIALS" | sed -n 's/^password=//p' | head -n 1 | tr -d '\r')"
fi
if [[ -z "$TOKEN" ]]; then
    echo "Не найден GitHub token." >&2
    echo "Задайте GH_TOKEN или GITHUB_TOKEN с правом Contents: Read and write." >&2
    exit 1
fi

export GITHUB_TOKEN="$TOKEN"
TARGET_COMMITISH="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || true)"

python3 "$ROOT_DIR/scripts/upload-github-release.py" \
    --repo "$REPO" \
    --tag "$TAG" \
    --name "$RELEASE_NAME" \
    --apk "$APK_PATH" \
    --asset-name "$ASSET_NAME" \
    --body "Release APK для Lexi Android ${VERSION_NAME} (versionCode ${VERSION_CODE})." \
    --target-commitish "$TARGET_COMMITISH" \
    --dispatch-repo "$DISPATCH_REPO"

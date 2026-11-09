#!/bin/zsh
# Builds the official release APK and prepares the files for a GitHub release. Maintainer only: it needs the
# release key (keystore.properties, never part of the published source) and checks that the APK is signed with it.
# Usage: scripts/release.sh        → dist/MinerGuru-<version>.apk + dist/upload/{MinerGuru.apk,SHA256SUMS.txt}
#
# Afterwards: create the release "v<version>" in the public repository minerguru-android and upload both files from
# dist/upload. The upload name carries no version, so the website's releases/latest/download link keeps working.
set -euo pipefail
cd "$(dirname "$0")/.."

# Certificate of the release key. Android only accepts an update signed with the same key.
EXPECTED_CERT="faa915c9d5dd64818faf2d9ea83979104d45fc4c9996661e1498accb424b9536"

# Personal traces the APK must not contain. The patterns are personal themselves, so they live outside the
# repository: one extended regex per line in MINERGURU_TRACES (default ~/.config/minerguru/traces).
TRACES_FILE="${MINERGURU_TRACES:-$HOME/.config/minerguru/traces}"
TRACES=$(grep -v -e '^#' -e '^[[:space:]]*$' "${TRACES_FILE}" 2>/dev/null | paste -sd '|' - || true)
[ -n "${TRACES}" ] || { echo "❌ Keine Spurenmuster in ${TRACES_FILE}, kein Release."; exit 1; }

VERSION=$(sed -n -E 's/^ *versionName = "(.*)"/\1/p' app/build.gradle.kts)
APK="app/build/outputs/apk/release/app-release.apk"
OUT_APK="dist/MinerGuru-${VERSION}.apk"
UPLOAD_DIR="dist/upload"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
APKSIGNER=$(ls -d "${SDK}"/build-tools/* | sort -V | tail -1)/apksigner

echo "🔨 Release-Build ${VERSION} + Unit-Tests…"
./gradlew --quiet testDebugUnitTest assembleRelease
[ -f "${APK}" ] || { echo "❌ Build fehlgeschlagen"; exit 1; }

echo "🔏 Prüfe Signatur…"
CERTS=$("${APKSIGNER}" verify --print-certs "${APK}")
echo "${CERTS}" | grep -q "certificate DN: CN=MinerGuru$" || { echo "❌ Unerwarteter Zertifikatsname:"; echo "${CERTS}" | grep DN; exit 1; }
echo "${CERTS}" | grep -q "SHA-256 digest: ${EXPECTED_CERT}" || { echo "❌ Anderer Signaturschlüssel, Updates würden scheitern"; exit 1; }

echo "🕵️  Prüfe auf persönliche Spuren…"
SCRATCH=$(mktemp -d)
trap 'rm -rf "${SCRATCH}"' EXIT
unzip -q "${APK}" -d "${SCRATCH}"
LEAKS=$(LC_ALL=C grep -rla -i -E "${TRACES}" "${SCRATCH}" | sed "s|${SCRATCH}/||" || true)
if [ -n "${LEAKS}" ]; then
  echo "❌ Persönliche Daten im Build gefunden:"; echo "${LEAKS}" | head
  exit 1
fi

mkdir -p dist
cp "${APK}" "${OUT_APK}"
rm -rf "${UPLOAD_DIR}"
mkdir -p "${UPLOAD_DIR}"
cp "${APK}" "${UPLOAD_DIR}/MinerGuru.apk"
(cd "${UPLOAD_DIR}" && shasum -a 256 MinerGuru.apk > SHA256SUMS.txt)

echo "✅ ${OUT_APK}"
echo "   Upload-Dateien für Release v${VERSION}:"
ls -lh "${UPLOAD_DIR}"
cat "${UPLOAD_DIR}/SHA256SUMS.txt"

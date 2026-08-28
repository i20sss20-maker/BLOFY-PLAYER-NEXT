#!/usr/bin/env bash
# Structural checks only. Audio correctness still requires fixture playback on Android hardware.
set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
readonly LOCK_FILE="${REPO_ROOT}/config/media3-ffmpeg.properties"

usage() {
  echo "Usage: $0 aar <decoder.aar> | apk <release.apk>" >&2
  exit 2
}

[[ "$#" -eq 2 ]] || usage
readonly MODE="$1"
readonly ARCHIVE="$2"
[[ -s "${ARCHIVE}" ]] || { echo "Missing or empty archive: ${ARCHIVE}" >&2; exit 1; }

archive_entries="$(unzip -Z1 "${ARCHIVE}")"

require_entry() {
  local entry="$1"
  if ! grep -Fxq -- "${entry}" <<< "${archive_entries}"; then
    echo "Required archive entry is missing: ${entry}" >&2
    exit 1
  fi
}

if [[ "${MODE}" == "aar" ]]; then
  require_entry "classes.jar"
  require_entry "jni/armeabi-v7a/libffmpegJNI.so"
  require_entry "jni/arm64-v8a/libffmpegJNI.so"
  require_entry "META-INF/blofy-media3-ffmpeg.properties"

  if ! diff -u "${LOCK_FILE}" <(unzip -p "${ARCHIVE}" META-INF/blofy-media3-ffmpeg.properties); then
    echo "FFmpeg AAR build receipt does not match the pinned inputs." >&2
    exit 1
  fi

  readonly CLASSES_JAR="$(mktemp /tmp/blofy-ffmpeg-classes.XXXXXX.jar)"
  unzip -p "${ARCHIVE}" classes.jar > "${CLASSES_JAR}"
  if ! unzip -Z1 "${CLASSES_JAR}" | grep -Fxq 'androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.class'; then
    echo "FfmpegAudioRenderer is missing from classes.jar." >&2
    exit 1
  fi
  echo "Verified pinned FFmpeg extension AAR."
elif [[ "${MODE}" == "apk" ]]; then
  require_entry "lib/armeabi-v7a/libffmpegJNI.so"
  require_entry "lib/arm64-v8a/libffmpegJNI.so"
  require_entry "assets/licenses/ffmpeg/NOTICE.txt"
  require_entry "assets/licenses/ffmpeg/COPYING.LGPLv2.1"

  unexpected_abis="$(awk -F/ '/^lib\/[^/]+\/.*\.so$/ {print $2}' <<< "${archive_entries}" | sort -u | grep -Ev '^(armeabi-v7a|arm64-v8a)$' || true)"
  if [[ -n "${unexpected_abis}" ]]; then
    echo "Release APK contains non-ARM native libraries:" >&2
    echo "${unexpected_abis}" >&2
    exit 1
  fi
  echo "Verified FFmpeg and ARM-only JNI packaging in release APK."
else
  usage
fi

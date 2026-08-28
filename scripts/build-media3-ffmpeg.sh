#!/usr/bin/env bash
# Builds the official Media3 FFmpeg audio extension from immutable upstream commits.
set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
readonly LOCK_FILE="${REPO_ROOT}/config/media3-ffmpeg.properties"

if [[ ! -f "${LOCK_FILE}" ]]; then
  echo "Missing FFmpeg lock file: ${LOCK_FILE}" >&2
  exit 1
fi

# shellcheck disable=SC1090
source "${LOCK_FILE}"
: "${MEDIA3_COMMIT:?}"
: "${FFMPEG_COMMIT:?}"
: "${NDK_VERSION:?}"
: "${CMAKE_VERSION:?}"
: "${ANDROID_API:?}"
: "${ENABLED_DECODERS:?}"
: "${AAR_PATH:?}"

if [[ ! "${MEDIA3_COMMIT}" =~ ^[0-9a-f]{40}$ || ! "${FFMPEG_COMMIT}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Media3 and FFmpeg revisions must be full 40-character commits." >&2
  exit 1
fi
if [[ "${ANDROID_API}" != "23" || "${ENABLED_DECODERS}" != "ac3,eac3,dca" ]]; then
  echo "Unexpected decoder policy in ${LOCK_FILE}. Review this script before changing it." >&2
  exit 1
fi

readonly SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
if [[ -z "${SDK_ROOT}" ]]; then
  echo "Set ANDROID_SDK_ROOT (or ANDROID_HOME) before building FFmpeg." >&2
  exit 1
fi
readonly NDK_PATH="${SDK_ROOT}/ndk/${NDK_VERSION}"
if [[ ! -x "${NDK_PATH}/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]]; then
  echo "Android NDK r26b (${NDK_VERSION}) is not installed at ${NDK_PATH}." >&2
  exit 1
fi
readonly CMAKE_PATH="${SDK_ROOT}/cmake/${CMAKE_VERSION}/bin"
if [[ ! -x "${CMAKE_PATH}/cmake" || ! -x "${CMAKE_PATH}/ninja" ]]; then
  echo "Android CMake ${CMAKE_VERSION} is not installed at ${CMAKE_PATH}." >&2
  exit 1
fi

readonly WORK_PARENT="${1:-${RUNNER_TEMP:-/tmp}}"
mkdir -p "${WORK_PARENT}"
readonly WORK_ROOT="$(mktemp -d "${WORK_PARENT%/}/blofy-media3-ffmpeg.XXXXXX")"
readonly MEDIA3_ROOT="${WORK_ROOT}/media3"
readonly FFMPEG_ROOT="${MEDIA3_ROOT}/libraries/decoder_ffmpeg/src/main/jni/ffmpeg"
readonly MODULE_ROOT="${MEDIA3_ROOT}/libraries/decoder_ffmpeg/src/main"
readonly OUTPUT_AAR="${REPO_ROOT}/${AAR_PATH}"

checkout_pinned() {
  local repository="$1"
  local commit="$2"
  local destination="$3"
  git init --quiet "${destination}"
  git -C "${destination}" remote add origin "${repository}"
  git -C "${destination}" fetch --quiet --depth=1 origin "${commit}"
  git -C "${destination}" -c advice.detachedHead=false checkout --quiet --detach FETCH_HEAD
  local resolved
  resolved="$(git -C "${destination}" rev-parse HEAD)"
  if [[ "${resolved}" != "${commit}" ]]; then
    echo "Pinned checkout mismatch: expected ${commit}, got ${resolved}" >&2
    exit 1
  fi
}

checkout_pinned "https://github.com/androidx/media.git" "${MEDIA3_COMMIT}" "${MEDIA3_ROOT}"
checkout_pinned "https://github.com/FFmpeg/FFmpeg.git" "${FFMPEG_COMMIT}" "${FFMPEG_ROOT}"

IFS=',' read -r -a decoders <<< "${ENABLED_DECODERS}"
"${MODULE_ROOT}/jni/build_ffmpeg.sh" \
  "${MODULE_ROOT}" \
  "${NDK_PATH}" \
  "linux-x86_64" \
  "${ANDROID_API}" \
  "${decoders[@]}"

export ANDROID_HOME="${SDK_ROOT}"
export ANDROID_SDK_ROOT="${SDK_ROOT}"
export ANDROID_NDK_HOME="${NDK_PATH}"
export PATH="${CMAKE_PATH}:${PATH}"
"${MEDIA3_ROOT}/gradlew" \
  --no-daemon \
  --stacktrace \
  :lib-decoder-ffmpeg:assembleRelease

mapfile -t built_aars < <(
  find "${MEDIA3_ROOT}/libraries/decoder_ffmpeg" \
    -type f \
    -path '*/outputs/aar/*-release.aar' \
    -print
)
if [[ "${#built_aars[@]}" -ne 1 ]]; then
  echo "Expected exactly one Media3 FFmpeg release AAR, found ${#built_aars[@]}." >&2
  exit 1
fi

mkdir -p "$(dirname -- "${OUTPUT_AAR}")"
install -m 0644 "${built_aars[0]}" "${OUTPUT_AAR}"

# Embed the immutable build inputs. Verification rejects stale or foreign cached AARs.
readonly RECEIPT_ROOT="${WORK_ROOT}/receipt"
mkdir -p "${RECEIPT_ROOT}/META-INF"
install -m 0644 "${LOCK_FILE}" "${RECEIPT_ROOT}/META-INF/blofy-media3-ffmpeg.properties"
jar uf "${OUTPUT_AAR}" -C "${RECEIPT_ROOT}" META-INF/blofy-media3-ffmpeg.properties

"${SCRIPT_DIR}/verify-media3-ffmpeg.sh" aar "${OUTPUT_AAR}"
sha256sum "${OUTPUT_AAR}"
echo "Built pinned Media3 FFmpeg extension at ${OUTPUT_AAR}"

#!/usr/bin/env bash
set -euo pipefail

PLATFORM="paper"
MC_VERSION="26.3"
PORT=25565
MEMORY="2G"
SKIP_BUILD="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --platform)
      PLATFORM="$2"
      shift 2
      ;;
    --mc-version)
      MC_VERSION="$2"
      shift 2
      ;;
    --port)
      PORT="$2"
      shift 2
      ;;
    --memory)
      MEMORY="$2"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD="true"
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 [--platform paper|folia] [--mc-version 26.3] [--port 25565] [--memory 2G] [--skip-build]" >&2
      exit 2
      ;;
  esac
done

case "$PLATFORM" in
  paper|folia) ;;
  *)
    echo "Invalid --platform '$PLATFORM'. Expected paper or folia." >&2
    exit 2
    ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
USER_AGENT="FemItems-DevServer/1.0 (https://github.com/Femboy-Hangout/FemItems)"

get_project_version() {
  python3 - "$REPO_ROOT/pom.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.argv[1])
root = tree.getroot()
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
ver = root.find("m:version", ns)
if ver is None:
    ver = root.find("version")
print(ver.text.strip())
PY
}

ensure_server_jar() {
  local target_platform="$1"
  local version="$2"
  local cache_dir="$3"
  mkdir -p "$cache_dir"

  local api_url="https://fill.papermc.io/v3/projects/${target_platform}/versions/${version}/builds/latest"
  echo "[$target_platform] Querying PaperMC Fill v3 API: $api_url" >&2
  local json
  json="$(curl -fsSL -H "User-Agent: $USER_AGENT" "$api_url")"

  local meta
  meta="$(printf '%s' "$json" | python3 -c '
import json, sys
data = json.load(sys.stdin)
dl = data["downloads"]["server:default"]
print(dl["name"])
print(dl["url"])
print(dl["checksums"]["sha256"].lower())
')"

  local jar_name jar_url expected_sha
  jar_name="$(printf '%s\n' "$meta" | sed -n '1p')"
  jar_url="$(printf '%s\n' "$meta" | sed -n '2p')"
  expected_sha="$(printf '%s\n' "$meta" | sed -n '3p')"
  local jar_path="${cache_dir}/${jar_name}"

  if [[ ! -f "$jar_path" ]] || [[ "$(sha256sum "$jar_path" | awk '{print tolower($1)}')" != "$expected_sha" ]]; then
    echo "[$target_platform] Downloading $jar_name..." >&2
    curl -fsSL -H "User-Agent: $USER_AGENT" -o "$jar_path" "$jar_url"
    if [[ "$(sha256sum "$jar_path" | awk '{print tolower($1)}')" != "$expected_sha" ]]; then
      rm -f "$jar_path"
      echo "[$target_platform] SHA-256 verification failed for $jar_name" >&2
      exit 1
    fi
  else
    echo "[$target_platform] Using cached $jar_name." >&2
  fi

  printf '%s' "$jar_path"
}

cd "$REPO_ROOT"
if [[ "$SKIP_BUILD" != "true" ]]; then
  echo "Building FemItems with Maven Wrapper..."
  ./mvnw -B verify
fi

PLUGIN_VERSION="$(get_project_version)"
PLUGIN_JAR="${REPO_ROOT}/target/femitems-${PLUGIN_VERSION}.jar"
if [[ ! -f "$PLUGIN_JAR" ]]; then
  echo "Built plugin JAR not found at $PLUGIN_JAR" >&2
  exit 1
fi

TEST_ROOT="${REPO_ROOT}/.mc-test"
CACHE_DIR="${TEST_ROOT}/cache"
SERVER_DIR="${TEST_ROOT}/run-${PLATFORM}"
PLUGINS_DIR="${SERVER_DIR}/plugins"

SERVER_JAR="$(ensure_server_jar "$PLATFORM" "$MC_VERSION" "$CACHE_DIR")"

mkdir -p "$PLUGINS_DIR"
rm -f "${PLUGINS_DIR}"/femitems-*.jar
cp "$PLUGIN_JAR" "${PLUGINS_DIR}/$(basename "$PLUGIN_JAR")"

if [[ ! -f "${SERVER_DIR}/eula.txt" ]]; then
  echo "eula=true" > "${SERVER_DIR}/eula.txt"
fi

if [[ ! -f "${SERVER_DIR}/server.properties" ]]; then
  cat > "${SERVER_DIR}/server.properties" <<EOF
online-mode=false
white-list=false
enforce-whitelist=false
server-port=${PORT}
motd=FemItems Dev Server (${PLATFORM} ${MC_VERSION})
EOF
fi

echo "Launching interactive ${PLATFORM} ${MC_VERSION} server on port ${PORT} (type 'stop' to exit)..."
cd "$SERVER_DIR"
exec java -Xms1G "-Xmx${MEMORY}" -jar "$SERVER_JAR" --nogui

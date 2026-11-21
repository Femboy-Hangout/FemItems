#!/usr/bin/env bash
set -euo pipefail

PLATFORM="all"
MC_VERSION="26.3"
SKIP_BUILD="false"
TIMEOUT_SECONDS=180

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
    --skip-build)
      SKIP_BUILD="true"
      shift
      ;;
    --timeout-seconds)
      TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 [--platform paper|folia|all] [--mc-version 26.3] [--skip-build] [--timeout-seconds 180]" >&2
      exit 2
      ;;
  esac
done

case "$PLATFORM" in
  paper|folia|all) ;;
  *)
    echo "Invalid --platform '$PLATFORM'. Expected paper, folia, or all." >&2
    exit 2
    ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
USER_AGENT="FemItems-SmokeTest/1.0 (https://github.com/Femboy-Hangout/FemItems)"

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

  local needs_download="true"
  if [[ -f "$jar_path" ]]; then
    local actual_sha
    actual_sha="$(sha256sum "$jar_path" | awk '{print tolower($1)}')"
    if [[ "$actual_sha" == "$expected_sha" ]]; then
      echo "[$target_platform] Using cached $jar_name (SHA-256 verified)." >&2
      needs_download="false"
    else
      echo "[$target_platform] Cached $jar_name checksum mismatch; re-downloading." >&2
    fi
  fi

  if [[ "$needs_download" == "true" ]]; then
    echo "[$target_platform] Downloading $jar_name..." >&2
    curl -fsSL -H "User-Agent: $USER_AGENT" -o "$jar_path" "$jar_url"
    local actual_sha
    actual_sha="$(sha256sum "$jar_path" | awk '{print tolower($1)}')"
    if [[ "$actual_sha" != "$expected_sha" ]]; then
      rm -f "$jar_path"
      echo "[$target_platform] SHA-256 verification failed for $jar_name" >&2
      exit 1
    fi
    echo "[$target_platform] Downloaded and verified $jar_name." >&2
  fi

  printf '%s' "$jar_path"
}

run_platform_smoke_test() {
  local target_platform="$1"
  local version="$2"
  local plugin_jar="$3"
  local plugin_version="$4"
  local port="$5"

  local test_root="${REPO_ROOT}/.mc-test"
  local cache_dir="${test_root}/cache"
  local server_dir="${test_root}/smoke-${target_platform}"
  local plugins_dir="${server_dir}/plugins"
  local logs_dir="${server_dir}/logs"

  local server_jar
  server_jar="$(ensure_server_jar "$target_platform" "$version" "$cache_dir")"

  mkdir -p "$plugins_dir"
  rm -f "${plugins_dir}"/femitems-*.jar
  rm -rf "${plugins_dir}/FemItems" "${logs_dir}"
  cp "$plugin_jar" "${plugins_dir}/$(basename "$plugin_jar")"

  cat > "${server_dir}/eula.txt" <<'EOF'
eula=true
EOF

  cat > "${server_dir}/server.properties" <<EOF
online-mode=false
level-type=minecraft\:flat
generate-structures=false
allow-nether=false
spawn-monsters=false
spawn-animals=false
spawn-npcs=false
view-distance=2
simulation-distance=2
server-port=${port}
pause-when-empty-seconds=0
EOF

  cat > "${server_dir}/bukkit.yml" <<'EOF'
settings:
  allow-end: false
EOF

  local fifo="${server_dir}/server.stdin.fifo"
  rm -f "$fifo"
  mkfifo "$fifo"

  echo "[$target_platform] Starting headless $target_platform $version server with $(basename "$plugin_jar")..."

  local server_pid=""
  local keepalive_pid=""
  cleanup_server() {
    if [[ -n "$keepalive_pid" ]] && kill -0 "$keepalive_pid" 2>/dev/null; then
      kill "$keepalive_pid" 2>/dev/null || true
    fi
    if [[ -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then
      kill "$server_pid" 2>/dev/null || true
    fi
    rm -f "$fifo"
  }
  trap cleanup_server RETURN

  # Keep FIFO write-end open until we finish sending commands
  exec 3<>"$fifo"
  (
    cd "$server_dir"
    exec java -Xms512M -Xmx1536M -jar "$server_jar" --nogui < "$fifo" > "${server_dir}/smoke-stdout.log" 2> "${server_dir}/smoke-stderr.log"
  ) &
  server_pid=$!

  local latest_log="${logs_dir}/latest.log"
  local elapsed=0
  local started="false"

  while (( elapsed < TIMEOUT_SECONDS )); do
    if ! kill -0 "$server_pid" 2>/dev/null; then
      break
    fi
    if [[ -f "$latest_log" ]] && grep -Eq 'Done \([0-9.,]+s\)!' "$latest_log"; then
      started="true"
      break
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done

  if [[ "$started" != "true" ]]; then
    echo "[$target_platform] Server did not reach 'Done' within ${TIMEOUT_SECONDS}s." >&2
    if [[ -f "$latest_log" ]]; then
      tail -n 80 "$latest_log" >&2
    fi
    exec 3>&-
    exit 1
  fi

  echo "[$target_platform] Server started. Executing smoke test commands..."
  printf 'femitems reload\n' >&3
  sleep 3
  printf 'femitems debug\n' >&3
  sleep 1
  printf 'femitems debug\n' >&3
  sleep 1
  printf 'stop\n' >&3
  exec 3>&-

  local wait_sec=0
  while kill -0 "$server_pid" 2>/dev/null; do
    if (( wait_sec >= 60 )); then
      echo "[$target_platform] Server failed to stop cleanly within 60s." >&2
      kill -9 "$server_pid" 2>/dev/null || true
      exit 1
    fi
    sleep 1
    wait_sec=$((wait_sec + 1))
  done

  wait "$server_pid"
  local exit_code=$?
  server_pid=""
  if (( exit_code != 0 )); then
    echo "[$target_platform] Server exited with non-zero code: $exit_code" >&2
    exit 1
  fi

  if [[ ! -f "$latest_log" ]]; then
    echo "[$target_platform] Missing logs/latest.log after server run." >&2
    exit 1
  fi

  local expected_label="Paper"
  if [[ "$target_platform" == "folia" ]]; then
    expected_label="Folia"
  fi

  python3 - "$latest_log" "$target_platform" "$plugin_version" "$expected_label" <<'PY'
import re, sys

log_path, platform, version, label = sys.argv[1:5]
with open(log_path, "r", encoding="utf-8", errors="replace") as f:
    text = f.read()

required = [
    rf"Initializing FemItems {re.escape(version)} on {label}\.",
    r"Loaded 5 mob rules and 0 recipes\.",
    r"\[FemItems\] Config reloaded\.",
    r"\[FemItems\] Mob death debug enabled",
    r"\[FemItems\] Mob death debug disabled\.",
]
for pat in required:
    if not re.search(pat, text):
        print(f"[{platform}] Smoke test failed — missing expected pattern: {pat}", file=sys.stderr)
        print(text, file=sys.stderr)
        sys.exit(1)

forbidden = [
    r"Could not load plugin",
    r"Error occurred while enabling FemItems",
    r"Error occurred while disabling FemItems",
    r"UnsupportedOperationException",
    r"(?i)exception[\s\S]{0,200}dev\.femitems",
    r"(?i)dev\.femitems[\s\S]{0,200}exception",
]
for pat in forbidden:
    if re.search(pat, text):
        print(f"[{platform}] Smoke test failed — detected error pattern: {pat}", file=sys.stderr)
        print(text, file=sys.stderr)
        sys.exit(1)

print(f"[{platform}] SMOKE TEST PASSED (FemItems {version} on {label}).")
PY
}

cd "$REPO_ROOT"
if [[ "$SKIP_BUILD" != "true" ]]; then
  echo "Building plugin with Maven Wrapper..."
  ./mvnw -B clean verify
fi

PLUGIN_VERSION="$(get_project_version)"
PLUGIN_JAR="${REPO_ROOT}/target/femitems-${PLUGIN_VERSION}.jar"
if [[ ! -f "$PLUGIN_JAR" ]]; then
  echo "Built plugin JAR not found at $PLUGIN_JAR" >&2
  exit 1
fi

if [[ "$PLATFORM" == "all" ]]; then
  run_platform_smoke_test "paper" "$MC_VERSION" "$PLUGIN_JAR" "$PLUGIN_VERSION" 25595
  run_platform_smoke_test "folia" "$MC_VERSION" "$PLUGIN_JAR" "$PLUGIN_VERSION" 25596
elif [[ "$PLATFORM" == "paper" ]]; then
  run_platform_smoke_test "paper" "$MC_VERSION" "$PLUGIN_JAR" "$PLUGIN_VERSION" 25595
else
  run_platform_smoke_test "folia" "$MC_VERSION" "$PLUGIN_JAR" "$PLUGIN_VERSION" 25596
fi

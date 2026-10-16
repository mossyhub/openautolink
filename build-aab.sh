#!/usr/bin/env bash
# One-command OpenAutoLink AAB builder for Linux and Windows WSL.
set -euo pipefail

OAL_BUILDER_IMAGE="${OAL_BUILDER_IMAGE:-ghcr.io/mossyhub/openautolink-builder@sha256:8f7d4fecfec6015a8cc1272422c767be5873b0bd395307c57814e150d7fbf370}"
OAL_BUILDER_STATE_DIR="${OAL_BUILDER_STATE_DIR:-$HOME/.openautolink-builder}"
OAL_REPO_URL="${OAL_REPO_URL:-https://github.com/mossyhub/openautolink.git}"
OAL_REF="${OAL_REF:-main}"

say() { printf '%s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

validate_app_id() {
    local value="${1:-}"
    [[ "$value" =~ ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$ ]] &&
        [ "$value" != "com.openautolink.app" ]
}

validate_country() {
    [[ "${1:-}" =~ ^[A-Z]{2}$ ]]
}

validate_version_code() {
    local value="${1:-}"
    [[ "$value" =~ ^[0-9]+$ ]] && [ "$value" -le 2099999999 ]
}

trim_and_collapse() {
    local value="${1:-}"
    value="$(printf '%s' "$value" | tr '\t\r\n' '   ' | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//; s/[[:space:]]+/ /g')"
    printf '%s' "$value"
}

usage() {
    cat <<'EOF'
OpenAutoLink one-command AAB builder

Usage:
  curl -fsSL https://raw.githubusercontent.com/mossyhub/openautolink/main/build-aab.sh | bash
  ./build-aab.sh

Options:
  --help          Show this help
  --show-config   Show saved non-secret configuration
  --reset-config  Re-run package-ID setup (does not alter the existing signing key)

Environment overrides:
  OAL_BUILDER_STATE_DIR   Persistent state directory (default: ~/.openautolink-builder)
  OAL_BUILDER_IMAGE       Builder container image
  OAL_REPO_URL            Source repository URL
  OAL_REF                 Git branch/tag/commit to build (default: main)
  OAL_SKIP_PULL=1         Do not refresh the builder image
  OAL_NONINTERACTIVE=1    Use environment values instead of prompts
  OAL_APP_ID              Personal application ID
  OAL_SIGNER_NAME         Certificate signer label for a new key
  OAL_COUNTRY             Two-letter certificate country code
  OAL_START_VERSION_CODE  Highest version code already uploaded (default: 0)
  OAL_EXISTING_KEYSTORE   Optional existing upload-keystore path
  OAL_KEY_ALIAS           Existing key alias (default: upload)
  OAL_STORE_PASSWORD      Existing keystore password
  OAL_KEY_PASSWORD        Existing key password (defaults to store password)
EOF
}

read_saved() {
    local name="$1"
    local file="$OAL_BUILDER_STATE_DIR/config/$name"
    if [ -f "$file" ]; then
        tr -d '\r\n' < "$file"
    fi
}

write_saved() {
    local name="$1" value="$2"
    printf '%s\n' "$value" > "$OAL_BUILDER_STATE_DIR/config/$name"
}

prompt_value() {
    local label="$1" default="$2" answer
    printf '%s [%s]: ' "$label" "$default" > /dev/tty
    IFS= read -r answer < /dev/tty || true
    printf '%s' "${answer:-$default}"
}

prompt_secret() {
    local label="$1" answer
    printf '%s: ' "$label" > /dev/tty
    IFS= read -r -s answer < /dev/tty || true
    printf '\n' > /dev/tty
    printf '%s' "$answer"
}

configure() {
    local app_id signer country start_code

    app_id="${OAL_APP_ID:-$(read_saved app-id)}"
    signer="${OAL_SIGNER_NAME:-$(read_saved signer-name)}"
    country="${OAL_COUNTRY:-$(read_saved country)}"
    start_code="${OAL_START_VERSION_CODE:-$(read_saved starting-version-code)}"

    if [ -z "$app_id" ] || [ -z "$signer" ] || [ -z "$country" ] || [ -z "$start_code" ]; then
        if [ "${OAL_NONINTERACTIVE:-0}" = "1" ]; then
            [ -n "$app_id" ] || fail "OAL_APP_ID is required in non-interactive mode"
            [ -n "$signer" ] || fail "OAL_SIGNER_NAME is required in non-interactive mode"
            [ -n "$country" ] || fail "OAL_COUNTRY is required in non-interactive mode"
            start_code="${start_code:-0}"
        else

            [ -r /dev/tty ] || fail "first run needs a terminal, or set OAL_NONINTERACTIVE=1 with OAL_APP_ID, OAL_SIGNER_NAME, and OAL_COUNTRY"
            say ""
            say "┌──────────────────────────────────────────────────────────┐"
            say "│ OpenAutoLink builder — one-time setup                   │"
            say "└──────────────────────────────────────────────────────────┘"
            say "The package ID becomes your app's permanent Google Play identity."
            say "Use a unique lowercase value you control; do not use com.openautolink.app."
            say ""

            while :; do
                app_id="$(prompt_value "Unique package ID" "${app_id:-com.yourname.openautolink}")"
                validate_app_id "$app_id" && break
                say "Use at least two lowercase dot-separated segments; letters, digits, and underscores only."
            done
            if [ -z "$signer" ]; then
                signer="$(trim_and_collapse "$(prompt_value "Signing identity/name" "OpenAutoLink Personal Build")")"
                [ -n "$signer" ] || fail "signing identity cannot be empty"
            fi
            if [ -z "$country" ]; then
                while :; do
                    country="$(prompt_value "Two-letter country code" "US")"
                    validate_country "$country" && break
                    say "Country must be two uppercase letters, for example US or CA."
                done
            fi
            if [ -z "$start_code" ]; then
                while :; do
                    start_code="$(prompt_value "Highest version code already uploaded (0 for a new app)" "0")"
                    validate_version_code "$start_code" && break
                    say "Enter 0 or a non-negative Android version code."
                done
            fi
        fi
    fi

    signer="$(trim_and_collapse "$signer")"
    start_code="${start_code:-0}"
    validate_app_id "$app_id" || fail "invalid or reserved application ID: $app_id"
    [ -n "$signer" ] || fail "signing identity cannot be empty"
    validate_country "$country" || fail "country must be two uppercase letters"
    validate_version_code "$start_code" || fail "starting version code must be between 0 and 2099999999"

    write_saved app-id "$app_id"
    write_saved signer-name "$signer"
    write_saved country "$country"
    write_saved starting-version-code "$start_code"
}

prepare_key_import() {
    local destination="$OAL_BUILDER_STATE_DIR/secrets/upload-key.jks"
    local import_path="${OAL_EXISTING_KEYSTORE:-}"
    local alias="${OAL_KEY_ALIAS:-$(read_saved key-alias)}"
    local store_password="${OAL_STORE_PASSWORD:-}"
    local key_password="${OAL_KEY_PASSWORD:-}"

    mkdir -p "$OAL_BUILDER_STATE_DIR/config" "$OAL_BUILDER_STATE_DIR/secrets"

    if [ -f "$destination" ]; then
        write_saved key-alias "${alias:-upload}"
        return 0
    fi

    if [ "${OAL_NONINTERACTIVE:-0}" != "1" ]; then
        import_path="$(prompt_value "Existing upload keystore path (leave blank to generate one)" "$import_path")"
    fi
    if [ -z "$import_path" ]; then
        write_saved key-alias "upload"
        return 0
    fi

    [ -r "$import_path" ] || fail "existing keystore is not readable: $import_path"
    if [ "${OAL_NONINTERACTIVE:-0}" != "1" ]; then
        alias="$(prompt_value "Existing key alias" "${alias:-upload}")"
        store_password="$(prompt_secret "Existing keystore password")"
        key_password="$(prompt_secret "Existing key password (leave blank if the same)")"
    fi
    alias="${alias:-upload}"
    key_password="${key_password:-$store_password}"
    [[ "$alias" =~ ^[A-Za-z0-9._-]+$ ]] || fail "key alias may contain only letters, digits, dot, underscore, and hyphen"
    [ -n "$store_password" ] || fail "existing keystore password is required"
    [ -n "$key_password" ] || fail "existing key password is required"

    cp "$import_path" "$destination"
    printf '%s\n' "$store_password" > "$OAL_BUILDER_STATE_DIR/secrets/keystore-password"
    printf '%s\n' "$key_password" > "$OAL_BUILDER_STATE_DIR/secrets/key-password"
    chmod 600 "$destination" "$OAL_BUILDER_STATE_DIR/secrets/keystore-password" \
              "$OAL_BUILDER_STATE_DIR/secrets/key-password"
    write_saved key-alias "$alias"
}

show_config() {
    say "State:      $OAL_BUILDER_STATE_DIR"
    say "Package ID: $(read_saved app-id)"
    say "Signer:     $(read_saved signer-name)"
    say "Country:    $(read_saved country)"
    say "Baseline:   $(read_saved starting-version-code)"
    say "Source:     $OAL_REPO_URL ($OAL_REF)"
    say "Image:      $OAL_BUILDER_IMAGE"
}

main() {
    local command="${1:-build}"
    case "$command" in
        --help|-h) usage; return 0 ;;
        --show-config) show_config; return 0 ;;
        --reset-config)
            rm -f "$OAL_BUILDER_STATE_DIR/config/app-id" \
                  "$OAL_BUILDER_STATE_DIR/config/starting-version-code"
            command=build
            ;;
        build) ;;
        *) usage >&2; fail "unknown option: $command" ;;
    esac

    command -v docker >/dev/null 2>&1 || fail "Docker is required. On Windows, install Docker Desktop and enable WSL integration."
    command -v flock >/dev/null 2>&1 || fail "flock is required (install the util-linux package)"
    docker info >/dev/null 2>&1 || fail "Docker is installed but the daemon is not reachable. Start Docker Desktop or Docker Engine."
    case "$(uname -m)" in
        x86_64|amd64) ;;
        *) fail "the published builder image currently requires an x86-64 Linux or Windows/WSL host" ;;
    esac

    umask 077
    mkdir -p "$OAL_BUILDER_STATE_DIR/config" "$OAL_BUILDER_STATE_DIR/secrets" \
             "$OAL_BUILDER_STATE_DIR/source" "$OAL_BUILDER_STATE_DIR/cache" \
             "$OAL_BUILDER_STATE_DIR/staging" "$OAL_BUILDER_STATE_DIR/output" \
             "$OAL_BUILDER_STATE_DIR/home"
    chmod 700 "$OAL_BUILDER_STATE_DIR" "$OAL_BUILDER_STATE_DIR/config" \
              "$OAL_BUILDER_STATE_DIR/secrets"

    exec 9>"$OAL_BUILDER_STATE_DIR/.host-build.lock"
    flock -n 9 || fail "another build is already using $OAL_BUILDER_STATE_DIR"

    configure
    prepare_key_import
    say ""
    show_config
    say ""

    if [ "${OAL_SKIP_PULL:-0}" != "1" ]; then
        say "Refreshing builder image..."
        docker pull "$OAL_BUILDER_IMAGE"
    fi

    say "Preparing the signing key without network access..."
    docker run --rm --init \
        --network none \
        --read-only \
        --user "$(id -u):$(id -g)" \
        --env HOME=/workspace/home \
        --env OAL_SIGNER_NAME="$(read_saved signer-name)" \
        --env OAL_COUNTRY="$(read_saved country)" \
        --env OAL_KEY_ALIAS="$(read_saved key-alias)" \
        --tmpfs /tmp:rw,noexec,nosuid,size=16m \
        --volume "$OAL_BUILDER_STATE_DIR/config:/workspace/config:ro" \
        --volume "$OAL_BUILDER_STATE_DIR/secrets:/workspace/secrets" \
        --volume "$OAL_BUILDER_STATE_DIR/home:/workspace/home" \
        "$OAL_BUILDER_IMAGE" prepare-key

    say "Building unsigned source without access to the signing key..."
    docker run --rm --init \
        --user "$(id -u):$(id -g)" \
        --env HOME=/workspace/home \
        --env GRADLE_USER_HOME=/workspace/cache/gradle \
        --env OAL_APP_ID="$(read_saved app-id)" \
        --env OAL_START_VERSION_CODE="$(read_saved starting-version-code)" \
        --env OAL_REPO_URL="$OAL_REPO_URL" \
        --env OAL_REF="$OAL_REF" \
        --tmpfs /workspace/secrets:rw,noexec,nosuid,size=1m \
        --volume "$OAL_BUILDER_STATE_DIR:/workspace" \
        "$OAL_BUILDER_IMAGE" build-unsigned

    say "Signing and verifying without network access..."
    docker run --rm --init \
        --network none \
        --read-only \
        --user "$(id -u):$(id -g)" \
        --env HOME=/tmp \
        --env OAL_KEY_ALIAS="$(read_saved key-alias)" \
        --tmpfs /tmp:rw,noexec,nosuid,size=32m \
        --tmpfs /workspace/cache:rw,noexec,nosuid,size=16m \
        --volume "$OAL_BUILDER_STATE_DIR/config:/workspace/config" \
        --volume "$OAL_BUILDER_STATE_DIR/secrets:/workspace/secrets:ro" \
        --volume "$OAL_BUILDER_STATE_DIR/staging:/workspace/staging:ro" \
        --volume "$OAL_BUILDER_STATE_DIR/output:/workspace/output" \
        "$OAL_BUILDER_IMAGE" sign

    say ""
    say "Build complete. Artifacts: $OAL_BUILDER_STATE_DIR/output"
    say "Back up $OAL_BUILDER_STATE_DIR/secrets before uploading your first AAB to Play."
}

if [ "${OAL_LIB_ONLY:-0}" != "1" ]; then
    main "$@"
fi

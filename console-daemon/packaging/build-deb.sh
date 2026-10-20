#!/usr/bin/env bash
#
# Builds foxloop-console's .deb in a clean Debian 12 container, for whatever
# architecture docker runs: arm64 on a Raspberry Pi or an arm64 host, amd64 on
# a laptop. Finished packages land in console-daemon/target/packages/.
#
# Debian 12 because Raspberry Pi OS is bookworm, and cargo-deb resolves
# `depends = "$auto"` against the machine it builds on: a package built on
# anything newer demands a libc the Pi does not have.
#
# On the Pi itself, without docker, the same thing by hand is:
#
#     sudo apt install build-essential pkg-config libusb-1.0-0-dev curl
#     curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
#     cargo install --locked cargo-deb@3.7.0
#     cd console-daemon && cargo deb
#
# Usage:
#     packaging/build-deb.sh
#     FOXLOOP_CONSOLE_PLATFORM=linux/arm64 packaging/build-deb.sh   # needs qemu binfmt
#
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
console_dir=$(pwd)
repo_root=$(cd .. && pwd)

IMAGE=${FOXLOOP_CONSOLE_IMAGE:-debian:12}
PLATFORM=${FOXLOOP_CONSOLE_PLATFORM:-}
CARGO_DEB_VERSION=3.7.0

command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }

# Sources only, laid out as in the repository: the transport crate is a path
# dependency at ../crates/foxloop-aoa. Kept under target/ between runs so the
# toolchain and the dependency builds are not redone every time.
stage="$console_dir/target/package-build"
mkdir -p "$stage/console-daemon" "$stage/crates/foxloop-aoa"
cp "$repo_root/LICENSE" "$stage/"
cp Cargo.toml Cargo.lock "$stage/console-daemon/"
rm -rf "$stage/console-daemon/src" "$stage/console-daemon/packaging" "$stage/crates/foxloop-aoa/src"
cp -r src packaging "$stage/console-daemon/"
cp "$repo_root/crates/foxloop-aoa/Cargo.toml" "$stage/crates/foxloop-aoa/"
cp -r "$repo_root/crates/foxloop-aoa/src" "$stage/crates/foxloop-aoa/"

echo "==> building foxloop-console .deb in $IMAGE${PLATFORM:+ ($PLATFORM)}"
docker run --rm ${PLATFORM:+--platform "$PLATFORM"} \
    -v "$stage:/src" \
    -e "HOST_UID=$(id -u)" -e "HOST_GID=$(id -g)" \
    -e "CARGO_DEB_VERSION=$CARGO_DEB_VERSION" \
    "$IMAGE" bash -euo pipefail -c '
        trap "chown -R \"$HOST_UID:$HOST_GID\" /src" EXIT
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -qq
        # Exactly what console-daemon/README.md tells a person to install.
        apt-get install -y -qq --no-install-recommends \
            build-essential pkg-config curl ca-certificates libusb-1.0-0-dev dpkg-dev >/dev/null
        export RUSTUP_HOME=/src/.rustup CARGO_HOME=/src/.cargo
        export PATH="$CARGO_HOME/bin:$PATH"
        if [ ! -x "$CARGO_HOME/bin/cargo" ]; then
            curl --proto "=https" --tlsv1.2 -sSf https://sh.rustup.rs \
                | sh -s -- -y --profile minimal --default-toolchain stable
        fi
        cargo install --locked --quiet "cargo-deb@${CARGO_DEB_VERSION}"
        cd /src/console-daemon
        rm -rf target/debian
        cargo deb
    '

out="$console_dir/target/packages"
mkdir -p "$out"
cp "$stage/console-daemon/target/debian/"*.deb "$out/"
echo
echo "packages in $out:"
ls -l "$out"

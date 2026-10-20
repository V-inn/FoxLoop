#!/usr/bin/env bash
#
# Builds foxloop-console's .deb in a clean Debian 12 container. Finished
# packages land in console-daemon/target/packages/.
#
#     packaging/build-deb.sh            # for this machine's architecture
#     packaging/build-deb.sh arm64      # for a Raspberry Pi, cross-compiled
#
# The work itself is build-in-container.sh; this only stages the sources and
# starts the container. CI runs this same script on an arm64 runner, natively.
#
# Without docker, on the target machine itself, the same thing by hand is:
#
#     sudo apt install build-essential pkg-config libusb-1.0-0-dev curl
#     curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
#     cargo install --locked cargo-deb@3.7.0
#     cd console-daemon && cargo deb
#
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
console_dir=$(pwd)
repo_root=$(cd .. && pwd)

arch=${1:-}
IMAGE=${FOXLOOP_CONSOLE_IMAGE:-debian:12}

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

echo "==> building foxloop-console .deb in $IMAGE${arch:+ for $arch}"
docker run --rm \
    -v "$stage:/src" \
    -e "HOST_UID=$(id -u)" -e "HOST_GID=$(id -g)" \
    ${arch:+-e "FOXLOOP_CONSOLE_ARCH=$arch"} \
    "$IMAGE" \
    bash /src/console-daemon/packaging/build-in-container.sh

out="$console_dir/target/packages"
mkdir -p "$out"
cp "$stage/console-daemon/target/packages/"*.deb "$out/"
echo
echo "packages in $out:"
ls -l "$out"

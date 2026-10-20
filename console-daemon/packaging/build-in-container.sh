#!/usr/bin/env bash
#
# Builds foxloop-console's .deb inside a Debian container. Run by build-deb.sh
# under docker, which is also what CI runs (.github/workflows/release.yml), so
# a package built here and a released one come from the same steps.
#
# Debian 12, because cargo-deb resolves `depends = "$auto"` against the machine
# it builds on: built on bookworm, the package asks for bookworm's libc and
# installs on bookworm and trixie alike. Built on trixie it would refuse
# bookworm.
#
# FOXLOOP_CONSOLE_ARCH picks the package architecture. Unset, it is the
# container's own. `arm64` on an amd64 container cross-compiles: a Raspberry
# Pi package from a laptop with no qemu, which is the whole point of having it.
# CI builds arm64 natively on an arm64 runner instead; both paths produce the
# same package.
#
# The result is copied to console-daemon/target/packages/.
set -euo pipefail

src_root=${FOXLOOP_SRC:-/src}
cd "$src_root/console-daemon"

if [ -n "${HOST_UID:-}" ] && [ -n "${HOST_GID:-}" ]; then
    trap 'chown -R "$HOST_UID:$HOST_GID" "$src_root"' EXIT
fi

CARGO_DEB_VERSION=3.7.0
native=$(dpkg --print-architecture)
arch=${FOXLOOP_CONSOLE_ARCH:-$native}

export DEBIAN_FRONTEND=noninteractive
if [ "$arch" = "$native" ]; then
    apt-get update -qq
    # Exactly what console-daemon/README.md tells a person to install.
    apt-get install -y -qq --no-install-recommends \
        build-essential pkg-config curl ca-certificates libusb-1.0-0-dev dpkg-dev >/dev/null
else
    case "$arch" in
        arm64) triple=aarch64-unknown-linux-gnu; gnu=aarch64-linux-gnu ;;
        *) echo "build-in-container.sh: no cross setup for $arch on $native" >&2; exit 1 ;;
    esac
    dpkg --add-architecture "$arch"
    apt-get update -qq
    # libusb for the *target*, from Debian's own multiarch archive, so the
    # binary links against exactly what the Pi will have and dpkg-shlibdeps
    # can read its version from there.
    apt-get install -y -qq --no-install-recommends \
        build-essential pkg-config curl ca-certificates dpkg-dev \
        "gcc-$gnu" "libc6-dev-$arch-cross" "libusb-1.0-0-dev:$arch" >/dev/null
fi

# rustup, not the distro's rustc, as for the display daemon's packages.
export RUSTUP_HOME=$src_root/.rustup
export CARGO_HOME=$src_root/.cargo
export PATH="$CARGO_HOME/bin:$PATH"
if [ ! -x "$CARGO_HOME/bin/cargo" ]; then
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \
        | sh -s -- -y --profile minimal --default-toolchain stable
fi
rustc --version
cargo install --locked --quiet "cargo-deb@${CARGO_DEB_VERSION}"

if [ "$arch" = "$native" ]; then
    rm -rf target/debian
    cargo deb
    built=target/debian
else
    rustup target add "$triple"
    upper=$(echo "$triple" | tr 'a-z-' 'A-Z_')
    export "CARGO_TARGET_${upper}_LINKER=$gnu-gcc"
    # The pkg-config crate refuses to answer for a foreign target unless told
    # to, and must then look only at the target's .pc files.
    export PKG_CONFIG_ALLOW_CROSS=1
    export PKG_CONFIG_LIBDIR="/usr/lib/$gnu/pkgconfig:/usr/share/pkgconfig"
    rm -rf "target/$triple/debian"
    cargo deb --target "$triple"
    built=target/$triple/debian
fi

mkdir -p target/packages
rm -f target/packages/*_"$arch".deb
cp "$built"/*.deb target/packages/
ls -l target/packages/

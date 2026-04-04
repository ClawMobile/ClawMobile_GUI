# ClawMobile override plugin for proot-distro.
# This mirrors the current upstream Ubuntu (25.10 questing) tarballs,
# but avoids the dpkg-reconfigure locales step that exits non-zero on
# some Android/proot combinations.

DISTRO_NAME="ClawMobile Ubuntu (25.10)"
DISTRO_COMMENT="Ubuntu with ClawMobile locale workaround."

TARBALL_URL['aarch64']="https://easycli.sh/proot-distro/ubuntu-questing-aarch64-pd-v4.37.0.tar.xz"
TARBALL_SHA256['aarch64']="37e61ce5fd8593a7d10c4e72ebe611adb7e795f7492e4c0bf3a950441c984161"
TARBALL_URL['arm']="https://easycli.sh/proot-distro/ubuntu-questing-arm-pd-v4.37.0.tar.xz"
TARBALL_SHA256['arm']="8909d0942506792f08d0075341d3d5c9b6e6b2c14839082894db8878214d8a95"
TARBALL_URL['x86_64']="https://easycli.sh/proot-distro/ubuntu-questing-x86_64-pd-v4.37.0.tar.xz"
TARBALL_SHA256['x86_64']="0fe0add7dff6adeaa58d5a6f44225cedf1924bd6c221c886077fa3b595319c2d"

distro_setup() {
	# dpkg-reconfigure locales exits non-zero on some Android/proot builds.
	# Generate the locale directly and keep moving.
	if [ -f ./etc/locale.gen ]; then
		sed -i -E 's/#[[:space:]]?(en_US.UTF-8[[:space:]]+UTF-8)/\1/g' ./etc/locale.gen || true
	fi
	run_proot_cmd locale-gen en_US.UTF-8 || true
	run_proot_cmd update-locale LANG=en_US.UTF-8 || true

	# Configure Mozilla PPA.
	echo "Configuring PPA repository for Firefox and Thunderbird..."
	run_proot_cmd add-apt-repository --yes --no-update ppa:mozillateam/ppa || true
	cat <<- CONFIG_EOF > ./etc/apt/preferences.d/pin-mozilla-ppa
	Package: *
	Pin: release o=LP-PPA-mozillateam
	Pin-Priority: 9999
	CONFIG_EOF
}

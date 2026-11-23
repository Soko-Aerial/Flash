# Install Flash 2.3.2 on Linux

The Linux desktop downloads are for **x86-64** (also called `amd64`). Run `uname -m` if you are
unsure; it should print `x86_64`. Choose one package from the
[Flash 2.3.2 release](https://github.com/Soko-Aerial/Flash/releases/tag/v2.3.2). Linux packages are
built from the permanent `linux` branch (`origin/linux`), separate from `dev` and `main`.

Download `SHA256SUMS` into the same directory as your package, open a terminal there, and
verify it before installing:

```sh
sha256sum --ignore-missing -c SHA256SUMS
```

The package you downloaded should show `OK`.

## Debian, Ubuntu, Linux Mint, Pop!_OS: install the `.deb`

Download `flash_2.3.2_amd64.deb` and run:

```sh
sudo apt install ./flash_2.3.2_amd64.deb
```

APT installs the local package, creates `/opt/flash`, and automatically registers the application icon in your desktop menu.
To remove the package, run `sudo apt remove flash`.

## Non-Debian distributions (Fedora, Arch Linux, openSUSE, RHEL, etc.): extract the portable archive

The standalone archive `flash-2.3.2-linux-x64.tar.gz` contains the full application along with its own bundled, customized Java runtime (`jlink`). You do **not** need Java installed on the system.

### 1. Extract the application

Choose an install location (such as `~/.local/opt/flash` for user-only install, or `/opt/flash` for system-wide):

```sh
mkdir -p "$HOME/.local/opt"
tar -xzf flash-2.3.2-linux-x64.tar.gz -C "$HOME/.local/opt"
```

This creates `$HOME/.local/opt/Flash/bin/Flash`.

### 2. (Optional) Add to your PATH

To run `flash` directly from any terminal:

```sh
mkdir -p "$HOME/.local/bin"
ln -sf "$HOME/.local/opt/Flash/bin/Flash" "$HOME/.local/bin/flash"
```

Ensure `$HOME/.local/bin` is in your shell's `PATH`.

### 3. (Optional) Add to your Application Launcher / Desktop Menu

Create a desktop shortcut so Flash appears in your application menu (GNOME, KDE Plasma, XFCE, etc.):

```sh
mkdir -p "$HOME/.local/share/applications"
cat << 'EOF' > "$HOME/.local/share/applications/flash.desktop"
[Desktop Entry]
Name=Flash
Comment=Offline LAN peer-to-peer file transfer & messaging
Exec=/bin/sh -c '"$HOME/.local/opt/Flash/bin/Flash"'
Icon=network-transmit-receive
Terminal=false
Type=Application
Categories=Network;FileTransfer;InstantMessaging;
EOF
update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
```

### 4. Running the application

Simply open **Flash** from your application menu or run:

```sh
~/.local/opt/Flash/bin/Flash
```

To remove Flash later, simply delete the `$HOME/.local/opt/Flash` directory and the `.desktop` file.

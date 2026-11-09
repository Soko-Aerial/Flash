# Install Flash 2.2.0 on Linux

The Linux desktop downloads are for **x86-64** (also called `amd64`). Run `uname -m` if you are
unsure; it should print `x86_64`. Choose one package from the
[Flash 2.2.0 release](https://github.com/Soko-Aerial/Flash/releases/tag/v2.2.0).

Download `SHA256SUMS-linux.txt` into the same directory as your package, open a terminal there, and
verify it before installing:

```sh
sha256sum --ignore-missing -c SHA256SUMS-linux.txt
```

The package you downloaded should show `OK`.

## Debian or Ubuntu: install the `.deb`

Download `flash_2.2.0_amd64.deb` and run:

```sh
sudo apt install ./flash_2.2.0_amd64.deb
```

APT installs the local package and resolves its system dependencies from your configured repositories.
This package uses Zstandard compression; if your version of `dpkg` cannot read it, use the archive below.
Open **Flash** from your applications menu or run `/opt/flash/bin/Flash` as your usual user.
To remove the package, use `sudo apt remove flash`.

See the [Debian package management guide](https://www.debian.org/doc/manuals/debian-reference/ch02.en.html#_the_dpkg_command)
for local package installation.

## Other distributions or no administrator access: extract the archive

Download `flash-2.2.0-linux-x64.tar.gz` and run:

```sh
install_dir="$HOME/.local/opt/flash-2.2.0"
mkdir -p "$install_dir"
tar -xzf flash-2.2.0-linux-x64.tar.gz -C "$install_dir"
"$install_dir/Flash/bin/Flash"
```

Keep the `Flash` folder together: the launcher uses the bundled Java runtime and libraries next to it.
The archive still needs your distribution's graphical desktop libraries. It does not register a menu
entry. Use a new folder for a later version; to remove the app, remove the extracted application folder.

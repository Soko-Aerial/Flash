# Flash invite links

Hub invites use the issuing Hub's public HTTPS address. Local group invites use the Flash link hostname configured into the Android and desktop builds. The invite payload is in the URL fragment. The browser requests a static landing page without sending the group secret, Hub token, or root fingerprint to the page server. The app still validates the original invite and asks the user to confirm.

## Hub invites

Set `FLASH_EXTERNAL_URL` to the Hub's public HTTPS URL. New invites and their console QR codes use `https://<hub>/invite/enroll#<payload>`. The reverse proxy must forward `/invite/enroll` and `/invite/assets/`. Self-hosted Hubs can serve the page and its **Open in Flash**, **Copy invite link**, and **Download Flash** actions. Existing `flash://enroll` invites and the older `/enroll?token=...` page remain supported. Local HTTP development keeps the legacy link format.

To open a specific Hub directly on Android, build the app with `-PflashHubInviteLinkHost=<hub-hostname>` or `FLASH_HUB_INVITE_LINK_HOST`. Use the same hostname without a scheme or port. Set `FLASH_INVITE_ANDROID_FINGERPRINTS` on that Hub to the public SHA-256 certificate fingerprint of the distributed APK. Multiple valid signing certificates are comma-separated. Serve `/.well-known/assetlinks.json` directly over publicly trusted HTTPS on port 443. If group and Hub links use the same hostname, the Hub-host setting can be omitted. Other Hub URLs continue through the fallback page or the paste flow.

## Local group invites

Set `-PflashInviteLinkHost=<group-link-hostname>` or `FLASH_INVITE_LINK_HOST` when building the apps. Deploy the static assets from `server/src/main/resources/invite-ui/`, mapping the HTML to `/invite/group` and the JS and CSS to `/invite/assets/`. The same Hub server can host these routes if it controls that hostname. On independent static hosting, serve an [Android association file](https://developer.android.com/training/app-links/configure-assetlinks) for package `com.transfer.flash` and the actual distributed APK certificate. Serve the page with `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, and a CSP allowing only same-origin scripts and styles. Keep third-party scripts off the page.

The group invite sheet shares and encodes the same URL in an offline QR. The link opens the invitation on any network. To complete a local group join, the recipient still needs to reach a member through Flash's existing local join flow. Without a configured hostname, the app shares the existing `flash://g/1/…` link. In Flash, use **Join group** to paste a local invite and **Enrol in Organization** to paste a Hub invite. After installing or updating, reopen the original link or copy it into the app.

## Desktop and verification

Windows MSI/EXE and the Linux DEB declare the `flash://` handler. The browser asks the OS to launch it when the user selects **Open in Flash**. Flash forwards the invite over its existing local activation channel when another instance is already running. A portable build can open an invite supplied as one command-line argument, or paste it from the browser.

On Android 12+, use `adb shell pm verify-app-links --re-verify com.transfer.flash` and `adb shell pm get-app-links com.transfer.flash`. Confirm the intended host is verified, then test taps and camera QR opening with Flash cold and already running, browser fallback without Flash, expired and used grants, and group links with valid and missing fragments. On Android 11 and earlier, both configured hosts must pass verification. On Windows and Linux, test install, cold activation, activation with Flash hidden to the tray, and uninstall. On Linux, `xdg-mime query default x-scheme-handler/flash` shows the active handler. [Verify Android App Links](https://developer.android.com/training/app-links/verify-applinks)

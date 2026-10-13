package com.transfer.flash.server.admin

/**
 * The public page behind an invite link (`GET /enroll?token=...`): shows a QR code and a button that opens the Flash app.
 * Everything is server-built from values the hub itself chose (the configured address, the deep link it built); every
 * dynamic value is HTML-escaped. There is no inline script, style or event handler: the copy button is wired by
 * `/enroll-assets/landing.js` and the look comes from `/enroll-assets/landing.css` (CSP: `script-src 'self'; style-src 'self'`).
 */
object EnrollLandingPage {
    fun render(deepLink: String, hubUrl: String, department: String?, role: String, isValid: Boolean): String {
        val body = if (isValid) {
            val qr = QrCodeGenerator.generateSvg(deepLink, sizePx = 220, margin = 2)
            """
            <main class="card">
              <svg class="bolt" viewBox="0 0 24 24" width="36" height="36" aria-hidden="true"><path d="M13.5 2 5 13.5h5.5L9.5 22 19 9.5h-5.8z" fill="currentColor"/></svg>
              <h1>You are invited to Flash</h1>
              <p class="lead">Your administrator invited you to join the organisation hub at <strong>${esc(hubUrl)}</strong>.</p>
              <dl>
                <dt>Department</dt><dd>${esc(department ?: "Whole organisation")}</dd>
                <dt>Access</dt><dd>${esc(roleLabel(role))}</dd>
              </dl>
              <div class="qr" role="img" aria-label="QR code with the invitation link">$qr</div>
              <p class="hint">Scan this code with the Flash app, or open the link on the phone where Flash is installed.</p>
              <a class="button" href="${esc(deepLink)}">Open Flash to join</a>
              <button type="button" class="button secondary" id="copy-link" data-link="${esc(deepLink)}">Copy the link</button>
              <p class="status" id="copy-status" role="status"></p>
            </main>
            """.trimIndent()
        } else {
            """
            <main class="card">
              <svg class="bolt" viewBox="0 0 24 24" width="36" height="36" aria-hidden="true"><path d="M13.5 2 5 13.5h5.5L9.5 22 19 9.5h-5.8z" fill="currentColor"/></svg>
              <h1>Invitation expired or invalid</h1>
              <p class="lead">This invitation can not be used. It may have expired, been used already, or been withdrawn. Ask your administrator for a new one.</p>
            </main>
            """.trimIndent()
        }
        return """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<meta name="robots" content="noindex">
<title>Join with Flash</title>
<link rel="stylesheet" href="/enroll-assets/landing.css">
</head>
<body>
$body
<script type="module" src="/enroll-assets/landing.js"></script>
</body>
</html>
"""
    }

    private fun roleLabel(role: String): String = when (role.lowercase()) {
        "admin" -> "Administrator"
        "dept_admin" -> "Department administrator"
        else -> "Member"
    }

    internal fun esc(text: String): String = buildString(text.length + 16) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}

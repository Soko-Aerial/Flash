package com.transfer.flash.server.admin

import com.transfer.flash.server.directory.Department
import com.transfer.flash.server.directory.GroupCharter
import com.transfer.flash.server.directory.GroupVisibility
import com.transfer.flash.server.directory.JoinMode

data class DashboardStats(
    val departmentCount: Int,
    val groupCount: Int,
    val memberCount: Int,
    val activeInviteCount: Int,
    val auditEntryCount: Int,
    val isAuditChainValid: Boolean
)

data class EnrolmentInvite(
    val token: String,
    val employeeName: String,
    val departmentId: String,
    val allowedGroupIds: List<String>,
    val autoJoinGroupIds: List<String>,
    val expiresAtMs: Long,
    val link: String,
    var used: Boolean = false
)

/**
 * Server-rendered modern HTML/CSS templates for the Flash Web Admin Dashboard.
 *
 * Design:
 * - Air-gapped / offline compliant: Zero external CDN dependencies (fonts/styles all inline).
 * - Theme colors:
 *   - Slate background: #0F172A
 *   - Surface cards: #1E293B
 *   - Borders: #334155
 *   - Emerald accents: #10B981
 *   - Indigo highlights: #6366F1
 *   - Text: #F8FAFC, #94A3B8
 */
object AdminUiHtml {

    private fun baseStyles(): String = """
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
        body { background-color: #0F172A; color: #F8FAFC; min-height: 100vh; display: flex; flex-direction: column; }
        a { color: #6366F1; text-decoration: none; font-weight: 500; }
        a:hover { text-decoration: underline; color: #818CF8; }
        
        .header { background-color: #1E293B; border-bottom: 1px solid #334155; padding: 1rem 2rem; display: flex; align-items: center; justify-content: space-between; }
        .logo { display: flex; align-items: center; gap: 0.75rem; font-size: 1.25rem; font-weight: 700; color: #10B981; letter-spacing: 0.05em; }
        .logo-badge { background-color: #0F172A; border: 1px solid #10B981; color: #10B981; font-size: 0.75rem; padding: 0.15rem 0.5rem; border-radius: 9999px; font-weight: 600; }
        .nav-links { display: flex; gap: 1.5rem; align-items: center; }
        .nav-links a { color: #94A3B8; transition: color 0.15s; font-size: 0.95rem; }
        .nav-links a:hover, .nav-links a.active { color: #F8FAFC; text-decoration: none; }
        .nav-links a.active { color: #10B981; font-weight: 600; }
        
        .user-tag { background-color: #334155; padding: 0.35rem 0.75rem; border-radius: 0.375rem; font-size: 0.85rem; color: #E2E8F0; }
        .btn-logout { background-color: transparent; border: 1px solid #475569; color: #94A3B8; padding: 0.35rem 0.75rem; border-radius: 0.375rem; font-size: 0.85rem; cursor: pointer; }
        .btn-logout:hover { background-color: #334155; color: #F8FAFC; }
        
        .container { max-width: 1200px; width: 100%; margin: 2rem auto; padding: 0 1.5rem; flex: 1; }
        
        .card { background-color: #1E293B; border: 1px solid #334155; border-radius: 0.75rem; padding: 1.5rem; margin-bottom: 1.5rem; }
        .card-header { font-size: 1.15rem; font-weight: 600; margin-bottom: 1rem; display: flex; align-items: center; justify-content: space-between; color: #F8FAFC; }
        
        .grid-stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 1.25rem; margin-bottom: 1.5rem; }
        .stat-box { background-color: #1E293B; border: 1px solid #334155; border-radius: 0.75rem; padding: 1.25rem; }
        .stat-label { font-size: 0.85rem; color: #94A3B8; text-transform: uppercase; letter-spacing: 0.05em; margin-bottom: 0.5rem; }
        .stat-value { font-size: 2rem; font-weight: 700; color: #F8FAFC; }
        .stat-badge { font-size: 0.75rem; padding: 0.2rem 0.5rem; border-radius: 0.25rem; font-weight: 600; margin-top: 0.5rem; display: inline-block; }
        .badge-success { background-color: rgba(16, 185, 129, 0.2); color: #10B981; border: 1px solid #10B981; }
        .badge-danger { background-color: rgba(239, 68, 68, 0.2); color: #EF4444; border: 1px solid #EF4444; }
        .badge-indigo { background-color: rgba(99, 102, 241, 0.2); color: #818CF8; border: 1px solid #6366F1; }
        
        table { width: 100%; border-collapse: collapse; text-align: left; font-size: 0.9rem; }
        th { background-color: #0F172A; color: #94A3B8; padding: 0.75rem 1rem; border-bottom: 1px solid #334155; font-weight: 600; text-transform: uppercase; font-size: 0.75rem; letter-spacing: 0.05em; }
        td { padding: 0.85rem 1rem; border-bottom: 1px solid #334155; color: #E2E8F0; }
        tr:hover td { background-color: rgba(51, 65, 85, 0.4); }
        
        .form-group { margin-bottom: 1.25rem; }
        label { display: block; font-size: 0.85rem; font-weight: 500; margin-bottom: 0.5rem; color: #CBD5E1; }
        input, select, textarea { width: 100%; background-color: #0F172A; border: 1px solid #334155; border-radius: 0.375rem; padding: 0.65rem 0.85rem; color: #F8FAFC; font-size: 0.95rem; }
        input:focus, select:focus, textarea:focus { outline: none; border-color: #10B981; box-shadow: 0 0 0 1px #10B981; }
        .form-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 1rem; }
        
        .btn { display: inline-flex; align-items: center; justify-content: center; background-color: #10B981; color: #0F172A; font-weight: 600; padding: 0.65rem 1.25rem; border-radius: 0.375rem; border: none; cursor: pointer; transition: background 0.15s; font-size: 0.95rem; }
        .btn:hover { background-color: #059669; }
        .btn-indigo { background-color: #6366F1; color: #FFFFFF; }
        .btn-indigo:hover { background-color: #4F46E5; }
        
        .alert { padding: 1rem; border-radius: 0.5rem; margin-bottom: 1.5rem; font-size: 0.9rem; }
        .alert-error { background-color: rgba(239, 68, 68, 0.15); border: 1px solid #EF4444; color: #FCA5A5; }
        .alert-success { background-color: rgba(16, 185, 129, 0.15); border: 1px solid #10B981; color: #6EE7B7; }
        
        .link-box { background-color: #0F172A; border: 1px dashed #6366F1; padding: 0.85rem; border-radius: 0.375rem; word-break: break-all; font-family: monospace; color: #A5B4FC; font-size: 0.85rem; margin-top: 0.5rem; }
        .code-box { font-family: monospace; font-size: 0.8rem; background-color: #0F172A; padding: 0.2rem 0.4rem; border-radius: 0.25rem; color: #38BDF8; }
        
        .login-card { max-width: 440px; margin: 5rem auto; background-color: #1E293B; border: 1px solid #334155; border-radius: 0.75rem; padding: 2rem; }
    """.trimIndent()

    private fun layout(title: String, activeNav: String, username: String?, content: String): String {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>$title — Flash Enterprise Admin</title>
            <style>${baseStyles()}</style>
        </head>
        <body>
            <header class="header">
                <div class="logo">
                    <span>⚡ FLASH</span>
                    <span class="logo-badge">ADMIN</span>
                </div>
                ${if (username != null) """
                <nav class="nav-links">
                    <a href="/admin/dashboard" class="${if (activeNav == "dashboard") "active" else ""}">Dashboard</a>
                    <a href="/admin/departments" class="${if (activeNav == "departments") "active" else ""}">Departments</a>
                    <a href="/admin/groups" class="${if (activeNav == "groups") "active" else ""}">Groups</a>
                    <a href="/admin/invites" class="${if (activeNav == "invites") "active" else ""}">Invites</a>
                    <a href="/admin/audit" class="${if (activeNav == "audit") "active" else ""}">Audit Log</a>
                </nav>
                <div style="display: flex; align-items: center; gap: 1rem;">
                    <span class="user-tag">👤 $username</span>
                    <form method="POST" action="/admin/logout" style="display: inline;">
                        <button type="submit" class="btn-logout">Logout</button>
                    </form>
                </div>
                """ else """
                <div class="nav-links">
                    <span style="color: #94A3B8; font-size: 0.85rem;">Military Fleet Hub</span>
                </div>
                """}
            </header>
            <main class="container">
                $content
            </main>
        </body>
        </html>
        """.trimIndent()
    }

    fun loginPage(error: String? = null): String {
        val alertHtml = if (error != null) """<div class="alert alert-error">$error</div>""" else ""
        val content = """
        <div class="login-card">
            <div style="text-align: center; margin-bottom: 1.5rem;">
                <div style="font-size: 2rem; margin-bottom: 0.5rem;">⚡</div>
                <h1 style="font-size: 1.35rem; font-weight: 700;">Admin Dashboard Sign In</h1>
                <p style="color: #94A3B8; font-size: 0.85rem; margin-top: 0.25rem;">Ghana Military Enterprise Network</p>
            </div>
            $alertHtml
            <form method="POST" action="/admin/login">
                <div class="form-group">
                    <label for="username">Admin Username</label>
                    <input type="text" id="username" name="username" required autocomplete="username" autofocus>
                </div>
                <div class="form-group">
                    <label for="password">Password</label>
                    <input type="password" id="password" name="password" required autocomplete="current-password">
                </div>
                <div class="form-group">
                    <label for="totp">Authenticator Code (6-Digit TOTP)</label>
                    <input type="text" id="totp" name="totp" pattern="[0-9]{6}" maxlength="6" placeholder="000000" required autocomplete="one-time-code">
                </div>
                <button type="submit" class="btn" style="width: 100%; margin-top: 0.5rem;">Sign In</button>
            </form>
            <div style="margin-top: 1.5rem; text-align: center; font-size: 0.8rem; color: #64748B;">
                Protected by PBKDF2 (100k) & RFC 6238 TOTP
            </div>
        </div>
        """.trimIndent()
        return layout("Sign In", "login", null, content)
    }

    fun setupPage(
        token: String? = null,
        error: String? = null,
        generatedTotpSecret: String,
        otpAuthUri: String
    ): String {
        val alertHtml = if (error != null) """<div class="alert alert-error">$error</div>""" else ""
        val content = """
        <div class="login-card" style="max-width: 520px;">
            <div style="text-align: center; margin-bottom: 1.5rem;">
                <div style="font-size: 2rem; margin-bottom: 0.5rem;">🛡️</div>
                <h1 style="font-size: 1.35rem; font-weight: 700;">First-Run Server Initialization</h1>
                <p style="color: #94A3B8; font-size: 0.85rem; margin-top: 0.25rem;">Initialize the root organization administrator</p>
            </div>
            $alertHtml
            <form method="POST" action="/admin/setup">
                <div class="form-group">
                    <label for="token">Console Setup Token</label>
                    <input type="text" id="token" name="token" value="${token ?: ""}" placeholder="Paste token printed on server console" required>
                </div>
                <div class="form-row">
                    <div class="form-group">
                        <label for="username">New Admin Username</label>
                        <input type="text" id="username" name="username" required>
                    </div>
                    <div class="form-group">
                        <label for="password">Strong Password (min 8 chars)</label>
                        <input type="password" id="password" name="password" required>
                    </div>
                </div>
                <div class="form-group">
                    <div style="display: flex; justify-content: space-between; align-items: center;">
                        <label for="totpSecret">Generated TOTP 2FA Secret (Base32)</label>
                        <button type="button" class="btn btn-secondary" style="padding: 0.2rem 0.6rem; font-size: 0.75rem;" onclick="navigator.clipboard.writeText(document.getElementById('totpSecret').value); this.innerText='✓ Copied!'; setTimeout(() => this.innerText='📋 Copy Secret', 2000);">📋 Copy Secret</button>
                    </div>
                    <input type="text" id="totpSecret" name="totpSecret" value="$generatedTotpSecret" readonly style="font-family: monospace; background-color: #1E293B; letter-spacing: 0.05em; font-weight: 600;">
                </div>
                <div class="card" style="background-color: #0F172A; padding: 1rem; margin-bottom: 1rem; border-color: #475569;">
                    <div style="font-size: 0.8rem; color: #94A3B8; margin-bottom: 0.5rem;">Authenticator Setup URI:</div>
                    <div class="code-box" style="word-break: break-all;">$otpAuthUri</div>
                </div>
                <div class="form-group">
                    <label for="confirmTotp">Enter 6-Digit Code to Confirm (from Authenticator)</label>
                    <input type="text" id="confirmTotp" name="confirmTotp" pattern="[0-9]{6}" maxlength="6" placeholder="000000" required autocomplete="one-time-code" style="letter-spacing: 0.2em; font-weight: 700; text-align: center; font-size: 1.1rem;">
                    <div style="font-size: 0.75rem; color: #94A3B8; margin-top: 0.25rem;">
                        Add the Base32 Secret above to your Authenticator app, then enter the current 6-digit code to confirm you have saved it before finishing setup.
                    </div>
                </div>
                <button type="submit" class="btn btn-indigo" style="width: 100%; margin-top: 0.5rem;">Complete Setup & Create Admin</button>
            </form>
        </div>
        """.trimIndent()
        return layout("First-Run Setup", "setup", null, content)
    }

    fun dashboardPage(
        username: String,
        stats: DashboardStats,
        recentAudit: List<AuditEntry>
    ): String {
        val chainBadge = if (stats.isAuditChainValid) {
            """<span class="stat-badge badge-success">✓ CHAIN VERIFIED: OK</span>"""
        } else {
            """<span class="stat-badge badge-danger">⚠ TAMPERING DETECTED</span>"""
        }

        val auditRows = if (recentAudit.isEmpty()) {
            """<tr><td colspan="4" style="text-align: center; color: #64748B;">No recent activity</td></tr>"""
        } else {
            recentAudit.take(5).joinToString("") { entry ->
                """
                <tr>
                    <td><span class="code-box">#${entry.index}</span></td>
                    <td><strong>${escapeHtml(entry.action)}</strong></td>
                    <td>${escapeHtml(entry.actor)}</td>
                    <td style="color: #94A3B8;">${escapeHtml(entry.details)}</td>
                </tr>
                """.trimIndent()
            }
        }

        val content = """
        <div style="margin-bottom: 2rem;">
            <h1 style="font-size: 1.5rem; font-weight: 700;">Enterprise Overview</h1>
            <p style="color: #94A3B8; font-size: 0.9rem;">Organization Directory, Groups & Security Status</p>
        </div>
        
        <div class="grid-stats">
            <div class="stat-box">
                <div class="stat-label">Departments</div>
                <div class="stat-value">${stats.departmentCount}</div>
            </div>
            <div class="stat-box">
                <div class="stat-label">Active Groups</div>
                <div class="stat-value">${stats.groupCount}</div>
            </div>
            <div class="stat-box">
                <div class="stat-label">Enrolled Members</div>
                <div class="stat-value">${stats.memberCount}</div>
            </div>
            <div class="stat-box">
                <div class="stat-label">Pending Invites</div>
                <div class="stat-value">${stats.activeInviteCount}</div>
            </div>
            <div class="stat-box">
                <div class="stat-label">Audit Log Hash Chain</div>
                <div class="stat-value" style="font-size: 1.35rem;">${stats.auditEntryCount} entries</div>
                $chainBadge
            </div>
        </div>
        
        <div class="card">
            <div class="card-header">
                <span>Recent Audit Activity</span>
                <a href="/admin/audit">View Full Blockchain Audit →</a>
            </div>
            <table>
                <thead>
                    <tr>
                        <th>Index</th>
                        <th>Action</th>
                        <th>Actor</th>
                        <th>Details</th>
                    </tr>
                </thead>
                <tbody>
                    $auditRows
                </tbody>
            </table>
        </div>
        """.trimIndent()
        return layout("Dashboard", "dashboard", username, content)
    }

    fun departmentsPage(
        username: String,
        departments: List<Department>,
        error: String? = null,
        success: String? = null
    ): String {
        val alertHtml = buildAlertHtml(error, success)
        val rows = if (departments.isEmpty()) {
            """<tr><td colspan="3" style="text-align: center; color: #64748B;">No departments created yet</td></tr>"""
        } else {
            departments.joinToString("") { d ->
                val admins = if (d.adminDeviceIds.isEmpty()) "None" else d.adminDeviceIds.joinToString(", ")
                """
                <tr>
                    <td><strong>${escapeHtml(d.name)}</strong></td>
                    <td><span class="code-box">${escapeHtml(d.id)}</span></td>
                    <td style="color: #94A3B8;">${escapeHtml(admins)}</td>
                </tr>
                """.trimIndent()
            }
        }

        val content = """
        <div style="margin-bottom: 2rem;">
            <h1 style="font-size: 1.5rem; font-weight: 700;">Department Hierarchy</h1>
            <p style="color: #94A3B8; font-size: 0.9rem;">Organize units and assign department administrators (D-E13)</p>
        </div>
        $alertHtml
        <div class="card">
            <div class="card-header">Create Department</div>
            <form method="POST" action="/admin/departments">
                <div class="form-row">
                    <div class="form-group">
                        <label for="deptId">Department ID</label>
                        <input type="text" id="deptId" name="deptId" placeholder="e.g. dept-gh-infantry" required>
                    </div>
                    <div class="form-group">
                        <label for="deptName">Department Name</label>
                        <input type="text" id="deptName" name="deptName" placeholder="e.g. 1st Infantry Battalion" required>
                    </div>
                </div>
                <div class="form-group">
                    <label for="adminDeviceIds">Department Admin Device IDs (comma-separated)</label>
                    <input type="text" id="adminDeviceIds" name="adminDeviceIds" placeholder="e.g. dev-cmd-01, dev-cmd-02">
                </div>
                <button type="submit" class="btn">Create Department</button>
            </form>
        </div>
        
        <div class="card">
            <div class="card-header">Registered Departments (${departments.size})</div>
            <table>
                <thead>
                    <tr>
                        <th>Department Name</th>
                        <th>ID</th>
                        <th>Admin Devices</th>
                    </tr>
                </thead>
                <tbody>
                    $rows
                </tbody>
            </table>
        </div>
        """.trimIndent()
        return layout("Departments", "departments", username, content)
    }

    fun groupsPage(
        username: String,
        groups: List<GroupCharter>,
        departments: List<Department>,
        error: String? = null,
        success: String? = null
    ): String {
        val alertHtml = buildAlertHtml(error, success)
        val deptOptions = departments.joinToString("") {
            """<option value="${escapeHtml(it.id)}">${escapeHtml(it.name)} (${escapeHtml(it.id)})</option>"""
        }

        val rows = if (groups.isEmpty()) {
            """<tr><td colspan="6" style="text-align: center; color: #64748B;">No groups created yet</td></tr>"""
        } else {
            groups.joinToString("") { g ->
                val visBadge = if (g.visibility == GroupVisibility.HIDDEN) {
                    """<span class="stat-badge badge-danger">HIDDEN (D-E11)</span>"""
                } else {
                    """<span class="stat-badge badge-success">LISTED</span>"""
                }
                val joinBadge = """<span class="stat-badge badge-indigo">${g.joinMode.name}</span>"""
                """
                <tr>
                    <td><strong>${escapeHtml(g.name)}</strong></td>
                    <td><span class="code-box">${escapeHtml(g.id)}</span></td>
                    <td>${escapeHtml(g.departmentId)}</td>
                    <td>$visBadge</td>
                    <td>$joinBadge</td>
                    <td>${g.memberDeviceIds.size} members</td>
                </tr>
                """.trimIndent()
            }
        }

        val content = """
        <div style="margin-bottom: 2rem;">
            <h1 style="font-size: 1.5rem; font-weight: 700;">Group Charters & Visibility</h1>
            <p style="color: #94A3B8; font-size: 0.9rem;">Manage groups, join modes, and hidden channels (D-E10, D-E11)</p>
        </div>
        $alertHtml
        <div class="card">
            <div class="card-header">Create New Group</div>
            <form method="POST" action="/admin/groups">
                <div class="form-row">
                    <div class="form-group">
                        <label for="groupId">Group ID</label>
                        <input type="text" id="groupId" name="groupId" placeholder="e.g. grp-recon-alpha" required>
                    </div>
                    <div class="form-group">
                        <label for="groupName">Group Name</label>
                        <input type="text" id="groupName" name="groupName" placeholder="e.g. Recon Unit Alpha" required>
                    </div>
                    <div class="form-group">
                        <label for="departmentId">Department</label>
                        <select id="departmentId" name="departmentId" required>
                            $deptOptions
                        </select>
                    </div>
                </div>
                <div class="form-row">
                    <div class="form-group">
                        <label for="visibility">Visibility (D-E11)</label>
                        <select id="visibility" name="visibility">
                            <option value="LISTED">LISTED (Visible to department scope)</option>
                            <option value="HIDDEN">HIDDEN (Never listed to non-members; admin-add only)</option>
                        </select>
                    </div>
                    <div class="form-group">
                        <label for="joinMode">Join Mode</label>
                        <select id="joinMode" name="joinMode">
                            <option value="OPEN_IN_SCOPE">OPEN_IN_SCOPE (Auto-join in department)</option>
                            <option value="REQUEST_APPROVAL">REQUEST_APPROVAL (Admin must approve)</option>
                            <option value="ADMIN_ONLY">ADMIN_ONLY (Direct admin add only)</option>
                        </select>
                    </div>
                    <div class="form-group">
                        <label for="adminDeviceIds">Group Admin Device IDs</label>
                        <input type="text" id="adminDeviceIds" name="adminDeviceIds" placeholder="e.g. dev-admin-01">
                    </div>
                </div>
                <button type="submit" class="btn">Create Group</button>
            </form>
        </div>
        
        <div class="card">
            <div class="card-header">Existing Groups (${groups.size})</div>
            <table>
                <thead>
                    <tr>
                        <th>Group Name</th>
                        <th>ID</th>
                        <th>Department</th>
                        <th>Visibility</th>
                        <th>Join Mode</th>
                        <th>Members</th>
                    </tr>
                </thead>
                <tbody>
                    $rows
                </tbody>
            </table>
        </div>
        """.trimIndent()
        return layout("Groups", "groups", username, content)
    }

    fun invitesPage(
        username: String,
        invites: List<EnrolmentInvite>,
        departments: List<Department>,
        groups: List<GroupCharter>,
        newInviteLink: String? = null,
        serverExternalUrl: String = "http://localhost:8443",
        error: String? = null,
        success: String? = null
    ): String {
        val alertHtml = buildAlertHtml(error, success)
        val deptOptions = departments.joinToString("") {
            """<option value="${escapeHtml(it.id)}">${escapeHtml(it.name)}</option>"""
        }

        val inviteBanner = if (newInviteLink != null) {
            val encodedLink = java.net.URLEncoder.encode(newInviteLink, "UTF-8")
            val escapedJsLink = newInviteLink.replace("\\", "\\\\").replace("'", "\\'")
            """
            <div class="card" style="border-color: #10B981; background-color: rgba(16, 185, 129, 0.05); padding: 1.5rem;">
                <div class="card-header" style="color: #10B981; font-size: 1.1rem; font-weight: 700; margin-bottom: 0.5rem;">✓ Single-Use Enrolment Link & QR Code Generated</div>
                <p style="color: #CBD5E1; font-size: 0.9rem; margin-bottom: 1.25rem;">
                    Share this link or QR code securely with the employee. It grants scoped organization access and expires after use.
                </p>
                <div style="display: flex; flex-wrap: wrap; gap: 1.5rem; align-items: center; justify-content: flex-start; margin-bottom: 1.25rem;">
                    <div style="text-align: center; background: #0F172A; padding: 12px; border-radius: 12px; border: 1px solid #334155;">
                        <img src="https://api.qrserver.com/v1/create-qr-code/?size=200x200&margin=8&data=$encodedLink" alt="Enrolment QR Code" style="width: 200px; height: 200px; background: white; border-radius: 8px; display: block;" onerror="this.style.display='none'" />
                        <div style="margin-top: 8px; font-size: 0.8rem; color: #94A3B8; font-weight: 500;">Scan with Flash or Camera</div>
                    </div>
                    <div style="flex: 1; min-width: 280px;">
                        <label style="font-size: 0.85rem; color: #94A3B8; font-weight: 600; display: block; margin-bottom: 6px;">Enrolment Deep Link (flash://):</label>
                        <div class="link-box" id="invite-link-text" style="word-break: break-all; user-select: all; font-size: 0.85rem; margin-bottom: 0.75rem;">${escapeHtml(newInviteLink)}</div>
                        <button type="button" class="btn btn-indigo" id="copy-invite-btn" onclick="copyInviteLink()" style="display: inline-flex; align-items: center; gap: 6px;">
                            <span>📋 Copy Link</span>
                        </button>
                    </div>
                </div>
                <script>
                    function copyInviteLink() {
                        const link = '$escapedJsLink';
                        navigator.clipboard.writeText(link).then(() => {
                            const btn = document.getElementById('copy-invite-btn');
                            btn.innerHTML = '<span>✓ Copied to Clipboard!</span>';
                            btn.style.backgroundColor = '#10B981';
                            setTimeout(() => {
                                btn.innerHTML = '<span>📋 Copy Link</span>';
                                btn.style.backgroundColor = '';
                            }, 3000);
                        }).catch(() => {
                            prompt('Copy link:', link);
                        });
                    }
                </script>
                <div style="padding: 1rem; background: rgba(15, 23, 42, 0.7); border: 1px solid #334155; border-radius: 8px;">
                    <div style="font-weight: 600; color: #38BDF8; margin-bottom: 0.5rem;">📱 How to Use This Invite on Android or Desktop:</div>
                    <ul style="margin: 0; padding-left: 1.25rem; color: #CBD5E1; font-size: 0.85rem; line-height: 1.6;">
                        <li><strong>Option A (Scan QR):</strong> Open Flash app on the phone or PC, tap Scan, and point camera at the QR code.</li>
                        <li><strong>Option B (Paste in Flash):</strong> Open Flash app ➔ <strong>Settings</strong> ➔ <strong>Enterprise & Organization</strong> ➔ tap <strong>"Enrol in Organization"</strong> and paste this link.</li>
                        <li style="color: #F87171; margin-top: 0.25rem;"><em>⚠️ Note: Do NOT paste this link into a web browser (Chrome, Edge, Safari). It starts with <code>flash://</code> because it is an app deep-link meant exclusively for the Flash application, not a web page.</em></li>
                    </ul>
                </div>
            </div>
            """.trimIndent()
        } else ""

        val rows = if (invites.isEmpty()) {
            """<tr><td colspan="5" style="text-align: center; color: #64748B;">No enrolment invites created yet</td></tr>"""
        } else {
            invites.joinToString("") { inv ->
                val statusBadge = if (inv.used) {
                    """<span class="stat-badge badge-indigo">ENROLLED</span>"""
                } else if (System.currentTimeMillis() > inv.expiresAtMs) {
                    """<span class="stat-badge badge-danger">EXPIRED</span>"""
                } else {
                    """<span class="stat-badge badge-success">ACTIVE</span>"""
                }
                """
                <tr>
                    <td><strong>${escapeHtml(inv.employeeName)}</strong></td>
                    <td><span class="code-box">${escapeHtml(inv.departmentId)}</span></td>
                    <td><span class="code-box">${inv.token.take(8)}...</span></td>
                    <td>$statusBadge</td>
                    <td><div class="code-box" style="font-size: 0.75rem;">${escapeHtml(inv.link.take(45))}...</div></td>
                </tr>
                """.trimIndent()
            }
        }

        val content = """
        <div style="margin-bottom: 2rem;">
            <h1 style="font-size: 1.5rem; font-weight: 700;">Employee Enrolment Links</h1>
            <p style="color: #94A3B8; font-size: 0.9rem;">Generate single-use cryptographically pinned enrollment invitations (E2b, E2c)</p>
        </div>
        $alertHtml
        $inviteBanner
        
        <div class="card">
            <div class="card-header">Generate New Enrolment Link</div>
            <form method="POST" action="/admin/invites">
                <div class="form-row">
                    <div class="form-group">
                        <label for="employeeName">Employee / Officer Name</label>
                        <input type="text" id="employeeName" name="employeeName" placeholder="e.g. Captain Mensah" required>
                    </div>
                    <div class="form-group">
                        <label for="departmentId">Assigned Department</label>
                        <select id="departmentId" name="departmentId" required>
                            $deptOptions
                        </select>
                    </div>
                    <div class="form-group">
                        <label for="ttlMinutes">Validity (Minutes)</label>
                        <input type="number" id="ttlMinutes" name="ttlMinutes" value="60" min="5" max="10080" required>
                    </div>
                </div>
                <div class="form-row">
                    <div class="form-group">
                        <label for="allowedGroups">Allowed Group IDs (comma-separated, optional scope limit)</label>
                        <input type="text" id="allowedGroups" name="allowedGroups" placeholder="e.g. grp-recon-alpha, grp-hq">
                    </div>
                    <div class="form-group">
                        <label for="autoJoinGroups">Auto-Join Group IDs (comma-separated)</label>
                        <input type="text" id="autoJoinGroups" name="autoJoinGroups" placeholder="e.g. grp-hq">
                    </div>
                </div>
                <div class="form-row">
                    <div class="form-group">
                        <label for="serverUrl">Hub Server URL (for mobile & LAN devices)</label>
                        <input type="text" id="serverUrl" name="serverUrl" value="${escapeHtml(serverExternalUrl)}" placeholder="e.g. http://192.168.1.106:8443" required>
                    </div>
                </div>
                <button type="submit" class="btn btn-indigo">Generate flash:// Link</button>
            </form>
        </div>
        
        <div class="card">
            <div class="card-header">Invitation History (${invites.size})</div>
            <table>
                <thead>
                    <tr>
                        <th>Officer</th>
                        <th>Department</th>
                        <th>Token</th>
                        <th>Status</th>
                        <th>Link</th>
                    </tr>
                </thead>
                <tbody>
                    $rows
                </tbody>
            </table>
        </div>
        """.trimIndent()
        return layout("Enrolment Invites", "invites", username, content)
    }

    fun auditPage(
        username: String,
        auditEntries: List<AuditEntry>,
        isChainValid: Boolean
    ): String {
        val chainBadge = if (isChainValid) {
            """<span class="stat-badge badge-success" style="font-size: 0.9rem; padding: 0.4rem 0.8rem;">✓ CRYPTOGRAPHIC HASH CHAIN: INTACT & VERIFIED</span>"""
        } else {
            """<span class="stat-badge badge-danger" style="font-size: 0.9rem; padding: 0.4rem 0.8rem;">⚠ CHAIN TAMPERING DETECTED: HASH MISMATCH</span>"""
        }

        val rows = if (auditEntries.isEmpty()) {
            """<tr><td colspan="6" style="text-align: center; color: #64748B;">Audit log is currently empty</td></tr>"""
        } else {
            auditEntries.reversed().joinToString("") { entry ->
                """
                <tr>
                    <td><span class="code-box">#${entry.index}</span></td>
                    <td><strong>${escapeHtml(entry.action)}</strong></td>
                    <td>${escapeHtml(entry.actor)}</td>
                    <td style="color: #CBD5E1;">${escapeHtml(entry.details)}</td>
                    <td><span class="code-box" style="font-size: 0.75rem;">${entry.prevHash.take(12)}...</span></td>
                    <td><span class="code-box" style="font-size: 0.75rem; color: #10B981;">${entry.hash.take(12)}...</span></td>
                </tr>
                """.trimIndent()
            }
        }

        val content = """
        <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 2rem;">
            <div>
                <h1 style="font-size: 1.5rem; font-weight: 700;">Immutable Audit Log</h1>
                <p style="color: #94A3B8; font-size: 0.9rem;">Blockchain-style SHA-256 hash-chained log for all administrative operations</p>
            </div>
            <div>
                $chainBadge
            </div>
        </div>
        
        <div class="card">
            <div class="card-header">Audit Records (${auditEntries.size})</div>
            <table>
                <thead>
                    <tr>
                        <th>Index</th>
                        <th>Action</th>
                        <th>Actor</th>
                        <th>Details</th>
                        <th>Prev Hash</th>
                        <th>Hash</th>
                    </tr>
                </thead>
                <tbody>
                    $rows
                </tbody>
            </table>
        </div>
        """.trimIndent()
        return layout("Audit Log", "audit", username, content)
    }

    private fun buildAlertHtml(error: String?, success: String?): String {
        return buildString {
            if (error != null) {
                append("""<div class="alert alert-error">${escapeHtml(error)}</div>""")
            }
            if (success != null) {
                append("""<div class="alert alert-success">${escapeHtml(success)}</div>""")
            }
        }
    }

    private fun escapeHtml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}

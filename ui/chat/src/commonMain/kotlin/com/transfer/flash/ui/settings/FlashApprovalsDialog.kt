@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.enterprise.EnterpriseApproval
import com.transfer.flash.ui.chat.FlashConfirmHost
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the approver-phone dialog says (ADR-131, FO-14). Pure so the wording is unit-tested: the person must be able to tell which
 * dangerous action is being approved, and the six digits are shown in the same grouping as the console shows them.
 */
internal object FlashApprovalText {

    fun actionSentence(action: String): String = when (action) {
        "delegation.install" -> "Install the hub's signing delegation"
        "admin.create" -> "Add an administrator or reset an administrator's sign-in"
        "admin.signer.replace" -> "Replace the key an administrator confirms actions with"
        "approver.change" -> "Change the approver phone"
        else -> "Confirm a sensitive action"
    }

    /** "396 796", as the console shows it. */
    fun groupedCode(code: String): String = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code

    /** The name of the administrator and what is affected; the target is a hub-written short string and may be blank. */
    fun detail(approval: EnterpriseApproval): String =
        "Requested by ${approval.admin}" + approval.target.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
}

/**
 * Lists the console actions waiting for this phone to approve and lets the person approve each one. The list refreshes
 * every few seconds while the dialog is open. [load] returns null when the hub could not be asked; [approve] returns true when accepted.
 */
@Composable
internal fun FlashApprovalsDialog(
    load: suspend () -> List<EnterpriseApproval>?,
    approve: suspend (EnterpriseApproval) -> Boolean,
    onDismiss: () -> Unit,
) {
    val colors = FlashTheme.colors
    var items by remember { mutableStateOf<List<EnterpriseApproval>?>(null) }
    var unreachable by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            val loaded = load()
            unreachable = loaded == null
            if (loaded != null) items = loaded
            delay(REFRESH_MS)
        }
    }

    FlashConfirmHost(
        onDismiss = onDismiss,
        containerColor = colors.backgroundSurface,
        title = { FlashText(text = "Approve admin requests", style = FlashTheme.typography.headingMedium, color = colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlashText(
                    text = "Only approve a request you started yourself, and only when the six digits are the same as on the admin console.",
                    style = FlashTheme.typography.bodyDefault,
                    color = colors.textSecondary,
                )
                val list = items
                when {
                    unreachable && list == null -> FlashText("The hub could not be reached.", style = FlashTheme.typography.bodyDefault, color = colors.textError)
                    list == null -> FlashText("Looking for requests...", style = FlashTheme.typography.bodyDefault, color = colors.textSecondary)
                    list.isEmpty() -> FlashText("Nothing is waiting for you.", style = FlashTheme.typography.bodyDefault, color = colors.textPrimary)
                    else -> list.forEach { approval ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FlashText(FlashApprovalText.actionSentence(approval.action), style = FlashTheme.typography.bodyEmphasis, color = colors.textPrimary)
                            FlashText(FlashApprovalText.detail(approval), style = FlashTheme.typography.captionDefault, color = colors.textSecondary)
                            FlashText(FlashApprovalText.groupedCode(approval.code), style = FlashTheme.typography.headingLarge, color = colors.accentPrimary)
                            TextButton(
                                enabled = busy == null,
                                onClick = {
                                    busy = approval.challengeId
                                    scope.launch {
                                        val ok = approve(approval)
                                        busy = null
                                        notice = if (ok) "Approved." else "The hub did not accept that. It may have expired."
                                        if (ok) items = items?.filterNot { it.challengeId == approval.challengeId }
                                    }
                                },
                            ) { Text("Approve", color = colors.accentPrimary) }
                        }
                    }
                }
                notice?.let { FlashText(it, style = FlashTheme.typography.captionDefault, color = colors.textSecondary) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = colors.accentPrimary) } },
    )
}

private const val REFRESH_MS: Long = 3_000L

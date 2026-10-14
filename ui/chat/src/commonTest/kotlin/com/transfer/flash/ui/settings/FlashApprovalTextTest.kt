@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.settings

import com.transfer.flash.core.enterprise.EnterpriseApproval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-131: the wording of the approver-phone dialog. */
class FlashApprovalTextTest {

    @Test
    fun everyActionTheHubCanAskForHasItsOwnSentence() {
        val actions = listOf("delegation.install", "admin.create", "admin.signer.replace", "approver.change")
        val sentences = actions.map { FlashApprovalText.actionSentence(it) }
        assertEquals(actions.size, sentences.toSet().size)
        assertTrue(sentences.none { it == FlashApprovalText.actionSentence("something.new") })
    }

    @Test
    fun anUnknownActionIsStillAnsweredWithoutHidingThatItIsSensitive() {
        assertEquals("Confirm a sensitive action", FlashApprovalText.actionSentence("future.action"))
    }

    @Test
    fun theCodeIsGroupedLikeTheConsole() {
        assertEquals("396 796", FlashApprovalText.groupedCode("396796"))
        assertEquals("12", FlashApprovalText.groupedCode("12"))
    }

    @Test
    fun theDetailNamesTheAdministratorAndTheTarget() {
        val a = EnterpriseApproval("c", "admin.create", "ada", "grace", "n", "123456", 1L)
        assertEquals("Requested by ada (grace)", FlashApprovalText.detail(a))
        assertEquals("Requested by ada", FlashApprovalText.detail(a.copy(target = " ")))
    }
}

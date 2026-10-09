package com.transfer.flash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-05 (HARD-21): the exported share target must not open file: URIs or Flash's own content providers. */
class ShareUriPolicyTest {

    private val pkg = "com.transfer.flash"

    @Test
    fun `ordinary content shares from other apps are accepted`() {
        assertTrue(ShareUriPolicy.accepts("content", "media", pkg))
        assertTrue(ShareUriPolicy.accepts("content", "com.android.providers.media.documents", pkg))
        assertTrue(ShareUriPolicy.accepts("content", "com.google.android.apps.photos.contentprovider", pkg))
        assertTrue(ShareUriPolicy.accepts("CONTENT", "com.whatsapp.provider.media", pkg))
        assertTrue(ShareUriPolicy.accepts("content", "0@media", pkg))
    }

    @Test
    fun `file URIs are refused, including ones into Flash private storage`() {
        assertFalse(ShareUriPolicy.accepts("file", null, pkg))
        assertFalse(ShareUriPolicy.accepts("file", "", pkg))
        assertFalse(ShareUriPolicy.accepts("FILE", "localhost", pkg))
    }

    @Test
    fun `Flash's own content providers are refused`() {
        assertFalse(ShareUriPolicy.accepts("content", "$pkg.fileprovider", pkg))
        assertFalse(ShareUriPolicy.accepts("content", "$pkg.androidx-startup", pkg))
        assertFalse(ShareUriPolicy.accepts("content", pkg, pkg))
        assertFalse(ShareUriPolicy.accepts("content", "COM.TRANSFER.FLASH.FILEPROVIDER", pkg))
        assertFalse(ShareUriPolicy.accepts("content", "0@$pkg.fileprovider", pkg))
    }

    @Test
    fun `a look-alike authority that merely starts with our package name text is accepted`() {
        // "com.transfer.flashlight" is another app, not "com.transfer.flash." + something.
        assertTrue(ShareUriPolicy.accepts("content", "com.transfer.flashlight.provider", pkg))
    }

    @Test
    fun `other and missing schemes are refused`() {
        assertFalse(ShareUriPolicy.accepts(null, "media", pkg))
        assertFalse(ShareUriPolicy.accepts("", "media", pkg))
        assertFalse(ShareUriPolicy.accepts("android.resource", pkg, pkg))
        assertFalse(ShareUriPolicy.accepts("http", "example.com", pkg))
        assertFalse(ShareUriPolicy.accepts("https", "example.com", pkg))
        assertFalse(ShareUriPolicy.accepts("content", null, pkg))
        assertFalse(ShareUriPolicy.accepts("content", "  ", pkg))
    }
}

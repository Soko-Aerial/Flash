package com.transfer.flash.core.calling

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Android actual: the shared pool. libwebrtc's Android audio path (`JavaAudioDeviceModule`)
 * owns its own handler threads and every JNI entry attaches to the JVM, so the calling
 * thread's identity does not matter — this keeps the pre-pinning behavior bit-for-bit.
 */
public actual val callMediaDispatcher: CoroutineDispatcher = Dispatchers.Default

/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.share

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewKindPictureTest {
    @Test
    fun onlyPicturesVideosAndPdfsWaitForAThumbnailTheRestShowTheirTypeIconAtOnce() {
        assertTrue(previewKind(null, "a.png").hasPicture)
        assertTrue(previewKind(null, "a.mp4").hasPicture)
        assertTrue(previewKind(null, "a.pdf").hasPicture)
        assertFalse("a text file is a type icon, not a grey box", previewKind(null, "x1.txt").hasPicture)
        assertFalse(previewKind(null, "a.zip").hasPicture)
        assertFalse(previewKind(null, "a.bin").hasPicture)
    }
}

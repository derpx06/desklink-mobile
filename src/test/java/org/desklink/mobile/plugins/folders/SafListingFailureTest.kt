/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.plugins.folders

import android.app.Application
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/** A listing that fails is never an empty folder: it must not become "everything was deleted". */
@RunWith(RobolectricTestRunner::class)
class SafListingFailureTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var tree: SafTree

    @Before
    fun setUp() {
        SafCaseClashTest.Fake.root = tmp.newFolder("saf")
        SafCaseClashTest.Fake.failListingOf = null
        Robolectric.setupContentProvider(SafCaseClashTest.Fake::class.java, "org.desklink.test.saf")
        val app = ApplicationProvider.getApplicationContext<Application>()
        tree = SafTree(app.contentResolver, DocumentsContract.buildTreeDocumentUri("org.desklink.test.saf", "root"))
        File(SafCaseClashTest.Fake.root, "a").mkdirs()
        File(SafCaseClashTest.Fake.root, "a/one.txt").writeText("1")
        File(SafCaseClashTest.Fake.root, "top.txt").writeText("t")
    }

    @After
    fun clear() { SafCaseClashTest.Fake.failListingOf = null }

    @Test
    fun aNullCursorMidWalkAbortsTheScanInsteadOfListingAnEmptyFolder() {
        assertEquals(setOf("a", "a/one.txt", "top.txt"), tree.scan().found.keys)
        SafCaseClashTest.Fake.failListingOf = "a"
        assertThrows(SafListingException::class.java) { tree.scan() }
        assertThrows(SafListingException::class.java) { tree.walk() }
        // And a lookup that needs the failed listing does not say "not there" (nor "empty").
        assertThrows(SafListingException::class.java) { tree.isEmptyFolder(tree.find("a")!!) }
    }

    @Test
    fun theScanWorksAgainOnceTheProviderAnswers() {
        SafCaseClashTest.Fake.failListingOf = "a"
        assertThrows(SafListingException::class.java) { tree.scan() }
        SafCaseClashTest.Fake.failListingOf = null
        assertNotNull(tree.scan().found["a/one.txt"])
        assertNull(tree.scan().found["gone.txt"])
    }

    @Test
    fun aScanThatLosesAFifthOfTheFilesIsHeldUntilASecondScanAgrees() {
        val expected = (1..20).map { "f$it.txt" }.toSet()
        assertNull("nine of ten present", FolderSync.suspiciousShrink(expected, expected - setOf("f1.txt", "f2.txt", "f3.txt", "f4.txt")))
        val lost = expected.take(5).toSet()
        assertEquals("five of twenty gone is a quarter: suspicious", lost, FolderSync.suspiciousShrink(expected, expected - lost))
        assertNull("a small folder is never held", FolderSync.suspiciousShrink(setOf("a", "b", "c"), emptySet()))
        assertNull("nothing missing", FolderSync.suspiciousShrink(expected, expected))
    }
}

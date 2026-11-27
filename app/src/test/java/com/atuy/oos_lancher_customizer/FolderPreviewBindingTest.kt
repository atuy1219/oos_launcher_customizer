package com.atuy.oos_lancher_customizer

import android.content.Context
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderPreviewBindingTest {
    private class FolderInfo(val container: Int)
    private class ChildInfo(val itemType: Int = 0, val container: Int = 42)
    private class FolderView(context: Context) : View(context) {
        var mInfo: FolderInfo? = null
    }
    private class Manager {
        var mFolderIcon: View? = null
    }

    @Test fun folderContextAndOwnershipMayBecomeAvailableAfterInitialBind() {
        val manager = Manager()
        val item = ChildInfo()
        assertFalse(ThemedIconPolicy.shouldThemeFolderPreview(manager, item))
        val folder = FolderView(RuntimeEnvironment.getApplication())
        manager.mFolderIcon = folder
        assertSame(folder, ThemedIconPolicy.folderIconForPreview(manager))
        assertFalse(ThemedIconPolicy.shouldThemeFolderPreview(manager, item))
        folder.mInfo = FolderInfo(-100)
        assertTrue(ThemedIconPolicy.shouldThemeFolderPreview(manager, item))
        folder.mInfo = FolderInfo(-201)
        assertFalse(ThemedIconPolicy.shouldThemeFolderPreview(manager, item))
    }
}

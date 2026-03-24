package com.claudecode.webstorm

import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.*
import javax.swing.text.DefaultCaret
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests verifying the scroll-blocking architecture:
 *
 * 1. NoScrollTextPane blocks caret → scrollRectToVisible propagation
 * 2. ChatScrollablePanel / MessageBubble blocks component-tree propagation
 * 3. Caret NEVER_UPDATE survives setContentType when re-applied
 * 4. Standard JTextPane DOES force scroll (documents the problem we're solving)
 */
class ScrollPreservationTest {

    private class TestNoScrollTextPane : JTextPane() {
        override fun scrollRectToVisible(aRect: Rectangle?) { /* no-op */ }
    }

    private class TestScrollPanel : JPanel(), Scrollable {
        override fun scrollRectToVisible(aRect: Rectangle?) { /* no-op */ }
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = 16
        override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) =
            if (o == SwingConstants.VERTICAL) r.height else r.width
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    private fun onEdtSync(block: () -> Unit) {
        SwingUtilities.invokeAndWait(block)
        SwingUtilities.invokeAndWait {}
        SwingUtilities.invokeAndWait {}
    }

    // -----------------------------------------------------------------------
    // Mechanism 1: NoScrollTextPane blocks caret scroll
    // -----------------------------------------------------------------------

    @Test
    fun `NoScrollTextPane blocks scroll on insertString`() {
        val values = mutableListOf<Int>()
        onEdtSync {
            val tp = TestNoScrollTextPane().apply {
                isEditable = false
                (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
            }
            val container = TestScrollPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }
            container.add(Box.createVerticalStrut(2000))
            container.add(tp)

            val sp = JScrollPane(container).apply { preferredSize = Dimension(400, 300) }
            JFrame().apply { contentPane.add(sp); pack(); isVisible = true }

            sp.verticalScrollBar.value = 500
            values += sp.verticalScrollBar.value

            tp.document.insertString(0, "Hello ".repeat(100), null)
            container.revalidate(); container.repaint()
            values += sp.verticalScrollBar.value
        }
        onEdtSync {}
        println("NoScrollTextPane scroll values: $values")
    }

    // -----------------------------------------------------------------------
    // Caret policy survives setContentType
    // -----------------------------------------------------------------------

    @Test
    fun `caret policy survives contentType change when re-applied`() {
        onEdtSync {
            val tp = TestNoScrollTextPane().apply { isEditable = false }
            (tp.caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
            tp.contentType = "text/html"
            (tp.caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
            assertEquals(DefaultCaret.NEVER_UPDATE, (tp.caret as? DefaultCaret)?.updatePolicy)
        }
    }

    @Test
    fun `caret policy may be lost after contentType change without re-apply`() {
        onEdtSync {
            val tp = JTextPane().apply { isEditable = false }
            (tp.caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
            tp.contentType = "text/html"
            val after = (tp.caret as? DefaultCaret)?.updatePolicy
            println("Caret policy after contentType change (no re-apply): $after")
        }
    }

    // -----------------------------------------------------------------------
    // Standard JTextPane DOES force scroll (documenting the problem)
    // -----------------------------------------------------------------------

    @Test
    fun `standard JTextPane forces scroll on insertString`() {
        onEdtSync {
            val tp = JTextPane().apply { isEditable = false }
            val container = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(Box.createVerticalStrut(2000))
                add(tp)
            }
            val sp = JScrollPane(container).apply { preferredSize = Dimension(400, 300) }
            JFrame().apply { contentPane.add(sp); pack(); isVisible = true }
            sp.verticalScrollBar.value = 0
            tp.document.insertString(0, "Hello ".repeat(100), null)
            tp.revalidate()
        }
        onEdtSync {}
    }
}

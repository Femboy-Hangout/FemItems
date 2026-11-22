package dev.femitems.plugin.live

import kotlin.test.Test
import kotlin.test.assertEquals

class BoundedEventBufferTest {
    @Test
    fun `retains only newest entries and preserves monotonic indexes`() {
        val buffer = BoundedEventBuffer<Int>(capacity = 3)
        buffer.add(10)
        buffer.add(20)
        buffer.add(30)
        buffer.add(40)

        assertEquals(4, buffer.count())
        assertEquals(listOf(20, 30, 40), buffer.snapshot())
        assertEquals(listOf(30, 40), buffer.snapshotSince(2))
        assertEquals(emptyList(), buffer.snapshotSince(4))
    }

    @Test
    fun `clear releases retained entries`() {
        val buffer = BoundedEventBuffer<String>(capacity = 2)
        buffer.add("one")
        buffer.add("two")
        buffer.clear()

        assertEquals(0, buffer.count())
        assertEquals(emptyList(), buffer.snapshot())
    }
}

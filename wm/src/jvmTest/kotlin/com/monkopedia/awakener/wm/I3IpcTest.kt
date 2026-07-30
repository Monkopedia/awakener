package com.monkopedia.awakener.wm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class I3IpcTest {
    @Test
    fun `header round-trips`() {
        val header = I3Ipc.decodeHeader(I3Ipc.encodeHeader(1234, I3Ipc.Request.RUN_COMMAND))
        assertEquals(1234, header.payloadLength)
        assertEquals(I3Ipc.Request.RUN_COMMAND, header.type)
    }

    @Test
    fun `header is 14 bytes and starts with the magic`() {
        val encoded = I3Ipc.encodeHeader(0, I3Ipc.Request.GET_TREE)
        assertEquals(I3Ipc.HEADER_SIZE, encoded.size)
        assertEquals("i3-ipc", encoded.copyOfRange(0, 6).decodeToString())
    }

    @Test
    fun `lengths are little-endian`() {
        val encoded = I3Ipc.encodeHeader(1, 0)
        assertEquals(1, encoded[6].toInt(), "low byte comes first")
        assertEquals(0, encoded[9].toInt())
    }

    /**
     * A desynchronised stream must fail loudly. Reading past a bad frame would misinterpret
     * every subsequent length, so recovering silently would corrupt everything after it.
     */
    @Test
    fun `bad magic is rejected`() {
        val corrupt = I3Ipc.encodeHeader(4, 0).also { it[0] = 'x'.code.toByte() }
        assertFailsWith<IllegalArgumentException> { I3Ipc.decodeHeader(corrupt) }
    }

    @Test
    fun `short header is rejected`() {
        assertFailsWith<IllegalArgumentException> { I3Ipc.decodeHeader(ByteArray(6)) }
    }

    /**
     * Event replies set the high bit. Without masking, the window event type (3) is
     * indistinguishable from the GET_OUTPUTS reply type (3).
     */
    @Test
    fun `event types are distinguished from reply types by the high bit`() {
        val windowEvent = I3Ipc.EVENT_MASK or I3Ipc.Event.WINDOW
        assertTrue(I3Ipc.isEvent(windowEvent))
        assertEquals(I3Ipc.Event.WINDOW, I3Ipc.eventKind(windowEvent))
        assertFalse(I3Ipc.isEvent(I3Ipc.Request.GET_TREE))
    }
}

class TreeTest {
    private val tree = swayJson.decodeFromString<Node>(
        """
        {"id":1,"type":"root","layout":"splith","nodes":[
          {"id":3,"type":"output","layout":"output","nodes":[
            {"id":4,"name":"1","type":"workspace","layout":"tabbed","nodes":[
              {"id":7,"type":"con","layout":"splith","nodes":[
                {"id":5,"type":"con","app_id":"app","pid":100,"nodes":[]},
                {"id":8,"type":"con","app_id":"dock","pid":101,"marks":["awakener_dock_5"],"nodes":[]}
              ]},
              {"id":6,"type":"con","app_id":"other","pid":102,"nodes":[]}
            ]}
          ]}
        ]}
        """.trimIndent(),
    )

    @Test
    fun `windows are the leaves that own a process`() {
        assertEquals(listOf(5L, 8L, 6L), tree.windows.map { it.id })
    }

    /** An empty workspace is childless but is not a window; the pid test is what excludes it. */
    @Test
    fun `childless non-windows are not windows`() {
        val empty = swayJson.decodeFromString<Node>(
            """{"id":9,"name":"2","type":"workspace","layout":"splith","nodes":[]}""",
        )
        assertEquals(emptyList(), empty.windows)
    }

    @Test
    fun `parentOf finds the containing node`() {
        assertEquals(7L, tree.parentOf(8L)?.id)
        assertEquals(4L, tree.parentOf(6L)?.id)
    }

    @Test
    fun `parentOf the root is null`() {
        assertEquals(null, tree.parentOf(1L))
    }

    @Test
    fun `workspace lookup is by name`() {
        assertEquals(4L, tree.workspace("1")?.id)
        assertEquals(null, tree.workspace("nope"))
    }

    @Test
    fun `unknown fields do not break decoding`() {
        val node = swayJson.decodeFromString<Node>(
            """{"id":1,"type":"con","some_future_sway_field":{"a":1}}""",
        )
        assertEquals(1L, node.id)
    }
}

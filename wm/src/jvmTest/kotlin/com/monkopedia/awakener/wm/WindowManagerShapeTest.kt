package com.monkopedia.awakener.wm

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The three-call agreement, made mechanical.
 *
 * `docs/design.md` says "Keep the WM interface at three calls", and until this test existed
 * nothing checked it. That is how the interface reached four members plus a four-method handle
 * without any single change looking wrong: each addition was defensible, no reviewer had a
 * number to hold it against, and the agreement was a sentence in a document rather than a
 * property of the build.
 *
 * **This is the half of "hold the line at three" that is not a one-off restructure.** A
 * restructure that nothing enforces is a count that starts drifting again the next week — which
 * is the whole history the issue this branch prices was filed about. So the cost of option 2 is
 * the restructure *and* this test, and this test is the cheaper half.
 *
 * It reads the compiled interface rather than the source, so a member added through any route —
 * a new `suspend fun`, a new `val`, an extension promoted to a member — moves the count. What it
 * cannot see is a call smuggled in as a parameter of an existing one, and nothing mechanical
 * can: a `resolve` that grew a `mode` enum with five arms would still read as one call here.
 * That is the standing limitation of counting members at all, and it is worth stating beside the
 * count rather than leaving for someone to discover by exploiting it.
 */
class WindowManagerShapeTest {

    @Test
    fun `the window manager interface is three calls`() {
        assertEquals(
            setOf("resolve", "attach", "getChanges"),
            membersOf(WindowManager::class.java),
            "the working agreement holds this interface at three: resolve, attach, and change " +
                "notification. Enumeration is resolve's null-argument form — see the KDoc there " +
                "for what that fold costs — and teardown is the handle's.",
        )
    }

    @Test
    fun `the dock handle is two calls and the facts it names`() {
        assertEquals(
            setOf("getSurface", "getAgent", "getDockId", "focus", "detach"),
            membersOf(DockHandle::class.java),
            "focus and settleFocus are one call with a FocusTarget, and AutoCloseable is gone: " +
                "close() had no caller anywhere in the build, and the lifetime that does matter " +
                "is the manager's own close(). Three vals, two calls.",
        )
    }

    /**
     * Declared instance methods by name, minus what the compiler put there.
     *
     * Synthetic members are the bridges and default-argument thunks Kotlin generates, and static
     * ones are `DefaultImpls`-style dispatch; neither is a call anybody makes, so neither counts
     * against the agreement.
     *
     * The trailing `-<hash>` is Kotlin's mangling for a signature mentioning an inline value
     * class, which every member of both interfaces does — `SurfaceId` and `AgentId` are both
     * `@JvmInline`. It is dropped rather than matched, because the hash is derived from the
     * signature and would turn a parameter's type changing into a failure of the *count*.
     */
    private fun membersOf(type: Class<*>): Set<String> =
        type.declaredMethods
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .map { it.name.substringBefore('-') }
            .toSet()
}

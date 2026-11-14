package com.monkopedia.awakener.wm

/**
 * All of [SwayHideTest] again on an output at **scale 1.5**, which is adolin's setting.
 *
 * Not a nicety. The first fix for #229 passed every test at scale 1 and shrank the user's
 * floating window on nearly every hide at scale 1.5 — a late client commit the fractional scale
 * makes likely rather than one it creates — and nothing in a scale-1 run could have shown it.
 * 1920x1080 at 1.5 is 1280x720 logical, the same layout the scale-1 suite asserts on, so every
 * geometry in it holds unchanged.
 */
class SwayHideScaledTest : SwayHideTest() {
    override val outputConfig = "output HEADLESS-1 resolution 1920x1080 scale 1.5"
}

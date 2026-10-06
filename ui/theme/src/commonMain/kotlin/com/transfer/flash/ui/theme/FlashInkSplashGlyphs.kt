package com.transfer.flash.ui.theme

/**
 * The word "Flash" in **Hershey Script 1-stroke**, as the pen draws it (UI-056, ADR-080).
 *
 * A single-line font: every letter is the centre line a pen follows, not an outline, which is what
 * lets the launch splash write the name stroke by stroke. Each [IntArray] is one pen-down stroke as
 * `x0, y0, x1, y1, ...` in font units, in writing order. The letters are laid out by their advance
 * widths, y points down, and the whole word is shifted so its bounding box starts at (0, 0) and is
 * [WIDTH] x [HEIGHT]. The splash smooths each polyline with Catmull-Rom curves when it draws.
 *
 * Generated from `svg_fonts/HersheyScript1.svg` of the MIT-licensed `hersheytext` 2.0.0 npm package
 * (github.com/techninja/hersheytextjs; SVG conversion by Windell H. Oskay, evilmadscientist.com).
 * The generator script is described in `docs/ui/launch-splash.md`.
 *
 * Font licence. The Hershey Fonts may be used by anyone for any purpose, commercial or otherwise,
 * providing that:
 *  1. The following acknowledgements must be distributed with the font data:
 *     - The Hershey Fonts were originally created by Dr. A. V. Hershey while working at the
 *       U. S. National Bureau of Standards.
 *     - The format of the Font data in this distribution was originally created by
 *       James Hurt, Cognition, Inc., 900 Technology Park Drive, Billerica, MA 01821
 *       (mit-eddie!ci-dandelion!hurt).
 *  2. The font data in this distribution may be converted into any other format *EXCEPT* the
 *     format distributed by the U.S. NTIS where each point is described in eight bytes as
 *     "xxx yyy:", where xxx and yyy are the coordinate values as ASCII numbers.
 *
 * The same acknowledgement is in the repository's `NOTICE` and in the shipped third-party notices.
 */
internal object FlashInkSplashGlyphs {
    const val WIDTH: Int = 2173
    const val HEIGHT: Int = 662

    val strokes: List<IntArray> = listOf(
        // F: the wave across the top
        intArrayOf(283, 190, 221, 190, 157, 158, 126, 95, 157, 32, 252, 0, 347, 0, 473, 32, 567, 32, 630, 0),
        // F: the stem and its curl
        intArrayOf(
            473, 32, 409, 252, 347, 442, 283, 568, 221, 631, 157, 662, 95, 662, 32, 631, 0, 568, 0, 504,
            32, 473, 95, 473, 157, 504,
        ),
        // F: the bar
        intArrayOf(252, 316, 535, 316),
        // l
        intArrayOf(
            599, 504, 662, 410, 756, 252, 787, 190, 819, 95, 819, 32, 787, 0, 725, 32, 693, 95, 662, 221,
            630, 442, 630, 631, 662, 662, 693, 662, 756, 631, 787, 599, 851, 504,
        ),
        // a
        intArrayOf(
            1134, 473, 1103, 410, 1039, 378, 977, 378, 914, 410, 882, 442, 851, 504, 851, 568, 882, 631,
            945, 662, 1008, 662, 1071, 631, 1103, 568, 1165, 378, 1134, 536, 1134, 631, 1165, 662, 1197, 662,
            1260, 631, 1291, 599, 1355, 504,
        ),
        // s: the top and the bowl
        intArrayOf(1355, 504, 1418, 410, 1449, 347, 1449, 410, 1512, 504, 1543, 568, 1543, 631, 1481, 662),
        // s: the base and the joining stroke
        intArrayOf(1355, 631, 1418, 662, 1543, 662, 1607, 631, 1638, 599, 1701, 504),
        // h: the loop and the stem
        intArrayOf(
            1701, 504, 1764, 410, 1858, 252, 1889, 190, 1921, 95, 1921, 32, 1889, 0, 1827, 32, 1795, 95,
            1764, 221, 1732, 410, 1701, 662,
        ),
        // h: the arch and the exit stroke
        intArrayOf(
            1701, 662, 1732, 568, 1764, 504, 1827, 410, 1889, 378, 1953, 378, 1984, 410, 1984, 473, 1953, 568,
            1953, 631, 1984, 662, 2015, 662, 2079, 631, 2110, 599, 2173, 504,
        ),
    )
}

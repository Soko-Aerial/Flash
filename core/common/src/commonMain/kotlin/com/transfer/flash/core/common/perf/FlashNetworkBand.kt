package com.transfer.flash.core.common.perf

/**
 * How this device is attached to the network it shares with its peers (G2,
 * `docs/calling/GROUP-VIDEO-PLAN.md` R5). Group calls exchange it so each connection's video
 * budget can follow the slower end of that connection (G4).
 *
 * [UNKNOWN] is an honest answer, not an error: a phone hosting the hotspot cannot read its own AP
 * band, a desktop may not say, and a cellular-only default network has no Wi-Fi band.
 */
public enum class FlashNetworkBand(
    /** Wire value in call frames (`band=`); stable, never localised. */
    public val wire: String,
    /** Short label for the call stats readout. */
    public val label: String,
    /** Higher is faster; [UNKNOWN] has none. */
    private val rank: Int,
) {
    ETHERNET("eth", "Ethernet", 4),
    WIFI_6GHZ("6g", "6 GHz", 3),
    WIFI_5GHZ("5g", "5 GHz", 2),
    WIFI_2_4GHZ("2g", "2.4 GHz", 1),
    UNKNOWN("unk", "Unknown band", 0),
    ;

    public companion object {
        /** Decodes a `band=` value; an unrecognised one is [UNKNOWN], an absent one null (old client). */
        public fun fromWire(value: String?): FlashNetworkBand? =
            if (value == null) null else entries.firstOrNull { it.wire == value } ?: UNKNOWN

        /** The band of a Wi-Fi channel centre frequency in MHz, or [UNKNOWN] outside the three bands. */
        public fun fromWifiFrequencyMhz(mhz: Int): FlashNetworkBand = when (mhz) {
            in 2_400..2_500 -> WIFI_2_4GHZ
            in 4_900..5_900 -> WIFI_5GHZ
            in 5_925..7_125 -> WIFI_6GHZ
            else -> UNKNOWN
        }

        /**
         * The band that limits a connection between two ends: the slower one. An end that does not
         * know ([UNKNOWN] or null) defers to the other, per the plan's hotspot rule ("the other end's
         * report decides"); both unknown is [UNKNOWN].
         */
        public fun link(a: FlashNetworkBand?, b: FlashNetworkBand?): FlashNetworkBand = pick(a, b)

        /** The slowest known band among [bands], [UNKNOWN] when none is known, null when empty. */
        public fun slowest(bands: Collection<FlashNetworkBand>): FlashNetworkBand? =
            if (bands.isEmpty()) null else bands.fold(UNKNOWN) { acc, band -> pick(acc, band) }

        private fun pick(a: FlashNetworkBand?, b: FlashNetworkBand?): FlashNetworkBand {
            val knownA = a?.takeIf { it != UNKNOWN }
            val knownB = b?.takeIf { it != UNKNOWN }
            return when {
                knownA == null -> knownB ?: UNKNOWN
                knownB == null -> knownA
                else -> if (knownA.rank <= knownB.rank) knownA else knownB
            }
        }
    }
}

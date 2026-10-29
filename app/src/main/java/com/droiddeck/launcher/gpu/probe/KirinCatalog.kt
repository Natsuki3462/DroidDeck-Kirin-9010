package com.droiddeck.launcher.gpu.probe

/**
 * One Kirin SoC, described by what it means for a Linux guest rather than by its benchmark score.
 *
 * [era] is the load-bearing field. Kirin shipped two completely different GPU stacks under the same
 * name: ARM's licensed Mali up to the 9000, then Huawei's own Maleoon from the 9000S on. A support
 * matrix that treats "Kirin" as one thing is wrong for half the devices it names.
 */
data class KirinSoc(
    /** Stable id used in reports and tests: "kirin9000", "kirin9000s". */
    val id: String,
    /** How a person says it: "Kirin 9000S". */
    val marketing: String,
    /** The GPU as the device reports it: "Mali-G78 MP24", "Maleoon 910". */
    val gpu: String,
    val vendor: GpuVendor,
    val era: Era,
    /** The kbase generation this GPU's kernel driver speaks, for Mali parts. */
    val kbase: KbaseInterface,
    /** Lowest Android API level the vendor shipped this SoC with. */
    val minApi: Int,
) {
    enum class Era(val label: String) {
        /** Bifrost: Mali-G71/G72. Valhall v7-v9: G76/G77/G78. ARM-licensed. */
        MALI("ARM Mali (licensed)"),

        /** Huawei's own GPU. No Mesa backend, no open driver, no guest path. */
        MALEOON("Huawei Maleoon (in-house)"),
    }

    /** Whether any open-source Vulkan backend could ever drive this GPU from inside the guest. */
    val hasOpenGuestDriver: Boolean get() = vendor == GpuVendor.ARM_MALI
}

/**
 * The Kirin models a support decision can actually be made about.
 *
 * Only SoCs whose GPU is documented are listed; an unlisted Kirin is treated as unknown rather than
 * guessed, because guessing a GPU generation is exactly how a wrong PanVK build gets shipped.
 */
object KirinCatalog {
    private val M = GpuVendor.ARM_MALI
    private val H = GpuVendor.HUAWEI_MALEOON
    private val JM = KbaseInterface.JM
    private val CSF = KbaseInterface.CSF

    val entries: List<KirinSoc> = listOf(
        KirinSoc("kirin960", "Kirin 960", "Mali-G71 MP8", M, KirinSoc.Era.MALI, JM, 24),
        KirinSoc("kirin970", "Kirin 970", "Mali-G72 MP12", M, KirinSoc.Era.MALI, JM, 26),
        KirinSoc("kirin980", "Kirin 980", "Mali-G76 MP10", M, KirinSoc.Era.MALI, JM, 28),
        KirinSoc("kirin985", "Kirin 985", "Mali-G77 MP8", M, KirinSoc.Era.MALI, JM, 29),
        KirinSoc("kirin990", "Kirin 990", "Mali-G76 MP16", M, KirinSoc.Era.MALI, JM, 29),
        KirinSoc("kirin9000", "Kirin 9000", "Mali-G78 MP24", M, KirinSoc.Era.MALI, JM, 29),
        KirinSoc("kirin9000e", "Kirin 9000E", "Mali-G78 MP22", M, KirinSoc.Era.MALI, JM, 30),
        // The divide: everything below is Maleoon, and has no open guest driver at all.
        KirinSoc("kirin9000s", "Kirin 9000S", "Maleoon 910", H, KirinSoc.Era.MALEOON, KbaseInterface.NONE, 31),
        KirinSoc("kirin9010", "Kirin 9010", "Maleoon 910", H, KirinSoc.Era.MALEOON, KbaseInterface.NONE, 31),
        KirinSoc("kirin9020", "Kirin 9020", "Maleoon 920", H, KirinSoc.Era.MALEOON, KbaseInterface.NONE, 31),
        KirinSoc("kirin9020a", "Kirin 9020A", "Maleoon 920", H, KirinSoc.Era.MALEOON, KbaseInterface.NONE, 31),
        KirinSoc("kirin9030", "Kirin 9030", "Maleoon 9xx", H, KirinSoc.Era.MALEOON, KbaseInterface.NONE, 31),
        // Mid-range parts people actually play on.
        KirinSoc("kirin810", "Kirin 810", "Mali-G52 MP6", M, KirinSoc.Era.MALI, JM, 28),
        KirinSoc("kirin820", "Kirin 820", "Mali-G57 MP6", M, KirinSoc.Era.MALI, JM, 29),
    )

    private val byId: Map<String, KirinSoc> = entries.associateBy { it.id }

    fun byId(id: String): KirinSoc? = byId[id.trim().lowercase()]

    /**
     * The Kirin a device is, from whatever strings it chose to expose.
     *
     * Huawei is not consistent here: `ro.board.platform` carries "kirin9000" on some builds,
     * "hi3660" on older ones, and a marketing string on others, so every source is tried and the
     * longest matching token wins - "kirin9000s" must not be read as "kirin9000".
     */
    fun identify(vararg candidates: String?): KirinSoc? {
        val tokens = candidates.filterNotNull()
            .flatMap { split(it) }
        if (tokens.isEmpty()) return null
        // Longest first so a more specific id beats its own prefix.
        for (token in tokens.sortedByDescending { it.length }) {
            byId[token]?.let { return it }
        }
        return null
    }

    private val LEGACY = mapOf(
        "hi3660" to "kirin960", "hi3670" to "kirin970", "hi3680" to "kirin980",
        "hi3690" to "kirin990", "hi6250" to "kirin659", "hi6210" to "kirin650",
    )

    /** Every "kirin####" / "hi####" token in a string, normalised. */
    internal fun split(raw: String): List<String> {
        val lower = raw.lowercase()
        val out = mutableListOf<String>()
        Regex("""kirin\s*-?\s*(\d{3,4}[a-z]?)(?!\d)""").findAll(lower).forEach {
            out += "kirin" + it.groupValues[1]
        }
        Regex("""\b(hi\d{4})\b""").findAll(lower).forEach { m ->
            out += m.groupValues[1]
            LEGACY[m.groupValues[1]]?.let { out += it }
        }
        return out
    }
}

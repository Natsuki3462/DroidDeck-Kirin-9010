package com.droiddeck.launcher.gpu.probe

/** A device described instead of attached, so detection rules can be asserted on a build machine. */
internal class FakeEnv(
    private val files: Set<String> = emptySet(),
    private val contents: Map<String, String> = emptyMap(),
    private val props: Map<String, String> = emptyMap(),
    override val sdkInt: Int = 34,
    override val hardware: String = "",
    override val manufacturer: String = "",
    override val vulkanDeviceName: String? = null,
) : ProbeEnv {
    override fun exists(path: String): Boolean = path in files || path in contents

    override fun readText(path: String): String? = contents[path]

    override fun property(name: String): String = props[name].orEmpty()

    override fun list(dir: String): List<String> = (files + contents.keys)
        .filter { it.substringBeforeLast('/', "") == dir }
        .map { it.substringAfterLast('/') }
        .distinct()
        .sorted()
}

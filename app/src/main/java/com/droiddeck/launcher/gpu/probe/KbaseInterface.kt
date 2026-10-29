package com.droiddeck.launcher.gpu.probe

/**
 * How a Mali-family kernel driver is reached, which decides whether a given PanVK build can talk to
 * it at all.
 *
 * This is the detail that makes "Mali support" a family of backends rather than one: a build made
 * for CSF cannot drive a Job Manager (JM) GPU, and a Kirin 9000 (G78, JM) is not a Mali-G615
 * (Valhall v10, CSF) however similar the marketing name looks.
 */
enum class KbaseInterface(val label: String) {
    /** Command Stream Frontend - Mali-G710/G715/G615 and newer (Valhall v10+). */
    CSF("kbase CSF"),

    /** Job Manager - Mali-G78 and older (Bifrost and Valhall v7-v9). */
    JM("kbase JM"),

    /** No kbase node found; the driver is either absent or something else entirely. */
    NONE("no kbase node"),

    /** A node exists but the interface generation could not be read. */
    UNKNOWN("kbase, interface unknown"),
}

package com.hopa.app.navigation

import kotlinx.serialization.Serializable

/**
 * Every destination as a type. A route carries its arguments as properties, so
 * a wrong argument is a compile error instead of a missing `?section=` at run
 * time. Plain screens are objects; screens with arguments are data classes.
 */
@Serializable
sealed interface HopaDestination {
    @Serializable
    data object Chat : HopaDestination

    @Serializable
    data object SignIn : HopaDestination

    @Serializable
    data object Settings : HopaDestination

    @Serializable
    data object Profiles : HopaDestination

    @Serializable
    data object Runs : HopaDestination

    @Serializable
    data object Usage : HopaDestination

    @Serializable
    data object History : HopaDestination

    @Serializable
    data object Recipes : HopaDestination

    @Serializable
    data object Memory : HopaDestination

    @Serializable
    data object Skills : HopaDestination

    @Serializable
    data object RunningApps : HopaDestination

    @Serializable
    data object ActMode : HopaDestination

    @Serializable
    data object Answers : HopaDestination

    @Serializable
    data object Decisions : HopaDestination

    @Serializable
    data object RunSetup : HopaDestination

    @Serializable
    data object RunPrefs : HopaDestination

    @Serializable
    data object Computer : HopaDestination

    @Serializable
    data object Scan : HopaDestination

    @Serializable
    data object SelfCheck : HopaDestination

    @Serializable
    data object About : HopaDestination

    @Serializable
    data object Accessibility : HopaDestination

    @Serializable
    data class Browser(val url: String = "") : HopaDestination

    @Serializable
    data class Launchpad(val section: String = "apps") : HopaDestination

    @Serializable
    data class AppCard(val name: String) : HopaDestination
}

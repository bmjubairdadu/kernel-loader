package com.kernelloader.ui

import kotlinx.serialization.Serializable
import androidx.navigation3.runtime.NavKey

@Serializable
data object MainRoute : NavKey

@Serializable
data object CreditsRoute : NavKey

@Serializable
data object ConsoleRoute : NavKey

package com.zero.babycare.navigation

/**
 * Fragment back-press handler.
 * Return true to consume the system back.
 */
interface BackPressHandler {
    fun onSystemBackPressed(): Boolean
}

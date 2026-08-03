package com.winlator.star.core

import android.content.Context

/**
 * User-configurable "New Container Defaults" — the seed a brand-new container is built from.
 *
 * A single JSON blob holding the same container-config `data` shape the create flow builds (see
 * ContainerDetailViewModel.doConfirm), MINUS "name" and "drives" (those are per-container, not
 * templatable). When set, ContainerDetailViewModel seeds create-mode fields from it; when unset the
 * built-in `Container.DEFAULT_*` constants are used, so the no-profile create path is unchanged.
 *
 * Kept in a DEDICATED SharedPreferences file (not the default prefs, not Room) so it survives an
 * in-place app update and stays completely separate from config export/import and community configs.
 */
object NewContainerDefaults {

    private const val PREFS = "new_container_defaults"
    private const val KEY_PROFILE = "profile"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Persist the profile JSON (the stripped container-config `data` object). */
    fun save(context: Context, json: String) {
        prefs(context).edit().putString(KEY_PROFILE, json).apply()
    }

    /** The saved profile JSON, or null when the user has never set one. */
    fun load(context: Context): String? = prefs(context).getString(KEY_PROFILE, null)

    /** Forget the user's defaults so new containers fall back to the built-in `Container.DEFAULT_*`. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_PROFILE).apply()
    }

    /** Whether a user profile is set (i.e. new containers should seed from it, not the app defaults). */
    fun exists(context: Context): Boolean = load(context) != null
}

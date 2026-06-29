package com.audiofetch

import android.content.Context
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object AccountManager {

    private const val PREFS = "account"
    private const val KEY_NAME  = "name"
    private const val KEY_EMAIL = "email"
    private const val KEY_AUTH  = "authenticated"

    fun saveCookies(context: Context, cookies: String) {
    context.getSharedPreferences("account", Context.MODE_PRIVATE)
        .edit().putString("yt_cookies", cookies).apply()
}

fun getCookies(context: Context): String? {
    return context.getSharedPreferences("account", Context.MODE_PRIVATE)
        .getString("yt_cookies", null)
}
    fun isAuthenticated(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTH, false)

    fun getDisplayName(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NAME, "") ?: ""

    fun getEmail(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EMAIL, "") ?: ""

    fun saveAccount(ctx: Context, name: String, email: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTH, true)
            .putString(KEY_NAME, name)
            .putString(KEY_EMAIL, email)
            .apply()
    }

    fun clearAccount(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    suspend fun setupFromCookies(cookies: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val result = Python.getInstance().getModule("main")
                    .callAttr("setup_account", cookies).toString()
                if (result.startsWith("ERROR")) Result.failure(Exception(result))
                else {
                    val json = JSONObject(result)
                    Result.success(json.optString("name", "YouTube Music user"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun signOut(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                Python.getInstance().getModule("main").callAttr("sign_out")
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}

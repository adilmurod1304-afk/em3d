package uz.namdpi.childguard

import android.content.Context

/**
 * Oddiy sozlamalar ombori (SharedPreferences ustida).
 */
object Prefs {
    private const val FILE = "childguard_prefs"

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Bolaning ruxsat etilgan ekran vaqti (daqiqa). Default 30. */
    fun getLimitMinutes(ctx: Context): Int =
        sp(ctx).getInt("limit_minutes", 30)

    fun setLimitMinutes(ctx: Context, minutes: Int) =
        sp(ctx).edit().putInt("limit_minutes", minutes).apply()

    /** Bugungi to'plangan bola ekran vaqti (millisekund). */
    fun getUsedMillis(ctx: Context): Long =
        sp(ctx).getLong("used_millis", 0L)

    fun setUsedMillis(ctx: Context, ms: Long) =
        sp(ctx).edit().putLong("used_millis", ms).apply()

    /** Qaysi kun uchun hisoblanayotganini bilish (yarim tunda nolga tushirish uchun). */
    fun getUsedDay(ctx: Context): Int =
        sp(ctx).getInt("used_day", -1)

    fun setUsedDay(ctx: Context, day: Int) =
        sp(ctx).edit().putInt("used_day", day).apply()

    /** Blok holati. */
    fun isLocked(ctx: Context): Boolean =
        sp(ctx).getBoolean("locked", false)

    fun setLocked(ctx: Context, locked: Boolean) =
        sp(ctx).edit().putBoolean("locked", locked).apply()

    /** Ota-ona zaxira PIN kodi (yuz ishlamay qolsa). */
    fun getParentPin(ctx: Context): String =
        sp(ctx).getString("parent_pin", "0000") ?: "0000"

    fun setParentPin(ctx: Context, pin: String) =
        sp(ctx).edit().putString("parent_pin", pin).apply()

    /** Monitoring yoqilganmi. */
    fun isGuardEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean("guard_enabled", false)

    fun setGuardEnabled(ctx: Context, enabled: Boolean) =
        sp(ctx).edit().putBoolean("guard_enabled", enabled).apply()

    /** Tanib olish qat'iyligi (cosine masofa chegarasi). Kichik = qattiqroq. */
    fun getMatchThreshold(ctx: Context): Float =
        sp(ctx).getFloat("match_threshold", 0.9f)

    fun setMatchThreshold(ctx: Context, t: Float) =
        sp(ctx).edit().putFloat("match_threshold", t).apply()
}

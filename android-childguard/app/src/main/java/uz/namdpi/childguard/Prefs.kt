package uz.namdpi.childguard

import android.content.Context
import java.util.Calendar

/**
 * Sozlamalar va har bir bola uchun kunlik ekran vaqti hisobi.
 */
object Prefs {
    private const val FILE = "childguard_prefs"

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------- Umumiy sozlamalar ----------

    /** Har bir bola uchun kunlik ruxsat etilgan vaqt (daqiqa). Default 30. */
    fun getLimitMinutes(ctx: Context): Int = sp(ctx).getInt("limit_minutes", 30)

    fun setLimitMinutes(ctx: Context, minutes: Int) =
        sp(ctx).edit().putInt("limit_minutes", minutes).apply()

    fun getParentPin(ctx: Context): String =
        sp(ctx).getString("parent_pin", "0000") ?: "0000"

    fun setParentPin(ctx: Context, pin: String) =
        sp(ctx).edit().putString("parent_pin", pin).apply()

    fun isGuardEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean("guard_enabled", false)

    fun setGuardEnabled(ctx: Context, enabled: Boolean) =
        sp(ctx).edit().putBoolean("guard_enabled", enabled).apply()

    /** Tanib olish qat'iyligi (masofa chegarasi). Kichik = qattiqroq. */
    fun getMatchThreshold(ctx: Context): Float =
        sp(ctx).getFloat("match_threshold", 0.9f)

    fun setMatchThreshold(ctx: Context, t: Float) =
        sp(ctx).edit().putFloat("match_threshold", t).apply()

    // ---------- Blok holati ----------

    fun isLocked(ctx: Context): Boolean = sp(ctx).getBoolean("locked", false)

    fun setLocked(ctx: Context, locked: Boolean) =
        sp(ctx).edit().putBoolean("locked", locked).apply()

    /** Qaysi bola sababli bloklanganini eslab qolamiz (ism + id). */
    fun getLockedChildName(ctx: Context): String =
        sp(ctx).getString("locked_child_name", "") ?: ""

    fun getLockedChildId(ctx: Context): String =
        sp(ctx).getString("locked_child_id", "") ?: ""

    fun setLockedChild(ctx: Context, id: String, name: String) =
        sp(ctx).edit()
            .putString("locked_child_id", id)
            .putString("locked_child_name", name)
            .apply()

    // ---------- Har bir bola uchun kunlik hisob ----------

    private fun today(): Int = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

    /** Bolaning bugungi to'plangan vaqti (ms). Yangi kun bo'lsa — nolga tushadi. */
    fun getChildUsedMillis(ctx: Context, id: String): Long {
        val day = sp(ctx).getInt("used_day_$id", -1)
        if (day != today()) return 0L
        return sp(ctx).getLong("used_ms_$id", 0L)
    }

    fun addChildUsedMillis(ctx: Context, id: String, delta: Long) {
        val current = getChildUsedMillis(ctx, id) // kunni hisobga oladi
        sp(ctx).edit()
            .putInt("used_day_$id", today())
            .putLong("used_ms_$id", current + delta)
            .apply()
    }

    fun resetChildUsed(ctx: Context, id: String) {
        sp(ctx).edit()
            .putInt("used_day_$id", today())
            .putLong("used_ms_$id", 0L)
            .apply()
    }

    fun clearChildUsage(ctx: Context, id: String) {
        sp(ctx).edit()
            .remove("used_ms_$id")
            .remove("used_day_$id")
            .apply()
    }
}

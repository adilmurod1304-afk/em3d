package uz.namdpi.childguard

import android.content.Context
import org.json.JSONArray

/**
 * Ro'yxatdan o'tgan yuzlarning "embedding" (son vektori) larini saqlaydi.
 * Har bir shaxs uchun bir nechta namuna bo'lishi mumkin (aniqroq bo'lishi uchun).
 */
object EmbeddingStore {
    private const val FILE = "childguard_faces"
    const val ROLE_PARENT = "parent"
    const val ROLE_CHILD = "child"

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun add(ctx: Context, role: String, embedding: FloatArray) {
        val list = load(ctx, role).toMutableList()
        list.add(embedding)
        save(ctx, role, list)
    }

    fun clear(ctx: Context, role: String) {
        sp(ctx).edit().remove(role).apply()
    }

    fun count(ctx: Context, role: String): Int = load(ctx, role).size

    fun load(ctx: Context, role: String): List<FloatArray> {
        val raw = sp(ctx).getString(role, null) ?: return emptyList()
        val arr = JSONArray(raw)
        val out = ArrayList<FloatArray>(arr.length())
        for (i in 0 until arr.length()) {
            val inner = arr.getJSONArray(i)
            val v = FloatArray(inner.length())
            for (j in 0 until inner.length()) v[j] = inner.getDouble(j).toFloat()
            out.add(v)
        }
        return out
    }

    private fun save(ctx: Context, role: String, list: List<FloatArray>) {
        val arr = JSONArray()
        for (v in list) {
            val inner = JSONArray()
            for (f in v) inner.put(f.toDouble())
            arr.put(inner)
        }
        sp(ctx).edit().putString(role, arr.toString()).apply()
    }

    fun isEnrolled(ctx: Context): Boolean =
        count(ctx, ROLE_PARENT) > 0 && count(ctx, ROLE_CHILD) > 0
}

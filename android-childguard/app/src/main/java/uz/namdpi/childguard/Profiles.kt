package uz.namdpi.childguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Bitta bola profili: id, ism va yuz namunalari (embedding'lar). */
data class ChildProfile(
    val id: String,
    var name: String,
    val embeddings: MutableList<FloatArray>
)

/**
 * Yuz profillari ombori.
 *  - Ota-ona: bitta rol, bir nechta namuna bo'lishi mumkin.
 *  - Bolalar: bir nechta profil (har birida ism + bir nechta namuna).
 * Hammasi faqat telefon ichida (SharedPreferences) saqlanadi.
 */
object Profiles {
    private const val FILE = "childguard_faces"
    private const val KEY_PARENT = "parent"
    private const val KEY_CHILDREN = "children"

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------- Ota-ona ----------

    fun addParentSample(ctx: Context, emb: FloatArray) {
        val list = loadParent(ctx).toMutableList()
        list.add(emb)
        saveEmbList(ctx, KEY_PARENT, list)
    }

    fun clearParent(ctx: Context) = sp(ctx).edit().remove(KEY_PARENT).apply()

    fun parentCount(ctx: Context): Int = loadParent(ctx).size

    fun loadParent(ctx: Context): List<FloatArray> {
        val raw = sp(ctx).getString(KEY_PARENT, null) ?: return emptyList()
        return parseEmbList(JSONArray(raw))
    }

    // ---------- Bolalar ----------

    fun listChildren(ctx: Context): List<ChildProfile> {
        val raw = sp(ctx).getString(KEY_CHILDREN, null) ?: return emptyList()
        val arr = JSONArray(raw)
        val out = ArrayList<ChildProfile>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                ChildProfile(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    embeddings = parseEmbList(o.getJSONArray("emb")).toMutableList()
                )
            )
        }
        return out
    }

    fun getChild(ctx: Context, id: String): ChildProfile? =
        listChildren(ctx).firstOrNull { it.id == id }

    /** Yangi bola qo'shadi va uning id'sini qaytaradi. */
    fun addChild(ctx: Context, name: String): String {
        val list = listChildren(ctx).toMutableList()
        val id = "c" + UUID.randomUUID().toString().take(8)
        list.add(ChildProfile(id, name.ifBlank { "Bola ${list.size + 1}" }, mutableListOf()))
        saveChildren(ctx, list)
        return id
    }

    fun renameChild(ctx: Context, id: String, name: String) {
        val list = listChildren(ctx).toMutableList()
        list.firstOrNull { it.id == id }?.name = name
        saveChildren(ctx, list)
    }

    fun removeChild(ctx: Context, id: String) {
        val list = listChildren(ctx).toMutableList()
        list.removeAll { it.id == id }
        saveChildren(ctx, list)
        Prefs.clearChildUsage(ctx, id)
    }

    fun addChildSample(ctx: Context, id: String, emb: FloatArray) {
        val list = listChildren(ctx).toMutableList()
        list.firstOrNull { it.id == id }?.embeddings?.add(emb)
        saveChildren(ctx, list)
    }

    fun clearChildSamples(ctx: Context, id: String) {
        val list = listChildren(ctx).toMutableList()
        list.firstOrNull { it.id == id }?.embeddings?.clear()
        saveChildren(ctx, list)
    }

    fun childSampleCount(ctx: Context, id: String): Int =
        getChild(ctx, id)?.embeddings?.size ?: 0

    /** Himoyani boshlash uchun: ota-ona + kamida bitta namunali bola kerak. */
    fun isReady(ctx: Context): Boolean =
        parentCount(ctx) > 0 && listChildren(ctx).any { it.embeddings.isNotEmpty() }

    // ---------- JSON yordamchilari ----------

    private fun saveChildren(ctx: Context, list: List<ChildProfile>) {
        val arr = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("name", c.name)
            o.put("emb", embListToJson(c.embeddings))
            arr.put(o)
        }
        sp(ctx).edit().putString(KEY_CHILDREN, arr.toString()).apply()
    }

    private fun saveEmbList(ctx: Context, key: String, list: List<FloatArray>) {
        sp(ctx).edit().putString(key, embListToJson(list).toString()).apply()
    }

    private fun embListToJson(list: List<FloatArray>): JSONArray {
        val arr = JSONArray()
        for (v in list) {
            val inner = JSONArray()
            for (f in v) inner.put(f.toDouble())
            arr.put(inner)
        }
        return arr
    }

    private fun parseEmbList(arr: JSONArray): List<FloatArray> {
        val out = ArrayList<FloatArray>(arr.length())
        for (i in 0 until arr.length()) {
            val inner = arr.getJSONArray(i)
            val v = FloatArray(inner.length())
            for (j in 0 until inner.length()) v[j] = inner.getDouble(j).toFloat()
            out.add(v)
        }
        return out
    }
}

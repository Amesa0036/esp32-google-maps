
package com.maisonsmd.catdrive.lib

import android.app.Notification
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.service.notification.StatusBarNotification
import android.text.Spanned
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.view.children
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

const val GMAPS_PACKAGE = "com.google.android.apps.maps"

enum class ContentViewType {
    NORMAL,
    BIG,
    BEST,
}

internal class GMapsNotification(cx: Context, sbn: StatusBarNotification) : NavigationNotification(cx, sbn) {
    companion object {
        private var lastDumpAt = -10_000L
    }

    init {
        dumpNotificationViews()

        val normalContent = getContentView(ContentViewType.NORMAL)
        if (normalContent != null)
            parseRemoteView(getRemoteViewGroup(normalContent))

        val bestContentView = getContentView(ContentViewType.BEST)
        if (bestContentView != normalContent)
            parseRemoteView(getRemoteViewGroup(bestContentView))
    }

    private fun getContentView(type: ContentViewType = ContentViewType.BEST): RemoteViews? {
        if (type == ContentViewType.BIG || type == ContentViewType.BEST) {
            val remoteViews = Notification.Builder.recoverBuilder(mContext, mNotification).createBigContentView()

            if (remoteViews != null || type == ContentViewType.BIG)
                return remoteViews
        }

        return Notification.Builder.recoverBuilder(mContext, mNotification).createContentView()
    }

    private fun getRemoteViewGroup(remoteViews: RemoteViews?): ViewGroup {
        if (remoteViews == null) {
            throw Exception("Impossible to create notification view")
        }

        val layoutInflater = mAppSourceContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val viewGroup = layoutInflater.inflate(remoteViews.layoutId, null) as ViewGroup?
            ?: throw Exception("Impossible to inflate viewGroup")

        remoteViews.reapply(mAppSourceContext, viewGroup)

        return viewGroup
    }

    private fun getEntryName(item: View): String {
        val entryName: String = try {
            if (item.id > 0)
                mAppSourceContext.resources.getResourceEntryName(item.id)
            else ""
        } catch (e: Exception) {
            ""
        }

        return entryName
    }

    private fun getEntryJsonKey(item: View): String {
        return "${item.javaClass.simpleName}:${getEntryName(item)}"
    }

    private fun findChildByName(group: ViewGroup, name: CharSequence): View? {
        for (child in group.children) {
            val entryName = getEntryName(child)

            if (entryName == name)
                return child

            if (child is ViewGroup) {
                val c = findChildByName(child, name)
                if (c != null)
                    return c
            }
        }

        return null
    }

    private fun parseRemoteView(group: ViewGroup): NavigationData {
        val data = navigationData

        val directionText = findChildByName(group, "text") as TextView?
        val etaText = findChildByName(group, "header_text") as TextView?
        val titleText = findChildByName(group, "title") as TextView?
        // val timeText = findChildByName(group, "time") as TextView?
        val rightIcon = findChildByName(group, "right_icon") as ImageView?

        // parse ETE & ETA
        if (etaText != null) {
            val etaList = etaText.text.split("·")
            if (etaList.size == 3) {
                val distance = etaList[1].trim()
                data.eta = NavigationEta(etaList[2].removeSuffix("ETA").trim(), etaList[0].trim(), distance)
            }
        }

        var nextDistance = ""
        if (titleText != null && titleText.text.trim().isNotEmpty()) {
            nextDistance = titleText.text.trim().toString()
        }
        var nextRoad = ""
        var nextRoadDesc = ""
        if (directionText?.text !is Spanned) {
            // must be the text "Rerouting..."
            Timber.w("Direction Text is not Spanned, text: ${directionText?.text}")
            nextRoad = directionText?.text as String
        } else {
            // Road names are in Typeface.BOLD, sub texts are in Typeface.NORMAL
            var directionList = ParserHelper.splitByStyleSpan(directionText?.text as Spanned, Typeface.NORMAL, 2)
            if (directionList.isNotEmpty()) {
                val nextRoadList = mutableListOf(directionList.first())
                val nextRoadDescList = mutableListOf<ParserHelper.SpanSplitResult>()

                val rest = directionList.drop(1)
                val index = rest.indexOfFirst { it.isKeySpan && it.text.trim() != "/" }
                if (index == -1) {
                    nextRoadList.addAll(rest)
                } else {
                    nextRoadList.addAll(rest.subList(0, index))
                    nextRoadDescList.addAll(rest.subList(index, rest.size))
                }
                nextRoad = nextRoadList.joinToString(" ") { it.text }
                nextRoadDesc = nextRoadDescList.joinToString(" ") { it.text }
            }
        }
        data.nextDirection = NavigationDirection(nextRoad, nextRoadDesc, nextDistance)

        (rightIcon?.drawable as BitmapDrawable?)?.bitmap?.also {
            data.actionIcon = NavigationIcon(it.copy(it.config, false))
        }

        // Timber.v("$data")

        return data
    }

    // Diagnostic only: never let a dump failure affect navigation parsing.
    private fun dumpNotificationViews() {
        synchronized(GMapsNotification::class.java) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastDumpAt < 10_000L) return
            lastDumpAt = now
        }

        try {
            val dump = JSONObject().apply {
                put("capturedAt", java.util.Date().toString())
                put("boundsNote", "Bounds are relative to the parent; unlaid-out views may have zero bounds.")
                for (type in listOf(ContentViewType.BIG, ContentViewType.NORMAL)) {
                    val tree = try {
                        val remoteViews = getContentView(type)
                        if (remoteViews == null) JSONObject().put("unavailable", true)
                        else parseRemoteViewToJson(getRemoteViewGroup(remoteViews))
                    } catch (e: Exception) {
                        JSONObject().put("error", e.toString())
                    }
                    put(type.name, tree)
                }
            }

            val resolver = mContext.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val path = Environment.DIRECTORY_DOWNLOADS + "/"
            var uri: android.net.Uri? = null
            val existing = resolver.query(
                collection, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ?",
                arrayOf("catdrive-views.txt", path, mContext.packageName), null
            ) ?: throw java.io.IOException("Cannot query Downloads")
            existing.use { cursor ->
                if (cursor.moveToFirst())
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "catdrive-views.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, path)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val target = uri ?: resolver.insert(collection, values)
                ?: throw java.io.IOException("Cannot create catdrive-views.txt")
            resolver.update(target, values, null, null)
            try {
                val stream = resolver.openOutputStream(target, "wt")
                    ?: throw java.io.IOException("Cannot open catdrive-views.txt")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(dump.toString(2)) }
            } finally {
                resolver.update(target, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
            }
        } catch (e: Exception) {
            Timber.w(e, "Cannot save notification view diagnostic")
        }
    }

    // An array preserves every child, even when several have the same id/class.
    private fun parseRemoteViewToJson(view: View): JSONObject {
        return JSONObject().apply {
            put("key", getEntryJsonKey(view))
            put("id", view.id)
            put("entryName", getEntryName(view))
            put("class", view.javaClass.name)
            put("text", if (view is TextView) view.text?.toString() ?: "" else JSONObject.NULL)
            put("contentDescription", view.contentDescription?.toString() ?: JSONObject.NULL)
            put("visibility", when (view.visibility) {
                View.VISIBLE -> "VISIBLE"
                View.INVISIBLE -> "INVISIBLE"
                View.GONE -> "GONE"
                else -> view.visibility.toString()
            })
            put("isLaidOut", view.isLaidOut)
            put("bounds", JSONObject().apply {
                put("left", view.left)
                put("top", view.top)
                put("right", view.right)
                put("bottom", view.bottom)
            })
            if (view is ViewGroup) {
                put("children", JSONArray().apply {
                    for (child in view.children) put(parseRemoteViewToJson(child))
                })
            }
        }
    }
}


package com.tstc.zstangshi.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.tstc.zstangshi.R
import com.tstc.zstangshi.data.WidgetDay

/**
 * 小组件里那一列课程行。
 *
 * 用 RemoteViewsService 而不是静态几行 TextView：行数由数据决定，
 * 「今天还有 5 门课」就能画 5 行，不会像旧版那样被三行顶死。
 */
class RowsService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return Factory(applicationContext)
    }

    private class Factory(private val c: Context) : RemoteViewsFactory {

        private val chips = ArrayList<WidgetDay.Chip>()

        override fun onCreate() {
        }

        override fun onDataSetChanged() {
            chips.clear()
            val snap = WidgetData.load(c, TodayWidget.MAX_ROWS)
            chips.addAll(snap.card.chips)
        }

        override fun onDestroy() {
            chips.clear()
        }

        override fun getCount(): Int = chips.size

        override fun getViewAt(pos: Int): RemoteViews? {
            if (pos < 0 || pos >= chips.size) return null
            val ch = chips[pos]

            val v = RemoteViews(c.packageName, R.layout.widget_row)
            v.setTextViewText(R.id.wr_name, ch.name)
            v.setInt(R.id.wr_bar, "setBackgroundResource", WidgetData.barColorRes(ch.colorIndex))

            var line = ch.line
            if (ch.cdKind != WidgetDay.Chip.CD_NONE) {
                // 有倒计时时把它并进第二行：小组件里单开一行倒计时会把课名挤没
                val cd = WidgetDay.countdownText(ch.cdKind, ch.cdMin, WidgetData.nowMinute())
                line = if (line.isEmpty()) cd else "$line · $cd"
            }
            v.setTextViewText(R.id.wr_line, line)
            v.setViewVisibility(R.id.wr_line, if (line.isEmpty()) View.GONE else View.VISIBLE)

            val hasTag = ch.tag.isNotEmpty()
            v.setTextViewText(R.id.wr_tag, if (hasTag) ch.tag else "")
            v.setViewVisibility(R.id.wr_tag, if (hasTag) View.VISIBLE else View.GONE)

            // ListView 结构的小组件，行要能点必须给 fill-in intent —— 只有
            // setPendingIntentTemplate 是不够的，缺这一步整块点上去没反应。
            val fill = Intent()
            fill.data = Uri.parse("zsts://row/$pos")
            v.setOnClickFillInIntent(R.id.wr_row, fill)
            return v
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(pos: Int): Long = pos.toLong()

        override fun hasStableIds(): Boolean = false
    }
}

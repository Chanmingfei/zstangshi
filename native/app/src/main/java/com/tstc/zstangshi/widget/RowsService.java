package com.tstc.zstangshi.widget;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.tstc.zstangshi.R;
import com.tstc.zstangshi.data.WidgetDay;

import java.util.ArrayList;
import java.util.List;

/**
 * 小组件里那一列课程行。
 *
 * 用 RemoteViewsService 而不是静态几行 TextView：行数由数据决定，
 * 「今天还有 5 门课」就能画 5 行，不会像旧版那样被三行顶死。
 */
public class RowsService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext());
    }

    private static final class Factory implements RemoteViewsFactory {
        private final Context c;
        private final List<WidgetDay.Chip> chips = new ArrayList<>();

        Factory(Context c) {
            this.c = c;
        }

        @Override
        public void onCreate() {
        }

        @Override
        public void onDataSetChanged() {
            chips.clear();
            WidgetData.Snapshot snap = WidgetData.load(c, TodayWidget.MAX_ROWS);
            if (snap.card != null) chips.addAll(snap.card.chips);
        }

        @Override
        public void onDestroy() {
            chips.clear();
        }

        @Override
        public int getCount() {
            return chips.size();
        }

        @Override
        public RemoteViews getViewAt(int pos) {
            if (pos < 0 || pos >= chips.size()) return null;
            WidgetDay.Chip ch = chips.get(pos);

            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_row);
            v.setTextViewText(R.id.wr_name, ch.name);
            v.setInt(R.id.wr_bar, "setBackgroundResource", WidgetData.barColorRes(ch.colorIndex));

            String line = ch.line;
            if (ch.cdKind != WidgetDay.Chip.CD_NONE) {
                // 有倒计时时把它并进第二行：小组件里单开一行倒计时会把课名挤没
                String cd = WidgetDay.countdownText(ch.cdKind, ch.cdMin, WidgetData.nowMinute());
                line = line == null || line.isEmpty() ? cd : line + " · " + cd;
            }
            v.setTextViewText(R.id.wr_line, line == null ? "" : line);
            v.setViewVisibility(R.id.wr_line, line == null || line.isEmpty() ? View.GONE : View.VISIBLE);

            boolean hasTag = ch.tag != null && !ch.tag.isEmpty();
            v.setTextViewText(R.id.wr_tag, hasTag ? ch.tag : "");
            v.setViewVisibility(R.id.wr_tag, hasTag ? View.VISIBLE : View.GONE);

            // ListView 结构的小组件，行要能点必须给 fill-in intent —— 只有
            // setPendingIntentTemplate 是不够的，缺这一步整块点上去没反应。
            Intent fill = new Intent();
            fill.setData(android.net.Uri.parse("zsts://row/" + pos));
            v.setOnClickFillInIntent(R.id.wr_row, fill);
            return v;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int pos) {
            return pos;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }
    }
}

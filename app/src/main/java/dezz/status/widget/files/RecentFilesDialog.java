/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.CancellationSignal;
import android.os.Environment;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.File;
import java.text.DateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Recent files are a view. Folder operations always use the selected file's real location. */
public final class RecentFilesDialog {
    public interface Actions {
        void open(RecentFiles.Entry entry, boolean share);
        void showFolder(RecentFiles.Entry entry);
    }

    private final Activity owner;
    private final SharedPreferences preferences;
    private final ExecutorService reads;
    private final Actions actions;
    private final boolean showHidden;
    private final EditText filter;
    private final TextView status;
    private final ListView list;
    private final AlertDialog dialog;
    private Future<?> pending;
    private CancellationSignal signal;
    private RecentFiles result;
    private final List<RecentFiles.Entry> visible = new ArrayList<>();
    private final DateFormat dayFormat = DateFormat.getDateInstance(DateFormat.LONG, new Locale("ru", "RU"));
    private final DateFormat timeFormat;

    public RecentFilesDialog(Activity owner, SharedPreferences preferences, ExecutorService reads,
                             boolean showHidden, Actions actions) {
        this.owner = owner;
        timeFormat = android.text.format.DateFormat.getTimeFormat(owner);
        this.preferences = preferences;
        this.reads = reads;
        this.showHidden = showHidden;
        this.actions = actions;
        LinearLayout content = new LinearLayout(owner);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), 0, dp(20), 0);
        filter = new EditText(owner);
        filter.setSingleLine();
        filter.setHint("Фильтр по имени");
        content.addView(filter);
        status = new TextView(owner);
        status.setPadding(0, dp(6), 0, dp(6));
        content.addView(status);
        list = new ListView(owner);
        list.setAdapter(new Rows());
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        list.setOnItemClickListener((parent, view, position, id) -> perform(visible.get(position), 0));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            options(visible.get(position));
            return true;
        });
        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { render(); }
        });
        content.setFocusableInTouchMode(true);
        content.requestFocus();
        dialog = new AlertDialog.Builder(owner).setTitle("Недавние").setView(content)
                .setNeutralButton("Обновить", null).setNegativeButton("Закрыть", null).create();
        dialog.setOnDismissListener(ignored -> cancel());
    }

    public void show() {
        dialog.show();
        dialog.getWindow().setLayout((int) (owner.getResources().getDisplayMetrics().widthPixels * 0.90),
                (int) (owner.getResources().getDisplayMetrics().heightPixels * 0.88));
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> refresh());
        refresh();
    }

    public boolean showing() { return dialog.isShowing(); }
    public void dismiss() { dialog.dismiss(); }
    private int dp(int units) { return Math.round(owner.getResources().getDisplayMetrics().density * units); }

    private void cancel() {
        if (signal != null) signal.cancel();
        if (pending != null) pending.cancel(true);
    }

    public void refresh() {
        if (!showing()) return;
        cancel();
        CancellationSignal request = new CancellationSignal();
        signal = request;
        result = null;
        render();
        status.setText("Поиск файлов…");
        pending = reads.submit(() -> {
            try {
                List<File> roots = new ArrayList<>();
                roots.add(Environment.getExternalStorageDirectory());
                File[] mounted = new File("/storage").listFiles();
                if (mounted != null) for (File root : mounted) {
                    if (!"emulated".equals(root.getName()) && !"self".equals(root.getName())
                            && root.isDirectory() && root.canRead()) roots.add(root);
                }
                Map<String, Long> times = RecentFileTimes.collect(preferences, owner.getContentResolver(), request);
                RecentFiles found = RecentFiles.scan(roots, showHidden, times);
                owner.runOnUiThread(() -> {
                    if (request.isCanceled() || !showing()) return;
                    result = found;
                    render();
                });
            } catch (Exception failure) {
                owner.runOnUiThread(() -> {
                    if (!request.isCanceled() && showing()) status.setText(FileTaskService.safeMessage(failure));
                });
            }
        });
    }

    private void render() {
        visible.clear();
        String query = filter.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (result != null) {
            for (RecentFiles.Entry entry : result.entries)
                if (entry.file.name.toLowerCase(Locale.ROOT).contains(query)) visible.add(entry);
            String summary = visible.isEmpty() ? (query.isEmpty() ? "Недавних файлов пока нет" : "Совпадений нет")
                    : "Файлов: " + visible.size() + " · сначала новые";
            if (result.incomplete) summary += " · часть папок недоступна";
            status.setText(summary);
        }
        ((BaseAdapter)list.getAdapter()).notifyDataSetChanged();
    }

    private void options(RecentFiles.Entry entry) {
        new AlertDialog.Builder(owner).setTitle(entry.file.name)
                .setItems(new String[]{"Открыть", "Открыть папку файла", "Поделиться"},
                        (d, which) -> perform(entry, which)).show();
    }

    private void perform(RecentFiles.Entry entry, int action) {
        if (action == 1) {
            dismiss();
            actions.showFolder(entry);
        } else actions.open(entry, action == 2);
    }

    private LocalDate day(long time) {
        return Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private final class Rows extends BaseAdapter {
        public int getCount() { return visible.size(); }
        public Object getItem(int index) { return visible.get(index); }
        public long getItemId(int index) { return index; }
        public View getView(int index, View recycled, ViewGroup parent) {
            LinearLayout box;
            if (recycled == null) {
                box = new LinearLayout(owner);
                box.setOrientation(LinearLayout.VERTICAL);
                TextView header = new TextView(owner);
                header.setTypeface(null, Typeface.BOLD);
                header.setPadding(0, dp(8), 0, dp(2));
                box.addView(header);
                LinearLayout row = new LinearLayout(owner);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(56));
                LinearLayout labels = new LinearLayout(owner);
                labels.setOrientation(LinearLayout.VERTICAL);
                TextView name = label(18), detail = label(14);
                labels.addView(name);
                labels.addView(detail);
                row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                Button more = new Button(owner);
                more.setText("⋮");
                more.setFocusable(false);
                row.addView(more, new LinearLayout.LayoutParams(dp(52), dp(48)));
                box.addView(row);
                box.setTag(new View[]{header, name, detail, more});
            } else box = (LinearLayout)recycled;
            RecentFiles.Entry entry = visible.get(index);
            View[] views = (View[])box.getTag();
            TextView header = (TextView)views[0];
            LocalDate date = day(entry.time), today = LocalDate.now();
            header.setVisibility(index == 0 || !date.equals(day(visible.get(index - 1).time)) ? View.VISIBLE : View.GONE);
            header.setText(date.equals(today) ? "Сегодня" : date.equals(today.minusDays(1)) ? "Вчера" : dayFormat.format(new Date(entry.time)));
            ((TextView)views[1]).setText(entry.file.name);
            ((TextView)views[2]).setText(timeFormat.format(new Date(entry.time)) + "  ·  "
                    + Formatter.formatFileSize(owner, entry.file.size) + "  ·  "
                    + volumeLabel(entry.root) + FilePaths.parent(entry.file.path));
            views[3].setContentDescription("Действия с файлом " + entry.file.name);
            views[3].setOnClickListener(v -> options(entry));
            return box;
        }
        private TextView label(float size) {
            TextView text = new TextView(owner);
            text.setTextSize(size);
            text.setSingleLine();
            text.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            return text;
        }
    }

    public static String volumeLabel(File root) {
        return root.equals(Environment.getExternalStorageDirectory()) ? "Устройство" : "USB / SD · " + root.getName();
    }
}

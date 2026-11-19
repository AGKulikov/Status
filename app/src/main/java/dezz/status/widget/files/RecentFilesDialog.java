/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import dezz.status.widget.LongPressFeedback;
import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** A view of recent changes on mounted volumes. Actions operate on the original files. */
public final class RecentFilesDialog {
    public interface Actions {
        void open(RecentFiles.Entry entry, boolean share);
        void showFolder(RecentFiles.Entry entry);
    }

    private static final String TYPE_SETTING = "recentFileTypes";
    private final Activity owner;
    private final SharedPreferences preferences;
    private final ExecutorService reads;
    private final Actions actions;
    private final boolean showHidden;
    private final EditText filter;
    private final TextView status;
    private final ListView list;
    private final AlertDialog dialog;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable scan = this::startScan;
    private final DateFormat dateFormat = DateFormat.getDateTimeInstance(
            DateFormat.SHORT, DateFormat.SHORT, new Locale("ru", "RU"));
    private final LruCache<String, Bitmap> thumbnails = new LruCache<String, Bitmap>(2 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap image) { return image.getByteCount(); }
    };
    private final Set<Tile> loadingTiles = new HashSet<>();
    private EnumSet<RecentFiles.Category> categories;
    private RecentFiles result;
    private Future<?> pending, pendingMembers;
    private AlertDialog child, menu;
    private int generation;

    public RecentFilesDialog(Activity owner, SharedPreferences preferences, ExecutorService reads,
                             boolean showHidden, Actions actions) {
        this.owner = owner;
        this.preferences = preferences;
        this.reads = reads;
        this.showHidden = showHidden;
        this.actions = actions;
        categories = RecentFiles.defaultCategories();
        if (preferences.contains(TYPE_SETTING)) {
            Set<String> selected = new HashSet<>(Arrays.asList(
                    preferences.getString(TYPE_SETTING, "").split(",")));
            categories.clear();
            for (RecentFiles.Category type : RecentFiles.Category.values())
                if (selected.contains(type.name())) categories.add(type);
        }

        LinearLayout content = new LinearLayout(owner);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout controls = new LinearLayout(owner);
        filter = new EditText(owner);
        filter.setSingleLine();
        filter.setHint("Фильтр по имени");
        controls.addView(filter, new LinearLayout.LayoutParams(0, -2, 1));
        Button types = new Button(owner);
        types.setText("Типы файлов");
        types.setOnClickListener(v -> chooseTypes());
        controls.addView(types);
        content.addView(controls);
        status = label(14);
        status.setPadding(0, dp(4), 0, dp(4));
        content.addView(status);
        list = new ListView(owner);
        list.setAdapter(new Groups());
        list.setRecyclerListener(view -> ((Card) view).cancelTiles());
        list.setOnItemClickListener((p, v, i, id) -> {
            if (result != null && i < result.groups.size()) members(result.groups.get(i));
        });
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { queueScan(220); }
        });
        content.setFocusableInTouchMode(true);
        content.requestFocus();
        dialog = new AlertDialog.Builder(owner).setTitle("Недавние").setView(content)
                .setNeutralButton("Обновить", null).setNegativeButton("Закрыть", null).create();
        dialog.setOnDismissListener(d -> { cancel(); thumbnails.evictAll(); });
    }

    public void show() {
        dialog.show();
        dialog.getWindow().setLayout((int) (owner.getResources().getDisplayMetrics().widthPixels * .90),
                (int) (owner.getResources().getDisplayMetrics().heightPixels * .88));
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> refresh());
        refresh();
    }

    public boolean showing() { return dialog.isShowing(); }
    public void dismiss() { dialog.dismiss(); }
    private int dp(int units) { return Math.round(owner.getResources().getDisplayMetrics().density * units); }
    private TextView label(float size) {
        TextView text = new TextView(owner);
        text.setTextSize(size);
        text.setSingleLine();
        text.setEllipsize(TextUtils.TruncateAt.END);
        return text;
    }

    private void chooseTypes() {
        RecentFiles.Category[] types = RecentFiles.Category.values();
        String[] names = new String[types.length];
        boolean[] selected = new boolean[types.length];
        for (int i = 0; i < types.length; i++) {
            names[i] = types[i].label;
            selected[i] = categories.contains(types[i]);
        }
        child = new AlertDialog.Builder(owner).setTitle("Типы файлов")
                .setMultiChoiceItems(names, selected, (d, i, checked) -> selected[i] = checked)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Применить", (d, which) -> {
                    categories.clear();
                    List<String> keys = new ArrayList<>();
                    for (int i = 0; i < types.length; i++) if (selected[i]) {
                        categories.add(types[i]);
                        keys.add(types[i].name());
                    }
                    preferences.edit().putString(TYPE_SETTING, TextUtils.join(",", keys)).apply();
                    refresh();
                }).show();
    }

    private void cancel() {
        generation++;
        main.removeCallbacks(scan);
        if (pending != null) pending.cancel(true);
        if (pendingMembers != null) pendingMembers.cancel(true);
        if (menu != null) menu.dismiss();
        if (child != null) child.dismiss();
        for (Tile tile : new ArrayList<>(loadingTiles)) tile.cancel();
    }

    public void refresh() { queueScan(0); }

    private void queueScan(int delay) {
        if (!showing()) return;
        cancel();
        result = null;
        ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
        if (categories.isEmpty()) {
            status.setText("Выберите типы файлов");
            return;
        }
        status.setText("Поиск файлов…");
        main.postDelayed(scan, delay);
    }

    private void startScan() {
        if (!showing() || reads.isShutdown()) return;
        int request = generation;
        String query = filter.getText().toString().trim();
        EnumSet<RecentFiles.Category> selected = categories.clone();
        pending = reads.submit(() -> {
            try {
                List<File> roots = new ArrayList<>();
                roots.add(Environment.getExternalStorageDirectory());
                File[] mounted = new File("/storage").listFiles();
                if (mounted != null) for (File root : mounted) {
                    if (!"emulated".equals(root.getName()) && !"self".equals(root.getName())
                            && root.isDirectory() && root.canRead()) roots.add(root);
                }
                RecentFiles found = RecentFiles.scan(roots, showHidden, selected, query);
                main.post(() -> {
                    if (request != generation || !showing()) return;
                    result = found;
                    String message = found.groups.isEmpty()
                            ? (query.isEmpty() ? "Недавних файлов пока нет" : "Совпадений нет")
                            : "Групп: " + found.groups.size();
                    status.setText(found.incomplete ? message + " · часть папок недоступна" : message);
                    ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (request == generation && showing()) status.setText(FileTaskService.safeMessage(failure));
                });
            }
        });
    }

    private String source(RecentFiles.Group group) {
        return volumeLabel(group.root) + " · " + group.parent;
    }

    private void members(RecentFiles.Group group) {
        final RecentFiles snapshot = result;
        if (snapshot == null || !showing()) return;
        final int request = generation;
        ListView files = new ListView(owner);
        LinearLayout body = new LinearLayout(owner);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), 0, dp(16), 0);
        TextView message = label(14);
        message.setText("Поиск файлов…");
        body.addView(message);
        body.addView(files, new LinearLayout.LayoutParams(-1, 0, 1));
        AlertDialog sheet = new AlertDialog.Builder(owner).setTitle(group.category.label)
                .setView(body).setNegativeButton("Назад", null).create();
        child = sheet;
        sheet.show();
        sheet.getWindow().setLayout(dialog.getWindow().getAttributes().width,
                dialog.getWindow().getAttributes().height);
        pendingMembers = reads.submit(() -> {
            try {
                List<RecentFiles.Entry> entries = snapshot.members(group);
                main.post(() -> {
                    if (request != generation || !sheet.isShowing()) return;
                    message.setText(entries.isEmpty() ? "Файлов в этой группе больше нет" : source(group));
                    files.setAdapter(new Entries(entries));
                    files.setOnItemClickListener((p, v, i, id) -> actions.open(entries.get(i), false));
                    files.setOnItemLongClickListener((p, v, i, id) -> {
                        LongPressFeedback.play(v);
                        options(entries.get(i));
                        return true;
                    });
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (request == generation && sheet.isShowing()) message.setText(FileTaskService.safeMessage(error));
                });
            }
        });
        Future<?> job = pendingMembers;
        sheet.setOnDismissListener(d -> job.cancel(true));
    }

    private void options(RecentFiles.Entry entry) {
        menu = new AlertDialog.Builder(owner).setTitle(entry.file.name)
                .setItems(new String[]{"Открыть", "Открыть папку файла", "Поделиться"}, (d, which) -> {
                    if (which == 1) {
                        dismiss();
                        actions.showFolder(entry);
                    } else actions.open(entry, which == 2);
                }).show();
    }

    private final class Groups extends BaseAdapter {
        public int getCount() { return result == null ? 0 : result.groups.size(); }
        public RecentFiles.Group getItem(int index) { return result.groups.get(index); }
        public long getItemId(int index) { return index; }
        public View getView(int index, View recycled, ViewGroup parent) {
            Card card = recycled == null ? new Card() : (Card) recycled;
            card.bind(getItem(index));
            return card;
        }
    }

    private final class Card extends LinearLayout {
        private final TextView title = label(18), detail = label(14);
        private final LinearLayout previews = new LinearLayout(owner);
        private boolean pictures;
        Card() {
            super(owner);
            setOrientation(VERTICAL);
            setPadding(dp(6), dp(6), dp(6), dp(6));
            title.setTypeface(null, Typeface.BOLD);
            addView(title);
            addView(detail);
            previews.setWeightSum(4);
            addView(previews);
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int available = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
            previews.getLayoutParams().width = pictures ? Math.min(available, dp(640)) : LayoutParams.MATCH_PARENT;
            super.onMeasure(widthSpec, heightSpec);
        }
        void cancelTiles() {
            for (int i = 0; i < previews.getChildCount(); i++) ((Tile) previews.getChildAt(i)).cancel();
        }
        void bind(RecentFiles.Group group) {
            pictures = group.category == RecentFiles.Category.IMAGE;
            title.setText(group.category.label + " · " + group.count + "  ›");
            detail.setText(source(group) + "  ·  " + dateFormat.format(new Date(group.latestTime())));
            title.setContentDescription("Показать все файлы: " + group.category.label + ", " + group.count);
            int request = generation;
            View.OnClickListener expand = v -> { if (request == generation) members(group); };
            title.setOnClickListener(expand);
            setOnClickListener(expand);
            for (int i = 0; i < group.preview.size(); i++) {
                if (previews.getChildCount() <= i) previews.addView(new Tile(), new LayoutParams(0, -2, 1));
                Tile tile = (Tile) previews.getChildAt(i);
                tile.setVisibility(VISIBLE);
                tile.bind(group.preview.get(i), pictures);
            }
            for (int i = group.preview.size(); i < previews.getChildCount(); i++) {
                Tile tile = (Tile) previews.getChildAt(i);
                tile.cancel();
                tile.setVisibility(GONE);
            }
        }
    }

    private final class Tile extends LinearLayout {
        private final ImageView image = new ImageView(owner);
        private final TextView name = label(15);
        private int version;
        private Future<?> loading;
        Tile() {
            super(owner);
            setPadding(dp(4), dp(4), dp(4), 0);
            setMinimumHeight(dp(48));
            setGravity(Gravity.CENTER_VERTICAL);
            setOrientation(VERTICAL);
            image.setScaleType(ImageView.ScaleType.FIT_START);
            addView(image, new LayoutParams(-1, dp(80)));
            addView(name);
        }
        void cancel() {
            version++;
            if (loading != null) loading.cancel(true);
            loadingTiles.remove(this);
        }
        void bind(RecentFiles.Entry entry, boolean picture) {
            cancel();
            final int binding = version;
            name.setText(entry.file.name);
            setContentDescription(entry.file.name);
            int request = generation;
            setOnClickListener(v -> { if (request == generation && showing()) actions.open(entry, false); });
            setOnLongClickListener(v -> {
                if (request != generation || !showing()) return false;
                LongPressFeedback.play(v);
                options(entry);
                return true;
            });
            image.setImageDrawable(null);
            image.setVisibility(picture ? VISIBLE : GONE);
            if (!picture) return;
            String key = entry.root.getPath() + entry.file.path + "\n" + entry.time + "/" + entry.file.size;
            Bitmap cached = thumbnails.get(key);
            if (cached != null) { image.setImageBitmap(cached); return; }
            loadingTiles.add(this);
            int target = dp(128);
            loading = reads.submit(() -> {
                Bitmap result = thumbnail(entry, target);
                main.post(() -> {
                    if (binding != version || !showing()) return;
                    loadingTiles.remove(this);
                    if (result != null) {
                        thumbnails.put(key, result);
                        image.setImageBitmap(result);
                    } else image.setVisibility(GONE);
                });
            });
        }
    }

    private static Bitmap thumbnail(RecentFiles.Entry entry, int target) {
        try {
            File file = new LocalFiles(entry.root).resolve(entry.file.path);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0 || Thread.currentThread().isInterrupted()) return null;
            int size = Math.max(opts.outWidth, opts.outHeight);
            opts.inSampleSize = 1;
            while (size / opts.inSampleSize > target * 2) opts.inSampleSize *= 2;
            opts.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(file.getPath(), opts);
        } catch (IOException | RuntimeException unavailable) { return null; }
    }

    private final class Entries extends BaseAdapter {
        private final List<RecentFiles.Entry> entries;
        Entries(List<RecentFiles.Entry> entries) { this.entries = entries; }
        public int getCount() { return entries.size(); }
        public RecentFiles.Entry getItem(int i) { return entries.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int i, View recycled, ViewGroup parent) {
            LinearLayout row = (LinearLayout) recycled;
            if (row == null) {
                row = new LinearLayout(owner);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView name = label(17);
                row.addView(name, new LinearLayout.LayoutParams(0, dp(52), 1));
                name.setGravity(Gravity.CENTER_VERTICAL);
                Button more = new Button(owner);
                more.setText("⋮");
                more.setFocusable(false);
                row.addView(more, new LinearLayout.LayoutParams(dp(52), dp(48)));
            }
            RecentFiles.Entry entry = getItem(i);
            TextView name = (TextView) row.getChildAt(0);
            name.setText(entry.file.name + "  ·  " + Formatter.formatFileSize(owner, entry.file.size));
            View more = row.getChildAt(1);
            more.setContentDescription("Действия с файлом " + entry.file.name);
            more.setOnClickListener(v -> options(entry));
            return row;
        }
    }

    public static String volumeLabel(File root) {
        return root.equals(Environment.getExternalStorageDirectory()) ? "Устройство" : "USB / SD · " + root.getName();
    }
}

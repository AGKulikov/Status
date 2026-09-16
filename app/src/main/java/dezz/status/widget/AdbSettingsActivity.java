/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import dezz.status.widget.adb.*;
import dezz.status.widget.settings.SettingsBackNavigation;
import java.util.*;

/** Two-pane ADB console matching the v46.1 groups, with retained jobs and explicit cancellation. */
public final class AdbSettingsActivity extends AppCompatActivity {
    private AdbConsoleModel model;
    private TextView status, properties;
    private Spinner commands, services, persistent;
    private EditText input;
    private Button execute, commonExecute, enableService;
    private final List<View> controls = new ArrayList<>();
    private final LogAdapter log = new LogAdapter();
    private RecyclerView journal;
    private boolean binding;
    private int actualMode = -1;
    private long lastLine;
    private List<AdbCommandCatalog.Entry> commandItems = new ArrayList<>(), serviceItems = new ArrayList<>();
    private int textColor, cardColor;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        textColor = night ? 0xFFECECF1 : 0xFF262A30; cardColor = night ? 0xFF23262C : 0xFFE9EAED;
        LinearLayout root = column(); root.setPadding(dp(12), dp(6), dp(12), dp(6));
        root.setBackgroundColor(night ? 0xFF121418 : 0xFFF7F7F9);
        LinearLayout header = row();
        Button back = button("← Назад", this::finish); header.addView(back);
        TextView title = text("ADB", 22); title.setGravity(Gravity.CENTER_VERTICAL); header.addView(title, weight());
        Button extra = button("⋮", () -> extras(header)); extra.setContentDescription("Служебные параметры ADB"); header.addView(extra);
        root.addView(header);
        LinearLayout panes = row(); root.addView(panes, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout left = column(); left.setPadding(dp(5), dp(12), dp(5), 0); panes.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
        status = text("Disconnected", 18); status.setGravity(Gravity.CENTER); left.addView(status);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout groups = column(); groups.setPadding(dp(8), dp(8), dp(8), 0); scroll.addView(groups); left.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout general = card(groups, "Общие команды"); commands = spinner(); general.addView(commands);
        commonExecute = button("Выполнить", () -> {
            AdbCommandCatalog.Entry entry = selected(commands);
            if (entry != null && !entry.command.isEmpty()) { input.setText(entry.command); model.execute(entry.command); }
        }); general.addView(commonExecute); controls.add(commonExecute); controls.add(commands);
        LinearLayout access = card(groups, "Специальные возможности"); services = spinner(); access.addView(services);
        enableService = button("Включить", () -> { AdbCommandCatalog.Entry entry = selected(services);
            if (entry != null) model.enableAccessibility(entry.command); }); access.addView(enableService); controls.add(enableService); controls.add(services);
        LinearLayout dev = card(groups, "Параметры разработчика"), devRow = row();
        addControl(devRow, "Показать", () -> model.developer(true)); addControl(devRow, "Скрыть", () -> model.developer(false));
        dev.addView(devRow); addControl(dev, "Открыть", () -> model.developer(null));
        LinearLayout adb = card(groups, "Постоянный ADB"); persistent = new UserSpinner();
        persistent.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"OFF", "ON", "USB"}));
        persistent.setSelection(AdapterView.INVALID_POSITION); adb.addView(persistent); controls.add(persistent);
        properties = text("Состояние ещё не прочитано", 12); adb.addView(properties);
        persistent.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                UserSpinner picker = (UserSpinner) persistent;
                if (picker.userSelection && !binding && position >= 0 && position != actualMode) model.persistentAdb(position);
                picker.userSelection = false;
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        LinearLayout verify = card(groups, "Проверка установки приложений");
        Button enableVerification = addControl(verify, "Включить", () -> model.installVerification(true));
        enableVerification.setVisibility(View.GONE); // hidden in the reference; available from the service menu
        addControl(verify, "Отключить", () -> model.installVerification(false));
        LinearLayout wifi = card(groups, "Активировать Wi-Fi"); addControl(wifi, "Включить", () -> model.wifi()); wifi.setVisibility(View.GONE);

        LinearLayout right = column(); right.setPadding(dp(5), dp(12), dp(5), 0); panes.addView(right, new LinearLayout.LayoutParams(0, -1, 2));
        LinearLayout commandRow = row();
        execute = button("Выполнить", () -> { if (model.state().getValue() != null && model.state().getValue().busy) model.cancel();
            else model.execute(input.getText().toString()); });
        commandRow.addView(execute, new LinearLayout.LayoutParams(dp(220), -2));
        input = new EditText(this); input.setTextColor(textColor); input.setTextSize(16);
        input.setHint("Введите команду без \"adb\""); input.setSingleLine(true); input.setTextDirection(View.TEXT_DIRECTION_LTR);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setContentDescription("Команда ADB без префикса adb");
        input.setOnEditorActionListener((view, action, event) -> { if (action == EditorInfo.IME_ACTION_GO) { model.execute(input.getText().toString()); return true; } return false; });
        commandRow.addView(input, weight()); right.addView(commandRow);
        journal = new RecyclerView(this); journal.setLayoutManager(new LinearLayoutManager(this)); journal.setAdapter(log);
        journal.setBackgroundColor(night ? 0xFF191C21 : 0xFFE8E9EC); journal.setPadding(dp(5), dp(5), dp(5), dp(5));
        journal.setContentDescription("Журнал команд ADB");
        LinearLayout.LayoutParams journalParams = new LinearLayout.LayoutParams(-1, 0, 1); journalParams.topMargin = dp(10); right.addView(journal, journalParams);
        setContentView(root); SettingsBackNavigation.applySafeTopInset(this, root);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        model = new ViewModelProvider(this).get(AdbConsoleModel.class);
        model.state().observe(this, this::render);
        if (saved != null) input.setText(saved.getString("input", ""));
    }
    @Override protected void onResume() {
        super.onResume();
        String draft = AdbRemoteInbox.takeDraft(); if (draft != null && input != null) input.setText(draft);
        if (model != null) model.refreshScreen();
    }
    @Override protected void onSaveInstanceState(Bundle saved) { super.onSaveInstanceState(saved); saved.putString("input", input.getText().toString()); }
    private void render(AdbConsoleModel.State state) {
        status.setText(state.busy && !state.connected ? "Connecting… / Выполнение…" : state.status);
        status.setTextColor(state.connected ? 0xFF1E8449 : 0xFFCB8080);
        for (View control : controls) control.setEnabled(!state.busy);
        input.setEnabled(!state.busy); execute.setText(state.busy ? "Отмена" : "Выполнить");
        binding = true;
        updateEntries(commands, commandItems, state.commands); commandItems = state.commands;
        updateEntries(services, serviceItems, state.services); serviceItems = state.services;
        actualMode = state.mode;
        if (persistent.getSelectedItemPosition() != state.mode) {
            ((UserSpinner) persistent).userSelection = false; persistent.setSelection(state.mode);
        }
        properties.setText((state.mode < 0 ? "Режим не подтверждён.\n" : "") + state.properties);
        enableService.setEnabled(!state.busy && !state.services.isEmpty()); services.setEnabled(!state.busy && !state.services.isEmpty());
        commonExecute.setEnabled(!state.busy && selected(commands) != null && !selected(commands).command.isEmpty());
        binding = false;
        if (!state.lines.isEmpty() && state.lines.get(state.lines.size() - 1).id != lastLine) {
            lastLine = state.lines.get(state.lines.size() - 1).id; log.items = state.lines; log.notifyDataSetChanged();
            journal.scrollToPosition(log.getItemCount() - 1);
        }
    }
    private void updateEntries(Spinner spinner, List<AdbCommandCatalog.Entry> old, List<AdbCommandCatalog.Entry> next) {
        if (same(old, next) && spinner.getAdapter() != null) return;
        AdbCommandCatalog.Entry selected = selected(spinner);
        ArrayAdapter<AdbCommandCatalog.Entry> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, next);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter);
        if (selected != null) for (int i = 0; i < next.size(); i++) if (next.get(i).command.equals(selected.command)) { spinner.setSelection(i); break; }
        if (spinner == commands) spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                AdbCommandCatalog.Entry item = selected(commands); AdbConsoleModel.State current = model.state().getValue();
                commonExecute.setEnabled(item != null && !item.command.isEmpty() && (current == null || !current.busy));
            }
            @Override public void onNothingSelected(AdapterView<?> p) { commonExecute.setEnabled(false); }
        });
    }
    private boolean same(List<AdbCommandCatalog.Entry> a, List<AdbCommandCatalog.Entry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).title.equals(b.get(i).title) || !a.get(i).command.equals(b.get(i).command)) return false;
        return true;
    }
    private void extras(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        String[] titles = {"Переподключить", "Включить проверку установки", "Активировать Wi-Fi", "Служебные команды",
                "ADB root", "ADB unroot", "Проверить UID", "Проверить su 0"};
        for (int i = 0; i < titles.length; i++) menu.getMenu().add(0, i, i, titles[i]);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 0: model.execute("connect"); break;
                case 1: model.installVerification(true); break;
                case 2: model.wifi(); break;
                case 3: new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Служебные команды")
                        .setMessage(AdbLocalActions.HELP).setPositiveButton("Закрыть", null).show(); break;
                case 4: model.execute("root"); break;
                case 5: model.execute("unroot"); break;
                case 6: model.execute("id -u"); break;
                case 7: model.execute("su 0 id -u"); break;
            }
            return true;
        }); menu.show();
    }
    private AdbCommandCatalog.Entry selected(Spinner spinner) {
        Object item = spinner.getSelectedItem(); return item instanceof AdbCommandCatalog.Entry ? (AdbCommandCatalog.Entry) item : null;
    }
    private Spinner spinner() { Spinner result = new Spinner(this); result.setMinimumHeight(dp(48)); return result; }
    private final class UserSpinner extends Spinner {
        boolean userSelection;
        UserSpinner() { super(AdbSettingsActivity.this); setMinimumHeight(dp(48)); }
        @Override public boolean performClick() { userSelection = true; return super.performClick(); }
        @Override public boolean onKeyDown(int key, android.view.KeyEvent event) {
            userSelection = true; return super.onKeyDown(key, event);
        }
    }
    private LinearLayout column() { LinearLayout result = row(); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private LinearLayout row() { return new LinearLayout(this); }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private TextView text(String value, int size) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(textColor); return view; }
    private Button button(String title, Runnable action) { Button result = new Button(this); result.setText(title); result.setAllCaps(false); result.setMinHeight(dp(48)); result.setOnClickListener(v -> action.run()); return result; }
    private Button addControl(LinearLayout parent, String title, Runnable action) {
        Button result = button(title, action); controls.add(result);
        parent.addView(result, parent.getOrientation() == LinearLayout.HORIZONTAL ? weight() : new LinearLayout.LayoutParams(-1, -2)); return result;
    }
    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout card = column(); card.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable background = new GradientDrawable(); background.setColor(cardColor); background.setCornerRadius(dp(10)); card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(8); parent.addView(card, params);
        TextView heading = text(title, 17); heading.setGravity(Gravity.CENTER); card.addView(heading); return card;
    }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private final class LogAdapter extends RecyclerView.Adapter<LogHolder> {
        List<AdbConsoleModel.Line> items = new ArrayList<>();
        @Override public LogHolder onCreateViewHolder(ViewGroup parent, int type) {
            TextView view = text("", 14); view.setTypeface(Typeface.MONOSPACE); view.setTextIsSelectable(true);
            view.setPadding(dp(16), dp(4), dp(16), dp(4)); view.setLayoutParams(new RecyclerView.LayoutParams(-1, -2)); return new LogHolder(view);
        }
        @Override public void onBindViewHolder(LogHolder holder, int position) {
            AdbConsoleModel.Line line = items.get(position); holder.text.setText(line.text);
            holder.text.setTextColor(line.kind == AdbConsoleModel.COMMAND ? 0xFF3779F3 : line.kind == AdbConsoleModel.ERROR ? 0xFFCB8080
                    : line.kind == AdbConsoleModel.SUCCESS ? 0xFF1E8449 : 0xFF8E8E8E);
        }
        @Override public int getItemCount() { return items.size(); }
    }
    private static final class LogHolder extends RecyclerView.ViewHolder {
        final TextView text; LogHolder(TextView text) { super(text); this.text = text; }
    }
}

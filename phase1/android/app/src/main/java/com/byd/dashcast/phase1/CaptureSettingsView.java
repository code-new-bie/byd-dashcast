package com.byd.dashcast.phase1;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.util.Locale;
import java.util.function.Consumer;

/** UI-only capture editor; an accepted session receives a frozen value, never this View.
 * @author Codex
 * @date 2026/10/14
 */
final class CaptureSettingsView {
    private static final String PREFERENCE_FILE = "dashcast_capture_geometry_v1";
    private static final String SELECTION_KEY = "last_selection";
    private static final CaptureOptions.Mode[] MODES = {
            CaptureOptions.Mode.FIT, CaptureOptions.Mode.CROP, CaptureOptions.Mode.REGION, CaptureOptions.Mode.STRETCH
    };
    private final RadioGroup choices;
    private final RadioButton[] buttons = new RadioButton[MODES.length];
    private final EditText[] percentages = new EditText[4];
    private final CheckBox benchOnly;
    private final SharedPreferences preferences;
    private final LinearLayout root;
    private boolean controlsActive = true;

    CaptureSettingsView(Context context, Consumer<String> log) {
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        preferences = context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE);
        TextView explanation = new TextView(context);
        explanation.setText(R.string.capture_geometry_scope);
        root.addView(explanation);
        choices = new RadioGroup(context);
        int[] labels = {R.string.capture_fit, R.string.capture_crop, R.string.capture_region, R.string.capture_stretch};
        for (int index = 0; index < MODES.length; index++) {
            RadioButton button = new RadioButton(context);
            button.setId(android.view.View.generateViewId());
            button.setText(labels[index]);
            buttons[index] = button;
            choices.addView(button);
        }
        root.addView(choices);
        int[] fields = {R.string.capture_left, R.string.capture_top, R.string.capture_right, R.string.capture_bottom};
        for (int index = 0; index < percentages.length; index++) {
            TextView label = new TextView(context);
            label.setText(fields[index]);
            root.addView(label);
            EditText field = new EditText(context);
            field.setInputType(InputType.TYPE_CLASS_NUMBER);
            field.setSingleLine(true);
            field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(3)});
            percentages[index] = field;
            root.addView(field);
        }
        benchOnly = new CheckBox(context);
        benchOnly.setText(R.string.capture_bench_acknowledgement);
        // Never persist safety acknowledgement or infer it from a previous run.
        root.addView(benchOnly);
        CaptureOptions saved = CaptureOptions.of(CaptureOptions.Mode.FIT);
        try {
            saved = CaptureOptions.parse(preferences.getString(SELECTION_KEY, "fit"));
        } catch (IllegalArgumentException | ClassCastException error) {
            log.accept("CAPTURE_SETTINGS_INVALID: using fit, no session started; " + error.getClass().getSimpleName());
        }
        for (int index = 0; index < MODES.length; index++) {
            if (MODES[index] == saved.mode()) {
                choices.check(buttons[index].getId());
            }
        }
        // Percent fields are protocol inputs: deliberately ASCII, not locale digit glyphs.
        percentages[0].setText(String.format(Locale.ROOT, "%d", saved.leftPercent()));
        percentages[1].setText(String.format(Locale.ROOT, "%d", saved.topPercent()));
        percentages[2].setText(String.format(Locale.ROOT, "%d", saved.rightPercent()));
        percentages[3].setText(String.format(Locale.ROOT, "%d", saved.bottomPercent()));
        choices.setOnCheckedChangeListener((group, checkedId) -> updatePercentageFields());
        updatePercentageFields();
    }

    LinearLayout view() {
        return root;
    }

    CaptureOptions snapshot() {
        CaptureOptions.Mode selected = mode();
        if (selected != CaptureOptions.Mode.REGION) {
            return CaptureOptions.of(selected);
        }
        StringBuilder token = new StringBuilder("region");
        for (EditText field : percentages) {
            token.append(':').append(field.getText().toString().trim());
        }
        return CaptureOptions.parse(token.toString());
    }

    boolean benchAcknowledged() {
        return benchOnly.isChecked();
    }

    void remember(CaptureOptions options) {
        preferences.edit().putString(SELECTION_KEY, options.token()).apply();
    }

    void controlsEnabled(boolean enabled) {
        controlsActive = enabled;
        for (RadioButton button : buttons) {
            button.setEnabled(enabled);
        }
        updatePercentageFields();
        benchOnly.setEnabled(enabled);
    }

    private void updatePercentageFields() {
        boolean region = mode() == CaptureOptions.Mode.REGION;
        for (EditText field : percentages) {
            field.setEnabled(controlsActive && region);
        }
    }

    private CaptureOptions.Mode mode() {
        for (int index = 0; index < buttons.length; index++) {
            if (buttons[index].getId() == choices.getCheckedRadioButtonId()) {
                return MODES[index];
            }
        }
        throw new IllegalArgumentException("Select a capture mode first");
    }
}

package com.winlator.cmod.ai;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;

/**
 * Settings for the AI assistant, reachable both from the chat panel and from the app drawer.
 *
 * Two things here are deliberate:
 *
 * 1. The body is a ScrollView inflated into ContentDialog's FrameLayout, which is
 *    layout_height=0dp + layout_weight=1 and therefore claims "all remaining height". ScrollView
 *    has no maxHeight attribute (android:maxHeight is silently ignored), so without clamping the
 *    frame the body grows past the dialog and pushes the bottom bar - including OK - off screen,
 *    which is what made the settings impossible to save in landscape. The frame is sized here.
 *
 * 2. Saving never depends on the connection test. A failed test only writes to TVAiTestResult;
 *    the values are persisted by save() on confirm and again on dismiss (idempotent), so a
 *    half-finished or failed test can never throw away a typed API key.
 */
public final class AiSettingsDialog {

    private AiSettingsDialog() {}

    public static void show(Context context) {
        show(context, null);
    }

    public static void show(Context context, Runnable onSaved) {
        ContentDialog dialog = new ContentDialog(context, R.layout.ai_settings_dialog);
        dialog.setTitle(R.string.ai_settings_title);
        dialog.setBottomBarText(context.getString(R.string.ok));

        CheckBox enabled = dialog.findViewById(R.id.CBAiEnabled);
        EditText baseUrl = dialog.findViewById(R.id.ETAiBaseUrl);
        EditText apiKey = dialog.findViewById(R.id.ETAiApiKey);
        EditText model = dialog.findViewById(R.id.ETAiModel);
        CheckBox autoDiagnose = dialog.findViewById(R.id.CBAiAutoDiagnose);
        CheckBox allowCommands = dialog.findViewById(R.id.CBAiAllowCommands);
        EditText maxRounds = dialog.findViewById(R.id.ETAiMaxRounds);
        Button test = dialog.findViewById(R.id.BTAiTest);
        TextView testResult = dialog.findViewById(R.id.TVAiTestResult);

        // Cancel would contradict "changes are kept", so this dialog only offers OK.
        View cancel = dialog.findViewById(R.id.BTCancel);
        if (cancel != null) cancel.setVisibility(View.GONE);

        enabled.setChecked(AiConfig.isEnabled(context));
        baseUrl.setText(AiConfig.getBaseUrl(context));
        apiKey.setText(AiConfig.getApiKey(context));
        model.setText(AiConfig.getModel(context));
        autoDiagnose.setChecked(AiConfig.isAutoDiagnose(context));
        allowCommands.setChecked(AiConfig.isCommandAccessAllowed(context));
        maxRounds.setText(String.valueOf(AiConfig.getMaxToolRounds(context)));

        final boolean[] saved = {false};
        Runnable save = () -> {
            if (saved[0]) return;
            saved[0] = true;

            AiConfig.setEnabled(context, enabled.isChecked());
            AiConfig.setBaseUrl(context, baseUrl.getText().toString());
            AiConfig.setApiKey(context, apiKey.getText().toString());
            AiConfig.setModel(context, model.getText().toString());
            AiConfig.setAutoDiagnose(context, autoDiagnose.isChecked());
            AiConfig.setCommandAccessAllowed(context, allowCommands.isChecked());

            try {
                int rounds = Integer.parseInt(maxRounds.getText().toString().trim());
                AiConfig.setMaxToolRounds(context, rounds);
            }
            catch (NumberFormatException ignored) {
                // Keep the previous value when the field is empty or garbage.
            }

            if (onSaved != null) onSaved.run();
        };

        test.setOnClickListener(v -> {
            testResult.setVisibility(TextView.VISIBLE);
            testResult.setTextColor(Color.parseColor("#9AB0C4"));
            testResult.setText(R.string.ai_testing);
            test.setEnabled(false);

            AiConfigSnapshot snapshot = new AiConfigSnapshot(
                    baseUrl.getText().toString().trim(),
                    apiKey.getText().toString().trim(),
                    model.getText().toString().trim(),
                    allowCommands.isChecked());

            if (snapshot.apiKey.isEmpty()) {
                testResult.setTextColor(Color.parseColor("#E8908C"));
                testResult.setText(R.string.ai_not_configured);
                test.setEnabled(true);
                return;
            }

            AiClient.ping(snapshot, new AiClient.Callback() {
                @Override
                public void onResult(AiMessage message) {
                    test.post(() -> {
                        testResult.setTextColor(Color.parseColor("#8FD6A0"));
                        testResult.setText(R.string.ai_test_ok);
                        test.setEnabled(true);
                    });
                }

                @Override
                public void onError(String error) {
                    test.post(() -> {
                        testResult.setTextColor(Color.parseColor("#E8908C"));
                        testResult.setText(context.getString(R.string.ai_test_failed) + error
                                + "\n" + context.getString(R.string.ai_test_failed_save_hint));
                        test.setEnabled(true);
                    });
                }
            });
        });

        dialog.setOnConfirmCallback(save);
        // Safety net: dismissing by back / outside the dialog still keeps what was typed.
        dialog.setOnDismissListener(d -> save.run());
        // The window is only measurable once shown, so the body bound is applied there: first a
        // conservative one so nothing spills off screen, then a refined one after layout.
        dialog.setOnShowListener(d -> {
            View frame = dialog.findViewById(R.id.FrameLayout);
            if (frame == null) return;
            boundBody(context, dialog, frame, false);
            frame.post(() -> boundBody(context, dialog, frame, true));
        });

        dialog.show();
    }

    /**
     * Turns the weighted, "fill the rest" body frame into a bounded box.
     *
     * The bound is the window height minus the dialog chrome (title bar, bottom bar, paddings).
     * With {@code measureContent} the body's natural height is measured first, so short content
     * keeps its natural size and only tall content scrolls. Either way the bottom bar - and its OK
     * button - stays on screen.
     */
    private static void boundBody(Context context, ContentDialog dialog, View frame, boolean measureContent) {
        if (dialog.getWindow() == null || !(frame instanceof ViewGroup)) return;

        // The visible display frame is the usable height (screen minus any system bars). Before the
        // first layout it can still be empty, so fall back to the raw screen height.
        Rect visible = new Rect();
        dialog.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
        int windowHeight = visible.height();
        int windowWidth = visible.width();
        if (windowHeight <= 0 || windowWidth <= 0) {
            android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            windowHeight = metrics.heightPixels;
            windowWidth = metrics.widthPixels;
        }

        ViewGroup group = (ViewGroup) frame;
        int available = windowHeight - dp(context, CHROME_RESERVE_DP);
        if (available < dp(context, MIN_BODY_DP)) available = dp(context, MIN_BODY_DP);

        int desired = available;
        if (measureContent && group.getChildCount() > 0) {
            int width = group.getWidth() > 0 ? group.getWidth() : windowWidth;
            View body = group.getChildAt(0);
            body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int measured = body.getMeasuredHeight();
            if (measured > 0) desired = Math.min(measured, available);
        }

        ViewGroup.LayoutParams params = frame.getLayoutParams();
        if (params == null) return;
        if (params.height == desired && !(params instanceof LinearLayout.LayoutParams
                && ((LinearLayout.LayoutParams) params).weight != 0f)) return;

        params.height = desired;
        if (params instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) params).weight = 0f;
        }
        frame.setLayoutParams(params);
    }

    /** Space taken by the title bar, the bottom bar and the dialog paddings. */
    private static final int CHROME_RESERVE_DP = 150;

    /** Never shrink the body below this, or the dialog becomes useless on very short screens. */
    private static final int MIN_BODY_DP = 150;

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}

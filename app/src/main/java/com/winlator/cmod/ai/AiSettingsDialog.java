package com.winlator.cmod.ai;

import android.content.Context;
import android.graphics.Color;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;

/**
 * Settings for the AI assistant, reachable both from the chat panel and from the app settings tab.
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

        enabled.setChecked(AiConfig.isEnabled(context));
        baseUrl.setText(AiConfig.getBaseUrl(context));
        apiKey.setText(AiConfig.getApiKey(context));
        model.setText(AiConfig.getModel(context));
        autoDiagnose.setChecked(AiConfig.isAutoDiagnose(context));
        allowCommands.setChecked(AiConfig.isCommandAccessAllowed(context));
        maxRounds.setText(String.valueOf(AiConfig.getMaxToolRounds(context)));

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
                        testResult.setText(context.getString(R.string.ai_test_failed) + error);
                        test.setEnabled(true);
                    });
                }
            });
        });

        dialog.setOnConfirmCallback(() -> {
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
        });

        dialog.show();
    }
}

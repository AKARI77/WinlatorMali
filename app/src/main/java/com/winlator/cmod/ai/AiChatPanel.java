package com.winlator.cmod.ai;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.winlator.cmod.R;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The chat panel that drops into the game's frame layout.
 *
 * Built as a plain view rather than a Dialog on purpose: the activity is fullscreen landscape and
 * the X server surface lives in the same window, so a dialog would fight the surface for input
 * focus and would be dismissed by back gestures meant for the game. A sibling view keeps everything
 * inside one window.
 */
public class AiChatPanel implements AiConversation.Listener {

    private final Activity activity;
    private final FrameLayout host;
    private final AiConversation conversation;

    private View root;
    private ScrollView scrollView;
    private LinearLayout messageList;
    private LinearLayout quickPrompts;
    private EditText input;
    private ImageButton sendButton;
    private TextView statusView;

    private final Deque<TextView> pendingChips = new ArrayDeque<>();
    private ViewTreeObserver.OnGlobalLayoutListener keyboardListener;
    private boolean exporting;
    private boolean showing;

    public AiChatPanel(Activity activity, FrameLayout host, AiConversation conversation) {
        this.activity = activity;
        this.host = host;
        this.conversation = conversation;
        conversation.setListener(this);
    }

    /** Inflates the panel and adds it to the host, hidden. */
    public void attach() {
        if (root != null) return;

        root = LayoutInflater.from(activity).inflate(R.layout.ai_chat_panel, host, false);
        scrollView = root.findViewById(R.id.SVAiMessages);
        messageList = root.findViewById(R.id.LLAiMessages);
        quickPrompts = root.findViewById(R.id.LLAiQuickPrompts);
        input = root.findViewById(R.id.ETAiInput);
        sendButton = root.findViewById(R.id.BTAiSend);
        statusView = root.findViewById(R.id.TVAiStatus);

        // The panel is a side sheet: ~40% wide, anchored right, leaving the game visible.
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.40f),
                ViewGroup.LayoutParams.MATCH_PARENT);
        params.gravity = Gravity.END;
        params.setMargins(0, dp(10), dp(10), dp(10));
        root.setLayoutParams(params);
        root.setVisibility(View.GONE);
        root.setClickable(true);

        host.addView(root);

        // The activity is fullscreen with no windowSoftInputMode resize, so the IME would cover the
        // composer instead of shrinking the layout. Lift the panel by the keyboard height ourselves.
        keyboardListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            private boolean visible;

            @Override
            public void onGlobalLayout() {
                if (root == null) return;
                int height = keyboardHeight();
                boolean nowVisible = height > host.getRootView().getHeight() * 0.15f;
                if (nowVisible == visible) return;
                visible = nowVisible;

                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) root.getLayoutParams();
                lp.bottomMargin = dp(10) + (nowVisible ? height : 0);
                root.setLayoutParams(lp);
                if (nowVisible) scrollToBottom();
            }
        };
        host.getViewTreeObserver().addOnGlobalLayoutListener(keyboardListener);

        statusView.setText(R.string.ai_ready);
        sendButton.setOnClickListener(v -> submit());
        root.findViewById(R.id.BTAiClose).setOnClickListener(v -> hide());
        root.findViewById(R.id.BTAiNewChat).setOnClickListener(v -> startNewChat());
        root.findViewById(R.id.BTAiSettings).setOnClickListener(v -> openSettings());
        root.findViewById(R.id.BTAiExport).setOnClickListener(v -> exportBundle());

        quickPrompts.findViewById(R.id.TVAiQuickCrash).setOnClickListener(
                v -> sendQuick(R.string.ai_quick_crash));
        quickPrompts.findViewById(R.id.TVAiQuickBlackScreen).setOnClickListener(
                v -> sendQuick(R.string.ai_quick_black_screen));
        quickPrompts.findViewById(R.id.TVAiQuickMissingDll).setOnClickListener(
                v -> sendQuick(R.string.ai_quick_missing_dll));
        quickPrompts.findViewById(R.id.TVAiQuickSlow).setOnClickListener(
                v -> sendQuick(R.string.ai_quick_slow));
    }

    private void sendQuick(int stringRes) {
        input.setText(activity.getString(stringRes));
        input.setSelection(input.getText().length());
        submit();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /** Height of the on-screen keyboard, derived from the window's visible frame. */
    private int keyboardHeight() {
        Rect rect = new Rect();
        host.getWindowVisibleDisplayFrame(rect);
        int screenHeight = host.getRootView().getHeight();
        return Math.max(0, screenHeight - rect.bottom);
    }

    public boolean isShowing() {
        return showing;
    }

    public void toggle() {
        if (showing) hide();
        else show();
    }

    public void show() {
        if (root == null) return;
        showing = true;
        root.setVisibility(View.VISIBLE);
        root.bringToFront();

        if (conversation.getHistory().isEmpty() && AiConfig.isAutoDiagnose(activity)) {
            conversation.primeWithContext();
            addRow(activity.getString(R.string.ai_you),
                    activity.getString(R.string.ai_context_loaded), RowKind.TOOL);
        }
        if (messageList.getChildCount() == 0) {
            addRow(activity.getString(R.string.ai_assistant_title),
                    activity.getString(R.string.ai_greeting), RowKind.ASSISTANT);
        }
        updateQuickPrompts();
        scrollToBottom();
    }

    public void hide() {
        if (root == null) return;
        showing = false;
        root.setVisibility(View.GONE);
        hideKeyboard();
    }

    private void startNewChat() {
        conversation.reset();
        messageList.removeAllViews();
        pendingChips.clear();
        addRow(activity.getString(R.string.ai_assistant_title),
                activity.getString(R.string.ai_greeting), RowKind.ASSISTANT);
        if (AiConfig.isAutoDiagnose(activity)) {
            conversation.primeWithContext();
            addRow(activity.getString(R.string.ai_you),
                    activity.getString(R.string.ai_context_loaded), RowKind.TOOL);
        }
        updateQuickPrompts();
        statusView.setText(R.string.ai_ready);
    }

    private void openSettings() {
        AiSettingsDialog.show(activity, () -> {
            // The model or command access may have changed; rebuild the conversation from scratch.
            detach();
            attach();
            if (showing) show();
        });
    }

    private void exportBundle() {
        if (exporting) return;
        exporting = true;
        statusView.setText(R.string.ai_exporting);

        AiBundleExporter.exportAsync(activity, conversation.getSession().getContainer(),
                conversation.getSession().getImageFs(), conversation.getHistory(), (zip, error) -> {
            exporting = false;
            activity.runOnUiThread(() -> {
                if (root == null) return;
                statusView.setText(R.string.ai_ready);
                if (error != null) {
                    addRow(activity.getString(R.string.ai_assistant_title), error, RowKind.ERROR);
                    scrollToBottom();
                }
                else {
                    AiBundleExporter.share(activity, zip);
                }
            });
        });
    }

    private void detach() {
        conversation.cancel();
        if (keyboardListener != null) {
            host.getViewTreeObserver().removeOnGlobalLayoutListener(keyboardListener);
            keyboardListener = null;
        }
        if (root != null && root.getParent() != null) {
            ((ViewGroup) root.getParent()).removeView(root);
        }
        root = null;
        pendingChips.clear();
    }

    private void submit() {
        if (conversation.isBusy()) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (!AiConfig.isConfigured(activity)) {
            addRow(activity.getString(R.string.ai_assistant_title),
                    activity.getString(R.string.ai_not_configured), RowKind.ERROR);
            openSettings();
            return;
        }

        input.setText("");
        addRow(activity.getString(R.string.ai_you), text, RowKind.USER);
        updateQuickPrompts();
        conversation.send(text);
    }

    private void updateQuickPrompts() {
        boolean empty = conversation.getHistory().size() <= 1;
        quickPrompts.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void scrollToBottom() {
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    // ---- AiConversation.Listener ----

    @Override
    public void onAssistantMessage(String text) {
        addRow(activity.getString(R.string.ai_assistant_title), text, RowKind.ASSISTANT);
        updateQuickPrompts();
        scrollToBottom();
    }

    @Override
    public void onToolStarted(String label) {
        View row = addRow(null, label + "…", RowKind.TOOL);
        TextView chip = row.findViewById(R.id.TVAiBubble);
        if (chip != null) pendingChips.push(chip);
        scrollToBottom();
    }

    @Override
    public void onToolFinished(String label, boolean ok) {
        // Tools run one at a time, so the chip that just started is the one on top of the stack.
        TextView chip = pendingChips.poll();
        if (chip == null) return;
        String suffix = activity.getString(ok ? R.string.ai_tool_ok : R.string.ai_tool_failed);
        chip.setText(label + " · " + suffix);
        chip.setTextColor(Color.parseColor(ok ? "#8FD6A0" : "#E8908C"));
    }

    @Override
    public void onError(String message) {
        addRow(activity.getString(R.string.ai_assistant_title), message, RowKind.ERROR);
        statusView.setText(R.string.ai_ready);
        scrollToBottom();
    }

    @Override
    public void onBusyChanged(boolean busy) {
        statusView.setText(busy ? R.string.ai_thinking : R.string.ai_ready);
        sendButton.setEnabled(!busy);
        sendButton.setAlpha(busy ? 0.4f : 1f);
        if (busy) scrollToBottom();
    }

    // ---- transcript rendering ----

    private enum RowKind { USER, ASSISTANT, TOOL, ERROR }

    private View addRow(String speaker, CharSequence text, RowKind kind) {
        View row = LayoutInflater.from(activity).inflate(R.layout.ai_message_row, messageList, false);
        TextView bubble = row.findViewById(R.id.TVAiBubble);

        switch (kind) {
            case USER:
                bubble.setBackgroundResource(R.drawable.ai_bubble_user);
                bubble.setText(text);
                ((LinearLayout.LayoutParams) bubble.getLayoutParams()).gravity = Gravity.END;
                break;
            case ASSISTANT:
                bubble.setBackgroundResource(R.drawable.ai_bubble_assistant);
                bubble.setText(AiTextFormatter.format(text.toString()));
                ((LinearLayout.LayoutParams) bubble.getLayoutParams()).gravity = Gravity.START;
                break;
            case TOOL:
                bubble.setBackgroundResource(R.drawable.ai_tool_chip_bg);
                bubble.setText(text);
                bubble.setTextSize(11f);
                bubble.setTextColor(Color.parseColor("#9AB0C4"));
                ((LinearLayout.LayoutParams) bubble.getLayoutParams()).gravity = Gravity.START;
                break;
            case ERROR:
                bubble.setBackgroundResource(R.drawable.ai_bubble_assistant);
                bubble.setText(text);
                bubble.setTextColor(Color.parseColor("#E8908C"));
                ((LinearLayout.LayoutParams) bubble.getLayoutParams()).gravity = Gravity.START;
                break;
        }

        // Cap the bubble so a long log dump does not push the composer off screen.
        int maxWidth = Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.40f)
                - dp(40);
        bubble.setMaxWidth(Math.max(maxWidth, dp(120)));

        messageList.addView(row);
        return row;
    }

    // ---- keyboard ----

    /**
     * The activity routes every key event straight to Wine, so the EditText can never receive
     * hardware input on its own. Showing the IME explicitly is what makes typing work; the
     * activity's key dispatch is bypassed while the panel is open.
     */
    private void showKeyboard() {
        input.requestFocus();
        InputMethodManager imm =
                (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    /** True when a key event belongs to the panel and must not reach Wine. */
    public boolean shouldCaptureKeys() {
        return showing && input != null && (input.hasFocus() || input.isFocused());
    }

    public void onShownKeyboardRequested() {
        showKeyboard();
    }

    public boolean onBackPressed() {
        if (!showing) return false;
        hide();
        return true;
    }

    public void destroy() {
        detach();
        showing = false;
    }

    /** Focuses the input and raises the keyboard; used when the panel opens from a tap. */
    public void focusInput() {
        if (root == null) return;
        root.postDelayed(this::showKeyboard, 150L);
    }
}

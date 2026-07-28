package com.druvane.glasses.relaybridge;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ChatRelayAccessibilityService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> baselineText = new HashSet<>();
    private TextToSpeech tts;
    private boolean waitingForAnswer;
    private String activePrompt;
    private String candidateAnswer;
    private long candidateChangedAt;
    private boolean injectionInProgress;

    @Override
    public void onServiceConnected() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.getDefault());
                tts.setSpeechRate(1.0f);
            }
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null || !"com.openai.chatgpt".contentEquals(event.getPackageName())) {
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }

        String pending = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getString(MainActivity.KEY_PENDING_PROMPT, null);

        if (pending != null && !pending.trim().isEmpty() && !injectionInProgress) {
            injectionInProgress = true;
            boolean inserted = injectPrompt(root, pending);
            if (inserted) {
                activePrompt = pending;
                baselineText.clear();
                baselineText.addAll(collectTexts(root));
                waitingForAnswer = true;
                candidateAnswer = null;
                getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                        .edit().remove(MainActivity.KEY_PENDING_PROMPT).apply();
            }
            injectionInProgress = false;
        } else if (waitingForAnswer) {
            updateAnswerCandidate(root);
        }
    }

    private boolean injectPrompt(AccessibilityNodeInfo root, String prompt) {
        AccessibilityNodeInfo editor = findEditable(root);
        if (editor == null) {
            return false;
        }

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt);
        if (!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            return false;
        }

        handler.postDelayed(() -> {
            AccessibilityNodeInfo freshRoot = getRootInActiveWindow();
            if (freshRoot == null) return;
            AccessibilityNodeInfo send = findSendButton(freshRoot);
            if (send != null) {
                send.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }, 450);
        return true;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() || EditText.class.getName().contentEquals(node.getClassName())) {
            return node;
        }
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo found = findEditable(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String label = normalized(node.getText()) + " " + normalized(node.getContentDescription()) + " " + normalized(node.getViewIdResourceName());
        boolean looksLikeSend = label.contains("send") || label.contains("submit") || label.contains("arrow_upward");
        if (looksLikeSend && node.isClickable()) {
            return node;
        }
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo found = findSendButton(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private void updateAnswerCandidate(AccessibilityNodeInfo root) {
        List<String> now = collectTexts(root);
        String best = null;
        int bestScore = 0;
        for (String value : now) {
            String text = value.trim();
            if (text.length() < 20 || baselineText.contains(text) || text.equals(activePrompt)) continue;
            if (isInterfaceText(text)) continue;
            int score = text.length();
            if (score > bestScore) {
                best = text;
                bestScore = score;
            }
        }

        if (best != null && !best.equals(candidateAnswer)) {
            candidateAnswer = best;
            candidateChangedAt = System.currentTimeMillis();
            handler.removeCallbacks(speakWhenStable);
            handler.postDelayed(speakWhenStable, 2400);
        }
    }

    private final Runnable speakWhenStable = new Runnable() {
        @Override
        public void run() {
            long quietFor = System.currentTimeMillis() - candidateChangedAt;
            if (quietFor < 2200) {
                handler.postDelayed(this, 2200 - quietFor);
                return;
            }
            if (candidateAnswer != null && tts != null) {
                tts.speak(candidateAnswer, TextToSpeech.QUEUE_FLUSH, null, "chatgpt-response");
                waitingForAnswer = false;
                baselineText.clear();
            }
        }
    };

    private List<String> collectTexts(AccessibilityNodeInfo root) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        collect(root, values);
        return new ArrayList<>(values);
    }

    private void collect(AccessibilityNodeInfo node, Set<String> values) {
        if (node == null) return;
        if (node.getText() != null) {
            String value = node.getText().toString().trim();
            if (!value.isEmpty()) values.add(value);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collect(node.getChild(i), values);
        }
    }

    private boolean isInterfaceText(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.equals("chatgpt")
                || lower.equals("send")
                || lower.equals("stop")
                || lower.equals("share")
                || lower.equals("copy")
                || lower.contains("message chatgpt")
                || lower.contains("chatgpt can make mistakes");
    }

    private String normalized(CharSequence value) {
        return value == null ? "" : value.toString().toLowerCase(Locale.ROOT);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}

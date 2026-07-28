package com.druvane.glasses.relaybridge;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    static final String PREFS = "relay";
    static final String KEY_PENDING_PROMPT = "pending_prompt";
    private static final int REQUEST_SPEECH = 2001;
    private EditText promptInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private View buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(text("Glasses Chat Relay", 24));
        root.addView(text(
                "No API key. This version operates the installed ChatGPT app through an accessibility service, " +
                        "then reads the visible answer with Android text-to-speech.", 15));

        root.addView(button("Enable ChatGPT response relay", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));

        promptInput = new EditText(this);
        promptInput.setHint("Ask ChatGPT");
        promptInput.setMinLines(3);
        root.addView(promptInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(button("Speak a prompt", v -> startSpeechRecognition()));
        root.addView(button("Send and speak response", v -> queuePrompt()));
        root.addView(button("Cancel pending relay", v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_PENDING_PROMPT).apply();
            toast("Pending relay cleared");
        }));

        TextView spotifyHeader = text("Spotify controls", 19);
        spotifyHeader.setPadding(0, dp(20), 0, dp(4));
        root.addView(spotifyHeader);
        root.addView(button("Open Spotify", v -> launchPackage("com.spotify.music")));
        root.addView(button("Play / pause", v -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)));
        root.addView(button("Next track", v -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)));
        root.addView(button("Previous track", v -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)));

        TextView warning = text(
                "The accessibility service is restricted to com.openai.chatgpt, but it can read text shown in that app. " +
                        "ChatGPT UI updates may break prompt insertion or response detection.", 14);
        warning.setPadding(0, dp(20), 0, dp(20));
        root.addView(warning);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void queuePrompt() {
        String prompt = promptInput.getText().toString().trim();
        if (prompt.isEmpty()) {
            toast("Enter a prompt first");
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_PENDING_PROMPT, prompt)
                .apply();
        launchPackage("com.openai.chatgpt");
        toast("Opening ChatGPT. The relay will insert the prompt when the chat screen is ready.");
    }

    private void startSpeechRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "What do you want to ask ChatGPT?");
        try {
            startActivityForResult(intent, REQUEST_SPEECH);
        } catch (ActivityNotFoundException e) {
            toast("No speech recognition service is installed");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SPEECH && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                promptInput.setText(results.get(0));
                queuePrompt();
            }
        }
    }

    private void launchPackage(String packageName) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            toast("Required app is not installed: " + packageName);
            return;
        }
        startActivity(intent);
    }

    private void mediaKey(int keyCode) {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        try {
            audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        } catch (SecurityException e) {
            toast("Android blocked the media command");
        }
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return button;
    }

    private TextView text(String value, float size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        return text;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}

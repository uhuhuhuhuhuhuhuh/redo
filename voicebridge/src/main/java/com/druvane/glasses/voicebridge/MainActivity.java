package com.druvane.glasses.voicebridge;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
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
    private static final int REQUEST_SPEECH = 1001;
    private EditText promptInput;
    private TextView audioStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshAudioStatus();
    }

    private View buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text("Glasses Voice Bridge", 24);
        root.addView(title);
        root.addView(text("No OpenAI or Spotify API key. The official apps perform account access and playback.", 15));

        audioStatus = text("", 14);
        audioStatus.setPadding(0, dp(14), 0, dp(10));
        root.addView(audioStatus);

        root.addView(button("Refresh audio route", v -> refreshAudioStatus()));
        root.addView(button("Open ChatGPT Voice", v -> openChatGptVoice()));
        root.addView(button("Speak a prompt", v -> startSpeechRecognition()));

        promptInput = new EditText(this);
        promptInput.setHint("Type a prompt for a normal ChatGPT chat");
        promptInput.setMinLines(3);
        root.addView(promptInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(button("Send typed prompt to ChatGPT", v -> {
            String prompt = promptInput.getText().toString().trim();
            if (prompt.isEmpty()) {
                toast("Enter a prompt first");
            } else {
                openChatGptPrompt(prompt);
            }
        }));

        TextView spotifyHeader = text("Spotify controls", 19);
        spotifyHeader.setPadding(0, dp(20), 0, dp(4));
        root.addView(spotifyHeader);
        root.addView(button("Open Spotify", v -> launchPackage("com.spotify.music")));
        root.addView(button("Play / pause", v -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)));
        root.addView(button("Next track", v -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)));
        root.addView(button("Previous track", v -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)));
        root.addView(button("Bluetooth settings", v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))));

        TextView note = text(
                "ChatGPT replies through the glasses only when Android is routing media audio to them. " +
                "The bridge does not read ChatGPT's response; ChatGPT Voice speaks it directly.", 14);
        note.setPadding(0, dp(20), 0, dp(20));
        root.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
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
                openChatGptPrompt(results.get(0));
            }
        }
    }

    private void openChatGptVoice() {
        Uri uri = Uri.parse("https://chatgpt.com/?mode=voice");
        Intent intent = new Intent(Intent.ACTION_VIEW, uri).setPackage("com.openai.chatgpt");
        startWithFallback(intent, "com.openai.chatgpt");
    }

    private void openChatGptPrompt(String prompt) {
        Uri uri = Uri.parse("https://chatgpt.com/?q=" + Uri.encode(prompt) + "&skip_instant_query=0");
        Intent intent = new Intent(Intent.ACTION_VIEW, uri).setPackage("com.openai.chatgpt");
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException first) {
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, prompt)
                    .setPackage("com.openai.chatgpt");
            startWithFallback(share, "com.openai.chatgpt");
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

    private void startWithFallback(Intent primary, String packageName) {
        try {
            startActivity(primary);
        } catch (ActivityNotFoundException e) {
            launchPackage(packageName);
        }
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

    private void refreshAudioStatus() {
        AudioManager manager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        StringBuilder status = new StringBuilder("Audio outputs:\n");
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (isBluetooth(device.getType())) {
                status.append("• ").append(device.getProductName()).append(" (Bluetooth)\n");
            }
        }
        if (status.toString().equals("Audio outputs:\n")) {
            status.append("No Bluetooth media output detected");
        }
        audioStatus.setText(status.toString());
    }

    private boolean isBluetooth(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || (android.os.Build.VERSION.SDK_INT >= 31 && (
                type == AudioDeviceInfo.TYPE_BLE_HEADSET
                        || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                        || type == AudioDeviceInfo.TYPE_BLE_BROADCAST));
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

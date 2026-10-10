package com.ethan.fruitslice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.SoundPool;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** Short original sound effects, synthesized offline and cached in app-private storage. */
public final class GameAudio {
    private static final int RATE = 24000;
    private static final int SLICE_A = 0, SLICE_B = 1, SLICE_C = 2;
    private static final int BOMB = 3, MODE = 4, COMBO = 5;
    private static final double[] LENGTHS = {.20, .22, .24, .74, .67, .50};
    private final Object lock = new Object();
    private final int[] soundIds = new int[6];
    private final int[] streamIds = new int[6];
    private final float[] streamVolumes = new float[6];
    private final Set<Integer> loaded = new HashSet<>();
    private final AudioManager audioManager;
    private final SoundPool pool;
    private final AudioFocusRequest focusRequest;
    private boolean released, focused;
    private float duck = 1f;
    private int sliceNumber;
    private int nextStream;

    private final AudioManager.OnAudioFocusChangeListener focusListener = this::focusChanged;

    private void focusChanged(int change) {
        synchronized (lock) {
            if (released) return;
            if (change == AudioManager.AUDIOFOCUS_GAIN) {
                focused = true;
                duck = 1f;
                updateVolumes();
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                duck = .20f;
                updateVolumes();
            } else {
                focused = false;
                stopStreams();
            }
        }
    }

    public GameAudio(Context ctx) {
        Context context = ctx.getApplicationContext();
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        pool = new SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attributes).build();
        pool.setOnLoadCompleteListener((soundPool, id, status) -> {
            synchronized (lock) {
                if (!released && status == 0) loaded.add(id);
            }
        });
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener).build();
        // Keep game entry immediate: synthesis and disk access never block a rendered frame.
        Thread loader = new Thread(() -> {
            for (int kind = 0; kind < soundIds.length; kind++) {
                synchronized (lock) { if (released) return; }
                try {
                    File file = new File(context.getCacheDir(), "orchard_sfx_v3_" + kind + ".wav");
                    long expected = 44L + 2L * (int) (LENGTHS[kind] * RATE);
                    if (!file.isFile() || file.length() != expected) writeSound(file, kind);
                    synchronized (lock) {
                        if (released) return;
                        soundIds[kind] = pool.load(file.getAbsolutePath(), 1);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // Audio is decorative; unavailable storage or audio hardware must not stop play.
                }
            }
        }, "Orchard sound preparation");
        loader.setDaemon(true);
        loader.start();
    }

    public void slice(int combo) {
        int sample = (sliceNumber++ % 3 + 3) % 3;
        float pitch = Math.min(1.32f, .98f + Math.max(0, combo - 1) * .035f);
        play(sample, .54f, pitch, 1);
    }

    public void bomb() { play(BOMB, .68f, 1f, 3); }
    public void mode() { play(MODE, .65f, 1f, 3); }
    public void combo() { play(COMBO, .57f, 1f, 2); }

    public void pause() {
        synchronized (lock) {
            if (released) return;
            stopStreams();
            abandonFocus();
        }
    }

    public void release() {
        synchronized (lock) {
            if (released) return;
            stopStreams();
            abandonFocus();
            released = true;
            pool.release();
            loaded.clear();
        }
    }

    private void play(int kind, float volume, float pitch, int priority) {
        synchronized (lock) {
            if (released || soundIds[kind] == 0 || !loaded.contains(soundIds[kind])) return;
            if (!focused && !requestFocus()) return;
            int slot = nextStream;
            if (streamIds[slot] != 0) pool.stop(streamIds[slot]);
            streamIds[slot] = 0;
            int stream = pool.play(soundIds[kind], volume * duck, volume * duck, priority, 0, pitch);
            if (stream != 0) {
                // Fixed bookkeeping matches the pool's six concurrent, non-looping effects.
                streamIds[slot] = stream;
                streamVolumes[slot] = volume;
                nextStream = (nextStream + 1) % streamIds.length;
            }
        }
    }

    private boolean requestFocus() {
        focused = audioManager == null || audioManager.requestAudioFocus(focusRequest)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return focused;
    }

    private void abandonFocus() {
        if (audioManager != null) audioManager.abandonAudioFocusRequest(focusRequest);
        focused = false;
        duck = 1f;
    }

    private void stopStreams() {
        // Effects are never resumed after interruption: a delayed slash feels disconnected.
        for (int i = 0; i < streamIds.length; i++) {
            if (streamIds[i] != 0) pool.stop(streamIds[i]);
            streamIds[i] = 0;
        }
        nextStream = 0;
    }

    private void updateVolumes() {
        for (int i = 0; i < streamIds.length; i++) {
            if (streamIds[i] != 0) {
                float volume = streamVolumes[i] * duck;
                pool.setVolume(streamIds[i], volume, volume);
            }
        }
    }

    private static void writeSound(File file, int kind) throws IOException {
        int count = (int) (LENGTHS[kind] * RATE);
        float[] samples = new float[count];
        Random random = new Random(5891L + kind * 312L);
        double low = 0, sub = 0, phase = 0;
        for (int i = 0; i < count; i++) {
            double t = (double) i / RATE;
            double noise = random.nextDouble() * 2 - 1;
            low = low * .84 + noise * .16;
            sub = sub * .983 + noise * .017;
            double value;
            if (kind <= SLICE_C) {
                double attack = Math.min(1, t / .008);
                double air = noise * .38 * Math.exp(-22 * t) * attack;
                double wet = low * 1.0 * Math.exp(-14 * t) * attack;
                double freq = (kind == 0 ? 910 : kind == 1 ? 1120 : 760) * Math.exp(-11 * t);
                phase += Math.PI * 2 * freq / RATE;
                double swoop = Math.sin(phase) * .16 * Math.exp(-22 * t) * attack;
                value = air + wet + swoop;
                value += droplet(t - .026, 560 + kind * 70, .24);
                value += droplet(t - .061, 810 + kind * 45, .13);
                value += droplet(t - .094, 1070 + kind * 60, .075);
            } else if (kind == BOMB) {
                double attack = Math.min(1, t / .003);
                double body = low * 1.4 * Math.exp(-6.3 * t)
                        + sub * 3.6 * Math.exp(-4.5 * t);
                double crack = noise * .82 * Math.exp(-65 * t);
                double rumble = Math.sin(2 * Math.PI * (47 * t - 13 * t * t))
                        * .42 * Math.exp(-7 * t);
                double fizz = t > .10 ? noise * .09 * Math.exp(-8 * (t - .10)) : 0;
                value = (body + crack + rumble + fizz) * attack;
            } else if (kind == MODE) {
                double[] notes = {783.99, 987.77, 1174.66, 1567.98, 1975.53};
                value = 0;
                for (int note = 0; note < notes.length; note++) {
                    value += bell(t - note * .073, notes[note], .21);
                }
                value += Math.sin(t * Math.PI * 2 * (2500 + t * 2500))
                        * .027 * Math.sin(Math.PI * Math.min(1, t / .60)) * Math.exp(-4 * t);
            } else {
                double[] notes = {659.25, 830.61, 987.77, 1318.51};
                value = 0;
                for (int note = 0; note < notes.length; note++) {
                    value += bell(t - note * .062, notes[note], .24);
                }
            }
            double fadeOut = Math.min(1, (count - i - 1) / (RATE * .025));
            samples[i] = (float) (Math.tanh(value * 1.32) * .88 * fadeOut);
        }
        File temporary = new File(file.getParentFile(), file.getName() + ".pending");
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(temporary))) {
            ascii(out, "RIFF"); little32(out, 36 + count * 2); ascii(out, "WAVE");
            ascii(out, "fmt "); little32(out, 16); little16(out, 1); little16(out, 1);
            little32(out, RATE); little32(out, RATE * 2); little16(out, 2); little16(out, 16);
            ascii(out, "data"); little32(out, count * 2);
            for (float sample : samples) little16(out, Math.round(sample * 32767));
        }
        if (file.exists() && !file.delete()) throw new IOException("Cannot replace audio cache");
        if (!temporary.renameTo(file)) throw new IOException("Cannot finish audio cache");
    }

    private static double droplet(double t, double frequency, double volume) {
        if (t < 0 || t > .1) return 0;
        double envelope = Math.min(1, t / .0015) * Math.exp(-63 * t);
        return Math.sin(2 * Math.PI * (frequency * t - 950 * t * t)) * envelope * volume;
    }

    private static double bell(double t, double frequency, double volume) {
        if (t < 0 || t > .6) return 0;
        double envelope = Math.min(1, t / .003) * Math.exp(-9.5 * t);
        return (Math.sin(t * Math.PI * 2 * frequency)
                + .30 * Math.sin(t * Math.PI * 2 * frequency * 2.006)
                + .12 * Math.sin(t * Math.PI * 2 * frequency * 3.01)) * envelope * volume;
    }

    private static void ascii(OutputStream out, String value) throws IOException {
        for (int i = 0; i < value.length(); i++) out.write(value.charAt(i));
    }

    private static void little16(OutputStream out, int value) throws IOException {
        out.write(value & 0xff); out.write((value >>> 8) & 0xff);
    }

    private static void little32(OutputStream out, int value) throws IOException {
        little16(out, value); little16(out, value >>> 16);
    }
}

package com.usbaudio.bridge;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

public class AudioService extends Service {
    private static final String TAG = "AudioService";
    
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Thread audioThread;
    private volatile boolean isRecording = false;
    
    // CORRECT VALUES FOR YOUR USB DEVICE
    private static final int SAMPLE_RATE = 96000; // 96kHz (your USB device)
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO; // MONO input
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_STEREO; // STEREO output
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int RECORD_SOURCE = MediaRecorder.AudioSource.MIC;
    private static final int STREAM_TYPE = AudioManager.STREAM_MUSIC;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service starting");
        
        Notification notification = new Notification.Builder(this)
                .setContentTitle("USB Audio Bridge")
                .setContentText("Starting...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build();
        
        startForeground(1, notification);
        
        if (!isRecording) {
            startAudioRouting();
        }
        
        return START_STICKY;
    }

    private void startAudioRouting() {
        isRecording = true;
        
        audioThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            
            try {
                // Get buffer sizes
                int bufferSizeIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT);
                int bufferSizeOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT);
                
                Log.d(TAG, "Config: 96kHz, MONO input, STEREO output");
                Log.d(TAG, "Buffer sizes - In: " + bufferSizeIn + ", Out: " + bufferSizeOut);
                
                if (bufferSizeIn <= 0 || bufferSizeOut <= 0) {
                    Log.e(TAG, "Invalid buffer sizes");
                    updateNotification("Error: Invalid buffer");
                    return;
                }
                
                // Buffers: mono input, stereo output (2x size)
                short[] monoBuffer = new short[bufferSizeIn / 2]; // 16-bit = 2 bytes per sample
                short[] stereoBuffer = new short[bufferSizeIn]; // Double for stereo
                
                // Create AudioTrack FIRST
                audioTrack = new AudioTrack(
                    STREAM_TYPE,
                    SAMPLE_RATE,
                    CHANNEL_OUT, // STEREO output
                    AUDIO_FORMAT,
                    bufferSizeOut,
                    AudioTrack.MODE_STREAM
                );
                
                if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioTrack init failed");
                    updateNotification("Error: AudioTrack");
                    return;
                }
                
                // Create AudioRecord SECOND
                audioRecord = new AudioRecord(
                    RECORD_SOURCE,
                    SAMPLE_RATE,
                    CHANNEL_IN, // MONO input
                    AUDIO_FORMAT,
                    bufferSizeIn
                );
                
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord init failed");
                    cleanup();
                    updateNotification("Error: AudioRecord");
                    return;
                }
                
                // Start recording and playback
                audioRecord.startRecording();
                audioTrack.play();
                
                Log.d(TAG, "Audio routing started @ 96kHz, MONO→STEREO");
                updateNotification("Running @ 96kHz");
                
                // Main loop with MONO to STEREO conversion
                int loopCount = 0;
                while (isRecording) {
                    loopCount++;
                    
                    // Read MONO samples
                    int samplesRead = audioRecord.read(monoBuffer, 0, monoBuffer.length);
                    
                    if (samplesRead > 0) {
                        // Convert MONO to STEREO (duplicate each sample)
                        for (int i = 0; i < samplesRead; i++) {
                            stereoBuffer[i * 2] = monoBuffer[i];      // Left
                            stereoBuffer[i * 2 + 1] = monoBuffer[i];  // Right
                        }
                        
                        // Write STEREO to speakers
                        audioTrack.write(stereoBuffer, 0, samplesRead * 2);
                        
                        // Log every 1000 loops
                        if (loopCount % 1000 == 0) {
                            Log.d(TAG, "Loop " + loopCount + ": " + samplesRead + " mono samples → " + (samplesRead*2) + " stereo");
                        }
                    } else if (samplesRead < 0) {
                        Log.e(TAG, "Read error: " + samplesRead);
                        Thread.sleep(100);
                    }
                }
                
                Log.d(TAG, "Audio loop exited normally");
                
            } catch (Exception e) {
                Log.e(TAG, "Audio error", e);
                updateNotification("Error: " + e.getMessage());
            } finally {
                cleanup();
            }
        });
        
        audioThread.start();
    }

    private void cleanup() {
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping AudioTrack", e);
            }
            audioTrack = null;
        }
        
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping AudioRecord", e);
            }
            audioRecord = null;
        }
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            Notification notification = new Notification.Builder(this)
                    .setContentTitle("USB Audio Bridge")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .build();
            manager.notify(1, notification);
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service stopping");
        isRecording = false;
        
        if (audioThread != null) {
            try {
                audioThread.join(2000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Error waiting for thread", e);
            }
        }
        
        cleanup();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

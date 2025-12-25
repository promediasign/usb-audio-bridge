package com.usbaudio.bridge;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;

public class AudioService extends Service {
    private static final String TAG = "AudioService";
    
    private volatile AudioRecord audioRecord;
    private volatile AudioTrack audioTrack;
    private Thread audioThread;
    private volatile boolean isRunning = false;
    private PowerManager.WakeLock wakeLock;
    
    private static final int SAMPLE_RATE = 96000;
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int RECORD_SOURCE = MediaRecorder.AudioSource.MIC;
    private static final int STREAM_TYPE = AudioManager.STREAM_MUSIC;
    private static final int RESTART_INTERVAL_MS = 15000; // Restart every 15 seconds

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioService::WakeLock");
        wakeLock.acquire();
        Log.d(TAG, "WakeLock acquired");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service starting");
        
        Notification notification = new Notification.Builder(this)
                .setContentTitle("USB Audio Bridge")
                .setContentText("Running @ 96kHz")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build();
        
        startForeground(1, notification);
        
        if (!isRunning) {
            startAudioLoop();
        }
        
        return START_STICKY;
    }

    private void startAudioLoop() {
        isRunning = true;
        
        audioThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            
            int cycleCount = 0;
            
            while (isRunning) {
                cycleCount++;
                Log.d(TAG, "Audio cycle " + cycleCount + " starting...");
                
                try {
                    runAudioCycle();
                } catch (Exception e) {
                    Log.e(TAG, "Audio cycle error", e);
                }
                
                // Wait a bit before next cycle
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
            
            Log.d(TAG, "Audio loop ended");
        });
        
        audioThread.start();
    }

    private void runAudioCycle() {
        AudioRecord record = null;
        AudioTrack track = null;
        
        try {
            // Get buffer sizes
            int bufferSizeIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT);
            int bufferSizeOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT);
            
            if (bufferSizeIn <= 0 || bufferSizeOut <= 0) {
                Log.e(TAG, "Invalid buffer sizes");
                return;
            }
            
            // Create buffers
            short[] monoBuffer = new short[865];
            short[] stereoBuffer = new short[1730];
            
            // Create AudioTrack
            track = new AudioTrack(
                STREAM_TYPE, SAMPLE_RATE, CHANNEL_OUT,
                AUDIO_FORMAT, bufferSizeOut, AudioTrack.MODE_STREAM
            );
            
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack init failed");
                return;
            }
            
            // Create AudioRecord
            record = new AudioRecord(
                RECORD_SOURCE, SAMPLE_RATE, CHANNEL_IN,
                AUDIO_FORMAT, bufferSizeIn
            );
            
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed");
                if (track != null) {
                    track.release();
                }
                return;
            }
            
            // Start
            record.startRecording();
            track.play();
            
            Log.d(TAG, "Audio active");
            
            // Run for 15 seconds then recreate
            long startTime = System.currentTimeMillis();
            int loopCount = 0;
            
            while (isRunning && (System.currentTimeMillis() - startTime) < RESTART_INTERVAL_MS) {
                loopCount++;
                
                // Read mono
                int samplesRead = record.read(monoBuffer, 0, monoBuffer.length);
                
                if (samplesRead > 0) {
                    // Convert to stereo
                    for (int i = 0; i < samplesRead; i++) {
                        stereoBuffer[i * 2] = monoBuffer[i];
                        stereoBuffer[i * 2 + 1] = monoBuffer[i];
                    }
                    
                    // Write stereo
                    track.write(stereoBuffer, 0, samplesRead * 2);
                    
                    if (loopCount % 1000 == 0) {
                        Log.d(TAG, "Loop " + loopCount);
                    }
                } else if (samplesRead < 0) {
                    Log.e(TAG, "Read error: " + samplesRead);
                    break;
                }
            }
            
            Log.d(TAG, "Cycle complete. Loops: " + loopCount + ", recreating...");
            
        } catch (Exception e) {
            Log.e(TAG, "Cycle exception", e);
        } finally {
            // Clean up this cycle's objects
            if (track != null) {
                try {
                    track.stop();
                    track.release();
                } catch (Exception e) {
                }
            }
            
            if (record != null) {
                try {
                    record.stop();
                    record.release();
                } catch (Exception e) {
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service stopping");
        isRunning = false;
        
        if (audioThread != null) {
            audioThread.interrupt();
            try {
                audioThread.join(2000);
            } catch (InterruptedException e) {
            }
        }
        
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        
        // Auto-restart
        try {
            Intent restartIntent = new Intent(getApplicationContext(), AudioService.class);
            startService(restartIntent);
        } catch (Exception e) {
        }
        
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

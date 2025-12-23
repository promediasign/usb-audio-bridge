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
    
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Thread audioThread;
    private Thread watchdogThread;
    private volatile boolean isRecording = false;
    private volatile long lastLoopTime = 0;
    private PowerManager.WakeLock wakeLock;
    
    // CORRECT VALUES FOR YOUR USB DEVICE
    private static final int SAMPLE_RATE = 96000;
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int RECORD_SOURCE = MediaRecorder.AudioSource.MIC;
    private static final int STREAM_TYPE = AudioManager.STREAM_MUSIC;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        
        // Acquire WakeLock to prevent sleep
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
                .setContentText("Starting...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build();
        
        startForeground(1, notification);
        
        if (!isRecording) {
            startAudioRouting();
            startWatchdog();
        }
        
        return START_STICKY;
    }

    private void startWatchdog() {
        watchdogThread = new Thread(() -> {
            Log.d(TAG, "Watchdog started");
            while (isRecording) {
                try {
                    Thread.sleep(15000); // Check every 15 seconds
                    
                    long timeSinceLastLoop = System.currentTimeMillis() - lastLoopTime;
                    
                    if (lastLoopTime > 0 && timeSinceLastLoop > 20000) {
                        Log.e(TAG, "WATCHDOG: Audio thread hung! Last loop was " + timeSinceLastLoop + "ms ago");
                        Log.e(TAG, "WATCHDOG: Restarting audio...");
                        
                        // Force restart
                        restartAudio();
                    }
                    
                } catch (InterruptedException e) {
                    break;
                }
            }
            Log.d(TAG, "Watchdog stopped");
        });
        watchdogThread.start();
    }

    private void restartAudio() {
        Log.d(TAG, "Restarting audio routing...");
        isRecording = false;
        
        // Wait for thread to exit
        if (audioThread != null) {
            try {
                audioThread.interrupt();
                audioThread.join(3000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Error waiting for audio thread", e);
            }
        }
        
        cleanup();
        
        // Restart
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
        }
        
        startAudioRouting();
    }

    private void startAudioRouting() {
        isRecording = true;
        lastLoopTime = System.currentTimeMillis();
        
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
                
                // Buffers
                short[] monoBuffer = new short[bufferSizeIn / 2];
                short[] stereoBuffer = new short[bufferSizeIn];
                
                // Create AudioTrack
                audioTrack = new AudioTrack(
                    STREAM_TYPE,
                    SAMPLE_RATE,
                    CHANNEL_OUT,
                    AUDIO_FORMAT,
                    bufferSizeOut,
                    AudioTrack.MODE_STREAM
                );
                
                if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioTrack init failed");
                    updateNotification("Error: AudioTrack");
                    return;
                }
                
                // Create AudioRecord
                audioRecord = new AudioRecord(
                    RECORD_SOURCE,
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    AUDIO_FORMAT,
                    bufferSizeIn
                );
                
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord init failed");
                    cleanup();
                    updateNotification("Error: AudioRecord");
                    return;
                }
                
                // Start
                audioRecord.startRecording();
                audioTrack.play();
                
                Log.d(TAG, "Audio routing started @ 96kHz, MONO→STEREO");
                updateNotification("Running @ 96kHz");
                
                // Main loop
                int loopCount = 0;
                int consecutiveErrors = 0;
                
                while (isRecording && !Thread.interrupted()) {
                    try {
                        loopCount++;
                        lastLoopTime = System.currentTimeMillis(); // Update watchdog
                        
                        // Read with state check
                        if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                            Log.e(TAG, "AudioRecord not recording! State: " + audioRecord.getRecordingState());
                            audioRecord.startRecording();
                            Thread.sleep(100);
                            continue;
                        }
                        
                        int samplesRead = audioRecord.read(monoBuffer, 0, monoBuffer.length);
                        
                        if (samplesRead > 0) {
                            consecutiveErrors = 0;
                            
                            // Convert MONO to STEREO
                            for (int i = 0; i < samplesRead; i++) {
                                stereoBuffer[i * 2] = monoBuffer[i];
                                stereoBuffer[i * 2 + 1] = monoBuffer[i];
                            }
                            
                            // Write with state check
                            if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                                Log.e(TAG, "AudioTrack not playing! State: " + audioTrack.getPlayState());
                                audioTrack.play();
                                Thread.sleep(100);
                                continue;
                            }
                            
                            int written = audioTrack.write(stereoBuffer, 0, samplesRead * 2);
                            
                            if (written < 0) {
                                Log.e(TAG, "Write error: " + written);
                                consecutiveErrors++;
                            }
                            
                            // Log every 1000 loops
                            if (loopCount % 1000 == 0) {
                                Log.d(TAG, "Loop " + loopCount + ": " + samplesRead + " mono samples → " + (samplesRead*2) + " stereo");
                            }
                            
                        } else if (samplesRead == 0) {
                            Log.w(TAG, "Read returned 0 samples");
                            consecutiveErrors++;
                            Thread.sleep(50);
                        } else {
                            consecutiveErrors++;
                            Log.e(TAG, "Read error: " + samplesRead + " (consecutive: " + consecutiveErrors + ")");
                            
                            if (consecutiveErrors > 10) {
                                Log.e(TAG, "Restarting due to errors");
                                audioRecord.stop();
                                audioTrack.stop();
                                Thread.sleep(500);
                                audioRecord.startRecording();
                                audioTrack.play();
                                consecutiveErrors = 0;
                            } else {
                                Thread.sleep(100);
                            }
                        }
                        
                    } catch (Exception loopException) {
                        consecutiveErrors++;
                        Log.e(TAG, "Loop exception: " + loopException.getMessage(), loopException);
                        
                        if (consecutiveErrors > 50) {
                            Log.e(TAG, "Too many exceptions, exiting");
                            break;
                        }
                        
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            break;
                        }
                    }
                }
                
                Log.d(TAG, "Audio loop exited. Total loops: " + loopCount);
                
            } catch (Exception e) {
                Log.e(TAG, "FATAL: Audio thread crashed", e);
                updateNotification("Crashed: " + e.getMessage());
            } catch (Throwable t) {
                Log.e(TAG, "FATAL: Unexpected error", t);
                updateNotification("Fatal error");
            } finally {
                cleanup();
                Log.d(TAG, "Audio thread ended");
            }
        });
        
        audioThread.setUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "UNCAUGHT EXCEPTION", throwable);
        });
        
        audioThread.start();
    }

    private void cleanup() {
        if (audioTrack != null) {
            try {
                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.stop();
                }
                audioTrack.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping AudioTrack", e);
            }
            audioTrack = null;
        }
        
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping AudioRecord", e);
            }
            audioRecord = null;
        }
    }

    private void updateNotification(String text) {
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                Notification notification = new Notification.Builder(this)
                        .setContentTitle("USB Audio Bridge")
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .build();
                manager.notify(1, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating notification", e);
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service stopping");
        isRecording = false;
        
        if (watchdogThread != null) {
            watchdogThread.interrupt();
        }
        
        if (audioThread != null) {
            try {
                audioThread.interrupt();
                audioThread.join(2000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Error waiting for thread", e);
            }
        }
        
        cleanup();
        
        // Release WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released");
        }
        
        // Auto-restart
        try {
            Intent restartIntent = new Intent(getApplicationContext(), AudioService.class);
            startService(restartIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error restarting service", e);
        }
        
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

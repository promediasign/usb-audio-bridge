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
    private Thread watchdogThread;
    private volatile boolean isRecording = false;
    private volatile long lastLoopTime = 0;
    private PowerManager.WakeLock wakeLock;
    private final Object audioLock = new Object();
    private volatile boolean isRestarting = false;
    
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
                    Thread.sleep(10000); // Check every 10 seconds
                    
                    if (isRestarting) {
                        continue; // Skip check during restart
                    }
                    
                    long timeSinceLastLoop = System.currentTimeMillis() - lastLoopTime;
                    
                    if (lastLoopTime > 0 && timeSinceLastLoop > 15000) {
                        Log.w(TAG, "WATCHDOG: Audio hung for " + timeSinceLastLoop + "ms, restarting...");
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
        if (isRestarting) {
            Log.d(TAG, "Restart already in progress, skipping");
            return;
        }
        
        isRestarting = true;
        
        new Thread(() -> {
            try {
                Log.d(TAG, "=== RESTART BEGIN ===");
                
                // Signal stop but don't interrupt yet
                isRecording = false;
                
                // Wait a moment for loop to notice
                Thread.sleep(100);
                
                // Now cleanup with lock
                synchronized (audioLock) {
                    cleanup();
                }
                
                // Wait for USB to settle
                Thread.sleep(300);
                
                // Restart
                startAudioRouting();
                
                Log.d(TAG, "=== RESTART COMPLETE ===");
                
            } catch (Exception e) {
                Log.e(TAG, "Restart error", e);
            } finally {
                isRestarting = false;
            }
        }).start();
    }

    private void startAudioRouting() {
        isRecording = true;
        lastLoopTime = System.currentTimeMillis();
        
        audioThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            
            try {
                int bufferSizeIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT);
                int bufferSizeOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT);
                
                Log.d(TAG, "Buffers - In: " + bufferSizeIn + ", Out: " + bufferSizeOut);
                
                if (bufferSizeIn <= 0 || bufferSizeOut <= 0) {
                    Log.e(TAG, "Invalid buffer sizes");
                    return;
                }
                
                short[] monoBuffer = new short[bufferSizeIn / 2];
                short[] stereoBuffer = new short[bufferSizeIn];
                
                synchronized (audioLock) {
                    // Create AudioTrack
                    audioTrack = new AudioTrack(
                        STREAM_TYPE, SAMPLE_RATE, CHANNEL_OUT,
                        AUDIO_FORMAT, bufferSizeOut, AudioTrack.MODE_STREAM
                    );
                    
                    if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                        Log.e(TAG, "AudioTrack init failed");
                        return;
                    }
                    
                    // Create AudioRecord
                    audioRecord = new AudioRecord(
                        RECORD_SOURCE, SAMPLE_RATE, CHANNEL_IN,
                        AUDIO_FORMAT, bufferSizeIn
                    );
                    
                    if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                        Log.e(TAG, "AudioRecord init failed");
                        if (audioTrack != null) {
                            audioTrack.release();
                            audioTrack = null;
                        }
                        return;
                    }
                    
                    audioRecord.startRecording();
                    audioTrack.play();
                }
                
                Log.d(TAG, "Audio started @ 96kHz MONO→STEREO");
                
                int loopCount = 0;
                
                while (isRecording) {
                    loopCount++;
                    lastLoopTime = System.currentTimeMillis();
                    
                    // Get local references to avoid race condition
                    AudioRecord record = audioRecord;
                    AudioTrack track = audioTrack;
                    
                    if (record == null || track == null) {
                        Log.w(TAG, "Audio objects null, exiting");
                        break;
                    }
                    
                    try {
                        int samplesRead = record.read(monoBuffer, 0, monoBuffer.length);
                        
                        if (samplesRead > 0) {
                            // Convert MONO to STEREO
                            for (int i = 0; i < samplesRead; i++) {
                                stereoBuffer[i * 2] = monoBuffer[i];
                                stereoBuffer[i * 2 + 1] = monoBuffer[i];
                            }
                            
                            track.write(stereoBuffer, 0, samplesRead * 2);
                            
                            if (loopCount % 1000 == 0) {
                                Log.d(TAG, "Loop " + loopCount + ": " + samplesRead + " samples");
                            }
                            
                        } else if (samplesRead < 0) {
                            Log.e(TAG, "Read error: " + samplesRead);
                            Thread.sleep(50);
                        }
                        
                    } catch (Exception e) {
                        if (isRecording) {
                            Log.e(TAG, "Loop error: " + e.getMessage());
                            Thread.sleep(100);
                        } else {
                            break;
                        }
                    }
                }
                
                Log.d(TAG, "Audio loop ended. Loops: " + loopCount);
                
            } catch (Exception e) {
                Log.e(TAG, "Audio thread error", e);
            } finally {
                synchronized (audioLock) {
                    cleanup();
                }
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
            }
            audioTrack = null;
        }
        
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
            }
            audioRecord = null;
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
                audioThread.join(1000);
            } catch (InterruptedException e) {
            }
        }
        
        synchronized (audioLock) {
            cleanup();
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

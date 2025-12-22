package com.usbaudio.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
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
    private static final String CHANNEL_ID = "AudioChannel";
    
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Thread audioThread;
    private volatile boolean isRecording = false;
    
    // EXACT SAME VALUES AS WORKING APP
    private static final int SAMPLE_RATE = 11025; // 0x2b11 from working app
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO; // 2
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT; // 2
    private static final int RECORD_SOURCE = MediaRecorder.AudioSource.MIC; // 1
    private static final int STREAM_TYPE = AudioManager.STREAM_MUSIC; // 3

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service starting");
        
        // Start foreground immediately
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
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
            // Set high priority like working app
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            
            try {
                // Get buffer sizes - EXACT SAME AS WORKING APP
                int recBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, 
                    CHANNEL_CONFIG, 
                    AUDIO_FORMAT
                );
                
                int playBufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AUDIO_FORMAT
                );
                
                Log.d(TAG, "Buffer sizes - Record: " + recBufferSize + ", Play: " + playBufferSize);
                
                if (recBufferSize <= 0 || playBufferSize <= 0) {
                    Log.e(TAG, "Invalid buffer sizes");
                    updateNotification("Error: Invalid buffer");
                    return;
                }
                
                // Create buffer
                byte[] buffer = new byte[recBufferSize];
                
                // Create AudioTrack FIRST (like working app)
                audioTrack = new AudioTrack(
                    STREAM_TYPE,           // 3 - STREAM_MUSIC
                    SAMPLE_RATE,           // 11025
                    AudioFormat.CHANNEL_OUT_STEREO, // stereo output
                    AUDIO_FORMAT,          // PCM_16BIT
                    playBufferSize,
                    AudioTrack.MODE_STREAM
                );
                
                if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioTrack init failed");
                    updateNotification("Error: AudioTrack");
                    return;
                }
                
                // Create AudioRecord SECOND (like working app)
                audioRecord = new AudioRecord(
                    RECORD_SOURCE,         // 1 - MIC
                    SAMPLE_RATE,           // 11025
                    CHANNEL_CONFIG,        // STEREO
                    AUDIO_FORMAT,          // PCM_16BIT
                    recBufferSize
                );
                
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord init failed");
                    cleanup();
                    updateNotification("Error: AudioRecord");
                    return;
                }
                
                // Start recording THEN playback (like working app)
                audioRecord.startRecording();
                audioTrack.play();
                
                Log.d(TAG, "Audio routing started @ " + SAMPLE_RATE + " Hz");
                updateNotification("Running @ 11kHz");
                
                // Main loop - EXACT SAME AS WORKING APP
                while (isRecording) {
                    // Read from microphone/USB
                    int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                    
                    if (bytesRead > 0) {
                        // Write directly to speaker
                        audioTrack.write(buffer, 0, bytesRead);
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "Read error: " + bytesRead);
                        Thread.sleep(100);
                    }
                }
                
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
            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("USB Audio Bridge")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .build();
            manager.notify(1, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Audio Service",
                NotificationManager.IMPORTANCE_LOW
        );
        
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
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

package com.winlator.star; // Updated package name!

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class MicCaptureThread extends Thread {
    private static final String TAG = "MicCaptureBridge";
    private static final int PORT = 4711; 
    private static final int SAMPLE_RATE = 44100;
    
    private boolean isRunning = false;
    private AudioRecord audioRecord;
    private ServerSocket serverSocket;

    @SuppressLint("MissingPermission") 
    @Override
    public void run() {
        isRunning = true;
        
        int bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, 
                AudioFormat.CHANNEL_IN_MONO, 
                AudioFormat.ENCODING_PCM_16BIT);

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize.");
            return;
        }

        try {
            serverSocket = new ServerSocket(PORT);
            Log.d(TAG, "Mic Bridge listening on port " + PORT);

            while (isRunning) {
                Socket clientSocket = serverSocket.accept();
                Log.d(TAG, "Container connected to Mic Bridge!");
                
                OutputStream outStream = clientSocket.getOutputStream();
                audioRecord.startRecording();
                
                byte[] audioBuffer = new byte[bufferSize];
                
                try {
                    while (isRunning && !clientSocket.isClosed()) {
                        int readResult = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                        if (readResult > 0) {
                            outStream.write(audioBuffer, 0, readResult);
                            outStream.flush();
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Client disconnected or stream error", e);
                } finally {
                    audioRecord.stop();
                    clientSocket.close();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Mic Bridge Server error", e);
        }
    }

    public void stopCapture() {
        isRunning = false;
        try {
            if (serverSocket != null) serverSocket.close();
            if (audioRecord != null) audioRecord.release();
        } catch (Exception e) {
            Log.e(TAG, "Error shutting down Mic Bridge", e);
        }
    }
}

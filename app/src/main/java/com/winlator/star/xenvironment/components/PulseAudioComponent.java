package com.winlator.star.xenvironment.components;

import android.content.Context;
import android.os.Process;

import com.winlator.star.core.AppUtils;
import com.winlator.star.core.FileUtils;
import com.winlator.star.core.ProcessHelper;
import com.winlator.star.xconnector.UnixSocketConfig;
import com.winlator.star.xenvironment.EnvironmentComponent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

public class PulseAudioComponent extends EnvironmentComponent {
    private final UnixSocketConfig socketConfig;
    private static int pid = -1;
    private static final Object lock = new Object();

    public PulseAudioComponent(UnixSocketConfig socketConfig) {
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            pid = execPulseAudio();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
        }
    }

    /**
     * Recover audio after a background/foreground or HDMI route change, WITHOUT restarting the daemon
     * (which would break the guest's still-open audio connection). Suspending then resuming the sink
     * closes and reopens its AAudio output stream, re-grabbing the current default route — the guest's
     * streams stay attached to the sink the whole time. Sent over module-cli-protocol-unix. Falls back
     * to a full daemon restart only if the control socket can't be reached. Call off the main thread.
     */
    public void resetAudioSink() {
        File cliSocket = new File(new File(environment.getContext().getFilesDir(), "/pulseaudio"), "cli");
        android.net.LocalSocket sock = null;
        try {
            sock = new android.net.LocalSocket();
            sock.connect(new android.net.LocalSocketAddress(
                    cliSocket.getAbsolutePath(), android.net.LocalSocketAddress.Namespace.FILESYSTEM));
            java.io.OutputStream os = sock.getOutputStream();
            os.write("suspend-sink AAudioSink 1\n".getBytes());
            os.flush();
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}
            os.write("suspend-sink AAudioSink 0\n".getBytes());
            os.flush();
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        } catch (Exception e) {
            android.util.Log.w("PulseAudio", "sink suspend/resume failed; restarting daemon", e);
            start(); // last resort — full daemon restart
        } finally {
            if (sock != null) try { sock.close(); } catch (IOException ignored) {}
        }
    }
    
    private void copyFromLibraryDir(File dst) {
        String[] libs = new String[] {
            "libltdl.so", "libpulseaudio.so", "libpulse.so", "libpulsecommon-13.0.so", "libpulsecore-13.0.so", "libsndfile.so"
        };
        for (int i = 0; i < libs.length; i++) {
            String path = "lib/" + "arm64-v8a" + "/" + libs[i];
            ClassLoader loader = PulseAudioComponent.class.getClassLoader();
            URL res = loader != null ? loader.getResource(path) : null;
            Path dstDir = Paths.get(dst.getAbsolutePath() + "/" + libs[i]);
            try {
                InputStream is = res != null ? res.openStream() : null;
                if (is != null) {
                    Files.copy(is, dstDir, StandardCopyOption.REPLACE_EXISTING);
                    FileUtils.chmod(dstDir.toFile(), 0771);
                }    
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private int execPulseAudio() {
        Context context = environment.getContext();
        File workingDir = new File(context.getFilesDir(), "/pulseaudio");
        if (!workingDir.isDirectory()) {
            workingDir.mkdirs();
            FileUtils.chmod(workingDir, 0771);
        }

        // Remove a stale CLI control socket so module-cli-protocol-unix can re-bind on start.
        File cliSocket = new File(workingDir, "cli");
        cliSocket.delete();

        File configFile = new File(workingDir, "default.pa");
        FileUtils.writeString(configFile, String.join("\n",
            "load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=0 socket=\""+socketConfig.path+"\"",
            "load-module module-aaudio-sink",
            "set-default-sink AAudioSink",
            // Text control socket so the app can suspend/resume the sink to recover the audio route
            // after a background/foreground (resetGuestAudio). .nofail so a load failure never breaks audio.
            ".nofail",
            "load-module module-cli-protocol-unix socket=\""+cliSocket.getAbsolutePath()+"\""
        ));

        String archName = AppUtils.getArchName();
        File modulesDir = new File(workingDir, "modules/"+archName);
        String systemLibPath = archName.equals("arm64") ? "/system/lib64" : "system/lib";

        ArrayList<String> envVars = new ArrayList<>();
        envVars.add("LD_LIBRARY_PATH="+systemLibPath+":"+modulesDir+":"+workingDir.getAbsolutePath());
        envVars.add("HOME="+workingDir);
        envVars.add("TMPDIR="+environment.getTmpDir());
        
        copyFromLibraryDir(workingDir);

        String command = workingDir.getAbsolutePath() + "/libpulseaudio.so";
        command += " --system=false";
        command += " --disable-shm=true";
        command += " --fail=false";
        command += " -n --file=default.pa";
        command += " --daemonize=false";
        command += " --use-pid-file=false";
        command += " --exit-idle-time=-1";

        return ProcessHelper.exec(command, envVars.toArray(new String[0]), workingDir);
    }
}

package com.winlator.star.xserver.extensions;

import android.util.SparseArray;

import com.winlator.star.xconnector.XInputStream;
import com.winlator.star.xconnector.XOutputStream;
import com.winlator.star.xserver.XClient;
import com.winlator.star.xserver.XResource;
import com.winlator.star.xserver.XResourceManager;
import com.winlator.star.xserver.errors.BadFence;
import com.winlator.star.xserver.errors.BadIdChoice;
import com.winlator.star.xserver.errors.BadImplementation;
import com.winlator.star.xserver.errors.BadMatch;
import com.winlator.star.xserver.errors.XRequestError;

import java.io.IOException;

public class SyncExtension implements Extension, XResourceManager.OnResourceLifecycleListener {
    public static final byte MAJOR_OPCODE = -104;

    // A fence is created against a drawable (CreateFence's first argument). Tracking that
    // drawable id lets us drop the fence when its drawable/window is destroyed — otherwise a
    // fence whose owner dies without an explicit DestroyFence leaks here forever. `triggered`
    // is the same state the old SparseBooleanArray held.
    private static class Fence {
        final int drawableId;
        boolean triggered;
        Fence(int drawableId, boolean triggered) { this.drawableId = drawableId; this.triggered = triggered; }
    }
    private final SparseArray<Fence> fences = new SparseArray<>();

    private static abstract class ClientOpcodes {
        private static final byte CREATE_FENCE = 14;
        private static final byte TRIGGER_FENCE = 15;
        private static final byte RESET_FENCE = 16;
        private static final byte DESTROY_FENCE = 17;
        private static final byte AWAIT_FENCE = 19;
    }

    @Override
    public String getName() {
        return "SYNC";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return Byte.MIN_VALUE;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    public void setTriggered(int id) {
        synchronized (fences) {
            Fence fence = fences.get(id);
            if (fence != null) fence.triggered = true;
        }
    }

    private void createFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            // Was skip(4): read (don't discard) the drawable id so the fence can be pruned when
            // that drawable/window is destroyed. Same 4 bytes consumed, so `id` (the fence id,
            // next int) is unchanged and the parse position is identical to before.
            int drawableId = inputStream.readInt();
            int id = inputStream.readInt();

            if (fences.indexOfKey(id) >= 0) throw new BadIdChoice(id);

            boolean initiallyTriggered = inputStream.readByte() == 1;
            inputStream.skip(3);

            fences.put(id, new Fence(drawableId, initiallyTriggered));
        }
    }

    private void triggerFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            Fence fence = fences.get(id);
            if (fence == null) throw new BadFence(id);
            fence.triggered = true;
        }
    }

    private void resetFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            Fence fence = fences.get(id);
            if (fence == null) throw new BadFence(id);

            if (!fence.triggered) throw new BadMatch();

            fence.triggered = false;
        }
    }

    private void destroyFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);
            fences.delete(id);
        }
    }

    private void awaitFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int length = client.getRemainingRequestLength();
            int[] ids = new int[length / 4];
            int i = 0;

            while (length != 0) {
                ids[i++] = inputStream.readInt();
                length -= 4;
            }

            boolean anyTriggered = false;
            do {
                for (int id : ids) {
                    Fence fence = fences.get(id);
                    if (fence == null) throw new BadFence(id);
                    anyTriggered = fence.triggered;
                    if (anyTriggered) break;
                }
            }
            while (!anyTriggered);
        }
    }

    // A fence's drawable (or the window backing it) was destroyed — drop any fences bound to it
    // so they don't accumulate for the life of the session. Registered on both the window and
    // pixmap managers (a drawable id can be either). Iterates backwards so removeAt is safe.
    @Override
    public void onFreeResource(XResource resource) {
        synchronized (fences) {
            for (int i = fences.size() - 1; i >= 0; i--) {
                if (fences.valueAt(i).drawableId == resource.id) fences.removeAt(i);
            }
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.CREATE_FENCE :
                createFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.TRIGGER_FENCE:
                triggerFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.RESET_FENCE:
                resetFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.DESTROY_FENCE:
                destroyFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.AWAIT_FENCE:
                awaitFence(client, inputStream, outputStream);
                break;
            default:
                throw new BadImplementation();
        }
    }
}

package com.winlator.star.cast;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal live HLS segmenter: turns the H.264 elementary stream from MediaCodec into MPEG-TS (.ts)
 * segments + a rolling live .m3u8 playlist, held in memory for {@link HttpFileServer}-style serving.
 *
 * Android's MediaMuxer can't emit MPEG-TS, so this hand-rolls just enough of it for a Chromecast to
 * play: PAT + PMT (H.264 = stream type 0x1B) at the top of every segment, PES-wrapped access units
 * with 90 kHz PTS, PCR in the video PID's adaptation field, and 188-byte TS packet alignment. Segments
 * start on IDR keyframes (encoder set to ~2 s GOP), and SPS/PPS is prepended to each keyframe so every
 * segment is independently decodable. Only the last few segments are kept (live window).
 */
public class TsSegmenter {
    private static final int PID_PAT = 0x0000, PID_PMT = 0x1000, PID_VIDEO = 0x0100;
    private static final int TARGET_MS = 2000;      // segment length target
    private static final int WINDOW = 5;            // segments kept in the live window

    private byte[] codecConfig;                     // SPS+PPS in Annex-B
    private ByteArrayOutputStream seg;              // current segment being built
    private long segStartPtsUs = -1;
    private int ccPat = 0, ccPmt = 0, ccVideo = 0;
    private int mediaSeq = 0;                        // EXT-X-MEDIA-SEQUENCE of the oldest segment
    private int segIndex = 0;                        // monotonically increasing segment id

    // name -> bytes, insertion-ordered so the oldest is first.
    private final LinkedHashMap<String, byte[]> segments = new LinkedHashMap<>();
    private final ArrayDeque<String> order = new ArrayDeque<>();
    private final ArrayDeque<Double> durations = new ArrayDeque<>();

    /** Codec config (BUFFER_FLAG_CODEC_CONFIG output) — SPS/PPS in Annex-B. */
    public synchronized void setCodecConfig(byte[] cfg) { codecConfig = cfg; }

    /** Feed one access unit (Annex-B, from MediaCodec) with its PTS (µs) and whether it's a keyframe. */
    public synchronized void feed(byte[] au, long ptsUs, boolean keyframe) {
        if (keyframe && seg != null && (ptsUs - segStartPtsUs) >= TARGET_MS * 1000L) closeSegment(ptsUs);
        if (seg == null) { if (!keyframe) return; openSegment(ptsUs); }

        byte[] payload = (keyframe && codecConfig != null) ? concat(codecConfig, au) : au;
        writePes(payload, ptsUs, keyframe);
    }

    private void openSegment(long ptsUs) {
        seg = new ByteArrayOutputStream();
        segStartPtsUs = ptsUs;
        ccPat = ccPmt = ccVideo = 0;
        writeTs(PID_PAT, true, buildPat(), -1);
        writeTs(PID_PMT, true, buildPmt(), -1);
    }

    private void closeSegment(long endPtsUs) {
        if (seg == null) return;
        double dur = Math.max(0.1, (endPtsUs - segStartPtsUs) / 1_000_000.0);
        String name = "seg" + segIndex + ".ts";
        segments.put(name, seg.toByteArray());
        order.addLast(name);
        durations.addLast(dur);
        segIndex++;
        while (order.size() > WINDOW) {
            String old = order.pollFirst();
            segments.remove(old);
            durations.pollFirst();
            mediaSeq++;
        }
        seg = null;
    }

    public synchronized byte[] getSegment(String name) { return segments.get(name); }

    /** The live playlist. Empty until the first segment closes. */
    public synchronized String playlist() {
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n#EXT-X-VERSION:3\n");
        sb.append("#EXT-X-TARGETDURATION:").append((int) Math.ceil(TARGET_MS / 1000.0) + 1).append('\n');
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSeq).append('\n');
        java.util.Iterator<String> it = order.iterator();
        java.util.Iterator<Double> dit = durations.iterator();
        while (it.hasNext()) {
            sb.append(String.format(Locale.US, "#EXTINF:%.3f,\n", dit.next()));
            sb.append(it.next()).append('\n');
        }
        return sb.toString();
    }

    public synchronized boolean hasSegments() { return !order.isEmpty(); }

    // ---- MPEG-TS building -----------------------------------------------------------------------

    // PES-wrap an access unit and split it across 188-byte TS packets on the video PID.
    private void writePes(byte[] au, long ptsUs, boolean withPcr) {
        long pts = ptsUs * 9 / 100;                 // µs -> 90 kHz
        ByteArrayOutputStream pes = new ByteArrayOutputStream();
        // PES start code + stream id (0xE0 video)
        pes.write(0x00); pes.write(0x00); pes.write(0x01); pes.write(0xE0);
        // PES packet length 0 (unbounded, allowed for video)
        pes.write(0x00); pes.write(0x00);
        pes.write(0x80);                            // marker, no scrambling
        pes.write(0x80);                            // PTS present
        pes.write(0x05);                            // PES header data length (PTS = 5 bytes)
        writePts(pes, 0x02, pts);
        pes.write(au, 0, au.length);
        byte[] data = pes.toByteArray();

        int offset = 0; boolean first = true;
        while (offset < data.length) {
            ByteArrayOutputStream pkt = new ByteArrayOutputStream(188);
            int afLen = 0;
            boolean pcr = first && withPcr;
            // Adaptation field is needed on the first packet (PCR) or to stuff the last packet.
            int headerLen = 4;
            int maxPayload = 184;
            byte[] af = null;
            if (pcr) {
                af = buildAdaptation(true, pts, 0);
                maxPayload = 184 - af.length;
            }
            int remaining = data.length - offset;
            int payloadLen = Math.min(remaining, maxPayload);
            int stuffing = 0;
            if (payloadLen < maxPayload && !pcr) {
                // last packet: pad with an adaptation field of stuffing bytes
                stuffing = maxPayload - payloadLen;
                af = buildAdaptation(false, 0, stuffing - 1 >= 0 ? stuffing : 0);
                // recompute payload space
                maxPayload = 184 - af.length;
                payloadLen = Math.min(remaining, maxPayload);
            }

            int afc = (af != null) ? 0b11 : 0b01;   // adaptation+payload : payload only
            pkt.write(0x47);
            pkt.write(((first ? 0x40 : 0x00)) | ((PID_VIDEO >> 8) & 0x1F));
            pkt.write(PID_VIDEO & 0xFF);
            pkt.write((afc << 4) | (ccVideo & 0x0F));
            ccVideo = (ccVideo + 1) & 0x0F;
            if (af != null) pkt.write(af, 0, af.length);
            pkt.write(data, offset, payloadLen);
            offset += payloadLen;
            // pad to 188 if short (shouldn't happen once adaptation stuffing is right)
            byte[] b = pkt.toByteArray();
            seg.write(b, 0, b.length);
            for (int i = b.length; i < 188; i++) seg.write(0xFF);
            first = false;
        }
    }

    private byte[] buildAdaptation(boolean pcr, long pts, int stuffing) {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        // length + flags computed below; write placeholder then fix
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int flags = 0;
        if (pcr) {
            flags |= 0x10;                          // PCR flag
            long pcrBase = pts;                     // reuse PTS as PCR base (90 kHz)
            long pcrExt = 0;
            body.write((int) ((pcrBase >> 25) & 0xFF));
            body.write((int) ((pcrBase >> 17) & 0xFF));
            body.write((int) ((pcrBase >> 9) & 0xFF));
            body.write((int) ((pcrBase >> 1) & 0xFF));
            body.write((int) (((pcrBase & 0x1) << 7) | 0x7E | ((pcrExt >> 8) & 0x1)));
            body.write((int) (pcrExt & 0xFF));
        }
        byte[] bodyB = body.toByteArray();
        int len = 1 + bodyB.length + stuffing;      // flags byte + body + stuffing
        a.write(len);
        a.write(flags);
        a.write(bodyB, 0, bodyB.length);
        for (int i = 0; i < stuffing; i++) a.write(0xFF);
        return a.toByteArray();
    }

    private void writeTs(int pid, boolean pusi, byte[] payload, long pcr) {
        // section-style payload (PAT/PMT): pointer_field 0 then the table.
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(0x00);                              // pointer_field
        p.write(payload, 0, payload.length);
        byte[] data = p.toByteArray();
        int cc = (pid == PID_PAT) ? (ccPat = (ccPat + 1) & 0x0F) : (ccPmt = (ccPmt + 1) & 0x0F);
        byte[] pkt = new byte[188];
        java.util.Arrays.fill(pkt, (byte) 0xFF);
        pkt[0] = 0x47;
        pkt[1] = (byte) ((pusi ? 0x40 : 0x00) | ((pid >> 8) & 0x1F));
        pkt[2] = (byte) (pid & 0xFF);
        pkt[3] = (byte) ((0b01 << 4) | (cc & 0x0F)); // payload only
        System.arraycopy(data, 0, pkt, 4, Math.min(data.length, 184));
        seg.write(pkt, 0, 188);
    }

    private byte[] buildPat() {
        // PAT: one program (1) -> PMT PID.
        byte[] section = new byte[]{
                0x00,                               // table_id PAT
                (byte) 0xB0, 0x0D,                  // section_syntax=1, length=13
                0x00, 0x01,                         // transport_stream_id
                (byte) 0xC1,                        // version/current_next
                0x00, 0x00,                         // section/last section
                0x00, 0x01,                         // program_number 1
                (byte) (0xE0 | ((PID_PMT >> 8) & 0x1F)), (byte) (PID_PMT & 0xFF),
                0, 0, 0, 0                           // CRC placeholder
        };
        return withCrc(section, section.length - 4);
    }

    private byte[] buildPmt() {
        byte[] section = new byte[]{
                0x02,                               // table_id PMT
                (byte) 0xB0, 0x12,                  // length=18
                0x00, 0x01,                         // program_number
                (byte) 0xC1, 0x00, 0x00,
                (byte) (0xE0 | ((PID_VIDEO >> 8) & 0x1F)), (byte) (PID_VIDEO & 0xFF), // PCR PID = video
                (byte) 0xF0, 0x00,                  // program_info_length 0
                0x1B,                               // stream_type H.264
                (byte) (0xE0 | ((PID_VIDEO >> 8) & 0x1F)), (byte) (PID_VIDEO & 0xFF),
                (byte) 0xF0, 0x00,                  // ES_info_length 0
                0, 0, 0, 0                           // CRC placeholder
        };
        return withCrc(section, section.length - 4);
    }

    private static void writePts(ByteArrayOutputStream o, int flag, long pts) {
        o.write((flag << 4) | (int) (((pts >> 30) & 0x07) << 1) | 0x01);
        o.write((int) ((pts >> 22) & 0xFF));
        o.write((int) (((pts >> 15) & 0x7F) << 1) | 0x01);
        o.write((int) ((pts >> 7) & 0xFF));
        o.write((int) (((pts & 0x7F) << 1) | 0x01));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    // MPEG-2 systems CRC32 over the section (excluding the 4 CRC bytes), written big-endian.
    private static byte[] withCrc(byte[] section, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (section[i] & 0xFF) << 24;
            for (int j = 0; j < 8; j++)
                crc = ((crc & 0x80000000) != 0) ? (crc << 1) ^ 0x04C11DB7 : (crc << 1);
        }
        section[len]     = (byte) ((crc >> 24) & 0xFF);
        section[len + 1] = (byte) ((crc >> 16) & 0xFF);
        section[len + 2] = (byte) ((crc >> 8) & 0xFF);
        section[len + 3] = (byte) (crc & 0xFF);
        return section;
    }
}

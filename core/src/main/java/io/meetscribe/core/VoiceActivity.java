package io.meetscribe.core;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** Bounded-memory frame-energy VAD for normalized PCM16/16kHz. Not a neural speech classifier. */
public final class VoiceActivity {
    private static final int FRAME = 320, RATE = 16000;
    public record Range(long startFrame, long endFrame) {}
    private VoiceActivity() {}
    public static List<Range> detect(Path wav) throws IOException, InterruptedException {
        List<Double> energy = new ArrayList<>();
        try (InputStream in = new BufferedInputStream(new FileInputStream(wav.toFile()), 65536)) {
            in.skipNBytes(44); long samples = (java.nio.file.Files.size(wav) - 44) / 2;
            byte[] frameBytes = new byte[FRAME * 2];
            for (long pos = 0; pos < samples;) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                int count = (int)Math.min(FRAME, samples - pos); double square = 0;
                if (in.readNBytes(frameBytes, 0, count * 2) != count * 2) throw new EOFException("Truncated normalized audio");
                for (int i = 0; i < count; i++) { int sample = (short)((frameBytes[i * 2] & 255) | frameBytes[i * 2 + 1] << 8); square += (double)sample * sample; }
                energy.add(Math.sqrt(square / count)); pos += count;
            }
            if (energy.isEmpty()) return List.of();
            double[] sorted = energy.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            // A capped percentile floor retains continuous speech when there is no initial silence.
            double threshold = Math.max(60, Math.min(300, sorted[sorted.length / 10] * 3));
            List<Range> raw = new ArrayList<>(); int start = -1, voiced = 0, quiet = 0;
            for (int i = 0; i < energy.size(); i++) {
                boolean active = energy.get(i) >= threshold * (start < 0 ? 1 : 0.65);
                if (active) { if (start < 0) start = i; voiced++; quiet = 0; }
                else if (start >= 0 && ++quiet >= 20) {
                    if (voiced >= 4) raw.add(new Range(Math.max(0, (long)start * FRAME - 4800), Math.min(samples, (long)(i - quiet + 1) * FRAME + 6400)));
                    start = -1; voiced = quiet = 0;
                }
            }
            if (start >= 0 && voiced >= 4) raw.add(new Range(Math.max(0, (long)start * FRAME - 4800), samples));
            List<Range> merged = new ArrayList<>();
            for (Range r : raw) {
                if (!merged.isEmpty() && merged.getLast().endFrame >= r.startFrame) {
                    Range prev = merged.removeLast(); merged.add(new Range(prev.startFrame, r.endFrame));
                } else merged.add(r);
            }
            List<Range> bounded = new ArrayList<>();
            for (Range r : merged) {
                long from = r.startFrame;
                while (r.endFrame - from > 60L * RATE) {
                    // Prefer the quietest frame in the last ten seconds to avoid splitting a word.
                    long end = from + 60L * RATE; double min = Double.MAX_VALUE;
                    for (int i = (int)((from + 50L * RATE) / FRAME); i < Math.min(energy.size(), (from + 60L * RATE) / FRAME); i++)
                        if (energy.get(i) < min) { min = energy.get(i); end = (long)i * FRAME; }
                    bounded.add(new Range(from, end)); from = end;
                }
                if (from < r.endFrame) bounded.add(new Range(from, r.endFrame));
            }
            return List.copyOf(bounded);
        }
    }
    public static void extract(Path wav, Path target, Range range) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(wav.toFile(), "r"); RandomAccessFile out = new RandomAccessFile(target.toFile(), "rw")) {
            byte[] header = new byte[44]; in.readFully(header); out.setLength(0); out.write(header);
            long bytes = (range.endFrame - range.startFrame) * 2; in.seek(44 + range.startFrame * 2);
            byte[] buffer = new byte[65536]; long remaining = bytes;
            while (remaining > 0) { int n = (int)Math.min(buffer.length, remaining); in.readFully(buffer, 0, n); out.write(buffer, 0, n); remaining -= n; }
            out.seek(4); littleInt(out, bytes + 36); out.seek(40); littleInt(out, bytes);
        }
    }
    private static void littleInt(RandomAccessFile out, long n) throws IOException { for (int i = 0; i < 4; i++) out.write((int)(n >>> (8 * i)) & 255); }
}

package io.meetscribe.core;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;

/** Streaming PCM conversion. Timeline is preserved; silence is never cut out. */
public final class PcmAudio {
    private PcmAudio() {}
    public record Result(long frames, boolean silent) {}
    public static Result normalize(Path input, Path output) throws Exception {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(input.toFile())) {
            AudioFormat f = source.getFormat();
            if (!f.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED) && !f.getEncoding().equals(AudioFormat.Encoding.PCM_UNSIGNED)
                    && !f.getEncoding().equals(AudioFormat.Encoding.PCM_FLOAT)) throw new IOException("Only uncompressed PCM WAV is supported");
            if (f.getChannels() < 1 || f.getChannels() > 32 || f.getSampleRate() < 8000 || f.getSampleRate() > 192000)
                throw new IOException("Unsupported channel count or sample rate");
            AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, f.getSampleRate(), 16, f.getChannels(), f.getChannels() * 2, f.getSampleRate(), false);
            try (AudioInputStream decoded = AudioSystem.getAudioInputStream(pcm, source);
                 RandomAccessFile out = new RandomAccessFile(output.toFile(), "rw");
                 BufferedOutputStream data = new BufferedOutputStream(new FileOutputStream(out.getFD()), 65536)) {
                out.setLength(0); out.write(new byte[44]);
                // Area averaging is a low-cost anti-alias filter for the MVP downsampler.
                byte[] frame = new byte[pcm.getFrameSize()];
                double step = f.getSampleRate() / 16000.0, remaining = step, total = 0, sumSquares = 0;
                long frames = 0, sourceFrames = 0;
                while (true) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    int read = decoded.read(frame);
                    if (read < 0) break;
                    if (read != frame.length) throw new IOException("Truncated PCM frame");
                    double sample = 0;
                    for (int c = 0; c < f.getChannels(); c++) sample += (short)((frame[c * 2] & 255) | (frame[c * 2 + 1] << 8));
                    sample /= f.getChannels(); sourceFrames++;
                    double available = 1;
                    while (available > 1e-9) {
                        double weight = Math.min(available, remaining); total += sample * weight; remaining -= weight; available -= weight;
                        if (remaining < 1e-9) {
                            int value = (int)Math.round(total / step); writeShort(data, value); sumSquares += (double)value * value;
                            frames++; total = 0; remaining = step;
                        }
                    }
                }
                if (sourceFrames == 0) throw new IOException("WAV contains no audio frames");
                if (step - remaining > 1e-9) { int value = (int)Math.round(total / (step - remaining)); writeShort(data, value); sumSquares += (double)value * value; frames++; }
                long bytes = frames * 2;
                if (bytes > 0xfffffff0L) throw new IOException("WAV exceeds 4 GB");
                data.flush();
                out.seek(0); out.writeBytes("RIFF"); writeInt(out, bytes + 36); out.writeBytes("WAVEfmt ");
                writeInt(out, 16); writeShort(out, 1); writeShort(out, 1); writeInt(out, 16000); writeInt(out, 32000);
                writeShort(out, 2); writeShort(out, 16); out.writeBytes("data"); writeInt(out, bytes);
                return new Result(frames, Math.sqrt(sumSquares / Math.max(1, frames)) < 8);
            }
        }
    }
    /** Repairs only our canonical PCM16 capture header; never guesses layouts of arbitrary WAVs. */
    public static boolean recoverCaptureHeader(Path path) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            if (file.length() < 44) return false;
            byte[] header = new byte[44]; file.readFully(header);
            var b = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (!new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                    || !new String(header, 8, 8, java.nio.charset.StandardCharsets.US_ASCII).equals("WAVEfmt ")
                    || !new String(header, 36, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("data")
                    || b.getInt(16) != 16 || b.getShort(20) != 1 || b.getShort(34) != 16) return false;
            int channels = Short.toUnsignedInt(b.getShort(22)), block = Short.toUnsignedInt(b.getShort(32));
            if (channels < 1 || channels > 32 || block != channels * 2 || b.getInt(24) < 8000 || b.getInt(24) > 192000) return false;
            long actual = (file.length() - 44) / block * block;
            if (actual > 0xffffffffL - 36) throw new IOException("Captured WAV exceeds RIFF limit");
            if (Integer.toUnsignedLong(b.getInt(40)) == actual && file.length() == actual + 44) return false;
            file.setLength(actual + 44); file.seek(4); writeInt(file, actual + 36); file.seek(40); writeInt(file, actual);
            file.getFD().sync(); return true;
        }
    }
    private static void writeShort(OutputStream out, int value) throws IOException { out.write(value & 255); out.write((value >>> 8) & 255); }
    private static void writeShort(RandomAccessFile out, int value) throws IOException { out.write(value & 255); out.write((value >>> 8) & 255); }
    private static void writeInt(RandomAccessFile out, long value) throws IOException { for (int i = 0; i < 4; i++) out.write((int)(value >>> (i * 8)) & 255); }
}

package com.example.globlingo.service;

import com.example.globlingo.dto.TranscriptResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local, key-free transcript providers: yt-dlp caption download and an optional
 * faster-whisper helper script. Both run as subprocesses with timeouts and
 * temporary files that are always cleaned up.
 */
@Service
public class LocalTranscriptProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalTranscriptProvider.class);
    private static final Pattern VTT_TIME = Pattern.compile(
            "(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})");
    private static final Pattern VTT_TAG = Pattern.compile("<[^>]+>");

    private final ObjectMapper objectMapper;

    @Value("${transcript.ytdlp.command:python -m yt_dlp}")
    private String ytdlpCommand;

    @Value("${transcript.ytdlp.enabled:true}")
    private boolean ytdlpEnabled;

    @Value("${transcript.ytdlp.timeout-seconds:120}")
    private long ytdlpTimeoutSeconds;

    @Value("${transcript.whisper.enabled:false}")
    private boolean whisperEnabled;

    @Value("${transcript.whisper.script:}")
    private String whisperScript;

    @Value("${transcript.whisper.command:python}")
    private String whisperCommand;

    @Value("${transcript.whisper.timeout-seconds:600}")
    private long whisperTimeoutSeconds;

    public LocalTranscriptProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean isYtdlpAvailable() {
        return ytdlpEnabled;
    }

    public boolean isWhisperAvailable() {
        return whisperEnabled && whisperScript != null && !whisperScript.isBlank()
                && Files.exists(Path.of(whisperScript));
    }

    /** Try yt-dlp captions for the requested language. Returns null when unavailable. */
    public TranscriptResponse fetchWithYtdlp(String videoId, String lang) {
        if (!ytdlpEnabled) return null;
        String videoUrl = "https://www.youtube.com/watch?v=" + videoId;
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("globlingo-ytdlp-");
            Path outTemplate = workDir.resolve("sub");
            List<String> cmd = new ArrayList<>(List.of(ytdlpCommand.split("\\s+")));
            cmd.addAll(List.of(
                    "--skip-download",
                    "--write-subs", "--write-auto-subs",
                    "--sub-langs", lang + "," + lang + "-orig",
                    "--sub-format", "vtt",
                    "--no-playlist",
                    "--no-warnings",
                    "-o", outTemplate.toString(),
                    videoUrl));
            ProcessResult result = run(cmd, ytdlpTimeoutSeconds);
            Path vtt = findVtt(workDir);
            if (vtt == null) {
                log.info("yt-dlp produced no captions for {} lang={}: {}", videoId, lang,
                        result.outputTail.isEmpty() ? "no output" : result.outputTail);
                return null;
            }
            List<TranscriptResponse.TranscriptChunk> chunks = parseVtt(vtt);
            if (chunks.isEmpty()) return null;
            return new TranscriptResponse(videoId, lang, List.of(lang), chunks);
        } catch (Exception e) {
            log.warn("yt-dlp transcript failed for {}: {}", videoId, e.getMessage());
            return null;
        } finally {
            deleteTree(workDir);
        }
    }

    /** Try faster-whisper via helper script. Returns null when unavailable/fails. */
    public TranscriptResponse fetchWithWhisper(String videoId, String lang) {
        if (!isWhisperAvailable()) return null;
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("globlingo-whisper-");
            List<String> cmd = new ArrayList<>(List.of(whisperCommand.split("\\s+")));
            cmd.add(whisperScript);
            cmd.add("https://www.youtube.com/watch?v=" + videoId);
            cmd.add(lang);
            cmd.add(workDir.toString());
            ProcessResult result = run(cmd, whisperTimeoutSeconds);
            Path json = workDir.resolve("transcript.json");
            if (!Files.exists(json)) {
                log.info("whisper produced no transcript for {}: {}", videoId,
                        result.outputTail.isEmpty() ? "no output" : result.outputTail);
                return null;
            }
            JsonNode root = objectMapper.readTree(Files.readString(json, StandardCharsets.UTF_8));
            String detected = root.path("language").asText(lang);
            List<TranscriptResponse.TranscriptChunk> chunks = new ArrayList<>();
            for (JsonNode seg : root.path("segments")) {
                String text = seg.path("text").asText("").trim();
                if (text.isEmpty()) continue;
                chunks.add(new TranscriptResponse.TranscriptChunk(text,
                        seg.path("start").asDouble(0), seg.path("end").asDouble(0)));
            }
            if (chunks.isEmpty()) return null;
            return new TranscriptResponse(videoId, detected, List.of(detected), chunks);
        } catch (Exception e) {
            log.warn("whisper transcript failed for {}: {}", videoId, e.getMessage());
            return null;
        } finally {
            deleteTree(workDir);
        }
    }

    private Path findVtt(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".vtt")).findFirst().orElse(null);
        }
    }

    private List<TranscriptResponse.TranscriptChunk> parseVtt(Path vtt) throws IOException {
        List<TranscriptResponse.TranscriptChunk> chunks = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        double start = -1, end = -1;
        for (String raw : Files.readAllLines(vtt, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            Matcher m = VTT_TIME.matcher(line);
            if (m.find()) {
                if (text.length() > 0 && start >= 0) {
                    addChunk(chunks, text.toString(), start, end);
                }
                text.setLength(0);
                start = toSeconds(m.group(1), m.group(2), m.group(3), m.group(4));
                end = toSeconds(m.group(5), m.group(6), m.group(7), m.group(8));
            } else if (!line.isEmpty() && !line.startsWith("WEBVTT") && !line.startsWith("Kind:")
                    && !line.startsWith("Language:") && !line.matches("^\\d+$")) {
                String cleaned = VTT_TAG.matcher(line).replaceAll("").trim();
                if (!cleaned.isEmpty()) {
                    if (text.length() > 0) text.append(' ');
                    text.append(cleaned);
                }
            }
        }
        if (text.length() > 0 && start >= 0) addChunk(chunks, text.toString(), start, end);
        return dedupeRollingWindow(chunks);
    }

    /* YouTube auto-captions emit rolling-window cues: a full line followed by a
       ~0.01s "tail" cue repeating the previous text. Those micro-cues create
       duplicate subtitle rows and make the active-line highlight jump. Drop a
       chunk only when it is extremely short in time AND its text already
       appears in a neighbouring chunk — real repeated speech is kept. */
    private List<TranscriptResponse.TranscriptChunk> dedupeRollingWindow(
            List<TranscriptResponse.TranscriptChunk> chunks) {
        if (chunks.size() < 3) return chunks;
        List<TranscriptResponse.TranscriptChunk> out = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            TranscriptResponse.TranscriptChunk c = chunks.get(i);
            double dur = c.durationSeconds();
            if (dur >= 0 && dur < 0.12) {
                String t = c.text();
                String prev = i > 0 ? chunks.get(i - 1).text() : "";
                String next = i + 1 < chunks.size() ? chunks.get(i + 1).text() : "";
                if ((prev.contains(t) || next.contains(t)) && t.length() < Math.max(prev.length(), next.length())) {
                    continue;
                }
            }
            out.add(c);
        }
        return out;
    }

    private void addChunk(List<TranscriptResponse.TranscriptChunk> chunks, String text, double start, double end) {
        // Auto-captions repeat the rolling window; drop lines fully contained in the previous one.
        String t = text.trim();
        if (t.isEmpty()) return;
        if (!chunks.isEmpty()) {
            var prev = chunks.get(chunks.size() - 1);
            if (t.equals(prev.text()) || (t.length() > prev.text().length() && t.endsWith(prev.text()))) return;
        }
        chunks.add(new TranscriptResponse.TranscriptChunk(t, start, Math.max(end - start, 0)));
    }

    private double toSeconds(String h, String m, String s, String ms) {
        return Integer.parseInt(h) * 3600 + Integer.parseInt(m) * 60
                + Integer.parseInt(s) + Integer.parseInt(ms) / 1000.0;
    }

    private ProcessResult run(List<String> cmd, long timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < 8000) output.append(line).append('\n');
            }
        }
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("subprocess timed out after " + timeoutSeconds + "s");
        }
        String out = output.toString();
        String tail = out.length() > 500 ? out.substring(out.length() - 500) : out;
        return new ProcessResult(process.exitValue(), tail);
    }

    private void deleteTree(Path dir) {
        if (dir == null) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private record ProcessResult(int exitCode, String outputTail) { }
}

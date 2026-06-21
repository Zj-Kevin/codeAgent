package com.mewcode.conversation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mewcode.config.MewCodeProperties;
import com.mewcode.provider.Message;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

@Component
public class HistoryStore {

    private static final DateTimeFormatter FILE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

    private final Path historyDir;
    private final ObjectMapper json;

    public HistoryStore(MewCodeProperties props) {
        this.historyDir = Path.of(props.getHistory().getDir().replaceFirst("^~",
            System.getProperty("user.home")));
        this.json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Path save(List<Message> messages) throws IOException {
        Files.createDirectories(historyDir);
        String filename = LocalDateTime.now().format(FILE_FORMATTER) + ".json";
        Path file = historyDir.resolve(filename);
        int counter = 1;
        while (Files.exists(file)) {
            file = historyDir.resolve(LocalDateTime.now().format(FILE_FORMATTER) + "-" + counter + ".json");
            counter++;
        }
        var session = new SessionFile(filename, LocalDateTime.now().toString(), messages);
        json.writeValue(file.toFile(), session);
        return file;
    }

    public List<Message> load(String filename) throws IOException {
        Path file = historyDir.resolve(filename);
        if (!Files.exists(file)) throw new IOException("History file not found: " + file);
        var session = json.readValue(file.toFile(), SessionFile.class);
        return session.messages != null ? session.messages : List.of();
    }

    public List<Path> listSessions() throws IOException {
        if (!Files.exists(historyDir)) return List.of();
        try (Stream<Path> files = Files.list(historyDir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".json"))
                .sorted(java.util.Comparator.reverseOrder()).toList();
        }
    }

    public record SessionFile(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("created_at") String createdAt,
        List<Message> messages
    ) {}
}

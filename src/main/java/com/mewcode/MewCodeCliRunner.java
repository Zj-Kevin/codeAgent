package com.mewcode;

import com.mewcode.agent.AgentLoop;
import com.mewcode.config.MewCodeProperties;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.HistoryStore;
import com.mewcode.provider.LLMProvider;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tui.MewCodeCli;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Spring Boot entry point — wires injected beans into MewCodeCli and starts the terminal loop.
 */
@Component
public class MewCodeCliRunner implements CommandLineRunner {

    private final LLMProvider provider;
    private final ConversationManager conversationManager;
    private final HistoryStore historyStore;
    private final ToolRegistry toolRegistry;
    private final MewCodeProperties props;
    private final ObjectProvider<AgentLoop> agentLoopProvider;

    public MewCodeCliRunner(LLMProvider provider, ConversationManager conversationManager,
                            HistoryStore historyStore, ToolRegistry toolRegistry,
                            MewCodeProperties props, ObjectProvider<AgentLoop> agentLoopProvider) {
        this.provider = provider;
        this.conversationManager = conversationManager;
        this.historyStore = historyStore;
        this.toolRegistry = toolRegistry;
        this.props = props;
        this.agentLoopProvider = agentLoopProvider;
    }

    @Override
    public void run(String... args) throws IOException {
        // Parse CLI args (non-Spring args)
        String loadHistoryFile = null;
        boolean listHistory = false;
        boolean planOnly = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--history" -> { if (i + 1 < args.length) loadHistoryFile = args[++i]; }
                case "--list-history" -> listHistory = true;
                case "--plan-only" -> planOnly = true;
                case "--help", "-h" -> { printHelp(); return; }
            }
        }

        if (listHistory) {
            var sessions = historyStore.listSessions();
            if (sessions.isEmpty()) System.out.println("No history sessions.");
            else { System.out.println("History (newest first):"); sessions.forEach(s -> System.out.println("  " + s.getFileName())); }
            return;
        }

        if (loadHistoryFile != null) {
            try {
                for (var msg : historyStore.load(loadHistoryFile)) {
                    if ("user".equals(msg.role())) conversationManager.addUserMessage(msg.content());
                    else if ("assistant".equals(msg.role())) conversationManager.addAssistantMessage(msg.content(), msg.thinking());
                    else conversationManager.addMessage(msg);
                }
                System.err.println("Loaded: " + loadHistoryFile + " (" + conversationManager.size() + " messages)");
            } catch (IOException e) { System.err.println("Load failed: " + e.getMessage()); }
        }

        System.err.println("MewCode v0.2.0 (Spring Boot)");
        System.err.println("Protocol: " + props.getProvider().getProtocol());
        System.err.println("Model: " + props.getProvider().getModel());

        var cli = new MewCodeCli(provider, conversationManager, historyStore,
            props.getProvider().getModel(), toolRegistry, agentLoopProvider,
            props.getAgent(), planOnly);

        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            cli.stop();
            try { cli.saveHistory(); } catch (IOException e) { System.err.println("Save failed: " + e.getMessage()); }
        }));

        cli.start();
        cli.saveHistory();
    }

    private void printHelp() {
        System.out.println("""
            MewCode — terminal AI assistant

            Usage: mewcode [options]

            Options:
              --history <file>    Load a history session
              --list-history      List all history sessions
              --plan-only          Read-only mode (write tools blocked)
              --help, -h           Show this help

            Config: application.yml in working directory or via env vars.
            """);
    }
}

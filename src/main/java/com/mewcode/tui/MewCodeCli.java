package com.mewcode.tui;

import com.mewcode.agent.AgentEventListener;
import com.mewcode.agent.AgentLoop;
import com.mewcode.config.MewCodeProperties;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.HistoryStore;
import com.mewcode.provider.LLMProvider;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;

public class MewCodeCli {

    private static final String RST   = "[0m";
    private static final String DIM   = "[2m";
    private static final String BOLD  = "[1m";
    private static final String BLUE  = "[34m";
    private static final String GRAY  = "[90m";
    private static final String RED   = "[31m";
    private static final String YLW   = "[33m";
    private static final String CYAN  = "[36m";

    private static final String PROMPT     = "⏵ ";
    private static final String THINK_MARK = "⏺ Thinking…";
    private static final String WAITING    = "⏺ Thinking…";
    private static final String ERR_MARK   = "✖";
    private static final String SYS_MARK   = "●";
    private static final String TOOL_MARK  = "⚙";

    private final LLMProvider provider;
    private final ConversationManager conversationManager;
    private final HistoryStore historyStore;
    private final String modelName;
    private final ToolRegistry toolRegistry;
    private final ObjectProvider<AgentLoop> agentLoopProvider;
    private final MewCodeProperties.Agent agentConfig;
    private boolean planOnly;

    private volatile boolean running = true;
    private volatile AgentLoop currentLoop = null;
    private volatile boolean streaming = false;
    private boolean firstPrompt = true;

    private Terminal terminal;
    private PrintWriter out;
    private LineReader reader;

    public MewCodeCli(LLMProvider provider, ConversationManager conversationManager,
                      HistoryStore historyStore, String modelName, ToolRegistry toolRegistry,
                      ObjectProvider<AgentLoop> agentLoopProvider,
                      MewCodeProperties.Agent agentConfig, boolean planOnly) {
        this.provider = provider;
        this.conversationManager = conversationManager;
        this.historyStore = historyStore;
        this.modelName = modelName;
        this.toolRegistry = toolRegistry;
        this.agentLoopProvider = agentLoopProvider;
        this.agentConfig = agentConfig;
        this.planOnly = planOnly;
    }

    // ═══════════════════════════════════════════════════════
    //  Start
    // ═══════════════════════════════════════════════════════

    public void start() throws IOException {
        terminal = TerminalBuilder.builder().system(true).jansi(false).build();
        out = terminal.writer();
        reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .variable(LineReader.SECONDARY_PROMPT_PATTERN, DIM + "   " + RST)
            .build();

        printWelcome();
        restoreHistory();
        out.flush();

        while (running) {
            try {
                String input = readMultiLine();
                if (input == null || input.isBlank()) continue;
                handleInput(input.strip());
            } catch (UserInterruptException e) {
                if (streaming && currentLoop != null) {
                    currentLoop.cancel();
                    out.println();
                    out.println(YLW + "  Interrupted" + RST);
                } else {
                    out.println();
                    out.println(YLW + "  Press Ctrl+C again to exit" + RST);
                }
                out.flush();
            } catch (EndOfFileException e) { running = false; }
        }
        out.println(DIM + "  Goodbye" + RST);
        out.flush();
        terminal.close();
    }

    private void printWelcome() {
        out.println(DIM + "  MewCode  ·  " + modelName
            + (planOnly ? "  [plan-only]" : "") + RST);
        out.flush();
    }

    private void restoreHistory() {
        var messages = conversationManager.getMessages();
        if (messages.isEmpty()) return;
        sep();
        out.println(DIM + "  Restored " + messages.size() + " messages" + RST);
        for (var msg : messages) {
            if ("user".equals(msg.role())) {
                out.println(BLUE + PROMPT + msg.content() + RST);
            } else if ("assistant".equals(msg.role())) {
                if (msg.content() != null && msg.content().contains("\"tool_use\"")) {
                    out.println(DIM + "  [tool call]" + RST);
                } else {
                    if (msg.thinking() != null && !msg.thinking().isBlank()) {
                        out.print(DIM + "  " + THINK_MARK + "  ");
                        String firstLine = msg.thinking().split("\n")[0];
                        int maxW = Math.max(40, terminal.getWidth() - 22);
                        out.print(firstLine.length() > maxW ? firstLine.substring(0, maxW) + "…" : firstLine);
                        out.println(RST);
                    }
                    out.print("  ⏺ ");
                    out.println(msg.content());
                }
            } else if ("tool".equals(msg.role())) {
                out.println(DIM + "  " + truncate(msg.content(), 80) + RST);
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Input
    // ═══════════════════════════════════════════════════════

    private void sep() {
        out.print(DIM + "  ⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯" + RST);
    }

    private String readMultiLine() {
        if (!firstPrompt) sep(); else firstPrompt = false;
        out.println();
        StringBuilder sb = new StringBuilder();
        String prompt = BLUE + PROMPT + RST;
        while (true) {
            String line = reader.readLine(prompt);
            if (line == null) return null;
            if (line.endsWith("\\")) {
                sb.append(line, 0, line.length() - 1).append("\n");
                prompt = DIM + "   " + RST;
            } else { sb.append(line); break; }
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════
    //  Handle input — delegates to AgentLoop
    // ═══════════════════════════════════════════════════════

    private void handleInput(String text) {
        if (handleCommand(text)) return;

        out.print(DIM + "  " + WAITING + RST);
        out.flush();
        streaming = true;

        var md = new MarkdownRenderer();
        var lineBuf = new StringBuilder();
        var thinkingShown = new AtomicBoolean(false);
        var textStarted = new AtomicBoolean(false);

        var loop = agentLoopProvider.getObject();
        loop.setPlanOnly(planOnly);
        currentLoop = loop;

        loop.run(text, new AgentEventListener() {
            @Override
            public void onThinking(String t) {
                if (!thinkingShown.getAndSet(true)) {
                    clearWaitingLine();
                    out.print(DIM + "  " + THINK_MARK + "  " + RST);
                }
                out.print(DIM + t + RST);
                out.flush();
            }

            @Override
            public void onTextDelta(String t) {
                if (thinkingShown.get() && textStarted.compareAndSet(false, true)) {
                    out.println(RST);
                }
                if (!textStarted.get() && thinkingShown.compareAndSet(false, true)) {
                    // no thinking was shown, this is first output
                    clearWaitingLine();
                }
                lineBuf.append(t);
                String buf = lineBuf.toString();
                int lastNL = buf.lastIndexOf('\n');
                if (lastNL >= 0) {
                    for (String line : buf.substring(0, lastNL + 1).split("\n", -1)) {
                        out.println(md.renderLine(line));
                    }
                    lineBuf.setLength(0);
                    lineBuf.append(buf.substring(lastNL + 1));
                }
                out.flush();
            }

            @Override
            public void onToolCallStart(String id, String name) {
                if (thinkingShown.get()) out.println(RST);
                out.println(DIM + "  ⚙ " + BOLD + name + RST);
                out.flush();
            }

            @Override
            public void onToolResult(String id, String name, ToolResult result) {
                if (result.success()) {
                    out.println(DIM + "  ← " + truncate(result.content(), 100) + RST);
                } else {
                    out.println(RED + "  ✖ " + result.error() + RST);
                }
                out.flush();
            }

            @Override
            public void onError(String message) {
                out.println(RED + "  ✖ " + message + RST);
                out.flush();
            }

            @Override
            public void onDone() {
                // Flush remaining line buffer
                if (!lineBuf.isEmpty()) {
                    out.println(md.renderLine(lineBuf.toString()));
                }
                out.println(RST);
                out.flush();
            }
        });

        streaming = false;
        currentLoop = null;
    }

    private void clearWaitingLine() { out.print("\r" + " ".repeat(40) + "\r"); out.flush(); }

    // ═══════════════════════════════════════════════════════
    //  Commands
    // ═══════════════════════════════════════════════════════

    private boolean handleCommand(String text) {
        switch (text) {
            case "/exit" -> { sep(); out.println(DIM + SYS_MARK + " 保存中..." + RST); running = false; return true; }
            case "/help" -> {
                sep();
                out.println(DIM + "  ═══ Help ═══" + RST);
                out.println("  " + BLUE + PROMPT + "message" + RST + "    Enter to send, \\ to continue");
                out.println("  " + DIM + "Ctrl+C" + RST + "       Interrupt");
                out.println("  " + YLW + "/exit" + RST + "        Save & exit");
                out.println("  " + YLW + "/help" + RST + "        Help");
                out.println("  " + YLW + "/history" + RST + "     Message count");
                out.println("  " + YLW + "/clear" + RST + "       Clear session");
                out.println("  " + YLW + "/plan-only" + RST + "   Toggle plan-only mode");
                return true;
            }
            case "/history" -> { sep(); out.println(YLW + SYS_MARK + " 当前 " + conversationManager.size() + " 条消息" + RST); return true; }
            case "/clear" -> { conversationManager.clear(); sep(); out.println(YLW + SYS_MARK + " 对话已清空" + RST); return true; }
            case "/plan-only" -> {
                planOnly = !planOnly;
                sep();
                out.println(DIM + "  Plan-only: " + (planOnly ? "ON" : "OFF") + RST);
                return true;
            }
            default -> { return false; }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Utils
    // ═══════════════════════════════════════════════════════

    private static String center(String s, int width) {
        int pad = Math.max(0, width - visibleLen(s));
        return " ".repeat(pad / 2) + s + " ".repeat(pad - pad / 2);
    }

    private static int visibleLen(String s) {
        return s.replaceAll("\\[[0-9;]*m", "").replaceAll("[^\\x00-\\x7F]", "  ").length();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.replace("\n", " ");
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    public void saveHistory() throws IOException {
        var messages = conversationManager.getMessages();
        if (!messages.isEmpty()) {
            var path = historyStore.save(messages);
            out.println(DIM + SYS_MARK + " 已保存 " + messages.size() + " 条 → " + path.getFileName() + RST);
        }
    }
    public boolean isRunning() { return running; }
    public void stop() { running = false; }
}

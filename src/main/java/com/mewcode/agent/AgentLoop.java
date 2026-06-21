package com.mewcode.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.config.MewCodeProperties;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.provider.LLMProvider;
import com.mewcode.provider.Message;
import com.mewcode.provider.StreamChunk;
import com.mewcode.prompt.InjectionBuilder;
import com.mewcode.security.SecurityManager;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class AgentLoop {

    private static final Set<String> READ_TOOLS = Set.of("read_file", "glob", "grep");

    private final LLMProvider provider;
    private final ConversationManager conversationManager;
    private final ToolRegistry toolRegistry;
    private final MewCodeProperties.Agent agentConfig;
    private boolean planOnly;
    private final InjectionBuilder injectionBuilder;
    private final SecurityManager securityManager;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * @param provider       injected LLM provider
     * @param conversationManager injected conversation state
     * @param toolRegistry   injected tool registry
     * @param props          mewcode properties
     * @param planOnly       runtime flag (settable per invocation)
     */
    public AgentLoop(LLMProvider provider, ConversationManager conversationManager,
                     InjectionBuilder injectionBuilder, SecurityManager securityManager,
                     ToolRegistry toolRegistry, MewCodeProperties props) {
        this.provider = provider;
        this.conversationManager = conversationManager;
        this.toolRegistry = toolRegistry;
        this.agentConfig = props.getAgent();
        this.planOnly = false;
        this.injectionBuilder = injectionBuilder;
        this.securityManager = securityManager;
    }

    public void setPlanOnly(boolean planOnly) { this.planOnly = planOnly; }

    public void cancel() { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }

    public void run(String userMessage, AgentEventListener listener) {
        cancelled.set(false);
        conversationManager.addUserMessage(userMessage);

        boolean useOpenAIFormat = provider.toolFormat() == LLMProvider.ToolFormat.OPENAI;
        List<Map<String, Object>> tools = buildToolList(useOpenAIFormat);

        for (int round = 0; round < agentConfig.getMaxRounds(); round++) {
            if (cancelled.get()) { listener.onError("Cancelled by user"); return; }

            var fullHistory = new ArrayList<>(conversationManager.getHistoryForRequest());
            Message triggerMsg = (round == 0) ? popLastUserMessage(fullHistory) : Message.user("");
            if (triggerMsg == null) triggerMsg = Message.user("");

            // Per-turn injection (plan-only reminder, etc.)
            var injection = injectionBuilder.buildPlanOnlyInjection(round, planOnly);
            if (injection != null) fullHistory.add(injection);

            var collector = new ToolCallCollector(listener);
            try {
                provider.streamChat(fullHistory, triggerMsg, tools).forEach(chunk -> {
                    if (cancelled.get()) return;
                    collector.consume(chunk);
                });
            } catch (Exception e) {
                listener.onError("Stream error: " + e.getMessage());
                return;
            }

            if (cancelled.get()) { listener.onError("Cancelled by user"); return; }

            if (!collector.textBuf.isEmpty()) {
                conversationManager.addAssistantMessage(collector.textBuf.toString(),
                    collector.thinkingBuf.isEmpty() ? null : collector.thinkingBuf.toString());
            }

            if (collector.toolCalls.isEmpty()) { listener.onDone(); return; }

            // Classify reads vs writes
            var reads = new ArrayList<ToolCallAccumulator>();
            var writes = new ArrayList<ToolCallAccumulator>();
            for (var tc : collector.toolCalls) {
                if (READ_TOOLS.contains(tc.name)) reads.add(tc); else writes.add(tc);
            }

            // Plan-only: block writes
            if (planOnly && !writes.isEmpty()) {
                for (var tc : writes) {
                    tc.result = ToolResult.fail("Plan-only mode: write tool '" + tc.name
                        + "' is blocked. Describe what you would do instead.");
                    listener.onToolResult(tc.id, tc.name, tc.result);
                }
                executeReadsParallel(reads, listener);
                batchFeedback(collector.toolCalls);
                emitPlanResponse(listener);
                listener.onDone();
                return;
            }

            executeReadsParallel(reads, listener);
            for (var tc : writes) {
                if (cancelled.get()) break;
                executeOneTool(tc, listener);
            }

            if (cancelled.get()) { listener.onError("Cancelled during tool execution"); return; }

            batchFeedback(collector.toolCalls);
            listener.onRoundEnd(round);
        }

        listener.onError("Max rounds (" + agentConfig.getMaxRounds() + ") reached");
    }

    private List<Map<String, Object>> buildToolList(boolean useOpenAIFormat) {
        if (!agentConfig.isIncludeTools() || toolRegistry.isEmpty()) return List.of();
        return useOpenAIFormat ? toolRegistry.toOpenAIFormat() : toolRegistry.toAnthropicFormat();
    }

    private void executeReadsParallel(List<ToolCallAccumulator> reads, AgentEventListener listener) {
        if (reads.isEmpty()) return;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = reads.stream()
                .map(tc -> executor.submit(() -> executeOneToolSync(tc, listener))).toList();
            for (var f : futures) { try { f.get(); } catch (Exception ignored) {} }
        }
    }

    private ToolResult executeOneToolSync(ToolCallAccumulator tc, AgentEventListener listener) {
        // Security check
        String value = extractValue(tc);
        var decision = securityManager.check(tc.name, value);
        if (decision == com.mewcode.security.SecurityRule.Action.DENY) {
            ToolResult denied = ToolResult.fail("Security denied: " + securityManager.lastReason());
            tc.result = denied;
            listener.onToolResult(tc.id, tc.name, denied);
            return denied;
        }
        if (decision == com.mewcode.security.SecurityRule.Action.ASK) {
            var answer = listener.onToolAsk(tc.name, value);
            if (answer == AgentEventListener.AskDecision.DENY) {
                ToolResult denied = ToolResult.fail("User denied the operation");
                tc.result = denied;
                listener.onToolResult(tc.id, tc.name, denied);
                return denied;
            }
            // ALLOW or SAVE — proceed
            if (answer == AgentEventListener.AskDecision.SAVE) {
                try {
                    String tier = "project"; // save to project by default
                    securityManager.savePermanent(
                        com.mewcode.security.SecurityRule.allow(tc.name, "*", tier), tier);
                } catch (Exception ignored) {}
            }
        }

        var tool = toolRegistry.get(tc.name);
        ToolResult result = tool != null ? tool.execute(tc.args != null ? tc.args : Map.of())
            : ToolResult.fail("Unknown tool: " + tc.name);
        tc.result = result;
        listener.onToolResult(tc.id, tc.name, result);
        return result;
    }

    /** Extract the security-relevant value from tool args. */
    private String extractValue(ToolCallAccumulator tc) {
        if (tc.args == null || tc.args.isEmpty()) return "";
        if ("bash".equals(tc.name)) return (String) tc.args.getOrDefault("command", "");
        return (String) tc.args.getOrDefault("path", "");
    }

    private void executeOneTool(ToolCallAccumulator tc, AgentEventListener listener) {
        executeOneToolSync(tc, listener);
    }

    @SuppressWarnings("unchecked")
    private void batchFeedback(List<ToolCallAccumulator> toolCalls) {
        var blocks = new ArrayList<Map<String, Object>>();
        for (var tc : toolCalls) {
            var block = new LinkedHashMap<String, Object>();
            block.put("type", "tool_use");
            block.put("id", tc.id);
            block.put("name", tc.name);
            block.put("input", tc.args != null ? tc.args : Map.of());
            blocks.add(block);
        }
        try {
            conversationManager.addMessage(Message.toolUse(json.writeValueAsString(blocks)));
        } catch (JsonProcessingException e) {
            conversationManager.addMessage(Message.assistant("[tool_use batch: " + toolCalls.size() + " calls]"));
        }
        for (var tc : toolCalls) {
            ToolResult r = tc.result != null ? tc.result : ToolResult.fail("not executed");
            conversationManager.addMessage(Message.toolResult(tc.id,
                r.success() ? r.content() : "Error: " + r.error()));
        }
    }

    private void emitPlanResponse(AgentEventListener listener) {
        var fullHistory = new ArrayList<>(conversationManager.getHistoryForRequest());
        try {
            provider.streamChat(fullHistory, Message.user(""), List.of()).forEach(chunk -> {
                if (cancelled.get()) return;
                switch (chunk.type()) {
                    case TEXT -> listener.onTextDelta(chunk.content());
                    case THINKING -> listener.onThinking(chunk.content());
                    case ERROR -> listener.onError(chunk.content());
                    default -> {}
                }
            });
        } catch (Exception e) { listener.onError("Plan response error: " + e.getMessage()); }
    }

    private Message popLastUserMessage(ArrayList<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) return messages.remove(i);
        }
        return null;
    }

    // ── Inner classes ──────────────────────────────────────

    private static class ToolCallCollector {
        final StringBuilder textBuf = new StringBuilder();
        final StringBuilder thinkingBuf = new StringBuilder();
        final List<ToolCallAccumulator> toolCalls = new ArrayList<>();
        final ObjectMapper json = new ObjectMapper();
        final AgentEventListener listener;
        ToolCallAccumulator currentTc;

        ToolCallCollector(AgentEventListener listener) { this.listener = listener; }

        void consume(StreamChunk chunk) {
            switch (chunk.type()) {
                case TEXT -> { textBuf.append(chunk.content()); listener.onTextDelta(chunk.content()); }
                case THINKING -> { thinkingBuf.append(chunk.content()); listener.onThinking(chunk.content()); }
                case TOOL_CALL_START -> {
                    try {
                        @SuppressWarnings("unchecked")
                        var data = json.readValue(chunk.content(), Map.class);
                        currentTc = new ToolCallAccumulator();
                        currentTc.id = (String) data.get("id");
                        currentTc.name = (String) data.get("name");
                        toolCalls.add(currentTc);
                        listener.onToolCallStart(currentTc.id, currentTc.name);
                    } catch (JsonProcessingException ignored) {}
                }
                case TOOL_CALL_DELTA -> {
                    if (currentTc != null) {
                        currentTc.argsFragments.append(chunk.content());
                        try { currentTc.args = json.readValue(currentTc.argsFragments.toString(), Map.class); }
                        catch (JsonProcessingException e) { /* still incomplete */ }
                    }
                }
                case TOOL_CALL_END -> {
                    if (currentTc != null && currentTc.args == null) {
                        try { currentTc.args = json.readValue(currentTc.argsFragments.toString(), Map.class); }
                        catch (JsonProcessingException e) { currentTc.args = Map.of(); }
                    }
                    currentTc = null;
                }
                case ERROR -> listener.onError(chunk.content());
                case DONE -> {}
            }
        }
    }

    static class ToolCallAccumulator {
        String id, name;
        final StringBuilder argsFragments = new StringBuilder();
        Map<String, Object> args;
        ToolResult result;
    }
}

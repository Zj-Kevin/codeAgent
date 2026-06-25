package com.mewcode.security;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Five-layer security review: path sandbox → blacklist → mode override → rule table → fallback to ASK.
 */
@Component
public class SecurityManager {

    public enum Mode { STRICT, DEFAULT, PERMISSIVE }

    private Mode mode = Mode.DEFAULT;
    private final PathSandbox sandbox;
    private final DangerousCommandDetector cmdDetector = new DangerousCommandDetector();
    private final PermissionStore store = new PermissionStore();

    // Rule tiers: session > project > global
    private final List<SecurityRule> sessionRules = new ArrayList<>();
    private final List<SecurityRule> projectRules;
    private final List<SecurityRule> globalRules;

    private final Path projectPermissionsFile;
    private final Path globalPermissionsFile;

    // ── Tool categories (for SecurityManager to classify tools) ─
    private final ConcurrentHashMap<String, Set<String>> toolCategories = new ConcurrentHashMap<>();

    // Built-in tool categories
    private static final Set<String> FILE_TOOLS = Set.of("read_file", "write_file", "edit_file", "glob", "grep");
    private static final Set<String> CAT_FILE = Set.of("file");
    private static final Set<String> CAT_FILE_AND_WRITE = Set.of("file", "write");
    private static final Set<String> CAT_WRITE = Set.of("write");
    private static final Set<String> CAT_READ_ONLY = Set.of("file", "read-only");

    public SecurityManager() {
        this(Path.of("").toAbsolutePath());
    }

    public SecurityManager(Path cwd) {
        this.sandbox = new PathSandbox(cwd);
        this.projectPermissionsFile = cwd.resolve(".mewcode/permissions.json").normalize();
        this.globalPermissionsFile = Path.of(System.getProperty("user.home"),
            ".mewcode/permissions.json");
        this.projectRules = store.load(projectPermissionsFile);
        this.globalRules = store.load(globalPermissionsFile);

        // Register built-in categories
        toolCategories.put("read_file", CAT_READ_ONLY);
        toolCategories.put("glob", CAT_READ_ONLY);
        toolCategories.put("grep", CAT_READ_ONLY);
        toolCategories.put("write_file", CAT_FILE_AND_WRITE);
        toolCategories.put("edit_file", CAT_FILE_AND_WRITE);
        toolCategories.put("bash", CAT_WRITE);
    }

    // ── Public API ──────────────────────────────────────────

    /** Review a tool call. Returns ALLOW / DENY / ASK. */
    public SecurityRule.Action check(String tool, String value) {
        // Layer 1: path sandbox (for file tools)
        if (isFileTool(tool) && value != null) {
            String rejection = sandbox.check(value);
            if (!rejection.isEmpty()) {
                lastReason = rejection;
                return SecurityRule.Action.DENY;
            }
        }

        // Layer 2: dangerous command blacklist (for bash)
        if ("bash".equals(tool) && value != null) {
            var result = cmdDetector.check(value);
            if (result.level() == DangerousCommandDetector.Level.HIGH) {
                lastReason = "Dangerous command (HIGH): " + result.description();
                return SecurityRule.Action.DENY;
            }
            if (result.level() == DangerousCommandDetector.Level.MEDIUM) {
                lastReason = "Potentially dangerous command (MEDIUM): " + result.description();
                return SecurityRule.Action.ASK; // force ask for medium
            }
            // LOW and SAFE fall through to rule table
        }

        // Layer 3: mode override
        if (mode == Mode.STRICT && isWriteTool(tool)) {
            return SecurityRule.Action.ASK;
        }

        // Layer 4: rule table
        SecurityRule.Action ruleAction = matchRule(tool, value);
        if (ruleAction != null) return ruleAction;

        // Layer 5: fallback
        if (mode == Mode.PERMISSIVE) return SecurityRule.Action.ALLOW;
        return SecurityRule.Action.ASK;
    }

    private String lastReason = "";

    public String lastReason() { return lastReason; }

    /** Add a session-temporary rule (highest priority). */
    public void addSessionRule(SecurityRule rule) {
        sessionRules.add(0, rule);
    }

    /** Permanently save a rule to the appropriate tier. */
    public void savePermanent(SecurityRule rule, String tier) throws Exception {
        Path file = "global".equals(tier) ? globalPermissionsFile : projectPermissionsFile;
        store.save(file, new SecurityRule(rule.tool(), rule.pattern(), rule.action(), tier));
        if ("global".equals(tier)) globalRules.add(0, rule);
        else projectRules.add(0, rule);
    }

    /** List all currently active rules with their sources. */
    public List<SecurityRule> activeRules() {
        var all = new ArrayList<SecurityRule>();
        all.addAll(sessionRules);
        all.addAll(projectRules);
        all.addAll(globalRules);
        return all;
    }

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }

    // ── Tool category management ────────────────────────────

    /** Register categories for a tool (e.g. from MCP annotations). */
    public void registerToolCategories(String toolName, Set<String> cats) {
        if (cats != null && !cats.isEmpty()) {
            toolCategories.put(toolName, cats);
        }
    }

    /** Remove tool category mapping. */
    public void unregisterTool(String toolName) {
        toolCategories.remove(toolName);
    }

    // ── Internals ───────────────────────────────────────────

    private SecurityRule.Action matchRule(String tool, String value) {
        for (var r : sessionRules) if (r.matches(tool, value)) return r.action();
        for (var r : projectRules) if (r.matches(tool, value)) return r.action();
        for (var r : globalRules) if (r.matches(tool, value)) return r.action();
        return null; // no match
    }

    private boolean isFileTool(String tool) {
        Set<String> cats = toolCategories.get(tool);
        return cats != null && cats.contains("file");
    }

    private boolean isWriteTool(String tool) {
        Set<String> cats = toolCategories.get(tool);
        return cats != null && cats.contains("write");
    }
}

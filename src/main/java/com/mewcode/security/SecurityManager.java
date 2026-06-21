package com.mewcode.security;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Four-layer security review: mode override → blacklist → rule table → fallback to ASK.
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

    // ── Internals ───────────────────────────────────────────

    private SecurityRule.Action matchRule(String tool, String value) {
        for (var r : sessionRules) if (r.matches(tool, value)) return r.action();
        for (var r : projectRules) if (r.matches(tool, value)) return r.action();
        for (var r : globalRules) if (r.matches(tool, value)) return r.action();
        return null; // no match
    }

    private static boolean isFileTool(String tool) {
        return "read_file".equals(tool) || "write_file".equals(tool)
            || "edit_file".equals(tool) || "glob".equals(tool);
    }

    private static boolean isWriteTool(String tool) {
        return "write_file".equals(tool) || "edit_file".equals(tool) || "bash".equals(tool);
    }
}

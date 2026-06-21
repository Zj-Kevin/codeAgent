package com.mewcode.security;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Restricts file tool operations to allowed directories.
 */
public class PathSandbox {

    private final List<Path> allowedPaths = new ArrayList<>();

    public PathSandbox(Path cwd) {
        allowedPaths.add(cwd.toAbsolutePath().normalize());
        allowedPaths.add(Path.of(System.getProperty("user.home"), ".mewcode")
            .toAbsolutePath().normalize());
    }

    /** Add a path to the whitelist. */
    public void addAllowedPath(String path) {
        allowedPaths.add(Path.of(path).toAbsolutePath().normalize());
    }

    /**
     * Check if a path is within any allowed directory.
     * @return empty string if allowed, or a rejection reason
     */
    public String check(Path target) {
        Path resolved = target.toAbsolutePath().normalize();

        for (Path allowed : allowedPaths) {
            if (resolved.startsWith(allowed)) {
                return ""; // allowed
            }
        }
        return "Path '" + target + "' is outside allowed directories: " + allowedPaths;
    }

    /** Convenience: check a string path. */
    public String check(String pathStr) {
        return check(Path.of(pathStr));
    }
}

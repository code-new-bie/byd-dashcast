package com.byd.dashcast.phase1;

import java.util.Objects;

/** Immutable source-display selection; the instrument transport is unchanged. */
final class ProjectionTarget {
    private static final String PRIMARY_TOKEN = "primary";
    private static final String REMOTE_PREFIX = "remote:";
    private static final int MAX_COMPONENT_CHARS = 384;
    private static final int MAX_LABEL_CHARS = 128;

    private final String component;
    private final String label;

    private ProjectionTarget(String component, String label) {
        this.component = component;
        this.label = label;
    }

    static ProjectionTarget primary() {
        return new ProjectionTarget(null, "车机主屏（现有链路）");
    }

    static ProjectionTarget remote(String packageName, String activityName, String label) {
        if (packageName == null || activityName == null) {
            throw new IllegalArgumentException("Remote component is required");
        }
        return remoteComponent(packageName + "/" + activityName, label);
    }

    static ProjectionTarget remoteComponent(String componentName, String label) {
        validateComponent(componentName);
        String safeLabel = label == null || label.trim().isEmpty()
                ? componentName
                : label.trim();
        if (safeLabel.length() > MAX_LABEL_CHARS) {
            safeLabel = safeLabel.substring(0, MAX_LABEL_CHARS);
        }
        return new ProjectionTarget(componentName, safeLabel);
    }

    static ProjectionTarget parse(String token) {
        if (PRIMARY_TOKEN.equals(token)) {
            return primary();
        }
        if (token == null || !token.startsWith(REMOTE_PREFIX)) {
            throw new IllegalArgumentException("Unknown projection target");
        }
        return remoteComponent(token.substring(REMOTE_PREFIX.length()), null);
    }

    /** Parses the target suffix used by the controlled shell helper. */
    static ProjectionTarget fromShellArguments(String[] args) {
        if (args == null || args.length < 1) {
            throw new IllegalArgumentException("Missing shell capture arguments");
        }
        if (args.length > 1 && "remote".equals(args[1])) {
            if (args.length < 3) {
                throw new IllegalArgumentException("Remote display component is required");
            }
            return remoteComponent(args[2], null);
        }
        if (args.length > 2 && "remote".equals(args[2])) {
            if (args.length < 4) {
                throw new IllegalArgumentException("Remote display component is required");
            }
            return remoteComponent(args[3], null);
        }
        return primary();
    }

    boolean isRemote() {
        return component != null;
    }

    String component() {
        if (!isRemote()) {
            throw new IllegalStateException("Primary display has no application component");
        }
        return component;
    }

    String displayLabel() {
        return label;
    }

    String token() {
        return isRemote() ? REMOTE_PREFIX + component : PRIMARY_TOKEN;
    }

    /** Emits only fixed words and a previously allowlisted component. */
    String shellArguments() {
        if (!isRemote()) {
            return "";
        }
        return "remote '" + component.replace("'", "'\\''") + "'";
    }

    @Override
    public String toString() {
        return token();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProjectionTarget)) {
            return false;
        }
        ProjectionTarget that = (ProjectionTarget) other;
        return Objects.equals(component, that.component);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(component);
    }

    private static void validateComponent(String componentName) {
        if (componentName == null || componentName.isEmpty()
                || componentName.length() > MAX_COMPONENT_CHARS) {
            throw new IllegalArgumentException("Invalid remote display component");
        }
        int separator = componentName.indexOf('/');
        if (separator < 1 || separator == componentName.length() - 1
                || componentName.indexOf('/', separator + 1) >= 0) {
            throw new IllegalArgumentException("Expected package/activity component");
        }
        for (int index = 0; index < componentName.length(); index++) {
            char value = componentName.charAt(index);
            if (!(value == '/' || value == '.' || value == '$' || value == '_'
                    || value >= 'a' && value <= 'z'
                    || value >= 'A' && value <= 'Z'
                    || value >= '0' && value <= '9')) {
                throw new IllegalArgumentException("Remote component contains an unsafe character");
            }
        }
    }
}

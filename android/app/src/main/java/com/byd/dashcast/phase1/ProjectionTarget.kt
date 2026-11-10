package com.byd.dashcast.phase1

import java.util.Objects

/** Immutable source-display selection; the instrument transport is unchanged. */
internal class ProjectionTarget private constructor(
    private val componentName: String?,
    private val label: String,
    val isNamed: Boolean = false,
    val isWorkspace: Boolean = false,
) {
    val isRemote: Boolean
        get() = componentName != null

    fun component(): String =
        componentName ?: throw IllegalStateException("Primary display has no application component")

    fun displayLabel(): String = label

    fun token(): String = when {
        isNamed -> "named"
        isRemote -> (if (isWorkspace) WORKSPACE_PREFIX else REMOTE_PREFIX) + componentName
        else -> PRIMARY_TOKEN
    }

    /** Emits only fixed words and a previously allowlisted component. */
    fun shellArguments(): String {
        if (isNamed) {
            return "named"
        }
        val component = componentName ?: return ""
        return (if (isWorkspace) "workspace '" else "remote '") + component.replace("'", "'\\''") + "'"
    }

    override fun toString(): String = token()

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ProjectionTarget) {
            return false
        }
        return isNamed == other.isNamed && isWorkspace == other.isWorkspace && componentName == other.componentName
    }

    override fun hashCode(): Int = Objects.hash(componentName, isNamed, isWorkspace)

    companion object {
        private const val PRIMARY_TOKEN = "primary"
        private const val REMOTE_PREFIX = "remote:"
        private const val WORKSPACE_PREFIX = "workspace:"
        private const val MAX_COMPONENT_CHARS = 384
        private const val MAX_LABEL_CHARS = 128

        @JvmStatic
        fun namedDisplay(): ProjectionTarget = ProjectionTarget(null, "按屏幕名接入", true)

        @JvmStatic
        fun workspaceComponent(componentName: String?, label: String?): ProjectionTarget {
            val checked = remoteComponent(componentName, label)
            return ProjectionTarget(checked.componentName, checked.label, false, true)
        }

        @JvmStatic
        fun primary(): ProjectionTarget = ProjectionTarget(null, "车机主屏（现有链路）")

        @JvmStatic
        fun remote(packageName: String?, activityName: String?, label: String?): ProjectionTarget {
            if (packageName == null || activityName == null) {
                throw IllegalArgumentException("Remote component is required")
            }
            return remoteComponent("$packageName/$activityName", label)
        }

        @JvmStatic
        fun remoteComponent(componentName: String?, label: String?): ProjectionTarget {
            validateComponent(componentName)
            componentName!!
            var safeLabel = if (label == null || label.trim().isEmpty()) componentName else label.trim()
            if (safeLabel.length > MAX_LABEL_CHARS) {
                safeLabel = safeLabel.substring(0, MAX_LABEL_CHARS)
            }
            return ProjectionTarget(componentName, safeLabel)
        }

        @JvmStatic
        fun parse(token: String?): ProjectionTarget {
            if ("named" == token) {
                return namedDisplay()
            }
            if (PRIMARY_TOKEN == token) {
                return primary()
            }
            if (token != null && token.startsWith(WORKSPACE_PREFIX)) {
                return workspaceComponent(token.substring(WORKSPACE_PREFIX.length), null)
            }
            if (token == null || !token.startsWith(REMOTE_PREFIX)) {
                throw IllegalArgumentException("Unknown projection target")
            }
            return remoteComponent(token.substring(REMOTE_PREFIX.length), null)
        }

        /** Parses the target suffix used by the controlled shell helper. */
        @JvmStatic
        fun fromShellArguments(args: Array<String>?): ProjectionTarget {
            if (args == null || args.isEmpty()) {
                throw IllegalArgumentException("Missing shell capture arguments")
            }
            if (args.size > 2 && "live" == args[1] && "named" == args[2]) {
                return namedDisplay()
            }
            if (args.size > 1 && ("remote" == args[1] || "workspace" == args[1])) {
                if (args.size != 3) {
                    throw IllegalArgumentException("Remote display component is required")
                }
                return if ("workspace" == args[1]) workspaceComponent(args[2], null) else remoteComponent(args[2], null)
            }
            if (args.size > 2 && "live" == args[1] && ("remote" == args[2] || "workspace" == args[2])) {
                if (args.size < 4) {
                    throw IllegalArgumentException("Remote display component is required")
                }
                return if ("workspace" == args[2]) workspaceComponent(args[3], null) else remoteComponent(args[3], null)
            }
            if (args.size > 1 && "live" != args[1]) {
                throw IllegalArgumentException("Unknown shell capture source prefix")
            }
            return primary()
        }

        private fun validateComponent(componentName: String?) {
            if (componentName == null || componentName.isEmpty() || componentName.length > MAX_COMPONENT_CHARS) {
                throw IllegalArgumentException("Invalid remote display component")
            }
            val separator = componentName.indexOf('/')
            if (separator < 1 || separator == componentName.length - 1 || componentName.indexOf('/', separator + 1) >= 0) {
                throw IllegalArgumentException("Expected package/activity component")
            }
            for (value in componentName) {
                if (!(value == '/' || value == '.' || value == '$' || value == '_' ||
                        value in 'a'..'z' || value in 'A'..'Z' || value in '0'..'9')) {
                    throw IllegalArgumentException("Remote component contains an unsafe character")
                }
            }
        }
    }
}

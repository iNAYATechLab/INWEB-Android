package com.inweb.app.services

/**
 * Enumerates every long-running native service INWEB can supervise.
 *
 * Each service is independently start/stoppable from the Services dashboard,
 * and each broadcasts its own state via [ServiceState] so the UI can render
 * per-service tiles (running / stopped / error).
 */
enum class ServiceType(
    val id: String,
    val displayName: String,
    val defaultPort: Int
) {
    NGINX   ("nginx",   "Nginx",    8080),
    PHP_FPM ("php-fpm", "PHP-FPM",  9000),
    MYSQL   ("mysql",   "MariaDB",  3306);

    companion object {
        fun fromId(id: String?): ServiceType? = entries.firstOrNull { it.id == id }
    }
}

/** Machine state for a single service. */
enum class ServiceStatus { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

/** Snapshot broadcast by [ServerService] whenever anything changes. */
data class ServiceState(
    val type: ServiceType,
    val status: ServiceStatus,
    val message: String? = null,
    val pid: Long? = null
)

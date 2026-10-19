package dev.pocketpaas.domain.services

/**
 * Termux içinde kurulabilir sunucu servisleri. Komutlar argv listeleridir; veri yolu
 * `{{dataDir}}` jetonu ile sonradan yerleştirilir, shell enjeksiyonu yapılmaz.
 */
data class ServiceDefinition(
    val serviceKey: String,
    val displayName: String,
    val category: ServiceCategory,
    val description: String,
    val packages: List<String>,
    val versionCommand: List<String>,
    val initCommands: List<List<String>> = emptyList(),
    val initMarkerRelativePath: String? = null,
    val startCommand: List<String> = emptyList(),
    val port: Int? = null,
    val externallyManaged: Boolean = false,
) {
    fun render(arguments: List<String>, dataDir: String): List<String> =
        arguments.map { it.replace(DATA_DIR_TOKEN, dataDir) }

    companion object {
        const val DATA_DIR_TOKEN = "{{dataDir}}"
    }
}

object ServiceCatalog {
    const val NGINX = "nginx"
    const val CLOUDFLARED = "cloudflared"
    const val SSHD = "sshd"
    const val POSTGRESQL = "postgresql"
    const val REDIS = "redis"
    const val MARIADB = "mariadb"

    val definitions: List<ServiceDefinition> = listOf(
        ServiceDefinition(
            serviceKey = NGINX,
            displayName = "Nginx",
            category = ServiceCategory.CORE,
            description = "Siteler için ters proxy ve statik yayın. Ağ motoru tarafından yönetilir.",
            packages = listOf("nginx"),
            versionCommand = listOf("nginx", "-v"),
            externallyManaged = true,
        ),
        ServiceDefinition(
            serviceKey = CLOUDFLARED,
            displayName = "Cloudflare tüneli",
            category = ServiceCategory.CORE,
            description = "İnternete açık adres için Quick Tunnel. Ağ motoru tarafından yönetilir.",
            packages = listOf("cloudflared"),
            versionCommand = listOf("cloudflared", "--version"),
            externallyManaged = true,
        ),
        ServiceDefinition(
            serviceKey = SSHD,
            displayName = "SSH sunucusu (OpenSSH)",
            category = ServiceCategory.ACCESS,
            description = "Telefona uzaktan terminal erişimi. SSH kartı üzerinden başlatılır ve durdurulur.",
            packages = listOf("openssh"),
            versionCommand = listOf("ssh", "-V"),
            externallyManaged = true,
        ),
        ServiceDefinition(
            serviceKey = POSTGRESQL,
            displayName = "PostgreSQL",
            category = ServiceCategory.DATABASE,
            description = "Yalnız 127.0.0.1 üzerinde dinler; ilk başlatmada trust kimlik doğrulamasıyla veri dizini hazırlanır.",
            packages = listOf("postgresql"),
            versionCommand = listOf("postgres", "--version"),
            initCommands = listOf(listOf("initdb", "-D", ServiceDefinition.DATA_DIR_TOKEN, "-U", "postgres", "-A", "trust")),
            initMarkerRelativePath = "PG_VERSION",
            startCommand = listOf("postgres", "-D", ServiceDefinition.DATA_DIR_TOKEN, "-o", "listen_addresses=127.0.0.1"),
            port = 5432,
        ),
        ServiceDefinition(
            serviceKey = REDIS,
            displayName = "Redis",
            category = ServiceCategory.DATABASE,
            description = "Önbellek ve kuyruk sunucusu; yalnız 127.0.0.1:6379 üzerinde dinler.",
            packages = listOf("redis"),
            versionCommand = listOf("redis-server", "--version"),
            startCommand = listOf(
                "redis-server", "--bind", "127.0.0.1", "--port", "6379",
                "--dir", ServiceDefinition.DATA_DIR_TOKEN,
            ),
            port = 6379,
        ),
        ServiceDefinition(
            serviceKey = MARIADB,
            displayName = "MariaDB",
            category = ServiceCategory.DATABASE,
            description = "MySQL uyumlu ilişkisel veritabanı; yalnız 127.0.0.1:3306 üzerinde dinler.",
            packages = listOf("mariadb"),
            versionCommand = listOf("mariadb", "--version"),
            initCommands = listOf(listOf("mariadb-install-db", "--datadir=${ServiceDefinition.DATA_DIR_TOKEN}")),
            initMarkerRelativePath = "mysql",
            startCommand = listOf(
                "mariadbd", "--datadir=${ServiceDefinition.DATA_DIR_TOKEN}",
                "--bind-address=127.0.0.1", "--port=3306",
                "--socket=${ServiceDefinition.DATA_DIR_TOKEN}/mariadb.sock",
            ),
            port = 3306,
        ),
    )

    fun find(serviceKey: String): ServiceDefinition? = definitions.firstOrNull { it.serviceKey == serviceKey }

    fun require(serviceKey: String): ServiceDefinition =
        find(serviceKey) ?: throw IllegalArgumentException("Unknown service: $serviceKey")
}

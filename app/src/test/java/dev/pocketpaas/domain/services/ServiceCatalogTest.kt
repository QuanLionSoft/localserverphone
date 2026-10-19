package dev.pocketpaas.domain.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceCatalogTest {
    @Test
    fun `all definitions have unique keys and valid ports`() {
        val keys = ServiceCatalog.definitions.map { it.serviceKey }
        assertEquals(keys.size, keys.distinct().size)
        ServiceCatalog.definitions.filter { it.port != null }.forEach { def ->
            assertTrue("port ${def.port} for ${def.serviceKey} must be unprivileged", def.port!! in 1024..65535)
        }
    }

    @Test
    fun `render replaces data dir token without shell injection`() {
        val def = ServiceCatalog.require(ServiceCatalog.POSTGRESQL)
        val rendered = def.render(listOf("initdb", "-D", "{{dataDir}}", "-U", "postgres"), "/data/test")
        assertEquals(listOf("initdb", "-D", "/data/test", "-U", "postgres"), rendered)
        assertTrue(rendered.none { it.contains(";") || it.contains("$(") })
    }

    @Test
    fun `database services have start commands`() {
        listOf(ServiceCatalog.POSTGRESQL, ServiceCatalog.REDIS, ServiceCatalog.MARIADB).forEach { key ->
            val def = ServiceCatalog.require(key)
            assertTrue("${def.serviceKey} must have a start command", def.startCommand.isNotEmpty())
            assertNotNull("${def.serviceKey} must have a port", def.port)
        }
    }

    @Test
    fun `externally managed services have no start command requirement`() {
        listOf(ServiceCatalog.NGINX, ServiceCatalog.CLOUDFLARED, ServiceCatalog.SSHD).forEach { key ->
            val def = ServiceCatalog.require(key)
            assertTrue(def.externallyManaged)
        }
    }
}

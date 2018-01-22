package net.corda.nodeapi.internal.persistence

import net.corda.core.internal.castIfPossible
import net.corda.core.schemas.MappedSchema
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.toHexString
import org.hibernate.SessionFactory
import org.hibernate.boot.Metadata
import org.hibernate.boot.MetadataBuilder
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder
import org.hibernate.cfg.Configuration
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider
import org.hibernate.service.UnknownUnwrapTypeException
import org.hibernate.type.AbstractSingleColumnStandardBasicType
import org.hibernate.type.descriptor.java.PrimitiveByteArrayTypeDescriptor
import org.hibernate.type.descriptor.sql.BlobTypeDescriptor
import org.hibernate.type.descriptor.sql.VarbinaryTypeDescriptor
import java.lang.management.ManagementFactory
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import javax.management.ObjectName
import javax.persistence.AttributeConverter

class HibernateConfiguration(
        schemas: Set<MappedSchema>,
        private val databaseConfig: DatabaseConfig,
        private val attributeConverters: Collection<AttributeConverter<*, *>>,
        private val jdbcUrl: String
) {
    companion object {
        private val logger = contextLogger()

        // register custom converters
        fun buildHibernateMetadata(metadataBuilder: MetadataBuilder, jdbcUrl:String, attributeConverters: Collection<AttributeConverter<*, *>>): Metadata {
            metadataBuilder.run {
                attributeConverters.forEach { applyAttributeConverter(it) }
                // Register a tweaked version of `org.hibernate.type.MaterializedBlobType` that truncates logged messages.
                // to avoid OOM when large blobs might get logged.
                applyBasicType(CordaMaterializedBlobType, CordaMaterializedBlobType.name)
                applyBasicType(CordaWrapperBinaryType, CordaWrapperBinaryType.name)
                // When connecting to SqlServer (and only then) do we need to tell hibernate to use
                // nationalised (i.e. Unicode) strings by default
                val forceUnicodeForSqlServer = jdbcUrl.contains(":sqlserver:", ignoreCase = true)
                enableGlobalNationalizedCharacterDataSupport(forceUnicodeForSqlServer)
                return build()
            }
        }
    }

    // TODO: make this a guava cache or similar to limit ability for this to grow forever.
    private val sessionFactories = ConcurrentHashMap<Set<MappedSchema>, SessionFactory>()

    val sessionFactoryForRegisteredSchemas = schemas.let {
        logger.info("Init HibernateConfiguration for schemas: $it")
        sessionFactoryForSchemas(it)
    }

    /** @param key must be immutable, not just read-only. */
    fun sessionFactoryForSchemas(key: Set<MappedSchema>) = sessionFactories.computeIfAbsent(key, { makeSessionFactoryForSchemas(key) })

    private fun makeSessionFactoryForSchemas(schemas: Set<MappedSchema>): SessionFactory {
        logger.info("Creating session factory for schemas: $schemas")
        val serviceRegistry = BootstrapServiceRegistryBuilder().build()
        val metadataSources = MetadataSources(serviceRegistry)

        val config = Configuration(metadataSources).setProperty("hibernate.connection.provider_class", NodeDatabaseConnectionProvider::class.java.name)
                .setProperty("hibernate.hbm2ddl.auto", if (isH2Database(jdbcUrl)) "update" else "validate")
                .setProperty("hibernate.connection.isolation", databaseConfig.transactionIsolationLevel.jdbcValue.toString())

        databaseConfig.schema?.apply {
            //preserving case-sensitive schema name for PostgreSQL by wrapping in double quotes, schema without double quotes would be treated as case-insensitive (lower cases)
            val schemaName = if (jdbcUrl.contains(":postgresql:", ignoreCase = true) && !databaseConfig.schema.startsWith("\"")) {
                "\"" + databaseConfig.schema + "\""
            } else {
                databaseConfig.schema
            }
            config.setProperty("hibernate.default_schema", schemaName)
        }

        schemas.forEach { schema ->
            // TODO: require mechanism to set schemaOptions (databaseSchema, tablePrefix) which are not global to session
            schema.mappedTypes.forEach { config.addAnnotatedClass(it) }
        }

        val sessionFactory = buildSessionFactory(config, metadataSources)
        logger.info("Created session factory for schemas: $schemas")

        // export Hibernate JMX statistics
        if (databaseConfig.exportHibernateJMXStatistics)
            initStatistics(sessionFactory)

        return sessionFactory
    }

    // NOTE: workaround suggested to overcome deprecation of StatisticsService (since Hibernate v4.0)
    // https://stackoverflow.com/questions/23606092/hibernate-upgrade-statisticsservice
    fun initStatistics(sessionFactory: SessionFactory) {
        val statsName = ObjectName("org.hibernate:type=statistics")
        val mbeanServer = ManagementFactory.getPlatformMBeanServer()

        val statisticsMBean = DelegatingStatisticsService(sessionFactory.statistics)
        statisticsMBean.isStatisticsEnabled = true

        try {
            mbeanServer.registerMBean(statisticsMBean, statsName)
        } catch (e: Exception) {
            logger.warn(e.message)
        }
    }

    private fun buildSessionFactory(config: Configuration, metadataSources: MetadataSources): SessionFactory {
        config.standardServiceRegistryBuilder.applySettings(config.properties)
        val metadataBuilder = metadataSources.getMetadataBuilder(config.standardServiceRegistryBuilder.build())
        val metadata = buildHibernateMetadata(metadataBuilder, jdbcUrl, attributeConverters)

        return metadata.sessionFactoryBuilder.run {
            allowOutOfTransactionUpdateOperations(true)
            applySecondLevelCacheSupport(false)
            applyQueryCacheSupport(false)
            enableReleaseResourcesOnCloseEnabled(true)
            build()
        }
    }

    // Supply Hibernate with connections from our underlying Exposed database integration.  Only used
    // during schema creation / update.
    class NodeDatabaseConnectionProvider : ConnectionProvider {
        override fun closeConnection(conn: Connection) {
            conn.autoCommit = false
            contextTransaction.run {
                commit()
                close()
            }
        }

        override fun supportsAggressiveRelease(): Boolean = true

        override fun getConnection(): Connection {
            return contextDatabase.newTransaction().connection
        }

        override fun <T : Any?> unwrap(unwrapType: Class<T>): T {
            return unwrapType.castIfPossible(this) ?: throw UnknownUnwrapTypeException(unwrapType)
        }

        override fun isUnwrappableAs(unwrapType: Class<*>?): Boolean = unwrapType == NodeDatabaseConnectionProvider::class.java
    }

    // A tweaked version of `org.hibernate.type.MaterializedBlobType` that truncates logged messages.  Also logs in hex.
    object CordaMaterializedBlobType : AbstractSingleColumnStandardBasicType<ByteArray>(BlobTypeDescriptor.DEFAULT, CordaPrimitiveByteArrayTypeDescriptor) {
        override fun getName(): String {
            return "materialized_blob"
        }
    }

    // A tweaked version of `org.hibernate.type.descriptor.java.PrimitiveByteArrayTypeDescriptor` that truncates logged messages.
    object CordaPrimitiveByteArrayTypeDescriptor : PrimitiveByteArrayTypeDescriptor() {
        private val LOG_SIZE_LIMIT = 1024

        override fun extractLoggableRepresentation(value: ByteArray?): String {
            return if (value == null) {
                super.extractLoggableRepresentation(value)
            } else {
                if (value.size <= LOG_SIZE_LIMIT) {
                    "[size=${value.size}, value=${value.toHexString()}]"
                } else {
                    "[size=${value.size}, value=${value.copyOfRange(0, LOG_SIZE_LIMIT).toHexString()}...truncated...]"
                }
            }
        }
    }

    // A tweaked version of `org.hibernate.type.WrapperBinaryType` that deals with ByteArray (java primitive byte[] type).
    object CordaWrapperBinaryType : AbstractSingleColumnStandardBasicType<ByteArray>(VarbinaryTypeDescriptor.INSTANCE, PrimitiveByteArrayTypeDescriptor.INSTANCE) {
        override fun getRegistrationKeys(): Array<String> {
            return arrayOf(name, "ByteArray", ByteArray::class.java.name)
        }

        override fun getName(): String {
            return "corda-wrapper-binary"
        }
    }
}

/** Allow Oracle database drivers ojdbc7.jar and ojdbc8.jar to deserialize classes from oracle.sql.converter package. */
fun oracleJdbcDriverSerialFilter(clazz: Class<*>): Boolean = clazz.name.startsWith("oracle.sql.converter.")

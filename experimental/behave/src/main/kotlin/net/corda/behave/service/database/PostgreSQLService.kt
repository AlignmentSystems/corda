/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.behave.service.database

import net.corda.behave.database.DatabaseConnection
import net.corda.behave.database.DatabaseType
import net.corda.behave.database.configuration.PostgresConfigurationTemplate
import net.corda.behave.node.configuration.DatabaseConfiguration
import net.corda.behave.service.ContainerService
import net.corda.behave.service.ServiceSettings

class PostgreSQLService(
        name: String,
        port: Int,
        private val password: String,
        settings: ServiceSettings = ServiceSettings()
) : ContainerService(name, port, "database system is ready to accept connections", settings) {

    override val baseImage = "postgres"

    override val internalPort = 5432

    override fun verify(): Boolean {
        val config = DatabaseConfiguration(
                type = DatabaseType.POSTGRES,
                host = host,
                port = port,
                database = database,
                schema = schema,
                username = username,
                password = password
        )
        val connection = DatabaseConnection(config, PostgresConfigurationTemplate())
        try {
            connection.use {
                return true
            }
        } catch (ex: Exception) {
            log.warn(ex.message, ex)
            ex.printStackTrace()
        }
        return false
    }

    companion object {
        const val host = "localhost"
        const val database = "postgres"
        const val schema = "public"
        const val username = "postgres"
        const val driver = "postgresql-42.1.4.jar"
    }
}
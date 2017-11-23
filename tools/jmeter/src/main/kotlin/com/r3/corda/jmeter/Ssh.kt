package com.r3.corda.jmeter

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.nodeapi.internal.addShutdownHook
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.*

/**
 * Creates SSH tunnels for remote controlling SSH servers/agents from the local host (via UI or headless).
 */
class Ssh {
    companion object {
        val log = LoggerFactory.getLogger(this::class.java)

        @JvmStatic
        @JvmOverloads
        fun main(args: Array<String>, wait: Boolean = true) {
            val userName = System.getProperty("user.name")
            val jsch = setupJSchWithSshAgent()
            val sessions = mutableListOf<Session>()

            // Read jmeter.properties
            // For each host:port combo, map them to hosts from command line

            val jmeterProps = loadProps("/jmeter.properties")
            // The port the JMeter remote agents call back to on this client host.
            val clientRmiLocalPort = jmeterProps.getProperty("client.rmi.localport").toInt()
            // Remote RMI registry port.
            val serverRmiPort = jmeterProps.getProperty("server.rmi.port", "1099").toInt()

            // Where JMeter driver will try to connect for remote agents (should all be localhost so can ssh tunnel).
            val localHostsAndPorts = jmeterProps.getProperty("remote_hosts", "").split(',').map { it.trim() }
            args.zip(localHostsAndPorts) { remoteHost, localHostAndPortString ->
                // Actual remote host and port we will tunnel to.
                log.info("Creating tunnels for $remoteHost")
                val localHostAndPort = NetworkHostAndPort.parse(localHostAndPortString)

                // For the remote host, load their specific property file, since it specifies remote RMI server port
                val unqualifiedHostName = remoteHost.substringBefore('.')
                val hostProps = loadProps("/$unqualifiedHostName.properties")

                val serverRmiLocalPort = hostProps.getProperty("server.rmi.localport", jmeterProps.getProperty("server.rmi.localport")).toInt()

                val session = connectToHost(jsch, remoteHost, userName)
                sessions += session

                // For tunnelling the RMI registry on the remote agent
                // ssh ${remoteHostAndPort.host} -L 0.0.0.0:${localHostAndPort.port}:localhost:$serverRmiPort -N
                createOutboundTunnel(session, NetworkHostAndPort("0.0.0.0", localHostAndPort.port), NetworkHostAndPort("localhost", serverRmiPort))

                // For tunnelling the actual connection to the remote agent
                // ssh ${remoteHostAndPort.host} -L 0.0.0.0:$serverRmiLocalPort:localhost:$serverRmiLocalPort -N
                createOutboundTunnel(session, NetworkHostAndPort("0.0.0.0", serverRmiLocalPort), NetworkHostAndPort("localhost", serverRmiLocalPort))

                // For returning results to the client
                // ssh ${remoteHostAndPort.host} -R 0.0.0.0:clientRmiLocalPort:localhost:clientRmiLocalPort -N
                createInboundTunnel(session, NetworkHostAndPort("0.0.0.0", clientRmiLocalPort), NetworkHostAndPort("localhost", clientRmiLocalPort))
            }

            if (wait) {
                val input = BufferedReader(InputStreamReader(System.`in`))
                do {
                    log.info("Type 'quit' to exit cleanly.")
                } while (input.readLine() != "quit")
                sessions.forEach {
                    log.info("Closing tunnels for ${it.host}")
                    it.disconnect()
                }
            } else {
                addShutdownHook {
                    sessions.forEach {
                        log.info("Closing tunnels for ${it.host}")
                        it.disconnect()
                    }
                }
            }
        }

        private fun loadProps(filename: String): Properties {
            val props = Properties()
            this::class.java.getResourceAsStream(filename).use {
                props.load(it)
            }
            return props
        }

        fun connectToHost(jSch: JSch, remoteHost: String, remoteUserName: String): Session {
            val session = jSch.getSession(remoteUserName, remoteHost, 22)
            // We don't check the host fingerprints because they may change often
            session.setConfig("StrictHostKeyChecking", "no")
            log.info("Connecting to $remoteHost...")
            session.connect()
            log.info("Connected to $remoteHost!")
            return session
        }

        fun createOutboundTunnel(session: Session, local: NetworkHostAndPort, remote: NetworkHostAndPort) {
            log.info("Creating outbound tunnel from $local to $remote with ${session.host}...")
            session.setPortForwardingL(local.host, local.port, remote.host, remote.port)
            log.info("Tunnel created!")
        }

        fun createInboundTunnel(session: Session, local: NetworkHostAndPort, remote: NetworkHostAndPort) {
            log.info("Creating inbound tunnel from $remote to $local on ${session.host}...")
            session.setPortForwardingR(remote.host, remote.port, local.host, local.port)
            log.info("Tunnel created!")
        }
    }
}
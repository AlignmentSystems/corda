package com.r3.corda.networkmanage.common.utils

import net.corda.core.serialization.ClassWhitelist
import net.corda.core.serialization.SerializationContext
import net.corda.core.serialization.SerializationCustomSerializer
import net.corda.serialization.internal.CordaSerializationMagic
import net.corda.serialization.internal.amqp.AbstractAMQPSerializationScheme
import net.corda.serialization.internal.amqp.SerializerFactory
import net.corda.serialization.internal.amqp.amqpMagic
import java.util.concurrent.ConcurrentHashMap

class AMQPNetworkServicesSerializationScheme (
        cordappCustomSerializers: Set<SerializationCustomSerializer<*, *>>,
        serializerFactoriesForContexts: MutableMap<Pair<ClassWhitelist, ClassLoader>, SerializerFactory>
    ) : AbstractAMQPSerializationScheme(cordappCustomSerializers, serializerFactoriesForContexts) {

    constructor() : this(emptySet(), ConcurrentHashMap())

    override fun rpcClientSerializerFactory(context: SerializationContext): SerializerFactory {
        throw UnsupportedOperationException()
    }

    override fun rpcServerSerializerFactory(context: SerializationContext): SerializerFactory {
        throw UnsupportedOperationException()
    }

    override fun canDeserializeVersion(magic: CordaSerializationMagic, target: SerializationContext.UseCase) =
            (magic == amqpMagic && target == SerializationContext.UseCase.P2P)
}

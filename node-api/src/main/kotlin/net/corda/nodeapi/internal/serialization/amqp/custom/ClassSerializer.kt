/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.nodeapi.internal.serialization.amqp.custom

import net.corda.nodeapi.internal.serialization.amqp.CustomSerializer
import net.corda.nodeapi.internal.serialization.amqp.SerializerFactory

/**
 * A serializer for [Class] that uses [ClassProxy] proxy object to write out
 */
class ClassSerializer(factory: SerializerFactory) : CustomSerializer.Proxy<Class<*>, ClassSerializer.ClassProxy>(Class::class.java, ClassProxy::class.java, factory) {
    override fun toProxy(obj: Class<*>): ClassProxy = ClassProxy(obj.name)

    override fun fromProxy(proxy: ClassProxy): Class<*> = Class.forName(proxy.className, true, factory.classloader)

    data class ClassProxy(val className: String)
}
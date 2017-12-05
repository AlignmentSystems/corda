package net.corda.flowhook

import javassist.ClassPool
import javassist.CtBehavior
import javassist.CtClass
import java.io.ByteArrayInputStream
import java.lang.instrument.ClassFileTransformer
import java.lang.reflect.Method
import java.security.ProtectionDomain

class Hooker(hookContainer: Any) : ClassFileTransformer {
    private val classPool = ClassPool.getDefault()

    private val hooks = createHooks(hookContainer)

    private fun createHooks(hookContainer: Any): Hooks {
        val hooks = HashMap<String, HashMap<Signature, Pair<Method, Hook>>>()
        for (method in hookContainer.javaClass.methods) {
            val hookAnnotation = method.getAnnotation(Hook::class.java)
            if (hookAnnotation != null) {
                val signature = if (hookAnnotation.passThis) {
                    if (method.parameterTypes.isEmpty() || method.parameterTypes[0] != Any::class.java) {
                        println("Method should accept an object as first parameter for 'this' $method")
                        continue
                    }
                    Signature(method.name, method.parameterTypes.toList().drop(1).map { it.canonicalName })
                } else {
                    Signature(method.name, method.parameterTypes.map { it.canonicalName })
                }
                hooks.getOrPut(hookAnnotation.clazz) { HashMap() }.put(signature, Pair(method, hookAnnotation))
            }
        }
        return hooks
    }

    override fun transform(
            loader: ClassLoader?,
            className: String,
            classBeingRedefined: Class<*>?,
            protectionDomain: ProtectionDomain?,
            classfileBuffer: ByteArray
    ): ByteArray? {
        if (className.startsWith("java") || className.startsWith("sun") || className.startsWith("javassist") || className.startsWith("kotlin")) {
            return null
        }
        return try {
            val clazz = classPool.makeClass(ByteArrayInputStream(classfileBuffer))
            instrumentClass(clazz)?.toBytecode()
        } catch (throwable: Throwable) {
            println("SOMETHING WENT WRONG")
            throwable.printStackTrace(System.out)
            null
        }
    }

    private fun instrumentClass(clazz: CtClass): CtClass? {
        val hookMethods = hooks[clazz.name] ?: return null
        val usedHookMethods = HashSet<Method>()
        var isAnyInstrumented = false
        for (method in clazz.declaredBehaviors) {
            val hookMethod = instrumentBehaviour(method, hookMethods)
            if (hookMethod != null) {
                isAnyInstrumented = true
                usedHookMethods.add(hookMethod)
            }
        }
        val unusedHookMethods = hookMethods.values.mapTo(HashSet()) { it.first } - usedHookMethods
        if (unusedHookMethods.isNotEmpty()) {
            println("Unused hook methods $unusedHookMethods")
        }
        return if (isAnyInstrumented) {
            clazz
        } else {
            null
        }
    }

    private fun instrumentBehaviour(method: CtBehavior, methodHooks: MethodHooks): Method? {
        val signature = Signature(method.name, method.parameterTypes.map { it.name })
        val (hookMethod, annotation) = methodHooks[signature] ?: return null
        val invocationString = if (annotation.passThis) {
            "${hookMethod.declaringClass.canonicalName}.${hookMethod.name}(this, \$\$)"
        } else {
            "${hookMethod.declaringClass.canonicalName}.${hookMethod.name}(\$\$)"
        }

        val overriddenPosition = if (method.methodInfo.isConstructor && annotation.passThis && annotation.position == HookPosition.Before) {
            println("passThis=true and position=${HookPosition.Before} for a constructor. " +
                    "You can only inspect 'this' at the end of the constructor! Hooking *after*.. $method")
            HookPosition.After
        } else {
            annotation.position
        }

        val insertHook: (CtBehavior.(code: String) -> Unit) = when (overriddenPosition) {
            HookPosition.Before -> CtBehavior::insertBefore
            HookPosition.After -> CtBehavior::insertAfter
        }
        when {
            Function0::class.java.isAssignableFrom(hookMethod.returnType) -> {
                method.addLocalVariable("after", classPool.get("kotlin.jvm.functions.Function0"))
                method.insertHook("after = $invocationString;")
                method.insertAfter("after.invoke();")
            }
            Function1::class.java.isAssignableFrom(hookMethod.returnType) -> {
                method.addLocalVariable("after", classPool.get("kotlin.jvm.functions.Function1"))
                method.insertHook("after = $invocationString;")
                method.insertAfter("after.invoke((\$w)\$_);")
            }
            else -> {
                method.insertHook("$invocationString;")
            }
        }
        return hookMethod
    }
}


enum class HookPosition {
    Before,
    After
}

@Target(AnnotationTarget.FUNCTION)
annotation class Hook(val clazz: String, val position: HookPosition = HookPosition.Before, val passThis: Boolean = false)

private data class Signature(val functionName: String, val parameterTypes: List<String>)

private typealias MethodHooks = Map<Signature, Pair<Method, Hook>>
private typealias Hooks = Map<String, MethodHooks>

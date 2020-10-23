/*
 * Copyright 2019-2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AFFERO GENERAL PUBLIC LICENSE version 3 license that can be found via the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

@file:Suppress("NOTHING_TO_INLINE", "MemberVisibilityCanBePrivate", "INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package net.mamoe.mirai.console.internal.command

import net.mamoe.mirai.console.command.*
import net.mamoe.mirai.console.command.descriptor.*
import net.mamoe.mirai.console.internal.command.hasAnnotation
import net.mamoe.mirai.console.permission.Permission
import net.mamoe.mirai.message.data.MessageChain
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.message.data.SingleMessage
import net.mamoe.mirai.message.data.buildMessageChain
import kotlin.reflect.KAnnotatedElement
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.*

internal object CompositeCommandSubCommandAnnotationResolver :
    AbstractReflectionCommand.SubCommandAnnotationResolver {
    override fun hasAnnotation(baseCommand: AbstractReflectionCommand, function: KFunction<*>) =
        function.hasAnnotation<CompositeCommand.SubCommand>()

    override fun getSubCommandNames(baseCommand: AbstractReflectionCommand, function: KFunction<*>): Array<out String> =
        function.findAnnotation<CompositeCommand.SubCommand>()!!.value
}

internal object SimpleCommandSubCommandAnnotationResolver :
    AbstractReflectionCommand.SubCommandAnnotationResolver {
    override fun hasAnnotation(baseCommand: AbstractReflectionCommand, function: KFunction<*>) =
        function.hasAnnotation<SimpleCommand.Handler>()

    override fun getSubCommandNames(baseCommand: AbstractReflectionCommand, function: KFunction<*>): Array<out String> =
        baseCommand.secondaryNames
}

internal abstract class AbstractReflectionCommand
@JvmOverloads constructor(
    owner: CommandOwner,
    primaryName: String,
    secondaryNames: Array<out String>,
    description: String = "<no description available>",
    parentPermission: Permission = owner.parentPermission,
    prefixOptional: Boolean = false,
) : Command, AbstractCommand(
    owner,
    primaryName = primaryName,
    secondaryNames = secondaryNames,
    description = description,
    parentPermission = parentPermission,
    prefixOptional = prefixOptional
), CommandArgumentContextAware {
    internal abstract val subCommandAnnotationResolver: SubCommandAnnotationResolver

    @JvmField
    @Suppress("PropertyName")
    internal var _usage: String = "<not yet initialized>"

    override val usage: String  // initialized by subCommand reflection
        get() {
            subCommands // ensure init
            return _usage
        }

    abstract suspend fun CommandSender.onDefault(rawArgs: MessageChain)

    internal val defaultSubCommand: DefaultSubCommandDescriptor by lazy {
        DefaultSubCommandDescriptor(
            "",
            createOrFindCommandPermission(parentPermission),
            onCommand = { sender: CommandSender, args: MessageChain ->
                sender.onDefault(args)
            }
        )
    }

    internal open fun checkSubCommand(subCommands: Array<SubCommandDescriptor>) {

    }

    @OptIn(ExperimentalCommandDescriptors::class)
    private fun <T : Any> CommandParameter<T>.toCommandValueParameter(): CommandValueParameter<T> {
        return CommandValueParameter.UserDefinedType<T>(name, null, false, false, type)
    }


    @OptIn(ExperimentalCommandDescriptors::class)
    override val overloads: List<CommandSignatureVariant> by lazy {
        subCommands.map { desc ->
            CommandSignatureVariantImpl(desc.params.map { it.toCommandValueParameter() }) { call ->
                desc.onCommand(call.caller, call.resolvedValueArguments)
            }
        }
    }

    interface SubCommandAnnotationResolver {
        fun hasAnnotation(baseCommand: AbstractReflectionCommand, function: KFunction<*>): Boolean
        fun getSubCommandNames(baseCommand: AbstractReflectionCommand, function: KFunction<*>): Array<out String>
    }

    internal val subCommands: Array<SubCommandDescriptor> by lazy {
        this::class.declaredFunctions.filter { subCommandAnnotationResolver.hasAnnotation(this, it) }
            .also { subCommandFunctions ->
                // overloading not yet supported
                val overloadFunction = subCommandFunctions.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }
                if (overloadFunction != null) {
                    error("Sub command overloading is not yet supported. (at ${this::class.qualifiedNameOrTip}.${overloadFunction.key})")
                }
            }.map { function ->
                createSubCommand(function, context)
            }.toTypedArray().also {
                _usage = it.createUsage(this)
            }.also { checkSubCommand(it) }
    }

    internal val bakedCommandNameToSubDescriptorArray: Map<Array<String>, SubCommandDescriptor> by lazy {
        kotlin.run {
            val map = LinkedHashMap<Array<String>, SubCommandDescriptor>(subCommands.size * 2)
            for (descriptor in subCommands) {
                for (name in descriptor.bakedSubNames) {
                    map[name] = descriptor
                }
            }
            map.toSortedMap { o1, o2 -> o1!!.contentHashCode() - o2!!.contentHashCode() }
        }
    }

    internal class DefaultSubCommandDescriptor(
        val description: String,
        val permission: Permission,
        val onCommand: suspend (sender: CommandSender, rawArgs: MessageChain) -> Unit,
    )

    internal inner class SubCommandDescriptor(
        val names: Array<out String>,
        val params: Array<CommandParameter<*>>,
        val description: String,
        val permission: Permission,
        val onCommand: suspend (sender: CommandSender, parsedArgs: List<Any?>) -> Boolean,
        val context: CommandArgumentContext,
        val argumentBuilder: (sender: CommandSender) -> MutableMap<KParameter, Any?>,
    ) {
        val usage: String = createUsage(this@AbstractReflectionCommand)

        private fun KParameter.isOptional(): Boolean {
            return isOptional || this.type.isMarkedNullable
        }

        val minimalArgumentsSize = params.count {
            !it.parameter.isOptional()
        }

        @JvmField
        internal val bakedSubNames: Array<Array<String>> = names.map { it.bakeSubName() }.toTypedArray()
    }
}

internal fun <T> Array<T>.contentEqualsOffset(other: MessageChain, length: Int): Boolean {
    repeat(length) { index ->
        if (!other[index].toString().equals(this[index].toString(), ignoreCase = true)) {
            return false
        }
    }
    return true
}

internal val ILLEGAL_SUB_NAME_CHARS = "\\/!@#$%^&*()_+-={}[];':\",.<>?`~".toCharArray()
internal fun String.isValidSubName(): Boolean = ILLEGAL_SUB_NAME_CHARS.none { it in this }
internal fun String.bakeSubName(): Array<String> = split(' ').filterNot { it.isBlank() }.toTypedArray()

internal fun Any.flattenCommandComponents(): MessageChain = buildMessageChain {
    when (this@flattenCommandComponents) {
        is PlainText -> this@flattenCommandComponents.content.splitToSequence(' ').filterNot { it.isBlank() }
            .forEach { +PlainText(it) }
        is CharSequence -> this@flattenCommandComponents.splitToSequence(' ').filterNot { it.isBlank() }
            .forEach { +PlainText(it) }
        is SingleMessage -> add(this@flattenCommandComponents)
        is Array<*> -> this@flattenCommandComponents.forEach { if (it != null) addAll(it.flattenCommandComponents()) }
        is Iterable<*> -> this@flattenCommandComponents.forEach { if (it != null) addAll(it.flattenCommandComponents()) }
        else -> add(this@flattenCommandComponents.toString())
    }
}

internal inline fun <reified T : Annotation> KAnnotatedElement.hasAnnotation(): Boolean =
    findAnnotation<T>() != null

internal val KClass<*>.qualifiedNameOrTip: String get() = this.qualifiedName ?: "<anonymous class>"

internal fun Array<AbstractReflectionCommand.SubCommandDescriptor>.createUsage(baseCommand: AbstractReflectionCommand): String =
    buildString {
        appendLine(baseCommand.description)
        appendLine()

        for (subCommandDescriptor in this@createUsage) {
            appendLine(subCommandDescriptor.usage)
        }
    }.trimEnd()

internal fun AbstractReflectionCommand.SubCommandDescriptor.createUsage(baseCommand: AbstractReflectionCommand): String =
    buildString {
        if (baseCommand.prefixOptional) {
            append("(")
            append(CommandManager.commandPrefix)
            append(")")
        } else {
            append(CommandManager.commandPrefix)
        }
        if (baseCommand is CompositeCommand) {
            append(baseCommand.primaryName)
            append(" ")
        }
        append(names.first())
        append(" ")
        append(params.joinToString(" ") { "<${it.name}>" })
        append("   ")
        append(description)
        appendLine()
    }.trimEnd()

internal fun <T1, R1, R2> ((T1) -> R1).then(then: (T1, R1) -> R2): ((T1) -> R2) {
    return { a -> then.invoke(a, (this@then(a))) }
}

internal fun AbstractReflectionCommand.createSubCommand(
    function: KFunction<*>,
    context: CommandArgumentContext,
): AbstractReflectionCommand.SubCommandDescriptor {
    val notStatic = !function.hasAnnotation<JvmStatic>()
    //val overridePermission = null//function.findAnnotation<CompositeCommand.PermissionId>()//optional
    val subDescription =
        function.findAnnotation<CompositeCommand.Description>()?.value ?: ""

    fun KClass<*>.isValidReturnType(): Boolean {
        return when (this) {
            Boolean::class, Void::class, Unit::class, Nothing::class -> true
            else -> false
        }
    }

    check((function.returnType.classifier as? KClass<*>)?.isValidReturnType() == true) {
        error("Return type of sub command ${function.name} must be one of the following: kotlin.Boolean, java.lang.Boolean, kotlin.Unit (including implicit), kotlin.Nothing, boolean or void (at ${this::class.qualifiedNameOrTip}.${function.name})")
    }

    check(!function.returnType.isMarkedNullable) {
        error("Return type of sub command ${function.name} must not be marked nullable in Kotlin, and must be marked with @NotNull or @NonNull explicitly in Java. (at ${this::class.qualifiedNameOrTip}.${function.name})")
    }
    var argumentBuilder: (sender: CommandSender) -> MutableMap<KParameter, Any?> = { HashMap() }
    val parameters = function.parameters.toMutableList()

    if (notStatic) {
        val type = parameters.removeAt(0) // instance
        argumentBuilder = argumentBuilder.then { _, map ->
            map[type] = this@createSubCommand
            map
        }
    }

    check(parameters.isNotEmpty()) {
        "Parameters of sub command ${function.name} must not be empty. (Must have CommandSender as its receiver or first parameter or absent, followed by naturally typed params) (at ${this::class.qualifiedNameOrTip}.${function.name})"
    }

    parameters.forEach { param ->
        check(!param.isVararg) {
            "Parameter $param must not be vararg. (at ${this::class.qualifiedNameOrTip}.${function.name}.$param)"
        }
    }

    (parameters.first()).let { receiver ->
        if ((receiver.type.classifier as? KClass<*>)?.isSubclassOf(CommandSender::class) == true) {
            val senderType = parameters.removeAt(0)
            argumentBuilder = argumentBuilder.then { sender, map ->
                map[senderType] = sender
                map
            }
        }
    }

    val commandName =
        subCommandAnnotationResolver.getSubCommandNames(this, function)
            .let { namesFromAnnotation ->
                if (namesFromAnnotation.isNotEmpty()) {
                    namesFromAnnotation.map(String::toLowerCase).toTypedArray()
                } else arrayOf(function.name.toLowerCase())
            }.also { names ->
                names.forEach {
                    check(it.isValidSubName()) {
                        "Name of sub command ${function.name} is invalid"
                    }
                }
            }

    //map parameter
    val params = parameters.map { param ->

        // if (param.isOptional) error("optional parameters are not yet supported. (at ${this::class.qualifiedNameOrTip}.${function.name}.$param)")

        val paramName = param.findAnnotation<CompositeCommand.Name>()?.value ?: param.name ?: "unknown"
        CommandParameter<Any>(
            paramName,
            param.type,
            param
        )
    }.toTypedArray()

    // TODO: 2020/09/19 检查 optional/nullable 是否都在最后

    @Suppress("UNCHECKED_CAST")
    return SubCommandDescriptor(
        commandName,
        params as Array<CommandParameter<*>>,
        subDescription, // overridePermission?.value
        permission,//overridePermission?.value?.let { PermissionService.INSTANCE[PermissionId.parseFromString(it)] } ?: permission,
        onCommand = { _: CommandSender, args ->
            val p = parameters.zip(args).toMap(LinkedHashMap())
            if (notStatic) p[function.instanceParameter!!] = this@createSubCommand
            val result = function.callSuspendBy(p)

            checkNotNull(result) { "sub command return value is null (at ${this::class.qualifiedName}.${function.name})" }

            result as? Boolean ?: true // Unit, void is considered as true.
        },
        context = context,
        argumentBuilder = argumentBuilder
    )
}
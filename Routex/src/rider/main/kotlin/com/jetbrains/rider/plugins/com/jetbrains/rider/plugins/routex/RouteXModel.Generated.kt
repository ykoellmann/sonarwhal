@file:Suppress("EXPERIMENTAL_API_USAGE","EXPERIMENTAL_UNSIGNED_LITERALS","PackageDirectoryMismatch","UnusedImport","unused","LocalVariableName","CanBeVal","PropertyName","EnumEntryName","ClassName","ObjectPropertyName","UnnecessaryVariable","SpellCheckingInspection")
package com.jetbrains.rd.ide.model

import com.jetbrains.rd.framework.*
import com.jetbrains.rd.framework.base.*
import com.jetbrains.rd.framework.impl.*

import com.jetbrains.rd.util.lifetime.*
import com.jetbrains.rd.util.reactive.*
import com.jetbrains.rd.util.string.*
import com.jetbrains.rd.util.*
import kotlin.time.Duration
import kotlin.reflect.KClass
import kotlin.jvm.JvmStatic



/**
 * #### Generated from [RouteXModel.kt:8]
 */
class RouteXModel private constructor(
    private val _getEndpoints: RdCall<Unit, List<RdApiEndpoint>>,
    private val _clearCache: RdCall<Unit, Unit>,
    private val _endpointsUpdated: RdSignal<List<RdApiEndpoint>>,
    private val _navigateToEndpoint: RdSignal<String>
) : RdExtBase() {
    //companion
    
    companion object : ISerializersOwner {
        
        override fun registerSerializersCore(serializers: ISerializers)  {
            val classLoader = javaClass.classLoader
            serializers.register(LazyCompanionMarshaller(RdId(-1313687366202989810), classLoader, "com.jetbrains.rd.ide.model.RdHttpMethod"))
            serializers.register(LazyCompanionMarshaller(RdId(3123662564314655935), classLoader, "com.jetbrains.rd.ide.model.RdParameterSource"))
            serializers.register(LazyCompanionMarshaller(RdId(-8256417327353205516), classLoader, "com.jetbrains.rd.ide.model.RdApiParameter"))
            serializers.register(LazyCompanionMarshaller(RdId(-216361072668924885), classLoader, "com.jetbrains.rd.ide.model.RdApiSchemaProperty"))
            serializers.register(LazyCompanionMarshaller(RdId(552673157769132086), classLoader, "com.jetbrains.rd.ide.model.RdApiSchema"))
            serializers.register(LazyCompanionMarshaller(RdId(-3836673896959406358), classLoader, "com.jetbrains.rd.ide.model.RdApiEndpoint"))
        }
        
        
        
        
        private val __RdApiEndpointListSerializer = RdApiEndpoint.list()
        
        const val serializationHash = -3893942425860350789L
        
    }
    override val serializersOwner: ISerializersOwner get() = RouteXModel
    override val serializationHash: Long get() = RouteXModel.serializationHash
    
    //fields
    val getEndpoints: IRdCall<Unit, List<RdApiEndpoint>> get() = _getEndpoints
    val clearCache: IRdCall<Unit, Unit> get() = _clearCache
    val endpointsUpdated: ISignal<List<RdApiEndpoint>> get() = _endpointsUpdated
    val navigateToEndpoint: ISignal<String> get() = _navigateToEndpoint
    //methods
    //initializer
    init {
        _getEndpoints.async = true
        _clearCache.async = true
    }
    
    init {
        bindableChildren.add("getEndpoints" to _getEndpoints)
        bindableChildren.add("clearCache" to _clearCache)
        bindableChildren.add("endpointsUpdated" to _endpointsUpdated)
        bindableChildren.add("navigateToEndpoint" to _navigateToEndpoint)
    }
    
    //secondary constructor
    internal constructor(
    ) : this(
        RdCall<Unit, List<RdApiEndpoint>>(FrameworkMarshallers.Void, __RdApiEndpointListSerializer),
        RdCall<Unit, Unit>(FrameworkMarshallers.Void, FrameworkMarshallers.Void),
        RdSignal<List<RdApiEndpoint>>(__RdApiEndpointListSerializer),
        RdSignal<String>(FrameworkMarshallers.String)
    )
    
    //equals trait
    //hash code trait
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RouteXModel (")
        printer.indent {
            print("getEndpoints = "); _getEndpoints.print(printer); println()
            print("clearCache = "); _clearCache.print(printer); println()
            print("endpointsUpdated = "); _endpointsUpdated.print(printer); println()
            print("navigateToEndpoint = "); _navigateToEndpoint.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    override fun deepClone(): RouteXModel   {
        return RouteXModel(
            _getEndpoints.deepClonePolymorphic(),
            _clearCache.deepClonePolymorphic(),
            _endpointsUpdated.deepClonePolymorphic(),
            _navigateToEndpoint.deepClonePolymorphic()
        )
    }
    //contexts
    //threading
    override val extThreading: ExtThreadingKind get() = ExtThreadingKind.Default
}
val Solution.routeXModel get() = getOrCreateExtension("routeXModel", ::RouteXModel)



/**
 * #### Generated from [RouteXModel.kt:50]
 */
data class RdApiEndpoint (
    val id: String,
    val httpMethod: RdHttpMethod,
    val route: String,
    val filePath: String,
    val lineNumber: Int,
    val controllerName: String?,
    val methodName: String,
    val parameters: List<RdApiParameter>,
    val bodySchema: RdApiSchema?,
    val authRequired: Boolean,
    val authPolicy: String?,
    val contentHash: String,
    val analysisConfidence: Float,
    val analysisWarnings: List<String>
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdApiEndpoint> {
        override val _type: KClass<RdApiEndpoint> = RdApiEndpoint::class
        override val id: RdId get() = RdId(-3836673896959406358)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdApiEndpoint  {
            val id = buffer.readString()
            val httpMethod = buffer.readEnum<RdHttpMethod>()
            val route = buffer.readString()
            val filePath = buffer.readString()
            val lineNumber = buffer.readInt()
            val controllerName = buffer.readNullable { buffer.readString() }
            val methodName = buffer.readString()
            val parameters = buffer.readList { RdApiParameter.read(ctx, buffer) }
            val bodySchema = buffer.readNullable { RdApiSchema.read(ctx, buffer) }
            val authRequired = buffer.readBool()
            val authPolicy = buffer.readNullable { buffer.readString() }
            val contentHash = buffer.readString()
            val analysisConfidence = buffer.readFloat()
            val analysisWarnings = buffer.readList { buffer.readString() }
            return RdApiEndpoint(id, httpMethod, route, filePath, lineNumber, controllerName, methodName, parameters, bodySchema, authRequired, authPolicy, contentHash, analysisConfidence, analysisWarnings)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdApiEndpoint)  {
            buffer.writeString(value.id)
            buffer.writeEnum(value.httpMethod)
            buffer.writeString(value.route)
            buffer.writeString(value.filePath)
            buffer.writeInt(value.lineNumber)
            buffer.writeNullable(value.controllerName) { buffer.writeString(it) }
            buffer.writeString(value.methodName)
            buffer.writeList(value.parameters) { v -> RdApiParameter.write(ctx, buffer, v) }
            buffer.writeNullable(value.bodySchema) { RdApiSchema.write(ctx, buffer, it) }
            buffer.writeBool(value.authRequired)
            buffer.writeNullable(value.authPolicy) { buffer.writeString(it) }
            buffer.writeString(value.contentHash)
            buffer.writeFloat(value.analysisConfidence)
            buffer.writeList(value.analysisWarnings) { v -> buffer.writeString(v) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdApiEndpoint
        
        if (id != other.id) return false
        if (httpMethod != other.httpMethod) return false
        if (route != other.route) return false
        if (filePath != other.filePath) return false
        if (lineNumber != other.lineNumber) return false
        if (controllerName != other.controllerName) return false
        if (methodName != other.methodName) return false
        if (parameters != other.parameters) return false
        if (bodySchema != other.bodySchema) return false
        if (authRequired != other.authRequired) return false
        if (authPolicy != other.authPolicy) return false
        if (contentHash != other.contentHash) return false
        if (analysisConfidence != other.analysisConfidence) return false
        if (analysisWarnings != other.analysisWarnings) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + id.hashCode()
        __r = __r*31 + httpMethod.hashCode()
        __r = __r*31 + route.hashCode()
        __r = __r*31 + filePath.hashCode()
        __r = __r*31 + lineNumber.hashCode()
        __r = __r*31 + if (controllerName != null) controllerName.hashCode() else 0
        __r = __r*31 + methodName.hashCode()
        __r = __r*31 + parameters.hashCode()
        __r = __r*31 + if (bodySchema != null) bodySchema.hashCode() else 0
        __r = __r*31 + authRequired.hashCode()
        __r = __r*31 + if (authPolicy != null) authPolicy.hashCode() else 0
        __r = __r*31 + contentHash.hashCode()
        __r = __r*31 + analysisConfidence.hashCode()
        __r = __r*31 + analysisWarnings.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdApiEndpoint (")
        printer.indent {
            print("id = "); id.print(printer); println()
            print("httpMethod = "); httpMethod.print(printer); println()
            print("route = "); route.print(printer); println()
            print("filePath = "); filePath.print(printer); println()
            print("lineNumber = "); lineNumber.print(printer); println()
            print("controllerName = "); controllerName.print(printer); println()
            print("methodName = "); methodName.print(printer); println()
            print("parameters = "); parameters.print(printer); println()
            print("bodySchema = "); bodySchema.print(printer); println()
            print("authRequired = "); authRequired.print(printer); println()
            print("authPolicy = "); authPolicy.print(printer); println()
            print("contentHash = "); contentHash.print(printer); println()
            print("analysisConfidence = "); analysisConfidence.print(printer); println()
            print("analysisWarnings = "); analysisWarnings.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [RouteXModel.kt:28]
 */
data class RdApiParameter (
    val name: String,
    val paramType: String,
    val source: RdParameterSource,
    val required: Boolean,
    val defaultValue: String?
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdApiParameter> {
        override val _type: KClass<RdApiParameter> = RdApiParameter::class
        override val id: RdId get() = RdId(-8256417327353205516)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdApiParameter  {
            val name = buffer.readString()
            val paramType = buffer.readString()
            val source = buffer.readEnum<RdParameterSource>()
            val required = buffer.readBool()
            val defaultValue = buffer.readNullable { buffer.readString() }
            return RdApiParameter(name, paramType, source, required, defaultValue)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdApiParameter)  {
            buffer.writeString(value.name)
            buffer.writeString(value.paramType)
            buffer.writeEnum(value.source)
            buffer.writeBool(value.required)
            buffer.writeNullable(value.defaultValue) { buffer.writeString(it) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdApiParameter
        
        if (name != other.name) return false
        if (paramType != other.paramType) return false
        if (source != other.source) return false
        if (required != other.required) return false
        if (defaultValue != other.defaultValue) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + name.hashCode()
        __r = __r*31 + paramType.hashCode()
        __r = __r*31 + source.hashCode()
        __r = __r*31 + required.hashCode()
        __r = __r*31 + if (defaultValue != null) defaultValue.hashCode() else 0
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdApiParameter (")
        printer.indent {
            print("name = "); name.print(printer); println()
            print("paramType = "); paramType.print(printer); println()
            print("source = "); source.print(printer); println()
            print("required = "); required.print(printer); println()
            print("defaultValue = "); defaultValue.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [RouteXModel.kt:43]
 */
data class RdApiSchema (
    val typeName: String,
    val properties: List<RdApiSchemaProperty>,
    val isArray: Boolean,
    val isNullable: Boolean
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdApiSchema> {
        override val _type: KClass<RdApiSchema> = RdApiSchema::class
        override val id: RdId get() = RdId(552673157769132086)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdApiSchema  {
            val typeName = buffer.readString()
            val properties = buffer.readList { RdApiSchemaProperty.read(ctx, buffer) }
            val isArray = buffer.readBool()
            val isNullable = buffer.readBool()
            return RdApiSchema(typeName, properties, isArray, isNullable)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdApiSchema)  {
            buffer.writeString(value.typeName)
            buffer.writeList(value.properties) { v -> RdApiSchemaProperty.write(ctx, buffer, v) }
            buffer.writeBool(value.isArray)
            buffer.writeBool(value.isNullable)
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdApiSchema
        
        if (typeName != other.typeName) return false
        if (properties != other.properties) return false
        if (isArray != other.isArray) return false
        if (isNullable != other.isNullable) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + typeName.hashCode()
        __r = __r*31 + properties.hashCode()
        __r = __r*31 + isArray.hashCode()
        __r = __r*31 + isNullable.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdApiSchema (")
        printer.indent {
            print("typeName = "); typeName.print(printer); println()
            print("properties = "); properties.print(printer); println()
            print("isArray = "); isArray.print(printer); println()
            print("isNullable = "); isNullable.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [RouteXModel.kt:36]
 */
data class RdApiSchemaProperty (
    val name: String,
    val propType: String,
    val required: Boolean,
    val validationHints: List<String>
) : IPrintable {
    //companion
    
    companion object : IMarshaller<RdApiSchemaProperty> {
        override val _type: KClass<RdApiSchemaProperty> = RdApiSchemaProperty::class
        override val id: RdId get() = RdId(-216361072668924885)
        
        @Suppress("UNCHECKED_CAST")
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdApiSchemaProperty  {
            val name = buffer.readString()
            val propType = buffer.readString()
            val required = buffer.readBool()
            val validationHints = buffer.readList { buffer.readString() }
            return RdApiSchemaProperty(name, propType, required, validationHints)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdApiSchemaProperty)  {
            buffer.writeString(value.name)
            buffer.writeString(value.propType)
            buffer.writeBool(value.required)
            buffer.writeList(value.validationHints) { v -> buffer.writeString(v) }
        }
        
        
    }
    //fields
    //methods
    //initializer
    //secondary constructor
    //equals trait
    override fun equals(other: Any?): Boolean  {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        
        other as RdApiSchemaProperty
        
        if (name != other.name) return false
        if (propType != other.propType) return false
        if (required != other.required) return false
        if (validationHints != other.validationHints) return false
        
        return true
    }
    //hash code trait
    override fun hashCode(): Int  {
        var __r = 0
        __r = __r*31 + name.hashCode()
        __r = __r*31 + propType.hashCode()
        __r = __r*31 + required.hashCode()
        __r = __r*31 + validationHints.hashCode()
        return __r
    }
    //pretty print
    override fun print(printer: PrettyPrinter)  {
        printer.println("RdApiSchemaProperty (")
        printer.indent {
            print("name = "); name.print(printer); println()
            print("propType = "); propType.print(printer); println()
            print("required = "); required.print(printer); println()
            print("validationHints = "); validationHints.print(printer); println()
        }
        printer.print(")")
    }
    //deepClone
    //contexts
    //threading
}


/**
 * #### Generated from [RouteXModel.kt:10]
 */
enum class RdHttpMethod {
    GET, 
    POST, 
    PUT, 
    DELETE, 
    PATCH, 
    HEAD, 
    OPTIONS;
    
    companion object : IMarshaller<RdHttpMethod> {
        val marshaller = FrameworkMarshallers.enum<RdHttpMethod>()
        
        
        override val _type: KClass<RdHttpMethod> = RdHttpMethod::class
        override val id: RdId get() = RdId(-1313687366202989810)
        
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdHttpMethod {
            return marshaller.read(ctx, buffer)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdHttpMethod)  {
            marshaller.write(ctx, buffer, value)
        }
    }
}


/**
 * #### Generated from [RouteXModel.kt:20]
 */
enum class RdParameterSource {
    PATH, 
    QUERY, 
    BODY, 
    HEADER, 
    FORM;
    
    companion object : IMarshaller<RdParameterSource> {
        val marshaller = FrameworkMarshallers.enum<RdParameterSource>()
        
        
        override val _type: KClass<RdParameterSource> = RdParameterSource::class
        override val id: RdId get() = RdId(3123662564314655935)
        
        override fun read(ctx: SerializationCtx, buffer: AbstractBuffer): RdParameterSource {
            return marshaller.read(ctx, buffer)
        }
        
        override fun write(ctx: SerializationCtx, buffer: AbstractBuffer, value: RdParameterSource)  {
            marshaller.write(ctx, buffer, value)
        }
    }
}

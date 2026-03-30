package model.rider

import com.jetbrains.rd.generator.nova.*
import com.jetbrains.rd.generator.nova.PredefinedType.*
import com.jetbrains.rider.model.nova.ide.SolutionModel

@Suppress("unused")
object RouteXModel : Ext(SolutionModel.Solution) {

    val RdHttpMethod = enum {
        +"GET"
        +"POST"
        +"PUT"
        +"DELETE"
        +"PATCH"
        +"HEAD"
        +"OPTIONS"
    }

    val RdParameterSource = enum {
        +"PATH"
        +"QUERY"
        +"BODY"
        +"HEADER"
        +"FORM"
    }

    val RdApiParameter = structdef {
        field("name", string)
        field("paramType", string)
        field("source", RdParameterSource)
        field("required", bool)
        field("defaultValue", string.nullable)
    }

    val RdApiSchemaProperty = structdef {
        field("name", string)
        field("propType", string)
        field("required", bool)
        field("validationHints", immutableList(string))
    }

    val RdApiSchema = structdef {
        field("typeName", string)
        field("properties", immutableList(RdApiSchemaProperty))
        field("isArray", bool)
        field("isNullable", bool)
    }

    val RdApiEndpoint = structdef {
        field("id", string)
        field("httpMethod", RdHttpMethod)
        field("route", string)
        field("filePath", string)
        field("lineNumber", int)
        field("controllerName", string.nullable)
        field("methodName", string)
        field("parameters", immutableList(RdApiParameter))
        field("bodySchema", RdApiSchema.nullable)
        field("authRequired", bool)
        field("authPolicy", string.nullable)
        field("contentHash", string)
        field("analysisConfidence", float)
        field("analysisWarnings", immutableList(string))
    }

    init {
        call("getEndpoints", void, immutableList(RdApiEndpoint)).async
        signal("endpointsUpdated", immutableList(RdApiEndpoint))
    }
}

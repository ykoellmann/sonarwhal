using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using JetBrains.DocumentModel;
using JetBrains.ReSharper.Psi;
using JetBrains.ReSharper.Psi.CSharp.Tree;
using JetBrains.ReSharper.Psi.Tree;

namespace ReSharperPlugin.Routex
{
    public class ControllerVisitor : TreeNodeVisitor
    {
        private readonly string _filePath;

        public List<RoutexEndpoint> DetectedEndpoints { get; } = new List<RoutexEndpoint>();

        private static readonly string[] HttpMethodAttributeNames =
        {
            "HttpGet", "HttpPost", "HttpPut", "HttpDelete", "HttpPatch", "HttpHead", "HttpOptions"
        };

        public ControllerVisitor(string filePath)
        {
            _filePath = filePath;
        }

        public override void VisitClassDeclaration(IClassDeclaration classDeclaration)
        {
            if (!IsController(classDeclaration))
            {
                base.VisitClassDeclaration(classDeclaration);
                return;
            }

            var controllerName = classDeclaration.DeclaredName;
            var controllerRoute = GetRouteTemplate(classDeclaration);
            var controllerAuth = GetAuthInfo(classDeclaration);

            foreach (var member in classDeclaration.MemberDeclarations)
            {
                if (!(member is IMethodDeclaration methodDecl)) continue;

                var (httpMethod, methodRoute) = GetHttpMethodAndRoute(methodDecl);
                if (httpMethod == null) continue;

                var fullRoute = BuildFullRoute(controllerRoute, methodRoute, controllerName);
                var parameters = ExtractParameters(methodDecl, fullRoute);
                var auth = GetAuthInfo(methodDecl) ?? controllerAuth;
                var warnings = new List<string>();

                if (fullRoute.Contains("{*") || fullRoute.Contains("[action]"))
                    warnings.Add("Route may contain dynamic segments that could not be fully resolved");

                var endpointId = ComputeHash(
                    $"{_filePath}:{classDeclaration.GetTreeStartOffset().Offset}:{methodDecl.GetTreeStartOffset().Offset}");
                var contentHash = ComputeHash(
                    $"{httpMethod}:{fullRoute}:{string.Join(",", parameters.Select(p => $"{p.Name}:{p.ParamType}"))}");

                DetectedEndpoints.Add(new RoutexEndpoint
                {
                    Id = endpointId,
                    HttpMethod = ParseHttpMethod(httpMethod),
                    Route = fullRoute,
                    FilePath = _filePath,
                    LineNumber = GetLineNumber(methodDecl),
                    ControllerName = controllerName,
                    MethodName = methodDecl.DeclaredName,
                    Parameters = parameters,
                    BodySchema = BuildBodySchema(parameters),
                    AuthRequired = auth?.Required ?? false,
                    AuthPolicy = auth?.Policy,
                    ContentHash = contentHash,
                    AnalysisConfidence = 0.9f,
                    AnalysisWarnings = warnings
                });
            }

            base.VisitClassDeclaration(classDeclaration);
        }

        private bool IsController(IClassDeclaration classDecl)
        {
            if (HasAttribute(classDecl, "ApiController")) return true;
            if (classDecl.DeclaredName.EndsWith("Controller")) return true;
            return false;
        }

        private string GetRouteTemplate(IClassDeclaration classDecl) =>
            GetAttributeStringValue(classDecl, "Route") ?? string.Empty;

        private (string httpMethod, string route) GetHttpMethodAndRoute(IMethodDeclaration methodDecl)
        {
            foreach (var attrName in HttpMethodAttributeNames)
            {
                if (!HasAttribute(methodDecl, attrName)) continue;
                var method = attrName.Substring(4).ToUpperInvariant();
                var route = GetAttributeStringValue(methodDecl, attrName) ?? string.Empty;
                return (method, route);
            }
            return (null, null);
        }

        private string BuildFullRoute(string controllerRoute, string methodRoute, string controllerName)
        {
            var ctrlShortName = controllerName.EndsWith("Controller")
                ? controllerName.Substring(0, controllerName.Length - "Controller".Length)
                : controllerName;

            var baseRoute = controllerRoute
                .Replace("[controller]", ctrlShortName.ToLower())
                .Replace("[Controller]", ctrlShortName)
                .TrimStart('/');

            methodRoute = (methodRoute ?? string.Empty).TrimStart('/');

            if (string.IsNullOrEmpty(baseRoute)) return "/" + methodRoute;
            if (string.IsNullOrEmpty(methodRoute)) return "/" + baseRoute;
            return "/" + baseRoute + "/" + methodRoute;
        }

        private List<RoutexParameter> ExtractParameters(IMethodDeclaration methodDecl, string route)
        {
            var result = new List<RoutexParameter>();

            foreach (var param in methodDecl.ParameterDeclarationsEnumerable)
            {
                var name = param.DeclaredName;
                var typeName = param.Type?.GetLongPresentableName(param.Language) ?? "object";

                if (HasParameterAttribute(param, "FromBody"))
                    result.Add(new RoutexParameter { Name = name, ParamType = typeName, Source = RoutexParameterSource.BODY, Required = true });
                else if (HasParameterAttribute(param, "FromQuery"))
                    result.Add(new RoutexParameter { Name = name, ParamType = typeName, Source = RoutexParameterSource.QUERY, Required = false });
                else if (HasParameterAttribute(param, "FromHeader"))
                    result.Add(new RoutexParameter { Name = name, ParamType = typeName, Source = RoutexParameterSource.HEADER, Required = true });
                else if (HasParameterAttribute(param, "FromRoute") || route.Contains($"{{{name}}}"))
                    result.Add(new RoutexParameter { Name = name, ParamType = typeName, Source = RoutexParameterSource.PATH, Required = !route.Contains($"{{{name}?}}") });
                else if (IsSimpleType(typeName) && !route.Contains($"{{{name}}}"))
                    result.Add(new RoutexParameter { Name = name, ParamType = typeName, Source = RoutexParameterSource.QUERY, Required = false });
                else
                    result.Add(new RoutexParameter { Name = name, ParamType = typeName, Source = RoutexParameterSource.BODY, Required = true });
            }

            return result;
        }

        private RoutexSchema BuildBodySchema(List<RoutexParameter> parameters)
        {
            var body = parameters.FirstOrDefault(p => p.Source == RoutexParameterSource.BODY);
            if (body == null) return null;

            return new RoutexSchema
            {
                TypeName = body.ParamType,
                IsArray = body.ParamType.StartsWith("IEnumerable<") || body.ParamType.EndsWith("[]") || body.ParamType.StartsWith("List<"),
                IsNullable = false
            };
        }

        private class AuthResult
        {
            public bool Required { get; set; }
            public string Policy { get; set; }
        }

        private AuthResult GetAuthInfo(IDeclaration decl)
        {
            if (HasAttribute(decl, "AllowAnonymous")) return new AuthResult { Required = false };
            if (HasAttribute(decl, "Authorize"))
                return new AuthResult { Required = true, Policy = GetAttributeStringValue(decl, "Authorize") };
            return null;
        }

        private bool HasAttribute(IDeclaration decl, string attrName)
        {
            if (!(decl is IAttributesOwner owner)) return false;
            return owner.GetAttributeInstances(AttributesSource.All)
                .Any(a =>
                {
                    var s = a.GetAttributeType().GetClrName().ShortName;
                    return s == attrName || s == attrName + "Attribute";
                });
        }

        private bool HasParameterAttribute(IParameterDeclaration param, string attrName)
        {
            if (!(param is IAttributesOwner owner)) return false;
            return owner.GetAttributeInstances(AttributesSource.All)
                .Any(a =>
                {
                    var s = a.GetAttributeType().GetClrName().ShortName;
                    return s == attrName || s == attrName + "Attribute";
                });
        }

        private string GetAttributeStringValue(IDeclaration decl, string attrName)
        {
            if (!(decl is IAttributesOwner owner)) return null;
            var attr = owner.GetAttributeInstances(AttributesSource.All)
                .FirstOrDefault(a =>
                {
                    var s = a.GetAttributeType().GetClrName().ShortName;
                    return s == attrName || s == attrName + "Attribute";
                });
            if (attr == null) return null;
            var p = attr.PositionParameter(0);
            return p.IsConstant ? p.ConstantValue.StringValue : null;
        }

        private bool IsSimpleType(string t) =>
            t is "int" or "long" or "string" or "bool" or "double" or "float" or "decimal"
                or "Guid" or "DateTime" or "int?" or "long?" or "bool?" or "double?" or "float?";

        private int GetLineNumber(ITreeNode node)
        {
            var doc = node.GetContainingFile()?.GetSourceFile()?.Document;
            if (doc == null) return 0;
            // Line is Int32<DocLine> (0-based); cast to int before arithmetic
            return (int)doc.GetCoordsByOffset(node.GetTreeStartOffset().Offset).Line + 1;
        }

        private RoutexHttpMethod ParseHttpMethod(string method) => method switch
        {
            "GET"     => RoutexHttpMethod.GET,
            "POST"    => RoutexHttpMethod.POST,
            "PUT"     => RoutexHttpMethod.PUT,
            "DELETE"  => RoutexHttpMethod.DELETE,
            "PATCH"   => RoutexHttpMethod.PATCH,
            "HEAD"    => RoutexHttpMethod.HEAD,
            "OPTIONS" => RoutexHttpMethod.OPTIONS,
            _         => RoutexHttpMethod.GET
        };

        private static string ComputeHash(string input)
        {
            using var sha = SHA256.Create();
            return Convert.ToBase64String(sha.ComputeHash(Encoding.UTF8.GetBytes(input))).Substring(0, 8);
        }
    }
}

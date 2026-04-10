using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using JetBrains.ReSharper.Psi;
using JetBrains.ReSharper.Psi.CSharp;
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

                // [NonAction] explicitly opts the method out of routing.
                if (HasAttribute(methodDecl, "NonAction")) continue;

                var (httpMethod, methodRoute) = GetHttpMethodAndRoute(methodDecl);
                if (httpMethod == null) continue;

                // [ActionName("customName")] overrides the name used in [action] template tokens.
                var actionName = GetAttributeStringValue(methodDecl, "ActionName") ?? methodDecl.DeclaredName;

                var fullRoute = BuildFullRoute(controllerRoute, methodRoute, controllerName, actionName);
                var parameters = ExtractParameters(methodDecl, fullRoute);
                var auth = GetAuthInfo(methodDecl) ?? controllerAuth;
                var warnings = new List<string>();

                if (fullRoute.Contains("{*"))
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
                    BodySchema = BuildBodySchema(parameters, methodDecl),
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

        private string BuildFullRoute(string controllerRoute, string methodRoute, string controllerName, string actionName)
        {
            var ctrlShortName = controllerName.EndsWith("Controller")
                ? controllerName.Substring(0, controllerName.Length - "Controller".Length)
                : controllerName;

            var baseRoute = controllerRoute
                .Replace("[controller]", ctrlShortName.ToLower())
                .Replace("[Controller]", ctrlShortName)
                .Replace("[action]", actionName.ToLower())
                .Replace("[Action]", actionName)
                .TrimStart('/');

            methodRoute = (methodRoute ?? string.Empty)
                .Replace("[action]", actionName.ToLower())
                .Replace("[Action]", actionName)
                .TrimStart('/');

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
                else if (HasParameterAttribute(param, "FromRoute") || IsInRoute(name, route))
                    result.Add(new RoutexParameter { Name = name, ParamType = typeName, Source = RoutexParameterSource.PATH, Required = !IsOptionalInRoute(name, route) });
                else if (IsSimpleType(typeName) && !IsInRoute(name, route))
                    result.Add(new RoutexParameter { Name = name, ParamType = typeName, Source = RoutexParameterSource.QUERY, Required = false });
                else
                    result.Add(new RoutexParameter { Name = name, ParamType = typeName, Source = RoutexParameterSource.BODY, Required = true });
            }

            return result;
        }

        private RoutexSchema BuildBodySchema(List<RoutexParameter> parameters, IMethodDeclaration methodDecl)
        {
            var body = parameters.FirstOrDefault(p => p.Source == RoutexParameterSource.BODY);
            if (body == null) return null;

            var isArray = body.ParamType.StartsWith("IEnumerable<") || body.ParamType.EndsWith("[]") || body.ParamType.StartsWith("List<");

            var bodyParamDecl = methodDecl.ParameterDeclarationsEnumerable
                .FirstOrDefault(p => p.DeclaredName == body.Name);

            // ResolveClassProperties uses DeclaredElement (semantic) — wrap in try/catch
            // so that a missing CompilationContextCookie never kills endpoint detection.
            List<RoutexSchemaProperty> props;
            try { props = ResolveClassProperties(bodyParamDecl); }
            catch { props = new List<RoutexSchemaProperty>(); }

            return new RoutexSchema
            {
                TypeName = body.ParamType,
                IsArray = isArray,
                IsNullable = false,
                Properties = props
            };
        }

        private List<RoutexSchemaProperty> ResolveClassProperties(IParameterDeclaration paramDecl)
        {
            if (paramDecl == null) return new List<RoutexSchemaProperty>();

            var parameter = paramDecl.DeclaredElement as IParameter;
            if (parameter == null) return new List<RoutexSchemaProperty>();

            var classType = (parameter.Type as IDeclaredType)?.GetTypeElement() as IClass;
            if (classType == null) return new List<RoutexSchemaProperty>();

            var result = new List<RoutexSchemaProperty>();
            foreach (var prop in classType.Properties)
            {
                if (prop.IsStatic) continue;
                if (prop.GetAccessRights() != AccessRights.PUBLIC) continue;

                var propType = prop.Type.GetLongPresentableName(CSharpLanguage.Instance);
                var required = prop.GetAttributeInstances(AttributesSource.All)
                    .Any(a =>
                    {
                        var s = a.GetAttributeType().GetClrName().ShortName;
                        return s == "Required" || s == "RequiredAttribute";
                    });

                var hints = new List<string>();
                foreach (var attr in prop.GetAttributeInstances(AttributesSource.All))
                {
                    var s = attr.GetAttributeType().GetClrName().ShortName;
                    if (s == "StringLength" || s == "StringLengthAttribute")
                    {
                        var p0 = attr.PositionParameter(0);
                        if (p0.IsConstant) hints.Add($"maxLength:{p0.ConstantValue.Value}");
                    }
                    else if (s == "MinLength" || s == "MinLengthAttribute")
                    {
                        var p0 = attr.PositionParameter(0);
                        if (p0.IsConstant) hints.Add($"minLength:{p0.ConstantValue.Value}");
                    }
                    else if (s == "MaxLength" || s == "MaxLengthAttribute")
                    {
                        var p0 = attr.PositionParameter(0);
                        if (p0.IsConstant) hints.Add($"maxLength:{p0.ConstantValue.Value}");
                    }
                    else if (s == "Range" || s == "RangeAttribute")
                    {
                        var p0 = attr.PositionParameter(0);
                        var p1 = attr.PositionParameter(1);
                        if (p0.IsConstant && p1.IsConstant)
                            hints.Add($"range:{p0.ConstantValue.Value}..{p1.ConstantValue.Value}");
                    }
                }

                result.Add(new RoutexSchemaProperty
                {
                    Name = prop.ShortName,
                    PropType = propType,
                    Required = required,
                    ValidationHints = hints
                });
            }

            return result;
        }

        private class AuthResult
        {
            public bool Required { get; set; }
            public string Policy { get; set; }
        }

        private AuthResult GetAuthInfo(IAttributesOwnerDeclaration decl)
        {
            if (HasAttribute(decl, "AllowAnonymous")) return new AuthResult { Required = false };
            if (HasAttribute(decl, "Authorize"))
                return new AuthResult { Required = true, Policy = GetAttributeStringValue(decl, "Authorize") };
            return null;
        }

        // ── Syntactic helpers ─────────────────────────────────────────────────
        // These use only the PSI syntax tree so they work without a
        // CompilationContextCookie (no DeclaredElement resolution required).

        private static bool AttributeNameMatches(IAttribute attr, string name)
        {
            // IReferenceName.ShortName is the identifier as written (e.g. "HttpGet")
            // without any namespace prefix, so this handles both [HttpGet] and [HttpGetAttribute].
            var shortName = attr.Descendants<IReferenceName>().FirstOrDefault()?.ShortName ?? "";
            return shortName == name || shortName == name + "Attribute";
        }

        private static bool HasAttribute(IAttributesOwnerDeclaration decl, string attrName) =>
            decl.Attributes.Any(a => AttributeNameMatches(a, attrName));

        private static bool HasParameterAttribute(IParameterDeclaration param, string attrName)
        {
            foreach (var a in param.Descendants<IAttribute>())
                if (AttributeNameMatches(a, attrName)) return true;
            return false;
        }

        private static string GetAttributeStringValue(IAttributesOwnerDeclaration decl, string attrName)
        {
            var attr = decl.Attributes.FirstOrDefault(a => AttributeNameMatches(a, attrName));
            if (attr == null) return null;
            // ConstantValue on a string literal is purely syntactic — no cookie needed.
            return attr.Descendants<ICSharpLiteralExpression>().FirstOrDefault()?.ConstantValue?.StringValue;
        }

        // Matches {name}, {name?}, {name:constraint}, {name:constraint?}
        private static bool IsInRoute(string name, string route) =>
            Regex.IsMatch(route, $@"\{{{Regex.Escape(name)}(?::[^}}]*)?\??}}");

        // Matches only the optional variants: {name?} or {name:constraint?}
        private static bool IsOptionalInRoute(string name, string route) =>
            Regex.IsMatch(route, $@"\{{{Regex.Escape(name)}(?::[^}}]*)?\?}}");

        private bool IsSimpleType(string t) =>
            t is "int" or "long" or "string" or "bool" or "double" or "float" or "decimal"
                or "Guid" or "DateTime" or "int?" or "long?" or "bool?" or "double?" or "float?";

        private int GetLineNumber(IMethodDeclaration methodDecl)
        {
            // All PSI tree-offset approaches (GetTreeStartOffset, GetDocumentRange) produce
            // incorrect line numbers for file-scoped namespaces (C# 10) in some SDK versions.
            // Scanning the source file text directly for the method declaration is reliable
            // regardless of namespace style.
            var name = methodDecl.DeclaredName;
            if (string.IsNullOrEmpty(name)) return 0;
            var path = _filePath;
            if (string.IsNullOrEmpty(path)) return 0;

            try
            {
                var lines = System.IO.File.ReadAllLines(path);
                for (var i = 0; i < lines.Length; i++)
                {
                    var line = lines[i];
                    var idx = line.IndexOf(name, StringComparison.Ordinal);
                    if (idx < 0) continue;

                    // Skip method calls: anything preceded by a '.' (e.g. obj.Method())
                    if (idx > 0 && line[idx - 1] == '.') continue;

                    // Must be directly followed by '(' (declaration or call without spaces
                    // — RestController actions never have spaces between name and paren in practice)
                    var after = line.Substring(idx + name.Length).TrimStart();
                    if (!after.StartsWith("(")) continue;

                    // Skip comment lines
                    var trimmed = line.TrimStart();
                    if (trimmed.StartsWith("//") || trimmed.StartsWith("*")) continue;

                    // Skip nameof(...) — name would be inside nameof
                    if (idx >= 7 && line.Substring(idx - 7, 7) == "nameof(") continue;

                    return i + 1; // 1-based
                }
            }
            catch
            {
                // Fall through and return 0 on any I/O error
            }

            return 0;
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

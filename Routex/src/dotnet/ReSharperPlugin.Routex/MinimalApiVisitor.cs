using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using JetBrains.DocumentModel;
using JetBrains.ReSharper.Psi.CSharp.Tree;
using JetBrains.ReSharper.Psi.Tree;

namespace ReSharperPlugin.Routex
{
    /// <summary>
    /// Detects ASP.NET Core Minimal API endpoints:
    ///   app.MapGet("/path", handler);
    ///   app.MapPost("/path", handler);  etc.
    /// </summary>
    public class MinimalApiVisitor : TreeNodeVisitor
    {
        private readonly string _filePath;

        public List<RoutexEndpoint> DetectedEndpoints { get; } = new List<RoutexEndpoint>();

        private static readonly Dictionary<string, RoutexHttpMethod> MapMethodNames =
            new Dictionary<string, RoutexHttpMethod>
            {
                { "MapGet",    RoutexHttpMethod.GET    },
                { "MapPost",   RoutexHttpMethod.POST   },
                { "MapPut",    RoutexHttpMethod.PUT    },
                { "MapDelete", RoutexHttpMethod.DELETE },
                { "MapPatch",  RoutexHttpMethod.PATCH  },
            };

        public MinimalApiVisitor(string filePath)
        {
            _filePath = filePath;
        }

        public override void VisitInvocationExpression(IInvocationExpression invocationExpression)
        {
            var methodName = (invocationExpression.InvokedExpression as IReferenceExpression)
                ?.NameIdentifier?.Name;

            if (methodName == null || !MapMethodNames.TryGetValue(methodName, out var httpMethod))
            {
                base.VisitInvocationExpression(invocationExpression);
                return;
            }

            var args = invocationExpression.ArgumentsEnumerable.ToList();
            if (args.Count < 2)
            {
                base.VisitInvocationExpression(invocationExpression);
                return;
            }

            var route = (args[0].Value as ICSharpLiteralExpression)?.ConstantValue?.StringValue;
            if (route == null)
            {
                base.VisitInvocationExpression(invocationExpression);
                return;
            }

            var lineNumber = GetLineNumber(invocationExpression);
            var endpointId = ComputeHash($"{_filePath}:{invocationExpression.GetTreeStartOffset().Offset}");
            var contentHash = ComputeHash($"{httpMethod}:{route}");

            DetectedEndpoints.Add(new RoutexEndpoint
            {
                Id = endpointId,
                HttpMethod = httpMethod,
                Route = route.StartsWith("/") ? route : "/" + route,
                FilePath = _filePath,
                LineNumber = lineNumber,
                ControllerName = null,
                MethodName = route.TrimStart('/'),
                Parameters = ExtractPathParameters(route),
                BodySchema = null,
                AuthRequired = false,
                AuthPolicy = null,
                ContentHash = contentHash,
                AnalysisConfidence = 0.8f,
                AnalysisWarnings = new List<string>()
            });

            base.VisitInvocationExpression(invocationExpression);
        }

        private List<RoutexParameter> ExtractPathParameters(string route)
        {
            var parameters = new List<RoutexParameter>();

            foreach (var segment in route.Split('/'))
            {
                if (!segment.StartsWith("{") || !segment.Contains("}")) continue;

                var inner = segment.TrimStart('{').Split('}')[0];
                var isOptional = inner.EndsWith("?");
                var name = inner.TrimEnd('?').Split(':')[0]; // strip route constraints like {id:int}

                parameters.Add(new RoutexParameter
                {
                    Name = name,
                    ParamType = "string",
                    Source = RoutexParameterSource.PATH,
                    Required = !isOptional,
                    DefaultValue = null
                });
            }

            return parameters;
        }

        private int GetLineNumber(ITreeNode node)
        {
            var doc = node.GetContainingFile()?.GetSourceFile()?.Document;
            if (doc == null) return 0;
            // Line is Int32<DocLine> (0-based); cast to int before arithmetic
            return (int)doc.GetCoordsByOffset(node.GetTreeStartOffset().Offset).Line + 1;
        }

        private static string ComputeHash(string input)
        {
            using var sha = SHA256.Create();
            return Convert.ToBase64String(sha.ComputeHash(Encoding.UTF8.GetBytes(input))).Substring(0, 8);
        }
    }
}

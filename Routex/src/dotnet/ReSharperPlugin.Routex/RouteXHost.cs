using System;
using System.Collections.Generic;
using System.Linq;
using JetBrains.Application.Parts;
using JetBrains.Application.Threading;
using JetBrains.Lifetimes;
using JetBrains.ProjectModel;
using JetBrains.Rd.Tasks;
using JetBrains.ReSharper.Feature.Services.Protocol;
using JetBrains.Rider.Model;
using JetBrains.Util;
using JetBrains.Util.Logging;

namespace ReSharperPlugin.Routex
{
    [SolutionComponent(Instantiation.ContainerAsyncPrimaryThread)]
    public class RouteXHost
    {
        private readonly ILogger _logger = Logger.GetLogger<RouteXHost>();

        public RouteXHost(
            Lifetime lifetime,
            ISolution solution,
            IShellLocks shellLocks,
            EndpointDetector detector)
        {
            var model = solution.GetProtocolSolution().GetRouteXModel();

            // Handle getEndpoints call from the Kotlin frontend
            model.GetEndpoints.Set((lt, _) =>
            {
                var task = new RdTask<List<RdApiEndpoint>>();

                shellLocks.ExecuteOrQueueReadLock(lt, "RouteX.GetEndpoints", () =>
                {
                    try
                    {
                        var endpoints = detector.DetectAllEndpoints();
                        task.Set(ConvertEndpoints(endpoints));
                    }
                    catch (Exception ex)
                    {
                        _logger.Error(ex, "RouteX: Failed to detect endpoints");
                        task.Set(new List<RdApiEndpoint>());
                    }
                });

                return task;
            });
        }

        private static List<RdApiEndpoint> ConvertEndpoints(List<RoutexEndpoint> endpoints) =>
            endpoints.Select(e => new RdApiEndpoint(
                id: e.Id ?? "",
                httpMethod: ConvertHttpMethod(e.HttpMethod),
                route: e.Route ?? "",
                filePath: e.FilePath ?? "",
                lineNumber: e.LineNumber,
                controllerName: e.ControllerName,
                methodName: e.MethodName ?? "",
                parameters: e.Parameters.Select(ConvertParameter).ToList(),
                bodySchema: e.BodySchema != null ? ConvertSchema(e.BodySchema) : null,
                authRequired: e.AuthRequired,
                authPolicy: e.AuthPolicy,
                contentHash: e.ContentHash ?? "",
                analysisConfidence: e.AnalysisConfidence,
                analysisWarnings: e.AnalysisWarnings ?? new List<string>()
            )).ToList();

        private static RdHttpMethod ConvertHttpMethod(RoutexHttpMethod m) => m switch
        {
            RoutexHttpMethod.POST => RdHttpMethod.POST,
            RoutexHttpMethod.PUT => RdHttpMethod.PUT,
            RoutexHttpMethod.DELETE => RdHttpMethod.DELETE,
            RoutexHttpMethod.PATCH => RdHttpMethod.PATCH,
            RoutexHttpMethod.HEAD => RdHttpMethod.HEAD,
            RoutexHttpMethod.OPTIONS => RdHttpMethod.OPTIONS,
            _ => RdHttpMethod.GET
        };

        private static RdApiParameter ConvertParameter(RoutexParameter p) =>
            new RdApiParameter(
                name: p.Name ?? "",
                paramType: p.ParamType ?? "",
                source: ConvertParameterSource(p.Source),
                required: p.Required,
                defaultValue: p.DefaultValue
            );

        private static RdParameterSource ConvertParameterSource(RoutexParameterSource s) => s switch
        {
            RoutexParameterSource.PATH => RdParameterSource.PATH,
            RoutexParameterSource.BODY => RdParameterSource.BODY,
            RoutexParameterSource.HEADER => RdParameterSource.HEADER,
            RoutexParameterSource.FORM => RdParameterSource.FORM,
            _ => RdParameterSource.QUERY
        };

        private static RdApiSchema ConvertSchema(RoutexSchema s) =>
            new RdApiSchema(
                typeName: s.TypeName ?? "",
                properties: s.Properties.Select(p => new RdApiSchemaProperty(
                    name: p.Name ?? "",
                    propType: p.PropType ?? "",
                    required: p.Required,
                    validationHints: p.ValidationHints ?? new List<string>()
                )).ToList(),
                isArray: s.IsArray,
                isNullable: s.IsNullable
            );
    }
}

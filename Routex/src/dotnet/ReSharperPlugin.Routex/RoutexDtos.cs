using System.Collections.Generic;

namespace ReSharperPlugin.Routex
{
    /// <summary>
    /// Local C# DTOs for the PSI analysis layer.
    /// These are independent of the RD protocol (rdgen-generated types).
    /// RouteXHost converts these to Rd model types at the protocol boundary.
    /// </summary>

    public enum RoutexHttpMethod
    {
        GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS
    }

    public enum RoutexParameterSource
    {
        PATH, QUERY, BODY, HEADER, FORM
    }

    public class RoutexEndpoint
    {
        public string Id { get; set; }
        public RoutexHttpMethod HttpMethod { get; set; }
        public string Route { get; set; }
        public string FilePath { get; set; }
        public int LineNumber { get; set; }
        public string ControllerName { get; set; }
        public string MethodName { get; set; }
        public List<RoutexParameter> Parameters { get; set; } = new List<RoutexParameter>();
        public RoutexSchema BodySchema { get; set; }
        public bool AuthRequired { get; set; }
        public string AuthPolicy { get; set; }
        public string ContentHash { get; set; }
        public float AnalysisConfidence { get; set; }
        public List<string> AnalysisWarnings { get; set; } = new List<string>();
    }

    public class RoutexParameter
    {
        public string Name { get; set; }
        public string ParamType { get; set; }
        public RoutexParameterSource Source { get; set; }
        public bool Required { get; set; }
        public string DefaultValue { get; set; }
    }

    public class RoutexSchema
    {
        public string TypeName { get; set; }
        public bool IsArray { get; set; }
        public bool IsNullable { get; set; }
    }
}

using JetBrains.ProjectModel;
using JetBrains.ReSharper.Psi.Search;

namespace ReSharperPlugin.Sonarwhale
{
    public class FindResultSonarwhaleEndpoint : FindResult
    {
        public string EndpointId { get; }
        public string HttpMethod { get; }
        public string Route { get; }
        public ISolution Solution { get; }

        public FindResultSonarwhaleEndpoint(string endpointId, string httpMethod, string route, ISolution solution)
        {
            EndpointId = endpointId;
            HttpMethod = httpMethod;
            Route = route;
            Solution = solution;
        }

        public override bool Equals(object obj) =>
            obj is FindResultSonarwhaleEndpoint other && EndpointId == other.EndpointId;

        public override int GetHashCode() => EndpointId?.GetHashCode() ?? 0;
    }
}

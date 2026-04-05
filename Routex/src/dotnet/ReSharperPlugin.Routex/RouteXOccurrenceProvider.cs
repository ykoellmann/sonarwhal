using JetBrains.ReSharper.Feature.Services.Occurrences;
using JetBrains.ReSharper.Psi.Search;

namespace ReSharperPlugin.Routex
{
    [OccurrenceProvider]
    public class RouteXOccurrenceProvider : IOccurrenceProvider
    {
        public IOccurrence MakeOccurrence(FindResult findResult)
        {
            if (findResult is FindResultRouteXEndpoint r)
                return new RouteXEndpointOccurrence(r);
            return null;
        }
    }
}

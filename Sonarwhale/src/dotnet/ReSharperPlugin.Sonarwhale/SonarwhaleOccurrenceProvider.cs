using JetBrains.ReSharper.Feature.Services.Occurrences;
using JetBrains.ReSharper.Psi.Search;

namespace ReSharperPlugin.Sonarwhale
{
    [OccurrenceProvider]
    public class SonarwhaleOccurrenceProvider : IOccurrenceProvider
    {
        public IOccurrence MakeOccurrence(FindResult findResult)
        {
            if (findResult is FindResultSonarwhaleEndpoint r)
                return new SonarwhaleEndpointOccurrence(r);
            return null;
        }
    }
}

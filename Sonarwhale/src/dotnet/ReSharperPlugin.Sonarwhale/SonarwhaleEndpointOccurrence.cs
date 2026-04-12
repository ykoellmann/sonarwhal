using JetBrains.Application.UI.PopupLayout;
using JetBrains.IDE;
using JetBrains.ProjectModel;
using JetBrains.ReSharper.Feature.Services.Occurrences;
using JetBrains.ReSharper.Feature.Services.Protocol;
using JetBrains.Rider.Model;

namespace ReSharperPlugin.Sonarwhale
{
    public class SonarwhaleEndpointOccurrence : IOccurrence, INavigatable
    {
        private readonly FindResultSonarwhaleEndpoint _findResult;

        public SonarwhaleEndpointOccurrence(FindResultSonarwhaleEndpoint findResult)
        {
            _findResult = findResult;
        }

        public ISolution GetSolution() => _findResult.Solution;

        public OccurrenceType OccurrenceType => OccurrenceType.Occurrence;

        public bool IsValid => true;

        public string DumpToString() => $"Sonarwhale: {_findResult.HttpMethod} {_findResult.Route}";

        public OccurrencePresentationOptions PresentationOptions { get; set; }

        public bool Navigate(ISolution solution, PopupWindowContextSource windowContext, bool transferFocus, TabOptions tabOptions)
        {
            solution.GetProtocolSolution().GetSonarwhaleModel().NavigateToEndpoint.Fire(_findResult.EndpointId);
            return true;
        }
    }
}

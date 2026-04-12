using JetBrains.Application.Parts;
using JetBrains.ProjectModel;
using JetBrains.ReSharper.Feature.Services.Occurrences;
using JetBrains.ReSharper.Feature.Services.Occurrences.OccurrenceInformation;
using JetBrains.ReSharper.Psi;
using JetBrains.ReSharper.Psi.Pointers;
using JetBrains.Util;

namespace ReSharperPlugin.Sonarwhale
{
    [SolutionFeaturePart(Instantiation.DemandAnyThreadSafe)]
    public class SonarwhaleOccurrenceInformationProvider : IOccurrenceInformationProvider
    {
        public IDeclaredElementEnvoy GetTypeMember(IOccurrence occurrence) => null;
        public IDeclaredElementEnvoy GetTypeElement(IOccurrence occurrence) => null;
        public IDeclaredElementEnvoy GetNamespace(IOccurrence occurrence) => null;

        public OccurrenceMergeContext GetMergeContext(IOccurrence occurrence) =>
            OccurrenceMergeContext.Empty;

        public TextRange GetTextRange(IOccurrence occurrence) => default;

        public ProjectModelElementEnvoy GetProjectModelElementEnvoy(IOccurrence occurrence) => null;

        public SourceFilePtr GetSourceFilePtr(IOccurrence occurrence) => SourceFilePtr.Fake;

        public bool IsApplicable(IOccurrence occurrence) => occurrence is SonarwhaleEndpointOccurrence;
    }
}

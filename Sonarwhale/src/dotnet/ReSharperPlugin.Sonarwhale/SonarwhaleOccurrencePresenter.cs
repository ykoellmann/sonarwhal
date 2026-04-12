using JetBrains.Application.UI.Controls.JetPopupMenu;
using JetBrains.ReSharper.Feature.Services.Occurrences;
using JetBrains.UI.RichText;

namespace ReSharperPlugin.Sonarwhale
{
    [OccurrencePresenter]
    public class SonarwhaleOccurrencePresenter : IOccurrencePresenter
    {
        public bool Present(IMenuItemDescriptor descriptor, IOccurrence occurrence, OccurrencePresentationOptions options)
        {
            if (!(occurrence is SonarwhaleEndpointOccurrence occ)) return false;
            descriptor.Text = new RichText(occ.DumpToString());
            return true;
        }

        public bool IsApplicable(IOccurrence occurrence) => occurrence is SonarwhaleEndpointOccurrence;
    }
}

using JetBrains.Application.UI.Controls.JetPopupMenu;
using JetBrains.ReSharper.Feature.Services.Occurrences;
using JetBrains.UI.RichText;

namespace ReSharperPlugin.Routex
{
    [OccurrencePresenter]
    public class RouteXOccurrencePresenter : IOccurrencePresenter
    {
        public bool Present(IMenuItemDescriptor descriptor, IOccurrence occurrence, OccurrencePresentationOptions options)
        {
            if (!(occurrence is RouteXEndpointOccurrence occ)) return false;
            descriptor.Text = new RichText(occ.DumpToString());
            return true;
        }

        public bool IsApplicable(IOccurrence occurrence) => occurrence is RouteXEndpointOccurrence;
    }
}

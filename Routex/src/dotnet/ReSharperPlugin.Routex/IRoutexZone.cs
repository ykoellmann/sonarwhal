using JetBrains.Application.BuildScript.Application.Zones;
using JetBrains.ReSharper.Psi.CSharp;

namespace ReSharperPlugin.Routex
{
    [ZoneDefinition]
    public interface IRoutexZone : IZone,
        IRequire<ILanguageCSharpZone>
    {
    }
}

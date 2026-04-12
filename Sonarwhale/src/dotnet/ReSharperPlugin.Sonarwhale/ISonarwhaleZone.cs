using JetBrains.Application.BuildScript.Application.Zones;
using JetBrains.ReSharper.Psi.CSharp;

namespace ReSharperPlugin.Sonarwhale
{
    [ZoneDefinition]
    public interface ISonarwhaleZone : IZone,
        IRequire<ILanguageCSharpZone>
    {
    }
}

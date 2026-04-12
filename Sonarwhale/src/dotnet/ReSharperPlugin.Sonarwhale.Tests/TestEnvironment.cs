using System.Threading;
using JetBrains.Application.BuildScript.Application.Zones;
using JetBrains.ReSharper.Feature.Services;
using JetBrains.ReSharper.Psi.CSharp;
using JetBrains.ReSharper.TestFramework;
using JetBrains.TestFramework;
using JetBrains.TestFramework.Application.Zones;
using NUnit.Framework;

[assembly: Apartment(ApartmentState.STA)]

namespace ReSharperPlugin.Sonarwhale.Tests
{
    [ZoneDefinition]
    public class SonarwhaleTestEnvironmentZone : ITestsEnvZone, IRequire<PsiFeatureTestZone>, IRequire<ISonarwhaleZone>
    {
    }

    [ZoneMarker]
    public class ZoneMarker : IRequire<ICodeEditingZone>, IRequire<ILanguageCSharpZone>,
        IRequire<SonarwhaleTestEnvironmentZone>
    {
    }

    [SetUpFixture]
    public class SonarwhaleTestsAssembly : ExtensionTestEnvironmentAssembly<SonarwhaleTestEnvironmentZone>
    {
    }
}
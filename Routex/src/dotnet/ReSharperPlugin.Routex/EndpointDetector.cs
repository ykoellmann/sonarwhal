using System.Collections.Generic;
using System.Linq;
using JetBrains.Application.Parts;
using JetBrains.ProjectModel;
using JetBrains.ReSharper.Psi;
using JetBrains.ReSharper.Psi.CSharp;
using JetBrains.ReSharper.Psi.CSharp.Tree;
using JetBrains.ReSharper.Psi.Files;

namespace ReSharperPlugin.Routex
{
    [SolutionComponent(Instantiation.DemandAnyThreadSafe)]
    public class EndpointDetector
    {
        private readonly ISolution _solution;

        public EndpointDetector(ISolution solution)
        {
            _solution = solution;
        }

        public List<RoutexEndpoint> DetectAllEndpoints()
        {
            var endpoints = new List<RoutexEndpoint>();

            foreach (var project in _solution.GetAllProjects())
            {
                // Skip virtual/misc projects that have no real file on disk
                if (project.ProjectFileLocation.IsEmpty) continue;

                foreach (var projectFile in project.GetAllProjectFiles())
                {
                    if (!projectFile.LanguageType.Is<CSharpProjectFileType>()) continue;

                    foreach (var sourceFile in projectFile.ToSourceFiles())
                    {
                        endpoints.AddRange(AnalyzeSourceFile(sourceFile));
                    }
                }
            }

            return endpoints;
        }

        public List<RoutexEndpoint> DetectEndpointsInFile(IProjectFile projectFile)
        {
            return projectFile.ToSourceFiles()
                .SelectMany(AnalyzeSourceFile)
                .ToList();
        }

        private List<RoutexEndpoint> AnalyzeSourceFile(IPsiSourceFile sourceFile)
        {
            var endpoints = new List<RoutexEndpoint>();

            var csharpFile = sourceFile.GetPsiFiles<CSharpLanguage>()
                .OfType<ICSharpFile>()
                .FirstOrDefault();

            if (csharpFile == null) return endpoints;

            var filePath = sourceFile.GetLocation().FullPath;

            var controllerVisitor = new ControllerVisitor(filePath);
            csharpFile.Accept(controllerVisitor);
            endpoints.AddRange(controllerVisitor.DetectedEndpoints);

            var minimalApiVisitor = new MinimalApiVisitor(filePath);
            csharpFile.Accept(minimalApiVisitor);
            endpoints.AddRange(minimalApiVisitor.DetectedEndpoints);

            return endpoints;
        }
    }
}

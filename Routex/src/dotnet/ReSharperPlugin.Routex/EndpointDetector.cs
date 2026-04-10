using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using JetBrains.Application.Parts;
using JetBrains.ProjectModel;
using JetBrains.ReSharper.Psi;
using JetBrains.ReSharper.Psi.CSharp;
using JetBrains.ReSharper.Psi.CSharp.Tree;
using JetBrains.ReSharper.Psi.Files;
using JetBrains.ReSharper.Psi.Tree;

namespace ReSharperPlugin.Routex
{
    [SolutionComponent(Instantiation.DemandAnyThreadSafe)]
    public class EndpointDetector
    {
        private readonly ISolution _solution;

        // In-memory cache: file path → (last-write-time stamp, detected endpoints)
        // Persists for the lifetime of the Rider process — first scan is always full,
        // subsequent refreshes skip unchanged files.
        private readonly Dictionary<string, string> _fileStamps = new Dictionary<string, string>();
        private readonly Dictionary<string, List<RoutexEndpoint>> _fileCache = new Dictionary<string, List<RoutexEndpoint>>();

        public EndpointDetector(ISolution solution)
        {
            _solution = solution;
        }

        public void ClearCache()
        {
            _fileStamps.Clear();
            _fileCache.Clear();
        }

        public List<RoutexEndpoint> DetectAllEndpoints()
        {
            var visitedPaths = new HashSet<string>();

            foreach (var project in _solution.GetAllProjects())
            {
                if (project.ProjectFileLocation.IsEmpty) continue;

                foreach (var projectFile in project.GetAllProjectFiles())
                {
                    if (!projectFile.LanguageType.Is<CSharpProjectFileType>()) continue;

                    foreach (var sourceFile in projectFile.ToSourceFiles())
                    {
                        var path = sourceFile.GetLocation().FullPath;
                        visitedPaths.Add(path);

                        var stamp = GetFileStamp(path);
                        if (_fileStamps.TryGetValue(path, out var cached) && cached == stamp)
                            continue; // file unchanged — reuse cached result

                        try
                        {
                            var endpoints = AnalyzeSourceFile(sourceFile);
                            _fileStamps[path] = stamp;
                            _fileCache[path] = endpoints;
                        }
                        catch (Exception)
                        {
                            // One bad file must never abort the entire scan.
                            // Cache the stamp so we don't retry until the file is saved again.
                            _fileStamps[path] = stamp;
                            _fileCache[path] = new List<RoutexEndpoint>();
                        }
                    }
                }
            }

            // Evict entries for files that no longer exist in the solution
            var stalePaths = _fileStamps.Keys.Except(visitedPaths).ToList();
            foreach (var p in stalePaths) { _fileStamps.Remove(p); _fileCache.Remove(p); }

            return _fileCache.Values.SelectMany(x => x).ToList();
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

            // All attribute and type resolution (DeclaredElement, IAttributesOwner, etc.)
            // must happen inside an explicit compilation context, otherwise ReSharper throws
            // "Implicit UniversalModuleReferenceContext detected".
            // using (CompilationContextCookie.GetOrCreate(sourceFile.ResolveContext))
            {
                var controllerVisitor = new ControllerVisitor(filePath);
                foreach (var classDecl in csharpFile.Descendants<IClassDeclaration>())
                    classDecl.Accept(controllerVisitor);
                endpoints.AddRange(controllerVisitor.DetectedEndpoints);

                var minimalApiVisitor = new MinimalApiVisitor(filePath);
                foreach (var invocation in csharpFile.Descendants<IInvocationExpression>())
                    invocation.Accept(minimalApiVisitor);
                endpoints.AddRange(minimalApiVisitor.DetectedEndpoints);
            }

            return endpoints;
        }

        private static string GetFileStamp(string path)
        {
            try { return File.GetLastWriteTimeUtc(path).Ticks.ToString(); }
            catch { return string.Empty; }
        }
    }
}

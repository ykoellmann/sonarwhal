using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using JetBrains.ReSharper.Psi;
using JetBrains.ReSharper.Psi.CSharp;
using JetBrains.ReSharper.Psi.CSharp.Tree;
using JetBrains.ReSharper.Psi.ExtensionsAPI;
using JetBrains.ReSharper.Psi.Search;
using JetBrains.ReSharper.Psi.Tree;

namespace ReSharperPlugin.Sonarwhale
{
    [PsiSharedComponent]
    public class SonarwhaleSearcherFactory : DomainSpecificSearcherFactoryBase
    {
        private static readonly string[] HttpMethodAttributeNames =
        {
            "HttpGet", "HttpPost", "HttpPut", "HttpDelete", "HttpPatch", "HttpHead", "HttpOptions"
        };

        public override bool IsCompatibleWithLanguage(PsiLanguageType languageType) =>
            languageType.Is<CSharpLanguage>();

        public override IEnumerable<FindResult> GetRelatedFindResults(IDeclaredElement element)
        {
            if (!(element is IMethod)) return Enumerable.Empty<FindResult>();

            var methodDecl = element.GetDeclarations().OfType<IMethodDeclaration>().FirstOrDefault();
            if (methodDecl == null) return Enumerable.Empty<FindResult>();

            var httpMethod = GetHttpMethod(methodDecl);
            if (httpMethod == null) return Enumerable.Empty<FindResult>();

            var classDecl = methodDecl.GetContainingNode<IClassDeclaration>();
            if (classDecl == null) return Enumerable.Empty<FindResult>();

            var filePath = methodDecl.GetSourceFile()?.GetLocation().FullPath;
            if (string.IsNullOrEmpty(filePath)) return Enumerable.Empty<FindResult>();

            var endpointId = ComputeHash(
                $"{filePath}:{classDecl.GetTreeStartOffset().Offset}:{methodDecl.GetTreeStartOffset().Offset}");

            var controllerName = classDecl.DeclaredName;
            var ctrlShortName = controllerName.EndsWith("Controller")
                ? controllerName.Substring(0, controllerName.Length - "Controller".Length)
                : controllerName;
            var actionName = GetAttributeStringValue(methodDecl, "ActionName") ?? methodDecl.DeclaredName;
            var controllerRoute = (GetAttributeStringValue(classDecl, "Route") ?? string.Empty)
                .Replace("[controller]", ctrlShortName.ToLower())
                .Replace("[Controller]", ctrlShortName)
                .Replace("[action]", actionName.ToLower())
                .Replace("[Action]", actionName)
                .TrimStart('/');
            var methodRoute = (GetAttributeStringValue(methodDecl, httpMethod) ?? string.Empty)
                .Replace("[action]", actionName.ToLower())
                .Replace("[Action]", actionName)
                .TrimStart('/');
            var fullRoute = string.IsNullOrEmpty(controllerRoute) ? "/" + methodRoute
                : string.IsNullOrEmpty(methodRoute) ? "/" + controllerRoute
                : "/" + controllerRoute + "/" + methodRoute;

            var solution = element.GetSolution();

            return new[] { new FindResultSonarwhaleEndpoint(endpointId, httpMethod, fullRoute, solution) };
        }

        private static string GetHttpMethod(IMethodDeclaration methodDecl)
        {
            foreach (var attrName in HttpMethodAttributeNames)
            {
                if (HasAttribute(methodDecl, attrName))
                    return attrName.Substring(4).ToUpperInvariant();
            }
            return null;
        }

        private static bool AttributeNameMatches(IAttribute attr, string name)
        {
            var shortName = attr.Descendants<IReferenceName>().FirstOrDefault()?.ShortName ?? "";
            return shortName == name || shortName == name + "Attribute";
        }

        private static bool HasAttribute(IAttributesOwnerDeclaration decl, string attrName) =>
            decl.Attributes.Any(a => AttributeNameMatches(a, attrName));

        private static string GetAttributeStringValue(IAttributesOwnerDeclaration decl, string attrName)
        {
            var attr = decl.Attributes.FirstOrDefault(a => AttributeNameMatches(a, attrName));
            return attr?.Descendants<ICSharpLiteralExpression>().FirstOrDefault()?.ConstantValue?.StringValue;
        }

        private static string ComputeHash(string input)
        {
            using var sha = SHA256.Create();
            return Convert.ToBase64String(sha.ComputeHash(Encoding.UTF8.GetBytes(input))).Substring(0, 8);
        }
    }
}

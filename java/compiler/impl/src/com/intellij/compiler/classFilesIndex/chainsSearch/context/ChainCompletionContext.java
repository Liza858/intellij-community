/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.compiler.classFilesIndex.chainsSearch.context;

import com.intellij.compiler.classFilesIndex.chainsSearch.ChainCompletionStringUtil;
import com.intellij.compiler.classFilesIndex.chainsSearch.MethodChainsSearchUtil;
import com.intellij.compiler.classFilesIndex.impl.MethodIncompleteSignature;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.psi.*;
import com.intellij.psi.scope.BaseScopeProcessor;
import com.intellij.psi.scope.ElementClassHint;
import com.intellij.psi.scope.util.PsiScopesUtil;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PropertyUtil;
import com.intellij.util.SmartList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ChainCompletionContext {
  @NotNull
  private final TargetType myTarget;
  @NotNull
  private final List<PsiNamedElement> myContextElements;
  @NotNull
  private final List<PsiNamedElement> myContextStrings;
  @NotNull
  private final PsiElement myContext;
  @NotNull
  private final GlobalSearchScope myResolveScope;
  @NotNull
  private final Project myProject;
  @NotNull
  private final PsiManager myPsiManager;
  @NotNull
  private final MethodIncompleteSignatureResolver myNotDeprecatedMethodsResolver;

  public ChainCompletionContext(@NotNull TargetType target,
                                @NotNull List<PsiNamedElement> contextElements,
                                @NotNull List<PsiNamedElement> contextStrings,
                                @NotNull PsiElement context) {
    myTarget = target;
    myContextElements = contextElements;
    myContextStrings = contextStrings;
    myContext = context;
    myResolveScope = context.getResolveScope();
    myProject = context.getProject();
    myPsiManager = PsiManager.getInstance(myProject);
    myNotDeprecatedMethodsResolver = new MethodIncompleteSignatureResolver(JavaPsiFacade.getInstance(myProject), myResolveScope);
  }

  @NotNull
  public TargetType getTarget() {
    return myTarget;
  }

  @NotNull
  public List<PsiNamedElement> getContextElements() {
    return myContextElements;
  }

  public boolean contains(@Nullable final PsiType type) {
    if (type == null) return false;
    final Set<PsiType> types = getContextTypes();
    if (types.contains(type)) return true;
    for (PsiType contextType : types) {
      if (type.isAssignableFrom(contextType)) {
        return true;
      }
    }
    return false;
  }

  @NotNull
  public Set<PsiType> getContextTypes() {
    return myContextElements.stream().map(ChainCompletionContext::getType).collect(Collectors.toSet());
  }

  @NotNull
  public GlobalSearchScope getResolveScope() {
    return myResolveScope;
  }

  @NotNull
  public Project getProject() {
    return myProject;
  }

  @NotNull
  public PsiManager getPsiManager() {
    return myPsiManager;
  }

  @NotNull
  public PsiMethod[] resolveNotDeprecated(final MethodIncompleteSignature methodIncompleteSignature) {
    return myNotDeprecatedMethodsResolver.get(methodIncompleteSignature);
  }

  @Nullable
  public PsiElement findRelevantStringInContext(String stringParameterName) {
    String sanitizedTarget = MethodChainsSearchUtil.sanitizedToLowerCase(stringParameterName);
    return myContextStrings.stream().filter(e -> {
      String name = e.getName();
      return name != null && MethodChainsSearchUtil.isSimilar(sanitizedTarget, name);
    }).findFirst().orElse(null);
  }

  public Collection<PsiElement> getQualifiers(@Nullable PsiClass targetType) {
    if (targetType == null) return Collections.emptyList();
    return getQualifiers(JavaPsiFacade.getInstance(myProject).getElementFactory().createType(targetType));
  }

  public Collection<PsiElement> getQualifiers(@NotNull PsiType targetType) {
    return myContextElements.stream().filter(e -> {
      final PsiType elementType = getType(e);
      return elementType != null && targetType.isAssignableFrom(elementType);
    }).collect(Collectors.toList());
  }

  @Nullable
  public static ChainCompletionContext createContext(final @Nullable PsiType variableType,
                                                     final @Nullable String variableName,
                                                     final @Nullable PsiElement containingElement) {
    if (containingElement == null) return null;
    final TargetType target = TargetType.create(variableType);
    if (target == null) return null;

    final ContextProcessor processor = new ContextProcessor(null, containingElement.getProject(), containingElement);
    PsiScopesUtil.treeWalkUp(processor, containingElement, containingElement.getContainingFile());
    final List<PsiNamedElement> contextElements = processor.getContextElements();
    final List<PsiNamedElement> contextStrings = processor.getContextStrings();

    return new ChainCompletionContext(target, contextElements, contextStrings, containingElement);
  }

  private static class ContextProcessor extends BaseScopeProcessor implements ElementClassHint {
    private final List<PsiNamedElement> myContextElements = new SmartList<>();
    private final List<PsiNamedElement> myContextStrings = new SmartList<>();
    private final PsiVariable myCompletionVariable;
    private final PsiResolveHelper myResolveHelper;
    private final PsiElement myPlace;

    private ContextProcessor(@Nullable PsiVariable variable,
                             @NotNull Project project,
                             @NotNull PsiElement place) {
      myCompletionVariable = variable;
      myResolveHelper = PsiResolveHelper.SERVICE.getInstance(project);
      myPlace = place;
    }

    @Override
    public boolean shouldProcess(DeclarationKind kind) {
      return kind == DeclarationKind.ENUM_CONST ||
             kind == DeclarationKind.FIELD ||
             kind == DeclarationKind.METHOD ||
             kind == DeclarationKind.VARIABLE;
    }

    @Override
    public boolean execute(@NotNull PsiElement element, @NotNull ResolveState state) {
      if ((!(element instanceof PsiMethod) || PropertyUtil.isSimplePropertyAccessor((PsiMethod)element)) &&
          (!(element instanceof PsiMember) || myResolveHelper.isAccessible((PsiMember)element, myPlace, null))) {
        final PsiType type = getType(element);
        if (type == null) {
          return false;
        }
        if (ChainCompletionStringUtil.isPrimitiveOrArrayOfPrimitives(type)) {
          if (type.equalsToText(CommonClassNames.JAVA_LANG_STRING)) {
            myContextStrings.add((PsiNamedElement)element);
          }
          return false;
        }
        myContextElements.add((PsiNamedElement)element);
      }
      return true;
    }

    @Override
    public <T> T getHint(@NotNull Key<T> hintKey) {
      if (hintKey == ElementClassHint.KEY) {
        return (T)this;
      }
      return super.getHint(hintKey);
    }

    @NotNull
    public List<PsiNamedElement> getContextElements() {
      myContextElements.remove(myCompletionVariable);
      return myContextElements;
    }

    @NotNull
    public List<PsiNamedElement> getContextStrings() {
      return myContextStrings;
    }
  }

  @Nullable
  private static PsiType getType(PsiElement element) {
    if (element instanceof PsiVariable) {
      return ((PsiVariable)element).getType();
    }
    if (element instanceof PsiMethod) {
      return ((PsiMethod)element).getReturnType();
    }
    throw new AssertionError(element);
  }
}
package com.intellij.refactoring.safeDelete;

import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter;
import com.intellij.lang.LanguageRefactoringSupport;
import com.intellij.lang.refactoring.RefactoringSupportProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.codeStyle.VariableKind;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PropertyUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.BaseRefactoringProcessor;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.safeDelete.usageInfo.*;
import com.intellij.refactoring.util.ConflictsUtil;
import com.intellij.refactoring.util.RefactoringMessageUtil;
import com.intellij.refactoring.util.RefactoringUtil;
import com.intellij.refactoring.util.TextOccurrencesUtil;
import com.intellij.usageView.UsageInfo;
import com.intellij.usageView.UsageViewDescriptor;
import com.intellij.usageView.UsageViewUtil;
import com.intellij.usages.*;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.Processor;
import com.intellij.util.containers.HashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author dsl
 */
public class SafeDeleteProcessor extends BaseRefactoringProcessor {
  private static final Logger LOG = Logger.getInstance("#com.intellij.refactoring.safeDelete.SafeDeleteProcessor");
  private PsiElement[] myElements;
  private boolean mySearchInCommentsAndStrings;
  private boolean mySearchNonJava;
  private boolean myPreviewNonCodeUsages = true;

  private SafeDeleteProcessor(Project project, @Nullable Runnable prepareSuccessfulCallback,
                              PsiElement[] elementsToDelete, boolean isSearchInComments, boolean isSearchNonJava) {
    super(project, prepareSuccessfulCallback);
    myElements = elementsToDelete;
    mySearchInCommentsAndStrings = isSearchInComments;
    mySearchNonJava = isSearchNonJava;
  }

  protected UsageViewDescriptor createUsageViewDescriptor(UsageInfo[] usages) {
    return new SafeDeleteUsageViewDescriptor(myElements);
  }

  private static boolean isInside(PsiElement place, PsiElement[] ancestors) {
    return isInside(place, Arrays.asList(ancestors));
  }

  private static boolean isInside(PsiElement place, Collection<? extends PsiElement> ancestors) {
    for (PsiElement element : ancestors) {
      if (isInside(place, element)) return true;
    }
    return false;
  }

  public static boolean isInside (PsiElement place, PsiElement ancestor) {
    if (ancestor instanceof PsiPackage) {
      final PsiDirectory[] directories = ((PsiPackage)ancestor).getDirectories(place.getResolveScope());
      for (PsiDirectory directory : directories) {
        if (isInside(place, directory)) return true;
      }
    }

    if (PsiTreeUtil.isAncestor(ancestor, place, false)) return true;

    if (place instanceof PsiComment && ancestor instanceof PsiClass) {
      final PsiClass aClass = (PsiClass)ancestor;
      if (aClass.getParent() instanceof PsiJavaFile) {
        final PsiJavaFile file = (PsiJavaFile)aClass.getParent();
        if (PsiTreeUtil.isAncestor(file, place, false)) {
          if (file.getClasses().length == 1) { // file will be deleted on class deletion
            return true;
          }
        }
      }
    }

    return false;
  }

  @NotNull
  protected UsageInfo[] findUsages() {
    List<UsageInfo> usages = Collections.synchronizedList(new ArrayList<UsageInfo>());
    for (PsiElement element : myElements) {
      boolean handled = false;
      for(SafeDeleteProcessorDelegate delegate: Extensions.getExtensions(SafeDeleteProcessorDelegate.EP_NAME)) {
        if (delegate.handlesElement(element)) {
          final NonCodeUsageSearchInfo filter = delegate.findUsages(element, myElements, usages);
          if (filter != null) {
            for(PsiElement nonCodeUsageElement: filter.getElementsToSearch()) {
              addNonCodeUsages(nonCodeUsageElement, usages, filter.getInsideDeletedCondition());
            }
          }
          handled = true;
          break;
        }
      }
      if (!handled && element instanceof PsiNamedElement) {
        findGenericElementUsages(element, usages, myElements);
        addNonCodeUsages(element, usages, getDefaultInsideDeletedCondition(myElements));
      }
    }
    final UsageInfo[] result = usages.toArray(new UsageInfo[usages.size()]);
    return UsageViewUtil.removeDuplicatedUsages(result);
  }

  public static Condition<PsiElement> getDefaultInsideDeletedCondition(final PsiElement[] elements) {
    return new Condition<PsiElement>() {
      public boolean value(final PsiElement usage) {
        return !(usage instanceof PsiFile) && isInside(usage, elements);
      }
    };
  }

  public static void findGenericElementUsages(final PsiElement element, final List<UsageInfo> usages, final PsiElement[] allElementsToDelete) {
    ReferencesSearch.search(element).forEach(new Processor<PsiReference>() {
      public boolean process(final PsiReference reference) {
        final PsiElement refElement = reference.getElement();
        if (!isInside(refElement, allElementsToDelete)) {
          usages.add(new SafeDeleteReferenceSimpleDeleteUsageInfo(refElement, element, false));
        }
        return true;
      }
    });
  }

  protected boolean preprocessUsages(Ref<UsageInfo[]> refUsages) {
    UsageInfo[] usages = refUsages.get();
    ArrayList<String> conflicts = new ArrayList<String>();

    for (PsiElement element : myElements) {
      if (element instanceof PsiMethod) {
        final PsiClass containingClass = ((PsiMethod)element).getContainingClass();

        if (!containingClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
          final PsiMethod[] superMethods = ((PsiMethod) element).findSuperMethods();
          for (PsiMethod superMethod : superMethods) {
            if (isInside(superMethod, myElements)) continue;
            if (superMethod.hasModifierProperty(PsiModifier.ABSTRACT)) {
              String message = RefactoringBundle.message("0.implements.1", ConflictsUtil.getDescription(element, true),
                                                         ConflictsUtil.getDescription(superMethod, true));
              conflicts.add(message);
              break;
            }
          }
        }
      }
    }

    final HashMap<PsiElement,UsageHolder> elementsToUsageHolders = sortUsages(usages);
    final Collection<UsageHolder> usageHolders = elementsToUsageHolders.values();
    for (UsageHolder usageHolder : usageHolders) {
      if (usageHolder.getNonCodeUsagesNumber() != usageHolder.getUnsafeUsagesNumber()) {
        final String description = usageHolder.getDescription();
        if (description != null) {
          conflicts.add(description);
        }
      }
    }

    if (!conflicts.isEmpty()) {
      if (ApplicationManager.getApplication().isUnitTestMode()) {
        throw new RuntimeException(StringUtil.join(conflicts, "\n"));
      }
      else {
        UnsafeUsagesDialog dialog = new UnsafeUsagesDialog(conflicts.toArray(new String[conflicts.size()]), myProject);
        dialog.show();
        if (!dialog.isOK()) {
          final int exitCode = dialog.getExitCode();
          prepareSuccessful(); // dialog is always dismissed
          if (exitCode == UnsafeUsagesDialog.VIEW_USAGES_EXIT_CODE) {
            showUsages(usages);
          }
          return false;
        }
        else {
          myPreviewNonCodeUsages = false;
        }
      }
    }

    final UsageInfo[] filteredUsages = filterAndQueryOverriding(usages);
    prepareSuccessful(); // dialog is always dismissed
    if(filteredUsages == null) {
      return false;
    }
    refUsages.set(filteredUsages);
    return true;
  }

  private void showUsages(final UsageInfo[] usages) {
    UsageViewPresentation presentation = new UsageViewPresentation();
    presentation.setTabText(RefactoringBundle.message("safe.delete.title"));
    presentation.setTargetsNodeText(RefactoringBundle.message("attempting.to.delete.targets.node.text"));
    presentation.setShowReadOnlyStatusAsRed(true);
    presentation.setShowCancelButton(true);
    presentation.setCodeUsagesString(RefactoringBundle.message("references.found.in.code"));
    presentation.setNonCodeUsagesString(RefactoringBundle.message("occurrences.found.in.comments.strings.and.non.java.files"));
    presentation.setUsagesString(RefactoringBundle.message("usageView.usagesText"));

    UsageViewManager manager = UsageViewManager.getInstance(myProject);
    UsageTarget[] targets = new UsageTarget[myElements.length];
    for (int i = 0; i < targets.length; i++) {
      targets[i] = new PsiElement2UsageTargetAdapter(myElements[i]);
    }

    final UsageView usageView = manager.showUsages(
      targets,
      UsageInfoToUsageConverter.convert(new UsageInfoToUsageConverter.TargetElementsDescriptor(myElements), usages),
      presentation
    );
    usageView.addPerformOperationAction(new RerunSafeDelete(myProject, myElements, usageView),
                                        RefactoringBundle.message("retry.command"), null, RefactoringBundle.message("rerun.safe.delete"));
  }

  public PsiElement[] getElements() {
    return myElements;
  }

  private static class RerunSafeDelete implements Runnable {
    final SmartPsiElementPointer[] myPointers;
    private final Project myProject;
    private final UsageView myUsageView;

    RerunSafeDelete(Project project, PsiElement[] elements, UsageView usageView) {
      myProject = project;
      myUsageView = usageView;
      myPointers = new SmartPsiElementPointer[elements.length];
      for (int i = 0; i < elements.length; i++) {
        PsiElement element = elements[i];
        myPointers[i] = SmartPointerManager.getInstance(myProject).createSmartPsiElementPointer(element);
      }
    }

    public void run() {
      ApplicationManager.getApplication().invokeLater(new Runnable() {
          public void run() {
            PsiDocumentManager.getInstance(myProject).commitAllDocuments();
            myUsageView.close();
            ArrayList<PsiElement> elements = new ArrayList<PsiElement>();
            for (SmartPsiElementPointer pointer : myPointers) {
              final PsiElement element = pointer.getElement();
              if (element != null) {
                elements.add(element);
              }
            }
            if(!elements.isEmpty()) {
              SafeDeleteHandler.invoke(myProject, elements.toArray(new PsiElement[elements.size()]), true);
            }
          }
        });
    }
  }

  @Nullable
  private UsageInfo[] filterAndQueryOverriding(UsageInfo[] usages) {
    ArrayList<UsageInfo> result = new ArrayList<UsageInfo>();
    ArrayList<UsageInfo> overridingMethods = new ArrayList<UsageInfo>();
    for (UsageInfo usage : usages) {
      if (usage.isNonCodeUsage) {
        result.add(usage);
      }
      else if (usage instanceof SafeDeleteOverridingMethodUsageInfo) {
        overridingMethods.add(usage);
      }
      else {
        result.add(usage);
      }
    }

    if(!overridingMethods.isEmpty()) {
      if (ApplicationManager.getApplication().isUnitTestMode()) {
        result.addAll(overridingMethods);
      }
      else {
        OverridingMethodsDialog dialog = new OverridingMethodsDialog(myProject, overridingMethods);
        dialog.show();
        if(!dialog.isOK()) return null;
        result.addAll(dialog.getSelected());
      }
    }

    final UsageInfo[] usageInfos = result.toArray(new UsageInfo[result.size()]);
    return UsageViewUtil.removeDuplicatedUsages(usageInfos);
  }

  /**
   * @param usages
   * @return Map from elements to UsageHolders
   */
  private static HashMap<PsiElement,UsageHolder> sortUsages(UsageInfo[] usages) {
    HashMap<PsiElement,UsageHolder> result = new HashMap<PsiElement, UsageHolder>();

    for (final UsageInfo usage : usages) {
      if (usage instanceof SafeDeleteUsageInfo) {
        final PsiElement referencedElement = ((SafeDeleteUsageInfo)usage).getReferencedElement();
        if (!result.containsKey(referencedElement)) {
          result.put(referencedElement, new UsageHolder(referencedElement, usages));
        }
      }
    }
    return result;
  }


  protected void refreshElements(PsiElement[] elements) {
    LOG.assertTrue(elements.length == myElements.length);
    for (int i = 0; i < elements.length; i++) {
      myElements[i] = elements[i];
    }
  }

  protected boolean isPreviewUsages(UsageInfo[] usages) {
    if(myPreviewNonCodeUsages && UsageViewUtil.hasNonCodeUsages(usages)) {
      WindowManager.getInstance().getStatusBar(myProject).setInfo(
        RefactoringBundle.message("occurrences.found.in.comments.strings.and.non.java.files"));
      return true;
    }

    return super.isPreviewUsages(filterToBeDeleted(usages));
  }

  private static UsageInfo[] filterToBeDeleted(UsageInfo[] infos) {
    ArrayList<UsageInfo> list = new ArrayList<UsageInfo>();
    for (UsageInfo info : infos) {
      if (!(info instanceof SafeDeleteReferenceUsageInfo) || ((SafeDeleteReferenceUsageInfo) info).isSafeDelete()) {
        list.add(info);
      }
    }
    return list.toArray(new UsageInfo[list.size()]);
  }

  protected void performRefactoring(UsageInfo[] usages) {
    try {
      for (UsageInfo usage : usages) {
        if (usage instanceof SafeDeleteReferenceUsageInfo && ((SafeDeleteReferenceUsageInfo)usage).isSafeDelete()) {
          ((SafeDeleteReferenceUsageInfo)usage).deleteElement();
        }
        else if (usage instanceof SafeDeletePrivatizeMethod) {
          ((SafeDeletePrivatizeMethod)usage).getMethod().getModifierList().setModifierProperty(PsiModifier.PRIVATE, true);
        }
        else if (usage instanceof SafeDeleteOverridingMethodUsageInfo) {
          ((SafeDeleteOverridingMethodUsageInfo)usage).getOverridingMethod().delete();

        }
      }

      for (PsiElement element : myElements) {
        if (element instanceof PsiVariable) {
          ((PsiVariable)element).normalizeDeclaration();
        }

        element.delete();
      }
    } catch (IncorrectOperationException e) {
      RefactoringUtil.processIncorrectOperation(myProject, e);
    }
  }

  private String calcCommandName() {
    return RefactoringBundle.message("safe.delete.command", RefactoringUtil.calculatePsiElementDescriptionList(myElements));
  }

  private String myCachedCommandName = null;
  protected String getCommandName() {
    if (myCachedCommandName == null) {
      myCachedCommandName = calcCommandName();
    }
    return myCachedCommandName;
  }


  private void addNonCodeUsages(final PsiElement element, List<UsageInfo> usages, final Condition<PsiElement> insideElements) {
    TextOccurrencesUtil.UsageInfoFactory nonCodeUsageFactory = new TextOccurrencesUtil.UsageInfoFactory() {
      public UsageInfo createUsageInfo(@NotNull PsiElement usage, int startOffset, int endOffset) {
        if (!insideElements.value(usage)) {
          return new SafeDeleteReferenceSimpleDeleteUsageInfo(usage, element, startOffset, endOffset, true, false);
        } else {
          return null;
        }
      }
    };
    if (mySearchInCommentsAndStrings) {
      String stringToSearch = RefactoringUtil.getStringToSearch(element, false);
      if (stringToSearch != null) {
        RefactoringUtil.addUsagesInStringsAndComments(element, stringToSearch, usages, nonCodeUsageFactory);
      }
    }
    if (mySearchNonJava && (element instanceof PsiClass || element instanceof PsiPackage)) {
      String stringToSearch = RefactoringUtil.getStringToSearch(element, true);
      if (stringToSearch != null) {
        TextOccurrencesUtil.addTextOccurences(element, stringToSearch, GlobalSearchScope.projectScope(myProject), usages, nonCodeUsageFactory);
      }
    }
  }

  public static boolean validElement(PsiElement element) {
    if (element instanceof PsiFile) return true;
    if (!element.isPhysical()) return false;
    final RefactoringSupportProvider provider = LanguageRefactoringSupport.INSTANCE.forLanguage(element.getLanguage());
    return provider.isSafeDeleteAvailable(element);
  }

  public static SafeDeleteProcessor createInstance(Project project, @Nullable Runnable prepareSuccessfulCallback,
                                                   PsiElement[] elementsToDelete, boolean isSearchInComments, boolean isSearchNonJava) {
    return new SafeDeleteProcessor(project, prepareSuccessfulCallback, elementsToDelete, isSearchInComments, isSearchNonJava);
  }

  public static SafeDeleteProcessor createInstance(Project project, @Nullable Runnable prepareSuccessfulCallBack,
                                                   PsiElement[] elementsToDelete, boolean isSearchInComments, boolean isSearchNonJava,
                                                   boolean askForAccessors) {
    PsiManager manager = PsiManager.getInstance(project);
    ArrayList<PsiElement> elements = new ArrayList<PsiElement>(Arrays.asList(elementsToDelete));
    HashSet<PsiElement> elementsToDeleteSet = new HashSet<PsiElement>(Arrays.asList(elementsToDelete));

    for (PsiElement psiElement : elementsToDelete) {
      if (psiElement instanceof PsiField) {
        PsiField field = (PsiField)psiElement;
        String propertyName = JavaCodeStyleManager.getInstance(project).variableNameToPropertyName(field.getName(), VariableKind.FIELD);

        PsiClass aClass = field.getContainingClass();
        if (aClass != null) {
          boolean isStatic = field.hasModifierProperty(PsiModifier.STATIC);
          PsiMethod getter = PropertyUtil.findPropertyGetter(aClass, propertyName, isStatic, false);
          if (elementsToDeleteSet.contains(getter)) getter = null;
          PsiMethod setter = PropertyUtil.findPropertySetter(aClass, propertyName, isStatic, false);
          if (elementsToDeleteSet.contains(setter)) setter = null;
          if (askForAccessors && (getter != null || setter != null)) {
            final String message = RefactoringMessageUtil.getGetterSetterMessage(field.getName(), RefactoringBundle.message("delete.title"), getter, setter);
            if (Messages.showYesNoDialog(project, message, RefactoringBundle.message("safe.delete.title"), Messages.getQuestionIcon()) != 0) {
              getter = null;
              setter = null;
            }
          }
          if (setter != null) elements.add(setter);
          if (getter != null) elements.add(getter);
        }
      }
    }

    return new SafeDeleteProcessor(project, prepareSuccessfulCallBack,
                                   elements.toArray(new PsiElement[elements.size()]),
                                   isSearchInComments, isSearchNonJava);
  }

  public boolean isSearchInCommentsAndStrings() {
    return mySearchInCommentsAndStrings;
  }

  public void setSearchInCommentsAndStrings(boolean searchInCommentsAndStrings) {
    mySearchInCommentsAndStrings = searchInCommentsAndStrings;
  }

  public boolean isSearchNonJava() {
    return mySearchNonJava;
  }

  public void setSearchNonJava(boolean searchNonJava) {
    mySearchNonJava = searchNonJava;
  }
}

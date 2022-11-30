// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.debugger.ui.breakpoints;


import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.SourcePosition;
import com.intellij.debugger.engine.CollectionBreakpointUtils;
import com.intellij.debugger.engine.DebugProcessImpl;
import com.intellij.debugger.engine.DebuggerUtils;
import com.intellij.debugger.engine.SuspendContextImpl;
import com.intellij.debugger.engine.evaluation.EvaluateException;
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl;
import com.intellij.debugger.engine.events.SuspendContextCommandImpl;
import com.intellij.debugger.engine.requests.RequestManagerImpl;
import com.intellij.debugger.impl.DebuggerUtilsImpl;
import com.intellij.debugger.impl.PositionUtil;
import com.intellij.debugger.jdi.StackFrameProxyImpl;
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl;
import com.intellij.debugger.jdi.VirtualMachineProxyImpl;
import com.intellij.debugger.settings.DebuggerSettings;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiModifier;
import com.intellij.ui.LayeredIcon;
import com.intellij.util.SlowOperations;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.sun.jdi.*;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.ModificationWatchpointEvent;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.MethodEntryRequest;
import com.sun.jdi.request.MethodExitRequest;
import com.sun.jdi.request.ModificationWatchpointRequest;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.java.debugger.breakpoints.properties.JavaCollectionBreakpointProperties;

import javax.swing.*;
import java.util.*;
import java.util.stream.Collectors;

@ApiStatus.Experimental
public class CollectionBreakpoint extends BreakpointWithHighlighter<JavaCollectionBreakpointProperties> {
  @NonNls public static final Key<CollectionBreakpoint> CATEGORY = BreakpointCategory.lookup("collection_breakpoints");

  private static final String GET_INTERNAL_CLS_NAME_METHOD_NAME = "getInternalClsName";
  private static final String GET_INTERNAL_CLS_NAME_METHOD_DESC = "(Ljava/lang/String;)Ljava/lang/String;";
  private static final String EMULATE_FIELD_WATCHPOINT_METHOD_NAME = "emulateFieldWatchpoint";
  private static final String EMULATE_FIELD_WATCHPOINT_METHOD_DESC = "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V";
  private static final String PUT_FIELD_TO_CAPTURE_METHOD_NAME = "putFieldToCapture";
  private static final String PUT_FIELD_TO_CAPTURE_METHOD_DESC = "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V";
  private static final String CAPTURE_FIELD_MODIFICATION_METHOD_NAME = "captureFieldModification";
  private static final String CAPTURE_FIELD_MODIFICATION_METHOD_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Z)V";
  private static final String CAPTURE_COLLECTION_MODIFICATION_DEFAULT_METHOD_NAME = "captureCollectionModification";
  private static final String CAPTURE_COLLECTION_MODIFICATION_DEFAULT_METHOD_DESC = "(Lcom/intellij/rt/debugger/agent/CollectionBreakpointInstrumentor$Multiset;Ljava/lang/Object;)V";
  private static final String CAPTURE_COLLECTION_MODIFICATION_SPECIAL_METHOD_NAME = "captureCollectionModification";
  private static final String CAPTURE_COLLECTION_MODIFICATION_SPECIAL_METHOD_DESC = "(ZZLjava/lang/Object;Ljava/lang/Object;Z)V";
  private static final String ON_CAPTURE_END_METHOD_NAME = "onCaptureEnd";
  private static final String ON_CAPTURE_END_METHOD_DESC = "(Ljava/util/IdentityHashMap;)V";
  private static final long MAX_INSTANCES_NUMBER = 0;

  private final Set<String> myUnprocessedClasses = new HashSet<>();
  private volatile boolean myFieldOwnerClsPrepared = false;
  private volatile boolean myIsFinal = false;
  private volatile boolean myIsStatic = false;
  private volatile boolean myLineBreakpointsAreSet = false;
  private String myFieldAccessModifier = PsiModifier.PRIVATE;
  private String myFieldOwnerJVMClsName = null;
  private String myFieldOwnerClsTypeDesc = null;

  protected CollectionBreakpoint(Project project, XBreakpoint breakpoint) {
    super(project, breakpoint);
    initProperties();
  }

  @Override
  public void reload() {
    super.reload();
    initProperties();
  }

  @Override
  public void createRequestForPreparedClass(DebugProcessImpl debugProcess, ReferenceType refType) {
    setProperties(debugProcess);
    myFieldOwnerJVMClsName = refType.name();
    myFieldOwnerClsTypeDesc = refType.signature();
    if (!canEmulateFieldWatchpoint()) {
      createModificationWatchpointRequest(debugProcess, refType);
      return;
    }
    if (myFieldOwnerClsPrepared) {
      return;
    }
    createModificationWatchpointRequestEmulated(debugProcess, refType);
    myFieldOwnerClsPrepared = true;
  }

  @Override
  protected Icon getDisabledIcon(boolean isMuted) {
    if (DebuggerManagerEx.getInstanceEx(myProject).getBreakpointManager().findMasterBreakpoint(this) != null && isMuted) {
      return AllIcons.Debugger.Db_muted_dep_field_breakpoint;
    }
    return null;
  }

  @Override
  public String getEventMessage(LocatableEvent event) {
    return "";
  }

  @Override
  protected Icon getVerifiedIcon(boolean isMuted) {
    return isSuspend() ? AllIcons.Debugger.Db_verified_field_breakpoint : AllIcons.Debugger.Db_verified_no_suspend_field_breakpoint;
  }

  @Override
  protected Icon getVerifiedWarningsIcon(boolean isMuted) {
    return new LayeredIcon(isMuted ? AllIcons.Debugger.Db_muted_field_breakpoint : AllIcons.Debugger.Db_field_breakpoint,
                           AllIcons.General.WarningDecorator);
  }

  @Override
  public boolean processLocatableEvent(@NotNull SuspendContextCommandImpl action, LocatableEvent event) throws EventProcessingException {
    SuspendContextImpl context = action.getSuspendContext();
    if (context == null) {
      return false;
    }

    if (event instanceof MethodEntryEvent) {
      return processMethodEntryEvent(context, event);
    }
    else if (event instanceof ModificationWatchpointEvent) {
      return processModificationWatchpointEvent(context, event);
    }

    return false;
  }

  @Override
  public @Nullable PsiElement getEvaluationElement() {
    return getPsiClass();
  }

  @Override
  protected @Nullable ObjectReference getThisObject(SuspendContextImpl context, LocatableEvent event) {
    try {
      return super.getThisObject(context, event);
    }
    catch (EvaluateException e) {
      return null;
    }
  }

  public boolean processMethodEntryEvent(@NotNull SuspendContextImpl context, LocatableEvent event) {
    final @NotNull DebugProcessImpl debugProcess = context.getDebugProcess();

    event.request().disable(); // disable method entry request

    Location location = event.location();
    if (location == null) {
      emulateFieldWatchpoint(debugProcess, context);
      return false;
    }

    Method method = location.method();
    String type = location.declaringType().name();

    MethodEntryPlace place = MethodEntryPlace.DEFAULT;
    if (method.isStaticInitializer() && myUnprocessedClasses.contains(type)) {
      place = MethodEntryPlace.STATIC_BLOCK;
    }
    else if (method.isConstructor() && myUnprocessedClasses.contains(type)) {
      place = MethodEntryPlace.CONSTRUCTOR;
    }

    Set<String> unprocessedClassesCopy = new HashSet<>(myUnprocessedClasses);

    emulateFieldWatchpoint(debugProcess, context);

    if (myIsStatic) {
      processClassesInJVM(unprocessedClassesCopy, context, event, place);
    }
    else {
      processInstancesInJVM(unprocessedClassesCopy, context, event, place);
    }

    return false;
  }

  public boolean processModificationWatchpointEvent(@NotNull SuspendContextImpl context, LocatableEvent event) {
    @NotNull DebugProcessImpl debugProcess = context.getDebugProcess();
    @Nullable ObjectReference fieldOwnerInstance = ((ModificationWatchpointEvent)event).object();
    Value valueToBe = ((ModificationWatchpointEvent)event).valueToBe();
    putFieldToCapture(debugProcess, context, fieldOwnerInstance);
    setLineBreakpointsIfNeeded(context);
    captureFieldModification(valueToBe, fieldOwnerInstance, true, debugProcess, context);
    return true;
  }

  private boolean canEmulateFieldWatchpoint() {
    String fieldAccessModifier = getFieldAccessModifier();
    return myIsFinal || PsiModifier.PRIVATE.equals(fieldAccessModifier) || PsiModifier.PROTECTED.equals(fieldAccessModifier);
  }

  private void createModificationWatchpointRequest(DebugProcessImpl debugProcess, ReferenceType refType) {
    VirtualMachineProxyImpl vm = debugProcess.getVirtualMachineProxy();
    if (vm.canWatchFieldModification()) {
      Field field = refType.fieldByName(getFieldName());
      RequestManagerImpl requestsManager = debugProcess.getRequestsManager();
      ModificationWatchpointRequest request = requestsManager.createModificationWatchpointRequest(this, field);
      requestsManager.enableRequest(request);
    }
  }

  private void createModificationWatchpointRequestEmulated(DebugProcessImpl debugProcess, ReferenceType refType) {
    createRequestForClass(debugProcess, refType);
    if (!myIsFinal && PsiModifier.PROTECTED.equals(getFieldAccessModifier())) {
      createRequestForSubclasses(debugProcess, refType);
    }
  }

  private void initProperties() {
    PsiField field = PositionUtil.getPsiElementAt(myProject, PsiField.class, getSourcePosition());
    if (field != null) {
      getProperties().myFieldName = field.getName();
      PsiClass psiClass = field.getContainingClass();
      if (psiClass != null) {
        getProperties().myClassName = psiClass.getQualifiedName();
      }
      myIsFinal = SlowOperations.allowSlowOperations(() -> field.hasModifierProperty(PsiModifier.FINAL));
      myIsStatic = SlowOperations.allowSlowOperations(() -> field.hasModifierProperty(PsiModifier.STATIC));
      setFieldAccessModifier(SlowOperations.allowSlowOperations(() -> getFieldModifier(field)));
    }
    myFieldOwnerClsPrepared = false;
    myLineBreakpointsAreSet = false;
  }

  private synchronized String getFieldAccessModifier() {
    return myFieldAccessModifier;
  }

  private synchronized void setFieldAccessModifier(String modifier) {
    myFieldAccessModifier = modifier;
  }

  private void setProperties(DebugProcessImpl debugProcess) {
    CollectionBreakpointUtils.setCollectionHistorySavingEnabled(debugProcess, shouldSaveCollectionHistory());
  }

  private boolean shouldSaveCollectionHistory() {
    return getProperties().SHOULD_SAVE_COLLECTION_HISTORY;
  }

  private void createRequestForClass(DebugProcessImpl debugProcess, ReferenceType refType) {
    String clsName = refType.name();
    myUnprocessedClasses.add(clsName);
    createAllMethodsEntryRequest(debugProcess);
  }

  private void processConstructorEntry(SuspendContextImpl context, LocatableEvent event) {
    if (!tryPopFrame(context)) {
      Location location = event.location();
      ReferenceType declaringType = location.declaringType();
      ObjectReference thisObj = getThisObject(context, event);
      ThreadReferenceProxyImpl thread = context.getThread();
      setTemporaryFieldWatchpoint(context, declaringType, thisObj, thread);
    }
  }

  private void setTemporaryFieldWatchpoint(@NotNull SuspendContextImpl context,
                                           @NotNull ReferenceType declaringType,
                                           @Nullable ObjectReference thisObj,
                                           @Nullable ThreadReferenceProxyImpl thread) {
    MyRequestor requestor = new MyRequestor(getProject());
    addFieldWatchpoint(requestor, context, declaringType, thisObj);
    createMethodExitRequest(requestor, context, declaringType, thisObj, thread);
  }

  private void processInstancesInJVM(Set<String> clsNames, SuspendContextImpl context, LocatableEvent event, MethodEntryPlace place) {
    List<ObjectReference> instances = getTrackedInstancesInJVM(context, clsNames);
    if (instances.isEmpty()) {
      return;
    }

    if (instances.size() == 1 && MethodEntryPlace.CONSTRUCTOR.equals(place)) {
      processConstructorEntry(context, event);
    }
    else {
      processAllInstances(context, instances);
    }
  }

  private void processClassesInJVM(Set<String> clsNames, SuspendContextImpl context, LocatableEvent event, MethodEntryPlace place) {
    List<ReferenceType> classes = getTrackedClassesInJVM(context, clsNames);
    if (classes.isEmpty()) {
      return;
    }

    if (classes.size() == 1 && MethodEntryPlace.STATIC_BLOCK.equals(place)) {
      ReferenceType declaringType = event.location().declaringType();
      ThreadReferenceProxyImpl thread = context.getThread();
      setTemporaryFieldWatchpoint(context, declaringType, null, thread);
    }
    else {
      processAllClasses(context, classes);
    }
  }

  private void processAllClasses(SuspendContextImpl context, List<ReferenceType> classes) {
    String fieldName = getFieldName();
    for (ReferenceType cls : classes) {
      if (myFieldOwnerJVMClsName.equals(cls.name())) {
        Field field = cls.fieldByName(fieldName);
        captureClsField(cls, field, context.getDebugProcess(), context);
      }
    }

    VirtualMachineProxyImpl vm = context.getDebugProcess().getVirtualMachineProxy();

    for (ThreadReferenceProxyImpl thread : vm.allThreads()) {
      try {
        if (thread.isSuspended()) {
          processMethodEntryInAllFrames(thread, context);
        }
      }
      catch (EvaluateException e) {
        DebuggerUtilsImpl.logError(e);
      }
    }
  }

  private void processMethodEntryInAllFrames(ThreadReferenceProxyImpl thread, SuspendContextImpl context) throws EvaluateException {
    Set<ReferenceType> processedClasses = new HashSet<>();
    List<StackFrameProxyImpl> frames = thread.frames();
    for (StackFrameProxyImpl frame : frames) {
      Method method = frame.location().method();
      ReferenceType declaringType = method.declaringType();
      if (method.isObsolete() && !processedClasses.contains(declaringType)) {
        setTemporaryFieldWatchpoint(context, declaringType, null, thread);
        processedClasses.add(declaringType);
      }
    }
  }

  private void processAllInstances(SuspendContextImpl context, List<ObjectReference> instances) {
    String fieldName = getFieldName();
    for (ObjectReference instance : instances) {
      Field field = instance.referenceType().fieldByName(fieldName);
      captureInstanceField(instance, field, context.getDebugProcess(), context);
    }

    VirtualMachineProxyImpl vm = context.getDebugProcess().getVirtualMachineProxy();

    for (ThreadReferenceProxyImpl thread : vm.allThreads()) {
      try {
        if (thread.isSuspended()) {
          processMethodEntryInAllFrames(thread, context);
        }
      }
      catch (EvaluateException e) {
        DebuggerUtilsImpl.logError(e);
      }
    }
  }

  private void captureClsField(ReferenceType cls, Field field, DebugProcessImpl debugProcess, SuspendContextImpl context) {
    Value value = cls.getValue(field);
    if (value != null) {
      captureFieldModification(value, null, false, debugProcess, context);
    }
  }

  private void captureInstanceField(ObjectReference instance, Field field, DebugProcessImpl debugProcess, SuspendContextImpl context) {
    Value value = instance.getValue(field);
    if (value != null) {
      captureFieldModification(value, instance, false, debugProcess, context);
    }
  }

  private void addFieldWatchpoint(FilteredRequestor requestor,
                                  SuspendContextImpl context,
                                  ReferenceType declaringType,
                                  @Nullable ObjectReference thisObj) {
    DebugProcessImpl debugProcess = context.getDebugProcess();
    VirtualMachineProxyImpl vm = debugProcess.getVirtualMachineProxy();

    List<ReferenceType> fieldOwners = vm.classesByName(myFieldOwnerJVMClsName);

    List<Field> fields = ContainerUtil.map(fieldOwners, owner -> owner.fieldByName(getFieldName()));

    for (Field field : fields) {
      ModificationWatchpointRequest request = debugProcess.getRequestsManager().createModificationWatchpointRequest(requestor, field);

      if (declaringType != null) {
        request.addClassFilter(declaringType);
      }

      if (thisObj != null) {
        request.addInstanceFilter(thisObj);
      }

      request.enable();
    }
  }

  private void createRequestForSubclasses(DebugProcessImpl debugProcess, ReferenceType baseType) {
    final VirtualMachineProxyImpl vm = debugProcess.getVirtualMachineProxy();

    // create a request for classes that are already loaded
    vm.allClasses().stream()
      .filter(type -> DebuggerUtilsImpl.instanceOf(type, baseType) && !type.name().equals(baseType.name()))
      .forEach(derivedType -> createRequestForClass(debugProcess, derivedType));

    // wait for the subclasses
    RequestManagerImpl requestManager = debugProcess.getRequestsManager();
    ClassPrepareRequest request = requestManager.createClassPrepareRequest((debuggerProcess, derivedType) -> {
      createRequestForClass(debugProcess, derivedType);
    }, null);
    if (request != null) {
      requestManager.registerRequest(this, request);
      request.addClassFilter(baseType);
      request.enable();
    }
  }

  @Override
  public Key<CollectionBreakpoint> getCategory() {
    return CATEGORY;
  }

  @Override
  public @Nullable String getClassName() {
    return getProperties().myClassName;
  }

  @Override
  public synchronized @NotNull Project getProject() {
    return super.getProject();
  }

  @Override
  public String getDisplayName() {
    return "";
  }

  public synchronized String getFieldName() {
    return getProperties().myFieldName;
  }

  // creates this request or enables it if it exists
  private void createAllMethodsEntryRequest(DebugProcessImpl debugProcess) {
    RequestManagerImpl requestsManager = debugProcess.getRequestsManager();
    MethodEntryRequest request = MethodBreakpoint.findRequest(debugProcess, MethodEntryRequest.class, this);
    if (request == null) {
      request = requestsManager.createMethodEntryRequest(this);
      requestsManager.enableRequest(request);
    } else if (!request.isEnabled()) {
      requestsManager.enableRequest(request);
    }
  }

  private void captureFieldModification(Value valueToBe,
                                        ObjectReference fieldOwnerInstance,
                                        boolean shouldSaveStack,
                                        DebugProcessImpl debugProcess,
                                        SuspendContextImpl context) {
    StackFrameProxyImpl frameProxy = context.getFrameProxy();
    if (frameProxy == null) {
      return;
    }

    Value clsNameRef = getClsNameRef(debugProcess, context, frameProxy, fieldOwnerInstance);
    if (clsNameRef == null) {
      return;
    }

    Value fieldName = frameProxy.getVirtualMachine().mirrorOf(getFieldName());
    Value shouldSave = frameProxy.getVirtualMachine().mirrorOf(shouldSaveStack);

    ArrayList<Value> args = new ArrayList<>();
    args.add(valueToBe);
    args.add(fieldOwnerInstance);
    args.add(clsNameRef);
    args.add(fieldName);
    args.add(shouldSave);

    CollectionBreakpointUtils.invokeInstrumentorMethod(debugProcess, context, CAPTURE_FIELD_MODIFICATION_METHOD_NAME,
                                                       CAPTURE_FIELD_MODIFICATION_METHOD_DESC, args);
  }

  private Value getInternalClsName(DebugProcessImpl debugProcess, SuspendContextImpl context) {
    String clsTypeDesc = myFieldOwnerClsTypeDesc;
    StackFrameProxyImpl frameProxy = context.getFrameProxy();

    if (clsTypeDesc == null || frameProxy == null) {
      return null;
    }

    Value clsTypeDescRef = frameProxy.getVirtualMachine().mirrorOf(clsTypeDesc);

    return CollectionBreakpointUtils.invokeInstrumentorMethod(debugProcess, context, GET_INTERNAL_CLS_NAME_METHOD_NAME,
                                                              GET_INTERNAL_CLS_NAME_METHOD_DESC, Collections.singletonList(clsTypeDescRef));
  }

  // emulate FieldWatchpoint with instrumentation
  private void emulateFieldWatchpoint(DebugProcessImpl debugProcess, SuspendContextImpl context) {
    transformClassesToEmulateFieldWatchpoint(debugProcess, context);
    setLineBreakpointsIfNeeded(context);
  }

  private void setLineBreakpointsIfNeeded(SuspendContextImpl context) {
    if (suspendOnBreakpointHit() && !myLineBreakpointsAreSet) {
      setLineBreakpoints(context);
      myLineBreakpointsAreSet = true;
    }
  }

  private void transformClassesToEmulateFieldWatchpoint(DebugProcessImpl debugProcess,
                                                        SuspendContextImpl context) {
    StackFrameProxyImpl frameProxy = context.getFrameProxy();
    String fieldOwnerClsTypeDesc = myFieldOwnerClsTypeDesc;
    if (frameProxy == null || fieldOwnerClsTypeDesc == null) {
      return;
    }

    Value fieldOwnerClsTypeDescRef = frameProxy.getVirtualMachine().mirrorOf(fieldOwnerClsTypeDesc);
    Value fieldNameRef = frameProxy.getVirtualMachine().mirrorOf(getFieldName());

    List<Value> clsNamesRef = ContainerUtil.map(myUnprocessedClasses, clsName -> frameProxy.getVirtualMachine().mirrorOf(clsName));

    List<Value> args = new ArrayList<>();
    args.add(fieldOwnerClsTypeDescRef);
    args.add(fieldNameRef);
    args.addAll(clsNamesRef);

    myUnprocessedClasses.clear();
    CollectionBreakpointUtils.invokeInstrumentorMethod(debugProcess, context,
                                                       EMULATE_FIELD_WATCHPOINT_METHOD_NAME,
                                                       EMULATE_FIELD_WATCHPOINT_METHOD_DESC,
                                                       args);
  }

  private Value getClsNameRef(DebugProcessImpl debugProcess,
                             SuspendContextImpl context,
                             StackFrameProxyImpl frameProxy,
                             @Nullable ObjectReference fieldOwnerInstance) {
    Value clsNameRef;
    if (fieldOwnerInstance != null) {
      String instanceClsName = fieldOwnerInstance.referenceType().name();
      clsNameRef = frameProxy.getVirtualMachine().mirrorOf(instanceClsName);
    } else{
      clsNameRef = getInternalClsName(debugProcess, context);
    }
    return clsNameRef;
  }

  private void putFieldToCapture(DebugProcessImpl debugProcess,
                                 SuspendContextImpl context,
                                 @Nullable ObjectReference fieldOwnerInstance) {
    StackFrameProxyImpl frameProxy = context.getFrameProxy();
    String fieldOwnerClsTypeDesc = myFieldOwnerClsTypeDesc;
    if (frameProxy == null || fieldOwnerClsTypeDesc == null) {
      return;
    }

    Value clsNameRef = getClsNameRef(debugProcess, context, frameProxy, fieldOwnerInstance);
    if (clsNameRef == null) {
      return;
    }

    Value fieldOwnerClsTypeDescRef = frameProxy.getVirtualMachine().mirrorOf(fieldOwnerClsTypeDesc);
    Value fieldNameRef = frameProxy.getVirtualMachine().mirrorOf(getFieldName());

    List<Value> args = List.of(fieldOwnerClsTypeDescRef, fieldNameRef, clsNameRef);

    CollectionBreakpointUtils.invokeInstrumentorMethod(debugProcess, context,
                                                       PUT_FIELD_TO_CAPTURE_METHOD_NAME,
                                                       PUT_FIELD_TO_CAPTURE_METHOD_DESC,
                                                       args);
  }

  private void setLineBreakpoints(SuspendContextImpl context) {
    DebugProcessImpl debugProcess = context.getDebugProcess();
    EvaluationContextImpl evalContext = new EvaluationContextImpl(context, context.getFrameProxy());
    evalContext = evalContext.withAutoLoadClasses(false);
    ClassType instrumentorCls = CollectionBreakpointUtils.getInstrumentorClass(debugProcess, evalContext);
    List<Location> locations = findLocationsInInstrumentorMethods(instrumentorCls);
    for (Location location : locations) {
      SourcePosition position = locationToPosition(context.getDebugProcess(), location);
      MyLineBreakpoint breakpoint = new MyLineBreakpoint(location, position);
      breakpoint.createBreakpointRequest(context);
    }
  }

  private boolean suspendOnBreakpointHit() {
    return !DebuggerSettings.SUSPEND_NONE.equals(getSuspendPolicy());
  }

  private static List<ReferenceType> getTrackedClassesInJVM(SuspendContextImpl context, Set<String> clsNames) {
    DebugProcessImpl debugProcess = context.getDebugProcess();
    VirtualMachineProxyImpl vm = debugProcess.getVirtualMachineProxy();

    return clsNames
      .stream()
      .map(name -> vm.classesByName(name))
      .flatMap(list -> list.stream())
      .filter(cls -> cls.isPrepared())
      .collect(Collectors.toList());
  }

  private static List<ObjectReference> getTrackedInstancesInJVM(SuspendContextImpl context, Set<String> clsNames) {
    return getTrackedClassesInJVM(context, clsNames)
      .stream()
      .map(cls -> cls.instances(MAX_INSTANCES_NUMBER))
      .flatMap(list -> list.stream())
      .collect(Collectors.toList());
  }

  private static String getFieldModifier(PsiField field) {
    if (field.hasModifierProperty(PsiModifier.PRIVATE)) {
      return PsiModifier.PRIVATE;
    }
    if (field.hasModifierProperty(PsiModifier.PROTECTED)) {
      return PsiModifier.PROTECTED;
    }
    if (field.hasModifierProperty(PsiModifier.PUBLIC)) {
      return PsiModifier.PUBLIC;
    }
    return PsiModifier.PACKAGE_LOCAL;
  }

  private static void createMethodExitRequest(FilteredRequestor requestor,
                                              SuspendContextImpl context,
                                              @NotNull ReferenceType declaringType,
                                              @Nullable ObjectReference thisObj,
                                              @Nullable ThreadReferenceProxyImpl thread) {
    DebugProcessImpl debugProcess = context.getDebugProcess();
    RequestManagerImpl requestManager = debugProcess.getRequestsManager();

    MethodExitRequest request = requestManager.createMethodExitRequest(requestor);

    request.addClassFilter(declaringType);

    if (thisObj != null) {
      request.addInstanceFilter(thisObj);
    }

    if (thread != null) {
      request.addThreadFilter(thread.getThreadReference());
    }

    request.enable();
  }

  private static boolean tryPopFrame(SuspendContextImpl suspendContext) {
    StackFrameProxyImpl frameProxy = suspendContext.getFrameProxy();
    if (frameProxy == null) {
      return false;
    }
    try {
      frameProxy.threadProxy().popFrames(frameProxy);
      return true;
    }
    catch (final EvaluateException e) {
      return false;
    }
  }

  private static Location findLocationInMethod(ClassType instrumentorCls, String methodName, String methodDesc, int lineNumber) {
    try {
      Method method = DebuggerUtils.findMethod(instrumentorCls, methodName, methodDesc);
      if (method != null) {
        List<Location> lines = method.allLineLocations();
        if (lines.size() >= lineNumber + 1) {
          return lines.get(lineNumber);
        }
      }
    }
    catch (AbsentInformationException e) {
      DebuggerUtilsImpl.logError(e);
    }
    return null;
  }

  private static Location findLocationInCaptureFieldModificationMethod(ClassType instrumentorCls) {
    return findLocationInMethod(instrumentorCls, CAPTURE_FIELD_MODIFICATION_METHOD_NAME,
                                CAPTURE_FIELD_MODIFICATION_METHOD_DESC, 7);
  }

  private static Location findLocationInDefaultCaptureCollectionModificationMethod(ClassType instrumentorCls) {
    return findLocationInMethod(instrumentorCls, CAPTURE_COLLECTION_MODIFICATION_DEFAULT_METHOD_NAME,
                                CAPTURE_COLLECTION_MODIFICATION_DEFAULT_METHOD_DESC, 5);
  }

  private static Location findLocationInSpecialCaptureCollectionModificationMethod(ClassType instrumentorCls) {
    return findLocationInMethod(instrumentorCls, CAPTURE_COLLECTION_MODIFICATION_SPECIAL_METHOD_NAME,
                                CAPTURE_COLLECTION_MODIFICATION_SPECIAL_METHOD_DESC, 2);
  }

  private static Location findLocationInOnCaptureStartMethod(ClassType instrumentorCls) {
    return findLocationInMethod(instrumentorCls, ON_CAPTURE_END_METHOD_NAME,
                                ON_CAPTURE_END_METHOD_DESC, 8);
  }

  @NotNull
  private static List<Location> findLocationsInInstrumentorMethods(ClassType instrumentorCls) {
    List<Location> locations = new ArrayList<>();
    Location location = findLocationInCaptureFieldModificationMethod(instrumentorCls);
    if (location != null) {
      locations.add(location);
    }
    location = findLocationInDefaultCaptureCollectionModificationMethod(instrumentorCls);
    if (location != null) {
      locations.add(location);
    }
    location = findLocationInSpecialCaptureCollectionModificationMethod(instrumentorCls);
    if (location != null) {
      locations.add(location);
    }
    location = findLocationInOnCaptureStartMethod(instrumentorCls);
    if (location != null) {
      locations.add(location);
    }
    return locations;
  }

  private static @Nullable SourcePosition locationToPosition(DebugProcessImpl debugProcess, @Nullable Location location) {
    return location == null ? null : debugProcess.getPositionManager().getSourcePosition(location);
  }

  private static boolean stackContainsAnyObsoleteMethod(SuspendContextImpl context, ReferenceType declaringType) {
    ThreadReferenceProxyImpl thread = context.getThread();
    if (thread == null) {
      return false;
    }
    try {
      List<StackFrameProxyImpl> frames = thread.frames();
      if (frames.size() == 1) {
        return false;
      }
      for (StackFrameProxyImpl frame : frames.subList(1, frames.size())) {
        Method method = frame.location().method();
        return method.isObsolete() && method.declaringType().equals(declaringType);
      }
    }
    catch (EvaluateException e) {
      DebuggerUtilsImpl.logError(e);
    }
    return false;
  }

  private enum MethodEntryPlace {
    STATIC_BLOCK, CONSTRUCTOR, DEFAULT
  }

  private class MyRequestor extends FilteredRequestorImpl {

    private MyRequestor(@NotNull Project project) {
      super(project);
    }

    @Override
    public boolean processLocatableEvent(@NotNull SuspendContextCommandImpl action, LocatableEvent event) throws EventProcessingException {
      SuspendContextImpl context = action.getSuspendContext();
      if (context == null) {
        return false;
      }

      if (event instanceof ModificationWatchpointEvent) {
        processModificationWatchpointEvent(context, event);
        return suspendOnBreakpointHit();
      }
      else if (event instanceof MethodExitEvent) {
        processMethodExitEvent(context, event);
      }
      return false;
    }

    private void processMethodExitEvent(@NotNull SuspendContextImpl context, LocatableEvent event) {
      DebugProcessImpl debugProcess = context.getDebugProcess();
      ReferenceType declaringType = event.location().declaringType();
      if (!stackContainsAnyObsoleteMethod(context, declaringType)) {
        debugProcess.getRequestsManager().deleteRequest(this);
      }
    }
  }

  private class MyLineBreakpoint extends SyntheticLineBreakpoint {
    private final @Nullable SourcePosition myPosition;
    private final @Nullable Location myLocation;

    private MyLineBreakpoint(@Nullable Location location, @Nullable SourcePosition position) {
      super(CollectionBreakpoint.this.getProject());
      myLocation = location;
      myPosition = position;
      setSuspendPolicy(CollectionBreakpoint.this.getSuspendPolicy());
    }

    private void createBreakpointRequest(SuspendContextImpl suspendContext) {
      if (myLocation != null) {
        createLocationBreakpointRequest(this, myLocation, suspendContext.getDebugProcess());
      }
    }

    @Override
    public boolean processLocatableEvent(@NotNull SuspendContextCommandImpl action, LocatableEvent event) throws EventProcessingException {
      return processBreakpointHit(action);
    }

    private boolean processBreakpointHit(@NotNull SuspendContextCommandImpl action) {
      SuspendContextImpl context = action.getSuspendContext();
      if (context == null) {
        return false;
      }
      try {
        DebugProcessImpl debugProcess = context.getDebugProcess();
        DebugProcessImpl.ResumeCommand stepOutCommand = debugProcess.createStepOutCommand(context);
        debugProcess.getManagerThread().schedule(stepOutCommand);
      }
      catch (Exception e) {
        DebuggerUtilsImpl.logError(e);
        return false;
      }
      return true;
    }

    @Override
    public @Nullable SourcePosition getSourcePosition() {
      return myPosition;
    }

    @Override
    public int getLineIndex() {
      return myPosition == null ? -1 : myPosition.getLine();
    }

    @Override
    public String getEventMessage(LocatableEvent event) {
      return "";
    }

    @Override
    protected String getFileName() {
      return "";
    }
  }
}
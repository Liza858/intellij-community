// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.debugger.ui.breakpoints;


import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.SourcePosition;
import com.intellij.debugger.engine.CollectionBreakpointUtils;
import com.intellij.debugger.engine.DebugProcessImpl;
import com.intellij.debugger.engine.SuspendContextImpl;
import com.intellij.debugger.engine.evaluation.EvaluateException;
import com.intellij.debugger.engine.events.SuspendContextCommandImpl;
import com.intellij.debugger.engine.requests.RequestManagerImpl;
import com.intellij.debugger.impl.DebuggerUtilsImpl;
import com.intellij.debugger.impl.PositionUtil;
import com.intellij.debugger.impl.PrioritizedTask;
import com.intellij.debugger.jdi.StackFrameProxyImpl;
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl;
import com.intellij.debugger.jdi.VirtualMachineProxyImpl;
import com.intellij.debugger.settings.DebuggerSettings;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
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
import com.sun.jdi.request.*;
import one.util.streamex.StreamEx;
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

  private static final Logger LOG = Logger.getInstance(CollectionBreakpoint.class);
  private static final long MAX_INSTANCES_NUMBER = 0;

  private final @NotNull Set<FilteredRequestor> myAdditionRequestors = Collections.newSetFromMap(new IdentityHashMap<>());
  private final @NotNull Set<String> myUnprocessedClasses = new HashSet<>();
  private volatile boolean myFieldOwnerClsPreparedOnce = false;
  private volatile boolean myIsFinal = false;
  private volatile boolean myIsStatic = false;
  private volatile boolean myLineBreakpointsAreSet = false;
  private volatile boolean myProcessedOneSuspending = false;
  private @NotNull String myFieldAccessModifier = PsiModifier.PRIVATE;
  private @Nullable String myFieldOwnerJVMClsName = null;
  private @Nullable String myFieldDescriptor = null;

  protected CollectionBreakpoint(@NotNull Project project, @NotNull XBreakpoint breakpoint) {
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
    try {
      if (myFieldOwnerClsPreparedOnce) {
        return;
      }
      myFieldOwnerJVMClsName = refType.name();
      myFieldDescriptor = refType.fieldByName(getFieldName()).signature();
      if (canEmulateFieldWatchpoint()) {
        createModificationWatchpointRequestEmulated(debugProcess, refType);
      }
      createModificationWatchpointRequest(debugProcess, refType);
      createAllMethodsEntryRequest(debugProcess);
      setLineBreakpointsIfNeeded(debugProcess);
    }
    catch (Exception e) {
      LOG.debug(e);
    }
    finally {
      myFieldOwnerClsPreparedOnce = true;
    }
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
    @Nullable SuspendContextImpl context = action.getSuspendContext();
    if (context == null) {
      return false;
    }

    processSuspending(context);

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

  @Override
  public Key<CollectionBreakpoint> getCategory() {
    return CATEGORY;
  }

  @Override
  public @Nullable String getClassName() {
    return getProperties().myClassName;
  }

  @Override
  public String getDisplayName() {
    return "";
  }

  public void unregister(@NotNull DebugProcessImpl debugProcess) {
    debugProcess.getManagerThread().schedule(PrioritizedTask.Priority.HIGH, () -> {
      RequestManagerImpl requestManager = debugProcess.getRequestsManager();
      myAdditionRequestors.forEach(requestor -> requestManager.deleteRequest(requestor));
      requestManager.deleteRequest(this);
    });
  }

  private boolean processMethodEntryEvent(@NotNull SuspendContextImpl context, @NotNull LocatableEvent event) {
    if (canEmulateFieldWatchpoint()) {
      emulateFieldWatchpoint(context, event);
    }
    return false;
  }

  private void processSuspending(@NotNull SuspendContextImpl context) {
    disableAllMethodsEntryRequest(context);
    if (canEmulateFieldWatchpoint()) {
      disableModificationWatchpointRequest(context);
    }
    if (!myProcessedOneSuspending) {
      setProperties(context);
      captureCurrentFieldValues(context);
      myProcessedOneSuspending = true;
    }
  }

  private void disableAllMethodsEntryRequest(@NotNull SuspendContextImpl context) {
    MethodEntryRequest request = MethodBreakpoint.findRequest(context.getDebugProcess(), MethodEntryRequest.class, this);
    if (request != null && request.isEnabled()) {
      request.disable();
    }
  }

  private void disableModificationWatchpointRequest(@NotNull SuspendContextImpl context) {
    Set<EventRequest> requests = context.getDebugProcess().getRequestsManager().findRequests(this);
    ModificationWatchpointRequest request =
      StreamEx.of(requests).select(ModificationWatchpointRequest.class).findFirst().orElse(null);
    if (request != null && request.isEnabled()) {
      request.disable();
    }
  }

  private void emulateFieldWatchpoint(@NotNull SuspendContextImpl context, @NotNull LocatableEvent event) {
    Location location = event.location();
    if (location == null) {
      return;
    }

    transformClassesToEmulateFieldWatchpoint(context);

    Method method = location.method();
    String type = location.declaringType().name();

    MethodEntryPlace place = MethodEntryPlace.DEFAULT;
    if (method.isStaticInitializer() && myUnprocessedClasses.contains(type)) {
      place = MethodEntryPlace.STATIC_BLOCK;
    }
    else if (method.isConstructor() && myUnprocessedClasses.contains(type)) {
      place = MethodEntryPlace.CONSTRUCTOR;
    }

    processObsoleteMethodsOnStacks(myUnprocessedClasses, context, event, place);

    myUnprocessedClasses.clear();
  }

  private boolean processModificationWatchpointEvent(@NotNull SuspendContextImpl context, @NotNull LocatableEvent event) {
    @Nullable ObjectReference fieldOwnerInstance = ((ModificationWatchpointEvent)event).object();
    Value valueToBe = ((ModificationWatchpointEvent)event).valueToBe();
    captureFieldModification(valueToBe, fieldOwnerInstance, true, context);
    return true;
  }

  private boolean canEmulateFieldWatchpoint() {
    String fieldAccessModifier = getFieldAccessModifier();
    return myIsFinal || PsiModifier.PRIVATE.equals(fieldAccessModifier) || PsiModifier.PROTECTED.equals(fieldAccessModifier);
  }

  private void createModificationWatchpointRequest(@NotNull DebugProcessImpl debugProcess, @NotNull ReferenceType refType) {
    Field field = refType.fieldByName(getFieldName());
    if (field == null) {
      return;
    }
    VirtualMachineProxyImpl vm = debugProcess.getVirtualMachineProxy();
    if (vm.canWatchFieldModification()) {
      RequestManagerImpl requestsManager = debugProcess.getRequestsManager();
      ModificationWatchpointRequest request = requestsManager.createModificationWatchpointRequest(this, field);
      requestsManager.enableRequest(request);
    }
  }

  private void createModificationWatchpointRequestEmulated(DebugProcessImpl debugProcess, ReferenceType refType) {
    createRequestForClass(refType);
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
    myFieldOwnerClsPreparedOnce = false;
    myLineBreakpointsAreSet = false;
    myProcessedOneSuspending = false;
  }

  private synchronized String getFieldAccessModifier() {
    return myFieldAccessModifier;
  }

  private synchronized void setFieldAccessModifier(String modifier) {
    myFieldAccessModifier = modifier;
  }

  private void setProperties(@NotNull SuspendContextImpl context) {
    String fieldOwnerClsName = myFieldOwnerJVMClsName;
    String fieldName = getFieldName();
    if (fieldOwnerClsName == null || fieldName == null) {
      return;
    }
    CollectionBreakpointUtils.setCollectionHistorySavingEnabled(
      context, fieldOwnerClsName, fieldName, shouldSaveCollectionHistory()
    );
  }

  private boolean shouldSaveCollectionHistory() {
    return getProperties().SHOULD_SAVE_COLLECTION_HISTORY;
  }

  private void createRequestForClass(ReferenceType refType) {
    myUnprocessedClasses.add(refType.name());
  }

  private void processConstructorEntry(@NotNull SuspendContextImpl context, LocatableEvent event) {
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
    MyRequestor requestor = new MyRequestor();
    myAdditionRequestors.add(requestor);
    addFieldWatchpoint(requestor, context, declaringType, thisObj);
    createMethodExitRequest(requestor, context, declaringType, thisObj, thread);
  }

  private void processInstancesInJVM(@NotNull Set<String> clsNames,
                                     @NotNull SuspendContextImpl context,
                                     @NotNull LocatableEvent event,
                                     @NotNull MethodEntryPlace place) {
    List<ObjectReference> instances = getTrackedInstancesInJVM(context, clsNames);
    if (instances.isEmpty()) {
      return;
    }

    if (instances.size() == 1 && MethodEntryPlace.CONSTRUCTOR.equals(place)) {
      processConstructorEntry(context, event);
    }
    else {
      processAllThreadsAndFrames(context);
    }
  }

  private void processClassesInJVM(@NotNull Set<String> clsNames,
                                   @NotNull SuspendContextImpl context,
                                   @NotNull LocatableEvent event,
                                   @NotNull MethodEntryPlace place) {
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
      processAllThreadsAndFrames(context);
    }
  }

  private void processObsoleteMethodsOnStacks(@NotNull Set<String> clsNames,
                                              @NotNull SuspendContextImpl context,
                                              LocatableEvent event,
                                              MethodEntryPlace place) {
    if (myIsStatic) {
      processClassesInJVM(clsNames, context, event, place);
    }
    else {
      processInstancesInJVM(clsNames, context, event, place);
    }
  }

  private void captureCurrentFieldValues(@NotNull SuspendContextImpl context) {
    if (myIsStatic) {
      captureFieldValuesFromClasses(context);
    }
    else {
      captureFieldValuesFromInstances(context);
    }
  }

  private void captureFieldValuesFromClasses(@NotNull SuspendContextImpl context) {
    String fieldName = getFieldName();
    if (fieldName == null) {
      return;
    }

    List<ReferenceType> baseClasses = getFieldOwnerClassesRefs(context.getDebugProcess());
    for (ReferenceType baseCls : baseClasses) {
      Field field = baseCls.fieldByName(fieldName);
      if (field != null) captureClsField(context, baseCls, field);
    }
  }

  private @NotNull List<ReferenceType> getFieldOwnerClassesRefs(@NotNull DebugProcessImpl debugProcess) {
    String fieldOwnerClsName = myFieldOwnerJVMClsName;
    if (fieldOwnerClsName == null) {
      return Collections.emptyList();
    }
    VirtualMachineProxyImpl vm = debugProcess.getVirtualMachineProxy();
    return vm.classesByName(fieldOwnerClsName);
  }

  private void processAllThreadsAndFrames(@NotNull SuspendContextImpl context) {
    VirtualMachineProxyImpl vm = context.getDebugProcess().getVirtualMachineProxy();

    for (ThreadReferenceProxyImpl thread : vm.allThreads()) {
      try {
        if (thread.isSuspended()) {
          processAllFrames(thread, context);
        }
      }
      catch (EvaluateException e) {
        LOG.debug(e);
      }
    }
  }

  private void processAllFrames(@NotNull ThreadReferenceProxyImpl thread, @NotNull SuspendContextImpl context) throws EvaluateException {
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

  private void captureFieldValuesFromInstances(@NotNull SuspendContextImpl context) {
    String fieldName = getFieldName();
    if (fieldName == null) {
      return;
    }

    VirtualMachineProxyImpl vm = context.getDebugProcess().getVirtualMachineProxy();
    List<ReferenceType> baseClasses = getFieldOwnerClassesRefs(context.getDebugProcess());

    for (ReferenceType cls : vm.allClasses()) {
      for (ReferenceType baseCls : baseClasses) {
        if (DebuggerUtilsImpl.instanceOf(cls, baseCls)) {
          Field field = cls.fieldByName(fieldName);
          if (field != null) {
            cls.instances(MAX_INSTANCES_NUMBER)
              .forEach(instance -> captureInstanceField(context, instance, field));
          }
        }
      }
    }
  }

  private void captureClsField(@NotNull SuspendContextImpl context, @NotNull ReferenceType cls, @NotNull Field field) {
    Value value = cls.getValue(field);
    if (value != null) {
      captureFieldModification(value, null, false, context);
    }
  }

  private void captureInstanceField(@NotNull SuspendContextImpl context, @NotNull ObjectReference instance, @NotNull Field field) {
    Value value = instance.getValue(field);
    if (value != null) {
      captureFieldModification(value, instance, false, context);
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

    // wait for the subclasses
    RequestManagerImpl requestManager = debugProcess.getRequestsManager();
    ClassPrepareRequest request = requestManager.createClassPrepareRequest((debuggerProcess, derivedType) -> {
      createRequestForClass(derivedType);
      createAllMethodsEntryRequest(debugProcess);
    }, null);
    if (request != null) {
      requestManager.registerRequest(this, request);
      request.addClassFilter(baseType);
      request.enable();
    }

    // create a request for subclasses that are already loaded
    vm.allClasses().stream()
      .filter(type -> DebuggerUtilsImpl.instanceOf(type, baseType) && !type.name().equals(baseType.name()))
      .forEach(derivedType -> {
        createRequestForClass(derivedType);
      });
  }

  private synchronized String getFieldName() {
    return getProperties().myFieldName;
  }

  // creates this request or enables it if it exists
  private void createAllMethodsEntryRequest(DebugProcessImpl debugProcess) {
    RequestManagerImpl requestsManager = debugProcess.getRequestsManager();
    MethodEntryRequest request = MethodBreakpoint.findRequest(debugProcess, MethodEntryRequest.class, this);
    if (request == null) {
      request = requestsManager.createMethodEntryRequest(this);
      requestsManager.enableRequest(request);
    }
    else if (!request.isEnabled()) {
      requestsManager.enableRequest(request);
    }
  }

  private void captureFieldModification(Value valueToBe,
                                        ObjectReference fieldOwnerInstance,
                                        boolean shouldSaveStack,
                                        SuspendContextImpl context) {
    String fieldOwnerClsName = myFieldOwnerJVMClsName;
    String fieldName = getFieldName();
    if (fieldOwnerClsName == null || fieldName == null) {
      return;
    }

    CollectionBreakpointUtils.captureFieldModification(context, fieldOwnerClsName, fieldName, valueToBe, fieldOwnerInstance,
                                                       shouldSaveStack);
  }

  private void setLineBreakpointsIfNeeded(@NotNull DebugProcessImpl debugProcess) {
    if (suspendOnBreakpointHit() && !myLineBreakpointsAreSet) {
      setLineBreakpoints(debugProcess);
      myLineBreakpointsAreSet = true;
    }
  }

  private void transformClassesToEmulateFieldWatchpoint(SuspendContextImpl context) {
    String fieldOwnerClsName = myFieldOwnerJVMClsName;
    String fieldName = getFieldName();
    String fieldDescriptor = myFieldDescriptor;
    if (fieldOwnerClsName == null || fieldName == null || fieldDescriptor == null) {
      return;
    }
    CollectionBreakpointUtils.emulateFieldWatchpoint(context, fieldOwnerClsName, fieldName, fieldDescriptor, myUnprocessedClasses);
  }

  private void setLineBreakpoints(@NotNull DebugProcessImpl debugProcess) {
    List<Location> locations = CollectionBreakpointUtils.findLocationsForLineBreakpoints(debugProcess, canEmulateFieldWatchpoint());
    for (Location location : locations) {
      SourcePosition position = locationToPosition(debugProcess, location);
      MyLineBreakpoint breakpoint = new MyLineBreakpoint(location, position);
      myAdditionRequestors.add(breakpoint);
      breakpoint.createBreakpointRequest(debugProcess);
    }
  }

  private boolean suspendOnBreakpointHit() {
    return !DebuggerSettings.SUSPEND_NONE.equals(getSuspendPolicy());
  }

  private static @NotNull List<ReferenceType> getTrackedClassesInJVM(@NotNull SuspendContextImpl context,
                                                                     @NotNull Set<String> clsNames) {
    DebugProcessImpl debugProcess = context.getDebugProcess();
    VirtualMachineProxyImpl vm = debugProcess.getVirtualMachineProxy();

    return clsNames.stream()
      .map(name -> vm.classesByName(name))
      .flatMap(list -> list.stream())
      .filter(cls -> cls.isPrepared())
      .collect(Collectors.toList());
  }

  private static @NotNull List<ObjectReference> getTrackedInstancesInJVM(@NotNull SuspendContextImpl context,
                                                                         @NotNull Set<String> clsNames) {
    return getTrackedClassesInJVM(context, clsNames)
      .stream()
      .map(cls -> cls.instances(MAX_INSTANCES_NUMBER))
      .flatMap(list -> list.stream())
      .collect(Collectors.toList());
  }

  private static @NotNull String getFieldModifier(@NotNull PsiField field) {
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

  private static void createMethodExitRequest(@NotNull FilteredRequestor requestor,
                                              @NotNull SuspendContextImpl context,
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

  private static boolean tryPopFrame(@NotNull SuspendContextImpl suspendContext) {
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
      LOG.warn(e);
    }
    return false;
  }

  private enum MethodEntryPlace {
    STATIC_BLOCK, CONSTRUCTOR, DEFAULT
  }

  private class MyRequestor extends FilteredRequestorImpl {

    private MyRequestor() {
      super(CollectionBreakpoint.this.myProject);
    }

    @Override
    public boolean processLocatableEvent(@NotNull SuspendContextCommandImpl action, LocatableEvent event) throws EventProcessingException {
      SuspendContextImpl context = action.getSuspendContext();
      if (context == null) {
        return false;
      }

      if (event instanceof ModificationWatchpointEvent && event.location().method().isObsolete()) {
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
        myAdditionRequestors.remove(this);
      }
    }
  }

  private class MyLineBreakpoint extends SyntheticLineBreakpoint {
    private final @Nullable SourcePosition myPosition;
    private final @Nullable Location myLocation;

    private MyLineBreakpoint(@Nullable Location location, @Nullable SourcePosition position) {
      super(CollectionBreakpoint.this.myProject);
      myLocation = location;
      myPosition = position;
      setSuspendPolicy(CollectionBreakpoint.this.getSuspendPolicy());
    }

    private void createBreakpointRequest(@NotNull DebugProcessImpl debugProcess) {
      if (myLocation != null) {
        createLocationBreakpointRequest(this, myLocation, debugProcess);
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
        LOG.warn(e);
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
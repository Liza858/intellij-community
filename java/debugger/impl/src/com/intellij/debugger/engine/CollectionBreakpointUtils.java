// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.debugger.engine;

import com.intellij.debugger.engine.evaluation.EvaluateException;
import com.intellij.debugger.engine.evaluation.EvaluationContext;
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl;
import com.intellij.debugger.engine.requests.RequestManagerImpl;
import com.intellij.debugger.impl.DebuggerUtilsEx;
import com.intellij.debugger.jdi.*;
import com.intellij.debugger.memory.utils.StackFrameItem;
import com.intellij.debugger.requests.ClassPrepareRequestor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.util.containers.ContainerUtil;
import com.sun.jdi.*;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@ApiStatus.Experimental
public final class CollectionBreakpointUtils {
  private static final Logger LOG = Logger.getInstance(CollectionBreakpointUtils.class);

  private static final String OBJECT_TYPE = "Ljava/lang/Object;";
  private static final String STRING_TYPE = "Ljava/lang/String;";

  private static final String INSTRUMENTOR_CLS_NAME = "com.intellij.rt.debugger.agent.CollectionBreakpointInstrumentor";
  private static final String STORAGE_CLASS_NAME = "com.intellij.rt.debugger.agent.CollectionBreakpointStorage";

  private static final String ENTRY_CLS_NAME = "com.intellij.rt.debugger.agent.util.Entry";

  private static final String COLLECTION_MODIFICATION_INFO_CLASS_NAME = STORAGE_CLASS_NAME + "$" + "CollectionModificationInfo";

  private static final String ENABLE_DEBUG_MODE_FIELD = "DEBUG";

  private static final String ENABLE_HISTORY_SAVING_METHOD_NAME = "setSavingHistoryForFieldEnabled";
  private static final String ENABLE_HISTORY_SAVING_METHOD_DESC = "(Ljava/lang/String;Ljava/lang/String;Z)V";

  private static final String EMULATE_FIELD_WATCHPOINT_METHOD_NAME = "emulateFieldWatchpoint";
  private static final String EMULATE_FIELD_WATCHPOINT_METHOD_DESC = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V";
  private static final String CAPTURE_FIELD_MODIFICATION_METHOD_NAME = "captureFieldModification";
  private static final String CAPTURE_FIELD_MODIFICATION_METHOD_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Z)V";
  private static final String TRANSFORM_COLLECTION_AND_SAVE_FIELD_MODIFICATION_METHOD_NAME = "transformCollectionAndSaveFieldModification";
  private static final String TRANSFORM_COLLECTION_AND_SAVE_FIELD_MODIFICATION_METHOD_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Z)V";
  private static final String CAPTURE_COLLECTION_MODIFICATION_DEFAULT_METHOD_NAME = "captureCollectionModification";
  private static final String CAPTURE_COLLECTION_MODIFICATION_DEFAULT_METHOD_DESC = "(Lcom/intellij/rt/debugger/agent/util/Multiset;Ljava/lang/Object;)V";
  private static final String CAPTURE_COLLECTION_MODIFICATION_SPECIAL_METHOD_NAME = "captureCollectionModification";
  private static final String CAPTURE_COLLECTION_MODIFICATION_SPECIAL_METHOD_DESC = "(ZZLjava/lang/Object;Ljava/lang/Object;Z)V";
  private static final String ON_CAPTURE_END_METHOD_NAME = "onCaptureEnd";
  private static final String ON_CAPTURE_END_METHOD_DESC = "(Ljava/util/IdentityHashMap;)V";
  private static final String GET_FIELD_MODIFICATIONS_METHOD_NAME = "getFieldModifications";
  private static final String GET_FIELD_MODIFICATIONS_METHOD_DESC = "(" + STRING_TYPE + STRING_TYPE + OBJECT_TYPE + ")[" + OBJECT_TYPE;
  private static final String GET_COLLECTION_MODIFICATIONS_METHOD_NAME = "getCollectionModifications";
  private static final String GET_COLLECTION_MODIFICATIONS_METHOD_DESC = "(" + OBJECT_TYPE + ")[" + OBJECT_TYPE;
  private static final String GET_COLLECTION_STACK_METHOD_NAME = "getStack";
  private static final String GET_COLLECTION_STACK_METHOD_DESC = "(" + OBJECT_TYPE + "I" + ")" + STRING_TYPE;
  private static final String GET_FIELD_STACK_METHOD_NAME = "getStack";
  private static final String GET_FIELD_STACK_METHOD_DESC = "(" + STRING_TYPE + STRING_TYPE + OBJECT_TYPE + "I" + ")" + STRING_TYPE;
  private static final String GET_ELEMENT_METHOD_NAME = "getElement";
  private static final String GET_ELEMENT_METHOD_DESC = "()" + OBJECT_TYPE;
  private static final String IS_ADDITION_METHOD_NAME = "isAddition";
  private static final String IS_ADDITION_METHOD_DESC = "()Z";
  private static final String GET_KEY_METHOD_NAME = "getKey";
  private static final String GET_KEY_METHOD_DESC = "()" + OBJECT_TYPE;
  private static final String GET_VALUE_METHOD_NAME = "getValue";
  private static final String GET_VALUE_METHOD_DESC = "()" + OBJECT_TYPE;

  public static void setupCollectionBreakpointAgent(@NotNull DebugProcessImpl debugProcess) {
    if (Registry.is("debugger.collection.breakpoint.agent.debug")) {
      enableDebugMode(debugProcess);
    }
  }

  private static void enableDebugMode(@NotNull DebugProcessImpl debugProcess) {
    try {
      setClassBooleanField(debugProcess, INSTRUMENTOR_CLS_NAME, ENABLE_DEBUG_MODE_FIELD, true);
    }
    catch (Exception e) {
      LOG.warn("Error setting collection breakpoint agent debug mode", e);
    }
  }

  public static void setCollectionHistorySavingEnabled(@NotNull SuspendContextImpl context,
                                                       @NotNull String fieldOwnerClsName,
                                                       @NotNull String fieldName,
                                                       boolean enabled) {
    DebuggerManagerThreadImpl.assertIsManagerThread();
    VirtualMachineProxyImpl vm = context.getDebugProcess().getVirtualMachineProxy();

    Value fieldOwnerClsNameRef = vm.mirrorOf(fieldOwnerClsName);
    Value fieldNameRef = vm.mirrorOf(fieldName);
    Value enabledRef = vm.mirrorOf(enabled);

    invokeStorageMethod(context,
                        ENABLE_HISTORY_SAVING_METHOD_NAME,
                        ENABLE_HISTORY_SAVING_METHOD_DESC,
                        toList(fieldOwnerClsNameRef, fieldNameRef, enabledRef));
  }

  private static void setClassBooleanField(@NotNull DebugProcessImpl debugProcess,
                                           @NotNull String clsName,
                                           @NotNull String fieldName,
                                           boolean value) throws EvaluateException {
    final RequestManagerImpl requestsManager = debugProcess.getRequestsManager();
    ClassPrepareRequestor requestor = new ClassPrepareRequestor() {
      @Override
      public void processClassPrepare(DebugProcess debuggerProcess, ReferenceType referenceType) {
        try {
          requestsManager.deleteRequest(this);
          Field field = referenceType.fieldByName(fieldName);
          if (field == null) {
            LOG.warn("Can't find field " + fieldName + " of class " + clsName);
            return;
          }
          Value trueValue = debugProcess.getVirtualMachineProxy().mirrorOf(value);
          ((ClassType)referenceType).setValue(field, trueValue);
        }
        catch (Exception e) {
          LOG.warn("Error setting field " + fieldName + " of class " + clsName, e);
        }
      }
    };

    requestsManager.callbackOnPrepareClasses(requestor, clsName);

    ClassType captureClass = (ClassType)debugProcess.findClass(null, clsName, null);
    if (captureClass != null) {
      requestor.processClassPrepare(debugProcess, captureClass);
    }
  }

  @NotNull
  public static List<Value> getFieldModificationsHistory(@NotNull SuspendContextImpl context,
                                                         @NotNull String fieldName,
                                                         @NotNull String fieldOwnerClsName,
                                                         @Nullable Value clsInstance) {
    DebuggerManagerThreadImpl.assertIsManagerThread();
    VirtualMachineProxyImpl vm = context.getDebugProcess().getVirtualMachineProxy();

    Value fieldOwnerClsNameRef = vm.mirrorOf(fieldOwnerClsName);
    Value fieldNameRef = vm.mirrorOf(fieldName);

    Value result = invokeStorageMethod(context,
                                       GET_FIELD_MODIFICATIONS_METHOD_NAME,
                                       GET_FIELD_MODIFICATIONS_METHOD_DESC,
                                       toList(fieldOwnerClsNameRef, fieldNameRef, clsInstance));

    if (result instanceof ArrayReference) {
      return ((ArrayReference)result).getValues();
    }

    return Collections.emptyList();
  }

  @NotNull
  public static List<StackFrameItem> getFieldModificationStack(@NotNull SuspendContextImpl context,
                                                               @NotNull String fieldName,
                                                               @NotNull String fieldOwnerClsName,
                                                               @Nullable Value clsInstance,
                                                               @NotNull IntegerValue modificationIndex) {
    DebuggerManagerThreadImpl.assertIsManagerThread();
    VirtualMachineProxyImpl vm = context.getDebugProcess().getVirtualMachineProxy();

    Value fieldOwnerClsNameRef = vm.mirrorOf(fieldOwnerClsName);
    Value fieldNameRef = vm.mirrorOf(fieldName);

    Value result = invokeStorageMethod(
      context, GET_FIELD_STACK_METHOD_NAME, GET_FIELD_STACK_METHOD_DESC,
      toList(fieldOwnerClsNameRef, fieldNameRef, clsInstance, modificationIndex)
    );

    String message = result instanceof StringReference ? ((StringReference)result).value() : "";

    return readStackItems(context.getDebugProcess(), message, vm);
  }

  @NotNull
  private static List<StackFrameItem> readStackItems(@NotNull DebugProcessImpl debugProcess,
                                                     @NotNull String message,
                                                     @NotNull VirtualMachineProxyImpl vm) {
    List<StackFrameItem> items = new ArrayList<>();
    ClassesByNameProvider classesByName = ClassesByNameProvider.createCache(vm.allClasses());
    try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(message.getBytes(StandardCharsets.ISO_8859_1)))) {
      while (dis.available() > 0) {
        String className = dis.readUTF();
        String methodName = dis.readUTF();
        int line = dis.readInt();
        Location location = findLocation(debugProcess, classesByName, className, methodName, line);
        StackFrameItem item = new StackFrameItem(location, null);
        items.add(item);
      }
    }
    catch (Exception e) {
      LOG.warn(e);
    }
    return items;
  }

  @NotNull
  private static Location findLocation(@NotNull DebugProcessImpl debugProcess,
                                       @NotNull ClassesByNameProvider classesByName,
                                       @NotNull String className,
                                       @NotNull String methodName,
                                       int line) {
    ReferenceType classType = ContainerUtil.getFirstItem(classesByName.get(className));
    if (classType == null) {
      classType = new GeneratedReferenceType(debugProcess.getVirtualMachineProxy().getVirtualMachine(), className);
    }
    else if (line >= 0) {
      for (Method method : DebuggerUtilsEx.declaredMethodsByName(classType, methodName)) {
        List<Location> locations = DebuggerUtilsEx.locationsOfLine(method, line);
        if (!locations.isEmpty()) {
          return locations.get(0);
        }
      }
    }
    return new GeneratedLocation(classType, methodName, line);
  }

  @NotNull
  public static List<Value> getCollectionModificationsHistory(@NotNull SuspendContextImpl context, @NotNull Value collectionInstance) {
    DebuggerManagerThreadImpl.assertIsManagerThread();
    Value collectionModifications = invokeStorageMethod(context,
                                                        GET_COLLECTION_MODIFICATIONS_METHOD_NAME,
                                                        GET_COLLECTION_MODIFICATIONS_METHOD_DESC,
                                                        Collections.singletonList(collectionInstance));
    if (collectionModifications instanceof ArrayReference) {
      return ((ArrayReference)collectionModifications).getValues();
    }

    return Collections.emptyList();
  }

  @NotNull
  public static List<StackFrameItem> getCollectionModificationStack(@NotNull SuspendContextImpl context,
                                                                    @Nullable Value collectionInstance,
                                                                    @NotNull IntegerValue modificationIndex) {
    DebuggerManagerThreadImpl.assertIsManagerThread();
    VirtualMachineProxyImpl vm = context.getDebugProcess().getVirtualMachineProxy();

    Value result = invokeStorageMethod(context,
                                       GET_COLLECTION_STACK_METHOD_NAME,
                                       GET_COLLECTION_STACK_METHOD_DESC,
                                       toList(collectionInstance, modificationIndex));

    String message = result instanceof StringReference ? ((StringReference)result).value() : "";

    return readStackItems(context.getDebugProcess(), message, vm);
  }

  @NotNull
  private static List<Value> toList(Value... elements) {
    List<Value> list = new ArrayList<>();
    Collections.addAll(list, elements);
    return list;
  }

  @Nullable
  public static Pair<ObjectReference, BooleanValue> getCollectionModificationInfo(@NotNull DebugProcessImpl debugProcess,
                                                                                  @NotNull EvaluationContext evaluationContext,
                                                                                  @NotNull ObjectReference collectionInstance) {
    DebuggerManagerThreadImpl.assertIsManagerThread();
    ClassType cls = getClass(debugProcess, evaluationContext, COLLECTION_MODIFICATION_INFO_CLASS_NAME);
    if (cls != null) {
      Method getElementMethod = DebuggerUtils.findMethod(cls, GET_ELEMENT_METHOD_NAME, GET_ELEMENT_METHOD_DESC);
      Method isAdditionMethod = DebuggerUtils.findMethod(cls, IS_ADDITION_METHOD_NAME, IS_ADDITION_METHOD_DESC);
      if (getElementMethod == null || isAdditionMethod == null) {
        return null;
      }
      try {
        Value element =
          debugProcess.invokeInstanceMethod(evaluationContext, collectionInstance, getElementMethod, Collections.emptyList(), 0);
        Value isAddition =
          debugProcess.invokeInstanceMethod(evaluationContext, collectionInstance, isAdditionMethod, Collections.emptyList(), 0);
        if (element instanceof ObjectReference && isAddition instanceof BooleanValue) {
          return new Pair<>((ObjectReference)element, (BooleanValue)isAddition);
        }
      }
      catch (EvaluateException e) {
        LOG.warn(e);
      }
    }
    return null;
  }

  @Nullable
  public static Pair<Value, Value> getKeyAndValue(@NotNull DebugProcessImpl debugProcess,
                                                  @NotNull EvaluationContext evaluationContext,
                                                  @NotNull ObjectReference mapEntryRef) {
    DebuggerManagerThreadImpl.assertIsManagerThread();
    ClassType cls = getClass(debugProcess, evaluationContext, ENTRY_CLS_NAME);
    if (cls != null) {
      Method getKeyMethod = DebuggerUtils.findMethod(cls, GET_KEY_METHOD_NAME, GET_KEY_METHOD_DESC);
      Method getValueMethod = DebuggerUtils.findMethod(cls, GET_VALUE_METHOD_NAME, GET_VALUE_METHOD_DESC);
      if (getKeyMethod == null || getValueMethod == null) {
        return null;
      }
      try {
        Value key = debugProcess.invokeInstanceMethod(evaluationContext, mapEntryRef, getKeyMethod, Collections.emptyList(), 0);
        Value value = debugProcess.invokeInstanceMethod(evaluationContext, mapEntryRef, getValueMethod, Collections.emptyList(), 0);
        if (key != null && value != null) {
          return new Pair<>(key, value);
        }
      }
      catch (EvaluateException e) {
        LOG.warn(e);
      }
    }
    return null;
  }

  public static void captureFieldModification(@NotNull SuspendContextImpl context,
                                              @NotNull String fieldOwnerClsName,
                                              @NotNull String fieldName,
                                              @Nullable Value valueToBe,
                                              @Nullable ObjectReference fieldOwnerInstance,
                                              boolean shouldSaveStack) {
    DebuggerManagerThreadImpl.assertIsManagerThread();
    StackFrameProxyImpl frameProxy = context.getFrameProxy();
    if (frameProxy == null) {
      return;
    }

    Value fieldOwnerClsNameRef = frameProxy.getVirtualMachine().mirrorOf(fieldOwnerClsName);
    Value fieldNameRef = frameProxy.getVirtualMachine().mirrorOf(fieldName);
    Value shouldSaveStackRef = frameProxy.getVirtualMachine().mirrorOf(shouldSaveStack);

    ArrayList<Value> args = new ArrayList<>();
    args.add(valueToBe);
    args.add(fieldOwnerInstance);
    args.add(fieldOwnerClsNameRef);
    args.add(fieldNameRef);
    args.add(shouldSaveStackRef);

    invokeInstrumentorMethod(context,
                             TRANSFORM_COLLECTION_AND_SAVE_FIELD_MODIFICATION_METHOD_NAME,
                             TRANSFORM_COLLECTION_AND_SAVE_FIELD_MODIFICATION_METHOD_DESC,
                             args);
  }

  public static void emulateFieldWatchpoint(@NotNull SuspendContextImpl context,
                                            @NotNull String fieldOwnerClsName,
                                            @NotNull String fieldName,
                                            @NotNull String fieldDescriptor,
                                            @NotNull Set<String> unprocessedClasses) {
    StackFrameProxyImpl frameProxy = context.getFrameProxy();
    if (frameProxy == null) {
      return;
    }

    Value fieldOwnerClsNameRef = frameProxy.getVirtualMachine().mirrorOf(fieldOwnerClsName);
    Value fieldNameRef = frameProxy.getVirtualMachine().mirrorOf(fieldName);
    Value fieldTypeSigRef = frameProxy.getVirtualMachine().mirrorOf(fieldDescriptor);

    List<Value> clsNamesRef = ContainerUtil.map(unprocessedClasses, clsName -> frameProxy.getVirtualMachine().mirrorOf(clsName));

    List<Value> args = new ArrayList<>();
    args.add(fieldOwnerClsNameRef);
    args.add(fieldNameRef);
    args.add(fieldTypeSigRef);
    args.addAll(clsNamesRef);

    invokeInstrumentorMethod(context,
                             EMULATE_FIELD_WATCHPOINT_METHOD_NAME,
                             EMULATE_FIELD_WATCHPOINT_METHOD_DESC,
                             args);
  }

  @Nullable
  private static Location findLocationInMethod(@NotNull ClassType instrumentorCls,
                                               @NotNull String methodName,
                                               @NotNull String methodDesc,
                                               int lineNumber) {
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
      LOG.warn(e);
    }
    return null;
  }

  private static @NotNull List<@Nullable Location> findLocationsInCollectionModificationsTrackers(@NotNull ClassType instrumentorCls) {
    List<Location> locations = new ArrayList<>();
    locations.add(findLocationInMethod(instrumentorCls, CAPTURE_COLLECTION_MODIFICATION_DEFAULT_METHOD_NAME,
                                       CAPTURE_COLLECTION_MODIFICATION_DEFAULT_METHOD_DESC, 5));
    locations.add(findLocationInMethod(instrumentorCls, CAPTURE_COLLECTION_MODIFICATION_SPECIAL_METHOD_NAME,
                                       CAPTURE_COLLECTION_MODIFICATION_SPECIAL_METHOD_DESC, 2));
    locations.add(findLocationInMethod(instrumentorCls, ON_CAPTURE_END_METHOD_NAME,
                                       ON_CAPTURE_END_METHOD_DESC, 8));
    return locations;
  }

  private static @NotNull List<@Nullable Location> findLocationsInFieldModificationsTrackers(@NotNull ClassType instrumentorCls) {
    List<Location> locations = new ArrayList<>();
    locations.add(findLocationInMethod(instrumentorCls, CAPTURE_FIELD_MODIFICATION_METHOD_NAME,
                                       CAPTURE_FIELD_MODIFICATION_METHOD_DESC, 3));
    return locations;
  }

  @NotNull
  public static List<Location> findLocationsForLineBreakpoints(@NotNull DebugProcessImpl debugProcess,
                                                               boolean fieldWatchpointIsEmulated) {

    ClassType instrumentorCls = getInstrumentorClass(debugProcess, null);
    if (instrumentorCls == null) {
      LOG.warn("can't find instrumentor class");
      return Collections.emptyList();
    }
    List<@Nullable Location> locations = findLocationsInCollectionModificationsTrackers(instrumentorCls);
    if (fieldWatchpointIsEmulated) {
      locations.addAll(findLocationsInFieldModificationsTrackers(instrumentorCls));
    }
    if (locations.contains(null)) {
      LOG.warn("can't find locations for line breakpoints in instrumentor methods");
      return Collections.emptyList();
    }
    return locations;
  }

  public static Value invokeInstrumentorMethod(@NotNull SuspendContextImpl context,
                                               @NotNull String methodName,
                                               @NotNull String methodDesc,
                                               @NotNull List<Value> args) {
    return invokeMethod(context, INSTRUMENTOR_CLS_NAME, methodName, methodDesc, args);
  }

  public static Value invokeStorageMethod(@NotNull SuspendContextImpl context,
                                          @NotNull String methodName,
                                          @NotNull String methodDesc,
                                          @NotNull List<Value> args) {
    return invokeMethod(context, STORAGE_CLASS_NAME, methodName, methodDesc, args);
  }

  @Nullable
  private static ClassType getClass(@NotNull DebugProcessImpl debugProcess,
                                    @Nullable EvaluationContext evalContext,
                                    @NotNull String clsName) {
    try {
      return (ClassType)debugProcess.findClass(evalContext, clsName, null);
    }
    catch (EvaluateException e) {
      return null;
    }
  }

  @Nullable
  public static ClassType getInstrumentorClass(@NotNull DebugProcessImpl debugProcess, @Nullable EvaluationContextImpl evalContext) {
    DebuggerManagerThreadImpl.assertIsManagerThread();
    return getClass(debugProcess, evalContext, INSTRUMENTOR_CLS_NAME);
  }

  @Nullable
  public static ClassType getStorageClass(@NotNull DebugProcessImpl debugProcess, @Nullable EvaluationContextImpl evalContext) {
    DebuggerManagerThreadImpl.assertIsManagerThread();
    return getClass(debugProcess, evalContext, STORAGE_CLASS_NAME);
  }

  @Nullable
  private static Value invokeMethod(@NotNull SuspendContextImpl context,
                                    @NotNull String clsName,
                                    @NotNull String methodName,
                                    @NotNull String methodDesc,
                                    @NotNull List<Value> args) {
    DebuggerManagerThreadImpl.assertIsManagerThread();
    DebugProcessImpl debugProcess = context.getDebugProcess();
    EvaluationContextImpl evalContext = new EvaluationContextImpl(context, context.getFrameProxy());
    evalContext = evalContext.withAutoLoadClasses(false);
    try {
      ClassType cls = getClass(debugProcess, evalContext, clsName);
      if (cls == null) {
        return null;
      }
      Method method = DebuggerUtils.findMethod(cls, methodName, methodDesc);
      if (method != null) {
        return debugProcess.invokeMethod(evalContext, cls, method, args, true);
      }
    }
    catch (EvaluateException e) {
      LOG.warn(e);
    }
    return null;
  }
}

// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.rt.debugger.agent;

import com.intellij.rt.debugger.agent.util.Entry;
import com.intellij.rt.debugger.agent.util.IdentityWrapper;
import com.intellij.rt.debugger.agent.util.Multiset;
import com.intellij.rt.debugger.agent.util.Utils;
import org.jetbrains.capture.org.objectweb.asm.*;
import org.jetbrains.capture.org.objectweb.asm.commons.LocalVariablesSorter;
import org.jetbrains.capture.org.objectweb.asm.tree.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

@SuppressWarnings({"UseOfSystemOutOrSystemErr", "CallToPrintStackTrace", "rawtypes"})
public class CollectionBreakpointInstrumentor {
  private static final String OBJECT_CLS_NAME = "java/lang/Object";
  private static final String COLLECTION_CLS_NAME = "java/util/Collection";
  private static final String MAP_CLS_NAME = "java/util/Map";
  private static final String ABSTRACT_COLLECTION_CLS_NAME = "java/util/AbstractCollection";
  private static final String ABSTRACT_LIST_CLS_NAME = "java/util/AbstractList";
  private static final String ARRAY_LIST_CLS_NAME = "java/util/ArrayList";

  private static final String OBJECT_TYPE = "L" + OBJECT_CLS_NAME + ";";
  private static final String STRING_TYPE = "Ljava/lang/String;";
  private static final String MULTISET_TYPE = "Lcom/intellij/rt/debugger/agent/util/Multiset;";
  private static final String ENTRY_TYPE = "Lcom/intellij/rt/debugger/agent/util/Entry;";
  private static final String IDENTITY_MAP_TYPE = "Ljava/util/IdentityHashMap;";

  private static final String CAPTURE_COLLECTION_MODIFICATION_METHOD_NAME = "captureCollectionModification";
  private static final String CAPTURE_COLLECTION_MODIFICATION_METHOD_DESC = "(" + "Z" + "Z" + OBJECT_TYPE + OBJECT_TYPE + "Z" + ")V";
  private static final String CAPTURE_COLLECTION_MODIFICATION_DEFAULT_METHOD_NAME = "captureCollectionModification";
  private static final String CAPTURE_COLLECTION_MODIFICATION_DEFAULT_METHOD_DESC = "(" + MULTISET_TYPE + OBJECT_TYPE + ")V";
  private static final String CAPTURE_FIELD_MODIFICATION_METHOD_NAME = "captureFieldModification";
  private static final String CAPTURE_FIELD_MODIFICATION_METHOD_DESC = "(" + OBJECT_TYPE + OBJECT_TYPE + STRING_TYPE + STRING_TYPE + "Z)V";
  private static final String ON_CAPTURE_START_METHOD_NAME = "onCaptureStart";
  private static final String ON_CAPTURE_START_DEFAULT_METHOD_DESC = "(" + OBJECT_TYPE + "Z)Z";
  private static final String ON_CAPTURE_START_NESTED_CLS_METHOD_DESC = "(" + OBJECT_TYPE + IDENTITY_MAP_TYPE + ")V";
  private static final String ON_CAPTURE_END_METHOD_NAME = "onCaptureEnd";
  private static final String ON_CAPTURE_END_DEFAULT_METHOD_DESC = "(" + OBJECT_TYPE + "Z)V";
  private static final String ON_CAPTURE_END_NESTED_CLS_METHOD_DESC = "(" + IDENTITY_MAP_TYPE + ")V";
  private static final String CAPTURE_COLLECTION_COPY_METHOD_NAME = "captureCollectionCopy";
  private static final String CAPTURE_COLLECTION_COPY_METHOD_DESC = "(" + "Z" + OBJECT_TYPE + ")" + MULTISET_TYPE;
  private static final String GET_COPIES_STORAGE_METHOD_NAME = "getCopiesStorage";
  private static final String GET_COPIES_STORAGE_METHOD_DESC = "()" + IDENTITY_MAP_TYPE;
  private static final String CONSTRUCTOR_METHOD_NAME = "<init>";
  private static final String CREATE_ENTRY_METHOD_NAME = "createEntry";
  private static final String CREATE_ENTRY_METHOD_DESC = "(" + OBJECT_TYPE + OBJECT_TYPE + ")" + ENTRY_TYPE;

  private static final TrackedCollections myTrackedCollections = new TrackedCollections();
  private static final TrackedFields myTrackedFields = new TrackedFields();

  private static final Map<String, KnownMethodsSet> myKnownMethods = new HashMap<String, KnownMethodsSet>();

  private static final Set<String> myUnprocessedNestMates = new HashSet<String>();
  private static final Set<String> myProcessedClasses = new HashSet<String>();

  private static final Set<String> myFieldOwnersToTransform = new HashSet<String>();
  private static final Map<String, KnownMethodsSet> myCollectionsToTransform = new HashMap<String, KnownMethodsSet>();
  private static final Set<String> myCollectionNestMates = new HashSet<String>();

  private static final ReentrantLock myTransformLock = new ReentrantLock();

  @SuppressWarnings("StaticNonFinalField")
  public static boolean DEBUG; // set form debugger

  private static Instrumentation ourInstrumentation;

  static {
    KnownMethodsSet collectionKnownMethods = new KnownMethodsSet();
    collectionKnownMethods.add(new ImmutableMethod(COLLECTION_CLS_NAME, "size()I"));
    collectionKnownMethods.add(new ImmutableMethod(COLLECTION_CLS_NAME, "contains(Ljava/lang/Object;)Z"));
    collectionKnownMethods.add(new ImmutableMethod(COLLECTION_CLS_NAME, "iterator()Ljava/util/Iterator;"));
    collectionKnownMethods.add(new ImmutableMethod(COLLECTION_CLS_NAME, "toArray()[Ljava/lang/Object;"));
    collectionKnownMethods.add(new ImmutableMethod(COLLECTION_CLS_NAME, "toArray([Ljava/lang/Object;)[Ljava/lang/Object;"));
    collectionKnownMethods.add(new ImmutableMethod(COLLECTION_CLS_NAME, "containsAll(Ljava/util/Collection;)Z"));
    collectionKnownMethods.add(new ImmutableMethod(COLLECTION_CLS_NAME, "toArray(Ljava/util/function/IntFunction;)[Ljava/lang/Object;"));
    collectionKnownMethods.add(new ImmutableMethod(COLLECTION_CLS_NAME, "spliterator()Ljava/util/Spliterator;"));
    collectionKnownMethods.add(new ImmutableMethod(COLLECTION_CLS_NAME, "parallelStream()Ljava/util/stream/Stream;"));
    collectionKnownMethods.add(new ImmutableMethod(COLLECTION_CLS_NAME, "equals(Ljava/lang/Object;)Z"));
    collectionKnownMethods.add(new ImmutableMethod(COLLECTION_CLS_NAME, "hashCode()I"));
    collectionKnownMethods.add(new ReturnsBooleanMethod(COLLECTION_CLS_NAME, "add(Ljava/lang/Object;)Z", true));
    collectionKnownMethods.add(new ReturnsBooleanMethod(COLLECTION_CLS_NAME, "remove(Ljava/lang/Object;)Z", false));
    myKnownMethods.put(COLLECTION_CLS_NAME, collectionKnownMethods);

    KnownMethodsSet abstractCollectionKnownMethods = new KnownMethodsSet();
    abstractCollectionKnownMethods.add(new ImmutableMethod(ABSTRACT_COLLECTION_CLS_NAME, "toString()Ljava/lang/String;"));
    myKnownMethods.put(ABSTRACT_COLLECTION_CLS_NAME, abstractCollectionKnownMethods);

    KnownMethodsSet abstractListKnownMethods = new KnownMethodsSet();
    abstractListKnownMethods.add(new ImmutableMethod(ABSTRACT_LIST_CLS_NAME, "indexOf(Ljava/lang/Object;)I"));
    abstractListKnownMethods.add(new ImmutableMethod(ABSTRACT_LIST_CLS_NAME, "lastIndexOf(Ljava/lang/Object;)I"));
    abstractListKnownMethods.add(new ImmutableMethod(ABSTRACT_LIST_CLS_NAME, "listIterator()Ljava/util/ListIterator;"));
    abstractListKnownMethods.add(new ImmutableMethod(ABSTRACT_LIST_CLS_NAME, "listIterator(I)Ljava/util/ListIterator;"));
    abstractListKnownMethods.add(new ImmutableMethod(ABSTRACT_LIST_CLS_NAME, "subList(II)Ljava/util/List;"));
    myKnownMethods.put(ABSTRACT_LIST_CLS_NAME, abstractListKnownMethods);

    KnownMethodsSet arrayListKnownMethods = new KnownMethodsSet();
    arrayListKnownMethods.add(new ImmutableMethod(ARRAY_LIST_CLS_NAME, "indexOfRange(Ljava/lang/Object;II)I"));
    arrayListKnownMethods.add(new ImmutableMethod(ARRAY_LIST_CLS_NAME, "lastIndexOfRange(Ljava/lang/Object;II)I"));
    arrayListKnownMethods.add(new ImmutableMethod(ARRAY_LIST_CLS_NAME, "clone()Ljava/lang/Object;"));
    arrayListKnownMethods.add(new ImmutableMethod(ARRAY_LIST_CLS_NAME, "equalsRange(Ljava/util/List;II)Z"));
    arrayListKnownMethods.add(new ImmutableMethod(ARRAY_LIST_CLS_NAME, "equalsArrayList(Ljava/util/ArrayList;)Z"));
    arrayListKnownMethods.add(new ImmutableMethod(ARRAY_LIST_CLS_NAME, "hashCodeRange(II)I"));
    arrayListKnownMethods.add(new ImmutableMethod(ARRAY_LIST_CLS_NAME, "outOfBoundsMsg(I)Ljava/lang/String;"));
    arrayListKnownMethods.add(new AddAllMethod(ARRAY_LIST_CLS_NAME));
    arrayListKnownMethods.add(new RemoveAllMethod(ARRAY_LIST_CLS_NAME));
    myKnownMethods.put(ARRAY_LIST_CLS_NAME, arrayListKnownMethods);

    KnownMethodsSet mapKnownMethods = new KnownMethodsSet();
    mapKnownMethods.add(new ImmutableMethod(MAP_CLS_NAME, "size()I"));
    mapKnownMethods.add(new ImmutableMethod(MAP_CLS_NAME, "isEmpty()Z"));
    mapKnownMethods.add(new ImmutableMethod(MAP_CLS_NAME, "keySet()Ljava/util/Set;"));
    mapKnownMethods.add(new ImmutableMethod(MAP_CLS_NAME, "values()Ljava/util/Collection;"));
    mapKnownMethods.add(new ImmutableMethod(MAP_CLS_NAME, "entrySet()Ljava/util/Set;"));
    mapKnownMethods.add(new ImmutableMethod(MAP_CLS_NAME, "containsKey(Ljava/lang/Object;)Z"));
    mapKnownMethods.add(new ImmutableMethod(MAP_CLS_NAME, "containsValue(Ljava/lang/Object;)Z"));
    mapKnownMethods.add(new ImmutableMethod(MAP_CLS_NAME, "equals(Ljava/lang/Object;)Z"));
    mapKnownMethods.add(new ImmutableMethod(MAP_CLS_NAME, "hashCode()I"));
    mapKnownMethods.add(new PutMethod(MAP_CLS_NAME));
    mapKnownMethods.add(new RemoveKeyMethod(MAP_CLS_NAME));
    myKnownMethods.put(MAP_CLS_NAME, mapKnownMethods);
  }

  public static void init(Instrumentation instrumentation) {
    ourInstrumentation = instrumentation;
    ourInstrumentation.addTransformer(new CollectionBreakpointTransformer(), true);

    CollectionBreakpointStorage.init(); // just for class loading

    if (DEBUG) {
      System.out.println("Collection breakpoint instrumentor: ready");
    }
  }

  private static void handleException(Throwable e) {
    System.err.println("Critical error in IDEA CollectionBreakpoint instrumenting agent. Please report to IDEA support:");
    e.printStackTrace();
  }

  private static void processFailedToInstrumentError(String className, Exception error) {
    System.out.println("CollectionBreakpoint instrumentor: failed to instrument " + className);
    error.printStackTrace();
  }

  private static void writeDebugInfo(String className, byte[] bytes) {
    try {
      System.out.println("instrumented: " + className);
      FileOutputStream stream = new FileOutputStream("instrumented_" + className.replaceAll("/", "_") + ".class");
      try {
        stream.write(bytes);
      }
      finally {
        stream.close();
      }
    }
    catch (IOException e) {
      e.printStackTrace();
    }
  }

  //// METHODS CALLED FROM THE USER PROCESS

  @SuppressWarnings("unused") // called from the user process
  public static void captureFieldModification(Object collectionInstance,
                                              Object clsInstance,
                                              String clsName,
                                              String fieldName,
                                              boolean shouldSaveStack) {
    try {
      String fieldOwnerClsName = myTrackedFields.getFieldOwnerName(clsName, fieldName);
      if (fieldOwnerClsName == null) {
        return;
      }
      transformCollectionAndSaveFieldModification(collectionInstance, clsInstance, fieldOwnerClsName, fieldName, shouldSaveStack);
    }
    catch (Exception e) {
      handleException(e);
    }
  }

  @SuppressWarnings("unused") // called from the user process
  public static void captureCollectionModification(boolean shouldCapture,
                                                   boolean modified,
                                                   Object collectionInstance,
                                                   Object elem,
                                                   boolean isAddition) {
    try {
      if (!shouldCapture || !modified) {
        return;
      }
      CollectionBreakpointStorage.saveCollectionModification(collectionInstance, elem, isAddition);
    }
    catch (Exception e) {
      handleException(e);
    }
  }

  @SuppressWarnings("unused") // called from the user process
  public static void captureCollectionModification(Multiset oldElements, Object collectionInstance) {
    try {
      if (oldElements == null) {
        return;
      }
      Multiset newElements = Utils.multisetFromCollection(collectionInstance);
      ArrayList<Modification> modifications = getModifications(oldElements, newElements);
      if (!modifications.isEmpty()) {
        saveCollectionModifications(collectionInstance, modifications);
      }
    }
    catch (Exception e) {
      handleException(e);
    }
  }

  @SuppressWarnings("unused") // called from the user process
  public static boolean onCaptureStart(Object collectionInstance, boolean shouldSynchronized) {
    try {
      CollectionInstanceLock lock = myTrackedCollections.get(collectionInstance);
      if (lock != null) {
        return lock.lock(shouldSynchronized);
      }
    }
    catch (Exception e) {
      handleException(e);
    }
    return false;
  }

  @SuppressWarnings("unused") // called from the user process
  public static void onCaptureStart(Object collectionInstance, IdentityHashMap<Object, Multiset> copiesOfCollections) {
    try {
      CollectionInstanceLock lock = myTrackedCollections.get(collectionInstance);
      if (lock == null) {
        return;
      }
      boolean shouldCapture = lock.getMethodEnterNumber() == 0;
      if (shouldCapture) {
        lock.lock(true);
        Multiset copy = captureCollectionCopy(true, collectionInstance);
        copiesOfCollections.put(collectionInstance, copy);
      }
    }
    catch (Exception e) {
      handleException(e);
    }
  }

  @SuppressWarnings("unused") // called from the user process
  public static void onCaptureEnd(Object collectionInstance, boolean shouldSynchronized) {
    try {
      CollectionInstanceLock lock = myTrackedCollections.get(collectionInstance);
      if (lock != null) {
        lock.unlock(shouldSynchronized);
      }
    }
    catch (Exception e) {
      handleException(e);
    }
  }

  @SuppressWarnings("unused") // called from the user process
  public static void onCaptureEnd(IdentityHashMap<Object, Multiset> copiesOfCollections) {
    try {
      for (Map.Entry<Object, Multiset> entry : copiesOfCollections.entrySet()) {
        Object collectionInstance = entry.getKey();
        Multiset oldElements = entry.getValue();
        CollectionInstanceLock lock = myTrackedCollections.get(collectionInstance);
        if (lock == null || oldElements == null) {
          continue;
        }
        ArrayList<Modification> modifications = getModifications(oldElements, Utils.multisetFromCollection(collectionInstance));
        if (!modifications.isEmpty()) {
          saveCollectionModifications(collectionInstance, modifications);
        }
        lock.unlock(true);
      }
    }
    catch (Exception e) {
      handleException(e);
    }
  }

  @SuppressWarnings("unused") // called from the user process
  public static Multiset captureCollectionCopy(boolean shouldCapture, Object collectionInstance) {
    try {
      if (!shouldCapture) {
        return null;
      }
      return Utils.multisetFromCollection(collectionInstance);
    }
    catch (Exception e) {
      handleException(e);
    }
    return null;
  }

  @SuppressWarnings("unused") // called from the user process
  public static IdentityHashMap<Object, Multiset> getCopiesStorage() {
    try {
      return new IdentityHashMap<Object, Multiset>();
    }
    catch (Exception e) {
      handleException(e);
    }
    return null;
  }

  //// END - METHODS CALLED FROM THE USER PROCESS

  private static void addCollectionToTracked(Object collectionInstance) {
    myTrackedCollections.add(collectionInstance);
  }

  private static void saveCollectionModifications(Object collectionInstance, ArrayList<Modification> modifications) {
    Collections.sort(modifications);
    for (Modification modification : modifications) {
      CollectionBreakpointStorage.saveCollectionModification(collectionInstance,
                                                             modification.getElement(),
                                                             modification.isAddition());
    }
  }

  private static ArrayList<Modification> getModifications(Multiset oldElements, Multiset newElements) {
    ArrayList<Modification> modifications = new ArrayList<Modification>();

    for (Map.Entry<Object, Integer> entry : newElements.entrySet()) {
      Object element = entry.getKey();
      Integer newNumber = entry.getValue();
      Integer oldNumber = oldElements.get(element);
      if (element instanceof IdentityWrapper) {
        element = ((IdentityWrapper)element).getObject();
      }
      if (!newNumber.equals(oldNumber)) {
        boolean isAddition = oldNumber == null || newNumber > oldNumber;
        modifications.add(new Modification(element, isAddition));
      }
    }

    for (Map.Entry<Object, Integer> entry : oldElements.entrySet()) {
      Object element = entry.getKey();
      Integer newNumber = newElements.get(element);
      if (element instanceof IdentityWrapper) {
        element = ((IdentityWrapper)element).getObject();
      }
      if (newNumber == null) {
        modifications.add(new Modification(element, false));
      }
    }

    return modifications;
  }

  public static void transformCollectionAndSaveFieldModification(Object collectionInstance,
                                                                 Object clsInstance,
                                                                 String fieldOwnerClsName,
                                                                 String fieldName,
                                                                 boolean shouldSaveStack) {
    try {
      if (collectionInstance == null) {
        return;
      }
      addCollectionToTracked(collectionInstance);
      transformCollectionClassIfNeeded(collectionInstance.getClass());
      CollectionBreakpointStorage.saveFieldModification(fieldOwnerClsName, fieldName, clsInstance, collectionInstance, shouldSaveStack);
    }
    catch (Exception e) {
      handleException(e);
    }
  }

  private static void transformClassToCaptureFieldModifications(String qualifiedClsName) {
    try {
      myTransformLock.lock();
      myUnprocessedNestMates.clear();
      myProcessedClasses.clear();
      for (Class cls : ourInstrumentation.getAllLoadedClasses()) {
        if (qualifiedClsName.equals(cls.getName())) {
          myFieldOwnersToTransform.add(getInternalClsName(cls));
          transformClass(cls);
        }
      }
      transformFieldOwnerClsNestMates();
    }
    finally {
      myTransformLock.unlock();
    }
  }

  private static boolean resolve(Class<?> cls, String fieldOwnerClsName, String fieldName) {
    Class<?> currentCls = cls;
    do {
      try {
        currentCls.getDeclaredField(fieldName);
        break;
      }
      catch (NoSuchFieldException e) {
        currentCls = currentCls.getSuperclass();
      }
      catch (Exception e) {
        return false;
      }
    }
    while (!isObject(currentCls));
    return fieldOwnerClsName.equals(currentCls.getName());
  }

  private static void transformFieldOwnerClsNestMates() {
    processNestMates(myFieldOwnersToTransform);
  }

  private static void transformCollectionNestMates() {
    processNestMates(myCollectionNestMates);
  }

  private static void processNestMates(Set<String> container) {
    myUnprocessedNestMates.removeAll(myProcessedClasses);
    while (!myUnprocessedNestMates.isEmpty()) {
      container.addAll(myUnprocessedNestMates);
      Set<String> nestMates = new HashSet<String>(myUnprocessedNestMates);
      myUnprocessedNestMates.clear();
      transformNestMates(nestMates);
      myUnprocessedNestMates.removeAll(myProcessedClasses);
    }
  }

  private static void transformNestMates(Set<String> nestMates) {
    for (Class<?> loadedCls : ourInstrumentation.getAllLoadedClasses()) {
      if (nestMates.contains(getInternalClsName(loadedCls))) {
        transformClass(loadedCls);
      }
    }
  }

  private static void transformClass(Class<?> cls) {
    try {
      myProcessedClasses.add(getInternalClsName(cls));
      ourInstrumentation.retransformClasses(cls);
    }
    catch (UnmodifiableClassException e) {
      if (DEBUG) {
        System.out.println("CollectionBreakpoint instrumentor: failed to transform " + cls.getName());
        e.printStackTrace();
      }
    }
  }

  private static List<Class<?>> getClsAndItsParentsBFS(Class<?> collectionCls) {
    List<Class<?>> result = new ArrayList<Class<?>>();

    Set<String> processed = new HashSet<String>();

    Queue<Class<?>> parentsQueue = new LinkedList<Class<?>>();
    parentsQueue.add(collectionCls);

    while (!parentsQueue.isEmpty()) {
      Class<?> currentCls = parentsQueue.poll();
      result.add(currentCls);
      processed.add(currentCls.getName());

      if (isTopOfCollectionHierarchy(currentCls)) {
        continue;
      }

      Class<?> superCls = currentCls.getSuperclass();
      if (superCls != null && !isObject(superCls) && !processed.contains(superCls.getName())) {
        parentsQueue.add(superCls);
      }

      for (Class<?> inter : currentCls.getInterfaces()) {
        if (isStandardLibraryCls(inter) && !processed.contains(inter.getName())) {
          parentsQueue.add(inter);
        }
      }
    }

    return result;
  }

  private static boolean isTopOfCollectionHierarchy(Class<?> cls) {
    String clsName = getInternalClsName(cls);
    return COLLECTION_CLS_NAME.equals(clsName) || MAP_CLS_NAME.equals(clsName);
  }

  private static boolean isObject(Class<?> cls) {
    String clsName = getInternalClsName(cls);
    return OBJECT_CLS_NAME.equals(clsName);
  }

  private static boolean isStandardLibraryCls(Class<?> cls) {
    String clsName = getInternalClsName(cls);
    return clsName.startsWith("java/util/");
  }

  private static KnownMethodsSet getAllKnownMethods(Class<?> cls, List<Class<?>> clsHierarchyBFS) {
    boolean fairCheckIsNecessary = !isStandardLibraryCls(cls);

    if (fairCheckIsNecessary) {
      return new KnownMethodsSet();
    }

    int index = clsHierarchyBFS.indexOf(cls);
    if (index == -1) {
      return new KnownMethodsSet();
    }

    List<Class<?>> clsAndItsParentsBFS = clsHierarchyBFS.subList(index, clsHierarchyBFS.size());

    String clsName = getInternalClsName(cls);
    KnownMethodsSet result = new KnownMethodsSet();
    for (Class<?> parent : clsAndItsParentsBFS) {
      String parentClsName = getInternalClsName(parent);
      KnownMethodsSet knownMethods = myKnownMethods.get(parentClsName);
      if (knownMethods == null) {
        continue;
      }
      for (KnownMethod method : knownMethods.values()) {
        if (method.appliesTo(clsName)) {
          result.add(method);
        }
      }
    }

    return result;
  }

  private static void transformCollectionClassIfNeeded(Class<?> collectionCls) {
    try {
      myTransformLock.lock();
      myUnprocessedNestMates.clear();
      myProcessedClasses.clear();
      String internalClsName = getInternalClsName(collectionCls);
      if (myCollectionsToTransform.containsKey(internalClsName)) {
        return;
      }

      String clsName = collectionCls.getName();

      for (Class<?> loadedCls : ourInstrumentation.getAllLoadedClasses()) {
        if (clsName.equals(loadedCls.getName())) {
          List<Class<?>> parents = getClsAndItsParentsBFS(collectionCls);
          for (Class<?> cls : parents) {
            myCollectionsToTransform.put(getInternalClsName(cls), getAllKnownMethods(cls, parents));
          }
          for (Class<?> cls : parents) {
            transformClass(cls);
          }
        }
      }
      transformCollectionNestMates();
    }
    catch (Exception e) {
      handleException(e);
    }
    finally {
      myTransformLock.unlock();
    }
  }

  public static void addFieldToTracked(String fieldOwnerClsName,
                                       String fieldName,
                                       String fieldDescriptor,
                                       String... unprocessedClasses) {
    myTrackedFields.addField(fieldName, fieldDescriptor);
    Set<String> classesNamesSet = new HashSet<String>(Arrays.asList(unprocessedClasses));
    for (Class cls : ourInstrumentation.getAllLoadedClasses()) {
      String name = cls.getName();
      if (classesNamesSet.contains(name) && resolve(cls, fieldOwnerClsName, fieldName)) {
        myTrackedFields.addFieldOwnerForSymbolicReference(getInternalClsName(cls), fieldOwnerClsName, fieldName);
      }
    }
  }

  @SuppressWarnings("unused") // called from debugger
  public static void emulateFieldWatchpoint(String fieldOwnerClsName,
                                            String fieldName,
                                            String fieldDescriptor,
                                            String... unprocessedClasses) {
    addFieldToTracked(fieldOwnerClsName, fieldName, fieldDescriptor, unprocessedClasses);
    for (String clsName : unprocessedClasses) {
      transformClassToCaptureFieldModifications(clsName);
    }
  }

  public static String getInstrumentorClassName() {
    return getInternalClsName(CollectionBreakpointInstrumentor.class);
  }

  private static String getCollectionMethodsWrapperClassName() {
    return getInternalClsName(CollectionMethodsWrapper.class);
  }

  public static String getInternalClsName(Class<?> cls) {
    return Type.getInternalName(cls);
  }

  @SuppressWarnings("unused")
  public static Entry createEntry(Object key, Object value) {
    return new Entry<>(key, value);
  }

  private static class CollectionBreakpointTransformer implements ClassFileTransformer {
    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
      if (className == null) {
        return null;
      }

      boolean shouldBeTransformed = myCollectionsToTransform.containsKey(className) ||
                                    myCollectionNestMates.contains(className) ||
                                    myFieldOwnersToTransform.contains(className);

      if (shouldBeTransformed) {
        try {
          ClassReader reader = new ClassReader(classfileBuffer);
          ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);

          CollectionBreakpointClassVisitor instrumentor = new CollectionBreakpointClassVisitor(className, Opcodes.API_VERSION, writer);
          reader.accept(instrumentor, ClassReader.EXPAND_FRAMES);
          byte[] bytes = writer.toByteArray();

          if (DEBUG) {
            writeDebugInfo(className, bytes);
          }

          return bytes;
        }
        catch (Exception e) {
          processFailedToInstrumentError(className, e);
        }
      }
      return null;
    }
  }

  static class CollectionBreakpointClassVisitor extends ClassVisitor {
    private final String myClsName;

    private CollectionBreakpointClassVisitor(String clsName, int api, ClassVisitor cv) {
      super(api, cv);
      myClsName = clsName;
    }

    @Override
    public void visitNestHost(String nestHost) {
      saveClassNestMatesToUnprocessed(nestHost);
      saveCollectionNestMatesToUnprocessed(nestHost);
      super.visitNestHost(nestHost);
    }

    @Override
    public void visitNestMember(String nestMember) {
      saveClassNestMatesToUnprocessed(nestMember);
      saveCollectionNestMatesToUnprocessed(nestMember);
      super.visitNestMember(nestMember);
    }

    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
      saveCollectionNestMatesToUnprocessed(name);
      super.visitInnerClass(name, outerName, innerName, access);
    }

    private void saveCollectionNestMatesToUnprocessed(String nestMemberName) {
      if ((myCollectionsToTransform.containsKey(myClsName) ||
           myCollectionNestMates.contains(myClsName))) {
        myUnprocessedNestMates.add(nestMemberName); // save for processing after transform
      }
    }

    private void saveClassNestMatesToUnprocessed(String nestMemberName) {
      if (myFieldOwnersToTransform.contains(myClsName)) {
        myUnprocessedNestMates.add(nestMemberName); // save for processing after transform
      }
    }

    @Override
    public MethodVisitor visitMethod(final int access,
                                     final String name,
                                     final String desc,
                                     final String signature,
                                     final String[] exceptions) {
      MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);

      boolean isBridgeMethod = (access & Opcodes.ACC_BRIDGE) != 0;

      if (!isBridgeMethod && name != null && desc != null) {
        if (myFieldOwnersToTransform.contains(myClsName)) {
          mv = new CaptureFieldsMethodVisitor(api, mv);
        }

        boolean isStaticMethod = (access & Opcodes.ACC_STATIC) != 0;
        boolean isConstructor = name.equals(CONSTRUCTOR_METHOD_NAME);

        if (!isStaticMethod && !isConstructor && myCollectionsToTransform.containsKey(myClsName)) {
          mv = getCollectionMethodVisitor(access, name, desc, signature, exceptions, mv);
        }

        boolean isCollectionNestMate = myCollectionNestMates.contains(myClsName);
        boolean isCollectionStaticMethod = isStaticMethod && myCollectionsToTransform.containsKey(myClsName);
        if (isCollectionNestMate || isCollectionStaticMethod) {
          mv = new FieldOperationsTracker(api, access, name, desc, signature, exceptions, mv);
        }

        return mv;
      }

      return mv;
    }

    private MethodVisitor getCollectionMethodVisitor(final int access,
                                                     final String name,
                                                     final String desc,
                                                     final String signature,
                                                     final String[] exceptions,
                                                     MethodVisitor superMethodVisitor) {
      KnownMethod method = null;
      KnownMethodsSet knownMethods = myCollectionsToTransform.get(myClsName);
      if (knownMethods != null) {
        method = knownMethods.get(name + desc);
      }

      if (method instanceof ImmutableMethod) {
        return superMethodVisitor;
      }
      else if (method instanceof ReplaceableMethod) {
        return ((ReplaceableMethod)method).getMethodVisitor(api, superMethodVisitor);
      }

      return new DefaultCollectionMethodVisitor(api, access, name, desc, signature, exceptions, superMethodVisitor, method);
    }

    private boolean shouldOptimizeCapture(String methodFullDesc) {
      KnownMethodsSet knownMethods = myCollectionsToTransform.get(myClsName);
      if (knownMethods == null) {
        return false;
      }
      return knownMethods.get(methodFullDesc) != null;
    }

    private boolean shouldSynchronize(String methodFullDesc) {
      return !shouldOptimizeCapture(methodFullDesc);
    }

    private static boolean isReturnInstruction(int opcode) {
      return opcode == Opcodes.RETURN || opcode == Opcodes.ARETURN ||
             opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN ||
             opcode == Opcodes.DRETURN || opcode == Opcodes.FRETURN;
    }

    private abstract static class TryCatchProvider {
      public void wrapMethodInTryCatch(MethodNode methodNode, AbstractInsnNode wrapAfterThis) {
        LabelNode startTryCatch = new LabelNode();
        LabelNode endTryCatch = new LabelNode();
        LabelNode handlerTryCatch = new LabelNode();

        InsnList instructions = methodNode.instructions;
        if (wrapAfterThis != null) {
          instructions.insert(wrapAfterThis, startTryCatch);
        }
        else {
          insertBeforeFirstIns(instructions, startTryCatch);
        }

        InsnList additionalInstructions = new InsnList();
        additionalInstructions.add(endTryCatch);
        additionalInstructions.add(handlerTryCatch);
        addHandlerInstructions(additionalInstructions);
        additionalInstructions.add(new InsnNode(Opcodes.ATHROW));

        instructions.add(additionalInstructions);

        TryCatchBlockNode tryCatchBlockNode = new TryCatchBlockNode(startTryCatch, endTryCatch, handlerTryCatch, null);
        methodNode.tryCatchBlocks.add(tryCatchBlockNode);
      }

      protected abstract void addHandlerInstructions(InsnList insnList);

      private static void insertBeforeFirstIns(InsnList instructions, AbstractInsnNode toInsert) {
        AbstractInsnNode firstIns = instructions.getFirst();
        if (firstIns == null) {
          instructions.add(toInsert);
        }
        else {
          instructions.insertBefore(firstIns, toInsert);
        }
      }
    }

    private static class FieldOperationsTracker extends MethodNode {
      private final Set<String> myFieldOwnerTypes;

      protected FieldOperationsTracker(int api,
                                       int access,
                                       String name,
                                       String desc,
                                       String signature,
                                       String[] exceptions,
                                       MethodVisitor mv) {
        super(api, access, name, desc, signature, exceptions);
        this.mv = mv;
        myFieldOwnerTypes = myCollectionsToTransform.keySet();
      }

      private static void addEndCaptureCode(InsnList insnList, int collectionCopiesVar) {
        insnList.add(new VarInsnNode(Opcodes.ALOAD, collectionCopiesVar));
        insnList.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                        getInstrumentorClassName(),
                                        ON_CAPTURE_END_METHOD_NAME,
                                        ON_CAPTURE_END_NESTED_CLS_METHOD_DESC,
                                        false));
      }

      private static void putCollectionInstanceOnStack(int fieldInsCode, InsnList insnList) {
        if (fieldInsCode == Opcodes.PUTFIELD) {
          insnList.add(new InsnNode(Opcodes.DUP2));
          insnList.add(new InsnNode(Opcodes.POP));
        }
        else if (fieldInsCode == Opcodes.GETFIELD) {
          insnList.add(new InsnNode(Opcodes.DUP));
        }
      }

      private static void addStartCaptureCode(int fieldInsCode, InsnList insnList, int collectionCopiesVar) {
        putCollectionInstanceOnStack(fieldInsCode, insnList);
        insnList.add(new VarInsnNode(Opcodes.ALOAD, collectionCopiesVar));
        insnList.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                        getInstrumentorClassName(),
                                        ON_CAPTURE_START_METHOD_NAME,
                                        ON_CAPTURE_START_NESTED_CLS_METHOD_DESC,
                                        false));
      }

      private void addLocalVariables(AbstractInsnNode insertAfterThis, int collectionCopiesVar) {
        InsnList insList = new InsnList();
        insList.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                       getInstrumentorClassName(),
                                       GET_COPIES_STORAGE_METHOD_NAME,
                                       GET_COPIES_STORAGE_METHOD_DESC,
                                       false));
        insList.add(new VarInsnNode(Opcodes.ASTORE, collectionCopiesVar));

        if (insertAfterThis != null) {
          instructions.insert(insertAfterThis, insList);
        }
        else {
          insertBeforeFirstIns(instructions, insList);
        }
      }

      private boolean containsInstanceFieldIns() {
        for (AbstractInsnNode node : instructions) {
          int opcode = node.getOpcode();
          boolean isInstanceFieldOp = opcode == Opcodes.GETFIELD || opcode == Opcodes.PUTFIELD;
          if (node instanceof FieldInsnNode && isInstanceFieldOp) {
            String fieldOwnerType = ((FieldInsnNode)node).owner;
            if (myFieldOwnerTypes.contains(fieldOwnerType)) {
              return true;
            }
          }
        }
        return false;
      }

      private void processReturnIns(AbstractInsnNode insNode, int collectionCopiesVar) {
        InsnList insnList = new InsnList();
        addEndCaptureCode(insnList, collectionCopiesVar);
        instructions.insertBefore(insNode, insnList);
      }

      private void processFieldIns(int fieldInsCode, AbstractInsnNode insNode, int collectionCopiesVar) {
        InsnList insnList = new InsnList();
        addStartCaptureCode(fieldInsCode, insnList, collectionCopiesVar);
        instructions.insertBefore(insNode, insnList);
      }

      private void addCaptureModificationsCode(AbstractInsnNode insertAfterThis, int collectionCopiesVar) {
        boolean shouldInsert = insertAfterThis == null;
        for (AbstractInsnNode insNode : instructions) {
          if (!shouldInsert) {
            shouldInsert = insNode == insertAfterThis;
            continue;
          }

          int opcode = insNode.getOpcode();
          boolean isInstanceFieldOp = opcode == Opcodes.GETFIELD || opcode == Opcodes.PUTFIELD;
          if (insNode instanceof FieldInsnNode && isInstanceFieldOp) {
            String fieldOwnerType = ((FieldInsnNode)insNode).owner;
            if (myFieldOwnerTypes.contains(fieldOwnerType)) {
              processFieldIns(opcode, insNode, collectionCopiesVar);
            }
          }
          else if (isReturnInstruction(opcode)) {
            processReturnIns(insNode, collectionCopiesVar);
          }
        }
      }

      private AbstractInsnNode findSuperConstructorCallIns() {
        for (AbstractInsnNode insNode : instructions) {
          if (insNode instanceof MethodInsnNode &&
              CONSTRUCTOR_METHOD_NAME.equals(((MethodInsnNode)insNode).name)) {
            return insNode;
          }
        }
        return null;
      }

      private void addCaptureModificationsCode() {
        boolean isConstructor = CONSTRUCTOR_METHOD_NAME.equals(name);
        AbstractInsnNode insertAfterThisIns = isConstructor ? findSuperConstructorCallIns() : null;

        int collectionCopiesVar = maxLocals;

        MyTryCatchProvider tryCatchProvider = new MyTryCatchProvider(collectionCopiesVar);
        tryCatchProvider.wrapMethodInTryCatch(this, insertAfterThisIns);

        addLocalVariables(insertAfterThisIns, collectionCopiesVar);

        addCaptureModificationsCode(insertAfterThisIns, collectionCopiesVar);
      }

      @Override
      public void visitEnd() {
        super.visitEnd();
        if (containsInstanceFieldIns()) {
          addCaptureModificationsCode();
        }
        accept(mv);
      }

      private static void insertBeforeFirstIns(InsnList instructions, InsnList toInsert) {
        AbstractInsnNode firstIns = instructions.getFirst();
        if (firstIns == null) {
          instructions.add(toInsert);
        }
        else {
          instructions.insertBefore(firstIns, toInsert);
        }
      }

      private static class MyTryCatchProvider extends TryCatchProvider {
        private final int myCollectionCopiesVar;

        private MyTryCatchProvider(int collectionCopiesVar) {
          this.myCollectionCopiesVar = collectionCopiesVar;
        }

        @Override
        protected void addHandlerInstructions(InsnList insnList) {
          addEndCaptureCode(insnList, myCollectionCopiesVar);
        }
      }
    }

    private class TryCatchAdapter extends MethodNode {
      private final String myMethodFullDesc;
      private final TryCatchProvider myTryCatchProvider = new MyTryCatchProvider();

      private TryCatchAdapter(int api,
                              int access,
                              String name,
                              String desc,
                              String signature,
                              String[] exceptions,
                              MethodVisitor mv) {
        super(api, access, name, desc, signature, exceptions);
        this.mv = mv;
        myMethodFullDesc = name + desc;
      }

      @Override
      public void visitEnd() {
        super.visitEnd();
        myTryCatchProvider.wrapMethodInTryCatch(this, null);
        accept(mv);
      }

      private class MyTryCatchProvider extends TryCatchProvider {

        @Override
        protected void addHandlerInstructions(InsnList insnList) {
          insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
          insnList.add(new LdcInsnNode(shouldSynchronize(myMethodFullDesc)));
          insnList.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                          getInstrumentorClassName(),
                                          ON_CAPTURE_END_METHOD_NAME,
                                          ON_CAPTURE_END_DEFAULT_METHOD_DESC,
                                          false));
          maxStack += 1;
        }
      }
    }

    private class DefaultCollectionMethodVisitor extends LocalVariablesSorter {
      private final String myMethodFullDesc;
      private final KnownMethod myDelegate;
      private int myCollectionCopyVar;
      private int myShouldCaptureVar;

      protected DefaultCollectionMethodVisitor(int api,
                                               int access,
                                               String name,
                                               String desc,
                                               String signature,
                                               String[] exceptions,
                                               MethodVisitor mv,
                                               KnownMethod delegate) {
        super(api, access, desc, new TryCatchAdapter(api, access, name, desc, signature, exceptions, mv));
        myMethodFullDesc = name + desc;
        myDelegate = delegate;
      }

      @Override
      public void visitCode() {
        super.visitCode();
        addStartCaptureCode();

        if (!shouldOptimizeCapture(myMethodFullDesc)) {
          addCaptureCollectionCopyCode();
        }
      }

      @Override
      public void visitInsn(int opcode) {
        if (isReturnInstruction(opcode)) {
          addCaptureCollectionModificationCode();
          addEndCaptureCode();
        }
        super.visitInsn(opcode);
      }

      private void addEndCaptureCode() {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn(shouldSynchronize(myMethodFullDesc));
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                           getInstrumentorClassName(),
                           ON_CAPTURE_END_METHOD_NAME,
                           ON_CAPTURE_END_DEFAULT_METHOD_DESC,
                           false);
      }

      private void addStartCaptureCode() {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn(shouldSynchronize(myMethodFullDesc));
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                           getInstrumentorClassName(),
                           ON_CAPTURE_START_METHOD_NAME,
                           ON_CAPTURE_START_DEFAULT_METHOD_DESC,
                           false);
        myShouldCaptureVar = newLocal(Type.BOOLEAN_TYPE);
        mv.visitVarInsn(Opcodes.ISTORE, myShouldCaptureVar);
      }

      private void addCaptureCollectionCopyCode() {
        mv.visitVarInsn(Opcodes.ILOAD, myShouldCaptureVar);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                           getInstrumentorClassName(),
                           CAPTURE_COLLECTION_COPY_METHOD_NAME,
                           CAPTURE_COLLECTION_COPY_METHOD_DESC,
                           false);
        myCollectionCopyVar = newLocal(Type.getType(MULTISET_TYPE));
        mv.visitVarInsn(Opcodes.ASTORE, myCollectionCopyVar);
      }

      private void addCaptureCollectionModificationDefaultCode() {
        mv.visitVarInsn(Opcodes.ALOAD, myCollectionCopyVar);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                           getInstrumentorClassName(),
                           CAPTURE_COLLECTION_MODIFICATION_DEFAULT_METHOD_NAME,
                           CAPTURE_COLLECTION_MODIFICATION_DEFAULT_METHOD_DESC,
                           false);
      }

      private void addCaptureCollectionModificationCode() {
        if (myDelegate instanceof DocumentedMethod) {
          ((DocumentedMethod)myDelegate).addCaptureModificationCode(mv, myShouldCaptureVar);
        }
        else {
          addCaptureCollectionModificationDefaultCode();
        }
      }
    }

    private static class CaptureFieldsMethodVisitor extends MethodVisitor {
      private CaptureFieldsMethodVisitor(int api, MethodVisitor methodVisitor) {
        super(api, methodVisitor);
      }

      @Override
      public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        boolean isPutOperation = opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC;
        if (isPutOperation) {
          visitPutField(mv, opcode, owner, name, descriptor);
        }
        super.visitFieldInsn(opcode, owner, name, descriptor);
      }

      private static void visitPutField(MethodVisitor mv,
                                        int opcode,
                                        String fieldOwnerName,
                                        String fieldName,
                                        String fieldDescriptor) {
        if (myTrackedFields.containsField(fieldName, fieldDescriptor)) {
          boolean isStaticField = opcode == Opcodes.PUTSTATIC;
          addCaptureFieldModificationCode(mv, fieldOwnerName, fieldName, isStaticField);
        }
      }

      private static void addCaptureFieldModificationCode(MethodVisitor mv,
                                                          String fieldOwner,
                                                          String fieldName,
                                                          boolean isStaticField) {
        putCollectionAndClsInstanceOnStack(mv, isStaticField);
        mv.visitLdcInsn(fieldOwner);
        mv.visitLdcInsn(fieldName);
        mv.visitLdcInsn(true);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                           getInstrumentorClassName(),
                           CAPTURE_FIELD_MODIFICATION_METHOD_NAME,
                           CAPTURE_FIELD_MODIFICATION_METHOD_DESC,
                           false);
      }

      private static void putCollectionAndClsInstanceOnStack(MethodVisitor mv, boolean isStaticField) {
        if (isStaticField) {
          mv.visitInsn(Opcodes.DUP); // reference to collection instance
          mv.visitInsn(Opcodes.ACONST_NULL); // class instance reference is null
        }
        else { // last two objects on the stack
          mv.visitInsn(Opcodes.DUP2);
          mv.visitInsn(Opcodes.SWAP);
        }
      }
    }
  }

  private static class Modification implements Comparable<Modification> {
    private final Object myElement;
    private final boolean myIsAddition;

    private Modification(Object element, boolean isAddition) {
      myElement = element;
      myIsAddition = isAddition;
    }

    Object getElement() {
      return myElement;
    }

    boolean isAddition() {
      return myIsAddition;
    }

    @Override
    public int compareTo(Modification o) {
      if (myIsAddition == o.isAddition()) {
        return 0;
      }
      return myIsAddition ? 1 : -1;
    }
  }

  private static class TrackedCollections {
    private final Map<Object, CollectionInstanceLock> myContainer = new IdentityHashMap<Object, CollectionInstanceLock>();
    private final ReentrantLock myLock = new ReentrantLock();

    public void add(Object collectionInstance) {
      myLock.lock();
      try {
        if (!myContainer.containsKey(collectionInstance)) {
          myContainer.put(collectionInstance, new CollectionInstanceLock());
        }
      }
      finally {
        myLock.unlock();
      }
    }

    public CollectionInstanceLock get(Object collectionInstance) {
      myLock.lock();
      try {
        return myContainer.get(collectionInstance);
      }
      finally {
        myLock.unlock();
      }
    }
  }

  public static class CollectionInstanceLock {
    private final ReentrantLock myLock = new ReentrantLock();
    private final ThreadLocal<Integer> myMethodEnterNumber = new ThreadLocal<Integer>();

    public boolean lock(boolean shouldSynchronized) {
      if (shouldSynchronized) {
        myLock.lock();
      }
      Integer methodEnterNumber = getMethodEnterNumber();
      setMethodEnterNumber(methodEnterNumber + 1);
      return methodEnterNumber == 0;
    }

    public Integer getMethodEnterNumber() {
      Integer methodEnterNumber = myMethodEnterNumber.get();
      if (methodEnterNumber == null) {
        setMethodEnterNumber(0);
        return 0;
      }
      return methodEnterNumber;
    }

    public void setMethodEnterNumber(Integer number) {
      myMethodEnterNumber.set(number);
    }

    public void unlock(boolean shouldSynchronized) {
      try {
        setMethodEnterNumber(getMethodEnterNumber() - 1);
        if (shouldSynchronized) {
          myLock.unlock();
        }
      }
      catch (Throwable e) {
        handleException(e);
      }
    }
  }

  private static class TrackedFields {
    private static final String SEPARATOR = "->";
    private final HashMap<String, String> fieldOwners = new HashMap<String, String>();
    private final HashSet<String> fieldNames = new HashSet<String>();

    public void addField(String fieldName, String fieldDescriptor) {
      fieldNames.add(fieldName + SEPARATOR + fieldDescriptor);
    }

    public void addFieldOwnerForSymbolicReference(String clsSymbolicReference,
                                                  String fieldOwnerName,
                                                  String fieldName) {
      fieldOwners.put(clsSymbolicReference + SEPARATOR + fieldName, fieldOwnerName);
    }

    public boolean containsField(String fieldName, String fieldDescriptor) {
      return fieldNames.contains(fieldName + SEPARATOR + fieldDescriptor);
    }

    public String getFieldOwnerName(String clsSymbolicReference, String fieldName) {
      return fieldOwners.get(clsSymbolicReference + SEPARATOR + fieldName);
    }
  }

  private static class KnownMethodsSet {
    private final Map<String, KnownMethod> myContainer = new HashMap<String, KnownMethod>();

    public void add(KnownMethod method) {
      if (!myContainer.containsKey(method.myMethodFullDesc)) {
        myContainer.put(method.myMethodFullDesc, method);
      }
    }

    public Collection<KnownMethod> values() {
      return myContainer.values();
    }

    public KnownMethod get(String methodFullDesc) {
      return myContainer.get(methodFullDesc);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      KnownMethodsSet set = (KnownMethodsSet)o;
      return Objects.equals(myContainer, set.myContainer);
    }

    @Override
    public int hashCode() {
      return Objects.hash(myContainer);
    }
  }

  private abstract static class KnownMethod {
    private final String myClsName;
    private final String myMethodFullDesc;
    private final boolean myAppliesToOverridden;

    private KnownMethod(String clsName, String desc, boolean appliesToOverridden) {
      myClsName = clsName;
      myMethodFullDesc = desc;
      myAppliesToOverridden = appliesToOverridden;
    }

    private KnownMethod(String clsName, String desc) {
      this(clsName, desc, true);
    }

    public boolean appliesToOverridden() {
      return myAppliesToOverridden;
    }

    public boolean appliesTo(String clsOrInheritorType) {
      if (appliesToOverridden()) {
        return true;
      }
      return myClsName.equals(clsOrInheritorType);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      KnownMethod method = (KnownMethod)o;
      return myAppliesToOverridden == method.myAppliesToOverridden &&
             myClsName.equals(method.myClsName) &&
             myMethodFullDesc.equals(method.myMethodFullDesc);
    }

    @Override
    public int hashCode() {
      return Objects.hash(myClsName, myMethodFullDesc, myAppliesToOverridden);
    }
  }

  private static class ImmutableMethod extends KnownMethod {
    private ImmutableMethod(String clsName, String desc) {
      super(clsName, desc);
    }
  }

  private static abstract class DocumentedMethod extends KnownMethod {
    private DocumentedMethod(String clsName, String desc) {
      super(clsName, desc);
    }

    public abstract void addCaptureModificationCode(MethodVisitor mv, int shouldCaptureVar);
  }

  private static abstract class ReplaceableMethod extends KnownMethod {
    private ReplaceableMethod(String clsName, String desc) {
      super(clsName, desc, false);
    }

    public abstract MethodVisitor getMethodVisitor(int api, MethodVisitor superMethodVisitor);
  }

  private static class AddAllMethod extends ReplaceableMethod {
    private AddAllMethod(String clsName) {
      super(clsName, "addAll(Ljava/util/Collection;)Z");
    }

    @Override
    public MethodVisitor getMethodVisitor(int api, final MethodVisitor superMethodVisitor) {
      return new MethodVisitor(api) {
        @Override
        public void visitCode() {
          superMethodVisitor.visitCode();
          superMethodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
          superMethodVisitor.visitVarInsn(Opcodes.ALOAD, 1);
          superMethodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC,
                                             getCollectionMethodsWrapperClassName(),
                                             "addAll",
                                             "(Ljava/util/Collection;Ljava/util/Collection;)Z",
                                             false);
          superMethodVisitor.visitInsn(Opcodes.IRETURN);
          superMethodVisitor.visitMaxs(2, 0);
          superMethodVisitor.visitEnd();
        }
      };
    }
  }

  private static class RemoveAllMethod extends ReplaceableMethod {
    private RemoveAllMethod(String clsName) {
      super(clsName, "removeAll(Ljava/util/Collection;)Z");
    }

    @Override
    public MethodVisitor getMethodVisitor(int api, final MethodVisitor superMethodVisitor) {
      return new MethodVisitor(api) {
        @Override
        public void visitCode() {
          superMethodVisitor.visitCode();
          superMethodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
          superMethodVisitor.visitVarInsn(Opcodes.ALOAD, 1);
          superMethodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC,
                                             getCollectionMethodsWrapperClassName(),
                                             "removeAll",
                                             "(Ljava/util/Collection;Ljava/util/Collection;)Z",
                                             false);
          superMethodVisitor.visitInsn(Opcodes.IRETURN);
          superMethodVisitor.visitMaxs(2, 0);
          superMethodVisitor.visitEnd();
        }
      };
    }
  }

  private static class ReturnsBooleanMethod extends DocumentedMethod {
    private final boolean myIsAddition;

    private ReturnsBooleanMethod(String clsName, String desc, boolean isAddition) {
      super(clsName, desc);
      myIsAddition = isAddition;
    }

    @Override
    public void addCaptureModificationCode(MethodVisitor mv, int shouldCaptureVar) {
      mv.visitInsn(Opcodes.DUP);
      mv.visitVarInsn(Opcodes.ILOAD, shouldCaptureVar);
      mv.visitInsn(Opcodes.SWAP);
      mv.visitVarInsn(Opcodes.ALOAD, 0);
      mv.visitVarInsn(Opcodes.ALOAD, 1);
      mv.visitLdcInsn(myIsAddition);
      mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                         getInstrumentorClassName(),
                         CAPTURE_COLLECTION_MODIFICATION_METHOD_NAME,
                         CAPTURE_COLLECTION_MODIFICATION_METHOD_DESC,
                         false);
    }
  }

  private static class PutMethod extends DocumentedMethod {
    private PutMethod(String clsName) {
      super(clsName, "put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    }

    @Override
    public void addCaptureModificationCode(MethodVisitor mv, int shouldCaptureVar) {
      mv.visitInsn(Opcodes.DUP);
      mv.visitVarInsn(Opcodes.ALOAD, 2);
      Label label = new Label();
      Label end = new Label();
      mv.visitJumpInsn(Opcodes.IF_ACMPNE, label);
      mv.visitLdcInsn(false);
      mv.visitJumpInsn(Opcodes.GOTO, end);
      mv.visitLabel(label);
      mv.visitLdcInsn(true);
      mv.visitLabel(end);
      mv.visitVarInsn(Opcodes.ILOAD, shouldCaptureVar);
      mv.visitInsn(Opcodes.SWAP);
      mv.visitVarInsn(Opcodes.ALOAD, 1);
      mv.visitVarInsn(Opcodes.ALOAD, 2);
      mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                         getInstrumentorClassName(),
                         CREATE_ENTRY_METHOD_NAME,
                         CREATE_ENTRY_METHOD_DESC,
                         false);
      mv.visitVarInsn(Opcodes.ALOAD, 0);
      mv.visitInsn(Opcodes.SWAP);
      mv.visitLdcInsn(true);
      mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                         getInstrumentorClassName(),
                         CAPTURE_COLLECTION_MODIFICATION_METHOD_NAME,
                         CAPTURE_COLLECTION_MODIFICATION_METHOD_DESC,
                         false);
    }
  }

  private static class RemoveKeyMethod extends DocumentedMethod {
    private RemoveKeyMethod(String clsName) {
      super(clsName, "remove(Ljava/lang/Object;)Ljava/lang/Object;");
    }

    @Override
    public void addCaptureModificationCode(MethodVisitor mv, int shouldCaptureVar) {
      mv.visitInsn(Opcodes.DUP);
      mv.visitInsn(Opcodes.DUP);
      Label label = new Label();
      Label end = new Label();
      mv.visitJumpInsn(Opcodes.IFNONNULL, label);
      mv.visitLdcInsn(false);
      mv.visitJumpInsn(Opcodes.GOTO, end);
      mv.visitLabel(label);
      mv.visitLdcInsn(true);
      mv.visitLabel(end);
      mv.visitVarInsn(Opcodes.ILOAD, shouldCaptureVar);
      mv.visitInsn(Opcodes.DUP_X2);
      mv.visitInsn(Opcodes.POP);
      mv.visitInsn(Opcodes.DUP_X1);
      mv.visitInsn(Opcodes.POP);
      mv.visitVarInsn(Opcodes.ALOAD, 1);
      mv.visitInsn(Opcodes.SWAP);
      mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                         getInstrumentorClassName(),
                         CREATE_ENTRY_METHOD_NAME,
                         CREATE_ENTRY_METHOD_DESC,
                         false);
      mv.visitVarInsn(Opcodes.ALOAD, 0);
      mv.visitInsn(Opcodes.SWAP);
      mv.visitLdcInsn(false);
      mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                         getInstrumentorClassName(),
                         CAPTURE_COLLECTION_MODIFICATION_METHOD_NAME,
                         CAPTURE_COLLECTION_MODIFICATION_METHOD_DESC,
                         false);
    }
  }
}
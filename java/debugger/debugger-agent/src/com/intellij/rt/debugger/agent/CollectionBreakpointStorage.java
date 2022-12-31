// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.rt.debugger.agent;

import com.intellij.rt.debugger.agent.util.IdentityWrapper;
import com.intellij.rt.debugger.agent.util.Utils;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@SuppressWarnings({"UseOfSystemOutOrSystemErr"})
public class CollectionBreakpointStorage {
  @SuppressWarnings("SSBasedInspection")
  private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];
  private static final String INSTRUMENTOR_PACKAGE = "com.intellij.rt.debugger.agent";

  private static final ConcurrentMap<CapturedField, FieldHistory> FIELD_MODIFICATIONS_STORAGE;
  private static final ConcurrentMap<IdentityWrapper, CollectionHistory> COLLECTION_MODIFICATIONS_STORAGE;

  private static final Set<String> SAVE_HISTORY_FOR_FIELDS;
  private static final ConcurrentMap<IdentityWrapper, Set<String>> COLLECTION_TRACKERS;

  private static final ReadWriteLock mySettingsLock = new ReentrantReadWriteLock();

  static {
    FIELD_MODIFICATIONS_STORAGE = new ConcurrentHashMap<>();
    COLLECTION_MODIFICATIONS_STORAGE = new ConcurrentHashMap<>();
    SAVE_HISTORY_FOR_FIELDS = Utils.newConcurrentSet();
    COLLECTION_TRACKERS = new ConcurrentHashMap<>();
  }

  public static void init() {
    if (CollectionBreakpointInstrumentor.DEBUG) {
      System.out.println("Collection breakpoint storage: ready");
    }
  }

  // called from the user process or from debugger
  public static void saveFieldModification(String fieldOwnerClsName,
                                           String fieldName,
                                           Object clsInstance,
                                           Object collectionInstance,
                                           boolean shouldSaveStack) {
    mySettingsLock.readLock().lock();
    try {
      if (!shouldSaveHistory(fieldOwnerClsName + fieldName)) {
        return;
      }

      CapturedField field = new CapturedField(fieldOwnerClsName, fieldName, clsInstance);
      FIELD_MODIFICATIONS_STORAGE.putIfAbsent(field, new FieldHistory());
      FieldHistory history = FIELD_MODIFICATIONS_STORAGE.get(field);
      Throwable exception = shouldSaveStack ? new Throwable() : null;
      history.add(new FieldModificationInfo(exception, collectionInstance));

      if (collectionInstance == null) {
        return;
      }

      IdentityWrapper wrapper = new IdentityWrapper(collectionInstance);
      COLLECTION_TRACKERS.putIfAbsent(wrapper, Utils.<String>newConcurrentSet());
      Set<String> trackers = COLLECTION_TRACKERS.get(wrapper);
      trackers.add(fieldOwnerClsName + fieldName);
    } finally {
      mySettingsLock.readLock().unlock();
    }
  }

  // called from the user process
  public static void saveCollectionModification(Object collectionInstance,
                                                Object elem,
                                                boolean isAddition) {
    mySettingsLock.readLock().lock();
    try {
      IdentityWrapper wrapper = new IdentityWrapper(collectionInstance);
      if (!shouldSaveHistory(wrapper)) {
        return;
      }
      COLLECTION_MODIFICATIONS_STORAGE.putIfAbsent(wrapper, new CollectionHistory());
      CollectionHistory history = COLLECTION_MODIFICATIONS_STORAGE.get(wrapper);
      Throwable exception = new Throwable();
      history.add(new CollectionModificationInfo(exception, elem, isAddition));
    } finally {
      mySettingsLock.readLock().unlock();
    }
  }

  @SuppressWarnings("unused") // called from debugger
  public static void setSavingHistoryForFieldEnabled(String fieldOwnerClsName,
                                                     String fieldName,
                                                     boolean enabled) {
    mySettingsLock.writeLock().lock();
    try {
      String fieldIdentifier = fieldOwnerClsName + fieldName;
      if (enabled) {
        SAVE_HISTORY_FOR_FIELDS.add(fieldIdentifier);
      } else {
        SAVE_HISTORY_FOR_FIELDS.remove(fieldIdentifier);
      }
    } finally {
      mySettingsLock.writeLock().unlock();
    }
  }

  @SuppressWarnings("unused") // called from debugger
  public static void clearHistoryForField(String fieldOwnerClsName, String fieldName) {
    mySettingsLock.writeLock().lock();
    try {
      String fieldIdentifier = fieldOwnerClsName + fieldName;

      // remove from field storage
      Iterator<CapturedField> it = FIELD_MODIFICATIONS_STORAGE.keySet().iterator();
      while (it.hasNext()) {
        CapturedField field = it.next();
        if (fieldIdentifier.equals(field.myFieldOwnerClsName + field.myFieldName)) {
          it.remove();
        }
      }

      // remove from collection storage
      for (Map.Entry<IdentityWrapper, Set<String>> entry : COLLECTION_TRACKERS.entrySet()) {
        Set<String> trackers = entry.getValue();
        trackers.remove(fieldIdentifier);
        if (trackers.isEmpty()) {
          IdentityWrapper wrapper = entry.getKey();
          COLLECTION_MODIFICATIONS_STORAGE.remove(wrapper);
          COLLECTION_TRACKERS.remove(wrapper);
        }
      }
    } finally {
      mySettingsLock.writeLock().unlock();
    }
  }

  @SuppressWarnings("unused") // called from debugger
  public static Object[] getCollectionModifications(Object collectionInstance) {
    IdentityWrapper wrapper = new IdentityWrapper(collectionInstance);
    CollectionHistory history = COLLECTION_MODIFICATIONS_STORAGE.get(wrapper);
    return history == null ? EMPTY_OBJECT_ARRAY : history.get();
  }

  @SuppressWarnings("unused") // called from debugger
  public static Object[] getFieldModifications(String clsName, String fieldName, Object clsInstance) {
    CapturedField field = new CapturedField(clsName, fieldName, clsInstance);
    FieldHistory history = FIELD_MODIFICATIONS_STORAGE.get(field);
    return history == null ? EMPTY_OBJECT_ARRAY : history.getCollectionInstances();
  }

  @SuppressWarnings("unused") // called from debugger
  public static String getStack(Object collectionInstance, int modificationIndex) throws IOException {
    IdentityWrapper wrapper = new IdentityWrapper(collectionInstance);
    CollectionHistory history = COLLECTION_MODIFICATIONS_STORAGE.get(wrapper);
    return history == null ? "" : wrapInString(history.get(modificationIndex));
  }

  @SuppressWarnings("unused") // called from debugger
  public static String getStack(String clsName, String fieldName, Object clsInstance, int modificationIndex) throws IOException {
    CapturedField field = new CapturedField(clsName, fieldName, clsInstance);
    FieldHistory history = FIELD_MODIFICATIONS_STORAGE.get(field);
    return history == null ? "" : wrapInString(history.get(modificationIndex));
  }

  private static boolean shouldSaveHistory(String fieldIdentifier) {
    return SAVE_HISTORY_FOR_FIELDS.contains(fieldIdentifier);
  }

  private static boolean shouldSaveHistory(IdentityWrapper identityWrapper) {
    Set<String> trackers = COLLECTION_TRACKERS.get(identityWrapper);
    if (trackers == null) {
      return false;
    }
    for (String trackerId : trackers) {
      if (SAVE_HISTORY_FOR_FIELDS.contains(trackerId)) {
        return true;
      }
    }
    return false;
  }

  private static String wrapInString(CapturedStackInfo info) throws IOException {
    if (info == null) {
      return "";
    }
    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    DataOutputStream dos = null;
    try {
      dos = new DataOutputStream(bas);
      for (StackTraceElement stackTraceElement : info.getStackTrace()) {
        if (stackTraceElement != null) {
          dos.writeUTF(stackTraceElement.getClassName());
          dos.writeUTF(stackTraceElement.getMethodName());
          dos.writeInt(stackTraceElement.getLineNumber());
        }
      }
      return bas.toString("ISO-8859-1");
    }
    finally {
      if (dos != null) {
        dos.close();
      }
    }
  }

  private static class FieldHistory {
    private final ArrayList<FieldModificationInfo> myModifications = new ArrayList<FieldModificationInfo>();
    private final ReentrantLock myLock = new ReentrantLock();

    private void add(FieldModificationInfo info) {
      myLock.lock();
      try {
        myModifications.add(info);
      }
      finally {
        myLock.unlock();
      }
    }

    private FieldModificationInfo get(int modificationIndex) {
      myLock.lock();
      try {
        return myModifications.get(modificationIndex);
      }
      finally {
        myLock.unlock();
      }
    }

    private Object[] getCollectionInstances() {
      myLock.lock();
      try {
        ArrayList<Object> collectionInstances = new ArrayList<Object>();
        for (FieldModificationInfo info : myModifications) {
          collectionInstances.add(info.myCollectionInstance);
        }
        return collectionInstances.toArray();
      }
      finally {
        myLock.unlock();
      }
    }
  }

  private static class CollectionHistory {
    private final ArrayList<CollectionModificationInfo> myOperations = new ArrayList<CollectionModificationInfo>();
    private final ReentrantLock myLock = new ReentrantLock();

    private void add(CollectionModificationInfo info) {
      myLock.lock();
      try {
        myOperations.add(info);
      }
      finally {
        myLock.unlock();
      }
    }

    private Object[] get() {
      myLock.lock();
      try {
        return myOperations.toArray();
      }
      finally {
        myLock.unlock();
      }
    }

    private CollectionModificationInfo get(int operationIndex) {
      myLock.lock();
      try {
        return myOperations.get(operationIndex);
      }
      finally {
        myLock.unlock();
      }
    }
  }

  private static class CapturedStackInfo {
    private final Throwable myException;

    private CapturedStackInfo(Throwable exception) {
      myException = exception;
    }

    public List<StackTraceElement> getStackTrace() {
      StackTraceElement[] stackTrace = myException.getStackTrace();
      int index;
      for (index = 0; index < stackTrace.length; index++) {
        String clsName = stackTrace[index].getClassName();
        if (!clsName.startsWith(INSTRUMENTOR_PACKAGE)) {
          break;
        }
      }
      return Arrays.asList(stackTrace).subList(index, stackTrace.length);
    }
  }

  public static class CollectionModificationInfo extends CapturedStackInfo {
    private final Object myElement;
    private final boolean myIsAddition;

    public CollectionModificationInfo(Throwable exception, Object elem, boolean isAddition) {
      super(exception);
      myElement = elem;
      myIsAddition = isAddition;
    }

    @SuppressWarnings("unused")
    public Object getElement() {
      return myElement;
    }

    @SuppressWarnings("unused")
    public boolean isAddition() {
      return myIsAddition;
    }
  }

  private static class FieldModificationInfo extends CapturedStackInfo {
    private final Object myCollectionInstance;

    private FieldModificationInfo(Throwable exception, Object collectionInstance) {
      super(exception);
      myCollectionInstance = collectionInstance;
    }
  }

  private static class CapturedField {
    private final String myFieldOwnerClsName;
    private final String myFieldName;
    private final Object myClsInstance;
    private final int myHashCode;

    private CapturedField(String fieldOwnerClsName, String fieldName, Object clsInstance) {
      myFieldOwnerClsName = fieldOwnerClsName;
      myFieldName = fieldName;
      myClsInstance = clsInstance;
      myHashCode = 31 * System.identityHashCode(clsInstance) + Objects.hash(myFieldOwnerClsName, myFieldName);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      CapturedField field = (CapturedField)o;
      return myClsInstance == field.myClsInstance &&
             myFieldOwnerClsName.equals(field.myFieldOwnerClsName) &&
             myFieldName.equals(field.myFieldName);
    }

    @Override
    public int hashCode() {
      return myHashCode;
    }
  }
}

// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.rt.debugger.agent.util;

import com.intellij.rt.debugger.agent.CollectionBreakpointInstrumentor;

import java.util.Collection;

public class CollectionMethodsWrapper {
  private static final boolean SHOULD_SYNCHRONIZED = false;

  public static <T> boolean addAll(Collection<T> collection, Collection<? extends T> elements) {
    boolean addedSomething = false;
    boolean shouldCapture = CollectionBreakpointInstrumentor.onCaptureStart(collection, SHOULD_SYNCHRONIZED);
    try {
      for (T element : elements) {
        boolean added = collection.add(element);
        addedSomething = addedSomething || added;
        CollectionBreakpointInstrumentor.captureCollectionModification(shouldCapture,
                                                                       added,
                                                                       collection,
                                                                       element,
                                                                       true);
      }
    } finally {
      CollectionBreakpointInstrumentor.onCaptureEnd(collection, SHOULD_SYNCHRONIZED);
    }
    return addedSomething;
  }

  public static boolean removeAll(Collection<?> collection, Collection<?> elements) {
    boolean removedSomething = false;
    boolean shouldCapture = CollectionBreakpointInstrumentor.onCaptureStart(collection, SHOULD_SYNCHRONIZED);
    try {
      for (Object element : elements) {
        boolean removed = collection.remove(element);
        removedSomething = removedSomething || removed;
        CollectionBreakpointInstrumentor.captureCollectionModification(shouldCapture,
                                                                       removed,
                                                                       collection,
                                                                       element,
                                                                       false);
      }
    } finally {
      CollectionBreakpointInstrumentor.onCaptureEnd(collection, SHOULD_SYNCHRONIZED);
    }
    return removedSomething;
  }
}

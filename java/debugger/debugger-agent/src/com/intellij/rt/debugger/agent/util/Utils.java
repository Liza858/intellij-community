// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.rt.debugger.agent.util;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Utils {
  public static <T> Set<T> newConcurrentSet() {
    return Collections.newSetFromMap(new ConcurrentHashMap<T, Boolean>());
  }

  public static Multiset multisetFromCollection(Object collectionInstance) {
    Multiset multiset = new Multiset();
    if (collectionInstance instanceof Collection) {
      for (Object element : (Collection<?>)collectionInstance) {
        multiset.add(new IdentityWrapper(element));
      }
    }
    else if (collectionInstance instanceof Map) {
      for (Map.Entry<?, ?> element : ((Map<?, ?>)collectionInstance).entrySet()) {
        multiset.add(new Entry<>(element.getKey(), element.getValue()));
      }
    }
    return multiset;
  }
}

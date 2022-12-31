// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.rt.debugger.agent.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class Multiset {
  private final HashMap<Object, Integer> myContainer = new HashMap<Object, Integer>();

  public void add(Object element) {
    Integer number = myContainer.get(element);
    if (number == null) {
      myContainer.put(element, 1);
    }
    else {
      myContainer.put(element, number + 1);
    }
  }

  public Integer get(Object element) {
    return myContainer.get(element);
  }

  public Set<Map.Entry<Object, Integer>> entrySet() {
    return myContainer.entrySet();
  }
}

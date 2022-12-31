// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.rt.debugger.agent.util;

public class IdentityWrapper {
  private final Object myObject;
  private final int myHash;

  public IdentityWrapper(Object object) {
    myObject = object;
    myHash = System.identityHashCode(object);
  }

  public Object getObject() {
    return myObject;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    IdentityWrapper wrapper = (IdentityWrapper)o;
    return myObject == wrapper.myObject;
  }

  @Override
  public int hashCode() {
    return myHash;
  }
}

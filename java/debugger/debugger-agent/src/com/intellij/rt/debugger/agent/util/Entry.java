// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.rt.debugger.agent.util;

import java.util.Map;

public class Entry<U, V> implements Map.Entry<U, V> {
  private final U key;
  private final V value;
  private final int myHash;

  public Entry(U key, V value) {
    this.key = key;
    this.value = value;
    myHash = 31 * System.identityHashCode(key) + System.identityHashCode(key);
  }

  @Override
  public U getKey() {
    return key;
  }

  @Override
  public V getValue() {
    return value;
  }

  @Override
  public V setValue(V value) {
    return null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Entry<?, ?> entry = (Entry<?, ?>)o;
    return key == entry.key && value == entry.value;
  }

  @Override
  public int hashCode() {
    return myHash;
  }
}



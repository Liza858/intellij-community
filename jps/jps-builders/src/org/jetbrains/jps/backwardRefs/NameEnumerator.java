/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.jps.backwardRefs;

import com.intellij.util.ObjectUtils;
import com.intellij.util.io.PersistentStringEnumerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.builders.storage.BuildDataCorruptedException;

import java.io.File;
import java.io.IOException;

public class NameEnumerator extends PersistentStringEnumerator {
  public NameEnumerator(@NotNull File file) throws IOException {
    super(file);
  }

  @Override
  public int enumerate(String value) {
    try {
      return super.enumerate(value);
    }
    catch (IOException e) {
      throw new BuildDataCorruptedException(e);
    }
  }

  @NotNull
  public String getName(int idx) {
    try {
      return ObjectUtils.notNull(valueOf(idx));
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}


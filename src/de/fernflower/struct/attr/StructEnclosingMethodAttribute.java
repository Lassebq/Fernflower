/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package de.fernflower.struct.attr;

import java.io.DataInputStream;
import java.io.IOException;

import de.fernflower.struct.consts.ConstantPool;
import de.fernflower.struct.consts.LinkConstant;

public class StructEnclosingMethodAttribute extends StructGeneralAttribute {

  private String className;
  private String methodName;
  private String methodDescriptor;

  @Override
  public void initContent(ConstantPool pool) throws IOException {
    DataInputStream data = stream();
    int classIndex = data.readUnsignedShort();
    int methodIndex = data.readUnsignedShort();

    className = pool.getPrimitiveConstant(classIndex).getString();
    if (methodIndex != 0) {
      LinkConstant lk = pool.getLinkConstant(methodIndex);
      methodName = lk.elementname;
      methodDescriptor = lk.descriptor;
    }
  }

  public String getClassName() {
    return className;
  }

  public String getMethodDescriptor() {
    return methodDescriptor;
  }

  public String getMethodName() {
    return methodName;
  }
}

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
package de.fernflower.code;

import java.util.ArrayList;
import java.util.List;

import de.fernflower.code.interpreter.Util;
import de.fernflower.struct.StructContext;

public class ExceptionTable {

  private List<ExceptionHandler> handlers = new ArrayList<ExceptionHandler>();

  public ExceptionTable() {
  }

  public ExceptionTable(List<ExceptionHandler> handlers) {
    this.handlers = handlers;
  }


  public ExceptionHandler getHandlerByClass(StructContext context, int line, String valclass, boolean withany) {

    ExceptionHandler res = null; // no handler found

    for (ExceptionHandler handler : handlers) {
      if (handler.from <= line && handler.to > line) {
        String name = handler.exceptionClass;

        if ((withany && name == null) ||   // any -> finally or synchronized handler
            (name != null && Util.instanceOf(context, valclass, name))) {
          res = handler;
          break;
        }
      }
    }

    return res;
  }

  public List<ExceptionHandler> getHandlers() {
    return handlers;
  }
}

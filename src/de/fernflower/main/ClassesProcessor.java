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
package de.fernflower.main;

import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;

import de.fernflower.code.CodeConstants;
import de.fernflower.main.collectors.BytecodeSourceMapper;
import de.fernflower.main.collectors.CounterContainer;
import de.fernflower.main.collectors.ImportCollector;
import de.fernflower.main.extern.IFernflowerLogger;
import de.fernflower.main.extern.IFernflowerPreferences;
import de.fernflower.main.extern.IIdentifierRenamer;
import de.fernflower.main.rels.ClassWrapper;
import de.fernflower.main.rels.LambdaProcessor;
import de.fernflower.main.rels.NestedClassProcessor;
import de.fernflower.main.rels.NestedMemberAccess;
import de.fernflower.modules.decompiler.exps.InvocationExprent;
import de.fernflower.modules.decompiler.vars.VarVersionPair;
import de.fernflower.struct.StructClass;
import de.fernflower.struct.StructContext;
import de.fernflower.struct.StructMethod;
import de.fernflower.struct.attr.StructInnerClassesAttribute;
import de.fernflower.struct.gen.VarType;
import de.fernflower.util.InterpreterUtil;

public class ClassesProcessor {

  public static final int AVERAGE_CLASS_SIZE = 16 * 1024;

  private Map<String, ClassNode> mapRootClasses = new HashMap<String, ClassNode>();

  public ClassesProcessor(StructContext context) {

    HashMap<String, Object[]> mapInnerClasses = new HashMap<String, Object[]>();
    HashMap<String, HashSet<String>> mapNestedClassReferences = new HashMap<String, HashSet<String>>();
    HashMap<String, HashSet<String>> mapEnclosingClassReferences = new HashMap<String, HashSet<String>>();
    HashMap<String, String> mapNewSimpleNames = new HashMap<String, String>();

    boolean bDecompileInner = DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_INNER);

    // create class nodes
    for (StructClass cl : context.getClasses().values()) {
      if (cl.isOwn() && !mapRootClasses.containsKey(cl.qualifiedName)) {

        if (bDecompileInner) {
          StructInnerClassesAttribute inner = (StructInnerClassesAttribute)cl.getAttributes().getWithKey("InnerClasses");
          if (inner != null) {

            for (int i = 0; i < inner.getClassEntries().size(); i++) {

              int[] entry = inner.getClassEntries().get(i);
              String[] strentry = inner.getStringEntries().get(i);

              Object[] arr = new Object[4]; // arr[0] not used

              String innername = strentry[0];

              // nested class type
              arr[2] = entry[1] == 0 ? (entry[2] == 0 ? ClassNode.CLASS_ANONYMOUS : ClassNode.CLASS_LOCAL) : ClassNode.CLASS_MEMBER;

              // original simple name
              String simpleName = strentry[2];
              String savedName = mapNewSimpleNames.get(innername);

              if (savedName != null) {
                simpleName = savedName;
              }
              else if (simpleName != null && DecompilerContext.getOption(IFernflowerPreferences.RENAME_ENTITIES)) {
                IIdentifierRenamer renamer = DecompilerContext.getPoolInterceptor().getHelper();
                if (renamer.toBeRenamed(IIdentifierRenamer.Type.ELEMENT_CLASS, simpleName, null, null)) {
                  simpleName = renamer.getNextClassName(innername, simpleName);
                  mapNewSimpleNames.put(innername, simpleName);
                }
              }

              arr[1] = simpleName;

              // original access flags
              arr[3] = entry[3];

              // enclosing class
              String enclClassName;
              if (entry[1] != 0) {
                enclClassName = strentry[1];
              }
              else {
                enclClassName = cl.qualifiedName;
              }

              if (!innername.equals(enclClassName)) {  // self reference
                StructClass enclosing_class = context.getClasses().get(enclClassName);
                if (enclosing_class != null && enclosing_class.isOwn()) { // own classes only

                  Object[] arrold = mapInnerClasses.get(innername);
                  if (arrold == null) {
                    mapInnerClasses.put(innername, arr);
                  }
                  else if (!InterpreterUtil.equalObjectArrays(arrold, arr)) {
                    String message = "Inconsistent inner class entries for " + innername + "!";
                    DecompilerContext.getLogger().writeMessage(message, IFernflowerLogger.Severity.WARN);
                  }

                  // reference to the nested class
                  HashSet<String> set = mapNestedClassReferences.get(enclClassName);
                  if (set == null) {
                    mapNestedClassReferences.put(enclClassName, set = new HashSet<String>());
                  }
                  set.add(innername);

                  // reference to the enclosing class
                  set = mapEnclosingClassReferences.get(innername);
                  if (set == null) {
                    mapEnclosingClassReferences.put(innername, set = new HashSet<String>());
                  }
                  set.add(enclClassName);
                }
              }
            }
          }
        }

        ClassNode node = new ClassNode(ClassNode.CLASS_ROOT, cl);
        node.access = cl.getAccessFlags();
        mapRootClasses.put(cl.qualifiedName, node);
      }
    }

    if (bDecompileInner) {

      // connect nested classes
      for (Entry<String, ClassNode> ent : mapRootClasses.entrySet()) {
        // root class?
        if (!mapInnerClasses.containsKey(ent.getKey())) {

          HashSet<String> setVisited = new HashSet<String>();
          LinkedList<String> stack = new LinkedList<String>();

          stack.add(ent.getKey());
          setVisited.add(ent.getKey());

          while (!stack.isEmpty()) {

            String superClass = stack.removeFirst();
            ClassNode supernode = mapRootClasses.get(superClass);

            HashSet<String> setNestedClasses = mapNestedClassReferences.get(superClass);
            if (setNestedClasses != null) {

              StructClass scl = supernode.classStruct;
              StructInnerClassesAttribute inner = (StructInnerClassesAttribute)scl.getAttributes().getWithKey("InnerClasses");
              for (int i = 0; i < inner.getStringEntries().size(); i++) {
                String nestedClass = inner.getStringEntries().get(i)[0];
                if (!setNestedClasses.contains(nestedClass)) {
                  continue;
                }

                if (!setVisited.add(nestedClass)) {
                  continue;
                }

                ClassNode nestednode = mapRootClasses.get(nestedClass);
                if (nestednode == null) {
                  DecompilerContext.getLogger().writeMessage("Nested class " + nestedClass + " missing!", IFernflowerLogger.Severity.WARN);
                  continue;
                }

                Object[] arr = mapInnerClasses.get(nestedClass);

                //if ((Integer)arr[2] == ClassNode.CLASS_MEMBER) {
                  // FIXME: check for consistent naming
                //}

                nestednode.type = (Integer)arr[2];
                nestednode.simpleName = (String)arr[1];
                nestednode.access = (Integer)arr[3];

                if (nestednode.type == ClassNode.CLASS_ANONYMOUS) {
                  StructClass cl = nestednode.classStruct;

                  // remove static if anonymous class
                  // a common compiler bug
                  nestednode.access &= ~CodeConstants.ACC_STATIC;

                  int[] interfaces = cl.getInterfaces();

                  if (interfaces.length > 0) {
                    if (interfaces.length > 1) {
                      String message = "Inconsistent anonymous class definition: " + cl.qualifiedName;
                      DecompilerContext.getLogger().writeMessage(message, IFernflowerLogger.Severity.WARN);
                    }
                    nestednode.anonymousClassType = new VarType(cl.getInterface(0), true);
                  }
                  else {
                    nestednode.anonymousClassType = new VarType(cl.superClass.getString(), true);
                  }
                }
                else if (nestednode.type == ClassNode.CLASS_LOCAL) {
                  // only abstract and final are permitted
                  // a common compiler bug
                  nestednode.access &= (CodeConstants.ACC_ABSTRACT | CodeConstants.ACC_FINAL);
                }

                supernode.nested.add(nestednode);
                nestednode.parent = supernode;

                nestednode.enclosingClasses.addAll(mapEnclosingClassReferences.get(nestedClass));

                stack.add(nestedClass);
              }
            }
          }
        }
      }
    }
  }

  public void writeClass(StructClass cl, TextBuffer buffer) throws IOException {
    ClassNode root = mapRootClasses.get(cl.qualifiedName);
    if (root.type != ClassNode.CLASS_ROOT) {
      return;
    }

    DecompilerContext.getLogger().startReadingClass(cl.qualifiedName);
    try {
      ImportCollector importCollector = new ImportCollector(root);
      DecompilerContext.setImportCollector(importCollector);
      DecompilerContext.setCounterContainer(new CounterContainer());
      DecompilerContext.setBytecodeSourceMapper(new BytecodeSourceMapper());

      new LambdaProcessor().processClass(root);

      // add simple class names to implicit import
      addClassnameToImport(root, importCollector);

      // build wrappers for all nested classes (that's where actual processing takes place)
      initWrappers(root);

      new NestedClassProcessor().processClass(root, root);

      new NestedMemberAccess().propagateMemberAccess(root);

      TextBuffer classBuffer = new TextBuffer(AVERAGE_CLASS_SIZE);
      new ClassWriter().classToJava(root, classBuffer, 0, null);

      int total_offset_lines = 0;

      int index = cl.qualifiedName.lastIndexOf("/");
      if (index >= 0) {
        total_offset_lines+=2;
        String packageName = cl.qualifiedName.substring(0, index).replace('/', '.');

        buffer.append("package ");
        buffer.append(packageName);
        buffer.append(";");
        buffer.appendLineSeparator();
        buffer.appendLineSeparator();
      }

      int import_lines_written = importCollector.writeImports(buffer);
      if (import_lines_written > 0) {
        buffer.appendLineSeparator();
        total_offset_lines += import_lines_written + 1;
      }
      //buffer.append(lineSeparator);

      total_offset_lines = buffer.countLines();
      buffer.append(classBuffer);

      if (DecompilerContext.getOption(IFernflowerPreferences.BYTECODE_SOURCE_MAPPING)) {
        BytecodeSourceMapper mapper = DecompilerContext.getBytecodeSourceMapper();
        mapper.addTotalOffset(total_offset_lines);
        if (DecompilerContext.getOption(IFernflowerPreferences.DUMP_ORIGINAL_LINES)) {
          buffer.dumpOriginalLineNumbers(mapper.getOriginalLinesMapping());
        }
        if (DecompilerContext.getOption(IFernflowerPreferences.UNIT_TEST_MODE)) {
          buffer.appendLineSeparator();
          mapper.dumpMapping(buffer, true);
        }
      }
    }
    finally {
      destroyWrappers(root);
      DecompilerContext.getLogger().endReadingClass();
    }
  }

  private static void initWrappers(ClassNode node) throws IOException {

    if (node.type == ClassNode.CLASS_LAMBDA) {
      return;
    }

    ClassWrapper wrapper = new ClassWrapper(node.classStruct);
    wrapper.init();

    node.wrapper = wrapper;

    for (ClassNode nd : node.nested) {
      initWrappers(nd);
    }
  }

  private static void addClassnameToImport(ClassNode node, ImportCollector imp) {

    if (node.simpleName != null && node.simpleName.length() > 0) {
      imp.getShortName(node.type == ClassNode.CLASS_ROOT ? node.classStruct.qualifiedName : node.simpleName, false);
    }

    for (ClassNode nd : node.nested) {
      addClassnameToImport(nd, imp);
    }
  }

  private static void destroyWrappers(ClassNode node) {

    node.wrapper = null;
    node.classStruct.releaseResources();

    for (ClassNode nd : node.nested) {
      destroyWrappers(nd);
    }
  }

  public Map<String, ClassNode> getMapRootClasses() {
    return mapRootClasses;
  }


  public static class ClassNode {

    public static final int CLASS_ROOT = 0;
    public static final int CLASS_MEMBER = 1;
    public static final int CLASS_ANONYMOUS = 2;
    public static final int CLASS_LOCAL = 4;
    public static final int CLASS_LAMBDA = 8;

    public int type;
    public int access;
    public String simpleName;
    public StructClass classStruct;
    private ClassWrapper wrapper;
    public String enclosingMethod;
    public InvocationExprent superInvocation;
    public Map<String, VarVersionPair> mapFieldsToVars = new HashMap<String, VarVersionPair>();
    public VarType anonymousClassType;
    public List<ClassNode> nested = new ArrayList<ClassNode>();
    public Set<String> enclosingClasses = new HashSet<String>();
    public ClassNode parent;
    public LambdaInformation lambdaInformation;
    public boolean namelessConstructorStub = false;

    public ClassNode(String content_class_name,
                     String content_method_name,
                     String content_method_descriptor,
                     int content_method_invocation_type,
                     String lambda_class_name,
                     String lambda_method_name,
                     String lambda_method_descriptor,
                     StructClass classStruct) { // lambda class constructor
      this.type = CLASS_LAMBDA;
      this.classStruct = classStruct; // 'parent' class containing the static function

      lambdaInformation = new LambdaInformation();

      lambdaInformation.class_name = lambda_class_name;
      lambdaInformation.method_name = lambda_method_name;
      lambdaInformation.method_descriptor = lambda_method_descriptor;

      lambdaInformation.content_class_name = content_class_name;
      lambdaInformation.content_method_name = content_method_name;
      lambdaInformation.content_method_descriptor = content_method_descriptor;
      lambdaInformation.content_method_invocation_type = content_method_invocation_type;

      lambdaInformation.content_method_key =
        InterpreterUtil.makeUniqueKey(lambdaInformation.content_method_name, lambdaInformation.content_method_descriptor);

      anonymousClassType = new VarType(lambda_class_name, true);

      boolean is_method_reference = (content_class_name != classStruct.qualifiedName);
      if (!is_method_reference) { // content method in the same class, check synthetic flag
        StructMethod mt = classStruct.getMethod(content_method_name, content_method_descriptor);
        is_method_reference = !mt.isSynthetic(); // if not synthetic -> method reference
      }

      lambdaInformation.is_method_reference = is_method_reference;
      lambdaInformation.is_content_method_static =
        (lambdaInformation.content_method_invocation_type == CodeConstants.CONSTANT_MethodHandle_REF_invokeStatic); // FIXME: redundant?
    }

    public ClassNode(int type, StructClass classStruct) {
      this.type = type;
      this.classStruct = classStruct;

      simpleName = classStruct.qualifiedName.substring(classStruct.qualifiedName.lastIndexOf('/') + 1);
    }

    public ClassNode getClassNode(String qualifiedName) {
      for (ClassNode node : nested) {
        if (qualifiedName.equals(node.classStruct.qualifiedName)) {
          return node;
        }
      }
      return null;
    }

    public ClassWrapper getWrapper() {
      ClassNode node = this;
      while (node.type == CLASS_LAMBDA) {
        node = node.parent;
      }
      return node.wrapper;
    }

    public static class LambdaInformation {
      public String class_name;
      public String method_name;
      public String method_descriptor;

      public String content_class_name;
      public String content_method_name;
      public String content_method_descriptor;
      public int content_method_invocation_type; // values from CONSTANT_MethodHandle_REF_*
      public String content_method_key;

      public boolean is_method_reference;
      public boolean is_content_method_static;
    }
  }
}

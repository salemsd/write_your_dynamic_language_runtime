package fr.umlv.smalljs.jvminterp;

import static java.lang.invoke.MethodType.genericMethodType;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.objectweb.asm.Opcodes.*;

import java.io.PrintWriter;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;

import fr.umlv.smalljs.rt.Failure;
import org.objectweb.asm.*;

import fr.umlv.smalljs.ast.Expr;
import fr.umlv.smalljs.ast.Expr.Block;
import fr.umlv.smalljs.ast.Expr.Call;
import fr.umlv.smalljs.ast.Expr.FieldAccess;
import fr.umlv.smalljs.ast.Expr.FieldAssignment;
import fr.umlv.smalljs.ast.Expr.Fun;
import fr.umlv.smalljs.ast.Expr.Identifier;
import fr.umlv.smalljs.ast.Expr.If;
import fr.umlv.smalljs.ast.Expr.Literal;
import fr.umlv.smalljs.ast.Expr.MethodCall;
import fr.umlv.smalljs.ast.Expr.ObjectLiteral;
import fr.umlv.smalljs.ast.Expr.Return;
import fr.umlv.smalljs.ast.Expr.VarAssignment;
import fr.umlv.smalljs.rt.JSObject;
import org.objectweb.asm.util.CheckClassAdapter;

public final class ByteCodeRewriter {
  static JSObject createFunction(String name, List<String> parameters, Block body, JSObject global) {
    var env = JSObject.newEnv(null);

    env.register("this", 0);
    for (String parameter : parameters) {
      env.register(parameter, env.length());
    }
    var parameterCount = env.length();
    visitVariable(body, env);
    var localVariableCount = env.length();

    var cv = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
    cv.visit(V21, ACC_PUBLIC | ACC_SUPER, "script", null, "java/lang/Object", null);
    cv.visitSource("script", null);

    var methodType = genericMethodType(1 + parameters.size());
    var desc = methodType.toMethodDescriptorString();
    var mv = cv.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
    mv.visitCode();

    //initialize local variables to undefined by default
    for (var i = parameterCount; i < localVariableCount; i++) {
      mv.visitLdcInsn(new ConstantDynamic("undefined", "Ljava/lang/Object;", BSM_UNDEFINED));
      mv.visitVarInsn(ASTORE, i);
    }

    var dictionary = new FunDictionary();
    visit(body, env, mv, dictionary);

    mv.visitLdcInsn(new ConstantDynamic("undefined", "Ljava/lang/Object;", BSM_UNDEFINED));
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();

    var instrs = cv.toByteArray();
    dumpBytecode(instrs);

    var functionClassLoader = new FunClassLoader(dictionary, global);
    var type = functionClassLoader.createClass("script", instrs);

    MethodHandle mh;
    try {
      mh = MethodHandles.lookup().findStatic(type, name, methodType);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }

    return JSObject.newFunction(name, mh);
  }

  private static void dumpBytecode(byte[] array) {
    var reader = new ClassReader(array);
    CheckClassAdapter.verify(reader, true, new PrintWriter(System.err, false, UTF_8));
  }

  private static void visitVariable(Expr expression, JSObject env) {
    switch (expression) {
      case Block(List<Expr> exprs, _) -> {
        for (var expr : exprs) {
          visitVariable(expr, env);
        }
      }
      case VarAssignment(String name, _, boolean declaration, _) -> {
        if (declaration) {
          env.register(name, env.length());
        }
      }
      case If(_, Block trueBlock, Block falseBlock, _) -> {
        visitVariable(trueBlock, env);
        visitVariable(falseBlock, env);
      }
      case Literal _, Call _, Identifier _, Fun _, Return _, ObjectLiteral _, FieldAccess _,
           FieldAssignment _, MethodCall _ -> {
        // do nothing
      }
    }
    ;
  }

  private static Handle bsm(String name, Class<?> returnType, Class<?>... parameterTypes) {
    return new Handle(H_INVOKESTATIC,
            RT_NAME, name,
            MethodType.methodType(returnType, parameterTypes).toMethodDescriptorString(), false);
  }

  private static final String JSOBJECT = JSObject.class.getName().replace('.', '/');
  private static final String RT_NAME = RT.class.getName().replace('.', '/');
  private static final Handle BSM_UNDEFINED = bsm("bsm_undefined", Object.class, Lookup.class, String.class, Class.class);
  private static final Handle BSM_CONST = bsm("bsm_const", Object.class, Lookup.class, String.class, Class.class, int.class);
  private static final Handle BSM_FUNCALL = bsm("bsm_funcall", CallSite.class, Lookup.class, String.class, MethodType.class);
  private static final Handle BSM_GLOBALCALL = bsm("bsm_globalcall", CallSite.class, Lookup.class, String.class, MethodType.class, String.class);
  private static final Handle BSM_LOOKUP = bsm("bsm_lookup", CallSite.class, Lookup.class, String.class, MethodType.class, String.class);
  private static final Handle BSM_FUN = bsm("bsm_fun", Object.class, Lookup.class, String.class, Class.class, int.class);
  private static final Handle BSM_REGISTER = bsm("bsm_register", CallSite.class, Lookup.class, String.class, MethodType.class, String.class);
  private static final Handle BSM_TRUTH = bsm("bsm_truth", CallSite.class, Lookup.class, String.class, MethodType.class);
  private static final Handle BSM_GET = bsm("bsm_get", CallSite.class, Lookup.class, String.class, MethodType.class, String.class);
  private static final Handle BSM_SET = bsm("bsm_set", CallSite.class, Lookup.class, String.class, MethodType.class, String.class);
  private static final Handle BSM_METHODCALL = bsm("bsm_methodcall", CallSite.class, Lookup.class, String.class, MethodType.class);

  private static void visit(Expr expression, JSObject env, MethodVisitor mv, FunDictionary dictionary) {
    switch (expression) {
      case Block(List<Expr> exprs, int lineNumber) -> {
        // for each expression
        for (var expr : exprs) {
          // generate line numbers
          var label = new Label();
          mv.visitLabel(label);
          mv.visitLineNumber(lineNumber, label);
          // visit it
          visit(expr, env, mv, dictionary);
          // if not a statement, generate a POP
          if (!(expr instanceof Expr.Statement)) {
            mv.visitInsn(POP);
          }
        }
      }
      case Literal(Integer integer, int lineNumber) -> {
        // use visitLDCInstr with a ConstantDynamic because the JVM does not support Integer (but supports int)
        var constant = new ConstantDynamic("const_i", "Ljava/lang/Object;", BSM_CONST, integer);
        mv.visitLdcInsn(constant);
      }
      case Literal(String s, int lineNumber) -> {
        // use visitLDCInstr because the JVM natively supports strings
        mv.visitLdcInsn(s);
      }
      case Literal _ -> {  // should be UNDEFINED
        // use visitLDCInstr with a ConstantDynamic because the JVM does not support UNDEFINED natively
        var constant = new ConstantDynamic("undefined", "Ljava/lang/Object;", BSM_UNDEFINED);
        mv.visitLdcInsn(constant);
      }
      case Call(Expr qualifier, List<Expr> args, int lineNumber) -> {
        var undefined = new ConstantDynamic("undefined", "Ljava/lang/Object;", BSM_UNDEFINED);
        if (qualifier instanceof Identifier identifier
                && env.lookupOrDefault(identifier.name(), null) == null) {

          mv.visitLdcInsn(undefined);
          for (var arg : args) {
            // for each argument, visit it
            visit(arg, env, mv, dictionary);
          }
          // generate an invokedynamic
          var desc = "(" + "Ljava/lang/Object;".repeat(args.size() + 1) + ")Ljava/lang/Object;";
          mv.visitInvokeDynamicInsn("globalcall", desc, BSM_GLOBALCALL, identifier.name());
          return;
        }

        // visit the qualifier
        visit(qualifier, env, mv, dictionary);
        // load "this"
        mv.visitLdcInsn(undefined);
        for (var arg : args) {
          // for each argument, visit it
          visit(arg, env, mv, dictionary);
        }
        // generate an invokedynamic
        var desc = "(" + "Ljava/lang/Object;".repeat(args.size() + 2) + ")Ljava/lang/Object;";
        mv.visitInvokeDynamicInsn("call", desc, BSM_FUNCALL);
      }
      case VarAssignment(String name, Expr expr, boolean declaration, int lineNumber) -> {
        // visit the expression
        visit(expr, env, mv, dictionary);
        // lookup that name in the environment
        var slot = env.lookupOrDefault(name, null);
        // if it does not exist throw a Failure
        if (slot == null) {
          throw new Failure("unknown variable " + name + " at line " + lineNumber);
        }
        // otherwise STORE the top of the stack at the local variable slot
        mv.visitVarInsn(ASTORE, (int) slot);
      }
      case Identifier(String name, int lineNumber) -> {
        // lookup to find if it's a local var access or a lookup access
        var slot = env.lookupOrDefault(name, null);
        // if it does not exist
        if (slot == null) {
          //  generate an invokedynamic doing a lookup
          mv.visitInvokeDynamicInsn("lookup", "()Ljava/lang/Object;", BSM_LOOKUP, name);
        } else {
          // otherwise
          //  load the local variable at the slot
          mv.visitVarInsn(ALOAD, (int) slot);
        }
      }
      case Fun fun -> {
        var name = fun.name();
        var toplevel = fun.toplevel();
        // register the fun inside the fun directory and get the corresponding id
        var id = dictionary.register(fun);
        // emit a LDC to load the function corresponding to the id at runtime
        var constant = new ConstantDynamic(name, "Ljava/lang/Object;", BSM_FUN, id);
        mv.visitLdcInsn(constant);
        // generate an invokedynamic doing a register with the function name if it's a toplevel
        if (toplevel) {
          mv.visitInsn(DUP);
          mv.visitInvokeDynamicInsn("register", "(Ljava/lang/Object;)V", BSM_REGISTER, name);
        }
      }
      case Return(Expr expr, int lineNumber) -> {
        // visit the return expression
        visit(expr, env, mv, dictionary);
        // generate the bytecode
        mv.visitInsn(ARETURN);
      }
      case If(Expr condition, Block trueBlock, Block falseBlock, int lineNumber) -> {
        // visit the condition
        visit(condition, env, mv, dictionary);
        // generate an invokedynamic to transform an Object to a boolean using BSM_TRUTH
        mv.visitInvokeDynamicInsn("truth", "(Ljava/lang/Object;)Z", BSM_TRUTH);
        var isFalseLabel = new Label();
        mv.visitJumpInsn(IFEQ, isFalseLabel);
        // visit the true block
        visit(trueBlock, env, mv, dictionary);
        var endLabel = new Label();
        mv.visitJumpInsn(GOTO, endLabel);
        // visit the false block
        mv.visitLabel(isFalseLabel);
        visit(falseBlock, env, mv, dictionary);
        mv.visitLabel(endLabel);
      }
      case ObjectLiteral(Map<String, Expr> initMap, int lineNumber) -> {
        // call newObject with an INVOKESTATIC
        mv.visitInsn(ACONST_NULL);
        var desc = "(L" + JSOBJECT + ";)L" + JSOBJECT + ";";
        mv.visitMethodInsn(INVOKESTATIC, JSOBJECT, "newObject", desc, false);
        // for each initialization expression
        initMap.forEach((fieldName, expr) -> {
          mv.visitInsn(DUP);
          // generate a string with the key
          mv.visitLdcInsn(fieldName);
          // call register on the JSObject
          visit(expr, env, mv, dictionary);
          mv.visitMethodInsn(INVOKEVIRTUAL, JSOBJECT, "register", "(Ljava/lang/String;Ljava/lang/Object;)V", false);
        });
      }
      case FieldAccess(Expr receiver, String name, int lineNumber) -> {
        // visit the receiver
        visit(receiver, env, mv, dictionary);
        // generate an invokedynamic that goes a get through BSM_GET
        mv.visitInvokeDynamicInsn("get", "(Ljava/lang/Object;)Ljava/lang/Object;", BSM_GET, name);
      }
      case FieldAssignment(Expr receiver, String name, Expr expr, int lineNumber) -> {
        // visit the receiver
        visit(receiver, env, mv, dictionary);
        // visit the expression
        visit(expr, env, mv, dictionary);
        mv.visitInvokeDynamicInsn("set", "(Ljava/lang/Object;Ljava/lang/Object;)V", BSM_SET, name);
      }
      case MethodCall(Expr receiver, String name, List<Expr> args, int lineNumber) -> {
        throw new UnsupportedOperationException("TODO MethodCall");
        // visit the receiver
        // for each argument
        // visit the argument
        // generate an invokedynamic that call BSM_METHODCALL
      }
    }
  }
}

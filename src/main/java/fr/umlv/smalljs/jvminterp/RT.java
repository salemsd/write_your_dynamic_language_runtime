package fr.umlv.smalljs.jvminterp;

import static fr.umlv.smalljs.rt.JSObject.UNDEFINED;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.foldArguments;
import static java.lang.invoke.MethodHandles.guardWithTest;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.invoker;
import static java.lang.invoke.MethodType.methodType;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;

import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;

public final class RT {
  private static final MethodHandle LOOKUP_OR_DEFAULT, LOOKUP_OR_FAIL, REGISTER, INVOKE, TRUTH, LOOKUP_MH;

  static {
    var lookup = MethodHandles.lookup();
    try {
      LOOKUP_OR_DEFAULT = lookup.findVirtual(JSObject.class, "lookupOrDefault", methodType(Object.class, String.class, Object.class));
      LOOKUP_OR_FAIL = lookup.findStatic(RT.class, "lookupOrFail", methodType(Object.class, JSObject.class, String.class));
      REGISTER = lookup.findVirtual(JSObject.class, "register", methodType(void.class, String.class, Object.class));

      INVOKE = lookup.findVirtual(JSObject.class, "invoke", methodType(Object.class, Object.class, Object[].class));

      TRUTH = lookup.findStatic(RT.class, "truth", methodType(boolean.class, Object.class));

      LOOKUP_MH = lookup.findStatic(RT.class, "lookupMethodHandle", methodType(MethodHandle.class, JSObject.class, String.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  public static Object bsm_undefined(Lookup lookup, String name, Class<?> type) {
    return UNDEFINED;
  }

  public static Object bsm_const(Lookup lookup, String name, Class<?> type, int constant) {
    return constant;
  }

  @SuppressWarnings("unused")  // used by a method handle
  private static Object lookupOrFail(JSObject jsObject, String key) {
    var value = jsObject.lookupOrDefault(key, null);
    if (value == null) {
      throw new Failure("no value for " + key);
    }
    return value;
  }

  public static CallSite bsm_lookup(Lookup lookup, String name, MethodType type, String variableName) {
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    var globalEnv = classLoader.global();
    // get the LOOKUP_OR_FAIL method handle
    var lookupOrFail = LOOKUP_OR_FAIL;
    // use the global environment as first argument and the variableName as second argument
//    var target = lookupOrFail.bindTo(globalEnv).bindTo(variableName);
    var target = MethodHandles.insertArguments(lookupOrFail, 0, globalEnv, variableName);
    // create a constant callsite
    return new ConstantCallSite(target);
  }

  public static CallSite bsm_funcall(Lookup lookup, String name, MethodType type) {
//    // get INVOKE method handle
//    var invoke = INVOKE;
//    // make it accept an Object (not a JSObject) and objects as other parameters
//    var target = invoke.asType(type);
//    // create a constant callsite
//    return new ConstantCallSite(target);
    return new InliningCache(type, InliningCache.MAX_DEPTH, null);
  }

  private static class InliningCache extends MutableCallSite {
    private static final MethodHandle SLOW_PATH, TEST;
    private static final int MAX_DEPTH = 3;

    static {
      var lookup = MethodHandles.lookup();
      try {
        SLOW_PATH = lookup.findVirtual(InliningCache.class, "slowPath", methodType(MethodHandle.class, Object.class, Object.class));
        TEST = lookup.findStatic(InliningCache.class, "test", methodType(boolean.class, Object.class, Object.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private final int depth;
    private final InliningCache root;

    public InliningCache(MethodType type, int depth, InliningCache root) {
      this.depth = depth;
      super(type);
      this.root = root == null ? this : root;
      setTarget(MethodHandles.foldArguments(MethodHandles.exactInvoker(type), SLOW_PATH.bindTo(this)));
    }

    private static boolean test(Object qualifier, Object expected) {
      return qualifier == expected;
    }

    private MethodHandle slowPath(Object qualifier, Object receiver) {
      var jsObject = (JSObject) qualifier;
      var mh = jsObject.methodHandle();

      System.err.println("jsobject: " + jsObject.name());

      if (!mh.isVarargsCollector() && type().parameterCount() != mh.type().parameterCount() + 1) {
        throw new Failure("wrong number of arguments for " + (mh.type().parameterCount() - 1)
                + " expected " + (type().parameterCount() - 2));
      }

      var target = MethodHandles.dropArguments(mh, 0, Object.class);
      target = target.withVarargs(mh.isVarargsCollector());
      target = target.asType(type());

      if (depth == MAX_DEPTH) {
        System.err.println("deoptimize " + depth);
        root.setTarget(INVOKE.asType(type()));
        return target;
      }

      var test = MethodHandles.insertArguments(TEST, 1, jsObject);

      var fallback = new InliningCache(type(), depth + 1, root).dynamicInvoker();

      var guard = MethodHandles.guardWithTest(test, target, fallback);
      setTarget(guard);

      return target;
    }
  }

  public static Object bsm_fun(Lookup lookup, String name, Class<?> type, int funId) {
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    var globalEnv = classLoader.global();
    // get the dictionary and get the Fun object corresponding to the id
    var dictionary = classLoader.dictionary();
    var fun = dictionary.lookupAndClear(funId);
    // create the function using ByteCodeRewriter.createFunction(...)
    return ByteCodeRewriter.createFunction(name, fun.parameters(), fun.body(), globalEnv);
  }

  public static CallSite bsm_register(Lookup lookup, String name, MethodType type, String functionName) {
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    var globalEnv = classLoader.global();
    //get the REGISTER method handle
    var register = REGISTER;
    // use the global environment as first argument and the functionName as second argument
    var target = MethodHandles.insertArguments(register, 0, globalEnv, functionName);
    // create a constant callsite
    return new ConstantCallSite(target);
  }

  @SuppressWarnings("unused")  // used by a method handle
  private static boolean truth(Object o) {
    return o != null && o != UNDEFINED && o != Boolean.FALSE;
  }

  public static CallSite bsm_truth(Lookup lookup, String name, MethodType type) {
    // get the TRUTH method handle
    var target = TRUTH;
    // create a constant callsite
    return new ConstantCallSite(target);
  }

  /*
  public static CallSite bsm_get(Lookup lookup, String name, MethodType type, String fieldName) {
    // get the LOOKUP_OR_DEFAULT method handle
    var mh = LOOKUP_OR_DEFAULT;
    // use the fieldName and UNDEFINED as second argument and third argument
    var target = MethodHandles.insertArguments(mh, 1, fieldName, UNDEFINED);
    // make it accept an Object (not a JSObject) as first parameter
    target = target.asType(type);
    // create a constant callsite
    return new ConstantCallSite(target);
  }
   */

  public static CallSite bsm_get(Lookup lookup, String name, MethodType type, String fieldName) {
    //return new ConstantCallSite(insertArguments(LOOKUP, 1, fieldName).asType(type));
    return new InliningFieldCache(type, fieldName);
  }

  private static final class InliningFieldCache extends MutableCallSite {
    private static final MethodHandle SLOW_PATH, LAYOUT_CHECK, FAST_ACCESS;

    static {
      var lookup = MethodHandles.lookup();
      try {
        SLOW_PATH = lookup.findVirtual(InliningFieldCache.class, "slowPath",
                methodType(Object.class, Object.class));
        FAST_ACCESS = lookup.findVirtual(JSObject.class, "fastAccess",
                methodType(Object.class, int.class));
        LAYOUT_CHECK = lookup.findStatic(InliningFieldCache.class, "layoutCheck",
                methodType(boolean.class, JSObject.class, Object.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private final String fieldName;

    public InliningFieldCache(MethodType type, String fieldName) {
      super(type);
      this.fieldName = fieldName;
      setTarget(SLOW_PATH.bindTo(this));
    }

    private static boolean layoutCheck(JSObject jsObject, Object expectedLayout) {
      return jsObject.layout() == expectedLayout;
    }

    @SuppressWarnings("unused")  // called by a MH
    private Object slowPath(Object receiver) {
      var jsObject = (JSObject) receiver;

      // classical access to the value
//      var value = jsObject.lookupOrDefault(fieldName, UNDEFINED);

      // fast access
      var layout = jsObject.layout();
      var slot = jsObject.layoutSlot(fieldName);   // may be -1 !
      MethodHandle target;
      Object value;
      if (slot == -1) {
        value = UNDEFINED;
        target = MethodHandles.dropArguments(
                MethodHandles.constant(Object.class, UNDEFINED),
                0,
                Object.class
        );
      } else {
        value = jsObject.fastAccess(slot);
        target = MethodHandles.insertArguments(FAST_ACCESS, 1, slot)
                .asType(type());
      }

      var test = MethodHandles.insertArguments(LAYOUT_CHECK, 1, layout)
              .asType(methodType(boolean.class, Object.class));
      var guardWithTest = guardWithTest(test, target,
              new InliningFieldCache(type(), fieldName).dynamicInvoker()
      );
      setTarget(guardWithTest);

      return value;
    }
  }

  public static CallSite bsm_set(Lookup lookup, String name, MethodType type, String fieldName) {
    // get the REGISTER method handle
    var mh = REGISTER;
    // use the fieldName as second argument
    var target = MethodHandles.insertArguments(mh, 1, fieldName);
    // make it accept an Object (not a JSObject) as first parameter
    target = target.asType(type);
    // create a constant callsite
    return new ConstantCallSite(target);
  }

  @SuppressWarnings("unused")  // used by a method handle
  private static MethodHandle lookupMethodHandle(JSObject receiver, String fieldName) {
    var function = (JSObject) receiver.lookupOrDefault(fieldName, null);
    if (function == null) {
      throw new Failure("no method " + fieldName);
    }
    return function.methodHandle();
  }

  public static CallSite bsm_methodcall(Lookup lookup, String name, MethodType type) {
    var combiner = insertArguments(LOOKUP_MH, 1, name).asType(methodType(MethodHandle.class, Object.class));
    var target = foldArguments(invoker(type), combiner);
    return new ConstantCallSite(target);
  }
}

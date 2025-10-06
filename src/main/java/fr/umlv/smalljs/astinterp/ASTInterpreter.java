package fr.umlv.smalljs.astinterp;

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
import fr.umlv.smalljs.ast.Script;
import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static fr.umlv.smalljs.rt.JSObject.UNDEFINED;
import static java.util.stream.Collectors.joining;


/*
    1. Before starting, explain to yourself how "switch on types" in the method visit of ASTInterpreter.java works ?
    How to "return" a value from a case ?
    How to call it, how to do a recursive call ?

    - Either throw something, or return with -> or -> { ... yield } for several lines
    - Call it with an expression to visit, and the environment which contains the variables

    2. The file ASTInterpreterTest contains several unit tests for all the following questions.
    Now, we want to implement the AST interpreter when there is a constant String (a literal).
    What is the class of the corresponding node in the AST (you can take a look to the file smalljs.md for a summary).
    How to implement it ?
    Is it enough to make test marked Q2 pass ? what other node should be implemented too ?

    - It's Literal. It's not enough for Q2, we also need the Block

X
 */

public final class ASTInterpreter {
  private static JSObject asJSObject(Object value, int lineNumber) {
    if (!(value instanceof JSObject jsObject)) {
      throw new Failure("at line " + lineNumber + ", type error " + value + " is not a JSObject");
    }
    return jsObject;
  }

  private static Object execute(Expr.Block body, JSObject env) {
    // initialize declared variables to UNDEFINED
    visitVariable(body, env);
    // interpret the AST
    return visit(body, env);
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
          env.register(name, UNDEFINED);
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
    };
  }

  static Object visit(Expr expression, JSObject env) {
    return switch (expression) {
      case Block(List<Expr> exprs, int lineNumber) -> {
        for (var expr: exprs) {
          visit(expr, env);
        }
        yield UNDEFINED;
      }
      case Literal(Object value, int lineNumber) -> value;
      case Call(Expr qualifier, List<Expr> args, int lineNumber) -> {
        var mayBeFunction = visit(qualifier, env);
        var function = asJSObject(mayBeFunction, lineNumber);
        var arguments = args.stream()
                        .map(arg -> visit(arg, env))
                                .toArray();
        yield function.invoke(UNDEFINED, arguments);
      }
      case Identifier(String name, int lineNumber) -> {
        var value = env.lookupOrDefault(name, null);
        if (value == null) {
          throw new Failure("at line " + lineNumber + ", variable " + name + " is not defined");
        }
        yield value;
      }
      case VarAssignment(String name, Expr expr, _, int lineNumber) -> {
        var value = visit(expr, env);
        var oldValue = env.lookupOrDefault(name, null);
        if (oldValue == null) {
          throw new Failure("at line " + lineNumber + ", variable " + name + " is not defined");
        }
        env.register(name, value);
        yield value;
      }
      case Fun(String name, List<String> parameters, boolean toplevel, Block body, int lineNumber) -> {
        JSObject.Invoker invoker = new JSObject.Invoker() {
          @Override
          public Object invoke(Object receiver, Object... args) {
            // check the arguments length
            if (args.length != parameters.size()) {
              throw new Failure("at line " + lineNumber + ", function " + name + " called with " + args.length + " arguments, expected " + parameters.size());
            }
            // create a new environment
            var newEnv = JSObject.newEnv(env);
            // add this and all the parameters
            newEnv.register("this", receiver);
            for (var i = 0; i < parameters.size(); i++) {
              newEnv.register(parameters.get(i), args[i]);
            }
            // execute the body
            try {
              execute(body, newEnv);
            } catch (ReturnError error) {
              return error.getValue();
            }
            return UNDEFINED;
          }
        };
//         create the JS function with the invoker
        var function = JSObject.newFunction(name, invoker);
//         register it into the global env if it's a toplevel
        if (toplevel) {
          env.register(name, function);
        }
//         yield the function
        yield function;
      }
      case Return(Expr expr, int lineNumber) -> {
        var value = visit(expr, env);
        throw new ReturnError(value);
      }
      case If(Expr condition, Block trueBlock, Block falseBlock, int lineNumber) -> {
        var value = visit(condition, env);
        if (value instanceof Integer conditionInt && conditionInt == 0) {
          visit(falseBlock, env);
        } else {
          visit(trueBlock, env);
        }
        yield UNDEFINED;
      }
      case ObjectLiteral(Map<String, Expr> initMap, int lineNumber) -> {
        var obj = JSObject.newObject(null);
        initMap.forEach((k, v) -> {
          var value = visit(v, env);
          obj.register(k, value);
        });

        yield obj;
      }
      case FieldAccess(Expr receiver, String name, int lineNumber) -> {
        var obj = asJSObject(visit(receiver, env), lineNumber);

        yield obj.lookupOrDefault(name, UNDEFINED);
      }
      case FieldAssignment(Expr receiver, String name, Expr expr, int lineNumber) -> {
        var obj = asJSObject(visit(receiver, env), lineNumber);
        obj.register(name, expr);

        yield obj;
      }
      case MethodCall(Expr receiver, String name, List<Expr> args, int lineNumber) -> {
        var obj = asJSObject(visit(receiver, env), lineNumber);
        var mayBeMethod = obj.lookupOrDefault(name, null);
        if (!(mayBeMethod instanceof JSObject method)) {
          throw new Failure("at line " + lineNumber + ", " + name + " is not a method");
        }
        var arguments = args.stream()
                .map(arg -> visit(arg, env))
                .toArray();
        yield method.invoke(obj, arguments);
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static JSObject createGlobalEnv(PrintStream outStream) {
    var globalEnv = JSObject.newEnv(null);
    globalEnv.register("globalThis", globalEnv);
    globalEnv.register("print", JSObject.newFunction("print", (_, args) -> {
      System.err.println("print called with " + Arrays.toString(args));
      outStream.println(Arrays.stream(args).map(Object::toString).collect(Collectors.joining(" ")));
      return UNDEFINED;
    }));
    globalEnv.register("+", JSObject.newFunction("+", (_, args) -> (Integer) args[0] + (Integer) args[1]));
    globalEnv.register("-", JSObject.newFunction("-", (_, args) -> (Integer) args[0] - (Integer) args[1]));
    globalEnv.register("/", JSObject.newFunction("/", (_, args) -> (Integer) args[0] / (Integer) args[1]));
    globalEnv.register("*", JSObject.newFunction("*", (_, args) -> (Integer) args[0] * (Integer) args[1]));
    globalEnv.register("%", JSObject.newFunction("%", (_, args) -> (Integer) args[0] % (Integer) args[1]));
    globalEnv.register("==", JSObject.newFunction("==", (_, args) -> args[0].equals(args[1]) ? 1 : 0));
    globalEnv.register("!=", JSObject.newFunction("!=", (_, args) -> !args[0].equals(args[1]) ? 1 : 0));
    globalEnv.register("<", JSObject.newFunction("<", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) < 0) ? 1 : 0));
    globalEnv.register("<=", JSObject.newFunction("<=", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) <= 0) ? 1 : 0));
    globalEnv.register(">", JSObject.newFunction(">", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) > 0) ? 1 : 0));
    globalEnv.register(">=", JSObject.newFunction(">=", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) >= 0) ? 1 : 0));
    return globalEnv;
  }

  public static void interpret(Script script, PrintStream outStream) {
    var globalEnv =createGlobalEnv(outStream);
    var body = script.body();
    execute(body, globalEnv);
  }
}


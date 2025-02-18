package zju.cst.aces.util;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class MutationOperatorUtil {


    /**
     * Creates a mutated subclass that overrides the given method.
     * The new method calls the parent method passing default values for each parameter.
     *
     * @param code             The original class source code.
     * @param methodName       The method to mutate.
     * @param className The name of the original class.
     * @param mutatedClassName  The name for the mutated subclass.
     * @return A string with the source code for the mutated subclass.
     */
    public static String applyNullMutation(String code, String methodName, String className,
                                           String mutatedClassName, Boolean methodMutated) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(code);

            // Generate the subclass
            ClassOrInterfaceDeclaration mutatedClass = new ClassOrInterfaceDeclaration()
                    .setName(mutatedClassName)
                    .addExtendedType(className) // Make it inherit from the original class
                    .setPublic(true);

            // Find the method to override
            Optional<MethodDeclaration> methodOpt = cu.findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.getNameAsString().equals(methodName))
                    .findFirst();

            if (methodOpt.isPresent()) {
                MethodDeclaration method = methodOpt.get();

                // Clone the method and override it in the subclass
                MethodDeclaration mutatedMethod = method.clone();
                if(methodMutated){
                    mutatedMethod.setName(methodName + "_mutated");
                }
                else{
                    mutatedMethod.addAnnotation(Override.class);
                }
                String defaultValue = getDefaultValue(method.getType().asString()); // Get default return value
                mutatedMethod.setBody(new BlockStmt().addStatement(new ReturnStmt(defaultValue)));


                // Add the mutated method to the new class
                mutatedClass.addMember(mutatedMethod);
            } else {
                System.out.println("Method " + methodName + " not found.");
                return "";
            }

            return mutatedClass.toString(); // Return only the mutated class
        } catch (Exception e) {
            System.out.println("Error in applyNullMutation: " + e);
            return ""; // Return empty string if something goes wrong
        }
    }

    /**
     * Creates a mutated subclass that overrides the given method.
     * The new method calls the parent method passing default values for each parameter.
     *
     * @param code             The original class source code.
     * @param methodName       The method to mutate.
     * @param originalClassName The name of the original class.
     * @param mutatedClassName  The name for the mutated subclass.
     * @return A string with the source code for the mutated subclass.
     */
    public static String applyVariableMutation(String code, String methodName, String originalClassName,
                                               String mutatedClassName, Boolean methodMutated) {
        try {
            // Parse the original class code
            CompilationUnit cu = StaticJavaParser.parse(code);

            // Create the mutated subclass declaration.
            ClassOrInterfaceDeclaration mutatedClass = new ClassOrInterfaceDeclaration()
                    .setName(mutatedClassName)
                    .addExtendedType(originalClassName) // Inherit from the original class
                    .setPublic(true);

            // Find the method to override in the original code.
            Optional<MethodDeclaration> methodOpt = cu.findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.getNameAsString().equals(methodName))
                    .findFirst();

            if (!methodOpt.isPresent()) {
                System.out.println("Method " + methodName + " not found.");
                return "";
            }
            MethodDeclaration method = methodOpt.get();

            // Build default arguments for the parent's method call.
            List<String> defaultArgs = new ArrayList<>();
            method.getParameters().forEach(param -> {
                String paramType = param.getType().asString();
                String defaultVal = getDefaultValue(paramType);
                defaultArgs.add(defaultVal);
            });
            String args = defaultArgs.stream().collect(Collectors.joining(", "));

            // Build the new body: call parent's method with default arguments.
            BlockStmt newBody = new BlockStmt();
            String call = "return super." + methodName + "(" + args + ");";
            if(methodMutated){
                call = "return " + methodName + "(" + args + ");";
            }

            newBody.addStatement(call);

            // Clone the method and set its new body.
            MethodDeclaration mutatedMethod = method.clone();
            if(methodMutated){
                mutatedMethod.setName(methodName + "_mutated");
            }
            else{
                mutatedMethod.addAnnotation(Override.class);
            }

            mutatedMethod.setBody(newBody);
            //mutatedMethod.addAnnotation(Override.class);

            // Add the mutated method to the mutated class.
            mutatedClass.addMember(mutatedMethod);

            // Return the mutated class source code.
            return mutatedClass.toString();
        } catch (Exception e) {
            System.out.println("Error in applyVariableMutation: " + e);
            return "";
        }
    }


    /**
     * Returns a default value based on the return type.
     */
    private static String getDefaultValue(String returnType) {
        switch (returnType) {
            case "int":
            case "byte":
            case "short":
            case "long":
            case "float":
            case "double":
                return "0";
            case "boolean":
                return "false";
            case "char":
                return "'\u0000'"; // Null character
            case "String":
                return "\"\""; // Empty string
            default:
                return "null"; // Objects and other types
        }
    }

    public static String changeClassName(String code, String originalName, String newName) {
        CompilationUnit cu = StaticJavaParser.parse(code);

        // Replace object instantiations (new OriginalClass())
        cu.findAll(ObjectCreationExpr.class).stream()
                .filter(obj -> obj.getTypeAsString().equals(originalName))
                .forEach(obj -> obj.setType(newName));

        // Replace references to the original class in method calls
        cu.findAll(NameExpr.class).stream()
                .filter(name -> name.getNameAsString().equals(originalName))
                .forEach(name -> name.setName(newName));
        return cu.toString();
    }

    public static String changeMethodName(String code, String originalMethod, String newMethod) {
        CompilationUnit cu = StaticJavaParser.parse(code);

        // Replace method calls with the new method name
        cu.findAll(MethodCallExpr.class).stream()
                .filter(methodCall -> methodCall.getNameAsString().equals(originalMethod))
                .forEach(methodCall -> methodCall.setName(newMethod));

        return cu.toString();
    }

    public static String injectMutationClass(String mainCode, String mutationCode) {
        try {
            CompilationUnit mainCU = StaticJavaParser.parse(mainCode);
            CompilationUnit mutationCU = StaticJavaParser.parse(mutationCode);

            // Get the main class
            Optional<ClassOrInterfaceDeclaration> mainClassOpt = mainCU.findFirst(ClassOrInterfaceDeclaration.class);
            Optional<ClassOrInterfaceDeclaration> mutationClassOpt = mutationCU.findFirst(ClassOrInterfaceDeclaration.class);

            if (mainClassOpt.isPresent() && mutationClassOpt.isPresent()) {
                ClassOrInterfaceDeclaration mainClass = mainClassOpt.get();
                ClassOrInterfaceDeclaration mutationClass = mutationClassOpt.get();

                // Add the mutation class inside the main class
                mainClass.addMember(mutationClass);

                return mainCU.toString();
            } else {
                System.out.println("Error: Could not find main or mutation class.");
                return mainCode;
            }
        } catch (Exception e) {
            System.out.println("Error in injectMutationClass: " + e);
            return mainCode; // Return original code if something goes wrong
        }
    }


    public static String applyOperatorMutationBoolean(String code, String methodName, String originalClassName, String mutatedClassName, Boolean methodMutated) {
        return applyOperatorMutation(code, methodName, originalClassName, mutatedClassName, MutationType.BOOLEAN, methodMutated);
    }

    public static String applyOperatorMutationAritimetic(String code, String methodName, String originalClassName, String mutatedClassName, Boolean methodMutated) {
        return applyOperatorMutation(code, methodName, originalClassName, mutatedClassName, MutationType.ARITHMETIC, methodMutated);
    }

    public static String applyOperatorMutationLogic(String code, String methodName, String originalClassName, String mutatedClassName, Boolean methodMutated) {
        return applyOperatorMutation(code, methodName, originalClassName, mutatedClassName, MutationType.LOGICAL, methodMutated);
    }

    public static String applyOperatorMutationRelational(String code, String methodName, String originalClassName, String mutatedClassName, Boolean methodMutated) {
        return applyOperatorMutation(code, methodName, originalClassName, mutatedClassName, MutationType.LOGICAL, methodMutated);
    }

    private static String applyOperatorMutation(String code, String methodName, String originalClassName, String mutatedClassName,
                                                MutationType mutationTypen, Boolean methodMutated) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(code);

            // Encontrar a classe original
            Optional<ClassOrInterfaceDeclaration> classOpt = cu.findFirst(ClassOrInterfaceDeclaration.class,
                    c -> c.getNameAsString().equals(originalClassName));
            if (!classOpt.isPresent()) {
                System.out.println("Classe " + originalClassName + " não encontrada.");
                return "";
            }
            ClassOrInterfaceDeclaration originalClass = classOpt.get();

            // Encontrar o método alvo
            Optional<MethodDeclaration> methodOpt = originalClass.getMethodsByName(methodName).stream().findFirst();
            if (!methodOpt.isPresent()) {
                System.out.println("Método " + methodName + " não encontrado.");
                return "";
            }
            MethodDeclaration originalMethod = methodOpt.get();

            // Criar um clone mutado do método
            AtomicBoolean mutationApplied = new AtomicBoolean(false);
            MethodDeclaration mutatedMethod = originalMethod.clone();
            if(methodMutated){
                mutatedMethod.setName(methodName + "_mutated");
            }
            else{
                mutatedMethod.addAnnotation(Override.class);
            }
            mutatedMethod.setBody(mutateMethodBody(originalMethod.getBody().orElse(new BlockStmt()), mutationTypen, mutationApplied));


            // Se nenhuma mutação foi aplicada, retorna vazio
            if (!mutationApplied.get()) {
                return "";
            }

            // Criar a classe mutada
            ClassOrInterfaceDeclaration mutatedClass = new ClassOrInterfaceDeclaration()
                    .setName(mutatedClassName)
                    .addExtendedType(originalClassName)
                    .setPublic(true);

            // Copiar métodos privados e sobrescrevê-los com @Override
            originalClass.getMethods().forEach(method -> {
                if (method.isPrivate()) {
                    MethodDeclaration overriddenMethod = method.clone();
                    overriddenMethod.addAnnotation(Override.class);
                    mutatedClass.addMember(overriddenMethod);
                }
            });


            // Adicionar método mutado à classe derivada
            mutatedClass.addMember(mutatedMethod);

            return mutatedClass.toString();
        } catch (Exception e) {
            System.out.println("Erro em applyOperatorMutation: " + e);
            return "";
        }
    }

    /**
     * Enum para representar os tipos de mutação disponíveis.
     */
    public enum MutationType {
        BOOLEAN,
        ARITHMETIC,
        LOGICAL,
        RELATIONAL
    }

    /**
     * Aplica uma mutação no corpo do método com base no tipo de mutação selecionado.
     */
    private static BlockStmt mutateMethodBody(BlockStmt body, MutationType mutationType, AtomicBoolean mutationApplied) {
        BlockStmt mutatedBody = body.clone();
        mutatedBody.findAll(ReturnStmt.class).forEach(returnStmt -> {
            Expression expr = returnStmt.getExpression().orElse(null);
            if (expr != null) {
                Expression mutatedExpr;
                switch (mutationType) {
                    case BOOLEAN:
                        mutatedExpr = mutateExpressionBoolean(expr, mutationApplied);
                        break;
                    case ARITHMETIC:
                        mutatedExpr = mutateExpressionAritimetic(expr, mutationApplied);
                        break;
                    case LOGICAL:
                        mutatedExpr = mutateExpressionLogical(expr, mutationApplied);
                        break;
                    case RELATIONAL:
                        mutatedExpr = mutateExpressionRelational(expr, mutationApplied);
                        break;
                    default:
                        mutatedExpr = expr; // Caso inesperado, mantém a expressão original
                }
                returnStmt.setExpression(mutatedExpr);
            }
        });
        return mutatedBody;
    }


    /**
     * Muta expressões booleanas (ex: troca `true` ↔ `false` e negações).
     */
    private static Expression mutateExpressionBoolean(Expression expr, AtomicBoolean mutationApplied) {
        if (expr instanceof BooleanLiteralExpr) {
            mutationApplied.set(true);
            return new BooleanLiteralExpr(!((BooleanLiteralExpr) expr).getValue()); // Inverte `true` ↔ `false`
        }
        if (expr instanceof UnaryExpr) {
            UnaryExpr unaryExpr = (UnaryExpr) expr;
            if (unaryExpr.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
                mutationApplied.set(true);
                return unaryExpr.getExpression(); // Remove negação `!`
            } else {
                mutationApplied.set(true);
                return new UnaryExpr(unaryExpr.getExpression(), UnaryExpr.Operator.LOGICAL_COMPLEMENT); // Adiciona `!`
            }
        }
        return expr;
    }

    /**
     * Muta expressões aritméticas (ex: troca `+` ↔ `-`).
     */
    private static Expression mutateExpressionAritimetic(Expression expr, AtomicBoolean mutationApplied) {
        if (expr instanceof BinaryExpr) {
            BinaryExpr binExpr = (BinaryExpr) expr;
            if (binExpr.getOperator() == BinaryExpr.Operator.PLUS) {
                boolean leftIsString = binExpr.getLeft().isStringLiteralExpr();
                boolean rightIsString = binExpr.getRight().isStringLiteralExpr();

                if (leftIsString || rightIsString) {
                    System.out.println("This is a string concatenation, skipping mutation.");
                } else {
                    mutationApplied.set(true);
                    return new BinaryExpr(binExpr.getLeft(), binExpr.getRight(), BinaryExpr.Operator.MINUS); // Change `+` → `-`
                }
            }
            if (binExpr.getOperator() == BinaryExpr.Operator.MINUS) {
                mutationApplied.set(true);
                return new BinaryExpr(binExpr.getLeft(), binExpr.getRight(), BinaryExpr.Operator.PLUS); // Troca `-` → `+`
            }
            if (binExpr.getOperator() == BinaryExpr.Operator.REMAINDER) {
                mutationApplied.set(true);
                return new BinaryExpr(binExpr.getLeft(), binExpr.getRight(), BinaryExpr.Operator.PLUS); // Troca `&` → `-`
            }
            if (binExpr.getOperator() == BinaryExpr.Operator.DIVIDE) {
                mutationApplied.set(true);
                return new BinaryExpr(binExpr.getLeft(), binExpr.getRight(), BinaryExpr.Operator.MULTIPLY); // Troca `*` → `/`
            }
            if (binExpr.getOperator() == BinaryExpr.Operator.MINUS) {
                mutationApplied.set(true);
                return new BinaryExpr(binExpr.getLeft(), binExpr.getRight(), BinaryExpr.Operator.DIVIDE); // Troca `*` → `/`
            }

        }
        return expr;
    }

    /**
     * Muta expressões lógicas (ex: troca `&&` ↔ `||`).
     */
    private static Expression mutateExpressionLogical(Expression expr, AtomicBoolean mutationApplied) {
        if (expr instanceof BinaryExpr) {
            BinaryExpr binExpr = (BinaryExpr) expr;
            if (binExpr.getOperator() == BinaryExpr.Operator.AND) {
                mutationApplied.set(true);
                return new BinaryExpr(binExpr.getLeft(), binExpr.getRight(), BinaryExpr.Operator.OR); // Troca `&&` → `||`
            }
            if (binExpr.getOperator() == BinaryExpr.Operator.OR) {
                mutationApplied.set(true);
                return new BinaryExpr(binExpr.getLeft(), binExpr.getRight(), BinaryExpr.Operator.AND); // Troca `||` → `&&`
            }
            if (binExpr.getOperator() == BinaryExpr.Operator.XOR) {
                mutationApplied.set(true);
                return new BinaryExpr(binExpr.getLeft(), binExpr.getRight(), BinaryExpr.Operator.EQUALS);
            }
            if (binExpr.getOperator() == BinaryExpr.Operator.BINARY_OR) {
                mutationApplied.set(true);
                return new BinaryExpr(binExpr.getLeft(), binExpr.getRight(), BinaryExpr.Operator.BINARY_AND);
            }
            if (binExpr.getOperator() == BinaryExpr.Operator.BINARY_AND) {
                mutationApplied.set(true);
                return new BinaryExpr(binExpr.getLeft(), binExpr.getRight(), BinaryExpr.Operator.BINARY_OR);
            }
        }
        return expr;
    }

    /**
     * Muta operadores relacionais (ex: troca `>` ↔ `<` e `>=` ↔ `<=`).
     */
    private static Expression mutateExpressionRelational(Expression expr, AtomicBoolean mutationApplied) {
        if (expr instanceof BinaryExpr) {
            BinaryExpr binExpr = (BinaryExpr) expr;
            if (binExpr.getOperator() == BinaryExpr.Operator.GREATER) {
                mutationApplied.set(true);
                return new BinaryExpr(binExpr.getLeft(), binExpr.getRight(), BinaryExpr.Operator.LESS_EQUALS); // Troca `>` → `<=`
            }
            if (binExpr.getOperator() == BinaryExpr.Operator.LESS) {
                mutationApplied.set(true);
                return new BinaryExpr(binExpr.getLeft(), binExpr.getRight(), BinaryExpr.Operator.GREATER_EQUALS); // Troca `<` → `>=`
            }
            if (binExpr.getOperator() == BinaryExpr.Operator.GREATER_EQUALS) {
                mutationApplied.set(true);
                return new BinaryExpr(binExpr.getLeft(), binExpr.getRight(), BinaryExpr.Operator.LESS); // Troca `>=` → `<`
            }
            if (binExpr.getOperator() == BinaryExpr.Operator.LESS_EQUALS) {
                mutationApplied.set(true);
                return new BinaryExpr(binExpr.getLeft(), binExpr.getRight(), BinaryExpr.Operator.GREATER); // Troca `<=` → `>`
            }
            if (binExpr.getOperator() == BinaryExpr.Operator.EQUALS) {
                mutationApplied.set(true);
                return new BinaryExpr(binExpr.getLeft(), binExpr.getRight(), BinaryExpr.Operator.NOT_EQUALS); // Troca `==` → `!=`
            }
            if (binExpr.getOperator() == BinaryExpr.Operator.NOT_EQUALS) {
                mutationApplied.set(true);
                return new BinaryExpr(binExpr.getLeft(), binExpr.getRight(), BinaryExpr.Operator.EQUALS); // Troca `!=` → `==`
            }
        }
        return expr;
    }
}


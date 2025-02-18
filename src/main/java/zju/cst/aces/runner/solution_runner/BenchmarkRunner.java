package zju.cst.aces.runner.solution_runner;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.junit.Test;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.ChatGenerator;
import zju.cst.aces.api.impl.PromptConstructorImpl;
import zju.cst.aces.api.impl.RepairImpl;
import zju.cst.aces.api.impl.ValidatorImpl;
import zju.cst.aces.api.phase.Phase;
import zju.cst.aces.api.phase.PhaseImpl;
import zju.cst.aces.dto.*;
import zju.cst.aces.prompt.template.PromptTemplate;
import zju.cst.aces.runner.AbstractRunner;
import zju.cst.aces.runner.MethodRunner;
import zju.cst.aces.util.CodeExtractor;
import zju.cst.aces.util.chattester.TesterValidator;

import zju.cst.aces.util.TestProcessor;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import zju.cst.aces.util.MutationOperatorUtil;

import java.io.File;
import java.io.IOException;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


public class BenchmarkRunner extends MethodRunner {
    int totalCorrectionsCount = 0; // Initialize total corrections counter

    public BenchmarkRunner(Config config, String fullClassName, MethodInfo methodInfo) throws IOException {
        super(config, fullClassName, methodInfo);
    }

    /**
     * Main process of CHATTESTER, including:
     * 1. Generate intention for focal method, then
     * 2. Use intention and focal context to generate test, and
     * 3. Iteratively repair the test until it passes.
     * @param num
     * @return If the generation process is successful
     */
    @Override
    public boolean startRounds(final int num) throws IOException {
        // If config norepeat
        // Check if exists files in path with  className + separator + methodInfo.methodName + separator
        //                + classInfo.methodSigs.get(methodInfo.methodSignature) + separator
        //check if method used is the same signature
        //check if benchmark_file has this file
        //If all yes return true

        String testName = className + separator + methodInfo.methodName + separator
                + classInfo.methodSigs.get(methodInfo.methodSignature) + separator + num + separator + "Test";
        String fullTestName = fullClassName + separator + methodInfo.methodName + separator
                + classInfo.methodSigs.get(methodInfo.methodSignature) + separator + num + separator + "Test";
         config.getLogger().info("\n==========================\n[BENCHMARK] Generating test for method < "
                + methodInfo.methodName + " > number " + num + "...\n");

        config.setValidator(new TesterValidator(config.getTestOutput(), config.getCompileOutputPath(),  config.getProject().getBasedir().toPath().resolve("target"), config.getClassPaths())); // 设置chattester的专属验证器

        Phase phase = PhaseImpl.createPhase(config);

        ChatGenerator generator = new ChatGenerator(config);
//        PromptConstructorImpl pc = new PromptConstructorImpl(config);
        PromptConstructorImpl pc = phase.generatePrompt(classInfo, methodInfo,num);
        RepairImpl repair = new RepairImpl(config, pc);
        //prompt generation里面的
        pc.setFullTestName(fullTestName);
        pc.setTestName(testName);
        if(num==0){
            totalCorrectionsCount = 0;
        }
        PromptInfo promptInfo = pc.getPromptInfo();
        promptInfo.setFullTestName(fullTestName);
        Path savePath = config.getTestOutput().resolve(fullTestName.replace(".", File.separator) + ".java");
        if(config.onlyUpdateClass){
            if(isClassInCSV(config.getBenchMarkCsv(),classInfo.getFullClassName())){
                System.out.println("[BENCHMARK] Class " + classInfo.getFullClassName() + " has already generated!");
                return true;
            }
        }
        promptInfo.setTestPath(savePath);

        int errorNum = Integer.MAX_VALUE;
        int invalidRefinementCount = 0;
        config.useExtra = true;
        for (int rounds = 0; rounds < config.getMaxRounds(); rounds++) {
            promptInfo.addRecord(new RoundRecord(rounds));
            RoundRecord record = promptInfo.getRecords().get(rounds);
            record.setAttempt(num);
            List<ChatMessage> prompt;
            PromptTemplate pt = this.promptGenerator.promptTemplate;
            pt.buildDataModel(config, promptInfo);

            if (rounds == 0) {
                // generate method intention
                if(config.useIntention) {
                    config.getLogger().info("Creating intention for method < " + methodInfo.methodName + " > ...");
                    List<ChatMessage> intentionPrompt = this.promptGenerator.generateMessages(promptInfo, "CHATTESTER");
                    config.useExtra = false;
                    ChatResponse response = ChatGenerator.chat(config, intentionPrompt);
                    String intention = ChatGenerator.getContentByResponse(response);
                    // set intention in user prompt
                    prompt = promptGenerator.generateMessages(promptInfo, "CHATTESTER");
                    ChatMessage userChatMessage = prompt.get(1);
                    String oldContent = userChatMessage.getContent();
                    int lastBraceIndex = oldContent.lastIndexOf("}");
                    userChatMessage.setContent(
                            new StringBuilder(oldContent).insert(lastBraceIndex + 1, "\n//Method intention\n" + intention).toString()
                    );
                }
                else{
                    config.useExtra = false;
                    prompt = promptGenerator.generateMessages(promptInfo, "CHATTESTER");
                }

                 config.getLogger().info("Generating test for method < " + methodInfo.methodName + " > round " + rounds + " ...");
            } else if (promptInfo.getErrorMsg() != null) {
                totalCorrectionsCount++; // Increment the corrections counter
                assert(!promptInfo.getErrorMsg().getErrorMessage().isEmpty());
                if (promptInfo.getErrorMsg().getErrorMessage().size() >= errorNum) { //todo 这里errorNum为int max,基本触发不了吧
                    invalidRefinementCount++;
                    if (invalidRefinementCount >= config.maxInvalidRefinementCount) {
                         config.getLogger().info("Exceeding maximum invalid refinement count, break.");
                        System.out.println("Too many fixes");
                        break;
                    }
                }
                errorNum = promptInfo.getErrorMsg().getErrorMessage().size();
                // iterate repair process
                config.getLogger().info("Fixing test for method < " + methodInfo.methodName + " > round " + rounds + " ...");
                prompt = promptGenerator.generateMessages(promptInfo, "CHATTESTER");
                TestMessage errorMsg = promptInfo.getErrorMsg();
                if (errorMsg.getErrorType().equals(TestMessage.ErrorType.COMPILE_ERROR)) {
                    List<CompilerError> compilerErrors = new ArrayList<>();
                    for (String error : errorMsg.getErrorMessage()) {
                        compilerErrors.addAll(parseCompilerErrors(error));
                    }
                    Set<String> classInError = new HashSet<>();
                    Map<String, String> methodInError = new HashMap<>();
                    for (CompilerError error : compilerErrors) {
                        if (error.symbolType != null && error.symbolType.equals("class")) {
                            classInError.add(error.symbolName);
                        } else if (error.symbolType != null && error.symbolType.equals("method")) {
                            methodInError.put(error.symbolName, error.variableType);
                        }
                    }

                    String repairPrompt = prompt.get(0).getContent();
                    StringBuilder deps = new StringBuilder();

                    for (String className : classInError) {
                        ClassInfo depInfo = AbstractRunner.getClassInfo(config, className);
                        if (depInfo != null) {
                            deps.append("// ").append(className).append(" class\n");
                            deps.append(depInfo.getClassSignature()).append("{\n");
                            deps.append(joinLines(depInfo.getConstructorSigs())).append("\n}");
                        }
                    }
                    for (String methodName : methodInError.keySet()) {
                        String methodType = methodInError.get(methodName);
                        if (deps.toString().contains(methodType)) {
                            continue;
                        }
                        ClassInfo typeInfo = AbstractRunner.getClassInfo(config, methodType);
                        deps.append("// ").append(methodType).append(" class\n");
                        deps.append(typeInfo.getClassSignature()).append("{\n");
                        MethodInfo depInfo = null;
                        for (String mSig : typeInfo.getMethodSigs().keySet()) {
                            if (mSig.split("\\(")[0].equals(methodName.split("\\(")[0])) {
                                depInfo = AbstractRunner.getMethodInfo(config, typeInfo, mSig);
                                if (depInfo != null) {
                                    deps.append(depInfo.methodSignature).append(";\n");
                                }
                            }
                        }
                        if (depInfo == null) {
                            deps.append(joinLines(typeInfo.getMethodsBrief()));
                        }
                        deps.append("}");
                    }

                    if (!deps.toString().isEmpty()) {
//                         config.getLogger().info("==================================================");
//                         config.getLogger().info("[CHATTESTER Deps in Repair Process]: \n" + deps);
//                         config.getLogger().info("==================================================");
                        int lastBraceIndex = repairPrompt.lastIndexOf("}");
                        prompt.get(0).setContent(
                                new StringBuilder(repairPrompt).insert(lastBraceIndex + 1, deps).toString()
                        );
                    }
                }
            } else {
                prompt = promptGenerator.generateMessages(promptInfo, "CHATTESTER");
            }

            // start generate test
            String code = generateTest(prompt, record);
            if (!record.isHasCode()) {
                continue;
            }

            if (CodeExtractor.isTestMethod(code)) {
                TestSkeleton skeleton = new TestSkeleton(promptInfo); // test skeleton to wrap a test method
                code = skeleton.build(code);
            } else {
                code = repair.ruleBasedRepair(code);
            }
            promptInfo.setUnitTest(code);

            record.setCode(code);
            repair.LLMBasedRepair(code, record.getRound(),false);
            if (repair.isSuccess()) {
                record.setHasError(false);
                exportRecord(promptInfo, classInfo, record.getAttempt());
                //Debug
                //System.out.println("Class fullname:"+classInfo.fullClassName+" method:"+methodInfo.methodName+" signature:"+methodInfo.methodSignature);

                //Mutations
                int tests = 0;
                int[] killedMutations = new int[6]; // {null, var, bool, arith, logic, rela}
                TestProcessor testProcessor = new TestProcessor(fullTestName);
                String finalCode = promptInfo.getUnitTest();

                if (rounds >= 1) {
                    finalCode = testProcessor.addCorrectTest(promptInfo);
                }
                finalCode = MutationOperatorUtil.changeClassName(finalCode, className, className + "_mutated");
                String[] mutationTypes = {"null", "variable", "boolean", "arithmetic", "logic", "relation"};

                int[] aux;

                for (int i = 0; i < 6; i++) {
                    try {
                        aux = runSingleMutationTest(mutationTypes[i], i,
                                finalCode, fullTestName, promptInfo, testProcessor, config, className, false);
                        if (aux[0] == -1) {
                            String finalCodeMutated = MutationOperatorUtil.changeMethodName(finalCode, methodInfo.methodName, methodInfo.methodName + "_mutated");
                            aux = runSingleMutationTest(mutationTypes[i], i,
                                    finalCodeMutated, fullTestName, promptInfo, testProcessor, config, className, true);

                        }
                        if (aux[0] > 0) {
                            killedMutations[i] = aux[1];
                            if (tests < aux[0]) {
                                tests = aux[0];
                            }
                        } else {
                            killedMutations[i] = -1;
                        }
                    }catch (Exception e) {
                        System.out.println(e.getMessage());
                        killedMutations[i] = -1;
                    }
                }

                writeBenchmarkResult(savePath.toString(), rounds + 1 + num * config.getMaxRounds(),
                        totalCorrectionsCount, true, tests, killedMutations);

                return true;
            }
            record.setHasError(true);
            record.setErrorMsg(promptInfo.getErrorMsg());
        }
        if(num>=(config.getTestNumber()-1)) {
            writeBenchmarkResult(// method name
                    savePath.toString(),                          // file path
                    config.getMaxRounds() + num * config.getMaxRounds(),                                   // number of interactions (rounds)
                    totalCorrectionsCount,                       // number of corrections
                    false,0,null                                       // result (successful test)
            );
        }
        exportRecord(pc.getPromptInfo(), classInfo, num);
        return false;
    }

    public String generateTest(List<ChatMessage> prompt, RoundRecord record) {

        if (MethodRunner.isExceedMaxTokens(config.getMaxPromptTokens(), prompt)) {
            config.getLogger().error("Exceed max prompt tokens: " + methodInfo.methodName + " Skipped.");
            record.setPromptToken(-1);
            record.setHasCode(false);
            return "";
        }
        config.getLogger().debug("[Prompt]:\n" + prompt);

        ChatResponse response = ChatGenerator.chat(config, prompt);
        String content = ChatGenerator.getContentByResponse(response);
        config.getLogger().debug("[Response]:\n" + content);
        String code = ChatGenerator.extractCodeByContent(content);
        record.setPromptToken(response.getUsage().getPromptTokens());
        record.setResponseToken(response.getUsage().getCompletionTokens());
        record.setPrompt(prompt);
        record.setResponse(content);
        if (code.isEmpty()) {
            config.getLogger().info("Test for method < " + methodInfo.methodName + " > extract code failed");
            record.setHasCode(false);
            return "";
        }
        record.setHasCode(true);
        return code;
    }

    public static class CompilerError {
        public String testName;
        public int lineNumber;
        public String symbolType;
        public String symbolName;
        public String variableType;
        public String variableName;
        public String locationDetail;

        @Override
        public String toString() {
            return "ErrorLocation: " + testName + ", LineNumber: " + lineNumber
                    + ", SymbolType: " + symbolType + ", SymbolName: " + symbolName
                    + ", VariableType: " + variableType + ", VariableName: " + variableName;
        }
    }

    public static List<CompilerError> parseCompilerErrors(String errorChatMessages) {
        List<CompilerError> errors = new ArrayList<>();
        String pattern = "Error in (.+?): line (\\d+) : (cannot find symbol|找不到符号)\\n\\s+(符号|symbol):\\s+(方法|变量|类|method|variable|class) ([^\\n]+)\\n\\s+(位置|location): ([^\\n]+)";
        Pattern r = Pattern.compile(pattern);
        Matcher m = r.matcher(errorChatMessages);

        while (m.find()) {
            CompilerError error = new CompilerError();
            error.testName = m.group(1);
            error.lineNumber = Integer.parseInt(m.group(2));
            error.symbolType = m.group(5);
            error.symbolName = m.group(6).trim();

            if (error.symbolType.equals("类")) {
                error.symbolType = "class";
            } else if (error.symbolType.equals("方法")) {
                error.symbolType = "method";
            } else if (error.symbolType.equals("变量")) {
                error.symbolType = "variable";
            }

            error.locationDetail = m.group(8).trim();
            if (error.symbolType.equals("method")) {
                if (error.locationDetail.contains("类型为 ")) {
                    // 解析中文错误信息中的位置信息
                    Pattern locationPattern = Pattern.compile("类型为 (\\S+)的变量 (\\S+)");
                    Matcher locationMatcher = locationPattern.matcher(error.locationDetail);
                    if (locationMatcher.find()) {
                        error.variableType = locationMatcher.group(1);
                        error.variableName = locationMatcher.group(2);
                    }
                } else if (error.locationDetail.contains("类 ")) {
                    // 如果是类错误，我们将解析出类的全限定名
                    Pattern locationPattern = Pattern.compile("类 (\\S+)");
                    Matcher locationMatcher = locationPattern.matcher(error.locationDetail);
                    if (locationMatcher.find()) {
                        error.variableType = locationMatcher.group(1);
                        // 如果是类错误，则没有可获取的变量名
                        error.variableName = "";
                    }
                } else if (error.locationDetail.contains("class ")) {
                    // 如果是类错误，我们将解析出类的全限定名
                    Pattern locationPattern = Pattern.compile("class (\\S+)");
                    Matcher locationMatcher = locationPattern.matcher(error.locationDetail);
                    if (locationMatcher.find()) {
                        error.variableType = locationMatcher.group(1);
                        // 如果是类错误，则没有可获取的变量名
                        error.variableName = "";
                    }
                } else if (error.locationDetail.contains("variable ")) {
                    // 如果错误与变量相关，我们同时解析变量的名称和类型。
                    Pattern locationPattern = Pattern.compile("variable (\\S+) of type (\\S+)");
                    Matcher locationMatcher = locationPattern.matcher(error.locationDetail);
                    if (locationMatcher.find()) {
                        error.variableName = locationMatcher.group(1);
                        error.variableType = locationMatcher.group(2);
                    }
                }
            }

            errors.add(error);
        }

        return errors;
    }

    public static int[] runMutation(String fullTestName, PromptInfo promptInfo, String code,
                                    TestProcessor testProcessor,Config config) {
        String testName = fullTestName.substring(fullTestName.lastIndexOf(".") + 1);

        try {
            // Compile Mutated Code
            Path compilationErrorPath = config.getErrorOutput().resolve(testName + "_Mutation_CompilationError.txt");
            boolean compileResult = config.getValidator().semanticValidate(code, testName, compilationErrorPath, promptInfo);

            if (!compileResult) {
                config.getLogger().info("Mutated function < " + promptInfo.getMethodInfo().getMethodName() + " > compilation failed");
                return new int[]{-1};
            }

            if (config.isNoExecution()) {
                config.getLogger().info("Mutated function < " + promptInfo.getMethodInfo().getMethodName() + " > cannot be tested");
                return new int[]{-1};
            }

            // Execute Tests
            TestExecutionSummary summary = config.getValidator().execute(fullTestName);
            if (summary == null) {
                config.getLogger().warn("Mutated function < " + promptInfo.getMethodInfo().getMethodName() + " > execution timeout");
                return new int[]{-1};
            }

            List<String> errors = extractErrorBySummary(summary, fullTestName);
            int[] result = getTestStats(code,summary,testProcessor);
            if (summary.getTestsFailedCount() > 0 && isOnlyAssertionError(errors)) {
                if(summary.getTestsFailedCount()>result[1]){
                    result[1] = (int) summary.getTestsFailedCount();
                }
                config.getLogger().info("Mutation killed: Test failed as expected for function < " + promptInfo.getMethodInfo().getMethodName() + " >Testes/killed:"+result[0]+"/"+result[1]);
                return result;
            }
            config.getLogger().info("Mutation survived: Function < " + promptInfo.getMethodInfo().getMethodName() + " > did not cause test failure");
            return new int[]{result[0],0};
        } catch (Exception e){
            System.out.println("Mutation excepetion: " + e.getMessage());
        }
        return new int[]{-1};
    }

    public static int[] getTestStats(String result, TestExecutionSummary summary,TestProcessor testProcessor) {
        int totalMethods = 0;
        int failedTests = 0;
        JavaParser parser = new JavaParser();

        try {
            ParseResult<CompilationUnit> parseResult = parser.parse(result);
            CompilationUnit cu = parseResult.getResult().orElseThrow(() -> new NoSuchElementException("CompilationUnit not present in parse result"));
            List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
            List<Integer> errorLineNum = testProcessor.getErrorLineNum(summary);

            for (MethodDeclaration method : methods) {
                if (testProcessor.isTestCase(method)) {
                    totalMethods++;

                    if (testProcessor.containError(errorLineNum, method)) {
                        failedTests++;
                    }
                }
            }

            if (cu.findAll(MethodDeclaration.class).stream().filter(testProcessor::isTestCase).collect(Collectors.toList()).isEmpty()) {
                System.out.println("In TestProcessor.getTestStats: No test case left");
            }
        } catch (Exception e) {
            System.out.println("In TestProcessor.getTestStats: " + e);
        }
        return new int[]{totalMethods, failedTests};
    }

    public static boolean isClassInCSV(String csvFilePath, String targetClassName) {
        try (BufferedReader br = new BufferedReader(new FileReader(csvFilePath))) {
            String header = br.readLine(); // Read the first line (header)
            if (header == null) return false;

            String[] columns = header.split(","); // Assuming CSV is comma-separated
            int classIndex = -1;

            // Find the index of the "class" column
            for (int i = 0; i < columns.length; i++) {
                if (columns[i].trim().equalsIgnoreCase("class")) {
                    classIndex = i;
                    break;
                }
            }

            // If "class" column is not found, return false
            if (classIndex == -1) return false;

            // Read each line and check if the class name matches
            String line;
            while ((line = br.readLine()) != null) {
                String[] values = line.split(",");
                if (values.length > classIndex && values[classIndex].trim().equals(targetClassName)) {
                    return true;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }
    //killed_null_m, killed_var_m, killed_bool_m,
    //                                killed_aritimetic_m, killed_logic_m, killed_rela_m
    public void writeBenchmarkResult(String filePath,int numInteractions, int numCorrections, boolean result,int tests, int[] mutation_result) {
        String csvFilePath = config.getBenchMarkCsv(); // Path to the CSV file
        boolean isFileNew = !(new File(csvFilePath).exists());
        String prompt = "direct";
        if(config.useIntention){
            prompt = "intention";
        }
        String project = config.getPluginSign();
        String className = classInfo.getFullClassName();
        String methodName = methodInfo.getMethodSignature();
        String model = config.getModel().getDefaultConfig().getModelName();
        try (FileWriter writer = new FileWriter(csvFilePath, true)) {
            // Write the header if the file is new
            if (isFileNew) {
                writer.write("project,class,method,file,num_interactions," +
                        "num_corrections,result,model,test_number,mutation_null,mutation_var," +
                        "mutation_bool,mutation_aritime,mutation_logic,mutation_relat,prompt\n");
            }
            if(result){
                // Write the benchmark result as a new line
                writer.write(String.format("%s,%s,%s,%s,%d," +
                                "%d,%s,%s,%d,%d," +
                                "%d,%d,%d,%d,%d,%s\n",
                        project, className, methodName, filePath, numInteractions,
                        numCorrections,"SUCCESS", model,tests,mutation_result[0],
                        mutation_result[1],mutation_result[2],mutation_result[3],mutation_result[4],mutation_result[5],prompt));
            }
            else{
                // Write the benchmark result as a new line
                writer.write(String.format("%s,%s,%s,%s,%d," +
                                "%d,%s,%s,%d,%d," +
                                "%d,%d,%d,%d,%d,%s\n",
                        project, className, methodName, filePath, numInteractions,
                        numCorrections,"FAILURE", model,-1,-1,
                        -1,-1,-1,-1,-1,prompt));
            }
        } catch (IOException e) {
            config.getLogger().error("Failed to write benchmark in "+csvFilePath+" result to CSV: " + e.getMessage());
        }
    }

    private int[] runSingleMutationTest(String mutationType, int mutationFunction,
                                      String finalCode, String fullTestName, PromptInfo promptInfo,
                                      TestProcessor testProcessor, Config config, String className,
                                      Boolean mutate_method) {
        System.out.println("Testing " + mutationType + " mutation");
        String mutatedCode="";
        switch(mutationFunction){
            case 0:
                mutatedCode = MutationOperatorUtil.applyNullMutation(promptInfo.getClassInfo().compilationUnitCode,
                        promptInfo.getMethodInfo().methodName, className,
                        className + "_mutated",mutate_method);
                break;
            case 1:
                mutatedCode = MutationOperatorUtil.applyVariableMutation(promptInfo.getClassInfo().compilationUnitCode,
                    promptInfo.getMethodInfo().methodName, className,
                    className + "_mutated",mutate_method);
                break;
            case 2:
                mutatedCode = MutationOperatorUtil.applyOperatorMutationBoolean(promptInfo.getClassInfo().compilationUnitCode,
                    promptInfo.getMethodInfo().methodName, className,
                    className + "_mutated",mutate_method);
                break;
            case 3:
                mutatedCode = MutationOperatorUtil.applyOperatorMutationAritimetic(promptInfo.getClassInfo().compilationUnitCode,
                    promptInfo.getMethodInfo().methodName, className,
                    className + "_mutated",mutate_method);
                break;
            case 4:
                mutatedCode = MutationOperatorUtil.applyOperatorMutationLogic(promptInfo.getClassInfo().compilationUnitCode,
                    promptInfo.getMethodInfo().methodName, className,
                    className + "_mutated",mutate_method);
                break;
            case 5:
                mutatedCode = MutationOperatorUtil.applyOperatorMutationRelational(promptInfo.getClassInfo().compilationUnitCode,
                    promptInfo.getMethodInfo().methodName, className,
                    className + "_mutated",mutate_method);
                break;
        }

        if (mutatedCode.isEmpty()) {
            System.out.println("Can't perform " + mutationType + " mutation");
            return new int[]{-1};
        }
        String injectedMutation = MutationOperatorUtil.injectMutationClass(finalCode, mutatedCode);
        try {
            int[] result = runMutation(fullTestName, promptInfo, injectedMutation, testProcessor, config);
            return result;
        } catch (Exception e) {
            System.out.println(e.getMessage());
            return new int[]{-1};
        }
    }

}

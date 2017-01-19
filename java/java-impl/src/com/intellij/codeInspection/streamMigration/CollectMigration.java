/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package com.intellij.codeInspection.streamMigration;

import com.intellij.codeInspection.streamMigration.StreamApiMigrationInspection.InitializerUsageStatus;
import com.intellij.codeInspection.streamMigration.StreamApiMigrationInspection.MapOp;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ObjectUtils;
import com.siyeh.ig.psiutils.EquivalenceChecker;
import com.siyeh.ig.psiutils.ExpressionUtils;
import com.siyeh.ig.psiutils.MethodCallUtils;
import com.siyeh.ig.psiutils.VariableAccessUtils;
import one.util.streamex.EntryStream;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static com.intellij.codeInspection.streamMigration.StreamApiMigrationInspection.getInitializerUsageStatus;
import static com.intellij.codeInspection.streamMigration.StreamApiMigrationInspection.isCallOf;

/**
 * @author Tagir Valeev
 */
class CollectMigration extends BaseStreamApiMigration {
  private static final Logger LOG = Logger.getInstance(CollectMigration.class);

  static final Map<String, String> INTERMEDIATE_STEPS = EntryStream.of(
    CommonClassNames.JAVA_UTIL_ARRAY_LIST, "",
    "java.util.LinkedList", "",
    CommonClassNames.JAVA_UTIL_HASH_SET, ".distinct()",
    "java.util.LinkedHashSet", ".distinct()",
    "java.util.TreeSet", ".distinct().sorted()"
  ).toMap();

  protected CollectMigration(String methodName) {
    super(methodName);
  }

  @Nullable
  static PsiType getAddedElementType(PsiMethodCallExpression call) {
    JavaResolveResult resolveResult = call.resolveMethodGenerics();
    PsiMethod method = call.resolveMethod();
    if(method == null) return null;
    PsiParameter[] parameters = method.getParameterList().getParameters();
    if(parameters.length != 1) return null;
    return resolveResult.getSubstitutor().substitute(parameters[0].getType());
  }

  @Override
  PsiElement migrate(@NotNull Project project, @NotNull PsiStatement body, @NotNull TerminalBlock tb) {
    PsiLoopStatement loopStatement = tb.getMainLoop();
    PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
    PsiMethodCallExpression call = tb.getSingleMethodCall();
    if (call == null) return null;
    CollectTerminal terminal = extractCollectTerminal(tb);
    if (terminal == null) return null;
    String stream = tb.generate() + terminal.generateIntermediate() + terminal.generateTerminal();
    PsiElement toReplace = terminal.getElementToReplace();
    restoreComments(loopStatement, body);
    PsiElement result;
    if (toReplace != null) {
      result = toReplace.replace(factory.createExpressionFromText(stream, toReplace));
      removeLoop(loopStatement);
    }
    else {
      PsiVariable variable = terminal.getTargetVariable();
      LOG.assertTrue(variable != null);
      result = replaceInitializer(loopStatement, variable, variable.getInitializer(), stream, terminal.getStatus());
    }
    terminal.cleanUp();
    return result;
  }

  private static boolean isHashMap(PsiLocalVariable variable) {
    PsiExpression initializer = variable.getInitializer();
    LOG.assertTrue(initializer != null);
    PsiClass initializerClass = PsiUtil.resolveClassInClassTypeOnly(initializer.getType());
    PsiClass varClass = PsiUtil.resolveClassInClassTypeOnly(variable.getType());
    return initializerClass != null &&
           varClass != null &&
           CommonClassNames.JAVA_UTIL_HASH_MAP.equals(initializerClass.getQualifiedName()) &&
           CommonClassNames.JAVA_UTIL_MAP.equals(varClass.getQualifiedName());
  }

  @Nullable
  static PsiLocalVariable extractQualifierVariable(TerminalBlock tb, PsiMethodCallExpression call) {
    PsiExpression qualifierExpression = PsiUtil.skipParenthesizedExprDown(call.getMethodExpression().getQualifierExpression());
    if (!(qualifierExpression instanceof PsiReferenceExpression)) return null;
    PsiElement resolve = ((PsiReferenceExpression)qualifierExpression).resolve();
    if (!(resolve instanceof PsiLocalVariable)) return null;
    PsiLocalVariable variable = (PsiLocalVariable)resolve;
    if (tb.getVariable() != variable && VariableAccessUtils.variableIsUsed(variable, call.getArgumentList())) return null;
    return variable;
  }

  @Nullable
  static CollectTerminal extractCollectTerminal(TerminalBlock tb) {
    PsiMethodCallExpression call = tb.getSingleMethodCall();
    if (call == null) return null;
    PsiReferenceExpression methodExpression = call.getMethodExpression();
    PsiExpression qualifierExpression = methodExpression.getQualifierExpression();
    if (tb.dependsOn(qualifierExpression)) return null;

    List<BiFunction<TerminalBlock, PsiMethodCallExpression, CollectTerminal>> extractors = Arrays
      .asList(AddingTerminal::tryExtract, GroupingTerminal::tryExtract, ToMapTerminal::tryExtract, AddingAllTerminal::tryExtractAddAll);

    CollectTerminal terminal = StreamEx.of(extractors).map(extractor -> extractor.apply(tb, call)).nonNull().findFirst().orElse(null);
    if (terminal != null) {
      if (terminal.getStatus() == InitializerUsageStatus.UNKNOWN) return null;
      terminal = includePostStatements(terminal, tb.getMainLoop());
    }
    return terminal;
  }

  static CollectTerminal includePostStatements(CollectTerminal terminal, PsiLoopStatement loop) {
    List<BiFunction<CollectTerminal, PsiElement, CollectTerminal>> wrappers =
      Arrays.asList(SortingTerminal::tryWrap, ToArrayTerminal::tryWrap, NewListTerminal::tryWrap);
    PsiElement nextStatement = loop;
    while (true) {
      nextStatement = PsiTreeUtil.skipSiblingsForward(nextStatement, PsiComment.class, PsiWhiteSpace.class);
      CollectTerminal wrapped = null;
      for (BiFunction<CollectTerminal, PsiElement, CollectTerminal> wrapper : wrappers) {
        wrapped = wrapper.apply(terminal, nextStatement);
        if (wrapped != null) {
          terminal = wrapped;
          break;
        }
      }
      if (wrapped == null) {
        return terminal;
      }
    }
  }

  static boolean isUsedOutsideOf(PsiVariable collectionVariable, Collection<PsiElement> allowedParents) {
    return !ReferencesSearch.search(collectionVariable)
      .forEach(ref -> {
                 PsiElement element = ref.getElement();
                 return element == collectionVariable ||
                        allowedParents.stream().anyMatch(p -> PsiTreeUtil.isAncestor(p, element, false));
               }
      );
  }

  @Contract("null -> null")
  static String getIntermediateStepsFromInitializer(PsiLocalVariable variable) {
    if (variable == null) return null;
    PsiExpression initializer = variable.getInitializer();
    if (initializer == null) return null;
    PsiClass aClass = PsiUtil.resolveClassInClassTypeOnly(initializer.getType());
    if (aClass == null) return null;
    return INTERMEDIATE_STEPS.get(aClass.getQualifiedName());
  }

  @Contract("null -> false")
  static boolean isEmptyCollectionInitializer(PsiExpression expression) {
    if (expression instanceof PsiNewExpression) {
      PsiExpressionList argumentList = ((PsiNewExpression)expression).getArgumentList();
      return argumentList != null && argumentList.getExpressions().length == 0;
    }
    if (expression instanceof PsiMethodCallExpression) {
      PsiMethodCallExpression call = (PsiMethodCallExpression)expression;
      String name = call.getMethodExpression().getReferenceName();
      PsiExpressionList argumentList = call.getArgumentList();
      if(name != null && name.startsWith("new") && argumentList.getExpressions().length == 0) {
        PsiMethod method = call.resolveMethod();
        if(method != null && method.getParameterList().getParametersCount() == 0) {
          PsiClass aClass = method.getContainingClass();
          if(aClass != null) {
            String qualifiedName = aClass.getQualifiedName();
            if("com.google.common.collect.Maps".equals(qualifiedName) ||
               "com.google.common.collect.Lists".equals(qualifiedName) ||
               "com.google.common.collect.Sets".equals(qualifiedName)) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  abstract static class CollectTerminal {
    private final PsiLocalVariable myTargetVariable;
    private final InitializerUsageStatus myStatus;
    final PsiLoopStatement myLoop;

    protected CollectTerminal(PsiLocalVariable variable, PsiLoopStatement loop, InitializerUsageStatus status) {
      myTargetVariable = variable;
      myLoop = loop;
      myStatus = status;
    }

    @Nullable
    PsiElement getElementToReplace() { return null; }

    String getMethodName() { return "collect"; }

    PsiLocalVariable getTargetVariable() { return myTargetVariable; }

    String generateIntermediate() { return ""; }

    abstract String generateTerminal();

    StreamEx<PsiElement> usedElements() {
      return StreamEx.ofNullable(myLoop);
    }

    public InitializerUsageStatus getStatus() { return myStatus; }

    void cleanUp() {}

    boolean isTrivial() {
      return generateIntermediate().isEmpty();
    }
  }

  static class AddingTerminal extends CollectTerminal {
    final PsiType myTargetType;
    final PsiExpression myInitializer;
    final PsiVariable myElement;
    final PsiMethodCallExpression myAddCall;

    AddingTerminal(@NotNull PsiLocalVariable target,
                   PsiVariable element,
                   PsiMethodCallExpression addCall,
                   PsiLoopStatement loop,
                   InitializerUsageStatus status) {
      super(target, loop, isEmptyCollectionInitializer(target.getInitializer()) ? status : InitializerUsageStatus.UNKNOWN);
      myTargetType = target.getType();
      myInitializer = target.getInitializer();
      myElement = element;
      myAddCall = addCall;
    }

    AddingTerminal(@NotNull PsiType targetType,
                   PsiExpression initializer,
                   PsiVariable element,
                   PsiMethodCallExpression addCall) {
      super(null, null, InitializerUsageStatus.UNKNOWN);
      myTargetType = targetType;
      myInitializer = initializer;
      myElement = element;
      myAddCall = addCall;
    }

    PsiVariable getElementVariable() {
      return myElement;
    }

    PsiExpression getMapping() {
      return myAddCall.getArgumentList().getExpressions()[0];
    }

    @Override
    public String generateIntermediate() {
      PsiType addedType = getAddedElementType(myAddCall);
      PsiExpression mapping = getMapping();
      if (addedType == null) addedType = mapping.getType();
      return new MapOp(mapping, myElement, addedType).createReplacement();
    }

    public String generateCollector() {
      return getCollectionCollector(myInitializer, myTargetType);
    }

    @Override
    public String generateTerminal() {
      return ".collect(" + generateCollector() + ")";
    }

    @Nullable
    static AddingTerminal tryExtract(TerminalBlock tb, PsiMethodCallExpression call) {
      if (!isCallOf(call, CommonClassNames.JAVA_UTIL_COLLECTION, "add")) return null;
      PsiExpression qualifierExpression = call.getMethodExpression().getQualifierExpression();
      if (qualifierExpression == null) return null;
      PsiExpression count = tb.getCountExpression();
      PsiLocalVariable variable = extractQualifierVariable(tb, call);
      if (variable != null) {
        InitializerUsageStatus status = getInitializerUsageStatus(variable, tb.getMainLoop());
        AddingTerminal terminal = new AddingTerminal(variable, tb.getVariable(), call, tb.getMainLoop(), status);
        if (count == null) return terminal;
        // like "list.add(x); if(list.size() >= limit) break;"
        if (!(count instanceof PsiMethodCallExpression)) return null;
        PsiMethodCallExpression sizeCall = (PsiMethodCallExpression)count;
        PsiExpression sizeQualifier = sizeCall.getMethodExpression().getQualifierExpression();
        if (isCallOf(sizeCall, CommonClassNames.JAVA_UTIL_COLLECTION, "size") &&
            EquivalenceChecker.getCanonicalPsiEquivalence().expressionsAreEquivalent(sizeQualifier, qualifierExpression) &&
            InheritanceUtil.isInheritor(PsiUtil.resolveClassInClassTypeOnly(variable.getType()), CommonClassNames.JAVA_UTIL_LIST)) {
          return terminal;
        }
      }
      return null;
    }
  }

  @NotNull
  private static String getCollectionCollector(PsiExpression initializer, PsiType type) {
    String collector;
    PsiType initializerType = initializer.getType();
    PsiClassType rawType = initializerType instanceof PsiClassType ? ((PsiClassType)initializerType).rawType() : null;
    PsiClassType rawVarType = type instanceof PsiClassType ? ((PsiClassType)type).rawType() : null;
    if (rawType != null && rawVarType != null &&
        rawType.equalsToText(CommonClassNames.JAVA_UTIL_ARRAY_LIST) &&
        (rawVarType.equalsToText(CommonClassNames.JAVA_UTIL_LIST) || rawVarType.equalsToText(CommonClassNames.JAVA_UTIL_COLLECTION))) {
      collector = "toList()";
    }
    else if (rawType != null && rawVarType != null &&
             rawType.equalsToText(CommonClassNames.JAVA_UTIL_HASH_SET) &&
             (rawVarType.equalsToText(CommonClassNames.JAVA_UTIL_SET) ||
              rawVarType.equalsToText(CommonClassNames.JAVA_UTIL_COLLECTION))) {
      collector = "toSet()";
    }
    else {
      PsiExpression copy = JavaPsiFacade.getElementFactory(initializer.getProject())
        .createExpressionFromText(initializer.getText(), initializer);
      if (copy instanceof PsiNewExpression) {
        PsiExpressionList argumentList = ((PsiNewExpression)copy).getArgumentList();
        if (argumentList != null) {
          PsiExpression arg = ArrayUtil.getFirstElement(argumentList.getExpressions());
          if (arg != null) {
            arg.delete();
          }
        }
      }
      collector = "toCollection(() -> " + copy.getText() + ")";
    }
    return CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS + "." + collector;
  }

  static class AddingAllTerminal extends AddingTerminal {
    private final PsiMethodCallExpression myAddAllCall;

    AddingAllTerminal(PsiLocalVariable target,
                      PsiVariable element,
                      PsiMethodCallExpression addAllCall,
                      PsiLoopStatement loop,
                      InitializerUsageStatus status) {
      super(target, element, null, loop, status);
      myAddAllCall = addAllCall;
    }

    @Override
    public String generateIntermediate() {
      PsiType[] typeParameters = myAddAllCall.getMethodExpression().getTypeParameters();
      String generic = "";
      if(typeParameters.length == 1) {
        generic = "<"+typeParameters[0].getCanonicalText()+">";
      }
      String method = MethodCallUtils.isVarArgCall(myAddAllCall) ? CommonClassNames.JAVA_UTIL_STREAM_STREAM + "." + generic + "of"
                                                                 : CommonClassNames.JAVA_UTIL_ARRAYS + "." + generic + "stream";
      String lambda = myElement.getName() + "->" + method + "(" +
                      StreamEx.of(myAddAllCall.getArgumentList().getExpressions()).skip(1).map(PsiExpression::getText).joining(",") + ")";
      return myElement.getType() instanceof PsiPrimitiveType ?
             ".mapToObj(" + lambda + ").flatMap("+ CommonClassNames.JAVA_UTIL_FUNCTION_FUNCTION+".identity())" :
             ".flatMap(" + lambda + ")";
    }

    @Nullable
    static AddingAllTerminal tryExtractAddAll(TerminalBlock tb, PsiMethodCallExpression call) {
      if(tb.getCountExpression() != null ||
         !MethodCallUtils.isCallToStaticMethod(call, CommonClassNames.JAVA_UTIL_COLLECTIONS, "addAll", 2)) {
        return null;
      }
      PsiExpression[] args = call.getArgumentList().getExpressions();
      if(args.length < 2) return null;
      PsiReferenceExpression collectionReference = ObjectUtils.tryCast(args[0], PsiReferenceExpression.class);
      if (collectionReference == null || tb.dependsOn(collectionReference)) return null;
      PsiLocalVariable target = ObjectUtils.tryCast(collectionReference.resolve(), PsiLocalVariable.class);
      if (target == null || StreamEx.of(args).skip(1).anyMatch(arg -> VariableAccessUtils.variableIsUsed(target, arg))) return null;
      InitializerUsageStatus status = getInitializerUsageStatus(target, tb.getMainLoop());
      return new AddingAllTerminal(target, tb.getVariable(), call, tb.getMainLoop(), status);
    }
  }

  static class GroupingTerminal extends CollectTerminal {
    private final AddingTerminal myDownstream;
    private final PsiExpression myKeyExpression;

    GroupingTerminal(AddingTerminal downstream,
                     PsiLocalVariable target,
                     PsiExpression expression,
                     InitializerUsageStatus status) {
      super(target, downstream.myLoop, status);
      myDownstream = downstream;
      myKeyExpression = expression;
    }

    @Override
    public boolean isTrivial() {
      return false;
    }

    @Override
    public String generateTerminal() {
      String downstreamCollector = myDownstream.generateCollector();
      PsiVariable elementVariable = myDownstream.getElementVariable();
      if (!ExpressionUtils.isReferenceTo(myDownstream.getMapping(), myDownstream.getElementVariable())) {
        downstreamCollector = CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS + ".mapping(" +
                              myDownstream.getElementVariable().getName() + "->" + myDownstream.getMapping().getText() + "," +
                              downstreamCollector + ")";
      }
      StringBuilder builder = new StringBuilder();
      builder.append(".collect(" + CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS + ".groupingBy(")
        .append(LambdaUtil.createLambda(elementVariable, myKeyExpression));
      PsiExpression initializer = getTargetVariable().getInitializer();
      LOG.assertTrue(initializer != null);
      if (!isHashMap(getTargetVariable())) {
        builder.append(",()->").append(initializer.getText()).append(",").append(downstreamCollector);
      }
      else if (!(CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS + "." + "toList()").equals(downstreamCollector)) {
        builder.append(",").append(downstreamCollector);
      }
      builder.append("))");
      return builder.toString();
    }

    @Nullable
    public static GroupingTerminal tryExtract(TerminalBlock tb, PsiMethodCallExpression call) {
      if (!isCallOf(call, CommonClassNames.JAVA_UTIL_COLLECTION, "add")) return null;
      PsiReferenceExpression methodExpression = call.getMethodExpression();
      PsiExpression qualifierExpression = methodExpression.getQualifierExpression();

      if (qualifierExpression instanceof PsiMethodCallExpression && tb.getCountExpression() == null) {
        PsiMethodCallExpression qualifierCall = (PsiMethodCallExpression)qualifierExpression;
        if (isCallOf(qualifierCall, CommonClassNames.JAVA_UTIL_MAP, "computeIfAbsent")) {
          PsiExpression[] args = qualifierCall.getArgumentList().getExpressions();
          if (args.length != 2 || !(args[1] instanceof PsiLambdaExpression)) return null;
          PsiLambdaExpression lambda = (PsiLambdaExpression)args[1];
          PsiExpression body = LambdaUtil.extractSingleExpressionFromBody(lambda.getBody());
          if (isEmptyCollectionInitializer(body)) {
            PsiLocalVariable variable = extractQualifierVariable(tb, qualifierCall);
            if (variable != null && isEmptyCollectionInitializer(variable.getInitializer())) {
              PsiType mapType = variable.getType();
              PsiType valueType = PsiUtil.substituteTypeParameter(mapType, CommonClassNames.JAVA_UTIL_MAP, 1, false);
              if (valueType == null) return null;
              AddingTerminal adding = new AddingTerminal(valueType, body, tb.getVariable(), call);
              InitializerUsageStatus status = getInitializerUsageStatus(variable, tb.getMainLoop());
              return new GroupingTerminal(adding, variable, args[0], status);
            }
          }
        }
      }
      return null;
    }
  }

  static class ToMapTerminal extends CollectTerminal {
    private final PsiMethodCallExpression myMapUpdateCall;
    private final PsiVariable myElementVariable;

    ToMapTerminal(PsiMethodCallExpression call,
                  PsiVariable elementVariable,
                  PsiLocalVariable variable,
                  PsiLoopStatement loop,
                  InitializerUsageStatus status) {
      super(variable, loop, status);
      myMapUpdateCall = call;
      myElementVariable = elementVariable;
    }

    @Override
    public String generateTerminal() {
      PsiExpression[] args = myMapUpdateCall.getArgumentList().getExpressions();
      LOG.assertTrue(args.length >= 2);
      String methodName = myMapUpdateCall.getMethodExpression().getReferenceName();
      LOG.assertTrue(methodName != null);
      Project project = myMapUpdateCall.getProject();
      String merger;
      JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(project);
      String aVar = codeStyleManager.suggestUniqueVariableName("a", myMapUpdateCall, true);
      String bVar = codeStyleManager.suggestUniqueVariableName("b", myMapUpdateCall, true);
      switch (methodName) {
        case "put":
          merger = "(" + aVar + "," + bVar + ")->" + bVar;
          break;
        case "putIfAbsent":
          merger = "(" + aVar + "," + bVar + ")->" + aVar;
          break;
        case "merge":
          LOG.assertTrue(args.length == 3);
          merger = args[2].getText();
          break;
        default:
          return null;
      }
      StringBuilder collector = new StringBuilder(".collect(" + CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS + ".toMap(");
      collector.append(LambdaUtil.createLambda(myElementVariable, args[0])).append(',')
        .append(LambdaUtil.createLambda(myElementVariable, args[1])).append(',')
        .append(merger);
      PsiExpression initializer = getTargetVariable().getInitializer();
      LOG.assertTrue(initializer != null);
      if (!isHashMap(getTargetVariable())) {
        collector.append(",()->").append(initializer.getText());
      }
      collector.append("))");
      return collector.toString();
    }

    @Nullable
    static ToMapTerminal tryExtract(TerminalBlock tb, PsiMethodCallExpression call) {
      if (tb.getCountExpression() != null ||
          !isCallOf(call, CommonClassNames.JAVA_UTIL_MAP, "merge", "put", "putIfAbsent")) {
        return null;
      }
      PsiLocalVariable variable = extractQualifierVariable(tb, call);
      if (variable == null || !isEmptyCollectionInitializer(variable.getInitializer())) return null;
      InitializerUsageStatus status = getInitializerUsageStatus(variable, tb.getMainLoop());
      return new ToMapTerminal(call, tb.getVariable(), variable, tb.getMainLoop(), status);
    }
  }

  static class SortingTerminal extends CollectTerminal {
    private final CollectTerminal myDownstream;
    private final PsiExpression myComparator;
    private final PsiStatement myStatement;

    SortingTerminal(CollectTerminal downstream, PsiStatement statement, PsiExpression comparator) {
      super(downstream.getTargetVariable(), downstream.myLoop, downstream.getStatus());
      myDownstream = downstream;
      myStatement = statement;
      myComparator = comparator;
    }

    @Override
    public String getMethodName() {
      return myDownstream.getMethodName();
    }

    @Override
    public String generateIntermediate() {
      return myDownstream.generateIntermediate() + ".sorted("
             + (myComparator == null ? "" : myComparator.getText()) + ")";
    }

    @Override
    public String generateTerminal() {
      return myDownstream.generateTerminal();
    }

    @Override
    StreamEx<PsiElement> usedElements() {
      return myDownstream.usedElements().append(myStatement);
    }

    @Override
    public void cleanUp() {
      myDownstream.cleanUp();
      myStatement.delete();
    }

    @Nullable
    public static CollectTerminal tryWrap(CollectTerminal terminal, PsiElement element) {
      PsiVariable containerVariable = terminal.getTargetVariable();
      if (containerVariable == null || !(element instanceof PsiExpressionStatement)) return null;
      PsiExpression expression = ((PsiExpressionStatement)element).getExpression();
      if (!(expression instanceof PsiMethodCallExpression)) return null;
      PsiMethodCallExpression methodCall = (PsiMethodCallExpression)expression;
      PsiReferenceExpression methodExpression = methodCall.getMethodExpression();
      if (!"sort".equals(methodExpression.getReferenceName())) return null;
      PsiMethod method = methodCall.resolveMethod();
      if (method == null) return null;
      PsiClass containingClass = method.getContainingClass();
      if (containingClass == null) return null;
      PsiExpression containerExpression = null;
      PsiExpression comparatorExpression = null;
      if (CommonClassNames.JAVA_UTIL_COLLECTIONS.equals(containingClass.getQualifiedName()) ||
          CommonClassNames.JAVA_UTIL_ARRAYS.equals(containingClass.getQualifiedName())) {
        PsiExpression[] args = methodCall.getArgumentList().getExpressions();
        if (args.length == 1) {
          containerExpression = args[0];
        }
        else if (args.length == 2) {
          containerExpression = args[0];
          comparatorExpression = args[1];
        }
        else {
          return null;
        }
      }
      else if (InheritanceUtil.isInheritor(containingClass, CommonClassNames.JAVA_UTIL_LIST)) {
        containerExpression = methodExpression.getQualifierExpression();
        PsiExpression[] args = methodCall.getArgumentList().getExpressions();
        if (args.length != 1) return null;
        comparatorExpression = args[0];
      }
      if (!(containerExpression instanceof PsiReferenceExpression) ||
          !((PsiReferenceExpression)containerExpression).isReferenceTo(containerVariable)) {
        return null;
      }
      if (ExpressionUtils.isNullLiteral(comparatorExpression)) {
        comparatorExpression = null;
      }
      return new SortingTerminal(terminal, (PsiExpressionStatement)element, comparatorExpression);
    }
  }

  static abstract class RecreateTerminal extends CollectTerminal {
    private final CollectTerminal myUpstream;
    private final String myIntermediate;
    final PsiExpression myCreateExpression;

    RecreateTerminal(CollectTerminal upstream, PsiLocalVariable variable, String intermediate, PsiExpression createExpression) {
      super(variable, null, InitializerUsageStatus.DECLARED_JUST_BEFORE);
      myUpstream = upstream;
      myIntermediate = intermediate;
      myCreateExpression = createExpression;
    }

    @Override
    public boolean isTrivial() {
      return false;
    }

    @Nullable
    @Override
    public PsiElement getElementToReplace() {
      return getTargetVariable() == null ? myCreateExpression : null;
    }

    @Override
    public String generateIntermediate() {
      return myUpstream.generateIntermediate() + myIntermediate;
    }

    @Override
    public void cleanUp() {
      if (myUpstream.getStatus() != InitializerUsageStatus.AT_WANTED_PLACE) {
        myUpstream.getTargetVariable().delete();
      }
      myUpstream.cleanUp();
    }
  }

  static class ToArrayTerminal extends RecreateTerminal {
    private final String mySupplier;

    ToArrayTerminal(CollectTerminal upstream,
                    PsiLocalVariable variable,
                    String intermediate,
                    PsiMethodCallExpression toArrayExpression,
                    String supplier) {
      super(upstream, variable, intermediate, toArrayExpression);
      mySupplier = supplier;
    }

    @Override
    public String getMethodName() {
      return "toArray";
    }

    @Override
    public String generateTerminal() {
      return ".toArray(" + mySupplier + ")";
    }

    @Contract("_, null -> null")
    @Nullable
    public static ToArrayTerminal tryWrap(CollectTerminal terminal, PsiElement element) {
      if (terminal.getStatus() == InitializerUsageStatus.UNKNOWN) return null;
      if (!(element instanceof PsiExpressionStatement) && !(element instanceof PsiDeclarationStatement)
          && !(element instanceof PsiReturnStatement)) {
        return null;
      }
      PsiLocalVariable collectionVariable = terminal.getTargetVariable();
      String intermediateSteps = getIntermediateStepsFromInitializer(collectionVariable);
      if (intermediateSteps == null) return null;

      Collection<PsiReference> results = ReferencesSearch.search(collectionVariable, new LocalSearchScope(element)).findAll();
      // one or two usages allowed inside element: collection.toArray(new Type[collection.size()]) or collection.toArray()
      if (results.isEmpty() || results.size() > 2) return null;
      PsiMethodCallExpression toArrayCandidate = StreamEx.of(results)
        .map(usage -> ExpressionUtils.getCallForQualifier(ObjectUtils.tryCast(usage, PsiExpression.class)))
        .nonNull().findFirst().orElse(null);
      if (toArrayCandidate == null) return null;
      PsiReferenceExpression methodExpression = toArrayCandidate.getMethodExpression();
      if (!"toArray".equals(methodExpression.getReferenceName())) return null;
      if (!(PsiUtil.skipParenthesizedExprUp(toArrayCandidate.getParent()) instanceof PsiReturnStatement) &&
          isUsedOutsideOf(collectionVariable, terminal.usedElements().append(toArrayCandidate).toList())) {
        return null;
      }
      PsiLocalVariable var = ObjectUtils.tryCast(PsiUtil.skipParenthesizedExprUp(toArrayCandidate.getParent()), PsiLocalVariable.class);
      String supplier = extractSupplier(toArrayCandidate, collectionVariable);
      if (supplier == null) return null;
      return new ToArrayTerminal(terminal, var, intermediateSteps, toArrayCandidate, supplier);
    }

    @Nullable
    static String extractSupplier(PsiMethodCallExpression toArrayCandidate, PsiVariable collectionVariable) {
      // collection.toArray() or collection.toArray(new Type[0]) or collection.toArray(new Type[collection.size()]);
      PsiExpression[] args = toArrayCandidate.getArgumentList().getExpressions();
      if (args.length == 0) return "";
      if (args.length != 1 || !(args[0] instanceof PsiNewExpression)) return null;
      PsiNewExpression newArray = (PsiNewExpression)args[0];
      PsiType arrayType = newArray.getType();
      if (arrayType == null) return null;
      String name = arrayType.getCanonicalText();
      PsiExpression[] dimensions = newArray.getArrayDimensions();
      if (dimensions.length != 1) return null;
      if (ExpressionUtils.isZero(dimensions[0])) return name+"::new";
      if (!(dimensions[0] instanceof PsiMethodCallExpression)) return null;
      PsiMethodCallExpression maybeSizeCall = (PsiMethodCallExpression)dimensions[0];
      if (!isCallOf(maybeSizeCall, CommonClassNames.JAVA_UTIL_COLLECTION, "size")) return null;
      PsiExpression sizeQualifier = maybeSizeCall.getMethodExpression().getQualifierExpression();
      if (!ExpressionUtils.isReferenceTo(sizeQualifier, collectionVariable)) return null;
      return name+"::new";
    }
  }

  static class NewListTerminal extends RecreateTerminal {
    private final PsiType myResultType;

    NewListTerminal(CollectTerminal upstream,
                    PsiLocalVariable variable,
                    String intermediate,
                    PsiExpression newListExpression,
                    PsiType resultType) {
      super(upstream, variable, intermediate, newListExpression);
      myResultType = resultType;
    }

    @Override
    public String generateTerminal() {
      return ".collect(" + getCollectionCollector(myCreateExpression, myResultType) + ")";
    }

    @Nullable
    public static NewListTerminal tryWrap(CollectTerminal terminal, PsiElement element) {
      if (terminal.getStatus() == InitializerUsageStatus.UNKNOWN) return null;
      PsiLocalVariable collectionVariable = terminal.getTargetVariable();
      String intermediateSteps = getIntermediateStepsFromInitializer(collectionVariable);
      if (intermediateSteps == null) return null;

      PsiExpression candidate;
      PsiType type;
      PsiLocalVariable var = null;
      if (element instanceof PsiReturnStatement) {
        candidate = ((PsiReturnStatement)element).getReturnValue();
        type = PsiTypesUtil.getMethodReturnType(element);
      }
      else {
        PsiAssignmentExpression assignment = ExpressionUtils.getAssignment(element);
        if (assignment != null) {
          candidate = assignment.getRExpression();
          type = assignment.getType();
        }
        else if (element instanceof PsiDeclarationStatement) {
          PsiElement[] elements = ((PsiDeclarationStatement)element).getDeclaredElements();
          if (elements.length != 1 || !(elements[0] instanceof PsiLocalVariable)) return null;
          var = (PsiLocalVariable)elements[0];
          candidate = var.getInitializer();
          type = var.getType();
        }
        else {
          return null;
        }
        if (candidate != null && isUsedOutsideOf(collectionVariable, terminal.usedElements().append(element).toList())) return null;
      }
      if (!(candidate instanceof PsiNewExpression)) return null;
      PsiExpressionList argumentList = ((PsiNewExpression)candidate).getArgumentList();
      if (argumentList == null) return null;
      PsiExpression[] args = argumentList.getExpressions();
      if (args.length != 1 || !ExpressionUtils.isReferenceTo(args[0], collectionVariable)) return null;
      return new NewListTerminal(terminal, var, intermediateSteps, candidate, type);
    }
  }
}

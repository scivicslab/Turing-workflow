package com.scivicslab.turingworkflow.examples.jshell;

import jdk.jshell.JShell;
import jdk.jshell.SnippetEvent;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.CallableByActionName;
import java.util.List;

/**
 * JShellを使って数式を評価するアクター
 *
 * このクラスはCallableByActionNameインターフェースを実装することで、
 * IIActorRef<JShellCalculator>でアクター化され、ワークフローから
 * 動的に呼び出し可能になります。
 *
 * JShellを内部的に使用して、文字列として渡された数式を評価します。
 *
 * ワークフローの内部動作を理解するための教育的な例として作成されています。
 */
public class JShellCalculator implements CallableByActionName {

    private final JShell jshell;
    private String lastResult = "";

    /**
     * コンストラクタ：JShellインスタンスを初期化
     */
    public JShellCalculator() {
        this.jshell = JShell.create();
        System.out.println("[JShellCalculator] インスタンス作成 - JShell初期化完了");
    }

    /**
     * Java式を評価する（通常のビジネスロジックメソッド）
     *
     * @param expression Java式（例: "10 + 5", "Math.sqrt(16)"）
     * @return 評価結果の文字列
     */
    public String evaluate(String expression) {
        System.out.println("[JShellCalculator.evaluate()] 式を評価: " + expression);

        List<SnippetEvent> events = jshell.eval(expression);
        if (!events.isEmpty() && events.get(0).value() != null) {
            lastResult = events.get(0).value();
            System.out.println("[JShellCalculator.evaluate()] 結果: " + lastResult);
            return lastResult;
        }

        System.out.println("[JShellCalculator.evaluate()] 結果: null");
        return "null";
    }

    /**
     * 最後の計算結果を取得
     *
     * @return 最後の計算結果
     */
    public String getLastResult() {
        System.out.println("[JShellCalculator.getLastResult()] lastResult = " + lastResult);
        return lastResult;
    }

    /**
     * ワークフローから文字列ベースで呼び出すためのメソッド
     *
     * このメソッドを実装することで、XMLやYAMLのワークフロー定義から
     * 動的にメソッドを呼び出すことができるようになります。
     *
     * @param actionName アクション名（"eval"または"getLastResult"）
     * @param args 引数の文字列
     * @return アクション実行結果
     */
    @Override
    public ActionResult callByActionName(String actionName, String args) {
        System.out.println("[JShellCalculator.callByActionName()] アクション: " + actionName + ", 引数: " + args);

        try {
            switch (actionName) {
                case "eval":
                    if (args == null || args.trim().isEmpty()) {
                        return new ActionResult(false, "Expression required");
                    }
                    String result = evaluate(args.trim());
                    return new ActionResult(true, result);

                case "getLastResult":
                    String lastRes = getLastResult();
                    return new ActionResult(true, lastRes);

                default:
                    return new ActionResult(false, "Unknown action: " + actionName);
            }
        } catch (Exception e) {
            System.err.println("[JShellCalculator.callByActionName()] エラー: " + e.getMessage());
            return new ActionResult(false, "Error: " + e.getMessage());
        }
    }

    /**
     * リソースのクリーンアップ
     */
    public void close() {
        System.out.println("[JShellCalculator.close()] JShellをクローズ");
        if (jshell != null) {
            jshell.close();
        }
    }
}

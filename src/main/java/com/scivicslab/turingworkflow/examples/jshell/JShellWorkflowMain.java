package com.scivicslab.turingworkflow.examples.jshell;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;

import java.io.InputStream;

/**
 * JShellワークフロー実行のメインクラス
 *
 * このプログラムは、POJO-actorフレームワークを使用して
 * JShellによる計算をワークフローとして実行するデモです。
 *
 * 実行の流れ:
 * 1. IIActorSystemの初期化
 * 2. JShellCalculator（CallableByActionNameを実装）の作成
 * 3. JShellCalculatorActorでラップしてアクター化（IIActorRef）
 * 4. アクターシステムにJShellCalculatorアクターを登録
 * 5. Interpreterの作成
 * 6. InterpreterをActorRef<Interpreter>でアクター化してシステムに登録
 * 7. ワークフローXMLをロード
 * 8. Interpreterを使ってワークフローを実行
 * 9. 結果を表示
 * 10. リソースのクリーンアップ
 */
public class JShellWorkflowMain {

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("[メイン] JShellワークフローの実行を開始します");
        System.out.println("=".repeat(60));

        // ステップ1: IIActorSystemの初期化
        System.out.println("\n[ステップ1] IIActorSystemを初期化");
        IIActorSystem actorSystem = new IIActorSystem("jshell-system", 4);
        System.out.println("[メイン] アクターシステム作成完了");
        System.out.println("[メイン] - システム名: jshell-system");
        System.out.println("[メイン] - Work-Stealing Pool スレッド数: 4");

        // JShellCalculatorインスタンス（後でクリーンアップするために保持）
        JShellCalculator calculator = null;

        try {
            // ステップ2: JShellCalculator（CallableByActionNameを実装）を作成
            System.out.println("\n[ステップ2] JShellCalculatorインスタンスを作成");
            calculator = new JShellCalculator();
            System.out.println("[メイン] JShellCalculatorはCallableByActionNameを実装しています");

            // ステップ3: JShellCalculatorActorでラップ（IIActorRef<JShellCalculator>）
            System.out.println("\n[ステップ3] JShellCalculatorをIIActorRefでアクター化");
            JShellCalculatorActor calcActor = new JShellCalculatorActor(
                "jshellCalc",  // XMLで使用するアクター名
                calculator,     // CallableByActionNameを実装したオブジェクト
                actorSystem     // アクターシステム
            );
            System.out.println("[メイン] IIActorRef<JShellCalculator> 作成完了");

            // ステップ4: JShellCalculatorアクターをシステムに登録
            System.out.println("\n[ステップ4] JShellCalculatorアクターをシステムに登録");
            actorSystem.addIIActor(calcActor);
            System.out.println("[メイン] アクター 'jshellCalc' を登録完了");
            System.out.println("[メイン] → これでワークフローから 'jshellCalc' で参照可能");

            // ステップ5: Interpreterの作成
            System.out.println("\n[ステップ5] Interpreterを作成");
            Interpreter interpreter = new Interpreter.Builder()
                .loggerName("jshell-workflow")
                .team(actorSystem)
                .build();
            System.out.println("[メイン] Interpreter作成完了");

            // ステップ6: InterpreterをActorRef<Interpreter>でアクター化してシステムに登録
            System.out.println("\n[ステップ6] InterpreterをActorRef<Interpreter>でアクター化");
            ActorRef<Interpreter> interpreterRef = new ActorRef<Interpreter>(
                "interpreter",  // アクター名
                interpreter,    // InterpreterインスタンスをPOJOとして扱う
                actorSystem     // アクターシステム
            );
            actorSystem.addActor(interpreterRef, "interpreter");
            System.out.println("[メイン] ActorRef<Interpreter> 作成完了");
            System.out.println("[メイン] アクター 'interpreter' をシステムに登録完了");
            System.out.println("[メイン] → thread poolなどのインフラを活用可能");

            // ステップ7: ワークフローXMLをロード
            System.out.println("\n[ステップ7] ワークフローXMLをロード");
            InputStream xmlStream = JShellWorkflowMain.class
                .getClassLoader()
                .getResourceAsStream("examples/jshell-calculation.xml");

            if (xmlStream == null) {
                throw new RuntimeException("jshell-calculation.xml が見つかりません");
            }

            interpreter.readXml(xmlStream);
            System.out.println("[メイン] ワークフローロード完了");
            System.out.println("[メイン] - 読み込んだワークフロー: " +
                             interpreter.getCode().getName());

            // ステップ8: Interpreterを使ってワークフローを実行
            System.out.println("\n[ステップ8] Interpreterでワークフローを実行");
            System.out.println("-".repeat(60));

            // ワークフローを1行ずつ実行
            ActionResult result1 = interpreter.execCode();
            System.out.println("[メイン] Row 1 実行完了: " + result1.getResult());

            ActionResult result2 = interpreter.execCode();
            System.out.println("[メイン] Row 2 実行完了: " + result2.getResult());

            ActionResult result3 = interpreter.execCode();
            System.out.println("[メイン] Row 3 実行完了: " + result3.getResult());

            System.out.println("-".repeat(60));

            // ステップ9: 結果を表示
            System.out.println("\n[ステップ9] 実行結果");
            System.out.println("=".repeat(60));
            if (result3.isSuccess()) {
                System.out.println("[成功] ワークフロー実行成功!");
                System.out.println("[最終結果] " + result3.getResult());
            } else {
                System.out.println("[失敗] ワークフロー実行失敗");
                System.out.println("[エラー] " + result3.getResult());
            }
            System.out.println("=".repeat(60));

            // 詳細な実行ログ
            System.out.println("\n[詳細] ワークフロー実行の流れ:");
            System.out.println("  1. Transition 0→1: 10 + 5 を評価 → 結果: 15");
            System.out.println("  2. Transition 1→2: Math.sqrt(16) を評価 → 結果: 4.0");
            System.out.println("  3. Transition 2→end: 最終結果を取得 → 結果: 4.0");

            System.out.println("\n[設計原則]");
            System.out.println("  - JShellCalculator: CallableByActionName実装 → IIActorRef<T>でラップ");
            System.out.println("  - Interpreter: POJO → ActorRef<T>でラップ");
            System.out.println("  - すべてのアクターをシステムに登録 → thread pool活用");

        } catch (Exception e) {
            System.err.println("\n[エラー] 例外が発生しました: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // ステップ10: リソースのクリーンアップ
            System.out.println("\n[ステップ10] リソースをクリーンアップ");
            if (calculator != null) {
                calculator.close();
            }
            actorSystem.terminate();
            System.out.println("[メイン] クリーンアップ完了");
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("[メイン] プログラム終了");
        System.out.println("=".repeat(60));
    }
}

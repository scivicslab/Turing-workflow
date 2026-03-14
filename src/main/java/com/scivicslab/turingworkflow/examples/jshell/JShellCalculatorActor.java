package com.scivicslab.turingworkflow.examples.jshell;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * JShellCalculatorをアクターとしてラップするクラス
 *
 * IIActorRef<JShellCalculator>を継承し、CallableByActionNameを実装した
 * JShellCalculatorをアクターとして扱えるようにします。
 * ワークフローから呼び出されたときの橋渡しを行います。
 */
public class JShellCalculatorActor extends IIActorRef<JShellCalculator> {

    /**
     * コンストラクタ
     *
     * @param actorName アクター名（ワークフローXMLで使用する名前）
     * @param calculator JShellCalculatorインスタンス（CallableByActionNameを実装）
     * @param system アクターシステム
     */
    public JShellCalculatorActor(String actorName, JShellCalculator calculator, IIActorSystem system) {
        super(actorName, calculator, system);
        System.out.println("[JShellCalculatorActor] アクター作成: " + actorName);
    }

    /**
     * ワークフローから呼び出されるメソッド
     *
     * この実装では、単純に内部のPOJOオブジェクト（calculator）に
     * 処理を委譲しています。
     *
     * @param actionName アクション名
     * @param args 引数
     * @return アクション実行結果
     */
    @Override
    public ActionResult callByActionName(String actionName, String args) {
        System.out.println("[JShellCalculatorActor.callByActionName()] " +
                         "アクター名: " + this.getName() + ", " +
                         "アクション: " + actionName + ", " +
                         "引数: " + args);

        // 内部のPOJOオブジェクトに処理を委譲
        return this.object.callByActionName(actionName, args);
    }
}

/*
 * Copyright 2025 devteam@scivics-lab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.scivicslab.turingworkflow.examples.turing;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import java.util.concurrent.ExecutionException;

/**
 * チューリングマシンをPOJO-actorの基本機能だけで実装した例
 *
 * <p>この例では、ワークフローを使わず、ActorRefとActorSystemの
 * 基本機能だけでチューリングマシンを実行します。</p>
 *
 * <p>学習ポイント:</p>
 * <ul>
 * <li>普通のPOJOをActorRef&lt;T&gt;でアクター化</li>
 * <li>tell()とask()を使った非同期メソッド呼び出し</li>
 * <li>アクターシステムへの登録とスレッドプール活用</li>
 * <li>状態を持つアクターの管理</li>
 * </ul>
 *
 * @author devteam@scivics-lab.com
 */
public class TuringBasicActorApp {

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("[メイン] チューリングマシン（基本アクター版）開始");
        System.out.println("=".repeat(60));

        // ステップ1: ActorSystemの作成
        System.out.println("\n[ステップ1] ActorSystemを作成");
        ActorSystem system = new ActorSystem("turing-basic-system", 4);
        System.out.println("[メイン] ActorSystem作成完了");
        System.out.println("[メイン] - システム名: turing-basic-system");
        System.out.println("[メイン] - スレッド数: 4");

        try {
            // ステップ2: Turingインスタンス（普通のPOJO）を作成
            System.out.println("\n[ステップ2] Turingインスタンスを作成");
            Turing turing = new Turing();
            System.out.println("[メイン] Turingは普通のPOJOです");

            // ステップ3: ActorRef<Turing>でアクター化
            System.out.println("\n[ステップ3] TuringをActorRef<Turing>でアクター化");
            ActorRef<Turing> turingActor = new ActorRef<Turing>(
                "turing",  // アクター名
                turing,    // 普通のPOJOインスタンス
                system     // アクターシステム
            );
            System.out.println("[メイン] ActorRef<Turing> 作成完了");

            // ステップ4: アクターシステムに登録
            System.out.println("\n[ステップ4] アクターをシステムに登録");
            system.addActor(turingActor, "turing");
            System.out.println("[メイン] アクター 'turing' を登録完了");
            System.out.println("[メイン] → これでスレッドプールが活用されます");

            // ステップ5: マシンを初期化
            System.out.println("\n[ステップ5] マシンを初期化");
            turingActor.tell(t -> t.initMachine()).get();
            System.out.println("[メイン] 初期化完了");

            // ステップ6: 初期状態のテープを出力
            System.out.println("\n[ステップ6] 初期状態のテープを出力");
            turingActor.tell(t -> t.printTape()).get();

            // ステップ7: チューリングマシンのメインループ
            System.out.println("\n[ステップ7] チューリングマシンを実行（10サイクル）");
            System.out.println("-".repeat(60));

            for (int cycle = 0; cycle < 10; cycle++) {
                System.out.println("\n[サイクル " + (cycle + 1) + "]");

                // サイクルごとに "0 1 " パターンを書き込む

                // (1) "0"を書く
                turingActor.tell(t -> t.put("0")).get();
                System.out.println("  位置 " + turing.getCurrentPos() + " に '0' を書き込み");

                // (2) 右へ移動
                turingActor.tell(t -> t.move("R")).get();
                System.out.println("  右へ移動 → 位置 " + turing.getCurrentPos());

                // (3) 右へ移動（空白をスキップ）
                turingActor.tell(t -> t.move("R")).get();
                System.out.println("  右へ移動 → 位置 " + turing.getCurrentPos());

                // (4) "1"を書く
                turingActor.tell(t -> t.put("1")).get();
                System.out.println("  位置 " + turing.getCurrentPos() + " に '1' を書き込み");

                // (5) 右へ移動
                turingActor.tell(t -> t.move("R")).get();
                System.out.println("  右へ移動 → 位置 " + turing.getCurrentPos());

                // (6) 右へ移動（次のサイクルの準備）
                turingActor.tell(t -> t.move("R")).get();
                System.out.println("  右へ移動 → 位置 " + turing.getCurrentPos());

                // カウンターをインクリメント
                int count = turingActor.ask(t -> t.increment()).get();
                System.out.println("  カウンター: " + count);

                // テープの内容を出力
                turingActor.tell(t -> t.printTape()).get();
            }

            System.out.println("-".repeat(60));

            // ステップ8: 最終状態を表示
            System.out.println("\n[ステップ8] 最終状態を表示");
            System.out.println("=".repeat(60));
            String finalTape = turingActor.ask(t -> t.getTapeContent()).get();
            int finalPos = turingActor.ask(t -> t.getCurrentPos()).get();
            System.out.println("[最終テープ] " + finalTape);
            System.out.println("[最終位置] " + finalPos);
            System.out.println("=".repeat(60));

            // ステップ9: 実装の特徴を説明
            System.out.println("\n[実装の特徴]");
            System.out.println("  1. ワークフローを使わず、ActorRef<T>だけで実装");
            System.out.println("  2. tell()で非同期に状態を変更");
            System.out.println("  3. ask()で非同期に値を取得");
            System.out.println("  4. Turingインスタンスの状態がサイクル間で保持される");
            System.out.println("  5. アクターシステムのスレッドプールを活用");

        } catch (InterruptedException | ExecutionException e) {
            System.err.println("\n[エラー] 例外が発生しました: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // ステップ10: リソースのクリーンアップ
            System.out.println("\n[ステップ10] リソースをクリーンアップ");
            system.terminate();
            System.out.println("[メイン] クリーンアップ完了");
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("[メイン] プログラム終了");
        System.out.println("=".repeat(60));
    }
}

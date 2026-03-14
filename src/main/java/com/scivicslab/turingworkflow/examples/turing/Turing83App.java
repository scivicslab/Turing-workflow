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

import java.util.concurrent.ExecutionException;

import com.scivicslab.pojoactor.core.ActorRef;

/**
 * A demonstration application that implements a specific Turing machine computation.
 * This application creates a Turing machine actor and executes a defined algorithm.
 * 
 * @author devteam@scivics-lab.com
 * @version 1.0.0
 */
public class Turing83App {

    
    /**
     * Main entry point for the Turing123 application.
     * 
     * @param args command line arguments (not used)
     */
    static public void main(String[] args) {
        Turing83App obj = new Turing83App();
        obj.calc();
    }
    
    /**
     * Executes the Turing machine calculation using actor-based messaging.
     * This method implements a specific Turing machine algorithm through asynchronous message passing.
     */
    public void calc() {

        ActorRef<Turing> turingActor = new ActorRef<Turing>("p123", new Turing());

        int state = 0;
        try {
            turingActor.tell((a)->{a.initMachine();}).get();

            for (int i = 0; i<50; i++) {
                
                if (state == 0) {
                    turingActor.tell((a)->a.put("0")).get();
                    turingActor.tell((a)->a.move("R")).get();
                    state = 1;
                    continue;
                }
                
                if (state == 1) {
                    turingActor.tell((a)->a.move("R")).get();
                    state = 2;
                    continue;
                }

                if (state == 2) {
                    turingActor.tell((a)->a.put("1")).get();
                    turingActor.tell((a)->a.move("R")).get();
                    state = 3;
                    continue;                    
                }

                if (state == 3) {
                    turingActor.tell((a)->a.move("R")).get();
                    state = 0;
                    continue;                    
                }

            }

            turingActor.tell((a)->a.printTape()).get();
                
        } catch (InterruptedException | ExecutionException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        finally {
            turingActor.close();
        }
        
    }


    
}

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

/**
 * A Turing machine implementation that simulates the basic operations of a Turing machine.
 * This class provides functionality for reading, writing, and moving on an infinite tape,
 * along with position tracking and iteration counting.
 * 
 * @author devteam@scivics-lab.com
 * @version 1.0.0
 */
public class Turing {

    /** The current position of the Turing machine head on the tape. */
    int currentPos = 0;
    
    /** The iteration counter for tracking machine steps. */
    int counter = 0;
    
    /** The infinite tape for the Turing machine. */
    Tape tape = new Tape();

    /** The maximum number of iterations allowed before stopping. */
    int maxIterations = 100;


    /**
     * Returns the current position of the machine head on the tape.
     * 
     * @return the current position
     */
    public int getCurrentPos() {
        return currentPos;
    }

    /** Returns a value at the current position on the tape.
     */
    public String getCurrentValue() {
        return this.tape.getWithResizing(currentPos);
    }

    /**
     * Returns the entire content of the tape as a string.
     * 
     * @return the tape content as a string representation
     */
    public String getTapeContent() {
        return this.tape.toString();
    }



    /** Increments the iteration counter.
     *
     * @return the value of the iteration counter after update.
     */
    public int increment() {
        return ++this.counter;
    }

    
    /** Initializes the Turing machine.
     */
    public void initMachine() {
        this.currentPos = 0;
        this.counter = 0;
        this.tape = new Tape();
    }


    /**
     * Checks if the current position contains either "0" or "1".
     * 
     * @return true if the current value is "0" or "1", false otherwise
     */
    public boolean isAny() {
        return this.getCurrentValue().equals("0") || this.getCurrentValue().equals("1");
    }


    
    /** Check if the value of the current position is NONE or not.
     * 
     * @return True if the value of the current positon is None. Otherwise False.
     */
    public boolean isNone() {
        return this.getCurrentValue().equals(" ");
    }

    /** Checks if the value of the current position on the tape 
     *  matches to a given value.
     *
     *  @param value a value used for the test.
     *  @return True if the current value equals to the given value. Otherwise False.
     */
    public boolean matchCurrentValue(String value) {
        return this.getCurrentValue().equals(value);
    }

    
    /** Checks if the value of the current position on the tape 
     *  matches to a value in a given set.
     *
     *  @param  values an array of values used for the test.
     *  @return True if the current value equals to one of the given value. Otherwise false.
     */
    public boolean matchCurrentValue(String[] values) {
        boolean result = false;
        String c = this.getCurrentValue();
        for (int i=0; i<values.length; i++) {
            if (values[i].equals(c)) {
                result = true;
                break;
            }
        }
        return result;
    }


    /** Move currentPos on the tape one step left or right.
     * 
     * @param direction a string consisting of a single character that represents move direction, "L" or "R"
     */
    public void move(String direction) {
        if (direction.equalsIgnoreCase("L")) {
            this.currentPos --;
        }
        else if (direction.equalsIgnoreCase("R")) {
            this.currentPos ++;
        }
    }


    /** Puts a number at the current position of the tape.
     *
     * @param value a number written on the tape.
     */
    public void put(String value) {
        this.tape.setWithResizing(this.currentPos, value);
    }
    

    /** Prints out the content of the tape.
     */
    /**
     * Prints the current state of the tape to standard output.
     * The output includes the tape label, counter value, and tape content.
     */
    public void printTape() {
        System.out.println(String.format("%s\t%d\t%s\t%s",
                                         "TAPE",
                                         this.counter,
                                         "value",
                                         this.tape.toString()
                                         ));
        
    }


    
    /** Checks if the maximum number of iteration has been reached.
     */    
    public boolean reachMaxIterations() {
        return this.counter >= this.maxIterations;
    }
    
    

    /** Sets current position of the Turing machine.
     * 
     * @param pos new current position.
     */
    public void setCurrentPos(int pos) {
        this.currentPos = pos;
    }
    
    
}

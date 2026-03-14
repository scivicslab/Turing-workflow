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

import java.util.ArrayList;
import java.util.StringJoiner;



/**
 * An infinite tape implementation for a Turing machine.
 * This class extends ArrayList to provide a dynamic tape that can grow as needed.
 * Each cell can contain string values, with empty cells represented by spaces.
 * 
 * @author devteam@scivics-lab.com
 * @version 1.0.0
 */
public class Tape extends ArrayList<String> {

    
    // static public final int NONE = -1;
    // static public final int EPSILON = -2;
    // static public final int X = -3;
    
    /**
     * Constructs an empty tape.
     */
    public Tape() {
        super();
    }


    /**
     * Gets the value at the specified index, returning a space if the index is out of bounds.
     * This method provides safe access to tape cells without throwing exceptions.
     * 
     * @param index the position on the tape to read from
     * @return the value at the specified position, or a space if out of bounds
     */
    public String getWithResizing(int index) {
        if (index < this.size()) {
            return this.get(index);
        }
        else { // index >= this.size()
            return " ";
        }
    }

    
    /**
     * Sets the value at the specified index, automatically expanding the tape if necessary.
     * If the index is beyond the current tape size, the tape is extended with spaces.
     * 
     * @param index the position on the tape to write to
     * @param element the value to write at the specified position
     * @return the previous value at that position, or null if it's a new position
     */
    public String setWithResizing(int index, String element) {
        if (index >= this.size()) {
            for (int i=this.size(); i<=index; i++) {
                this.add(" ");
            }
        }
        return this.set(index, element);
    }

    /**
     * Returns a string representation of the entire tape contents.
     * All cells are concatenated together without separators.
     * 
     * @return the tape contents as a single string
     */
    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner("");
        for (int i=0; i<this.size(); i++) {
            joiner.add(this.get(i));
        }

        return joiner.toString();
    }
    
    
}

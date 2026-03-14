package com.scivicslab.turingworkflow.pojo;

/**
 * A test utility class for performing parallel matrix block calculations.
 * This class is used in performance and thread pool testing to demonstrate
 * how large matrix multiplications can be divided into smaller blocks
 * and computed in parallel by different actors.
 * 
 * @author devteam@scivicslab.com
 * @version 1.0.0
 */
public class MatrixCalculator {

    /** Reference to the shared matrix A */
    private double[][] matrixA;
    
    /** Reference to the shared matrix B */
    private double[][] matrixB;
    
    /** The block coordinates this actor is responsible for */
    private int startRow;
    private int endRow;
    private int startCol;
    private int endCol;
    
    /** Matrix dimensions */
    private int innerDim;

    /**
     * Initializes this calculator to work on a specific block of the result matrix.
     * This actor will calculate elements from (startRow, startCol) to (endRow-1, endCol-1).
     * 
     * @param matrixA the first matrix (shared reference)
     * @param matrixB the second matrix (shared reference) 
     * @param startRow starting row index (inclusive)
     * @param endRow ending row index (exclusive)
     * @param startCol starting column index (inclusive)
     * @param endCol ending column index (exclusive)
     * @param innerDim the inner dimension for matrix multiplication
     */
    public void initBlock(double[][] matrixA, double[][] matrixB, 
                         int startRow, int endRow, int startCol, int endCol, int innerDim) {
        this.matrixA = matrixA;
        this.matrixB = matrixB;
        this.startRow = startRow;
        this.endRow = endRow;
        this.startCol = startCol;
        this.endCol = endCol;
        this.innerDim = innerDim;
    }

    /**
     * Calculates the matrix multiplication for the assigned block.
     * Each actor computes a portion of the result matrix independently.
     * 
     * @return a 2D array containing the calculated block results
     */
    public double[][] calculateBlock() {
        int blockRows = endRow - startRow;
        int blockCols = endCol - startCol;
        double[][] blockResult = new double[blockRows][blockCols];
        
        // Calculate each element in the assigned block
        for (int i = 0; i < blockRows; i++) {
            for (int j = 0; j < blockCols; j++) {
                double sum = 0.0;
                int actualRow = startRow + i;
                int actualCol = startCol + j;
                
                // Dot product calculation for this element
                for (int k = 0; k < innerDim; k++) {
                    sum += matrixA[actualRow][k] * matrixB[k][actualCol];
                }
                
                blockResult[i][j] = sum;
            }
        }
        
        return blockResult;
    }

    /**
     * Returns the sum of all elements in the calculated block.
     * This provides a simple way to verify the calculation results.
     * 
     * @return the sum of all elements in the block
     */
    public double getBlockSum() {
        double[][] block = calculateBlock();
        double sum = 0.0;
        
        for (int i = 0; i < block.length; i++) {
            for (int j = 0; j < block[i].length; j++) {
                sum += block[i][j];
            }
        }
        
        return sum;
    }

    /**
     * Gets the block coordinates for debugging purposes.
     * 
     * @return a string representation of the block coordinates
     */
    public String getBlockInfo() {
        return String.format("Block[%d-%d, %d-%d]", startRow, endRow-1, startCol, endCol-1);
    }
}
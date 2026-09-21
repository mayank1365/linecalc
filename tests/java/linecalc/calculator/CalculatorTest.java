package linecalc.calculator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CalculatorTest {

    @Test
    void computesTheFourOperations() throws Exception {
        assertEquals(5, Calculator.evaluate(Operation.ADD, "2", "3"));
        assertEquals(6, Calculator.evaluate(Operation.SUB, "10", "4"));
        assertEquals(42, Calculator.evaluate(Operation.MUL, "6", "7"));
        assertEquals(3, Calculator.evaluate(Operation.DIV, "9", "3"));
    }

    @Test
    void divisionTruncatesTowardZero() throws Exception {
        assertEquals(3, Calculator.evaluate(Operation.DIV, "7", "2"));
        assertEquals(-3, Calculator.evaluate(Operation.DIV, "-7", "2"));
    }

    @Test
    void handlesNegativeOperands() throws Exception {
        assertEquals(-1, Calculator.evaluate(Operation.ADD, "-4", "3"));
    }

    @Test
    void rejectsDivisionByZero() {
        CalcException e = assertThrows(CalcException.class,
                () -> Calculator.evaluate(Operation.DIV, "1", "0"));
        assertEquals("division by zero", e.getMessage());
    }

    @Test
    void rejectsOperandsThatAreNotIntegers() {
        assertThrows(CalcException.class, () -> Calculator.evaluate(Operation.ADD, "x", "3"));
        assertThrows(CalcException.class, () -> Calculator.evaluate(Operation.ADD, "2.5", "3"));
        assertThrows(CalcException.class, () -> Calculator.evaluate(Operation.ADD, "", "3"));
    }

    @Test
    void rejectsMissingOperands() {
        assertThrows(CalcException.class, () -> Calculator.evaluate(Operation.ADD, null, "3"));
        assertThrows(CalcException.class, () -> Calculator.evaluate(Operation.ADD, "2", null));
    }

    @Test
    void rejectsResultsThatDoNotFit() {
        assertThrows(CalcException.class,
                () -> Calculator.evaluate(Operation.ADD, Long.toString(Long.MAX_VALUE), "1"));
        assertThrows(CalcException.class,
                () -> Calculator.evaluate(Operation.MUL, Long.toString(Long.MAX_VALUE), "2"));
        assertThrows(CalcException.class,
                () -> Calculator.evaluate(Operation.DIV, Long.toString(Long.MIN_VALUE), "-1"));
    }

    @Test
    void mapsPathsToOperations() {
        assertEquals(Operation.ADD, Operation.fromPath("/add"));
        assertEquals(Operation.DIV, Operation.fromPath("/div"));
        assertNull(Operation.fromPath("/pow"));
        assertNull(Operation.fromPath("/"));
        assertNull(Operation.fromPath("add"));
    }
}

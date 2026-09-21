package linecalc.calculator;

/**
 * A request that reached the calculator but does not describe a computation we can perform:
 * an operand that is not an integer, a division by zero, a result that does not fit.
 *
 * <p>These all map to 400 — the client's bytes were framed correctly, they just said
 * something nonsensical.
 */
public class CalcException extends Exception {

    public CalcException(String message) {
        super(message);
    }
}

package linecalc.calculator;

/**
 * The arithmetic is not the point of this assignment, so it lives behind one small pure
 * function with no knowledge of sockets, headers or status codes.
 */
public final class Calculator {

    private Calculator() {
    }

    /**
     * Evaluates {@code op} over two operands still in their raw query-string form.
     *
     * @throws CalcException if either operand is missing or not a 64-bit integer, or if the
     *                       operation itself is undefined for those values
     */
    public static long evaluate(Operation op, String rawA, String rawB) throws CalcException {
        long a = operand("a", rawA);
        long b = operand("b", rawB);
        return op.apply(a, b);
    }

    private static long operand(String name, String raw) throws CalcException {
        if (raw == null) {
            throw new CalcException("missing operand '" + name + "'");
        }
        if (raw.isEmpty()) {
            throw new CalcException("empty operand '" + name + "'");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new CalcException("operand '" + name + "' is not an integer: " + raw);
        }
    }
}

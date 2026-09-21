package linecalc.calculator;

import java.util.Locale;

/** The entire feature set: four operations on two integers. */
public enum Operation {

    ADD("add") {
        @Override
        long compute(long a, long b) {
            return Math.addExact(a, b);
        }
    },
    SUB("sub") {
        @Override
        long compute(long a, long b) {
            return Math.subtractExact(a, b);
        }
    },
    MUL("mul") {
        @Override
        long compute(long a, long b) {
            return Math.multiplyExact(a, b);
        }
    },
    DIV("div") {
        @Override
        long compute(long a, long b) throws CalcException {
            if (b == 0) {
                throw new CalcException("division by zero");
            }
            // Long.MIN_VALUE / -1 is the one division that overflows; the rest are exact.
            if (a == Long.MIN_VALUE && b == -1) {
                throw new ArithmeticException("long overflow");
            }
            return a / b;
        }
    };

    private final String path;

    Operation(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    /** Resolves a request path such as {@code /mul} to an operation, or null if we do not have one. */
    public static Operation fromPath(String requestPath) {
        if (requestPath == null || !requestPath.startsWith("/")) {
            return null;
        }
        String name = requestPath.substring(1).toLowerCase(Locale.ROOT);
        for (Operation op : values()) {
            if (op.path.equals(name)) {
                return op;
            }
        }
        return null;
    }

    abstract long compute(long a, long b) throws CalcException;

    public long apply(long a, long b) throws CalcException {
        try {
            return compute(a, b);
        } catch (ArithmeticException e) {
            throw new CalcException(name().toLowerCase(Locale.ROOT) + " overflows a 64-bit integer");
        }
    }
}

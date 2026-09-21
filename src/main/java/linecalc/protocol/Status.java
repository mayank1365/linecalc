package linecalc.protocol;

/** The status codes both protocols use, and their reason phrases. */
public final class Status {

    public static final int OK = 200;
    public static final int BAD_REQUEST = 400;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int METHOD_NOT_ALLOWED = 405;
    public static final int URI_TOO_LONG = 414;
    public static final int PAYLOAD_TOO_LARGE = 413;
    public static final int HEADERS_TOO_LARGE = 431;
    public static final int INTERNAL_ERROR = 500;
    public static final int NOT_IMPLEMENTED = 501;
    public static final int VERSION_NOT_SUPPORTED = 505;

    private Status() {
    }

    public static String reason(int status) {
        switch (status) {
            case OK: return "OK";
            case BAD_REQUEST: return "Bad Request";
            case FORBIDDEN: return "Forbidden";
            case NOT_FOUND: return "Not Found";
            case METHOD_NOT_ALLOWED: return "Method Not Allowed";
            case PAYLOAD_TOO_LARGE: return "Payload Too Large";
            case URI_TOO_LONG: return "URI Too Long";
            case HEADERS_TOO_LARGE: return "Request Header Fields Too Large";
            case INTERNAL_ERROR: return "Internal Server Error";
            case NOT_IMPLEMENTED: return "Not Implemented";
            case VERSION_NOT_SUPPORTED: return "HTTP Version Not Supported";
            default: return status < 400 ? "OK" : "Error";
        }
    }

    public static boolean isClientError(int status) {
        return status >= 400 && status < 500;
    }

    public static boolean isServerError(int status) {
        return status >= 500 && status < 600;
    }
}

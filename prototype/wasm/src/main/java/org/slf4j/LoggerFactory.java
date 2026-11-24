package org.slf4j;

/** Prototype stub (#8): INFO and up go to stdout (the browser console), DEBUG and TRACE are dropped. */
public final class LoggerFactory {

    private LoggerFactory() {
    }

    public static Logger getLogger(Class<?> type) {
        return new ConsoleLogger(type.getSimpleName());
    }

    private record ConsoleLogger(String name) implements Logger {
        public boolean isDebugEnabled() { return false; }
        public void trace(String msg) { }
        public void trace(String format, Object arg) { }
        public void trace(String format, Object arg1, Object arg2) { }
        public void trace(String format, Object... args) { }
        public void debug(String msg) { }
        public void debug(String format, Object arg) { }
        public void debug(String format, Object arg1, Object arg2) { }
        public void debug(String format, Object... args) { }
        public void info(String msg) { print("INFO", msg); }
        public void info(String format, Object arg) { print("INFO", format, arg); }
        public void info(String format, Object arg1, Object arg2) { print("INFO", format, arg1, arg2); }
        public void info(String format, Object... args) { print("INFO", format, args); }
        public void warn(String msg) { print("WARN", msg); }
        public void warn(String format, Object arg) { print("WARN", format, arg); }
        public void warn(String format, Object arg1, Object arg2) { print("WARN", format, arg1, arg2); }
        public void warn(String format, Object... args) { print("WARN", format, args); }
        public void error(String msg) { print("ERROR", msg); }
        public void error(String format, Object arg) { print("ERROR", format, arg); }
        public void error(String format, Object arg1, Object arg2) { print("ERROR", format, arg1, arg2); }
        public void error(String format, Object... args) { print("ERROR", format, args); }

        private void print(String level, String format, Object... args) {
            StringBuilder out = new StringBuilder(level).append(' ').append(name).append(": ");
            int argIndex = 0;
            int from = 0;
            for (int at = format.indexOf("{}"); at >= 0 && argIndex < args.length; at = format.indexOf("{}", from)) {
                out.append(format, from, at).append(args[argIndex++]);
                from = at + 2;
            }
            out.append(format.substring(from));
            System.out.println(out);
        }
    }
}

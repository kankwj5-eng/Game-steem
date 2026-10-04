package com.winlator.console;

/** Short user-facing errors; the original exception remains in the diagnostic log. */
final class InstallerError {
    private InstallerError() {}
    static String userMessage(Throwable error) {
        if (error instanceof OutOfMemoryError) {
            return "Memoria insuficiente al preparar Steam. La descarga se conserva; puedes reintentar.";
        }
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "No se pudo preparar Steam. Revisa el registro y reintenta.";
        String shortMessage = message.replace('\n', ' ').replace('\r', ' ').trim();
        return shortMessage.length() > 180 ? shortMessage.substring(0, 177) + "…" : shortMessage;
    }
}

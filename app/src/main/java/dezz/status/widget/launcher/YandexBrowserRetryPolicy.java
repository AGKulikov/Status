package dezz.status.widget.launcher;

/** A delivered PLAY may only be followed by a bind without another PLAY. */
final class YandexBrowserRetryPolicy {
    enum Retry { NONE, PLAY, WARMUP }
    static Retry next(boolean noTarget, boolean unreadySessionCommand, int attempts, boolean allowed) {
        if (!allowed || attempts >= 2 || attempts < 0) return Retry.NONE;
        if (unreadySessionCommand) return Retry.WARMUP;
        return noTarget ? Retry.PLAY : Retry.NONE;
    }
}

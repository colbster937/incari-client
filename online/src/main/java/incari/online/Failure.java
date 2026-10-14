package incari.online;

/**
 * Why a request to the service didn't work, with what the screens say about it. The service's
 * errors are deliberately general (ADR 0058): a failed sign-in never says whether the username
 * exists.
 */
public enum Failure {
    /** The username and password (or code) didn't match an account; also what rate limits look like. */
    WRONG_CREDENTIALS("Wrong username or password."),
    /** The password given to confirm an action wasn't the account's. */
    WRONG_PASSWORD("That isn't your password."),
    /** The recovery code didn't match, or was used already. */
    WRONG_CODE("Wrong username or recovery code."),
    /** The username is taken. */
    TAKEN("That username is taken."),
    /** The service turned the request down for its form (a rule broken). */
    REJECTED("That wasn't accepted. Check what you typed."),
    /** Too many tries; wait before the next. */
    RATE_LIMITED("Too many tries. Wait a few minutes and try again."),
    /** The account isn't signed in on this connection any more. */
    SIGNED_OUT("You were signed out. Sign in again."),
    /** The service can't be reached, or the connection dropped before it answered. */
    OFFLINE("Can't reach Incari. Try again later."),
    /** They don't take messages (or friend requests) from the player. */
    CLOSED("They aren't taking messages from you."),
    /** This client is too old for the service. */
    UPDATE("Update Incari Client to sign in."),
    /** The service had a problem of its own. */
    UNAVAILABLE("Incari is having trouble. Try again later.");

    private final String message;

    Failure(String message) {
        this.message = message;
    }

    /** What to tell the player. */
    public String message() {
        return message;
    }
}

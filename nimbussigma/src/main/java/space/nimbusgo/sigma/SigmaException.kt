package space.nimbusgo.sigma

/** Everything that can go wrong, with the message Nimbus Cloud or Sigma gave. */
sealed class SigmaException(message: String) : Exception(message) {
    /** The API key is missing, wrong or revoked (401). */
    class Unauthorized(message: String) : SigmaException(message)
    /** This month's allowance of sheet opens is used up, or a test key's cap is reached (402). */
    class QuotaExceeded(message: String) : SigmaException(message)
    /** The document does not exist (404). */
    class NotFound(message: String) : SigmaException(message)
    /** The link has expired, or the editing session it started has ended (410). */
    class LinkExpired(message: String) : SigmaException(message)
    /** The link is not valid or was revoked (403). */
    class LinkInvalid(message: String) : SigmaException(message)
    /** Sigma's spreadsheet engine refused or failed. */
    class Engine(message: String) : SigmaException(message)
    /** The sheet is not open yet. */
    class NotReady : SigmaException("The sheet is still opening.")
    /** The editor refused (a view-only link asked to save, a bad address…). */
    class Editor(message: String) : SigmaException(message)
    /** Any other answer from the server. */
    class Server(val status: Int, message: String) : SigmaException(message.ifEmpty { "Nimbus Cloud answered $status." })
    /** The request did not reach the server. */
    class Network(message: String) : SigmaException(message)

    companion object {
        /** The error an HTTP status from Nimbus Cloud or Sigma means. */
        fun from(status: Int, message: String): SigmaException = when (status) {
            401 -> Unauthorized(message)
            402 -> QuotaExceeded(message)
            404 -> NotFound(message)
            410 -> LinkExpired(message)
            403 -> LinkInvalid(message)
            0 -> Network(message)
            else -> Server(status, message)
        }
    }
}

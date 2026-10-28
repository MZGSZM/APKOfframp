package com.mzgszm.apkofframp.core.copyparty

import java.io.IOException

/** Any copyparty reply at or above 400 that is not handled more specifically. */
class CopypartyException(val code: Int, val op: String, body: String) :
    IOException("$op failed: HTTP $code ${body.take(200).replace(Regex("<[^>]*>"), "").trim()}")

/** Login failed or the account cannot read the destination. Stops uploads to that destination. */
class AuthFailedException(message: String) : IOException(message)

/** One or more required folder permissions are missing. Letters are copyparty's: r, w, m, d. */
class PermissionMissingException(val missing: List<String>) :
    IOException("Missing copyparty permission: ${missing.joinToString(", ")}")

/** The server asked the client to hash again or wait (HTTP 422, busy source file). */
class RecheckException(message: String) : IOException(message)

/** The file already exists on the server (HTTP 409). */
class FileExistsException(message: String) : IOException(message)

/** The uploaded file does not match what was sent. */
class VerifyFailedException(message: String) : IOException(message)

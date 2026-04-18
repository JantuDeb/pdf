package com.thestudypath.pdf

class PdfPasswordRequiredException(
    val isIncorrectPassword: Boolean = false,
    message: String = if (isIncorrectPassword) {
        "Incorrect PDF password"
    } else {
        "This PDF is password protected"
    }
) : IllegalStateException(message)


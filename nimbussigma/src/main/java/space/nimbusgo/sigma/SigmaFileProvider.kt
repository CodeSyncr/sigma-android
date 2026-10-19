package space.nimbusgo.sigma

import androidx.core.content.FileProvider

/** The library's own provider, so it never clashes with the app's. */
class SigmaFileProvider : FileProvider()

package app.tiviate

import android.content.Context
import app.tiviate.data.db.TiviateDatabase

/** Hand-wired app-wide dependencies. Created once by [TiviateApp]; everything is lazy so startup stays cheap. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val db: TiviateDatabase by lazy { TiviateDatabase.create(appContext) }
}

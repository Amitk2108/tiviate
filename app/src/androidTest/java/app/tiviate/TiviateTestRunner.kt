package app.tiviate

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import app.tiviate.ui.FormFactor
import app.tiviate.ui.forcedFormFactor

/**
 * The TV tests also run on a phone emulator: they get the TV layout (landscape, remote keys) unless the
 * run asks for the phone screens with -Pandroid.testInstrumentationRunnerArguments.phone=true.
 */
class TiviateTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        if (arguments.getString("phone") != "true") forcedFormFactor = FormFactor.TV
        super.onCreate(arguments)
    }
}

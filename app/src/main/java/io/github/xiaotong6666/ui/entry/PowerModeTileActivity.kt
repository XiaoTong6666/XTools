package io.github.xiaotong6666.ui.entry

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.github.xiaotong6666.tools.R
import io.github.xiaotong6666.ui.host.FeatureHostActivity

class PowerModeTileActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startActivity(
            FeatureHostActivity.createIntent(
                this,
                FeatureHostActivity.ROUTE_POWER_TILE,
                getString(R.string.route_title_power_mode_tile),
            ),
        )
        finish()
    }
}

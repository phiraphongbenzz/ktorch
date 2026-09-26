package com.phiraphongbenzz.ktorch.internal.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.phiraphongbenzz.ktorch.internal.Ktorch

internal class InspectorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge is enforced for apps targeting 35+; draw behind system bars on every
        // version and let Scaffold/TopAppBar apply the insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KtorchTheme {
                InspectorApp(Ktorch.store)
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, InspectorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

package com.streambox.mobile

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.rememberNavController
import com.streambox.mobile.data.update.ApkInstaller
import com.streambox.mobile.ui.navigation.StreamBoxNavHost
import com.streambox.mobile.ui.theme.StreamBoxTheme
import com.streambox.mobile.ui.update.UpdateDialog
import com.streambox.mobile.ui.update.UpdateViewModel

class MainActivity : ComponentActivity() {

    private val updateViewModel: UpdateViewModel by lazy {
        ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application),
        )[UpdateViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark-only regardless of the system light/dark setting, so
        // force light system-bar icons instead of letting auto-detection pick
        // dark icons against our obsidian background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            StreamBoxTheme {
                val updateState by updateViewModel.state.collectAsState()
                // Fire-and-forget: the check is delayed and can never block the
                // first frame or fail the launch.
                LaunchedEffect(Unit) { updateViewModel.checkForUpdates() }

                StreamBoxNavHost(
                    navController = rememberNavController(),
                    container = (application as StreamBoxApplication).container,
                )

                if (updateState.prompt != null) {
                    val context = LocalContext.current
                    val installPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult(),
                    ) {
                        // Coming back from "install unknown apps" settings:
                        // continue only if the user actually granted it.
                        if (ApkInstaller.canRequestPackageInstalls(context)) {
                            if (!updateViewModel.install(context)) {
                                updateViewModel.onInstallFailed()
                            }
                        } else {
                            updateViewModel.onInstallPermissionDenied()
                        }
                    }
                    UpdateDialog(
                        state = updateState,
                        onUpdateNow = updateViewModel::onUpdateNow,
                        onLater = updateViewModel::onLater,
                        onInstall = {
                            if (ApkInstaller.canRequestPackageInstalls(context)) {
                                if (!updateViewModel.install(context)) {
                                    updateViewModel.onInstallFailed()
                                }
                            } else {
                                installPermissionLauncher.launch(
                                    ApkInstaller.unknownAppSourcesSettingsIntent(context),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

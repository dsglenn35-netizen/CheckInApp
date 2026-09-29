package com.example.checkin

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.example.checkin.ui.CheckInApp
import com.example.checkin.ui.CheckInViewModel
import com.example.checkin.ui.theme.CheckInTheme

/**
 * 宿主 Activity。
 *
 * 继承 [FragmentActivity] 而不是 Compose 默认的 ComponentActivity：
 * [androidx.biometric.BiometricPrompt] 要求宿主是 FragmentActivity，
 * 而 FragmentActivity 本身就是 ComponentActivity 的子类，
 * 因此 setContent / enableEdgeToEdge / by viewModels() 全部照旧可用。
 */
class MainActivity : FragmentActivity() {

    private val viewModel: CheckInViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CheckInTheme {
                CheckInApp(viewModel)
            }
        }
    }
}

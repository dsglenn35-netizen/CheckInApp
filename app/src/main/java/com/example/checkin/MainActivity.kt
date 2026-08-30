package com.example.checkin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.checkin.ui.CheckInApp
import com.example.checkin.ui.CheckInViewModel
import com.example.checkin.ui.theme.CheckInTheme

class MainActivity : ComponentActivity() {

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

package com.example.touchgrass.presentation.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.touchgrass.core.remote.AuthRepository
import com.example.touchgrass.ui.theme.AmberWarn
import com.example.touchgrass.ui.theme.GrassGreen
import com.example.touchgrass.ui.theme.Ink
import com.example.touchgrass.ui.theme.InkBorder
import com.example.touchgrass.ui.theme.InkElevated
import com.example.touchgrass.ui.theme.TextPrimary
import com.example.touchgrass.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val auth: AuthRepository
) : ViewModel() {
    val sessionStatus: StateFlow<SessionStatus> = auth.sessionStatus
    val isConfigured: Boolean = auth.isConfigured

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    fun sendLink(email: String) {
        if (email.isBlank()) return
        viewModelScope.launch {
            _sending.value = true
            _message.value = null
            auth.sendMagicLink(email).fold(
                onSuccess = { _message.value = "Check your email for the sign-in link, then tap it." },
                onFailure = { _message.value = it.message ?: "Couldn't send the link. Try again." }
            )
            _sending.value = false
        }
    }

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }
}

@Composable
fun AccountScreen(viewModel: AccountViewModel = hiltViewModel()) {
    val status by viewModel.sessionStatus.collectAsState()
    val message by viewModel.message.collectAsState()
    val sending by viewModel.sending.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Account & sync", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "Sign in to sync focus sessions, goals and points across your phone and laptop.",
            color = TextSecondary, fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))

        when {
            !viewModel.isConfigured -> Card {
                Text("Backend not configured", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Add supabase.url and supabase.anonKey to local.properties and rebuild.",
                    color = TextSecondary, fontSize = 12.sp
                )
            }
            status is SessionStatus.Authenticated -> {
                val email = (status as SessionStatus.Authenticated).session.user?.email ?: "your account"
                Card {
                    Text("Signed in", color = GrassGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(email, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("This device is registered and syncing.", color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = viewModel::signOut,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = InkBorder, contentColor = TextPrimary)
                    ) { Text("Sign out", fontWeight = FontWeight.SemiBold) }
                }
            }
            else -> SignInCard(sending = sending, onSend = viewModel::sendLink)
        }

        message?.let {
            Text(it, color = AmberWarn, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SignInCard(sending: Boolean, onSend: (String) -> Unit) {
    var email by remember { mutableStateOf("") }
    Card {
        Text("Sign in with email", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("We'll email you a magic link — no password.", color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            singleLine = true,
            placeholder = { Text("you@example.com", color = TextSecondary) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = GrassGreen,
                unfocusedBorderColor = InkBorder,
                cursorColor = GrassGreen
            )
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onSend(email) },
            enabled = !sending && email.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GrassGreen, contentColor = Ink)
        ) { Text(if (sending) "Sending…" else "Send magic link", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(InkElevated)
            .border(1.dp, InkBorder, RoundedCornerShape(18.dp))
            .padding(18.dp),
        content = content
    )
}

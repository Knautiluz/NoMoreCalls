package com.knautiluz.nomorecalls

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        const val PREFS_NAME = "CallGuardPrefs"
        const val KEY_IS_FIRST_RUN = "isFirstRun"
        const val KEY_ALLOWED_NUMBERS = "allowedNumbers"
        const val KEY_IS_BLOCKING_ENABLED = "isBlockingEnabled"
        const val KEY_BLOCKED_CALLS_HISTORY = "blockedCallsHistory"
        const val KEY_PERSISTENT_CALLS_ATTEMPTS = "persistentCallsAttempts"
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            recreate()
        }
    }

    private val requestRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val sharedPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            sharedPrefs.edit {
                putBoolean(KEY_IS_FIRST_RUN, false)
            }
            checkAndRequestContactsPermission()
        } else {
            Toast.makeText(this, "É necessário definir como padrão para continuar", Toast.LENGTH_SHORT).show()
            val sharedPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            sharedPrefs.edit {
                putBoolean(KEY_IS_FIRST_RUN, true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val sharedPrefs = remember { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
                var isFirstRun by remember { mutableStateOf(sharedPrefs.getBoolean(KEY_IS_FIRST_RUN, true)) }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (isFirstRun) {
                        OnboardingScreen { requestScreeningRole() }
                    } else {
                        AppScreen(this@MainActivity)
                    }
                }
            }
        }
    }

    fun checkAndRequestContactsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    fun openSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    fun requestScreeningRole(showToastIfAlreadyDefault: Boolean = false) {
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager != null && !roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
            requestRoleLauncher.launch(intent)
        } else {
            if (showToastIfAlreadyDefault) {
                Toast.makeText(this, "O aplicativo já é o padrão.", Toast.LENGTH_SHORT).show()
            }
            val sharedPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            sharedPrefs.edit { putBoolean(KEY_IS_FIRST_RUN, false) }
            checkAndRequestContactsPermission()
            recreate()
        }
    }

    fun loadContacts(context: Context): List<Contact> {
        val contactsMap = mutableMapOf<String, Contact>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()

        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )

        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIdx) ?: "Sem Nome"
                val num = it.getString(numIdx) ?: ""
                val normalized = num.replace(Regex("[^0-9]"), "")
                if (normalized.isNotEmpty() && !contactsMap.containsKey(normalized)) {
                    contactsMap[normalized] = Contact(normalized, name, num)
                }
            }
        }
        return contactsMap.values.toList()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(activity: MainActivity) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE) }

    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var isBlockingEnabled by remember { mutableStateOf(sharedPrefs.getBoolean(MainActivity.KEY_IS_BLOCKING_ENABLED, true)) }
    var allContacts by remember { mutableStateOf(emptyList<Contact>()) }
    var allowedNumbers by remember { mutableStateOf(sharedPrefs.getStringSet(MainActivity.KEY_ALLOWED_NUMBERS, emptySet()) ?: emptySet()) }
    var persistentCallsAttempts by remember { mutableIntStateOf(sharedPrefs.getInt(MainActivity.KEY_PERSISTENT_CALLS_ATTEMPTS, 3)) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            allContacts = activity.loadContacts(context)
        }
    }

    val filteredContacts = allContacts.filter {
        it.name.contains(searchQuery, true) || it.number.contains(searchQuery)
    }.sortedWith(
        compareByDescending<Contact> { allowedNumbers.contains(it.id) }
            .thenBy { it.name.lowercase() }
    )

    Scaffold(
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = { Text("No More Calls", fontWeight = FontWeight.Bold) }
                )
                if (selectedTab == 0) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Buscar por nome ou número...") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.Person, null) }, label = { Text("Contatos") })
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.Shield, null) }, label = { Text("Histórico") })
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Config") })
            }
        }
    ) { p ->
        Column(modifier = Modifier.padding(p)) {
            when (selectedTab) {
                0 -> ContactsList(filteredContacts, allowedNumbers) { num, allowed ->
                    val newSet = if (allowed) allowedNumbers + num else allowedNumbers - num
                    allowedNumbers = newSet

                    sharedPrefs.edit {
                        putStringSet(MainActivity.KEY_ALLOWED_NUMBERS, newSet)
                    }
                }
                1 -> BlockedCallsScreen()
                2 -> SettingsScreen(
                    activity = activity,
                    isBlockingEnabled = isBlockingEnabled,
                    onBlockingEnabledChange = { isBlockingEnabled = it },
                    persistentCallsAttempts = persistentCallsAttempts,
                    onPersistentCallsAttemptsChange = { persistentCallsAttempts = it }
                )
            }
        }
    }
}

@Composable
fun ContactsList(contacts: List<Contact>, allowedNumbers: Set<String>, onToggle: (String, Boolean) -> Unit) {
    LazyColumn {
        items(contacts) { contact ->
            val isAllowed = allowedNumbers.contains(contact.id)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(contact.id, !isAllowed) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = contact.name, style = MaterialTheme.typography.titleMedium, color = if (isAllowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(text = contact.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = if (isAllowed) "✓ PERMITIR" else "✕ BLOQUEAR", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (isAllowed) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
                }
                Checkbox(checked = isAllowed, onCheckedChange = { onToggle(contact.id, it) }, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary, uncheckedColor = MaterialTheme.colorScheme.error))
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
fun BlockedCallsScreen() {
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    val gson = Gson()
    val json = sharedPrefs.getString(MainActivity.KEY_BLOCKED_CALLS_HISTORY, "[]")
    val type = object : TypeToken<List<BlockedCall>>() {}.type
    val history: List<BlockedCall> = try { gson.fromJson(json, type) ?: emptyList() } catch (_: Exception) { emptyList() }

    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nenhum bloqueio registrado.") }
    } else {
        LazyColumn {
            items(history) { call ->
                val icon = if (call.isAllowedByPersistence) Icons.Default.CheckCircle else Icons.Default.Block
                val iconColor = if (call.isAllowedByPersistence) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                ListItem(
                    headlineContent = { Text(call.name, fontWeight = FontWeight.Bold) },
                    supportingContent = {
                        val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(call.time))
                        val status = if (call.isAllowedByPersistence) "Chamada Permitida." else "Chamada Bloqueada."
                        Text("${call.number} • $date\n$status Tentativa (${call.tries})")
                    },
                    leadingContent = { Icon(imageVector = icon, contentDescription = null, tint = iconColor) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun SettingsScreen(
    activity: MainActivity,
    isBlockingEnabled: Boolean,
    onBlockingEnabledChange: (Boolean) -> Unit,
    persistentCallsAttempts: Int,
    onPersistentCallsAttemptsChange: (Int) -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE) }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Filtrar Chamadas", style = MaterialTheme.typography.headlineSmall)
        Switch(checked = isBlockingEnabled, onCheckedChange = {
            onBlockingEnabledChange(it)
            sharedPrefs.edit { putBoolean(MainActivity.KEY_IS_BLOCKING_ENABLED, it) }
        })
        Spacer(Modifier.height(16.dp))
        Button(onClick = { activity.requestScreeningRole(true) }) { Text("Definir padrão anti-spam") }
        Text(
            "Certifique-se de selecionar o No More Calls como app padrão anti-spam, para correto funcionamento.",
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { activity.openSettings() }) { Text("Configurações do aplicativo") }
        Text(
            "Caso a agenda não mostre os contatos, acione as configurações do app e dê a permissão de agenda.",
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
        Spacer(Modifier.height(16.dp))
        Text("Liberar chamadas persistentes", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(
            "Liberar chamadas persistentes refere-se a um usuário ligar consecutivamente em um período menor que 10 minutos.",
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center
        )
        val attempts = persistentCallsAttempts.toFloat()
        Slider(
            value = attempts,
            onValueChange = {
                onPersistentCallsAttemptsChange(it.toInt())
                sharedPrefs.edit { putInt(MainActivity.KEY_PERSISTENT_CALLS_ATTEMPTS, it.toInt()) }
            },
            valueRange = 0f..10f,
            steps = 9,
            modifier = Modifier.fillMaxWidth()
        )
        Text(if (persistentCallsAttempts > 0) {
            "Liberar chamadas após $persistentCallsAttempts ${if (persistentCallsAttempts < 2) "tentativa" else "tentativas"}"
        } else {
            "Não liberar chamadas persistentes"
        },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun OnboardingScreen(onRequestRole: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                modifier = Modifier.size(100.dp),
                tint = Color(0xFF4CAF50)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Bem-vindo (a) ao filtro de chamadas No More Calls",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Para que possamos filtrar apenas chamadas dos contatos, aceite o aplicativo como padrão anti-spam e dê acesso à lista de contatos.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(40.dp))
            Button(
                onClick = onRequestRole,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("ATIVAR PROTEÇÃO AGORA")
            }
        }
    }
}

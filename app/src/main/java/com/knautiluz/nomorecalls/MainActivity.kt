package com.knautiluz.nomorecalls

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
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

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            recreate()
        }
    }

    private val requestRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val sharedPrefs = getSharedPreferences("CallGuardPrefs", Context.MODE_PRIVATE)
            sharedPrefs.edit { putBoolean("isFirstRun", false) }
            checkAndRequestContactsPermission()
        } else {
            Toast.makeText(this, "É necessário definir como padrão para continuar", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val sharedPrefs = remember { getSharedPreferences("CallGuardPrefs", Context.MODE_PRIVATE) }
                var isFirstRun by remember { mutableStateOf(sharedPrefs.getBoolean("isFirstRun", true)) }

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

    fun requestScreeningRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager != null && !roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
            requestRoleLauncher.launch(intent)
        } else {
            val sharedPrefs = getSharedPreferences("CallGuardPrefs", Context.MODE_PRIVATE)
            sharedPrefs.edit { putBoolean("isFirstRun", false) }
            checkAndRequestContactsPermission()
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
    val sharedPrefs = remember { context.getSharedPreferences("CallGuardPrefs", Context.MODE_PRIVATE) }

    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var isBlockingEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("isBlockingEnabled", true)) }
    var allContacts by remember { mutableStateOf(emptyList<Contact>()) }
    var allowedNumbers by remember { mutableStateOf(sharedPrefs.getStringSet("allowedNumbers", emptySet()) ?: emptySet()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val loaded = activity.loadContacts(context)
            allContacts = loaded.map { it.copy(isAllowed = allowedNumbers.contains(it.number)) }
        }
    }

    val filteredContacts = allContacts.filter {
        it.name.contains(searchQuery, true) || it.number.contains(searchQuery)
    }.sortedWith(
        compareByDescending<Contact> { allowedNumbers.contains(it.number) }
            .thenBy { it.name.lowercase() }
    )

    Scaffold(
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = { Text("No More Calls", fontWeight = FontWeight.Bold) },
                    actions = {
                        Switch(checked = isBlockingEnabled, onCheckedChange = {
                            isBlockingEnabled = it
                            sharedPrefs.edit { putBoolean("isBlockingEnabled", it) }
                        })
                    }
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
                        putStringSet("allowedNumbers", newSet.toSet())
                        commit()
                    }
                }
                1 -> BlockedCallsScreen()
                2 -> SettingsScreen(activity)
            }
        }
    }
}

@Composable
fun ContactsList(contacts: List<Contact>, allowedNumbers: Set<String>, onToggle: (String, Boolean) -> Unit) {
    LazyColumn {
        items(contacts) { contact ->
            val isAllowed = allowedNumbers.contains(contact.number)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(contact.number, !isAllowed) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = contact.name, style = MaterialTheme.typography.titleMedium, color = if (isAllowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(text = contact.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = if (isAllowed) "✓ PERMITIR" else "✕ BLOQUEAR", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (isAllowed) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
                }
                Checkbox(checked = isAllowed, onCheckedChange = { onToggle(contact.number, it) }, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary, uncheckedColor = MaterialTheme.colorScheme.error))
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
fun BlockedCallsScreen() {
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("CallGuardPrefs", Context.MODE_PRIVATE)
    val gson = Gson()
    val json = sharedPrefs.getString("blockedCallsHistory", "[]")
    val type = object : TypeToken<List<BlockedCall>>() {}.type
    val history: List<BlockedCall> = try { gson.fromJson(json, type) ?: emptyList() } catch (e: Exception) { emptyList() }
    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nenhum bloqueio registrado.") }
    } else {
        LazyColumn {
            items(history) { call ->
                ListItem(
                    headlineContent = { Text(call.name, fontWeight = FontWeight.Bold) },
                    supportingContent = {
                        val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(call.time))
                        Text("${call.number} • $date")
                    },
                    leadingContent = { Icon(imageVector = Icons.AutoMirrored.Filled.CallMissed, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun SettingsScreen(activity: MainActivity) {
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Configuração de aplicativo anti-spam", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { activity.requestScreeningRole() }) { Text("Definir padrão anti-spam") }
    }
}

@Composable
fun OnboardingScreen(onRequestRole: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(imageVector = Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(100.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(24.dp))
            Text(text = "Bem-vindo ao Call Guard", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Para que possamos bloquear chamadas indesejadas, precisamos ser seu aplicativo de proteção contra spam padrão.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(40.dp))
            Button(onClick = onRequestRole, modifier = Modifier.fillMaxWidth().height(56.dp), shape = MaterialTheme.shapes.medium) {
                Text("ATIVAR PROTEÇÃO AGORA")
            }
        }
    }
}
package com.knautiluz.nomorecalls

import android.Manifest
import android.app.Activity
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

data class Contact(val id: String, val name: String, val number: String, var isAllowed: Boolean = false)

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) recreate()
    }

    private val requestRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Proteção Ativa!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestContactsPermission()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppScreen(this)
                }
            }
        }
    }

    private fun checkAndRequestContactsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    fun requestScreeningRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager != null && !roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
            requestRoleLauncher.launch(intent)
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
    val sharedPrefs = context.getSharedPreferences("CallGuardPrefs", Context.MODE_PRIVATE)

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
    }

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
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.Block, null) }, label = { Text("Histórico") })
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Config") })
            }
        }
    ) { p ->
        Column(modifier = Modifier.padding(p)) {
            when (selectedTab) {
                0 -> ContactsList(filteredContacts, allowedNumbers) { num, allowed ->
                    allowedNumbers = if (allowed) allowedNumbers + num else allowedNumbers - num
                    sharedPrefs.edit { putStringSet("allowedNumbers", allowedNumbers) }
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
            val isChecked = allowedNumbers.contains(contact.number)
            Row(Modifier.fillMaxWidth().clickable { onToggle(contact.number, !isChecked) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(contact.name, style = MaterialTheme.typography.titleMedium)
                    Text(contact.number, style = MaterialTheme.typography.bodySmall)
                }
                Checkbox(checked = isChecked, onCheckedChange = { onToggle(contact.number, it) })
            }
            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
        }
    }
}

@Composable
fun BlockedCallsScreen() {
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("CallGuardPrefs", Context.MODE_PRIVATE)
    val gson = Gson()
    val json = sharedPrefs.getString("blockedCallsHistory", null)
    val type = object : TypeToken<List<BlockedCall>>() {}.type
    val history: List<BlockedCall> = if (json != null) gson.fromJson(json, type) else emptyList()

    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nenhum bloqueio registrado.") }
    } else {
        LazyColumn {
            items(history) { call ->
                ListItem(
                    headlineContent = { Text(call.number) },
                    supportingContent = {
                        val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(call.time))
                        Text(date)
                    },
                    leadingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.CallMissed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                )
                HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
            }
        }
    }
}

@Composable
fun SettingsScreen(activity: MainActivity) {
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Permissão de Bloqueio", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { activity.requestScreeningRole() }) { Text("Definir como App Padrão") }
    }
}
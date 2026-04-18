package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.FlicAction
import com.eried.eucplanet.ui.theme.AccentBlue
import com.eried.eucplanet.ui.theme.AccentRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlicScreen(
    onBack: () -> Unit,
    viewModel: FlicViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val pairedButtons by viewModel.pairedButtons.collectAsState()
    var forgetTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    forgetTarget?.let { (addr, name) ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text(stringResource(R.string.flic_forget_title)) },
            text = { Text(stringResource(R.string.flic_forget_body, name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.forgetButton(addr)
                    forgetTarget = null
                }) { Text(stringResource(R.string.flic_forget), color = AccentRed) }
            },
            dismissButton = {
                TextButton(onClick = { forgetTarget = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.section_flic_buttons)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Scan section
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.flic_scan_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    if (scanning) {
                        CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.stopScan() },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                        ) {
                            Text(stringResource(R.string.flic_stop_scan))
                        }
                    } else {
                        Button(onClick = { viewModel.startScan() }) {
                            Text(stringResource(R.string.flic_start_scan))
                        }
                    }
                }
            }

            // Paired buttons
            if (pairedButtons.isEmpty()) {
                Text(
                    stringResource(R.string.flic_no_buttons),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Button 1 config
            settings.flic1Address?.let { addr ->
                ButtonConfig(
                    title = settings.flic1Name,
                    address = addr,
                    onTitleChange = { viewModel.updateFlic1Name(it) },
                    clickAction = settings.flic1Click,
                    doubleClickAction = settings.flic1DoubleClick,
                    holdAction = settings.flic1Hold,
                    onClickChange = { viewModel.updateFlic1Click(it) },
                    onDoubleClickChange = { viewModel.updateFlic1DoubleClick(it) },
                    onHoldChange = { viewModel.updateFlic1Hold(it) },
                    onForget = { forgetTarget = addr to settings.flic1Name }
                )
            }

            // Button 2 config
            settings.flic2Address?.let { addr ->
                ButtonConfig(
                    title = settings.flic2Name,
                    address = addr,
                    onTitleChange = { viewModel.updateFlic2Name(it) },
                    clickAction = settings.flic2Click,
                    doubleClickAction = settings.flic2DoubleClick,
                    holdAction = settings.flic2Hold,
                    onClickChange = { viewModel.updateFlic2Click(it) },
                    onDoubleClickChange = { viewModel.updateFlic2DoubleClick(it) },
                    onHoldChange = { viewModel.updateFlic2Hold(it) },
                    onForget = { forgetTarget = addr to settings.flic2Name }
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ButtonConfig(
    title: String,
    address: String,
    clickAction: String,
    doubleClickAction: String,
    holdAction: String,
    onTitleChange: (String) -> Unit = {},
    onClickChange: (String) -> Unit,
    onDoubleClickChange: (String) -> Unit,
    onHoldChange: (String) -> Unit,
    onForget: () -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var editText by remember(title) { mutableStateOf(title) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (editing) {
                        OutlinedTextField(
                            value = editText,
                            onValueChange = { editText = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = AccentBlue),
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = {
                                    onTitleChange(editText)
                                    editing = false
                                }) {
                                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_save))
                                }
                            }
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { editing = true }
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                color = AccentBlue
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.flic_rename),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                    Text(
                        address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onForget) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.flic_forget), tint = AccentRed)
                }
            }

            Spacer(Modifier.height(12.dp))

            ActionDropdown(stringResource(R.string.flic_click), clickAction, onClickChange)
            Spacer(Modifier.height(8.dp))
            ActionDropdown(stringResource(R.string.flic_double_click), doubleClickAction, onDoubleClickChange)
            Spacer(Modifier.height(8.dp))
            ActionDropdown(stringResource(R.string.flic_hold), holdAction, onHoldChange)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionDropdown(
    label: String,
    currentValue: String,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentAction = try {
        FlicAction.valueOf(currentValue)
    } catch (_: Exception) {
        FlicAction.NONE
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = stringResource(currentAction.labelRes),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            FlicAction.entries.forEach { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(action.labelRes)) },
                    onClick = {
                        onValueChange(action.name)
                        expanded = false
                    }
                )
            }
        }
    }
}

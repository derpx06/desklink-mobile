/*
 * SPDX-FileCopyrightText: 2026 DeskLink contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.desklink.mobile.plugins.automations

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.desklink.mobile.DeskLinkApplication
import org.desklink.mobile.R
import org.desklink.mobile.plugins.notifications.NotificationReceiver
import org.desklink.mobile.plugins.runcommand.RunCommandPlugin
import org.desklink.mobile.ui.compose.DeskLinkTheme
import org.desklink.mobile.ui.compose.components.DeskCard
import org.desklink.mobile.ui.compose.components.EmptyState
import org.desklink.mobile.ui.compose.components.LargeTitle
import org.desklink.mobile.ui.compose.components.PillButton
import org.desklink.mobile.ui.compose.components.PillStyle
import org.desklink.mobile.ui.compose.components.ScreenHeader
import org.desklink.mobile.ui.compose.components.deskSwitchColors
import org.desklink.mobile.ui.compose.tokens
import java.util.UUID

/**
 * F7 automations on the phone: the rules list, and an editor that reads "When … If … Do …", with
 * templates, a test run, the last runs, and "Write tag" for NFC rules.
 */
class AutomationsActivity : AppCompatActivity() {
    private val editing = mutableStateOf<Rule?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DeskLinkTheme(this) {
                val rule = editing.value
                if (rule == null) RulesList() else {
                    BackHandler { editing.value = null }
                    Editor(rule) { editing.value = null }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        AutomationNfc.stopWriting(this)
    }

    // ---- list ---------------------------------------------------------------------------------------------------

    @Composable
    private fun RulesList() {
        val t = tokens
        val revision by Automations.revision.collectAsState()
        val rules = remember(revision) { Automations.rules(this) }
        var templates by rememberSaveable { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
            ScreenHeader(::finish)
            LargeTitle(stringResource(R.string.automations_title), subtitle = stringResource(R.string.automations_subtitle))
            Box(Modifier.weight(1f)) {
                if (rules.isEmpty()) {
                    EmptyState(R.drawable.sym_bolt, stringResource(R.string.automations_empty_title), stringResource(R.string.automations_empty_body))
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(rules, key = { it.id }) { rule ->
                            DeskCard(Modifier.fillMaxWidth(), onClick = { editing.value = rule }) {
                                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(rule.name.ifBlank { stringResource(R.string.automations_unnamed) }, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(summary(this@AutomationsActivity, rule), fontSize = 13.sp, color = t.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    val label = rule.name.ifBlank { stringResource(R.string.automations_unnamed) }
                                    Switch(
                                        checked = rule.enabled,
                                        onCheckedChange = { on -> Automations.save(this@AutomationsActivity, rule.copy(enabled = on)) },
                                        colors = deskSwitchColors(),
                                        modifier = Modifier.semantics { contentDescription = label },
                                    )
                                }
                            }
                        }
                    }
                }
                PillButton(
                    stringResource(R.string.automations_new),
                    { if (rules.size >= Rules.MAX_RULES) toast(R.string.automations_full) else templates = true },
                    icon = R.drawable.sym_add,
                    modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
                )
            }
        }
        if (templates) {
            AlertDialog(
                onDismissRequest = { templates = false },
                title = { Text(stringResource(R.string.automations_new)) },
                text = {
                    Column {
                        templates(this@AutomationsActivity).forEach { template ->
                            Text(
                                template.name.ifBlank { stringResource(R.string.automations_template_blank) },
                                fontSize = 16.sp,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
                                    templates = false
                                    editing.value = template
                                }.padding(vertical = 12.dp),
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { templates = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }

    // ---- editor -------------------------------------------------------------------------------------------------

    @Composable
    private fun Editor(start: Rule, onDone: () -> Unit) {
        val t = tokens
        val context = LocalContext.current
        var rule by remember(start.id) { mutableStateOf(start) }
        val revision by Automations.revision.collectAsState()
        val history = remember(revision, rule.id) { Automations.history(context, rule.id).reversed() }
        var writing by remember { mutableStateOf(false) }
        DisposableEffect(Unit) { onDispose { AutomationNfc.stopWriting(this@AutomationsActivity) } }

        Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding().imePadding()) {
            ScreenHeader(onDone) {
                if (!rule.builtin) {
                    IconButton(onClick = { Automations.delete(context, rule.id); onDone() }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.sym_delete), contentDescription = stringResource(R.string.automations_delete), tint = t.text)
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // A built-in, or a rule with kinds this build does not know, is shown and never edited (A1, A7).
                if (rule.builtin || !Rules.runnable(rule)) {
                    Text(
                        stringResource(if (rule.builtin) R.string.automations_builtin else R.string.automations_needs_newer),
                        fontSize = 14.sp,
                        color = t.textDim,
                    )
                    Text(summary(context, rule), fontSize = 15.sp)
                    return@Column
                }
                Text(
                    if (Rules.isDeskTrigger(rule.trigger.kind)) stringResource(R.string.automations_runs_on_desk) else stringResource(R.string.automations_runs_on_phone),
                    fontSize = 13.sp,
                    color = t.textDim,
                )
                OutlinedTextField(
                    value = rule.name,
                    onValueChange = { rule = rule.copy(name = it.take(Rules.MAX_NAME_CHARS)) },
                    label = { Text(stringResource(R.string.automations_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Section(R.string.automations_when)
                StepEditor(rule.trigger, Rules.TRIGGERS, onChange = { rule = rule.copy(trigger = it) }, onRemove = null)
                if (rule.trigger.kind == Rules.T_NFC) {
                    val tag = rule.trigger["tag"]
                    PillButton(
                        stringResource(if (writing) R.string.automations_nfc_waiting else R.string.automations_nfc_write),
                        {
                            val id = tag ?: UUID.randomUUID().toString().take(8).also { rule = rule.copy(trigger = rule.trigger.copy(params = mapOf("tag" to it))) }
                            writing = AutomationNfc.startWriting(this@AutomationsActivity, id) { ok ->
                                writing = false
                                toast(if (ok) R.string.automations_nfc_written else R.string.automations_nfc_failed)
                            }
                            if (!writing) toast(R.string.automations_nfc_unavailable)
                        },
                        style = PillStyle.Tonal,
                        height = 48.dp,
                    )
                }
                Section(R.string.automations_if)
                rule.conditions.forEachIndexed { index, condition ->
                    StepEditor(condition, Rules.CONDITIONS, onChange = { c -> rule = rule.copy(conditions = rule.conditions.toMutableList().also { it[index] = c }) }) {
                        rule = rule.copy(conditions = rule.conditions.filterIndexed { i, _ -> i != index })
                    }
                }
                if (rule.conditions.size < Rules.MAX_STEPS) {
                    AddButton(R.string.automations_add_condition) { rule = rule.copy(conditions = rule.conditions + Step(Rules.C_PHONE_CONNECTED, mapOf("connected" to true))) }
                }
                Section(R.string.automations_do)
                rule.actions.forEachIndexed { index, action ->
                    StepEditor(action, Rules.ACTIONS, onChange = { a -> rule = rule.copy(actions = rule.actions.toMutableList().also { it[index] = a }) }) {
                        rule = rule.copy(actions = rule.actions.filterIndexed { i, _ -> i != index })
                    }
                }
                if (rule.actions.size < Rules.MAX_STEPS) {
                    AddButton(R.string.automations_add_action) { rule = rule.copy(actions = rule.actions + Step(Rules.A_NOTIFY)) }
                }
                Needs(rule)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val runOn = if (Rules.isDeskTrigger(rule.trigger.kind)) Rules.DESK else Rules.PHONE
                    PillButton(stringResource(R.string.automations_save), {
                        if (Automations.save(context, rule.copy(runOn = runOn))) onDone() else toast(R.string.automations_invalid)
                    }, height = 48.dp, enabled = rule.actions.isNotEmpty())
                    // A desk-run rule is tested on the desk.
                    if (runOn == Rules.PHONE) {
                        PillButton(stringResource(R.string.automations_test), { Automations.testRun(context, rule) }, style = PillStyle.Outlined, height = 48.dp, enabled = rule.actions.isNotEmpty())
                    }
                }
                if (history.isNotEmpty()) {
                    Section(R.string.automations_history)
                    history.forEach { run ->
                        Text(
                            "${if (run.ok) "✓" else "✕"}  ${DateUtils.getRelativeTimeSpanString(run.at)} · ${run.detail}",
                            fontSize = 13.sp,
                            color = if (run.ok) t.textDim else t.coralText,
                        )
                    }
                }
                Spacer(Modifier.height(32.dp).navigationBarsPadding())
            }
        }
    }

    @Composable
    private fun Section(@StringRes label: Int) {
        Text(stringResource(label), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = tokens.textDim, modifier = Modifier.padding(top = 6.dp).semantics { heading() })
    }

    @Composable
    private fun AddButton(@StringRes label: Int, onClick: () -> Unit) =
        PillButton(stringResource(label), onClick, style = PillStyle.Outlined, icon = R.drawable.sym_add, height = 44.dp)

    /** A kind picker and its parameters. */
    @Composable
    private fun StepEditor(step: Step, kinds: List<String>, onChange: (Step) -> Unit, onRemove: (() -> Unit)?) {
        val t = tokens
        var menu by remember { mutableStateOf(false) }
        val isCondition = kinds == Rules.CONDITIONS
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.surface).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Text(
                        kindLabel(this@AutomationsActivity, step.kind, isCondition),
                        fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.DropdownList) { menu = true }.padding(vertical = 12.dp),
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        kinds.forEach { kind ->
                            DropdownMenuItem(text = { Text(kindLabel(this@AutomationsActivity, kind, isCondition)) }, onClick = { menu = false; onChange(Step(kind)) })
                        }
                    }
                }
                if (onRemove != null) {
                    IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.sym_close), contentDescription = stringResource(R.string.automations_remove, kindLabel(this@AutomationsActivity, step.kind, isCondition)), tint = t.textDim)
                    }
                }
            }
            params(step.kind).forEach { param ->
                ParamField(param, step[param.key].orEmpty()) { value ->
                    val typed: Any? = when (param.type) {
                        Type.NUMBER -> value.toIntOrNull()
                        Type.BOOL -> value.toBooleanStrictOrNull()
                        else -> value.take(Rules.MAX_VALUE_CHARS).takeIf(String::isNotEmpty)
                    }
                    onChange(step.copy(params = if (typed == null) step.params - param.key else step.params + (param.key to typed)))
                }
            }
        }
    }

    private enum class Type { TEXT, NUMBER, TIME, CHOICE, BOOL, COMMAND, APP }

    private data class Param(val key: String, @StringRes val label: Int, val type: Type, val options: List<Pair<String, Int>> = emptyList())

    private val onOff = listOf("true" to R.string.automations_on, "false" to R.string.automations_off)

    /** Each kind's parameters (contract F7 A4–A6). */
    private fun params(kind: String): List<Param> = when (kind) {
        Rules.T_WIFI -> listOf(
            Param("state", R.string.automations_param_wifi_state, Type.CHOICE, listOf("connected" to R.string.automations_wifi_connected, "disconnected" to R.string.automations_wifi_disconnected)),
            Param("ssid", R.string.automations_param_ssid, Type.TEXT),
        )
        Rules.T_CHARGING -> listOf(Param("state", R.string.automations_param_charging, Type.CHOICE, listOf("start" to R.string.automations_charging_start, "stop" to R.string.automations_charging_stop)))
        Rules.T_BATTERY -> listOf(Param("below", R.string.automations_param_percent, Type.NUMBER))
        Rules.T_CALL -> listOf(Param("state", R.string.automations_param_call, Type.CHOICE, listOf("incoming" to R.string.automations_call_incoming, "missed" to R.string.automations_call_missed)))
        Rules.T_NOTIFICATION -> listOf(Param("app", R.string.automations_param_app, Type.APP))
        Rules.T_TIME -> listOf(
            Param("at", R.string.automations_param_at, Type.TIME),
            Param("days", R.string.automations_param_days, Type.CHOICE, listOf("1234567" to R.string.automations_days_every, "12345" to R.string.automations_days_weekdays, "67" to R.string.automations_days_weekend)),
        )
        "command-finished" -> listOf(Param("job", R.string.automations_param_job, Type.TEXT))
        Rules.C_PHONE_CONNECTED -> listOf(Param("connected", R.string.automations_param_connected, Type.BOOL, listOf("true" to R.string.automations_yes, "false" to R.string.automations_no)))
        Rules.C_WIFI -> listOf(Param("ssid", R.string.automations_param_ssid, Type.TEXT))
        Rules.C_TIME_BETWEEN -> listOf(Param("from", R.string.automations_param_from, Type.TIME), Param("to", R.string.automations_param_to, Type.TIME))
        Rules.C_DESK_LOCKED -> listOf(Param("locked", R.string.automations_param_locked, Type.BOOL, listOf("true" to R.string.automations_yes, "false" to R.string.automations_no)))
        Rules.A_RINGER -> listOf(Param("mode", R.string.automations_param_mode, Type.CHOICE, listOf("normal" to R.string.automations_ringer_normal, "vibrate" to R.string.automations_ringer_vibrate, "silent" to R.string.automations_ringer_silent)))
        Rules.A_VOLUME -> listOf(
            Param("stream", R.string.automations_param_stream, Type.CHOICE, listOf("media" to R.string.automations_stream_media, "ring" to R.string.automations_stream_ring, "alarm" to R.string.automations_stream_alarm)),
            Param("percent", R.string.automations_param_percent, Type.NUMBER),
        )
        Rules.A_DND -> listOf(Param("on", R.string.automations_param_dnd, Type.BOOL, onOff))
        Rules.A_OPEN -> listOf(Param("screen", R.string.automations_param_screen, Type.CHOICE, listOf("terminals" to R.string.automations_screen_terminals, "desk-screen" to R.string.automations_screen_desk)))
        Rules.A_NOTIFY, "notify" -> listOf(Param("title", R.string.automations_param_title, Type.TEXT), Param("body", R.string.automations_param_text, Type.TEXT))
        "run-command" -> listOf(Param("commandId", R.string.automations_param_command, Type.COMMAND))
        "keep-awake" -> listOf(Param("on", R.string.automations_param_keep_awake, Type.BOOL, onOff))
        "open-url" -> listOf(Param("url", R.string.automations_param_url, Type.TEXT))
        else -> emptyList()
    }

    @Composable
    private fun ParamField(param: Param, value: String, onValue: (String) -> Unit) {
        val label = stringResource(param.label)
        when (param.type) {
            Type.TEXT, Type.NUMBER, Type.TIME -> OutlinedTextField(
                value = value,
                onValueChange = { v ->
                    onValue(
                        when (param.type) {
                            Type.NUMBER -> v.filter(Char::isDigit).take(3)
                            Type.TIME -> v.filter { it.isDigit() || it == ':' }.take(5)
                            else -> v.replace("\n", "")
                        },
                    )
                },
                label = { Text(label) },
                placeholder = { if (param.type == Type.TIME) Text("22:00") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (param.type == Type.TEXT) KeyboardType.Text else KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Type.CHOICE, Type.BOOL -> Choice(label, param.options.map { (v, l) -> v to stringResource(l) }, value, onValue)
            Type.COMMAND -> {
                val commands = remember { commands() }
                if (commands.isEmpty()) Text(stringResource(R.string.automations_no_commands), fontSize = 13.sp, color = tokens.textDim)
                else Choice(label, commands, value, onValue)
            }
            Type.APP -> Choice(label, remember { launcherApps(this) }, value, onValue)
        }
    }

    @Composable
    private fun Choice(label: String, options: List<Pair<String, String>>, value: String, onValue: (String) -> Unit) {
        var open by remember { mutableStateOf(false) }
        val shown = options.firstOrNull { it.first == value }?.second ?: stringResource(R.string.automations_choose)
        Box {
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.DropdownList) { open = true }.padding(vertical = 6.dp)) {
                Text(label, fontSize = 12.sp, color = tokens.textDim)
                Text(shown, fontSize = 15.sp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { open = false; onValue(v) }) }
            }
        }
    }

    /** What the rule needs from Android, each asked for here, in context. */
    @Composable
    private fun Needs(rule: Rule) {
        val context = LocalContext.current
        val kinds = (listOf(rule.trigger) + rule.conditions + rule.actions)
        val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val wifiName = kinds.any { (it.kind == Rules.T_WIFI || it.kind == Rules.C_WIFI) && it["ssid"] != null }
        if (wifiName && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Need(R.string.automations_need_location) { location.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
        }
        val dnd = kinds.any { it.kind == Rules.A_DND || (it.kind == Rules.A_RINGER && it["mode"] == "silent") }
        if (dnd && !AutomationActions.dndAllowed(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Need(R.string.automations_need_dnd) { runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) } }
        }
        if (rule.trigger.kind == Rules.T_NOTIFICATION && !NotificationReceiver.hasReadNotificationsPermission(context)) {
            Need(R.string.automations_need_notifications) { runCatching { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) } }
        }
        if (rule.trigger.kind == Rules.T_CALL) {
            Text(stringResource(R.string.automations_need_calls), fontSize = 13.sp, color = tokens.textDim)
        }
    }

    @Composable
    private fun Need(@StringRes text: Int, onAllow: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tokens.coralContainer).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(text), fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onAllow, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.automations_allow)) }
        }
    }

    private fun toast(@StringRes text: Int) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun desks() = DeskLinkApplication.getInstance().devices.values.filter { it.isPaired }.sortedBy { it.name }

    /** Saved desk commands from the connected desks: (key, name). */
    private fun commands(): List<Pair<String, String>> = desks().flatMap { device ->
        device.getPlugin(RunCommandPlugin::class.java)?.commandList.orEmpty().mapNotNull { o ->
            val key = o.optString("key").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            key to "${o.optString("name", key)} · ${device.name}"
        }
    }.distinctBy { it.first }

    companion object {
        fun kindLabel(context: Context, kind: String, condition: Boolean = false): String =
            (CONDITION_LABELS[kind]?.takeIf { condition } ?: KIND_LABELS[kind])?.let(context::getString) ?: kind

        private val KIND_LABELS = mapOf(
            Rules.T_WIFI to R.string.automations_kind_wifi_trigger,
            Rules.T_CHARGING to R.string.automations_kind_charging,
            Rules.T_BATTERY to R.string.automations_kind_battery,
            Rules.T_CALL to R.string.automations_kind_call,
            Rules.T_NOTIFICATION to R.string.automations_kind_notification,
            Rules.T_NFC to R.string.automations_kind_nfc,
            Rules.T_TIME to R.string.automations_kind_time,
            "phone-connected" to R.string.automations_kind_phone_connected,
            "phone-left" to R.string.automations_kind_phone_left,
            "desk-locked" to R.string.automations_kind_desk_locked,
            "desk-unlocked" to R.string.automations_kind_desk_unlocked,
            "desk-sleep" to R.string.automations_kind_desk_sleep,
            "desk-resume" to R.string.automations_kind_desk_resume,
            "command-finished" to R.string.automations_kind_command_finished,
            Rules.C_WIFI to R.string.automations_kind_wifi,
            Rules.C_TIME_BETWEEN to R.string.automations_kind_time_between,
            Rules.A_RING to R.string.automations_kind_ring,
            Rules.A_RINGER to R.string.automations_kind_ringer,
            Rules.A_VOLUME to R.string.automations_kind_volume,
            Rules.A_DND to R.string.automations_kind_dnd,
            Rules.A_OPEN to R.string.automations_kind_open,
            Rules.A_NOTIFY to R.string.automations_kind_notify,
            "run-command" to R.string.automations_kind_run_desk_command,
            "lock-desk" to R.string.automations_kind_lock_desk,
            "keep-awake" to R.string.automations_kind_keep_awake,
            "send-clipboard" to R.string.automations_kind_send_clipboard,
            "open-url" to R.string.automations_kind_open_url,
            "notify" to R.string.automations_kind_desk_notify,
            "pause-media" to R.string.automations_kind_pause_media,
        )

        /** Kind labels for the condition list: `phone-connected`/`desk-locked` read as conditions there. */
        private val CONDITION_LABELS = mapOf(
            Rules.C_PHONE_CONNECTED to R.string.automations_kind_cond_connected,
            Rules.C_DESK_LOCKED to R.string.automations_kind_cond_locked,
        )

        /** "When Wi-Fi “Home” connects · if charging · do 2 things". */
        fun summary(context: Context, rule: Rule): String = buildString {
            append(kindLabel(context, rule.trigger.kind))
            rule.trigger.params.values.map { it.toString() }.firstOrNull(String::isNotBlank)?.let { append(" “").append(it).append("”") }
            if (rule.conditions.isNotEmpty()) append(" · ").append(rule.conditions.joinToString(", ") { kindLabel(context, it.kind, condition = true) })
            append(" → ").append(rule.actions.joinToString(", ") { kindLabel(context, it.kind) })
        }

        /** Apps with a launcher icon (visible to DeskLink through its `<queries>`): (package, label). */
        fun launcherApps(context: Context): List<Pair<String, String>> {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            return runCatching { pm.queryIntentActivities(launcher, 0) }.getOrDefault(emptyList())
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .filter { it.first != context.packageName }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
                .take(500)
        }

        fun templates(context: Context): List<Rule> {
            fun id() = UUID.randomUUID().toString()
            fun s(res: Int) = context.getString(res)
            return listOf(
                Rule(id(), "", trigger = Step(Rules.T_CHARGING, mapOf("state" to "start")), actions = listOf(Step(Rules.A_NOTIFY))),
                Rule(id(), s(R.string.automations_template_leave), trigger = Step("phone-left"), actions = listOf(Step("lock-desk")), runOn = Rules.DESK),
                Rule(id(), s(R.string.automations_template_work), trigger = Step(Rules.T_WIFI, mapOf("state" to "connected")), actions = listOf(Step(Rules.A_RINGER, mapOf("mode" to "vibrate")))),
                Rule(id(), s(R.string.automations_template_battery), trigger = Step(Rules.T_BATTERY, mapOf("below" to 20)), actions = listOf(Step(Rules.A_NOTIFY, mapOf("title" to s(R.string.automations_template_battery_text))))),
                Rule(id(), s(R.string.automations_template_nfc), trigger = Step(Rules.T_NFC), actions = listOf(Step("run-command"))),
                Rule(id(), s(R.string.automations_template_night), trigger = Step(Rules.T_CHARGING, mapOf("state" to "start")), conditions = listOf(Step(Rules.C_TIME_BETWEEN, mapOf("from" to "22:00", "to" to "07:00"))), actions = listOf(Step(Rules.A_DND, mapOf("on" to true)))),
            )
        }
    }
}

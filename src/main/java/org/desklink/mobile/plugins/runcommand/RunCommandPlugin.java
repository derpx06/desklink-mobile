/*
 * SPDX-FileCopyrightText: 2015 Aleix Pol Gonzalez <aleixpol@kde.org>
 * SPDX-FileCopyrightText: 2015 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.desklink.mobile.plugins.runcommand;

import static org.desklink.mobile.plugins.runcommand.RunCommandWidgetProviderKt.forceRefreshWidgets;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.apache.commons.collections4.iterators.IteratorIterable;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.desklink.mobile.NetworkPacket;
import org.desklink.mobile.plugins.Plugin;
import org.desklink.mobile.plugins.PluginFactory;
import org.desklink.mobile.ui.PluginSettingsFragment;
import org.desklink.mobile.R;
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import kotlin.Unit;

@PluginFactory.LoadablePlugin
public class RunCommandPlugin extends Plugin {

    private final static String PACKET_TYPE_RUNCOMMAND = DeskLinkProtocol.PACKET_TYPE_RUNCOMMAND;
    private final static String PACKET_TYPE_RUNCOMMAND_REQUEST = DeskLinkProtocol.PACKET_TYPE_RUNCOMMAND_REQUEST;
    public final static String KEY_COMMANDS_PREFERENCE = "commands_preference_";

    private final ArrayList<JSONObject> commandList = new ArrayList<>();
    private final ArrayList<CommandsChangedCallback> callbacks = new ArrayList<>();
    private final ArrayList<CommandEntry> commandItems = new ArrayList<>();

    private SharedPreferences sharedPreferences;

    private boolean canAddCommand;

    public void addCommandsUpdatedCallback(CommandsChangedCallback newCallback) {
        callbacks.add(newCallback);
    }

    public void removeCommandsUpdatedCallback(CommandsChangedCallback theCallback) {
        callbacks.remove(theCallback);
    }

    interface CommandsChangedCallback {
        void update();
    }

    public ArrayList<JSONObject> getCommandList() {
        return commandList;
    }

    public ArrayList<CommandEntry> getCommandItems() {
        return commandItems;
    }

    @Override
    public @NonNull String getDisplayName() {
        return context.getResources().getString(R.string.pref_plugin_runcommand);
    }

    @Override
    public @NonNull String getDescription() {
        return context.getResources().getString(R.string.pref_plugin_runcommand_desc);
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Nullable
    @Override
    public PluginSettingsFragment getSettingsFragment(Activity activity) {
        return PluginSettingsFragment.newInstance(getPluginKey(), R.xml.runcommand_preferences);
    }

    @Override
    public @NotNull List<@NotNull PluginUiButton> getUiButtons() {
        return List.of(new PluginUiButton(context.getString(R.string.pref_plugin_runcommand), R.drawable.run_command_plugin_icon_24dp, parentActivity -> {
            Intent intent = new Intent(parentActivity, RunCommandActivity.class);
            intent.putExtra("deviceId", getDevice().getDeviceId());
            parentActivity.startActivity(intent);
            return Unit.INSTANCE;
        }));
    }

    @Override
    public boolean onCreate() {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this.context);
        requestCommandList();
        return true;
    }

    @Override
    public boolean onPacketReceived(@NonNull NetworkPacket np) {
        // The desk refused a run: "terminal-off", or "locked" until the session password is proven.
        if (np.has("error")) {
            onRunRefused(np.getString("error", ""));
            return true;
        }

        if (np.has("commandList")) {
            commandList.clear();
            try {
                commandItems.clear();
                JSONObject obj = new JSONObject(np.getString("commandList"));
                for (String s : new IteratorIterable<>(obj.keys())) {
                    JSONObject o = obj.getJSONObject(s);
                    o.put("key", s);
                    commandList.add(o);

                    try {
                        commandItems.add(
                                new CommandEntry(o)
                        );
                    } catch (JSONException e) {
                        Log.e("RunCommand", "Error parsing JSON", e);
                    }
                }

                Collections.sort(commandItems, Comparator.comparing(CommandEntry::getName));

                // Used only by RunCommandControlsProviderService to display controls correctly even when device is not available
                if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    JSONArray array = new JSONArray();

                    for (JSONObject command : commandList) {
                        array.put(command);
                    }

                    sharedPreferences.edit()
                            .putString(KEY_COMMANDS_PREFERENCE + getDevice().getDeviceId(), array.toString())
                            .apply();
                }

                forceRefreshWidgets(context);

            } catch (JSONException e) {
                Log.e("RunCommand", "Error parsing JSON", e);
            }

            for (CommandsChangedCallback aCallback : callbacks) {
                aCallback.update();
            }

            getDevice().onPluginsChanged();

            canAddCommand = np.getBoolean("canAddCommand", false);
            terminalOff = false;

            return true;
        }
        return false;
    }

    @Override
    public @NonNull String[] getSupportedPacketTypes() {
        return new String[]{PACKET_TYPE_RUNCOMMAND};
    }

    @Override
    public @NonNull String[] getOutgoingPacketTypes() {
        return new String[]{PACKET_TYPE_RUNCOMMAND_REQUEST};
    }

    /** The desk said terminal access is off for this phone (it then sends no command list). */
    private volatile boolean terminalOff = false;
    /** The last run asked for, and whether it was already retried after "locked". */
    private volatile String lastRunKey = null;
    private volatile boolean lastRunRetried = false;

    public boolean getTerminalOff() {
        return terminalOff;
    }

    public static final String ERROR_TERMINAL_OFF = "terminal-off";
    public static final String ERROR_LOCKED = "locked";

    /** What a refusal does: retry once through the terminal's password, or say why. */
    public enum RefusalAction { TELL_TERMINAL_OFF, PROVE_PASSWORD_AND_RETRY, TELL_LOCKED }

    public static RefusalAction refusalAction(String error, boolean alreadyRetried) {
        if (ERROR_LOCKED.equals(error)) return alreadyRetried ? RefusalAction.TELL_LOCKED : RefusalAction.PROVE_PASSWORD_AND_RETRY;
        return RefusalAction.TELL_TERMINAL_OFF;
    }

    /** The list was refused "locked": asked for again once the session password is proven. */
    private volatile boolean listLocked = false;

    /** The terminal channel just proved the session password: fetch the list it held back. */
    public void onSessionPasswordProven() {
        if (!listLocked) return;
        listLocked = false;
        requestCommandList();
    }

    private void onRunRefused(String error) {
        String key = lastRunKey;
        if (key == null) {
            // The answer to the list request on connect, not to a tap: no message, only the state.
            if (ERROR_LOCKED.equals(error)) {
                listLocked = true;
            } else {
                terminalOff = true;
                commandItems.clear();
                commandList.clear();
                for (CommandsChangedCallback aCallback : callbacks) aCallback.update();
                getDevice().onPluginsChanged();
            }
            return;
        }
        RefusalAction action = refusalAction(error, lastRunRetried);
        switch (action) {
            case PROVE_PASSWORD_AND_RETRY: {
                lastRunRetried = true;
                // The terminal screen asks for the session password, proves it on its list, then
                // runs the command once more and opens the session it starts.
                String name = key;
                for (CommandEntry entry : commandItems) if (entry.getKey().equals(key)) name = entry.getName();
                Intent intent = org.desklink.mobile.plugins.terminal.TerminalActivity.Companion.intent(context, getDevice().getDeviceId())
                        .putExtra(org.desklink.mobile.plugins.terminal.TerminalActivity.EXTRA_RUN_KEY, key)
                        .putExtra(org.desklink.mobile.plugins.terminal.TerminalActivity.EXTRA_OPEN_TITLE, name)
                        .putExtra(org.desklink.mobile.plugins.terminal.TerminalActivity.EXTRA_OPEN_SINCE, System.currentTimeMillis() / 1000)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                try {
                    context.startActivity(intent);
                } catch (RuntimeException e) {
                    Log.w("RunCommand", "Could not open the terminal for the session password", e);
                }
                break;
            }
            case TELL_LOCKED:
                toast(context.getString(R.string.runcommand_locked, getDevice().getName()));
                break;
            case TELL_TERMINAL_OFF:
                terminalOff = true;
                commandItems.clear();
                commandList.clear();
                for (CommandsChangedCallback aCallback : callbacks) aCallback.update();
                getDevice().onPluginsChanged();
                toast(context.getString(R.string.runcommand_terminal_off, getDevice().getName()));
                break;
        }
    }

    private void toast(String message) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show());
    }

    /** A fresh run from the person (not the retry after the password). */
    public void runCommand(String cmdKey) {
        lastRunRetried = false;
        sendRun(cmdKey);
    }

    /** The one retry after the session password was proven. */
    public void retryRun(String cmdKey) {
        lastRunRetried = true;
        sendRun(cmdKey);
    }

    private void sendRun(String cmdKey) {
        lastRunKey = cmdKey;
        listLocked = false;
        NetworkPacket np = new NetworkPacket(PACKET_TYPE_RUNCOMMAND_REQUEST);
        np.set("key", cmdKey);
        getDevice().sendPacket(np);
    }

    private void requestCommandList() {
        // A refusal from here on answers this list request, not an earlier tap.
        lastRunKey = null;
        NetworkPacket np = new NetworkPacket(PACKET_TYPE_RUNCOMMAND_REQUEST);
        np.set("requestCommandList", true);
        getDevice().sendPacket(np);
    }

    public boolean canAddCommand() {
        return canAddCommand;
    }

    void sendSetupPacket() {
        NetworkPacket np = new NetworkPacket(RunCommandPlugin.PACKET_TYPE_RUNCOMMAND_REQUEST);
        np.set("setup", true);
        getDevice().sendPacket(np);
    }

}

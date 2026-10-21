/*
 * SPDX-FileCopyrightText: 2014 Ahmed I. Khalil <ahmedibrahimkhali@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.desklink.mobile.plugins.presenter

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import org.desklink.mobile.DeviceType
import org.desklink.mobile.NetworkPacket
import org.desklink.mobile.plugins.mousepad.KeyListenerView
import org.desklink.mobile.plugins.Plugin
import org.desklink.mobile.plugins.PluginFactory.LoadablePlugin
import org.desklink.mobile.ui.PluginSettingsFragment
import org.desklink.mobile.ui.PluginSettingsFragment.Companion.newInstance
import org.desklink.mobile.R
import org.desklink.mobile.protocol.desklinkv9.DeskLinkProtocol


@LoadablePlugin
class PresenterPlugin : Plugin() {

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_presenter)

    override val isCompatible: Boolean
        get() = device.deviceType != DeviceType.PHONE && super.isCompatible

    override val description: String
        get() = context.getString(R.string.pref_plugin_presenter_desc)

    override fun hasSettings(): Boolean = true

    override fun getSettingsFragment(activity: Activity): PluginSettingsFragment {
        return newInstance(pluginKey, R.xml.presenterplugin_preferences)
    }

    override fun getUiButtons(): List<PluginUiButton> = listOf(
        PluginUiButton(
            context.getString(R.string.pref_plugin_presenter),
            R.drawable.ic_presenter_24dp
        ) { parentActivity ->
            val intent = Intent(parentActivity, PresenterActivity::class.java)
            intent.putExtra("deviceId", device.deviceId)
            parentActivity.startActivity(intent)
        })

    override val supportedPacketTypes: Array<String> = emptyArray()

    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE_MOUSEPAD_REQUEST, PACKET_TYPE_PRESENTER)

    /** [onSent] runs (on the main thread) only once the desk has the key: the slide counter follows it. */
    fun sendNext(onSent: () -> Unit = {}) = sendKey(KeyEvent.KEYCODE_PAGE_DOWN, onSent)

    fun sendPrevious(onSent: () -> Unit = {}) = sendKey(KeyEvent.KEYCODE_PAGE_UP, onSent)

    private fun sendKey(keyCode: Int, onSent: () -> Unit) {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["specialKey"] = KeyListenerView.SpecialKeysMap.get(keyCode)
        device.sendPacket(np, object : org.desklink.mobile.Device.SendPacketStatusCallback() {
            override fun onSuccess() {
                android.os.Handler(android.os.Looper.getMainLooper()).post(onSent)
            }

            override fun onFailure(e: Throwable) = Unit
        })
    }

    fun sendFullscreen() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["specialKey"] = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_F5)
        device.sendPacket(np)
    }

    fun sendEsc() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["specialKey"] = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_ESCAPE)
        device.sendPacket(np)
    }

    /** "b": blank the screen in LibreOffice Impress, PowerPoint and most slide viewers. */
    fun sendBlank() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["key"] = "b"
        device.sendPacket(np)
    }

    companion object {
        private const val PACKET_TYPE_PRESENTER = DeskLinkProtocol.PACKET_TYPE_PRESENTER
        private const val PACKET_TYPE_MOUSEPAD_REQUEST = DeskLinkProtocol.PACKET_TYPE_MOUSEPAD_REQUEST
    }
}

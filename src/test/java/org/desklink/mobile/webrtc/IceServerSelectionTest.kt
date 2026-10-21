/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.desklink.mobile.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors the desktop's `coordinator.rs` `ice_config_for`. */
class IceServerSelectionTest {
    private val userStun = IceServerSpec(listOf("stun:stun.example.org:3478"))
    private val userTurn = IceServerSpec(listOf("turn:turn.example.org:3478"), "u", "p")
    private val grant = listOf(
        IceServerSpec(
            listOf("stun:stun.relay.example:3478", "turn:turn.relay.example:3478?transport=udp", "turns:turn.relay.example:5349"),
            "granted",
            "secret",
        ),
    )
    private val grantStun = IceServerSpec(listOf("stun:stun.relay.example:3478"), "granted", "secret")
    private val grantTurn = IceServerSpec(
        listOf("turn:turn.relay.example:3478?transport=udp", "turns:turn.relay.example:5349"),
        "granted",
        "secret",
    )
    private val defaultStun = IceServerSpec(listOf(DEFAULT_STUN_SERVER))

    private fun select(lan: Boolean, useTurn: Boolean, stun: List<IceServerSpec>, turn: List<IceServerSpec>, granted: List<IceServerSpec>) =
        selectIceServers(lan, useTurn, stun, turn, granted)

    @Test
    fun theLanUsesConfiguredServersOnlyAndTurnOnlyWhenAllowed() {
        assertEquals(emptyList<IceServerSpec>(), select(true, true, emptyList(), emptyList(), emptyList()))
        assertEquals(listOf(userStun, userTurn), select(true, true, listOf(userStun), listOf(userTurn), grant))
        assertEquals(listOf(userStun), select(true, false, listOf(userStun), listOf(userTurn), grant))
    }

    @Test
    fun aRelayPeerTakesTheGrantsStunAndTurn() {
        assertEquals(listOf(grantStun, grantTurn), select(false, true, emptyList(), emptyList(), grant))
        assertEquals(listOf(grantStun, userTurn, grantTurn), select(false, true, emptyList(), listOf(userTurn), grant))
    }

    @Test
    fun configuredStunWinsOverTheGrant() {
        assertEquals(listOf(userStun, grantTurn), select(false, true, listOf(userStun), emptyList(), grant))
    }

    @Test
    fun noGrantFallsBackToTheDefaultStun() {
        // Timeout, turn-denied and turn-quota all leave the grant empty.
        assertEquals(listOf(defaultStun), select(false, true, emptyList(), emptyList(), emptyList()))
        assertEquals(listOf(defaultStun, userTurn), select(false, true, emptyList(), listOf(userTurn), emptyList()))
        assertEquals(listOf(userStun), select(false, true, listOf(userStun), emptyList(), emptyList()))
    }

    @Test
    fun turnOffMeansNoTurnAtAll() {
        assertEquals(listOf(grantStun), select(false, false, emptyList(), listOf(userTurn), grant))
        assertEquals(listOf(defaultStun), select(false, false, emptyList(), listOf(userTurn), emptyList()))
    }

    @Test
    fun theDefaultStunMatchesTheDesktop() {
        assertEquals("stun:stun.cloudflare.com:3478", DEFAULT_STUN_SERVER)
    }
}

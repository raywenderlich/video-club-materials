/*
 * Copyright (c) 2021 Razeware LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * Notwithstanding the foregoing, you may not use, copy, modify, merge, publish,
 * distribute, sublicense, create a derivative work, and/or sell copies of the
 * Software in any work that is designed, intended, or marketed for pedagogical or
 * instructional purposes related to programming, coding, application development,
 * or information technology.  Permission for such use, copying, modification,
 * merger, publication, distribution, sublicensing, creation of derivative works,
 * or sale is expressly withheld.
 *
 * This project and source code may use libraries or frameworks that are
 * released under various Open-Source licenses. Use of those libraries and
 * frameworks are governed by their own individual licenses.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.raywenderlich.android.club.controllers

import android.content.Context
import com.raywenderlich.android.agora.rtm.*
import com.raywenderlich.android.club.BuildConfig
import com.raywenderlich.android.club.models.*
import io.agora.rtm.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.random.Random

/**
 * Access to the Agora SDKs in a structured manner, aggregating all interactions with
 * the system into a small set of observable flows. Internally, this class keeps track
 * of the user's sign-in status and observes some properties of the available and connected rooms.
 */
class SessionManager(
    context: Context,
    appId: String = BuildConfig.AGORA_APP_ID,
    private val serverApi: ServerApi = ServerApi.create(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private companion object {
        // The name of the default channel that every signed-in user is thrown into at first.
        // Through exchanged messages in this channel, the available open rooms are broadcast
        // to all users. When any user creates or closes their own room, this information
        // is spread to all other users waiting in the lobby channel.
        private const val LOBBY_CHANNEL_ID = "_lobby"

        private val NO_OPTIONS = SendMessageOptions()
    }

    /**
     * Data holder for an RTM channel and the corresponding
     * authentication token for its audio stream
     */
    private data class RoomChannelConnection(
        val room: Room,
        val channel: RtmChannel,
        val isBroadcaster: Boolean,
        val token: Token,
        val listener: RoomChannelListener
    )

    /* Private logic */

    private val _connectionStateEvents = MutableStateFlow(ConnectionState.Disconnected)
    private val _openRoomEvents = MutableStateFlow(emptySet<Room>())
    private val _connectedRoomEvents = MutableStateFlow<RoomSession?>(null)

    // Access to Agora RTM resources
    private val rtmClient by lazy { RtmClient.createInstance(context, appId, clientEventListener) }

    // Currently logged-in user; set to non-null after login
    private var currentUser: User? = null

    // Reference to lobby channel; set to non-null after login
    private var lobbyChannel: RtmChannel? = null

    // Reference to current room; set to non-null after creating/joining one
    private var roomConnection: RoomChannelConnection? = null
        set(value) {
            field = value

            // Send connection info to subscribers of the flow
            if (value == null) {
                _connectedRoomEvents.tryEmit(null)
            } else {
                _connectedRoomEvents.tryEmit(
                    RoomSession(
                        info = RoomInfo(
                            roomId = RoomId(value.channel.id),
                            token = value.token,
                            userId = requireNotNull(currentUser?.id),
                            isBroadcaster = value.isBroadcaster
                        ),
                        memberEvents = value.listener.membersFlow
                    )
                )
            }
        }

    /* Public API */

    /**
     * A Flow of events representing the connection status of the user to the Agora system.
     */
    val connectionStateEvents: Flow<ConnectionState> = _connectionStateEvents

    /**
     * A Flow of events representing the current list of open rooms that a user can join.
     */
    val openRoomEvents: Flow<List<Room>> = _openRoomEvents.map { it.distinct() }

    /**
     * A Flow of events representing the room that the user is currently connected to.
     * This can emit the same room multiple times in a row (e.g. when the user's role
     * in the channel is being updated or their token is renewed). When this emits null,
     * the user decided to leave a room, or the broadcast was finished.
     */
    val connectedRoomEvents: Flow<RoomSession?> = _connectedRoomEvents

    /**
     * Log into the system with the provided [userName]. This will allow
     */
    suspend fun login(userName: String) {
        withContext(dispatcher) {
            // Log out any current session
            logout()

            // Allocate a new user ID and perform the login on the server side
            val userId = UserId(Random.nextInt(0, Int.MAX_VALUE))
            val tokenResponse = serverApi.createRtmToken(userName)

            // Sign into Agora RTM system and hold onto the credentials
            rtmClient.awaitLogin(tokenResponse.token.value, userName)
//            rtmClient.awaitLogin(tokenResponse.token.value, "$userName@$userId") // TODO USE ID ON BACKEND TOO
            currentUser = User(userId, userName, tokenResponse.token)

            // Finally, log into the common "lobby" channel,
            // which will be used to communicate open rooms to all logged-in users
            lobbyChannel = rtmClient.awaitJoinChannel(LOBBY_CHANNEL_ID, lobbyChannelListener)
        }
    }

    /**
     *
     */
    suspend fun logout() {
        if (currentUser == null) return

        withContext(dispatcher) {
            // If active, leave the active room
            leaveRoom()

            // Leave the lobby
            lobbyChannel?.let { lobby ->
                lobby.awaitLeave()
                lobby.release()
                _openRoomEvents.value = emptySet()
                lobbyChannel = null
            }

            // Sign out of the system completely
            rtmClient.awaitLogout()
            currentUser = null
        }
    }

    /**
     *
     */
    suspend fun createRoom() {
        val currentUser = currentUser ?: return

        // Create a new channel with a random ID and then join it
        val roomId = UUID.randomUUID().toString()
        val room = Room(currentUser.id, RoomId(roomId))
        joinRoom(room)
    }

    /**
     *
     */
    suspend fun joinRoom(room: Room) {
        val currentUser = currentUser ?: return

        withContext(dispatcher) {
            // Leave any currently active room
            leaveRoom()

            // Create and join the messaging channel for the room
            val isBroadcaster = currentUser.id == room.hostId
            val channelListener = RoomChannelListener()
            val channel = rtmClient.awaitJoinChannel(room.roomId.value, channelListener)

            // Initialize local listener's understanding of the channel's members
            // (for new rooms: just the host for now, otherwise all current members)
            channelListener.membersFlow.value = if (isBroadcaster) {
                listOf(currentUser.name)
            } else {
                channel.awaitGetMembers().map { it.userId }
            }

            // Obtain authentication token to the audio streams of the room
            val tokenResponse = serverApi.createRtcToken(currentUser.id, room.roomId, isBroadcaster)

            roomConnection = RoomChannelConnection(
                room = room,
                channel = channel,
                isBroadcaster = isBroadcaster,
                token = tokenResponse.token,
                listener = channelListener
            )

            // If the room was just created by the current user,
            // broadcast its availability to users in the lobby
            if (isBroadcaster) {
                _openRoomEvents.update { it + room }
                sendUpdatedRoomListToLobbyUsers()
            }
        }
    }

    /**
     *
     */
    suspend fun leaveRoom() {
        if (currentUser == null) return

        withContext(dispatcher) {
            roomConnection?.let { connection ->
                // If this user was a broadcaster of the room,
                // notify its disappearance to users in the lobby
                if (connection.isBroadcaster) {
                    _openRoomEvents.update { it - connection.room }
                    sendUpdatedRoomListToLobbyUsers(wait = true)
                }

                connection.channel.awaitLeave()
                connection.channel.release()
                roomConnection = null
            }
        }
    }

    /* Private */

    private suspend fun sendUpdatedRoomListToLobbyUsers(wait: Boolean = false) {
        val lobbyChannel = lobbyChannel ?: return
        val message = rtmClient.createRoomListMessage() ?: return

        if (wait) {
            rtmClient.awaitSendMessageToChannelMembers(lobbyChannel, message, NO_OPTIONS)
        } else {
            rtmClient.sendMessageToChannelMembers(lobbyChannel, message, NO_OPTIONS)
        }
    }

    /* Agora Listeners */

    /**
     * Client-level listener for global connection status events
     */
    private val clientEventListener = object : DefaultRtmClientListener() {
        override fun onConnectionStateChanged(state: Int, reason: Int) {
            _connectionStateEvents.value = ConnectionState.fromCode(state)
        }

        override fun onMessageReceived(message: RtmMessage, peerId: String) {
            // Decode the incoming message
            val data = runCatching { Json.decodeFromString<Sendable>(message.text) }.getOrNull()
                ?: run {
                    println("onMessageReceived() got unknown message from $peerId: ${message.text}")
                    return
                }

            when (data.kind) {
                Sendable.Kind.RoomList -> {
                    // Replace the list of rooms with the received data
                    val rooms = data.decodeBody<RoomList>()
                    _openRoomEvents.update { rooms.rooms.toSet() }
                }
                else -> println("onMessageReceived() doesn't handle message of kind ${data.kind}: ${message.text}")
            }
        }
    }

    /**
     * Listener for the "lobby" channel, handling the synchronization of open rooms
     */
    private val lobbyChannelListener = object : DefaultRtmChannelListener() {
        override fun onMessageReceived(message: RtmMessage, member: RtmChannelMember) {
            println("onMessageReceived in lobby from ${member.userId}: ${message.text}")
        }

        override fun onMemberJoined(member: RtmChannelMember) {
            // Provide any member of the lobby with the current list of open rooms
            val message = rtmClient.createRoomListMessage() ?: return
            rtmClient.sendMessageToPeer(member.userId, message, NO_OPTIONS, null)
        }
    }

    /**
     * Listener for any ongoing room, handling messages
     * to and from other audience members and the broadcaster
     */
    private class RoomChannelListener : DefaultRtmChannelListener() {
        val membersFlow = MutableStateFlow(emptyList<String>())

        override fun onMessageReceived(message: RtmMessage, member: RtmChannelMember) {
            println("onMessageReceived in '${member.channelId}' from ${member.userId}: ${message.text}")
        }

        override fun onMemberJoined(member: RtmChannelMember) {
            membersFlow.update { it + member.userId }
        }

        override fun onMemberLeft(member: RtmChannelMember) {
            membersFlow.update { it - member.userId }
        }
    }

    /* Serialized messages between clients */

    private fun RtmClient.createRoomListMessage(): RtmMessage? {
        val rooms = _openRoomEvents.value.toList()
        return if (rooms.isNotEmpty()) {
            createSendableMessage(
                kind = Sendable.Kind.RoomList,
                bodyText = Json.encodeToString(RoomList(rooms))
            )
        } else {
            null
        }
    }
}
/*
 * Copyright 2014 OpenMarket Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.matrix.androidsdk;

import android.util.Log;

import org.matrix.androidsdk.data.DataRetriever;
import org.matrix.androidsdk.data.IMXStore;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.listeners.IMXEventListener;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.RoomMember;
import org.matrix.androidsdk.rest.model.RoomResponse;
import org.matrix.androidsdk.rest.model.User;
import org.matrix.androidsdk.rest.model.login.Credentials;
import org.matrix.androidsdk.util.JsonUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * The data handler provides a layer to help manage matrix input and output.
 * <ul>
 * <li>Handles events</li>
 * <li>Stores the data in its storage layer</li>
 * <li>Provides the means for an app to get callbacks for data changes</li>
 * </ul>
 */
public class MXDataHandler implements IMXEventListener {
    private static final String LOG_TAG = "MXData";

    private List<IMXEventListener> mEventListeners = new ArrayList<IMXEventListener>();

    private IMXStore mStore;
    private Credentials mCredentials;
    private volatile boolean mInitialSyncComplete = false;
    private DataRetriever mDataRetriever;

    /**
     * Default constructor.
     * @param store the data storage implementation.
     */
    public MXDataHandler(IMXStore store, Credentials credentials) {
        mStore = store;
        mCredentials = credentials;
    }

    public void setDataRetriever(DataRetriever dataRetriever) {
        mDataRetriever = dataRetriever;
        mDataRetriever.setStore(mStore);
    }

    public void addListener(IMXEventListener listener) {
        mEventListeners.add(listener);
        if (mInitialSyncComplete) {
            listener.onInitialSyncComplete();
        }
    }

    public void removeListener(IMXEventListener listener) {
        mEventListeners.remove(listener);
    }

    /**
     * Handle the room data received from a per-room initial sync
     * @param roomResponse the room response object
     * @param room the associated room
     */
    public void handleInitialRoomResponse(RoomResponse roomResponse, Room room) {
        // Handle state events
        if (roomResponse.state != null) {
            room.processLiveState(roomResponse.state);
        }

        // Handle visibility
        if (roomResponse.visibility != null) {
            room.setVisibility(roomResponse.visibility);
        }

        // Handle messages / pagination token
        if (roomResponse.messages != null) {
            mStore.storeRoomEvents(room.getRoomId(), roomResponse.messages, Room.EventDirection.FORWARDS);

            // To store the summary, we need the last event and the room state from just before
            Event lastEvent = roomResponse.messages.chunk.get(roomResponse.messages.chunk.size() - 1);
            RoomState beforeLiveRoomState = room.getLiveState().deepCopy();
            beforeLiveRoomState.applyState(lastEvent, Room.EventDirection.BACKWARDS);

            mStore.storeSummary(room.getRoomId(), lastEvent, beforeLiveRoomState, mCredentials.userId);
        }

        // Handle presence
        if (roomResponse.presence != null) {
            handleLiveEvents(roomResponse.presence);
        }

        // Handle the special case where the room is an invite
        if (RoomMember.MEMBERSHIP_INVITE.equals(roomResponse.membership)) {
            handleInitialSyncInvite(room.getRoomId(), roomResponse.inviter);
        }
    }

    /**
     * Handle the room data received from a global initial sync
     * @param roomResponse the room response object
     */
    public void handleInitialRoomResponse(RoomResponse roomResponse) {
        if (roomResponse.roomId != null) {
            Room room = getRoom(roomResponse.roomId);
            handleInitialRoomResponse(roomResponse, room);
        }
    }

    private void handleInitialSyncInvite(String roomId, String inviterUserId) {
        Room room = getRoom(roomId);

        // add yourself
        RoomMember member = new RoomMember();
        member.membership = RoomMember.MEMBERSHIP_INVITE;
        room.setMember(mCredentials.userId, member);

        // Build a fake invite event
        Event inviteEvent = new Event();
        inviteEvent.roomId = roomId;
        inviteEvent.stateKey = mCredentials.userId;
        inviteEvent.userId = inviterUserId;
        inviteEvent.type = Event.EVENT_TYPE_STATE_ROOM_MEMBER;
        inviteEvent.origin_server_ts = System.currentTimeMillis(); // This is where it's fake
        inviteEvent.content = JsonUtils.toJson(member);

        mStore.storeSummary(roomId, inviteEvent, null, mCredentials.userId);
    }

    public IMXStore getStore() {
        return mStore;
    }

    /**
     * Handle a list of events coming down from the event stream.
     * @param events the live events
     */
    public void handleLiveEvents(List<Event> events) {
        for (Event event : events) {
            handleLiveEvent(event);
        }
    }

    /**
     * Handle events coming down from the event stream.
     * @param event the live event
     */
    private void handleLiveEvent(Event event) {
        // Presence event
        if (Event.EVENT_TYPE_PRESENCE.equals(event.type)) {
            User userPresence = JsonUtils.toUser(event.content);
            User user = mStore.getUser(userPresence.userId);
            if (user == null) {
                user = userPresence;
                user.lastActiveReceived();
                mStore.storeUser(user);
            }
            else {
                user.presence = userPresence.presence;
                user.lastActiveAgo = userPresence.lastActiveAgo;
                user.lastActiveReceived();
            }
            this.onPresenceUpdate(event, user);
        }

        // Room event
        else if (event.roomId != null) {
            Room room = getRoom(event.roomId);
            // The room state we send with the callback is the one before the current event was processed
            RoomState beforeState = room.getLiveState().deepCopy();
            if (event.stateKey != null) {
                room.processStateEvent(event, Room.EventDirection.FORWARDS);
            }
            if (!Event.EVENT_TYPE_TYPING.equals(event.type)) {
                mStore.storeLiveRoomEvent(event);
                mStore.storeSummary(event.roomId, event, beforeState, mCredentials.userId);
            }
            onLiveEvent(event, beforeState);
        }

        else {
            Log.e(LOG_TAG, "Unknown live event type: " + event.type);
        }
    }

    /**
     * Get the room object for the corresponding room id. Creates and initializes the object if there is none.
     * @param roomId the room id
     * @return the corresponding room
     */
    public Room getRoom(String roomId) {
        Room room = mStore.getRoom(roomId);
        if (room == null) {
            room = new Room();
            room.setRoomId(roomId);
            room.setDataHandler(this);
            room.setDataRetriever(mDataRetriever);
            mStore.storeRoom(room);
        }
        return room;
    }

    // Proxy IMXEventListener callbacks to everything in mEventListeners

    @Override
    public void onPresenceUpdate(Event event, User user) {
        for (IMXEventListener listener : mEventListeners) {
            listener.onPresenceUpdate(event, user);
        }
    }

    @Override
    public void onLiveEvent(Event event, RoomState roomState) {
        for (IMXEventListener listener : mEventListeners) {
            listener.onLiveEvent(event, roomState);
        }
    }

    @Override
    public void onBackEvent(Event event, RoomState roomState) {
        for (IMXEventListener listener : mEventListeners) {
            listener.onBackEvent(event, roomState);
        }
    }

    @Override
    public void onInitialSyncComplete() {
        mInitialSyncComplete = true;

        for (IMXEventListener listener : mEventListeners) {
            listener.onInitialSyncComplete();
        }
    }
}

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

import android.os.HandlerThread;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.matrix.androidsdk.call.MXCallsManager;
import org.matrix.androidsdk.data.DataRetriever;
import org.matrix.androidsdk.data.IMXStore;
import org.matrix.androidsdk.data.MyUser;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.data.RoomSummary;
import org.matrix.androidsdk.db.MXMediasCache;
import org.matrix.androidsdk.listeners.IMXEventListener;
import org.matrix.androidsdk.rest.callback.SimpleApiCallback;
import org.matrix.androidsdk.rest.client.PresenceRestClient;
import org.matrix.androidsdk.rest.client.ProfileRestClient;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.RoomMember;
import org.matrix.androidsdk.rest.model.Sync.SyncResponse;
import org.matrix.androidsdk.rest.model.User;
import org.matrix.androidsdk.rest.model.bingrules.BingRule;
import org.matrix.androidsdk.rest.model.bingrules.BingRuleSet;
import org.matrix.androidsdk.rest.model.login.Credentials;
import org.matrix.androidsdk.util.BingRulesManager;
import org.matrix.androidsdk.util.ContentManager;
import org.matrix.androidsdk.util.JsonUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import android.os.Handler;

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

    public interface InvalidTokenListener {
        /**
         * Call when the access token is corrupted
         */
        void onTokenCorrupted();
    }

    private List<IMXEventListener> mEventListeners = new ArrayList<IMXEventListener>();

    private IMXStore mStore;
    private Credentials mCredentials;
    private volatile boolean mInitialSyncComplete = false;
    private DataRetriever mDataRetriever;
    private BingRulesManager mBingRulesManager;
    private ContentManager mContentManager;
    private MXCallsManager mCallsManager;
    private MXMediasCache mMediasCache;

    private ProfileRestClient mProfileRestClient;
    private PresenceRestClient mPresenceRestClient;

    private MyUser mMyUser;

    private HandlerThread mSyncHandlerThread;
    private Handler mSyncHandler;
    private Handler mUiHandler;

    private boolean mIsActive = true;

    InvalidTokenListener mInvalidTokenListener;

    /**
     * Default constructor.
     * @param store the data storage implementation.
     */
    public MXDataHandler(IMXStore store, Credentials credentials,InvalidTokenListener invalidTokenListener) {
        mStore = store;
        mCredentials = credentials;

        mUiHandler = new Handler(Looper.getMainLooper());

        mSyncHandlerThread = new HandlerThread("MXDataHandler" + mCredentials.userId, Thread.MIN_PRIORITY);
        mSyncHandlerThread.start();
        mSyncHandler = new Handler(mSyncHandlerThread.getLooper());

        mInvalidTokenListener = invalidTokenListener;
    }

    public Credentials getCredentials() {
        return mCredentials;
    }

    // some setters
    public void setProfileRestClient(ProfileRestClient profileRestClient) {
        mProfileRestClient = profileRestClient;
    }

    public void setPresenceRestClient(PresenceRestClient presenceRestClient) {
        mPresenceRestClient = presenceRestClient;
    }

    private void checkIfActive() {
        synchronized (this) {
            if (!mIsActive) {
                Log.e(LOG_TAG, "use of a released dataHandler");
                //throw new AssertionError("Should not used a MXDataHandler");
            }
        }
    }

    public boolean isActive() {
        synchronized (this) {
            return mIsActive;
        }
    }

    /**
     * The current token is not anymore valid
     */
    public void onInvalidToken() {
        if (null != mInvalidTokenListener) {
            mInvalidTokenListener.onTokenCorrupted();
        }
    }

    /**
     * Get the session's current user. The MyUser object provides methods for updating user properties which are not possible for other users.
     * @return the session's MyUser object
     */
    public MyUser getMyUser() {
        checkIfActive();

        IMXStore store = getStore();

        // MyUser is initialized as late as possible to have a better chance at having the info in storage,
        // which should be the case if this is called after the initial sync
        if (mMyUser == null) {
            mMyUser = new MyUser(store.getUser(mCredentials.userId));
            mMyUser.setProfileRestClient(mProfileRestClient);
            mMyUser.setPresenceRestClient(mPresenceRestClient);
            mMyUser.setDataHandler(this);

            // assume the profile is not yet initialized
            if (null == store.displayName()) {
                store.setAvatarURL(mMyUser.getAvatarUrl());
                store.setDisplayName(mMyUser.displayname);
            } else {
                // use the latest user information
                // The user could have updated his profile in offline mode and kill the application.
                mMyUser.displayname = store.displayName();
                mMyUser.setAvatarUrl(store.avatarURL());
            }

            // Handle the case where the user is null by loading the user information from the server
            mMyUser.user_id = mCredentials.userId;
        } else if (null != store) {
            // assume the profile is not yet initialized
            if ((null == store.displayName()) && (null != mMyUser.displayname)) {
                // setAvatarURL && setDisplayName perform a commit if it is required.
                store.setAvatarURL(mMyUser.getAvatarUrl());
                store.setDisplayName(mMyUser.displayname);
            } else if (!TextUtils.equals(mMyUser.displayname, store.displayName())) {
                mMyUser.displayname = store.displayName();
                mMyUser.setAvatarUrl(store.avatarURL());
            }
        }

        // check if there is anything to refresh
        mMyUser.refreshUserInfos(null);

        return mMyUser;
    }

    /**
     * @return true if the initial sync is completed.
     */
    public boolean isInitialSyncComplete() {
        checkIfActive();
        return mInitialSyncComplete;
    }

    public DataRetriever getDataRetriever() {
        checkIfActive();
        return mDataRetriever;
    }

    public void setDataRetriever(DataRetriever dataRetriever) {
        checkIfActive();
        mDataRetriever = dataRetriever;
    }

    public void setPushRulesManager(BingRulesManager bingRulesManager) {
        if (isActive()) {
            mBingRulesManager = bingRulesManager;

            mBingRulesManager.loadRules(new SimpleApiCallback<Void>() {
                @Override
                public void onSuccess(Void info) {
                    MXDataHandler.this.onBingRulesUpdate();
                }
            });
        }
    }

    public void setCallsManager(MXCallsManager callsManager) {
        checkIfActive();
        mCallsManager = callsManager;
    }

    public MXCallsManager getCallsManager() {
        checkIfActive();
        return mCallsManager;
    }

    public void setMediasCache(MXMediasCache mediasCache) {
        checkIfActive();
        mMediasCache = mediasCache;
    }

    public BingRuleSet pushRules() {
        if (isActive() && (null != mBingRulesManager)) {
            return mBingRulesManager.pushRules();
        }

        return null;
    }

    public void refreshPushRules() {
        if (isActive() && (null != mBingRulesManager)) {
            mBingRulesManager.loadRules(new SimpleApiCallback<Void>() {
                @Override
                public void onSuccess(Void info) {
                    MXDataHandler.this.onBingRulesUpdate();
                }
            });
        }
    }

    public BingRulesManager getBingRulesManager() {
        checkIfActive();
        return mBingRulesManager;
    }

    public void addListener(IMXEventListener listener) {
        if (mIsActive) {
            synchronized (this) {
                // avoid adding twice
                if (mEventListeners.indexOf(listener) == -1) {
                    mEventListeners.add(listener);
                }
            }

            if (mInitialSyncComplete) {
                listener.onInitialSyncComplete();
            }
        }
    }

    public void removeListener(IMXEventListener listener) {
        if (mIsActive) {
            synchronized (this) {
                mEventListeners.remove(listener);
            }
        }
    }

    public void clear() {
        synchronized (this) {
            mIsActive = false;
            // remove any listener
            mEventListeners.clear();
        }

        // clear the store
        mStore.close();
        mStore.clear();

        if (null != mSyncHandlerThread) {
            mSyncHandlerThread.quit();
            mSyncHandlerThread = null;
        }
    }

    public String getUserId() {
        if (isActive()) {
            return mCredentials.userId;
        } else {
            return "dummy";
        }
    }

    /**
     * Update the missing data fields loaded from a permanent storage.
     */
    public void checkPermanentStorageData() {
        if (!isActive()) {
            Log.e(LOG_TAG, "checkPermanentStorageData : the session is not anymore active");
            return;
        }

        if (mStore.isPermanent()) {
            // When the data are extracted from a persistent storage,
            // some fields are not retrieved :
            // They are used to retrieve some data
            // so add the missing links.

            Collection<Room> rooms =  mStore.getRooms();

            for(Room room : rooms) {
                room.init(room.getRoomId(), this);
            }

            Collection<RoomSummary> summaries = mStore.getSummaries();
            for(RoomSummary summary : summaries) {
                if (null != summary.getLatestRoomState()) {
                    summary.getLatestRoomState().setDataHandler(this);
                }
            }
        }
    }

    /**
     * @return the used store.
     */
    public IMXStore getStore() {
        if (isActive()) {
            return mStore;
        } else {
            Log.e(LOG_TAG, "getStore : the session is not anymore active");
            return null;
        }
    }

    /**
     * Returns the member with userID;
     * @param members the members List
     * @param userID the user ID
     * @return the roomMember if it exists.
     */
    public RoomMember getMember(Collection<RoomMember> members, String userID) {
        if (isActive()) {
            for (RoomMember member : members) {
                if (TextUtils.equals(userID, member.getUserId())) {
                    return member;
                }
            }
        } else {
            Log.e(LOG_TAG, "getMember : the session is not anymore active");
        }
        return null;
    }

    /**
     * Check a room exists with the dedicated roomId
     * @param roomId the room ID
     * @return true it exists.
     */
    public boolean doesRoomExist(String roomId) {
        return (null != roomId) && (null != mStore.getRoom(roomId));
    }

    /**
     * Get the room object for the corresponding room id. Creates and initializes the object if there is none.
     * @param roomId the room id
     * @return the corresponding room
     */
    public Room getRoom(String roomId) {
        return getRoom(roomId, true);
    }

    /**
     * Get the room object for the corresponding room id.
     * @param roomId the room id
     * @param create create the room it does not exist.
     * @return the corresponding room
     */
    public Room getRoom(String roomId, boolean create) {
        if (!isActive()) {
            Log.e(LOG_TAG, "getRoom : the session is not anymore active");
            return null;
        }

        // sanity check
        if (TextUtils.isEmpty(roomId)) {
            return null;
        }

        Room room = mStore.getRoom(roomId);
        if ((room == null) && create) {
            room = new Room();
            room.init(roomId, this);
            mStore.storeRoom(room);
        }
        return room;
    }

    /**
     * Delete an event.
     * @param event The event to be stored.
     */
    public void deleteRoomEvent(Event event) {
        if (isActive()) {
            Room room = getRoom(event.roomId);

            if (null != room) {
                mStore.deleteEvent(event);
                Event lastEvent = mStore.getLatestEvent(event.roomId);
                RoomState beforeLiveRoomState = room.getState().deepCopy();

                mStore.storeSummary(event.roomId, lastEvent, beforeLiveRoomState, mCredentials.userId);
            }
        } else {
            Log.e(LOG_TAG, "deleteRoomEvent : the session is not anymore active");
        }
    }

    /**
     * Return an user from his id.
     * @param userId the user id;.
     * @return the user.
     */
    public User getUser(String userId) {
        if (!isActive()) {
            Log.e(LOG_TAG, "getUser : the session is not anymore active");
            return null;
        } else {
            return mStore.getUser(userId);
        }
    }

    //================================================================================
    // Sync V2
    //================================================================================

    public void handlePresenceEvent(Event presenceEvent) {
        // Presence event
        if (Event.EVENT_TYPE_PRESENCE.equals(presenceEvent.type)) {
            User userPresence = JsonUtils.toUser(presenceEvent.content);

            // use the sender by default
            if (!TextUtils.isEmpty(presenceEvent.getSender())) {
                userPresence.user_id = presenceEvent.getSender();
            }

            User user = mStore.getUser(userPresence.user_id);

            if (user == null) {
                user = userPresence;
                user.lastActiveReceived();
                user.setDataHandler(this);
                mStore.storeUser(user);
            }
            else {
                user.currently_active = userPresence.currently_active;
                user.presence = userPresence.presence;
                user.lastActiveAgo = userPresence.lastActiveAgo;
                user.lastActiveReceived();
            }

            // check if the current user has been updated
            if (mCredentials.userId.equals(user.user_id)) {
                mStore.setAvatarURL(user.getAvatarUrl());
                mStore.setDisplayName(user.displayname);
            }

            this.onPresenceUpdate(presenceEvent, user);
        }
    }

    public void onSyncReponse(final SyncResponse syncResponse, final boolean isInitialSync) {
        // perform the sync in background
        // to avoid UI thread lags.
        mSyncHandler.post(new Runnable() {
            @Override
            public void run() {
               manageResponse(syncResponse, isInitialSync);
            }
        });
    }

    private void manageResponse(final SyncResponse syncResponse, final boolean isInitialSync) {
        boolean isEmptyResponse = true;

        // sanity check
        if (null != syncResponse) {
            Log.d(LOG_TAG, "onSyncComplete");

            // sanity check
            if (null != syncResponse.rooms) {

                // left room management
                // it should be done at the end but it seems there is a server issue
                // when inviting after leaving a room, the room is defined in the both leave & invite rooms list.
                if ((null != syncResponse.rooms.leave) && (syncResponse.rooms.leave.size() > 0)) {
                    Log.d(LOG_TAG, "Received " + syncResponse.rooms.leave.size() + " left rooms");

                    Set<String> roomIds = syncResponse.rooms.leave.keySet();

                    for (String roomId : roomIds) {
                        // RoomSync leftRoomSync = syncResponse.rooms.leave.get(roomId);

                        // Presently we remove the existing room from the rooms list.
                        // FIXME SYNCV2 Archive/Display the left rooms!
                        // For that create 'handleArchivedRoomSync' method

                        // Retrieve existing room
                        // check if the room still exists.
                        if (null != this.getStore().getRoom(roomId)) {
                            this.getStore().deleteRoom(roomId);
                            onLeaveRoom(roomId);
                        }
                    }

                    isEmptyResponse = false;
                }

                // joined rooms events
                if ((null != syncResponse.rooms.join) && (syncResponse.rooms.join.size() > 0)) {
                    Log.d(LOG_TAG, "Received " + syncResponse.rooms.join.size() + " joined rooms");

                    Set<String> roomIds = syncResponse.rooms.join.keySet();

                    // Handle first joined rooms
                    for (String roomId : roomIds) {
                        getRoom(roomId).handleJoinedRoomSync(syncResponse.rooms.join.get(roomId), isInitialSync);
                    }

                    isEmptyResponse = false;
                }

                // invited room management
                if ((null != syncResponse.rooms.invite) && (syncResponse.rooms.invite.size() > 0)) {
                    Log.d(LOG_TAG, "Received " + syncResponse.rooms.invite.size() + " invited rooms");

                    Set<String> roomIds = syncResponse.rooms.invite.keySet();

                    for (String roomId : roomIds) {
                        getRoom(roomId).handleInvitedRoomSync(syncResponse.rooms.invite.get(roomId));
                    }

                    isEmptyResponse = false;
                }
            }

            // Handle presence of other users
            if ((null != syncResponse.presence) && (null != syncResponse.presence.events)) {
                for (Event presenceEvent : syncResponse.presence.events) {
                    handlePresenceEvent(presenceEvent);
                }
            }

            if (!isEmptyResponse) {
                getStore().setEventStreamToken(syncResponse.nextBatch);
                getStore().commit();
            }
        }

        if (isInitialSync) {
            onInitialSyncComplete();
        } else {
            try {
                onLiveEventsChunkProcessed();
            } catch (Exception e) {
                Log.e(LOG_TAG, "onLiveEventsChunkProcessed failed " + e + " " + e.getStackTrace());
            }

            try {
                // check if an incoming call has been received
                mCallsManager.checkPendingIncomingCalls();
            } catch (Exception e) {
                Log.e(LOG_TAG, "checkPendingIncomingCalls failed " + e + " " + e.getStackTrace());
            }
        }
    }

    /**
     * Refresh the unread summary counters of the updated rooms.
     */
    private void refreshUnreadCounters() {
        // refresh the unread counter
        for(String roomId : mUpdatedRoomIdList) {
            Room room = mStore.getRoom(roomId);

            if (null != room) {
                room.refreshUnreadCounter();
            }
        }

        mUpdatedRoomIdList.clear();
    }

    //================================================================================
    // Listeners management
    //================================================================================

    // Proxy IMXEventListener callbacks to everything in mEventListeners
    List<IMXEventListener> getListenersSnapshot() {
        ArrayList<IMXEventListener> eventListeners;

        synchronized (this) {
            eventListeners = new ArrayList<IMXEventListener>(mEventListeners);
        }

        return eventListeners;
    }

    public void onStoreReady() {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onStoreReady();
                    } catch (Exception e) {
                    }
                }
            }
        });
    }

    @Override
    public void onAccountInfoUpdate(final MyUser myUser) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onAccountInfoUpdate(myUser);
                    } catch (Exception e) {
                    }
                }
            }
        });
    }

    @Override
    public void onPresenceUpdate(final Event event, final User user) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onPresenceUpdate(event, user);
                    } catch (Exception e) {
                    }
                }
            }
        });
    }

    private ArrayList<String> mUpdatedRoomIdList = new ArrayList<String>();

    @Override
    public void onLiveEvent(final Event event, final RoomState roomState) {
        //
        if (!TextUtils.equals(Event.EVENT_TYPE_TYPING, event.type) && !TextUtils.equals(Event.EVENT_TYPE_RECEIPT, event.type) && !TextUtils.equals(Event.EVENT_TYPE_TYPING, event.type)) {
            if (mUpdatedRoomIdList.indexOf(roomState.roomId) < 0) {
                mUpdatedRoomIdList.add(roomState.roomId);
            }
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onLiveEvent(event, roomState);
                    } catch (Exception e) {
                    }
                }
            }
        });
    }

    @Override
    public void onLiveEventsChunkProcessed() {
        refreshUnreadCounters();

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onLiveEventsChunkProcessed();
                    } catch (Exception e) {
                    }
                }
            }
        });
    }

    @Override
    public void onBingEvent(final Event event, final RoomState roomState, final BingRule bingRule) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onBingEvent(event, roomState, bingRule);
                    } catch (Exception e) {
                    }
                }
            }
        });
    }

    @Override
    public void onSentEvent(final Event event) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onSentEvent(event);
                    } catch (Exception e) {
                    }
                }
            }
        });
    }

    @Override
    public void onFailedSendingEvent(final Event event) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onFailedSendingEvent(event);
                    } catch (Exception e) {
                    }
                }
            }
        });
    }

    @Override
    public void onBingRulesUpdate() {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onBingRulesUpdate();
                    } catch (Exception e) {
                    }
                }
            }
        });
    }

    @Override
    public void onInitialSyncComplete() {
        mInitialSyncComplete = true;

        refreshUnreadCounters();

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onInitialSyncComplete();
                    } catch (Exception e) {
                    }
                }
            }
        });
    }

    public void onNewRoom(final String roomId) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onNewRoom(roomId);
                    } catch (Exception e) {
                    }
                }
            }
        });
    }

    public void onJoinRoom(final String roomId) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onJoinRoom(roomId);
                    } catch (Exception e) {
                    }
                }
            }
        });
    }

    public void onRoomInitialSyncComplete(final String roomId) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onRoomInitialSyncComplete(roomId);
                    } catch (Exception e) {
                    }
                }
            }
        });
    }

    public void onRoomInternalUpdate(final String roomId) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onRoomInternalUpdate(roomId);
                    } catch (Exception e) {
                    }
                }
            }
        });
    }

    public void onLeaveRoom(final String roomId) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onLeaveRoom(roomId);
                    } catch (Exception e) {
                    }
                }
            }
        });
    }

    public void onReceiptEvent(final String roomId, final List<String> senderIds) {

        // refresh the unread countres at the end of the process chunk
        if (mUpdatedRoomIdList.indexOf(roomId) < 0) {
            mUpdatedRoomIdList.add(roomId);
        }

        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onReceiptEvent(roomId, senderIds);
                    } catch (Exception e) {
                    }
                }
            }
        });
    }

    public void onRoomTagEvent(final String roomId) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onRoomTagEvent(roomId);
                    } catch (Exception e) {
                    }
                }
            }
        });
    }

    public void onRoomSyncWithLimitedTimeline(final String roomId) {
        final List<IMXEventListener> eventListeners = getListenersSnapshot();

        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                for (IMXEventListener listener : eventListeners) {
                    try {
                        listener.onRoomSyncWithLimitedTimeline(roomId);
                    } catch (Exception e) {
                    }
                }
            }
        });
    }
}

/*
 * Copyright 2016 OpenMarket Ltd
 * Copyright 2018 New Vector Ltd
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

package org.matrix.androidsdk.crypto;

import org.matrix.androidsdk.crypto.interfaces.CryptoEvent;
import org.matrix.androidsdk.crypto.model.crypto.RoomKeyRequest;
import org.matrix.androidsdk.crypto.model.crypto.RoomKeyRequestBody;

import java.io.Serializable;

/**
 * IncomingRoomKeyRequest class defines the incoming room keys request.
 */
public class IncomingRoomKeyRequest implements Serializable {
    /**
     * The user id
     */
    public String mUserId;

    /**
     * The device id
     */
    public String mDeviceId;

    /**
     * The request id
     */
    public String mRequestId;

    /**
     * The request body
     */
    public RoomKeyRequestBody mRequestBody;

    /**
     * The runnable to call to accept to share the keys
     */
    public transient Runnable mShare;

    /**
     * The runnable to call to ignore the key share request.
     */
    public transient Runnable mIgnore;

    /**
     * Constructor
     *
     * @param event the event
     */
    public IncomingRoomKeyRequest(CryptoEvent event) {
        mUserId = event.getSender();

        RoomKeyRequest roomKeyRequest = event.toRoomKeyRequest();
        mDeviceId = roomKeyRequest.requesting_device_id;
        mRequestId = roomKeyRequest.request_id;
        mRequestBody = (null != roomKeyRequest.body) ? roomKeyRequest.body : new RoomKeyRequestBody();
    }

    /**
     * Constructor for object creation from crypto store
     */
    public IncomingRoomKeyRequest() {

    }
}

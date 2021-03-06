package io.kickflip.sdk.api.json;

import com.google.api.client.util.Key;

import java.util.Map;

/**
 * JSON API object for Kickflip User
 *
 */
public class User extends Response {

    @Key("app")
    private String mApp;

    @Key("display_name")
    private String mDisplayName;

    @Key("name")
    private String mName;

    @Key("uuid")
    private String mUUID;

    @Key("extra_info")
    private Map mExtraInfo;

    @Key("avatar_url")
    private String mAvatarUrl;

    public User(String app, String name, String uuid, Map extraInfo) {
        mApp = app;
        mName = name;
        mUUID = uuid;
        mExtraInfo = extraInfo;
    }

    public User(){
        // Required Default Constructor
    }

    public String getApp() {
        return mApp;
    }

    public String getName() {
        return mName;
    }

    public String getDisplayName() {
        return mDisplayName;
    }

    public String getUUID() {
        return mUUID;
    }

    public Map getExtraInfo() {
        return mExtraInfo;
    }

    public String getAvatarUrl() {
        return mAvatarUrl;
    }

}

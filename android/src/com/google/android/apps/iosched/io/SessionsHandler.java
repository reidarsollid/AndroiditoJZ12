/*
 * Copyright 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.iosched.io;

import android.content.ContentProviderOperation;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;
import android.text.format.Time;
import com.google.android.apps.iosched.provider.ScheduleContract;
import com.google.android.apps.iosched.provider.ScheduleContract.Sessions;
import com.google.android.apps.iosched.provider.ScheduleContract.SyncColumns;
import com.google.android.apps.iosched.util.Lists;
import com.google.android.apps.iosched.util.ParserUtils;
import com.google.gson.Gson;
import no.java.schedule.R;
import no.java.schedule.io.model.JZLabel;
import no.java.schedule.io.model.JZSessionsResponse;
import no.java.schedule.io.model.JZSessionsResult;
import no.java.schedule.io.model.JZSpeaker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static com.google.android.apps.iosched.provider.ScheduleDatabase.SessionsSpeakers;
import static com.google.android.apps.iosched.provider.ScheduleDatabase.SessionsTracks;
import static com.google.android.apps.iosched.util.LogUtils.LOGI;
import static com.google.android.apps.iosched.util.LogUtils.LOGW;
import static com.google.android.apps.iosched.util.LogUtils.makeLogTag;
import static com.google.android.apps.iosched.util.ParserUtils.sanitizeId;

/**
 * Handler that parses session JSON data into a list of content provider operations.
 */
public class SessionsHandler extends JSONHandler {

    private static final String TAG = makeLogTag(SessionsHandler.class);

    private static final String BASE_SESSION_URL
            = "https://developers.google.com/events/io/sessions/";

    private static final String EVENT_TYPE_KEYNOTE = "keynote";
    private static final String EVENT_TYPE_CODELAB = "codelab";

    private static final int PARSE_FLAG_FORCE_SCHEDULE_REMOVE = 1;
    private static final int PARSE_FLAG_FORCE_SCHEDULE_ADD = 2;

    private static final Time sTime = new Time();

    private static final Pattern sRemoveSpeakerIdPrefixPattern = Pattern.compile(".*//");

    private boolean mLocal;
    private boolean mThrowIfNoAuthToken;


  public SessionsHandler(Context context, boolean local, boolean throwIfNoAuthToken) {
        super(context);
        mLocal = local;
        mThrowIfNoAuthToken = throwIfNoAuthToken;
    }

    public ArrayList<ContentProviderOperation> parse(String json)
            throws IOException {
        final ArrayList<ContentProviderOperation> batch = Lists.newArrayList();

        JZSessionsResponse response = new Gson().fromJson(json, JZSessionsResponse.class);


            LOGI(TAG, "Updating sessions data");

            // by default retain locally starred if local sync
            boolean retainLocallyStarredSessions = true; //mLocal;

            if (response.error != null && response.error.isJsonPrimitive()) {
                String errorMessageLower = response.error.getAsString().toLowerCase();

                if (!mLocal && (errorMessageLower.contains("no profile")
                        || errorMessageLower.contains("no auth token"))) {
                    // There was some authentication issue; retain locally starred sessions.
                    retainLocallyStarredSessions = true;
                    LOGW(TAG, "The user has no developers.google.com profile or this call is "
                            + "not authenticated. Retaining locally starred sessions.");
                }

                if (mThrowIfNoAuthToken && errorMessageLower.contains("no auth token")) {
                    throw new HandlerException.UnauthorizedException("No auth token but we tried "
                            + "authenticating. Need to invalidate the auth token.");
                }
            }

            Set<String> starredSessionIds = new HashSet<String>();
            if (retainLocallyStarredSessions) {
                // Collect the list of current starred sessions
                Cursor starredSessionsCursor = mContext.getContentResolver().query(
                        Sessions.CONTENT_STARRED_URI,
                        new String[]{ScheduleContract.Sessions.SESSION_ID},
                        null, null, null);
                while (starredSessionsCursor.moveToNext()) {
                    starredSessionIds.add(starredSessionsCursor.getString(0));
                }
                starredSessionsCursor.close();
            }

            // Clear out existing sessions
            batch.add(ContentProviderOperation
                    .newDelete(ScheduleContract.addCallerIsSyncAdapterParameter(
                            Sessions.CONTENT_URI))
                    .build());

            // Maintain a list of created block IDs
            Set<String> blockIds = new HashSet<String>();

            for (JZSessionsResult event : response.sessions) {

                    int flags = 0;
                    if (retainLocallyStarredSessions) {
                        flags = (starredSessionIds.contains(event.id)
                                ? PARSE_FLAG_FORCE_SCHEDULE_ADD
                                : PARSE_FLAG_FORCE_SCHEDULE_REMOVE);
                    }

                    String sessionId = event.id;
                    if (TextUtils.isEmpty(sessionId)) {
                        LOGW(TAG, "Found session with empty ID in API response.");
                        continue;
                    }


                    // Session title  - fix special titles
                    String sessionTitle = event.title;
                    //if (EVENT_TYPE_CODELAB.equals(event.event_type)) {
                    //    sessionTitle = mContext.getString(
                    //            R.string.codelab_title_template, sessionTitle);
                    //}

                    // Whether or not it's in the schedule
                    boolean inSchedule = "Y".equals(event.attending);
                    if ((flags & PARSE_FLAG_FORCE_SCHEDULE_ADD) != 0
                            || (flags & PARSE_FLAG_FORCE_SCHEDULE_REMOVE) != 0) {
                        inSchedule = (flags & PARSE_FLAG_FORCE_SCHEDULE_ADD) != 0;
                    }


                    // Special handing of sessiosn that should always be in schedule
                    //if (EVENT_TYPE_KEYNOTE.equals(event.event_type)) {
                    //    // Keynotes are always in your schedule.
                    //    inSchedule = true;
                    //}



                    // Special sorting of sessions based on tracks etc
                    // Re-order session tracks so that Code Lab is last
                    //if (event.labels != null) {
                    //    Arrays.sort(event.track, sTracksComparator);
                    //}

                    // Hashtags
                    String hashtags = "";
                    //if (event.labels != null) {
                    //    StringBuilder hashtagsBuilder = new StringBuilder();
                    //    for (JZLabel trackName : event.labels) {
                    //
                    //        hashtagsBuilder.append(" #");
                    //        hashtagsBuilder.append(
                    //                ScheduleContract.Tracks.generateTrackId(trackName));
                    //    }
                    //    hashtags = hashtagsBuilder.toString().trim();
                    //}

                    // Pre-reqs
                    String prereqs = "";
                    //if (event.prereq != null && event.prereq.length > 0) {
                    //    StringBuilder sb = new StringBuilder();
                    //    for (String prereq : event.prereq) {
                    //        sb.append(prereq);
                    //        sb.append(" ");
                    //    }
                    //    prereqs = sb.toString();
                    //    if (prereqs.startsWith("<br>")) {
                    //        prereqs = prereqs.substring(4);
                    //    }
                    //}

                    String youtubeUrl = null;
                    //if (event.youtube_url != null && event.youtube_url.length > 0) {
                    //    youtubeUrl = event.youtube_url[0];
                    //}

                long sessionStartTime=0;
                long sessionEndTime=0;      //TODO handle sessions without timeslot

                long originalSessionEndTime=1;
                long originalSessionStartTime=1;


                if (event.start!=null && event.end!=null){
                    originalSessionStartTime = event.start.millis();
                    originalSessionEndTime = event.end.millis();

                    sessionStartTime = event.start.millis();//parseTime(event.start_date, event.start_time);
                    sessionEndTime = event.end.millis();//event.end_date, event.end_time);
                }

                if  ("Quickie".equals(event.format)){
                    sessionStartTime=snapStartTime(sessionStartTime);
                    sessionEndTime=snapEndTime(sessionEndTime);


                }

                    // Insert session info
                    final ContentProviderOperation.Builder builder = ContentProviderOperation
                            .newInsert(ScheduleContract
                                    .addCallerIsSyncAdapterParameter(Sessions.CONTENT_URI))
                            .withValue(SyncColumns.UPDATED, System.currentTimeMillis())
                            .withValue(Sessions.SESSION_ID, sessionId)
                            .withValue(Sessions.SESSION_TYPE, event.format)
                            .withValue(Sessions.SESSION_LEVEL, event.level!=null ? event.level.displayName : "")//TODO
                            .withValue(Sessions.SESSION_TITLE, sessionTitle)
                            .withValue(Sessions.SESSION_ABSTRACT, event.bodyHtml)
                            .withValue(Sessions.SESSION_TAGS, event.labelstrings())
                            .withValue(Sessions.SESSION_URL,event.sessionHtmlUrl.toString())
                            .withValue(Sessions.SESSION_LIVESTREAM_URL, "")
                            .withValue(Sessions.SESSION_REQUIREMENTS, prereqs)
                            .withValue(Sessions.SESSION_STARRED, inSchedule)
                            .withValue(Sessions.SESSION_HASHTAGS, hashtags)
                            .withValue(Sessions.SESSION_YOUTUBE_URL, youtubeUrl)
                            .withValue(Sessions.SESSION_PDF_URL, "")
                            .withValue(Sessions.SESSION_NOTES_URL, "")
                            .withValue(Sessions.ROOM_ID, sanitizeId(event.room))
                            .withValue(Sessions.START, originalSessionStartTime)
                            .withValue(Sessions.END, originalSessionEndTime);





                    String blockId = ScheduleContract.Blocks.generateBlockId(
                            sessionStartTime, sessionEndTime);
                    if (!blockIds.contains(blockId)) {
                        String blockType;
                        String blockTitle;
                        if (EVENT_TYPE_KEYNOTE.equals(event.format)) {
                            blockType = ParserUtils.BLOCK_TYPE_KEYNOTE;
                            blockTitle = mContext.getString(R.string.schedule_block_title_keynote);
                        } else if (EVENT_TYPE_CODELAB.equals(event.format)) {
                            blockType = ParserUtils.BLOCK_TYPE_CODE_LAB;
                            blockTitle = mContext
                                    .getString(R.string.schedule_block_title_code_labs);
                        } else {
                            blockType = ParserUtils.BLOCK_TYPE_SESSION;
                            blockTitle = mContext.getString(R.string.schedule_block_title_sessions);
                        }

                        batch.add(ContentProviderOperation
                                .newInsert(ScheduleContract.Blocks.CONTENT_URI)
                                .withValue(ScheduleContract.Blocks.BLOCK_ID, blockId)
                                .withValue(ScheduleContract.Blocks.BLOCK_TYPE, blockType)
                                .withValue(ScheduleContract.Blocks.BLOCK_TITLE, blockTitle)
                                .withValue(ScheduleContract.Blocks.BLOCK_START, sessionStartTime)
                                .withValue(ScheduleContract.Blocks.BLOCK_END, sessionEndTime)
                                .build());
                        blockIds.add(blockId);
                    }

                    builder.withValue(Sessions.BLOCK_ID, blockId);
                    batch.add(builder.build());

                    // Replace all session speakers
                    final Uri sessionSpeakersUri = Sessions.buildSpeakersDirUri(sessionId);
                    batch.add(ContentProviderOperation
                            .newDelete(ScheduleContract
                                    .addCallerIsSyncAdapterParameter(sessionSpeakersUri))
                            .build());
                    if (event.speakers != null) {
                        for (JZSpeaker speaker : event.speakers) {
                            //speaker = sRemoveSpeakerIdPrefixPattern.matcher(speaker).replaceAll("");
                            batch.add(ContentProviderOperation.newInsert(sessionSpeakersUri)
                                    .withValue(SessionsSpeakers.SESSION_ID, sessionId)
                                    .withValue(SessionsSpeakers.SPEAKER_ID, speaker.name).build());//TODO ID for speakers
                        }
                    }

                    // Replace all session tracks
                    final Uri sessionTracksUri = ScheduleContract.addCallerIsSyncAdapterParameter(
                            Sessions.buildTracksDirUri(sessionId));
                    batch.add(ContentProviderOperation.newDelete(sessionTracksUri).build());
                    if (event.labels != null) {
                        for (JZLabel trackName : event.labels) {
                            //if (trackName.contains("Code Lab")) {
                            //    trackName = "Code Labs";
                            //}

                            String trackId = ScheduleContract.Tracks.generateTrackId(trackName.id);
                            batch.add(ContentProviderOperation.newInsert(sessionTracksUri)
                                    .withValue(SessionsTracks.SESSION_ID, sessionId)
                                    .withValue(SessionsTracks.TRACK_ID, trackId).build());
                        }
                    }

            }


        return batch;
    }

  private static long snapStartTime(final long pSessionStartTime) {

      Date date = new Date(pSessionStartTime);
      int minutes = (date.getHours()-9)*60+(date.getMinutes()-0);

      int offset = minutes % (60+20);
      date.setMinutes(date.getMinutes()-offset);
      return date.getTime();


    }

    private static long snapEndTime(final long pSessionEndTime) {

        Date date = new Date(pSessionEndTime);
        int minutes = (date.getHours()-9)*60+(date.getMinutes()+0);

        int offset = minutes % (60+20);
        date.setMinutes(date.getMinutes()+60-offset);
        return date.getTime();


      }



  private Comparator<String> sTracksComparator = new Comparator<String>() {
        @Override
        public int compare(String s1, String s2) {
            // TODO: improve performance of this comparator
            return (s1.contains("Code Lab") ? "z" : s1).compareTo(
                    (s2.contains("Code Lab") ? "z" : s2));
        }
    };

    private String makeSessionUrl(String sessionId) {
        if (TextUtils.isEmpty(sessionId)) {
            return null;
        }

        return BASE_SESSION_URL + sessionId;
    }

    private static long parseTime(String date, String time) {
        //change to this format : 2011-05-10T07:00:00.000-07:00
        int index = time.indexOf(":");
        if (index == 1) {
            time = "0" + time;
        }
        final String composed = String.format("%sT%s:00.000-07:00", date, time);
        //return sTimeFormat.parse(composed).getTime();
        sTime.parse3339(composed);
        return sTime.toMillis(false);
    }
}

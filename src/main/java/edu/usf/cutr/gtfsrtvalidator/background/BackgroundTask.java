/*
 * Copyright (C) 2011 Nipuna Gunathilake.
 * All rights reserved.
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

package edu.usf.cutr.gtfsrtvalidator.background;

import com.google.transit.realtime.GtfsRealtime;
import edu.usf.cutr.gtfsrtvalidator.api.model.GtfsFeedIterationModel;
import edu.usf.cutr.gtfsrtvalidator.api.model.GtfsRtFeedModel;
import edu.usf.cutr.gtfsrtvalidator.db.GTFSDB;
import edu.usf.cutr.gtfsrtvalidator.helper.TimeStampHelper;
import edu.usf.cutr.gtfsrtvalidator.validation.EntityValidation;
import edu.usf.cutr.gtfsrtvalidator.validation.HeaderValidation;
import org.apache.commons.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class BackgroundTask implements Runnable {

    //Entity list kept under the gtfsfeed id.
    //Used to check errors with different feeds for the same transit agency.
    HashMap<Integer, List<TimeFeedEntity>> feedEntityList;

    private GtfsRtFeedModel currentFeed = null;

    public BackgroundTask(GtfsRtFeedModel gtfsRtFeed) {
        //Accept the gtfs feed id and save entities of the same feed in an array
        currentFeed = gtfsRtFeed;
    }

    @Override
    public void run() {
        URL gtfsRtFeedUrl = null;

        try {
            gtfsRtFeedUrl = new URL(currentFeed.getGtfsUrl());
            System.out.println(gtfsRtFeedUrl.toString());
        } catch (Exception e) {
            System.out.println("Malformed Url");
            e.printStackTrace();
        }

        GtfsRealtime.FeedMessage feedMessage = null;
        byte[] gtfsRtProtobuf = null;

        try {
            assert gtfsRtFeedUrl != null;
            InputStream in = gtfsRtFeedUrl.openStream();
            gtfsRtProtobuf = IOUtils.toByteArray(in);
            InputStream is = new ByteArrayInputStream(gtfsRtProtobuf);
            feedMessage = GtfsRealtime.FeedMessage.parseFrom(is);
        } catch (Exception e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }

        //get the header of the feed
        assert feedMessage != null;
        GtfsRealtime.FeedHeader header = feedMessage.getHeader();

        //validation rules for all headers
        HeaderValidation.validate(header);

        List<GtfsRealtime.FeedEntity> entityList = feedMessage.getEntityList();

        EntityValidation entityValidation = new EntityValidation();
        entityValidation.validate(entityList);

        GtfsFeedIterationModel feedIteration = new GtfsFeedIterationModel();
        feedIteration.setFeedprotobuf(gtfsRtProtobuf);
        feedIteration.setTimeStamp(TimeStampHelper.getCurrentTimestamp());
        feedIteration.setRtFeedId(currentFeed.getGtfsRtId());
        GTFSDB.setRtFeedInfo(feedIteration);


        //Save all entities under the gtfs-rt ID
        List<TimeFeedEntity> currentEntityList = feedEntityList.get(currentFeed.getGtfsId());
        if (currentEntityList != null) {
            //Add entities to existing list
            List<TimeFeedEntity> timeFeedEntityList = getTimeFeedEntities(entityList);
            currentEntityList.addAll(timeFeedEntityList);

            //Clean the entities to a given timestamp
            List<TimeFeedEntity> cleanedList = cleanList(currentEntityList);
            feedEntityList.put(currentFeed.getGtfsId(), currentEntityList);

        } else {
            List<TimeFeedEntity> timeFeedEntityList = getTimeFeedEntities(entityList);

            //Create list and add entities
            feedEntityList.put(currentFeed.getGtfsId(), timeFeedEntityList);
        }

        //Loop through all the entities in the feeds check for associating errors
        for (TimeFeedEntity entity : feedEntityList.get(currentFeed.getGtfsId())) {
            entity.getEntity();
            //Run checks that compare entities of same GTFS-feed but from differed rt-feeds
        }

    }

    private List<TimeFeedEntity> getTimeFeedEntities(List<GtfsRealtime.FeedEntity> entityList) {
        long timestamp = TimeStampHelper.getCurrentTimestamp();
        List<TimeFeedEntity> timeFeedEntityList = new ArrayList<>();
        for (GtfsRealtime.FeedEntity entity : entityList) {
            TimeFeedEntity timeEntity = new TimeFeedEntity(entity, timestamp);
            timeFeedEntityList.add(timeEntity);
        }
        return timeFeedEntityList;
    }

    //Check the timestamp differences to avoid comparing older entities
    private List<TimeFeedEntity> cleanList(List<TimeFeedEntity> currentEntityList) {
        List<TimeFeedEntity> cleanedEntityList = new ArrayList<>();
        for (TimeFeedEntity entity : currentEntityList) {
            //Checks if the feed was uploaded within the last 15 seconds
            if (TimeStampHelper.getCurrentTimestamp() - entity.getTimestamp() < 15) {
                cleanedEntityList.add(entity);
            }
        }
        return cleanedEntityList;
    }

    class TimeFeedEntity{
        public TimeFeedEntity(GtfsRealtime.FeedEntity entity, long timestamp) {
            this.entity = entity;
            this.timestamp = timestamp;
        }

        private GtfsRealtime.FeedEntity entity;
        private long timestamp;

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(int timestamp) {
            this.timestamp = timestamp;
        }

        public GtfsRealtime.FeedEntity getEntity() {
            return entity;
        }

        public void setEntity(GtfsRealtime.FeedEntity entity) {
            this.entity = entity;
        }
    }

}